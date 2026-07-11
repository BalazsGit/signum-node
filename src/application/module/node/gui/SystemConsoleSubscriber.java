package application.module.node.gui;

import java.awt.Color;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import application.utils.logging.ConsoleColorScheme;
import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogEventBatcher;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;

/**
 * Aggregating log subscriber that collects events from ALL running profiles
 * and displays them in a single shared console with profile-based color coding.
 * <p>
 * Architecture:
 * <pre>
 *   ProfileLogContext("mainnet") ──┐
 *   ProfileLogContext("testnet") ──┼──→ SystemConsoleSubscriber → LogEventBatcher → EDT → StyledDocument
 *   ProfileLogContext("devnet")  ──┘
 * </pre>
 * <p>
 * <h3>Key Differences from {@link ProfileConsoleSubscriber}</h3>
 * <ul>
 *   <li><b>Multi-profile:</b> Receives events from all registered profiles, not just one.</li>
 *   <li><b>Profile tag:</b> Each line is prefixed with a colored profile identifier.</li>
 *   <li><b>Dynamic filter:</b> Supports runtime filter changes via {@link #setFilter(LogFilter)}.</li>
 *   <li><b>Color scheme:</b> Uses {@link ConsoleColorScheme} for per-profile color coding.</li>
 * </ul>
 * </p>
 * <p>
 * <h3>Thread Safety</h3>
 * Events arrive from arbitrary threads. All StyledDocument mutations are dispatched
 * to the EDT via {@link LogEventBatcher} with time/count thresholds (default: 200ms / 50 events).
 * </p>
 * <p>
 * <h3>Lifecycle</h3>
 * Created once for the global SystemConsole panel. When disposed,
 * {@link #dispose()} flushes remaining buffered events and stops the batcher.
 * </p>
 *
 * @see ConsoleColorScheme
 * @see ProfileConsoleSubscriber
 * @see LogEventBatcher
 */
public final class SystemConsoleSubscriber implements LogSubscriber {

    private static final Logger LOGGER = LoggerFactory.getLogger(SystemConsoleSubscriber.class);

    /** Default maximum console line count to prevent unbounded memory growth */
    public static final int DEFAULT_MAX_LINES = 1000;

    // ── Level-based color scheme (same base as ProfileConsoleSubscriber) ──

    private static final Color COLOR_ERROR = new Color(244, 86, 93);
    private static final Color COLOR_WARN  = new Color(255, 187, 0);
    private static final Color COLOR_INFO  = null;
    private static final Color COLOR_DEBUG = new Color(160, 160, 160);

    // ── Fields ───────────────────────────────────────────────────────────

    private final ConsoleColorScheme colorScheme;
    private final LogEventBatcher batcher;
    private final StyledDocument document;
    private final int maxLines;
    private volatile boolean disposed = false;

    /** Dynamic filter; volatile for safe concurrent reads without synchronization */
    private volatile LogFilter filter;

    // ── Constructors ─────────────────────────────────────────────────────

    /**
     * Creates a subscriber using the default ConsoleColorScheme.
     *
     * @param document the target StyledDocument (never null)
     */
    public SystemConsoleSubscriber(StyledDocument document) {
        this(document, DEFAULT_MAX_LINES, null);
    }

    /**
     * Creates a subscriber with custom settings.
     *
     * @param document    the target StyledDocument (never null)
     * @param maxLines    maximum line count before trimming oldest lines
     * @param filter      optional initial event filter (null = accept all)
     */
    public SystemConsoleSubscriber(StyledDocument document, int maxLines, LogFilter filter) {
        this(ConsoleColorScheme.getDefault(), document, maxLines, filter);
    }

    /**
     * Creates a subscriber with a custom ConsoleColorScheme.
     *
     * @param colorScheme the color scheme for profile coloring (never null)
     * @param document    the target StyledDocument (never null)
     * @param maxLines    maximum line count before trimming
     * @param filter      optional initial event filter (null = accept all)
     */
    public SystemConsoleSubscriber(ConsoleColorScheme colorScheme, StyledDocument document,
                                   int maxLines, LogFilter filter) {
        if (colorScheme == null) {
            throw new NullPointerException("ConsoleColorScheme must not be null");
        }
        if (document == null) {
            throw new NullPointerException("StyledDocument must not be null");
        }
        if (maxLines <= 0) {
            throw new IllegalArgumentException("maxLines must be positive, got: " + maxLines);
        }

        this.colorScheme = colorScheme;
        this.document = document;
        this.maxLines = maxLines;
        this.filter = filter;

        batcher = new LogEventBatcher(this::appendBatch);
        batcher.start();
    }

    // ── LogSubscriber Implementation ────────────────────────────────────

    @Override
    public void onLogEvent(LogEvent event) {
        if (disposed) {
            return;
        }
        // Apply dynamic filter check before enqueueing
        if (filter != null && !filter.matches(event)) {
            return;
        }
        batcher.enqueue(event);
    }

    @Override
    public LogFilter getFilter() {
        return filter;
    }

    /**
     * Updates the active filter at runtime.
     * New events will be evaluated against the new filter immediately.
     * Events already in the batch buffer are not re-evaluated.
     *
     * @param newFilter the new filter to apply (null = accept all)
     */
    public void setFilter(LogFilter newFilter) {
        this.filter = newFilter;
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        batcher.stop();
    }

    // ── EDT Batch Consumer ──────────────────────────────────────────────

    /**
     * Appends a batch of log events to the StyledDocument with profile-based colors.
     * Called on the EDT by {@link LogEventBatcher}.
     */
    @SuppressWarnings("unchecked")
    private void appendBatch(List<LogEvent> events) {
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
                String text = formatAggregatedLine(event);
                if (text == null || text.isEmpty()) {
                    continue;
                }

                SimpleAttributeSet attrs = new SimpleAttributeSet();

                // Composite color: blend profile color with level color
                Color profileColor = colorScheme.resolveEventColor(event.getProfileName());
                Color levelColor = resolveLevelColor(event.getLevel());
                Color blended = blendColors(profileColor, levelColor);

                if (blended != null) {
                    StyleConstants.setForeground(attrs, blended);
                }

                document.insertString(docLength, text, attrs);
                docLength = document.getLength();
            }

            enforceMaxLines();

        } catch (BadLocationException e) {
            LOGGER.error("[SystemConsoleSubscriber] BadLocationException during batch append", e);
        }
    }

    // ── Log Line Formatting (Aggregated View) ───────────────────────────

    /**
     * Formats a log event for the aggregated system console.
     * Includes profile tag: [{LEVEL}] {PROFILE}: message
     */
    private String formatAggregatedLine(LogEvent event) {
        StringBuilder sb = new StringBuilder(160);

        // [LEVEL]
        sb.append('[').append(event.getLevel().getDisplayName()).append(']');
        sb.append(' ');

        // Timestamp
        sb.append(formatTimestamp(event.getTimestamp()));
        sb.append(' ');

        // Profile tag (colored in practice via the attrs)
        String profile = event.getProfileName();
        if (profile != null && !profile.isEmpty()) {
            sb.append("<").append(profile).append(">: ");
        } else {
            sb.append("<system>: ");
        }

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
     * Formats a timestamp: "yyyy-MM-dd HH:mm:ss".
     */
    private static String formatTimestamp(long millis) {
        java.util.Date date = new java.util.Date(millis);
        return java.text.SimpleDateFormat.getDateInstance(java.text.SimpleDateFormat.MEDIUM).format(date)
                + " "
                + java.text.SimpleDateFormat.getTimeInstance(java.text.SimpleDateFormat.MEDIUM).format(date);
    }

    // ── Color Resolution & Blending ─────────────────────────────────────

    /**
     * Resolves the base color for a given log level.
     */
    private static Color resolveLevelColor(LogLevel level) {
        switch (level) {
            case ERROR: return COLOR_ERROR;
            case WARN:  return COLOR_WARN;
            case DEBUG:
            case TRACE: return COLOR_DEBUG;
            case INFO:
            default:    return COLOR_INFO; // null = use profile color only
        }
    }

    /**
     * Blends a profile color with a level color.
     * If levelColor is null (INFO/default), returns the profile color unchanged.
     * Otherwise, averages the RGB components for a composite hint.
     */
    private static Color blendColors(Color profileColor, Color levelColor) {
        if (levelColor == null) {
            return profileColor;
        }
        if (profileColor == null) {
            return levelColor;
        }

        int r = (profileColor.getRed() + levelColor.getRed()) >> 1;
        int g = (profileColor.getGreen() + levelColor.getGreen()) >> 1;
        int b = (profileColor.getBlue() + levelColor.getBlue()) >> 1;
        return new Color(r, g, b);
    }

    // ── Line Trimming ───────────────────────────────────────────────────

    /**
     * Removes oldest lines when the document exceeds maxLines.
     */
    private void enforceMaxLines() throws BadLocationException {
        int docLength = document.getLength();
        if (docLength == 0) {
            return;
        }

        int lineCount = countLines(document);
        if (lineCount <= maxLines) {
            return;
        }

        int excessLines = lineCount - maxLines;
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
     * Counts lines by counting newline characters.
     */
    private static int countLines(StyledDocument doc) throws BadLocationException {
        int count = 1;
        String text = doc.getText(0, doc.getLength());
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    // ── Convenience Methods ─────────────────────────────────────────────

    /** Forces an immediate flush of all buffered events. */
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

    /** @return the ConsoleColorScheme used by this subscriber */
    public ConsoleColorScheme getColorScheme() {
        return colorScheme;
    }

    @Override
    public String toString() {
        return "SystemConsoleSubscriber{disposed=" + disposed + ", filter=" + filter + '}';
    }
}