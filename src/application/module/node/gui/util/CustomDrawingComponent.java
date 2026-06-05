package application.module.node.gui.util;

import application.module.node.gui.GuiColors;
import application.module.node.gui.GuiConstants;
import application.module.node.gui.util.CustomDrawings.Symbol;

import javax.swing.*;
import java.awt.*;

public class CustomDrawingComponent extends JComponent {

    private Symbol drawing;
    private final float scaleFactor;

    public CustomDrawingComponent(Symbol drawing) {
        this(drawing, 1.2f);
    }

    public CustomDrawingComponent(Symbol drawing, float scaleFactor) {
        this.drawing = drawing;
        this.scaleFactor = scaleFactor;
        setOpaque(false);
    }

    public void setDrawing(Symbol drawing) {
        if (this.drawing != drawing) {
            this.drawing = drawing;
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (drawing != null) {
            drawing.draw((Graphics2D) g, getWidth(), getHeight(), GuiColors.getButtonIcon());
        }
    }

    @Override
    public Dimension getPreferredSize() {
        int size = Math.round(GuiConstants.getToolBarIconSize() * scaleFactor);
        return new Dimension(size, size);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        revalidate();
    }
}