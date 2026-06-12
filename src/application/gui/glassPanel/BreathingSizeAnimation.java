package application.gui.glassPanel;

import java.awt.geom.Point2D;

/**
 * A "breathing" animation that changes the size (zoom) value.
 */
public class BreathingSizeAnimation implements GlassPanelAnimation {

    private float startSize;
    private float currentSizeValue;
    private boolean growing = true;
    private long elapsedTime = 0;
    private long durationMs = 2000; // Duration of a full breath (in and out)
    private float sizeRange = 0.1f; // How much the size changes
    private boolean stopRequested = false;

    public BreathingSizeAnimation(float startSize) {
        this.setStartSize(startSize);
        this.setCurrentSizeValue(startSize);
    }

    @Override
    public boolean update(long deltaTimeMs) {
        this.setElapsedTime(this.getElapsedTime() + deltaTimeMs);

        if (this.isStopRequested()) {
            float step = this.getSizeRange() * (deltaTimeMs / (float) this.getDurationMs());
            if (Math.abs(this.getCurrentSizeValue() - this.getStartSize()) <= step) {
                this.setCurrentSizeValue(this.getStartSize());
                return false;
            }
            this.setCurrentSizeValue(
                    this.getCurrentSizeValue() + (this.getCurrentSizeValue() < this.getStartSize() ? step : -step));
            return true;
        }

        float sizeStep = this.getSizeRange() * (deltaTimeMs / (float) this.getDurationMs());
        if (this.isGrowing()) {
            this.setCurrentSizeValue(this.getCurrentSizeValue() + sizeStep);
            if (this.getCurrentSizeValue() >= this.getStartSize() + this.getSizeRange()) {
                this.setCurrentSizeValue(this.getStartSize() + this.getSizeRange());
                this.setGrowing(false);
            }
        } else {
            this.setCurrentSizeValue(this.getCurrentSizeValue() - sizeStep);
            if (this.getCurrentSizeValue() <= this.getStartSize() - this.getSizeRange()) {
                this.setCurrentSizeValue(this.getStartSize() - this.getSizeRange());
                this.setGrowing(true);
            }
        }

        return true;
    }

    // Private Accessors
    private float getStartSize() {
        return startSize;
    }

    private void setStartSize(float startSize) {
        this.startSize = startSize;
    }

    private float getCurrentSizeValue() {
        return currentSizeValue;
    }

    private void setCurrentSizeValue(float val) {
        this.currentSizeValue = val;
    }

    private boolean isGrowing() {
        return growing;
    }

    private void setGrowing(boolean growing) {
        this.growing = growing;
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

    private float getSizeRange() {
        return sizeRange;
    }

    private void setSizeRange(float sizeRange) {
        this.sizeRange = sizeRange;
    }

    private boolean isStopRequested() {
        return stopRequested;
    }

    private void setStopRequested(boolean stop) {
        this.stopRequested = stop;
    }

    @Override
    public void requestStop() {
        this.setStopRequested(true);
    }

    @Override
    public String getType() {
        return "BreathingSize";
    }

    @Override
    public Float getAlphaContribution() {
        return null;
    }

    @Override
    public Double getRotationContribution() {
        return null;
    }

    @Override
    public Float getSizeContribution() {
        return this.getCurrentSizeValue();
    }

    @Override
    public Point2D getPositionContribution() {
        return null;
    }
}
