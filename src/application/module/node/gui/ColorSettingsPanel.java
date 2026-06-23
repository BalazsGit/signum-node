package application.module.node.gui;

import com.formdev.flatlaf.extras.FlatAnimatedLafChange;

import application.utils.gui.ColorPaletteManager;
import application.utils.gui.HelpButton;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Color settings panel with hierarchical module-based tab structure.
 * <p>
 * Tab hierarchy:
 * <ul>
 *   <li><b>Global</b> - Application-level color settings (applied, saved)</li>
 *   <li><b>Node</b> - Node module colors, with sub-tabs for each panel:</li>
 *   <ul>
 *     <li>Peer Metrics Panel (peer.*)</li>
 *     <li>Block Generation Panel (blockgen.*)</li>
 *     <li>Synchronization Panel (sync.*)</li>
 *   </ul>
 *   <li><b>GUI Elements</b> - General GUI color settings (gui.*)</li>
 *   <li><b>Database</b> - Placeholder for future database-specific colors</li>
 * </ul>
 */
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

    /** Module-level tabbed pane: category tabs (Global, Node, GUI Elements, Database) */
    private JTabbedPane moduleTabbedPane;
    /** Maps module name -> component-level tabbed pane for modules with sub-tabs (e.g., Node) */
    private final Map<String, JTabbedPane> componentTabbedPanes = new HashMap<>();
    private Runnable onChangeListener;

    /** Maps "module|component" -> tab index within that module's inner tabbed pane */
    private final Map<String, Integer> componentToTabIndex = new HashMap<>();
    /** Tracks which modules use nested tabs vs direct content */
    private final Map<String, Boolean> moduleUsesNestedTabs = new HashMap<>();
    private JPanel contentContainer;
    private final Map<String, String> descriptions = new HashMap<>();

    /**
     * Two-level category for color key organization.
     * Package-private for unit test accessibility via reflection on getCategoryForKey.
     */
    public static class CategoryInfo {
        final String module;
        final String component;

        CategoryInfo(String module, String component) {
            this.module = module;
            this.component = component;
        }

        String getHierarchicalKey() {
            return module + "|" + component;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            CategoryInfo other = (CategoryInfo) obj;
            return module.equals(other.module) && component.equals(other.component);
        }

        @Override
        public int hashCode() {
            return 31 * module.hashCode() + component.hashCode();
        }
    }

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

        // --- Module-level tabbed pane: category tabs ---
        moduleTabbedPane = new JTabbedPane();

        // Group keys by hierarchical category (module -> component -> keys)
        Map<CategoryInfo, List<String>> groupedByCategory = new LinkedHashMap<>();
        for (String key : allColorKeys) {
            CategoryInfo cat = getCategoryForKey(key);
            groupedByCategory.computeIfAbsent(cat, c -> new ArrayList<>()).add(key);
        }

        // Group by module
        Map<String, Map<String, List<String>>> moduleComponents = new LinkedHashMap<>();
        for (Map.Entry<CategoryInfo, List<String>> entry : groupedByCategory.entrySet()) {
            moduleComponents.computeIfAbsent(entry.getKey().module, m -> new LinkedHashMap<>())
                    .put(entry.getKey().component, entry.getValue());
        }

        // --- Global tab (direct content, no nesting) ---
        moduleUsesNestedTabs.put("Global", false);
        if (moduleComponents.containsKey("Global")) {
            Map<String, List<String>> globalComponents = moduleComponents.get("Global");
            createModuleTab_DirectContent("Global", globalComponents,
                    "Application-level color settings for UI feedback across all modules.");
        }

        // --- Node tab (nested sub-tabs) ---
        moduleUsesNestedTabs.put("Node", true);
        if (moduleComponents.containsKey("Node")) {
            createModuleTab_Nested("Node", moduleComponents.get("Node"),
                    "Node module color settings for charts, tables, and status indicators.");
        }

        // --- GUI Elements tab (direct content, no nesting) ---
        moduleUsesNestedTabs.put("GUI Elements", false);
        if (moduleComponents.containsKey("GUI Elements")) {
            createModuleTab_DirectContent("GUI Elements", moduleComponents.get("GUI Elements"),
                    "General GUI element colors for the application interface.");
        }

        // --- Database tab (placeholder for future) ---
        moduleUsesNestedTabs.put("Database", false);
        createDatabasePlaceholderTab();

        contentContainer.add(moduleTabbedPane, "TABS");

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

    /**
     * Creates a module tab with direct content (no nested sub-tabs).
     * Used for: Global, GUI Elements.
     */
    private void createModuleTab_DirectContent(String moduleName, Map<String, List<String>> components, String moduleHelpText) {
        // Merge all component keys into one panel since there's no nesting
        List<String> allKeys = new ArrayList<>();
        for (List<String> keys : components.values()) {
            allKeys.addAll(keys);
        }

        if (allKeys.isEmpty()) return;

        JPanel mainPanel = new JPanel(new MigLayout("insets 10, gapx 15", "[][][][][]", ""));
        for (String key : allKeys) {
            ColorRow row = addColorRowToPanel(key, mainPanel);
            allColorRows.add(row);
        }

        JScrollPane scrollPane = new JScrollPane(mainPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        addModuleTab(moduleName, scrollPane, moduleHelpText);
    }

    /**
     * Creates a module tab with nested sub-tabs inside.
     * Used for: Node (which has Peer Metrics, Block Generation, Synchronization sub-tabs).
     */
    private void createModuleTab_Nested(String moduleName, Map<String, List<String>> components, String moduleHelpText) {
        JTabbedPane innerPane = new JTabbedPane();
        componentTabbedPanes.put(moduleName, innerPane);

        int tabIndex = 0;
        for (Map.Entry<String, List<String>> entry : components.entrySet()) {
            String componentName = entry.getKey();
            List<String> keys = entry.getValue();

            if (keys.isEmpty()) continue;

            JPanel mainPanel = new JPanel(new MigLayout("insets 10, gapx 15", "[][][][][]", ""));
            for (String key : keys) {
                ColorRow row = addColorRowToPanel(key, mainPanel);
                allColorRows.add(row);
            }

            JScrollPane scrollPane = new JScrollPane(mainPanel);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            scrollPane.getVerticalScrollBar().setUnitIncrement(16);

            innerPane.addTab(componentName, scrollPane);
            componentToTabIndex.put(moduleName + "|" + componentName, tabIndex++);
        }

        addModuleTab(moduleName, innerPane, moduleHelpText);
    }

    /**
     * Creates a placeholder tab for the Database module (no color keys yet).
     */
    private void createDatabasePlaceholderTab() {
        JPanel placeholderPanel = new JPanel();
        placeholderPanel.setLayout(new BoxLayout(placeholderPanel, BoxLayout.Y_AXIS));
        placeholderPanel.setAlignmentX(CENTER_ALIGNMENT);
        placeholderPanel.setBorder(BorderFactory.createEmptyBorder(40, 20, 40, 20));

        JLabel messageLabel = new JLabel("Database-specific color settings will be available here.", SwingConstants.CENTER);
        messageLabel.setForeground(UIManager.getColor("Label.foreground").darker());
        placeholderPanel.add(Box.createVerticalGlue());
        placeholderPanel.add(messageLabel);
        placeholderPanel.add(Box.createVerticalGlue());

        addModuleTab("Database", placeholderPanel,
                "Placeholder for future database module color customization settings.");
    }

    /**
     * Adds a module-level tab to the module tabbed pane with help button.
     */
    private void addModuleTab(String title, Component content, String helpText) {
        moduleTabbedPane.addTab(title, content);
        int tabIndex = moduleTabbedPane.getTabCount() - 1;

        // Add help button to tab
        JPanel tabComponent = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        tabComponent.setOpaque(false);
        tabComponent.add(new JLabel(title));
        JButton helpButton = new HelpButton();
        helpButton.setToolTipText("Click for more info about " + title);
        helpButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpButton.addActionListener(e -> {
            JOptionPane.showMessageDialog(this,
                    "<html><body style='width:300px;'><p>" + helpText.replace("\n", "<br>") + "</p></body></html>",
                    "Help: " + title, JOptionPane.INFORMATION_MESSAGE);
        });
        tabComponent.add(helpButton);
        moduleTabbedPane.setTabComponentAt(tabIndex, tabComponent);
    }

    /**
     * Creates a single color row and adds it to the given panel.
     */
    private ColorRow addColorRowToPanel(String key, JPanel panel) {
        ColorRow row = new ColorRow(key, panel);

        JLabel keyLabel = new JLabel(key);
        panel.add(keyLabel, "align label");
        keyLabels.put(key, keyLabel);
        row.keyLabel = keyLabel;

        JPanel colorPreview = new JPanel();
        colorPreview.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        colorPreview.setPreferredSize(new Dimension(100, 25));
        panel.add(colorPreview);
        previewPanels.put(key, colorPreview);
        row.previewPanel = colorPreview;

        JLabel valueLabel = new JLabel();
        panel.add(valueLabel);
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

                    updateColorRow(key, previewColor);
                    updateTabDirtyStatus();
                    if (onChangeListener != null) {
                        onChangeListener.run();
                    }

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
                currentOverrides.put(key, colorChooser.getColor());
                ColorPaletteManager.applyOverrides(currentOverrides);
            } else {
                currentOverrides.put(key, originalColor);
                ColorPaletteManager.applyOverrides(currentOverrides);
            }
        });
        panel.add(editButton);
        row.editButton = editButton;

        JButton rowHelpButton = new HelpButton();
        rowHelpButton.setToolTipText("What is this?");
        rowHelpButton.addActionListener(e -> {
            String desc = descriptions.getOrDefault(key, "No description available for " + key);
            JOptionPane.showMessageDialog(this,
                    "<html><body style='width:300px;'><p>" + desc + "</p></body></html>",
                    "Color Information: " + key, JOptionPane.INFORMATION_MESSAGE);
        });
        panel.add(rowHelpButton, "wrap");
        row.helpButton = rowHelpButton;

        return row;
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

    /**
     * Returns the two-level category for a color key.
     * Maps key prefixes to module + component hierarchy.
     */
    private CategoryInfo getCategoryForKey(String key) {
        if (key.startsWith("peer.")) {
            return new CategoryInfo("Node", "Peer Metrics");
        } else if (key.startsWith("blockgen.")) {
            return new CategoryInfo("Node", "Block Generation");
        } else if (key.startsWith("sync.")) {
            return new CategoryInfo("Node", "Synchronization");
        } else if (key.startsWith("gui.")) {
            return new CategoryInfo("GUI Elements", "UI Colors");
        } else {
            // applied, saved, etc.
            return new CategoryInfo("Global", "General");
        }
    }

    private boolean objectsEqual(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }

    /**
     * Updates dirty (*) status on both module-level tabs and component-level sub-tabs.
     */
    private void updateTabDirtyStatus() {
        if (moduleTabbedPane == null)
            return;

        // Determine which modules and components are dirty
        Map<String, Boolean> moduleDirty = new HashMap<>();
        Map<String, Boolean> componentDirty = new HashMap<>();

        for (String key : allColorKeys) {
            CategoryInfo cat = getCategoryForKey(key);
            boolean isDirty = !objectsEqual(currentOverrides.get(key), loadedProfileOverrides.get(key));
            if (isDirty) {
                moduleDirty.put(cat.module, true);
                componentDirty.put(cat.getHierarchicalKey(), true);
            }
        }

        // Update module-level tab titles
        for (int i = 0; i < moduleTabbedPane.getTabCount(); i++) {
            Component tabComp = moduleTabbedPane.getTabComponentAt(i);
            if (tabComp instanceof JPanel) {
                JLabel label = findFirstLabel((JPanel) tabComp);
                if (label != null) {
                    String moduleName = label.getText();
                    boolean dirty = moduleDirty.getOrDefault(moduleName, false);
                    label.setText(dirty ? moduleName + " *" : moduleName);
                }
            }
        }

        // Update component-level tab titles within nested modules
        for (Map.Entry<String, JTabbedPane> entry : componentTabbedPanes.entrySet()) {
            String moduleName = entry.getKey();
            JTabbedPane innerPane = entry.getValue();
            for (int i = 0; i < innerPane.getTabCount(); i++) {
                String componentTitle = innerPane.getTitleAt(i);
                String hierKey = moduleName + "|" + componentTitle;
                boolean dirty = componentDirty.getOrDefault(hierKey, false);
                innerPane.setTitleAt(i, dirty ? componentTitle + " *" : componentTitle);
            }
        }
    }

    /**
     * Finds the first JLabel inside a tab component panel (used to read/update tab text).
     */
    private JLabel findFirstLabel(JPanel panel) {
        for (Component comp : panel.getComponents()) {
            if (comp instanceof JLabel) {
                return (JLabel) comp;
            }
        }
        return null;
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
