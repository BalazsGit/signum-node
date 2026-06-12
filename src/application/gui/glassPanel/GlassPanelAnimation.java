package application.gui.glassPanel;

import java.awt.geom.Point2D;

/**
 * Interface for GlassPanel animations.
 * Every animation must implement this interface to be managed by the
 * GlassPanelAnimationScheduler.
 */
public interface GlassPanelAnimation {

    /**
     * Updates the state of the animation based on elapsed time.
     *
     * @param deltaTimeMs Time elapsed since the last update in milliseconds.
     * @return true if the animation is still active and should continue, false if
     *         it
     *         has finished.
     */
    boolean update(long deltaTimeMs);

    /**
     * Returns the unique identifier of the animation type.
     * This ID can be used for managing animations (e.g., removal, overwriting).
     *
     * @return The string identifier of the animation type.
     */
    String getType();

    /**
     * Returns the current alpha (transparency) contribution of the animation.
     *
     * @return The current alpha value (between 0.0 and 1.0), or null if this
     *         animation does not affect alpha.
     */
    Float getAlphaContribution();

    /**
     * Returns the current rotation contribution of the animation.
     *
     * @return The current rotation value in radians, or null if this animation
     *         does not affect rotation.
     */
    Double getRotationContribution();

    /**
     * Returns the current size contribution of the animation.
     *
     * @return The current size value, or null if this animation does not affect
     *         size.
     */
    Float getSizeContribution();

    /**
     * Returns the current position offset contribution of the animation.
     *
     * @return The current position offset, or null if this animation does not
     *         affect position.
     */
    Point2D getPositionContribution();

    /**
     * Requests the animation to stop gracefully at its next logical base/end state.
     */
    void requestStop();
}
