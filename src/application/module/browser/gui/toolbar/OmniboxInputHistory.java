package application.module.browser.gui.toolbar;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The omnibox's own input history (plan F9, N10): the queries the user typed
 * and submitted are remembered for the Up/Down arrows — but only while the
 * suggestion popup is <em>not</em> open (with a popup, the arrows navigate
 * the popup rows, the F2 behavior).
 * <p>
 * The classic editor semantics: the first Up stores the current (not yet
 * submitted) input, then walks newest-first; Down walks back and finally
 * restores the stored input. Pure logic — the {@code Omnibox} owns exactly
 * one instance; fully unit-testable without Swing.
 */
public final class OmniboxInputHistory {

    /** Cap of the remembered entries (a session's worth, not a database). */
    private static final int CAP = 50;

    /** Newest first. */
    private final Deque<String> entries = new ArrayDeque<>();
    private boolean navigating;
    private String savedInput = "";
    private int cursor = -1; // index into the flattened list while navigating

    /**
     * Remembers a submitted input. Blank inputs are ignored; an input equal
     * to the newest entry is not duplicated.
     */
    public void commit(String text) {
        if (text == null) {
            return;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (!entries.isEmpty() && entries.peekFirst().equals(trimmed)) {
            return;
        }
        entries.addFirst(trimmed);
        while (entries.size() > CAP) {
            entries.removeLast();
        }
    }

    /**
     * @param currentInput the field content at the moment of the key press
     * @return the text the field should show after the press (the current
     *         input when there is nothing earlier to walk to)
     */
    public String up(String currentInput) {
        if (entries.isEmpty()) {
            return currentInput;
        }
        List<String> flat = new ArrayList<>(entries);
        if (!navigating) {
            navigating = true;
            savedInput = currentInput == null ? "" : currentInput;
            cursor = 0;
        } else if (cursor < flat.size() - 1) {
            cursor++;
        }
        return cursor < flat.size() ? flat.get(cursor) : savedInput;
    }

    /**
     * @param currentInput the field content at the moment of the key press
     * @return the text the field should show (back to the pre-history input
     *         when the walk is finished — the navigation state is left)
     */
    public String down(String currentInput) {
        if (!navigating || entries.isEmpty()) {
            return currentInput;
        }
        List<String> flat = new ArrayList<>(entries);
        if (cursor > 0) {
            cursor--;
            return flat.get(cursor);
        }
        navigating = false;
        cursor = -1;
        return savedInput;
    }

    /** @return {@code true} while the arrows walk the history (the popup is hidden then). */
    public boolean isNavigating() {
        return navigating;
    }

    /** Clears the remembered entries and any in-flight navigation. */
    public void clear() {
        entries.clear();
        navigating = false;
        savedInput = "";
        cursor = -1;
    }
}