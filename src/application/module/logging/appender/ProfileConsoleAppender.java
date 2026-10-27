package application.module.logging.appender;

import application.utils.logging.ProfileLogger;

/**
 * Node-context &rarr; GUI Node Console bridge (per-node isolated LoggerContext
 * plan, §7.3): forwards the events of ONE node's isolated {@code LoggerContext}
 * into that node's {@link ProfileLogger} bus (GUI console tab, replay buffer,
 * {@code forwardToSystem}).
 * <p>
 * The node's isolated {@code LoggerContext} is the SOLE level gate: an event
 * reaches the bus only if the context's configuration emitted it, so the bus
 * does not re-filter by level (plan §7.3/P5 direction).
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
    }
}