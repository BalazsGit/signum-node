package application.utils.gui;

import java.awt.Color;
import java.awt.Component;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListCellRenderer;

/**
 * The list-cell renderer that shows the search-match band behind the selected
 * value of a non-editable combo box (a non-editable combo has no text
 * component a document highlight could live in, so its value is rendered by
 * this band-painting label instead of the LAF's plain renderer).
 * <p>
 * When an original renderer is supplied, its foreground, background, font and
 * opacity are preserved so state-aware text colors and selection styling stay
 * intact. The {@link SearchMatchLabel} paints the search band below the text.
 * The band is anchored to a query: on every (re)render its range is re-resolved
 * in the item's own text, so the band shows on the selected value and — while
 * the popup is open — on every popup item containing the query, and on nothing
 * else.
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
    private ListCellRenderer<Object> delegateRenderer;

    /**
     * Keeps the combo's original renderer responsible for value-state colors
     * and selected-item styling while this renderer paints the search band.
     */
    @SuppressWarnings("unchecked")
    public <T> void setDelegateRenderer(ListCellRenderer<T> renderer) {
        delegateRenderer = (ListCellRenderer<Object>) (ListCellRenderer<?>) renderer;
    }

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
        if (delegateRenderer != null) {
            Component styled = delegateRenderer.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus);
            setForeground(styled.getForeground());
            setBackground(styled.getBackground());
            setFont(styled.getFont());
            setEnabled(styled.isEnabled());
            if (styled instanceof JComponent styledComponent) {
                setOpaque(styledComponent.isOpaque());
            }
            if (styled instanceof JLabel styledLabel) {
                setIcon(styledLabel.getIcon());
            }
        } else {
            setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setFont(list.getFont());
            setOpaque(false);
        }
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