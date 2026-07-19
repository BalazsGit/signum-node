package application.module.node.gui;

import java.awt.Color;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.text.StyledDocument;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.gui.BaseConsoleSubscriber;

/**
 * Log subscriber that routes log events to a Swing JTextPane (StyledDocument)
 * with batched UI updates and level-based color styling.
 * <p>
 * Architecture:
 * <pre>
 *   ProfileLogger("node.mainnet") → ProfileConsoleSubscriber → LogEventBatcher → EDT → StyledDocument
 * </pre>
 * <p>
 * <h3>Thread Safety</h3>
 * Events arrive from arbitrary threads via the routing system. The subscriber
 * delegates all StyledDocument mutations to the EDT via {@link application.utils.logging.event.LogEventBatcher},
 * which accumulates events and flushes them using time/count thresholds.
 * </p>
 * <p>
 * <h3>Lifecycle</h3>
 * Created per NodeConsolePanel instance. When the panel is disposed,
 * {@link BaseConsoleSubscriber#dispose()} flushes remaining buffered events and stops the batcher.
 * </p>
 * <p>
 * <h3>Log Format</h3>
 * Uses {@link TerminalFormatLogFormatter} to produce terminal-matching output:
 * <pre>
 *   [INFO] 2026-07-12 13:52:20 application.module.node.Signum - Initializing...
 * </pre>
 * </p>
 *
 * @see BaseConsoleSubscriber
 * @see SystemConsoleSubscriber
 */
public final class ProfileConsoleSubscriber extends BaseConsoleSubscriber {

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

    /** Profile name for diagnostics (immutable) */
    private final String profileName;

    /**
     * Optional JScrollPane reference for legacy smart auto-scroll.
     * When set, the console will only auto-scroll to the bottom if the user
     * is already viewing near the bottom (>92% of scroll range).
     * <p>
     * NOTE: This is a legacy API. Prefer using {@link #getScrollController()} for
     * modern smart-scroll integration via {@link application.utils.gui.SmartScrollController}.
     * </p>
     */
    private volatile JScrollPane scrollPane;

    /** Smart scroll threshold: only auto-scroll when user is at or above 92% of scroll range */
    private static final double SMART_SCROLL_THRESHOLD = 0.92;

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
        super(document, maxLines, filter);

        this.profileName = profileName;
    }

    // ── Legacy ScrollPane API (backward compatibility) ───────────────────

    /**
     * Sets the parent JScrollPane to enable smart auto-scroll behavior.
     * When a scrollPane is provided, the console will only auto-scroll to the bottom
     * when the user is already viewing near the bottom of the content (above 92% threshold).
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

    // ── Template Method Hooks ────────────────────────────────────────────

    @Override
    protected String formatLine(LogEvent event) {
        return formatter.format(event);
    }

    @Override
    protected Color resolveLineColor(LogEvent event) {
        return resolveColor(event.getLevel());
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

    // ── Legacy Smart Auto-Scroll (backward compat hook) ──────────────────

    /**
     * Scrolls the console to the end only if the user is already viewing near the bottom.
     * Similar to VS Code terminal: new content auto-scrolls when the user is at the bottom,
     * but does not jump when the user has scrolled up to read older content.
     * <p>
     * This is invoked after the base class batch-append via {@link #onBatchAppended()}.
     * </p>
     */
    private void maybeScrollToEnd() {
        JScrollPane pane = scrollPane;
        if (pane == null) {
            return;
        }

        JScrollBar verticalBar = pane.getVerticalScrollBar();
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
    }

    /**
     * Called after the base class completes a batch append.
     * Overridden to apply legacy smart-scroll behavior when a JScrollPane
     * was set via {@link #setScrollPane(JScrollPane)}.
     */
    protected void onBatchAppended() {
        if (scrollPane != null && SwingUtilities.isEventDispatchThread()) {
            maybeScrollToEnd();
        }
    }

    // ── Diagnostics ─────────────────────────────────────────────────────

    /** @return the profile name associated with this subscriber */
    public String getProfileName() {
        return profileName;
    }

    @Override
    public String toString() {
        return "ProfileConsoleSubscriber{profile='" + profileName + "', disposed=" + disposed + '}';
    }
}