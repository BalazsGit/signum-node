package application.utils.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.Icon;
import javax.swing.UIManager;

/**
 * The thin, hand-drawn "+" glyph, rendered offscreen exactly like the FontAwesome
 * toolbar icons: the "+" is pre-drawn into a small cached {@link BufferedImage} and
 * blitted (instead of being re-stroked with line segments on every paint), so it
 * scales cleanly on hover — the {@link HoverScaleIcon} pair simply swaps two
 * pre-rendered images.
 * <p>
 * The glyph is sized to the <em>same non-square box the FontAwesome siblings
 * expose</em> (a width slightly smaller than the height, exactly as
 * {@code IconFontSwing.buildIcon} derives it from the font metrics). Filling that
 * box — rather than a small centred square — is what makes the "+" read as the same
 * visual size as the other toolbar icons and grow on hover in step with them.
 * <p>
 * Both arms are equal and their endpoints are snapped to half-pixel steps, so the
 * anti-aliased "+" is symmetric about its centre (no lopsided arms from sub-pixel
 * drift). The arm and stroke are fractions of the shorter box side, so the line
 * stays thin and crisp at any size (it scales with the font, in the same units as
 * the rest of the toolbar).
 * <p>
 * It is the single source of every "+" button: the browser's two plus buttons and
 * the tab strip's trailing "+" (static), and the "new profile" action in the
 * configuration panels — installed as a grow-on-hover pair by
 * {@link ProfileActionIcon#NEW_PROFILE} in the header toolbars, and as a static,
 * non-growing glyph in the inline list-editor add rows (the flat "+" of the
 * "Allowed IPs", "Admin Password" and database-file lists).
 */
public final class PlusGlyphIcon implements Icon {

    /** The default square size (matches the tab strip's close "X"). */
    public static final int DEFAULT_SIZE = 16;
    /** The equal arm as a fraction of the shorter box side (the "+" fills the box like a FontAwesome glyph). */
    private static final float ARM_FRACTION = 0.40f;
    /** The stroke as a fraction of the shorter box side (a thin line that scales with the size). */
    private static final float STROKE_FRACTION = 0.085f;
    private static final Map<String, BufferedImage> CACHE = new ConcurrentHashMap<>();
    private static final int MAX_CACHE = 512;

    private final int width;
    private final int height;
    private final BufferedImage image;

    public PlusGlyphIcon() {
        this(DEFAULT_SIZE);
    }

    public PlusGlyphIcon(int size) {
        this(size, size, controlTextColor());
    }

    public PlusGlyphIcon(int size, Color color) {
        this(size, size, color);
    }

    /**
     * Creates the glyph sized to a specific (possibly non-square) box.
     *
     * @param width  the icon width (typically the FontAwesome sibling box width)
     * @param height the icon height (typically the FontAwesome sibling box height)
     * @param color  the glyph colour; a {@code null} color uses the L&F control text colour
     */
    public PlusGlyphIcon(int width, int height, Color color) {
        this.width = Math.max(8, width);
        this.height = Math.max(8, height);
        this.image = imageFor(this.width, this.height, color != null ? color : controlTextColor());
    }

    @Override
    public int getIconWidth() {
        return width;
    }

    @Override
    public int getIconHeight() {
        return height;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        g.drawImage(image, x, y, null);
    }

    private static BufferedImage imageFor(int width, int height, Color color) {
        String key = width + "x" + height + "|" + color.getRGB();
        BufferedImage img = CACHE.get(key);
        if (img == null) {
            img = render(width, height, color);
            if (CACHE.size() < MAX_CACHE) {
                BufferedImage prev = CACHE.putIfAbsent(key, img);
                img = prev != null ? prev : img;
            }
        }
        return img;
    }

    private static BufferedImage render(int width, int height, Color color) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(color);
        g.setStroke(new BasicStroke(strokeFor(width, height)));
        float cx = width / 2f;
        float cy = height / 2f;
        float arm = armFor(width, height);
        g.draw(new Line2D.Float(cx - arm, cy, cx + arm, cy));
        g.draw(new Line2D.Float(cx, cy - arm, cx, cy + arm));
        g.dispose();
        return img;
    }

    /** The equal arm length, snapped to half-pixel steps so both endpoints share a sub-pixel offset. */
    private static float armFor(int width, int height) {
        float side = Math.min(width, height);
        return Math.max(2f, Math.round(side * ARM_FRACTION * 2f) / 2f);
    }

    /** The stroke width, proportional to the glyph size so the line stays thin at any scale. */
    private static float strokeFor(int width, int height) {
        return Math.max(1f, Math.min(width, height) * STROKE_FRACTION);
    }

    private static Color controlTextColor() {
        Color c = UIManager.getColor("controlText");
        return c != null ? c : new Color(0x20, 0x21, 0x24);
    }
}
