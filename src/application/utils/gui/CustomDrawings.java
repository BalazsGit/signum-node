package application.utils.gui;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;

/**
 * Utility class containing custom drawing implementations for GUI components.
 * <p>
 * This class provides a collection of reusable symbols and icons that can be
 * drawn
 * onto a {@link Graphics2D} context. It includes support for rotatable symbols
 * and predefined shapes like chevrons and hamburger menus.
 * </p>
 */
public class CustomDrawings {

    /**
     * Enumeration representing cardinal directions.
     * Used for orienting rotatable symbols.
     */
    public enum Direction {
        /** Upward direction. */
        UP,
        /** Downward direction. */
        DOWN,
        /** Leftward direction. */
        LEFT,
        /** Rightward direction. */
        RIGHT
    }

    /**
     * Functional interface for drawing a symbol.
     */
    public interface Symbol {
        /**
         * Draws the symbol within the specified dimensions.
         *
         * @param g2    The graphics context to draw on.
         * @param w     The width of the drawing area.
         * @param h     The height of the drawing area.
         * @param color The color to use for drawing.
         */
        void draw(Graphics2D g2, int w, int h, Color color);
    }

    /**
     * A wrapper class that takes a base Symbol and rotates it according to a given
     * Direction.
     */
    private static class RotatedSymbol implements Symbol {
        private final Symbol baseSymbol;
        private final double angle;
        private final boolean swapDimensions;

        /**
         * Creates a new rotated symbol.
         *
         * @param baseSymbol The symbol to rotate.
         * @param direction  The direction to rotate to.
         */
        RotatedSymbol(Symbol baseSymbol, Direction direction) {
            this.baseSymbol = baseSymbol;
            switch (direction) {
                case RIGHT:
                    this.angle = Math.PI / 2;
                    this.swapDimensions = true;
                    break;
                case DOWN:
                    this.angle = Math.PI;
                    this.swapDimensions = false;
                    break;
                case LEFT:
                    this.angle = -Math.PI / 2;
                    this.swapDimensions = true;
                    break;
                case UP:
                default:
                    this.angle = 0;
                    this.swapDimensions = false;
                    break;
            }
        }

        @Override
        public void draw(Graphics2D g2, int w, int h, Color color) {
            AffineTransform oldTransform = g2.getTransform();
            try {
                g2.translate(w / 2.0, h / 2.0);
                g2.rotate(angle);

                int drawW = swapDimensions ? h : w;
                int drawH = swapDimensions ? w : h;

                // Translate back to origin for the base symbol to draw correctly
                g2.translate(-drawW / 2.0, -drawH / 2.0);

                baseSymbol.draw(g2, drawW, drawH, color);
            } finally {
                g2.setTransform(oldTransform);
            }
        }
    }

    /**
     * The base symbol for a chevron, pointing up by default.
     */
    private static final Symbol BASE_CHEVRON = (g2, w, h, color) -> {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        int padding = 4;
        if (w <= padding * 2 || h <= padding * 2) {
            return;
        }

        // Make the chevron flatter (wider angle) by reducing its height relative to the
        // container.
        // A divisor of 3.0 makes the chevron's width roughly 3 times its height in a
        // square container.
        double arrowHeight = (h - padding * 2.0) / 3.0;
        double halfSpanY = arrowHeight / 2.0;

        Path2D.Double path = new Path2D.Double();
        path.moveTo(padding, h / 2.0 + halfSpanY);
        path.lineTo(w / 2.0, h / 2.0 - halfSpanY);
        path.lineTo(w - padding, h / 2.0 + halfSpanY);
        g2.draw(path);
    };

    /**
     * A collection of pre-rotated Chevron symbols.
     */
    public static final class Chevron {
        public static final Symbol UP = BASE_CHEVRON;
        public static final Symbol DOWN = new RotatedSymbol(BASE_CHEVRON, Direction.DOWN);
        public static final Symbol LEFT = new RotatedSymbol(BASE_CHEVRON, Direction.LEFT);
        public static final Symbol RIGHT = new RotatedSymbol(BASE_CHEVRON, Direction.RIGHT);

        private Chevron() {
        }
    }

    /**
     * Draws a hamburger menu icon (three horizontal lines).
     */
    public static final Symbol HAMBURGER = (g2, w, h, color) -> {
        g2.setColor(color);
        int barHeight = Math.max(2, h / 7);
        int spacing = barHeight + 2;
        int totalHeight = 3 * barHeight + 2 * spacing;
        int yOffset = (h - totalHeight) / 2;

        for (int i = 0; i < 3; i++) {
            g2.fillRect(0, yOffset + i * (barHeight + spacing), w, barHeight);
        }
    };

    /**
     * Draws a command symbol (chevron pointing right followed by a colon).
     */
    public static final Symbol COMMAND_SYMBOL = (g2, w, h, color) -> {
        g2.setColor(color);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        int cy = h / 2;
        int cx = w / 2;

        Path2D.Double path = new Path2D.Double();
        path.moveTo(cx - 5, cy - 4);
        path.lineTo(cx, cy);
        path.lineTo(cx - 5, cy + 4);
        g2.draw(path);

        g2.fillOval(cx + 3, cy - 3, 2, 2);
        g2.fillOval(cx + 3, cy + 2, 2, 2);
    };
}