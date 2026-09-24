package application.module.browser.gui.animation;

import java.util.HashMap;
import java.util.Map;

/**
 * A1: tab open/move animation state (scale+fade, ~180 ms).
 * <p>
 * The effect is rendered by the tab strip's cell renderer (pure Swing). The
 * tab's CEF content cannot be scaled or faded — it is a separate native OS
 * window — so the animation lives on the tab cell itself, which is where the
 * user's attention is during open/close anyway.
 * <p>
 * All access happens on the EDT (the tab strip drives the repaints).
 */
public final class TabAnimation {

    public static final long BIRTH_DURATION_MS = 180;
    public static final long MOVE_DURATION_MS = 150;

    private static final class Effect {
        final long start;
        final long duration;

        Effect(long start, long duration) {
            this.start = start;
            this.duration = duration;
        }
    }

    private final Map<String, Effect> births = new HashMap<>();
    private final Map<String, Effect> moves = new HashMap<>();

    /** Called when a tab is added (open) — starts the scale+fade-in. */
    public void noteBirth(String tabId) {
        births.put(tabId, new Effect(System.currentTimeMillis(), BIRTH_DURATION_MS));
    }

    /** Called after a drag-reorder commit — a brief highlight of the moved cell. */
    public void noteMove(String tabId) {
        moves.put(tabId, new Effect(System.currentTimeMillis(), MOVE_DURATION_MS));
    }

    /**
     * @return the 0..1 progress of the tab's birth animation, or -1 when it is
     *         not currently animating (also prunes finished effects).
     */
    public float birthProgress(String tabId) {
        return progress(births, tabId);
    }

    /** @return the 0..1 progress of the tab's move highlight, or -1. */
    public float moveProgress(String tabId) {
        return progress(moves, tabId);
    }

    /** @return {@code true} while any effect is still running (drives the repaint timer). */
    public boolean isAnimating() {
        long now = System.currentTimeMillis();
        prune(births, now);
        prune(moves, now);
        return !births.isEmpty() || !moves.isEmpty();
    }

    private float progress(Map<String, Effect> effects, String tabId) {
        Effect effect = effects.get(tabId);
        if (effect == null) {
            return -1f;
        }
        long elapsed = System.currentTimeMillis() - effect.start;
        if (elapsed <= 0) {
            return 0f;
        }
        if (elapsed >= effect.duration) {
            effects.remove(tabId);
            return -1f;
        }
        return (float) elapsed / effect.duration;
    }

    private static void prune(Map<String, Effect> effects, long now) {
        effects.entrySet().removeIf(e -> now - e.getValue().start >= e.getValue().duration);
    }
}
