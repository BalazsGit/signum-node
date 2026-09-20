package application.utils.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pixel-level unit tests for {@link SearchMatchLabel}.
 * <p>
 * The label is painted into an opaque {@code TYPE_INT_RGB} image over a
 * black background, so the (semi-transparent) highlight band composites to
 * an exact, deterministic color that can be asserted pixel by pixel. A
 * monospaced font with uppercase text (no descenders) makes the band
 * geometry deterministic: the bottom row of the font box inside the band is
 * guaranteed to be pure band color (no glyph pixels there).
 * </p>
 */
@DisplayName("SearchMatchLabel Tests")
class SearchMatchLabelTest {

    private static final Color BLACK = Color.BLACK;

    private SearchMatchLabel label;
    private int columnWidth;
    private int descentRowY;

    @BeforeEach
    void setUp() {
        label = new SearchMatchLabel("ABC");
        label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        // White foreground: the text is visible against the black buffer,
        // and glyph pixels never equal the background/band colors.
        label.setForeground(Color.WHITE);
        label.setSize(label.getPreferredSize());

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = probe.createGraphics();
        FontMetrics fm = g.getFontMetrics(label.getFont());
        columnWidth = fm.charWidth('A');
        descentRowY = (label.getHeight() - fm.getHeight()) / 2 + fm.getHeight() - 1;
        g.dispose();
    }

    @Test
    void highlight_bandPaintedInPaletteSearchMatchColor() {
        label.setHighlightRange(1, 1); // the "B" column
        BufferedImage img = render();

        Color highlight = GuiColors.getSearchMatch();
        assertTrue(highlight.getAlpha() < 255, "the palette search-match color must be semi-transparent");
        // Band painted over the black buffer: src-over with dst = 0.
        // (The 2D pipeline rounds the composite to nearest — mirror that.)
        int a = highlight.getAlpha();
        Color expected = new Color((highlight.getRed() * a + 127) / 255,
                (highlight.getGreen() * a + 127) / 255, (highlight.getBlue() * a + 127) / 255);

        assertEquals(expected, new Color(img.getRGB(columnWidth * 2 - 1, descentRowY)),
                "a pure band pixel must equal the palette search-match color over the background");
        assertEquals(BLACK, new Color(img.getRGB(columnWidth / 2, descentRowY)),
                "a pixel outside the band must stay the background");
    }

    @Test
    void highlight_isPaintedBehindTheText() {
        label.setHighlightRange(1, 1);
        BufferedImage img = render();

        // The "B" column must contain bright (white text, anti-aliased)
        // pixels, proving the text is still painted on top of the band.
        // The pure band color has all channels well below 150 (a ~38-86%
        // alpha overlay over black), so brightness cleanly separates glyph
        // pixels from band pixels.
        boolean glyphFound = false;
        outer:
        for (int x = columnWidth; x < columnWidth * 2; x++) {
            for (int y = 0; y < label.getHeight(); y++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int gch = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (Math.min(Math.min(r, gch), b) > 150) {
                    glyphFound = true;
                    break outer;
                }
            }
        }
        assertTrue(glyphFound, "the text must remain painted (white glyph over the band)");
    }

    @Test
    void clearHighlight_removesTheBand() {
        label.setHighlightRange(1, 1);
        label.clearHighlight();
        BufferedImage img = render();

        assertEquals(BLACK, new Color(img.getRGB(columnWidth * 2 - 1, descentRowY)),
                "after clearing, the band area must be plain background");
    }

    @Test
    void noHighlight_paintsAPlainLabel() {
        BufferedImage img = render();

        assertEquals(BLACK, new Color(img.getRGB(columnWidth, descentRowY)),
                "left column descent row is background");
        assertEquals(BLACK, new Color(img.getRGB(columnWidth * 2, descentRowY)),
                "middle column descent row is background");
        assertTrue(hasNonBlackPixel(img), "the label text must be painted");
    }

    @Test
    void setHighlightColor_explicitColorWinsOverPaletteDefault() {
        label.setHighlightRange(1, 1); // the "B" column
        Color custom = new Color(255, 0, 0, 160);
        label.setHighlightColor(custom);
        BufferedImage img = render();

        int a = custom.getAlpha();
        Color expected = new Color((custom.getRed() * a + 127) / 255,
                (custom.getGreen() * a + 127) / 255, (custom.getBlue() * a + 127) / 255);
        assertEquals(expected, new Color(img.getRGB(columnWidth * 2 - 1, descentRowY)),
                "an explicit highlight color must be painted");
        assertNotEquals(expected, new Color(img.getRGB(columnWidth / 2, descentRowY)),
                "a pixel outside the band must not take the highlight color");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private BufferedImage render() {
        BufferedImage img = new BufferedImage(label.getWidth(), label.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        label.paint(g);
        g.dispose();
        return img;
    }

    private static boolean hasNonBlackPixel(BufferedImage img) {
        for (int x = 0; x < img.getWidth(); x++) {
            for (int y = 0; y < img.getHeight(); y++) {
                if (img.getRGB(x, y) != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}