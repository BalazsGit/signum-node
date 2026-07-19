package application.utils.logging.gui;

import java.awt.Color;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import application.utils.gui.SmartScrollController;
import application.utils.logging.TerminalFormatLogFormatter;
import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogEventBatcher;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogSubscriber;

/**
 * Abstract base class for console log subscribers.
 * <p>
 * Provides shared functionality for batch processing, filtering,
 * line trimming, smart scroll integration, and EDT-safe document mutations.
 * Concrete subclasses override {@link #formatLine(LogEvent)} and
 * {@link #resolveLineColor(LogEvent)} to provide profile-specific styling.
 * </p>
 * <p>
 * <h3>Template Method Pattern</h3>
 * The {@link #appendBatch(List)} method defines the skeleton algorithm:
 * <ol>
 *   <li>Verify dispatch thread (EDT)</li>
 *   <li>Iterate events, format each line via {@link #formatLine(LogEvent)}</li>
 *   <li>Resolve color via {@link #resolveLineColor(LogEvent)}</li>
 *   <li>Insert into StyledDocument</li>
 *   <li>Enforce max line count</li>
 *   <li>Notify scroll controller</li>
 * </ol>
 * </p>
 * <p>
 * <h3>Thread Safety</h3>
 * Events arrive from arbitrary threads. All StyledDocument mutations are dispatched
 * to the EDT via {@link LogEventBatcher} with time/count thresholds.
 * </p>
 * <p>
 * <h3>Lifecycle</h3>
 * When disposed, {@link #dispose()} flushes remaining buffered events and stops the batcher.
 * </p>
 *
 * @see SystemConsoleSubscriberImpl
 * @see ProfileConsoleSubscriberImpl
 * @see SmartScrollController
 * @see LogEventBatcher
 */
public abstract class BaseConsoleSubscriber implements LogSubscriber {

    /** Default maximum console line count to prevent unbounded memory growth */
    public static final int DEFAULT_MAX_LINES = 500;

    // ── Shared Fields ──────────────────────────────────────────────────────

    /** Target document for appending formatted log lines (immutable) */
    protected final StyledDocument document;

    /** Maximum line count before oldest lines are trimmed (immutable) */
    protected final int maxLines;

    /** Centralized scroll controller shared with UI components (immutable) */
    protected final SmartScrollController scrollController;

    /** Terminal-format log formatter (singleton, shared across all instances) */
    protected final TerminalFormatLogFormatter formatter = TerminalFormatLogFormatter.INSTANCE;

    /** Dynamic filter; volatile for safe concurrent reads without synchronization */
    protected volatile LogFilter filter;

    /** Disposal flag; volatile for safe concurrent access from any thread */
    protected volatile boolean disposed = false;

    /** Batcher that accumulates events and flushes them to EDT in bulk */
    protected final LogEventBatcher batcher;

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * Creates a subscriber with default max lines (500) and no initial filter.
     *
     * @param document the target StyledDocument (never null)
     */
    protected BaseConsoleSubscriber(StyledDocument document) {
        this(document, DEFAULT_MAX_LINES, null);
    }

    /**
     * Creates a subscriber with custom settings.
     *
     * @param document the target StyledDocument (never null)
     * @param maxLines maximum line count before trimming oldest lines
     * @param filter   optional initial event filter (null = accept all)
     */
    protected BaseConsoleSubscriber(StyledDocument document, int maxLines, LogFilter filter) {
        if (document == null) {
            throw new NullPointerException("StyledDocument must not be null");
        }
        if (maxLines <= 0) {
            throw new IllegalArgumentException("maxLines must be positive, got: " + maxLines);
        }

        this.document = document;
        this.maxLines = maxLines;
        this.filter = filter;
        this.scrollController = new SmartScrollController();

        batcher = new LogEventBatcher(this::appendBatch);
        batcher.start();
    }

    // ── Shared API (setter-based configuration) ────────────────────────────

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

    /**
     * Returns the centralized {@link SmartScrollController} for this subscriber.
     * Use it to attach a scroll-pane, query follow state, or trigger scroll-to-bottom.
     */
    public SmartScrollController getScrollController() {
        return scrollController;
    }

    // ── Template Method Hooks (abstract) ───────────────────────────────────

    /**
     * Formats a log event into a display-ready text string.
     * Subclasses decide whether to include profile tags, timestamps, etc.
     *
     * @param event the log event to format (never null)
     * @return the formatted text line, or null/empty to skip this event
     */
    protected abstract String formatLine(LogEvent event);

    /**
     * Resolves the display color for a log event.
     * Subclasses may consider log level, profile name, or both.
     *
     * @param event the log event to resolve color for (never null)
     * @return the desired foreground color, or null to use default text color
     */
    protected abstract Color resolveLineColor(LogEvent event);

    // ── Shared Implementation ──────────────────────────────────────────────

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

    /**
     * Appends a batch of log events to the StyledDocument.
     * Called on the EDT by {@link LogEventBatcher}.
     * <p>
     * This is the Template Method that defines the standard algorithm for
     * processing a batch of log events. Subclasses customize behavior via
     * {@link #formatLine(LogEvent)} and {@link #resolveLineColor(LogEvent)}.
     * </p>
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
                String text = formatLine(event);
                if (text == null || text.isEmpty()) {
                    continue;
                }

                SimpleAttributeSet attrs = new SimpleAttributeSet();
                Color lineColor = resolveLineColor(event);
                if (lineColor != null) {
                    StyleConstants.setForeground(attrs, lineColor);
                }

                document.insertString(docLength, text, attrs);
                docLength = document.getLength();
            }

            enforceMaxLines();

            // Delegate scroll logic to the centralized controller
            scrollController.contentAppended();

            // Hook for subclass post-batch processing (e.g., legacy smart-scroll behavior)
            onBatchAppended();

        } catch (BadLocationException e) {
            throw new RuntimeException("BadLocationException during batch append", e);
        }
    }

    // ── Line Trimming ──────────────────────────────────────────────────────

    /**
     * Removes oldest lines when the document exceeds maxLines.
     * This prevents unbounded memory growth during long-running nodes.
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
     * Counts lines in the StyledDocument by counting newline characters.
     */
    static int countLines(StyledDocument doc) throws BadLocationException {
        int count = 1; // At least one line if there's content
        String text = doc.getText(0, doc.getLength());
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        scrollController.detach();
        batcher.stop();
    }

    // ── Extension Hook ───────────────────────────────────────────────────

    /**
     * Called after a successful batch append completes (after maxLines enforcement
     * and scroll controller notification). Subclasses may override to add
     * post-batch processing, e.g., legacy smart-scroll behavior.
     * <p>
     * Default implementation is a no-op.
     * </p>
     */
    protected void onBatchAppended() {
        // Default no-op; subclasses may override
    }

    // ── Convenience Methods ────────────────────────────────────────────────

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

    /** @return the maxLines configured for this subscriber */
    public int getMaxLines() {
        return maxLines;
    }
}