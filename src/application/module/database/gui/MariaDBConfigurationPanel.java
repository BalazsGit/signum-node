package application.module.database.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.database.gui.DatabaseConfigurationPanel.PropertyRow;
import application.module.database.profile.MariadbProfile;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.Signum;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.io.PathUtils;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * MariaDB configuration panel that manages multiple profiles.
 * Each profile gets its own tab with a MariaDBProfilePanel instance.
 * 
 * This class is responsible for:
 * - Loading existing profiles from disk
 * - Creating new profiles
 * - Managing profile tabs (add/remove/rename)
 * - Delegating DatabaseEnginePanel interface calls to the active profile panel
 */
public class MariaDBConfigurationPanel extends JPanel implements DatabaseEnginePanel {
    private static final Logger logger = LoggerFactory.getLogger(MariaDBConfigurationPanel.class);
    public static final String API_BASE_URL = DatabaseConfigurationUtils.MARIA_DB_API_BASE_URL;

    private final JTabbedPane tabbedPane = new JTabbedPane();
    private final Map<String, MariaDBProfilePanel> profilePanelMap = new HashMap<>();

    // Toolbar components
    private JButton newProfileBtn;
    private JButton renameProfileBtn;
    private JButton deleteProfileBtn;
    private JButton reloadProfilesBtn;
    private JTextField searchField;

    private String confFolder;
    private Runnable onDirtyStatusChanged;

    // Current state tracking for DatabaseEnginePanel delegation
    private GlobalSettings globalSettings = new GlobalSettings();
    private String loadedProfileName;
    private String runningProfileName;
    private String activeProfileName;
    private JsonObject appliedProfileSettings = new JsonObject();

    float iconSize = GuiConstants.getHelpIconSize();
    Color iconColor = GuiColors.getButtonIcon();

    private DatabaseEngine currentEngine = DatabaseEngine.MARIADB;

    private final Icon tabIcon = IconFontSwing.buildIcon(FontAwesome.DATABASE, iconSize, iconColor);

    public MariaDBConfigurationPanel() {
        setLayout(new BorderLayout(0, 0));
        buildUI();
    }

    private void buildUI() {
        // Top toolbar for profile management
        JPanel toolbar = createToolbar();
        add(toolbar, BorderLayout.NORTH);

        // Main content: tabbed pane for profiles
        add(tabbedPane, BorderLayout.CENTER);

        // Load existing profiles on creation
        refreshProfiles();
    }

    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));

        // New profile button
        newProfileBtn = new JButton("New Profile");
        newProfileBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.FILE_O, iconSize, iconColor));
        ConfigurationUtils.fixComponentSize(newProfileBtn);
        newProfileBtn.addActionListener(e -> createNewProfile());
        toolbar.add(newProfileBtn);

        // Separator
        toolbar.add(Box.createHorizontalStrut(10));

        // Search field for filtering profile tabs
        searchField = new JTextField(20);
        searchField.setToolTipText("Search profiles...");
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                filterProfileTabs();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                filterProfileTabs();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                filterProfileTabs();
            }
        });
        toolbar.add(new JLabel(" Search:"));
        toolbar.add(searchField);

        return toolbar;
    }

    /**
     * Filter profile tabs based on the search field text.
     * Tabs matching the search query are shown, others are hidden.
     * When search is empty, all tabs are restored.
     */
    private void filterProfileTabs() {
        String query = searchField.getText().trim().toLowerCase();

        if (query.isEmpty()) {
            // Restore all tabs
            restoreAllTabs();
            return;
        }

        // Find matching tab names
        List<String> matchingProfiles = new ArrayList<>();
        for (String profileName : profilePanelMap.keySet()) {
            if (profileName.toLowerCase().contains(query)) {
                matchingProfiles.add(profileName);
            }
        }

        // Remember currently selected tab
        String currentSelectedTab = getActiveTabTitle();

        // Remove non-matching tabs from tabbedPane but keep panels in map
        // Use LinkedHashMap to preserve order
        LinkedHashMap<String, boolean[]> visibleTabs = new LinkedHashMap<>();
        for (String profileName : profilePanelMap.keySet()) {
            visibleTabs.put(profileName, new boolean[] { profileName.toLowerCase().contains(query) });
        }

        // Rebuild tabbedPane with only matching tabs
        int oldSelectedIndex = tabbedPane.getSelectedIndex();
        tabbedPane.removeAll();

        for (Map.Entry<String, boolean[]> entry : visibleTabs.entrySet()) {
            if (entry.getValue()[0]) {
                tabbedPane.addTab(entry.getKey(), tabIcon, profilePanelMap.get(entry.getKey()));
            }
        }

        // Try to restore selection if the previously selected tab is still visible
        if (currentSelectedTab != null && currentSelectedTab.toLowerCase().contains(query)) {
            int newIndex = getTabIndex(currentSelectedTab);
            if (newIndex >= 0) {
                tabbedPane.setSelectedIndex(newIndex);
            }
        }
    }

    /**
     * Restore all tabs to the tabbed pane (called when search field is cleared).
     */
    private void restoreAllTabs() {
        String currentSelectedTab = getActiveTabTitle();

        tabbedPane.removeAll();

        for (String profileName : profilePanelMap.keySet()) {
            tabbedPane.addTab(profileName, tabIcon, profilePanelMap.get(profileName));
        }

        // Restore selection
        if (currentSelectedTab != null && profilePanelMap.containsKey(currentSelectedTab)) {
            int tabIndex = getTabIndex(currentSelectedTab);
            if (tabIndex >= 0) {
                tabbedPane.setSelectedIndex(tabIndex);
            }
        }
    }

    /**
     * Scan disk for profile directories and create tabs for each.
     */
    private void refreshProfiles() {
        // Save current active tab name if possible
        int selectedIndex = tabbedPane.getSelectedIndex();
        String currentSelectedTab = (selectedIndex >= 0) ? tabbedPane.getTitleAt(selectedIndex) : null;

        // Clear existing tabs
        tabbedPane.removeAll();
        profilePanelMap.clear();

        Path profilesPath = getProfilesRoot();
        if (!Files.exists(profilesPath)) {
            try {
                Files.createDirectories(profilesPath);
            } catch (IOException e) {
                logger.warn("Could not create profiles directory: {}", profilesPath, e);
            }
        }

        // Load existing profiles from disk
        loadExistingProfiles(profilesPath);

        // If we had a selected tab, try to restore focus
        if (currentSelectedTab != null && profilePanelMap.containsKey(currentSelectedTab)) {
            tabbedPane.setSelectedIndex(getTabIndex(currentSelectedTab));
        }
    }

    private void loadExistingProfiles(Path profilesPath) {
        // Ensure directory exists for future profile creation
        if (!Files.exists(profilesPath)) {
            try {
                Files.createDirectories(profilesPath);
            } catch (IOException e) {
                logger.warn("Could not create profiles directory: {}", profilesPath, e);
            }
        }

        // Use centralized profile management from db-profiles.json
        DatabaseConfigurationUtils.getProfileNames(Signum.CONF_FOLDER, "MariaDB")
                .forEach(profileName -> addProfileTab(profileName, true));
    }

    /**
     * Add a profile tab to the tabbed pane.
     * 
     * @param profileName The profile name
     * @param skipLoading If true, don't load profile data (used when panel will
     *                    handle it)
     */
    private void addProfileTab(String profileName, boolean skipLoading) {
        if (profilePanelMap.containsKey(profileName)) {
            return; // Already exists
        }

        MariaDBProfilePanel profilePanel = new MariaDBProfilePanel(profileName);

        profilePanelMap.put(profileName, profilePanel);

        tabbedPane.addTab(profileName, tabIcon, profilePanel);
        // tabbedPane.setTabComponentAt(1, createTabComponent(profileName,
        // profilePanel));
    }

    /**
     * Create a custom tab component with close button.
     */
    private JComponent createTabComponent(String profileName, MariaDBProfilePanel panel) {
        JPanel tabPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tabPanel.setOpaque(false);

        JLabel label = new JLabel(" " + profileName);
        label.setIcon(tabIcon);
        tabPanel.add(label);

        return tabPanel;
    }

    /**
     * Create a new profile with user-specified name.
     */
    private boolean createNewProfile() {
        return createNewProfile("New-DB-Profile");
    }

    private boolean createNewProfile(String suggestedName) {
        String name = promptForNewProfileName(suggestedName);
        if (name != null && !name.trim().isEmpty()) {

            if (!DatabaseConfigurationUtils.isValidProfileName(name)) {
                JOptionPane.showMessageDialog(this,
                        "Invalid profile name. Use only alphanumeric characters, underscores, and hyphens.",
                        "Invalid Name",
                        JOptionPane.WARNING_MESSAGE);
                return false;
            }

            try {
                Path targetFolder = PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                        .resolve(currentEngine.toString())
                        .resolve(name);

                Files.createDirectories(targetFolder);

                MariadbProfile newProfile = new MariadbProfile(name);
                newProfile.saveToProfileJson(new HashMap<>());

                return true;
            } catch (Exception e) {
                logger.error("Error creating new profile '{}': {}", name, e.getMessage());
                JOptionPane.showMessageDialog(this, "Error creating new profile: " + e.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
        return false;
    }

    private String promptForNewProfileName(String suggestedName) {
        JTextField nameField = new JTextField(suggestedName != null ? suggestedName : "");
        JPanel panel = new JPanel(new MigLayout("wrap 1, fillx, insets 0", "[grow]", "[]5[]"));
        panel.add(new JLabel("Enter a name for the new profile:"));
        panel.add(nameField, "growx");

        int result = JOptionPane.showConfirmDialog(this, panel, "Create New Profile",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String enteredName = nameField.getText().trim();
            if (enteredName.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Profile name cannot be empty.", "Error",
                        JOptionPane.ERROR_MESSAGE);
                return promptForNewProfileName(suggestedName); // Re-prompt
            }
            Path enginePath = PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                    .resolve(currentEngine.toString());
            if (Files.exists(enginePath.resolve(enteredName))) {
                int overwriteChoice = JOptionPane.showConfirmDialog(this,
                        "Profile '" + enteredName + "' already exists. Use this profile?",
                        "Profile Exists", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (overwriteChoice == JOptionPane.NO_OPTION) {
                    return promptForNewProfileName(suggestedName); // Re-prompt
                }
            }
            return enteredName;
        }
        return null;
    }

    /**
     * Callback when a profile panel's settings change.
     */
    private void onProfileSettingsChanged(String profileName, JsonObject settings) {
        if (profileName.equals(activeProfileName)) {
            this.appliedProfileSettings = settings;
        }
        fireDirtyStatusChanged();
    }

    /**
     * Get the index of a tab by title.
     */
    private int getTabIndex(String title) {
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (title.equals(tabbedPane.getTitleAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Get the active profile panel (currently selected tab).
     */
    private MariaDBProfilePanel getActiveProfilePanel() {
        int selectedIndex = tabbedPane.getSelectedIndex();
        if (selectedIndex < 0) {
            return null;
        }
        Component comp = tabbedPane.getComponentAt(selectedIndex);
        if (comp instanceof MariaDBProfilePanel) {
            return (MariaDBProfilePanel) comp;
        }
        return null;
    }

    /**
     * Get active profile name.
     */
    private String getActiveTabTitle() {
        int selectedIndex = tabbedPane.getSelectedIndex();
        if (selectedIndex < 0) {
            return null;
        }
        return tabbedPane.getTitleAt(selectedIndex);
    }

    // ========================================================================
    // DatabaseEnginePanel Interface Implementation
    // Delegates to active profile panel where applicable
    // ========================================================================

    @Override
    public String getEngineName() {
        return DatabaseEngine.MARIADB.getDisplayName();
    }

    @Override
    public Path getProfilePath(String profileName) {
        return PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                .resolve(DatabaseEngine.MARIADB.toString()).resolve(profileName);
    }

    private Path getProfilesRoot() {
        return PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                .resolve(DatabaseEngine.MARIADB.toString());
    }

    @Override
    public void loadProfile(String profileName, GlobalSettings globalSettings) {
        this.globalSettings = globalSettings;

        // Check if profile tab already exists
        if (!profilePanelMap.containsKey(profileName)) {
            addProfileTab(profileName, false);
        }

        // Select the tab
        int tabIndex = getTabIndex(profileName);
        if (tabIndex >= 0) {
            tabbedPane.setSelectedIndex(tabIndex);
        }

        this.loadedProfileName = profileName;
        this.activeProfileName = profileName;
    }

    @Override
    public void resetToDefaults(GlobalSettings globalSettings) {
        this.globalSettings = globalSettings;
        MariaDBProfilePanel activePanel = getActiveProfilePanel();
        if (activePanel != null) {
            activePanel.resetToDefaults(globalSettings);
        }
    }

    @Override
    public String getUnsavedChangesReport() {
        StringBuilder report = new StringBuilder();
        for (Map.Entry<String, MariaDBProfilePanel> entry : profilePanelMap.entrySet()) {
            String profileName = entry.getKey();
            MariaDBProfilePanel panel = entry.getValue();
            String profileReport = panel.getUnsavedChangesReport();
            if (profileReport != null && !profileReport.isEmpty()) {
                if (report.length() > 0) {
                    report.append("\n\n");
                }
                report.append("Profile: ").append(profileName).append("\n").append(profileReport);
            }
        }
        return report.toString();
    }

    @Override
    public boolean hasUnsavedChanges() {
        for (MariaDBProfilePanel panel : profilePanelMap.values()) {
            if (panel.hasUnsavedChanges()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void updateUIFromData() {
        MariaDBProfilePanel activePanel = getActiveProfilePanel();
        if (activePanel != null) {
            activePanel.updateUIFromData();
        }
    }

    @Override
    public void refreshUIColors() {
        for (MariaDBProfilePanel panel : profilePanelMap.values()) {
            panel.refreshUIColors();
        }
    }

    @Override
    public void setLoadedProfileName(String name) {
        this.loadedProfileName = name;
        MariaDBProfilePanel activePanel = getActiveProfilePanel();
        if (activePanel != null) {
            activePanel.setLoadedProfileName(name);
        }
    }

    @Override
    public String getLoadedProfileName() {
        return loadedProfileName != null ? loadedProfileName : getActiveTabTitle();
    }

    @Override
    public void setRunningProfileName(String name) {
        this.runningProfileName = name;
        for (MariaDBProfilePanel panel : profilePanelMap.values()) {
            panel.setRunningProfileName(name);
        }
    }

    @Override
    public String getRunningProfileName() {
        return runningProfileName;
    }

    @Override
    public void setActiveProfileName(String name) {
        this.activeProfileName = name;
    }

    @Override
    public String getActiveProfileName() {
        return activeProfileName != null ? activeProfileName : getActiveTabTitle();
    }

    @Override
    public JsonObject getCurrentProfileSettings() {
        MariaDBProfilePanel activePanel = getActiveProfilePanel();
        if (activePanel != null) {
            return activePanel.getCurrentProfileSettings();
        }
        return appliedProfileSettings;
    }

    @Override
    public void setAppliedProfileSettings(JsonObject settings) {
        this.appliedProfileSettings = settings;
    }

    @Override
    public void setOnDirtyStatusChanged(Runnable listener) {
        this.onDirtyStatusChanged = listener;
    }

    private void fireDirtyStatusChanged() {
        if (onDirtyStatusChanged != null) {
            onDirtyStatusChanged.run();
        }
    }

    @Override
    public List<PropertyRow> getAllPropertyRows() {
        MariaDBProfilePanel activePanel = getActiveProfilePanel();
        if (activePanel != null) {
            return activePanel.getAllPropertyRows();
        }
        return new ArrayList<>();
    }

    @Override
    public Map<String, JComponent> getPropertyComponents() {
        MariaDBProfilePanel activePanel = getActiveProfilePanel();
        if (activePanel != null) {
            return activePanel.getPropertyComponents();
        }
        return new HashMap<>();
    }

    @Override
    public Map<String, Supplier<String>> getValueSuppliers() {
        MariaDBProfilePanel activePanel = getActiveProfilePanel();
        if (activePanel != null) {
            return activePanel.getValueSuppliers();
        }
        return new HashMap<>();
    }

    @Override
    public Map<String, String> getHelpTexts() {
        MariaDBProfilePanel activePanel = getActiveProfilePanel();
        if (activePanel != null) {
            return activePanel.getHelpTexts();
        }
        return new HashMap<>();
    }

    @Override
    public Map<String, String> getDefaultValues() {
        MariaDBProfilePanel activePanel = getActiveProfilePanel();
        if (activePanel != null) {
            return activePanel.getDefaultValues();
        }
        return new HashMap<>();
    }

    @Override
    public void setPathLabel(JLabel label) {
        // No-op: each profile panel manages its own labels
    }

    @Override
    public void setDownloadDatabaseBtn(JButton button) {
        // No-op: each profile panel manages its own buttons
    }

    @Override
    public void setDownloadStatusLabel(JLabel label) {
        // No-op: each profile panel manages its own labels
    }

    @Override
    public void setStep1StatusIcon(JLabel label) {
        // No-op: each profile panel manages its own status icons
    }

    @Override
    public void setStep2StatusIcon(JLabel label) {
        // No-op: each profile panel manages its own status icons
    }

    @Override
    public void setGlobalSettings(GlobalSettings globalSettings) {
        this.globalSettings = globalSettings;
        for (MariaDBProfilePanel panel : profilePanelMap.values()) {
            panel.setGlobalSettings(globalSettings);
        }
    }

    /**
     * Returns the DatabaseEngine for this panel.
     */
    public DatabaseEngine getEngine() {
        return DatabaseEngine.MARIADB;
    }
}