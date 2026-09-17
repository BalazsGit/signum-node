package application.utils.gui.console;

import java.awt.Component;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.JTextPane;
import javax.swing.JViewport;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import application.utils.gui.GuiColors;

/**
 * Live "find in console" highlighting for the unified console panel.
 * <p>
 * The search does NOT filter the console; it highlights every occurrence of
 * the query text in the styled document. Every match is painted immediately
 * with a soft background; the match the user is currently navigating to
 * (chevron up/down, first match after a query change) gets a stronger
 * highlight and is scrolled into view.
 * </p>
 * <p>
 * Highlight colors are resolved from the application color palette
 * ({@code gui.search.match} / {@code gui.search.active.match} keys — see
 * {@link GuiColors#getSearchMatch()} / {@link GuiColors#getSearchActiveMatch()}),
 * so they adapt to the active theme (light/dark) and can be fine-tuned per
 * theme or through Look-and-Feel profiles.
 * </p>
 * <p>
 * Matching is case-insensitive literal (plain text) — no regex, no flags.
 * A cap ({@code MAX_MATCHES}) keeps pathological queries bounded.
 * All methods must be called on the Swing EDT.
 * </p>
 */
public final class SearchHighlighter {

    /** Custom attribute key (name) marking a (non-active) search match */
    public static final String MATCH_ATTRIBUTE = "console.search.match";

    /** Custom attribute key (name) marking the currently active (navigated) search match */
    public static final String CURRENT_MATCH_ATTRIBUTE = "console.search.currentMatch";

    /** Cap to keep highlighting bounded on pathological inputs (e.g. single char) */
    private static final int MAX_MATCHES = 10_000;

    private final JTextPane textPane;
    private final StyledDocument document;

    /** Match ranges as {start, end} offsets in the document */
    private final List<int[]> matches = new ArrayList<>();
    private int currentIndex = -1;
    private String lastQuery = null;

    /**
     * Creates a highlighter for the given console text pane.
     *
     * @param textPane the JTextPane to highlight (never null)
     */
    public SearchHighlighter(JTextPane textPane) {
        if (textPane == null) {
            throw new NullPointerException("textPane must not be null");
        }
        this.textPane = textPane;
        this.document = (StyledDocument) textPane.getDocument();
    }

    /**
     * Runs the search and highlights all matches.
     * A null or blank query clears all highlights.
     *
     * @param query the query text (case-insensitive literal)
     * @return the number of matches found
     */
    public int applySearch(String query) {
        clearHighlights();
        lastQuery = query;
        if (query == null) {
            query = "";
        }
        query = query.trim();
        if (query.isEmpty()) {
            return 0;
        }
        String text;
        try {
            text = document.getText(0, document.getLength());
        } catch (BadLocationException e) {
            return 0;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        String needle = query.toLowerCase(Locale.ROOT);
        int from = 0;
        while (matches.size() < MAX_MATCHES) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) {
                break;
            }
            matches.add(new int[]{idx, idx + needle.length()});
            from = idx + needle.length();
        }
        if (matches.isEmpty()) {
            currentIndex = -1;
            return 0;
        }
        // Paint every match immediately: all with the soft highlight, the
        // first one (the new navigation anchor) with the strong highlight.
        for (int i = 0; i < matches.size(); i++) {
            applyMatchHighlight(i, i == 0);
        }
        currentIndex = 0;
        return matches.size();
    }

    /**
     * Re-applies the last search (e.g. after new log lines were appended).
     * Keeps the same active match position when it still exists.
     * No-op when there is no active (non-blank) search.
     */
    public void reapply() {
        if (lastQuery == null || lastQuery.trim().isEmpty()) {
            return;
        }
        int previous = currentIndex;
        int count = applySearch(lastQuery);
        if (count == 0) {
            return;
        }
        if (previous >= 0 && previous < count) {
            moveCurrent(previous, false);
        }
    }

    /** @return the number of matches found by the last search */
    public int matchCount() {
        return matches.size();
    }

    /** @return the index of the active match, or -1 when there is none */
    public int currentIndex() {
        return currentIndex;
    }

    /**
     * @return the document offsets {@code {start, end}} of the active match,
     *         or null when there is no active match.
     */
    public int[] currentMatchRange() {
        if (currentIndex < 0 || currentIndex >= matches.size()) {
            return null;
        }
        return matches.get(currentIndex);
    }

    /**
     * Moves to the next match (wrapping around to the first) and scrolls to it.
     *
     * @return true if a match became active, false if there are no matches
     */
    public boolean navigateNext() {
        if (matches.isEmpty()) {
            return false;
        }
        moveCurrent(currentIndex + 1, true);
        return true;
    }

    /**
     * Moves to the previous match (wrapping around to the last) and scrolls to it.
     *
     * @return true if a match became active, false if there are no matches
     */
    public boolean navigatePrevious() {
        if (matches.isEmpty()) {
            return false;
        }
        moveCurrent(currentIndex - 1, true);
        return true;
    }

    /** Removes all highlights and forgets the last query. */
    public void clear() {
        clearHighlights();
        lastQuery = null;
    }

    // ── Internals ────────────────────────────────────────────────────────

    /**
     * Moves the active (strong) highlight to {@code target} (wrapping) and
     * optionally scrolls the match into view.
     */
    private void moveCurrent(int target, boolean scroll) {
        int count = matches.size();
        int index = ((target % count) + count) % count;
        if (currentIndex >= 0 && currentIndex < count) {
            applyMatchHighlight(currentIndex, false);
        }
        currentIndex = index;
        applyMatchHighlight(index, true);
        if (scroll) {
            scrollToMatch(index);
        }
    }

    /** Applies the match (soft) or active (strong) highlight to a range. */
    private void applyMatchHighlight(int index, boolean current) {
        int[] range = matches.get(index);
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        attrs.addAttribute(MATCH_ATTRIBUTE, Boolean.TRUE);
        if (current) {
            attrs.addAttribute(CURRENT_MATCH_ATTRIBUTE, Boolean.TRUE);
            StyleConstants.setBackground(attrs, GuiColors.getSearchActiveMatch());
        } else {
            StyleConstants.setBackground(attrs, GuiColors.getSearchMatch());
        }
        // replace=false: merge — keeps the per-line foreground color intact
        document.setCharacterAttributes(range[0], range[1] - range[0], attrs, false);
    }

    /** Clears all search highlights from the document and resets the match list/index. */
    private void clearHighlights() {
        clearSearchAttributes();
        matches.clear();
        currentIndex = -1;
    }

    /**
     * Removes only our search attributes (plus the background we introduced),
     * preserving each line's own styling (e.g. level colors).
     * <p>
     * A whole-document sweep is deliberately used instead of clearing the
     * recorded match offsets: after the console trims its oldest lines, stored
     * offsets shift and would clear the wrong ranges while leaving stale
     * highlights behind. The document is bounded (console maxLines), so the
     * sweep stays cheap.
     * </p>
     */
    private void clearSearchAttributes() {
        Element root = document.getDefaultRootElement();
        int lineCount = root.getElementCount();
        for (int i = 0; i < lineCount; i++) {
            Element line = root.getElement(i);
            clearElement(line);
            int childCount = line.getElementCount();
            for (int j = 0; j < childCount; j++) {
                clearElement(line.getElement(j));
            }
        }
    }

    /** Strips our search attributes from a single element, if it carries any. */
    private void clearElement(Element element) {
        AttributeSet attrs = element.getAttributes();
        if (attrs.getAttribute(MATCH_ATTRIBUTE) == null
                && attrs.getAttribute(CURRENT_MATCH_ATTRIBUTE) == null) {
            return; // nothing of ours here
        }
        // replace=true: the element's attribute set is REPLACED by a copy of
        // itself minus our search attributes (and the background we introduced).
        // A merge (replace=false) would NOT remove the attributes — it only
        // overrides the ones present in the given set — and this reduced JDK
        // build does not honor null-valued removals on merge either.
        SimpleAttributeSet set = new SimpleAttributeSet(attrs);
        set.removeAttribute(MATCH_ATTRIBUTE);
        set.removeAttribute(CURRENT_MATCH_ATTRIBUTE);
        set.removeAttribute(StyleConstants.Background);
        int start = element.getStartOffset();
        int length = element.getEndOffset() - start;
        if (length <= 0) {
            return;
        }
        try {
            document.setCharacterAttributes(start, length, set, true);
        } catch (RuntimeException e) {
            // Document changed concurrently; skip this span
        }
    }

    /** Scrolls the given match into view in the text pane. */
    private void scrollToMatch(int index) {
        int[] range = matches.get(index);
        try {
            // modelToView2D returns the match's rectangle in the text pane's
            // coordinate space; the enclosing JViewport scrolls it into view.
            Rectangle2D rect = textPane.modelToView2D(range[0]);
            if (rect != null) {
                Component parent = textPane.getParent();
                if (parent instanceof JViewport) {
                    ((JViewport) parent).scrollRectToVisible(rect.getBounds());
                }
            }
        } catch (Exception e) {
            // Headless or layout not ready yet — the highlight itself is applied
        }
    }
}