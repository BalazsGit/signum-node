package brs.gui.util;

import brs.gui.util.CustomDrawings.Symbol;

import javax.swing.*;
import java.awt.*;

public class CustomDrawingIcon implements Icon {

    private final Symbol drawing;
    private final Color color;
    private final float scaleFactor;

    public CustomDrawingIcon(Symbol drawing, Color color) {
        this(drawing, color, 1.5f); // Default scale factor for table icons
    }

    public CustomDrawingIcon(Symbol drawing, Color color, float scaleFactor) {
        this.drawing = drawing;
        this.color = color;
        this.scaleFactor = scaleFactor;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        int iconSize = getIconSize(c);
        Graphics2D g2d = (Graphics2D) g.create();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.translate(x, y);
            Color finalColor = (this.color != null) ? this.color : c.getForeground();
            drawing.draw(g2d, iconSize, iconSize, finalColor);
        } finally {
            g2d.dispose();
        }
    }

    private int getIconSize(Component c) {
        Font font = (c != null) ? c.getFont() : UIManager.getFont("Table.font");
        if (font == null)
            font = UIManager.getFont("Label.font");
        return Math.round(font.getSize() * scaleFactor);
    }

    @Override
    public int getIconWidth() {
        return getIconSize(null);
    }

    @Override
    public int getIconHeight() {
        return getIconWidth();
    }
}