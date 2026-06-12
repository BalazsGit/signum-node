package application.utils.gui;

import application.module.appearance.gui.AppearancePanel;
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

                AppearancePanel appearancePanel = AppearancePanel.getInstance();
                if (appearancePanel != null) {
                    popup.addSeparator();
                    ContextMenuUtils.buildProfileMenu(popup, appearancePanel);
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