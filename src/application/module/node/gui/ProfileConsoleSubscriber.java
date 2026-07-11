package application.module.node.gui;

import java.awt.Color;
import java.io.PrintWriter;
import java.io.StringWriter;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import application.utils.logging.ProfileLogContext;
import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogEventBatcher;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;

/**
 * Log subscriber that routes log events to a Swing JTextPane (StyledDocument)
 * with batched UI updates and level-based color styling.
 * <p>
 * Architecture:
 * <pre>
 *   ProfileLogContext → ProfileConsoleSubscriber → LogEventBatcher → EDT → StyledDocument
 * </pre>
 * <p>
 * <h3>Thread Safety</h3>
 * Events arrive from arbitrary threads via the routing system. The subscriber
 * delegates all StyledDocument mutations to the EDT via {@link LogEventBatcher},
 * which accumulates events and flushes them using time/count thresholds
 * (default: 200ms / 50 events).
 * </p>
 * <p>
 * <h3>Lifecycle</h3>
 * Created per NodeConsolePanel instance. When the panel is disposed,
 * {@link #dispose()} flushes remaining buffered events and stops the batcher.
 * </p>
 *
 * @see ProfileLogContext
 * @see LogEventBatcher
 * @see LogSubscriber
 */
public final class ProfileConsoleSubscriber implements LogSubscriber {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileConsoleSubscriber.class);

    /** Default maximum console line count to prevent unbounded memory growth */
    public static final int DEFAULT_MAX_LINES = 500;

    // ── Color Scheme (level-based styling) ──────────────────────────────

    /** ERROR level text color – bright red */
    public static final Color COLOR_ERROR = new Color(244, 86, 93);

    /** WARN level text color – amber/orange */
    public static final Color COLOR_WARN = new Color(255, 187, 0);

    /** INFO level text color – inherits from UIManager (null = default) */
    public static final Color COLOR_INFO = null;

    /** DEBUG/TRACE level text color – muted grey */
    public static final Color COLOR_DEBUG = new Color(160, 160, 160);

    // ── Fields ──────────────────────────────────────────────────────────

    private final String profileName;
    private final LogEventBatcher batcher;
    private final StyledDocument document;
    private final int maxLines;
    private volatile boolean disposed = false;

    /** Optional filter; when non-null only matching events are processed */
    private final LogFilter filter;

    // ── Constructors ────────────────────────────────────────────────────

    /**
     * Creates a subscriber that appends log events to the given StyledDocument.
     *
     * @param profileName the node profile name (for diagnostics only)
     * @param document    the target StyledDocument (JTextPane), never null
     */
    public ProfileConsoleSubscriber(String profileName, StyledDocument document) {
        this(profileName, document, DEFAULT_MAX_LINES, null);
    }

    /**
     * Creates a subscriber with custom max-line limit and optional filter.
     *
     * @param profileName the node profile name
     * @param document    the target StyledDocument, never null
     * @param maxLines    maximum line count before oldest lines are trimmed
     * @param filter      optional event filter (null = accept all)
     */
    public ProfileConsoleSubscriber(String profileName, StyledDocument document, int maxLines, LogFilter filter) {
        if (document == null) {
            throw new NullPointerException("StyledDocument must not be null");
        }
        if (maxLines <= 0) {
            throw new IllegalArgumentException("maxLines must be positive, got: " + maxLines);
        }

        this.profileName = profileName;
        this.document = document;
        this.maxLines = maxLines;
        this.filter = filter;

        // BatchConsumer runs on EDT; appends events to StyledDocument in bulk
        batcher = new LogEventBatcher(this::appendBatch);
        batcher.start();
    }

    // ── LogSubscriber Implementation ────────────────────────────────────

    @Override
    public void onLogEvent(LogEvent event) {
        if (disposed) {
            return;
        }
        batcher.enqueue(event);
    }

    @Override
    public LogFilter getFilter() {
        return filter;
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        // Flush any remaining buffered events, then stop the timer
        batcher.stop();
    }

    // ── EDT Batch Consumer ──────────────────────────────────────────────

    /**
     * Appends a batch of log events to the StyledDocument.
     * Called on the EDT by {@link LogEventBatcher}.
     */
    @SuppressWarnings("unchecked")
    private void appendBatch(java.util.List<LogEvent> events) {
        if (disposed || events.isEmpty()) {
            return;
        }

        // Ensure all document mutations run on EDT
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> appendBatch(events));
            return;
        }

        try {
            int docLength = document.getLength();

            for (LogEvent event : events) {
                String text = formatLogLine(event);
                if (text == null || text.isEmpty()) {
                    continue;
                }

                SimpleAttributeSet attrs = new SimpleAttributeSet();
                Color levelColor = resolveColor(event.getLevel());
                if (levelColor != null) {
                    StyleConstants.setForeground(attrs, levelColor);
                }

                document.insertString(docLength, text, attrs);
                docLength = document.getLength();
            }

            // Enforce max line count by trimming oldest lines
            enforceMaxLines();

        } catch (BadLocationException e) {
            LOGGER.error("[ProfileConsoleSubscriber:{}] BadLocationException during batch append", profileName, e);
        }
    }

    // ── Log Line Formatting ─────────────────────────────────────────────

    /**
     * Formats a single log event into a display-ready text string.
     * Format: [{LEVEL}] yyyy-MM-dd HH:mm:ss - message (optional stack trace)
     */
    private String formatLogLine(LogEvent event) {
        StringBuilder sb = new StringBuilder(128);

        // [LEVEL]
        sb.append('[').append(event.getLevel().getDisplayName()).append(']');
        sb.append(' ');

        // Timestamp
        sb.append(formatTimestamp(event.getTimestamp()));
        sb.append(" - ");

        // Message
        String msg = event.getMessage();
        if (msg != null) {
            sb.append(msg);
        }

        // Stack trace
        Throwable throwable = event.getThrowable();
        if (throwable != null) {
            sb.append('\n');
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            sb.append(sw.toString());
        }

        sb.append('\n');
        return sb.toString();
    }

    /**
     * Formats a timestamp in milliseconds to "yyyy-MM-dd HH:mm:ss".
     */
    private static String formatTimestamp(long millis) {
        java.util.Date date = new java.util.Date(millis);
        return java.text.SimpleDateFormat.getDateInstance(java.text.SimpleDateFormat.MEDIUM).format(date)
                + " "
                + java.text.SimpleDateFormat.getTimeInstance(java.text.SimpleDateFormat.MEDIUM).format(date);
    }

    // ── Color Resolution ────────────────────────────────────────────────

    private static Color resolveColor(LogLevel level) {
        switch (level) {
            case ERROR: return COLOR_ERROR;
            case WARN:  return COLOR_WARN;
            case DEBUG:
            case TRACE: return COLOR_DEBUG;
            case INFO:
            default:    return COLOR_INFO; // null = use default foreground
        }
    }

    // ── Line Trimming ───────────────────────────────────────────────────

    /**
     * Removes oldest lines when the document exceeds maxLines.
     * This prevents unbounded memory growth during long-running nodes.
     */
    private void enforceMaxLines() throws BadLocationException {
        int docLength = document.getLength();
        if (docLength == 0) {
            return;
        }

        // Count actual lines by scanning newline characters
        int lineCount = countLines(document);
        if (lineCount <= maxLines) {
            return;
        }

        // Calculate lines to remove
        int excessLines = lineCount - maxLines;

        // Remove from the beginning: find the end position of excessLines
        int removeLength = 0;
        int removed = 0;
        int docLen = document.getLength();

        while (removed < excessLines && removeLength < docLen) {
            String ch;
            try {
                ch = document.getText(removeLength, 1);
            } catch (BadLocationException e) {
                break;
            }
            if ("\n".equals(ch)) {
                removed++;
            }
            removeLength++;
        }

        if (removeLength > 0) {
            document.remove(0, removeLength);
        }
    }

    /**
     * Counts lines in the StyledDocument by counting newline characters.
     */
    private static int countLines(StyledDocument doc) throws BadLocationException {
        int count = 1; // At least one line if there's content
        String text = doc.getText(0, doc.getLength());
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    // ── Convenience: Flush on demand ────────────────────────────────────

    /**
     * Forces an immediate flush of all buffered events.
     * Useful before panel disposal or visibility changes.
     */
    public void flush() {
        batcher.flush();
    }

    /** @return the number of events waiting in the batch buffer */
    public int pendingCount() {
        return batcher.pendingCount();
    }

    /** @return true if this subscriber has been disposed */
    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public String toString() {
        return "ProfileConsoleSubscriber{profile='" + profileName + "', disposed=" + disposed + '}';
    }
}