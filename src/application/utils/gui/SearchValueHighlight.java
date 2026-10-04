package application.utils.gui;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;

/**
 * The single source of truth for painting search-match bands on text values.
 * <p>
 * A Swing document highlighter hands its painter the component's WHOLE
 * content bounds (in this JDK build the match offsets arrive as separate
 * {@code int} parameters), so filling those bounds would paint the entire
 * text box for multi-line value components. Instead the match's own
 * rectangles are computed line by line from the document positions
 * ({@link JTextComponent#modelToView2D(int)}), so only the actually matching
 * text receives the band — the same policy as the console's
 * {@link SearchHighlighter}.
 * </p>
 * <p>
 * Both the node configuration panel's property search and the module logging
 * profile panels' row search build their value bands from this utility, so
 * the paint geometry and the palette colors stay in exactly one place.
 * </p>
 * <p>
 * <h3>Thread Safety</h3>
 * All painting happens on the Swing EDT (the highlighter's paint pass).
 * </p>
 *
 * @see SearchHighlighter
 * @see SearchMatchPanel
 */
public final class SearchValueHighlight {

    /**
     * The soft band painter for every plain search match (the palette's SSOT
     * search color).
     */
    public static final Highlighter.HighlightPainter PAINTER = painter(GuiColors.getSearchMatch());

    /**
     * The strong band painter for the match currently being navigated to
     * (the palette's SSOT active search color).
     */
    public static final Highlighter.HighlightPainter PAINTER_ACTIVE = painter(GuiColors.getSearchActiveMatch());

    private SearchValueHighlight() {
        // static utility
    }

    /**
     * Creates a value-match painter carrying the given band color. The actual
     * painting is done by {@link #paintRange}.
     *
     * @param color the band color (e.g. a {@link GuiColors} search color)
     */
    public static Highlighter.HighlightPainter painter(Color color) {
        return (g, start, end, bounds, component) -> paintRange(g, start, end, component, color);
    }

    /**
     * Paints the value search-match band for the document range
     * {@code [start, end)}.
     * <p>
     * The match's own rectangles are computed line by line from the document
     * positions ({@link JTextComponent#modelToView2D(int)}), so only the
     * actually matching text receives the band — a fully covered line of a
     * multi-line range spans the full component width, the first and last
     * line are clipped to the range itself.
     * </p>
     *
     * @param g         the painter's graphics (cloned internally)
     * @param start     the match start offset (inclusive; a negative value skips the band)
     * @param end       the match end offset (exclusive; clamped to the document length)
     * @param component the highlighted text component (a null component skips the band)
     * @param color     the band color
     */
    public static void paintRange(Graphics g, int start, int end, JTextComponent component, Color color) {
        if (component == null || start < 0) {
            return;
        }
        Document document = component.getDocument();
        end = Math.min(end, document.getLength());
        if (end <= start) {
            return;
        }
        Element root = document.getDefaultRootElement();
        int firstLine = root.getElementIndex(start);
        int lastLine = root.getElementIndex(Math.max(start, end - 1));
        Graphics2D gg = (Graphics2D) g.create();
        gg.setColor(color);
        for (int line = firstLine; line <= lastLine; line++) {
            Element lineElement = root.getElement(line);
            int lineStart = lineElement.getStartOffset();
            int lineEnd = lineElement.getEndOffset();
            int rangeStart = Math.max(start, lineStart);
            int rangeEnd = Math.min(end, lineEnd);
            if (rangeEnd <= rangeStart) {
                continue;
            }
            Rectangle2D rangeShape = viewOf(component, rangeStart);
            if (rangeShape == null) {
                continue;
            }
            int x1 = (int) rangeShape.getX();
            int y = (int) rangeShape.getY();
            int height = Math.max(1, (int) rangeShape.getHeight());
            int x2;
            if (line < lastLine) {
                // A fully covered line of a multi-line range: the band spans
                // the full line width.
                x2 = component.getWidth();
            } else {
                // The view position of the range's end: the next character's
                // start for a mid-line match, or the end of the line's text
                // when the match ends at the line end (the position of the
                // line separator still resolves to that line in the view).
                Rectangle2D endShape = viewOf(component, rangeEnd);
                x2 = endShape == null ? x1 : (int) endShape.getX();
            }
            if (x2 > x1) {
                gg.fillRect(x1, y, x2 - x1, height);
            }
        }
        gg.dispose();
    }

    /**
     * {@link JTextComponent#modelToView2D(int)} for the given offset, or
     * {@code null} when the offset is out of bounds (the document can
     * change concurrently on the EDT).
     */
    public static Rectangle2D viewOf(JTextComponent component, int offset) {
        try {
            return component.modelToView2D(offset);
        } catch (BadLocationException e) {
            return null;
        }
    }
}