package application.utils.gui.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;

import application.utils.gui.GuiColors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SearchHighlighter}.
 * <p>
 * The highlighter only manipulates the styled document model (no painting),
 * so it can be exercised in a headless test environment.
 * </p>
 */
@DisplayName("SearchHighlighter Tests")
class SearchHighlighterTest {

    private JTextPane textPane;
    private SearchHighlighter highlighter;

    @BeforeEach
    void setUp() {
        textPane = new JTextPane();
        textPane.setText("[INFO] line one\n"
                + "[WARN] error in two\n"
                + "[ERROR] another error here\n"
                + "[DEBUG] quiet line\n");
        highlighter = new SearchHighlighter(textPane);
    }

    @Test
    void applySearch_caseInsensitive_findsAllMatches() {
        int count = highlighter.applySearch("error");
        // "error" in line two, "ERROR" + "error" in line three => 3 matches
        assertEquals(3, count);
        assertEquals(0, highlighter.currentIndex(), "first match should become active");
    }

    @Test
    void applySearch_noMatches_returnsZeroAndNoActiveIndex() {
        int count = highlighter.applySearch("zzz-not-there");
        assertEquals(0, count);
        assertEquals(-1, highlighter.currentIndex());
    }

    @Test
    void applySearch_blankQuery_clearsAll() {
        highlighter.applySearch("error");
        int count = highlighter.applySearch("   ");
        assertEquals(0, count);
        assertEquals(-1, highlighter.currentIndex());
    }

    @Test
    void applySearch_nullQuery_clearsAll() {
        highlighter.applySearch("error");
        assertEquals(0, highlighter.applySearch(null));
    }

    @Test
    void navigateNext_wrapsAroundToFirst() {
        highlighter.applySearch("error"); // index 0 active, 3 matches
        assertTrue(highlighter.navigateNext());
        assertEquals(1, highlighter.currentIndex());
        assertTrue(highlighter.navigateNext());
        assertEquals(2, highlighter.currentIndex());
        assertTrue(highlighter.navigateNext());
        assertEquals(0, highlighter.currentIndex(), "next from last wraps to first");
    }

    @Test
    void navigatePrevious_wrapsAroundToLast() {
        highlighter.applySearch("error"); // index 0 active, 3 matches
        assertTrue(highlighter.navigatePrevious());
        assertEquals(2, highlighter.currentIndex(), "previous from first wraps to last");
    }

    @Test
    void navigate_withNoMatches_returnsFalse() {
        highlighter.applySearch("zzz");
        assertFalse(highlighter.navigateNext());
        assertFalse(highlighter.navigatePrevious());
    }

    @Test
    void currentMatchRange_nullWhenNoActiveMatch() {
        assertNull(highlighter.currentMatchRange(), "fresh highlighter has no active match");
        highlighter.applySearch("zzz-not-there");
        assertNull(highlighter.currentMatchRange(), "no matches => no active range");
        highlighter.applySearch("error");
        highlighter.clear();
        assertNull(highlighter.currentMatchRange(), "after clear => no active range");
    }

    @Test
    void currentMatchRange_tracksActiveMatchThroughNavigation() {
        String lower = textPane.getText().toLowerCase(Locale.ROOT);
        assertEquals(3, highlighter.applySearch("error"));

        int expected = lower.indexOf("error");
        int[] range = highlighter.currentMatchRange();
        assertNotNull(range);
        assertEquals(expected, range[0]);
        assertEquals(expected + "error".length(), range[1]);

        highlighter.navigateNext();
        expected = lower.indexOf("error", expected + 1);
        range = highlighter.currentMatchRange();
        assertEquals(expected, range[0], "range must follow navigation to the next match");

        highlighter.navigatePrevious();
        range = highlighter.currentMatchRange();
        assertEquals(lower.indexOf("error"), range[0], "previous returns to the first match");
    }

    @Test
    void clear_resetsState() {
        highlighter.applySearch("error");
        highlighter.clear();
        assertEquals(0, highlighter.matchCount());
        assertEquals(-1, highlighter.currentIndex());
    }

    @Test
    void reapply_afterContentChange_keepsActiveIndexWhenPossible() {
        highlighter.applySearch("error");
        highlighter.navigateNext(); // index 1
        int before = textPane.getDocument().getLength();
        textPane.setText(textPane.getText() + "[INFO] new line with ERROR\n");
        assertTrue(textPane.getDocument().getLength() > before);

        highlighter.reapply();

        assertEquals(4, highlighter.matchCount(), "one more match after appending");
        assertEquals(1, highlighter.currentIndex(), "active index must be preserved");
    }

    @Test
    void reapply_withoutQuery_isNoOp() {
        // no query yet — must not throw and must not create matches
        highlighter.reapply();
        assertEquals(0, highlighter.matchCount());
    }

    // ── Painting every match ──────────────────────────────────────────────

    @Test
    void applySearch_paintsEveryMatchWithSoftAndFirstWithStrong() throws BadLocationException {
        highlighter.applySearch("error");

        List<Element> matchEls = elementsWithAttribute(SearchHighlighter.MATCH_ATTRIBUTE);
        List<Element> currentEls = elementsWithAttribute(SearchHighlighter.CURRENT_MATCH_ATTRIBUTE);
        assertEquals(3, matchEls.size(), "all three occurrences must be highlighted immediately");
        assertEquals(1, currentEls.size(), "exactly one active (strong) highlight");
        for (Element el : matchEls) {
            assertEquals("error", elementText(el).toLowerCase(Locale.ROOT),
                    "each highlight must cover exactly one match");
        }
        // The active highlight must sit on the FIRST occurrence
        Element current = currentEls.get(0);
        Element first = matchEls.stream().min(Comparator.comparingInt(Element::getStartOffset)).get();
        assertEquals(first.getStartOffset(), current.getStartOffset(),
                "the first match carries the strong highlight");
    }

    @Test
    void queryChange_clearsPreviousHighlights() {
        highlighter.applySearch("error");
        highlighter.applySearch("quiet");

        List<Element> matchEls = elementsWithAttribute(SearchHighlighter.MATCH_ATTRIBUTE);
        assertEquals(1, matchEls.size(), "only the new query's match stays highlighted");
        assertEquals("quiet", elementText(matchEls.get(0)).toLowerCase(Locale.ROOT));
        assertEquals(0, elementsWithAttribute(SearchHighlighter.CURRENT_MATCH_ATTRIBUTE).size() - 1,
                "exactly one active highlight for the new query");
    }

    @Test
    void reapply_afterDocumentTrim_leavesNoStaleHighlights() throws BadLocationException {
        highlighter.applySearch("error"); // 3 matches
        // Simulate the console trimming its oldest line from the front — the
        // stored match offsets shift, a stale-range-based clear would miss.
        textPane.getDocument().remove(0, 16); // removes "[INFO] line one\n"
        highlighter.reapply();

        assertEquals(3, highlighter.matchCount(), "the remaining matches must be found again");
        List<Element> matchEls = elementsWithAttribute(SearchHighlighter.MATCH_ATTRIBUTE);
        assertEquals(3, matchEls.size(), "no stale and no missing highlights after trim");
        for (Element el : matchEls) {
            assertEquals("error", elementText(el).toLowerCase(Locale.ROOT),
                    "every highlight must cover an actual match");
        }
    }

    // ── Palette-driven colors ─────────────────────────────────────────────

    @Test
    void palette_definesSearchHighlightKeys() {
        Color soft = GuiColors.getSearchMatch();
        Color strong = GuiColors.getSearchActiveMatch();
        assertNotNull(soft, "gui.search.match must be defined in the palette");
        assertNotNull(strong, "gui.search.active.match must be defined in the palette");
        // the overlay must stay semi-transparent so the level-colored text remains readable
        assertTrue(soft.getAlpha() < 255, "soft match overlay must be semi-transparent");
        assertTrue(strong.getAlpha() < 255, "active match overlay must be semi-transparent");
    }

    @Test
    void applySearch_paintsPaletteColors() throws BadLocationException {
        highlighter.applySearch("error");

        Color soft = GuiColors.getSearchMatch();
        Color strong = GuiColors.getSearchActiveMatch();
        List<Element> matchEls = elementsWithAttribute(SearchHighlighter.MATCH_ATTRIBUTE);
        List<Element> currentEls = elementsWithAttribute(SearchHighlighter.CURRENT_MATCH_ATTRIBUTE);
        for (Element el : matchEls) {
            Color expected = currentEls.contains(el) ? strong : soft;
            assertEquals(expected, el.getAttributes().getAttribute(StyleConstants.Background),
                    "highlights must use the palette colors");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** @return every paragraph/leaf element carrying the given attribute = TRUE */
    private List<Element> elementsWithAttribute(String attrName) {
        List<Element> found = new ArrayList<>();
        Element root = textPane.getStyledDocument().getDefaultRootElement();
        for (int i = 0; i < root.getElementCount(); i++) {
            Element line = root.getElement(i);
            if (hasAttribute(line, attrName)) {
                found.add(line);
            }
            for (int j = 0; j < line.getElementCount(); j++) {
                Element leaf = line.getElement(j);
                if (hasAttribute(leaf, attrName)) {
                    found.add(leaf);
                }
            }
        }
        return found;
    }

    private static boolean hasAttribute(Element element, String attrName) {
        return Boolean.TRUE.equals(element.getAttributes().getAttribute(attrName));
    }

    /** The text covered by an element (the reduced JDK has no {@code Element.getText()}). */
    private String elementText(Element element) {
        try {
            return textPane.getDocument().getText(
                    element.getStartOffset(), element.getEndOffset() - element.getStartOffset());
        } catch (BadLocationException e) {
            throw new AssertionError("element out of document range", e);
        }
    }
}