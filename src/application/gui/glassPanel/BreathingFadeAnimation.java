package application.gui.glassPanel;

import java.awt.geom.Point2D;

/**
 * A "breathing" animation that changes the alpha value periodically.
 */
public class BreathingFadeAnimation implements GlassPanelAnimation {

    private float baseAlpha;
    private float currentAlphaValue;
    private boolean fadingIn = true;
    private long elapsedTime = 0;
    private long durationMs = 2000; // Duration of a full breath (in and out)
    private float alphaRange = 0.005f; // Degree of alpha change around baseAlpha
    private boolean stopRequested = false;

    public BreathingFadeAnimation(float baseAlpha) {
        this.setBaseAlpha(baseAlpha);
        this.setCurrentAlphaValue(baseAlpha);
    }

    @Override
    public boolean update(long deltaTimeMs) {
        this.setElapsedTime(this.getElapsedTime() + deltaTimeMs);

        if (this.isStopRequested()) {
            // Return to base alpha
            float step = this.getAlphaRange() * (deltaTimeMs / (float) this.getDurationMs());
            if (Math.abs(this.getCurrentAlphaValue() - this.getBaseAlpha()) <= step) {
                this.setCurrentAlphaValue(this.getBaseAlpha());
                return false;
            }
            this.setCurrentAlphaValue(
                    this.getCurrentAlphaValue() + (this.getCurrentAlphaValue() < this.getBaseAlpha() ? step : -step));
            return true;
        }

        // Alpha animation
        float alphaStep = this.getAlphaRange() * (deltaTimeMs / (float) this.getDurationMs());
        if (this.isFadingIn()) {
            this.setCurrentAlphaValue(this.getCurrentAlphaValue() + alphaStep);
            if (this.getCurrentAlphaValue() >= this.getBaseAlpha() + this.getAlphaRange()) {
                this.setCurrentAlphaValue(this.getBaseAlpha() + this.getAlphaRange());
                this.setFadingIn(false);
            }
        } else {
            this.setCurrentAlphaValue(this.getCurrentAlphaValue() - alphaStep);
            if (this.getCurrentAlphaValue() <= this.getBaseAlpha() - this.getAlphaRange()) {
                this.setCurrentAlphaValue(this.getBaseAlpha() - this.getAlphaRange());
                this.setFadingIn(true);
            }
        }

        return true; // This animation runs continuously, never finishes on its own
    }

    @Override
    public void requestStop() {
        this.setStopRequested(true);
    }

    // Private getters and setters
    private float getBaseAlpha() {
        return baseAlpha;
    }

    private void setBaseAlpha(float baseAlpha) {
        this.baseAlpha = baseAlpha;
    }

    private float getCurrentAlphaValue() {
        return currentAlphaValue;
    }

    private void setCurrentAlphaValue(float currentAlphaValue) {
        this.currentAlphaValue = currentAlphaValue;
    }

    private boolean isFadingIn() {
        return fadingIn;
    }

    private void setFadingIn(boolean fadingIn) {
        this.fadingIn = fadingIn;
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

    private float getAlphaRange() {
        return alphaRange;
    }

    private void setAlphaRange(float alphaRange) {
        this.alphaRange = alphaRange;
    }

    private boolean isStopRequested() {
        return stopRequested;
    }

    private void setStopRequested(boolean stopRequested) {
        this.stopRequested = stopRequested;
    }

    @Override
    public String getType() {
        return "BreathingFade";
    }

    @Override
    public Float getAlphaContribution() {
        return this.getCurrentAlphaValue();
    }

    @Override
    public Double getRotationContribution() {
        return null;
    }

    @Override
    public Float getSizeContribution() {
        return null;
    }

    @Override
    public Point2D getPositionContribution() {
        return null;
    }
}
