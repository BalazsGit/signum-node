package brs.gui;

import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ColorSettingsPanel extends JPanel {

    private Map<String, Color> currentOverrides = new HashMap<>();
    private Map<String, Color> loadedProfileOverrides = new HashMap<>();
    private final Map<String, JPanel> previewPanels = new HashMap<>();
    private final Map<String, JLabel> valueLabels = new HashMap<>();
    private final Map<String, JLabel> keyLabels = new HashMap<>();
    private final List<String> allColorKeys;

    public ColorSettingsPanel() {
        super(new BorderLayout());

        // Get all possible color keys from the default palette
        this.allColorKeys = new ArrayList<>(ColorPaletteManager.getAllKeys());
        Collections.sort(this.allColorKeys);

        initUI();
        refreshAllColorRows();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (allColorKeys != null && !allColorKeys.isEmpty()) {
            refreshAllColorRows();
        }
    }

    private void initUI() {
        JTabbedPane tabbedPane = new JTabbedPane();

        // Group keys by prefix
        Map<String, List<String>> groupedKeys = allColorKeys.stream()
                .collect(Collectors.groupingBy(this::getCategoryForKey));

        // Create tabs for each category
        createColorTab(tabbedPane, "General", groupedKeys.getOrDefault("General", Collections.emptyList()),
                "General application colors for UI feedback.");
        createColorTab(tabbedPane, "Peer Metrics", groupedKeys.getOrDefault("Peer Metrics", Collections.emptyList()),
                "Colors used in the Peer Metrics panel for charts and tables.");
        createColorTab(tabbedPane, "Block Generation",
                groupedKeys.getOrDefault("Block Generation", Collections.emptyList()),
                "Colors used in the Block Generation panel for charts, pies, and tables.");
        createColorTab(tabbedPane, "Synchronization",
                groupedKeys.getOrDefault("Synchronization", Collections.emptyList()),
                "Colors used in the Synchronization panel for performance and timing charts.");
        createColorTab(tabbedPane, "GUI", groupedKeys.getOrDefault("GUI", Collections.emptyList()),
                "General GUI element colors.");

        add(tabbedPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton applyButton = new JButton("Apply Changes");
        applyButton.addActionListener(e -> {
            ColorPaletteManager.applyOverrides(currentOverrides);
        });
        buttonPanel.add(applyButton);

        JButton resetButton = new JButton("Reset to Profile Defaults");
        resetButton.addActionListener(e -> {
            currentOverrides.clear();
            currentOverrides.putAll(loadedProfileOverrides);
            ColorPaletteManager.applyOverrides(currentOverrides);
        });
        buttonPanel.add(resetButton);

        add(buttonPanel, BorderLayout.SOUTH);
    }

    private String getCategoryForKey(String key) {
        if (key.startsWith("peer.")) {
            return "Peer Metrics";
        } else if (key.startsWith("blockgen.")) {
            return "Block Generation";
        } else if (key.startsWith("sync.")) {
            return "Synchronization";
        } else if (key.startsWith("gui.")) {
            return "GUI";
        } else {
            return "General";
        }
    }

    private void createColorTab(JTabbedPane tabbedPane, String title, List<String> keys, String helpText) {
        if (keys.isEmpty()) {
            return;
        }

        JPanel mainPanel = new JPanel(new MigLayout("insets 10, gapx 15", "[][][][]", ""));

        for (String key : keys) {
            JLabel keyLabel = new JLabel(key);
            mainPanel.add(keyLabel, "align label");
            keyLabels.put(key, keyLabel);

            JPanel colorPreview = new JPanel();
            colorPreview.setBorder(BorderFactory.createLineBorder(Color.GRAY));
            colorPreview.setPreferredSize(new Dimension(100, 25));
            mainPanel.add(colorPreview);
            previewPanels.put(key, colorPreview);

            JLabel valueLabel = new JLabel();
            valueLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            mainPanel.add(valueLabel);
            valueLabels.put(key, valueLabel);

            JButton editButton = new JButton("Edit...");
            editButton.addActionListener(e -> {
                Color originalColor = previewPanels.get(key).getBackground();
                final JColorChooser colorChooser = new JColorChooser(originalColor);

                colorChooser.getSelectionModel().addChangeListener(changeEvent -> {
                    Color previewColor = colorChooser.getColor();
                    if (previewColor != null) {
                        currentOverrides.put(key, previewColor);
                        ColorPaletteManager.applyLiveOverrides(currentOverrides);

                        // Directly update the color preview on this panel for immediate feedback.
                        updateColorRow(key, previewColor);

                        // To provide a live preview across the entire application (e.g., in tables),
                        // we need to trigger a UI update. A simple repaint() is often insufficient
                        // when a modal dialog is active. Calling updateComponentTreeUI is more robust,
                        // but we must exclude the modal color chooser dialog itself to prevent a
                        // NullPointerException during its own event handling.
                        for (Window window : Window.getWindows()) {
                            if (!(window instanceof JDialog && ((JDialog) window).isModal())) {
                                SwingUtilities.updateComponentTreeUI(window);
                            }
                        }
                    }
                });

                int result = JOptionPane.showConfirmDialog(this, colorChooser, "Choose Color for '" + key + "'",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

                if (result == JOptionPane.OK_OPTION) {
                    // OK was pressed. The final color is already in currentOverrides from the
                    // ChangeListener.
                    // We just need to make sure the final selected color is in the map and do a
                    // full update.
                    currentOverrides.put(key, colorChooser.getColor());
                    ColorPaletteManager.applyOverrides(currentOverrides);
                } else {
                    // Cancel or 'X' was pressed. Revert to the original color and do a full update.
                    currentOverrides.put(key, originalColor);
                    ColorPaletteManager.applyOverrides(currentOverrides);
                }
            });
            mainPanel.add(editButton, "wrap");
        }

        JScrollPane scrollPane = new JScrollPane(mainPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        // Create a panel for the tab component (title + help icon)
        JPanel tabComponent = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        tabComponent.setOpaque(false);
        tabComponent.add(new JLabel(title));
        JButton helpButton = new JButton(
                IconFontSwing.buildIcon(FontAwesome.QUESTION_CIRCLE, 12, GuiColors.getHelpIcon()));
        helpButton.setToolTipText("Click for more info");
        helpButton.setBorder(null);
        helpButton.setContentAreaFilled(false);
        helpButton.setFocusPainted(false);
        helpButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpButton.addActionListener(e -> {
            JOptionPane.showMessageDialog(this,
                    "<html><body style='width:300px;'><p>" + helpText.replace("\n", "<br>") + "</p></body></html>",
                    "Help", JOptionPane.INFORMATION_MESSAGE);
        });
        tabComponent.add(helpButton);

        tabbedPane.addTab(title, scrollPane);
        tabbedPane.setTabComponentAt(tabbedPane.getTabCount() - 1, tabComponent);
    }

    private void updateColorRow(String key, Color color) {
        previewPanels.get(key).setBackground(color);
        keyLabels.get(key).setForeground(color);
        valueLabels.get(key).setForeground(color);
        valueLabels.get(key).setText(String.format("%s (R:%d,G:%d,B:%d,A:%d)",
                toHexString(color), color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha()));
    }

    public void refreshAllColorRows() {
        for (String key : allColorKeys) {
            Color color = ColorPaletteManager.getColor(key);
            updateColorRow(key, color);
        }
    }

    public void setProfileOverrides(Map<String, Color> overrides) {
        this.loadedProfileOverrides = (overrides != null) ? new HashMap<>(overrides) : new HashMap<>();
        this.currentOverrides = new HashMap<>(this.loadedProfileOverrides);
        refreshAllColorRows();
    }

    public Map<String, Color> getCurrentOverrides() {
        return currentOverrides;
    }

    private static String toHexString(Color color) {
        if (color.getAlpha() == 255) {
            return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
        } else {
            return String.format("#%02X%02X%02X%02X", color.getAlpha(), color.getRed(), color.getGreen(),
                    color.getBlue());
        }
    }
}
