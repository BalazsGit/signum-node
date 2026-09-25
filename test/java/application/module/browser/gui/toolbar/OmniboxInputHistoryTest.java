package application.module.browser.gui.toolbar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the omnibox input history (plan F9, N10).
 */
class OmniboxInputHistoryTest {

    @Test
    @DisplayName("up walks the submitted inputs newest first, down walks back")
    void upDownWalk() {
        OmniboxInputHistory history = new OmniboxInputHistory();
        history.commit("first query");
        history.commit("second query");

        assertEquals("second query", history.up("typeahead"));
        assertTrue(history.isNavigating());
        assertEquals("first query", history.up("second query"));

        // further up stays on the oldest entry
        assertEquals("first query", history.up("first query"));

        assertEquals("second query", history.down("first query"));
        assertEquals("typeahead", history.down("second query")); // back to the input
        assertFalse(history.isNavigating());
    }

    @Test
    @DisplayName("up with no history keeps the current input")
    void upWithoutHistory() {
        OmniboxInputHistory history = new OmniboxInputHistory();
        assertEquals("still typing", history.up("still typing"));
        assertFalse(history.isNavigating());
    }

    @Test
    @DisplayName("duplicate, blank and null commits are ignored")
    void commitRules() {
        OmniboxInputHistory history = new OmniboxInputHistory();
        history.commit("a");
        history.commit("a"); // duplicate of the newest
        history.commit("a   "); // trimmed duplicate
        history.commit("  "); // blank
        history.commit(null);
        history.commit("b");

        assertEquals("b", history.up(""));
        assertEquals("a", history.up("b"));
        assertEquals("b", history.down("a")); // back one step in the history
        assertEquals("", history.down("b")); // out of the history: the saved input
        assertFalse(history.isNavigating());
    }

    @Test
    @DisplayName("the history is capped (oldest entries fall off)")
    void cap() {
        OmniboxInputHistory history = new OmniboxInputHistory();
        for (int i = 0; i < 60; i++) {
            history.commit("q" + i);
        }
        // walk the whole kept history (newest first: q59 ... q10)
        String text = "";
        for (int i = 0; i < 50; i++) {
            text = history.up(i == 0 ? "" : text);
        }
        assertEquals("q10", text); // q0..q9 fell off
        assertEquals("q10", history.up(text)); // stuck at the oldest
    }

    @Test
    @DisplayName("clear resets the entries and the navigation state")
    void clear() {
        OmniboxInputHistory history = new OmniboxInputHistory();
        history.commit("a");
        history.up("");
        history.clear();
        assertFalse(history.isNavigating());
        assertEquals("typing", history.up("typing"));
    }
}