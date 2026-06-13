package application.gui.glassPanel;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central scheduler for GlassPanel animations.
 * Manages all active animations, updates their states,
 * and ensures the GlassPanel is repainted.
 */
public class GlassPanelAnimationScheduler implements ActionListener {

    private final GlassPanel glassPanel;
    private final ConcurrentHashMap<String, GlassPanelAnimation> activeAnimations;
    private final Timer timer;
    private long lastUpdateTime;

    private float defaultAlpha;
    private double defaultRotation;
    private float defaultSize;

    public GlassPanelAnimationScheduler(GlassPanel glassPanel, float defaultAlpha, double defaultRotation,
            float defaultSize) {
        this.glassPanel = glassPanel;
        this.defaultAlpha = defaultAlpha;
        this.defaultRotation = defaultRotation;
        this.defaultSize = defaultSize;
        this.activeAnimations = new ConcurrentHashMap<>();
        this.timer = new Timer(50, this); // Updates every 50 ms
        this.timer.setCoalesce(true); // Coalesces consecutive animation events
    }

    /**
     * Adds an animation to the scheduler.
     * If an animation of the same type already exists, it will be overwritten.
     *
     * @param animation The animation to be added.
     */
    public void addAnimation(GlassPanelAnimation animation) {
        System.out.println("[DEBUG-Scheduler] Adding animation: " + animation.getType());
        activeAnimations.put(animation.getType(), animation);
        if (!timer.isRunning()) {
            start();
        }
    }

    /**
     * Requests an animation to stop based on its type identifier.
     * The animation will be removed automatically by the update loop once it
     * reaches its base state.
     *
     * @param type The type of the animation to be removed.
     */
    public void removeAnimation(String type) {
        GlassPanelAnimation anim = activeAnimations.get(type);
        System.out.println("[DEBUG-Scheduler] Requesting stop for animation: " + type);
        if (anim != null) {
            anim.requestStop();
        }
    }

    /**
     * Forces immediate removal of an animation.
     */
    public void forceRemoveAnimation(String type) {
        activeAnimations.remove(type);
        System.out.println("[DEBUG-Scheduler] Forcing removal of animation: " + type);
        checkIdle();
    }

    private void checkIdle() {
        if (activeAnimations.isEmpty() && timer.isRunning()) {
            stop();
            System.out.println("[DEBUG-Scheduler] Timer stopped, triggering final repaint.");
            glassPanel.repaint();
        }
    }

    /**
     * Returns an animation based on its type identifier.
     *
     * @param type The type of the animation.
     * @return The animation instance, or null if not found.
     */
    public GlassPanelAnimation getAnimation(String type) {
        return activeAnimations.get(type);
    }

    /**
     * Starts the scheduler.
     */
    public void start() {
        System.out.println("[DEBUG-Scheduler] Starting Timer...");
        lastUpdateTime = System.currentTimeMillis();
        timer.start();
    }

    /**
     * Stops the scheduler.
     */
    public void stop() {
        System.out.println("[DEBUG-Scheduler] Stopping Timer...");
        timer.stop();
    }

    /**
     * Returns the number of all active animations.
     *
     * @return The number of active animations.
     */
    public int getActiveAnimationCount() {
        return activeAnimations.size();
    }

    public void setDefaultAlpha(float alpha) {
        this.defaultAlpha = alpha;
    }

    public void setDefaultRotation(double rotation) {
        this.defaultRotation = rotation;
    }

    public void setDefaultSize(float size) {
        this.defaultSize = size;
    }

    /**
     * Calculates the current effective alpha value based on all active animations.
     *
     * @return The effective alpha value.
     */
    public float getEffectiveAlpha() {
        float effectiveAlpha = defaultAlpha;
        for (GlassPanelAnimation animation : activeAnimations.values()) {
            Float alpha = animation.getAlphaContribution();
            if (alpha != null) {
                // Example combination logic: the highest alpha value wins
                effectiveAlpha = Math.max(effectiveAlpha, alpha);
            }
        }
        return effectiveAlpha;
    }

    /**
     * Calculates the current effective rotation value based on all active
     * animations.
     *
     * @return The effective rotation value in radians.
     */
    public double getEffectiveRotation() {
        double effectiveRotation = defaultRotation;
        // Example combination logic: the rotation of the last added/updated animation
        // wins
        // Since HashMap does not guarantee order, the last added animation's
        // rotation will prevail if there are multiple.
        // If specific priority is needed, it could be handled using the
        // animations' getType() method.
        for (GlassPanelAnimation animation : activeAnimations.values()) {
            Double rotation = animation.getRotationContribution();
            if (rotation != null) {
                effectiveRotation = rotation;
            }
        }
        return effectiveRotation;
    }

    /**
     * Calculates the current effective position offset based on all active
     * animations.
     *
     * @return The effective position offset.
     */
    public Point2D getEffectivePosition() {
        double x = 0;
        double y = 0;
        for (GlassPanelAnimation animation : activeAnimations.values()) {
            Point2D offset = animation.getPositionContribution();
            if (offset != null) {
                x += offset.getX();
                y += offset.getY();
            }
        }
        return new Point2D.Double(x, y);
    }

    /**
     * Calculates the current effective size based on all active animations.
     *
     * @return The effective size value.
     */
    public float getEffectiveSize() {
        float effectiveSize = defaultSize;
        for (GlassPanelAnimation animation : activeAnimations.values()) {
            Float size = animation.getSizeContribution();
            if (size != null) {
                effectiveSize = size;
            }
        }
        return effectiveSize;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (activeAnimations.isEmpty()) {
            checkIdle();
            return;
        }

        long currentTime = System.currentTimeMillis();
        long deltaTimeMs = currentTime - lastUpdateTime;
        lastUpdateTime = currentTime;

        // Using CopyOnWriteArrayList is safer when iterating over
        // ConcurrentHashMap.values()
        // if the update method can remove animations.
        // Although ConcurrentHashMap handles concurrent modifications, this approach
        // ensures that the iteration occurs on a snapshot.
        List<GlassPanelAnimation> animationsSnapshot = new CopyOnWriteArrayList<>(activeAnimations.values());

        boolean animationStateChanged = false;

        for (GlassPanelAnimation animation : animationsSnapshot) {
            if (!animation.update(deltaTimeMs)) {
                // Az animáció befejeződött, eltávolítjuk
                // The animation has finished, remove it
                activeAnimations.remove(animation.getType());
                animationStateChanged = true; // Jelöljük, hogy történt változás
            }
        }

        if (!activeAnimations.isEmpty() || animationStateChanged) {
            glassPanel.repaint();
        }

        if (activeAnimations.isEmpty()) {
            checkIdle();
        }
    }
}
