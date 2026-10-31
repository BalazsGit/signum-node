package application.module.logging.appender;

import application.utils.logging.ModuleLogger;
import application.utils.logging.event.LogLevel;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import java.util.Objects;

/**
 * Shared base of the programmatic GUI-bridge appenders (per-node isolated
 * LoggerContext plan, §7.3): forwards the events of ONE Log4j2 context into
 * the application log bus ({@link ModuleLogger} — a {@code ProfileLogger} or
 * the {@code SystemLogger}) so the GUI consoles see them.
 * <p>
 * <b>Programmatic, no plugin registration:</b> the appender is attached
 * directly to the context's root logger config (P0/A.2/Q4). The generated
 * config file only ever declares the built-in Console/RollingFile appenders,
 * so a reconfigure REPLACES the configuration and silently drops this
 * appender's reference — but does NOT stop the instance (P0 spike fact).
 * {@link #attach(LoggerContext)} therefore must be called again after every
 * reconfigure/restart, and is idempotent (name-based deduplication on the
 * root logger → no double delivery).
 * </p>
 * <p>
 * The appender does NO level filtering: the context's configuration is the
 * single level gate (the event only arrives when the context emitted it).
 * It relays the event as a STRUCTURED bus {@code LogEvent} (original logger
 * name / thread / timestamp / raw message / throwable) — it does NOT pre-format:
 * the console/file formatter is the single renderer (P8/F2).
 * </p>
 */
public abstract class ConsoleBusAppender extends AbstractAppender {

    /** The bus this context's events are forwarded to (never null). */
    protected final ModuleLogger target;

    /**
     * Self-managed started state: the 2.26.1 {@code AbstractAppender} has no
     * protected append hook — subclasses implement the {@code Appender}'s
     * public {@code append(LogEvent)} plus the {@code LifeCycle} methods
     * themselves (as {@code AbstractOutputStreamAppender} does).
     */
    private volatile boolean started = false;

    /**
     * @param name   the appender name (unique on the root logger; dedup key
     *               for re-attachment)
     * @param target the destination bus ({@code ProfileLogger} /
     *               {@code SystemLogger})
     */
    protected ConsoleBusAppender(final String name, final ModuleLogger target) {
        super(name, null, null, true, null);
        this.target = Objects.requireNonNull(target, "target must not be null");
    }

    /** The bus this appender forwards to. */
    public ModuleLogger target() {
        return target;
    }

    /**
     * Attaches this appender to the context's root logger (idempotent). Call
     * this after EVERY reconfigure or (re)start of the context — the
     * configuration swap drops the reference but leaves this instance
     * running (P0 spike fact); the name-based check makes double-attachment
     * a no-op.
     *
     * @param ctx the node (or global) context to attach to
     */
    public synchronized void attach(final LoggerContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        final Configuration configuration = ctx.getConfiguration();
        if (configuration == null) {
            return;
        }
        final LoggerConfig root = configuration.getRootLogger();
        if (root.getAppenders().containsKey(getName())) {
            return; // already attached — no double delivery
        }
        if (!isStarted()) {
            start();
        }
        root.addAppender(this, null, null);
    }

    @Override
    public void append(final LogEvent event) {
        if (!started || event == null) {
            return;
        }
        // The context's configuration is the SOLE (native) level gate: the event
        // only reached here because the context emitted it at the profile's
        // configured level, so relay it via forward() — NOT log() — to avoid a
        // second, non-native minLevel re-filter (plan §7.3/P5.1).
        // P8/F2: relay a STRUCTURED bus event (original fields, no pre-rendering) so the
        // console formatter is the single renderer (no double level/timestamp).
        target.forward(toBusEvent(event));
    }

    @Override
    public synchronized void start() {
        started = true;
    }

    @Override
    public synchronized void stop() {
        started = false;
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    /**
     * Maps a Log4j2 level to the bus level; FATAL collapses to ERROR (the
     * bus has no FATAL).
     */
    static LogLevel toBusLevel(final org.apache.logging.log4j.Level level) {
        if (level == null) {
            return LogLevel.INFO;
        }
        switch (level.name()) {
            case "TRACE":
                return LogLevel.TRACE;
            case "DEBUG":
                return LogLevel.DEBUG;
            case "INFO":
                return LogLevel.INFO;
            case "WARN":
                return LogLevel.WARN;
            case "FATAL":
            case "ERROR":
                return LogLevel.ERROR;
            case "OFF":
                return LogLevel.OFF;
            default:
                return LogLevel.INFO;
        }
    }

    /**
     * Builds the structured bus {@link application.utils.logging.event.LogEvent} from the
     * ORIGINAL Log4j2 fields (P8/F2): raw message, original logger name / thread /
     * timestamp / throwable. The console formatter ({@code TerminalFormatLogFormatter}) is
     * the single renderer — no pre-formatting here (that produced the double level/timestamp).
     */
    static application.utils.logging.event.LogEvent toBusEvent(final LogEvent event) {
        // 2.26.1: the trailing-throwable varargs call (error("x", t)) lands on the
        // EVENT (LogEvent.getThrown); a ThrowableMessage carries it on the MESSAGE
        final Throwable thrown = event.getThrown() != null
                ? event.getThrown()
                : event.getMessage().getThrowable();
        final application.utils.logging.event.LogEvent.Builder b =
                new application.utils.logging.event.LogEvent.Builder()
                        .timestamp(event.getTimeMillis())
                        .level(toBusLevel(event.getLevel()))
                        .loggerName(event.getLoggerName())
                        .message(event.getMessage().getFormattedMessage())
                        .threadName(event.getThreadName());
        if (thrown != null) {
            b.throwable(thrown);
        }
        return b.build();
    }
}