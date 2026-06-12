package application.gui.glassPanel;

import java.awt.geom.Point2D;

/**
 * Animation that rotates the component counter-clockwise.
 */
public class RotateLeftAnimation implements GlassPanelAnimation {
    private double currentRotation = 0;
    private final double speed = Math.toRadians(5);
    private boolean stopRequested = false;

    @Override
    public boolean update(long deltaTimeMs) {
        currentRotation -= speed * (deltaTimeMs / 50.0);

        if (currentRotation <= -2 * Math.PI) {
            currentRotation += 2 * Math.PI;
        }

        if (stopRequested && Math.abs(currentRotation) < speed) {
            currentRotation = 0;
            return false;
        }
        return true;
    }

    @Override
    public String getType() {
        return "RotateLeft";
    }

    @Override
    public Float getAlphaContribution() {
        return null;
    }

    @Override
    public Double getRotationContribution() {
        return currentRotation;
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
        this.stopRequested = true;
    }
}