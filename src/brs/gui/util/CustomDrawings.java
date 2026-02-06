package brs.gui.util;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;

public class CustomDrawings {

    public enum Direction {
        UP, DOWN, LEFT, RIGHT
    }

    public interface Symbol {
        void draw(Graphics2D g2, int w, int h, Color color);
    }

    public static abstract class RotatableSymbol implements Symbol {
        private final Direction direction;

        protected RotatableSymbol(Direction direction) {
            this.direction = direction;
        }

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

        protected abstract void drawBaseShape(Graphics2D g2, int w, int h, int padding);
    }

    /**
     * Draws a chevron (arrowhead) character.
     * <p>
     * This character is typically used to indicate a collapsible panel or a
     * direction.
     * It looks like a 'V' shape.
     */
    public static class Chevron extends RotatableSymbol {
        public static final Chevron UP = new Chevron(Direction.UP);
        public static final Chevron DOWN = new Chevron(Direction.DOWN);
        public static final Chevron LEFT = new Chevron(Direction.LEFT);
        public static final Chevron RIGHT = new Chevron(Direction.RIGHT);

        private Chevron(Direction direction) {
            super(direction);
        }

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