package application.module.node.gui;

import java.awt.Color;
import java.util.List;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import application.utils.logging.ConsoleColorScheme;
import application.utils.logging.TerminalFormatLogFormatter;
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
 *   SystemLogger ─→ SystemConsoleSubscriber ─→ LogEventBatcher ─→ EDT ─→ StyledDocument
 *   ProfileLogger("node.mainnet") ──┐
 *   ProfileLogger("node.testnet")  ──┤──→ (forwardToSystem)
 *   ProfileLogger("database.x")    ──┘
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
 * <p>
 * <h3>Log Format</h3>
 * Uses {@link TerminalFormatLogFormatter} to produce terminal-matching output with profile tag:
 * <pre>
 *   [INFO] 2026-07-12 13:52:20 <hdhdh>: application.module.node.Signum - Initializing...
 * </pre>
 * </p>
 *
 * @see ConsoleColorScheme
 * @see ProfileConsoleSubscriber
 * @see LogEventBatcher
 * @see TerminalFormatLogFormatter
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

    /**
     * Optional JScrollPane reference for smart auto-scroll.
     * When set, the console will only auto-scroll to the bottom if the user
     * is already viewing near the bottom (>80% of scroll range), similar to VS Code terminal behavior.
     */
    private volatile JScrollPane scrollPane;

    /** Threshold (0.0-1.0): only auto-scroll when scrollbar is at or above this percentage of max position */
    private static final double SMART_SCROLL_THRESHOLD = 0.8;

    /** Terminal-format log formatter (singleton, shared across all instances) */
    private final TerminalFormatLogFormatter formatter = TerminalFormatLogFormatter.INSTANCE;

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

    /**
     * Sets the parent JScrollPane to enable smart auto-scroll behavior.
     * When a scrollPane is provided, the console will only auto-scroll to the bottom
     * when the user is already viewing near the bottom of the content (above 80% threshold).
     * This prevents unwanted scroll-jumping when the user reads older logs.
     *
     * @param pane the JScrollPane that contains the console text component (null to disable smart scroll)
     */
    public void setScrollPane(JScrollPane pane) {
        this.scrollPane = pane;
    }

    /** @return the current JScrollPane reference for smart auto-scroll, or null if disabled */
    public JScrollPane getScrollPane() {
        return scrollPane;
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

            // Smart auto-scroll: only scroll to bottom if user is already near the bottom
            maybeScrollToEnd();

        } catch (BadLocationException e) {
            LOGGER.error("[SystemConsoleSubscriber] BadLocationException during batch append", e);
        }
    }

    // ── Smart Auto-Scroll ────────────────────────────────────────────────

    /**
     * Scrolls the console to the end only if the user is already viewing near the bottom.
     * Similar to VS Code terminal: new content auto-scrolls when the user is at the bottom,
     * but does not jump when the user has scrolled up to read older content.
     */
    private void maybeScrollToEnd() {
        JScrollPane pane = scrollPane;
        if (pane == null) {
            return;
        }

        javax.swing.JScrollBar verticalBar = pane.getVerticalScrollBar();
        if (verticalBar == null) {
            return;
        }

        int max = verticalBar.getMaximum();
        int extent = verticalBar.getVisibleAmount();
        int current = verticalBar.getValue();

        // Calculate the effective scrollable range
        int scrollableRange = max - extent;
        if (scrollableRange <= 0) {
            // Content fits entirely in viewport – nothing to scroll
            return;
        }

        // Check if user is viewing at or below the threshold (near bottom)
        double positionRatio = (double) current / scrollableRange;
        if (positionRatio >= SMART_SCROLL_THRESHOLD) {
            // User is near bottom: scroll to end
            verticalBar.setValue(max);
        }
        // Otherwise: user scrolled up, do not disturb their view position
    }

    // ── Log Line Formatting (Aggregated View) ───────────────────────────

    /**
     * Formats a log event for the aggregated system console.
     * Uses {@link TerminalFormatLogFormatter} with profile tag to match terminal output:
     * <pre>
     *   [LEVEL] yyyy-MM-dd HH:mm:ss <profile>: loggerName - message
     * </pre>
     * For events without a profile, uses "<system>" as the tag.
     */
    private String formatAggregatedLine(LogEvent event) {
        String profileTag = event.getProfileName();
        if (profileTag == null || profileTag.isEmpty()) {
            profileTag = "system";
        }
        return formatter.formatWithProfile(event, profileTag);
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