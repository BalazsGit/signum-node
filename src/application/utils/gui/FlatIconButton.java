package application.utils.gui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.UIManager;

/** A borderless icon button with the shared rounded rollover highlight. */
public class FlatIconButton extends JButton {

    public FlatIconButton() {
        setContentAreaFilled(false);
        setFocusPainted(false);
        setBorderPainted(false);
        setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        setMargin(new java.awt.Insets(0, 0, 0, 0));
        setRolloverEnabled(true);
    }

    @Override
    protected void paintComponent(java.awt.Graphics g) {
        if (getModel().isRollover()) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            java.awt.Color c = UIManager.getColor("TabbedPane.hoverBackground");
            g2.setColor(c != null ? c : new java.awt.Color(0x80, 0x80, 0x80, 48));
            g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 10, 10);
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
