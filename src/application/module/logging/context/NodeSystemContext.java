package application.module.logging.context;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.properties.PropertiesConfiguration;
import org.apache.logging.log4j.core.config.properties.PropertiesConfigurationFactory;

import java.net.URI;

/**
 * The single JVM-wide GLOBAL Log4j2 context ({@code system},
 * per-node isolated LoggerContext plan §6.2).
 * <p>
 * Created exactly once (early, on first {@link #shared()}); unlike the node
 * contexts it lives for the whole JVM lifetime and is never stopped or
 * removed (§11.1: the global context is owned by the JVM, not by any node).
 * The node contexts are created separately by the
 * {@link NodeLoggingContextManager} and never route through this instance.
 * </p>
 * <p>
 * Two-phase configuration (mirrors the node two-phase start, §6.3): it boots
 * WITHOUT a config file (log4j default configuration) and is reconfigured
 * from the generated global file
 * ({@code conf/system/logging/runtime/global.log4j2.properties}, §6.4;
 * SSOT: {@code application.utils.config.LoggingPaths}) on the first
 * global Apply. Until the P3.5 binding swap the global context is a
 * <b>dormant</b> destination (SLF4J/JUL still route to the legacy chain) —
 * managing it now keeps the Apply pipeline complete and testable.
 * </p>
 * <p>Headless-safe (no Swing, no logging — the caller reports errors).</p>
 */
public final class NodeSystemContext {

    private final LoggerContext context;
    private final URI[] configUri = { null };

    private NodeSystemContext(final URI configUri) {
        this.context = configUri == null
                ? new LoggerContext(NodeSystemContextSelector.SYSTEM_CONTEXT_NAME)
                : new LoggerContext(NodeSystemContextSelector.SYSTEM_CONTEXT_NAME, null, configUri);
        // the LoggerContext constructor does NOT start the context (P0 Q2a)
        this.context.start();
        // P3.5: the default (no-file) configuration runs at root ERROR. Raise it to the
        // global default (INFO) so early bootstrap logs (before the first applyGlobal) are
        // not dropped from the System Console. applyGlobal() later sets the authoritative
        // resolved level.
        this.context.getConfiguration().getRootLogger().setLevel(Level.INFO);
        this.configUri[0] = configUri;
    }

    /**
     * The ONE shared global context (early-initialized). Reconfigure calls on
     * it are serialized by the LoggerContext's internal config lock (A.1/3).
     */
    public static NodeSystemContext shared() {
        return SHARED;
    }

    private static final NodeSystemContext SHARED = new NodeSystemContext(null);

    /** Reconfigures from the current config file (the in-place Apply path). */
    public NodeSystemContext reconfigure() {
        context.reconfigure();
        return this;
    }

    /**
     * Switches to (or first applies) a generated global config file. 2.26.1
     * has no {@code reconfigure(URI)}, so the Q2a fallback is used (the same
     * as {@link NodeLoggingContext#reconfigureFrom}).
     *
     * @return this instance
     */
    public NodeSystemContext reconfigureFrom(final URI newConfigUri) {
        if (newConfigUri == null) {
            throw new IllegalArgumentException("newConfigUri must not be null");
        }
        final PropertiesConfiguration configuration = new PropertiesConfigurationFactory()
                .getConfiguration(context, ConfigurationSource.fromUri(newConfigUri));
        context.reconfigure(configuration);
        configUri[0] = newConfigUri;
        return this;
    }

    /** @return the underlying Log4j2 context (never null, always started) */
    public LoggerContext loggerContext() {
        return context;
    }

    /** @return the current config file, or null before the first global Apply */
    public URI configUri() {
        return configUri[0];
    }
}