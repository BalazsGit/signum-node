package application.module.logging.appender;

import application.utils.logging.ProfileLogger;
import application.utils.logging.event.LogLevel;

/**
 * Node-context &rarr; GUI Node Console bridge (per-node isolated LoggerContext
 * plan, §7.3): forwards the events of ONE node's isolated {@code LoggerContext}
 * into that node's {@link ProfileLogger} bus (GUI console tab, replay buffer,
 * {@code forwardToSystem}).
 * <p>
 * The appender raises the bus's level to TRACE on construction: with the
 * node context as the SOLE level gate (the event only arrives when the
 * context's configuration emitted it), the bus's {@code minLevel} second gate
 * must not re-filter (the old JUL chain's events are already level-filtered
 * upstream by JUL before reaching the same bus — plan §7.3/P5 direction).
 * </p>
 *
 * <p>Headless-safe (no Swing).</p>
 */
public final class ProfileConsoleAppender extends ConsoleBusAppender {

    /** Appender name on the node context's root logger (re-attach dedup key). */
    public static final String NAME = "profileConsole";

    /**
     * @param profileLogger the node's ProfileLogger bus (never null)
     */
    public ProfileConsoleAppender(final ProfileLogger profileLogger) {
        super(NAME, profileLogger);
        profileLogger.setLogLevel(LogLevel.TRACE); // the context is the sole level gate
    }
}