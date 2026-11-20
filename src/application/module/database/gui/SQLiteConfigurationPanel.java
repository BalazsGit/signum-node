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
import application.utils.gui.ComboSearchHighlightRenderer;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiUtils;
import application.utils.gui.HelpButton;
import application.utils.gui.LeftFlowLayout;
import application.utils.gui.NewProfileChoiceDialog;
import application.utils.gui.ResponsiveToolbarScrollPane;
import application.utils.gui.SearchMatchLabel;
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
import javax.swing.text.Highlighter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
    /**
     * The profiles whose editor state currently carries unsaved changes: listed
     * with a trailing {@code " *"} in the profile selector (the node / logging
     * panel convention).
     */
    private final Set<String> unsavedProfiles = new HashSet<>();

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
    private DatabaseFileListEditor databaseFileEditor;
    private Path databaseDirectory;
    private String loadedActiveFile;
    private CardLayout contentCardLayout;
    private JPanel contentContainer;
    private JTabbedPane sqliteTabs;
    private SQLiteDatabaseAssignmentPanel assignmentsPanel;
    private boolean isInitialized = false;
    private int searchActiveIndex = -1;
    private List<SearchTarget> searchTargets = List.of();
    private final Map<JTextComponent, Object> searchValueHighlights = new java.util.IdentityHashMap<>();
    /** The non-editable selects carrying a search band and their original renderer (restored on clear). */
    private final Map<JComboBox<?>, ListCellRenderer<?>> originalComboRenderers = new java.util.IdentityHashMap<>();
    /** The state-aware selects and their dropdown recipe (option catalog + saved baseline). */
    private final Map<JComboBox<String>, StateAwareCombo> stateAwareCombos = new java.util.IdentityHashMap<>();
    /** The state-aware selects whose dropdown model rebuild is already in flight (re-entrancy guard). */
    private final Set<JComboBox<String>> rebuildingCombos = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** True while the panel updates its own controls programmatically (must not fire the change hooks). */
    private boolean isProgrammaticChange;

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
                ConfigurationUtils.createProfileComboBoxRenderer(() -> runningProfileName,
                        () -> activeProfileName, () -> unsavedProfiles));
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
        searchMatchPanel.setSearchTextListener(text -> filterProperties(text, true));
        searchMatchPanel.setSearchNavigationListener(this::navigateSearch);
        // Enter: jump to / advance the active match (the node configuration
        // panel's search behavior, same as the console).
        searchMatchPanel.setEnterListener(() -> navigateSearch(true));

        showValuesPanel = new CheckboxGroupPanel("Show values");
        showValuesPanel.setChangeListener(button -> filterProperties(searchField.getText(), false));
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
        stateAwareCombos.clear();

        // --- SQLite Configuration section ---
        addSectionHeader(settingsContentPanel, "SQLite Configuration", true);

        // The journal mode is a CLOSED set — SQLite accepts exactly these six
        // values and nothing else — so its select is NOT editable: every
        // possible value is already in the catalog. (A select that can hold
        // custom values — the cache size — stays editable.)
        JComboBox<String> journalMode = createStateAwareValueCombo(
                new String[] { "DELETE", "TRUNCATE", "PERSIST", "MEMORY", "WAL", "OFF" },
                currentProfile.getConfiguration().getOrDefault(SQLiteProfile.CFG_JOURNAL_MODE, "WAL"), false);
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

        JComboBox<String> cacheSize = createStateAwareValueCombo(
                new String[] { "-131072", "-65536", "-32768", "-16384", "-8192", "-4096", "0" },
                currentProfile.getConfiguration().getOrDefault(SQLiteProfile.CFG_CACHE_SIZE, "-131072"), true);
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

        // --- Database Storage Configuration section ---
        addSectionHeader(settingsContentPanel, "Database Storage Configuration", false);

        // The profile's database URL is DERIVED (the profile directory + the
        // active file — see SQLiteProfile): parse it back into the database
        // directory and the active-file fallback for when the directory holds
        // no *.db yet.
        String fullUrl = currentProfile.getDbUrl();
        SqliteDatabasePaths.SqliteUrlParts urlParts = SqliteDatabasePaths.splitUrl(fullUrl);
        databaseDirectory = urlParts != null ? urlParts.dir()
                : SqliteDatabasePaths.profileDir(loadedProfileName);
        String fileName = urlParts != null ? urlParts.fileName() : "";
        // Snapshot of the active file the derived URL points at WHEN LOADED —
        // the reference for the row's dirty state (deleting the active file
        // re-points the derived URL; the marker must survive that).
        loadedActiveFile = fileName;

        // The "Database File" field is an EXPANDABLE list (the node configuration
        // list editors' pattern): every database file is a row with a trash icon
        // and a flat "+" adds a new one. The FIRST file is the active database
        // (the profile's derived URL points at it); the file list is discovered
        // from disk.
        databaseFileEditor = new DatabaseFileListEditor();
        databaseFileEditor.setCandidates(refreshDatabaseFileCandidates(fileName));
        List<String> discoveredFiles = loadDatabaseFileList(fileName);
        // The per-row saved values (without .db) drive the save-time renames
        // and the trash's target (the SAVED file, not the not-yet-existing one).
        databaseFileEditor.setItemsWithProtected(discoveredFiles, new java.util.HashSet<>(discoveredFiles));
        databaseFileEditor.setItemSavedValues(discoveredFiles.stream().map(n -> n.endsWith(".db") ? n.substring(0, n.length() - 3) : n).toList());
        databaseFileEditor.onChange = () -> propertyChanged(databaseFileEditor, SQLiteProfile.CFG_URL);
        databaseFileEditor.removeConfirmation = this::confirmDatabaseFileDeletion;
        databaseFileEditor.helpAction = () -> JOptionPane.showMessageDialog(this,
                "This list contains all database files for this SQLite profile.\n\n"
                        + "<b>First file</b>: The active database (the profile's derived URL points at it).\n"
                        + "<b>Rename</b>: Editing an existing file's name renames the file on disk — with all its data — when you press Save.\n"
                        + "<b>Trash icon</b>: Removes the file from the list. A file that exists on disk asks a two-step confirmation and is deleted from disk (irreversible); a file not yet created is removed immediately.\n"
                        + "<b>+</b>: Add a new database file (the .db extension is appended automatically).\n\n"
                        + "Newly added files are created on disk only when you press Save.",
                "Database File", JOptionPane.INFORMATION_MESSAGE);
        addDatabaseFileProperty();

        valueSuppliers.put(SQLiteProfile.CFG_URL, this::databaseUrlFromControls);

        settingsContentPanel.revalidate();
        settingsContentPanel.repaint();
    }

    /**
     * When sizing a non-editable combo's DISPLAY area the L&F invokes the
     * renderer with a NULL list (BasicComboBoxUI.getDisplaySize), and this JDK's
     * DefaultListCellRenderer does not tolerate that (NPE) — a shared throwaway
     * list stands in; the renderer overwrites the foreground it would copy from
     * the list for every value entry anyway (the node configuration panel's
     * stateAwareItemRenderer uses the same guard).
     */
    private static final JList<?> RENDERER_FALLBACK_LIST = new JList<>(new String[0]);

    /**
     * The 2-state (unsaved/saved) form of the node configuration panel's
     * item-state rule: the state of ONE select dropdown entry — every entry
     * displays the state of its OWN value. The entry equal to the row's current
     * value displays the row's own state (saved only while the current value is
     * still the saved one), the entry equal to the saved baseline is saved,
     * everything else unsaved. (The database profile knows no applied baseline
     * — only unsaved and saved exist here.)
     */
    private static ValueStatus dropdownItemStatus(String item, String current, String saved) {
        if (item.equals(current)) {
            return current.equals(saved) ? ValueStatus.SAVED : ValueStatus.UNSAVED;
        }
        return item.equals(saved) ? ValueStatus.SAVED : ValueStatus.UNSAVED;
    }

    /**
     * The dropdown renderer of the state-aware selects: every entry is colored
     * by the state of its OWN value via {@link #dropdownItemStatus} (saved
     * yellow, unsaved the normal text color) — the node configuration panel's
     * stateAwareItemRenderer in its 2-state form.
     *
     * @param currentValue the supplier of the row's current (editor/selected) value
     * @param saved the row's saved (profile-on-disk) baseline value
     */
    private DefaultListCellRenderer stateAwareItemRenderer(Supplier<String> currentValue, String saved) {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(
                        list == null ? RENDERER_FALLBACK_LIST : list, value, index, isSelected, cellHasFocus);
                if (value instanceof String item) {
                    c.setForeground(dropdownItemStatus(item, currentValue.get(), saved).color());
                }
                return c;
            }
        };
    }

    /**
     * The state-aware select that is the input of the SQLite value rows (the
     * node configuration panel's createStateAwareValueCombo in its 2-state
     * form): the editor text is the row's current value and the dropdown model
     * is rebuilt on every value change by
     * {@link #rebuildStateAwareComboModel} with the option catalog plus the
     * current (unsaved) and the saved baseline values, so the user can always
     * pick either back in one click. The dropdown entries are colored per item
     * by {@link #stateAwareItemRenderer}; the row's own value coloring stays
     * driven by the editor text (the value supplier reads it too).
     * <p>A select whose value set is CLOSED (the journal mode — every
     * possible value is in the catalog) is created NOT editable; a select
     * that can hold custom values (the cache size) stays editable.
     *
     * @param options the fixed option catalog offered in the dropdown
     * @param savedValue the row's saved (profile-on-disk) baseline value
     * @param editable whether the user may type values beyond the catalog
     */
    private JComboBox<String> createStateAwareValueCombo(String[] options, String savedValue, boolean editable) {
        JComboBox<String> comboBox = new JComboBox<>(new String[0]);
        comboBox.setEditable(editable);
        stateAwareCombos.put(comboBox, new StateAwareCombo(options, savedValue));
        if (editable) {
            ConfigurationUtils.styleComboEditor(comboBox);
        } else {
            // A non-editable select has no editor to style — the input font
            // recipe applies (the storage-type select is styled the same way).
            ConfigurationUtils.styleInputComponent(comboBox);
        }
        ConfigurationUtils.fixComponentSize(comboBox);
        boolean prevFlag = isProgrammaticChange;
        isProgrammaticChange = true;
        try {
            String seed = savedValue == null ? "" : savedValue.trim();
            if (editable) {
                // The editor text is the row's current value — seed it with the
                // saved value, exactly like the node panel seeds its state-aware
                // combos. Flagged: the seed fires editor events that must not
                // queue a change of their own.
                if (!seed.isEmpty()) {
                    comboBox.getEditor().setItem(seed);
                }
                rebuildStateAwareComboModel(comboBox);
            } else {
                // A non-editable select has no editor: seed the initial model
                // (the option catalog plus the saved baseline — which may sit
                // outside the catalog for a legacy value) and select the saved
                // value in it.
                LinkedHashSet<String> initialItems = new LinkedHashSet<>();
                for (String option : options) {
                    initialItems.add(option);
                }
                if (!seed.isEmpty()) {
                    initialItems.add(seed);
                }
                comboBox.setModel(new DefaultComboBoxModel<>(initialItems.toArray(new String[0])));
                if (!seed.isEmpty()) {
                    comboBox.setSelectedItem(seed);
                }
            }
        } finally {
            isProgrammaticChange = prevFlag;
        }
        comboBox.setRenderer(stateAwareItemRenderer(() -> comboEditorText(comboBox), savedValue));
        return comboBox;
    }

    /**
     * Rebuilds a state-aware select's dropdown model: the option catalog plus
     * the row's current (unsaved) value and its saved baseline — the node
     * configuration panel's rebuildStateAwareComboModel in its 2-state form
     * (no applied baseline). Runs on every value change; the re-entrancy guard
     * keeps the combo's own event chain (editor change → updateColor →
     * rebuild) from re-triggering a nested rebuild.
     */
    private void rebuildStateAwareComboModel(JComboBox<String> comboBox) {
        StateAwareCombo info = stateAwareCombos.get(comboBox);
        if (info == null) {
            return;
        }
        if (rebuildingCombos.contains(comboBox)) {
            return;
        }
        rebuildingCombos.add(comboBox);
        try {
            LinkedHashSet<String> items = new LinkedHashSet<>();
            for (String option : info.options()) {
                items.add(option);
            }
            String typed = comboEditorText(comboBox);
            if (!typed.trim().isEmpty()) {
                items.add(typed);
            }
            if (info.saved() != null && !info.saved().trim().isEmpty()) {
                items.add(info.saved());
            }
            // Every editor / model touch below is PROGRAMMATIC, so it runs
            // flagged: the restore fires editor events (and the pre-swap
            // selection can fire the combo's action), and neither may fire a
            // change of its own.
            boolean prevFlag = isProgrammaticChange;
            isProgrammaticChange = true;
            try {
                // Keep the (typed) current value as the editor item before
                // swapping the model; when it is not among the entries the
                // selection simply drops (the editor text stays as typed).
                if (!typed.trim().isEmpty()) {
                    comboBox.setSelectedItem(typed);
                } else {
                    comboBox.setSelectedIndex(-1);
                }
                comboBox.setModel(new DefaultComboBoxModel<>(items.toArray(new String[0])));
                // Keep the model selection in sync with the editor text.
                comboBox.setSelectedItem(typed);
                if (!typed.equals(comboEditorText(comboBox))) {
                    comboBox.getEditor().setItem(typed);
                }
            } finally {
                isProgrammaticChange = prevFlag;
            }
        } finally {
            rebuildingCombos.remove(comboBox);
        }
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
     * The initial database-file list of the loaded profile: the {@code *.db}
     * files discovered in the profile directory (the filesystem is the source
     * of truth for the list — the SAME case-insensitive discovery the derived
     * URL's active file uses, so the editor's first row is the active
     * database). When the directory holds no database file yet but the
     * profile's derived URL references one, that file is
     * offered alone (the database is created lazily, on save / first run).
     */
    private List<String> loadDatabaseFileList(String activeFile) {
        List<String> files = SqliteDatabasePaths.discoverDbFiles(getProfilePath(loadedProfileName));
        if (files.isEmpty() && activeFile != null && !activeFile.isBlank()) {
            files = new ArrayList<>(List.of(activeFile));
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
        // Step 2: permanent, irreversible confirmation. The explicit "Delete"
        // button (instead of a generic "OK") makes the destructive action
        // unmistakable.
        String relatedFiles = listRelatedDbFiles(fileName);
        Object[] options = { "Delete", "Cancel" };
        JOptionPane pane = new JOptionPane(
                "⚠ PERMANENT AND IRREVERSIBLE ⚠\n\n"
                        + "The following files will be DELETED from disk:\n\n"
                        + relatedFiles + "\n\n"
                        + "This operation CANNOT be undone. All data in the database will be LOST.\n\n"
                        + "Do you want to proceed?",
                JOptionPane.ERROR_MESSAGE, JOptionPane.DEFAULT_OPTION,
                null, options, options[0]);
        JDialog dialog = pane.createDialog(this, "Confirm Permanent Deletion");
        dialog.setResizable(false);
        dialog.setVisible(true);
        boolean confirmed = "Delete".equals(pane.getValue());
        dialog.dispose();
        if (!confirmed) {
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
        SearchMatchLabel label = new SearchMatchLabel(row.labelText);

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
                "The database files for this SQLite profile. The first file is the active database.\n\n"
                        + "Editing a file's name renames it on disk — with all its data — when you press Save.\n"
                        + "The trash icon deletes an existing file (two-step confirmation) or removes a not-yet-created one immediately.",
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

    /**
     * The database URL the editor currently represents: the canonical
     * file-based URL of the profile's database directory composed from the
     * ACTIVE (first) file of the list. The URL is a derived value — it is
     * never stored in the profile's JSON (see {@code SQLiteProfile}).
     */
    private String databaseUrlFromControls() {
        List<String> files = databaseFileEditor == null ? List.of() : databaseFileEditor.getItems();
        String file = files.isEmpty() ? "" : files.get(0);
        return SqliteDatabasePaths.composeUrl(databaseDirectory, file);
    }

    private void addProperty(JPanel panel, String key, String labelText, JComponent input,
            Supplier<String> valueSupplier) {
        PropertyRow row = new PropertyRow(key, labelText, panel);
        SearchMatchLabel label = new SearchMatchLabel(labelText);
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
                    public void insertUpdate(DocumentEvent e) { onEditorTextChange(input, key); }
                    public void removeUpdate(DocumentEvent e) { onEditorTextChange(input, key); }
                    public void changedUpdate(DocumentEvent e) { onEditorTextChange(input, key); }
                });
            }
        }
    }

    /**
     * The change refresh for a combo's TYPED text — the document-listener twin
     * of the combo's action listener (selections arrive through the action,
     * typing through the document).
     * <p>The refresh is DEFERRED: the listener runs inside a document-change
     * notification, and the state-aware model rebuild touches the editor text
     * (the L&F reconfigures the editor on every model swap) — mutating a
     * document while its own notification is in flight is forbidden by Swing
     * ("Attempt to mutate in notification"). Deferred, the refresh settles
     * after the pending document events (the node configuration panel's
     * onEditorTextChange defers the same way).
     */
    private void onEditorTextChange(JComponent input, String key) {
        if (isProgrammaticChange) {
            return;
        }
        SwingUtilities.invokeLater(() -> propertyChanged(input, key));
    }

    private void propertyChanged(JComponent comp, String key) {
        if (isProgrammaticChange) {
            return;
        }
        updateColor(comp, key);
        updateUnsavedProfileMarker();
        filterProperties(searchField.getText(), false);
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

    private void updateColor(JComponent comp, String key) {
        updateRowDirtyMarker(key);
        Color color = valueColor(key);
        if (SQLiteProfile.CFG_URL.equals(key)) {
            // The database-file list uses per-item state coloring: each file row
            // is colored by its OWN membership in the saved and applied file
            // lists (the NodeConfigurationPanel list-editor pattern).
            if (databaseFileEditor != null) {
                databaseFileEditor.applyPerItemForegrounds(fileItemColors());
            }
            return;
        }
        comp.setForeground(color);
        if (comp instanceof JComboBox<?> combo && combo.isEditable()
                && combo.getEditor().getEditorComponent() instanceof JComponent editor) {
            editor.setForeground(color);
        }
        // The state-aware selects keep their dropdown model in sync with the
        // edited value: the option catalog plus the current (unsaved) and the
        // saved baseline (the node configuration panel's state-aware rebuild,
        // in its 2-state form).
        if (comp instanceof JComboBox<?> candidate && stateAwareCombos.containsKey(candidate)) {
            @SuppressWarnings("unchecked")
            JComboBox<String> stateCombo = (JComboBox<String>) candidate;
            rebuildStateAwareComboModel(stateCombo);
        }
    }

    /**
     * The unsaved-changes asterisk on the row's label: the property row's
     * label text gets a trailing " *" while the row still differs from its
     * saved baseline (the node configuration panel marks unsaved changes the
     * same way, on every row, including its list editors).
     */
    private void updateRowDirtyMarker(String key) {
        for (PropertyRow row : allPropertyRows) {
            if (key.equals(row.propertyKey) && row.label != null) {
                row.label.setText(rowDirty(key) ? row.labelText + " *" : row.labelText);
                return;
            }
        }
    }

    /**
     * Whether the property row still carries unsaved changes. The database
     * file row is dirty when the file list differs from the profile directory
     * ON DISK (a renamed — not yet moved — or a not-yet-created file is an
     * unsaved change, while a deleted file leaves no trace — the trash removes
     * it from disk immediately), or when the profile's derived URL points at a
     * file that is not the editor's active one (the save composes the URL from
     * the active file).
     */
    private boolean rowDirty(String key) {
        if (SQLiteProfile.CFG_URL.equals(key)) {
            List<String> current = databaseFileEditor == null ? List.of() : databaseFileEditor.getItems();
            Set<String> onDisk = listDatabaseFileNames(getProfilePath(loadedProfileName));
            // A file the profile's derived DB.URL references but that does not
            // exist on disk yet (a freshly created profile) is not an unsaved
            // change — the database file is created lazily (on save / first run).
            String savedFile = savedActiveFileName();
            for (String file : current) {
                if (!onDisk.contains(file) && !file.equals(savedFile)) {
                    return true;
                }
            }
            for (String file : onDisk) {
                if (!current.contains(file)) {
                    return true;
                }
            }
            // The derived URL's active file differing from the editor's first
            // file is a real pending change: the save composes the URL from the
            // first file.
            if (savedFile != null && SqliteDatabasePaths.isDbFile(savedFile)
                    && !savedFile.equals(current.isEmpty() ? "" : current.get(0))) {
                return true;
            }
            return false;
        }
        return valueStatus(key) != ValueStatus.SAVED;
    }

    /**
     * The file name (with {@code .db}) the profile's derived DB.URL pointed to
     * WHEN THE PROFILE WAS LOADED (the reference for the row's dirty state) —
     * the live derived file name when no snapshot is available, or null when
     * it cannot be parsed.
     */
    private String savedActiveFileName() {
        if (loadedActiveFile != null && !loadedActiveFile.isBlank()) {
            return loadedActiveFile;
        }
        if (currentProfile == null) {
            return null;
        }
        SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(currentProfile.getDbUrl());
        return parts == null ? null : parts.fileName();
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
        Set<String> savedFiles = listDatabaseFileNames(getProfilePath(loadedProfileName));
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
        if (SQLiteProfile.CFG_URL.equals(key)) {
            // The database URL is a derived value (never stored): its state is
            // the state of the database file row (the on-disk list plus the
            // derived URL's active file).
            return rowDirty(key) ? ValueStatus.UNSAVED : ValueStatus.SAVED;
        }
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

    /**
     * One search match: a property row ({@code itemIndex} {@code -1} — a match
     * in the label or key) or a single item of the database-file list editor
     * ({@code itemIndex} 0..n-1). The match counter and the chevron navigation
     * work over these targets: every target is ONE real match, so "i/n"
     * reflects the true number of matches and the navigated (strong) band can
     * move from one list item to the next while every other match keeps its
     * soft (selected) band — the node configuration panel's one-match-one-band
     * model, refined for the expandable list editor whose items are
     * individually searchable.
     */
    private record SearchTarget(PropertyRow row, int itemIndex) {
    }

    /**
     * The search / value-state filter: re-lists the flat results view and
     * (with a non-empty query) paints the EXACT match bands — the node
     * configuration panel's search behavior.
     * <p>
     * With a non-empty query every listed row is a match — the query is in
     * the property key, the label text, the row's value or (for the
     * database-file row) any individual file item — and the matching
     * occurrence (the label, the value text, the selected value of a
     * non-editable select or a file-list item) receives the soft band, the
     * first match the strong (active) one. The match counter counts the
     * individual TARGETS (each matching file-list item is its own match, not
     * the whole row), and the chevron navigation moves the strong band
     * between them. With the unsaved-changes filter alone (query empty, a
     * "Show values" checkbox off) the flat list is a plain filtered view — no
     * bands, no match counter and no chevron navigation.
     * </p>
     *
     * @param text           the search text (null treated as empty)
     * @param scrollToActive when true the active (first) match is brought into
     *                       view — search-box input does; a refresh caused by
     *                       the user editing a value must NOT yank the scroll
     *                       around, so it passes false
     */
    private void filterProperties(String text, boolean scrollToActive) {
        clearSearchHighlights();
        searchTargets = List.of();
        searchActiveIndex = -1;

        String query = text == null ? "" : text.trim();
        String low = query.toLowerCase(Locale.ROOT);
        boolean filterActive = !low.isEmpty() || !showValuesPanel.isAllSelected();
        if (!filterActive) {
            if (searchViewActive) {
                restoreSettingsRows();
                searchResultsPanel.removeAll();
                contentCardLayout.show(contentContainer, "SETTINGS");
                searchViewActive = false;
            }
            lastFilteredRows = null;
            updateSearchMatchIndicator(null);
            return;
        }

        List<PropertyRow> matchingRows = new ArrayList<>();
        for (PropertyRow row : allPropertyRows) {
            if (row.propertyKey != null && isValueVisible(row.propertyKey)
                    && (low.isEmpty() || matchesQuery(row, low))) {
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
        // Match tracking and the exact-match bands (only for text search, not
        // the filter-only flat list): every TARGET is one real match — each
        // matching file-list item counts individually — and gets the soft
        // band, the first one the strong (active) band; a new query follows
        // its first match.
        if (!low.isEmpty()) {
            List<SearchTarget> targets = new ArrayList<>();
            for (PropertyRow row : matchingRows) {
                targets.addAll(rowSearchTargets(row, low));
            }
            searchTargets = List.copyOf(targets);
            if (!searchTargets.isEmpty()) {
                searchActiveIndex = 0;
            }
            for (int i = 0; i < searchTargets.size(); i++) {
                paintSearchMatch(searchTargets.get(i), query, i == searchActiveIndex);
            }
            updateSearchMatchIndicator(formatSearchIndicator());
            if (scrollToActive) {
                showActiveSearchMatch();
            }
        } else {
            updateSearchMatchIndicator(null);
        }
        searchResultsPanel.revalidate();
        searchResultsPanel.repaint();
    }

    /**
     * The row's searchable text — the node configuration panel's policy:
     * the property key, the label text and the row's value. The
     * database-file row is the exception: it matches its label/key or any
     * INDIVIDUAL item of the file list editor (the node configuration's
     * list-editor pattern), while its composed URL value does NOT take part
     * in the search — the URL is a derived, not displayed value, and
     * matching it would only produce phantom matches (its directory part
     * would match queries no visible value contains).
     *
     * @param row        the property row to search (a non-null key)
     * @param lowerQuery the trimmed, lower-cased search query (non-empty)
     * @return true when the query occurs in any of the row's visible values
     */
    private boolean matchesQuery(PropertyRow row, String lowerQuery) {
        if (row.propertyKey.toLowerCase(Locale.ROOT).contains(lowerQuery)
                || row.labelText.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        if (SQLiteProfile.CFG_URL.equals(row.propertyKey)) {
            return databaseFileEditor != null && databaseFileEditor.getItems().stream()
                    .anyMatch(item -> item.toLowerCase(Locale.ROOT).contains(lowerQuery));
        }
        return valueSuppliers.getOrDefault(row.propertyKey, () -> "").get()
                .toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    /**
     * The search targets of one matching row: for the database-file row, ONE
     * target per matching list item (each item is a real match — the counter
     * and the chevron navigation work over these) plus a row-level target
     * when the label or key matches too; for every other row, a single
     * row-level target.
     */
    private List<SearchTarget> rowSearchTargets(PropertyRow row, String lowerQuery) {
        if (!SQLiteProfile.CFG_URL.equals(row.propertyKey) || databaseFileEditor == null) {
            return List.of(new SearchTarget(row, -1));
        }
        List<SearchTarget> targets = new ArrayList<>();
        if (row.propertyKey.toLowerCase(Locale.ROOT).contains(lowerQuery)
                || row.labelText.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            targets.add(new SearchTarget(row, -1));
        }
        int index = 0;
        for (String item : databaseFileEditor.getItems()) {
            if (item.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                targets.add(new SearchTarget(row, index));
            }
            index++;
        }
        return targets;
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

    /**
     * Moves the active (strong) highlight to the next/previous match
     * (wrapping) and scrolls it into view — the node configuration panel's
     * chevron behavior. Only a real (non-empty) search supports navigation;
     * the filter-only flat list is not a search, so an empty query is a
     * no-op (the chevrons are hidden in that mode anyway).
     */
    private void navigateSearch(boolean next) {
        String query = searchMatchPanel.getSearchText().trim();
        if (query.isEmpty() || searchTargets.isEmpty()) {
            return;
        }
        int count = searchTargets.size();
        int oldIndex = searchActiveIndex;
        searchActiveIndex = next
                ? (searchActiveIndex + 1) % count
                : (searchActiveIndex - 1 + count) % count;
        if (oldIndex >= 0 && oldIndex < count && oldIndex != searchActiveIndex) {
            paintSearchMatch(searchTargets.get(oldIndex), query, false);
        }
        paintSearchMatch(searchTargets.get(searchActiveIndex), query, true);
        updateSearchMatchIndicator(formatSearchIndicator());
        showActiveSearchMatch();
    }

    /**
     * Highlights the exact matching part of ONE search target (the node
     * configuration panel's policy): a row-level target banded the query's
     * occurrence in the row label, in the value text component and/or in the
     * selected value of a non-editable select; an item target banded the
     * occurrence inside that single database-file list item's editor. The
     * active match uses the strong palette color, the others the soft one
     * (the same SSOT colors as the console "find").
     */
    private void paintSearchMatch(SearchTarget target, String query, boolean active) {
        Color color = active ? GuiColors.getSearchActiveMatch() : GuiColors.getSearchMatch();
        Highlighter.HighlightPainter painter =
                active ? SearchValueHighlight.PAINTER_ACTIVE : SearchValueHighlight.PAINTER;
        if (target.itemIndex() >= 0) {
            highlightFileListItem(target.itemIndex(), query, painter);
            return;
        }
        PropertyRow row = target.row();
        if (row.label instanceof SearchMatchLabel searchLabel) {
            String lowerQuery = query.toLowerCase(Locale.ROOT);
            int nameIdx = row.labelText.toLowerCase(Locale.ROOT).indexOf(lowerQuery);
            if (nameIdx >= 0) {
                searchLabel.setHighlightRange(nameIdx, query.length());
                searchLabel.setHighlightColor(color);
            }
        }
        highlightValueMatch(row, query, painter);
        highlightComboValueMatch(row, query, color);
    }

    /**
     * (Re)highlights the first occurrence of the query inside the row's
     * value text component with the given painter (soft for a plain match,
     * strong for the active match). At most one highlight per component —
     * the previous one is replaced.
     */
    private void highlightValueMatch(PropertyRow row, String query, Highlighter.HighlightPainter painter) {
        JTextComponent target = rowValueTextComponent(row);
        if (target == null) {
            return;
        }
        // Remove any previous highlight on this component
        Object previous = searchValueHighlights.remove(target);
        if (previous != null) {
            target.getHighlighter().removeHighlight(previous);
        }
        String value = target.getText();
        int idx = value.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        if (idx < 0) {
            return;
        }
        try {
            searchValueHighlights.put(target,
                    target.getHighlighter().addHighlight(idx, idx + query.length(), painter));
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

    /**
     * Shows the search-match band behind the selected value of the row's
     * non-editable combo box (a non-editable combo has no text component a
     * document highlight could live in, so its renderer is swapped for a
     * {@link ComboSearchHighlightRenderer} that paints the band). Only
     * called when the query matches the selected value; the original
     * renderer is remembered and restored by {@link #clearComboHighlights()}.
     */
    private void highlightComboValueMatch(PropertyRow row, String query, Color color) {
        if (!(row.input instanceof JComboBox<?> comboBox) || comboBox.isEditable()) {
            return;
        }
        Object selected = comboBox.getSelectedItem();
        if (selected == null) {
            return;
        }
        String value = selected.toString();
        int idx = value.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        if (idx < 0) {
            return;
        }
        if (!originalComboRenderers.containsKey(comboBox)) {
            originalComboRenderers.put(comboBox, comboBox.getRenderer());
        }
        ComboSearchHighlightRenderer renderer = new ComboSearchHighlightRenderer();
        renderer.setBand(query, color);
        renderer.setDelegateRenderer(comboBox.getRenderer());
        comboBox.setRenderer(renderer);
        comboBox.repaint();
    }

    /**
     * Restores the original renderer of every combo box that carries a
     * search band.
     */
    private void clearComboHighlights() {
        for (Map.Entry<JComboBox<?>, ListCellRenderer<?>> entry : originalComboRenderers.entrySet()) {
            JComboBox<?> comboBox = entry.getKey();
            if (comboBox.getRenderer() instanceof ComboSearchHighlightRenderer) {
                // The captured wildcard of JComboBox<?> does not line up with
                // setRenderer's bound — the renderer is re-erased at runtime.
                @SuppressWarnings({"unchecked", "rawtypes"})
                ListCellRenderer<Object> original = (ListCellRenderer) entry.getValue();
                comboBox.setRenderer(original);
                comboBox.repaint();
            }
        }
        originalComboRenderers.clear();
    }

    /**
     * (Re)highlights the first occurrence of the query inside ONE
     * database-file list item's editor text field with the given painter
     * (soft for a plain match, strong for the active one). At most one
     * highlight per editor — the previous one is replaced (the same
     * mechanism the node configuration panel uses for its list editors).
     */
    private void highlightFileListItem(int itemIndex, String query, Highlighter.HighlightPainter painter) {
        if (databaseFileEditor == null || itemIndex < 0
                || itemIndex >= databaseFileEditor.getItemFields().size()) {
            return;
        }
        JComboBox<String> combo = databaseFileEditor.getItemFields().get(itemIndex);
        if (combo.getEditor() == null
                || !(combo.getEditor().getEditorComponent() instanceof JTextComponent editor)) {
            return;
        }
        String itemText = editor.getText();
        int idx = itemText.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        if (idx < 0) {
            return;
        }
        // Remove any previous highlight on this editor
        Object previous = searchValueHighlights.remove(editor);
        if (previous != null) {
            editor.getHighlighter().removeHighlight(previous);
        }
        try {
            searchValueHighlights.put(editor,
                    editor.getHighlighter().addHighlight(idx, idx + query.length(), painter));
            editor.repaint();
        } catch (BadLocationException e) {
            // range outside the document bounds
        }
    }

    /** Removes every search highlight (label bands, value marks and combo bands) from all rows. */
    private void clearSearchHighlights() {
        for (PropertyRow row : allPropertyRows) {
            if (row.label instanceof SearchMatchLabel searchLabel) {
                searchLabel.clearHighlight();
            }
        }
        clearAllValueHighlights();
        clearComboHighlights();
    }

    /**
     * Brings the active match into view. The flat results list has a single
     * viewport, so the row is scrolled there (the reveal is retried for a few
     * event ticks because after a card switch the results viewport gets its
     * size only on the next layout pass), and — when the match lives in a
     * value — the highlighted occurrence is made visible inside the value
     * component (or the file-list item's editor).
     */
    private void showActiveSearchMatch() {
        if (searchActiveIndex < 0 || searchActiveIndex >= searchTargets.size()) {
            return;
        }
        SearchTarget target = searchTargets.get(searchActiveIndex);
        if (target.row().label == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> revealActiveMatch(target, 10));
    }

    /**
     * Scrolls the given row into view in the enclosing viewport and reveals
     * its value highlight. Retries while the viewport is not showing or has
     * no size yet (a card switch is laid out on the next validate event). The
     * view position is computed and clamped explicitly — the same arithmetic
     * as {@code JViewport#scrollRectToVisible}, but without relying on its
     * implementation silently doing nothing for an unsized/invalid viewport
     * (the node configuration panel's reveal behavior).
     */
    private void revealActiveMatch(SearchTarget target, int attemptsLeft) {
        JComponent rowComponent = target.row().label;
        JViewport viewport = null;
        for (Container c = rowComponent.getParent(); c != null; c = c.getParent()) {
            if (c instanceof JViewport) {
                viewport = (JViewport) c;
                break;
            }
        }
        if (viewport == null) {
            return;
        }
        if (!viewport.isShowing() || viewport.getExtentSize().height <= 0) {
            if (attemptsLeft > 0) {
                SwingUtilities.invokeLater(() -> revealActiveMatch(target, attemptsLeft - 1));
            }
            return;
        }
        // The row's bounds converted into the viewport's view coordinates.
        Rectangle bounds = rowComponent.getBounds();
        Container c = rowComponent;
        while (c != null && c != viewport) {
            Container parent = c.getParent();
            if (parent == null) {
                return;
            }
            bounds.translate(c.getX(), c.getY());
            c = parent;
        }
        Point position = viewport.getViewPosition();
        Dimension extent = viewport.getExtentSize();
        int targetY = position.y;
        if (bounds.y < position.y) {
            targetY = bounds.y;
        } else if (bounds.y + bounds.height > position.y + extent.height) {
            targetY = bounds.y + bounds.height - extent.height;
        }
        int maxY = Math.max(0, viewport.getViewSize().height - extent.height);
        targetY = Math.max(0, Math.min(maxY, targetY));
        viewport.setViewPosition(new Point(position.x, targetY));
        revealValueMatch(target);
    }

    /**
     * Makes the active match's value highlight visible. When the match lives
     * in a value (not a label) the highlighted occurrence can sit outside the
     * value component's own visible window (a long text field) — in that case
     * the component is scrolled to the occurrence. Moving the caret there
     * does not take focus and selects nothing; it only makes the highlighted
     * band visible (the node configuration panel's reveal behavior). For a
     * file-list item target the caret moves inside that item's editor.
     */
    private void revealValueMatch(SearchTarget target) {
        String query = searchMatchPanel.getSearchText().trim();
        if (query.isEmpty()) {
            return;
        }
        PropertyRow row = target.row();
        if (target.itemIndex() >= 0 && databaseFileEditor != null
                && target.itemIndex() < databaseFileEditor.getItemFields().size()) {
            JComboBox<String> combo = databaseFileEditor.getItemFields().get(target.itemIndex());
            if (combo.getEditor() != null
                    && combo.getEditor().getEditorComponent() instanceof JTextComponent editor) {
                int idx = editor.getText().toLowerCase(Locale.ROOT)
                        .indexOf(query.toLowerCase(Locale.ROOT));
                if (idx >= 0) {
                    editor.setCaretPosition(idx);
                }
            }
            return;
        }
        if (row.labelText.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
            return; // the label band is already visible
        }
        JTextComponent value = rowValueTextComponent(row);
        if (value == null) {
            return;
        }
        int idx = value.getText().toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        if (idx >= 0) {
            value.setCaretPosition(idx);
        }
    }

    private String formatSearchIndicator() {
        if (searchTargets.isEmpty()) {
            return null;
        }
        int idx = Math.max(0, searchActiveIndex) + 1;
        return idx + "/" + searchTargets.size();
    }

    private void updateSearchMatchIndicator(String text) {
        boolean hasMatches = text != null && !text.isEmpty();
        searchMatchPanel.setChevronsVisible(hasMatches);
        searchMatchPanel.setMatchIndicatorText(text);
    }

    /**
     * Restores every row part to its original parent in the original creation
     * order. The parts — INCLUDING the section-header labels — are removed
     * first and then re-added in the {@code allPropertyRows} order: leaving
     * the headers in place and re-appending the property rows would shuffle
     * the sections (every row would land after the last header).
     */
    private void restoreSettingsRows() {
        for (PropertyRow row : allPropertyRows) {
            if (row.label == null) {
                continue;
            }
            if (row.label.getParent() != null) {
                row.label.getParent().remove(row.label);
            }
            if (row.propertyKey == null) {
                continue; // a section header's label is its only part
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
            if (row.label == null) {
                continue;
            }
            row.originalParent.add(row.label, row.labelConstraints);
            if (row.propertyKey == null) {
                continue; // a section header's label is its only part
            }
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

    /** The state-aware select's dropdown recipe: the fixed option catalog and the row's saved baseline value. */
    private record StateAwareCombo(String[] options, String saved) {
    }

    private enum ValueStatus {
        UNSAVED,
        SAVED;

        /**
         * The palette color of this state — read LIVE from the palette on every
         * call (never cached): the state colors are theme-dependent GUI color
         * constants, so a theme / appearance change must refresh them (the node
         * configuration panel's ValueStatus follows the same rule).
         */
        public Color color() {
            return switch (this) {
                case UNSAVED -> GuiColors.getUnsaved();
                case SAVED -> GuiColors.getSaved();
            };
        }
    }

    private void addSectionHeader(JPanel panel, String title, boolean isFirst) {
        PropertyRow row = new PropertyRow(null, title, panel);
        JLabel label = new JLabel(title);
        label.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 14f));
        Color separatorColor = GuiColors.getSeparator();
        label.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, separatorColor));
        // The constraint is stored on the row so restoreSettingsRows can re-add
        // the header at its original position (a plain re-add would append it
        // after the re-added property rows and shuffle the sections).
        row.labelConstraints = (isFirst ? "" : "gaptop 15, ") + "span, growx, wrap, gapbottom 5";
        panel.add(label, row.labelConstraints);
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
        // Reset journal mode and cache size to their hardcoded defaults.
        for (String key : List.of(SQLiteProfile.CFG_JOURNAL_MODE, SQLiteProfile.CFG_CACHE_SIZE)) {
            JComponent component = propertyComponents.get(key);
            if (component instanceof JComboBox<?> combo) {
                String defaultVal = SQLiteProfile.DEFAULTS.getOrDefault(key, "");
                if (combo.isEditable()) {
                    combo.getEditor().setItem(defaultVal);
                } else {
                    // The non-editable select (the closed journal-mode set) has
                    // no editor — its value is selected from the catalog.
                    combo.setSelectedItem(defaultVal);
                }
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

            // Renamed rows RENAME the existing file on disk (with its WAL /
            // SHM / journal companions — the database content survives); no
            // new database file is created for a rename.
            renameModifiedDatabaseFiles();

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

    /**
     * Copies the editor values into the profile's stored configuration —
     * engine settings only. The database URL is a derived value and is
     * intentionally NOT stored (see {@link SQLiteProfile}).
     */
    private void syncControlsToProfile() {
        for (String key : List.of(SQLiteProfile.CFG_JOURNAL_MODE,
                SQLiteProfile.CFG_CACHE_SIZE)) {
            Supplier<String> supplier = valueSuppliers.get(key);
            if (supplier != null) {
                currentProfile.getConfiguration().put(key, supplier.get());
            }
        }
        currentProfile.getConfiguration().remove(SQLiteProfile.CFG_URL);
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

    /**
     * Renames the profile's database files whose editor rows were renamed:
     * the saved, on-disk file moves to the row's new name — the database
     * content survives, no new database is created for the rename (the node
     * configuration list editors' saved-item semantics, applied to files).
     * Runs on save, before the new files are materialized.
     * <p>
     * A rename whose target name already exists on disk (a file the rename
     * does not itself move away) is a conflict: saving is aborted with an
     * {@link IOException} instead of overwriting the existing database.
     * Renames that chain or cycle (A→B with B→A) are resolved by moving the
     * files in dependency order, via a temporary name for a pure cycle.
     *
     * @throws IOException when a rename target exists and would overwrite an
     *         unrelated database file, or a file move fails (for example the
     *         file is locked while the node runs with this profile)
     */
    void renameModifiedDatabaseFiles() throws IOException {
        List<String[]> renames = databaseFileEditor == null ? List.of() : databaseFileEditor.getRenamedFiles();
        if (renames.isEmpty()) {
            return;
        }
        Path baseDir = databaseDirectory == null
                ? getProfilePath(loadedProfileName)
                : PathUtils.resolvePath(databaseDirectory.toString());
        if (!Files.isDirectory(baseDir)) {
            return;
        }
        Set<String> onDisk = listDatabaseFileNames(baseDir);
        // The existing files that a pending rename moves AWAY (its sources):
        // an existing target is a valid destination only when the file
        // currently there is itself renamed away, the other existing targets
        // would be overwritten (conflict).
        Set<String> movedAway = new HashSet<>();
        for (String[] rename : renames) {
            if (onDisk.contains(rename[0] + ".db")) {
                movedAway.add(rename[0] + ".db");
            }
        }
        for (String[] rename : renames) {
            if (onDisk.contains(rename[1] + ".db") && !movedAway.contains(rename[1] + ".db")) {
                throw new IOException("Cannot rename '" + rename[0] + ".db' to '" + rename[1]
                        + ".db': the target file already exists.");
            }
        }
        applyDatabaseFileRenames(baseDir, new ArrayList<>(renames));
    }

    /**
     * The actual file moves of {@link #renameModifiedDatabaseFiles()}: each
     * pending rename runs as soon as its target name is free (an existing
     * occupant is a file another pending rename moves away first). What is
     * left after a pass with no progress is a pure rename cycle — broken
     * with a temporary name, the rest re-applied, then the temporary file
     * moved into its final name.
     */
    private void applyDatabaseFileRenames(Path baseDir, List<String[]> pending) throws IOException {
        boolean movedAny = false;
        for (int i = pending.size() - 1; i >= 0; i--) {
            String[] rename = pending.get(i);
            Path source = baseDir.resolve(rename[0] + ".db");
            if (!Files.exists(source)) {
                pending.remove(i); // never existed on disk — it is created as a new file
                continue;
            }
            Path target = baseDir.resolve(rename[1] + ".db");
            if (Files.exists(target)) {
                continue; // occupied by a file a later rename moves away
            }
            moveDatabaseFileWithCompanions(baseDir, rename[0] + ".db", rename[1] + ".db");
            pending.remove(i);
            movedAny = true;
        }
        if (pending.isEmpty()) {
            return;
        }
        if (!movedAny) {
            // A pure rename cycle (A→B and B→A): break it via a temporary name.
            String[] first = pending.remove(0);
            String tempName = first[0] + ".rename-tmp";
            moveDatabaseFileWithCompanions(baseDir, first[0] + ".db", tempName + ".db");
            applyDatabaseFileRenames(baseDir, pending);
            moveDatabaseFileWithCompanions(baseDir, tempName + ".db", first[1] + ".db");
            return;
        }
        applyDatabaseFileRenames(baseDir, pending);
    }

    /**
     * Moves one database file together with its SQLite companions
     * ({@code -wal}, {@code -shm}, {@code -journal}) when they exist — a
     * rename keeps the whole on-disk footprint of the database.
     *
     * @param baseDir the database directory the names resolve against
     * @param fromName the existing file name (with the {@code .db} extension)
     * @param toName the new file name (with the {@code .db} extension)
     * @throws IOException when a file cannot be moved (for example it is locked)
     */
    private void moveDatabaseFileWithCompanions(Path baseDir, String fromName, String toName) throws IOException {
        String[] suffixes = { "", "-wal", "-shm", "-journal" };
        for (String suffix : suffixes) {
            Path source = baseDir.resolve(fromName + suffix);
            if (Files.exists(source)) {
                Files.move(source, baseDir.resolve(toName + suffix));
            }
        }
    }

    /**
     * The {@code *.db} file names (with the conventional extension) in a
     * database directory: the WAL / SHM / journal companions are not
     * databases and are excluded.
     *
     * @param dir the database directory to scan (may not exist — then empty)
     * @return the database file names (possibly empty, never null)
     */
    private static Set<String> listDatabaseFileNames(Path dir) {
        Set<String> names = new HashSet<>();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(f -> {
                String name = f.getFileName().toString();
                return name.endsWith(".db")
                        && !name.endsWith(".db-wal")
                        && !name.endsWith(".db-shm")
                        && !name.endsWith(".db-journal");
            })
                    .forEach(f -> names.add(f.getFileName().toString()));
        } catch (IOException e) {
            logger.error("Failed to scan database directory for .db files: {}", dir, e);
        }
        return names;
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
            if (rowDirty(key)) {
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
        updateUnsavedProfileMarker();
        filterProperties(searchField == null ? "" : searchField.getText(), false);
    }

    /**
     * Keeps the profile selector's unsaved marker in sync: the loaded profile is
     * starred while {@link #hasUnsavedChanges()} holds, and any other name is
     * dropped (this panel keeps editor state for the loaded profile only — the
     * switch / save / delete / rename flows reload it, so a stale entry would
     * star a profile that no longer carries the change).
     */
    private void updateUnsavedProfileMarker() {
        if (profileComboBox == null) {
            return;
        }
        boolean changed = unsavedProfiles.removeIf(name -> !name.equals(loadedProfileName));
        if (loadedProfileName != null) {
            changed |= hasUnsavedChanges()
                    ? unsavedProfiles.add(loadedProfileName)
                    : unsavedProfiles.remove(loadedProfileName);
        }
        if (changed) {
            profileComboBox.repaint();
        }
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