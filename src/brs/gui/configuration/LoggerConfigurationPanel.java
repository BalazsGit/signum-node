package brs.gui.configuration;

import brs.props.Props;
import brs.util.PathUtils;
import brs.gui.GuiColors;
import brs.gui.GuiConstants;
import brs.gui.GuiFontManager;
import brs.gui.util.GuiUtils;
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
import java.awt.event.HierarchyEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.slf4j.Logger;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;

public class LoggerConfigurationPanel extends JPanel {

    private static final String[] LOG_LEVELS = { "SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST",
            "ALL", "OFF" };
    private final Runnable restartAction;
    private final Runnable backAction;
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggerConfigurationPanel.class);
    private final Runnable switchAction;
    private final Properties props;
    private final Properties appliedProps;
    private final Map<String, String> helpTexts = new HashMap<>();
    private final Map<String, Supplier<String>> valueSuppliers = new HashMap<>();
    private final Map<String, JComponent> propertyComponents = new HashMap<>();
    private final Map<String, String> defaultValues = new HashMap<>();
    private final String confFolder;
    private Path propertiesFile;
    private JComboBox<String> profileComboBox;
    private final java.util.List<PropertyRow> allPropertyRows = new ArrayList<>();
    private JPanel searchResultsPanel;
    private CardLayout contentCardLayout;
    private JButton saveProfileBtn;
    private JButton applyProfileBtn;
    private JButton renameProfileBtn;
    private JButton deleteProfileBtn;
    private JButton newProfileBtn;
    private JButton reloadProfileBtn;
    private JButton resetToDefaultsBtn;
    private JButton refreshProfilesBtn;
    private JPanel contentContainer;
    private JComponent verticalFiller;
    private String runningProfileName;
    private String activeProfileName;
    private String loadedProfileName;
    private boolean isProgrammaticChange = false;

    public LoggerConfigurationPanel(Runnable restartAction, String confFolder, Runnable backAction,
            Runnable switchAction) {
        super(new BorderLayout());
        this.restartAction = restartAction;
        this.confFolder = confFolder;
        this.backAction = backAction;
        this.switchAction = switchAction;

        // Determine the currently applied profile name from metadata once at startup
        String lastProfile = ConfigurationUtils
                .loadAppliedProfile(ConfigurationUtils.getProfileMetadataPath(confFolder, "logging"));
        if ("logging-default".equals(lastProfile)) {
            lastProfile = "logging";
        }
        this.runningProfileName = lastProfile != null ? lastProfile.trim() : "logging";
        this.activeProfileName = this.runningProfileName;
        this.loadedProfileName = this.runningProfileName;

        this.propertiesFile = ConfigurationUtils.resolveProfilePath(confFolder, "logging",
                this.loadedProfileName + ".properties");
        ConfigurationUtils.ensureConfigFileExists(this.propertiesFile);

        this.props = new Properties();
        try (FileInputStream in = new FileInputStream(propertiesFile.toFile())) {
            this.props.load(in);
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.appliedProps = new Properties();
        loadAppliedProperties();

        this.renameProfileBtn = new JButton("Rename Profile");
        this.deleteProfileBtn = new JButton("Delete Profile");
        // Initialize buttons early to avoid NullPointerException in listeners during UI
        // construction
        this.saveProfileBtn = new JButton("Save Profile As");
        this.applyProfileBtn = new JButton("Apply Profile");

        initHelpTexts();
        initUI();
    }

    private void initUI() {
        JPanel bodyPanel = new JPanel(new BorderLayout());

        // --- Profile Panel ---
        JPanel profilePanel = new JPanel(new MigLayout("insets 0, gap 5"));
        profilePanel.setBorder(new EmptyBorder(5, 10, 5, 5));
        profilePanel.add(new JLabel("Configuration Profile:"));

        profileComboBox = new JComboBox<>();
        profileComboBox.setEditable(false);
        profileComboBox.setPrototypeDisplayValue("XXXXXXXXXXXXXXXXXXXX");
        ConfigurationUtils.fixComponentSize(profileComboBox);
        profilePanel.add(profileComboBox);

        profileComboBox.setRenderer(
                ConfigurationUtils.createProfileComboBoxRenderer(() -> runningProfileName, () -> activeProfileName));

        newProfileBtn = new JButton("New Default Profile");
        newProfileBtn.setToolTipText("Create a new profile initialized with application defaults");
        newProfileBtn.addActionListener(e -> createNewProfile());
        profilePanel.add(newProfileBtn);

        saveProfileBtn.setToolTipText("Save Logger Configuration Profile");
        saveProfileBtn.addActionListener(e -> saveProfile());
        profilePanel.add(saveProfileBtn);

        applyProfileBtn.setToolTipText("Apply selected profile to the node");
        applyProfileBtn.addActionListener(e -> applyProfile());
        profilePanel.add(applyProfileBtn);

        renameProfileBtn.setToolTipText("Rename selected profile");
        renameProfileBtn.addActionListener(e -> renameProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(renameProfileBtn);

        deleteProfileBtn.setToolTipText("Delete selected profile");
        deleteProfileBtn.addActionListener(e -> deleteProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(deleteProfileBtn);

        resetToDefaultsBtn = new JButton("Reset to Defaults");
        resetToDefaultsBtn.setToolTipText("Reset current profile settings to application defaults (without saving)");
        resetToDefaultsBtn.addActionListener(e -> resetToDefaults());
        profilePanel.add(resetToDefaultsBtn);

        reloadProfileBtn = new JButton("Reload Profile");
        reloadProfileBtn.setToolTipText("Reload settings from the current profile file on disk");
        reloadProfileBtn.addActionListener(e -> reloadProfile());
        profilePanel.add(reloadProfileBtn);

        refreshProfilesBtn = new JButton("Refresh Profiles");
        refreshProfilesBtn.setToolTipText("Refresh the list of available profiles from the disk");
        refreshProfilesBtn.addActionListener(e -> refreshProfileList());
        profilePanel.add(refreshProfilesBtn);

        updateProfileButtonsUI();

        profileComboBox.addActionListener(e -> {
            if (isProgrammaticChange)
                return;
            String selected = (String) profileComboBox.getSelectedItem();
            if (selected != null) {
                loadProfile(selected);
            }
            updateProfileComboBoxColor();
            updateProfileButtonStates();
        });

        JButton helpBtn = new HelpButton();
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("View detailed information about logger profile management");
        helpBtn.addActionListener(e -> showProfileHelp()); // Add help button
        profilePanel.add(helpBtn);

        JScrollPane profileScrollPane = new JScrollPane(profilePanel);
        profileScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        profileScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        profileScrollPane.setBorder(BorderFactory.createEmptyBorder());
        profileScrollPane.setOpaque(false);
        profileScrollPane.getViewport().setOpaque(false);

        GuiUtils.addHorizontalScrollPadding(profileScrollPane, profilePanel, new Insets(5, 10, 5, 5));

        refreshProfileList();

        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                refreshProfileList();
            }
        });

        // --- Search Panel ---
        JPanel searchPanel = new JPanel(new MigLayout("insets 5 10 5 5, fillx", "[][grow]", "[]"));
        searchPanel.add(new JLabel("Search Configuration:"));
        JTextField searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "Type to filter properties...");
        ConfigurationUtils.styleInputComponent(searchField);
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
        northPanel.add(profileScrollPane, BorderLayout.NORTH);
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

        JPanel bottomContainer = new JPanel(new BorderLayout());
        bottomContainer.add(new JSeparator(SwingConstants.HORIZONTAL), BorderLayout.NORTH);
        bottomContainer.add(bottomPanel, BorderLayout.CENTER);
        add(bottomContainer, BorderLayout.SOUTH);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Re-style and re-size input fields
        if (allPropertyRows != null) {
            for (PropertyRow row : allPropertyRows) {
                if (row.propertyKey == null && row.label != null) {
                    row.label.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD));
                }
                if (row.input != null) {
                    ConfigurationUtils.styleInputComponent(row.input);
                    ConfigurationUtils.fixComponentSize(row.input);
                }
            }
        }
        updateProfileButtonsUI();
    }

    private void updateProfileButtonsUI() {
        if (profileComboBox != null)
            ConfigurationUtils.fixComponentSize(profileComboBox);
        ConfigurationUtils.configureProfileToolbar(newProfileBtn, saveProfileBtn, applyProfileBtn, renameProfileBtn,
                deleteProfileBtn, reloadProfileBtn, refreshProfilesBtn, resetToDefaultsBtn);
    }

    private void refreshUIColors() {
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            updateColor(entry.getValue(), entry.getKey(), defaultValues.get(entry.getKey()));
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
            if (!allPropertyRows.isEmpty()) {
                allPropertyRows.get(0).originalParent.removeAll();
            }
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
        ConfigurationUtils.styleInputComponent(textField);
        ConfigurationUtils.fixComponentSize(textField);
        return textField;
    }

    private void refreshProfileList() {
        boolean wasProgrammatic = isProgrammaticChange;
        try {
            isProgrammaticChange = true;
            String currentSelection = (String) profileComboBox.getSelectedItem();
            profileComboBox.removeAllItems();

            String lastProfile = ConfigurationUtils
                    .loadAppliedProfile(ConfigurationUtils.getProfileMetadataPath(confFolder, "logging"));
            if ("logging-default".equals(lastProfile)) {
                lastProfile = "logging";
            }
            this.activeProfileName = lastProfile != null ? lastProfile.trim() : "logging";

            Path loggingConfPath = PathUtils.resolvePath(confFolder).resolve("logging");
            ConfigurationUtils.fetchProfileNames(loggingConfPath, brs.Signum.DEFAULT_LOGGING_PROPERTIES_NAME)
                    .forEach(profileComboBox::addItem);

            // Ensure the base profile is always available in the list
            boolean hasBase = false;
            for (int i = 0; i < profileComboBox.getItemCount(); i++) {
                if ("logging".equals(profileComboBox.getItemAt(i))) {
                    hasBase = true;
                    break;
                }
            }
            if (!hasBase) {
                profileComboBox.insertItemAt("logging", 0);
            }

            if (currentSelection != null && profileComboBox.getItemCount() > 0) {
                profileComboBox.setSelectedItem(currentSelection);
            } else {
                profileComboBox.setSelectedItem(this.activeProfileName);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            isProgrammaticChange = wasProgrammatic;
        }
        updateProfileComboBoxColor();
        updateProfileButtonStates();
    }

    private void updateProfileComboBoxColor() {
        ConfigurationUtils.updateProfileComboBoxColor(profileComboBox, runningProfileName, activeProfileName);
    }

    private boolean saveProfile() {
        String currentProfile = (String) profileComboBox.getSelectedItem();
        String suggestedName = currentProfile != null ? currentProfile : "";

        JTextField nameField = new JTextField(suggestedName);
        JLabel errorLabel = new JLabel("Saving as a system profile is not allowed.");
        errorLabel.setForeground(GuiColors.getContrastRed());
        errorLabel.setVisible(false);

        JPanel panel = new JPanel(new MigLayout("wrap 1, fillx, insets 0", "[grow]", "[]5[]5[]"));
        panel.add(new JLabel("Enter profile name:"));
        panel.add(nameField, "growx");
        panel.add(errorLabel, "hidemode 3");

        String report = getUnsavedChangesReport();
        if (report != null) {
            JLabel reportLabel = new JLabel(report);
            JScrollPane scroll = new JScrollPane(reportLabel);
            scroll.setPreferredSize(new Dimension(450, 150));
            scroll.setBorder(BorderFactory.createTitledBorder("Changes to be saved"));
            panel.add(scroll, "growx, gaptop 10");
        }

        JButton saveBtn = new JButton("Save");
        JButton discardBtn = new JButton("Discard");
        JButton cancelBtn = new JButton("Cancel");
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

        nameField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void validate() {
                String text = nameField.getText().trim();
                boolean isReserved = "logging-default".equalsIgnoreCase(text);
                errorLabel.setVisible(isReserved);
                saveBtn.setEnabled(!isReserved && !text.isEmpty());
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                validate();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                validate();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                validate();
            }
        });

        try {
            while (true) {
                pane.setValue(JOptionPane.UNINITIALIZED_VALUE);
                dialog.setVisible(true);
                Object value = pane.getValue();

                if (value == saveBtn) {
                    String name = nameField.getText().trim();
                    try {
                        Path targetFile = ConfigurationUtils.resolveProfilePath(confFolder, "logging",
                                name + ".properties");
                        if (Files.exists(targetFile)) {
                            int choice = JOptionPane.showConfirmDialog(this,
                                    "Profile '" + name + "' already exists. Do you want to overwrite it?",
                                    "Override profile settings",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.WARNING_MESSAGE);
                            if (choice != JOptionPane.YES_OPTION) {
                                continue;
                            }
                        }

                        Properties propsToSave = getPropertiesFromUIInternal();
                        ConfigurationUtils.savePropertiesPreservingFormat(targetFile, propsToSave,
                                propertyComponents.keySet());

                        isProgrammaticChange = true;
                        try {
                            this.loadedProfileName = name;
                            this.props.clear();
                            this.props.putAll(propsToSave);
                            this.propertiesFile = targetFile;

                            refreshProfileList();
                            profileComboBox.setSelectedItem(name);
                        } finally {
                            isProgrammaticChange = false;
                        }

                        updateProfileComboBoxColor();
                        updateDirtyStatus();

                        refreshUIColors();
                        JOptionPane.showMessageDialog(this, "Profile '" + name + "' saved successfully.",
                                "Success", JOptionPane.INFORMATION_MESSAGE);
                        return true;
                    } catch (Exception e) {
                        JOptionPane.showMessageDialog(this, "Error saving profile: " + e.getMessage(), "Error",
                                JOptionPane.ERROR_MESSAGE);
                    }
                } else if (value == discardBtn) {
                    isProgrammaticChange = true;
                    updateUIFromProperties(props);
                    updateDirtyStatus();
                    isProgrammaticChange = false;
                    return false;
                } else {
                    return false;
                }
            }
        } finally {
            dialog.dispose();
        }
    }

    private void loadProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()
                || profileName.equals(loadedProfileName)) {
            return;
        }

        checkUnsavedChangesAndProceed(
                () -> {
                    Path targetFile = ConfigurationUtils.resolveProfilePath(confFolder, "logging",
                            profileName + ".properties");
                    if (Files.exists(targetFile)) {
                        Properties loaded = new Properties();
                        try (FileInputStream in = new FileInputStream(targetFile.toFile())) {
                            isProgrammaticChange = true;
                            loaded.load(in);
                            props.clear();
                            props.putAll(loaded);
                            updateUIFromProperties(loaded);
                            this.propertiesFile = targetFile;
                            this.loadedProfileName = profileName;
                            updateDirtyStatus();
                            isProgrammaticChange = false;
                            updateProfileComboBoxColor();
                        } catch (Exception e) {
                            JOptionPane.showMessageDialog(this, "Error loading profile file: " + e.getMessage(),
                                    "Error",
                                    JOptionPane.ERROR_MESSAGE);
                            // Revert to headless fallback
                            isProgrammaticChange = true;
                            profileComboBox.setSelectedItem("logging");
                            loadProfile("logging-default");
                            isProgrammaticChange = false;
                        }
                    }
                },
                () -> profileComboBox.setSelectedItem(loadedProfileName));
    }

    public boolean checkUnsavedChangesAndProceed(Runnable onProceed, Runnable onCancel) {
        String report = getUnsavedChangesReport();
        if (report == null) {
            if (onProceed != null)
                onProceed.run();
            return true;
        }

        Object[] message = {
                "You have unsaved changes in profile '" + loadedProfileName + "'.",
                report,
                "What would you like to do?"
        };
        Object[] options = { "Save Profile As", "Discard", "Cancel" };
        int result = JOptionPane.showOptionDialog(this, message, "Unsaved Changes",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);

        if (result == JOptionPane.YES_OPTION) {
            if (saveProfile()) {
                if (onProceed != null)
                    onProceed.run();
                return true;
            }
            return false;
        } else if (result == JOptionPane.NO_OPTION) {
            isProgrammaticChange = true;
            updateUIFromProperties(props);
            updateDirtyStatus();
            isProgrammaticChange = false;
            if (onProceed != null)
                onProceed.run();
            return true;
        } else {
            if (onCancel != null)
                onCancel.run();
            return false;
        }
    }

    private String getUnsavedChangesReport() {
        StringBuilder report = new StringBuilder(
                "<html><b>Unsaved changes in Logger Configuration (Profile: '" + loadedProfileName + "'):</b><ul>");
        boolean changesFound = false;
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String savedValue = props.getProperty(key);
            if (savedValue == null)
                savedValue = defaultValues.get(key);
            if (savedValue == null)
                savedValue = "";

            String newVal = "";
            if (comp instanceof JComboBox) {
                newVal = (String) ((JComboBox<?>) comp).getSelectedItem();
            } else if (comp instanceof JTextComponent) {
                newVal = ((JTextComponent) comp).getText().trim();
            }

            if (!newVal.trim().equals(savedValue.trim())) {
                changesFound = true;
                PropertyRow row = allPropertyRows.stream()
                        .filter(r -> key.equals(r.propertyKey))
                        .findFirst()
                        .orElse(null);
                String label = row != null ? row.labelText : key;
                report.append("<li>").append(label).append(": '")
                        .append(savedValue.trim()).append("' &rarr; '")
                        .append(newVal.trim()).append("'</li>");
            }
        }
        report.append("</ul></html>");
        return changesFound ? report.toString() : null;
    }

    private void reloadProfile() {
        if (loadedProfileName != null) {
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

            Path targetFile = ConfigurationUtils.resolveProfilePath(confFolder, "logging",
                    loadedProfileName + ".properties");
            if (Files.exists(targetFile)) {
                Properties loaded = new Properties();
                try (FileInputStream in = new FileInputStream(targetFile.toFile())) {
                    isProgrammaticChange = true;
                    loaded.load(in);
                    props.clear();
                    props.putAll(loaded);
                    updateUIFromProperties(loaded);
                    updateDirtyStatus();
                    updateProfileComboBoxColor();
                    isProgrammaticChange = false;
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(this, "Error reloading profile: " + e.getMessage(), "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }

    private void createNewProfile() {
        checkUnsavedChangesAndProceed(() -> {
            String name = (String) JOptionPane.showInputDialog(this, "Enter new profile name:", "New Profile",
                    JOptionPane.PLAIN_MESSAGE, null, null, "");
            if (name == null || name.trim().isEmpty() || "logging-default".equalsIgnoreCase(name.trim()))
                return;

            Path targetFile = ConfigurationUtils.resolveProfilePath(confFolder, "logging", name + ".properties");
            if (Files.exists(targetFile)) {
                JOptionPane.showMessageDialog(this, "Profile '" + name + "' already exists.", "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            isProgrammaticChange = true;
            try {
                for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
                    String key = entry.getKey();
                    JComponent comp = entry.getValue();
                    String defaultValue = defaultValues.get(key);

                    if (comp instanceof JComboBox) {
                        ((JComboBox<?>) comp).setSelectedItem(defaultValue);
                    } else if (comp instanceof javax.swing.text.JTextComponent) {
                        ((javax.swing.text.JTextComponent) comp).setText(defaultValue);
                    }
                }

                Properties propsToSave = getPropertiesFromUIInternal();
                ConfigurationUtils.savePropertiesPreservingFormat(targetFile, propsToSave, propertyComponents.keySet());

                this.loadedProfileName = name; // Update early to prevent redundant load prompts during refresh
                refreshProfileList();
                profileComboBox.setSelectedItem(name);
                this.props.clear();
                this.props.putAll(propsToSave);
                this.propertiesFile = targetFile;
                updateDirtyStatus();
                updateUIFromProperties(propsToSave);
                updateProfileComboBoxColor();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Error creating profile: " + e.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
            } finally {
                isProgrammaticChange = false;
            }
        }, null);
    }

    private void resetToDefaults() {
        Properties defaultProps = new Properties();
        for (Map.Entry<String, String> entry : defaultValues.entrySet()) {
            defaultProps.setProperty(entry.getKey(), entry.getValue());
        }
        isProgrammaticChange = true;
        updateUIFromProperties(defaultProps);
        updateDirtyStatus();
        refreshUIColors();
        isProgrammaticChange = false;
        JOptionPane.showMessageDialog(this,
                "All settings reset to application defaults. Remember to save if you want to keep these changes.",
                "Reset to Defaults", JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateProfileButtonStates() {
        String selected = (String) profileComboBox.getSelectedItem();
        boolean isReadOnly = "logging".equals(selected) || "logging-default".equals(selected);
        resetToDefaultsBtn.setEnabled(true); // Always enable reset to defaults
        renameProfileBtn.setEnabled(!isReadOnly);
        deleteProfileBtn.setEnabled(!isReadOnly);
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
                || "logging-default".equalsIgnoreCase(newProfileName.trim())) {
            return; // User cancelled or entered the same name
        }

        try {
            Path oldFile = ConfigurationUtils.resolveProfilePath(confFolder, "logging", oldProfileName + ".properties");
            Path newFile = ConfigurationUtils.resolveProfilePath(confFolder, "logging", newProfileName + ".properties");

            if (ConfigurationUtils.confirmAndRenameProfile(this, oldFile, newFile, oldProfileName, newProfileName)) {
                refreshProfileList();
                profileComboBox.setSelectedItem(newProfileName);
                if (oldProfileName.equals(activeProfileName)) {
                    activeProfileName = newProfileName;
                    ConfigurationUtils.updateAppliedProfile(
                            ConfigurationUtils.getProfileMetadataPath(confFolder, "logging"), newProfileName);
                }
                updateProfileComboBoxColor();
                updateDirtyStatus();

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
        if ("logging-default".equals(profileName)) {
            JOptionPane.showMessageDialog(this, "The system profiles cannot be deleted.", "Action Not Allowed",
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
            Path file = ConfigurationUtils.resolveProfilePath(confFolder, "logging", profileName + ".properties");
            if (Files.exists(file)) {
                Files.delete(file);
                refreshProfileList();
                profileComboBox.setSelectedItem("logging");
                loadProfile("logging");
                JOptionPane.showMessageDialog(this, "Profile '" + profileName + "' deleted successfully.",
                        "Success", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error deleting profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    public void loadAppliedProperties() {
        Path appliedFile = ConfigurationUtils.resolveProfilePath(confFolder, "logging",
                brs.Signum.LOGGING_PROPERTIES_NAME);
        if (Files.exists(appliedFile)) {
            try (FileInputStream in = new FileInputStream(appliedFile.toFile())) {
                appliedProps.clear();
                appliedProps.load(in);
            } catch (Exception e) {
                LOGGER.warn("Could not load applied logging properties: {}", e.getMessage());
            }
        } else {
            appliedProps.clear();
        }
        refreshUIColors();
    }

    private boolean hasUnsavedChanges() {
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String savedValue = props.getProperty(key);
            if (savedValue == null)
                savedValue = defaultValues.get(key);
            if (savedValue == null)
                savedValue = "";

            String val = "";
            if (comp instanceof JComboBox) {
                val = (String) ((JComboBox<?>) comp).getSelectedItem();
            } else if (comp instanceof JTextComponent) {
                val = ((JTextComponent) comp).getText();
            }
            if (!val.trim().equals(savedValue.trim())) {
                return true;
            }
        }
        return false;
    }

    private void updateDirtyStatus() {
        boolean dirty = hasUnsavedChanges();
        saveProfileBtn.setText(dirty ? "Save Profile As *" : "Save Profile As");

        ConfigurationUtils.fixComponentSize(saveProfileBtn);
        if (saveProfileBtn.getParent() != null) {
            saveProfileBtn.getParent().revalidate();
        }
    }

    private void applyProfile() {
        String selected = (String) profileComboBox.getSelectedItem();
        if (selected == null)
            return;

        if (!checkUnsavedChangesAndProceed(null, null)) {
            return;
        }

        String message = "Apply profile '" + selected + "'?";
        Object[] options = { "Apply and Restart", "Apply for Next Startup", "Cancel" };
        int choice = JOptionPane.showOptionDialog(this,
                message,
                "Apply Profile",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);

        if (choice == 0 || choice == 1) {
            ConfigurationUtils.updateAppliedProfile(ConfigurationUtils.getProfileMetadataPath(confFolder, "logging"),
                    selected);
            this.activeProfileName = selected;
            updateProfileComboBoxColor();
        }
    }

    private Path getProfileMetadataPath() {
        return PathUtils.resolvePath(confFolder).resolve("logging").resolve("profile.json");
    }

    private void handleBack() {
        checkUnsavedChangesAndProceed(
                () -> {
                    if (backAction != null)
                        backAction.run();
                },
                null);
    }

    private Properties getPropertiesFromUIInternal() {
        Properties props = new Properties();
        for (Map.Entry<String, Supplier<String>> entry : valueSuppliers.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue().get();
            String def = defaultValues.get(key);
            if (val != null && !val.trim().equals(def != null ? def.trim() : "")) {
                props.setProperty(key, val);
            }
        }
        return props;
    }

    /**
     * Legacy support for external calls if any
     */
    public Properties getPropertiesFromUI() {
        return getPropertiesFromUIInternal();
    }

    private String getComponentValue(JComponent comp) {
        if (comp instanceof JComboBox) {
            Object item = ((JComboBox<?>) comp).getSelectedItem();
            return item != null ? item.toString() : "";
        } else if (comp instanceof JTextComponent) {
            return ((JTextComponent) comp).getText();
        }
        return "";
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
        String message = "<html><body style='width: 400px'>" +
                "<h2>Logger Configuration Profiles</h2>" +
                "<p>Profiles allow you to maintain multiple sets of logging configurations. Use the toolbar buttons to perform the following actions:</p>"
                +
                "<ul>" +
                "<li><b>New Default Profile</b>: Creates a new configuration profile initialized with application defaults.</li>"
                +
                "<li><b>Save Profile As</b>: Saves the current logging settings into the selected or a new profile.</li>"
                +
                "<li><b>Apply Profile</b>: Activates the selected logging profile. You can choose to apply it for the next startup or restart the node service immediately to apply changes.</li>"
                +
                "<li><b>Rename Profile</b>: Changes the name of the currently selected configuration profile.</li>"
                +
                "<li><b>Delete Profile</b>: Permanently removes the selected configuration profile from the disk.</li>"
                +
                "<li><b>Reset to Defaults</b>: Resets all current settings to their application default values without saving.</li>"
                +
                "<li><b>Reload Profile</b>: Reloads settings from the profile file on disk, discarding any unsaved changes in the UI.</li>"
                +
                "<li><b>Refresh Profiles</b>: Synchronizes the profile list with the files currently available on disk.</li>"
                +
                "</ul>" +
                "<p>Profiles are stored as \".properties\" files within the logging sub-directory of the configuration folder.</p>"
                +
                "</body></html>";

        JOptionPane.showMessageDialog(this, message, "About Configuration Profiles", JOptionPane.INFORMATION_MESSAGE);
    }

    private void addProperty(JPanel panel, String labelText, String propertyKey, JComponent inputComponent,
            String defaultValue) {
        PropertyRow row = new PropertyRow(propertyKey, labelText, panel);

        // Label
        JLabel label = new JLabel(labelText);
        row.label = label;
        row.labelConstraints = "align label";
        panel.add(label, row.labelConstraints);

        // Input
        ConfigurationUtils.fixComponentSize(inputComponent);
        row.inputConstraints = "split 2, growx, height pref!";
        panel.add(inputComponent, row.inputConstraints);

        if (inputComponent instanceof JComboBox) {
            JComboBox<?> combo = (JComboBox<?>) inputComponent;
            valueSuppliers.put(propertyKey, () -> (String) combo.getSelectedItem());
        } else if (inputComponent instanceof JTextComponent) {
            JTextComponent text = (JTextComponent) inputComponent;
            valueSuppliers.put(propertyKey, text::getText);
        }

        propertyComponents.put(propertyKey, inputComponent);
        defaultValues.put(propertyKey, defaultValue);

        if (inputComponent instanceof JComboBox) {
            JComboBox<?> comboBox = (JComboBox<?>) inputComponent;
            comboBox.addActionListener(e -> {
                if (isProgrammaticChange)
                    return;
                updateColor(inputComponent, propertyKey, defaultValue);
                updateDirtyStatus();
            });
            comboBox.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                        boolean isSelected, boolean cellHasFocus) {
                    Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    String savedVal = props.getProperty(propertyKey);
                    if (savedVal == null)
                        savedVal = defaultValue;

                    String applied = appliedProps.getProperty(propertyKey);
                    boolean hasApplied = appliedProps.containsKey(propertyKey);
                    if (applied == null)
                        applied = defaultValue;

                    if (hasApplied && value != null && value.toString().equals(applied)) {
                        c.setForeground(GuiColors.getApplied());
                    } else if (value != null && value.toString().equals(savedVal)) {
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
                    if (isProgrammaticChange)
                        return;
                    SwingUtilities.invokeLater(() -> {
                        updateColor(textField, propertyKey, defaultValue);
                        updateDirtyStatus();
                    });
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    if (isProgrammaticChange)
                        return;
                    SwingUtilities.invokeLater(() -> {
                        updateColor(textField, propertyKey, defaultValue);
                        updateDirtyStatus();
                    });
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    if (isProgrammaticChange)
                        return;
                    SwingUtilities.invokeLater(() -> {
                        updateColor(textField, propertyKey, defaultValue);
                        updateDirtyStatus();
                    });
                }
            });
        }

        updateColor(inputComponent, propertyKey, defaultValue);

        // Help Button
        JButton helpBtn = new HelpButton();
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info");
        helpBtn.addActionListener(e ->

        showHelp(labelText, propertyKey));

        row.input = inputComponent;
        row.help = helpBtn;
        row.helpConstraints = "wrap";
        row.separator = new JSeparator();
        row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";

        panel.add(helpBtn, row.helpConstraints);
        panel.add(row.separator, row.separatorConstraints);

        allPropertyRows.add(row);
    }

    private void updateColor(JComponent comp, String propName, String defaultValue) {
        String savedValue = props.getProperty(propName, defaultValue);
        String applied = appliedProps.getProperty(propName, defaultValue);

        String value = getComponentValue(comp).trim();
        savedValue = savedValue.trim();
        applied = applied.trim();

        Color color;
        if (value.equals(applied)) {
            color = GuiColors.getApplied();
        } else if (value.equals(savedValue)) {
            color = GuiColors.getSaved();
        } else {
            color = GuiColors.getUnsaved();
        }

        comp.setForeground(color);

        // Update Label asterisk
        PropertyRow row = allPropertyRows.stream()
                .filter(r -> propName.equals(r.propertyKey))
                .findFirst()
                .orElse(null);
        if (row != null && row.label != null) {
            boolean isDirty = !value.trim().equals(savedValue.trim());
            row.label.setText(isDirty ? row.labelText + " *" : row.labelText);
        }
    }

    private JPanel createLegendPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        panel.setBorder(new EmptyBorder(0, 0, 5, 0));

        panel.add(createLegendItem(GuiColors.getUnsaved(), "Unsaved values"));
        panel.add(createLegendItem(GuiColors.getSaved(), "Saved values"));
        panel.add(createLegendItem(GuiColors.getApplied(), "Applied values"));

        JButton helpBtn = new HelpButton();
        helpBtn.setToolTipText("Detailed Color Legend");
        helpBtn.addActionListener(e -> showColorLegendHelp());
        panel.add(helpBtn);

        return panel;
    }

    private void showColorLegendHelp() {
        String msg = "<html><body style='width: 350px'>" +
                "<h3>Color Coding Legend</h3>" +
                "<p>The configuration values are color-coded to indicate their current status:</p>" +
                "<ul>" +
                "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getUnsaved())
                + "'>\u25A0 Unsaved Values:</font></b> " +
                "These values have been modified in the UI but have not yet been saved to the configuration file. " +
                "Properties with unsaved changes are marked with an asterisk (*).</li>" +
                "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getSaved())
                + "'>\u25A0 Saved Values:</font></b> " +
                "These values are saved in the currently loaded profile on disk, but they differ from the values " +
                "currently being used by the running node.</li>" +
                "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getApplied())
                + "'>\u25A0 Applied Values:</font></b> " +
                "These values match exactly what the node is currently using. Note that most changes require a restart to take effect.</li>"
                +
                "</ul>" +
                "</body></html>";
        JOptionPane.showMessageDialog(this, msg, "Color Legend", JOptionPane.INFORMATION_MESSAGE);
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