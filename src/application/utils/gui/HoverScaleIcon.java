package application.utils.gui;

import com.formdev.flatlaf.FlatLaf;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Graphics;

/**
 * Reusable "grow on hover" icon support for Swing icon buttons.
 * <p>
 * Wraps a glyph in a fixed-size bounding box and installs a normal/rollover
 * icon pair on a {@link JButton} so that the glyph is rendered larger on
 * mouse rollover <b>without any layout shift</b>: both icons expose the same
 * (larger) box, so the button is sized once (to that box) and never resizes
 * when the hover glyph is swapped in — the glyph simply scales up inside a
 * constant area, and nothing below the button moves.
 * </p>
 * <p>
 * This generalizes the hover behaviour that used to be implemented privately
 * in the node toolbar's icon buttons; the main frame's shutdown icon and all
 * toolbar icon buttons share this single implementation now.
 * </p>
 *
 * @see JButton#setRolloverIcon(Icon)
 */
public final class HoverScaleIcon implements Icon {

    /**
     * Hover scale-up factor for toolbar icon buttons. On rollover the glyph
     * is rendered this many times larger (1.15f → 15% bigger) instead of
     * changing colour.
     */
    public static final float DEFAULT_SCALE = 1.15f;

    private final int boxSize;
    private final Icon glyph;

    private HoverScaleIcon(int boxSize, Icon glyph) {
        this.boxSize = boxSize;
        this.glyph = glyph;
    }

    /**
     * Wraps {@code glyph} in an icon whose bounding box is a fixed
     * {@code boxSize}: the glyph is centered inside that box. Two such icons
     * sharing the same boxSize have identical layout dimensions, so swapping
     * them (normal → rollover) never resizes the button that holds them —
     * only the glyph's apparent size changes.
     *
     * @param boxSize the fixed bounding box size (applies to both width and height)
     * @param glyph   the glyph to center inside the box
     * @return an {@link Icon} exposing the fixed box and painting the glyph centered
     */
    public static Icon box(float boxSize, Icon glyph) {
        return new HoverScaleIcon(Math.max(1, Math.round(boxSize)), glyph);
    }

    /**
     * Installs the "grow on hover" effect on {@code button}:
     * <ul>
     *   <li>{@code normalGlyph} becomes the button icon, and
     *   {@code hoverGlyph} (typically the same glyph rendered larger) becomes
     *   the rollover icon;</li>
     *   <li>both icons are wrapped into the SAME fixed bounding box (at least
     *   as large as the bigger glyph), so the button never resizes when the
     *   hover glyph is swapped in — the colour is unchanged on hover;</li>
     *   <li>a grayed disabled icon is derived when running under FlatLaf,
     *   because the fixed-box wrapper is a plain {@code Icon} (not an
     *   {@code ImageIcon}) that FlatLaf cannot auto-derive a disabled icon
     *   for — the disabled glyph is derived from the inner
     *   {@code normalGlyph} via {@code FlatLaf.getDisabledIcon(...)} so
     *   disabled buttons keep the L&F's proper grayed look.</li>
     * </ul>
     *
     * @param button      the button to install the hover effect on
     * @param normalGlyph the glyph used in the normal (non-hover) state
     * @param hoverGlyph  the glyph used in the rollover (hover) state
     */
    public static void install(JButton button, Icon normalGlyph, Icon hoverGlyph) {
        int box = Math.max(
                Math.max(normalGlyph.getIconWidth(), normalGlyph.getIconHeight()),
                Math.max(hoverGlyph.getIconWidth(), hoverGlyph.getIconHeight()));
        button.setIcon(new HoverScaleIcon(box, normalGlyph));
        button.setRolloverIcon(new HoverScaleIcon(box, hoverGlyph));
        if (UIManager.getLookAndFeel() instanceof FlatLaf flatLaf) {
            Icon disabled = flatLaf.getDisabledIcon(button, normalGlyph);
            if (disabled != null) {
                button.setDisabledIcon(new HoverScaleIcon(box, disabled));
            }
        }
    }

    @Override
    public int getIconWidth() {
        return boxSize;
    }

    @Override
    public int getIconHeight() {
        return boxSize;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        int dx = x + (boxSize - glyph.getIconWidth()) / 2;
        int dy = y + (boxSize - glyph.getIconHeight()) / 2;
        glyph.paintIcon(c, g, dx, dy);
    }
}
