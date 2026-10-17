package application.module.appearance.gui;

import application.module.node.props.Props;
import application.module.appearance.AppearanceModule;
import application.module.appearance.AppearanceProfile;
import application.module.appearance.gui.laf.FlatLafPanel;
import application.module.node.gui.ColorSettingsPanel;
import application.utils.gui.ColorPaletteManager;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiFontManager;
import application.utils.gui.GuiUtils;
import application.utils.gui.HelpButton;
import application.utils.io.PathUtils;

import com.formdev.flatlaf.*;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.util.LoggingFacade;
import com.formdev.flatlaf.util.SystemInfo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import javax.swing.*;
import javax.swing.UIManager.LookAndFeelInfo;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppearancePanel extends JPanel {

    private static AppearancePanel instance;
    private final Runnable backAction;
    private JComboBox<String> profileComboBox;
    private JButton newProfileBtn;
    private JButton cloneProfileBtn;
    private JButton copyProfileBtn;
    private JButton renameProfileBtn;
    private JButton deleteProfileBtn;
    private ColorSettingsPanel colorSettingsPanel;
    private JButton saveProfileBtn;
    private JButton resetToDefaultsBtn;
    private JButton reloadProfileBtn;
    private JTabbedPane tabbedPane;

    public static final String DEFAULT_PROFILE_NAME = "Default";
    private static final String LEGACY_DEFAULT_PROFILE_NAME = "gui";

    private AppearanceProfile savedProfile;
    private boolean isProgrammaticChange = false;
    private boolean lastUnsavedStatus = false;

    // GPU acceleration checkbox (requires restart to take effect)
    private JCheckBox gpuAccelerationCheckBox;

    private static final Logger LOGGER = LoggerFactory.getLogger(AppearancePanel.class);

    public static AppearancePanel getInstance() {
        return instance;
    }

    public ColorSettingsPanel getColorSettingsPanel() {
        return colorSettingsPanel;
    }

    public AppearancePanel(Runnable restartAction, Runnable backAction) {
        super(new BorderLayout());
        this.backAction = backAction;
        instance = this;

        // Determine initial state from file BEFORE the UI is created
        String lastProfile = DEFAULT_PROFILE_NAME;
        boolean legacyProfileExists = false;
        try {
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("lastSelectedLafProfile")) {
                        lastProfile = settings.get("lastSelectedLafProfile").getAsString();
                    }
                    legacyProfileExists = settings.has("lookAndFeelProfiles")
                            && settings.getAsJsonObject("lookAndFeelProfiles")
                                    .has(LEGACY_DEFAULT_PROFILE_NAME);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to read the last selected appearance profile; using '{}'",
                    DEFAULT_PROFILE_NAME, e);
        }
        if (LEGACY_DEFAULT_PROFILE_NAME.equals(lastProfile)
                && !legacyProfileExists) {
            lastProfile = DEFAULT_PROFILE_NAME;
        }
        updateLoadedStateFromProfile(lastProfile);

        isProgrammaticChange = true;
        initUI();
        isProgrammaticChange = false;
        updateProfileButtonStates();

        // Listen for LookAndFeel changes globally
        UIManager.addPropertyChangeListener(e -> {
            if ("lookAndFeel".equals(e.getPropertyName()) && !isProgrammaticChange) {
                // Only trigger dirty status update if the change was manual (not during profile
                // load)
                updateDirtyStatus();
            }
        });
    }

    private void initUI() {
        // --- Profile Panel ---
        JPanel profilePanel = ConfigurationUtils.createProfileToolbarRow();
        profilePanel.setBorder(BorderFactory.createEmptyBorder()); // ResponsiveToolbarScrollPane handles padding

        profileComboBox = new JComboBox<>();
        profileComboBox.setEditable(false);
        profileComboBox.setPrototypeDisplayValue("XXXXXXXXXXXXXXXXXXXX");
        ConfigurationUtils.fixComponentSize(profileComboBox);
        profileComboBox.setToolTipText("<html>Select the appearance profile to edit.<br>"
                + "Default is the built-in baseline.</html>");
        profilePanel.add(profileComboBox);

        newProfileBtn = new JButton();
        newProfileBtn.setToolTipText("<html><body style='width: 360px'>New Profile<br><br>"
                + "Creates and selects a new appearance profile initialized with the built-in Default appearance."
                + "</body></html>");
        newProfileBtn.addActionListener(e -> createNewProfile());
        profilePanel.add(newProfileBtn);

        cloneProfileBtn = new JButton();
        cloneProfileBtn.setToolTipText("<html><body style='width: 360px'>"
                + "Clone Configuration to New Profile<br><br>Creates a new profile from the current appearance, "
                + "including unsaved changes, and selects the new profile.</body></html>");
        cloneProfileBtn.addActionListener(e -> cloneProfile());
        profilePanel.add(cloneProfileBtn);

        copyProfileBtn = new JButton();
        copyProfileBtn.setToolTipText("<html><body style='width: 360px'>"
                + "Copy Existing Configuration to This Profile<br><br>Loads another profile's appearance into "
                + "the current editor as unsaved changes.</body></html>");
        copyProfileBtn.addActionListener(e -> copyProfile());
        profilePanel.add(copyProfileBtn);

        renameProfileBtn = new JButton();
        renameProfileBtn.setToolTipText("<html><body style='width: 360px'>Rename Profile<br><br>"
                + "Renames the selected custom profile. The built-in Default profile cannot be renamed."
                + "</body></html>");
        renameProfileBtn.addActionListener(e -> renameProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(renameProfileBtn);

        saveProfileBtn = new JButton();
        saveProfileBtn.setToolTipText("<html><body style='width: 360px'>Save Profile<br><br>"
                + "Saves the current appearance to the selected custom profile. "
                + "The built-in Default profile cannot be overwritten.</body></html>");
        saveProfileBtn.addActionListener(e -> saveCurrentProfile());
        profilePanel.add(saveProfileBtn);

        resetToDefaultsBtn = new JButton();
        resetToDefaultsBtn.setToolTipText("<html><body style='width: 360px'>Reset to Defaults<br><br>"
                + "Resets the current appearance to the built-in Default values without saving changes to the "
                + "selected profile.</body></html>");
        resetToDefaultsBtn.addActionListener(e -> resetToDefault());
        profilePanel.add(resetToDefaultsBtn);

        reloadProfileBtn = new JButton();
        reloadProfileBtn.setToolTipText("<html><body style='width: 360px'>Refresh<br><br>"
                + "Refreshes the available profile list and reloads the selected profile from disk. "
                + "Any unsaved changes are discarded only after confirmation.</body></html>");
        reloadProfileBtn.addActionListener(e -> refreshAndReloadProfile());
        profilePanel.add(reloadProfileBtn);

        deleteProfileBtn = new JButton();
        deleteProfileBtn.setToolTipText("<html><body style='width: 360px'>Delete Profile<br><br>"
                + "Permanently deletes the selected custom profile. "
                + "The built-in Default profile cannot be deleted.</body></html>");
        deleteProfileBtn.addActionListener(e -> deleteProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(deleteProfileBtn);

        JButton helpBtn = new HelpButton();
        helpBtn.setToolTipText("<html><body style='width: 360px'>Help<br><br>"
                + "Explains appearance profiles and their actions.</body></html>");
        helpBtn.addActionListener(e -> showProfileHelp());
        profilePanel.add(helpBtn);

        updateProfileButtonsUI();
        profileComboBox.addActionListener(e -> {
            if (isProgrammaticChange) {
                return;
            }
            String selected = (String) profileComboBox.getSelectedItem();
            if (selected != null) {
                loadProfile(selected);
            }
            updateProfileButtonStates();
        });

        JScrollPane profileScrollPane = ConfigurationUtils.wrapProfileToolbarRow(profilePanel);

        JPanel northWrapper = new JPanel(new BorderLayout());
        northWrapper.add(profileScrollPane, BorderLayout.CENTER);
        northWrapper.add(new JSeparator(SwingConstants.HORIZONTAL), BorderLayout.SOUTH);

        add(northWrapper, BorderLayout.NORTH);

        tabbedPane = new JTabbedPane();
        GuiUtils.applyDefaultTabLayoutPolicy(tabbedPane);

        FlatLafPanel themePanel = new FlatLafPanel();
        themePanel.setOnChangeListener(this::updateDirtyStatus);
        themePanel.setCloseAction(() -> checkUnsavedChangesAndProceed(true, backAction, null));
        tabbedPane.addTab("Themes & Fonts", themePanel);

        colorSettingsPanel = new ColorSettingsPanel();
        // Sync the color settings panel with the overrides loaded from the profile
        // to prevent false "unsaved changes" detection on startup.
        colorSettingsPanel.setProfileOverrides(savedProfile != null ? savedProfile.getColorOverrides() : null);
        colorSettingsPanel.setOnChangeListener(this::updateDirtyStatus);
        tabbedPane.addTab("Color Settings", colorSettingsPanel);

        add(tabbedPane, BorderLayout.CENTER);

        // GPU Acceleration section at the bottom
        JPanel gpuPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 5));
        gpuPanel.setOpaque(false);
        gpuPanel.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));

        gpuAccelerationCheckBox = new JCheckBox("GPU Acceleration (sun.java2d.opengl)");
        gpuAccelerationCheckBox.setToolTipText("<html>Enable GPU acceleration for Java 2D rendering.<br>"
                + "Requires an application restart to take effect.<br>"
                + "When enabled, sets sun.java2d.opengl=true at startup.</html>");
        GuiFontManager.applyDefaultFont(gpuAccelerationCheckBox);

        gpuAccelerationCheckBox.addItemListener(e -> {
            saveGpuAccelerationSetting(gpuAccelerationCheckBox.isSelected());
        });

        // Load current GPU acceleration setting
        gpuAccelerationCheckBox.setSelected(loadGpuAccelerationSetting());

        gpuPanel.add(gpuAccelerationCheckBox);

        // Add separator and GPU panel at SOUTH
        JPanel southWrapper = new JPanel(new BorderLayout());
        southWrapper.setOpaque(false);
        southWrapper.add(new JSeparator(SwingConstants.HORIZONTAL), BorderLayout.NORTH);
        southWrapper.add(gpuPanel, BorderLayout.CENTER);
        add(southWrapper, BorderLayout.SOUTH);

        loadProfiles(profileComboBox);
    }

    private void updateProfileButtonStates() {
        if (profileComboBox == null || renameProfileBtn == null || deleteProfileBtn == null || saveProfileBtn == null) {
            return;
        }
        String selected = (String) profileComboBox.getSelectedItem();
        boolean isDefault = DEFAULT_PROFILE_NAME.equals(selected);
        renameProfileBtn.setEnabled(!isDefault);
        deleteProfileBtn.setEnabled(!isDefault);
        saveProfileBtn.setEnabled(!isDefault && hasUnsavedChanges());
    }

    private boolean hasUnsavedChanges() {
        if (savedProfile == null)
            return false;

        AppearanceProfile currentUI = new AppearanceProfile(savedProfile.getName());
        currentUI.setThemeClassName(UIManager.getLookAndFeel().getClass().getName());

        currentUI.setGlobalFont(UIManager.getFont("Label.font"));
        // Fallback for console font if not explicitly set in UIManager
        currentUI.setConsoleFont(UIManager.getFont("TextArea.font"));
        currentUI.setColorOverrides(
                colorSettingsPanel != null ? colorSettingsPanel.getCurrentOverrides() : new HashMap<>());

        return !currentUI.equals(savedProfile);
    }

    private void updateDirtyStatus() {
        if (isProgrammaticChange || savedProfile == null)
            return;
        boolean dirty = hasUnsavedChanges();
        saveProfileBtn.setToolTipText("<html><body style='width: 360px'>Save Profile" + (dirty ? " *" : "")
                + "<br><br>Saves the current appearance to the selected custom profile. "
                + "The built-in Default profile cannot be overwritten.</body></html>");
        saveProfileBtn.setEnabled(!DEFAULT_PROFILE_NAME.equals(savedProfile.getName()) && dirty);

        // Mark tabs with an asterisk if there are unsaved changes
        boolean colorsDirty = colorSettingsPanel != null
                && !colorSettingsPanel.getCurrentOverrides().equals(savedProfile.getColorOverrides());
        boolean themeDirty = !UIManager.getLookAndFeel().getClass().getName().equals(savedProfile.getThemeClassName())
                || !fontsMatch(UIManager.getFont("Label.font"), savedProfile.getGlobalFont());

        updateTabTitle(0, "Themes & Fonts", themeDirty);
        updateTabTitle(1, "Color Settings", colorsDirty);
    }

    private void updateTabTitle(int index, String baseTitle, boolean dirty) {
        String currentTitle = tabbedPane.getTitleAt(index);
        String newTitle = dirty ? baseTitle + " *" : baseTitle;
        if (!currentTitle.equals(newTitle)) {
            tabbedPane.setTitleAt(index, newTitle);
        }
    }

    private void createNewProfile() {
        checkUnsavedChangesAndProceed(false, () -> { // allowKeepUnsaved = false
            String name = (String) JOptionPane.showInputDialog(this, "Enter new profile name:", "New Profile",
                    JOptionPane.PLAIN_MESSAGE, null, null, "");
            if (name == null || name.trim().isEmpty())
                return;
            String profileName = name.trim();
            if (DEFAULT_PROFILE_NAME.equals(profileName)) {
                JOptionPane.showMessageDialog(this, "The built-in Default profile name is reserved.",
                        "Invalid Profile Name", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (containsProfile(profileName) || profileExistsInSettings(profileName)) {
                JOptionPane.showMessageDialog(this, "Profile '" + profileName + "' already exists.",
                        "Profile Already Exists", JOptionPane.WARNING_MESSAGE);
                return;
            }

            // Create new profile with default values
            SwingUtilities.invokeLater(() -> {
                resetToDefaultNow();
                saveProfileInternal(profileName, false);
            });
        }, null);
    }

    private void cloneProfile() {
        String name = (String) JOptionPane.showInputDialog(this, "Name for the new profile:",
                "Clone Configuration to New Profile", JOptionPane.PLAIN_MESSAGE, null, null, "");
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        name = name.trim();
        if (DEFAULT_PROFILE_NAME.equals(name)) {
            JOptionPane.showMessageDialog(this, "The built-in Default profile cannot be overwritten.",
                    "Action Not Allowed", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (containsProfile(name) || profileExistsInSettings(name)) {
            JOptionPane.showMessageDialog(this, "Profile '" + name + "' already exists.",
                    "Profile Already Exists", JOptionPane.WARNING_MESSAGE);
            return;
        }
        saveProfileInternal(name, false);
    }

    private void copyProfile() {
        java.util.List<String> profiles = new java.util.ArrayList<>();
        profiles.add(DEFAULT_PROFILE_NAME);
        for (int i = 0; i < profileComboBox.getItemCount(); i++) {
            String profile = profileComboBox.getItemAt(i);
            if (!DEFAULT_PROFILE_NAME.equals(profile)) {
                profiles.add(profile);
            }
        }
        if (profiles.size() == 1) {
            JOptionPane.showMessageDialog(this, "There are no custom profiles to copy from.",
                    "Copy Configuration", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String current = (String) profileComboBox.getSelectedItem();
        Object source = JOptionPane.showInputDialog(this, "Copy appearance from profile:",
                "Copy Existing Configuration to This Profile", JOptionPane.QUESTION_MESSAGE, null,
                profiles.toArray(), current != null ? current : DEFAULT_PROFILE_NAME);
        if (source == null) {
            return;
        }
        if (hasUnsavedChanges()) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "The current appearance has unsaved changes that will be replaced. Continue?",
                    "Copy Existing Configuration to This Profile", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }
        }
        if (DEFAULT_PROFILE_NAME.equals(source)) {
            resetToDefault();
            return;
        }
        AppearanceProfile sourceProfile = ConfigurationUtils.loadLookAndFeelProfile(getGuiSettingsPath(),
                source.toString());
        if (sourceProfile == null || !profileExistsInSettings(source.toString())) {
            JOptionPane.showMessageDialog(this, "Failed to load profile '" + source + "'.",
                    "Copy Configuration", JOptionPane.ERROR_MESSAGE);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            applyAppearanceProfile(sourceProfile);
            updateDirtyStatus();
        });
    }

    private void applyAppearanceProfile(AppearanceProfile profile) {
        boolean snapshotShown = false;
        try {
            FlatAnimatedLafChange.showSnapshot();
            snapshotShown = true;
            String theme = profile.getThemeClassName() != null
                    ? profile.getThemeClassName()
                    : FlatDarkLaf.class.getName();
            UIManager.setLookAndFeel(theme);
            AppearanceModule.updateCommonFontKeys(profile.getGlobalFont());
            AppearanceModule.updateCommonConsoleFontKeys(profile.getConsoleFont());
            ColorPaletteManager.updatePalette(profile.getColorOverrides());
            colorSettingsPanel.setProfileOverrides(profile.getColorOverrides());
            AppearanceModule.updateAllUIs();
            FlatAnimatedLafChange.hideSnapshotWithAnimation();
            snapshotShown = false;
        } catch (Exception e) {
            LOGGER.error("Failed to copy appearance profile '{}'", profile.getName(), e);
            JOptionPane.showMessageDialog(this, "Failed to copy profile '" + profile.getName()
                    + "': " + e.getMessage(), "Copy Configuration", JOptionPane.ERROR_MESSAGE);
        } finally {
            if (snapshotShown) {
                FlatAnimatedLafChange.hideSnapshotWithAnimation();
            }
        }
    }

    private void saveCurrentProfile() {
        String selected = (String) profileComboBox.getSelectedItem();
        if (selected == null || DEFAULT_PROFILE_NAME.equals(selected)) {
            JOptionPane.showMessageDialog(this, "The built-in Default profile cannot be overwritten. "
                    + "Use Clone Configuration to New Profile to save a copy.",
                    "Action Not Allowed", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!hasUnsavedChanges()) {
            return;
        }
        saveProfileInternal(selected, false);
    }

    private void refreshProfileList() {
        boolean wasProgrammatic = isProgrammaticChange;
        try {
            isProgrammaticChange = true;
            String currentSelection = (String) profileComboBox.getSelectedItem();
            profileComboBox.removeAllItems();
            loadProfiles(profileComboBox);
            if (containsProfile(currentSelection)) {
                profileComboBox.setSelectedItem(currentSelection);
            } else {
                profileComboBox.setSelectedItem(DEFAULT_PROFILE_NAME);
            }
        } finally {
            isProgrammaticChange = wasProgrammatic;
        }
        updateProfileButtonStates();
    }

    private void refreshAndReloadProfile() {
        if (hasUnsavedChanges()) {
            int result = JOptionPane.showConfirmDialog(this,
                    "Refresh the profile list and reload the selected profile from disk? "
                            + "Unsaved changes will be discarded.",
                    "Refresh Appearance Profiles", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (result != JOptionPane.YES_OPTION) {
                return;
            }
        }
        String selected = (String) profileComboBox.getSelectedItem();
        refreshProfileList();
        if (containsProfile(selected)) {
            profileComboBox.setSelectedItem(selected);
        }
        String profileToReload = (String) profileComboBox.getSelectedItem();
        if (profileToReload != null) {
            performLoadProfile(profileToReload);
        }
    }

    private boolean containsProfile(String name) {
        if (name == null) {
            return false;
        }
        for (int i = 0; i < profileComboBox.getItemCount(); i++) {
            if (name.equals(profileComboBox.getItemAt(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean profileExistsInSettings(String name) {
        if (name == null) {
            return false;
        }
        Path settingsPath = getGuiSettingsPath();
        if (Files.notExists(settingsPath)) {
            return false;
        }
        try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
            JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
            return settings.has("lookAndFeelProfiles")
                    && settings.getAsJsonObject("lookAndFeelProfiles").has(name);
        } catch (IOException | IllegalStateException e) {
            LOGGER.warn("Failed to inspect appearance profile '{}'", name, e);
            return false;
        }
    }

    private void updateLoadedStateFromProfile(String profileName) {
        this.savedProfile = DEFAULT_PROFILE_NAME.equals(profileName)
                ? new AppearanceProfile(DEFAULT_PROFILE_NAME)
                : ConfigurationUtils.loadLookAndFeelProfile(getGuiSettingsPath(), profileName);
        if (this.savedProfile != null && this.savedProfile.getThemeClassName() == null) {
            this.savedProfile.setThemeClassName(FlatDarkLaf.class.getName());
        }
        if (colorSettingsPanel != null) {
            colorSettingsPanel.setProfileOverrides(savedProfile.getColorOverrides());
        }
        lastUnsavedStatus = false;
    }

    private Font parseFont(JsonObject fontSettings) {
        String family = fontSettings.get("family").getAsString();
        int style = fontSettings.get("style").getAsInt();
        int size = fontSettings.get("size").getAsInt();
        return new Font(family, style, size);
    }

    private void updateProfileButtonsUI() {
        if (profileComboBox != null) {
            ConfigurationUtils.fixComponentSize(profileComboBox);
        }
        ConfigurationUtils.configureProfileToolbar(newProfileBtn, saveProfileBtn, null, renameProfileBtn,
                deleteProfileBtn, null, reloadProfileBtn, resetToDefaultsBtn, copyProfileBtn, cloneProfileBtn);
        updateProfileButtonStates();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Re-apply icons and recalculate sizes based on new font metrics
        updateProfileButtonsUI();
    }

    public JComboBox<String> getProfileComboBox() {
        return profileComboBox;
    }

    public boolean saveProfile() {
        String currentProfile = (String) profileComboBox.getSelectedItem();
        String suggestedName = currentProfile != null ? currentProfile : "";

        JTextField nameField = new JTextField(suggestedName);
        // Reserved names are rejected by saveProfileInternal().

        JPanel panel = new JPanel(new MigLayout("wrap 1, fillx, insets 0", "[grow]", "[]5[]5[]"));
        panel.add(new JLabel("Enter profile name:"));
        panel.add(nameField, "growx");

        JButton saveBtn = new JButton("Save");
        JButton discardBtn = new JButton("Discard");
        JButton cancelBtn = new JButton("Cancel");

        String report = getUnsavedChangesReport();
        if (report != null) {
            JLabel reportLabel = new JLabel(report);
            JScrollPane scroll = new JScrollPane(reportLabel);
            scroll.setPreferredSize(new Dimension(450, 150));
            scroll.setBorder(BorderFactory.createTitledBorder("Changes to be saved"));
            panel.add(scroll, "growx, gaptop 10");
        }

        Object[] options = { saveBtn, discardBtn, cancelBtn };

        JOptionPane pane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.YES_NO_CANCEL_OPTION, null,
                options, saveBtn);
        JDialog dialog = pane.createDialog(this, "Save Profile As");

        saveBtn.addActionListener(e -> {
            pane.setValue(saveBtn);
            dialog.setVisible(false);
        });
        discardBtn.addActionListener(e -> {
            pane.setValue(discardBtn);
            dialog.setVisible(false);
        });
        cancelBtn.addActionListener(e -> {
            pane.setValue(cancelBtn);
            dialog.setVisible(false);
        });

        Runnable validate = () -> {
            String text = nameField.getText().trim();
            saveBtn.setEnabled(!text.isEmpty());
            dialog.pack();
        };

        nameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                validate.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                validate.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                validate.run();
            }
        });

        validate.run();

        try {
            while (true) {
                pane.setValue(JOptionPane.UNINITIALIZED_VALUE);
                dialog.setVisible(true);
                Object value = pane.getValue();

                if (value == saveBtn) {
                    String name = nameField.getText().trim();
                    if (saveProfileInternal(name, true)) {
                        return true;
                    }
                } else if (value == discardBtn) {
                    performLoadProfile(savedProfile != null ? savedProfile.getName() : DEFAULT_PROFILE_NAME);
                    return false;
                } else {
                    return false;
                }
            }
        } finally {
            dialog.dispose();
        }
    }

    private boolean saveProfileInternal(String name, boolean confirmOverwrite) {
        if (name == null || name.trim().isEmpty() || DEFAULT_PROFILE_NAME.equals(name.trim())) {
            JOptionPane.showMessageDialog(this, "The built-in Default profile cannot be overwritten.",
                    "Action Not Allowed", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        name = name.trim();
        try {
            Path settingsPath = getGuiSettingsPath();
            JsonObject settings;
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    settings = JsonParser.parseReader(reader).getAsJsonObject();
                }
            } else {
                settings = new JsonObject();
            }

            if (!settings.has("lookAndFeelProfiles")) {
                settings.add("lookAndFeelProfiles", new JsonObject());
            }
            JsonObject profiles = settings.getAsJsonObject("lookAndFeelProfiles");

            if (confirmOverwrite && profiles.has(name)) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "Profile '" + name + "' already exists. Do you want to overwrite it?",
                        "Override profile settings",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) {
                    return false;
                }
            }

            JsonObject lafSettings = new JsonObject();
            String currentTheme = UIManager.getLookAndFeel().getClass().getName();
            lafSettings.addProperty("theme", currentTheme);

            // Always save font information (global and console)
            Font font = UIManager.getFont("Label.font");
            JsonObject fontSettings = new JsonObject();
            fontSettings.addProperty("family", font.getFamily());
            fontSettings.addProperty("style", font.getStyle());
            fontSettings.addProperty("size", font.getSize());
            lafSettings.add("font", fontSettings);

            Font consoleFont = UIManager.getFont("TextArea.font");
            JsonObject consoleFontSettings = new JsonObject();
            consoleFontSettings.addProperty("family", consoleFont.getFamily());
            consoleFontSettings.addProperty("style", consoleFont.getStyle());
            consoleFontSettings.addProperty("size", consoleFont.getSize());
            lafSettings.add("consoleFont", consoleFontSettings);

            Map<String, Color> overrides = colorSettingsPanel.getCurrentOverrides();
            if (overrides != null && !overrides.isEmpty()) {
                JsonObject overridesJson = new JsonObject();
                for (Map.Entry<String, Color> entry : overrides.entrySet()) {
                    overridesJson.addProperty(entry.getKey(), toHexString(entry.getValue()));
                }
                lafSettings.add("colorOverrides", overridesJson);
            }

            profiles.add(name, lafSettings);
            settings.addProperty("lastSelectedLafProfile", name);

            if (Files.notExists(settingsPath.getParent())) {
                Files.createDirectories(settingsPath.getParent());
            }

            try (BufferedWriter writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                writer.write(gson.toJson(settings));
            }

            isProgrammaticChange = true;
            try {
                updateLoadedStateFromProfile(name);

                // Update combobox
                boolean exists = false;
                for (int i = 0; i < profileComboBox.getItemCount(); i++) {
                    if (profileComboBox.getItemAt(i).equals(name)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    profileComboBox.addItem(name);
                }
                profileComboBox.setSelectedItem(name);
            } finally {
                isProgrammaticChange = false;
            }

            JOptionPane.showMessageDialog(this, "Profile '" + name + "' saved successfully.", "Success",
                    JOptionPane.INFORMATION_MESSAGE);
            return true;

        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error saving profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            LOGGER.error("Failed to save appearance profile '{}'", name, e);
            return false;
        }
    }

    private void loadProfiles(JComboBox<String> comboBox) {
        try {
            comboBox.addItem(DEFAULT_PROFILE_NAME);
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("lookAndFeelProfiles")) {
                        JsonObject profiles = settings.getAsJsonObject("lookAndFeelProfiles");
                        for (String profileName : profiles.keySet()) {
                            if (DEFAULT_PROFILE_NAME.equals(profileName)) {
                                continue;
                            }
                            comboBox.addItem(profileName);
                        }
                    }
                    if (settings.has("lastSelectedLafProfile")) {
                        String lastSelected = settings.get("lastSelectedLafProfile").getAsString();
                        if (LEGACY_DEFAULT_PROFILE_NAME.equals(lastSelected)
                                && !comboHasItem(comboBox, LEGACY_DEFAULT_PROFILE_NAME)) {
                            lastSelected = DEFAULT_PROFILE_NAME;
                        }
                        if (comboHasItem(comboBox, lastSelected)) {
                            comboBox.setSelectedItem(lastSelected);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load appearance profiles from disk", e);
        }
    }

    private static boolean comboHasItem(JComboBox<String> comboBox, String name) {
        for (int i = 0; i < comboBox.getItemCount(); i++) {
            if (name.equals(comboBox.getItemAt(i))) {
                return true;
            }
        }
        return false;
    }

    public void renameProfile(String oldProfileName) {
        if (oldProfileName == null || oldProfileName.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No profile selected to rename.", "Rename Profile",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (DEFAULT_PROFILE_NAME.equals(oldProfileName)) {
            JOptionPane.showMessageDialog(this, "The built-in Default profile cannot be renamed.",
                    "Action Not Allowed", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String newProfileName = (String) JOptionPane.showInputDialog(
                this,
                "Enter new name for profile '" + oldProfileName + "':",
                "Rename Profile",
                JOptionPane.PLAIN_MESSAGE,
                null,
                null,
                oldProfileName);

        if (newProfileName == null || newProfileName.trim().isEmpty()
                || newProfileName.trim().equals(oldProfileName)) {
            return; // User cancelled or entered the same name
        }
        newProfileName = newProfileName.trim();
        if (DEFAULT_PROFILE_NAME.equals(newProfileName)) {
            JOptionPane.showMessageDialog(this, "The built-in Default profile name is reserved.",
                    "Invalid Profile Name", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            Path settingsPath = getGuiSettingsPath();
            JsonObject settings;
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    settings = JsonParser.parseReader(reader).getAsJsonObject();
                }
            } else {
                JOptionPane.showMessageDialog(this, "Settings file not found.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (!settings.has("lookAndFeelProfiles")) {
                JOptionPane.showMessageDialog(this, "No profiles found in settings file.", "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            JsonObject profiles = settings.getAsJsonObject("lookAndFeelProfiles");

            if (profiles.has(newProfileName)) {
                JOptionPane.showMessageDialog(this, "A profile with the name '" + newProfileName + "' already exists.",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (profiles.has(oldProfileName)) {
                JsonElement profileData = profiles.remove(oldProfileName);
                profiles.add(newProfileName, profileData);

                if (settings.has("lastSelectedLafProfile")
                        && settings.get("lastSelectedLafProfile").getAsString().equals(oldProfileName)) {
                    settings.addProperty("lastSelectedLafProfile", newProfileName);
                }

                try (BufferedWriter writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                    Gson gson = new GsonBuilder().setPrettyPrinting().create();
                    writer.write(gson.toJson(settings));
                }

                updateLoadedStateFromProfile(newProfileName);
                // Update combobox
                profileComboBox.removeItem(oldProfileName);
                profileComboBox.addItem(newProfileName);
                profileComboBox.setSelectedItem(newProfileName);
                updateProfileButtonStates();

                JOptionPane.showMessageDialog(this,
                        "Profile '" + oldProfileName + "' renamed to '" + newProfileName + "' successfully.", "Success",
                        JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, "Profile '" + oldProfileName + "' not found.", "Error",
                        JOptionPane.ERROR_MESSAGE);
            }

        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error renaming profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            LOGGER.error("Failed to rename appearance profile '{}' to '{}'", oldProfileName, newProfileName, e);
        }
    }

    public void loadProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()
                || (savedProfile != null && profileName.equals(savedProfile.getName())))
            return;

        checkUnsavedChangesAndProceed(false,
                () -> performLoadProfile(profileName),
                () -> profileComboBox
                        .setSelectedItem(savedProfile != null ? savedProfile.getName() : DEFAULT_PROFILE_NAME));
    }

    public boolean checkUnsavedChangesAndProceed(boolean allowKeepUnsaved, Runnable onProceed, Runnable onCancel) {
        String report = getUnsavedChangesReport();
        if (report == null) {
            if (onProceed != null)
                onProceed.run();
            return true;
        }

        Object[] message = {
                "You have unsaved changes in profile '"
                        + (savedProfile != null ? savedProfile.getName() : DEFAULT_PROFILE_NAME) + "'.",
                report,
                "What would you like to do?"
        };
        Object[] options;
        if (allowKeepUnsaved) {
            options = new Object[] { "Save Profile As", "Keep Unsaved", "Discard", "Cancel" };
        } else {
            options = new Object[] { "Save Profile As", "Discard", "Cancel" };
        }

        int result = JOptionPane.showOptionDialog(this, message, "Unsaved Changes",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);

        if (result == 0) { // Save Profile As
            if (saveProfile()) {
                if (onProceed != null)
                    onProceed.run();
                return true;
            }
            return false;
        }

        int discardIndex = allowKeepUnsaved ? 2 : 1;
        int keepUnsavedIndex = 1;

        if (allowKeepUnsaved && result == keepUnsavedIndex) {
            if (onProceed != null)
                onProceed.run();
            return true;
        } else if (result == discardIndex) {
            performLoadProfile(savedProfile != null ? savedProfile.getName() : DEFAULT_PROFILE_NAME);
            if (onProceed != null) {
                SwingUtilities.invokeLater(onProceed);
            }
            return true;
        } else { // Cancel
            if (onCancel != null)
                onCancel.run();
            return false;
        }
    }

    private String getUnsavedChangesReport() {
        if (savedProfile == null)
            return null;
        StringBuilder report = new StringBuilder(
                "<html><b>Unsaved changes in Look and Feel Settings (Profile: '" + savedProfile.getName()
                        + "'):</b><ul>");
        boolean changesFound = false;

        // Check theme
        String currentTheme = UIManager.getLookAndFeel().getClass().getName();
        if (!currentTheme.equals(savedProfile.getThemeClassName())) {
            changesFound = true;
            String oldName = getLookAndFeelName(savedProfile.getThemeClassName());
            String newName = UIManager.getLookAndFeel().getName();
            report.append("<li>Theme: '").append(oldName).append("' &rarr; '").append(newName).append("'</li>");
        }

        // Check global font
        Font currentGlobal = AppearanceModule.getActiveCustomFont();
        if (!fontsMatch(currentGlobal, savedProfile.getGlobalFont())) {
            changesFound = true;
            report.append("<li>Global font: '").append(formatFont(savedProfile.getGlobalFont())).append("' &rarr; '")
                    .append(formatFont(currentGlobal)).append("'</li>");
        }

        // Check console font
        Font currentConsole = AppearanceModule.getActiveConsoleFont();
        if (!fontsMatch(currentConsole, savedProfile.getConsoleFont())) {
            changesFound = true;
            report.append("<li>Console font: '").append(formatFont(savedProfile.getConsoleFont())).append("' &rarr; '")
                    .append(formatFont(currentConsole)).append("'</li>");
        }

        // Check colors
        Map<String, Color> currentColors = colorSettingsPanel != null ? colorSettingsPanel.getCurrentOverrides()
                : Collections.emptyMap();
        if (!currentColors.equals(savedProfile.getColorOverrides())) {
            changesFound = true;
            report.append("<li>Color modifications:<ul>");

            // Find added or modified overrides
            for (Map.Entry<String, Color> entry : currentColors.entrySet()) {
                String key = entry.getKey();
                Color newVal = entry.getValue();
                Color oldVal = savedProfile.getColorOverrides().get(key);

                if (oldVal == null) {
                    Color themeColor = ColorPaletteManager.getThemeColor(key);
                    report.append("<li>").append(key).append(": default ").append(toHexString(themeColor))
                            .append(" &rarr; ").append(toHexString(newVal)).append("</li>");
                } else if (!oldVal.equals(newVal)) {
                    report.append("<li>").append(key).append(": '").append(toHexString(oldVal)).append("' &rarr; '")
                            .append(toHexString(newVal)).append("'</li>");
                }
            }

            // Find removed overrides (reverted to palette default)
            for (String key : savedProfile.getColorOverrides().keySet()) {
                if (!currentColors.containsKey(key)) {
                    Color themeColor = ColorPaletteManager.getThemeColor(key);
                    report.append("<li>").append(key).append(": '")
                            .append(toHexString(savedProfile.getColorOverrides().get(key)))
                            .append("' &rarr; default ").append(toHexString(themeColor)).append("</li>");
                }
            }
            report.append("</ul></li>");
        }

        report.append("</ul></html>");
        return changesFound ? report.toString() : null;
    }

    private String getLookAndFeelName(String className) {
        if (className == null) {
            className = FlatDarkLaf.class.getName();
        }
        for (UIManager.LookAndFeelInfo laf : UIManager.getInstalledLookAndFeels()) {
            if (laf.getClassName().equals(className)) {
                return laf.getName();
            }
        }
        // Fallback to simple class name if not found in installed LAFs
        int lastDot = className.lastIndexOf('.');
        return lastDot != -1 ? className.substring(lastDot + 1) : className;
    }

    private String formatFont(Font f) {
        if (f == null)
            return "Default";
        return f.getFamily() + " " + f.getSize();
    }

    private void performLoadProfile(String profileName) {
        // Let the current selector/button event finish before FlatLaf captures
        // the visible windows for its animated theme transition.
        SwingUtilities.invokeLater(() -> performLoadProfileNow(profileName));
    }

    private void performLoadProfileNow(String profileName) {
        LOGGER.info("Loading Look and Feel profile: '{}'", profileName);
        isProgrammaticChange = true;
        boolean snapshotShown = false;
        try {
            if (DEFAULT_PROFILE_NAME.equals(profileName)) {
                resetToDefaultInternal();
                Path settingsPath = getGuiSettingsPath();
                JsonObject settings = new JsonObject();
                if (Files.exists(settingsPath)) {
                    try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                        settings = JsonParser.parseReader(reader).getAsJsonObject();
                    }
                }
                settings.addProperty("lastSelectedLafProfile", DEFAULT_PROFILE_NAME);
                if (Files.notExists(settingsPath.getParent())) {
                    Files.createDirectories(settingsPath.getParent());
                }
                try (BufferedWriter writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                    new GsonBuilder().setPrettyPrinting().create().toJson(settings, writer);
                }
                profileComboBox.setSelectedItem(DEFAULT_PROFILE_NAME);
                updateLoadedStateFromProfile(DEFAULT_PROFILE_NAME);
                return;
            }

            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("lookAndFeelProfiles")) {
                        JsonObject profiles = settings.getAsJsonObject("lookAndFeelProfiles");
                        if (profiles.has(profileName)) {
                            JsonObject lafSettings = profiles.getAsJsonObject(profileName);
                            FlatAnimatedLafChange.showSnapshot();
                            snapshotShown = true;

                            Map<String, Color> colorOverrides = null;

                            // Apply settings
                            String themeClassName = null;
                            if (lafSettings.has("theme")) {
                                themeClassName = lafSettings.get("theme").getAsString();
                            }

                            if (lafSettings.has("colorOverrides")) {
                                colorOverrides = parseColorOverrides(lafSettings.getAsJsonObject("colorOverrides"));
                            }

                            if (themeClassName != null) {
                                UIManager.setLookAndFeel(themeClassName);
                            }

                            if (lafSettings.has("font")) {
                                JsonObject fontSettings = lafSettings.getAsJsonObject("font");
                                String family = fontSettings.get("family").getAsString();
                                int style = fontSettings.get("style").getAsInt();
                                int size = fontSettings.get("size").getAsInt();
                                Font font = new Font(family, style, size);
                                AppearanceModule.updateCommonFontKeys(font);
                            } else {
                                AppearanceModule.updateCommonFontKeys(null);
                            }

                            if (lafSettings.has("consoleFont")) {
                                JsonObject fontSettings = lafSettings.getAsJsonObject("consoleFont");
                                String family = fontSettings.get("family").getAsString();
                                int style = fontSettings.get("style").getAsInt();
                                int size = fontSettings.get("size").getAsInt();
                                Font font = new Font(family, style, size);
                                AppearanceModule.updateCommonConsoleFontKeys(font);
                            } else {
                                AppearanceModule.updateCommonConsoleFontKeys(null);
                            }

                            AppearanceModule.updateAllUIs();
                            colorSettingsPanel.setProfileOverrides(colorOverrides);
                            updateLoadedStateFromProfile(profileName);
                            profileComboBox.setSelectedItem(profileName);

                            // Persist before capturing the new UI snapshot so
                            // the selector and all other visible state belong
                            // to the same frame as the newly applied theme.
                            settings.addProperty("lastSelectedLafProfile", profileName);
                            try (BufferedWriter writer = Files.newBufferedWriter(settingsPath,
                                    StandardCharsets.UTF_8)) {
                                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                                writer.write(gson.toJson(settings));
                            }

                            FlatAnimatedLafChange.hideSnapshotWithAnimation();
                            snapshotShown = false;
                            return;
                        }
                    }
                }
            }
            throw new IllegalArgumentException("Appearance profile '" + profileName + "' was not found.");
        } catch (Exception e) {
            LOGGER.error("Failed to load appearance profile '{}'", profileName, e);
            JOptionPane.showMessageDialog(this, "Error loading profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        } finally {
            if (snapshotShown) {
                FlatAnimatedLafChange.hideSnapshotWithAnimation();
            }
            isProgrammaticChange = false;
            updateDirtyStatus();
        }
    }

    private void resetToDefault() {
        SwingUtilities.invokeLater(this::resetToDefaultNow);
    }

    private void resetToDefaultNow() {
        isProgrammaticChange = true;
        try {
            resetToDefaultInternal();
        } finally {
            isProgrammaticChange = false;
            updateDirtyStatus();
        }
    }

    private void resetToDefaultInternal() {
        boolean snapshotShown = false;
        try {
            FlatAnimatedLafChange.showSnapshot();
            snapshotShown = true;
            // Reset to a known default theme, e.g., FlatDarkLaf
            UIManager.setLookAndFeel(FlatDarkLaf.class.getName());
            // Reset font
            AppearanceModule.updateCommonFontKeys(null);
            AppearanceModule.updateCommonConsoleFontKeys(null);
            // Reset colors
            ColorPaletteManager.updatePalette(null);
            colorSettingsPanel.setProfileOverrides(null);
            AppearanceModule.updateAllUIs();
            FlatAnimatedLafChange.hideSnapshotWithAnimation();
            snapshotShown = false;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error resetting to default: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        } finally {
            if (snapshotShown) {
                FlatAnimatedLafChange.hideSnapshotWithAnimation();
            }
        }

    }

    private static Map<String, Color> parseColorOverrides(JsonObject overridesJson) {
        if (overridesJson == null) {
            return null;
        }
        Map<String, Color> overrides = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : overridesJson.entrySet()) {
            try {
                overrides.put(entry.getKey(), Color.decode(entry.getValue().getAsString()));
            } catch (NumberFormatException e) {
                // Consider logging this warning
            }
        }
        return overrides;
    }

    private static String toHexString(Color color) {
        if (color.getAlpha() == 255) {
            return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
        } else {
            return String.format("#%02X%02X%02X%02X", color.getAlpha(), color.getRed(), color.getGreen(),
                    color.getBlue());
        }
    }

    private void showProfileHelp() {
        JPanel help = new JPanel(new MigLayout("wrap 2, insets 8, gapx 10, gapy 6", "[][grow]", ""));
        help.add(new JLabel("<html><h2>Look and Feel Profiles</h2>"
                + "Appearance profiles save and load themes, fonts and color settings.<br>"
                + "The built-in <b>Default</b> profile is read-only.</html>"), "span 2, growx, wrap");

        addProfileHelpRow(help, newProfileBtn, "New Profile",
                "Creates and selects a profile initialized with the built-in Default appearance.");
        addProfileHelpRow(help, cloneProfileBtn, "Clone Configuration to New Profile",
                "Creates and selects a new profile from the current appearance, including unsaved changes.");
        addProfileHelpRow(help, copyProfileBtn, "Copy Existing Configuration to This Profile",
                "Loads another profile into the current editor as unsaved changes.");
        addProfileHelpRow(help, renameProfileBtn, "Rename Profile",
                "Renames the selected custom profile. Default cannot be renamed.");
        addProfileHelpRow(help, saveProfileBtn, "Save Profile",
                "Saves the current appearance to the selected custom profile. Default cannot be overwritten.");
        addProfileHelpRow(help, resetToDefaultsBtn, "Reset to Defaults",
                "Loads built-in Default values into the editor without saving to the selected profile.");
        addProfileHelpRow(help, reloadProfileBtn, "Refresh",
                "Refreshes the profile list and reloads the selected profile from disk after confirmation.");
        addProfileHelpRow(help, deleteProfileBtn, "Delete Profile",
                "Permanently deletes the selected custom profile. Default cannot be deleted.");

        help.add(new JLabel("<html>Profiles are stored in <code>gui-settings.json</code> "
                + "in your settings directory.</html>"), "span 2, gaptop 4, wrap");
        JScrollPane scroll = new JScrollPane(help);
        scroll.setPreferredSize(new Dimension(540, 430));
        JOptionPane.showMessageDialog(this, scroll, "About Look and Feel Profiles",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void addProfileHelpRow(JPanel help, JButton actionButton, String title, String description) {
        JLabel icon = new JLabel(actionButton.getIcon());
        icon.setVerticalAlignment(SwingConstants.TOP);
        help.add(icon, "aligny top");
        help.add(new JLabel("<html><b>" + title + "</b><br>" + description + "</html>"), "growx, wrap");
    }

    public void deleteProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()) {
            return;
        }
        if (DEFAULT_PROFILE_NAME.equals(profileName)) {
            JOptionPane.showMessageDialog(this, "The '" + DEFAULT_PROFILE_NAME + "' profile cannot be deleted.",
                    "Action Not Allowed",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        int choice = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to delete profile '" + profileName + "'?",
                "Confirm Deletion",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                JsonObject settings;
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    settings = JsonParser.parseReader(reader).getAsJsonObject();
                }

                if (settings.has("lookAndFeelProfiles")) {
                    JsonObject profiles = settings.getAsJsonObject("lookAndFeelProfiles");
                    if (profiles.has(profileName)) {
                        profiles.remove(profileName);
                        if (settings.has("lastSelectedLafProfile")
                                && settings.get("lastSelectedLafProfile").getAsString().equals(profileName)) {
                            settings.addProperty("lastSelectedLafProfile", DEFAULT_PROFILE_NAME);
                        }
                        try (BufferedWriter writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                            Gson gson = new GsonBuilder().setPrettyPrinting().create();
                            writer.write(gson.toJson(settings));
                        }
                        String selectedBeforeDelete = (String) profileComboBox.getSelectedItem();
                        isProgrammaticChange = true;
                        try {
                            profileComboBox.removeItem(profileName);
                            if (profileName.equals(selectedBeforeDelete)) {
                                profileComboBox.setSelectedItem(DEFAULT_PROFILE_NAME);
                            }
                        } finally {
                            isProgrammaticChange = false;
                        }
                        if (profileName.equals(selectedBeforeDelete)) {
                            performLoadProfile(DEFAULT_PROFILE_NAME);
                        }
                        updateProfileButtonStates();
                        JOptionPane.showMessageDialog(this, "Profile '" + profileName + "' deleted successfully.",
                                "Success", JOptionPane.INFORMATION_MESSAGE);
                    }
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error deleting profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            LOGGER.error("Failed to delete appearance profile '{}'", profileName, e);
        }
    }

    private Path getGuiSettingsPath() {
        String settingsDir = Props.SETTINGS_DIR.getDefaultValue();
        return PathUtils.resolvePath(settingsDir).resolve("gui-settings.json");
    }

    // ====================================================================
    // GPU Acceleration setting persistence
    // ====================================================================

    /**
     * Loads the GPU acceleration setting from gui-settings.json.
     * Reads from nested "appearance.enableGPU" or root-level "enableGPU".
     */
    private boolean loadGpuAccelerationSetting() {
        try {
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    // Try nested "appearance" object first
                    if (settings.has("appearance") && settings.get("appearance").isJsonObject()) {
                        JsonObject appearanceObj = settings.getAsJsonObject("appearance");
                        if (appearanceObj.has("enableGPU")) {
                            return appearanceObj.get("enableGPU").getAsBoolean();
                        }
                    }
                    // Fallback to root level for backward compatibility
                    if (settings.has("enableGPU")) {
                        return settings.get("enableGPU").getAsBoolean();
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load GPU acceleration setting", e);
        }
        return false; // Default: disabled
    }

    /**
     * Saves the GPU acceleration setting to gui-settings.json.
     * Stores in nested "appearance.enableGPU" for organization.
     */
    private void saveGpuAccelerationSetting(boolean enabled) {
        try {
            Path settingsPath = getGuiSettingsPath();
            JsonObject settings;
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    settings = JsonParser.parseReader(reader).getAsJsonObject();
                }
            } else {
                settings = new JsonObject();
            }

            // Ensure nested "appearance" object exists
            if (!settings.has("appearance") || !settings.get("appearance").isJsonObject()) {
                settings.add("appearance", new JsonObject());
            }
            settings.getAsJsonObject("appearance").addProperty("enableGPU", enabled);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (BufferedWriter writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                writer.write(gson.toJson(settings));
            }

            LOGGER.info("GPU acceleration setting saved: {}", enabled);
        } catch (Exception e) {
            LOGGER.error("Failed to save GPU acceleration setting", e);
            JOptionPane.showMessageDialog(this,
                    "Error saving GPU acceleration setting: " + e.getMessage(),
                    "Save Error", JOptionPane.ERROR_MESSAGE);
            // Restore checkbox state on failure
            gpuAccelerationCheckBox.setSelected(!enabled);
        }
    }

    private boolean fontsMatch(Font a, Font b) {
        if (a == b)
            return true;
        if (a == null || b == null) {
            // If one is null, consider them matching if the other is the system default
            // font
            Font nonNull = (a != null) ? a : b;
            Font systemDefault = UIManager.getFont("Label.font");
            if (systemDefault == null)
                return false;
            return nonNull.getFamily().equals(systemDefault.getFamily()) &&
                    nonNull.getSize() == systemDefault.getSize() &&
                    nonNull.getStyle() == systemDefault.getStyle();
        }
        return a.getFamily().equals(b.getFamily()) && a.getSize() == b.getSize() && a.getStyle() == b.getStyle();
    }
}