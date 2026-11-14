package application.utils.gui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import javax.swing.JButton;
import javax.swing.Icon;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Profile action icon catalog")
class ProfileActionIconTest {

    @BeforeAll
    static void registerIconFont() {
        // The application registers the icon font at startup (AppearanceModule#init);
        // do the same here so the FontAwesome-backed profile actions resolve when this
        // test runs in isolation (otherwise it depends on a sibling test registering it).
        IconFontSwing.register(FontAwesome.getIconFont());
    }

    @Test
    @DisplayName("every profile action provides a normal and rollover icon")
    void everyActionProvidesBothIconVariants() {
        for (ProfileActionIcon action : ProfileActionIcon.values()) {
            Icon normal = action.normal(16);
            Icon rollover = action.rollover(16);

            assertNotNull(normal, action.name() + " normal icon");
            assertNotNull(rollover, action.name() + " rollover icon");
            assertTrue(normal.getIconWidth() > 0, action.name() + " normal width");
            assertTrue(normal.getIconHeight() > 0, action.name() + " normal height");
            assertTrue(rollover.getIconWidth() >= normal.getIconWidth(),
                    action.name() + " rollover width should not shrink");
            assertTrue(rollover.getIconHeight() >= normal.getIconHeight(),
                    action.name() + " rollover height should not shrink");
        }
    }

    @Test
    @DisplayName("hover installation keeps button bounds fixed while the plus glyph grows")
    void hoverInstallationKeepsBoundsAndScalesGlyph() {
        JButton button = new JButton();
        ProfileActionIcon.NEW_PROFILE.install(button, 16, 1.5f, Color.BLUE);

        assertNotNull(button.getIcon());
        assertNotNull(button.getRolloverIcon());
        assertEquals(button.getIcon().getIconWidth(), button.getRolloverIcon().getIconWidth());
        assertEquals(button.getIcon().getIconHeight(), button.getRolloverIcon().getIconHeight());
        assertTrue(countPaintedPixels(ProfileActionIcon.NEW_PROFILE.rollover(16, 1.5f, Color.BLUE))
                > countPaintedPixels(ProfileActionIcon.NEW_PROFILE.normal(16, Color.BLUE)));
        // The thin "+" is centered on a pixel edge, so its center pixel is only
        // partially covered by the 1.4 px stroke — assert the hue (pure blue)
        // rather than an exact color match.
        int centerPixel = paintedCenterPixel(ProfileActionIcon.NEW_PROFILE.normal(16, Color.BLUE));
        assertTrue((centerPixel >>> 24) > 0, "center pixel should be painted");
        assertEquals(0, (centerPixel >>> 16) & 0xFF, "center pixel should have no red");
        assertEquals(0, (centerPixel >>> 8) & 0xFF, "center pixel should have no green");
        assertTrue((centerPixel & 0xFF) > 0, "center pixel should have blue");
    }

    private static int countPaintedPixels(Icon icon) {
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            icon.paintIcon(null, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }
        int paintedPixels = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    paintedPixels++;
                }
            }
        }
        return paintedPixels;
    }

    private static int paintedCenterPixel(Icon icon) {
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            icon.paintIcon(null, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }
        return image.getRGB(icon.getIconWidth() / 2, icon.getIconHeight() / 2);
    }
}
