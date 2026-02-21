package brs.gui.util;

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
     * Abstract base class for symbols that can be rotated based on a
     * {@link Direction}.
     */
    public static abstract class RotatableSymbol implements Symbol {
        private final Direction direction;

        /**
         * Constructs a rotatable symbol pointing in the specified direction.
         *
         * @param direction The direction the symbol should point to.
         */
        protected RotatableSymbol(Direction direction) {
            this.direction = direction;
        }

        /**
         * Draws the symbol, applying rotation based on the configured direction.
         * <p>
         * This method handles the coordinate transformation and rotation logic,
         * delegating the actual shape drawing to
         * {@link #drawBaseShape(Graphics2D, int, int, int)}.
         * </p>
         *
         * @param g2    The graphics context.
         * @param w     The width of the area.
         * @param h     The height of the area.
         * @param color The color to draw with.
         */
        @Override
        public void draw(Graphics2D g2, int w, int h, Color color) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.5f));

            int padding = 4;
            if (w <= padding * 2 || h <= padding * 2) {
                return;
            }

            AffineTransform oldTransform = g2.getTransform();
            g2.translate(w / 2.0, h / 2.0);

            double angle = 0;
            int drawW = w;
            int drawH = h;

            switch (direction) {
                case UP:
                    angle = 0;
                    break;
                case RIGHT:
                    angle = Math.PI / 2;
                    drawW = h;
                    drawH = w;
                    break;
                case DOWN:
                    angle = Math.PI;
                    break;
                case LEFT:
                    angle = -Math.PI / 2;
                    drawW = h;
                    drawH = w;
                    break;
            }

            g2.rotate(angle);
            drawBaseShape(g2, drawW, drawH, padding);
            g2.setTransform(oldTransform);
        }

        /**
         * Draws the base shape of the symbol assuming an UP orientation.
         *
         * @param g2      The graphics context.
         * @param w       The width of the drawing area (possibly swapped if rotated).
         * @param h       The height of the drawing area (possibly swapped if rotated).
         * @param padding The padding to apply.
         */
        protected abstract void drawBaseShape(Graphics2D g2, int w, int h, int padding);
    }

    /**
     * Draws a chevron (arrowhead) character.
     * <p>
     * This character is typically used to indicate a collapsible panel or a
     * direction.
     * It looks like a 'V' shape.
     * </p>
     */
    public static class Chevron extends RotatableSymbol {
        /** A chevron pointing up. */
        public static final Chevron UP = new Chevron(Direction.UP);
        /** A chevron pointing down. */
        public static final Chevron DOWN = new Chevron(Direction.DOWN);
        /** A chevron pointing left. */
        public static final Chevron LEFT = new Chevron(Direction.LEFT);
        /** A chevron pointing right. */
        public static final Chevron RIGHT = new Chevron(Direction.RIGHT);

        private Chevron(Direction direction) {
            super(direction);
        }

        /**
         * Retrieves the Chevron instance corresponding to the given direction.
         *
         * @param direction The desired direction.
         * @return The Chevron instance for that direction.
         */
        public static Chevron get(Direction direction) {
            switch (direction) {
                case UP:
                    return UP;
                case DOWN:
                    return DOWN;
                case LEFT:
                    return LEFT;
                case RIGHT:
                    return RIGHT;
                default:
                    return UP;
            }
        }

        @Override
        protected void drawBaseShape(Graphics2D g2, int w, int h, int padding) {
            double arrowHeight = (h - padding * 2) / 2.0;
            double halfSpanX = (w - padding * 2) / 2.0;
            double halfSpanY = arrowHeight / 2.0;

            Path2D.Double path = new Path2D.Double();
            path.moveTo(-halfSpanX, halfSpanY);
            path.lineTo(0, -halfSpanY);
            path.lineTo(halfSpanX, halfSpanY);
            g2.draw(path);
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
}