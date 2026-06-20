package application.utils.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.AbstractListModel;
import javax.swing.ComboBoxModel;

/**
 * A ComboBoxModel that supports filtering items by text.
 * Supports three matching modes (configurable via constructor):
 * <ul>
 * <li><b>CONTAINS</b> – simple substring match (fast, good for small lists)
 * </li>
 * <li><b>FUZZY</b> – character-sequence match: "abd" matches "aBcDeF" as long
 * as characters appear in order (IntelliJ / VS Code style)</li>
 * <li><b>SMART</b> (default) – prefix boost + contains fallback, then fuzzy for
 * anything that still didn't match. Items are scored and sorted so the
 * "obvious" pick appears at the top.</li>
 * </ul>
 *
 * @param <E> the type of elements in this model
 */
public class FilteredComboBoxModel<E> extends AbstractListModel<E> implements ComboBoxModel<E> {

    /** Strategy that decides which items pass the filter and in what order. */
    public enum MatchMode {
        /** Simple case-insensitive substring match. */
        CONTAINS,
        /** Character-sequence (IntelliJ-style) fuzzy match. */
        FUZZY,
        /** Prefix boost → contains → fuzzy fallback with scoring. */
        SMART
    }

    private final List<E> allItems;
    private final List<E> filteredItems;
    private E selectedItem;
    private String filter = "";
    private final MatchMode matchMode;

    // ── Constructors ───────────────────────────────────────────────────

    /** Creates with {@link MatchMode#SMART}. */
    public FilteredComboBoxModel(List<E> items) {
        this(items, MatchMode.SMART);
    }

    /** Creates with the specified match mode. */
    public FilteredComboBoxModel(List<E> items, MatchMode matchMode) {
        this.allItems = new ArrayList<>(items);
        this.filteredItems = new ArrayList<>(this.allItems);
        this.matchMode = matchMode != null ? matchMode : MatchMode.SMART;
    }

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Sets the current text filter and refreshes the visible item list.
     *
     * @param filterText may be {@code null} (treated as empty string)
     */
    public void setFilter(String filterText) {
        this.filter = filterText != null ? filterText.toLowerCase() : "";
        updateFilteredItems();

        // Preserve user-typed text in editable combos. Only clear a selection when
        // it is an actual model item that is no longer visible after filtering.
        if (selectedItem != null && allItems.contains(selectedItem) && !filteredItems.contains(selectedItem)) {
            selectedItem = null;
        }

        // Notify listeners with a real index range so the combo box editor
        // can refresh the popup and preserve the caret correctly.
        int size = filteredItems.size();
        if (size > 0) {
            fireContentsChanged(this, 0, size - 1);
        } else {
            fireContentsChanged(this, 0, 0);
        }
    }

    /** Returns all original items (unfiltered). */
    public List<E> getAllItems() {
        return Collections.unmodifiableList(allItems);
    }

    /** Returns the current filter text. */
    public String getFilter() {
        return filter;
    }

    // ── Filtering logic ────────────────────────────────────────────────

    private void updateFilteredItems() {
        filteredItems.clear();

        if (filter.isEmpty()) {
            filteredItems.addAll(allItems);
            return;
        }

        switch (matchMode) {
            case CONTAINS:
                filterContains();
                break;
            case FUZZY:
                filterFuzzy();
                break;
            case SMART:
                filterSmart();
                break;
            default:
                filterContains();
        }
    }

    /** Simple substring match. */
    private void filterContains() {
        for (E item : allItems) {
            if (item != null && item.toString().toLowerCase().contains(filter)) {
                filteredItems.add(item);
            }
        }
    }

    /** IntelliJ-style fuzzy: each char of query must appear in order. */
    private void filterFuzzy() {
        for (E item : allItems) {
            if (item != null && isFuzzyMatch(item.toString().toLowerCase(), filter)) {
                filteredItems.add(item);
            }
        }
    }

    /** Smart: prefix boost → contains → fuzzy, with scoring. */
    private void filterSmart() {
        List<E> prefixMatches = new ArrayList<>();
        List<E> containsMatches = new ArrayList<>();
        List<E> fuzzyMatches = new ArrayList<>();

        int firstSpacePos = filter.indexOf(' ');
        String primaryToken = firstSpacePos >= 0 ? filter.substring(0, firstSpacePos) : filter;

        for (E item : allItems) {
            if (item == null)
                continue;

            String lower = item.toString().toLowerCase();

            // Prefix match gets highest priority
            if (lower.startsWith(primaryToken)) {
                prefixMatches.add(item);
            } else if (lower.contains(filter)) {
                // Contains match
                containsMatches.add(item);
            } else if (isFuzzyMatch(lower, filter)) {
                // Fuzzy match as fallback
                fuzzyMatches.add(item);
            }
        }

        // Add in priority order: prefix → contains → fuzzy
        filteredItems.addAll(prefixMatches);
        for (E item : containsMatches) {
            if (!filteredItems.contains(item)) {
                filteredItems.add(item);
            }
        }
        for (E item : fuzzyMatches) {
            if (!filteredItems.contains(item)) {
                filteredItems.add(item);
            }
        }
    }

    /**
     * Checks if the query matches the text in fuzzy mode.
     * Each character of the query must appear in order in the text.
     */
    private static boolean isFuzzyMatch(String text, String query) {
        if (query.isEmpty()) {
            return true;
        }

        int queryIndex = 0;
        for (int i = 0; i < text.length() && queryIndex < query.length(); i++) {
            if (text.charAt(i) == query.charAt(queryIndex)) {
                queryIndex++;
            }
        }

        return queryIndex == query.length();
    }

    // ── ComboBoxModel interface methods ────────────────────────────────

    public void resetFilter() {
        setFilter("");
    }

    @Override
    public int getSize() {
        return filteredItems.size();
    }

    @Override
    public E getElementAt(int index) {
        if (index < 0 || index >= filteredItems.size()) {
            return null;
        }
        return filteredItems.get(index);
    }

    @Override
    public void setSelectedItem(Object anItem) {
        this.selectedItem = (E) anItem;
    }

    @Override
    public Object getSelectedItem() {
        return selectedItem;
    }
}