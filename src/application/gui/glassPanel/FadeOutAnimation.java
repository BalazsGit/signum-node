package application.gui.glassPanel;

import java.awt.geom.Point2D;

/**
 * A "fade-out" animation that gradually decreases the alpha value to zero.
 */
public class FadeOutAnimation implements GlassPanelAnimation {

    private float startAlpha;
    private float currentAlphaValue;
    private long elapsedTime = 0;
    private final long durationMs = 500; // Animation duration in milliseconds

    public FadeOutAnimation(float startAlpha) {
        this.setStartAlpha(startAlpha);
        this.setCurrentAlphaValue(startAlpha);
    }

    @Override
    public boolean update(long deltaTimeMs) {
        this.setElapsedTime(this.getElapsedTime() + deltaTimeMs);

        if (this.getElapsedTime() >= this.getDurationMs()) {
            this.setCurrentAlphaValue(0.0f);
            return false; // Animation finished, will be automatically removed by scheduler
        }

        float progress = (float) this.getElapsedTime() / this.getDurationMs();
        this.setCurrentAlphaValue(this.getStartAlpha() * (1.0f - progress));
        return true;
    }

    @Override
    public String getType() {
        return "FadeOut";
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

    // Private getters and setters
    private float getStartAlpha() {
        return startAlpha;
    }

    private void setStartAlpha(float startAlpha) {
        this.startAlpha = startAlpha;
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
}