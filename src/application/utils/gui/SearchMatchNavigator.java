package application.utils.gui;

/**
 * The small, parameterizable match-navigation state machine behind the
 * unified {@link SearchMatchPanel} behavior: an ordered match set, an
 * active-match index that wraps on next/previous, and the
 * {@code "current/total"} indicator text.
 * <p>
 * Ownership semantics (the same rule the node configuration panel's
 * search uses): the owning panel recomputes its match set whenever the
 * query changes and reports it via {@link #setMatchCount(int)} — a
 * non-empty set activates the FIRST match, an empty set deactivates.
 * {@link #navigate(boolean)} then moves the active match by one,
 * wrapping around at both ends.
 * <p>
 * Pure logic (no Swing) — fully unit-testable.
 *
 * @see SearchMatchPanel
 */
public final class SearchMatchNavigator {

    /** The size of the current match set (0 = the query has no match). */
    private int matchCount;
    /** Zero-based index of the active match; -1 = none. */
    private int activeIndex = -1;

    /**
     * Reports the size of the (re)computed match set after a query
     * change: a non-empty set activates its first match, an empty set
     * deactivates.
     *
     * @param count the number of matches (a negative value is clamped to 0)
     */
    public void setMatchCount(int count) {
        this.matchCount = Math.max(0, count);
        this.activeIndex = this.matchCount > 0 ? 0 : -1;
    }

    /**
     * Moves the active match one step (wrapping around at both ends).
     * A no-op while the match set is empty.
     *
     * @param next {@code true} = the next match, {@code false} = the previous
     * @return {@code true} when the active index changed
     */
    public boolean navigate(boolean next) {
        if (matchCount == 0) {
            return false;
        }
        int old = activeIndex;
        activeIndex = next
                ? (activeIndex + 1) % matchCount
                : (activeIndex - 1 + matchCount) % matchCount;
        return activeIndex != old;
    }

    /** @return {@code true} while the current query has at least one match */
    public boolean hasMatches() {
        return matchCount > 0;
    }

    /** @return the size of the current match set */
    public int matchCount() {
        return matchCount;
    }

    /** @return the zero-based index of the active match, or -1 when there is none */
    public int activeIndex() {
        return activeIndex;
    }

    /**
     * @return the "current/total" indicator text (e.g. {@code "1/23"}), or
     *         {@code ""} while nothing is active (the panels map the empty
     *         string to "hide the counter")
     */
    public String indicator() {
        return hasMatches() ? (activeIndex + 1) + "/" + matchCount : "";
    }
}