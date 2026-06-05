package application.module.node.gui.util;

import application.module.node.gui.GuiColors;
import application.module.node.gui.GuiConstants;
import application.module.node.gui.configuration.LookAndFeelPanel;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class HelpButton extends JButton {

    public HelpButton() {
        super();
        init();
    }

    private void init() {
        setBorderPainted(false);
        setFocusPainted(false);
        setContentAreaFilled(false);
        setBorder(BorderFactory.createEmptyBorder());
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        updateIcon();

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                }
            }

            private void showPopup(MouseEvent e) {
                JPopupMenu popup = new JPopupMenu();
                JMenuItem changeColorItem = new JMenuItem("Change Color...");
                changeColorItem.addActionListener(
                        ae -> ContextMenuUtils.showColorChooserDialog(HelpButton.this, "gui.help.icon"));
                popup.add(changeColorItem);

                LookAndFeelPanel lafPanel = LookAndFeelPanel.getInstance();
                if (lafPanel != null) {
                    popup.addSeparator();
                    ContextMenuUtils.buildProfileMenu(popup, lafPanel);
                }

                popup.show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    private void updateIcon() {
        setIcon(IconFontSwing.buildIcon(FontAwesome.QUESTION_CIRCLE, GuiConstants.getHelpIconSize(),
                GuiColors.getHelpIcon()));
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Re-apply properties that might be reset by LookAndFeel change
        setBorderPainted(false);
        setFocusPainted(false);
        setContentAreaFilled(false);
        setBorder(BorderFactory.createEmptyBorder());
        updateIcon();
    }
}