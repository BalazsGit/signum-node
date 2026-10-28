package application.module.logging.context;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.properties.PropertiesConfiguration;
import org.apache.logging.log4j.core.config.properties.PropertiesConfigurationFactory;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
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
 *   <li>{@link #remove(Log4jContextFactory)} — dispose: stop the context, then call
 *       {@code factory.removeContext(ctx)} (a defensive no-op for our directly-created
 *       contexts, which are never registered in the factory/selector — the
 *       {@code NodeLoggingContextManager}'s own map is the only registry, §6.2/§14.4-A5)</li>
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
    /** Infrastructure logger (JVM-global): reports degraded-config reconfigure refusals (audit P1.4). */
    private static final Logger LOGGER = LoggerFactory.getLogger(NodeLoggingContext.class);

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
     * <p>
     * <b>Audit P1.4:</b> a corrupt / truncated / hand-broken file must NOT
     * silently degrade a running node to Log4j2's default configuration
     * (which would lose its configured levels and appenders). Before reloading
     * we verify the file is still a well-formed generated config; when it is
     * not, the current configuration is kept, a warning is logged, and
     * {@code false} is returned so the Apply chain does not report success.
     *
     * @return {@code true} when the reconfigure was applied; {@code false} when
     *         the current config file was invalid and the previous
     *         configuration was kept
     */
    public boolean reconfigure() {
        if (!isLoadableNodeConfig(this.configUri)) {
            LOGGER.warn("reconfigure skipped for node '{}': config file {} is not a valid generated Log4j2 config — keeping the current configuration",
                    profileName, this.configUri);
            return false;
        }
        final PropertiesConfiguration configuration = buildValidatedConfiguration(this.configUri);
        if (configuration == null) {
            LOGGER.warn("reconfigure skipped for node '{}': config file {} failed the full Log4j2 parse — keeping the current configuration",
                    profileName, this.configUri);
            return false;
        }
        applyLock.lock();
        try {
            ctx.reconfigure(configuration);
            return true;
        } finally {
            applyLock.unlock();
        }
    }

    /**
     * Reconfigures the SAME context from a DIFFERENT config file — the
     * two-phase start's minimal &rarr; full switch (plan §6.3). 2.26.1 has no
     * {@code reconfigure(URI)}, so the plan's Q2a fallback is used: build a
     * {@code PropertiesConfiguration} from the URI and reconfigure with it.
     * <p>
     * Like {@link #reconfigure()}, a corrupt / unreadable target file is refused
     * (the current configuration is kept, a warning is logged) instead of
     * degrading the node to Log4j2's default (audit P1.4).
     *
     * @param newConfigUri the new .properties file (e.g. the full config
     *                     replacing the minimal startup config)
     * @return {@code true} when the switch was applied; {@code false} when the
     *         target file was invalid and the previous configuration was kept
     */
    public boolean reconfigureFrom(final URI newConfigUri) {
        if (newConfigUri == null) {
            throw new IllegalArgumentException("newConfigUri must not be null");
        }
        if (!isLoadableNodeConfig(newConfigUri)) {
            LOGGER.warn("reconfigureFrom skipped for node '{}': target config file {} is not a valid generated Log4j2 config — keeping the current configuration",
                    profileName, newConfigUri);
            return false;
        }
        final PropertiesConfiguration configuration = buildValidatedConfiguration(newConfigUri);
        if (configuration == null) {
            LOGGER.warn("reconfigureFrom skipped for node '{}': target config file {} failed the full Log4j2 parse — keeping the current configuration",
                    profileName, newConfigUri);
            return false;
        }
        applyLock.lock();
        try {
            ctx.reconfigure(configuration);
            this.configUri = newConfigUri;
            return true;
        } finally {
            applyLock.unlock();
        }
    }

    /**
     * True when the file at {@code uri} is a readable generated node config,
     * i.e. a Log4j2 properties file that sets {@code rootLogger.level}. The
     * generated writer always emits {@code rootLogger.level}; a truncated,
     * corrupted or hand-broken file does not. This is the pre-reload guard that
     * lets {@link #reconfigure()} / {@link #reconfigureFrom(URI)} tell a
     * "reload a valid file" apart from a "this would degrade to Log4j2's
     * default" situation (audit P1.4).
     */
    private static boolean isLoadableNodeConfig(final URI uri) {
        if (uri == null) {
            return false;
        }
        try {
            final Properties props = new Properties();
            try (var in = Files.newInputStream(Paths.get(uri))) {
                props.load(in);
            }
            return props.getProperty("rootLogger.level") != null;
        } catch (final Exception e) {
            return false; // unreadable / non-file / missing marker -> treat as corrupt
        }
    }

    /**
     * Builds a Log4j2 {@link PropertiesConfiguration} from {@code uri} with the REAL
     * Log4j2 properties loader, WITHOUT applying it to the context. This is the audit
     * P1 "full validation" layer: it runs the same loader {@code ctx.reconfigure}
     * would use, so a file the loader cannot turn into a well-formed config (unreadable,
     * truncated, a malformed appender/logger definition, or a missing/invalid root level)
     * is rejected BEFORE it can degrade a running node to Log4j2's default configuration.
     *
     * @return the built configuration when {@code uri} parses to a config whose root
     *         logger carries a level (the generated writer always emits
     *         {@code rootLogger.level}); otherwise {@code null}
     */
    private PropertiesConfiguration buildValidatedConfiguration(final URI uri) {
        if (uri == null) {
            return null;
        }
        try {
            // (1) the real Log4j2 properties loader must turn the file into a config
            final PropertiesConfiguration configuration = new PropertiesConfigurationFactory()
                    .getConfiguration(ctx, ConfigurationSource.fromUri(uri));
            final LoggerConfig root = configuration.getRootLogger();
            if (root == null || root.getLevel() == null) {
                return null; // (2) missing / truncated -> no usable root level
            }
            // (3) Log4j2's loader is tolerant of an unparseable root level value (it
            //     keeps a default instead of failing), so a corrupt / hand-broken level
            //     would otherwise be applied silently. The generated writer always emits
            //     a real level name, so verify the raw value is one (audit P1).
            final Properties props = new Properties();
            try (var in = Files.newInputStream(Paths.get(uri))) {
                props.load(in);
            }
            final String rawLevel = props.getProperty("rootLogger.level");
            if (rawLevel == null || Level.toLevel(rawLevel, null) == null) {
                return null;
            }
            return configuration;
        } catch (final Exception e) {
            return null; // the loader rejected the file -> treat as corrupt
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
     * Disposes: stops the context (if running) and calls
     * {@code factory.removeContext(ctx)}. Node contexts are created directly (D7) and are
     * NEVER registered in the factory/selector, so {@code removeContext} is a no-op here
     * (the {@code NodeLoggingContextManager}'s own map is the registry); the call is kept as
     * a defensive guard in case a future selector starts tracking contexts (plan §6.2/§14.4-A5).
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