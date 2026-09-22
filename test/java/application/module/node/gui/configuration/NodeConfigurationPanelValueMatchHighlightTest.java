package application.module.node.gui.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.text.Highlighter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pixel-level regression test for the value search-match band of
 * {@link NodeConfigurationPanel}: when a multi-line value box is searched
 * (property values on their own lines) ONLY the matching text itself must be
 * highlighted — a reported bug was that the entire text box content got
 * painted.
 * <p>
 * The band is painted into an opaque white {@code TYPE_INT_RGB} image with an
 * opaque, glyph-distinct band color and a monospaced font, so the paint
 * geometry can be asserted pixel by pixel through
 * {@link JComponent#modelToView2D(int)} positions.
 * </p>
 */
@DisplayName("NodeConfigurationPanel value-match highlight tests")
class NodeConfigurationPanelValueMatchHighlightTest {

    /** Opaque, distinct from the white background and the black glyphs. */
    private static final Color BAND = new Color(255, 221, 0);

    @Test
    @DisplayName("in a multi-line value box only the matched text is highlighted, not the whole box")
    void multiLineMatch_onlyTheMatchingTextIsHighlighted() throws Exception {
        String[] lines = {"first line alpha", "second line beta", "target line gamma",
                "fourth line delta", "fifth line epsilon"};
        JTextArea area = areaWithLines(230, 150, lines);
        String text = area.getText();
        int idx = text.indexOf("target");
        assertTrue(idx >= 0);
        Object highlight = area.getHighlighter().addHighlight(idx, idx + 6,
                NodeConfigurationPanel.searchValueMatchPainter(BAND));
        try {
            BufferedImage img = render(area);

            Rectangle2D matchStart = area.modelToView2D(idx);
            Rectangle2D matchEnd = area.modelToView2D(idx + 6);
            int y = (int) matchStart.getY();
            int height = (int) matchStart.getHeight();
            // The matched range must carry the band (scanned as a region, so
            // glyph pixels cannot hide the band).
            assertTrue(regionContainsBand(img, (int) matchStart.getX(), y,
                    (int) matchEnd.getX() - (int) matchStart.getX(), height),
                    "the matched range must carry the band");
            // The rest of the same line, right after the match: unpainted.
            int afterX = (int) matchEnd.getX();
            assertFalse(regionContainsBand(img, afterX, y, area.getWidth() - afterX, height),
                    "the text right after the match must stay unpainted");

            // A non-matching line must be entirely unpainted.
            Rectangle2D other = area.modelToView2D(text.indexOf("alpha"));
            int otherY = (int) other.getY() + (int) other.getHeight() / 2;
            assertFalse(rowContainsBand(img, otherY),
                    "a non-matching line of the value box must not be highlighted");
        } finally {
            area.getHighlighter().removeHighlight(highlight);
        }
    }
    @Test
    @DisplayName("a match ending at the end of a line stops at the last matched character")
    void matchEndingAtLineEnd_stopsAtTheLastMatchedCharacter() throws Exception {
        String[] lines = {"first line", "second beta", "third line"};
        JTextArea area = areaWithLines(230, 120, lines);
        int idx = area.getText().indexOf("beta");
        assertTrue(idx >= 0);
        Object highlight = area.getHighlighter().addHighlight(idx, idx + 4,
                NodeConfigurationPanel.searchValueMatchPainter(BAND));
        try {
            BufferedImage img = render(area);

            Rectangle2D matchStart = area.modelToView2D(idx);
            Rectangle2D matchEnd = area.modelToView2D(idx + 4);
            int y = (int) matchStart.getY();
            int height = (int) matchStart.getHeight();
            // The view position of the range's end (the line separator) is
            // the end of the line's text.
            int bandEndX = (int) matchEnd.getX();
            // The matched range must carry the band.
            assertTrue(regionContainsBand(img, (int) matchStart.getX(), y,
                    bandEndX - (int) matchStart.getX(), height),
                    "the matched range must carry the band");
            // Past the line's text: unpainted (the band must not run to the
            // edge of the box or wrap to the next line).
            assertFalse(regionContainsBand(img, bandEndX, y, area.getWidth() - bandEndX, height),
                    "the space past the line's text must stay unpainted");

            // The next line must be entirely unpainted.
            Rectangle2D nextLine = area.modelToView2D(area.getText().indexOf("third"));
            int nextY = (int) nextLine.getY() + (int) nextLine.getHeight() / 2;
            assertFalse(rowContainsBand(img, nextY), "the line after the match must not be highlighted");
        } finally {
            area.getHighlighter().removeHighlight(highlight);
        }
    }

    @Test
    @DisplayName("in a single-line field the band covers exactly the matched text")
    void singleLineField_bandCoversExactlyTheMatchedText() throws Exception {
        JTextField field = new JTextField("hello world signum", 20);
        field.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        field.setForeground(Color.BLACK);
        field.setBackground(Color.WHITE);
        field.setSize(280, 30);
        field.validate();
        int idx = field.getText().indexOf("signum");
        assertTrue(idx >= 0);
        Object highlight = field.getHighlighter().addHighlight(idx, idx + 6,
                NodeConfigurationPanel.searchValueMatchPainter(BAND));
        try {
            BufferedImage img = render(field);

            Rectangle2D matchStart = field.modelToView2D(idx);
            Rectangle2D matchEnd = field.modelToView2D(idx + 6);
            int y = (int) matchStart.getY();
            int height = (int) matchStart.getHeight();
            // The matched range must carry the band.
            assertTrue(regionContainsBand(img, (int) matchStart.getX(), y,
                    (int) matchEnd.getX() - (int) matchStart.getX(), height),
                    "the matched range must carry the band");
            // Everything before the match on the line: unpainted.
            assertFalse(regionContainsBand(img, 0, y, (int) matchStart.getX(), height),
                    "the text before the match must stay unpainted");
        } finally {
            field.getHighlighter().removeHighlight(highlight);
        }
    }
    @Test
    @DisplayName("filling the highlighter's bounds paints the whole box — why the band uses the offsets")
    void fullBoundsFill_paintsTheWholeBox() throws Exception {
        String[] lines = {"first line alpha", "second line beta", "third line gamma"};
        JTextArea area = areaWithLines(230, 120, lines);
        int idx = area.getText().indexOf("beta");
        assertTrue(idx >= 0);
        // The legacy behavior: fill the bounds rectangle the highlighter
        // passes in (in this JDK build that is the component's whole content
        // area — the match offsets arrive as separate int parameters).
        Highlighter.HighlightPainter wholeBox = (g, s, e, bounds, c) -> {
            g.setColor(BAND);
            Rectangle r = bounds.getBounds();
            g.fillRect(r.x, r.y, r.width, r.height);
        };
        Object highlight = area.getHighlighter().addHighlight(idx, idx + 4, wholeBox);
        try {
            BufferedImage img = render(area);
            Rectangle2D first = area.modelToView2D(0);
            int y = (int) first.getY() + (int) first.getHeight() / 2;
            assertTrue(rowContainsBand(img, y),
                    "the legacy bounds fill highlights even non-matching lines (the reported bug)");
        } finally {
            area.getHighlighter().removeHighlight(highlight);
        }
    }

    /** Creates a white multi-line text area with a monospaced font and a fixed size. */
    private JTextArea areaWithLines(int width, int height, String... lines) {
        JTextArea area = new JTextArea(String.join("\n", lines), 0, 0);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        area.setForeground(Color.BLACK);
        area.setBackground(Color.WHITE);
        area.setSize(width, height);
        area.validate();
        return area;
    }

    /** Paints the (visible, sized) component into a white opaque image. */
    private BufferedImage render(JComponent component) {
        BufferedImage img = new BufferedImage(component.getWidth(), component.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        component.paint(g);
        g.dispose();
        return img;
    }

    private boolean isBand(int rgb) {
        return new Color(rgb).equals(BAND);
    }

    /** Whether any pixel of the given row carries the band color. */
    private boolean rowContainsBand(BufferedImage img, int y) {
        return regionContainsBand(img, 0, y, img.getWidth(), 1);
    }

    /** Whether any pixel of the given region carries the band color. */
    private boolean regionContainsBand(BufferedImage img, int x, int y, int width, int height) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(img.getWidth(), x + Math.max(0, width));
        int y1 = Math.min(img.getHeight(), y + Math.max(0, height));
        for (int yy = y0; yy < y1; yy++) {
            for (int xx = x0; xx < x1; xx++) {
                if (isBand(img.getRGB(xx, yy))) {
                    return true;
                }
            }
        }
        return false;
    }
}