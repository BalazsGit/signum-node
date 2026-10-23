package application.module.logging.context;

import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.selector.ContextSelector;

import java.net.URI;
import java.util.Collections;
import java.util.List;

/**
 * Log4j2 {@link ContextSelector} that serves a single global
 * {@link LoggerContext} for every lookup, whatever the requested context name
 * (per-node isolated LoggerContext logging plan, §6.2, P1).
 * <p>
 * Design premise (D7): the context name is <b>not</b> a routing key. The node
 * contexts are never looked up through this selector — they are created and
 * held by direct object reference (the future {@code NodeLoggingContextManager}).
 * The selector's one job is that every <em>static</em> log4j lookup — the
 * null-name path ({@code LogManager.getContext()}) and the caller-FQCN path
 * taken by the SLF4J binding ({@code log4j-slf4j2-impl}) and the JUL bridge
 * ({@code log4j-jul}) — resolves to the same single global context,
 * {@value #GLOBAL_CONTEXT_NAME}.
 * </p>
 * <p>
 * P0 spike evidence (2026-10-22, commit P0-1, Q1): on log4j 2.26.1 the
 * default factory ({@code ClassLoaderContextSelector}) does <b>not</b>
 * converge the null-name and FQCN lookups, so this selector is mandatory —
 * and a single-context selector provably converges both.
 * </p>
 * <p>
 * The selector holds no thread state and exposes no node paths. The global
 * context is created exactly once, in the constructor, so the instance is
 * immutable and thread-safe.
 * </p>
 */
public final class SignumGlobalContextSelector implements ContextSelector {

    /**
     * Deterministic name of the global context — replaces log4j's default
     * "Default" name so dumps/diagnostics can identify it.
     */
    public static final String GLOBAL_CONTEXT_NAME = "signum-global";

    private final LoggerContext globalContext;

    /**
     * Creates the selector with a global context that has no config URI (log4j
     * default configuration). Used until the generated global config file
     * ({@code conf/logging-runtime/global.log4j2.properties}, §6.4) exists.
     */
    public SignumGlobalContextSelector() {
        this((URI) null);
    }

    /**
     * @param configUri location of the global context configuration (the
     *                  generated global .properties file, §6.4) or {@code null}
     *                  for log4j default configuration. Note: the
     *                  {@code LoggerContext} constructor does <b>not</b> start
     *                  the context (P0 spike Q2a) — this constructor starts it
     *                  explicitly, which applies the configuration.
     */
    public SignumGlobalContextSelector(final URI configUri) {
        final LoggerContext context = configUri == null
                ? new LoggerContext(GLOBAL_CONTEXT_NAME)
                : new LoggerContext(GLOBAL_CONTEXT_NAME, null, configUri);
        context.start();
        this.globalContext = context;
    }

    /**
     * The {@code name} (null or the caller's FQCN) is deliberately ignored (D7).
     */
    @Override
    public LoggerContext getContext(final String name, final ClassLoader classLoader,
                                    final boolean useCurrentContext) {
        return globalContext;
    }

    /**
     * A config URI requested at lookup time is ignored: the global context's
     * configuration is deterministic (constructor, §6.4).
     */
    @Override
    public LoggerContext getContext(final String name, final ClassLoader classLoader,
                                    final boolean useCurrentContext, final URI config) {
        return globalContext;
    }

    @Override
    public List<LoggerContext> getLoggerContexts() {
        return Collections.singletonList(globalContext);
    }

    /**
     * No-op: the global context lives for the JVM lifetime; it is not owned
     * by the factory map and is not disposed through the selector.
     */
    @Override
    public void removeContext(final LoggerContext context) {
        // intentionally empty
    }
}