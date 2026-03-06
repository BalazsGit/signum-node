package brs.gui;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.net.URL;

public class GlassPane extends JPanel {

    private SVGDocument svgDocument;
    private final float baseAlpha;
    private float currentAlpha;
    private final Timer timer;

    // Animation properties
    private boolean breathing = false;
    private boolean fadingIn = true;
    private double rotation = 0.0;
    private double zoom = 1.0;

    public GlassPane() {
        this.baseAlpha = 0.01f;
        this.currentAlpha = this.baseAlpha;
        setOpaque(false);
        setFocusable(false);

        loadResources();

        this.timer = createTimer();
    }

    private void loadResources() {
        URL svgUrl = getClass().getResource("/Signum_node_white.svg");
        if (svgUrl != null) {
            SVGLoader loader = new SVGLoader();
            this.svgDocument = loader.load(svgUrl);
        } else {
            System.err.println("SVG not found: /Signum_node_white.svg");
        }
    }

    public void setBreathing(boolean breathing) {
        this.breathing = breathing;
        if (!breathing) {
            this.currentAlpha = baseAlpha;
            repaint();
            timer.stop();
        } else {
            setVisible(true);
            timer.start();
        }
    }

    public void setRotation(double degrees) {
        this.rotation = Math.toRadians(degrees);
        repaint();
    }

    public void setZoom(double zoom) {
        this.zoom = zoom;
        repaint();
    }

    public void fadeIn() {
        this.currentAlpha = 0f;
        setVisible(true);
        Timer fadeTimer = new Timer(20, null);
        fadeTimer.addActionListener(e -> {
            currentAlpha += 0.005f;
            if (currentAlpha >= baseAlpha) {
                currentAlpha = baseAlpha;
                fadeTimer.stop();
            }
            repaint();
        });
        fadeTimer.start();
    }

    private Timer createTimer() {
        return new Timer(50, e -> {
            if (breathing) {
                float step = 0.001f;
                if (fadingIn) {
                    currentAlpha += step;
                    if (currentAlpha >= baseAlpha * 1.5f)
                        fadingIn = false;
                } else {
                    currentAlpha -= step;
                    if (currentAlpha <= baseAlpha * 0.8f)
                        fadingIn = true;
                }
                repaint();
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (currentAlpha <= 0 || svgDocument == null) {
            return;
        }

        Graphics2D g2d = (Graphics2D) g.create();
        try {
            g2d.setComposite(
                    AlphaComposite.getInstance(
                            AlphaComposite.SRC_OVER,
                            Math.max(0f, Math.min(1f, currentAlpha))));

            int w = getWidth();
            int h = getHeight();

            // Center + rotation + zoom
            g2d.translate(w / 2.0, h / 2.0);
            g2d.rotate(rotation);
            g2d.scale(zoom, zoom);
            g2d.translate(-w / 2.0, -h / 2.0);

            Rectangle2D viewBox = svgDocument.viewBox();

            double imgW = viewBox.getWidth();
            double imgH = viewBox.getHeight();

            if (imgW > 0 && imgH > 0) {
                double scale = Math.min(w / imgW, h / imgH) * 0.8;
                double x = (w - imgW * scale) / 2.0;
                double y = (h - imgH * scale) / 2.0;

                g2d.translate(x, y);
                g2d.scale(scale, scale);

                svgDocument.render(null, g2d);
            }

        } finally {
            g2d.dispose();
        }
    }

    @Override
    public boolean contains(int x, int y) {
        return false;
    }
}
