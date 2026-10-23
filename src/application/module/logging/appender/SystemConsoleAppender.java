package application.module.logging.appender;

import application.utils.logging.SystemLogger;

/**
 * Global-context &rarr; System Console bridge (per-node isolated LoggerContext
 * plan, §7.3): forwards the events of the GLOBAL {@code LoggerContext} into
 * the singleton {@link SystemLogger} — preserving today's SystemLogger
 * function for all non-node (GUI, utils, external) loggers once the log4j
 * binding is live (P3.5).
 * <p>
 * The {@code SystemLogger} always captures TRACE+ (its {@code setLogLevel}
 * is pinned), so no bus-level adjustment is needed here.
 * </p>
 *
 * <p>Headless-safe (no Swing).</p>
 */
public final class SystemConsoleAppender extends ConsoleBusAppender {

    /** Appender name on the global context's root logger (re-attach dedup key). */
    public static final String NAME = "systemConsole";

    /** Creates the appender bound to the current singleton SystemLogger. */
    public SystemConsoleAppender() {
        super(NAME, SystemLogger.getInstance());
    }

    /**
     * @param systemLogger the destination bus (tests may inject a dedicated
     *                     instance instead of the singleton)
     */
    public SystemConsoleAppender(final SystemLogger systemLogger) {
        super(NAME, systemLogger);
    }
}