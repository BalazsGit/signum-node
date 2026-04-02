package brs.gui;

import brs.props.Props;
import brs.util.PathUtils;
import brs.gui.util.HelpButton;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class LoggerConfigurationPanel extends JPanel {

    private static final String[] LOG_LEVELS = { "SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST",
            "ALL", "OFF" };
    private final Runnable restartAction;
    private final Runnable backAction;
    private final Runnable switchAction;
    private final Properties props;
    private final Properties appliedProps;
    private final Map<String, String> helpTexts = new HashMap<>();
    private final Map<String, JComponent> propertyComponents = new HashMap<>();
    private final Map<String, String> defaultValues = new HashMap<>();
    private final String confFolder;
    private final Path propertiesFile;
    private JComboBox<String> profileComboBox;
    private final java.util.List<PropertyRow> allPropertyRows = new ArrayList<>();
    private JPanel searchResultsPanel;
    private CardLayout contentCardLayout;
    private JPanel contentContainer;
    private JComponent verticalFiller;
    private JLabel titleLabel;
    private JButton resetBtn;
    private JButton resetAppliedBtn;
    private JButton saveBtn;

    public LoggerConfigurationPanel(Runnable restartAction, String confFolder, Runnable backAction,
            Runnable switchAction) {
        super(new BorderLayout());
        this.restartAction = restartAction;
        this.confFolder = confFolder;
        this.backAction = backAction;
        this.switchAction = switchAction;
        this.propertiesFile = PathUtils.resolvePath(confFolder).resolve("logging.properties");

        ensurePropertiesFileExists();

        this.props = new Properties();
        try (FileInputStream in = new FileInputStream(propertiesFile.toFile())) {
            this.props.load(in);
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.appliedProps = new Properties();
        this.appliedProps.putAll(this.props);

        initHelpTexts();
        initUI();
    }

    private void initUI() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(new EmptyBorder(5, 5, 5, 5));

        JPanel leftHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        leftHeader.setOpaque(false);
        JButton backButton = new JButton("Back to Console",
                IconFontSwing.buildIcon(FontAwesome.ARROW_LEFT, GuiConstants.getHelpIconSize(),
                        UIManager.getColor("Label.foreground")));
        backButton.addActionListener(e -> {
            if (backAction != null)
                backAction.run();
        });
        leftHeader.add(backButton);

        JPanel rightHeader = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        rightHeader.setOpaque(false);
        JButton switchBtn = new JButton("Switch to Node Configuration",
                IconFontSwing.buildIcon(FontAwesome.EXCHANGE, GuiConstants.getHelpIconSize(),
                        UIManager.getColor("Label.foreground")));
        switchBtn.addActionListener(e -> {
            if (switchAction != null)
                switchAction.run();
        });
        rightHeader.add(switchBtn);

        titleLabel = new JLabel("Logger Configuration", SwingConstants.CENTER);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));

        header.add(leftHeader, BorderLayout.WEST);
        header.add(titleLabel, BorderLayout.CENTER);
        header.add(rightHeader, BorderLayout.EAST);

        JPanel topContainer = new JPanel(new BorderLayout());
        topContainer.add(header, BorderLayout.CENTER);
        topContainer.add(new JSeparator(SwingConstants.HORIZONTAL), BorderLayout.SOUTH);

        add(topContainer, BorderLayout.NORTH);

        JPanel bodyPanel = new JPanel(new BorderLayout());

        // --- Profile Panel ---
        JPanel profilePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        profilePanel.setBorder(new EmptyBorder(5, 10, 5, 5));
        profilePanel.add(new JLabel("Logger Configuration Profile:"));

        profileComboBox = new JComboBox<>();
        profileComboBox.setEditable(false);
        profileComboBox.setPrototypeDisplayValue("XXXXXXXXXXXXXXXXXXXX");
        profilePanel.add(profileComboBox);

        JButton loadProfileBtn = new JButton("Load Profile");
        loadProfileBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.FOLDER_OPEN, GuiConstants.getHelpIconSize(),
                GuiColors.getButtonIcon()));
        loadProfileBtn.setToolTipText("Load selected profile");
        loadProfileBtn.addActionListener(e -> loadProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(loadProfileBtn);

        JButton saveProfileBtn = new JButton("Save Profile");
        saveProfileBtn.setIcon(
                IconFontSwing.buildIcon(FontAwesome.FLOPPY_O, GuiConstants.getHelpIconSize(),
                        GuiColors.getButtonIcon()));
        saveProfileBtn.setToolTipText("Save Logger Configuration Profile");
        saveProfileBtn.addActionListener(e -> saveProfile());
        profilePanel.add(saveProfileBtn);

        JButton renameProfileBtn = new JButton("Rename Profile");
        renameProfileBtn.setIcon(
                IconFontSwing.buildIcon(FontAwesome.PENCIL_SQUARE_O, GuiConstants.getHelpIconSize(),
                        GuiColors.getButtonIcon()));
        renameProfileBtn.setToolTipText("Rename selected profile");
        renameProfileBtn.addActionListener(e -> renameProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(renameProfileBtn);

        JButton deleteProfileBtn = new JButton("Delete Profile");
        deleteProfileBtn.setIcon(
                IconFontSwing.buildIcon(FontAwesome.TRASH_O, GuiConstants.getHelpIconSize(),
                        GuiColors.getButtonIcon()));
        deleteProfileBtn.setToolTipText("Delete selected profile");
        deleteProfileBtn.addActionListener(e -> deleteProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(deleteProfileBtn);

        profileComboBox.addActionListener(e -> {
            String selected = (String) profileComboBox.getSelectedItem();
            boolean isDefault = "Default".equals(selected);
            renameProfileBtn.setEnabled(!isDefault);
            deleteProfileBtn.setEnabled(!isDefault);
        });

        JButton helpBtn = new HelpButton();
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info about Configuration Profiles");
        helpBtn.addActionListener(e -> showProfileHelp());
        profilePanel.add(helpBtn);

        loadProfiles(profileComboBox);

        // --- Search Panel ---
        JPanel searchPanel = new JPanel(new MigLayout("insets 5 10 5 5, fillx", "[][grow]", "[]"));
        searchPanel.add(new JLabel("Search Configuration:"));
        JTextField searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "Type to filter properties...");
        styleTextField(searchField);
        searchPanel.add(searchField, "growx");

        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                filterProperties(searchField.getText());
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                filterProperties(searchField.getText());
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                filterProperties(searchField.getText());
            }
        });

        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(profilePanel, BorderLayout.NORTH);
        northPanel.add(searchPanel, BorderLayout.SOUTH);
        bodyPanel.add(northPanel, BorderLayout.NORTH);

        JPanel contentPanel = new JPanel(new MigLayout("fillx, insets 10, gap 5", "[][grow]", ""));

        // Clear list before rebuilding UI (in case of re-init)
        allPropertyRows.clear();

        addSectionHeader(contentPanel, "Global Settings", true);

        String defaultGlobalLevel = "INFO";
        JComboBox<String> globalLevelCombo = new JComboBox<>(LOG_LEVELS);
        globalLevelCombo.setSelectedItem(props.getProperty(".level", defaultGlobalLevel));
        addProperty(contentPanel, "Global Level", ".level", globalLevelCombo, defaultGlobalLevel);

        addSectionHeader(contentPanel, "Console Handler", false);

        String defaultConsoleLevel = "INFO";
        JComboBox<String> consoleLevelCombo = new JComboBox<>(LOG_LEVELS);
        consoleLevelCombo
                .setSelectedItem(props.getProperty("java.util.logging.ConsoleHandler.level", defaultConsoleLevel));
        addProperty(contentPanel, "Console Level", "java.util.logging.ConsoleHandler.level", consoleLevelCombo,
                defaultConsoleLevel);

        addSectionHeader(contentPanel, "File Handler", false);

        String defaultFileLevel = "INFO";
        JComboBox<String> fileLevelCombo = new JComboBox<>(LOG_LEVELS);
        fileLevelCombo.setSelectedItem(props.getProperty("java.util.logging.FileHandler.level", defaultFileLevel));
        addProperty(contentPanel, "File Level", "java.util.logging.FileHandler.level", fileLevelCombo,
                defaultFileLevel);

        // File Pattern
        String defaultPattern = "logs/signum%u.log";
        JTextField filePatternField = createStyledTextField(
                props.getProperty("java.util.logging.FileHandler.pattern", defaultPattern));
        addProperty(contentPanel, "Log File Pattern", "java.util.logging.FileHandler.pattern", filePatternField,
                defaultPattern);

        // File Limit
        String defaultLimit = "0";
        JTextField fileLimitField = createStyledTextField(
                props.getProperty("java.util.logging.FileHandler.limit", defaultLimit));
        addProperty(contentPanel, "File Size Limit (bytes)", "java.util.logging.FileHandler.limit", fileLimitField,
                defaultLimit);

        // File Count
        String defaultCount = "1";
        JTextField fileCountField = createStyledTextField(
                props.getProperty("java.util.logging.FileHandler.count", defaultCount));
        addProperty(contentPanel, "File Count", "java.util.logging.FileHandler.count", fileCountField, defaultCount);

        // Push everything to top
        verticalFiller = new JLabel();
        contentPanel.add(verticalFiller, "pushy");

        // --- Content Container (CardLayout for Settings vs Search Results) ---
        contentCardLayout = new CardLayout();
        contentContainer = new JPanel(contentCardLayout);

        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        contentContainer.add(scrollPane, "SETTINGS");

        searchResultsPanel = new JPanel(new MigLayout("fillx, insets 10, gap 5", "[][grow]", ""));
        JScrollPane searchScrollPane = new JScrollPane(searchResultsPanel);
        searchScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        searchScrollPane.setBorder(BorderFactory.createEmptyBorder());
        contentContainer.add(searchScrollPane, "SEARCH");

        bodyPanel.add(contentContainer, BorderLayout.CENTER);
        add(bodyPanel, BorderLayout.CENTER);

        // --- Bottom Panel with Buttons and File Path ---
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 0));
        bottomPanel.setBorder(new EmptyBorder(5, 10, 5, 5));

        // Legend
        bottomPanel.add(createLegendPanel(), BorderLayout.NORTH);

        // File path field
        JLabel pathLabel = new JLabel("Configuration File: " + propertiesFile.toAbsolutePath().toString());
        pathLabel.setForeground(GuiColors.getFaintText());
        bottomPanel.add(pathLabel, BorderLayout.CENTER);

        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));

        resetBtn = new JButton("Reset to Saved Configuration");
        resetBtn.setFont(resetBtn.getFont().deriveFont(Font.BOLD));
        resetBtn.setIcon(
                IconFontSwing.buildIcon(FontAwesome.UNDO, GuiConstants.getHelpIconSize(), GuiColors.getButtonIcon()));
        resetBtn.addActionListener(e -> resetToCurrent());

        resetAppliedBtn = new JButton("Reset to Applied Configuration");
        resetAppliedBtn.setFont(resetAppliedBtn.getFont().deriveFont(Font.BOLD));
        resetAppliedBtn.setIcon(
                IconFontSwing.buildIcon(FontAwesome.HISTORY, GuiConstants.getHelpIconSize(),
                        GuiColors.getButtonIcon()));
        resetAppliedBtn.addActionListener(e -> resetToApplied());

        JButton resetToAppDefaultsBtn = new JButton("Reset to Application Defaults");
        resetToAppDefaultsBtn.setToolTipText(
                "Resets all settings on this form to their initial application defaults, ignoring saved files or profiles.");
        resetToAppDefaultsBtn.setIcon(
                IconFontSwing.buildIcon(FontAwesome.REFRESH, GuiConstants.getHelpIconSize(),
                        GuiColors.getButtonIcon()));
        resetToAppDefaultsBtn.addActionListener(e -> resetToHardcodedDefaults());

        JButton deleteConfigFileBtn = new JButton("Delete Config File");
        deleteConfigFileBtn.setToolTipText(
                "Deletes the content of the logging.properties file to reset to application defaults on next restart.");
        deleteConfigFileBtn.setIcon(
                IconFontSwing.buildIcon(FontAwesome.TRASH_O, GuiConstants.getHelpIconSize(),
                        GuiColors.getButtonIcon()));
        deleteConfigFileBtn.addActionListener(e -> {
            int choice = JOptionPane.showConfirmDialog(this,
                    "This will delete the content of the configuration file (" + propertiesFile.getFileName()
                            + ").\nThis action cannot be undone. Are you sure you want to proceed?",
                    "Confirm Deletion",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                try {
                    Files.write(propertiesFile, new byte[0]); // Deletes content
                    resetToCurrent(); // Reloads from the now-empty file and updates UI

                    Object[] options = { "Restart to Apply Changes", "Cancel" };
                    int result = JOptionPane.showOptionDialog(this,
                            "The content of " + propertiesFile.getFileName() + " has been deleted.",
                            "Success",
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.INFORMATION_MESSAGE,
                            null, options, options[0]);

                    if (result == 0 && restartAction != null) { // "Restart to Apply Changes"
                        restartAction.run();
                    }
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, "Error deleting file content: " + ex.getMessage(), "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        saveBtn = new JButton("Save Configuration");
        saveBtn.setFont(saveBtn.getFont().deriveFont(Font.BOLD));
        saveBtn.setIcon(
                IconFontSwing.buildIcon(FontAwesome.FLOPPY_O, GuiConstants.getHelpIconSize(),
                        GuiColors.getButtonIcon()));
        saveBtn.addActionListener(e -> {
            performSave();
            Object[] options = { "OK", "Restart and Apply Changes" };
            int result = JOptionPane.showOptionDialog(this,
                    "Configuration saved successfully!",
                    "Success",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.INFORMATION_MESSAGE,
                    null, options, options[0]);

            if (result == 1) { // "Restart and Apply Changes"
                if (restartAction != null) {
                    restartAction.run();
                }
            }
        });

        buttonPanel.add(resetAppliedBtn);
        buttonPanel.add(resetBtn);
        buttonPanel.add(resetToAppDefaultsBtn);
        buttonPanel.add(saveBtn);
        buttonPanel.add(deleteConfigFileBtn);

        JButton buttonBarHelpBtn = new HelpButton();
        buttonBarHelpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        String buttonBarHelpText = "<html><body style='width: 350px'>"
                + "<h2>Button Functions</h2>"
                + "<ul>"
                + "<li><b>Reset to Applied Configuration:</b> Reverts all settings on this form to the values that were active when the application was last started.</li>"
                + "<li><b>Reset to Saved Configuration:</b> Reverts all settings on this form to the values currently saved in the <code>logging.properties</code> file.</li>"
                + "<li><b>Reset to Application Defaults:</b> Resets all settings on this form to their initial application defaults, ignoring any saved files or profiles.</li>"
                + "<li><b>Save Configuration:</b> Saves the current settings to the <code>logging.properties</code> file. A restart is required for changes to take effect.</li>"
                + "<li><b>Delete Config File:</b> Deletes the content of the <code>logging.properties</code> file. This will cause the application to use default settings on the next restart.</li>"
                + "</ul>"
                + "</body></html>";
        buttonBarHelpBtn.setToolTipText("Click for more info about the buttons");
        buttonBarHelpBtn
                .addActionListener(e -> JOptionPane.showMessageDialog(this, buttonBarHelpText, "Button Functions",
                        JOptionPane.INFORMATION_MESSAGE));
        buttonPanel.add(buttonBarHelpBtn);

        bottomPanel.add(buttonPanel, BorderLayout.EAST);

        JPanel bottomContainer = new JPanel(new BorderLayout());
        bottomContainer.add(new JSeparator(SwingConstants.HORIZONTAL), BorderLayout.NORTH);
        bottomContainer.add(bottomPanel, BorderLayout.CENTER);
        add(bottomContainer, BorderLayout.SOUTH);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Re-apply derived fonts using central manager
        if (titleLabel != null) {
            titleLabel.setFont(GuiFontManager.getBoldDefaultFont());
        }
        if (resetBtn != null) {
            resetBtn.setFont(GuiFontManager.getBoldDefaultFont());
        }
        if (resetAppliedBtn != null) {
            resetAppliedBtn.setFont(GuiFontManager.getBoldDefaultFont());
        }
        if (saveBtn != null) {
            saveBtn.setFont(GuiFontManager.getBoldDefaultFont());
        }

        // Re-style input fields
        if (allPropertyRows != null) {
            for (PropertyRow row : allPropertyRows) {
                if (row.propertyKey == null && row.label != null) {
                    row.label.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD));
                }
                if (row.input != null) {
                    styleTextField(row.input);
                }
            }
        }
    }

    private void filterProperties(String text) {
        boolean isSearch = text != null && !text.trim().isEmpty();

        if (isSearch) {
            searchResultsPanel.removeAll();
            String lowerText = text.toLowerCase();

            for (PropertyRow row : allPropertyRows) {
                // Skip section headers (where propertyKey is null) in search results
                if (row.propertyKey != null && (row.propertyKey.toLowerCase().contains(lowerText) ||
                        row.labelText.toLowerCase().contains(lowerText))) {

                    searchResultsPanel.add(row.label, "align label");
                    searchResultsPanel.add(row.input, "split 2, growx, height pref!");
                    searchResultsPanel.add(row.help, "wrap");
                    searchResultsPanel.add(row.separator, "span, growx, wrap, gaptop 2, gapbottom 2");
                }
            }
            contentCardLayout.show(contentContainer, "SEARCH");
        } else {
            // Restore components to their original panels in order
            for (PropertyRow row : allPropertyRows) {
                row.originalParent.add(row.label, row.labelConstraints);
                if (row.input != null) {
                    row.originalParent.add(row.input, row.inputConstraints);
                }
                if (row.help != null) {
                    row.originalParent.add(row.help, row.helpConstraints);
                }
                if (row.separator != null) {
                    row.originalParent.add(row.separator, row.separatorConstraints);
                }
            }
            if (verticalFiller != null && !allPropertyRows.isEmpty()) {
                allPropertyRows.get(0).originalParent.add(verticalFiller, "pushy");
            }
            contentCardLayout.show(contentContainer, "SETTINGS");
        }
        revalidate();
        repaint();
    }

    private JTextField createStyledTextField(String text) {
        JTextField textField = new JTextField(text);
        styleTextField(textField);
        fixComponentSize(textField);
        return textField;
    }

    private void styleTextField(JComponent field) {
        if (field instanceof JTextField) {
            field.setFont(UIManager.getFont("TextField.font"));
            field.setBorder(BorderFactory.createCompoundBorder(
                    UIManager.getBorder("TextField.border"),
                    BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        }
    }

    private void fixComponentSize(JComponent comp) {
        JTextField dummy = new JTextField("Prototype");
        styleTextField(dummy);
        Dimension pref = dummy.getPreferredSize();
        comp.setPreferredSize(new Dimension(comp.getPreferredSize().width, pref.height));
        comp.setMinimumSize(new Dimension(comp.getMinimumSize().width, pref.height));
    }

    private void loadProfiles(JComboBox<String> comboBox) {
        try {
            comboBox.addItem("Default");
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("loggerConfigurationProfiles")) {
                        JsonObject profiles = settings.getAsJsonObject("loggerConfigurationProfiles");
                        for (String profileName : profiles.keySet()) {
                            comboBox.addItem(profileName);
                        }
                    }
                    if (settings.has("lastSelectedLoggerProfile")) {
                        comboBox.setSelectedItem(settings.get("lastSelectedLoggerProfile").getAsString());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveProfile() {
        String currentProfile = (String) profileComboBox.getSelectedItem();
        String suggestedName = ("Default".equals(currentProfile) || currentProfile == null) ? "" : currentProfile;
        String name = (String) JOptionPane.showInputDialog(this, "Enter profile name:", "Save Profile",
                JOptionPane.PLAIN_MESSAGE, null, null, suggestedName);
        if (name == null || name.trim().isEmpty() || "Default".equalsIgnoreCase(name.trim()))
            return;

        try {
            Path settingsPath = getGuiSettingsPath();
            JsonObject settings;
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                    settings = JsonParser.parseReader(reader).getAsJsonObject();
                }
            } else {
                settings = new JsonObject();
            }

            if (!settings.has("loggerConfigurationProfiles")) {
                settings.add("loggerConfigurationProfiles", new JsonObject());
            }
            JsonObject profiles = settings.getAsJsonObject("loggerConfigurationProfiles");

            if (profiles.has(name)) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "Profile '" + name + "' already exists. Do you want to overwrite it?",
                        "Confirm Overwrite",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            JsonObject profileData = new JsonObject();
            Properties props = getPropertiesFromUI();
            for (String key : props.stringPropertyNames()) {
                profileData.addProperty(key, props.getProperty(key));
            }

            profiles.add(name, profileData);
            settings.addProperty("lastSelectedLoggerProfile", name);

            if (Files.notExists(settingsPath.getParent())) {
                Files.createDirectories(settingsPath.getParent());
            }

            try (BufferedWriter writer = Files.newBufferedWriter(settingsPath)) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                writer.write(gson.toJson(settings));
            }

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

            JOptionPane.showMessageDialog(this, "Profile '" + name + "' saved successfully.", "Success",
                    JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error saving profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    private void loadProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()) {
            return;
        }

        if ("Default".equals(profileName)) {
            resetToHardcodedDefaults();
            // Save "Default" as the last selected profile
            try {
                Path settingsPath = getGuiSettingsPath();
                JsonObject settings;
                if (Files.exists(settingsPath)) {
                    try (BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                        settings = JsonParser.parseReader(reader).getAsJsonObject();
                    } catch (Exception e) {
                        settings = new JsonObject();
                    }
                } else {
                    settings = new JsonObject();
                }
                settings.addProperty("lastSelectedLoggerProfile", "Default");
                try (BufferedWriter writer = Files.newBufferedWriter(settingsPath)) {
                    new GsonBuilder().setPrettyPrinting().create().toJson(settings, writer);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return;
        }
        try {
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("loggerConfigurationProfiles")) {
                        JsonObject profiles = settings.getAsJsonObject("loggerConfigurationProfiles");
                        if (profiles.has(profileName)) {
                            JsonObject profileData = profiles.getAsJsonObject(profileName);
                            Properties props = new Properties();
                            for (String key : profileData.keySet()) {
                                props.setProperty(key, profileData.get(key).getAsString());
                            }
                            updateUIFromProperties(props);

                            // Update last selected
                            settings.addProperty("lastSelectedLoggerProfile", profileName);
                            try (BufferedWriter writer = Files.newBufferedWriter(settingsPath)) {
                                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                                writer.write(gson.toJson(settings));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error loading profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
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

        if (newProfileName == null || newProfileName.trim().isEmpty() || newProfileName.equals(oldProfileName)
                || "Default".equalsIgnoreCase(newProfileName.trim())) {
            return; // User cancelled or entered the same name
        }

        try {
            Path settingsPath = getGuiSettingsPath();
            JsonObject settings;
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                    settings = JsonParser.parseReader(reader).getAsJsonObject();
                }
            } else {
                JOptionPane.showMessageDialog(this, "Settings file not found.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (!settings.has("loggerConfigurationProfiles")) {
                JOptionPane.showMessageDialog(this, "No profiles found in settings file.", "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            JsonObject profiles = settings.getAsJsonObject("loggerConfigurationProfiles");

            if (profiles.has(newProfileName)) {
                JOptionPane.showMessageDialog(this, "A profile with the name '" + newProfileName + "' already exists.",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (profiles.has(oldProfileName)) {
                JsonElement profileData = profiles.remove(oldProfileName);
                profiles.add(newProfileName, profileData);

                if (settings.has("lastSelectedLoggerProfile")
                        && settings.get("lastSelectedLoggerProfile").getAsString().equals(oldProfileName)) {
                    settings.addProperty("lastSelectedLoggerProfile", newProfileName);
                }

                try (BufferedWriter writer = Files.newBufferedWriter(settingsPath)) {
                    Gson gson = new GsonBuilder().setPrettyPrinting().create();
                    writer.write(gson.toJson(settings));
                }

                // Update combobox
                profileComboBox.removeItem(oldProfileName);
                profileComboBox.addItem(newProfileName);
                profileComboBox.setSelectedItem(newProfileName);

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

    private void deleteProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()) {
            return;
        }
        if ("Default".equals(profileName)) {
            JOptionPane.showMessageDialog(this, "The 'Default' profile cannot be deleted.", "Action Not Allowed",
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
                try (BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                    settings = JsonParser.parseReader(reader).getAsJsonObject();
                }

                if (settings.has("loggerConfigurationProfiles")) {
                    JsonObject profiles = settings.getAsJsonObject("loggerConfigurationProfiles");
                    if (profiles.has(profileName)) {
                        profiles.remove(profileName);
                        if (settings.has("lastSelectedLoggerProfile")
                                && settings.get("lastSelectedLoggerProfile").getAsString().equals(profileName)) {
                            settings.remove("lastSelectedLoggerProfile");
                        }
                        try (BufferedWriter writer = Files.newBufferedWriter(settingsPath)) {
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

    private void performSave() {
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String val = "";
            if (comp instanceof JComboBox)
                val = (String) ((JComboBox<?>) comp).getSelectedItem();
            else if (comp instanceof JTextComponent)
                val = ((JTextComponent) comp).getText();

            String def = defaultValues.get(key);
            if (val != null && !val.equals(def)) {
                props.setProperty(key, val);
            } else {
                props.remove(key);
            }
        }
        try {
            savePropertiesPreservingFormat(propertiesFile, props, propertyComponents.keySet());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error saving properties: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }

        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            updateColor(entry.getValue(), entry.getKey(), defaultValues.get(entry.getKey()));
        }
    }

    private Properties getPropertiesFromUI() {
        Properties props = new Properties();
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String val = "";
            if (comp instanceof JComboBox) {
                val = (String) ((JComboBox<?>) comp).getSelectedItem();
            } else if (comp instanceof JTextComponent) {
                val = ((JTextComponent) comp).getText();
            }

            String def = defaultValues.get(key);
            if (val != null && !val.equals(def)) {
                props.setProperty(key, val);
            }
        }
        return props;
    }

    private void updateUIFromProperties(Properties loadedProps) {
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String def = defaultValues.get(key);
            String val = loadedProps.getProperty(key, def);

            if (comp instanceof JComboBox) {
                ((JComboBox<?>) comp).setSelectedItem(val);
            } else if (comp instanceof javax.swing.text.JTextComponent) {
                ((javax.swing.text.JTextComponent) comp).setText(val);
            }
            updateColor(comp, key, def);
        }
    }

    private void showProfileHelp() {
        String message = "<html><body style='width: 350px'>" +
                "<h2>Configuration Profiles</h2>" +
                "<p>Configuration Profiles allow you to save and load different sets of logger configurations.</p>" +
                "<ul>" +
                "<li><b>Load Profile</b>: Loads the settings from the selected profile in the dropdown, updating the fields.</li>"
                +
                "<li><b>Save Profile</b>: Saves the current settings from the panel into a named profile. If you use an existing profile name, it will be overwritten after confirmation.</li>"
                +
                "<li><b>Rename Profile</b>: Renames the currently selected profile.</li>" +
                "<li><b>Delete Profile</b>: Deletes the currently selected profile after confirmation.</li>" +
                "</ul>" +
                "<p>Profiles are stored in the <code>gui-settings.json</code> file in your settings directory.</p>" +
                "</body></html>";

        JOptionPane.showMessageDialog(this, message, "About Configuration Profiles", JOptionPane.INFORMATION_MESSAGE);
    }

    private Path getGuiSettingsPath() {
        String settingsDir = Props.SETTINGS_DIR.getDefaultValue();
        // Try to read settings.dir from node.properties in the same conf folder
        Path nodePropsFile = PathUtils.resolvePath(confFolder).resolve("node.properties");
        if (Files.exists(nodePropsFile)) {
            try (FileInputStream in = new FileInputStream(nodePropsFile.toFile())) {
                Properties nodeProps = new Properties();
                nodeProps.load(in);
                settingsDir = nodeProps.getProperty(Props.SETTINGS_DIR.getName(), settingsDir);
            } catch (Exception e) {
                // ignore
            }
        }
        return PathUtils.resolvePath(settingsDir).resolve("gui-settings.json");
    }

    private void savePropertiesPreservingFormat(Path file, Properties props, Set<String> managedKeys)
            throws IOException {
        List<String> lines = Files.exists(file) ? Files.readAllLines(file) : new ArrayList<>();
        List<String> newLines = new ArrayList<>();
        Set<String> processedKeys = new HashSet<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                newLines.add(line);
                continue;
            }

            int sepIdx = -1;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                    sepIdx = i;
                    break;
                }
            }

            if (sepIdx != -1) {
                String key = line.substring(0, sepIdx).trim();
                if (props.containsKey(key)) {
                    String val = props.getProperty(key);
                    newLines.add(key + "=" + escapePropertyValue(val));
                    processedKeys.add(key);
                } else {
                    if (!managedKeys.contains(key)) {
                        newLines.add(line);
                    }
                }
            } else {
                newLines.add(line);
            }
        }

        for (String key : props.stringPropertyNames()) {
            if (!processedKeys.contains(key)) {
                newLines.add(key + "=" + escapePropertyValue(props.getProperty(key)));
            }
        }

        Files.write(file, newLines);
    }

    private String escapePropertyValue(String value) {
        if (value == null)
            return "";
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\f", "\\f");
    }

    private void ensurePropertiesFileExists() {
        if (!Files.exists(propertiesFile)) {
            try {
                Files.createDirectories(propertiesFile.getParent());
                Files.createFile(propertiesFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void addProperty(JPanel panel, String labelText, String propertyKey, JComponent inputComponent,
            String defaultValue) {
        PropertyRow row = new PropertyRow(propertyKey, labelText, panel);

        // Label
        JLabel label = new JLabel(labelText + ":");
        row.label = label;
        row.labelConstraints = "align label";
        panel.add(label, row.labelConstraints);

        // Input
        fixComponentSize(inputComponent);
        row.inputConstraints = "split 2, growx, height pref!";
        panel.add(inputComponent, row.inputConstraints);

        propertyComponents.put(propertyKey, inputComponent);
        defaultValues.put(propertyKey, defaultValue);

        if (inputComponent instanceof JComboBox) {
            JComboBox<?> comboBox = (JComboBox<?>) inputComponent;
            comboBox.addActionListener(e -> updateColor(inputComponent, propertyKey, defaultValue));
            comboBox.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                        boolean isSelected, boolean cellHasFocus) {
                    Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    String current = props.getProperty(propertyKey);
                    if (current == null)
                        current = defaultValue;

                    String applied = appliedProps.getProperty(propertyKey);
                    if (applied == null)
                        applied = defaultValue;

                    if (value != null && value.toString().equals(applied)) { // NOSONAR
                        c.setForeground(GuiColors.getApplied());
                    } else if (value != null && value.toString().equals(current)) { // NOSONAR
                        c.setForeground(GuiColors.getSaved());
                    } else {
                        c.setForeground(GuiColors.getUnsaved());
                    }
                    return c;
                }
            });
        } else if (inputComponent instanceof JTextField) {
            JTextField textField = (JTextField) inputComponent;
            textField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {

                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    SwingUtilities.invokeLater(() -> updateColor(textField, propertyKey, defaultValue));
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    SwingUtilities.invokeLater(() -> updateColor(textField, propertyKey, defaultValue));
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    // Ignore attribute changes to avoid infinite loop
                }
            });
        }

        updateColor(inputComponent, propertyKey, defaultValue);

        // Help Button
        JButton helpBtn = new HelpButton();
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info");
        helpBtn.addActionListener(e -> showHelp(labelText, propertyKey));

        row.input = inputComponent;
        row.help = helpBtn;
        row.helpConstraints = "wrap";
        row.separator = new JSeparator();
        row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";

        panel.add(helpBtn, row.helpConstraints);
        panel.add(row.separator, row.separatorConstraints);

        allPropertyRows.add(row);
    }

    private void resetToCurrent() {
        try (FileInputStream in = new FileInputStream(propertiesFile.toFile())) {
            props.clear();
            props.load(in);
        } catch (Exception e) {
            e.printStackTrace();
        }

        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String def = defaultValues.get(key);
            String val = props.getProperty(key, def);

            if (comp instanceof JComboBox) {
                ((JComboBox<?>) comp).setSelectedItem(val);
            } else if (comp instanceof javax.swing.text.JTextComponent) {
                ((javax.swing.text.JTextComponent) comp).setText(val);
            }
        }
    }

    private void resetToApplied() {
        props.clear();
        props.putAll(appliedProps);

        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String def = defaultValues.get(key);
            String val = props.getProperty(key, def);

            if (comp instanceof JComboBox) {
                ((JComboBox<?>) comp).setSelectedItem(val);
            } else if (comp instanceof javax.swing.text.JTextComponent) {
                ((javax.swing.text.JTextComponent) comp).setText(val);
            }
        }
    }

    private void resetToHardcodedDefaults() {
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String defaultValue = defaultValues.get(key);

            if (comp instanceof JComboBox) {
                ((JComboBox<?>) comp).setSelectedItem(defaultValue);
            } else if (comp instanceof javax.swing.text.JTextComponent) {
                ((javax.swing.text.JTextComponent) comp).setText(defaultValue);
            }
            updateColor(comp, key, defaultValue);
        }
        JOptionPane.showMessageDialog(this, "All settings have been reset to their application defaults.",
                "Reset Complete", JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateColor(JComponent comp, String propName, String defaultValue) {
        String current = props.getProperty(propName);
        if (current == null) {
            current = defaultValue;
        }
        String applied = appliedProps.getProperty(propName);
        if (applied == null)
            applied = defaultValue;

        String value = "";
        if (comp instanceof JComboBox) {
            value = (String) ((JComboBox<?>) comp).getSelectedItem();
        } else if (comp instanceof javax.swing.text.JTextComponent) {
            value = ((javax.swing.text.JTextComponent) comp).getText().trim();
            current = current.trim();
            applied = applied.trim();
        }

        Color color;
        if (value != null && value.equals(applied)) {
            color = GuiColors.getApplied();
        } else if (value != null && value.equals(current)) {
            color = GuiColors.getSaved();
        } else {
            color = GuiColors.getUnsaved();
        }

        comp.setForeground(color);
    }

    private JPanel createLegendPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        panel.setBorder(new EmptyBorder(0, 0, 5, 0));

        panel.add(createLegendItem(GuiColors.getUnsaved(), "Unsaved values"));
        panel.add(createLegendItem(GuiColors.getSaved(), "Saved values"));
        panel.add(createLegendItem(GuiColors.getApplied(), "Applied values"));

        return panel;
    }

    private JPanel createLegendItem(Color color, String text) {
        JPanel item = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JLabel colorBox = new JLabel("\u25A0");
        colorBox.setForeground(color);
        item.add(colorBox);
        item.add(new JLabel(text));
        return item;
    }

    private void showHelp(String labelText, String propertyKey) {
        String description = helpTexts.getOrDefault(propertyKey, "No detailed description available.");
        String message = "<html><body style='width: 300px'>" +
                "<h2>" + labelText + "</h2>" +
                "<p><b>Property Key:</b> <code>" + propertyKey + "</code></p>" +
                "<hr>" +
                "<p>" + description.replace("\n", "<br>") + "</p>" +
                "</body></html>";

        JOptionPane.showMessageDialog(this, message, "Property Information", JOptionPane.INFORMATION_MESSAGE);
    }

    private void initHelpTexts() {
        helpTexts.put(".level",
                "Sets the default logging level for all loggers in the application unless overridden."
                        + "<br><br><b>Available Levels (from most to least severe):</b>"
                        + "<ul>"
                        + "<li><b>SEVERE:</b> Critical errors that may cause the application to terminate.</li>"
                        + "<li><b>WARNING:</b> Potential problems or unexpected events.</li>"
                        + "<li><b>INFO:</b> General operational information (default).</li>"
                        + "<li><b>CONFIG:</b> Static configuration messages.</li>"
                        + "<li><b>FINE:</b> Detailed tracing information.</li>"
                        + "<li><b>FINER:</b> More detailed tracing.</li>"
                        + "<li><b>FINEST:</b> Highly detailed tracing for debugging.</li>"
                        + "<li><b>ALL:</b> Log all messages.</li>"
                        + "<li><b>OFF:</b> Turn off logging.</li>"
                        + "</ul>");
        helpTexts.put("java.util.logging.ConsoleHandler.level",
                "Sets the minimum logging level for messages displayed in the console window (the main text area of the GUI)."
                        + "<br><br>Only messages with this level or higher will be shown in the console."
                        + "<br>This allows you to see important messages in the GUI while logging more detailed information to a file.");
        helpTexts.put("java.util.logging.FileHandler.level",
                "Sets the minimum logging level for messages written to the log file(s)."
                        + "<br><br>Only messages with this level or higher will be saved to disk."
                        + "<br>It is common to set this to a more verbose level (e.g., FINE) than the console to capture detailed information for debugging.");
        helpTexts.put("java.util.logging.FileHandler.pattern",
                "Defines the location and naming pattern for the log files."
                        + "<br><br><b>Special Placeholders:</b>"
                        + "<ul>"
                        + "<li><code>%h</code>: User's home directory.</li>"
                        + "<li><code>%t</code>: System's temporary directory.</li>"
                        + "<li><code>%u</code>: A unique number to resolve naming conflicts.</li>"
                        + "<li><code>%g</code>: The generation number for rotating logs.</li>"
                        + "<li><code>/</code>: The platform-specific path separator.</li>"
                        + "</ul>"
                        + "<b>Example:</b> <code>logs/signum%u.log</code> will create log files like <code>signum0.log</code>, <code>signum1.log</code>, etc., inside a 'logs' subdirectory.");
        helpTexts.put("java.util.logging.FileHandler.limit",
                "The approximate maximum size of a single log file in bytes."
                        + "<br><br>When a log file reaches this limit, it will be closed, and a new file will be opened for subsequent messages."
                        + "<br>This works in conjunction with 'File Count' to manage log rotation."
                        + "<br><br><b>Note:</b> Set to <code>0</code> for no size limit (a single, ever-growing log file).");
        helpTexts.put("java.util.logging.FileHandler.count",
                "The number of log files to use in the rotation sequence."
                        + "<br><br>Once this many files have been created, the logger will start overwriting the oldest file (e.g., <code>...log.0</code>)."
                        + "<br>For example, if 'File Count' is <code>5</code>, the logs will be named <code>...log.0, ...log.1, ...log.2, ...log.3, ...log.4</code>.");
    }

    private void addSectionHeader(JPanel panel, String title, boolean isFirst) {
        PropertyRow row = new PropertyRow(null, title, panel);
        JLabel label = new JLabel(title);
        label.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD));
        label.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, GuiColors.getSeparator()));

        row.label = label;
        if (isFirst) {
            row.labelConstraints = "span, growx, gapbottom 5, wrap";
        } else {
            row.labelConstraints = "span, growx, gaptop 15, gapbottom 5, wrap";
        }
        panel.add(label, row.labelConstraints);
        allPropertyRows.add(row);
    }

    private static class PropertyRow {
        final String propertyKey; // null for section headers
        final String labelText;
        final JPanel originalParent;

        JLabel label;
        String labelConstraints;

        JComponent input;
        String inputConstraints;

        JButton help;
        String helpConstraints;

        JSeparator separator;
        String separatorConstraints;

        PropertyRow(String propertyKey, String labelText, JPanel originalParent) {
            this.propertyKey = propertyKey;
            this.labelText = labelText;
            this.originalParent = originalParent;
        }
    }
}