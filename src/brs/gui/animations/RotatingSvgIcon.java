package brs.gui.animations;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.net.URL;

/**
 * A component that displays a rotating SVG icon.
 * Used to indicate an ongoing background process.
 */
public class RotatingSvgIcon extends JComponent {
    private SVGDocument svgDocument;
    private double rotation = 0.0;
    private final Timer timer;

    /**
     * Creates a new rotating icon.
     * 
     * @param rotationSpeedHz The rotation speed in Hertz (rotations per second).
     */
    public RotatingSvgIcon(double rotationSpeedHz) {
        setOpaque(false);
        loadResources();

        int delay = 20; // ms for smooth animation
        double degreesPerTick = 360.0 * rotationSpeedHz * (delay / 1000.0);

        this.timer = new Timer(delay, e -> {
            rotation += Math.toRadians(degreesPerTick);
            if (rotation >= 2 * Math.PI) {
                rotation -= 2 * Math.PI;
            }
            repaint();
        });
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

    public void start() {
        timer.start();
    }

    public void stop() {
        timer.stop();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (svgDocument == null)
            return;

        Graphics2D g2d = (Graphics2D) g.create();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();

            g2d.translate(w / 2.0, h / 2.0);
            g2d.rotate(rotation);
            g2d.translate(-w / 2.0, -h / 2.0);

            Rectangle2D viewBox = svgDocument.viewBox();
            double imgW = viewBox.getWidth();
            double imgH = viewBox.getHeight();

            if (imgW > 0 && imgH > 0) {
                double scale = Math.min((double) w / imgW, (double) h / imgH);
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
}