package application.module.node.gui;

import application.module.node.profile.NodeProfile;
import application.utils.gui.GuiConstants;
import application.utils.gui.HoverScaleIcon;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the hamburger (Menu) button's icon construction in
 * {@link NodeToolbar#setMenuButtonIcon}: the FlatLaf {@code listFiles.svg}
 * glyph must be PIXEL-sized for both the normal and the rollover icon.
 * <p>
 * Regression: {@code FlatSVGIcon#derive(float)} is a SCALE FACTOR (a single
 * int argument silently widens to it) while {@code derive(int, int)} is a
 * pixel size. Using the scale-factor overload for the hover glyph produced a
 * ~17x giant glyph; because {@link HoverScaleIcon#install} sizes its fixed
 * bounding box to the LARGER of the two glyphs, the whole button box blew up
 * and the small normal glyph floated in the middle of it even off hover.
 * <p>
 * The two pixel-sized glyphs are also centred in the SAME box the jiconfont
 * sibling icons expose (font-metric derived, 19 px for a 14.4 px toolbar
 * icon), so the menu button has exactly the same size as its neighbours and
 * the hamburger sits on the toolbar's vertical centre line.
 */
@DisplayName("NodeToolbar hamburger (Menu) icon Tests")
class NodeToolbarMenuButtonIconTest {

    @org.junit.jupiter.api.BeforeAll
    static void registerFontAwesome() {
        IconFontSwing.register(FontAwesome.getIconFont());
    }

    @Test
    @DisplayName("the menu icon is pixel-sized for both normal and rollover (no SVG scale-factor blow-up, no layout shift on hover)")
    void menuButtonIconIsPixelSized() throws Exception {
        final JButton[] holder = new JButton[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new JButton();
            NodeToolbar.setMenuButtonIcon(holder[0], GuiConstants.getToolBarIconSize());
        });
        JButton button = holder[0];

        float iconSize = GuiConstants.getToolBarIconSize();
        int size = Math.max(8, Math.round(iconSize));
        int hoverSize = Math.max(8, Math.round(iconSize * HoverScaleIcon.DEFAULT_SCALE));
        // The box must match what the jiconfont siblings expose: their
        // HoverScaleIcon box is sized to the LARGER glyph, i.e. the hover
        // (larger-font) icon — 19 px for a 14.4 px toolbar icon.
        Icon siblingMetrics = IconFontSwing.buildIcon(
                FontAwesome.BARS, iconSize * HoverScaleIcon.DEFAULT_SCALE, Color.WHITE);
        int box = Math.max(hoverSize,
                Math.max(siblingMetrics.getIconWidth(), siblingMetrics.getIconHeight()));

        assertNotNull(button.getIcon(), "the menu button carries its normal icon");
        assertNotNull(button.getRolloverIcon(), "the menu button carries its rollover (hover) icon");
        assertEquals(box, button.getIcon().getIconWidth(),
                "the fixed bounding box must be the siblings' jiconfont box, not a ~17x SVG scale");
        assertEquals(box, button.getIcon().getIconHeight());
        assertEquals(button.getIcon().getIconWidth(), button.getRolloverIcon().getIconWidth(),
                "normal and rollover share one bounding box (no layout shift on hover)");
        assertEquals(button.getIcon().getIconHeight(), button.getRolloverIcon().getIconHeight());
        assertTrue(box <= 32, "the toolbar glyph stays toolbar-sized (box=" + box + "px)");
        assertTrue(box >= hoverSize,
                "the box fits the hover glyph (box=" + box + "px, hover=" + hoverSize + "px)");
        assertTrue(size <= hoverSize, "sanity: the normal glyph is no larger than the hover glyph");
    }

    @Test
    @DisplayName("the menu button is pinned to the toolbar's right edge (tight 2px, no extra right inset)")
    void menuButtonSitsAtToolbarRightEdge() throws Exception {
        final NodeToolbar[] holder = new NodeToolbar[1];
        final int[] gap = new int[1];
        final int[] buttonWidth = new int[1];
        final int[] firstIconWidth = new int[1];
        final javax.swing.JFrame[] frame = new javax.swing.JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            NodeToolbar toolbar = new NodeToolbar(new NodeProfile("menu-margin"));
            // Real frame (displayable): validate() cascades the layout through
            // the toolbar's MigLayout rows. Wide enough that every action
            // button fits, so no overflow scrollbar changes the row geometry.
            javax.swing.JFrame f = new javax.swing.JFrame("menu-margin");
            f.setDefaultCloseOperation(javax.swing.JFrame.DISPOSE_ON_CLOSE);
            f.add(toolbar, java.awt.BorderLayout.CENTER);
            f.setSize(1200, 200);
            f.setVisible(true);
            f.validate();
            frame[0] = f;

            JButton menu = toolbar.getMenuButton();
            java.awt.Point p = SwingUtilities.convertPoint(menu, menu.getWidth(), 0, toolbar);
            gap[0] = toolbar.getWidth() - p.x;
            buttonWidth[0] = menu.getWidth();
            try {
                Field f2 = NodeToolbar.class.getDeclaredField("startStopButton");
                f2.setAccessible(true);
                firstIconWidth[0] = ((JButton) f2.get(toolbar)).getWidth();
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
            holder[0] = toolbar;
        });
        try {
            NodeToolbar toolbar = holder[0];
            JButton menu = toolbar.getMenuButton();

            // The menu button's right edge must sit FLUSH against the toolbar's
            // right edge — any rightIconsPanel inset would add to this and
            // produce the "large right margin" the user sees. The visible glyph
            // keeps a 2px breathing room via the button's tight right border.
            assertEquals(0, gap[0],
                    "the hamburger button must be pinned to the toolbar's right edge (no extra right inset)");
            // The nested window/panel borders (NodePanel 5px + NodeProfilePanel 5px)
            // then give the hamburger its final ~12px distance from the window edge.
            // The button keeps the same HEIGHT as the sibling icons (same border
            // top/bottom + same 19px icon box), only the right padding is tighter.
            assertEquals(buttonWidth[0], firstIconWidth[0] - 4,
                    "the menu button is the sibling width minus the 4px of right padding (left padding kept)");
            javax.swing.border.Border border = menu.getBorder();
            assertTrue(border instanceof javax.swing.border.EmptyBorder,
                    "the menu button border must be an EmptyBorder, was " + border);
            java.awt.Insets insets = border.getBorderInsets(menu);
            assertEquals(2, insets.top, "top padding matches the siblings");
            assertEquals(6, insets.left, "left padding matches the siblings");
            assertEquals(2, insets.bottom);
            assertEquals(2, insets.right, "the right padding is tight (button pinned to the right edge)");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (frame[0] != null) {
                    frame[0].dispose();
                }
            });
        }
    }
}