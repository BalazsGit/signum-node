package application.utils.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.UIManager;

/**
 * The thin hand-drawn "+" glyph (default 16 px, 1.4 px stroke) — the single
 * source of the browser's two plus buttons: the tab strip's new-tab "+" and
 * the bookmarks bar's new-folder "+". Sharing one icon keeps both controls
 * the same size and weight (a bold FontAwesome plus next to a thin line reads
 * as two different controls). {@link #installHover(JButton)} gives them the
 * same grow-on-hover behaviour as the navigation toolbar's icon buttons.
 */
public final class PlusGlyphIcon implements Icon {

    /** The default glyph size (matches the tab strip's close "X"). */
    public static final int DEFAULT_SIZE = 16;
    private static final float STROKE = 1.4f;
    private static final int ARM = 4;

    private final int size;
    private final int arm;

    public PlusGlyphIcon() {
        this(DEFAULT_SIZE);
    }

    public PlusGlyphIcon(int size) {
        this.size = Math.max(8, size);
        this.arm = Math.max(2, Math.round(ARM * this.size / (float) DEFAULT_SIZE));
    }

    /**
     * Installs the shared "+" on {@code button} with the toolbar-standard
     * grow-on-hover pair ({@link HoverScaleIcon} — the same effect the
     * settings gear and the other nav buttons use).
     */
    public static void installHover(JButton button) {
        HoverScaleIcon.install(button,
                new PlusGlyphIcon(),
                new PlusGlyphIcon(Math.round(DEFAULT_SIZE * HoverScaleIcon.DEFAULT_SCALE)));
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
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(color());
        g2.setStroke(new BasicStroke(STROKE));
        int cx = x + size / 2;
        int cy = y + size / 2;
        g2.drawLine(cx - arm, cy, cx + arm, cy);
        g2.drawLine(cx, cy - arm, cx, cy + arm);
        g2.dispose();
    }

    private static Color color() {
        Color c = UIManager.getColor("controlText");
        return c != null ? c : new Color(0x20, 0x21, 0x24);
    }
}
