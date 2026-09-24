package application.module.browser.model.tab;

import java.util.Objects;

/**
 * Immutable notification of a tab-list change (plan §4.4).
 * <p>
 * {@link TabController} dispatches events synchronously on the thread that
 * made the change — GUI listeners must hop to the EDT themselves. The GUI
 * updates only the affected parts (no full redraw): ADDED/REMOVED/MOVED touch
 * the tab strip structure, UPDATED only one cell, ACTIVATED the selection and
 * the content panel.
 */
public final class TabEvent {

    public enum Type {
        ADDED, REMOVED, MOVED, UPDATED, ACTIVATED
    }

    private final Type type;
    private final BrowserTab tab;
    private final int index;
    private final int newIndex;

    private TabEvent(Type type, BrowserTab tab, int index, int newIndex) {
        this.type = type;
        this.tab = Objects.requireNonNull(tab, "tab");
        this.index = index;
        this.newIndex = newIndex;
    }

    public static TabEvent added(BrowserTab tab, int index) {
        return new TabEvent(Type.ADDED, tab, index, -1);
    }

    public static TabEvent removed(BrowserTab tab, int index) {
        return new TabEvent(Type.REMOVED, tab, index, -1);
    }

    public static TabEvent moved(BrowserTab tab, int from, int to) {
        return new TabEvent(Type.MOVED, tab, from, to);
    }

    public static TabEvent updated(BrowserTab tab) {
        return new TabEvent(Type.UPDATED, tab, -1, -1);
    }

    public static TabEvent activated(BrowserTab tab, int index) {
        return new TabEvent(Type.ACTIVATED, tab, index, -1);
    }

    public Type getType() {
        return type;
    }

    public BrowserTab getTab() {
        return tab;
    }

    /** @return the tab's index (ADDED/REMOVED/ACTIVATED), or the source index (MOVED), or -1 (UPDATED). */
    public int getIndex() {
        return index;
    }

    /** @return the destination index (MOVED only), else -1. */
    public int getNewIndex() {
        return newIndex;
    }
}
