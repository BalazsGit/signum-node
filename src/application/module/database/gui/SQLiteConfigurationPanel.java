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
import application.utils.gui.SearchMatchPanel;
import application.utils.gui.SearchValueHighlight;
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
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.text.BadLocationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private JButton newProfileBtn, renameProfileBtn, deleteProfileBtn, refreshProfilesBtn;
    private JButton saveProfileBtn, resetToDefaultBtn;
    private JLabel pathLabel;
    private JPanel searchResultsPanel;
    private JPanel settingsContentPanel;
    private SearchMatchPanel searchMatchPanel;
    private JTextField searchField;
    private CheckboxGroupPanel showValuesPanel;
    private JCheckBox showUnsavedBox;
    private JCheckBox showSavedBox;
    private JComboBox<String> storageTypeCombo;
    private DatabaseFileListEditor databaseFileEditor;
    private Path databaseDirectory;
    private String databaseUrlSuffix = "";
    private CardLayout contentCardLayout;
    private JPanel contentContainer;
    private JTabbedPane sqliteTabs;
    private SQLiteDatabaseAssignmentPanel assignmentsPanel;
    private boolean isInitialized = false;
    private int searchActiveIndex = -1;
    private List<PropertyRow> searchMatches = List.of();
    private final Map<JTextComponent, Object> searchValueHighlights = new java.util.IdentityHashMap<>();

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

        resetToDefaultBtn = new JButton();
        resetToDefaultBtn.setToolTipText(ConfigurationUtils.profileTooltip(
                "Reset to Defaults<br><br>Copies the hardcoded default values into the current editor "
                        + "(unsaved). Press Save to persist."));
        resetToDefaultBtn.addActionListener(e -> resetToDefaults());
        toolbarRow.add(resetToDefaultBtn);

        refreshProfilesBtn = new JButton();
        refreshProfilesBtn.setToolTipText(ConfigurationUtils.profileTooltip(
                "Refresh Profiles<br><br>Re-scans profiles on disk and reloads the selected profile."));
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
                renameProfileBtn, deleteProfileBtn, null, refreshProfilesBtn, resetToDefaultBtn, null, null);

        JScrollPane profileScroll = ConfigurationUtils.wrapProfileToolbarRow(toolbarRow);

        // --- Search row: [Show values filter] [SearchMatchPanel] (SSOT: same as Node panel) ---
        searchMatchPanel = new SearchMatchPanel(
                "Search by property name, label or value");
        searchField = searchMatchPanel.getSearchField();
        searchMatchPanel.setSearchTextListener(text -> filterProperties(text));
        searchMatchPanel.setSearchNavigationListener(this::navigateSearch);

        showValuesPanel = new CheckboxGroupPanel("Show values");
        showValuesPanel.setChangeListener(button -> filterProperties(searchField.getText()));
        showUnsavedBox = showValuesPanel.addCheckbox("Unsaved", GuiColors.getUnsaved(), true);
        showSavedBox = showValuesPanel.addCheckbox("Saved", GuiColors.getSaved(), true);

        // The same responsive toolbar scroll wrapper the node/logging panels use:
        // a left-flow strip of height-unified boxes, 10px left / 5px right margin,
        // 4px strut between boxes, 4px top/bottom border.
        JPanel searchStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        searchStrip.setOpaque(false);
        searchStrip.add(showValuesPanel);
        searchStrip.add(Box.createHorizontalStrut(ConfigurationUtils.INPUT_ACTION_GAP));
        searchStrip.add(searchMatchPanel);
        ConfigurationUtils.unifyTitledBoxHeights(showValuesPanel, searchMatchPanel);
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

        settingsContentPanel = new JPanel(new MigLayout(
                ConfigurationUtils.PROPERTY_PANEL_LAYOUT,
                ConfigurationUtils.PROPERTY_PANEL_COLUMNS));
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

        addSectionHeader(settingsContentPanel, "Database Storage Configuration", true);

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
        HelpButton storageTypeHelp = new HelpButton();
        storageTypeHelp.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "Determines where the SQLite database is stored.\n\n"
                        + "<b>file:</b> The database is stored in a file on disk (persistent).\n"
                        + "<b>memory:</b> The database is kept in RAM and is lost when the node stops.\n\n"
                        + "For most use cases, 'file:' is the correct choice.",
                "Storage Type", JOptionPane.INFORMATION_MESSAGE));
        settingsContentPanel.add(storageTypeHelp, "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP + ", wrap");
        settingsContentPanel.add(new JSeparator(), ConfigurationUtils.PROPERTY_SEPARATOR_CONSTRAINTS);

        // The "Database File" field is an EXPANDABLE list (the node configuration
        // list editors' pattern): every database file is a row with a trash icon
        // and a flat "+" adds a new one. The FIRST file is the active database
        // (DB.Url is composed from it); the file list is discovered from disk.
        databaseFileEditor = new DatabaseFileListEditor();
        databaseFileEditor.setCandidates(refreshDatabaseFileCandidates(fileName));
        List<String> discoveredFiles = loadDatabaseFileList(fileName);
        databaseFileEditor.setItemsWithProtected(discoveredFiles, new java.util.HashSet<>(discoveredFiles));
        databaseFileEditor.setItemSavedValues(discoveredFiles.stream().map(n -> n.endsWith(".db") ? n.substring(0, n.length() - 3) : n).toList());
        databaseFileEditor.onChange = () -> propertyChanged(databaseFileEditor, SQLiteProfile.CFG_URL);
        databaseFileEditor.removeConfirmation = this::confirmDatabaseFileDeletion;
        databaseFileEditor.helpAction = () -> JOptionPane.showMessageDialog(this,
                "This list contains all database files for this SQLite profile.\n\n"
                        + "<b>First file</b>: The active database (used for DB.Url).\n"
                        + "<b>Trash icon</b>: Removes a file from the list. Files that exist on disk cannot be removed here — delete them from the file system.\n"
                        + "<b>+</b>: Add a new database file (the .db extension is appended automatically).\n\n"
                        + "Newly added files are created on disk only when you press Save.",
                "Database File", JOptionPane.INFORMATION_MESSAGE);
        addDatabaseFileProperty();

        // --- SQLite Configuration section ---
        addSectionHeader(settingsContentPanel, "SQLite Configuration", false);

        JComboBox<String> journalMode = editableCombo(
                new String[] { "DELETE", "TRUNCATE", "PERSIST", "MEMORY", "WAL", "OFF" },
                currentProfile.getConfiguration().getOrDefault(SQLiteProfile.CFG_JOURNAL_MODE, "WAL"));
        addProperty(settingsContentPanel, SQLiteProfile.CFG_JOURNAL_MODE, "SQLite Journal Mode", journalMode,
                () -> comboEditorText(journalMode));
        setHelpAction(allPropertyRows, SQLiteProfile.CFG_JOURNAL_MODE,
                "Controls how SQLite manages its rollback journal.\n\n"
                        + "<b>WAL</b> (Write-Ahead Logging) — recommended: allows concurrent reads and writes, better performance.\n"
                        + "<b>DELETE</b> — the default journal mode; the journal file is deleted after each transaction.\n"
                        + "<b>TRUNCATE</b> — the journal file is truncated to zero length after each transaction.\n"
                        + "<b>PERSIST</b> — the journal file is left on disk but not deleted.\n"
                        + "<b>MEMORY</b> — the journal is kept in RAM (not persistent across crashes).\n"
                        + "<b>OFF</b> — journaling disabled (NOT recommended; risk of corruption on crash).");

        JComboBox<String> cacheSize = editableCombo(
                new String[] { "-131072", "-65536", "-32768", "-16384", "-8192", "-4096", "0" },
                currentProfile.getConfiguration().getOrDefault(SQLiteProfile.CFG_CACHE_SIZE, "-131072"));
        addProperty(settingsContentPanel, SQLiteProfile.CFG_CACHE_SIZE, "SQLite Cache Size", cacheSize,
                () -> comboEditorText(cacheSize));
        setHelpAction(allPropertyRows, SQLiteProfile.CFG_CACHE_SIZE,
                "Sets the size of the SQLite page cache in pages.\n\n"
                        + "Negative values are multiplied by 1024 (KiB):\n"
                        + "  -131072 → 128 MiB (default)\n"
                        + "  -65536  → 64 MiB\n"
                        + "  -32768  → 32 MiB\n"
                        + "  -16384  → 16 MiB\n"
                        + "  -8192   → 8 MiB\n"
                        + "  -4096   → 4 MiB\n"
                        + "  0       → system default\n\n"
                        + "A larger cache improves read performance for frequently accessed data but uses more memory.");

        valueSuppliers.put(SQLiteProfile.CFG_URL, this::databaseUrlFromControls);
        storageTypeCombo.addActionListener(e -> {
            propertyChanged(databaseFileEditor, SQLiteProfile.CFG_URL);
            updateDatabaseFileVisibility();
        });
        // Initial visibility: hide the file list when memory: is selected.
        updateDatabaseFileVisibility();

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
     * The candidate names offered by the add row's dropdown: the files discovered
     * in the profile's database directory, with the conventional
     * {@code signum.sqlite} and the active file guaranteed to be offered.
     * Names are returned WITHOUT the {@code .db} extension (the suffix is
     * displayed as a static label by the editor).
     */
    private List<String> refreshDatabaseFileCandidates(String activeFile) {
        List<String> names = new ArrayList<>();
        for (String name : SqliteDatabasePaths.discoverDbFiles(databaseDirectory)) {
            names.add(stripDbExtension(name));
        }
        if (!names.contains("signum.sqlite")) {
            names.add("signum.sqlite");
        }
        if (activeFile != null && !activeFile.isBlank()) {
            String stripped = stripDbExtension(activeFile);
            if (!names.contains(stripped)) {
                names.add(stripped);
            }
        }
        return names;
    }

    private static String stripDbExtension(String name) {
        return name != null && name.endsWith(".db") ? name.substring(0, name.length() - 3) : name;
    }

    /**
     * The initial database-file list of the loaded profile: the stored
     * {@code DB.Files} when present, otherwise the single active file parsed
     * from {@code DB.Url}. When no {@code DB.Files} is stored yet the value is
     * also written back into the profile's configuration so the freshly loaded
     * list reads as saved (not unsaved).
     */
    private List<String> loadDatabaseFileList(String activeFile) {
        // Discover database files from the profile directory (*.db files only —
        // not .db-wal, .db-shm, .db-journal). The filesystem is the source of
        // truth for the file list; no DB.Files field in the JSON.
        Path dir = getProfilePath(loadedProfileName);
        List<String> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.filter(f -> {
                    String name = f.getFileName().toString();
                    return name.endsWith(".db")
                            && !name.endsWith(".db-wal")
                            && !name.endsWith(".db-shm")
                            && !name.endsWith(".db-journal");
                })
                        .sorted()
                        .forEach(f -> files.add(f.getFileName().toString()));
            } catch (Exception e) {
                logger.error("Failed to scan profile directory for .db files: {}", dir, e);
            }
        }
        // Fallback: if the directory has no .db files but the URL references one,
        // show it (the file might not exist yet on disk).
        if (files.isEmpty() && activeFile != null && !activeFile.isBlank()) {
            files.add(activeFile);
        }
        return files;
    }

    /**
     * Two-step confirmation for permanent database file deletion. The first
     * dialog warns that the file will be removed; the second emphasizes that
     * ALL associated files (WAL, SHM, journal) will be permanently deleted and
     * the operation is IRREVERSIBLE. Only when the user confirms BOTH does the
     * deletion proceed (the file + related files are removed from disk).
     *
     * @param fileName the {@code .db} file name being removed
     * @return true if the user confirmed both dialogs (proceed with deletion),
     *         false otherwise (abort)
     */
    private boolean confirmDatabaseFileDeletion(String fileName) {
        // Step 1: initial warning
        int r1 = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to remove the database file '" + fileName + "' from this profile?\n\n"
                        + "This will delete the database file and all its associated data.",
                "Remove Database File",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r1 != JOptionPane.OK_OPTION) {
            return false;
        }
        // Step 2: permanent, irreversible confirmation
        String relatedFiles = listRelatedDbFiles(fileName);
        int r2 = JOptionPane.showConfirmDialog(this,
                "⚠ PERMANENT AND IRREVERSIBLE ⚠\n\n"
                        + "The following files will be DELETED from disk:\n\n"
                        + relatedFiles + "\n\n"
                        + "This operation CANNOT be undone. All data in the database will be LOST.\n\n"
                        + "Do you want to proceed?",
                "Confirm Permanent Deletion",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.ERROR_MESSAGE);
        if (r2 != JOptionPane.OK_OPTION) {
            return false;
        }
        // Proceed: delete the .db file and all related files from disk.
        deleteDatabaseFilesFromDisk(fileName);
        return true;
    }

    private String listRelatedDbFiles(String fileName) {
        Path dir = getProfilePath(loadedProfileName);
        StringBuilder sb = new StringBuilder();
        String[] suffixes = {"", "-wal", "-shm", "-journal"};
        for (String suffix : suffixes) {
            Path p = dir.resolve(fileName + suffix);
            if (Files.exists(p)) {
                sb.append("  • ").append(p.getFileName()).append("\n");
            }
        }
        if (sb.isEmpty()) {
            sb.append("  • ").append(fileName).append(" (not yet on disk)\n");
        }
        return sb.toString();
    }

    private void deleteDatabaseFilesFromDisk(String fileName) {
        Path dir = getProfilePath(loadedProfileName);
        String[] suffixes = {"", "-wal", "-shm", "-journal"};
        for (String suffix : suffixes) {
            Path p = dir.resolve(fileName + suffix);
            try {
                Files.deleteIfExists(p);
            } catch (Exception e) {
                logger.error("Failed to delete database file: {}", p, e);
            }
        }
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
        settingsContentPanel.add(inputColumn, "growx, wrap");
        // The help ("?") is now INSIDE the list editor (on the first item row),
        // matching the Node panel's ListEditor pattern. No separate section-level
        // help button is needed.
        databaseFileEditor.helpAction = () -> JOptionPane.showMessageDialog(this,
                "The database files for this SQLite profile.\nThe first file is the active database.",
                "Database File", JOptionPane.INFORMATION_MESSAGE);
        // The row separator — the SAME trailing JSeparator the node configuration
        // panel puts between its properties (the shared central constraint).
        row.separator = new JSeparator();
        row.separatorConstraints = ConfigurationUtils.PROPERTY_SEPARATOR_CONSTRAINTS;
        settingsContentPanel.add(row.separator, row.separatorConstraints);
        row.label = label;
        row.input = inputColumn;
        row.help = null;
        row.labelConstraints = "align label, aligny top";
        row.inputConstraints = "growx, wrap";
        row.helpConstraints = null;
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

    /**
     * Sets a left-click info dialog on the HelpButton of the property row with
     * the given key (the row must already be in {@code allPropertyRows}).
     *
     * @param rows the property rows list
     * @param key  the property key to locate
     * @param message the HTML-capable info message
     */
    private void setHelpAction(List<PropertyRow> rows, String key, String message) {
        for (PropertyRow row : rows) {
            if (key.equals(row.propertyKey) && row.help != null) {
                row.help.addActionListener(e -> JOptionPane.showMessageDialog(this,
                        message, row.labelText, JOptionPane.INFORMATION_MESSAGE));
                return;
            }
        }
    }

    /**
     * Shows or hides the database-file editor based on the selected storage type.
     * When "memory:" is selected, the file list is irrelevant (the database lives
     * in RAM) and is hidden to avoid confusion.
     */
    private void updateDatabaseFileVisibility() {
        if (databaseFileEditor == null || storageTypeCombo == null) {
            return;
        }
        boolean isMemory = "memory:".equals(storageTypeCombo.getSelectedItem());
        // The database-file property row (label + input + separator) is toggled.
        for (PropertyRow row : allPropertyRows) {
            if (SQLiteProfile.CFG_URL.equals(row.propertyKey)) {
                row.label.setVisible(!isMemory);
                row.input.setVisible(!isMemory);
                if (row.help != null) {
                    row.help.setVisible(!isMemory);
                }
                if (row.separator != null) {
                    row.separator.setVisible(!isMemory);
                }
                break;
            }
        }
        settingsContentPanel.revalidate();
        settingsContentPanel.repaint();
    }

    private void updateColor(JComponent comp, String key) {
        Color color = valueColor(key);
        if (SQLiteProfile.CFG_URL.equals(key)) {
            // The database-file list uses per-item state coloring: each file row
            // is colored by its OWN membership in the saved and applied file
            // lists (the NodeConfigurationPanel list-editor pattern). The storage
            // type combo is colored from the overall CFG_URL state.
            if (databaseFileEditor != null) {
                databaseFileEditor.applyPerItemForegrounds(fileItemColors());
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

    /**
     * Computes the per-item state colors for the database-file list. Each file
     * row is colored by whether it exists on disk (SAVED) or not (UNSAVED).
     */
    private List<Color> fileItemColors() {
        List<String> items = databaseFileEditor.getItems();
        if (items.isEmpty()) {
            return List.of();
        }
        // Saved baseline: the .db files that exist on disk in the profile directory.
        Path dir = getProfilePath(loadedProfileName);
        Set<String> savedFiles = new java.util.HashSet<>();
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.filter(f -> {
                    String name = f.getFileName().toString();
                    return name.endsWith(".db")
                            && !name.endsWith(".db-wal")
                            && !name.endsWith(".db-shm")
                            && !name.endsWith(".db-journal");
                })
                        .forEach(f -> savedFiles.add(f.getFileName().toString()));
            } catch (Exception e) {
                // directory not accessible; treat as empty
            }
        }
        List<Color> colors = new java.util.ArrayList<>(items.size());
        for (String file : items) {
            colors.add(savedFiles.contains(file) ? GuiColors.getSaved() : GuiColors.getUnsaved());
        }
        return colors;
    }

    private Color valueColor(String key) {
        return switch (valueStatus(key)) {
            case UNSAVED -> GuiColors.getUnsaved();
            case SAVED -> GuiColors.getSaved();
        };
    }

    private ValueStatus valueStatus(String key) {
        String current = valueSuppliers.getOrDefault(key, () -> "").get();
        String saved = currentProfile.getConfiguration().getOrDefault(key, "");
        return current.equals(saved) ? ValueStatus.SAVED : ValueStatus.UNSAVED;
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
        // Reload the currently selected profile from disk (combined role of the
        // former separate "Reload Profile" button).
        if (loadedProfileName != null && profileComboBox.getSelectedItem() != null) {
            loadProfileInternal(loadedProfileName);
        }
        updateProfileButtonsUI();
    }

    private void updateProfileButtonsUI() {
        boolean hasSel = profileComboBox.getSelectedItem() != null;
        renameProfileBtn.setEnabled(hasSel);
        deleteProfileBtn.setEnabled(hasSel);
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
            searchMatches = List.of();
            searchActiveIndex = -1;
            updateSearchMatchIndicator(null);
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
        // Update match tracking and indicator (only for text search, not filter-only)
        if (!low.isEmpty()) {
            searchMatches = List.copyOf(matchingRows);
            if (searchActiveIndex < 0 && !matchingRows.isEmpty()) {
                searchActiveIndex = 0;
            }
            if (searchActiveIndex >= matchingRows.size()) {
                searchActiveIndex = matchingRows.isEmpty() ? -1 : matchingRows.size() - 1;
            }
            updateSearchMatchIndicator(formatSearchIndicator());
            // Highlight the active match's value text
            if (searchActiveIndex >= 0 && searchActiveIndex < searchMatches.size()) {
                paintSearchMatch(searchMatches.get(searchActiveIndex), true);
            }
        } else {
            searchMatches = List.of();
            searchActiveIndex = -1;
            updateSearchMatchIndicator(null);
            clearAllValueHighlights();
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

    private void navigateSearch(boolean next) {
        String query = searchMatchPanel.getSearchText().trim();
        if (query.isEmpty() || searchMatches.isEmpty()) {
            return;
        }
        int count = searchMatches.size();
        int oldIndex = searchActiveIndex;
        searchActiveIndex = next
                ? (searchActiveIndex + 1) % count
                : (searchActiveIndex - 1 + count) % count;
        if (oldIndex >= 0 && oldIndex < count && oldIndex != searchActiveIndex) {
            paintSearchMatch(searchMatches.get(oldIndex), false);
        }
        paintSearchMatch(searchMatches.get(searchActiveIndex), true);
        updateSearchMatchIndicator(formatSearchIndicator());
        SwingUtilities.invokeLater(() -> scrollToActiveMatch());
    }

    private void paintSearchMatch(PropertyRow row, boolean active) {
        if (row.label instanceof JLabel label) {
            label.setFont(active
                    ? label.getFont().deriveFont(java.awt.Font.BOLD)
                    : label.getFont().deriveFont(java.awt.Font.PLAIN));
        }
        highlightValueMatch(row, active);
    }

    private void highlightValueMatch(PropertyRow row, boolean active) {
        String query = searchMatchPanel.getSearchText().trim();
        if (query.isEmpty() || row.input == null) {
            return;
        }
        JTextComponent target = rowValueTextComponent(row);
        if (target == null) {
            return;
        }
        // Remove any previous highlight on this component
        Object previous = searchValueHighlights.remove(target);
        if (previous != null) {
            target.getHighlighter().removeHighlight(previous);
        }
        if (!active) {
            return;
        }
        String value = target.getText();
        int idx = value.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        if (idx < 0) {
            return;
        }
        try {
            searchValueHighlights.put(target,
                    target.getHighlighter().addHighlight(idx, idx + query.length(),
                            SearchValueHighlight.PAINTER_ACTIVE));
        } catch (BadLocationException e) {
            // range outside document bounds
        }
    }

    private static JTextComponent rowValueTextComponent(PropertyRow row) {
        JComponent input = row.input;
        if (input instanceof JTextComponent textComponent) {
            return textComponent;
        }
        if (input instanceof JComboBox<?> comboBox && comboBox.isEditable()) {
            Object editorComponent = comboBox.getEditor().getEditorComponent();
            if (editorComponent instanceof JTextComponent textComponent) {
                return textComponent;
            }
        }
        return null;
    }

    private void clearAllValueHighlights() {
        for (Map.Entry<JTextComponent, Object> entry : searchValueHighlights.entrySet()) {
            entry.getKey().getHighlighter().removeHighlight(entry.getValue());
        }
        searchValueHighlights.clear();
    }

    private void scrollToActiveMatch() {
        if (searchActiveIndex < 0 || searchActiveIndex >= searchMatches.size()) {
            return;
        }
        PropertyRow row = searchMatches.get(searchActiveIndex);
        if (row.label == null || row.label.getParent() == null) {
            return;
        }
        // Find the nearest scrollable ancestor and scroll the row into view.
        java.awt.Component parent = row.label.getParent();
        while (parent != null && !(parent instanceof JScrollPane)) {
            parent = parent.getParent();
        }
        if (parent instanceof JScrollPane sp) {
            sp.getViewport().scrollRectToVisible(row.label.getBounds());
        }
    }

    private String formatSearchIndicator() {
        if (searchMatches.isEmpty()) {
            return null;
        }
        int idx = Math.max(0, searchActiveIndex) + 1;
        return idx + "/" + searchMatches.size();
    }

    private void updateSearchMatchIndicator(String text) {
        boolean hasMatches = text != null && !text.isEmpty();
        searchMatchPanel.setChevronsVisible(hasMatches);
        searchMatchPanel.setMatchIndicatorText(text);
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
            case SAVED -> showSavedBox.isSelected();
        };
    }

    private enum ValueStatus {
        UNSAVED,
        SAVED
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

    /**
     * Resets the configuration editor values to the hardcoded application
     * defaults (unsaved). The file list is filesystem-based and is NOT reset.
     * After this, the values show as UNSAVED until the user saves.
     */
    private void resetToDefaults() {
        if (currentProfile == null || loadedProfileName == null) {
            return;
        }
        // Reset the storage type to the default ("file:").
        if (storageTypeCombo != null) {
            storageTypeCombo.setSelectedItem(STORAGE_TYPES[0]);
        }
        // Reset journal mode and cache size to their hardcoded defaults.
        for (String key : List.of(SQLiteProfile.CFG_JOURNAL_MODE, SQLiteProfile.CFG_CACHE_SIZE)) {
            JComponent component = propertyComponents.get(key);
            if (component instanceof JComboBox<?> combo) {
                String defaultVal = SQLiteProfile.DEFAULTS.getOrDefault(key, "");
                combo.getEditor().setItem(defaultVal);
            }
        }
        // Update colors and dirty status.
        updateUIFromData();
        if (onDirtyStatusChanged != null) {
            onDirtyStatusChanged.run();
        }
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
        for (String key : List.of(SQLiteProfile.CFG_URL,
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
        for (String key : List.of(SQLiteProfile.CFG_URL,
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
}