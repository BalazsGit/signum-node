package application.module.logging.context;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.properties.PropertiesConfiguration;
import org.apache.logging.log4j.core.config.properties.PropertiesConfigurationFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The ONE source of a node's loggers (per-node isolated LoggerContext plan,
 * §7.1): hands out {@link org.apache.logging.log4j.Logger} instances taken
 * from THIS node's isolated {@link LoggerContext}.
 * <p>
 * Why log4j-Logger directly (not an SLF4J wrapper): before the binding swap
 * (P3.5) the {@code log4j-slf4j2-impl} provider may not enter the classpath
 * (multiple-provider), and the wrapper would demand it. The call surface is
 * identical to SLF4J's ({@code isDebugEnabled()}, {@code debug(String,
 * Object...)}, {@code warn}, {@code error}, ...), so migration (P3) changes
 * only the import and the field initialization — the call sites stay
 * textually unchanged (plan §7.4 audit).
 * </p>
 * <p>
 * Identity: {@code ctx.getLogger(name)} serves the context's own registry —
 * the same name on the SAME node returns the SAME instance; two nodes with
 * the same name get SEPARATE instances, each bound to its own context's
 * configuration (plan A.1/3, 4; P0 spike Q1). The private cache on top is
 * only a fast path, never a second source of truth.
 * </p>
 *
 * <p>Headless-safe (no Swing).</p>
 */
public final class NodeLoggers {

    private final LoggerContext ctx;
    private final ConcurrentMap<String, Logger> cache = new ConcurrentHashMap<>();

    /**
     * @param ctx the node's isolated LoggerContext (never null)
     */
    public NodeLoggers(final LoggerContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx must not be null");
    }

    /**
     * Loggers bound to the JVM-GLOBAL context (null-name lookup, plan A.1/13):
     * the fallback for code that runs before or outside a live node (unit
     * tests without a {@code Signum}, deprecated no-context constructor
     * overloads, static utilities). A live node always passes its own
     * {@code Signum.loggers()} instance instead.
     *
     * @return a facade over the global (non-node) LoggerContext
     */
    public static NodeLoggers global() {
        return new NodeLoggers((org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false));
    }

    /**
     * The node logger of the given class (FQCN as logger name).
     *
     * @param clazz the declaring class of the calling node component
     * @return the log4j logger bound to this node's context (same instance
     *         for the same class, every time)
     */
    public Logger forClass(final Class<?> clazz) {
        return forName(clazz.getName());
    }

    /**
     * The node logger with the given name.
     *
     * @param name the logger name (typically a class FQCN)
     * @return the log4j logger bound to this node's context (same instance
     *         for the same name, every time)
     */
    public Logger forName(final String name) {
        Objects.requireNonNull(name, "name must not be null");
        return cache.computeIfAbsent(name, n -> ctx.getLogger(n));
    }

    /** The node context these loggers are bound to. */
    public LoggerContext context() {
        return ctx;
    }

    /**
     * A guaranteed no-op logger (effective level OFF, no appenders): the P3
     * migration stand-in for SLF4J's {@code NOPLogger} (plan §7.4, the
     * {@code AtMachineProcessor} debug gate). Backed by a dedicated, isolated
     * {@link LoggerContext} configured from {@value #NOOP_RESOURCE} at OFF
     * level, so every {@code isXxxEnabled()} is false and every log call is
     * dropped — independent of any node's or the global context's
     * configuration. Created lazily once, then shared and thread-safe.
     *
     * @return the shared no-op log4j logger
     */
    public Logger noop() {
        return NoopHolder.LOGGER;
    }

    private static final String NOOP_CONTEXT_NAME = "node-noop";

    private static final String NOOP_RESOURCE = "application/module/logging/context/noop.log4j2.properties";

    /** Lazy holder: the no-op context is built on first {@link #noop()} call. */
    private static final class NoopHolder {
        static final Logger LOGGER = createNoopLogger();
    }

    private static Logger createNoopLogger() {
        final LoggerContext ctx = new LoggerContext(NOOP_CONTEXT_NAME);
        // the LoggerContext constructor does NOT start the context (P0 spike Q2a)
        ctx.start();
        final PropertiesConfiguration configuration = new PropertiesConfigurationFactory()
                .getConfiguration(ctx, ConfigurationSource.fromResource(NOOP_RESOURCE,
                        NodeLoggers.class.getClassLoader()));
        ctx.reconfigure(configuration);
        return ctx.getLogger(NOOP_CONTEXT_NAME);
    }
}