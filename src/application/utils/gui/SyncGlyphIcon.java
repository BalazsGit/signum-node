package application.utils.gui;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/**
 * The thin hand-drawn double circular arrow ("sync") glyph: two chasing
 * curved arrows, each ending in an arrowhead — the icon of the
 * "Reload Configuration" toolbar button (node configuration panel) and of
 * the logging profile panel's merged Refresh button (reload + refresh).
 * <p>
 * The bundled FontAwesome set (4.7) predates the "sync" icon, so the glyph
 * is drawn by hand in the SAME thin-line style as {@link PlusGlyphIcon}
 * (1.4 px antialiased stroke, palette-driven color).
 */
public final class SyncGlyphIcon implements Icon {

    /** The default glyph size (matches {@link PlusGlyphIcon#DEFAULT_SIZE}). */
    public static final int DEFAULT_SIZE = 16;
    private static final float STROKE = 1.4f;

    private final int size;
    private final Color color;

    public SyncGlyphIcon() {
        this(DEFAULT_SIZE, null);
    }

    public SyncGlyphIcon(int size) {
        this(size, null);
    }

    public SyncGlyphIcon(int size, Color color) {
        this.size = Math.max(8, size);
        this.color = color;
    }

    /**
     * Installs the glyph on {@code button} in the profile toolbar's flat
     * icon-button style: the same grow-on-hover pair, slim uniform border
     * and unfilled surface that
     * {@code ConfigurationUtils.styleProfileIconButton} gives the
     * FontAwesome toolbar glyphs (this variant for the custom glyph, which
     * is not a FontAwesome code). A {@code null} button is skipped — the
     * callers may run before the button exists (an early {@code updateUI()}
     * during construction).
     */
    public static void install(JButton button, Color color, float size) {
        if (button == null) {
            return;
        }
        HoverScaleIcon.install(button,
                new SyncGlyphIcon(Math.round(size), color),
                new SyncGlyphIcon(Math.round(size * HoverScaleIcon.DEFAULT_SCALE), color));
        button.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        button.setOpaque(false);
        button.setContentAreaFilled(false);
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
        g2.setStroke(new BasicStroke(STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int s = size;
        float cx = x + s / 2f;
        float cy = y + s / 2f;
        float r = s * 0.42f;
        int arcD = Math.round(r * 2f);
        int arcX = Math.round(cx - r);
        int arcY = Math.round(cy - r);
        // Two 150-degree arcs offset by 180 degrees: each ends 30 degrees
        // before the other one starts, leaving the two gaps its arrowhead
        // sits in. (Java arc angles: 90 = 12 o'clock, positive extent =
        // counter-clockwise.)
        g2.drawArc(arcX, arcY, arcD, arcD, 300, 150); // ends at 90 (top)
        g2.drawArc(arcX, arcY, arcD, arcD, 120, 150); // ends at 270 (bottom)
        paintArrowHead(g2, cx, cy, r, 90);
        paintArrowHead(g2, cx, cy, r, 270);
        g2.dispose();
    }

    /**
     * The filled triangle at the arc's end, pointing along the arc's
     * direction of travel (counter-clockwise) — the chasing "sync" look.
     */
    private void paintArrowHead(Graphics2D g2, float cx, float cy, float r, double endAngleDeg) {
        double a = Math.toRadians(endAngleDeg);
        // The arc's end point (screen y grows downward).
        float px = (float) (cx + r * Math.cos(a));
        float py = (float) (cy - r * Math.sin(a));
        // The direction of travel at that point, in screen coordinates.
        float tx = (float) (-Math.sin(a));
        float ty = (float) (-Math.cos(a));
        float len = size * 0.30f;
        float half = size * 0.17f;
        // The head's tip (forward) and its base axis (perpendicular).
        float tipX = px + tx * len;
        float tipY = py + ty * len;
        float nx = -ty;
        float ny = tx;
        Path2D.Float head = new Path2D.Float();
        head.moveTo(tipX, tipY);
        head.lineTo(px + nx * half, py + ny * half);
        head.lineTo(px - nx * half, py - ny * half);
        head.closePath();
        g2.fill(head);
    }

    private Color color() {
        if (color != null) {
            return color;
        }
        Color c = UIManager.getColor("controlText");
        return c != null ? c : new Color(0x20, 0x21, 0x24);
    }
}