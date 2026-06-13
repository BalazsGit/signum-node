package application.gui.glassPanel;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;
import application.module.node.gui.GuiResources;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.geom.Rectangle2D;
import java.net.URL;

public class GlassPanel extends JPanel {

    private SVGDocument svgDocument;
    private final float baseAlpha; // The default alpha value if no animation is active
    private double fixedRotation = 0.0; // A közvetlenül beállított forgatás
    private double zoom = 1.0; // A közvetlenül beállított zoom
    private double fixedX = 0.0;
    private double fixedY = 0.0;
    private BufferedImage cachedImage;

    private final GlassPanelAnimationScheduler scheduler;

    public GlassPanel() {
        this.baseAlpha = 0.01f;
        setOpaque(false);
        setFocusable(false);
        loadResources(); // Load SVG resources
        setEnabled(false); // Teljesen tiltsuk le az interakciókat ezen a rétegen
        setRequestFocusEnabled(false);

        // Inicializáljuk az animáció ütemezőt
        this.scheduler = new GlassPanelAnimationScheduler(this, baseAlpha, 0.0, (float) zoom);
    }

    private void loadResources() {
        URL svgUrl = getClass().getClassLoader().getResource(GuiResources.SIGNUM_NODE_WHITE_SVG);
        if (svgUrl != null) {
            try {
                SVGLoader loader = new SVGLoader();
                this.svgDocument = loader.load(svgUrl);
            } catch (Exception e) {
                System.err.println("Failed to load SVG resources: " + e.getMessage());
            }
        } else {
            System.err.println("SVG not found: " + GuiResources.SIGNUM_NODE_WHITE_SVG);
        }
    }

    /**
     * Starts the "breathing" fade animation.
     */
    public void startBreathingFadeAnimation() {
        scheduler.addAnimation(new BreathingFadeAnimation(baseAlpha));
    }

    /**
     * Stops the "breathing" fade animation.
     */
    public void stopBreathingFadeAnimation() {
        scheduler.removeAnimation("BreathingFade");
    }

    public void startBreathingSizeAnimation() {
        scheduler.addAnimation(new BreathingSizeAnimation((float) zoom)); // Start breathing size animation
    }

    public void stopBreathingSizeAnimation() {
        scheduler.removeAnimation("BreathingSize"); // Stop breathing size animation
    }

    public void startRotateRightAnimation() {
        scheduler.addAnimation(new RotateRightAnimation()); // Start clockwise rotation animation
    }

    public void stopRotateRightAnimation() {
        scheduler.removeAnimation("RotateRight");
    }

    public void startRotateLeftAnimation() {
        scheduler.addAnimation(new RotateLeftAnimation()); // Start counter-clockwise rotation animation
    }

    public void stopRotateLeftAnimation() {
        scheduler.removeAnimation("RotateLeft");
    }

    /**
     * Starts the "fade-in" animation.
     */
    public void startFadeInAnimation() {
        scheduler.addAnimation(new FadeInAnimation(baseAlpha));
    }

    /**
     * Stops the "fade-in" animation.
     */
    public void stopFadeInAnimation() {
        scheduler.removeAnimation("FadeIn");
    }

    // Called by GlassPanelAnimationScheduler to set the GlassPanel's current alpha
    // value
    public void setEffectiveAlpha(float alpha) {
        // Ezt a metódust a scheduler hívja meg, hogy beállítsa a kombinált alfa értéket
        // A paintComponent majd ezt az értéket fogja használni
        // Nincs szükség külön mezőre itt, a paintComponent közvetlenül lekérdezi a
        // schedulertől
    }

    // Called by GlassPanelAnimationScheduler to set the GlassPanel's current
    // rotation value
    public void setEffectiveRotation(double rotation) {
        // Hasonlóan az alfához, a paintComponent közvetlenül lekérdezi a schedulertől
    }

    // Direct rotation setting (non-animated)
    public void setRotation(double degrees) {
        // Set the rotation directly (non-animated)
        this.fixedRotation = Math.toRadians(degrees);
        scheduler.setDefaultRotation(this.fixedRotation);
        repaint(); // Azonnali újrarajzolás a változás megjelenítéséhez
    }

    // Közvetlen zoom beállítása (nem animált)
    public void setZoom(double zoom) {
        // Set the zoom directly (non-animated)
        if (Double.compare(this.zoom, zoom) != 0) {
            this.zoom = zoom;
            scheduler.setDefaultSize((float) zoom);
            repaint();
        }
    }

    public void setPosition(double x, double y) {
        // Set the position directly (non-animated)
        this.fixedX = x;
        this.fixedY = y;
        repaint();
    }

    private void updateCache(int w, int h) {
        if (svgDocument == null || w <= 0 || h <= 0)
            return;

        cachedImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = cachedImage.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Rectangle2D viewBox = svgDocument.viewBox();
        double svgScale = Math.min(w / viewBox.getWidth(), h / viewBox.getHeight()) * 0.8;
        double tx = (w - viewBox.getWidth() * svgScale) / 2.0;
        double ty = (h - viewBox.getHeight() * svgScale) / 2.0;

        g2.translate(tx, ty);
        g2.scale(svgScale, svgScale);
        svgDocument.render(null, g2);
        g2.dispose();
    }

    public void setFixedAlpha(float alpha) {
        // Note: the scheduler currently uses baseAlpha as the baseline
    }

    @Override // Overrides the paintComponent method to draw the SVG document with animations
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        // Lekérdezzük az effektív alfa és forgatási értékeket a schedulertől
        float effectiveAlpha = scheduler.getEffectiveAlpha();
        double effectiveRotation = scheduler.getEffectiveRotation();
        Point2D effectivePos = scheduler.getEffectivePosition();
        float effectiveSize = scheduler.getEffectiveSize();

        if (svgDocument == null || effectiveAlpha <= 0) {
            return;
        }

        if (cachedImage == null || cachedImage.getWidth() != getWidth() || cachedImage.getHeight() != getHeight()) {
            updateCache(getWidth(), getHeight());
        }

        Graphics2D g2d = (Graphics2D) g.create();
        try {
            g2d.setComposite(
                    AlphaComposite.getInstance(
                            AlphaComposite.SRC_OVER,
                            Math.max(0f, Math.min(1f, effectiveAlpha))));

            int w = getWidth();
            int h = getHeight();
            double x = fixedX + effectivePos.getX();
            double y = fixedY + effectivePos.getY();

            // Centering + Position + Rotation + Zoom
            g2d.translate(w / 2.0 + x, h / 2.0 + y);
            g2d.rotate(effectiveRotation);
            g2d.scale(effectiveSize, effectiveSize);
            g2d.translate(-(w / 2.0 + x), -(h / 2.0 + y));

            g2d.drawImage(cachedImage, 0, 0, null);

        } finally {
            g2d.dispose();
        }
    }

    @Override
    public boolean contains(int x, int y) {
        return false;
    }
}
