/**
 * Centralized smart-scroll controller for console panels.
 * <p>
 * Manages auto-follow behavior with user override: when the user scrolls up,
 * following stops and a "new content below" flag is raised. Clicking the
 * scroll-to-bottom arrow (or scrolling to the bottom manually) resumes follow.
 * </p>
 * <p>
 * Thread-safe: all public methods delegate to the EDT when called off-thread.
 * </p>
 * <p>
 * <h3>Push-based Event Notification</h3>
 * Listeners registered via {@link #onStateChanged(Consumer)} are notified on the
 * EDT whenever the scroll state changes. The boolean parameter indicates whether
 * the floating "scroll to bottom" button should be visible:
 * <ul>
 *   <li>{@code true}  = PAUSED + new content below (show button)</li>
 *   <li>{@code false} = FOLLOWING or no new content (hide button)</li>
 * </ul>
 * This completely replaces any timer-based polling pattern.
 * </p>
 *
 * <h3>State Machine</h3>
 * <pre>
 *   FOLLOWING ──(user scrolls up below threshold)──→ PAUSED
 *     ↑                                                    │
 *     │            (click arrow OR scroll to bottom)       │
 *     └────────────────────────────────────────────────────┘
 * </pre>
 */
package application.utils.gui;

import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SmartScrollController {

    private static final Logger LOGGER = LoggerFactory.getLogger(SmartScrollController.class);

    /** Default smart-scroll threshold (92% of scroll range) */
    public static final double DEFAULT_THRESHOLD = 0.92;

    /** How many contentAppended calls between summary log messages */
    private static final int SUMMARY_INTERVAL = 100;

    // ── State ────────────────────────────────────────────────────────────

    private JScrollPane scrollPane;
    private final double threshold;
    private boolean following = true;
    private boolean hasNewContentBelow = false;
    private AdjustmentListener adjustmentListener;
    
    /** Suppresses adjustment events during programmatic scrolls */
    private boolean isSuppressingEvents = false;
    
    /** Push-based listeners notified on state changes (no polling) */
    private final List<Consumer<Boolean>> stateChangeListeners = new ArrayList<>();

    // ── Performance counters (low-overhead, no allocation) ────────────────

    /** Total number of contentAppended() invocations */
    private int totalCalls;

    /** How many times we actually scrolled to bottom */
    private int actualScrolls;

    /** How many times we skipped scrolling (user reading old logs) */
    private int skippedScrolls;

    /** How many times follow/pause state changed */
    private int stateTransitions;

    // ── Constructors ─────────────────────────────────────────────────────

    /** Creates a controller with the default threshold (0.92). */
    public SmartScrollController() {
        this(DEFAULT_THRESHOLD);
    }

    /** Creates a controller with an explicit threshold (0.0 - 1.0). */
    public SmartScrollController(double threshold) {
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be 0.0-1.0, got: " + threshold);
        }
        this.threshold = threshold;
    }

    // ── Push-based State Change Listeners ────────────────────────────────

    /**
     * Registers a push-based listener that is called on the EDT whenever the
     * scroll state changes. The boolean parameter indicates whether the caller
     * should show a "scroll to bottom" UI element:
     * <ul>
     *   <li>{@code true}  = user scrolled up AND there is new content below</li>
     *   <li>{@code false} = following mode OR no unread content below</li>
     * </ul>
     * This replaces any timer-based polling pattern for detecting scroll state.
     *
     * @param listener callback (never null)
     */
    public void onStateChanged(Consumer<Boolean> listener) {
        if (listener == null) {
            throw new NullPointerException("Listener must not be null");
        }
        stateChangeListeners.add(listener);
    }

    /**
     * Removes a previously registered state change listener.
     *
     * @param listener the callback to remove
     */
    public void removeStateChangeListener(Consumer<Boolean> listener) {
        stateChangeListeners.remove(listener);
    }

    /**
     * Notifies all registered listeners about a state change.
     * Always runs on EDT.
     *
     * @param showButton true to signal that the scroll-to-bottom button should appear
     */
    private void fireStateChanged(boolean showButton) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> fireStateChanged(showButton));
            return;
        }
        for (Consumer<Boolean> listener : stateChangeListeners) {
            try {
                listener.accept(showButton);
            } catch (Exception e) {
                LOGGER.warn("SmartScrollController listener error", e);
            }
        }
    }

    // ── Attachment ───────────────────────────────────────────────────────

    /**
     * Attaches this controller to the given JScrollPane.
     * Installs an {@link AdjustmentListener} on the vertical scrollbar to detect
     * manual user scroll events. Call {@link #detach()} before disposal.
     */
    public void attach(JScrollPane pane) {
        detach();
        this.scrollPane = pane;
        JScrollBar bar = pane.getVerticalScrollBar();
        if (bar != null) {
            adjustmentListener = this::onScrollbarAdjustment;
            bar.addAdjustmentListener(adjustmentListener);
        }
    }

    /** Removes the listener and clears the scroll-pane reference. */
    public void detach() {
        if (scrollPane != null && adjustmentListener != null) {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            if (bar != null) {
                bar.removeAdjustmentListener(adjustmentListener);
            }
        }
        adjustmentListener = null;
        scrollPane = null;
    }

    // ── Content-changed callback ─────────────────────────────────────────

    /**
     * Call this method after appending new content to the console.
     * If currently following, scrolls to the bottom. Otherwise raises the
     * "new content below" flag so the arrow button can appear.
     * <p>
     * Emits a periodic [ScrollDebug] summary every {@value #SUMMARY_INTERVAL} calls
     * showing efficiency metrics (no allocation overhead).
     * </p>
     *
     * Safe to call from any thread.
     */
    public void contentAppended() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::contentAppended);
            return;
        }

        totalCalls++;

        JScrollPane pane = this.scrollPane;
        if (pane == null) {
            return;
        }
        JScrollBar bar = pane.getVerticalScrollBar();
        if (bar == null) {
            return;
        }

        if (following) {
            scrollToBottomInternal(bar);
            hasNewContentBelow = false;
            actualScrolls++;
        } else {
            hasNewContentBelow = true;
            skippedScrolls++;
            // Push event: new content arrived while paused → show button
            fireStateChanged(true);
        }

        // Periodic summary log - no per-call allocation, only every N calls
        if (totalCalls % SUMMARY_INTERVAL == 0) {
            double efficiency = totalCalls > 0 ? (100.0 * actualScrolls / totalCalls) : 100.0;
            LOGGER.info("[ScrollDebug] SmartScrollController summary: totalCalls={}, actualScrolls={}, " +
                    "skipped={}, transitions={}, efficiency={}% ",
                    totalCalls, actualScrolls, skippedScrolls, stateTransitions, 
                    String.format("%.1f", efficiency));
        }
    }

    // ── User scroll detection ────────────────────────────────────────────

    /** Internal handler for scrollbar adjustment events. */
    private void onScrollbarAdjustment(AdjustmentEvent evt) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> onScrollbarAdjustment(evt));
            return;
        }

        // Ignore events triggered by programmatic scrolls
        if (isSuppressingEvents) {
            return;
        }

        JScrollBar bar = (JScrollBar) evt.getSource();
        double ratio = getScrollRatio(bar);

        boolean wasFollowing = following;
        if (ratio >= threshold) {
            // User scrolled to bottom: resume following
            following = true;
            hasNewContentBelow = false;
        } else {
            // User scrolled up: pause following
            following = false;
            hasNewContentBelow = true;
        }
        if (wasFollowing != following) {
            stateTransitions++;
            LOGGER.info("[ScrollDebug] SmartScrollController state transition: {} to {} " +
                    "(ratio={} vs threshold={})",
                    wasFollowing ? "FOLLOWING" : "PAUSED",
                    following ? "FOLLOWING" : "PAUSED",
                    String.format("%.3f", ratio), threshold);
            // Push event to listeners on state change
            fireStateChanged(!following && hasNewContentBelow);
        }
    }

    // ── Scroll-to-bottom action ──────────────────────────────────────────

    /**
     * Immediately scrolls to the bottom and resumes follow mode.
     * Typically called from the "scroll-down arrow" button.
     *
     * Safe to call from any thread.
     */
    public void scrollToBottom() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::scrollToBottom);
            return;
        }
        JScrollPane pane = this.scrollPane;
        if (pane == null) {
            return;
        }
        JScrollBar bar = pane.getVerticalScrollBar();
        if (bar == null) {
            return;
        }
        scrollToBottomInternal(bar);
        following = true;
        hasNewContentBelow = false;
        // Push event: button should be hidden after scrolling to bottom
        fireStateChanged(false);
    }

    // ── State accessors ──────────────────────────────────────────────────

    /** @return true when new content should auto-scroll */
    public boolean isFollowing() {
        return following;
    }

    /**
     * Manually sets the follow state.
     *
     * @param following true to resume auto-scroll on new content
     */
    public void setFollowing(boolean following) {
        this.following = following;
    }

    /**
     * @return true if there is unread content below the current viewport position
     */
    public boolean hasNewContentBelow() {
        return hasNewContentBelow;
    }

    /** @return the configured threshold value */
    public double getThreshold() {
        return threshold;
    }

    /** @return the attached JScrollPane, or null */
    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    // ── Performance counters (read-only) ─────────────────────────────────

    /** @return total number of contentAppended() calls received */
    public int getTotalCalls() {
        return totalCalls;
    }

    /** @return how many times we actually scrolled to bottom */
    public int getActualScrolls() {
        return actualScrolls;
    }

    /** @return how many times scrolling was skipped (user reading old logs) */
    public int getSkippedScrolls() {
        return skippedScrolls;
    }

    /** @return how many follow/pause state transitions occurred */
    public int getStateTransitions() {
        return stateTransitions;
    }

    /** @return scroll efficiency percentage (actualScrolls / totalCalls * 100) */
    public double getEfficiencyPercent() {
        return totalCalls > 0 ? (100.0 * actualScrolls / totalCalls) : 100.0;
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private void scrollToBottomInternal(JScrollBar bar) {
        int max = bar.getMaximum();
        int extent = bar.getVisibleAmount();
        int scrollableRange = max - extent;
        if (scrollableRange > 0) {
            // Suppress adjustment events during programmatic scroll to prevent
            // the listener from interpreting this as user intent
            boolean wasSuppressing = isSuppressingEvents;
            try {
                isSuppressingEvents = true;
                bar.setValue(max);
            } finally {
                isSuppressingEvents = wasSuppressing;
            }
        }
    }

    private static double getScrollRatio(JScrollBar bar) {
        int max = bar.getMaximum();
        int extent = bar.getVisibleAmount();
        int current = bar.getValue();
        int scrollableRange = max - extent;
        if (scrollableRange <= 0) {
            return 1.0; // content fits entirely - treat as at bottom
        }
        return (double) current / scrollableRange;
    }
}