package application.utils.gui;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;

import javax.swing.JLabel;

/**
 * A {@link JLabel} that can paint a search-match highlight band behind a
 * character range of its (plain-text) text.
 * <p>
 * By default the band is painted with the application palette's
 * search-match color ({@link GuiColors#getSearchMatch()} — the same SSOT
 * color the console "find" feature uses, see
 * {@code application.utils.gui.console.SearchHighlighter}), directly with
 * {@link Graphics}, so it works with any color (including the palette's
 * semi-transparent one) under any Look-and-Feel. This is unlike an HTML
 * {@code <span style="background-color:...">}, whose 8-digit hex (alpha)
 * form the Swing HTML renderer does not reliably support.
 * </p>
 * <p>
 * The label is non-opaque and the band is painted BEFORE the (LAF's) label
 * UI draws the text (the UI skips its background fill for non-opaque
 * labels), so the semi-transparent overlay stays BEHIND the glyphs and the
 * text remains readable — identical behavior to the console highlighter.
 * </p>
 * <p>
 * All methods must be called on the Swing EDT.
 * </p>
 */
public class SearchMatchLabel extends JLabel {

    /** Zero-based start offset (inclusive) of the highlighted range; -1 = no highlight. */
    private int highlightStart = -1;

    /** Length of the highlighted range. */
    private int highlightLength = 0;

    /** Explicit band color, or {@code null} for the palette's default. */
    private Color highlightColor;

    public SearchMatchLabel() {
        super();
        // Non-opaque: the parent's background shows through, and the
        // highlight band can be painted below the text (see paintComponent).
        setOpaque(false);
    }

    public SearchMatchLabel(String text) {
        this();
        setText(text);
    }

    /**
     * Highlights the character range {@code [start, start + length)} of the
     * current text with the palette's search-match color.
     *
     * @param start  zero-based start offset of the range
     * @param length number of characters to highlight
     */
    public void setHighlightRange(int start, int length) {
        this.highlightStart = start;
        this.highlightLength = length;
        repaint();
    }

    /** Removes any highlight band. */
    public void clearHighlight() {
        this.highlightStart = -1;
        this.highlightLength = 0;
        repaint();
    }

    /**
     * Overrides the band color.
     *
     * @param color the band color, or {@code null} for the palette's default
     *              search-match color
     */
    public void setHighlightColor(Color color) {
        this.highlightColor = color;
        repaint();
    }

    /** @return true if a highlight range is currently set */
    public boolean hasHighlight() {
        return highlightStart >= 0 && highlightLength > 0;
    }

    @Override
    protected void paintComponent(Graphics g) {
        // The label is non-opaque, so the LAF's UI skips its background
        // fill: paint the band first, then let the (LAF's) label UI draw
        // the text on top of it.
        if (hasHighlight()) {
            paintHighlightBand(g);
        }
        super.paintComponent(g);
    }

    private void paintHighlightBand(Graphics g) {
        String text = getText();
        if (text == null || text.isEmpty() || highlightStart >= text.length()) {
            return;
        }
        int end = Math.min(highlightStart + highlightLength, text.length());
        if (end <= highlightStart) {
            return;
        }
        FontMetrics fm = g.getFontMetrics();
        Insets in = getInsets();
        int textWidth = fm.stringWidth(text);
        int labelWidth = getWidth() - in.left - in.right;
        // Text start X — mirrors the standard label UI alignment math
        // (horizontal: 0 = left, 1 = center, 2 = right).
        int align = getHorizontalAlignment();
        int textX;
        if (align == 1) {
            textX = in.left + (labelWidth - textWidth) / 2;
        } else if (align == 2) {
            textX = in.left + labelWidth - textWidth;
        } else {
            textX = in.left;
        }
        // Text top Y — mirrors the standard label UI vertical alignment
        // (vertical: 0 = top, 1 = center, 2 = bottom).
        int viewHeight = getHeight() - in.top - in.bottom;
        int valign = getVerticalAlignment();
        int textY;
        if (valign == 2) {
            textY = in.top + viewHeight - fm.getHeight();
        } else if (valign == 0) {
            textY = in.top;
        } else {
            textY = in.top + (viewHeight - fm.getHeight()) / 2;
        }
        char[] chars = text.toCharArray();
        int bandX = textX + fm.charsWidth(chars, 0, highlightStart);
        int bandWidth = Math.max(1, fm.charsWidth(chars, highlightStart, end - highlightStart));
        g.setColor(resolveHighlightColor());
        g.fillRect(bandX, textY, bandWidth, fm.getHeight());
    }

    /** @return the explicit band color, or the palette's default search-match color */
    private Color resolveHighlightColor() {
        return highlightColor != null ? highlightColor : GuiColors.getSearchMatch();
    }
}