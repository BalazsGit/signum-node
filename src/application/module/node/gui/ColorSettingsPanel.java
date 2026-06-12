package application.module.node.gui;

import com.formdev.flatlaf.extras.FlatAnimatedLafChange;

import application.utils.gui.ColorPaletteManager;
import application.utils.gui.HelpButton;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
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
    private final List<ColorRow> allColorRows = new ArrayList<>();
    private JPanel searchResultsPanel;
    private CardLayout contentCardLayout;
    private JTabbedPane innerTabbedPane;
    private Runnable onChangeListener;
    private final Map<String, Integer> categoryToTabIndex = new HashMap<>();
    private JPanel contentContainer;
    private final Map<String, String> descriptions = new HashMap<>();

    public ColorSettingsPanel() {
        super(new BorderLayout());

        initDescriptions();

        // Get all possible color keys from the default palette
        this.allColorKeys = new ArrayList<>(ColorPaletteManager.getAllKeys());
        Collections.sort(this.allColorKeys);

        initUI();
        refreshAllColorRows();
    }

    public void setOnChangeListener(Runnable listener) {
        this.onChangeListener = listener;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (allColorKeys != null && !allColorKeys.isEmpty()) {
            refreshAllColorRows();
        }
    }

    private void initUI() {
        // Search Panel
        JPanel searchPanel = new JPanel(new MigLayout("insets 5 10 5 5, fillx", "[][grow]", "[]"));
        searchPanel.add(new JLabel("Search Colors:"));
        JTextField searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "Type to filter colors...");
        styleTextField(searchField);
        searchPanel.add(searchField, "growx");

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                filterProperties(searchField.getText());
            }

            public void removeUpdate(DocumentEvent e) {
                filterProperties(searchField.getText());
            }

            public void changedUpdate(DocumentEvent e) {
                filterProperties(searchField.getText());
            }
        });

        add(searchPanel, BorderLayout.NORTH);

        contentCardLayout = new CardLayout();
        contentContainer = new JPanel(contentCardLayout);

        innerTabbedPane = new JTabbedPane();

        // Group keys by prefix
        Map<String, List<String>> groupedKeys = allColorKeys.stream()
                .collect(Collectors.groupingBy(this::getCategoryForKey));

        // Create tabs for each category
        createColorTab(innerTabbedPane, "General", groupedKeys.getOrDefault("General", Collections.emptyList()),
                "General application colors for UI feedback.");
        createColorTab(innerTabbedPane, "Peer Metrics",
                groupedKeys.getOrDefault("Peer Metrics", Collections.emptyList()),
                "Colors used in the Peer Metrics panel for charts and tables.");
        createColorTab(innerTabbedPane, "Block Generation",
                groupedKeys.getOrDefault("Block Generation", Collections.emptyList()),
                "Colors used in the Block Generation panel for charts, pies, and tables.");
        createColorTab(innerTabbedPane, "Synchronization",
                groupedKeys.getOrDefault("Synchronization", Collections.emptyList()),
                "Colors used in the Synchronization panel for performance and timing charts.");
        createColorTab(innerTabbedPane, "GUI", groupedKeys.getOrDefault("GUI", Collections.emptyList()),
                "General GUI element colors.");

        contentContainer.add(innerTabbedPane, "TABS");

        searchResultsPanel = new JPanel(new MigLayout("insets 10, gapx 15", "[][][][]", ""));
        JScrollPane searchScrollPane = new JScrollPane(searchResultsPanel);
        searchScrollPane.setBorder(BorderFactory.createEmptyBorder());
        searchScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        contentContainer.add(searchScrollPane, "SEARCH");

        add(contentContainer, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton applyButton = new JButton("Apply Changes");
        applyButton.addActionListener(e -> {
            FlatAnimatedLafChange.showSnapshot();
            ColorPaletteManager.applyOverrides(currentOverrides);
            FlatAnimatedLafChange.hideSnapshotWithAnimation();
        });
        buttonPanel.add(applyButton);

        JButton resetButton = new JButton("Reset to Profile Defaults");
        resetButton.addActionListener(e -> {
            FlatAnimatedLafChange.showSnapshot();
            currentOverrides.clear();
            currentOverrides.putAll(loadedProfileOverrides);
            ColorPaletteManager.applyOverrides(currentOverrides);
            if (onChangeListener != null) {
                onChangeListener.run();
            }
            FlatAnimatedLafChange.hideSnapshotWithAnimation();
        });
        buttonPanel.add(resetButton);

        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void initDescriptions() {
        descriptions.put("applied",
                "Color used to indicate settings that are currently active/applied in configuration panels.");
        descriptions.put("saved",
                "Color used to indicate settings that are saved in the configuration file but may not be currently applied.");

        // Peer Metrics
        descriptions.put("peer.disconnected", "Color for peers that are currently disconnected.");
        descriptions.put("peer.outdated.version", "Color indicating a peer running an outdated version.");
        descriptions.put("peer.up-to-date.version", "Color indicating a peer running the latest version.");
        descriptions.put("peer.outdated.height", "Color indicating a peer that is behind in block height.");
        descriptions.put("peer.up-to-date.height", "Color indicating a peer that is fully synced.");
        descriptions.put("peer.other.response.time", "Chart line color for 'Other' request response times.");
        descriptions.put("peer.blacklisted", "Color for blacklisted peers.");
        descriptions.put("peer.min.response.time", "Chart line color for minimum response time.");
        descriptions.put("peer.max.response.time", "Chart line color for maximum response time.");
        descriptions.put("peer.rx.response.time", "Chart line color for Received (RX) block response time.");
        descriptions.put("peer.rx.count", "Chart bar color for Received (RX) block count.");
        descriptions.put("peer.tx.response.time", "Chart line color for Transmitted (TX) block response time.");
        descriptions.put("peer.tx.count", "Chart bar color for Transmitted (TX) block count.");
        descriptions.put("peer.other.count", "Chart bar color for 'Other' request count.");
        descriptions.put("peer.connected", "Color indicating a connected peer state.");
        descriptions.put("peer.active", "Color indicating an active peer (communicating).");
        descriptions.put("peer.all", "Color representing all known peers in charts.");

        // Block Generation
        descriptions.put("blockgen.network.size", "Chart line color for estimated network size.");
        descriptions.put("blockgen.commitment", "Chart line color for network commitment (SIGNA/TB).");
        descriptions.put("blockgen.base.target", "Chart line color for base target.");
        descriptions.put("blockgen.node.miners", "Chart line color for total miners connected to this node.");
        descriptions.put("blockgen.network.miners", "Chart line color for estimated total network miners.");
        descriptions.put("blockgen.active.miner",
                "Color for active miners (submitted nonce recently) in charts and tables.");
        descriptions.put("blockgen.deadlines.rx", "Chart line color for received deadlines count.");
        descriptions.put("blockgen.node.share", "Chart line color for this node's share of mined blocks.");
        descriptions.put("blockgen.chain.deadline", "Chart bar color for the accepted chain deadline.");
        descriptions.put("blockgen.chain.deadline.ma",
                "Chart line color for the moving average of accepted chain deadlines.");
        descriptions.put("blockgen.node.deadline.ma",
                "Chart line color for the moving average of this node's best deadlines.");
        descriptions.put("blockgen.node.share.legend", "Color for the 'Node Share' legend label.");
        descriptions.put("blockgen.network.share.legend", "Color for the 'Network Share' legend label.");
        descriptions.put("blockgen.pie.others", "Pie chart slice color for 'Others' category.");
        descriptions.put("blockgen.pie.waiting", "Pie chart slice color when waiting for blocks.");
        descriptions.put("blockgen.pie.filtered", "Pie chart slice color for filtered data.");

        // Synchronization
        descriptions.put("sync.system.tx.per.block", "Chart bar color for system transactions per block.");
        descriptions.put("sync.all.tx.per.block", "Chart bar color for all transactions per block.");
        descriptions.put("sync.upload.volume", "Chart fill color for upload volume.");
        descriptions.put("sync.download.volume", "Chart fill color for download volume.");
        descriptions.put("sync.push.time", "Chart line color for block push time.");
        descriptions.put("sync.validation.time", "Chart line color for validation time.");
        descriptions.put("sync.tx.loop.time", "Chart line color for transaction loop time.");
        descriptions.put("sync.housekeeping.time", "Chart line color for housekeeping time.");
        descriptions.put("sync.tx.apply.time", "Chart line color for transaction application time.");
        descriptions.put("sync.at.time", "Chart line color for AT processing time.");
        descriptions.put("sync.subscription.time", "Chart line color for subscription processing time.");
        descriptions.put("sync.block.apply.time", "Chart line color for block application time.");
        descriptions.put("sync.commit.time", "Chart line color for database commit time.");
        descriptions.put("sync.misc.time", "Chart line color for miscellaneous processing time.");
        descriptions.put("sync.payload.fullness", "Chart line color for payload fullness percentage.");
        descriptions.put("sync.blocks.per.sec", "Chart line color for blocks processed per second.");
        descriptions.put("sync.all.tx.per.sec", "Chart line color for all transactions processed per second.");
        descriptions.put("sync.system.tx.per.sec", "Chart line color for system transactions processed per second.");
        descriptions.put("sync.at.count.per.block", "Chart line color for ATs per block.");
        descriptions.put("sync.upload.speed", "Chart line color for upload speed.");
        descriptions.put("sync.download.speed", "Chart line color for download speed.");

        // GUI
        descriptions.put("gui.contrast.red", "Used for high-contrast error messages or alerts.");
        descriptions.put("gui.status.consistent", "Used to indicate a consistent state (e.g. DB consistent).");
        descriptions.put("gui.help.icon", "Color of the question mark help icons.");
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

    private boolean objectsEqual(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }

    private void createColorTab(JTabbedPane tabbedPane, String title, List<String> keys, String helpText) {
        if (keys.isEmpty()) {
            return;
        }

        JPanel mainPanel = new JPanel(new MigLayout("insets 10, gapx 15", "[][][][][]", ""));

        for (String key : keys) {
            ColorRow row = new ColorRow(key, mainPanel);
            JLabel keyLabel = new JLabel(key);
            mainPanel.add(keyLabel, "align label");
            keyLabels.put(key, keyLabel);
            row.keyLabel = keyLabel;

            JPanel colorPreview = new JPanel();
            colorPreview.setBorder(BorderFactory.createLineBorder(Color.GRAY));
            colorPreview.setPreferredSize(new Dimension(100, 25));
            mainPanel.add(colorPreview);
            previewPanels.put(key, colorPreview);
            row.previewPanel = colorPreview;

            JLabel valueLabel = new JLabel();
            mainPanel.add(valueLabel);
            valueLabels.put(key, valueLabel);
            row.valueLabel = valueLabel;

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
                        updateTabDirtyStatus();
                        if (onChangeListener != null) {
                            onChangeListener.run();
                        }

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
            mainPanel.add(editButton);
            row.editButton = editButton;

            JButton rowHelpButton = new HelpButton();
            rowHelpButton.setToolTipText("What is this?");
            rowHelpButton.addActionListener(e -> {
                String desc = descriptions.getOrDefault(key, "No description available for " + key);
                JOptionPane.showMessageDialog(this,
                        "<html><body style='width:300px;'><p>" + desc + "</p></body></html>",
                        "Color Information: " + key, JOptionPane.INFORMATION_MESSAGE);
            });
            mainPanel.add(rowHelpButton, "wrap");
            row.helpButton = rowHelpButton;

            allColorRows.add(row);
        }

        JScrollPane scrollPane = new JScrollPane(mainPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        // Create a panel for the tab component (title + help icon)
        JPanel tabComponent = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        tabComponent.setOpaque(false);
        tabComponent.add(new JLabel(title));
        JButton helpButton = new HelpButton();
        helpButton.setToolTipText("Click for more info");
        helpButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpButton.addActionListener(e -> {
            JOptionPane.showMessageDialog(this,
                    "<html><body style='width:300px;'><p>" + helpText.replace("\n", "<br>") + "</p></body></html>",
                    "Help", JOptionPane.INFORMATION_MESSAGE);
        });
        tabComponent.add(helpButton);

        tabbedPane.addTab(title, scrollPane);
        tabbedPane.setTabComponentAt(tabbedPane.getTabCount() - 1, tabComponent);
        categoryToTabIndex.put(title, tabbedPane.getTabCount() - 1);
    }

    private void updateTabDirtyStatus() {
        if (innerTabbedPane == null)
            return;

        Map<String, Boolean> categoryDirty = new HashMap<>();
        for (String key : allColorKeys) {
            String category = getCategoryForKey(key);
            boolean isDirty = !objectsEqual(currentOverrides.get(key), loadedProfileOverrides.get(key));
            if (isDirty) {
                categoryDirty.put(category, true);
            }
        }

        categoryToTabIndex.forEach((category, index) -> {
            String title = innerTabbedPane.getTitleAt(index);
            if (title.endsWith(" *"))
                title = title.substring(0, title.length() - 2);

            if (categoryDirty.getOrDefault(category, false)) {
                innerTabbedPane.setTitleAt(index, title + " *");
            } else {
                innerTabbedPane.setTitleAt(index, title);
            }
        });
    }

    private void updateColorRow(String key, Color color) {
        JPanel preview = previewPanels.get(key);
        if (preview != null)
            preview.setBackground(color);

        boolean isDirty = !objectsEqual(currentOverrides.get(key), loadedProfileOverrides.get(key));

        JLabel kLabel = keyLabels.get(key);
        if (kLabel != null) {
            kLabel.setText(isDirty ? key + " *" : key);
            kLabel.setForeground(color);
        }

        JLabel vLabel = valueLabels.get(key);
        if (vLabel != null) {
            vLabel.setForeground(color);
            vLabel.setText(String.format("%s (R:%d,G:%d,B:%d,A:%d)",
                    toHexString(color), color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha()));
        }
    }

    public void refreshAllColorRows() {
        for (String key : allColorKeys) {
            Color color = ColorPaletteManager.getColor(key);
            updateColorRow(key, color);
        }
        updateTabDirtyStatus();
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

    private void filterProperties(String text) {
        boolean isSearch = text != null && !text.trim().isEmpty();

        if (isSearch) {
            searchResultsPanel.removeAll();
            String lowerText = text.toLowerCase();

            for (ColorRow row : allColorRows) {
                if (row.key.toLowerCase().contains(lowerText)) {
                    searchResultsPanel.add(row.keyLabel, "align label");
                    searchResultsPanel.add(row.previewPanel);
                    searchResultsPanel.add(row.valueLabel);
                    searchResultsPanel.add(row.editButton);
                    searchResultsPanel.add(row.helpButton, "wrap");
                }
            }
            contentCardLayout.show(contentContainer, "SEARCH");
        } else {
            for (ColorRow row : allColorRows) {
                row.originalParent.add(row.keyLabel, "align label");
                row.originalParent.add(row.previewPanel);
                row.originalParent.add(row.valueLabel);
                row.originalParent.add(row.editButton);
                row.originalParent.add(row.helpButton, "wrap");
            }
            contentCardLayout.show(contentContainer, "TABS");
        }
        revalidate();
        repaint();
    }

    private void styleTextField(JComponent field) {
        if (field instanceof JTextField || field instanceof JPasswordField) {
            field.setFont(UIManager.getFont("TextField.font"));
            field.setBorder(BorderFactory.createCompoundBorder(
                    UIManager.getBorder("TextField.border"),
                    BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        }
    }

    private static class ColorRow {
        final String key;
        final JPanel originalParent;
        JLabel keyLabel;
        JPanel previewPanel;
        JLabel valueLabel;
        JButton editButton;
        JButton helpButton;

        ColorRow(String key, JPanel originalParent) {
            this.key = key;
            this.originalParent = originalParent;
        }
    }
}
