package application.module.database.gui;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Fallback database "logo" badge (brand-colored circle with a white monogram
 * letter), drawn on the fly when the engine's official logo asset is not
 * available on the classpath (see {@code DatabaseEngine#getLogoIcon}).
 * <p>
 * The normal branding is the official engine logo shipped under
 * {@code resources/images/databases/}; this badge only guarantees that the GUI
 * always has <i>some</i> engine icon, even if an asset is missing. It scales
 * cleanly at any size.
 * </p>
 */
public final class DatabaseEngineBadgeIcon implements Icon {

    private final DatabaseEngine engine;
    private final int size;

    /**
     * @param engine the engine the badge represents (not null)
     * @param size   the badge width/height in pixels (square)
     */
    public DatabaseEngineBadgeIcon(DatabaseEngine engine, int size) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        this.engine = engine;
        this.size = size;
    }

    /** Brand-ish color of the engine's badge fill (SSOT of engine branding). */
    public static Color brandColor(DatabaseEngine engine) {
        return switch (engine) {
            case SQLITE -> new Color(0x0F, 0x7E, 0x9C); // SQLite teal
            case MARIADB -> new Color(0x00, 0x5C, 0x99); // MariaDB blue
            case POSTGRESQL -> new Color(0x33, 0x67, 0x91); // PostgreSQL blue
        };
    }

    /** The single-letter monogram rendered on the badge (SSOT: display name initial). */
    public static String monogram(DatabaseEngine engine) {
        return String.valueOf(engine.getDisplayName().charAt(0));
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(brandColor(engine));
            g2.fillOval(x, y, size, size);
            g2.setColor(Color.WHITE);
            Font base = c != null ? c.getFont() : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
            g2.setFont(base.deriveFont(Font.BOLD, size * 0.55f));
            FontMetrics fm = g2.getFontMetrics();
            String letter = monogram(engine);
            int textX = x + (size - fm.stringWidth(letter)) / 2;
            int textY = y + (size - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(letter, textX, textY);
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