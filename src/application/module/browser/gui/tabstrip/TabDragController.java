package application.module.browser.gui.tabstrip;

/**
 * T3: in-window drag-reorder of the tab strip (no OS drag & drop — the tabs
 * never leave the window, so an in-list drag with a live placeholder is the
 * right interaction and it stays fully controllable).
 * <p>
 * State machine: idle → {@link #press} → (past the pixel threshold)
 * dragging with a {@link #targetIndex} → {@link #commit}/{@link #cancel}.
 * The bar repaints the list while dragging; the renderer draws the empty
 * slot (source) and the drop indicator (target).
 */
final class TabDragController {

    static final int DRAG_THRESHOLD_PX = 5;

    private int sourceIndex = -1;
    private int targetIndex = -1;
    private int pressX;
    private int pressY;

    void press(int index, int x, int y) {
        sourceIndex = index;
        targetIndex = -1;
        pressX = x;
        pressY = y;
    }

    /**
     * Feeds a drag move.
     *
     * @param x, y      the current mouse position (list coordinates)
     * @param target    the cell under the mouse (already clamped by the bar)
     * @return {@code true} when the visual state changed (repaint needed)
     */
    boolean movedTo(int x, int y, int target) {
        if (sourceIndex < 0) {
            return false;
        }
        if (targetIndex < 0 && Math.hypot(x - pressX, y - pressY) < DRAG_THRESHOLD_PX) {
            return false; // not a drag yet
        }
        if (target != targetIndex) {
            targetIndex = target;
            return true;
        }
        return targetIndex >= 0;
    }

    boolean isDragging() {
        return targetIndex >= 0;
    }

    int sourceIndex() {
        return sourceIndex;
    }

    /** @return the target cell in pre-removal coordinates (what the user dragged onto). */
    int targetIndex() {
        return targetIndex;
    }

    void cancel() {
        sourceIndex = -1;
        targetIndex = -1;
    }
}
