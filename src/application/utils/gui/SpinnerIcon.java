package application.utils.gui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Lightweight animated circular spinner icon for Swing buttons and labels.
 * <p>
 * Draws a faint full-circle track plus a rotating 270° arc with rounded caps —
 * the classic "loading" indicator, sized to fit a toolbar button.
 * </p>
 * <p>
 * The animation is driven externally (typically a {@code javax.swing.Timer} on
 * the EDT): call {@link #advance(float)} and repaint the host component on
 * every tick.
 * </p>
 * <p>
 * Thread model: Swing UI object — use from the EDT only.
 * </p>
 */
public final class SpinnerIcon implements Icon {

    private final int size;
    private final Color color;
    private float angle;

    /**
     * @param size  icon size in pixels (at least 8)
     * @param color arc color (never null)
     */
    public SpinnerIcon(int size, Color color) {
        if (size < 8) {
            throw new IllegalArgumentException("size must be at least 8 px");
        }
        if (color == null) {
            throw new NullPointerException("color must not be null");
        }
        this.size = size;
        this.color = color;
    }

    /** Advances the arc rotation by the given number of degrees (EDT only). */
    public void advance(float degrees) {
        angle = (angle + degrees) % 360f;
        if (angle < 0f) {
            angle += 360f;
        }
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int d = size - 2;
            int cx = x + (size - d) / 2;
            int cy = y + (size - d) / 2;
            float stroke = Math.max(1.5f, size / 8f);
            // Faint full-circle track
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 60));
            g2.setStroke(new BasicStroke(stroke));
            g2.drawOval(cx, cy, d, d);
            // Rotating arc
            g2.setColor(color);
            g2.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawArc(cx, cy, d, d, (int) angle, 270);
        } finally {
            g2.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }
}