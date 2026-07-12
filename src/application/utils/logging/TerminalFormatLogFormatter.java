package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFormatter;
import application.utils.logging.event.LogLevel;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * LogFormatter implementation that produces output matching the terminal's {@code BriefLogFormatter} exactly.
 * <p>
 * <h3>Format (Profile Console - no profile tag)</h3>
 * <pre>
 * [INFO] 2026-07-12 13:52:20 application.module.node.Signum - Initializing Signum Node version v3.9.8
 * </pre>
 * <p>
 * <h3>Format (System Console - with profile tag)</h3>
 * <pre>
 * [INFO] 2026-07-12 13:52:20 <hdhdh>: application.module.node.Signum - Initializing...
 * </pre>
 * <p>
 * <h3>Format (Bootstrap/Synthetics)</h3>
 * <pre>
 * [Bootstrap] INFO: message text
 * </pre>
 * <p>
 * <h4>Design Decisions</h4>
 * <ul>
 *   <li>Uses {@link DateTimeFormatter} with explicit locale-independent ISO pattern
 *       to avoid the locale-dependent {@link java.text.SimpleDateFormat} issue that caused
 *       Hungarian dates like "júl. 12, 2026" in the GUI.</li>
 *   <li>Always uses UTC+system default timezone (matching what BriefLogFormatter does via MessageFormat date formatting).</li>
 *   <li>Logger name is always included (matching terminal output).</li>
 *   <li>Throwable stack traces are appended after the main line, matching terminal behavior.</li>
 * </ul>
 * <p>
 * This formatter is thread-safe and stateless (singleton-ready).
 *
 * @see LogFormatter
 * @see LogEvent
 */
public final class TerminalFormatLogFormatter implements LogFormatter {

    /** Singleton instance for efficient reuse. */
    public static final TerminalFormatLogFormatter INSTANCE = new TerminalFormatLogFormatter();

    /** ISO date-time pattern matching BriefLogFormatter: yyyy-MM-dd HH:mm:ss */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Private constructor enforces singleton usage via {@link #INSTANCE}.
     */
    private TerminalFormatLogFormatter() {
    }

    /**
     * Formats the event in terminal-matching style (no profile tag).
     * Used by ProfileConsoleSubscriber for per-profile console output.
     *
     * @param event the log event to format (never null)
     * @return the formatted string (never null)
     */
    @Override
    public String format(LogEvent event) {
        Objects.requireNonNull(event, "LogEvent must not be null");
        return formatLine(event.getLevel(), event.getTimestamp(), event.getLoggerName(),
                event.getMessage(), event.getThrowable(), null);
    }

    /**
     * Formats the event with an optional profile tag prefix.
     * Used by SystemConsoleSubscriber for aggregated console output.
     *
     * @param event     the log event to format (never null)
     * @param profileTag the profile tag to display (may be null for no tag)
     * @return the formatted string (never null)
     */
    public String formatWithProfile(LogEvent event, String profileTag) {
        Objects.requireNonNull(event, "LogEvent must not be null");
        return formatLine(event.getLevel(), event.getTimestamp(), event.getLoggerName(),
                event.getMessage(), event.getThrowable(), profileTag);
    }

    /**
     * Formats a bootstrap-style log line matching the terminal bootstrap output.
     * <p>
     * Example: {@code [Bootstrap] INFO: Logging configuration applied}
     * </p>
     *
     * @param level   the log level
     * @param message the message text
     * @return the formatted bootstrap line
     */
    public static String formatBootstrap(LogLevel level, String message) {
        return "[Bootstrap] " + level.getDisplayName() + ": " + message;
    }

    /**
     * Core formatting logic. Produces:
     * {@code [LEVEL] yyyy-MM-dd HH:mm:ss [PROFILE_TAG] loggerName - message}
     */
    static String formatLine(LogLevel level, long timestamp, String loggerName,
                             String message, Throwable throwable, String profileTag) {

        StringBuilder sb = new StringBuilder(120);

        // [LEVEL]
        sb.append('[').append(level.getDisplayName()).append("] ");

        // yyyy-MM-dd HH:mm:ss
        sb.append(formatTimestamp(timestamp));

        // Optional profile tag: <profileName>:
        if (profileTag != null && !profileTag.isEmpty()) {
            sb.append(" <").append(profileTag).append(">");
        }

        // loggerName -
        if (loggerName != null && !loggerName.isEmpty()) {
            sb.append(':').append(' ').append(loggerName).append(" - ");
        } else {
            sb.append(": ");
        }

        // message
        if (message != null) {
            sb.append(message);
        }

        // Newline
        sb.append('\n');

        // Throwable stack trace
        if (throwable != null) {
            StringWriter writer = new StringWriter();
            throwable.printStackTrace(new PrintWriter(writer));
            sb.append(writer.toString());
        }

        return sb.toString();
    }

    /**
     * Formats a millisecond timestamp to ISO-style date-time string.
     * Uses the system default timezone, matching BriefLogFormatter behavior.
     *
     * @param epochMillis timestamp in milliseconds since Unix epoch
     * @return formatted date-time string like "2026-07-12 13:52:20"
     */
    static String formatTimestamp(long epochMillis) {
        Instant instant = Instant.ofEpochMilli(epochMillis);
        ZoneId zoneId = ZoneId.systemDefault();
        return DATE_FORMATTER.format(instant.atZone(zoneId));
    }

    @Override
    public boolean equals(Object o) {
        // Singleton always equals itself
        return this == o || o instanceof TerminalFormatLogFormatter;
    }

    @Override
    public int hashCode() {
        return TerminalFormatLogFormatter.class.hashCode();
    }
}