package brs.gui.configuration;

import brs.props.Props;
import brs.util.PathUtils;
import brs.Signum;
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
import java.awt.event.ActionListener;
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
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

public class LoggerConfigurationPanel extends JPanel {

    private static final String[] LOG_LEVELS = { "SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST",
            "ALL", "OFF" };
    private static final String[] COMMON_LOGGERS = {
            "brs", "brs.http", "brs.peer", "brs.db", "brs.crypto", "brs.util",
            "org.eclipse.jetty", "javax.servlet", "com.zaxxer.hikari", "org.jooq", "sun.rmi"
    };

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
    private final Set<String> dynamicLoggerKeys = new java.util.LinkedHashSet<>();
    private final Set<String> staticLoggerKeys = new java.util.HashSet<>();
    private JPanel mainContentPanel;
    private JComboBox<String> addLoggerClassCombo;
    private JPanel contentContainer;
    private JComponent verticalFiller;
    private String runningProfileName;
    private String activeProfileName;
    private String loadedProfileName;
    private boolean isProgrammaticChange = false;
    private JCheckBox linkToNodeCheck;
    private final Consumer<String> onLinkAction;
    private final Supplier<String> activeNodeProfileSupplier;
    private final Supplier<String> linkedProfileSupplier;

    public LoggerConfigurationPanel(Runnable restartAction, String confFolder, Runnable backAction,
            Runnable switchAction, Consumer<String> onLinkAction, Supplier<String> activeNodeProfileSupplier,
            Supplier<String> linkedProfileSupplier) {
        super(new BorderLayout());
        this.restartAction = restartAction;
        this.confFolder = confFolder;
        this.backAction = backAction;
        this.switchAction = switchAction;
        this.onLinkAction = onLinkAction;
        this.activeNodeProfileSupplier = activeNodeProfileSupplier;
        this.linkedProfileSupplier = linkedProfileSupplier;

        // Determine the currently applied profile name from metadata once at startup
        this.runningProfileName = Signum.getActiveLoggingProfile();
        this.activeProfileName = this.runningProfileName;
        this.loadedProfileName = this.runningProfileName;

        this.propertiesFile = getPropertiesPath(this.loadedProfileName);
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

        // After UI is initialized, ensure the correct profile is loaded and displayed
        isProgrammaticChange = true;
        updateUIFromProperties(this.props);
        isProgrammaticChange = false;
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
        updateLinkCheckbox();

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

        mainContentPanel = new JPanel(new MigLayout("fillx, insets 10, gap 5", "[][grow]", ""));

        // Clear list before rebuilding UI (in case of re-init)
        allPropertyRows.clear();

        addSectionHeader(mainContentPanel, "Global Settings", true);

        String defaultGlobalLevel = "SEVERE";
        JComboBox<String> globalLevelCombo = new JComboBox<>(LOG_LEVELS);
        globalLevelCombo.setSelectedItem(props.getProperty(".level", defaultGlobalLevel));
        addProperty(mainContentPanel, "Global Level", ".level", globalLevelCombo, defaultGlobalLevel);

        String defaultBrsLevel = "INFO";
        JComboBox<String> brsLevelCombo = new JComboBox<>(LOG_LEVELS);
        brsLevelCombo.setSelectedItem(props.getProperty("brs.level", defaultBrsLevel));
        addProperty(mainContentPanel, "Node (BRS) Level", "brs.level", brsLevelCombo, defaultBrsLevel);

        String defaultConsoleSize = "100000";
        JTextField consoleSizeField = createStyledTextField(
                props.getProperty("brs.gui.consoleLogSize", defaultConsoleSize));
        addProperty(mainContentPanel, "GUI Console Buffer Size (chars)", "brs.gui.consoleLogSize", consoleSizeField,
                defaultConsoleSize);

        addSectionHeader(mainContentPanel, "Log Destinations", false);
        addHandlersProperty(mainContentPanel);

        addSectionHeader(mainContentPanel, "Console Handler", false);

        String defaultConsoleLevel = "ALL";
        JComboBox<String> consoleLevelCombo = new JComboBox<>(LOG_LEVELS);
        consoleLevelCombo
                .setSelectedItem(props.getProperty("java.util.logging.ConsoleHandler.level", defaultConsoleLevel));
        addProperty(mainContentPanel, "Console Level", "java.util.logging.ConsoleHandler.level", consoleLevelCombo,
                defaultConsoleLevel);

        addSectionHeader(mainContentPanel, "File Handler", false);

        String defaultFileLevel = "INFO";
        JComboBox<String> fileLevelCombo = new JComboBox<>(LOG_LEVELS);
        fileLevelCombo.setSelectedItem(props.getProperty("java.util.logging.FileHandler.level", defaultFileLevel));
        addProperty(mainContentPanel, "File Level", "java.util.logging.FileHandler.level", fileLevelCombo,
                defaultFileLevel);

        // File Pattern
        String defaultPattern = "logs/signum%u.log";
        JTextField filePatternField = createStyledTextField(
                props.getProperty("java.util.logging.FileHandler.pattern", defaultPattern));
        addProperty(mainContentPanel, "Log File Pattern", "java.util.logging.FileHandler.pattern", filePatternField,
                defaultPattern);

        // File Limit
        String defaultLimit = "0";
        JTextField fileLimitField = createStyledTextField(
                props.getProperty("java.util.logging.FileHandler.limit", defaultLimit));
        addProperty(mainContentPanel, "File Size Limit (bytes)", "java.util.logging.FileHandler.limit", fileLimitField,
                defaultLimit);

        // File Count
        String defaultCount = "1";
        JTextField fileCountField = createStyledTextField(
                props.getProperty("java.util.logging.FileHandler.count", defaultCount));
        addProperty(mainContentPanel, "File Count", "java.util.logging.FileHandler.count", fileCountField,
                defaultCount);

        addSectionHeader(mainContentPanel, "Library Logging (Noise Suppression)", false);

        String defaultJettyLevel = "OFF";
        JComboBox<String> jettyLevelCombo = new JComboBox<>(LOG_LEVELS);
        jettyLevelCombo.setSelectedItem(props.getProperty("org.eclipse.jetty.level", defaultJettyLevel));
        addProperty(mainContentPanel, "Jetty Level", "org.eclipse.jetty.level", jettyLevelCombo, defaultJettyLevel);

        String defaultServletLevel = "OFF";
        JComboBox<String> servletLevelCombo = new JComboBox<>(LOG_LEVELS);
        servletLevelCombo.setSelectedItem(props.getProperty("javax.servlet.level", defaultServletLevel));
        addProperty(mainContentPanel, "Servlet Level", "javax.servlet.level", servletLevelCombo, defaultServletLevel);

        String defaultHikariLevel = "WARNING";
        JComboBox<String> hikariLevelCombo = new JComboBox<>(LOG_LEVELS);
        hikariLevelCombo.setSelectedItem(props.getProperty("com.zaxxer.hikari.level", defaultHikariLevel));
        addProperty(mainContentPanel, "Hikari Level", "com.zaxxer.hikari.level", hikariLevelCombo, defaultHikariLevel);

        String defaultJooqLevel = "OFF";
        JComboBox<String> jooqLevelCombo = new JComboBox<>(LOG_LEVELS);
        jooqLevelCombo.setSelectedItem(props.getProperty("org.jooq.Constants.level", defaultJooqLevel));
        addProperty(mainContentPanel, "JOOQ Level", "org.jooq.Constants.level", jooqLevelCombo, defaultJooqLevel);

        String defaultRmiLevel = "INFO";
        JComboBox<String> rmiLevelCombo = new JComboBox<>(LOG_LEVELS);
        rmiLevelCombo.setSelectedItem(props.getProperty("sun.rmi.level", defaultRmiLevel));
        addProperty(mainContentPanel, "RMI Level", "sun.rmi.level", rmiLevelCombo, defaultRmiLevel);

        String defaultDerivedLevel = "OFF";
        JComboBox<String> derivedLevelCombo = new JComboBox<>(LOG_LEVELS);
        derivedLevelCombo
                .setSelectedItem(props.getProperty("brs.db.store.DerivedTableManager.level", defaultDerivedLevel));
        addProperty(mainContentPanel, "Derived Table Level", "brs.db.store.DerivedTableManager.level",
                derivedLevelCombo,
                defaultDerivedLevel);

        staticLoggerKeys.addAll(propertyComponents.keySet());

        addSectionHeader(mainContentPanel, "Specific Logger Levels", false);
        addLoggerCreationGui(mainContentPanel);

        // Push everything to top
        JLabel verticalFiller = new JLabel();
        mainContentPanel.add(verticalFiller, "pushy");

        // --- Content Container (CardLayout for Settings vs Search Results) ---
        contentCardLayout = new CardLayout();
        contentContainer = new JPanel(contentCardLayout);

        JScrollPane scrollPane = new JScrollPane(mainContentPanel);
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
        JLabel pathLabel = new JLabel("Configuration File: " + propertiesFile.getFileName().toString());
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

    private void addHandlersProperty(JPanel panel) {
        String key = "handlers";
        String labelText = "Enabled Handlers";
        String defaultValue = "java.util.logging.ConsoleHandler";
        String savedValue = props.getProperty(key, defaultValue);

        PropertyRow row = new PropertyRow(key, labelText, panel);
        JLabel label = new JLabel(labelText);
        row.label = label;
        row.labelConstraints = "align label";
        panel.add(label, row.labelConstraints);

        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        wrapper.setOpaque(false);
        JCheckBox consoleCheck = new JCheckBox("Console");
        JCheckBox fileCheck = new JCheckBox("File");
        consoleCheck.setOpaque(false);
        fileCheck.setOpaque(false);

        consoleCheck.setSelected(savedValue.contains("ConsoleHandler"));
        fileCheck.setSelected(savedValue.contains("FileHandler"));

        wrapper.add(consoleCheck);
        wrapper.add(fileCheck);

        row.input = wrapper;
        row.inputConstraints = "split 2, growx, height pref!";
        panel.add(wrapper, row.inputConstraints);

        valueSuppliers.put(key, () -> {
            List<String> active = new ArrayList<>();
            if (consoleCheck.isSelected())
                active.add("java.util.logging.ConsoleHandler");
            if (fileCheck.isSelected())
                active.add("java.util.logging.FileHandler");
            return String.join(", ", active);
        });

        ActionListener al = e -> {
            if (isProgrammaticChange)
                return;
            updateColor(wrapper, key, defaultValue);
            updateDirtyStatus();
        };
        consoleCheck.addActionListener(al);
        fileCheck.addActionListener(al);

        propertyComponents.put(key, wrapper);
        defaultValues.put(key, defaultValue);

        JButton helpBtn = new HelpButton();
        helpBtn.addActionListener(e -> showHelp(labelText, key));
        row.help = helpBtn;
        row.helpConstraints = "wrap";
        panel.add(helpBtn, row.helpConstraints);

        row.separator = new JSeparator();
        row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";
        panel.add(row.separator, row.separatorConstraints);
        allPropertyRows.add(row);
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
            // Robust Restore: Clear all involved parents
            java.util.Set<JPanel> parents = allPropertyRows.stream()
                    .map(row -> row.originalParent)
                    .collect(java.util.stream.Collectors.toSet());
            parents.forEach(JPanel::removeAll);

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

            if (verticalFiller != null) {
                mainContentPanel.add(verticalFiller, "pushy");
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

            this.activeProfileName = Signum.getActiveLoggingProfile();

            Path loggingConfPath = PathUtils.resolvePath(confFolder).resolve(Signum.NODE_LOGGING_SUBFOLDER);
            String baseFileName = Signum.LOGGING_PROPERTIES_NAME + ".properties";
            ConfigurationUtils
                    .fetchProfileNames(loggingConfPath, Signum.DEFAULT_LOGGING_PROPERTIES_NAME + ".properties")
                    .stream()
                    .filter(name -> !(name + ".properties").equals(baseFileName))
                    .forEach(profileComboBox::addItem);

            // Ensure the base profile is always available in the list
            boolean hasBase = false;
            for (int i = 0; i < profileComboBox.getItemCount(); i++) {
                if (Signum.LOGGING_PROPERTIES_NAME.equals(profileComboBox.getItemAt(i))) {
                    hasBase = true;
                    break;
                }
            }
            if (!hasBase) {
                profileComboBox.insertItemAt(Signum.LOGGING_PROPERTIES_NAME, 0);
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
                boolean isReserved = Signum.DEFAULT_LOGGING_PROPERTIES_NAME.equalsIgnoreCase(text);
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
                        Path targetFile = getPropertiesPath(name);
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

                        // Build a comprehensive set of managed keys.
                        // Managed keys that are NOT in propsToSave will be removed from the file.
                        Set<String> managedKeys = new HashSet<>(staticLoggerKeys);
                        managedKeys.addAll(propertyComponents.keySet());
                        // Include all .level properties currently in the saved reference
                        // to ensure that those removed from the UI are also removed from the file.
                        for (String key : props.stringPropertyNames()) {
                            if (key.endsWith(".level")) {
                                managedKeys.add(key);
                            }
                        }

                        ConfigurationUtils.savePropertiesPreservingFormat(targetFile, propsToSave,
                                managedKeys);

                        isProgrammaticChange = true;
                        try {
                            this.loadedProfileName = name;
                            this.props.clear();
                            this.props.putAll(propsToSave);
                            this.propertiesFile = targetFile;

                            updateLinkCheckbox();
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

    public void loadProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()
                || profileName.equals(loadedProfileName)) {
            return;
        }

        checkUnsavedChangesAndProceed(
                () -> {
                    Path targetFile = getPropertiesPath(profileName);
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
                            updateLinkCheckbox();
                            updateProfileComboBoxColor();
                        } catch (Exception e) {
                            JOptionPane.showMessageDialog(this, "Error loading profile file: " + e.getMessage(),
                                    "Error",
                                    JOptionPane.ERROR_MESSAGE);
                            // Revert to headless fallback
                            isProgrammaticChange = true;
                            profileComboBox.setSelectedItem(Signum.NODE_LOGGING_SUBFOLDER);
                            loadProfile(Signum.NODE_LOGGING_SUBFOLDER + "-default");
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

        JLabel reportLabel = new JLabel(report);
        JScrollPane scrollPane = new JScrollPane(reportLabel);
        scrollPane.setPreferredSize(new Dimension(500, 250));

        Object[] message = {
                "You have unsaved changes in profile '" + loadedProfileName + "'.",
                scrollPane,
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

        // 1. Check existing components (updates and additions)
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String savedInFile = props.getProperty(key);

            String effectiveSaved = normalizeValue(key,
                    (savedInFile != null) ? savedInFile : defaultValues.getOrDefault(key, ""));
            String newVal = normalizeValue(key, getComponentValue(comp, key));

            boolean isNew = (savedInFile == null && dynamicLoggerKeys.contains(key));
            boolean isModified = !newVal.equals(effectiveSaved);

            if ((isNew && !newVal.isEmpty()) || isModified) {
                changesFound = true;
                PropertyRow row = allPropertyRows.stream()
                        .filter(r -> key.equals(r.propertyKey))
                        .findFirst()
                        .orElse(null);
                String label = row != null ? row.labelText : key;

                if (isNew) {
                    report.append("<li>[Added] ").append(label).append(": ").append(newVal).append("</li>");
                } else {
                    report.append("<li>").append(label).append(": '")
                            .append(effectiveSaved.isEmpty() ? "<i>none</i>" : effectiveSaved).append("' &rarr; '")
                            .append(newVal.isEmpty() ? "<i>none</i>" : newVal).append("'</li>");
                }
            }
        }

        // 2. Check for deletions (present in file but missing in UI)
        for (String key : props.stringPropertyNames()) {
            if (key.endsWith(".level") && !propertyComponents.containsKey(key)) {
                changesFound = true;
                String label = key.endsWith(".level") ? key.substring(0, key.length() - 6) : key;
                report.append("<li>[Deleted] ").append(label).append("</li>");
            }
        }

        report.append("</ul></html>");
        return changesFound ? report.toString() : null;
    }

    /**
     * Normalizes property values for stable comparison.
     * For the 'handlers' key, it splits, trims, sorts, and joins the values.
     * This prevents false "unsaved changes" detection when the order in the file
     * differs from the UI.
     */
    private String normalizeValue(String key, String value) {
        if (value == null)
            return "";
        if ("handlers".equals(key)) {
            return java.util.Arrays.stream(value.split("[,\\s;]+"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(", "));
        }
        return value.trim();
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

            Path targetFile = getPropertiesPath(loadedProfileName);
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
            if (name == null || name.trim().isEmpty()
                    || Signum.DEFAULT_LOGGING_PROPERTIES_NAME.equalsIgnoreCase(name.trim()))
                return;

            Path targetFile = getPropertiesPath(name);
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
                updateLinkCheckbox();
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

    void updateLinkCheckbox() {
        if (linkToNodeCheck == null || activeNodeProfileSupplier == null || linkedProfileSupplier == null)
            return;
        String activeNode = activeNodeProfileSupplier.get();
        linkToNodeCheck.setText("Link to Node: " + (activeNode != null ? activeNode : "None"));
        String linked = linkedProfileSupplier.get();
        linkToNodeCheck.setSelected(loadedProfileName != null && loadedProfileName.equals(linked));
    }

    private void updateProfileButtonStates() {
        String selected = (String) profileComboBox.getSelectedItem();
        boolean isReadOnly = Signum.LOGGING_PROPERTIES_NAME.equals(selected)
                || Signum.DEFAULT_LOGGING_PROPERTIES_NAME.equals(selected);
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
                || Signum.DEFAULT_LOGGING_PROPERTIES_NAME.equalsIgnoreCase(newProfileName.trim())) {
            return; // User cancelled or entered the same name
        }

        try {
            Path oldFile = getPropertiesPath(oldProfileName);
            Path newFile = getPropertiesPath(newProfileName);

            if (ConfigurationUtils.confirmAndRenameProfile(this, oldFile, newFile, oldProfileName, newProfileName)) {
                refreshProfileList();
                profileComboBox.setSelectedItem(newProfileName);
                if (oldProfileName.equals(loadedProfileName)) {
                    this.loadedProfileName = newProfileName;
                    this.propertiesFile = newFile;
                }
                if (oldProfileName.equals(activeProfileName)) {
                    ConfigurationUtils.updateAppliedProfile(ConfigurationUtils
                            .getProfileMetadataPath(confFolder, Signum.NODE_LOGGING_SUBFOLDER),
                            newProfileName);
                    activeProfileName = newProfileName;
                }
                updateProfileComboBoxColor();
                updateLinkCheckbox();
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
        if (Signum.DEFAULT_LOGGING_PROPERTIES_NAME.equals(profileName)) {
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
            Path file = getPropertiesPath(profileName);
            if (Files.exists(file)) {
                Files.delete(file);
                refreshProfileList();
                profileComboBox.setSelectedItem(Signum.LOGGING_PROPERTIES_NAME);
                loadProfile(Signum.LOGGING_PROPERTIES_NAME);
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
        // Replicate exactly what the node is running with
        LoggerProfile effective = ConfigurationUtils.loadEffectiveLoggerProfile(confFolder, runningProfileName);
        appliedProps.clear();
        appliedProps.putAll(effective.getProperties());
        refreshUIColors();
    }

    private boolean hasUnsavedChanges() {
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String savedInFile = props.getProperty(key);

            String effectiveSaved = normalizeValue(key,
                    (savedInFile != null) ? savedInFile : defaultValues.getOrDefault(key, ""));
            String newVal = normalizeValue(key, getComponentValue(comp, key));

            if (savedInFile == null && dynamicLoggerKeys.contains(key) && !newVal.isEmpty()) {
                return true;
            }

            if (!newVal.equals(effectiveSaved)) {
                return true;
            }
        }

        // Also check if any dynamic loggers that were in the saved profile are now
        // missing from the UI
        for (String key : props.stringPropertyNames()) {
            if (key.endsWith(".level") && !propertyComponents.containsKey(key)) {
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
            ConfigurationUtils.updateAppliedProfile(ConfigurationUtils
                    .getProfileMetadataPath(confFolder, Signum.NODE_LOGGING_SUBFOLDER),
                    selected);
            this.activeProfileName = selected;
            updateProfileComboBoxColor();
            if (choice == 0 && restartAction != null) {
                restartAction.run();
            }
        }
    }

    private Path getProfileMetadataPath() {
        return PathUtils.resolvePath(confFolder).resolve(Signum.NODE_LOGGING_SUBFOLDER)
                .resolve("profile.json");
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
            if (val == null)
                continue;

            if (dynamicLoggerKeys.contains(key)) {
                // Dynamic loggers are always saved if they exist in the UI list,
                // as their presence in the file is an explicit override.
                props.setProperty(key, val);
            } else {
                // Static properties are only saved if they differ from the system default.
                String def = defaultValues.get(key);
                if (!val.trim().equals(def != null ? def.trim() : "")) {
                    props.setProperty(key, val);
                }
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

    private String getComponentValue(JComponent comp, String propName) {
        if (comp instanceof JComboBox) {
            Object item = ((JComboBox<?>) comp).getSelectedItem();
            return item != null ? item.toString() : "";
        } else if (comp instanceof JTextComponent) {
            return ((JTextComponent) comp).getText();
        } else if (comp instanceof JPanel && valueSuppliers.containsKey(propName)) {
            // Handle custom value supplier for Handlers panel
            return valueSuppliers.get(propName).get();
        }
        return "";
    }

    private void updateUIFromProperties(Properties loadedProps) {
        // Identify dynamic loggers (anything .level not in static list)
        java.util.Set<String> foundDynamic = new java.util.LinkedHashSet<>();
        for (String key : loadedProps.stringPropertyNames()) {
            if (key.endsWith(".level") && !staticLoggerKeys.contains(key) && !key.startsWith("java.util.logging.")) {
                foundDynamic.add(key);
            }
        }

        if (!foundDynamic.equals(dynamicLoggerKeys)) {
            dynamicLoggerKeys.clear();
            dynamicLoggerKeys.addAll(foundDynamic);
            refreshDynamicLoggersUI();
        }

        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String def = defaultValues.get(key);
            String val = loadedProps.getProperty(key, def);

            if (comp instanceof JComboBox) {
                ((JComboBox<?>) comp).setSelectedItem(val);
            } else if (comp instanceof javax.swing.text.JTextComponent) {
                ((javax.swing.text.JTextComponent) comp).setText(val);
            } else if (key.equals("handlers") && comp instanceof JPanel) {
                for (Component c : ((JPanel) comp).getComponents()) {
                    if (c instanceof JCheckBox) {
                        JCheckBox cb = (JCheckBox) c;
                        if (cb.getText().equals("Console")) {
                            cb.setSelected(val.contains("ConsoleHandler"));
                        } else if (cb.getText().equals("File")) {
                            cb.setSelected(val.contains("FileHandler"));
                        }
                    }
                }
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
                    if (isSelected)
                        return c;

                    String savedVal = props.getProperty(propertyKey, defaultValue).trim();
                    String applied = appliedProps.getProperty(propertyKey, defaultValue).trim();
                    String valStr = (value != null) ? value.toString().trim() : "";

                    if (valStr.equals(applied)) {
                        c.setForeground(GuiColors.getApplied());
                    } else if (valStr.equals(savedVal)) {
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

    private String[] getFilteredCommonLoggers() {
        java.util.List<String> filtered = new java.util.ArrayList<>();
        for (String name : COMMON_LOGGERS) {
            String key = name + ".level";
            if (!staticLoggerKeys.contains(key) && !dynamicLoggerKeys.contains(key)) {
                filtered.add(name);
            }
        }
        return filtered.toArray(new String[0]);
    }

    private void addLoggerCreationGui(JPanel panel) {
        JPanel addPanel = new JPanel(new MigLayout("insets 0, gap 5, fillx", "[grow][pref!][pref!][pref!]", "[]"));
        addPanel.setOpaque(false);

        addLoggerClassCombo = new JComboBox<>(getFilteredCommonLoggers());
        addLoggerClassCombo.setEditable(true);
        addLoggerClassCombo.putClientProperty("JTextField.placeholderText", "Class or Package name...");
        ConfigurationUtils.styleInputComponent(addLoggerClassCombo);
        ConfigurationUtils.fixComponentSize(addLoggerClassCombo);

        JComboBox<String> levelCombo = new JComboBox<>(LOG_LEVELS);
        levelCombo.setSelectedItem("INFO");
        ConfigurationUtils.fixComponentSize(levelCombo);

        JButton addBtn = new JButton("Add");
        ConfigurationUtils.fixComponentSize(addBtn);

        addBtn.addActionListener(e -> {
            String className = (String) addLoggerClassCombo.getSelectedItem();
            if (className == null || className.trim().isEmpty())
                return;

            String key = className.trim() + ".level";
            if (dynamicLoggerKeys.contains(key) || staticLoggerKeys.contains(key)) {
                JOptionPane.showMessageDialog(this, "Logger configuration for '" + className + "' already exists.",
                        "Info", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            String level = (String) levelCombo.getSelectedItem();
            dynamicLoggerKeys.add(key);
            refreshDynamicLoggersUI();
            updateDirtyStatus();
            addLoggerClassCombo.setSelectedItem("");
        });

        addPanel.add(addLoggerClassCombo, "growx");
        addPanel.add(new JLabel("Level:"));
        addPanel.add(levelCombo);
        addPanel.add(addBtn);

        PropertyRow row = new PropertyRow("logger-creation-gui", "Add Logger", panel);
        row.label = new JLabel("Add Logger:");
        row.labelConstraints = "align label";
        row.input = addPanel;
        row.inputConstraints = "split 2, growx, height pref!";
        row.help = new HelpButton();
        row.help.setToolTipText(
                "Add a custom logging level for a specific package or class (e.g. 'brs.http' or 'brs.Signum')");
        row.helpConstraints = "wrap";
        row.separator = new JSeparator();
        row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";

        allPropertyRows.add(row);
        panel.add(row.label, row.labelConstraints);
        panel.add(row.input, row.inputConstraints);
        panel.add(row.help, row.helpConstraints);
        panel.add(row.separator, row.separatorConstraints);
    }

    private void refreshDynamicLoggersUI() {
        if (mainContentPanel == null)
            return;

        // Identify and remove all current dynamic rows from master list and trackers
        Set<String> trackedDynamicKeys = new HashSet<>(propertyComponents.keySet());
        trackedDynamicKeys.removeAll(staticLoggerKeys);

        allPropertyRows.removeIf(row -> row.propertyKey != null && trackedDynamicKeys.contains(row.propertyKey));
        for (String key : trackedDynamicKeys) {
            propertyComponents.remove(key);
            valueSuppliers.remove(key);
            defaultValues.remove(key);
        }

        // Re-add dynamic loggers to allPropertyRows before the creation GUI
        int insertionIndex = -1;
        for (int i = 0; i < allPropertyRows.size(); i++) {
            if ("logger-creation-gui".equals(allPropertyRows.get(i).propertyKey)) {
                insertionIndex = i;
                break;
            }
        }

        List<PropertyRow> dynamicRows = new ArrayList<>();
        for (String key : dynamicLoggerKeys) {
            String labelText = key.endsWith(".level") ? key.substring(0, key.length() - 6) : key;
            dynamicRows.add(createDynamicPropertyRow(labelText, key, "INFO"));
        }

        if (insertionIndex != -1) {
            allPropertyRows.addAll(insertionIndex, dynamicRows);
        } else {
            allPropertyRows.addAll(dynamicRows);
        }

        // Update the "Add Logger" dropdown model to hide already added items
        if (addLoggerClassCombo != null) {
            String currentSelection = (String) addLoggerClassCombo.getSelectedItem();
            addLoggerClassCombo.setModel(new DefaultComboBoxModel<>(getFilteredCommonLoggers()));
            addLoggerClassCombo.setSelectedItem(currentSelection);
        }

        filterProperties(null); // Triggers re-rendering of the panel
    }

    private PropertyRow createDynamicPropertyRow(String labelText, String propertyKey, String defaultValue) {
        PropertyRow row = new PropertyRow(propertyKey, labelText, mainContentPanel);
        row.label = new JLabel(labelText);
        row.labelConstraints = "align label";

        JComboBox<String> combo = new JComboBox<>(LOG_LEVELS);
        combo.setSelectedItem(props.getProperty(propertyKey, defaultValue));
        ConfigurationUtils.fixComponentSize(combo);
        valueSuppliers.put(propertyKey, () -> (String) combo.getSelectedItem());
        propertyComponents.put(propertyKey, combo);
        defaultValues.put(propertyKey, defaultValue);

        combo.addActionListener(e -> {
            if (!isProgrammaticChange) {
                updateColor(combo, propertyKey, defaultValue);
                updateDirtyStatus();
            }
        });
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (isSelected)
                    return c;

                String savedVal = props.getProperty(propertyKey, defaultValue).trim();
                String applied = appliedProps.getProperty(propertyKey, defaultValue).trim();
                String valStr = (value != null) ? value.toString().trim() : "";

                if (valStr.equals(applied)) {
                    c.setForeground(GuiColors.getApplied());
                } else if (valStr.equals(savedVal)) {
                    c.setForeground(GuiColors.getSaved());
                } else {
                    c.setForeground(GuiColors.getUnsaved());
                }
                return c;
            }
        });

        JPanel wrapper = new JPanel(new BorderLayout(5, 0));
        wrapper.setOpaque(false);
        wrapper.add(combo, BorderLayout.CENTER);

        // Create a panel for the action buttons (refresh and delete)
        JPanel actionButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actionButtonPanel.setOpaque(false);

        // Refresh button
        JButton refreshBtn = new JButton(
                IconFontSwing.buildIcon(FontAwesome.REFRESH, GuiConstants.getHelpIconSize(),
                        GuiColors.getApplied()));
        refreshBtn.setToolTipText("Update value in profile immediately");
        refreshBtn.setContentAreaFilled(false);
        refreshBtn.setBorderPainted(false);
        refreshBtn.setFocusPainted(false);
        refreshBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        refreshBtn.addActionListener(e -> {
            String newVal = (String) combo.getSelectedItem();
            if (newVal == null)
                return;
            try {
                props.setProperty(propertyKey, newVal);
                Set<String> managedKeys = new HashSet<>(staticLoggerKeys);
                managedKeys.addAll(propertyComponents.keySet());
                for (String k : props.stringPropertyNames()) {
                    if (k.endsWith(".level"))
                        managedKeys.add(k);
                }
                ConfigurationUtils.savePropertiesPreservingFormat(propertiesFile, props, managedKeys);
                updateColor(combo, propertyKey, defaultValue);
                updateDirtyStatus();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Failed to update configuration file: " + ex.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
        actionButtonPanel.add(refreshBtn);

        JButton delBtn = new JButton(
                IconFontSwing.buildIcon(FontAwesome.TRASH, GuiConstants.getHelpIconSize(), GuiColors.getContrastRed()));
        delBtn.setContentAreaFilled(false);
        delBtn.setBorderPainted(false);
        delBtn.setFocusPainted(false);
        delBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        delBtn.addActionListener(e -> {
            dynamicLoggerKeys.remove(propertyKey);
            refreshDynamicLoggersUI();
            updateDirtyStatus();
        });
        actionButtonPanel.add(delBtn);

        wrapper.add(actionButtonPanel, BorderLayout.EAST);

        row.input = wrapper;
        row.inputConstraints = "split 2, growx, height pref!";
        row.help = new HelpButton();
        row.help.addActionListener(e -> showHelp(labelText, propertyKey));
        row.helpConstraints = "wrap";
        row.separator = new JSeparator();
        row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";
        updateColor(combo, propertyKey, defaultValue);
        return row;
    }

    private void updateColor(JComponent comp, String propName, String defaultValue) {
        String savedInFile = props.getProperty(propName);

        String value = normalizeValue(propName, getComponentValue(comp, propName));
        String effectiveSaved = normalizeValue(propName, (savedInFile != null) ? savedInFile : defaultValue);
        String applied = normalizeValue(propName, appliedProps.getProperty(propName, defaultValue));

        boolean isNewDynamic = dynamicLoggerKeys.contains(propName) && savedInFile == null;
        boolean isModified = !value.equals(effectiveSaved);

        if (isNewDynamic && value.isEmpty()) {
            isNewDynamic = false; // Ne jelöljük sárgának az éppen csak hozzáadott, de még üres loggert
        }

        Color color;
        if (value.equals(applied)) {
            color = GuiColors.getApplied();
        } else if (!isNewDynamic && !isModified) {
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
            boolean isDirty = isNewDynamic || isModified;
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
                        + "</ul>");
        helpTexts.put("brs.level",
                "Sets the logging level specifically for the Signum Node application code (brs.* packages)."
                        + "<br>This is the primary setting to control how much information you see about block processing, P2P networking, and wallet operations.");
        helpTexts.put("handlers",
                "Defines where the log messages are sent."
                        + "<ul>"
                        + "<li><b>Console:</b> Messages are displayed in the GUI console and the terminal.</li>"
                        + "<li><b>File:</b> Messages are saved to the log file defined in 'Log File Pattern'.</li>"
                        + "</ul>");
        helpTexts.put("sun.rmi.level", "Level for RMI (Remote Method Invocation) system logs.");
        helpTexts.put("com.zaxxer.hikari.level", "Level for the HikariCP database connection pool logs.");
        helpTexts.put("org.eclipse.jetty.level", "Level for the internal Jetty web server logs.");
        helpTexts.put("brs.db.store.DerivedTableManager.level",
                "Level for internal database derived table maintenance logs.");
        helpTexts.put("org.jooq.Constants.level", "Level for the JOOQ database abstraction library.");
        helpTexts.put("javax.servlet.level", "Level for internal Java Servlet API logs.");

        helpTexts.put("java.util.logging.ConsoleHandler.level",
                "Sets the minimum logging level for messages sent to the <b>terminal / command prompt</b> (System.err)."
                        + "<br><br>Only messages with this level or higher will be visible in the terminal window. "
                        + "Selecting <b>ALL</b> ensures that the terminal output remains fully synchronized with the internal GUI console, "
                        + "forwarding all log entries generated by the application without additional filtering."
                        + "<br><br><b>Available Levels:</b>"
                        + "<ul>"
                        + "<li><b>INFO:</b> General operational information (default).</li>"
                        + "<li><b>CONFIG:</b> Static configuration messages.</li>"
                        + "<li><b>FINE:</b> Detailed tracing information.</li>"
                        + "<li><b>FINER:</b> More detailed tracing.</li>"
                        + "<li><b>FINEST:</b> Highly detailed tracing for debugging.</li>"
                        + "<li><b>ALL:</b> Log all messages.</li>"
                        + "<li><b>OFF:</b> Turn off logging.</li>"
                        + "</ul>");
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

    private Path getPropertiesPath(String profileName) {
        String fileName = profileName + ".properties";
        return ConfigurationUtils.resolveProfilePath(confFolder, Signum.NODE_LOGGING_SUBFOLDER, fileName);
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