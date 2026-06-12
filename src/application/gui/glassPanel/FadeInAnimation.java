package application.gui.glassPanel;

import java.awt.geom.Point2D;

/**
 * A "fade-in" animation that gradually increases the alpha value to a target
 * value.
 */
public class FadeInAnimation implements GlassPanelAnimation {

    private final float targetAlpha;
    private float currentAlphaValue;
    private long elapsedTime = 0;
    private final long durationMs = 500; // Animation duration in milliseconds

    public FadeInAnimation(float targetAlpha) {
        this.targetAlpha = targetAlpha;
        this.setCurrentAlphaValue(0.0f);
    }

    @Override
    public boolean update(long deltaTimeMs) {
        this.setElapsedTime(this.getElapsedTime() + deltaTimeMs);

        if (this.getElapsedTime() >= this.getDurationMs()) {
            this.setCurrentAlphaValue(this.getTargetAlpha());
            return false; // Animation finished, will be automatically removed by scheduler
        }

        float progress = (float) this.getElapsedTime() / this.getDurationMs();
        this.setCurrentAlphaValue(this.getTargetAlpha() * progress);
        return true;
    }

    // Private getters and setters
    private float getTargetAlpha() {
        return targetAlpha;
    }

    private float getCurrentAlphaValue() {
        return currentAlphaValue;
    }

    private void setCurrentAlphaValue(float currentAlphaValue) {
        this.currentAlphaValue = currentAlphaValue;
    }

    private long getElapsedTime() {
        return elapsedTime;
    }

    private void setElapsedTime(long elapsedTime) {
        this.elapsedTime = elapsedTime;
    }

    private long getDurationMs() {
        return durationMs;
    }

    @Override
    public String getType() {
        return "FadeIn";
    }

    @Override
    public Float getAlphaContribution() {
        return this.getCurrentAlphaValue();
    }

    @Override
    public Double getRotationContribution() {
        return null; // This animation does not affect rotation
    }

    @Override
    public Float getSizeContribution() {
        return null;
    }

    @Override
    public Point2D getPositionContribution() {
        return null;
    }

    @Override
    public void requestStop() {
        // This animation is short and one-shot, no special stop logic required.
    }
}
