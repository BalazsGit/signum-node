package application.utils.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JButton;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

@DisplayName("HoverScaleIcon - grow-on-hover icon support")
class HoverScaleIconTest {

    /**
     * A fixed-size test icon that paints a solid white square at its origin,
     * so positioning inside the fixed box can be verified via pixel checks.
     */
    private static final class FixedIcon implements Icon {
        private final int size;

        FixedIcon(int size) {
            this.size = size;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(Color.WHITE);
            g.fillRect(x, y, size, size);
        }
    }

    @Test
    @DisplayName("DEFAULT_SCALE is a grow-up factor above 1")
    void defaultScale_IsAGrowUpFactor() {
        assertEquals(true, HoverScaleIcon.DEFAULT_SCALE > 1.0f);
        // a reasonable "hover grow" ratio, not a dramatic zoom
        assertEquals(true, HoverScaleIcon.DEFAULT_SCALE < 2.0f);
    }

    @Test
    @DisplayName("box() exposes the fixed box size and centers the glyph")
    void box_ExposesFixedSizeAndCentersGlyph() {
        Icon boxIcon = HoverScaleIcon.box(20f, new FixedIcon(10));
        assertEquals(20, boxIcon.getIconWidth());
        assertEquals(20, boxIcon.getIconHeight());

        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        boxIcon.paintIcon(null, g, 0, 0);
        g.dispose();

        // glyph (10x10) is centered in the 20x20 box → white area covers (5..14)
        assertEquals(0xFFFFFFFF, image.getRGB(10, 10), "glyph must be centered in the box");
        assertEquals(0xFFFFFFFF, image.getRGB(6, 6), "glyph must be centered in the box");
        assertEquals(0, image.getRGB(2, 2), "area outside the glyph must stay transparent");
        assertEquals(0, image.getRGB(18, 18), "area outside the glyph must stay transparent");
    }

    @Test
    @DisplayName("box() never produces a zero-sized box")
    void box_UsesMinimumSizeOfOne() {
        Icon boxIcon = HoverScaleIcon.box(0.4f, new FixedIcon(1));
        assertEquals(true, boxIcon.getIconWidth() >= 1);
        assertEquals(true, boxIcon.getIconHeight() >= 1);
    }

    @Test
    @DisplayName("install() gives the button and its rollover icon the same bounding box")
    void install_NormalAndRolloverShareTheSameBox() {
        JButton button = new JButton();
        HoverScaleIcon.install(button, new FixedIcon(16), new FixedIcon(19));

        Icon normal = button.getIcon();
        Icon rollover = button.getRolloverIcon();
        assertNotSame(normal, rollover, "normal and rollover icons must be distinct instances");
        assertEquals(19, normal.getIconWidth(), "normal icon must expose the larger box");
        assertEquals(19, rollover.getIconWidth(), "rollover icon must expose the same box");
        assertEquals(rollover.getIconWidth(), normal.getIconHeight(), "box must be square");
        assertEquals(normal.getIconWidth(), rollover.getIconHeight(), "box must be square");
    }

    @Test
    @DisplayName("install() box is at least as large as the bigger glyph (no clipping)")
    void install_BoxIsAtLeastAsLargeAsTheBiggerGlyph() {
        JButton button = new JButton();
        HoverScaleIcon.install(button, new FixedIcon(12), new FixedIcon(30));
        assertEquals(30, button.getIcon().getIconWidth());
        assertEquals(30, button.getRolloverIcon().getIconWidth());
    }
}
