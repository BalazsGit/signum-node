package application.utils.logging.event;

/**
 * Strategy interface for formatting {@link LogEvent} instances into human-readable strings.
 * <p>
 * Implementations define different output styles (plain text, colored ANSI, HTML-styled, etc.)
 * and can be swapped at runtime to change the visual presentation without modifying
 * the underlying log routing logic.
 * </p>
 * <p>
 * <h3>Usage</h3>
 * <pre>{@code
 *   LogFormatter formatter = new PlainTextLogFormatter();
 *   String output = formatter.format(event);
 * }</pre>
 * </p>
 *
 * @see LogEvent
 */
public interface LogFormatter {

    /**
     * Formats the given log event into a display-ready string.
     * <p>
     * The returned string may include styling information (HTML tags, ANSI codes, etc.)
     * depending on the formatter implementation.
     * </p>
     *
     * @param event the log event to format (never null)
     * @return the formatted string representation (never null)
     */
    String format(LogEvent event);
}