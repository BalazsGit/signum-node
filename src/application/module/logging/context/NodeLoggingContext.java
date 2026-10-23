package application.module.logging.context;

import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.properties.PropertiesConfiguration;
import org.apache.logging.log4j.core.config.properties.PropertiesConfigurationFactory;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;

import java.net.URI;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * The full lifecycle of ONE node's isolated Log4j2 {@link LoggerContext}
 * (per-node isolated LoggerContext plan, §6.3).
 * <p>
 * The context is created <b>directly</b> (D7) — never looked up through the
 * factory/selector: {@code new LoggerContext(name, null, configUri)} plus an
 * explicit {@code start()} (the constructor does not start, P0 spike Q2a).
 * Its name is deterministic: {@code signum-node-<sanitized profile>}.
 * </p>
 *
 * <h3>Lifecycle (P0 spike Q6: stop -> start -> reconfigure is clean)</h3>
 * <ul>
 *   <li>{@link #start()} — node (re)start: opens the appenders of the config</li>
 *   <li>{@link #stop()} — node stop: appenders close, the context object stays</li>
 *   <li>{@link #reconfigure(Path)} — Apply: reload the (regenerated) config
 *       file into the SAME context; existing loggers rebind (spike Q2b)</li>
 *   <li>{@link #remove(Log4jContextFactory)} — dispose: stop +
 *       {@code factory.removeContext} so the factory's context map stays
 *       consistent (not a routing concern, §6.2)</li>
 * </ul>
 *
 * <p>
 * Every mutating call takes the private {@link #applyLock}, so a concurrent
 * Apply and stop can never interleave a resolve-write-reconfigure sequence.
 * </p>
 *
 * <p>Headless-safe (no Swing).</p>
 */
public final class NodeLoggingContext {

    /** Context name prefix: {@code signum-node-<sanitized profile>} (plan §6.1). */
    public static final String NAME_PREFIX = "signum-node-";
    private static final Pattern UNSAFE_NAME_CHARS = Pattern.compile("[^A-Za-z0-9_.\\-]");

    private final String profileName;
    private final LoggerContext ctx;
    private final ReentrantLock applyLock = new ReentrantLock();
    private volatile URI configUri;

    /**
     * @param profileName the node profile name
     * @param configUri   the generated .properties file of this context, or
     *                    null for the log4j default configuration (the
     *                    two-phase start's minimal phase, plan §6.3)
     */
    public NodeLoggingContext(final String profileName, final URI configUri) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("profileName must not be null or blank");
        }
        this.profileName = profileName;
        this.ctx = configUri == null
                ? new LoggerContext(contextNameFor(profileName))
                : new LoggerContext(contextNameFor(profileName), null, configUri);
        this.configUri = configUri;
    }

    /**
     * Deterministic context name: {@code signum-node-} + the profile name with
     * every character outside {@code [A-Za-z0-9_.-]} replaced by {@code _}.
     */
    public static String contextNameFor(String profileName) {
        return NAME_PREFIX + UNSAFE_NAME_CHARS.matcher(profileName).replaceAll("_");
    }

    /** Starts the context (idempotent); opens the config's appenders. */
    public NodeLoggingContext start() {
        applyLock.lock();
        try {
            if (!ctx.isStarted()) {
                ctx.start();
            }
            return this;
        } finally {
            applyLock.unlock();
        }
    }

    /**
     * Reconfigures the SAME context from its current config file (the Apply
     * path, §9.2A): the caller rewrites the generated file in place first
     * (the writer does this atomically), then this reloads it. Existing
     * logger instances rebind to the new configuration (spike Q2b).
     */
    public NodeLoggingContext reconfigure() {
        applyLock.lock();
        try {
            ctx.reconfigure();
            return this;
        } finally {
            applyLock.unlock();
        }
    }

    /**
     * Reconfigures the SAME context from a DIFFERENT config file — the
     * two-phase start's minimal &rarr; full switch (plan §6.3). 2.26.1 has no
     * {@code reconfigure(URI)}, so the plan's Q2a fallback is used: build a
     * {@code PropertiesConfiguration} from the URI and reconfigure with it.
     *
     * @param newConfigUri the new .properties file (e.g. the full config
     *                     replacing the minimal startup config)
     */
    public NodeLoggingContext reconfigureFrom(final URI newConfigUri) {
        if (newConfigUri == null) {
            throw new IllegalArgumentException("newConfigUri must not be null");
        }
        applyLock.lock();
        try {
            final PropertiesConfiguration configuration = new PropertiesConfigurationFactory()
                    .getConfiguration(ctx, ConfigurationSource.fromUri(newConfigUri));
            ctx.reconfigure(configuration);
            this.configUri = newConfigUri;
            return this;
        } finally {
            applyLock.unlock();
        }
    }

    /** Stops the context: appenders close, the object stays (restart support). */
    public void stop() {
        applyLock.lock();
        try {
            ctx.stop();
        } finally {
            applyLock.unlock();
        }
    }

    /**
     * Disposes: stops (if running) and removes the context from the factory's
     * map so {@code getLoggerContexts()} stays consistent (plan §6.2/§6.3).
     */
    public void remove(final Log4jContextFactory factory) {
        applyLock.lock();
        try {
            ctx.stop();
            if (factory != null) {
                factory.removeContext(ctx);
            }
        } finally {
            applyLock.unlock();
        }
    }

    public String profileName() {
        return profileName;
    }

    public LoggerContext loggerContext() {
        return ctx;
    }

    public boolean isStarted() {
        return ctx.isStarted();
    }

    /** The config file this context currently runs from (null = default config). */
    public URI configUri() {
        return configUri;
    }

    @Override
    public String toString() {
        return "NodeLoggingContext{profile='" + profileName + "', context='" + ctx.getName() + "'}";
    }
}