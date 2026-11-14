package application.module.database.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.database.gui.DatabaseConfigurationPanel.PropertyRow;
import application.module.database.profile.SQLiteProfile;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.Signum;
import application.module.node.profile.SqliteDatabasePaths;
import application.module.node.gui.wizard.SqliteDatabaseProfileWizard;
import application.utils.gui.CheckboxGroupPanel;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiUtils;
import application.utils.gui.HelpButton;
import application.utils.gui.LeftFlowLayout;
import application.utils.gui.NewProfileChoiceDialog;
import application.utils.gui.ResponsiveToolbarScrollPane;
import application.utils.io.PathUtils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jiconfont.icons.font_awesome.FontAwesome;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import application.module.node.NodeModule;

/**
 * SQLite specific configuration and profile management.
 */
public class SQLiteConfigurationPanel extends JPanel implements DatabaseEnginePanel {

    private static final Logger logger = LoggerFactory.getLogger(SQLiteConfigurationPanel.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final String[] STORAGE_TYPES = { "file:", "memory:" };
    private String confFolder;
    private Runnable restartAction;
    private Runnable backAction;

    private SQLiteProfile currentProfile;
    private GlobalSettings globalSettings = new GlobalSettings();
    private JsonObject appliedProfileSettings = new JsonObject();
    private final Map<String, Supplier<String>> valueSuppliers = new HashMap<>();
    private final Map<String, JComponent> propertyComponents = new HashMap<>();
    private final List<PropertyRow> allPropertyRows = new ArrayList<>();
    private List<PropertyRow> lastFilteredRows;
    private boolean searchViewActive;

    private String loadedProfileName;
    private String runningProfileName = "";
    private String activeProfileName = "";

    private JComboBox<String> profileComboBox;
    private JButton newProfileBtn, renameProfileBtn, deleteProfileBtn, reloadProfileBtn, refreshProfilesBtn;
    private JButton saveProfileBtn;
    private JLabel pathLabel;
    private JPanel searchResultsPanel;
    private JPanel settingsContentPanel;
    private JTextField searchField;
    private CheckboxGroupPanel showValuesPanel;
    private JCheckBox showUnsavedBox;
    private JCheckBox showUnsavedAppliedBox;
    private JCheckBox showSavedBox;
    private JCheckBox showSavedAppliedBox;
    private JComboBox<String> storageTypeCombo;
    private DatabaseFileListEditor databaseFileEditor;
    private Path databaseDirectory;
    private String databaseUrlSuffix = "";
    private CardLayout contentCardLayout;
    private JPanel contentContainer;
    private JTabbedPane sqliteTabs;
    private SQLiteDatabaseAssignmentPanel assignmentsPanel;
    private boolean isInitialized = false;

    private final Icon checkIcon = DatabaseActionIcon.VALIDATION_SUCCESS.icon(GuiConstants.getHelpIconSize());

    public SQLiteConfigurationPanel() {
        super(new BorderLayout());

        this.confFolder = Signum.CONF_FOLDER;

        String lastProfile = ConfigurationUtils.loadAppliedProfile(
                ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.DATABASE_SUBFOLDER));
        if (lastProfile != null && lastProfile.startsWith("SQLite:")) {
            this.runningProfileName = lastProfile.split(":")[1];
            this.activeProfileName = this.runningProfileName;
        }

        initUI();
        this.isInitialized = true;
        refreshProfileList();
    }

    private void initUI() {
        JPanel profileEditor = new JPanel(new BorderLayout());

        // --- Profile action toolbar (the node/profile/configuration pattern) ---
        // Icon-only action buttons on the left, the profile selector at the row's
        // RIGHT end (no "Profile:" label) — the same geometry the module logging
        // panel's profile row uses.
        JPanel toolbarRow = ConfigurationUtils.createProfileToolbarRow();

        newProfileBtn = new JButton();
        newProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip(
                "New Profile<br><br>Create a SQLite profile using the setup wizard or as an empty profile."));
        newProfileBtn.addActionListener(e -> createNewProfile());
        toolbarRow.add(newProfileBtn);

        renameProfileBtn = new JButton();
        renameProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip("Rename Profile"));
        renameProfileBtn.addActionListener(e -> renameProfile((String) profileComboBox.getSelectedItem()));
        toolbarRow.add(renameProfileBtn);

        saveProfileBtn = new JButton();
        saveProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip(
                "Save Profile<br><br>Save the current SQLite profile settings."));
        saveProfileBtn.addActionListener(e -> saveConfigurationToProfile());
        toolbarRow.add(saveProfileBtn);

        reloadProfileBtn = new JButton();
        reloadProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip("Reload Profile"));
        reloadProfileBtn.addActionListener(e -> reloadProfile());
        toolbarRow.add(reloadProfileBtn);

        refreshProfilesBtn = new JButton();
        refreshProfilesBtn.setToolTipText(ConfigurationUtils.profileTooltip("Refresh Profiles"));
        refreshProfilesBtn.addActionListener(e -> refreshProfileList());
        toolbarRow.add(refreshProfilesBtn);

        deleteProfileBtn = new JButton();
        deleteProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip("Delete Profile"));
        deleteProfileBtn.addActionListener(e -> deleteProfile((String) profileComboBox.getSelectedItem()));
        toolbarRow.add(deleteProfileBtn);

        // The profile selector at the row's RIGHT end (after the buttons).
        profileComboBox = new JComboBox<>();
        profileComboBox.setPrototypeDisplayValue("XXXXXXXXXXXXXXXXXXXX");
        profileComboBox.setRenderer(
                ConfigurationUtils.createProfileComboBoxRenderer(() -> runningProfileName, () -> activeProfileName));
        profileComboBox.addActionListener(e -> {
            if (isInitialized)
                loadProfileInternal((String) profileComboBox.getSelectedItem());
        });
        toolbarRow.add(profileComboBox);

        // One glyph per action, sized / spaced / wrapped exactly like the module
        // logging panel's profile toolbar (the shared profile-action icon catalog).
        ConfigurationUtils.configureProfileToolbar(newProfileBtn, saveProfileBtn, null,
                renameProfileBtn, deleteProfileBtn, reloadProfileBtn, refreshProfilesBtn, null, null, null);

        JScrollPane profileScroll = ConfigurationUtils.wrapProfileToolbarRow(toolbarRow);

        // --- Search row: [Show values filter] [search box] (the logging pattern) ---
        searchField = ConfigurationUtils.createSearchField("Search property, label or value...");
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
        JPanel searchBox = new JPanel(new BorderLayout());
        searchBox.setOpaque(false);
        searchBox.setBorder(new TitledBorder("Search"));
        searchBox.add(searchField, BorderLayout.CENTER);

        showValuesPanel = new CheckboxGroupPanel("Show values");
        showValuesPanel.setChangeListener(button -> filterProperties(searchField.getText()));
        showUnsavedBox = showValuesPanel.addCheckbox("Unsaved", GuiColors.getUnsaved(), true);
        showUnsavedAppliedBox = showValuesPanel.addCheckbox("Unsaved Applied", GuiColors.getUnsavedApplied(), true);
        showSavedBox = showValuesPanel.addCheckbox("Saved", GuiColors.getSaved(), true);
        showSavedAppliedBox = showValuesPanel.addCheckbox("Saved Applied", GuiColors.getApplied(), true);

        // The same responsive toolbar scroll wrapper the logging search row uses:
        // a left-flow strip of height-unified boxes, 10px left / 5px right margin
        // and a 4px top/bottom border (a horizontal scrollbar appears on narrow
        // windows instead of the row wrapping).
        JPanel searchStrip = new JPanel(new LeftFlowLayout(ConfigurationUtils.INPUT_ACTION_GAP));
        searchStrip.setOpaque(false);
        searchStrip.add(showValuesPanel);
        searchStrip.add(searchBox);
        ConfigurationUtils.unifyTitledBoxHeights(showValuesPanel, searchBox);
        JScrollPane searchRowScroll = new ResponsiveToolbarScrollPane(searchStrip, new Insets(0, 10, 0, 5), false);
        searchRowScroll.setBorder(new EmptyBorder(4, 0, 4, 0));
        JPanel searchRow = new JPanel(new BorderLayout());
        searchRow.setOpaque(false);
        searchRow.add(searchRowScroll, BorderLayout.CENTER);

        // The top area: the toolbar NORTH and the search row CENTER, with the
        // module logging panel's 5px vgap between them.
        JPanel topArea = new JPanel(new BorderLayout(0, 5));
        topArea.setOpaque(false);
        topArea.add(profileScroll, BorderLayout.NORTH);
        topArea.add(searchRow, BorderLayout.CENTER);
        profileEditor.add(topArea, BorderLayout.NORTH);

        // --- Content ---
        contentCardLayout = new CardLayout();
        contentContainer = new JPanel(contentCardLayout);

        settingsContentPanel = new JPanel(new MigLayout("fillx, insets 10, gap 10", "[][grow]"));
        JScrollPane settingsScroll = new JScrollPane(settingsContentPanel);
        settingsScroll.setBorder(BorderFactory.createEmptyBorder());
        contentContainer.add(settingsScroll, "SETTINGS");

        searchResultsPanel = new JPanel(new MigLayout("fillx, insets 10, gap 5", "[][grow]"));
        JScrollPane searchScroll = new JScrollPane(searchResultsPanel);
        searchScroll.setBorder(BorderFactory.createEmptyBorder());
        contentContainer.add(searchScroll, "SEARCH");

        profileEditor.add(contentContainer, BorderLayout.CENTER);

        // --- Bottom ---
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(new EmptyBorder(5, 10, 5, 5));
        pathLabel = new JLabel("Profile Path: N/A");
        pathLabel.setForeground(GuiColors.getFaintText());
        bottom.add(pathLabel, BorderLayout.WEST);
        profileEditor.add(bottom, BorderLayout.SOUTH);

        sqliteTabs = new JTabbedPane();
        GuiUtils.applyDefaultTabLayoutPolicy(sqliteTabs);
        sqliteTabs.addTab("Profiles", profileEditor);
        assignmentsPanel = new SQLiteDatabaseAssignmentPanel(confFolder);
        sqliteTabs.addTab("Assignments", assignmentsPanel);
        sqliteTabs.addChangeListener(event -> {
            if (sqliteTabs.getSelectedComponent() == assignmentsPanel) {
                assignmentsPanel.refreshIfSafe();
            }
        });
        add(sqliteTabs, BorderLayout.CENTER);

        updateProfileButtonsUI();
    }

    private void loadProfileInternal(String name) {
        if (name == null || name.isEmpty())
            return;
        this.loadedProfileName = name;
        this.activeProfileName = name;

        Path profileDir = getProfilePath(name);
        Path jsonFile = profileDir.resolve("profile.json");

        if (Files.exists(jsonFile)) {
            try (var reader = Files.newBufferedReader(jsonFile)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                this.currentProfile = new SQLiteProfile(name, json);
            } catch (Exception e) {
                logger.error("Failed to load SQLite profile '{}'", name, e);
                JOptionPane.showMessageDialog(this,
                        "Failed to load SQLite profile '" + name + "': " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        } else {
            this.currentProfile = new SQLiteProfile(name);
        }

        refreshSettingsUI();
        updateUIFromData();
        updateProfileButtonsUI();
    }

    private void refreshSettingsUI() {
        searchResultsPanel.removeAll();
        settingsContentPanel.removeAll();
        allPropertyRows.clear();
        propertyComponents.clear();
        valueSuppliers.clear();

        addSectionHeader(settingsContentPanel, "Database File Configuration", true);

        // Parse existing URL: jdbc:sqlite:file:../database/SQLite/{profile}/signum.sqlite.db
        String fullUrl = currentProfile.getDbUrl();
        String content = fullUrl.startsWith("jdbc:sqlite:") ? fullUrl.substring("jdbc:sqlite:".length()) : fullUrl;
        int queryIndex = content.indexOf('?');
        databaseUrlSuffix = queryIndex < 0 ? "" : content.substring(queryIndex);
        String pathContent = queryIndex < 0 ? content : content.substring(0, queryIndex);

        String selectedType = STORAGE_TYPES[0];
        String fileName = pathContent;
        for (String type : STORAGE_TYPES) {
            if (pathContent.startsWith(type)) {
                selectedType = type;
                fileName = pathContent.substring(type.length());
                break;
            }
        }

        SqliteDatabasePaths.SqliteUrlParts urlParts = SqliteDatabasePaths.splitUrl(fullUrl);
        databaseDirectory = urlParts != null ? urlParts.dir()
                : SqliteDatabasePaths.profileDir(loadedProfileName);
        if ("file:".equals(selectedType) && urlParts != null) {
            fileName = urlParts.fileName();
        }

        storageTypeCombo = new JComboBox<>(STORAGE_TYPES);
        storageTypeCombo.setSelectedItem(selectedType);
        ConfigurationUtils.styleInputComponent(storageTypeCombo);
        ConfigurationUtils.fixComponentSize(storageTypeCombo);
        // The storage type is part of the composed DB.URL (the "file:" / "jdbc:"
        // prefix), so it is shown as a configuration row like the others: a
        // labeled row with a help ("?") icon and a trailing row separator — the
        // SAME row layout the node configuration panel and the other SQLite
        // properties use. (It is NOT a tracked property: its state is derived
        // from DB.URL, so updateColor colors it together with the file list.)
        settingsContentPanel.add(new JLabel("Storage Type:"), "align label");
        settingsContentPanel.add(storageTypeCombo, "growx, split 2");
        settingsContentPanel.add(new HelpButton(), "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP + ", wrap");
        settingsContentPanel.add(new JSeparator(), ConfigurationUtils.PROPERTY_SEPARATOR_CONSTRAINTS);

        // The "Database File" field is an EXPANDABLE list (the node configuration
        // list editors' pattern): every database file is a row with a trash icon
        // and a flat "+" adds a new one. The FIRST file is the active database
        // (DB.Url is composed from it); the full list is persisted to DB.Files.
        databaseFileEditor = new DatabaseFileListEditor();
        databaseFileEditor.setCandidates(refreshDatabaseFileCandidates(fileName));
        databaseFileEditor.setItems(loadDatabaseFileList(fileName));
        databaseFileEditor.onChange = () -> propertyChanged(databaseFileEditor, SQLiteProfile.CFG_URL);
        addDatabaseFileProperty();

        JComboBox<String> journalMode = editableCombo(
                new String[] { "DELETE", "TRUNCATE", "PERSIST", "MEMORY", "WAL", "OFF" },
                currentProfile.getConfiguration().getOrDefault(SQLiteProfile.CFG_JOURNAL_MODE, "WAL"));
        addProperty(settingsContentPanel, SQLiteProfile.CFG_JOURNAL_MODE, "SQLite Journal Mode", journalMode,
                () -> comboEditorText(journalMode));

        JComboBox<String> cacheSize = editableCombo(
                new String[] { "-131072", "-65536", "-32768", "-16384", "-8192", "-4096", "0" },
                currentProfile.getConfiguration().getOrDefault(SQLiteProfile.CFG_CACHE_SIZE, "-131072"));
        addProperty(settingsContentPanel, SQLiteProfile.CFG_CACHE_SIZE, "SQLite Cache Size", cacheSize,
                () -> comboEditorText(cacheSize));

        valueSuppliers.put(SQLiteProfile.CFG_URL, this::databaseUrlFromControls);
        valueSuppliers.put(SQLiteProfile.CFG_FILES, this::databaseFilesValue);
        storageTypeCombo.addActionListener(e -> propertyChanged(databaseFileEditor, SQLiteProfile.CFG_URL));

        settingsContentPanel.revalidate();
        settingsContentPanel.repaint();
    }

    private JComboBox<String> editableCombo(String[] options, String selected) {
        JComboBox<String> combo = new JComboBox<>(options);
        combo.setEditable(true);
        ConfigurationUtils.styleComboEditor(combo);
        ConfigurationUtils.fixComponentSize(combo);
        combo.getEditor().setItem(selected);
        return combo;
    }

    /**
     * The {@code *.db} candidate names offered by the database-file list's
     * dropdowns: the files discovered in the profile's database directory, with
     * the conventional {@code signum.sqlite.db} and the active file guaranteed
     * to be offered.
     */
    private List<String> refreshDatabaseFileCandidates(String activeFile) {
        List<String> names = new ArrayList<>(SqliteDatabasePaths.discoverDbFiles(databaseDirectory));
        if (!names.contains("signum.sqlite.db")) {
            names.add("signum.sqlite.db");
        }
        if (activeFile != null && !activeFile.isBlank() && !names.contains(activeFile)) {
            names.add(activeFile);
        }
        return names;
    }

    /**
     * The initial database-file list of the loaded profile: the stored
     * {@code DB.Files} when present, otherwise the single active file parsed
     * from {@code DB.Url}. When no {@code DB.Files} is stored yet the value is
     * also written back into the profile's configuration so the freshly loaded
     * list reads as saved (not unsaved).
     */
    private List<String> loadDatabaseFileList(String activeFile) {
        String stored = currentProfile.getConfiguration().get(SQLiteProfile.CFG_FILES);
        List<String> files = new ArrayList<>();
        if (stored != null) {
            for (String line : stored.split("\\R")) {
                if (!line.trim().isEmpty()) {
                    files.add(line.trim());
                }
            }
        }
        if (files.isEmpty() && activeFile != null && !activeFile.isBlank()) {
            files.add(activeFile);
            currentProfile.getConfiguration().put(SQLiteProfile.CFG_FILES, activeFile);
        }
        return files;
    }

    /** The database-file list's editor value: the current file names, newline-joined. */
    private String databaseFilesValue() {
        return String.join("\n", databaseFileEditor == null ? List.of() : databaseFileEditor.getItems());
    }

    private void addDatabaseFileProperty() {
        PropertyRow row = new PropertyRow(SQLiteProfile.CFG_URL, "Database File", settingsContentPanel);
        JLabel label = new JLabel(row.labelText);

        // The expandable list editor IS the input: every database file a row
        // (combo + trash icon) plus a trailing add row (combo + flat "+") — the
        // node configuration list editors' pattern.
        JPanel inputColumn = new JPanel(new BorderLayout());
        inputColumn.setOpaque(false);
        inputColumn.add(databaseFileEditor, BorderLayout.CENTER);

        propertyComponents.put(SQLiteProfile.CFG_URL, inputColumn);
        // The label is TOP-aligned (aligny top), exactly like the node
        // configuration list editors: the list spans several rows, so the
        // "Database File" label sits on the FIRST row (level with the first
        // file's trash icon) instead of floating in the vertical middle.
        settingsContentPanel.add(label, "align label, aligny top");
        settingsContentPanel.add(inputColumn, "growx, split 2");
        HelpButton help = new HelpButton();
        // The help ("?") sits a uniform INPUT_ACTION_GAP from the list (the SAME
        // gap the node configuration list editors use for their help icon) and
        // is TOP-aligned: the list spans several rows, so the "?" lines up with
        // the FIRST row's trash icon instead of floating in the vertical middle
        // of the list.
        settingsContentPanel.add(help,
                "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP + ", aligny top, wrap");
        // The row separator — the SAME trailing JSeparator the node configuration
        // panel puts between its properties (the shared central constraint).
        row.separator = new JSeparator();
        row.separatorConstraints = ConfigurationUtils.PROPERTY_SEPARATOR_CONSTRAINTS;
        settingsContentPanel.add(row.separator, row.separatorConstraints);
        row.label = label;
        row.input = inputColumn;
        row.help = help;
        row.labelConstraints = "align label, aligny top";
        row.inputConstraints = "growx, split 2";
        row.helpConstraints = "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP + ", aligny top, wrap";
        allPropertyRows.add(row);
    }

    private String comboEditorText(JComboBox<String> combo) {
        Object item = combo.isEditable() ? combo.getEditor().getItem() : combo.getSelectedItem();
        return item == null ? "" : item.toString();
    }

    private String databaseUrlFromControls() {
        String type = (String) storageTypeCombo.getSelectedItem();
        List<String> files = databaseFileEditor == null ? List.of() : databaseFileEditor.getItems();
        String file = files.isEmpty() ? "" : files.get(0);
        if ("file:".equals(type)) {
            return SqliteDatabasePaths.composeUrl(databaseDirectory, file) + databaseUrlSuffix;
        }
        return "jdbc:sqlite:" + type + file + databaseUrlSuffix;
    }

    private void addProperty(JPanel panel, String key, String labelText, JComponent input,
            Supplier<String> valueSupplier) {
        PropertyRow row = new PropertyRow(key, labelText, panel);
        JLabel label = new JLabel(labelText);
        row.label = label;

        propertyComponents.put(key, input);
        valueSuppliers.put(key, valueSupplier);

        panel.add(label, "align label");
        panel.add(input, "growx, split 2");
        HelpButton help = new HelpButton();
        // Uniform INPUT_ACTION_GAP from the input — the SAME gap the node
        // configuration panel uses for its help icons (see INPUT_ACTION_GAP).
        panel.add(help, "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP + ", wrap");
        // The row separator — the SAME trailing JSeparator the node configuration
        // panel puts between its properties (the shared central constraint), so
        // the SQLite rows are visually divided exactly like the node rows.
        row.separator = new JSeparator();
        row.separatorConstraints = ConfigurationUtils.PROPERTY_SEPARATOR_CONSTRAINTS;
        panel.add(row.separator, row.separatorConstraints);

        row.input = input;
        row.help = help;
        row.labelConstraints = "align label";
        row.inputConstraints = "growx, split 2";
        row.helpConstraints = "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP + ", wrap";
        allPropertyRows.add(row);
        if (input instanceof JComboBox<?> combo) {
            combo.addActionListener(e -> propertyChanged(input, key));
            if (combo.isEditable() && combo.getEditor().getEditorComponent() instanceof JTextField field) {
                field.getDocument().addDocumentListener(new DocumentListener() {
                    public void insertUpdate(DocumentEvent e) { propertyChanged(input, key); }
                    public void removeUpdate(DocumentEvent e) { propertyChanged(input, key); }
                    public void changedUpdate(DocumentEvent e) { propertyChanged(input, key); }
                });
            }
        }
    }

    private void propertyChanged(JComponent comp, String key) {
        updateColor(comp, key);
        filterProperties(searchField.getText());
        if (onDirtyStatusChanged != null) {
            onDirtyStatusChanged.run();
        }
    }

    private void updateColor(JComponent comp, String key) {
        Color color = valueColor(key);
        if (SQLiteProfile.CFG_URL.equals(key)) {
            // The database-file list colors every row's editor (the row-level
            // analogue of the single-value inputs' state coloring), and the
            // storage type — which supplies the URL's "file:" / "jdbc:" prefix —
            // is colored from the SAME DB.URL state, so it stays in sync with
            // the list it composes.
            if (databaseFileEditor != null) {
                databaseFileEditor.applyItemForeground(color);
            }
            if (storageTypeCombo != null) {
                storageTypeCombo.setForeground(color);
            }
            return;
        }
        comp.setForeground(color);
        if (comp instanceof JComboBox<?> combo && combo.isEditable()
                && combo.getEditor().getEditorComponent() instanceof JComponent editor) {
            editor.setForeground(color);
        }
    }

    private Color valueColor(String key) {
        return switch (valueStatus(key)) {
            case UNSAVED -> GuiColors.getUnsaved();
            case UNSAVED_APPLIED -> GuiColors.getUnsavedApplied();
            case SAVED -> GuiColors.getSaved();
            case SAVED_APPLIED -> GuiColors.getApplied();
        };
    }

    private ValueStatus valueStatus(String key) {
        String current = valueSuppliers.getOrDefault(key, () -> "").get();
        String saved = currentProfile.getConfiguration().getOrDefault(key, "");
        String applied = appliedProfileSettings.has(key) && appliedProfileSettings.get(key).isJsonPrimitive()
                ? appliedProfileSettings.get(key).getAsString()
                : saved;
        if (current.equals(saved)) {
            return current.equals(applied) ? ValueStatus.SAVED_APPLIED : ValueStatus.SAVED;
        }
        return current.equals(applied) ? ValueStatus.UNSAVED_APPLIED : ValueStatus.UNSAVED;
    }

    private void createNewProfile() {
        NewProfileChoiceDialog.Choice choice = NewProfileChoiceDialog.show(this, true,
                "Launch SQLite Database Wizard",
                "Create a new SQLite database profile or an empty profile. To add a file to an existing profile, "
                        + "use the + button below its Database File field.");
        if (choice == null) {
            return;
        }
        if (choice == NewProfileChoiceDialog.Choice.WIZARD) {
            String created = SqliteDatabaseProfileWizard.showForNewProfile(this);
            if (created != null) {
                refreshProfileList();
                profileComboBox.setSelectedItem(created);
            }
            return;
        }

        String name = (String) JOptionPane.showInputDialog(this,
                "Enter the new SQLite database profile name:", "New Empty Profile",
                JOptionPane.PLAIN_MESSAGE, null, null, "my-profile");
        if (name == null) {
            return;
        }
        name = name.trim();
        if (!DatabaseConfigurationUtils.isValidProfileName(name)) {
            JOptionPane.showMessageDialog(this,
                    "Profile names must start with a letter or underscore and contain only letters, numbers, "
                            + "underscores or hyphens (up to 64 characters).",
                    "Invalid Profile Name", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (Files.exists(getProfilePath(name))) {
            JOptionPane.showMessageDialog(this, "Profile '" + name + "' already exists.",
                    "Profile Exists", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            createEmptyProfile(name);
            refreshProfileList();
            profileComboBox.setSelectedItem(name);
        } catch (IOException | IllegalArgumentException e) {
            logger.error("Failed to create empty SQLite profile '{}'", name, e);
            JOptionPane.showMessageDialog(this,
                    "Failed to create SQLite profile '" + name + "': " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    String createEmptyProfile(String name) throws IOException {
        String normalized = name == null ? "" : name.trim();
        if (!DatabaseConfigurationUtils.isValidProfileName(normalized)) {
            throw new IllegalArgumentException("Invalid SQLite profile name: " + name);
        }
        Path profilePath = getProfilePath(normalized);
        if (Files.exists(profilePath)) {
            throw new IllegalArgumentException("Profile '" + normalized + "' already exists.");
        }
        SQLiteProfile profile = new SQLiteProfile(normalized);
        profile.saveToProfileJson();
        profile.ensureDatabaseDirectory();
        return normalized;
    }

    private void saveProfile() {
        String sel = (String) profileComboBox.getSelectedItem();
        if (sel == null)
            return;
        ConfigurationUtils.updateAppliedProfile(
                ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.DATABASE_SUBFOLDER),
                "SQLite:" + sel);
        this.runningProfileName = sel;
        this.activeProfileName = sel;
        profileComboBox.repaint();
    }

    private void renameProfile(String old) {
        if (old == null)
            return;
        String n = (String) JOptionPane.showInputDialog(this, "Enter new name for profile '" + old + "':",
                "Rename Profile", JOptionPane.PLAIN_MESSAGE, null, null, old);
        if (n == null || n.trim().isEmpty() || n.equals(old))
            return;

        Path oldPath = getProfilePath(old);
        Path newPath = getProfilePath(n);

        if (Files.exists(newPath)) {
            JOptionPane.showMessageDialog(this, "Profile '" + n + "' already exists.", "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        // For SQLite, if this is the running profile, the Node holds a lock on the
        // file.
        boolean isRunning = old.equals(runningProfileName);

        if (isRunning) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "The profile '" + old + "' is currently in use.\n" +
                            "Renaming it will cause the Node to shut down and restart automatically to release file locks.\n\n"
                            +
                            "Do you want to proceed?",
                    "Confirm Node Restart",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION)
                return;
        }

        executeRenameProfileWorker(old, n, oldPath, newPath, isRunning);
    }

    private void executeRenameProfileWorker(String oldName, String newName, Path oldPath, Path newPath,
            boolean isRunning) {
        final JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        final JLabel statusLabel = new JLabel("Preparing...");

        JPanel progressPanel = new JPanel(new MigLayout("fillx, insets 20", "[grow]", "[]10[]"));
        progressPanel.add(statusLabel, "wrap");
        progressPanel.add(progressBar, "growx");

        final JDialog progressDialog = new JDialog((Window) SwingUtilities.getWindowAncestor(this),
                "Renaming Profile", Dialog.ModalityType.APPLICATION_MODAL);
        progressDialog.add(progressPanel);
        progressDialog.pack();
        progressDialog.setLocationRelativeTo(this);

        new SwingWorker<Void, ProgressInfo>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (isRunning) {
                    publish(new ProgressInfo("Stopping node lifecycle...", 20));
                    NodeModule.getInstance().stopAll();
                    Thread.sleep(2000);
                }

                publish(new ProgressInfo("Moving profile folder...", 50));
                Files.move(oldPath, newPath);
                try {
                    SQLiteDatabaseAssignmentPanel.renameAssignments(confFolder, oldName, newName);
                } catch (IOException e) {
                    try {
                        Files.move(newPath, oldPath);
                    } catch (IOException rollbackError) {
                        e.addSuppressed(rollbackError);
                    }
                    throw e;
                }

                publish(new ProgressInfo("Updating metadata...", 70));
                ConfigurationUtils.updateAppliedProfile(
                        ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.DATABASE_SUBFOLDER),
                        "SQLite:" + newName);

                if (oldName.equals(runningProfileName)) {
                    runningProfileName = newName;
                    activeProfileName = newName;
                    if (loadedProfileName != null && loadedProfileName.equals(oldName)) {
                        loadedProfileName = newName;
                    }
                }

                if (isRunning) {
                    publish(new ProgressInfo("Restarting node lifecycle...", 90));
                    NodeModule manager = NodeModule.getInstance();
                    
                    Signum s = manager.get(runningProfileName); if (s != null) s.start();
                }
                return null;
            }

            @Override
            protected void process(List<ProgressInfo> chunks) {
                ProgressInfo info = chunks.get(chunks.size() - 1);
                statusLabel.setText(info.message);
                progressBar.setValue(info.progress);
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                try {
                    get();
                    refreshProfileList();
                    profileComboBox.setSelectedItem(newName);
                    updateUIFromData();
                    JOptionPane.showMessageDialog(SQLiteConfigurationPanel.this, "Profile renamed successfully.");
                } catch (Exception e) {
                    logger.error("Rename operation failed", e);
                    JOptionPane.showMessageDialog(SQLiteConfigurationPanel.this,
                            "Error: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
        progressDialog.setVisible(true);
    }

    private static class ProgressInfo {
        final String message;
        final int progress;

        ProgressInfo(String m, int p) {
            this.message = m;
            this.progress = p;
        }
    }

    private void deleteProfile(String n) {
        if (n == null)
            return;
        try {
            List<String> assigned = SQLiteDatabaseAssignmentPanel.assignedNodeProfiles(confFolder, n);
            if (!assigned.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "SQLite profile '" + n + "' is assigned to these node profiles:\n"
                                + String.join(", ", assigned)
                                + "\n\nReassign them before deleting this profile.",
                        "Profile Is Assigned", JOptionPane.WARNING_MESSAGE);
                return;
            }
        } catch (IOException e) {
            logger.error("Failed to check assignments before deleting SQLite profile '{}'", n, e);
            JOptionPane.showMessageDialog(this,
                    "Could not check assignments for SQLite profile '" + n + "': " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Delete profile '" + n + "'?") == JOptionPane.YES_OPTION) {
            try {
                DatabaseConfigurationUtils.deleteDirectoryRecursively(getProfilePath(n));
                refreshProfileList();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Delete failed");
            }
        }
    }

    private void refreshProfileList() {
        profileComboBox.removeAllItems();
        DatabaseConfigurationUtils.getProfileNames(confFolder, "SQLite").forEach(profileComboBox::addItem);
        if (activeProfileName != null)
            profileComboBox.setSelectedItem(activeProfileName);
        if (assignmentsPanel != null) {
            assignmentsPanel.refreshIfSafe();
        }
        updateProfileButtonsUI();
    }

    private void updateProfileButtonsUI() {
        boolean hasSel = profileComboBox.getSelectedItem() != null;
        renameProfileBtn.setEnabled(hasSel);
        deleteProfileBtn.setEnabled(hasSel);
        reloadProfileBtn.setEnabled(hasSel);
        saveProfileBtn.setEnabled(hasSel);
    }

    private void filterProperties(String text) {
        String low = text == null ? "" : text.trim().toLowerCase();
        boolean filterActive = !low.isEmpty() || !showValuesPanel.isAllSelected();
        if (!filterActive) {
            if (searchViewActive) {
                restoreSettingsRows();
                searchResultsPanel.removeAll();
                contentCardLayout.show(contentContainer, "SETTINGS");
                searchViewActive = false;
            }
            lastFilteredRows = null;
            return;
        }

        List<PropertyRow> matchingRows = new ArrayList<>();
        for (PropertyRow row : allPropertyRows) {
            if (row.propertyKey != null && isValueVisible(row.propertyKey)
                    && (low.isEmpty() || row.propertyKey.toLowerCase().contains(low)
                            || row.labelText.toLowerCase().contains(low)
                            || valueSuppliers.getOrDefault(row.propertyKey, () -> "").get()
                                    .toLowerCase().contains(low))) {
                matchingRows.add(row);
            }
        }
        if (!sameRows(matchingRows, lastFilteredRows)) {
            restoreSettingsRows();
            searchResultsPanel.removeAll();
            for (PropertyRow row : matchingRows) {
                row.originalParent.remove(row.label);
                row.originalParent.remove(row.input);
                if (row.help != null) {
                    row.originalParent.remove(row.help);
                }
                if (row.separator != null) {
                    row.originalParent.remove(row.separator);
                }
                searchResultsPanel.add(row.label, row.labelConstraints);
                searchResultsPanel.add(row.input, row.inputConstraints);
                if (row.help != null) {
                    searchResultsPanel.add(row.help, row.helpConstraints);
                }
                if (row.separator != null) {
                    searchResultsPanel.add(row.separator, row.separatorConstraints);
                }
            }
            lastFilteredRows = new ArrayList<>(matchingRows);
        }
        if (!searchViewActive) {
            contentCardLayout.show(contentContainer, "SEARCH");
            searchViewActive = true;
        }
        searchResultsPanel.revalidate();
        searchResultsPanel.repaint();
    }

    private static boolean sameRows(List<PropertyRow> rows, List<PropertyRow> previousRows) {
        if (previousRows == null || rows.size() != previousRows.size()) {
            return false;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) != previousRows.get(i)) {
                return false;
            }
        }
        return true;
    }

    private void restoreSettingsRows() {
        for (PropertyRow row : allPropertyRows) {
            if (row.propertyKey == null || row.input == null) {
                continue;
            }
            if (row.label.getParent() != null) {
                row.label.getParent().remove(row.label);
            }
            if (row.input.getParent() != null) {
                row.input.getParent().remove(row.input);
            }
            if (row.help != null && row.help.getParent() != null) {
                row.help.getParent().remove(row.help);
            }
            if (row.separator != null && row.separator.getParent() != null) {
                row.separator.getParent().remove(row.separator);
            }
        }
        for (PropertyRow row : allPropertyRows) {
            if (row.propertyKey == null || row.input == null) {
                continue;
            }
            row.originalParent.add(row.label, row.labelConstraints);
            row.originalParent.add(row.input, row.inputConstraints);
            if (row.help != null) {
                row.originalParent.add(row.help, row.helpConstraints);
            }
            if (row.separator != null) {
                row.originalParent.add(row.separator, row.separatorConstraints);
            }
        }
        settingsContentPanel.revalidate();
        settingsContentPanel.repaint();
    }

    private boolean isValueVisible(String key) {
        return switch (valueStatus(key)) {
            case UNSAVED -> showUnsavedBox.isSelected();
            case UNSAVED_APPLIED -> showUnsavedAppliedBox.isSelected();
            case SAVED -> showSavedBox.isSelected();
            case SAVED_APPLIED -> showSavedAppliedBox.isSelected();
        };
    }

    private enum ValueStatus {
        UNSAVED,
        UNSAVED_APPLIED,
        SAVED,
        SAVED_APPLIED
    }

    private void addSectionHeader(JPanel panel, String title, boolean isFirst) {
        PropertyRow row = new PropertyRow(null, title, panel);
        JLabel label = new JLabel(title);
        label.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 14f));
        Color separatorColor = GuiColors.getSeparator();
        label.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, separatorColor));
        panel.add(label, (isFirst ? "" : "gaptop 15, ") + "span, growx, wrap, gapbottom 5");
        row.label = label;
        allPropertyRows.add(row);
    }

    // --- DatabaseEnginePanel Implementation ---

    public String getEngineName() {
        return "SQLite";
    }

    public Path getProfilePath(String profileName) {
        return PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR).resolve("SQLite")
                .resolve(profileName);
    }

    public void loadProfile(String profileName, GlobalSettings gs) {
        this.globalSettings = gs;
        loadProfileInternal(profileName);
    }

    private void saveConfigurationToProfile() {
        if (currentProfile == null || loadedProfileName == null) {
            return;
        }
        try {
            syncControlsToProfile();

            // Save profile.json
            currentProfile.saveToProfileJson();

            // Create database folder
            currentProfile.ensureDatabaseDirectory();

            // Materialize any newly added database files on disk only now (on save):
            // adding a file in the editor never touches the filesystem.
            createMissingDatabaseFiles();

            JOptionPane.showMessageDialog(this, "Profile saved and database files created.");
            loadProfileInternal(loadedProfileName);
        } catch (IOException e) {
            logger.error("Failed to save SQLite profile '{}'", loadedProfileName, e);
            JOptionPane.showMessageDialog(this,
                    "Failed to save SQLite profile '" + loadedProfileName + "': " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void syncControlsToProfile() {
        for (String key : List.of(SQLiteProfile.CFG_URL, SQLiteProfile.CFG_FILES,
                SQLiteProfile.CFG_JOURNAL_MODE,
                SQLiteProfile.CFG_CACHE_SIZE)) {
            Supplier<String> supplier = valueSuppliers.get(key);
            if (supplier != null) {
                currentProfile.getConfiguration().put(key, supplier.get());
            }
        }
    }

    /**
     * Creates any of the profile's configured database files that are missing on
     * disk, resolved against the profile's database directory (the per-profile
     * {@code ./database/SQLite/<profile>} convention, via
     * {@link PathUtils#resolvePath(String)}). Only valid {@code *.db} names are
     * created and existing files are left untouched. Called on save so a newly
     * added file is materialized only then — adding a file in the editor never
     * touches the filesystem.
     *
     * @throws IOException if a file (or its parent directory) cannot be created
     */
    private void createMissingDatabaseFiles() throws IOException {
        if (databaseDirectory == null) {
            return;
        }
        List<String> files = databaseFileEditor == null ? List.of() : databaseFileEditor.getItems();
        if (files.isEmpty()) {
            return;
        }
        Path baseDir = PathUtils.resolvePath(databaseDirectory.toString());
        for (String file : files) {
            if (!SqliteDatabasePaths.isDbFile(file)) {
                continue; // Never create a non-.db entry (defense in depth).
            }
            Path filePath = baseDir.resolve(file);
            if (!Files.exists(filePath)) {
                Files.createDirectories(filePath.getParent());
                Files.createFile(filePath);
                logger.info("Created new SQLite database file: {}", filePath);
            }
        }
    }

    public void resetToDefaults(GlobalSettings gs) {
        this.currentProfile = new SQLiteProfile(loadedProfileName);
        refreshSettingsUI();
        updateUIFromData();
    }

    @Override
    public String getUnsavedChangesReport() {
        return hasUnsavedChanges() ? "SQLite changes not saved." : null;
    }

    @Override
    public boolean hasUnsavedChanges() {
        if (currentProfile == null) {
            return false;
        }
        for (String key : List.of(SQLiteProfile.CFG_URL, SQLiteProfile.CFG_FILES,
                SQLiteProfile.CFG_JOURNAL_MODE,
                SQLiteProfile.CFG_CACHE_SIZE)) {
            Supplier<String> supplier = valueSuppliers.get(key);
            if (supplier != null && !supplier.get().equals(currentProfile.getConfiguration().getOrDefault(key, ""))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void updateUIFromData() {
        pathLabel.setText("Profile Path: "
                + (loadedProfileName != null ? getProfilePath(loadedProfileName).toAbsolutePath() : "N/A"));
        for (String key : valueSuppliers.keySet()) {
            JComponent component = propertyComponents.get(key);
            if (component != null) {
                updateColor(component, key);
            }
        }
        filterProperties(searchField == null ? "" : searchField.getText());
    }

    @Override
    public void refreshUIColors() {
    }

    public void setLoadedProfileName(String n) {
        this.loadedProfileName = n;
    }

    public String getLoadedProfileName() {
        return loadedProfileName;
    }

    public void setRunningProfileName(String n) {
        this.runningProfileName = n;
    }

    public String getRunningProfileName() {
        return runningProfileName;
    }

    public void setActiveProfileName(String n) {
        this.activeProfileName = n;
    }

    public String getActiveProfileName() {
        return activeProfileName;
    }

    public JsonObject getCurrentProfileSettings() {
        if (currentProfile == null) {
            return new JsonObject();
        }
        JsonObject settings = currentProfile.toJsonObject();
        valueSuppliers.forEach((key, supplier) -> settings.addProperty(key, supplier.get()));
        return settings;
    }

    @Override
    public void setAppliedProfileSettings(JsonObject s) {
        this.appliedProfileSettings = s == null ? new JsonObject() : s;
        updateUIFromData();
    }

    @Override
    public void setOnDirtyStatusChanged(Runnable l) {
        this.onDirtyStatusChanged = l;
    }

    @Override
    public List<PropertyRow> getAllPropertyRows() {
        return allPropertyRows;
    }

    @Override
    public Map<String, JComponent> getPropertyComponents() {
        return propertyComponents;
    }

    @Override
    public Map<String, Supplier<String>> getValueSuppliers() {
        return valueSuppliers;
    }

    @Override
    public Map<String, String> getHelpTexts() {
        return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getDefaultValues() {
        return Collections.emptyMap();
    }

    @Override
    public void setPathLabel(JLabel l) {
        this.pathLabel = l;
    }

    @Override
    public void setDownloadDatabaseBtn(JButton b) {
    }

    @Override
    public void setDownloadStatusLabel(JLabel l) {
    }

    @Override
    public void setStep1StatusIcon(JLabel l) {
    }

    @Override
    public void setStep2StatusIcon(JLabel l) {
    }

    @Override
    public void setGlobalSettings(GlobalSettings gs) {
        this.globalSettings = gs;
    }

    private Runnable onDirtyStatusChanged;

    private void reloadProfile() {
        loadProfileInternal(loadedProfileName);
    }
}