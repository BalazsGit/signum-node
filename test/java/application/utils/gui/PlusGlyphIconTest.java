package application.utils.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link PlusGlyphIcon}, the thin hand-drawn "+" rendered offscreen.
 * The key guarantee is that the glyph is symmetric (no lopsided arms from
 * sub-pixel drift) and grows on hover like the other toolbar icons.
 */
@DisplayName("Thin plus glyph")
class PlusGlyphIconTest {

    @Test
    @DisplayName("the glyph is square and paints a centered, mirror-symmetric plus")
    void paintsSymmetricPlus() {
        int size = 16;
        PlusGlyphIcon icon = new PlusGlyphIcon(size, Color.BLACK);
        assertEquals(size, icon.getIconWidth());
        assertEquals(size, icon.getIconHeight());

        BufferedImage img = paint(icon);
        int center = size / 2;
        assertTrue(alphaAt(img, center, center) > 0, "the plus center must be painted");

        // The "+" must be mirror-symmetric: every pixel's alpha equals the alpha
        // of the pixel mirrored about the center — no lopsided arms.
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                assertEquals(alphaAt(img, x, y), alphaAt(img, size - 1 - x, size - 1 - y),
                        "the plus must be symmetric at (" + x + "," + y + ")");
            }
        }
    }

    @Test
    @DisplayName("the hover (larger) glyph paints more than the base glyph")
    void hoverGlyphPaintsMore() {
        int base = countPaintedPixels(new PlusGlyphIcon(14, Color.BLACK));
        int hover = countPaintedPixels(new PlusGlyphIcon(17, Color.BLACK));
        assertTrue(hover > base,
                "the hover plus (" + hover + " px) must paint more than the base plus (" + base + " px)");
    }

    @Test
    @DisplayName("same size and color render identically, different colors differ")
    void rendersConsistentlyBySizeAndColor() {
        PlusGlyphIcon black = new PlusGlyphIcon(16, Color.BLACK);
        PlusGlyphIcon blue = new PlusGlyphIcon(16, Color.BLUE);
        // A second instance of the same size + color must render pixel-identically
        // (the glyph is cached), while a different color must differ.
        assertEquals(pixelData(paint(black)), pixelData(paint(new PlusGlyphIcon(16, Color.BLACK))),
                "same size + color must render identically");
        assertNotEquals(pixelData(paint(black)), pixelData(paint(blue)),
                "different colors must render differently");
    }

    private static BufferedImage paint(Icon icon) {
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            icon.paintIcon(null, g, 0, 0);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static int alphaAt(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) >>> 24;
    }

    private static String pixelData(BufferedImage image) {
        StringBuilder sb = new StringBuilder(image.getWidth() * image.getHeight() * 12);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                sb.append(image.getRGB(x, y)).append(',');
            }
        }
        return sb.toString();
    }

    private static int countPaintedPixels(Icon icon) {
        BufferedImage image = paint(icon);
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (alphaAt(image, x, y) > 0) {
                    count++;
                }
            }
        }
        return count;
    }
}
