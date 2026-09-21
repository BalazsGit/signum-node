package application.utils.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
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

    // ── Key text (property key, same line as the name) tests ────────────────

    /** X where the name "ABC" ends. */
    private int nameEndX() {
        return columnWidth * 3;
    }

    /** X where the opening bracket starts: after the name plus KEY_TEXT_GAP (8). */
    private int bracketStartX() {
        return nameEndX() + 8;
    }

    /** X where the raw key text starts: after the (monospaced) "<" bracket. */
    private int keyStartX() {
        return bracketStartX() + columnWidth;
    }

    /** Top of the key font box: vertically centered inside the name font box. */
    private int keyTop() {
        FontMetrics keyFm = label.getFontMetrics(label.getKeyFont());
        return (label.getHeight() - keyFm.getHeight()) / 2;
    }

    @Test
    void keyText_growsTheWidthButNotTheHeight() {
        Dimension plain = label.getPreferredSize();
        label.setKeyText("node.example");
        label.setSize(label.getPreferredSize());

        Dimension keyed = label.getPreferredSize();
        assertEquals(plain.height, keyed.height,
                "a same-line key must not change the label height");
        assertTrue(keyed.width > plain.width,
                "the key text after the name must widen the label");
    }

    @Test
    void keyText_isPaintedOnTheSameLineAfterTheName() {
        int plainHeight = label.getPreferredSize().height;
        label.setKeyText("node.example");
        label.setSize(label.getPreferredSize());
        BufferedImage keyedImg = render();

        assertEquals(plainHeight, keyedImg.getHeight(),
                "the key must sit on the name's line (no second line)");
        // The gap between the name and the bracket must stay empty.
        for (int x = nameEndX(); x < bracketStartX(); x++) {
            for (int y = 0; y < keyedImg.getHeight(); y++) {
                assertEquals(BLACK, new Color(keyedImg.getRGB(x, y)),
                        "the name/key gap must stay the background");
            }
        }
        // The opening bracket must be painted in the bracket column.
        boolean bracketFound = false;
        for (int x = bracketStartX(); x < keyStartX() && !bracketFound; x++) {
            for (int y = 0; y < keyedImg.getHeight(); y++) {
                if (keyedImg.getRGB(x, y) != 0) {
                    bracketFound = true;
                    break;
                }
            }
        }
        assertTrue(bracketFound, "the opening bracket must be painted before the key text");
        // The key glyphs must be visible to the RIGHT of the bracket, on the
        // same line.
        boolean keyGlyphFound = false;
        for (int x = keyStartX(); x < keyedImg.getWidth() && !keyGlyphFound; x++) {
            for (int y = 0; y < keyedImg.getHeight(); y++) {
                if (keyedImg.getRGB(x, y) != 0) {
                    keyGlyphFound = true;
                    break;
                }
            }
        }
        assertTrue(keyGlyphFound, "the key text must be painted after the bracket on the same line");
    }

    @Test
    void keyText_highlightBandStillTargetsTheNameTextOnly() {
        label.setKeyText("node.example");
        label.setSize(label.getPreferredSize());
        label.setHighlightRange(1, 1); // the "B" column of the name text
        BufferedImage img = render();

        // The label stays single-line (CENTER aligned), so the name font box
        // is exactly the plain label's font box (descentRowY from setUp):
        // the band must cover the NAME text, not the key text after it.
        Color highlight = GuiColors.getSearchMatch();
        int a = highlight.getAlpha();
        Color expected = new Color((highlight.getRed() * a + 127) / 255,
                (highlight.getGreen() * a + 127) / 255, (highlight.getBlue() * a + 127) / 255);
        assertEquals(expected, new Color(img.getRGB(columnWidth * 2 - 1, descentRowY)),
                "the highlight band must be painted on the name text (key set)");
        assertEquals(BLACK, new Color(img.getRGB(columnWidth / 2, descentRowY)),
                "a pixel outside the band must stay the background (key set)");
        // The key text (column "n", no descender) must stay un-highlighted.
        FontMetrics keyFm = label.getFontMetrics(label.getKeyFont());
        int keyBandRowY = keyTop() + keyFm.getHeight() - 1;
        assertEquals(BLACK, new Color(img.getRGB(keyStartX() + keyFm.charWidth('A') - 1, keyBandRowY)),
                "the key text must stay the background when only the name is highlighted");
    }

    @Test
    void keyText_keyHighlightBandPaintedNextToTheNameOnly() {
        label.setKeyText("node.example");
        label.setSize(label.getPreferredSize());
        label.setKeyHighlightRange(5, 7); // "example" of the key text
        BufferedImage img = render();

        assertTrue(label.hasKeyHighlight(), "setKeyHighlightRange must register the key-text band");
        FontMetrics keyFm = label.getFontMetrics(label.getKeyFont());
        int kw = keyFm.charWidth('A');
        // The band must cover the KEY font box (centered on the name's line).
        // "example" has no descenders, so the bottom row of its font box is
        // pure band color.
        int keyBandRowY = keyTop() + keyFm.getHeight() - 1;

        Color highlight = GuiColors.getSearchMatch();
        int a = highlight.getAlpha();
        Color expected = new Color((highlight.getRed() * a + 127) / 255,
                (highlight.getGreen() * a + 127) / 255, (highlight.getBlue() * a + 127) / 255);
        // The band spans key columns 5..11 ("example"): sample the right edge
        // of the first highlighted column (inside the band, right of its
        // start).
        assertEquals(expected, new Color(img.getRGB(keyStartX() + kw * 6 - 1, keyBandRowY)),
                "a pure band pixel on the key text must equal the palette search-match color over the background");
        // Key column 0 ("n", not highlighted, no descender) must stay the
        // background.
        assertEquals(BLACK, new Color(img.getRGB(keyStartX() + kw - 1, keyBandRowY)),
                "a pixel outside the key band must stay the background");
        // And the NAME text must stay un-highlighted (no name band set).
        assertEquals(BLACK, new Color(img.getRGB(columnWidth * 2 - 1, descentRowY)),
                "the name text must stay the background when only the key text is highlighted");
    }

    @Test
    void keyText_clearKeyHighlightRemovesTheKeyBand() {
        label.setKeyText("node.example");
        label.setSize(label.getPreferredSize());
        label.setKeyHighlightRange(5, 7);
        label.clearKeyHighlight();
        BufferedImage img = render();

        assertFalse(label.hasKeyHighlight());
        FontMetrics keyFm = label.getFontMetrics(label.getKeyFont());
        int keyBandRowY = keyTop() + keyFm.getHeight() - 1;
        assertEquals(BLACK, new Color(img.getRGB(keyStartX() + keyFm.charWidth('A') * 6 - 1, keyBandRowY)),
                "clearKeyHighlight must remove the key-text band");
    }

    @Test
    void keyText_nullOrEmptyRevertsToPlainSingleLineLabel() {
        int plainHeight = label.getPreferredSize().height;
        label.setKeyText("");
        assertEquals(plainHeight, label.getPreferredSize().height,
                "an empty key must not change the preferred size");
        label.setKeyText(null);
        assertEquals(plainHeight, label.getPreferredSize().height,
                "a null key must not change the preferred size");
        assertFalse(label.hasKeyText());
        assertNull(label.getKeyText());
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