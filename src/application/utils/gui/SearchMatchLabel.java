package application.utils.gui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

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

    /** Zero-based start offset (inclusive) of the highlighted range in the KEY line; -1 = none. */
    private int keyHighlightStart = -1;

    /** Length of the highlighted range in the key line. */
    private int keyHighlightLength = 0;

    /** Explicit band color, or {@code null} for the palette's default. */
    private Color highlightColor;

    /**
     * Multiple search-match bands on the NAME text, each with its own color
     * (a superset of the single {@link #highlightStart}/{@link #highlightLength}
     * band — e.g. the soft palette color on every plain match and the strong
     * one on the navigated match).
     */
    private final List<HighlightBand> highlightBands = new ArrayList<>();

    /**
     * The optional property key (e.g. {@code API.Port}) shown on the same
     * line, directly after the (human-readable) name, wrapped in angle
     * brackets and in the unified key style. {@code null} or empty = plain
     * name-only label (legacy behavior).
     */
    private String keyText;

    /** Horizontal gap (px) between the name text and the bracketed key text. */
    private static final int KEY_TEXT_GAP = 8;

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
     * One search-match band on the NAME text with its own color.
     *
     * @param start  zero-based start offset of the range (inclusive)
     * @param length number of characters in the range
     * @param color  the band color, or {@code null} for the palette's
     *               default search-match color
     */
    public record HighlightBand(int start, int length, Color color) {
    }

    /**
     * Sets the search-match bands painted on the NAME text (each band in its
     * own color — e.g. the soft palette color on every plain match and the
     * strong one on the navigated match). Replaces every previous name-line
     * band, including a single {@link #setHighlightRange(int, int)} one.
     *
     * @param bands the bands to paint (null treated as empty)
     */
    public void setHighlightBands(List<HighlightBand> bands) {
        highlightBands.clear();
        if (bands != null) {
            highlightBands.addAll(bands);
        }
        this.highlightStart = -1;
        this.highlightLength = 0;
        repaint();
    }

    /** Removes every name-line band (the band list and the single range). */
    public void clearHighlightBands() {
        highlightBands.clear();
        this.highlightStart = -1;
        this.highlightLength = 0;
        repaint();
    }

    /** @return the current name-line bands (a copy; empty when none) */
    public List<HighlightBand> getHighlightBands() {
        return List.copyOf(highlightBands);
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

    /** Removes any highlight band (the band list and the single range). */
    public void clearHighlight() {
        this.highlightStart = -1;
        this.highlightLength = 0;
        highlightBands.clear();
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

    /**
     * Highlights the character range {@code [start, start + length)} of the
     * KEY line (the second line) with the same band color as the name line —
     * used when a search query matches the property key rather than the name.
     *
     * @param start  zero-based start offset of the range within the key text
     * @param length number of characters to highlight
     */
    public void setKeyHighlightRange(int start, int length) {
        this.keyHighlightStart = start;
        this.keyHighlightLength = length;
        repaint();
    }

    /** Removes the highlight band from the key line. */
    public void clearKeyHighlight() {
        this.keyHighlightStart = -1;
        this.keyHighlightLength = 0;
        repaint();
    }

    /** @return true if a highlight range is set on the key line */
    public boolean hasKeyHighlight() {
        return keyHighlightStart >= 0 && keyHighlightLength > 0;
    }

    /**
     * Sets the property key rendered on the same line, directly after the
     * (name) text, wrapped in angle brackets (e.g. {@code <API.Port>}) in
     * the unified key style (same size as the name, faint). Pass {@code null} or an empty
     * string to revert to the plain name-only label.
     *
     * @param keyText the exact property key (e.g. {@code API.Port}), or null
     */
    public void setKeyText(String keyText) {
        this.keyText = keyText;
        revalidate();
        repaint();
    }

    /**
     * Factory for the unified "name + exact property key" label (the key
     * right after the name on the same line). Convenience for compact layouts
     * where the label is created and configured in a single expression.
     *
     * @param text    the (human-readable) name
     * @param keyText the exact property key (e.g. {@code DB.Username})
     */
    public static SearchMatchLabel withKeyText(String text, String keyText) {
        SearchMatchLabel label = new SearchMatchLabel(text);
        label.setKeyText(keyText);
        return label;
    }

    /** @return the current property key line, or {@code null} when unset */
    public String getKeyText() {
        return keyText;
    }

    /** @return true when a key line is set and will be painted */
    public boolean hasKeyText() {
        return keyText != null && !keyText.isEmpty();
    }

    /**
     * The unified key font: the label's base font — the same size and style
     * as the name (only the color differs, see {@link #getKeyColor()}). The
     * key is deliberately NOT italic: slanted key text is hard to read.
     * Kept here so every property key in the app shares one style definition.
     */
    public Font getKeyFont() {
        return getFont();
    }

    /**
     * The unified key-line color: the application palette's faint text color
     * (the same one the configuration file path label uses).
     */
    public Color getKeyColor() {
        return GuiColors.getFaintText();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension base = super.getPreferredSize();
        if (!hasKeyText()) {
            return base;
        }
        FontMetrics baseFm = getFontMetrics(getFont());
        FontMetrics keyFm = getFontMetrics(getKeyFont());
        Insets in = getInsets();
        // Same line: the label must be wide enough for the name text, the
        // gap and the bracketed key text (e.g. "<API.Port>"); the key font is
        // the base font's size, so the height stays the base line height.
        int nameWidth = baseFm.stringWidth(getText() == null ? "" : getText());
        int width = in.left + in.right + nameWidth + KEY_TEXT_GAP + keyFm.stringWidth("<" + keyText + ">");
        return new Dimension(Math.max(base.width, width), base.height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        // The label is non-opaque, so the LAF's UI skips its background
        // fill: paint the band(s) first, then let the (LAF's) label UI draw
        // the text on top of them.
        if (hasHighlight()) {
            paintHighlightBand(g, false);
        }
        for (HighlightBand band : highlightBands) {
            paintBand(g, band);
        }
        if (hasKeyHighlight()) {
            paintHighlightBand(g, true);
        }
        super.paintComponent(g);
        if (hasKeyText()) {
            paintKeyText(g);
        }
    }

    /**
     * Top Y of the (name) text's font box, mirroring the standard label UI
     * vertical alignment math (TOP / CENTER / BOTTOM). The key text is
     * vertically centered inside this box (see {@link #paintKeyText}).
     * <p>
     * The alignment is compared against the NAMED constants (never hardcoded
     * numbers): the SwingConstants values differ between JDK versions
     * (on this toolchain's JDK CENTER=0/TOP=1/BOTTOM=3, on older JDKs
     * CENTER=1/TOP=0/BOTTOM=2), so a magic number would silently take the
     * wrong branch and misplace the band (e.g. top of a taller FlatLaf
     * table cell while the LAF centers the text).
     */
    private int nameTextTopY(Graphics g) {
        FontMetrics baseFm = g.getFontMetrics(getFont());
        Insets in = getInsets();
        int viewHeight = getHeight() - in.top - in.bottom;
        int valign = getVerticalAlignment();
        if (valign == BOTTOM) {
            return in.top + viewHeight - baseFm.getHeight();
        }
        if (valign == TOP) {
            return in.top;
        }
        return in.top + (viewHeight - baseFm.getHeight()) / 2;
    }

    /**
     * Left X of the (name) text, mirroring the standard label UI horizontal
     * alignment math (LEFT/LEADING, CENTER, RIGHT/TRAILING). The key text
     * starts after this text plus {@link #KEY_TEXT_GAP} (same line).
     * <p>
     * As in {@link #nameTextTopY}, the alignment is compared against the
     * NAMED constants - the SwingConstants values differ between JDK
     * versions, so a magic number would misplace the band.
     */
    private int nameTextX(Graphics g) {
        FontMetrics baseFm = g.getFontMetrics(getFont());
        Insets in = getInsets();
        int textWidth = baseFm.stringWidth(getText() == null ? "" : getText());
        int labelWidth = getWidth() - in.left - in.right;
        int align = getHorizontalAlignment();
        if (align == CENTER) {
            return in.left + (labelWidth - textWidth) / 2;
        }
        if (align == RIGHT || align == TRAILING) {
            return in.left + labelWidth - textWidth;
        }
        return in.left;
    }

    /**
     * Left X of the key text: directly after the (name) text on the same
     * line, past the {@link #KEY_TEXT_GAP} gap and the opening bracket.
     */
    private int keyTextX(Graphics g) {
        FontMetrics baseFm = g.getFontMetrics(getFont());
        FontMetrics keyFm = g.getFontMetrics(getKeyFont());
        return nameTextX(g) + baseFm.stringWidth(getText() == null ? "" : getText()) + KEY_TEXT_GAP
                + keyFm.stringWidth("<");
    }

    /**
     * Top Y of the key text's font box: vertically centered inside the (name)
     * font box (a zero offset while the key font matches the base size).
     */
    private int keyTextTopY(Graphics g) {
        FontMetrics baseFm = g.getFontMetrics(getFont());
        FontMetrics keyFm = g.getFontMetrics(getKeyFont());
        return nameTextTopY(g) + (baseFm.getHeight() - keyFm.getHeight()) / 2;
    }

    /**
     * Paints one band of the {@link #highlightBands} list on the NAME text
     * (the same geometry as {@link #paintHighlightBand}), with the band's
     * own color (the palette's default search-match color when the band
     * carries none).
     */
    private void paintBand(Graphics g, HighlightBand band) {
        String text = getText();
        if (text == null || text.isEmpty() || band.start() < 0 || band.start() >= text.length()) {
            return;
        }
        int end = Math.min(band.start() + band.length(), text.length());
        if (end <= band.start()) {
            return;
        }
        FontMetrics fm = g.getFontMetrics(getFont());
        int textY = nameTextTopY(g);
        int textX = nameTextX(g);
        char[] chars = text.toCharArray();
        int bandX = textX + fm.charsWidth(chars, 0, band.start());
        int bandWidth = Math.max(1, fm.charsWidth(chars, band.start(), end - band.start()));
        g.setColor(band.color() != null ? band.color() : GuiColors.getSearchMatch());
        g.fillRect(bandX, textY, bandWidth, fm.getHeight());
    }

    /**
     * Paints the search-match band behind the given character range of the
     * NAME text ({@code onKeyLine == false}) or the KEY text
     * ({@code onKeyLine == true}). Always measured with the targeted text's
     * own font (not the caller's current Graphics font), so the band tracks
     * the text's geometry no matter what font state the surrounding component
     * left on the g.
     */
    private void paintHighlightBand(Graphics g, boolean onKeyLine) {
        String text = onKeyLine ? keyText : getText();
        int start = onKeyLine ? keyHighlightStart : highlightStart;
        int length = onKeyLine ? keyHighlightLength : highlightLength;
        if (text == null || text.isEmpty() || start < 0 || start >= text.length()) {
            return;
        }
        int end = Math.min(start + length, text.length());
        if (end <= start) {
            return;
        }
        FontMetrics fm = g.getFontMetrics(onKeyLine ? getKeyFont() : getFont());
        int textY = onKeyLine ? keyTextTopY(g) : nameTextTopY(g);
        int textX = onKeyLine ? keyTextX(g) : nameTextX(g);
        char[] chars = text.toCharArray();
        int bandX = textX + fm.charsWidth(chars, 0, start);
        int bandWidth = Math.max(1, fm.charsWidth(chars, start, end - start));
        g.setColor(resolveHighlightColor());
        g.fillRect(bandX, textY, bandWidth, fm.getHeight());
    }

    /**
     * Paints the property-key text on the same line, directly after the
     * (name) text with a {@link #KEY_TEXT_GAP}px gap, wrapped in angle
     * brackets (e.g. {@code <API.Port>}), vertically centered inside the
     * name font box and in the unified key style
     * ({@link #getKeyFont()} / {@link #getKeyColor()}). The name text itself
     * is painted by the LAF, so only the bracketed key is drawn here.
     */
    private void paintKeyText(Graphics g) {
        FontMetrics keyFm = g.getFontMetrics(getKeyFont());
        int baselineY = keyTextTopY(g) + keyFm.getAscent();
        g.setFont(getKeyFont());
        g.setColor(getKeyColor());
        int keyStartX = keyTextX(g);
        g.drawString("<", keyStartX - keyFm.stringWidth("<"), baselineY);
        g.drawString(keyText, keyStartX, baselineY);
        g.drawString(">", keyStartX + keyFm.stringWidth(keyText), baselineY);
    }

    /** @return the explicit band color, or the palette's default search-match color */
    private Color resolveHighlightColor() {
        return highlightColor != null ? highlightColor : GuiColors.getSearchMatch();
    }
}