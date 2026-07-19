package application.utils.gui;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;

/**
 * Utility class containing custom drawing implementations for GUI components.
 * <p>
 * This class provides a collection of reusable symbols and icons that can be
 * drawn onto a {@link Graphics2D} context. It includes support for rotatable
 * symbols and predefined shapes like chevrons and hamburger menus.
 * </p>
 * <p>
 * Chevron variants are organized under {@link Chevron}, allowing multiple
 * visual styles ({@code Heavy}, {@code Standard}, {@code Flat}) to coexist.
 * Each variant exposes directionally-rotated symbols via {@link Symbol}
 * instances (UP, DOWN, LEFT, RIGHT). The shape's form is preserved during
 * rotation — only orientation changes, not proportions.
 * </p>
 */
public class CustomDrawings {

    // ── Direction enum ─────────────────────────────────────────────────────

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

    // ── Chevron style enum ─────────────────────────────────────────────────

    /**
     * Visual styles for chevron arrows. Each style defines a distinct
     * arrow height relative to the available space, controlling the
     * sharpness of the chevron's peak.
     */
    public enum ChevronStyle {
        /**
         * Heavy (sharp) chevron — narrow angle with a tall peak.
         * Arrow height spans ~25% of available space (divisor 4.0).
         */
        HEAVY(4.0),

        /**
         * Standard chevron — natural, well-balanced proportions.
         * Arrow height spans ~40% of available space (divisor 2.5).
         */
        STANDARD(2.5),

        /**
         * Flat chevron — wide angle with a shallow profile.
         * Arrow height spans ~33% of available space (divisor 3.0).
         */
        FLAT(3.0);

        private final double heightDivisor;

        ChevronStyle(double heightDivisor) {
            this.heightDivisor = heightDivisor;
        }

        public double getHeightDivisor() {
            return heightDivisor;
        }
    }

    // ── Symbol interface ───────────────────────────────────────────────────

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

    // ── Rotation constants ─────────────────────────────────────────────────

    /** 90° rotation in radians, clearly documented (avoids raw Math.PI). */
    private static final double QUADRANT = Math.toRadians(90);

    // ── RotatedSymbol wrapper ──────────────────────────────────────────────

    /**
     * A wrapper class that takes a base Symbol and rotates it according to a
     * given Direction using true affine transformation (no dimension swapping).
     * The shape's form is preserved; only its orientation changes.
     */
    private static final class RotatedSymbol implements Symbol {
        private final Symbol baseSymbol;
        private final double angle;

        /**
         * Creates a new rotated symbol.
         *
         * @param baseSymbol The symbol to rotate.
         * @param direction  The direction to rotate to.
         */
        RotatedSymbol(Symbol baseSymbol, Direction direction) {
            this.baseSymbol = baseSymbol;
            this.angle = switch (direction) {
                case RIGHT -> QUADRANT;          // +90°
                case DOWN -> QUADRANT * 2;       // +180°
                case LEFT -> -QUADRANT;          // -90° (=270°)
                case UP -> 0;                    // no rotation
            };
        }

        @Override
        public void draw(Graphics2D g2, int w, int h, Color color) {
            AffineTransform oldTransform = g2.getTransform();
            try {
                // Rotate around the center of the bounding box
                g2.translate(w / 2.0, h / 2.0);
                g2.rotate(angle);
                g2.translate(-w / 2.0, -h / 2.0);

                // Pass original dimensions through — no swapping, shape preserved
                baseSymbol.draw(g2, w, h, color);
            } finally {
                g2.setTransform(oldTransform);
            }
        }
    }

    // ── Helper: rotate a symbol to the given direction ─────────────────────

    /**
     * Creates a rotated copy of the given symbol for the specified direction.
     */
    private static Symbol rotate(Symbol base, Direction direction) {
        return new RotatedSymbol(base, direction);
    }

    // ── Chevron drawing factories ──────────────────────────────────────────

    /**
     * Common rendering setup for all chevron variants.
     */
    private static void applyChevronStyle(Graphics2D g2, Color color) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
    }

    /**
     * Factory method to create an upward-pointing chevron arrow of the
     * specified style. The chevron's shape is defined by its height divisor:
     * a larger divisor produces a flatter (shorter peak) chevron, while
     * a smaller divisor produces a sharper (taller peak) chevron.
     *
     * @param style the visual style determining arrow proportions
     * @return a Symbol drawing an upward-pointing chevron of that style
     */
    private static Symbol createChevronUp(ChevronStyle style) {
        final double divisor = style.getHeightDivisor();

        return (g2, w, h, color) -> {
            applyChevronStyle(g2, color);

            int padding = 4;
            if (w <= padding * 2 || h <= padding * 2) {
                return;
            }

            // Arrow height determined by style divisor — shape is fixed per style
            double arrowHeight = (h - padding * 2.0) / divisor;
            double halfSpanY = arrowHeight / 2.0;

            Path2D.Double path = new Path2D.Double();
            path.moveTo(padding, h / 2.0 + halfSpanY);
            path.lineTo(w / 2.0, h / 2.0 - halfSpanY);
            path.lineTo(w - padding, h / 2.0 + halfSpanY);
            g2.draw(path);
        };
    }

    // ── Base (UP) symbols for each style ───────────────────────────────────

    private static final Symbol CHEVRON_HEAVY_UP = createChevronUp(ChevronStyle.HEAVY);
    private static final Symbol CHEVRON_STANDARD_UP = createChevronUp(ChevronStyle.STANDARD);
    private static final Symbol CHEVRON_FLAT_UP = createChevronUp(ChevronStyle.FLAT);

    // ── Public Chevron variants ────────────────────────────────────────────

    /**
     * A collection of pre-rotated Chevron arrow symbols organized by style.
     * <p>
     * Each inner class represents a distinct visual variant and exposes
     * directionally-rotated {@link Symbol} constants (UP, DOWN, LEFT, RIGHT).
     * The shape's form is preserved across all directions — rotation only
     * changes orientation, not proportions.
     * </p>
     * <p>
     * For backward compatibility, the outer class also re-exposes the
     * {@code Flat} variants directly as {@code Chevron.UP}, etc.
     * </p>
     */
    public static final class Chevron {

        /**
         * Heavy (sharp) chevron arrow — narrow angle, tall peak.
         */
        public static final class Heavy {
            public static final Symbol UP = CHEVRON_HEAVY_UP;
            public static final Symbol DOWN = rotate(UP, Direction.DOWN);
            public static final Symbol LEFT = rotate(UP, Direction.LEFT);
            public static final Symbol RIGHT = rotate(UP, Direction.RIGHT);

            private Heavy() {
            }
        }

        /**
         * Standard chevron arrow — natural proportions, well-balanced shape.
         */
        public static final class Standard {
            public static final Symbol UP = CHEVRON_STANDARD_UP;
            public static final Symbol DOWN = rotate(UP, Direction.DOWN);
            public static final Symbol LEFT = rotate(UP, Direction.LEFT);
            public static final Symbol RIGHT = rotate(UP, Direction.RIGHT);

            private Standard() {
            }
        }

        /**
         * Flat chevron arrow — wide angle, shallow profile.
         */
        public static final class Flat {
            public static final Symbol UP = CHEVRON_FLAT_UP;
            public static final Symbol DOWN = rotate(UP, Direction.DOWN);
            public static final Symbol LEFT = rotate(UP, Direction.LEFT);
            public static final Symbol RIGHT = rotate(UP, Direction.RIGHT);

            private Flat() {
            }
        }

        // ── Backward compatibility aliases (default to Flat) ──────────────

        /** @see Flat#UP */
        public static final Symbol UP = Flat.UP;
        /** @see Flat#DOWN */
        public static final Symbol DOWN = Flat.DOWN;
        /** @see Flat#LEFT */
        public static final Symbol LEFT = Flat.LEFT;
        /** @see Flat#RIGHT */
        public static final Symbol RIGHT = Flat.RIGHT;

        private Chevron() {
        }
    }

    // ── Other symbols ──────────────────────────────────────────────────────

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
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));

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