package application.module.brs.gui.util;

import application.module.brs.gui.ColorPaletteManager;
import application.module.brs.gui.ColorSettingsPanel;
import application.module.brs.gui.SignumGUI;
import application.module.brs.gui.configuration.LookAndFeelPanel;

import javax.swing.*;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;

public final class ContextMenuUtils {

    private ContextMenuUtils() {
    } // prevent instantiation

    public static void buildProfileMenu(JPopupMenu popup, LookAndFeelPanel lafPanel) {
        // Profile Info
        JComboBox<String> profileComboBox = lafPanel.getProfileComboBox();
        String currentProfile = (String) profileComboBox.getSelectedItem();
        JMenuItem profileInfoItem = new JMenuItem("Profile: " + (currentProfile != null ? currentProfile : "None"));
        profileInfoItem.setEnabled(false);
        popup.add(profileInfoItem);

        // Load Profile
        JMenu loadMenu = new JMenu("Load Profile");
        if (profileComboBox.getItemCount() == 0) {
            loadMenu.setEnabled(false);
        } else {
            for (int i = 0; i < profileComboBox.getItemCount(); i++) {
                String profileName = profileComboBox.getItemAt(i);
                JMenuItem profileItem = new JMenuItem(profileName);
                profileItem.addActionListener(ae -> lafPanel.loadProfile(profileName));
                loadMenu.add(profileItem);
            }
        }
        popup.add(loadMenu);

        // Save Profile
        JMenuItem saveItem = new JMenuItem("Save Profile...");
        saveItem.addActionListener(ae -> lafPanel.saveProfile());
        popup.add(saveItem);

        // Rename Profile
        JMenuItem renameItem = new JMenuItem("Rename Current Profile...");
        if (currentProfile == null) {
            renameItem.setEnabled(false);
        }
        renameItem.addActionListener(ae -> lafPanel.renameProfile(currentProfile));
        popup.add(renameItem);

        // Delete Profile
        JMenuItem deleteItem = new JMenuItem("Delete Current Profile");
        if (currentProfile == null) {
            deleteItem.setEnabled(false);
        }
        deleteItem.addActionListener(ae -> lafPanel.deleteProfile(currentProfile));
        popup.add(deleteItem);

        popup.addSeparator();
        JMenuItem goToSettings = new JMenuItem("Open Look and Feel Settings...");
        goToSettings.addActionListener(ae -> {
            SignumGUI gui = SignumGUI.getInstance();
            if (gui != null) {
                gui.showLookAndFeelSettings();
            }
        });
        popup.add(goToSettings);
    }

    public static void addInfoTooltip(JFrame parentFrame, JLabel label, String text, String colorKey) {
        if (label.getToolTipText() == null && text != null) {
            String newTooltip = "<html>" + text.replace("\n", "<br>");
            if (colorKey != null) {
                newTooltip += "<br><hr><i>Right-click for color and profile options.</i>";
            }
            newTooltip += "</html>";
            label.setToolTipText(newTooltip);
        }

        label.addMouseListener(new MouseAdapter() {
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

                // Show Info
                if (text != null) {
                    JMenuItem infoItem = new JMenuItem("Show Info");
                    infoItem.addActionListener(ae -> {
                        String title = label.getText();
                        if (title.endsWith(":")) {
                            title = title.substring(0, title.length() - 1);
                        }
                        String htmlText = "<html><body><p style='width: 300px;'>" + text.replace("\n", "<br>")
                                + "</p></body></html>";
                        JOptionPane.showMessageDialog(parentFrame, htmlText, title, JOptionPane.PLAIN_MESSAGE);
                    });
                    popup.add(infoItem);
                }

                // Color and Profile Management
                if (colorKey != null) {
                    if (text != null) {
                        popup.addSeparator();
                    }

                    LookAndFeelPanel lafPanel = LookAndFeelPanel.getInstance();

                    // Change Color
                    JMenuItem changeColorItem = new JMenuItem("Change Color...");
                    changeColorItem.addActionListener(ae -> showColorChooserDialog(parentFrame, colorKey));
                    popup.add(changeColorItem);

                    if (lafPanel != null) {
                        popup.addSeparator();
                        ContextMenuUtils.buildProfileMenu(popup, lafPanel);
                    }
                }

                if (popup.getComponentCount() > 0) {
                    popup.show(e.getComponent(), e.getX(), e.getY());
                } else if (text != null) {
                    // Fallback to old behavior if no menu items were added
                    String title = label.getText();
                    // Remove trailing colon for a cleaner title
                    if (title.endsWith(":")) {
                        title = title.substring(0, title.length() - 1);
                    }
                    // Wrap the text in HTML to control the width of the dialog.
                    String htmlText = "<html><body><p style='width: 300px;'>" + text.replace("\n", "<br>")
                            + "</p></body></html>";
                    JOptionPane.showMessageDialog(parentFrame, htmlText, title, JOptionPane.PLAIN_MESSAGE);
                }
            }
        });
    }

    public static void showColorChooserDialog(Component parent, String key) {
        LookAndFeelPanel lafPanel = LookAndFeelPanel.getInstance();
        if (lafPanel == null) {
            JOptionPane.showMessageDialog(parent, "Look and Feel settings are not available.", "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        ColorSettingsPanel colorSettingsPanel = lafPanel.getColorSettingsPanel();
        if (colorSettingsPanel == null) {
            JOptionPane.showMessageDialog(parent, "Color settings are not available.", "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        Map<String, Color> currentOverrides = new HashMap<>(colorSettingsPanel.getCurrentOverrides());
        Color originalColor = ColorPaletteManager.getColor(key);
        final JColorChooser colorChooser = new JColorChooser(originalColor);

        colorChooser.getSelectionModel().addChangeListener(changeEvent -> {
            Color previewColor = colorChooser.getColor();
            if (previewColor != null) {
                currentOverrides.put(key, previewColor);
                ColorPaletteManager.applyLiveOverrides(currentOverrides);

                for (Window window : Window.getWindows()) {
                    if (!(window instanceof JDialog && ((JDialog) window).isModal())) {
                        SwingUtilities.updateComponentTreeUI(window);
                    }
                }
            }
        });

        int result = JOptionPane.showConfirmDialog(parent, colorChooser, "Choose Color for '" + key + "'",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            currentOverrides.put(key, colorChooser.getColor());
            colorSettingsPanel.setProfileOverrides(currentOverrides);
            ColorPaletteManager.applyOverrides(currentOverrides);
        } else {
            currentOverrides.put(key, originalColor);
            colorSettingsPanel.setProfileOverrides(currentOverrides);
            ColorPaletteManager.applyOverrides(currentOverrides);
        }
    }
}