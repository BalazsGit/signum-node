package application.gui.glassPanel;

import javax.swing.JFrame;

/**
 * Manager class for global access and lifecycle control of the GlassPanel
 * overlay.
 * This ensures the overlay infrastructure is independent of specific business
 * modules.
 */
public class GlassPanelManager {
    private static GlassPanel instance;

    /**
     * Initializes the GlassPanel on the provided JFrame if it hasn't been done yet.
     * This follows a singleton-like pattern to ensure only one overlay exists.
     * 
     * @param frame The main application window.
     */
    public static synchronized void initialize(JFrame frame) {
        if (frame == null) {
            return;
        }
        if (instance == null) {
            instance = new GlassPanel();
            frame.setGlassPane(instance);

            // Important: GlassPanel is set to be visible but returns false in contains(),
            // so it remains transparent to mouse events, preventing flickering on tabs.
            instance.setVisible(true);
        }
    }

    /**
     * Returns the global GlassPanel instance for starting/stopping animations.
     * 
     * @return The GlassPanel instance, or null if initialize() hasn't been called.
     */
    public static GlassPanel getInstance() {
        return instance;
    }
}