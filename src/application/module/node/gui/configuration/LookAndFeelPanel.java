package application.module.node.gui.configuration;

import application.module.node.props.Props;
import application.module.node.util.PathUtils;
import application.module.node.gui.util.GuiUtils;
import application.module.node.gui.util.HelpButton;
import application.module.node.gui.ColorPaletteManager;
import application.module.node.gui.ColorSettingsPanel;
import application.module.node.gui.GuiColors;
import application.module.node.gui.GuiConstants;
import application.module.node.gui.SignumGUI;
import application.module.node.gui.laf.FlatLafPanel;
import application.module.node.gui.laf.LookAndFeelsComboBox;
import application.module.node.gui.laf.FlatLafPrefs;
import application.module.node.gui.laf.intellijthemes.IJThemesPanel;
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
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LookAndFeelPanel extends JPanel {

    private static LookAndFeelPanel instance;
    private final Runnable backAction;
    private JComboBox<String> profileComboBox;
    private JButton newProfileBtn;
    private JButton renameProfileBtn;
    private JButton deleteProfileBtn;
    private ColorSettingsPanel colorSettingsPanel;
    private JButton saveProfileBtn;
    private JButton resetToDefaultsBtn;
    private JButton reloadProfileBtn;
    private JButton refreshProfilesBtn;
    private JTabbedPane tabbedPane;
    private final Consumer<String> onLinkAction;
    private final Supplier<String> activeNodeProfileSupplier;
    private final Supplier<String> linkedProfileSupplier;

    private static final String DEFAULT_PROFILE_NAME = "gui";

    private LookAndFeelProfile savedProfile;
    private boolean isProgrammaticChange = false;
    private boolean lastUnsavedStatus = false;

    private static final Logger LOGGER = LoggerFactory.getLogger(LookAndFeelPanel.class);

    public static LookAndFeelPanel getInstance() {
        return instance;
    }

    public ColorSettingsPanel getColorSettingsPanel() {
        return colorSettingsPanel;
    }

    public LookAndFeelPanel(Runnable restartAction, Runnable backAction, Consumer<String> onLinkAction,
            Supplier<String> activeNodeProfileSupplier, Supplier<String> linkedProfileSupplier) {
        super(new BorderLayout());
        this.backAction = backAction;
        instance = this;
        this.onLinkAction = onLinkAction;
        this.activeNodeProfileSupplier = activeNodeProfileSupplier;
        this.linkedProfileSupplier = linkedProfileSupplier;

        // Determine initial state from file BEFORE the UI is created
        String lastProfile = DEFAULT_PROFILE_NAME;
        try {
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("lastSelectedLafProfile")) {
                        lastProfile = settings.get("lastSelectedLafProfile").getAsString();
                    }
                }
            }
        } catch (Exception e) {
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
        JPanel profilePanel = new JPanel(new MigLayout("insets 0, gap 5"));
        profilePanel.setBorder(new EmptyBorder(5, 10, 5, 5));
        profilePanel.add(new JLabel("Look and Feel Profile:"));

        profileComboBox = new JComboBox<>();
        profileComboBox.setEditable(false);
        profileComboBox.setPrototypeDisplayValue("XXXXXXXXXXXXXXXXXXXX");
        ConfigurationUtils.fixComponentSize(profileComboBox);
        profilePanel.add(profileComboBox);

        newProfileBtn = new JButton("New Default Profile");
        newProfileBtn.setToolTipText("Create a new profile initialized with application defaults");
        newProfileBtn.addActionListener(e -> createNewProfile());
        profilePanel.add(newProfileBtn);

        saveProfileBtn = new JButton("Save Profile As");
        saveProfileBtn.setToolTipText("Save Look and Feel Profile As");
        saveProfileBtn.addActionListener(e -> saveProfile());
        profilePanel.add(saveProfileBtn);

        renameProfileBtn = new JButton("Rename Profile");
        renameProfileBtn.setToolTipText("Rename selected profile");
        renameProfileBtn.addActionListener(e -> renameProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(renameProfileBtn);

        deleteProfileBtn = new JButton("Delete Profile");
        deleteProfileBtn.setToolTipText("Delete selected profile");
        deleteProfileBtn.addActionListener(e -> deleteProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(deleteProfileBtn);

        resetToDefaultsBtn = new JButton("Reset to Defaults");
        resetToDefaultsBtn.setToolTipText("Reset current profile settings to application defaults (without saving)");
        resetToDefaultsBtn.addActionListener(e -> resetToDefault());
        profilePanel.add(resetToDefaultsBtn);

        reloadProfileBtn = new JButton("Reload Profile");
        reloadProfileBtn.setToolTipText("Reload settings from the current profile file on disk");
        reloadProfileBtn.addActionListener(e -> reloadProfile());
        profilePanel.add(reloadProfileBtn);

        refreshProfilesBtn = new JButton("Refresh Profiles");
        refreshProfilesBtn.setToolTipText("Refresh the list of available profiles from the disk");
        refreshProfilesBtn.addActionListener(e -> refreshProfileList());
        profilePanel.add(refreshProfilesBtn);

        // Apply icons and sizes for the first time
        updateProfileButtonsUI();

        profileComboBox.addActionListener(e -> {
            if (isProgrammaticChange)
                return;
            String selected = (String) profileComboBox.getSelectedItem();
            if (selected != null) {
                loadProfile(selected);
            }
            updateProfileButtonStates();
        });

        JButton helpBtn = new HelpButton();
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info about Look and Feel Profiles");
        helpBtn.addActionListener(e -> showProfileHelp()); // Add help button
        profilePanel.add(helpBtn);

        JScrollPane profileScrollPane = new JScrollPane(profilePanel);
        profileScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        profileScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        profileScrollPane.setBorder(BorderFactory.createEmptyBorder());
        profileScrollPane.setOpaque(false);
        profileScrollPane.getViewport().setOpaque(false);

        GuiUtils.addHorizontalScrollPadding(profileScrollPane, profilePanel, new Insets(5, 10, 5, 5));

        JPanel northWrapper = new JPanel(new BorderLayout());
        northWrapper.add(profileScrollPane, BorderLayout.CENTER);
        northWrapper.add(new JSeparator(SwingConstants.HORIZONTAL), BorderLayout.SOUTH);

        add(northWrapper, BorderLayout.NORTH);

        tabbedPane = new JTabbedPane();

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

        loadProfiles(profileComboBox);
    }

    public void updateLinkCheckbox() {
        if (activeNodeProfileSupplier == null || linkedProfileSupplier == null)
            return;
    }

    private void updateProfileButtonStates() {
        String selected = (String) profileComboBox.getSelectedItem();
        boolean isReadOnly = DEFAULT_PROFILE_NAME.equals(selected);
        renameProfileBtn.setEnabled(!isReadOnly);
        deleteProfileBtn.setEnabled(!isReadOnly);
    }

    private boolean hasUnsavedChanges() {
        if (savedProfile == null)
            return false;

        LookAndFeelProfile currentUI = new LookAndFeelProfile(savedProfile.getName());
        currentUI.setThemeClassName(UIManager.getLookAndFeel().getClass().getName());
        currentUI.setGlobalFont(SignumGUI.getActiveCustomFont());
        currentUI.setConsoleFont(SignumGUI.getActiveConsoleFont());
        currentUI.setColorOverrides(
                colorSettingsPanel != null ? colorSettingsPanel.getCurrentOverrides() : new HashMap<>());

        return !currentUI.equals(savedProfile);
    }

    private void updateDirtyStatus() {
        if (isProgrammaticChange || savedProfile == null)
            return;
        boolean dirty = hasUnsavedChanges();
        saveProfileBtn.setText(dirty ? "Save Profile As *" : "Save Profile As");

        ConfigurationUtils.fixComponentSize(saveProfileBtn);
        if (saveProfileBtn.getParent() != null) {
            saveProfileBtn.getParent().revalidate();
        }

        // Mark tabs with an asterisk if there are unsaved changes
        boolean colorsDirty = colorSettingsPanel != null
                && !colorSettingsPanel.getCurrentOverrides().equals(savedProfile.getColorOverrides());
        boolean themeDirty = !UIManager.getLookAndFeel().getClass().getName().equals(savedProfile.getThemeClassName())
                ||
                !fontsMatch(SignumGUI.getActiveCustomFont(), savedProfile.getGlobalFont()) ||
                !fontsMatch(SignumGUI.getActiveConsoleFont(), savedProfile.getConsoleFont());

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

    private void reloadProfile() {
        if (savedProfile != null) {
            if (hasUnsavedChanges()) {
                String message = "You have unsaved changes. Are you sure you want to reload from disk and discard these changes?";
                Object[] options = { "Discard and Reload", "Cancel" };
                int result = JOptionPane.showOptionDialog(this, message, "Confirm Reload",
                        JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                        null, options, options[1]);
                if (result != JOptionPane.YES_OPTION) {
                    return;
                }
            }
            // Reload profile from saved state
            performLoadProfile(savedProfile.getName());
        }
    }

    private void createNewProfile() {
        checkUnsavedChangesAndProceed(false, () -> { // allowKeepUnsaved = false
            String name = (String) JOptionPane.showInputDialog(this, "Enter new profile name:", "New Profile",
                    JOptionPane.PLAIN_MESSAGE, null, null, "");
            if (name == null || name.trim().isEmpty())
                return;

            try {
                Path settingsPath = getGuiSettingsPath();
                if (Files.exists(settingsPath)) {
                    JsonObject settings = JsonParser
                            .parseReader(Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    if (settings.has("lookAndFeelProfiles")
                            && settings.getAsJsonObject("lookAndFeelProfiles").has(name)) {
                        JOptionPane.showMessageDialog(this, "Profile '" + name + "' already exists.", "Error",
                                JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                }
            } catch (Exception e) {
            }

            // Create new profile with default values
            resetToDefault();
            saveProfileInternal(name, false);
            updateLoadedStateFromProfile(name);
        }, null);
    }

    private void refreshProfileList() {
        boolean wasProgrammatic = isProgrammaticChange;
        try {
            isProgrammaticChange = true;
            String currentSelection = (String) profileComboBox.getSelectedItem();
            profileComboBox.removeAllItems();
            loadProfiles(profileComboBox);
            if (currentSelection != null) {
                profileComboBox.setSelectedItem(currentSelection);
            }
        } finally {
            isProgrammaticChange = wasProgrammatic;
        }
        updateProfileButtonStates();
    }

    private void updateLoadedStateFromProfile(String profileName) {
        this.savedProfile = ConfigurationUtils.loadLookAndFeelProfile(getGuiSettingsPath(), profileName);
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
                deleteProfileBtn, reloadProfileBtn, refreshProfilesBtn, resetToDefaultsBtn);
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
        // No error label for reserved names, as DEFAULT_PROFILE_NAME is now savable

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
            Font font = SignumGUI.getActiveCustomFont();
            JsonObject fontSettings = new JsonObject();
            fontSettings.addProperty("family", font.getFamily());
            fontSettings.addProperty("style", font.getStyle());
            fontSettings.addProperty("size", font.getSize());
            lafSettings.add("font", fontSettings);

            Font consoleFont = SignumGUI.getActiveConsoleFont();
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
            e.printStackTrace();
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
                        comboBox.setSelectedItem(settings.get("lastSelectedLafProfile").getAsString());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void renameProfile(String oldProfileName) {
        if (oldProfileName == null || oldProfileName.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No profile selected to rename.", "Rename Profile",
                    JOptionPane.WARNING_MESSAGE);
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

        if (newProfileName == null || newProfileName.trim().isEmpty() || newProfileName.equals(oldProfileName)) {
            return; // User cancelled or entered the same name
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

                updateLoadedStateFromProfile(newProfileName);

                try (BufferedWriter writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                    Gson gson = new GsonBuilder().setPrettyPrinting().create();
                    writer.write(gson.toJson(settings));
                }

                // Update combobox
                profileComboBox.removeItem(oldProfileName);
                profileComboBox.addItem(newProfileName);
                profileComboBox.setSelectedItem(newProfileName);
                updateLinkCheckbox();

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
            e.printStackTrace();
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
        Font currentGlobal = SignumGUI.getActiveCustomFont();
        if (!fontsMatch(currentGlobal, savedProfile.getGlobalFont())) {
            changesFound = true;
            report.append("<li>Global font: '").append(formatFont(savedProfile.getGlobalFont())).append("' &rarr; '")
                    .append(formatFont(currentGlobal)).append("'</li>");
        }

        // Check console font
        Font currentConsole = SignumGUI.getActiveConsoleFont();
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
        LOGGER.info("Loading Look and Feel profile: '{}'", profileName);
        isProgrammaticChange = true;
        try {
            // Check if profile is defined in settings file even if it is the "Default" one
            boolean profileExistsInFile = false;
            Path settingsPathForCheck = getGuiSettingsPath();
            if (Files.exists(settingsPathForCheck)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPathForCheck, StandardCharsets.UTF_8)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    profileExistsInFile = settings.has("lookAndFeelProfiles") &&
                            settings.getAsJsonObject("lookAndFeelProfiles").has(profileName);
                } catch (Exception e) {
                }
            }

            if (DEFAULT_PROFILE_NAME.equals(profileName) && !profileExistsInFile) {
                resetToDefaultInternal();
                try {
                    Path settingsPath = getGuiSettingsPath();
                    JsonObject settings;
                    if (Files.exists(settingsPath)) {
                        try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                            settings = JsonParser.parseReader(reader).getAsJsonObject();
                        } catch (Exception e) {
                            settings = new JsonObject();
                        }
                    } else {
                        settings = new JsonObject();
                    }
                    settings.addProperty("lastSelectedLafProfile", DEFAULT_PROFILE_NAME);
                    try (BufferedWriter writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                        new GsonBuilder().setPrettyPrinting().create().toJson(settings, writer);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
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
                                SignumGUI.updateCommonFontKeys(font);
                            } else {
                                SignumGUI.updateCommonFontKeys(null);
                            }

                            if (lafSettings.has("consoleFont")) {
                                JsonObject fontSettings = lafSettings.getAsJsonObject("consoleFont");
                                String family = fontSettings.get("family").getAsString();
                                int style = fontSettings.get("style").getAsInt();
                                int size = fontSettings.get("size").getAsInt();
                                Font font = new Font(family, style, size);
                                SignumGUI.updateCommonConsoleFontKeys(font);
                            } else {
                                SignumGUI.updateCommonConsoleFontKeys(null);
                            }

                            SignumGUI.updateAllUIs();
                            colorSettingsPanel.setProfileOverrides(colorOverrides);
                            updateLoadedStateFromProfile(profileName);
                            updateLinkCheckbox();

                            FlatAnimatedLafChange.hideSnapshotWithAnimation();

                            // Update last selected
                            settings.addProperty("lastSelectedLafProfile", profileName);
                            try (BufferedWriter writer = Files.newBufferedWriter(settingsPath,
                                    StandardCharsets.UTF_8)) {
                                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                                writer.write(gson.toJson(settings));
                            }

                            profileComboBox.setSelectedItem(profileName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error loading profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        } finally {
            isProgrammaticChange = false;
            updateDirtyStatus();
        }
    }

    private void resetToDefault() {
        isProgrammaticChange = true;
        try {
            resetToDefaultInternal();
        } finally {
            isProgrammaticChange = false;
            updateDirtyStatus();
        }
    }

    private void resetToDefaultInternal() {
        try {
            FlatAnimatedLafChange.showSnapshot();
            // Reset to a known default theme, e.g., FlatDarkLaf
            UIManager.setLookAndFeel(FlatDarkLaf.class.getName());
            // Reset font
            SignumGUI.updateCommonFontKeys(null);
            SignumGUI.updateCommonConsoleFontKeys(null);
            // Reset colors
            ColorPaletteManager.updatePalette(null);
            colorSettingsPanel.setProfileOverrides(null);
            SignumGUI.updateAllUIs();
            FlatAnimatedLafChange.hideSnapshotWithAnimation();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error resetting to default: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
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
        String message = "<html><body style='width: 350px'>" +
                "<h2>Look and Feel Profiles</h2>" +
                "<p>Look and Feel Profiles allow you to save and load different visual themes and font settings.</p>" +
                "<ul>"
                +
                "<li><b>New Default Profile</b>: Creates a new configuration profile initialized with application defaults.</li>"
                +
                "<li><b>Save Profile As</b>: Saves the current theme and font settings into a named profile. If you use an existing profile name, it will be overwritten after confirmation.</li>"
                +
                "<li><b>Rename Profile</b>: Renames the currently selected profile.</li>"
                +
                "<li><b>Delete Profile</b>: Deletes the currently selected profile after confirmation.</li>"
                +
                "<li><b>Reset to Defaults</b>: Resets all current settings to their application default values without saving.</li>"
                +
                "<li><b>Reload Profile</b>: Reloads the visual settings from the stored profile on disk, discarding manual changes.</li>"
                +
                "<li><b>Refresh Profiles</b>: Synchronizes the profile list with the settings file on disk.</li>" +
                "</ul>" +
                "<p>Profiles are stored in the <code>gui-settings.json</code> file in your settings directory.</p>" +
                "</body></html>";

        JOptionPane.showMessageDialog(this, message, "About Look and Feel Profiles", JOptionPane.INFORMATION_MESSAGE);
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
                            settings.remove("lastSelectedLafProfile");
                        }
                        try (BufferedWriter writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                            Gson gson = new GsonBuilder().setPrettyPrinting().create();
                            writer.write(gson.toJson(settings));
                        }
                        profileComboBox.removeItem(profileName);
                        JOptionPane.showMessageDialog(this, "Profile '" + profileName + "' deleted successfully.",
                                "Success", JOptionPane.INFORMATION_MESSAGE);
                    }
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error deleting profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    private Path getGuiSettingsPath() {
        String settingsDir = Props.SETTINGS_DIR.getDefaultValue();
        return PathUtils.resolvePath(settingsDir).resolve("gui-settings.json");
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