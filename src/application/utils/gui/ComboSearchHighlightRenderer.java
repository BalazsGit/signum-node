package application.utils.gui;

import java.awt.Color;
import java.awt.Component;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JList;
import javax.swing.ListCellRenderer;

/**
 * The list-cell renderer that shows the search-match band behind the selected
 * value of a non-editable combo box (a non-editable combo has no text
 * component a document highlight could live in, so its value is rendered by
 * this band-painting label instead of the LAF's plain renderer).
 * <p>
 * The renderer stays NON-opaque: the combo's own (opaque) background is
 * painted by the LAF underneath it, and the {@link SearchMatchLabel} band is
 * painted below the text. The band is anchored to a query: on every
 * (re)render its range is re-resolved in the item's own text, so the band
 * shows on the selected value and — while the popup is open — on every popup
 * item containing the query, and on nothing else.
 * </p>
 * <p>
 * Shared by every panel whose live search matches combo values (the node
 * configuration panel and the module logging profile panel). All methods must
 * be called on the Swing EDT.
 * </p>
 */
public class ComboSearchHighlightRenderer extends SearchMatchLabel
        implements ListCellRenderer<Object> {
    /** The query the band is anchored to (empty = no band). */
    private String bandQuery;
    /** The band color for the anchored query. */
    private Color bandColor;

    /** Anchors the band to the given query and color (an empty query clears it). */
    public void setBand(String query, Color color) {
        this.bandQuery = query == null ? "" : query;
        this.bandColor = color;
    }

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index,
            boolean isSelected, boolean cellHasFocus) {
        String text = value == null ? "" : value.toString();
        setText(text);
        setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
        setFont(list.getFont());
        // The reduced JDK's JList has no cell-renderer border accessor:
        // use the plain empty border (the LAF's default).
        setBorder(BorderFactory.createEmptyBorder());
        if (!bandQuery.isEmpty()) {
            int idx = text.toLowerCase(Locale.ROOT).indexOf(bandQuery.toLowerCase(Locale.ROOT));
            if (idx >= 0) {
                setHighlightRange(idx, bandQuery.length());
                setHighlightColor(bandColor);
            } else {
                clearHighlight();
            }
        }
        return this;
    }
}