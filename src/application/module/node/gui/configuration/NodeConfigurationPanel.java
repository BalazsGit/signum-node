package application.module.node.gui.configuration;
import application.utils.config.ModuleIds;

import application.module.logging.LoggingAssignmentStore;
import application.module.node.NodeModule;
import application.module.node.Signum;
import application.module.node.crypto.Crypto;
import application.module.node.gui.NodePanel;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.NodeProfileRepository;
import application.module.node.profile.ProfileCreateDefaults;
import application.module.node.profile.ProfileDiffCalculator;
import application.module.node.profile.ProfileNameSuggester;
import application.module.node.profile.ProfileRuntimeService;
import application.module.node.profile.ProfileConfig;
import application.module.node.profile.SqliteDatabasePaths;
import application.module.database.gui.DatabaseConfigurationPanel;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.Constants;
import application.module.node.props.Prop;
import application.module.node.props.Props;
import application.module.node.util.Convert;
import jiconfont.icons.font_awesome.FontAwesome;
import application.utils.gui.CheckboxGroupPanel;
import application.utils.gui.ComboSearchHighlightRenderer;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiUtils;
import application.utils.gui.HelpButton;
import application.utils.gui.HelpDialog;
import application.utils.gui.NewProfileChoiceDialog;
import application.utils.gui.ResponsiveToolbarScrollPane;
import application.utils.gui.SearchMatchLabel;
import application.utils.gui.SearchMatchPanel;
import application.utils.gui.SearchValueHighlight;
import application.utils.io.PathUtils;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import com.google.gson.*;

import java.awt.event.ActionListener;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NodeConfigurationPanel extends JPanel {

    private NodeProfile savedProfile;
    private NodeProfile appliedProfile;
    private static final Logger LOGGER = LoggerFactory.getLogger(NodeConfigurationPanel.class);
    private final Map<String, Supplier<String>> valueSuppliers = new HashMap<>();
    private final Map<String, JComponent> propertyComponents = new HashMap<>();

    /**
     * The value search highlight currently shown on each value component
     * (at most one per row; replaced on every search update).
     */
    private final Map<JTextComponent, Object> searchValueHighlights = new IdentityHashMap<>();

    /**
     * The original renderer of every non-editable combo box whose renderer
     * was swapped for a {@link ComboSearchHighlightRenderer} while a search
     * match band is shown on its selected value (restored on clear).
     */
    private final Map<JComboBox<?>, ListCellRenderer<?>> originalComboRenderers = new IdentityHashMap<>();

    /**
     * Painters for value search highlights — the palette's SSOT search colors
     * (the same ones the console "find" feature uses): the soft match color
     * for every match, the active color for the match being navigated to.
     * The actual painting is the shared {@link SearchValueHighlight} utility
     * (the same bands the module logging profile panels' row search uses).
     */
    static final Highlighter.HighlightPainter SEARCH_MATCH_PAINTER =
            SearchValueHighlight.PAINTER;

    static final Highlighter.HighlightPainter SEARCH_MATCH_PAINTER_ACTIVE =
            SearchValueHighlight.PAINTER_ACTIVE;

    private static final String KEY_PROFILE_LINKS = "profileLinks";
    private static final String KEY_DATABASE = "database";
    private static final String KEY_LOGGING = "logging";
    private static final String KEY_DB_AUTO_START = "dbAutoStart";
    private static final String KEY_DB_AUTO_STOP = "dbAutoStop";

    private final Map<String, String> helpTexts = new HashMap<>();
    private final Map<String, String> defaultValues = new HashMap<>();
    private final Runnable restartAction;

    // Database link UI (the "Linked Database Profile" controls of the JDBC
    // Connection URL row; the former separate "Linked Profiles" tab was
    // merged into the JDBC row — that control takes over its role)
    private JCheckBox autoStartDbCheck;
    private JCheckBox autoStopDbCheck;
    /** The DB engine's Auto Start/Stop row (server engines only — hidden for SQLite). */
    private JPanel autoDbRow;

    private String savedLinkedDb = "";
    /**
     * The database link the node runs (or will run) with — the applied
     * baseline of the link element in the JDBC row's tuple color coding.
     * Set on load (== {@code savedLinkedDb}, the loaded-file philosophy) and
     * refreshed by Apply, alongside {@code appliedProfile}.
     */
    private String appliedLinkedDb = "";
    private boolean savedDbAutoStart = false;
    private boolean savedDbAutoStop = false;

    private final Runnable backAction;
    private final Runnable switchAction;
    private final String confFolder;

    /**
     * Per-profile {@code Signum} facade injected by {@code NodeProfilePanel} (Facade
     * Pattern). Provides this node's own {@link application.module.node.instance.NodeCoreContext}
     * and, with it, the running {@code PropertyService}. In the multi-node architecture
     * each node profile owns its own facade, so this panel intentionally has <b>no</b>
     * global "active node" pointer. May be {@code null} until the node has started.
     */
    private Signum signum;
    private Path propertiesFile;
    /**
     * Host callback for the New Profile toolbar button: receives the choice
     * selected in the shared {@link NewProfileChoiceDialog} (launch the setup
     * wizard or create a new empty profile). {@code null} until the host
     * (the node profile window) wires it — with no handler the button shows a
     * notice instead of a dead click.
     */
    private java.util.function.Consumer<NewProfileChoiceDialog.Choice> newProfileHandler;

    /**
     * Wires the New Profile toolbar button to the host's creation paths.
     *
     * @param handler invoked on the EDT with the user's choice from the
     *                {@link NewProfileChoiceDialog} (wizard / empty profile)
     */
    public void setNewProfileHandler(java.util.function.Consumer<NewProfileChoiceDialog.Choice> handler) {
        this.newProfileHandler = handler;
    }
    private final java.util.List<PropertyRow> allPropertyRows = new ArrayList<>();
    /** Live search (console-style): the rows matching the current query. */
    private final List<PropertyRow> searchMatches = new ArrayList<>();
    private int searchActiveIndex = -1;
    /**
     * Unified search box (field, match counter, chevron buttons) shared with
     * the console's filter header — the "Search" titled box of the search row.
     */
    private SearchMatchPanel searchMatchPanel;
    /**
     * The "Show values" titled box holding the value-status visibility
     * checkboxes (the shared CheckboxGroupPanel, like the console header's
     * "Level" box). While all of its checkboxes are selected the group
     * filters nothing (tabbed view); any unchecked box switches to the flat
     * results view listing only the still-selected states.
     */
    private CheckboxGroupPanel statusPanel;
    /**
     * Value-status visibility checkboxes of the search panel (moved here from
     * the bottom legend row, which listed them as plain color boxes). Each
     * one is colored with its value-level state color and is selected by
     * default. While all three are selected everything is visible (tabbed
     * view); when any of them is not selected, the rows of the deselected
     * state(s) are neither listed nor displayed (flat results view, combined
     * with the search text when it is not empty).
     */
    private JCheckBox showUnsavedBox;
    private JCheckBox showSavedBox;
    private JCheckBox showAppliedBox;
    /**
     * Content container: the "TABS" card holds the tabbed configuration, the
     * "SEARCH" card a flat list of the matching rows from ALL tabs. While a
     * search is active the tabs are hidden and the matches are listed flat
     * (with live highlighting); clearing the search field brings the tabbed
     * view back.
     */
    private CardLayout contentCardLayout;
    private JPanel contentContainer;
    private JPanel searchResultsPanel;
    /** True while the "SEARCH" card is up (row parts are living in the results list). */
    private boolean searchViewActive = false;
    /** The match set the flat results list currently displays (skips no-op rebuilds). */
    private List<PropertyRow> lastListedMatches;
    private JTabbedPane categoryTabbedPane;
    private JButton newProfileBtn;
    private JButton saveBtn;
    private JButton applyBtn;
    private JButton helpBtn;
    private boolean overallDirty = false;
    private JButton renameProfileBtn;
    private JButton deleteProfileBtn;
    private JButton copyProfileDataBtn;
    private JButton cloneProfileBtn;
    private JButton reloadProfileBtn;
    private JButton resetToDefaultsBtn;
    private String runningProfileName;
    private String loadedProfileName;
    private int currentAddingTabIndex = -1;
    /** The category tab index of the Database tab (hosts the JDBC link row). */
    private int databaseTabIndex = -1;
    private boolean isProgrammaticChange = false;

    public NodeConfigurationPanel(Runnable restartAction, String confFolder, Runnable backAction,
            Runnable switchAction) {
        this(restartAction, confFolder, backAction, switchAction, null);
    }

    /**
     * Creates a configuration panel for a specific node profile.
     * <p>
     * In the multi-node architecture there is no "active node": each panel belongs to
     * a specific node. Pass the node's own profile name explicitly so the panel does
     * not have to fall back to a global lookup.
     * </p>
     *
     * @param restartAction restart callback
     * @param confFolder    configuration folder
     * @param backAction    back callback (may be null)
     * @param switchAction  switch callback (may be null)
     * @param profileName   the node's own profile name, or {@code null} to fall back to
     *                      the legacy global lookup
     */
    public NodeConfigurationPanel(Runnable restartAction, String confFolder, Runnable backAction,
            Runnable switchAction, String profileName) {
        super(new BorderLayout());
        LOGGER.debug("NodeConfigurationPanel constructor START");
        this.restartAction = restartAction;
        this.confFolder = confFolder;
        this.backAction = backAction;
        this.switchAction = switchAction;

        // Determine the currently applied profile name once at startup. Prefer the
        // explicitly passed node profile name (per-node ownership); only fall back to
        // the legacy global lookup when none was provided (single-node / headless).
        String resolvedProfileName = (profileName != null && !profileName.isEmpty())
                ? profileName
                : Signum.PROPERTIES_NAME;
        this.runningProfileName = resolvedProfileName;
        this.loadedProfileName = this.runningProfileName;

        // Use the unified profile loader to resolve the properties file path
        this.propertiesFile = ConfigurationUtils.resolveNodeProfilePath(this.loadedProfileName);
        ConfigurationUtils.ensureConfigFileExists(this.propertiesFile);

        this.savedProfile = new NodeProfile(this.loadedProfileName);
        try (FileInputStream in = new FileInputStream(propertiesFile.toFile())) {
            this.savedProfile.getProperties().load(in);
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.appliedProfile = new NodeProfile(this.loadedProfileName);
        // The values LOADED from the profile file ARE the applied values (the
        // applied baseline of the value-status coloring): this node runs — or
        // will run — with exactly this profile, so whatever the profile
        // loaded into the editor states is what the node uses. The baseline is
        // refreshed when the profile is reloaded from disk or the changes are
        // saved AND applied. It is deliberately NOT populated from the live
        // PropertyService: its string form can drift from the file's form (and
        // it is empty until the node starts, falling back to the application
        // defaults), which used to color freshly loaded profile values "saved"
        // instead of "applied".
        this.appliedProfile.setProperties(copyProperties(this.savedProfile.getProperties()));

        this.renameProfileBtn = new JButton();
        this.deleteProfileBtn = new JButton();
        this.categoryTabbedPane = new JTabbedPane();
        // Same scroll-type tab layout as every other tabbed pane in the app
        // (GuiManager policy from gui-settings.json, SCROLL by default).
        GuiUtils.applyDefaultTabLayoutPolicy(categoryTabbedPane);
        // No border around the tabbed pane itself: the only vertical spacing
        // between the search panel above and the tab area must be the search
        // panel's own EmptyBorder (the L&F default border would add more).
        this.categoryTabbedPane.setBorder(BorderFactory.createEmptyBorder());
        // Initialize buttons early to avoid NullPointerException in listeners during UI
        // construction. The toolbar is icon-only: the tooltip is the single source of
        // information about each action (F0 of the profile-actions refactor plan).
        this.newProfileBtn = new JButton();
        this.saveBtn = new JButton();
        this.applyBtn = new JButton();
        this.helpBtn = new HelpButton();
        
        LOGGER.debug("NodeConfigurationPanel - calling loadAppliedProperties()");
        loadAppliedProperties();
        
        LOGGER.debug("NodeConfigurationPanel - deferring initHelpTexts/initUI/loadProfileLinks to EDT (async)");
        
        // Defer heavy UI construction + profile link loading to EDT to avoid blocking the constructor.
        // initHelpTexts is ~1500 lines of HashMap.put calls, initUI builds 3000+ components,
        // and loadProfileLinks needs the JDBC link row built by initUI.
        SwingUtilities.invokeLater(() -> {
            try {
                LOGGER.debug("NodeConfigurationPanel - async initHelpTexts START");
                initHelpTexts();
                LOGGER.debug("NodeConfigurationPanel - async initHelpTexts DONE");
                
                LOGGER.debug("NodeConfigurationPanel - async initUI START");
                initUI();
                LOGGER.debug("NodeConfigurationPanel - async initUI DONE");
                
                LOGGER.debug("NodeConfigurationPanel - async loadProfileLinks START for: {}", loadedProfileName);
                loadProfileLinks(loadedProfileName);
                LOGGER.debug("NodeConfigurationPanel - async loadProfileLinks DONE");
            } catch (Exception e) {
                LOGGER.error("NodeConfigurationPanel - async init FAILED", e);
            }
        });
        
        LOGGER.debug("NodeConfigurationPanel constructor returned (UI will be built asynchronously)");
    }

    /**
     * Runs a profile toolbar action with a defensive guard: a failure (e.g. an
     * error while constructing its dialog) is logged and surfaced in an error
     * dialog instead of dying inside the button listener as a silent no-op.
     */
    private void runProfileAction(String actionName, Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            LOGGER.error("{} failed for profile '{}'", actionName, loadedProfileName, ex);
            JOptionPane.showMessageDialog(this, actionName + " failed: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void initUI() {
        JPanel bodyPanel = new JPanel(new BorderLayout());

        // --- Profile Panel (the shared profile-action toolbar row: identical
        //     button size / gap / margins to the module logging profile panels
        //     and the node profile toolbar) ---
        JPanel profilePanel = ConfigurationUtils.createProfileToolbarRow();

        newProfileBtn.setToolTipText(
                "<html>New Profile<br><br>Opens the dialog to launch the setup wizard<br>"
                        + "or to create a new empty profile.</html>");
        newProfileBtn.addActionListener(e -> runProfileAction("New Profile", this::newProfile));
        profilePanel.add(newProfileBtn);

        // Clone sits right after New Profile: both are "create a profile"
        // actions and belong next to each other.
        cloneProfileBtn = new JButton();
        cloneProfileBtn.setToolTipText(
                "<html>Clone Configuration<br><br>Creates a new profile from the current (unsaved, editor) "
                        + "effective state: only the values that differ from the default are copied.<br>"
                        + "The new profile is not started; the source profile is left untouched.</html>");
        cloneProfileBtn.addActionListener(e -> runProfileAction("Clone Configuration", this::cloneProfile));
        profilePanel.add(cloneProfileBtn);

        saveBtn.setToolTipText(
                "<html>Save<br><br>Saves all unsaved changes to the current profile<br>"
                        + "(reviewing them in a dialog first). Does NOT restart the node.<br>"
                        + "Active only while there are unsaved changes.</html>");
        saveBtn.setEnabled(false);
        saveBtn.addActionListener(e -> runProfileAction("Save", this::saveProfile));
        profilePanel.add(saveBtn);

        applyBtn.setToolTipText(
                "<html>Apply<br><br>Applies the saved changes: marks the profile as applied<br>"
                        + "and restarts the node so they take effect.<br>"
                        + "Active only while there are saved, not yet applied changes.</html>");
        applyBtn.setEnabled(false);
        applyBtn.addActionListener(e -> runProfileAction("Apply", this::applyProfile));
        profilePanel.add(applyBtn);

        renameProfileBtn.setToolTipText(
                "<html>Rename Profile<br><br>Renames this profile (including its data paths).<br>"
                        + "If the node is running it will be stopped during the rename<br>"
                        + "and returned to its previous state (running / paused / stopped).</html>");
        renameProfileBtn.addActionListener(e -> runProfileAction("Rename Profile",
                () -> renameProfile(loadedProfileName)));
        profilePanel.add(renameProfileBtn);

        resetToDefaultsBtn = new JButton();
        resetToDefaultsBtn.setToolTipText(
                "<html>Reset to Defaults<br><br>Resets all fields in the editor to the application default values.<br>"
                        + "Nothing is saved — use Save to persist the reset settings.</html>");
        resetToDefaultsBtn.addActionListener(e -> runProfileAction("Reset to Defaults", this::resetToDefaults));
        profilePanel.add(resetToDefaultsBtn);

        copyProfileDataBtn = new JButton();
        copyProfileDataBtn.setToolTipText(
                "<html>Copy Configuration<br><br>Copies another profile's configuration into this editor.<br>"
                        + "Unsaved changes in the editor are discarded (with confirmation).</html>");
        copyProfileDataBtn.addActionListener(e -> runProfileAction("Copy Configuration", this::copyProfileData));
        profilePanel.add(copyProfileDataBtn);

        reloadProfileBtn = new JButton();
        reloadProfileBtn.setToolTipText(
                "<html>Reload Configuration<br><br>Re-reads the current profile file from disk (e.g. after external edits).<br>"
                        + "Unsaved changes in the editor are discarded.</html>");
        reloadProfileBtn.addActionListener(e -> runProfileAction("Reload Configuration", this::reloadProfile));
        profilePanel.add(reloadProfileBtn);

        deleteProfileBtn.setToolTipText(
                "<html>Delete Profile<br><br>Permanently deletes this profile, its settings<br>"
                        + "(optionally its database files) and closes its tab.<br>"
                        + "If the node is running it will be stopped (and not restarted).</html>");
        deleteProfileBtn.addActionListener(e -> runProfileAction("Delete Profile",
                () -> deleteProfile(loadedProfileName)));
        profilePanel.add(deleteProfileBtn);

        updateProfileButtonsUI();

        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("View detailed information about configuration profile management");
        helpBtn.addActionListener(e -> HelpDialog.show(this, "Node Configuration Profiles",
                buildConfigurationHelpContent()));
        profilePanel.add(helpBtn);

        // The standard responsive toolbar wrapper (shared insets/policies —
        // identical margins to the logging profile panels' toolbar).
        JScrollPane profileScrollPane = ConfigurationUtils.wrapProfileToolbarRow(profilePanel);

        // --- Search panel: the unified SearchMatchPanel shared with the
        // console's filter header — a compact, left-aligned "Search" titled
        // box (16-column field, match counter, chevron buttons) instead of a
        // full-width bar. The match counter and the chevrons start hidden;
        // updateSearchMatchIndicator drives them from the live match state.
        searchMatchPanel = new SearchMatchPanel(
                "Text to find in the configuration — property names, property keys, or values (live highlighting)");
        searchMatchPanel.setSearchTextListener(text -> applySearch(text, true));
        searchMatchPanel.setSearchNavigationListener(this::navigateSearch);
        // Enter: jump to / advance the active match (same as the console).
        searchMatchPanel.setEnterListener(() -> navigateSearch(true));

        // Value-status visibility checkboxes in their own compact titled box
        // (the shared CheckboxGroupPanel, like the console filter header's
        // "Level" box), placed BEFORE the "Search" box: moved here from the
        // bottom legend row, which listed them as plain color boxes plus the
        // help button. Each is colored with its value-level state color and
        // selected by default (everything visible, tabbed view). Unchecking
        // any of them lists only the rows whose state is still selected
        // (flat results view, combined with the search text when it is not
        // empty). They are a pure visibility FILTER, not a search: with an
        // empty search text the search itself (highlighting, match counter,
        // chevrons) stays off.
        statusPanel = new CheckboxGroupPanel("Show values");
        statusPanel.setChangeListener(button -> applySearch(searchMatchPanel.getSearchText(), true));

        showUnsavedBox = statusPanel.addCheckbox("Unsaved values", GuiColors.getUnsaved(), true);
        showUnsavedBox.setToolTipText(
                "Show / hide the values that have unsaved changes. Combined with the search text when it is not empty.");

        showSavedBox = statusPanel.addCheckbox("Saved values", GuiColors.getSaved(), true);
        showSavedBox.setToolTipText(
                "Show / hide the values that are saved in the profile but differ from the running node.");

        showAppliedBox = statusPanel.addCheckbox("Applied values", GuiColors.getApplied(), true);
        showAppliedBox.setToolTipText(
                "Show / hide the values that match exactly what the running node is using.");

        JButton colorHelpBtn = new HelpButton();
        colorHelpBtn.setToolTipText("Detailed Color Legend");
        colorHelpBtn.addActionListener(e -> showColorLegendHelp());
        statusPanel.add(colorHelpBtn);

        // The search box is wrapped in the same responsive toolbar scroll
        // wrapper the profile row and the console filter header use, and added
        // as CENTER (like the console): it receives the row's full available
        // width, so when the window narrows past the box's content width a
        // horizontal scrollbar appears below it. (A WEST placement would keep
        // the wrapper's preferred width and clip the overflow instead.) The
        // thin 4px empty border lives on the WRAPPER (not the box): it clears
        // the row above and the possibly appearing scrollbar below.
        JPanel searchRow = new JPanel(new BorderLayout());
        // A transparent left-flow wrapper between the scroll pane and the
        // titled box: the scroll pane's viewport stretches its own
        // (BorderLayout) content wrapper to the row's full width, so without
        // this the titled box — and its border — would span the entire
        // window. The wrapper absorbs the stretch and keeps the border
        // hugging the box's content.
        JPanel searchPanelWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        searchPanelWrap.setOpaque(false);
        // The two compact titled boxes sit side by side (like the console
        // filter header's search + filter boxes): the value-status filter
        // box first, the search box second.
        searchPanelWrap.add(statusPanel);
        searchPanelWrap.add(searchMatchPanel);
        JScrollPane searchScroll = new ResponsiveToolbarScrollPane(searchPanelWrap, new Insets(0, 10, 0, 5), false);
        searchScroll.setBorder(new EmptyBorder(4, 0, 4, 0));
        searchRow.add(searchScroll, BorderLayout.CENTER);
        // The 5px toolbar-to-search-row vgap and the NORTH/CENTER placement
        // mirror the module logging profile panels' top area (a
        // BorderLayout(0, 5) wrapper with the toolbar NORTH and the search
        // row CENTER), so the toolbar row and the search row land at the same
        // vertical positions in both tabs. (A NORTH/SOUTH pair in a plain
        // BorderLayout would sit flush — no gap — and be 5px off.)
        JPanel northPanel = new JPanel(new BorderLayout(0, 5));
        northPanel.add(profileScrollPane, BorderLayout.NORTH);
        northPanel.add(searchRow, BorderLayout.CENTER);
        bodyPanel.add(northPanel, BorderLayout.NORTH);

        // No border around the tabbed pane itself

        // Clear list before rebuilding UI (in case of re-init)
        allPropertyRows.clear();
        searchMatches.clear();
        searchActiveIndex = -1;
        searchViewActive = false;
        lastListedMatches = null;

        LOGGER.debug("initUI - START");
        
        // --- API Server Settings ---
        LOGGER.debug("initUI - Building API tab");
        currentAddingTabIndex = 0;
        JPanel apiPanel = createCategoryPanel();
        addProperty(apiPanel, Props.API_SERVER, "Enable API Server");
        addProperty(apiPanel, Props.API_PORT, "API Port");
        addProperty(apiPanel, Props.API_LISTEN, "API Listen Interface");
        addListProperty(apiPanel, Props.API_ALLOWED, "Allowed IPs");
        addListProperty(apiPanel, Props.API_ADMIN_KEY_LIST, "Admin Password (API Key)");
        addProperty(apiPanel, Props.API_SSL, "Enable SSL");
        addProperty(apiPanel, Props.API_DOC_MODE, "API Documentation Mode", new String[] { "modern", "legacy", "off" });
        addProperty(apiPanel, Props.API_UI_DIR, "UI Directory");
        addProperty(apiPanel, Props.API_WEBSOCKET_ENABLE, "Enable WebSockets");
        addProperty(apiPanel, Props.API_WEBSOCKET_PORT, "WebSocket Port");
        addProperty(apiPanel, Props.API_WEBSOCKET_HEARTBEAT_INTERVAL, "WebSocket Heartbeat Interval");
        addListProperty(apiPanel, Props.API_ALLOWED_ORIGINS, "CORS Allowed Origins");
        addProperty(apiPanel, Props.API_ACCEPT_SURPLUS_PARAMS, "Accept Surplus Params");
        addProperty(apiPanel, Props.API_SERVER_ENFORCE_POST, "Enforce POST");
        addProperty(apiPanel, Props.API_SERVER_IDLE_TIMEOUT, "Idle Timeout");
        addProperty(apiPanel, Props.API_SSL_KEY_STORE_PATH, "SSL KeyStore Path");
        addPasswordProperty(apiPanel, Props.API_SSL_KEY_STORE_PASSWORD, "SSL KeyStore Password");
        addProperty(apiPanel, Props.API_SSL_LETSENCRYPT_PATH, "SSL LetsEncrypt Path");
        finalizeCategoryPanel(apiPanel);
        categoryTabbedPane.addTab("API Settings", createScrollPane(apiPanel));
        LOGGER.debug("initUI - API tab done");

        // --- Database Settings ---
        LOGGER.debug("initUI - Building Database tab");
        currentAddingTabIndex = 1;
        databaseTabIndex = currentAddingTabIndex;
        // Tighter row gap than the other tabs: the database entries should
        // read as one continuous list.
        JPanel dbPanel = createCategoryPanel(3);
        addJdbcUrlProperty(dbPanel, (Prop<String>) Props.DB_URL, "JDBC Connection URL");
        addProperty(dbPanel, Props.DB_CONNECTIONS, "Max Connections");
        addProperty(dbPanel, Props.DB_ARCHIVAL_MODE, "Archival Mode",
                new String[] { "ARCHIVE", "TRIM", "PRUNE" }, false);
        addProperty(dbPanel, Props.DB_OPTIMIZE, "Optimize DB on Start/Stop");
        addProperty(dbPanel, Props.DB_SQLITE_JOURNAL_MODE, "SQLite Journal Mode",
                new String[] { "WAL", "DELETE", "TRUNCATE", "PERSIST", "MEMORY", "OFF" });
        addProperty(dbPanel, Props.DB_SQLITE_SYNCHRONOUS, "SQLite Synchronous",
                new String[] { "NORMAL", "FULL", "OFF" });
        addProperty(dbPanel, Props.DB_SQLITE_CACHE_SIZE, "SQLite Cache Size");
        addProperty(dbPanel, Props.DB_INSERT_BATCH_MAX_SIZE, "DB Insert Batch Size");
        addProperty(dbPanel, Props.DB_SKIP_CHECK, "Skip DB Check on Start");
        addProperty(dbPanel, Props.DB_CONNECTION_RETRY_ENABLED, "DB Connection Retry");
        addProperty(dbPanel, Props.DB_CONNECTION_RETRY_MAX_ATTEMPTS, "DB Connection Retry Max Attempts");
        addProperty(dbPanel, Props.DB_CONNECTION_RETRY_INTERVAL_MS, "DB Connection Retry Interval (ms)");
        addProperty(dbPanel, Props.NODE_BLOCK_CACHE_MB, "Block Cache (MB)");
        finalizeCategoryPanel(dbPanel);
        categoryTabbedPane.addTab("Database", createScrollPane(dbPanel));
        LOGGER.debug("initUI - Database tab done");

        // --- P2P Networking ---
        LOGGER.debug("initUI - Building P2P tab");
        currentAddingTabIndex = 2;
        JPanel p2pPanel = createCategoryPanel();
        addProperty(p2pPanel, Props.P2P_PORT, "P2P Port");
        addProperty(p2pPanel, Props.P2P_LISTEN, "P2P Listen Interface");
        addProperty(p2pPanel, Props.P2P_UPNP, "Enable UPnP");
        addProperty(p2pPanel, Props.P2P_MY_PLATFORM, "My Platform");
        addProperty(p2pPanel, Props.P2P_MY_ADDRESS, "My External Address");
        addProperty(p2pPanel, Props.P2P_SHARE_MY_ADDRESS, "Share My Address");
        addListProperty(p2pPanel, Props.P2P_BOOTSTRAP_PEERS, "Bootstrap Peers");
        addListProperty(p2pPanel, Props.P2P_REBROADCAST_TO, "Rebroadcast To");
        addProperty(p2pPanel, Props.P2P_NUM_BOOTSTRAP_CONNECTIONS, "Num Bootstrap Connections");
        addListProperty(p2pPanel, Props.P2P_BLACKLISTED_PEERS, "Blacklisted Peers");
        addProperty(p2pPanel, Props.P2P_MAX_CONNECTIONS, "Max Connections");
        addProperty(p2pPanel, Props.P2P_MAX_BLOCKS, "Max Blocks per Req");
        addProperty(p2pPanel, Props.P2P_TIMEOUT_CONNECT_MS, "Connect Timeout (ms)");
        addProperty(p2pPanel, Props.P2P_TIMEOUT_READ_MS, "Read Timeout (ms)");
        addProperty(p2pPanel, Props.P2P_TIMEOUT_IDLE_MS, "Idle Timeout (ms)");
        addProperty(p2pPanel, Props.P2P_BLACKLISTING_TIME_MS, "Blacklisting Time (ms)");
        addProperty(p2pPanel, Props.P2P_ENABLE_TX_REBROADCAST, "Enable Tx Rebroadcast");
        addProperty(p2pPanel, Props.P2P_USE_PEERS_DB, "Use Peers DB");
        addProperty(p2pPanel, Props.P2P_SAVE_PEERS, "Save Peers");
        addProperty(p2pPanel, Props.P2P_GET_MORE_PEERS, "Get More Peers");
        addProperty(p2pPanel, Props.P2P_GET_MORE_PEERS_THRESHOLD, "Get More Peers Threshold");
        addProperty(p2pPanel, Props.P2P_SEND_TO_LIMIT, "Send To Limit");
        addProperty(p2pPanel, Props.P2P_MAX_UNCONFIRMED_TRANSACTIONS, "Max Unconfirmed Txs");
        addProperty(p2pPanel, Props.P2P_MAX_PERCENTAGE_UNCONFIRMED_TRANSACTIONS_FULL_HASH_REFERENCE,
                "Max Unconfirmed Txs Full Hash Ref %");
        addProperty(p2pPanel, Props.P2P_MAX_UNCONFIRMED_TRANSACTIONS_RAW_SIZE_BYTES_TO_SEND,
                "Max Unconfirmed Txs Raw Size Bytes");
        finalizeCategoryPanel(p2pPanel);
        categoryTabbedPane.addTab("P2P Networking", createScrollPane(p2pPanel));
        LOGGER.debug("initUI - P2P tab done");

        // --- Mining & GPU ---
        LOGGER.debug("initUI - Building Mining tab");
        currentAddingTabIndex = 3;
        JPanel miningPanel = createCategoryPanel();
        addProperty(miningPanel, Props.GPU_ACCELERATION, "Enable GPU Acceleration");
        addProperty(miningPanel, Props.GPU_AUTODETECT, "Auto-Detect GPU");
        addProperty(miningPanel, Props.GPU_PLATFORM_IDX, "GPU Platform Index");
        addProperty(miningPanel, Props.GPU_DEVICE_IDX, "GPU Device Index");
        addProperty(miningPanel, Props.GPU_MEM_PERCENT, "GPU Memory Usage (%)");
        addProperty(miningPanel, Props.GPU_UNVERIFIED_QUEUE, "Unverified Queue Size");
        addProperty(miningPanel, Props.GPU_DYNAMIC_HASHES_PER_BATCH, "Dynamic Hashes Per Batch");
        addProperty(miningPanel, Props.GPU_HASHES_PER_BATCH, "Hashes Per Batch");
        addListProperty(miningPanel, Props.SOLO_MINING_PASSPHRASES, "Solo Mining Passphrases");
        addListProperty(miningPanel, Props.REWARD_RECIPIENT_PASSPHRASES, "Reward Recipient Passphrases");
        addProperty(miningPanel, Props.ALLOW_OTHER_SOLO_MINERS, "Allow Other Solo Miners");
        finalizeCategoryPanel(miningPanel);
        categoryTabbedPane.addTab("Mining & GPU", createScrollPane(miningPanel));
        LOGGER.debug("initUI - Mining tab done");

        // --- System & Advanced ---
        LOGGER.debug("initUI - Building System tab");
        currentAddingTabIndex = 4;
        JPanel systemPanel = createCategoryPanel();
        addProperty(systemPanel, Props.APPLICATION, "Application Name");
        addProperty(systemPanel, Props.VERSION, "Node Version");
        addProperty(systemPanel, Props.NODE_AUTOSTART, "Auto-Start on App Launch");
        addProperty(systemPanel, Props.NETWORK_NAME, "Network Name");
        addProperty(systemPanel, Props.CPU_NUM_CORES, "CPU Cores Limit");
        addProperty(systemPanel, Props.BLOCK_PROCESS_THREAD_DELAY, "Thread Delay (ms)");
        addProperty(systemPanel, Props.MAX_INDIRECTS_PER_BLOCK, "Max Indirects Per Block");
        addProperty(systemPanel, Props.EXPERIMENTAL, "Enable Experimental Features");
        addProperty(systemPanel, Props.MEASUREMENT_ACTIVE, "Enable Metrics/Measurement");
        addProperty(systemPanel, Props.MEASUREMENT_DIR, "Measurement Dir");
        addProperty(systemPanel, Props.SETTINGS_DIR, "Settings Dir");
        addProperty(systemPanel, Props.ICON_LOCATION, "Icon Location");
        addProperty(systemPanel, Props.AUTO_POP_OFF_ENABLED, "Enable Auto Pop-Off");
        addProperty(systemPanel, Props.INITIAL_POP_OFF_ENABLED, "Enable Initial Safety Pop-Off");
        addProperty(systemPanel, Props.POP_OFF_SKIP_DB_CHECK, "Skip DB Check on Manual Pop-Off");
        addProperty(systemPanel, Props.AUTO_CONSISTENCY_RESOLVE_ENABLED, "Enable Auto DB Resolve");
        addProperty(systemPanel, Props.INDIRECT_INCOMING_SERVICE_ENABLE, "Enable Indirect Incoming Service");
        addProperty(systemPanel, Props.NODE_AT_PROCESSOR_CACHE_BLOCK_COUNT, "AT Processor Cache (Blocks)");
        addProperty(systemPanel, Props.NODE_SHUTDOWN_TIMEOUT, "Shutdown Timeout (sec)");
        addProperty(systemPanel, Props.NODE_CHECKPOINT_HEIGHT, "Checkpoint Height");
        addProperty(systemPanel, Props.NODE_CHECKPOINT_HASH, "Checkpoint Hash");
        addListProperty(systemPanel, Props.NODE_PK_CHECKS, "PK Checks");
        addProperty(systemPanel, Props.ENABLE_AT_DEBUG_LOG, "Enable AT Debug Log");
        addProperty(systemPanel, Props.CASH_BACK_ID, "Cash Back ID");
        addProperty(systemPanel, Props.CASH_BACK_FACTOR, "Cash Back Factor");
        addProperty(systemPanel, Props.ALIAS_RENEWAL_FREQUENCY, "Alias Renewal Frequency");
        finalizeCategoryPanel(systemPanel);
        categoryTabbedPane.addTab("System & Advanced", createScrollPane(systemPanel));
        LOGGER.debug("initUI - System tab done");

        // --- Dev & Debug ---
        LOGGER.debug("initUI - Building Dev tab");
        currentAddingTabIndex = 5;
        JPanel devPanel = createCategoryPanel();
        addProperty(devPanel, Props.DEV_OFFLINE, "Offline Mode");
        addProperty(devPanel, Props.DEV_TIMEWARP, "Time Warp");
        addProperty(devPanel, Props.DEV_MOCK_MINING, "Mock Mining");
        addProperty(devPanel, Props.DEV_MOCK_MINING_DEADLINE, "Mock Mining Deadline");
        addProperty(devPanel, Props.NODE_TEST_UNCONFIRMED_TRANSACTIONS, "Test Unconfirmed Txs");
        addProperty(devPanel, Props.DEV_DUMP_PEERS_VERSION, "Dump Peers Version");
        addProperty(devPanel, Props.NODE_DEBUG_TRACE_ENABLED, "Debug Trace Enabled");
        addProperty(devPanel, Props.NODE_DEBUG_TRACE_QUOTE, "Debug Trace Quote");
        addProperty(devPanel, Props.NODE_DEBUG_TRACE_SEPARATOR, "Debug Trace Separator");
        addProperty(devPanel, Props.NODE_DEBUG_LOG_CONFIRMED, "Log Confirmed");
        addListProperty(devPanel, Props.NODE_DEBUG_TRACE_ACCOUNTS, "Debug Trace Accounts");
        addProperty(devPanel, Props.NODE_DEBUG_TRACE_LOG, "Debug Trace Log File");
        addProperty(devPanel, Props.NODE_COMMUNICATION_LOGGING_MASK, "Communication Logging Mask");
        finalizeCategoryPanel(devPanel);
        categoryTabbedPane.addTab("Dev & Debug", createScrollPane(devPanel));
        LOGGER.debug("initUI - Dev tab done");

        // --- Jetty Server ---
        LOGGER.debug("initUI - Building Jetty tab");
        currentAddingTabIndex = 6;
        JPanel jettyPanel = createCategoryPanel();
        addProperty(jettyPanel, Props.JETTY_API_GZIP_FILTER, "API Gzip Filter");
        addProperty(jettyPanel, Props.JETTY_API_GZIP_FILTER_MIN_GZIP_SIZE, "API Gzip Min Size");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER, "API DoS Filter");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_MAX_REQUEST_PER_SEC, "API DoS Max Req/Sec");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_THROTTLED_REQUESTS, "API DoS Throttled Reqs");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_DELAY_MS, "API DoS Delay (ms)");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_MAX_WAIT_MS, "API DoS Max Wait (ms)");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_MAX_REQUEST_MS, "API DoS Max Req (ms)");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_THROTTLE_MS, "API DoS Throttle (ms)");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_MAX_IDLE_TRACKER_MS, "API DoS Max Idle Tracker (ms)");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_TRACK_SESSIONS, "API DoS Track Sessions");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_INSERT_HEADERS, "API DoS Insert Headers");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_REMOTE_PORT, "API DoS Remote Port");
        addListProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_IP_WHITELIST, "API DoS IP Whitelist");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_MANAGED_ATTR, "API DoS Managed Attr");

        addProperty(jettyPanel, Props.JETTY_P2P_GZIP_FILTER, "P2P Gzip Filter");
        addProperty(jettyPanel, Props.JETTY_P2P_GZIP_FILTER_MIN_GZIP_SIZE, "P2P Gzip Min Size");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER, "P2P DoS Filter");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_MAX_REQUESTS_PER_SEC, "P2P DoS Max Req/Sec");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_THROTTLED_REQUESTS, "P2P DoS Throttled Reqs");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_DELAY_MS, "P2P DoS Delay (ms)");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_MAX_WAIT_MS, "P2P DoS Max Wait (ms)");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_MAX_REQUEST_MS, "P2P DoS Max Req (ms)");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_THROTTLE_MS, "P2P DoS Throttle (ms)");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_MAX_IDLE_TRACKER_MS, "P2P DoS Max Idle Tracker (ms)");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_TRACK_SESSIONS, "P2P DoS Track Sessions");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_INSERT_HEADERS, "P2P DoS Insert Headers");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_REMOTE_PORT, "P2P DoS Remote Port");
        addListProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_IP_WHITELIST, "P2P DoS IP Whitelist");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_MANAGED_ATTR, "P2P DoS Managed Attr");
        finalizeCategoryPanel(jettyPanel);
        categoryTabbedPane.addTab("Jetty Server", createScrollPane(jettyPanel));
        LOGGER.debug("initUI - Jetty tab done");

        // --- Network Constants ---
        LOGGER.debug("initUI - Building Network tab");
        currentAddingTabIndex = 7;
        JPanel netPanel = createCategoryPanel();
        addProperty(netPanel, Props.BLOCK_TIME, "Block Time");
        addProperty(netPanel, Props.DECIMAL_PLACES, "Decimal Places");
        addProperty(netPanel, Props.ONE_COIN_NQT, "One Coin NQT");
        addProperty(netPanel, Props.GENESIS_BLOCK_ID, "Genesis Block ID");
        addProperty(netPanel, Props.GENESIS_TIMESTAMP, "Genesis Timestamp");
        addProperty(netPanel, Props.ADDRESS_PREFIX, "Address Prefix");
        addProperty(netPanel, Props.VALUE_SUFIX, "Value Suffix");
        addProperty(netPanel, Props.BLOCK_REWARD_START, "Block Reward Start");
        addProperty(netPanel, Props.BLOCK_REWARD_CYCLE, "Block Reward Cycle");
        addProperty(netPanel, Props.BLOCK_REWARD_CYCLE_PERCENTAGE, "Block Reward Cycle %");
        addProperty(netPanel, Props.BLOCK_REWARD_LIMIT_HEIGHT, "Block Reward Limit Height");
        addProperty(netPanel, Props.BLOCK_REWARD_LIMIT_AMOUNT, "Block Reward Limit Amount");
        addProperty(netPanel, Props.NETWORK_PARAMETERS, "Network Parameters Class");

        addProperty(netPanel, Props.REWARD_RECIPIENT_ENABLE_BLOCK_HEIGHT, "Reward Recipient Start");
        addProperty(netPanel, Props.DIGITAL_GOODS_STORE_BLOCK_HEIGHT, "DGS Start");
        addProperty(netPanel, Props.AUTOMATED_TRANSACTION_BLOCK_HEIGHT, "AT Start");
        addProperty(netPanel, Props.AT_FIX_BLOCK_2_BLOCK_HEIGHT, "AT Fix 2 Start");
        addProperty(netPanel, Props.AT_FIX_BLOCK_3_BLOCK_HEIGHT, "AT Fix 3 Start");
        addProperty(netPanel, Props.AT_FIX_BLOCK_4_BLOCK_HEIGHT, "AT Fix 4 Start");
        addProperty(netPanel, Props.AT_FIX_BLOCK_5_BLOCK_HEIGHT, "AT Fix 5 Start");
        addProperty(netPanel, Props.PRE_POC2_BLOCK_HEIGHT, "Pre-PoC2 Start");
        addProperty(netPanel, Props.POC2_BLOCK_HEIGHT, "PoC2 Start");
        addProperty(netPanel, Props.SODIUM_BLOCK_HEIGHT, "Sodium Start");
        addProperty(netPanel, Props.SIGNUM_HEIGHT, "Signum Start");
        addProperty(netPanel, Props.POC_PLUS_HEIGHT, "PoC+ Start");
        addProperty(netPanel, Props.SPEEDWAY_HEIGHT, "Speedway Start");
        addProperty(netPanel, Props.SMART_TOKEN_HEIGHT, "Smart Token Start");
        addProperty(netPanel, Props.SMART_FEES_HEIGHT, "Smart Fees Start");
        addProperty(netPanel, Props.SMART_ATS_HEIGHT, "Smart ATs Start");
        addProperty(netPanel, Props.DISTRIBUTION_FIX_BLOCK_HEIGHT, "Distribution Fix Start");
        addProperty(netPanel, Props.PK_BLOCK_HEIGHT, "PK Block Start");
        addProperty(netPanel, Props.PK2_BLOCK_HEIGHT, "PK2 Block Start");
        addProperty(netPanel, Props.PK_BLOCKS_PAST, "PK Blocks Past");
        addProperty(netPanel, Props.PK_API_BLOCK, "PK API Block");
        addProperty(netPanel, Props.SMART_ALIASES_HEIGHT, "Smart Aliases Start");
        addProperty(netPanel, Props.DEV_NEXT_FORK_BLOCK_HEIGHT, "Dev Next Fork Start");
        finalizeCategoryPanel(netPanel);
        categoryTabbedPane.addTab("Network Constants", createScrollPane(netPanel));
        LOGGER.debug("initUI - Network tab done");

        // --- Linked Profiles: NO separate tab. The "Linked Database Profile"
        //     control (checkbox + profile card + auto start/stop boxes) of the
        //     JDBC Connection URL row (Database tab) takes over its role, and
        //     the logging assignment is managed on the Logging tab (SSOT).

        // --- Content: the category tabs (search highlights matches in place) ---
        LOGGER.debug("initUI - Adding category tabs");
        // --- Content Container (CardLayout: tabbed settings vs flat search
        //     results). While a search is active the tabbed pane is hidden and
        //     the matching rows (from every tab) are listed flat; clearing the
        //     search field restores the tabbed view. ---
        contentCardLayout = new CardLayout();
        contentContainer = new JPanel(contentCardLayout);
        contentContainer.add(categoryTabbedPane, "TABS");
        searchResultsPanel = new JPanel(new MigLayout("fillx, insets 10, gap 5", "[][grow]", ""));
        contentContainer.add(createScrollPane(searchResultsPanel), "SEARCH");
        bodyPanel.add(contentContainer, BorderLayout.CENTER);

        // --- Bottom Panel with Buttons and File Path ---
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 0));
        bottomPanel.setBorder(new EmptyBorder(5, 10, 5, 5));

        // File path field
        JLabel pathLabel = new JLabel("Configuration File: " + propertiesFile.toAbsolutePath().toString());
        pathLabel.setForeground(GuiColors.getFaintText());
        bottomPanel.add(pathLabel, BorderLayout.CENTER);

        JPanel bottomContainer = new JPanel(new BorderLayout());
        bottomContainer.add(new JSeparator(SwingConstants.HORIZONTAL), BorderLayout.NORTH);
        bottomContainer.add(bottomPanel, BorderLayout.CENTER);

        // No panel-wide horizontal scroll wrapper here: the individual rows
        // (profile button row, search row) each have their own responsive
        // scroll wrapper and show their own scrollbar right below themselves
        // when the window narrows. Wrapping the whole body would steal the
        // compression from those rows (so their scrollbars never appeared) and
        // added an unwanted scrollbar at the very bottom of the panel.
        add(bodyPanel, BorderLayout.CENTER);
        add(bottomContainer, BorderLayout.SOUTH);
        
        LOGGER.debug("initUI - COMPLETED ({} tabs)", categoryTabbedPane.getTabCount());
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Re-style and re-size input fields (borders and fonts)
        if (allPropertyRows != null) {
            allPropertyRows.forEach(row -> {
                if (row.input != null) {
                    ConfigurationUtils.styleInputComponent(row.input);
                    ConfigurationUtils.fixComponentSize(row.input);
                }
            });
        }
        updateProfileButtonsUI();
    }

    private void updateProfileButtonsUI() {
        ConfigurationUtils.configureProfileToolbar(newProfileBtn, saveBtn, applyBtn, renameProfileBtn,
                deleteProfileBtn, reloadProfileBtn, null, resetToDefaultsBtn,
                copyProfileDataBtn, cloneProfileBtn);
        // Save / Apply are tinted with their value-state colors (the "saved"
        // and "applied" palette colors) so they read as the two halves of the
        // save-then-apply flow instead of two identical floppy icons.
        float iconSize = GuiConstants.getToolBarIconSize();
        ConfigurationUtils.styleProfileIconButton(saveBtn, FontAwesome.FLOPPY_O, GuiColors.getSaved(), iconSize);
        ConfigurationUtils.styleProfileIconButton(applyBtn, FontAwesome.CHECK_CIRCLE_O, GuiColors.getApplied(), iconSize);
        // The help button keeps the same flat toolbar style (size, border) as
        // the other profile-action buttons.
        ConfigurationUtils.styleProfileIconButton(helpBtn, FontAwesome.QUESTION_CIRCLE, GuiColors.getHelpIcon(), iconSize);
    }

    private void refreshUIColors() {
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            updateColor(entry.getValue(), entry.getKey(), defaultValues.get(entry.getKey()));
        }
    }

    /**
     * Recomputes the live match set for the given query and updates the UI.
     * While a query is active (or a value-status visibility checkbox is not
     * selected) the tabbed pane is hidden and every matching row (from every
     * tab) is listed flat in the search-results card with its matching
     * substring highlighted; an empty query together with all three
     * visibility checkboxes selected brings the tabbed view back. With a
     * non-empty query the search UI (highlighting, match counter, chevron
     * navigation) is active; in the filter-only case (query empty, a status
     * checkbox off) the flat list is a plain filtered view — the search UI
     * stays off. When {@code scrollToActive} is true the first match is also
     * brought into view (search-box input); a refresh caused by the user
     * editing a value must NOT yank the scroll around, so it passes false.
     */
    private void applySearch(String text, boolean scrollToActive) {
        clearSearchHighlights();
        searchMatches.clear();
        searchActiveIndex = -1;

        String query = text == null ? "" : text.trim();
        // The search-panel visibility checkboxes act as a value-status
        // filter: while all three are selected every row is visible (tabbed
        // view); when any of them is off only the rows whose state is still
        // selected are listed (when that filter is inactive the state is
        // irrelevant). The query keeps only the text matches (when it is
        // empty everything passes).
        boolean statusFilterActive = !statusPanel.isAllSelected();
        boolean filterActive = !query.isEmpty() || statusFilterActive;
        if (filterActive) {
            String lowerQuery = query.toLowerCase(Locale.ROOT);
            for (PropertyRow row : allPropertyRows) {
                if ((query.isEmpty() || isRowSearchMatch(row, lowerQuery))
                        && (!statusFilterActive || isRowStatusVisible(row))) {
                    searchMatches.add(row);
                }
            }
            // The active-match index belongs to the search itself: in the
            // filter-only case (no search text) the flat list is merely a
            // filtered view, so there is no match to navigate to and the
            // chevrons plus the "current/total" counter stay off.
            if (!query.isEmpty() && !searchMatches.isEmpty()) {
                searchActiveIndex = 0;
            }
        }

        if (!filterActive) {
            if (searchViewActive) {
                restoreAllRows();
                contentCardLayout.show(contentContainer, "TABS");
                searchViewActive = false;
            }
            lastListedMatches = null;
        } else if (!sameMatchSet(searchMatches, lastListedMatches)) {
            // (Re)build the flat results list: return the rows currently in it
            // to their category panels, then move the matching rows into it.
            // When the match set is unchanged (e.g. the user keeps typing in a
            // value) nothing is moved, so focus and scroll stay put.
            restoreAllRows();
            searchResultsPanel.removeAll();
            for (PropertyRow row : searchMatches) {
                searchResultsPanel.add(row.label, row.labelConstraints);
                searchResultsPanel.add(row.input, row.inputConstraints);
                if (row.extra != null) {
                    searchResultsPanel.add(row.extra, row.extraConstraints);
                }
                searchResultsPanel.add(row.help, row.helpConstraints);
                searchResultsPanel.add(row.separator, row.separatorConstraints);
            }
            contentCardLayout.show(contentContainer, "SEARCH");
            searchViewActive = true;
            lastListedMatches = new ArrayList<>(searchMatches);
        }

        // Text matches get their exact occurrence highlighted; with the
        // unsaved-changes filter alone (no search text) there are no match
        // ranges to paint — every listed row already carries its
        // "(unsaved changes)" label hint.
        if (!query.isEmpty()) {
            for (int i = 0; i < searchMatches.size(); i++) {
                paintSearchMatch(searchMatches.get(i), query, i == searchActiveIndex);
            }
        }
        // Filter-only results (no search text) carry no match counter and no
        // chevron navigation — that is search UI, not filter UI.
        updateSearchMatchIndicator(!query.isEmpty() ? formatSearchIndicator() : "");
        if (scrollToActive && searchActiveIndex >= 0) {
            showActiveSearchMatch();
        }
        revalidate();
        repaint();
    }

    /** True when both lists hold the same rows (identity, in order) — i.e. the flat results list does not need a rebuild. */
    private boolean sameMatchSet(List<PropertyRow> a, List<PropertyRow> b) {
        if (b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns every row that currently sits in the flat search-results list to
     * its category panel. Section headers and the other (untracked) children
     * of the category panels are never touched, so the tabbed view is restored
     * exactly as it was.
     */
    private void restoreAllRows() {
        for (PropertyRow row : allPropertyRows) {
            if (row.label != null && row.label.getParent() == searchResultsPanel) {
                restoreRow(row);
            }
        }
    }

    /**
     * Re-inserts one row's parts into its category panel, contiguously at the
     * earliest saved child position and in their original relative order
     * (sorted by saved index). Inserting at the saved positions re-occupies
     * exactly the slots the parts originally held, so the surrounding layout
     * (headers, other rows, the bottom filler) is left untouched.
     */
    private void restoreRow(PropertyRow row) {
        JPanel parent = row.originalParent;
        Component[] parts = { row.label, row.input, row.extra, row.help, row.separator };
        Object[] constraints = { row.labelConstraints, row.inputConstraints, row.extraConstraints,
                row.helpConstraints, row.separatorConstraints };
        int[] indices = { row.labelIndex, row.inputIndex, row.extraIndex, row.helpIndex,
                row.separatorIndex };
        // Insertion-sort the (part, constraint, index) triples by saved index;
        // null parts sort to the end.
        for (int i = 1; i < parts.length; i++) {
            Component part = parts[i];
            Object constraint = constraints[i];
            int index = indices[i];
            int j = i - 1;
            while (j >= 0 && (parts[j] == null || index < indices[j])) {
                parts[j + 1] = parts[j];
                constraints[j + 1] = constraints[j];
                indices[j + 1] = indices[j];
                j--;
            }
            parts[j + 1] = part;
            constraints[j + 1] = constraint;
            indices[j + 1] = index;
        }
        int pos = -1;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] != null) {
                pos = indices[i];
                break;
            }
        }
        if (pos < 0) {
            return;
        }
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] == null) {
                continue;
            }
            parent.add(parts[i], constraints[i], Math.min(pos, parent.getComponentCount()));
            pos++;
        }
    }

    /**
     * Re-applies the active search after the user edits a value: the match
     * set depends on the value text, so a value edit can add or remove
     * matches and the counter/highlights must follow — without navigating
     * (the user is typing inside the row, so tabs/scroll stay put).
     */
    private void refreshSearchFromValueEdit() {
        if (searchMatchPanel != null) {
            applySearch(searchMatchPanel.getSearchText(), false);
        }
    }

    /**
     * Registers a property row for the live search and — when the row has a
     * searchable value text component — keeps the match set in sync while
     * the user edits the value (e.g. a query that matched the old text must
     * stop matching once it is edited away).
     */
    private void addPropertyRow(PropertyRow row) {
        allPropertyRows.add(row);
        // Save the parts' child indices so the row can be re-inserted at its
        // original position when the search view is dismissed. (The reduced
        // JDK has no Container.indexOfComponent — use getComponentZOrder.)
        row.labelIndex = row.originalParent.getComponentZOrder(row.label);
        row.inputIndex = row.originalParent.getComponentZOrder(row.input);
        if (row.extra != null) {
            row.extraIndex = row.originalParent.getComponentZOrder(row.extra);
        }
        row.helpIndex = row.originalParent.getComponentZOrder(row.help);
        row.separatorIndex = row.originalParent.getComponentZOrder(row.separator);
        JTextComponent value = rowValueTextComponent(row);
        if (value != null) {
            value.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    refreshSearchFromValueEdit();
                }

                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    refreshSearchFromValueEdit();
                }

                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    refreshSearchFromValueEdit();
                }
            });
        }
        // A non-editable select has no document: its selected value (and
        // therefore the match set) changes on item selection instead.
        if (row.input instanceof JComboBox<?> comboBox && !comboBox.isEditable()) {
            comboBox.addItemListener(e -> {
                if (!isProgrammaticChange) {
                    refreshSearchFromValueEdit();
                }
            });
        }
    }

    /**
     * @return true when the query is in the row's visible text: the label
     *         name, the label's property key line, the value displayed in
     *         the row's input component, or — for a non-editable select —
     *         the value currently selected in it. Non-text inputs without a
     *         displayed value (such as check boxes) are deliberately NOT
     *         searched — only what the user can see can be a match, the
     *         same way the console only matches visible log text.
     */
    static boolean isRowSearchMatch(PropertyRow row, String lowerQuery) {
        if (row.labelText.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        String keyText = rowLabelKeyText(row);
        if (keyText != null && keyText.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        JTextComponent valueText = rowValueTextComponent(row);
        if (valueText != null && valueText.getText().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        String comboValue = rowComboSelectedText(row);
        return comboValue != null && comboValue.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    /** @return the selected item of the row's NON-editable combo box as text,
     *         or {@code null} when the row has no such combo or nothing is
     *         selected. Editable combos are already covered by their editor
     *         text field ({@link #rowValueTextComponent}).
     */
    private static String rowComboSelectedText(PropertyRow row) {
        if (row.input instanceof JComboBox<?> comboBox && !comboBox.isEditable()) {
            Object selected = comboBox.getSelectedItem();
            return selected == null ? null : selected.toString();
        }
        return null;
    }

    /** @return the row's property key line (the label's second line) text, or null when the row has none */
    private static String rowLabelKeyText(PropertyRow row) {
        return row.label instanceof SearchMatchLabel searchLabel ? searchLabel.getKeyText() : null;
    }

    /**
     * Highlights the exact matching part of one row: the query's occurrence
     * in the label name, or in the label's property key line when the name
     * does not match, and/or in the value text or the selected value of a
     * non-editable select (a row is a match when at least one of them
     * contains the query — nothing more is highlighted).
     * The active match uses the strong palette color, the others the soft
     * one (the same SSOT colors as the console "find").
     */
    private void paintSearchMatch(PropertyRow row, String query, boolean active) {
        Color color = active ? GuiColors.getSearchActiveMatch() : GuiColors.getSearchMatch();
        Highlighter.HighlightPainter painter =
                active ? SEARCH_MATCH_PAINTER_ACTIVE : SEARCH_MATCH_PAINTER;
        if (row.label instanceof SearchMatchLabel searchLabel) {
            String lowerQuery = query.toLowerCase(Locale.ROOT);
            int nameIdx = row.labelText.toLowerCase(Locale.ROOT).indexOf(lowerQuery);
            String keyText = searchLabel.getKeyText();
            int keyIdx = keyText == null ? -1 : keyText.toLowerCase(Locale.ROOT).indexOf(lowerQuery);
            if (nameIdx >= 0) {
                // The name line takes priority: it is the prominent line.
                searchLabel.setHighlightRange(nameIdx, query.length());
                searchLabel.setHighlightColor(color);
            } else if (keyIdx >= 0) {
                searchLabel.setKeyHighlightRange(keyIdx, query.length());
                searchLabel.setHighlightColor(color);
            }
        }
        highlightValueMatch(row, query, painter);
        highlightComboValueMatch(row, query, color);
    }

    /**
     * Moves the active (strong) highlight to the next/previous match
     * (wrapping) and scrolls it into view — the console's chevron behavior.
     * Only a real (non-empty) search supports navigation; the filter-only
     * flat list is not a search, so an empty query is a no-op (the chevrons
     * are hidden in that mode anyway).
     */
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
        if (!query.isEmpty()) {
            if (oldIndex >= 0 && oldIndex < count && oldIndex != searchActiveIndex) {
                paintSearchMatch(searchMatches.get(oldIndex), query, false);
            }
            paintSearchMatch(searchMatches.get(searchActiveIndex), query, true);
        }
        updateSearchMatchIndicator(formatSearchIndicator());
        showActiveSearchMatch();
    }

    /** @return the "current/total" indicator text (e.g. "1/23"), or "" when nothing matches */
    private String formatSearchIndicator() {
        return searchMatches.isEmpty() ? "" : (searchActiveIndex + 1) + "/" + searchMatches.size();
    }

    /**
     * Updates the match counter next to the search field (sized to its text,
     * mirroring {@code ConsoleFilterHeader#setSearchMatchIndicatorText}); an
     * empty text hides the counter. The chevron buttons share the indicator's
     * visibility: with no match there is nothing to navigate to, so both are
     * hidden until the search produces a match again.
     */
    private void updateSearchMatchIndicator(String text) {
        boolean hasMatches = text != null && !text.isEmpty();
        // The chevrons are only useful while a search has matches (same rule
        // as the console filter header) — hidden while filter-only or idle.
        searchMatchPanel.setChevronsVisible(hasMatches);
        searchMatchPanel.setMatchIndicatorText(text);
    }

    /**
     * Brings the active match into view. The flat results list has a single
     * viewport, so the row is scrolled there (the reveal is retried for a few
     * event ticks because after a card switch the results viewport gets its
     * size only on the next layout pass), and — when the row matches by
     * value — the highlighted occurrence is made visible inside the value
     * component.
     */
    private void showActiveSearchMatch() {
        if (searchActiveIndex < 0 || searchActiveIndex >= searchMatches.size()) {
            return;
        }
        PropertyRow row = searchMatches.get(searchActiveIndex);
        if (row.label == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> revealActiveMatch(row, 10));
    }

    /**
     * Scrolls the given row into view in the enclosing viewport (the flat
     * results list's) and reveals its value highlight. Retries while the
     * viewport is not showing or has no size yet (a card switch is laid out
     * on the next validate event). The view position is computed and clamped
     * explicitly — the same arithmetic as {@code JViewport#scrollRectToVisible},
     * but without relying on its implementation silently doing nothing for an
     * unsized/invalid viewport.
     */
    private void revealActiveMatch(PropertyRow row, int attemptsLeft) {
        JComponent rowComponent = row.label;
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
                SwingUtilities.invokeLater(() -> revealActiveMatch(row, attemptsLeft - 1));
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
        revealValueMatch(row);
    }

    /**
     * Makes the active row's value highlight visible. When the row matches
     * by value (not by label) the highlighted occurrence can sit outside the
     * value component's own visible window (a long text field, a list row
     * below the fold) — in that case the component is scrolled to the
     * occurrence. Moving the caret there does not take focus and selects
     * nothing; it only makes the highlighted band visible.
     */
    private void revealValueMatch(PropertyRow row) {
        String query = searchMatchPanel.getSearchText().trim();
        if (query.isEmpty()) {
            return;
        }
        if (row.labelText.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
            return; // the label band is already visible
        }
        String keyText = rowLabelKeyText(row);
        if (keyText != null && keyText.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
            return; // the key-line band is already visible
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

    /** Removes every search highlight (label bands, key-line bands, value marks and combo bands) from all rows. */
    private void clearSearchHighlights() {
        for (PropertyRow row : allPropertyRows) {
            if (row.label instanceof SearchMatchLabel searchLabel) {
                searchLabel.clearHighlight();
                searchLabel.clearKeyHighlight();
                row.label.setText(isRowDirty(row) ? row.labelText + " *" : row.labelText);
            }
        }
        clearValueHighlights();
        clearComboHighlights();
    }

    /**
     * The text component holding the row's editable value, if any: plain
     * text/password fields and text areas directly, the editor field of an
     * editable combo box, or the text area of a scrollable list property.
     * Returns {@code null} for rows whose value is not editable text
     * (check boxes, non-editable combos — the selected value of the latter
     * is matched via {@link #rowComboSelectedText} — and compound panels).
     */
    private static JTextComponent rowValueTextComponent(PropertyRow row) {
        JComponent input = row.input;
        if (input instanceof JTextComponent textComponent) {
            return textComponent;
        }
        if (input instanceof JComboBox<?> comboBox) {
            // Only an EDITABLE combo actually shows its editor text field; a
            // non-editable one renders its selection without it, so there is
            // no visible text to match or highlight there.
            if (comboBox.isEditable()
                    && comboBox.getEditor().getEditorComponent() instanceof JTextComponent textComponent) {
                return textComponent;
            }
            return null;
        }
        if (input instanceof JScrollPane scrollPane) {
            if (scrollPane.getViewport().getView() instanceof JTextComponent textComponent) {
                return textComponent;
            }
        }
        return null;
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
            // range outside the document bounds — nothing to highlight
        }
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
        String value = rowComboSelectedText(row);
        if (value == null) {
            return;
        }
        int idx = value.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        if (idx < 0) {
            return;
        }
        if (!originalComboRenderers.containsKey(comboBox)) {
            originalComboRenderers.put(comboBox, comboBox.getRenderer());
        }
        ComboSearchHighlightRenderer renderer = new ComboSearchHighlightRenderer();
        renderer.setBand(query, color);
        comboBox.setRenderer(renderer);
        comboBox.repaint();
    }

    /** Restores the original renderer of every combo box that carries a search band. */
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

    /** Removes all search-match highlights added to value components. */
    private void clearValueHighlights() {
        for (Map.Entry<JTextComponent, Object> entry : searchValueHighlights.entrySet()) {
            entry.getKey().getHighlighter().removeHighlight(entry.getValue());
        }
        searchValueHighlights.clear();
    }

    public String getLoadedProfileName() {
        return loadedProfileName;
    }

    /**
     * Loads the per-profile database assignment and the DB auto start/stop
     * flags, in the storage model:
     * <ul>
     *   <li><b>server engines</b> (MariaDB/PostgreSQL): the explicit link is
     *       stored in {@code profiles.json} (a server URL cannot tell which
     *       database profile it belongs to), and so are the auto start/stop
     *       flags;</li>
     *   <li><b>SQLite</b> (file-based): the link is <b>derived</b> from the
     *       saved {@code DB.Url} convention path inside
     *       {@link JdbcConfigurationPanel#loadInitial} — it is never stored;
     *       the auto start/stop flags are N/A (embedded engine);</li>
     *   <li>the legacy per-profile database keys of the node metadata
     *       ({@code profile.json} {@code profileLinks.<p>.{database,
     *       dbAutoStart,dbAutoStop}}) are migrated once and dropped.</li>
     * </ul>
     * Afterwards the deterministic initial-link resolution populates the JDBC
     * row and the row's colors/dirty state are re-settled against the
     * baselines (this method runs asynchronously after the initial property
     * load, so without the settle the load-time colors would stay stale).
     */
    private void loadProfileLinks(String profileName) {
        isProgrammaticChange = true;
        try {
            String savedUrl = savedProfile.getProperty(Props.DB_URL.getName(), getSafeDefault(Props.DB_URL));
            boolean sqliteUrl = savedUrl != null && savedUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:sqlite:");

            ProfileConfig profileConfig = new ProfileConfig();
            String dbLink = profileConfig.getDatabaseProfileLink(profileName);
            Boolean autoStart = profileConfig.getDatabaseAutoStart(profileName);
            Boolean autoStop = profileConfig.getDatabaseAutoStop(profileName);

            // One-time legacy migration: conf/node/profile.json used to carry
            // the database link + auto start/stop per profile.
            Path metadataPath = ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.NODE_SUBFOLDER);
            if (Files.exists(metadataPath)) {
                try (Reader reader = Files.newBufferedReader(metadataPath)) {
                    JsonObject metadata = JsonParser.parseReader(reader).getAsJsonObject();
                    if (metadata.has(KEY_PROFILE_LINKS) && metadata.getAsJsonObject(KEY_PROFILE_LINKS).has(profileName)) {
                        JsonObject links = metadata.getAsJsonObject(KEY_PROFILE_LINKS).getAsJsonObject(profileName);
                        boolean legacyRemoved = false;
                        if (dbLink == null && links.has(KEY_DATABASE)) {
                            String legacyLink = links.get(KEY_DATABASE).getAsString();
                            // A legacy SQLite link was derivable (or stale)
                            // anyway — drop it; a server link is migrated.
                            if (!sqliteUrl && !legacyLink.regionMatches(true, 0, "SQLite:", 0, "SQLite:".length())) {
                                profileConfig.setDatabaseProfileLink(profileName, legacyLink);
                                dbLink = legacyLink;
                            }
                            legacyRemoved = true;
                        }
                        if (!sqliteUrl) {
                            if (autoStart == null && links.has(KEY_DB_AUTO_START)) {
                                autoStart = links.get(KEY_DB_AUTO_START).getAsBoolean();
                                profileConfig.setDatabaseAutoStart(profileName, autoStart);
                                legacyRemoved = true;
                            }
                            if (autoStop == null && links.has(KEY_DB_AUTO_STOP)) {
                                autoStop = links.get(KEY_DB_AUTO_STOP).getAsBoolean();
                                profileConfig.setDatabaseAutoStop(profileName, autoStop);
                                legacyRemoved = true;
                            }
                        } else if (links.has(KEY_DB_AUTO_START) || links.has(KEY_DB_AUTO_STOP)) {
                            // SQLite: the flags are N/A — drop the stale ones.
                            legacyRemoved = true;
                        }
                        if (legacyRemoved) {
                            removeLegacyDatabaseLinkKeys(metadata, profileName);
                            writeNodeMetadata(metadata);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("Error loading legacy profile links from JSON", e);
                }
            }
            if (autoStartDbCheck != null) {
                autoStartDbCheck.setSelected(Boolean.TRUE.equals(autoStart));
            }
            if (autoStopDbCheck != null) {
                autoStopDbCheck.setSelected(Boolean.TRUE.equals(autoStop));
            }

            JdbcConfigurationPanel jdbc = jdbcPanel();
            if (jdbc != null) {
                // The single, deterministic initial-link resolution (explicit
                // link > SQLite convention derivation > manual) — see
                // JdbcConfigurationPanel#loadInitial. Server engines pass the
                // recorded assignment; for a SQLite URL the link is derived
                // from the properties inside the panel, so nothing is passed.
                jdbc.loadInitial(sqliteUrl ? null : dbLink, savedUrl,
                        savedProfile.getProperty(Props.DB_USERNAME.getName(), getSafeDefault(Props.DB_USERNAME)),
                        savedProfile.getProperty(Props.DB_PASSWORD.getName(), getSafeDefault(Props.DB_PASSWORD)));
                JComponent wrapper = propertyComponents.get(Props.DB_URL.getName());
                if (wrapper != null) {
                    JCheckBox cb = (JCheckBox) wrapper.getClientProperty("useProfileCheck");
                    if (cb != null) {
                        cb.setSelected(jdbc.isLinkedMode());
                    }
                }
            }
        } finally {
            isProgrammaticChange = false;
        }

        updateAutoDbCheckboxesState();
        savedLinkedDb = getLinkedDbProfile();
        // The applied baseline of the link element: the link of the profile
        // the node actually runs with (derived for SQLite, recorded for
        // server engines) — not the loaded profile's effective link.
        appliedLinkedDb = databaseLinkOfAppliedProfile();
        savedDbAutoStart = autoStartDbCheck != null && autoStartDbCheck.isSelected();
        savedDbAutoStop = autoStopDbCheck != null && autoStopDbCheck.isSelected();
        refreshUIColors();
    }

    /** The unified JDBC panel of the DB.Url row (or {@code null} before the row is built). */
    private JdbcConfigurationPanel jdbcPanel() {
        JComponent wrapper = propertyComponents.get(Props.DB_URL.getName());
        return wrapper == null ? null : (JdbcConfigurationPanel) wrapper.getClientProperty("profilePanel");
    }

    /**
     * The database link of the applied (the node's running baseline) profile:
     * derived from its {@code DB.Url} for SQLite, the recorded assignment
     * ({@code profiles.json}) for server engines — the applied baseline of
     * the JDBC row's link element.
     */
    private String databaseLinkOfAppliedProfile() {
        NodeProfile applied = appliedProfile != null ? appliedProfile : savedProfile;
        if (applied == null) {
            return "";
        }
        String url = applied.getProperty(Props.DB_URL.getName(), getSafeDefault(Props.DB_URL));
        return databaseLinkOf(url, applied.getName());
    }

    /**
     * The database link a profile's {@code DB.Url} names: the convention
     * derivation for SQLite (the profile must exist), the recorded assignment
     * for server engines, empty when none applies.
     */
    private String databaseLinkOf(String url, String profileName) {
        if (url != null && url.toLowerCase(Locale.ROOT).startsWith("jdbc:sqlite:")) {
            SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(url);
            if (parts != null) {
                String derived = SqliteDatabasePaths.deriveProfileName(parts.dir());
                if (derived != null && Files.isDirectory(SqliteDatabasePaths.profileDir(derived))) {
                    return "SQLite:" + derived;
                }
            }
            return "";
        }
        String link = profileName == null ? null : new ProfileConfig().getDatabaseProfileLink(profileName);
        return link == null ? "" : link;
    }

    /**
     * Removes the legacy per-profile database keys (link + auto start/stop)
     * from the node metadata's {@code profileLinks} object (the legacy
     * logging key and the top-level applied marker stay).
     *
     * @return {@code true} when something was removed
     */
    private static boolean removeLegacyDatabaseLinkKeys(JsonObject metadata, String profileName) {
        if (!metadata.has(KEY_PROFILE_LINKS)) {
            return false;
        }
        JsonObject linksMap = metadata.getAsJsonObject(KEY_PROFILE_LINKS);
        if (!linksMap.has(profileName)) {
            return false;
        }
        JsonObject links = linksMap.getAsJsonObject(profileName);
        boolean removed = false;
        for (String key : new String[] { KEY_DATABASE, KEY_DB_AUTO_START, KEY_DB_AUTO_STOP }) {
            if (links.has(key)) {
                links.remove(key);
                removed = true;
            }
        }
        if (links.size() == 0) {
            linksMap.remove(profileName);
        }
        return removed;
    }

    /** Writes the node metadata object back to {@code conf/node/profile.json}. */
    private void writeNodeMetadata(JsonObject metadata) {
        try (Writer writer = Files.newBufferedWriter(
                ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.NODE_SUBFOLDER))) {
            new GsonBuilder().setPrettyPrinting().create().toJson(metadata, writer);
        } catch (Exception e) {
            LOGGER.error("Error saving node metadata", e);
        }
    }

    /**
     * The host-side half of a link change (the panel's
     * {@link JdbcConfigurationPanel.LinkChangeListener}): keeps the "Linked
     * Database Profile" checkbox in sync with the panel's mode (the panel may
     * auto-unlink itself, e.g. an unmaterializable link at load) and persists the
     * effective link into the node metadata — unless the change is
     * programmatic (the initial load pass persists nothing).
     */
    private void onJdbcLinkChanged(JdbcConfigurationPanel panel, JdbcConfigurationPanel.JdbcState state) {
        JComponent wrapper = propertyComponents.get(Props.DB_URL.getName());
        if (wrapper != null) {
            JCheckBox cb = (JCheckBox) wrapper.getClientProperty("useProfileCheck");
            if (cb != null && cb.isSelected() != panel.isLinkedMode()) {
                isProgrammaticChange = true;
                try {
                    cb.setSelected(panel.isLinkedMode());
                } finally {
                    isProgrammaticChange = false;
                }
            }
        }
        if (!isProgrammaticChange) {
            saveDatabaseAssignment(loadedProfileName);
        }
        updateAutoDbCheckboxesState();
    }

    /**
     * Persists the current per-profile database assignment into
     * {@code profiles.json}:
     * <ul>
     *   <li><b>server engines</b>: the effective link (empty in manual mode)
     *       is recorded — it is the only way to know which database profile a
     *       server URL belongs to;</li>
     *   <li><b>SQLite</b> (file-based): nothing is recorded — the assignment
     *       is derived from the {@code DB.Url} convention path; the auto
     *       start/stop flags are N/A and cleared (embedded engine);</li>
     *   <li>server engines: the DB auto start/stop flags are recorded.</li>
     * </ul>
     * The legacy per-profile database keys of the node metadata are removed
     * for good (the legacy logging key and the applied marker stay).
     * <p>
     * This never moves the saved-link baseline ({@code savedLinkedDb}): a link
     * change is an <b>unsaved</b> (dirty) change until the profile is
     * explicitly saved — the baseline is updated on load, on the explicit
     * save, and on apply only.
     */
    private void saveDatabaseAssignment(String profileName) {
        JdbcConfigurationPanel jdbc = jdbcPanel();
        boolean sqlite = jdbc == null || jdbc.getEngine() == DatabaseConfigurationPanel.DatabaseEngine.SQLITE;
        boolean autoStart = autoStartDbCheck != null && autoStartDbCheck.isSelected();
        boolean autoStop = autoStopDbCheck != null && autoStopDbCheck.isSelected();

        ProfileConfig profileConfig = new ProfileConfig();
        String db = (sqlite || !jdbc.isLinkedMode()) ? null : jdbc.getLink();
        profileConfig.setDatabaseProfileLink(profileName, db);
        if (sqlite) {
            profileConfig.setDatabaseAutoStart(profileName, null);
            profileConfig.setDatabaseAutoStop(profileName, null);
        } else {
            profileConfig.setDatabaseAutoStart(profileName, autoStart);
            profileConfig.setDatabaseAutoStop(profileName, autoStop);
        }

        // One-time legacy cleanup: the node metadata's database keys are gone
        // for good (the legacy logging key + the applied marker stay).
        Path metadataPath = ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.NODE_SUBFOLDER);
        if (Files.exists(metadataPath)) {
            try (Reader reader = Files.newBufferedReader(metadataPath)) {
                JsonObject metadata = JsonParser.parseReader(reader).getAsJsonObject();
                if (removeLegacyDatabaseLinkKeys(metadata, profileName)) {
                    writeNodeMetadata(metadata);
                }
            } catch (Exception e) {
                LOGGER.error("Error cleaning up legacy profile links", e);
            }
        }

        savedDbAutoStart = autoStart;
        savedDbAutoStop = autoStop;
    }

    /**
     * Automatically creates (or updates) the SQLite database profile matching the
     * manual (non-linked) SQLite configuration: every new SQLite database setting
     * creates a new SQLite database profile in the database module's profile area
     * ({@code ./database/SQLite/<profile>/profile.json}). The profile name is
     * derived from the configuration's directory
     * ({@link SqliteDatabasePaths#deriveProfileName}) — a linkable name, never a
     * free UI input. Linked configurations are skipped because they already
     * reference an existing profile, and a configuration without a derivable
     * profile name (e.g. the convention base itself or a foreign path) or
     * without a database file creates nothing.
     */
    private void autoCreateSqliteDatabaseProfile(Properties props) {
        try {
            String dbUrl = props.getProperty(Props.DB_URL.getName());
            if (dbUrl == null || !dbUrl.startsWith("jdbc:sqlite:"))
                return;
            JdbcConfigurationPanel p = jdbcPanel();
            if (p == null || p.isLinkedMode())
                return;
            SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(dbUrl);
            if (parts == null || parts.fileName().isEmpty())
                return; // no well-formed file to anchor the profile to
            String profileName = SqliteDatabasePaths.deriveProfileName(parts.dir());
            if (profileName == null) {
                // No derivable profile name: the path is not under the
                // per-profile convention — nothing to create (custom path).
                return;
            }
            if (!DatabaseConfigurationUtils.isValidProfileName(profileName)) {
                LOGGER.warn(
                        "Skipping auto-created SQLite database profile: invalid profile name '{}'",
                        profileName);
                return;
            }
            ProfileCreateDefaults.ensureSqliteDatabaseProfile(profileName, dbUrl);
            LOGGER.info("Auto-created/updated SQLite database profile '{}' (URL: {})",
                    profileName, dbUrl);
        } catch (Exception e) {
            LOGGER.warn("Failed to auto-create SQLite database profile", e);
        }
    }

    /**
     * Saves the currently loaded profile: writes the effective UI values to
     * the profile file (preserving its original format) and refreshes the saved
     * state, dirty markers and colors.
     *
     * @return {@code true} on success, {@code false} on failure
     */
    private boolean doSaveCurrentProfile() {
        try {
            Properties propsToSave = getPropertiesFromUI();
            ConfigurationUtils.savePropertiesPreservingFormat(propertiesFile, propsToSave,
                    propertyComponents.keySet());

            isProgrammaticChange = true;
            try {
                this.savedProfile = new NodeProfile(loadedProfileName);
                this.savedProfile.setProperties(propsToSave);
            } finally {
                isProgrammaticChange = false;
            }
            saveDatabaseAssignment(loadedProfileName);
            autoCreateSqliteDatabaseProfile(propsToSave);
            // The auto-created database profile may make the (manual) SQLite
            // URL linkable now: re-derive the automatic SQLite link so the
            // checkbox follows the properties (the link callback syncs the
            // checkbox and the persistence).
            JdbcConfigurationPanel jdbc = jdbcPanel();
            if (jdbc != null) {
                jdbc.rediscoverSqliteLink();
                // The explicit save moves the saved-link baseline to the final
                // effective link (the link-change persistence alone never does
                // — a link change is unsaved until the profile is saved).
                savedLinkedDb = getLinkedDbProfile();
            }
            updateDirtyStatus();
            refreshUIColors();
            return true;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error saving profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
            return false;
        }
    }

    /**
     * New Profile toolbar action: shows the shared {@link NewProfileChoiceDialog}
     * (launch the setup wizard, or create a new empty profile) and hands the
     * selection to the host callback wired via {@link #setNewProfileHandler}.
     * With no host handler the button explains where profiles are created.
     */
    private void newProfile() {
        if (newProfileHandler == null) {
            JOptionPane.showMessageDialog(this,
                    "New profiles are created from the node profile window (the \"+\" button of the profile tabs).",
                    "New Profile", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        NewProfileChoiceDialog.Choice choice = NewProfileChoiceDialog.show(this, true);
        if (choice != null) {
            newProfileHandler.accept(choice);
        }
    }

    /**
     * Save toolbar action. Reviews the unsaved changes in a dialog (the profile
     * name cannot be changed here - the currently loaded profile is always the
     * target) and saves them. It does <b>not</b> apply anything — use the Apply
     * button to restart the node with the saved configuration.
     */
    private void saveProfile() {
        String name = loadedProfileName;
        if (name == null) {
            return;
        }
        if ((Signum.NODE_SUBFOLDER + "-default").equals(name)) {
            JOptionPane.showMessageDialog(this, "The system default profile template cannot be modified.",
                    "Action Not Allowed", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String report = getUnsavedChangesReport();
        if (report == null) {
            JOptionPane.showMessageDialog(this, "There are no unsaved changes in profile '" + name + "'.",
                    "Save", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        ProfileSaveApplyDialog.Result result = ProfileSaveApplyDialog.show(this, name, report);
        if (result == ProfileSaveApplyDialog.Result.DISCARD) {
            // The review dialog already listed the changes and the user explicitly
            // chose to discard them — reload the saved profile from disk without a
            // second confirmation.
            reloadFromDisk();
            JOptionPane.showMessageDialog(this,
                    "Unsaved changes discarded — the editor now shows the saved configuration of profile '"
                            + name + "'.",
                    "Changes Discarded", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (result != ProfileSaveApplyDialog.Result.SAVE) {
            return;
        }
        if (doSaveCurrentProfile()) {
            JOptionPane.showMessageDialog(this, "Profile '" + name + "' saved successfully.",
                    "Success", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * Apply toolbar action. Applies the <b>saved</b> configuration of the loaded
     * profile: marks it applied and restarts the node so the changes take effect.
     * Only usable while there are saved, not yet applied changes.
     */
    private void applyProfile() {
        String name = loadedProfileName;
        if (name == null) {
            return;
        }
        if ((Signum.NODE_SUBFOLDER + "-default").equals(name)) {
            JOptionPane.showMessageDialog(this, "The system default profile template cannot be modified.",
                    "Action Not Allowed", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Apply the saved configuration of profile '" + name + "'? It will restart the node.",
                "Apply Configuration", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            applySavedChanges();
        }
    }

    /**
     * Marks the currently loaded profile as applied and restarts the node
     * service so the freshly saved configuration takes effect immediately.
     */
    private void applySavedChanges() {
        ConfigurationUtils.updateAppliedProfile(
                ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.NODE_SUBFOLDER),
                loadedProfileName);
        // The freshly saved values are now the applied baseline: the node
        // restarts with them, so the editor's value-status coloring turns them
        // from "saved" (on disk, not yet applied) to "applied".
        this.appliedProfile = new NodeProfile(loadedProfileName);
        this.appliedProfile.setProperties(copyProperties(this.savedProfile.getProperties()));
        // The link the node runs with after the restart — the applied
        // baseline of the JDBC row's link element (mirrors appliedProfile).
        this.appliedLinkedDb = getLinkedDbProfile();
        refreshUIColors();
        if (restartAction != null) {
            restartAction.run();
        }
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
        Object[] options = { "Save", "Discard", "Cancel" };
        int result = JOptionPane.showOptionDialog(this, message, "Unsaved Changes",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);

        if (result == JOptionPane.YES_OPTION) {
            if (doSaveCurrentProfile()) {
                if (onProceed != null)
                    onProceed.run();
                return true;
            }
            return false;
        } else if (result == JOptionPane.NO_OPTION) {
            isProgrammaticChange = true;
            updateUIFromProperties(savedProfile.getProperties());
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
                "<html><b>Unsaved changes in Node Configuration (Profile: '" + loadedProfileName + "'):</b><ul>");
        boolean changesFound = false;
        for (PropertyRow row : allPropertyRows) {
            if (isRowDirty(row)) {
                changesFound = true;
                String savedValue = savedProfile.getProperty(row.prop.getName());
                if (savedValue == null)
                    savedValue = getSafeDefault(row.prop);
                Supplier<String> supplier = valueSuppliers.get(row.prop.getName());
                String newValue = (supplier != null) ? supplier.get() : "";
                if (newValue == null)
                    newValue = "";

                boolean isList = (row.input instanceof JScrollPane) ||
                        (row.input instanceof JPanel && Props.NODE_PK_CHECKS.getName().equals(row.prop.getName()));

                String displaySaved = savedValue;
                String displayNew = newValue;

                if (isList) {
                    displaySaved = normalizeListValue(savedValue, ";");
                    displayNew = normalizeListValue(newValue, "\n");
                }

                report.append("<li>").append(row.labelText).append(": '")
                        .append(displaySaved.length() > 50 ? displaySaved.substring(0, 47) + "..." : displaySaved)
                        .append("' &rarr; '")
                        .append(displayNew.length() > 50 ? displayNew.substring(0, 47) + "..." : displayNew)
                        .append("'</li>");
            }
        }
        report.append("</ul></html>");
        return changesFound ? report.toString() : null;
    }

    /**
     * Copy toolbar action: opens the "Copy Configuration" dialog and copies
     * the selected profile's configuration into this editor. The editor keeps
     * belonging to the <b>current</b> profile (its name and saved state do not
     * change), so the copied values show up as unsaved changes that can be
     * adjusted and saved with Save &amp; Apply. Unsaved changes in the editor
     * are discarded (with confirmation).
     */
    private void copyProfileData() {
        String selected = loadedProfileName;
        if (selected == null) {
            return;
        }
        // Reserved profiles (node-default) are never listed: they cannot be
        // copied (SSOT: NodeProfileRepository#isReservedProfileName).
        List<String> profiles = ConfigurationUtils.fetchProfileNames(
                ConfigurationUtils.getNodeProfilesDir(), null).stream()
                        .filter(name -> !NodeProfileRepository.isReservedProfileName(name))
                        .collect(Collectors.toList());
        String source = ProfileCopyDialog.show(this, profiles, selected);
        if (source == null || source.trim().isEmpty()) {
            return;
        }

        if (hasUnsavedChanges()) {
            String message = "You have unsaved changes. Are you sure you want to copy from the selected profile and discard these changes?";
            Object[] options = { "Discard and Copy", "Cancel" };
            int result = JOptionPane.showOptionDialog(this, message, "Copy Profile Data",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, options, options[1]);
            if (result != JOptionPane.YES_OPTION) {
                return;
            }
        }

        Path sourceFile = ConfigurationUtils.resolveNodeProfilePath(source);
        Properties loaded = new Properties();
        if (Files.exists(sourceFile)) {
            try (FileInputStream in = new FileInputStream(sourceFile.toFile())) {
                loaded.load(in);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Error copying profile data: " + e.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        // The editor keeps belonging to the current profile: only the values
        // (and the linked profiles) are copied over, so the copied values show
        // up as unsaved changes relative to the current profile's saved state.
        isProgrammaticChange = true;
        try {
            updateUIFromProperties(loaded);
        } finally {
            isProgrammaticChange = false;
        }
        loadProfileLinks(source);
        updateDirtyStatus();
    }

    /**
     * Clone toolbar action: opens the "Clone Configuration" dialog and creates a
     * <b>new</b> profile from the current (unsaved, editor) effective state.
     * <p>
     * Only the values that differ from the application default are copied
     * (minimal-override file, consistent with the profile design). Create-only
     * semantics: the clone is not started, the source profile's editor state is
     * left untouched (unsaved changes are preserved), and the clone keeps the
     * source's ports and database settings as-is.
     */
    private void cloneProfile() {
        String source = loadedProfileName;
        if (source == null || source.trim().isEmpty()) {
            return;
        }

        String newName;
        List<ProfileDiffCalculator.ProfileDiffEntry> labeled;
        try {
            // Diff against the default from the current (unsaved, editor) effective
            // state. getPropertiesFromUI() already holds the minimal overrides, so
            // it doubles as the effective state for the SSOT diff computation.
            Properties effective = getPropertiesFromUI();
            List<ProfileDiffCalculator.ProfileDiffEntry> diff = ProfileDiffCalculator
                    .diffAgainstDefault(effective, defaultValues);

            // Human-readable names from the property labels of this editor.
            Map<String, String> keyToLabel = new HashMap<>();
            for (PropertyRow row : allPropertyRows) {
                keyToLabel.putIfAbsent(row.prop.getName(), row.labelText);
            }
            labeled = new ArrayList<>(diff.size());
            for (ProfileDiffCalculator.ProfileDiffEntry entry : diff) {
                String label = keyToLabel.get(entry.getKey());
                labeled.add(label != null && !label.isEmpty() && !label.equals(entry.getKey())
                        ? new ProfileDiffCalculator.ProfileDiffEntry(entry.getKey(), entry.getValue(), label)
                        : entry);
            }

            Set<String> taken = new HashSet<>(NodeProfileRepository.listProfiles());
            String suggested = ProfileNameSuggester.nextCloneName(source, taken);
            newName = ProfileCloneDialog.show(this, source, labeled, suggested, taken);
        } catch (Exception e) {
            // Never leave the user with a silent no-op: any failure while
            // preparing the dialog (or the dialog itself) is surfaced and
            // logged explicitly instead of dying inside the button listener.
            LOGGER.error("Failed to open the clone dialog for profile '{}'", source, e);
            JOptionPane.showMessageDialog(this, "Error opening the clone dialog: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (newName == null) {
            return; // cancelled
        }

        try {
            // Creation chain (SSOT: ProfileRuntimeService): profile file from the
            // minimal-override payload + logging-association clone.
            String created = ProfileRuntimeService.cloneProfile(source, newName,
                    ProfileDiffCalculator.toProperties(labeled));
            // Tab registration: adds the new profile tab and selects it
            // (idempotent; safe no-op when the Node GUI has not been created).
            NodeModule.getInstance().openProfileConfiguration(created);
            JOptionPane.showMessageDialog(this,
                    "Profile '" + created + "' was created as a clone of '" + source + "'.",
                    "Clone Configuration", JOptionPane.INFORMATION_MESSAGE);
        } catch (IllegalArgumentException | IOException e) {
            LOGGER.error("Failed to clone profile '{}' -> '{}'", source, newName, e);
            JOptionPane.showMessageDialog(this, "Error cloning profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Reloads the <b>current</b> profile file from disk into the editor —
     * intended for picking up changes made externally (e.g. the properties file
     * was edited by hand). Unsaved UI changes are discarded (with confirmation).
     */
    private void reloadProfile() {
        if (loadedProfileName == null) {
            return;
        }
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
        reloadFromDisk();
    }

    /**
     * Re-reads the <b>current</b> profile file from disk into the editor, discarding
     * unsaved UI changes <b>without confirmation</b>. Callers must have already asked
     * the user (the Reload Profile confirmation, or the Save dialog's Discard action).
     */
    private void reloadFromDisk() {
        Path targetFile = ConfigurationUtils.resolveNodeProfilePath(loadedProfileName);
        if (Files.exists(targetFile)) {
            try (FileInputStream in = new FileInputStream(targetFile.toFile())) {
                Properties loaded = new Properties();
                isProgrammaticChange = true;
                try {
                    loaded.load(in);
                    savedProfile = new NodeProfile(loadedProfileName);
                    savedProfile.setProperties(loaded);
                    // A reload re-loads the profile into the editor: the
                    // freshly loaded values are again the applied baseline
                    // (the editor now mirrors the file on disk).
                    appliedProfile = new NodeProfile(loadedProfileName);
                    appliedProfile.setProperties(copyProperties(loaded));
                    updateUIFromProperties(loaded);
                } finally {
                    isProgrammaticChange = false;
                }
                // Re-derive the JDBC row from disk exactly like the initial
                // load: fresh database-profile discovery, the deterministic
                // link resolution (explicit link > SQLite convention >
                // manual) with the "Linked Database Profile" checkbox
                // following it, and the baselines (saved + applied link)
                // reset to the reloaded values. The panel's remembered
                // per-engine / per-profile working states are discarded —
                // nothing of the pre-reload UI state may survive.
                JdbcConfigurationPanel jdbc = jdbcPanel();
                if (jdbc != null) {
                    jdbc.resetWorkingState();
                }
                loadProfileLinks(loadedProfileName);
                updateDirtyStatus();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Error reloading profile: " + e.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * Node (re)start synchronization: once the node (re)starts it runs with
     * the profile file on disk — the db.URL section, the value-state
     * coloring and the Apply button's state must reflect that without a
     * manual Reload. With no unsaved editor changes this is a full reload
     * from disk (exactly the Reload button's refresh); while the user is
     * mid-edit the unsaved work is left untouched and only the applied
     * baseline (what the node now runs with) is re-anchored to the
     * on-disk state.
     */
    public void onNodeRestarted() {
        if (loadedProfileName == null) {
            return;
        }
        if (!hasUnsavedChanges()) {
            reloadFromDisk();
            return;
        }
        // Unsaved editor changes exist: only re-anchor the applied baseline
        // to the on-disk state — never discard the user's work.
        Path targetFile = ConfigurationUtils.resolveNodeProfilePath(loadedProfileName);
        if (!Files.exists(targetFile)) {
            return;
        }
        try (FileInputStream in = new FileInputStream(targetFile.toFile())) {
            Properties disk = new Properties();
            disk.load(in);
            this.appliedProfile = new NodeProfile(loadedProfileName);
            this.appliedProfile.setProperties(copyProperties(disk));
        } catch (Exception e) {
            LOGGER.warn("Could not refresh the applied baseline after the node restart", e);
            return;
        }
        this.appliedLinkedDb = databaseLinkOfAppliedProfile();
        refreshUIColors();
        updateDirtyStatus();
    }

    private void resetToDefaults() {
        Properties defaultProps = new Properties();
        for (Map.Entry<String, String> entry : defaultValues.entrySet()) {
            defaultProps.setProperty(entry.getKey(), entry.getValue());
        }
        isProgrammaticChange = true;
        updateUIFromProperties(defaultProps);
        loadProfileLinks(loadedProfileName);
        updateDirtyStatus();
        refreshUIColors();
        isProgrammaticChange = false;
        JOptionPane.showMessageDialog(this,
                "All settings reset to application defaults. Remember to save if you want to keep these changes.",
                "Reset to Defaults", JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateProfileButtonStates() {
        String selected = loadedProfileName;
        boolean isReadOnly = Signum.NODE_SUBFOLDER.equals(selected)
                || (Signum.NODE_SUBFOLDER + "-default").equals(selected);
        resetToDefaultsBtn.setEnabled(true); // Always enable reset to defaults
        saveBtn.setEnabled(overallDirty && !isReadOnly);
        applyBtn.setEnabled(!isReadOnly && hasSavedNotAppliedChanges());
        renameProfileBtn.setEnabled(!isReadOnly);
        deleteProfileBtn.setEnabled(!isReadOnly);
        cloneProfileBtn.setEnabled(!isReadOnly);
    }

    /**
     * Renames the selected profile — <b>state-preserving, runtime, no full app restart</b> (D3):
     * pre-guard for unsaved changes (Save / Discard / Cancel), node-state capture, then the
     * rename chain (stop → file + metadata + data-path operations → GUI rebuild → state
     * restoration) via {@link ProfileRuntimeService#renameProfile}.
     */
    public void renameProfile(String oldProfileName) {
        if (oldProfileName == null || oldProfileName.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No profile selected to rename.", "Rename Profile",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if ((Signum.NODE_SUBFOLDER + "-default").equals(oldProfileName)) {
            JOptionPane.showMessageDialog(this, "The system default profile cannot be renamed.",
                    "Action Not Allowed", JOptionPane.WARNING_MESSAGE);
            return;
        }
        checkUnsavedChangesAndProceed(() -> startRename(oldProfileName), null);
    }

    /**
     * Continues the rename after the unsaved-changes pre-guard (EDT): captures the node's
     * pre-rename state (D3), shows the rename dialog, then runs the blocking rename chain
     * (stop → meta → GUI rebuild → state restoration) on a background thread — the EDT stays
     * responsive with a wait cursor and disabled profile-action buttons.
     */
    private void startRename(String oldProfileName) {
        // State capture (D3): the node's pre-rename state drives the post-rename restoration.
        Signum signum = NodeModule.getInstance().get(oldProfileName);
        boolean nodeRunning = signum != null
                && (signum.getState() == Signum.State.RUNNING || signum.getState() == Signum.State.STARTING);
        boolean nodeUserPaused = nodeRunning
                && signum.getOperatingState() == Signum.OperatingState.PAUSED_USER;

        Set<String> taken = new HashSet<>(NodeProfileRepository.listProfiles());
        taken.remove(oldProfileName); // the current name is the (no-op) starting point
        String newName = ProfileRenameDialog.show(this, oldProfileName, nodeRunning, nodeUserPaused,
                oldProfileName, taken);
        if (newName == null || newName.equals(oldProfileName)) {
            return; // cancelled or unchanged
        }

        // The node GUI (tab owner) — captured on the EDT before the background work starts.
        NodePanel gui = (NodePanel) NodeModule.getInstance().getUI();
        final boolean[] guiRebuilt = { false };

        setProfileActionButtonsEnabled(false);
        Cursor previousCursor = getCursor();
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        application.utils.gui.GuiExecutors.prepare().execute(() -> {
            try {
                // The blocking rename chain (stop → meta → rebuild → state restoration).
                ProfileRuntimeService.renameProfile(oldProfileName, newName, () -> SwingUtilities
                        .invokeLater(() -> {
                            // GUI reinitialization: the tab's profile panel rebuilds under the new name.
                            if (gui != null) {
                                gui.renameProfileTab(oldProfileName, newName, true);
                                guiRebuilt[0] = true;
                            }
                        }));
            } catch (Exception e) {
                LOGGER.error("Failed to rename profile '{}' -> '{}'", oldProfileName, newName, e);
                SwingUtilities.invokeLater(() -> {
                    setCursor(previousCursor);
                    setProfileActionButtonsEnabled(true);
                    updateProfileButtonStates();
                    JOptionPane.showMessageDialog(this,
                            "Error renaming profile: " + e.getMessage(), "Error",
                            JOptionPane.ERROR_MESSAGE);
                });
                return;
            }
            SwingUtilities.invokeLater(() -> {
                setCursor(previousCursor);
                setProfileActionButtonsEnabled(true);
                updateProfileButtonStates();
                // When the tab was not rebuilt (no node GUI), this panel stays visible —
                // repoint it at the renamed profile file. (When the tab was rebuilt, this
                // (old) panel is already replaced by the fresh one, which loads from disk.)
                if (!guiRebuilt[0]) {
                    this.propertiesFile = ConfigurationUtils.resolveNodeProfilePath(newName);
                    this.loadedProfileName = newName;
                    this.savedProfile = new NodeProfile(newName);
                }
                JOptionPane.showMessageDialog(this,
                        "Profile '" + oldProfileName + "' was renamed to '" + newName + "'.",
                        "Rename Profile", JOptionPane.INFORMATION_MESSAGE);
            });
        });
    }

    /**
     * Enables/disables all profile-action toolbar buttons (used while a blocking profile
     * operation runs in the background).
     */
    private void setProfileActionButtonsEnabled(boolean enabled) {
        for (JButton button : new JButton[] { newProfileBtn, saveBtn, applyBtn, renameProfileBtn,
                deleteProfileBtn, resetToDefaultsBtn, copyProfileDataBtn, cloneProfileBtn,
                reloadProfileBtn, helpBtn }) {
            if (button != null) {
                button.setEnabled(enabled);
            }
        }
    }

    /**
     * Deletes the given profile (Delete toolbar action): confirms in the delete dialog
     * (running-node warning + optional SQLite data deletion), then runs the blocking delete
     * chain (stop → registry teardown → file + metadata → optional data) on a background
     * thread — the EDT stays responsive with a wait cursor and disabled profile-action
     * buttons. The profile tab is removed afterwards (and this panel disposed with it).
     */
    private void deleteProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()) {
            return;
        }
        String name = profileName.trim();
        if ((Signum.NODE_SUBFOLDER + "-default").equals(name)) {
            JOptionPane.showMessageDialog(this, "The system profiles cannot be deleted.", "Action Not Allowed",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        // State capture (drives the dialog's state text + the data-deletion checkbox).
        Signum signum = NodeModule.getInstance().get(name);
        boolean nodeRunning = signum != null
                && (signum.getState() == Signum.State.RUNNING || signum.getState() == Signum.State.STARTING);
        boolean sqliteConfigured = ProfileRuntimeService.usesPerProfileSqliteDatabase(name);

        ProfileDeleteDialog.Confirmation confirmation = ProfileDeleteDialog.show(this, name, nodeRunning,
                sqliteConfigured);
        if (confirmation == null) {
            return; // cancelled
        }
        boolean deleteData = confirmation.deleteData();

        // The node GUI (tab owner) — captured on the EDT before the background work starts.
        NodePanel gui = (NodePanel) NodeModule.getInstance().getUI();
        // The dialog window outlives this panel: its tab (and the panel) is removed after
        // the delete, so the result dialogs are owned by the window, not by 'this'.
        final Window owner = SwingUtilities.windowForComponent(this);

        setProfileActionButtonsEnabled(false);
        Cursor previousCursor = getCursor();
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        application.utils.gui.GuiExecutors.prepare().execute(() -> {
            try {
                // The blocking delete chain (stop → registry teardown → file + metadata → data).
                ProfileRuntimeService.deleteProfile(name, deleteData);
            } catch (Exception e) {
                LOGGER.error("Failed to delete profile '{}'", name, e);
                SwingUtilities.invokeLater(() -> {
                    setCursor(previousCursor);
                    setProfileActionButtonsEnabled(true);
                    updateProfileButtonStates();
                    JOptionPane.showMessageDialog(owner,
                            "Error deleting profile: " + e.getMessage(), "Error",
                            JOptionPane.ERROR_MESSAGE);
                });
                return;
            }
            SwingUtilities.invokeLater(() -> {
                // GUI cleanup: the tab is removed (and this panel disposed). The profile file
                // is already gone from the chain — removeProfileTab's repository call is a
                // tolerated no-op then.
                if (gui != null) {
                    gui.removeProfileTab(name);
                }
                setCursor(previousCursor);
                if (owner != null) {
                    JOptionPane.showMessageDialog(owner,
                            "Profile '" + name + "' deleted successfully.", "Success",
                            JOptionPane.INFORMATION_MESSAGE);
                }
            });
        });
    }

    /**
     * Builds the structured help content shown by the profile toolbar's Help
     * button, rendered in the compact {@link HelpDialog} (the same pattern as
     * the module logging profile panels): a sectioned paragraph, the value-state
     * row-color legend with the palette's real state colors, and the toolbar
     * actions with their actual icons and colors (the same icon + color pairing
     * as the toolbar itself — see {@link #updateProfileButtonsUI} and
     * {@link ConfigurationUtils#configureProfileToolbar}).
     */
    private JComponent buildConfigurationHelpContent() {
        Color iconColor = GuiColors.getButtonIcon();
        JComponent content = HelpDialog.content();
        content.add(HelpDialog.title("Node Configuration Profiles"));
        content.add(Box.createVerticalStrut(10));
        content.add(HelpDialog.paragraph(
                "Profiles allow you to maintain multiple sets of node configurations. Use the "
                        + "toolbar buttons to perform the following actions:"));
        content.add(HelpDialog.separator());
        content.add(HelpDialog.heading("Row colors"));
        content.add(HelpDialog.legendRow(GuiColors.getUnsaved(), "Unsaved",
                "The value has been modified in the UI and matches neither the values loaded from "
                        + "the profile nor the values saved in the configuration file. Unsaved rows "
                        + "are marked with a trailing <b>*</b> on their label."));
        content.add(HelpDialog.legendRow(GuiColors.getSaved(), "Saved",
                "Saved in the currently loaded profile on disk, but differs from the values the "
                        + "profile loaded into the editor (the applied baseline) — saved, but not "
                        + "yet applied."));
        content.add(HelpDialog.legendRow(GuiColors.getApplied(), "Applied",
                "Matches exactly the values loaded from the currently loaded profile — the baseline "
                        + "this node runs (or will run) with. Note that most changes require a "
                        + "restart to take effect."));
        content.add(Box.createVerticalStrut(8));
        content.add(HelpDialog.paragraph(
                "The <b>\u201CShow values\u201D</b> boxes filter rows by these states, and the "
                        + "<b>Search</b> box filters rows by label, key or value."));
        content.add(HelpDialog.separator());
        content.add(HelpDialog.heading("Toolbar actions"));
        content.add(HelpDialog.actionRow(FontAwesome.FILE_O, iconColor, "New Profile",
                "Opens the dialog to launch the setup wizard (guided database/connection/node setup) "
                        + "or to create a new empty profile pre-filled with the application default "
                        + "values."));
        content.add(HelpDialog.actionRow(FontAwesome.FLOPPY_O, GuiColors.getSaved(), "Save",
                "Saves all unsaved changes to the current profile (reviewing them in a dialog first "
                        + "— you can save, discard all unsaved changes, or cancel). It does not "
                        + "restart the node. Active only while there are unsaved changes."));
        content.add(HelpDialog.actionRow(FontAwesome.CHECK_CIRCLE_O, GuiColors.getApplied(),
                "Apply",
                "Applies the saved configuration — marks the profile as applied and restarts the "
                        + "node so the changes take effect. Active only while there are saved, not "
                        + "yet applied changes."));
        content.add(HelpDialog.actionRow(FontAwesome.PENCIL_SQUARE_O, iconColor, "Rename Profile",
                "Changes the name of the currently selected configuration profile."));
        content.add(HelpDialog.actionRow(FontAwesome.UNDO, iconColor, "Reset to Defaults",
                "Resets all current settings to their application default values without saving."));
        content.add(HelpDialog.actionRow(FontAwesome.CLIPBOARD, iconColor, "Copy Configuration",
                "Opens a dialog to select a profile and copies its configuration into the editor of "
                        + "the current profile. The copied values become unsaved changes you can "
                        + "adjust before saving."));
        content.add(HelpDialog.actionRow(FontAwesome.FILES_O, iconColor, "Clone Configuration",
                "Creates a new profile from the current (unsaved, editor) effective state; the "
                        + "source profile is left untouched."));
        content.add(HelpDialog.actionRow(FontAwesome.RECYCLE, iconColor, "Reload Configuration",
                "Reloads the current profile file from disk (e.g. after external edits), discarding "
                        + "any unsaved changes in the UI."));
        content.add(HelpDialog.actionRow(FontAwesome.TRASH_O, GuiColors.getContrastRed(),
                "Delete Profile",
                "Permanently deletes the selected profile — its node is stopped (if running, and "
                        + "not restarted), the profile settings are removed (optionally its SQLite "
                        + "database data) and its tab is closed."));
        content.add(HelpDialog.actionRow(FontAwesome.QUESTION_CIRCLE, iconColor, "Help",
                "Shows this help."));
        content.add(HelpDialog.separator());
        content.add(HelpDialog.paragraph(
                "The <b>database</b> profile link is managed on the Database tab's "
                        + "\u201CLinked Database Profile\u201D control, and the <b>logging</b> "
                        + "profile assignment on the Logging tab.<br><br>"
                        + "Profiles are stored as \".properties\" files within the node "
                        + "sub-directory of the configuration folder."));
        return content;
    }

    private boolean hasUnsavedChanges() {
        for (PropertyRow row : allPropertyRows) {
            if (isRowDirty(row)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRowDirty(PropertyRow row) {
        String savedValue = savedProfile.getProperty(row.prop.getName());
        boolean isDefault = (savedValue == null);
        // 'current' here represents the effective value from the file, considering
        // defaults
        String saved = isDefault ? getSafeDefault(row.prop) : savedValue;
        if (saved == null)
            saved = "";
        saved = saved.trim();

        Supplier<String> supplier = valueSuppliers.get(row.prop.getName());
        String val = (supplier != null) ? supplier.get() : "";
        if (val == null)
            val = ""; // Ensure non-null for comparison

        boolean dirty = false;

        // Special handling for JCheckBox as its value is boolean, not string directly
        if (row.input instanceof JCheckBox) {
            boolean savedBool = "true".equalsIgnoreCase(saved) || "yes".equalsIgnoreCase(saved)
                    || "1".equals(saved) || "on".equalsIgnoreCase(saved);
            boolean valBool = "true".equalsIgnoreCase(val);
            return (savedBool != valBool);
        }

        // List property detection: either a JScrollPane or our special JPanel wrapper
        // for PK checks
        boolean isList = (row.input instanceof JScrollPane) ||
                (row.input instanceof JPanel && Props.NODE_PK_CHECKS.getName().equals(row.prop.getName()));

        if (isList) {
            String normalizedVal = normalizeListValue(val, "\n");
            String normalizedSaved = normalizeListValue(saved, ";");
            dirty = !normalizedVal.equals(normalizedSaved);
        } else {
            dirty = !val.trim().equals(saved.trim());
        }

        if (Props.DB_URL.getName().equals(row.prop.getName())) {
            // The JDBC row is dirty when any of its effective values
            // (URL / username / password) differs from the saved ones —
            // the tuple model (the link itself is isDbLinkDirty's job).
            return jdbcValuesDirty();
        }

        return dirty;
    }

    private boolean isComponentDirty(Prop<?> prop) {
        String key = prop.getName();
        String saved = savedProfile.getProperty(key, getSafeDefault(prop));
        String current = valueSuppliers.get(key) != null ? valueSuppliers.get(key).get() : "";
        return !current.trim().equals(saved.trim());
    }

    /**
     * The DB engine's Auto Start/Stop row: visible for server engines only.
     * SQLite is an embedded engine — there is no separate database engine to
     * start or stop, so the row is N/A and hidden there (its flags are not
     * tracked either — see {@link #isDbLinkDirty()}).
     */
    private void updateAutoDbCheckboxesState() {
        if (autoStartDbCheck == null || autoStopDbCheck == null) {
            return;
        }
        JdbcConfigurationPanel p = jdbcPanel();
        boolean sqlite = p == null || p.getEngine() == DatabaseConfigurationPanel.DatabaseEngine.SQLITE;
        if (autoDbRow != null) {
            autoDbRow.setVisible(!sqlite);
        }
        autoStartDbCheck.setEnabled(true);
        autoStopDbCheck.setEnabled(true);
    }

    private void updateDirtyStatus() {
        boolean overallDirty = false;
        for (int i = 0; i < categoryTabbedPane.getTabCount(); i++) {
            boolean tabDirty = false;
            for (PropertyRow row : allPropertyRows) {
                if (row.tabIndex == i && isRowDirty(row)) {
                    tabDirty = true;
                    break;
                }
            }

            if (i == databaseTabIndex && !tabDirty) {
                tabDirty = isDbLinkDirty();
            }

            String title = categoryTabbedPane.getTitleAt(i);
            if (tabDirty) {
                if (!title.endsWith(" *"))
                    categoryTabbedPane.setTitleAt(i, title + " *");
                overallDirty = true;
            } else {
                if (title.endsWith(" *"))
                    categoryTabbedPane.setTitleAt(i, title.substring(0, title.length() - 2));
            }
        }
        // Icon-only toolbar: the dirty marker moves from the button label to the
        // tooltip (the category tab titles keep their "*" marker). The two halves
        // of the save-then-apply flow are independently usable:
        //   Save  — only while there is something to save (unsaved changes);
        //   Apply — only while there are saved, not yet applied changes.
        saveBtn.setToolTipText(overallDirty
                ? "Save — you have unsaved changes"
                : "Save");
        saveBtn.setEnabled(overallDirty);

        boolean savedNotApplied = hasSavedNotAppliedChanges();
        applyBtn.setToolTipText(savedNotApplied
                ? "<html>Apply<br><br>Applies the saved changes: marks the profile as applied<br>"
                        + "and restarts the node so they take effect.</html>"
                : "<html>Apply<br><br>Applies the saved changes: marks the profile as applied<br>"
                        + "and restarts the node so they take effect.<br>"
                        + "Active only while there are saved, not yet applied changes.</html>");
        applyBtn.setEnabled(savedNotApplied);
        this.overallDirty = overallDirty;

        // The Save button is an icon-only toolbar button: its size is uniform
        // with the other toolbar icons via styleProfileIconButton (and is
        // stable — the dirty marker lives in the tooltip, not the label).
        // Deliberately NO fixComponentSize here: that helper sizes TEXT
        // components (height = fontHeight + 10), which would make this button
        // ~3px taller than its icon-only siblings, grow the whole toolbar row
        // and offset the buttons in the same position relative to the other
        // profile toolbars (e.g. the module logging profiles) when switching
        // tabs.

        // The value-status visibility filter of the search panel depends on
        // the value states: when the flat results view is up, re-apply the
        // current search so the listed rows follow saves/reloads/copies. The
        // sameMatchSet guard keeps this a no-op when the set did not change,
        // so focus and scroll position stay put.
        if (searchViewActive && searchMatchPanel != null) {
            applySearch(searchMatchPanel.getSearchText(), false);
        }
    }

    /**
     * Whether the <b>saved</b> profile (what is on disk for the loaded profile)
     * differs from the <b>applied</b> baseline (what the node is actually
     * running) — i.e. there are saved, not yet applied changes the Apply
     * button can act on.
     */
    private boolean hasSavedNotAppliedChanges() {
        if (savedProfile == null || appliedProfile == null) {
            return false;
        }
        Set<String> keys = new HashSet<>(savedProfile.getProperties().stringPropertyNames());
        keys.addAll(appliedProfile.getProperties().stringPropertyNames());
        for (String key : keys) {
            String saved = savedProfile.getProperty(key, "");
            String applied = appliedProfile.getProperty(key, "");
            if (!saved.trim().equals(applied.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean isDbLinkDirty() {
        // The auto start/stop flags only count while their row is visible
        // (server engines — for SQLite the row is hidden and the flags are
        // N/A, so a stale checkbox state must not mark the row dirty).
        return !savedLinkedDb.equals(getLinkedDbProfile()) ||
                (autoStartDbCheck != null && autoStartDbCheck.isVisible() && savedDbAutoStart != autoStartDbCheck.isSelected()) ||
                (autoStopDbCheck != null && autoStopDbCheck.isVisible() && savedDbAutoStop != autoStopDbCheck.isSelected());
    }


    private Path getProfileMetadataPath() {
        return PathUtils.resolvePath(confFolder).resolve(Signum.NODE_SUBFOLDER).resolve("profile.json");
    }

    private Properties getPropertiesFromUI() {
        Properties props = new Properties();
        for (Map.Entry<String, Supplier<String>> entry : valueSuppliers.entrySet()) {
            String key = entry.getKey();
            String newValue = entry.getValue().get();
            String defaultValue = defaultValues.get(key);

            if (newValue != null && !newValue.equals(defaultValue)) {
                props.setProperty(key, newValue);
            }
        }
        return props;
    }

    private void updateUIFromProperties(Properties props) {
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();

            // Unwrap JPanel for PK checks to access the actual scroll pane
            if (comp instanceof JPanel && Props.NODE_PK_CHECKS.getName().equals(key)) {
                for (Component inner : ((JPanel) comp).getComponents()) {
                    if (inner instanceof JScrollPane) {
                        comp = (JComponent) inner;
                        break;
                    }
                }
            }

            if (comp instanceof JPanel && Props.DB_URL.getName().equals(key)) {
                String val = props.getProperty(key, defaultValues.get(key));
                String user = props.getProperty(Props.DB_USERNAME.getName(),
                        defaultValues.get(Props.DB_USERNAME.getName()));
                String pass = props.getProperty(Props.DB_PASSWORD.getName(),
                        defaultValues.get(Props.DB_PASSWORD.getName()));

                // A programmatic load into manual mode (no link callback);
                // the link recorded in the metadata is preserved by the host.
                JdbcConfigurationPanel p = jdbcPanel();
                if (p != null) {
                    p.forceManual(val, user, pass);
                    JCheckBox useProfileCheck = (JCheckBox) comp.getClientProperty("useProfileCheck");
                    if (useProfileCheck != null && useProfileCheck.isSelected()) {
                        useProfileCheck.setSelected(false);
                    }
                }
                updateColor(comp, key, defaultValues.get(key));
                continue;
            }

            String val = props.getProperty(key);
            if (val == null) {
                val = defaultValues.get(key);
            }
            if (val == null)
                val = "";

            if (comp instanceof JCheckBox) {
                boolean isSelected = "true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val) || "1".equals(val)
                        || "on".equalsIgnoreCase(val);
                ((JCheckBox) comp).setSelected(isSelected);
            } else if (comp instanceof JComboBox) {
                ((JComboBox<?>) comp).setSelectedItem(val);
            } else if (comp instanceof javax.swing.text.JTextComponent) {
                ((javax.swing.text.JTextComponent) comp).setText(val);
            } else if (comp instanceof JScrollPane) {
                JViewport viewport = ((JScrollPane) comp).getViewport();
                Component view = viewport.getView();
                if (view instanceof JTextArea) {
                    String[] items = val.split(";");
                    StringBuilder sb = new StringBuilder();
                    for (String item : items) {
                        if (!item.trim().isEmpty()) {
                            if (sb.length() > 0)
                                sb.append("\n");
                            sb.append(item.trim());
                        }
                    }
                    ((JTextArea) view).setText(sb.toString());
                }
            }
            updateColor(comp, key, defaultValues.get(key));
        }
    }

    private String[] getSuggestions(String propName) {
        Set<String> suggestions = new LinkedHashSet<>();
        String current = savedProfile.getProperty(propName);
        if (current != null && !current.isEmpty()) {
            suggestions.add(current);
        }

        // TODO check this part (why don't use Signum constants and utility method for
        // this?)
        Path[] paths = {
                PathUtils.resolvePath(confFolder).resolve(ModuleIds.NODE).resolve("node-default.properties"),
                PathUtils.resolvePath("conf/node/node-default.properties"),
                PathUtils.resolvePath("../conf/node/node-default.properties"),
                PathUtils.resolvePath("node/node/node-default.properties")
        };

        for (Path path : paths) {
            if (Files.exists(path)) {
                try {
                    List<String> lines = Files.readAllLines(path);
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("#")) {
                            String content = trimmed.substring(1).trim();
                            if (content.startsWith(propName)) {
                                String remainder = content.substring(propName.length()).trim();
                                if (remainder.startsWith("=")) {
                                    String val = remainder.substring(1).trim();
                                    if (!val.isEmpty()) {
                                        suggestions.add(val);
                                    }
                                }
                            }
                        }
                    }
                    break;
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        return suggestions.toArray(new String[0]);
    }

    private JPanel createCategoryPanel() {
        return createCategoryPanel(5);
    }

    private JPanel createCategoryPanel(int rowGap) {
        JPanel panel = new JPanel(new MigLayout("fillx, insets 10, gap " + rowGap, "[][grow]", ""));
        return panel;
    }

    private void finalizeCategoryPanel(JPanel panel) {
        panel.add(new JLabel(), "pushy");
    }

    private JScrollPane createScrollPane(JPanel panel) {
        JScrollPane scrollPane = new JScrollPane(panel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        return scrollPane;
    }

    private void addProperty(JPanel panel, Prop<?> prop, String labelText) {
        addProperty(panel, prop, labelText, null, false);
    }

    private void addProperty(JPanel panel, Prop<?> prop, String labelText, String[] options) {
        addProperty(panel, prop, labelText, options, false);
    }

    /**
     * Sets the per-profile {@code Signum} facade for this configuration panel.
     * Called by {@code NodeProfilePanel} after construction, mirroring how the
     * console panel is wired. Replaces the deprecated static
     * {@code Signum.getPropertyService()} bridge with a per-node, instance-scoped
     * lookup (no global active-instance indirection).
     *
     * @param signum this profile's Signum facade (may be null before start)
     */
    public void setSignum(Signum signum) {
        this.signum = signum;
    }

    public void loadAppliedProperties() {
        // No-op (intentional): the values loaded from the profile ARE the
        // applied values (see the appliedProfile initialization in the
        // constructor). This method used to overwrite that baseline with the
        // live PropertyService values, but their string form drifts from the
        // profile file's form (and until the node starts the baseline is
        // empty, falling back to the application defaults) — which colored
        // freshly loaded profile values "saved" instead of "applied". Kept for
        // API compatibility: ConfigurationPanel still invokes it on node
        // state changes.
        LOGGER.debug("loadAppliedProperties - no-op: the loaded profile values are the applied baseline");
    }

    private void addSectionHeader(JPanel panel, String title, boolean isFirst) {
        JLabel label = new JLabel(title);
        label.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 14f));
        panel.add(label, (isFirst ? "" : "gaptop 15, ") + "span, growx, wrap, gapbottom 5");
    }

    private void addProperty(JPanel panel, Prop<?> prop, String labelText, String[] options, boolean editable) {
        // Label — with the exact property key rendered below the name
        // (unified key style, see SearchMatchLabel#setKeyText).
        PropertyRow row = new PropertyRow(prop, labelText, panel, currentAddingTabIndex);
        SearchMatchLabel label = new SearchMatchLabel(labelText);
        label.setKeyText(prop.getName());
        row.label = label;
        row.labelConstraints = "align label";
        panel.add(label, row.labelConstraints);

        // Input Component
        String savedValue = savedProfile.getProperty(prop.getName());
        if (savedValue == null) {
            savedValue = getSafeDefault(prop);
        }
        defaultValues.put(prop.getName(), getSafeDefault(prop));

        JComponent inputComponent;

        if (prop.getDefaultValue() instanceof Boolean) {
            JCheckBox checkBox = new JCheckBox();
            boolean isSelected = "true".equalsIgnoreCase(savedValue) || "yes".equalsIgnoreCase(savedValue)
                    || "1".equals(savedValue) || "on".equalsIgnoreCase(savedValue);
            checkBox.setSelected(isSelected);
            inputComponent = checkBox;
            valueSuppliers.put(prop.getName(), () -> String.valueOf(checkBox.isSelected()));
            checkBox.addActionListener(e -> {
                if (isProgrammaticChange)
                    return;
                updateColor(checkBox, prop.getName(), getSafeDefault(prop));
                updateDirtyStatus();
            });
        } else if (options != null) {
            JComboBox<String> comboBox = new JComboBox<>(options);
            comboBox.setPrototypeDisplayValue("Prototype");
            comboBox.setSelectedItem(savedValue);
            comboBox.setEditable(editable);
            // If current value is not in options (e.g. custom), add it or handle gracefully
            if (comboBox.getSelectedItem() == null && savedValue != null) {
                comboBox.setEditable(true);
                comboBox.setSelectedItem(savedValue);
            }
            ConfigurationUtils.fixComponentSize(comboBox);
            inputComponent = comboBox;
            valueSuppliers.put(prop.getName(), () -> (String) comboBox.getSelectedItem());

            comboBox.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                        boolean isSelected, boolean cellHasFocus) {
                    Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    String savedVal = savedProfile.getProperty(prop.getName());
                    if (savedVal == null)
                        savedVal = getSafeDefault(prop);

                    String applied = appliedProfile.getProperty(prop.getName());
                    boolean hasApplied = appliedProfile.getProperties().containsKey(prop.getName());
                    if (applied == null)
                        applied = getSafeDefault(prop);

                    if (hasApplied && value != null && value.toString().trim().equals(applied.trim())) {
                        c.setForeground(GuiColors.getApplied());
                    } else if (value != null && value.toString().trim().equals(savedVal.trim())) {
                        c.setForeground(GuiColors.getSaved());
                    } else {
                        c.setForeground(GuiColors.getUnsaved());
                    }
                    return c;
                }
            });
            comboBox.addActionListener(e -> {
                if (isProgrammaticChange)
                    return;
                updateColor(comboBox, prop.getName(), getSafeDefault(prop));
                updateDirtyStatus();
            });
        } else {
            JTextField textField = new JTextField(savedValue);
            ConfigurationUtils.styleInputComponent(textField);

            // Fix dimensions to match standard text fields
            ConfigurationUtils.fixComponentSize(textField);

            inputComponent = textField;
            valueSuppliers.put(prop.getName(), textField::getText);
            textField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {

                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    if (isProgrammaticChange)
                        return;
                    SwingUtilities.invokeLater(() -> {
                        updateColor(textField, prop.getName(), getSafeDefault(prop));
                        updateDirtyStatus();
                    });
                }

                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    if (isProgrammaticChange)
                        return;
                    SwingUtilities.invokeLater(() -> {
                        updateColor(textField, prop.getName(), getSafeDefault(prop));
                        updateDirtyStatus();
                    });
                }

                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    if (isProgrammaticChange)
                        return;
                    SwingUtilities.invokeLater(() -> {
                        updateColor(textField, prop.getName(), getSafeDefault(prop));
                        updateDirtyStatus();
                    });
                }
            });
        }

        updateColor(inputComponent, prop.getName(), getSafeDefault(prop));
        row.inputConstraints = inputComponent instanceof JCheckBox
                ? "split 2, height pref!" : "split 2, growx, height pref!";
        panel.add(inputComponent, row.inputConstraints);
        propertyComponents.put(prop.getName(), inputComponent);

        // Help Button
        JButton helpBtn = new HelpButton();
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info");
        helpBtn.addActionListener(e ->

        showHelp(prop, labelText));

        row.input = inputComponent;

        row.help = helpBtn;
        row.helpConstraints = "wrap";
        panel.add(helpBtn, row.helpConstraints);
        row.separator = new JSeparator();
        row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";
        panel.add(row.separator, row.separatorConstraints);

        addPropertyRow(row);
    }

    private void addJdbcUrlProperty(JPanel panel, Prop<String> prop, String labelText) {
        // Label — with the exact property key rendered below the name
        // (unified key style, see SearchMatchLabel#setKeyText).
        PropertyRow row = new PropertyRow(prop, labelText, panel, currentAddingTabIndex);
        SearchMatchLabel label = new SearchMatchLabel(labelText);
        label.setKeyText(prop.getName());
        row.label = label;
        row.labelConstraints = "align label, aligny top";
        panel.add(label, row.labelConstraints);

        JPanel wrapper = new JPanel(new MigLayout("insets 0, fillx, gap 2", "[grow]", "[]5[]"));
        wrapper.setOpaque(false);

        JCheckBox useProfileCheck = new JCheckBox("Linked Database Profile");
        useProfileCheck.setOpaque(false);
        wrapper.add(useProfileCheck, "wrap");

        final boolean[] jdbcInitialized = { false };
        Runnable jdbcOnChange = () -> {
            if (jdbcInitialized[0]) {
                updateColor(wrapper, prop.getName(), defaultValues.get(prop.getName()));
                updateAutoDbCheckboxesState(); // the panel's engine may differ
                updateDirtyStatus();
            }
        };

        // The unified, mode-aware JDBC editor (manual + linked in one panel —
        // no CardLayout cards and no settle hacks: the panel sizes to its own
        // visible rows).
        JdbcConfigurationPanel jdbcPanel = new JdbcConfigurationPanel(confFolder, jdbcOnChange,
                this::onJdbcLinkChanged);
        wrapper.add(jdbcPanel, "growx");

        // The panel is the single "card" of the row (the client-property
        // contract the auto-link integration test reads).
        final Component[] currentJdbcCard = { jdbcPanel };

        // Auto start/stop of the linked database (moved here from the former
        // "Linked Profiles" tab — the JDBC row now owns the link details).
        autoStartDbCheck = new JCheckBox("Auto Start Database");
        autoStopDbCheck = new JCheckBox("Auto Stop Database");
        autoStartDbCheck.setOpaque(false);
        autoStopDbCheck.setOpaque(false);
        ActionListener autoDbCheckListener = e -> {
            if (!isProgrammaticChange) {
                updateDirtyStatus();
            }
        };
        autoStartDbCheck.addActionListener(autoDbCheckListener);
        autoStopDbCheck.addActionListener(autoDbCheckListener);
        autoDbRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        autoDbRow.setOpaque(false);
        autoDbRow.add(autoStartDbCheck);
        autoDbRow.add(autoStopDbCheck);
        wrapper.add(autoDbRow, "span, gaptop 4");

        // The host's "Linked Database Profile" checkbox drives the panel's
        // link mode; the panel may auto-unlink itself (e.g. an unmaterializable
        // link at load) — onJdbcLinkChanged keeps the checkbox in sync.
        useProfileCheck.addActionListener(e -> {
            if (isProgrammaticChange)
                return;
            jdbcPanel.requestLinkMode(useProfileCheck.isSelected());
            updateAutoDbCheckboxesState(); // the panel's engine may differ
            updateDirtyStatus();
            refreshUIColors();
        });

        // Client-property contract (the auto-link integration test reads
        // these): the JDBC panel is the single card of the row.
        wrapper.putClientProperty("profilePanel", jdbcPanel);
        wrapper.putClientProperty("useProfileCheck", useProfileCheck);
        wrapper.putClientProperty("currentJdbcCard", currentJdbcCard);

        // Populate valueSuppliers and propertyComponents for DB_URL, DB_USERNAME,
        // DB_PASSWORD early
        // to prevent NullPointerException when the initial load triggers the
        // onChange callback, which calls updateColor.
        valueSuppliers.put(Props.DB_USERNAME.getName(),
                () -> {
                    JdbcConfigurationPanel p = jdbcPanel();
                    return p != null ? p.getUsername() : "";
                });
        valueSuppliers.put(Props.DB_PASSWORD.getName(),
                () -> {
                    JdbcConfigurationPanel p = jdbcPanel();
                    return p != null ? p.getPassword() : "";
                });
        valueSuppliers.put(prop.getName(), // This is Props.DB_URL
                () -> {
                    JdbcConfigurationPanel p = jdbcPanel();
                    return p != null ? p.getJdbcUrl() : "";
                });

        // Register the editable credential components for search/highlight
        propertyComponents.put(Props.DB_USERNAME.getName(), (JComponent) jdbcPanel.getUserEditComponent());
        propertyComponents.put(Props.DB_PASSWORD.getName(), (JComponent) jdbcPanel.getPassField());

        String savedValue = savedProfile.getProperty(prop.getName(), getSafeDefault(prop));
        String savedUser = savedProfile.getProperty(Props.DB_USERNAME.getName(),
                getSafeDefault(Props.DB_USERNAME));
        String savedPass = savedProfile.getProperty(Props.DB_PASSWORD.getName(),
                getSafeDefault(Props.DB_PASSWORD));

        jdbcInitialized[0] = true;
        jdbcPanel.loadManual(savedValue, savedUser, savedPass);

        defaultValues.put(prop.getName(), getSafeDefault(prop));
        defaultValues.put(Props.DB_USERNAME.getName(), getSafeDefault(Props.DB_USERNAME));
        defaultValues.put(Props.DB_PASSWORD.getName(), getSafeDefault(Props.DB_PASSWORD));

        row.input = wrapper;
        row.inputConstraints = "split 2, growx";
        panel.add(wrapper, row.inputConstraints);
        propertyComponents.put(prop.getName(), wrapper);
        // Initial enablement of the auto start/stop boxes follows the current
        // (manual card's) engine.
        updateAutoDbCheckboxesState();

        JButton helpBtn = new HelpButton();
        helpBtn.addActionListener(e -> showHelp(prop, labelText));
        row.help = helpBtn;
        row.helpConstraints = "wrap, aligny top";
        panel.add(helpBtn, row.helpConstraints);
        row.separator = new JSeparator();
        row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";
        panel.add(row.separator, row.separatorConstraints);
        addPropertyRow(row);
        updateColor(wrapper, prop.getName(), defaultValues.get(prop.getName()));
    }

    private void addPasswordProperty(JPanel panel, Prop<String> prop, String labelText) {
        // Label — with the exact property key rendered below the name
        // (unified key style, see SearchMatchLabel#setKeyText).
        PropertyRow row = new PropertyRow(prop, labelText, panel, currentAddingTabIndex);
        SearchMatchLabel label = new SearchMatchLabel(labelText);
        label.setKeyText(prop.getName());
        row.label = label;
        row.labelConstraints = "align label";
        panel.add(label, row.labelConstraints);

        // Input Component
        String savedValue = savedProfile.getProperty(prop.getName());
        if (savedValue == null) {
            savedValue = getSafeDefault(prop);
        }
        defaultValues.put(prop.getName(), getSafeDefault(prop));
        JPasswordField passwordField = new JPasswordField(savedValue);
        passwordField.setColumns(20);
        ConfigurationUtils.styleInputComponent(passwordField);

        // Fix dimensions to match standard text fields (consistent with addProperty)
        ConfigurationUtils.fixComponentSize(passwordField);

        char defaultEchoChar = passwordField.getEchoChar();

        passwordField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                if (isProgrammaticChange)
                    return;
                SwingUtilities.invokeLater(() -> {
                    updateColor(passwordField, prop.getName(), getSafeDefault(prop));
                    updateDirtyStatus();
                });
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                if (isProgrammaticChange)
                    return;
                SwingUtilities.invokeLater(() -> {
                    updateColor(passwordField, prop.getName(), getSafeDefault(prop));
                    updateDirtyStatus();
                });
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                if (isProgrammaticChange)
                    return;
                SwingUtilities.invokeLater(() -> {
                    updateColor(passwordField, prop.getName(), getSafeDefault(prop));
                    updateDirtyStatus();
                });
            }
        });
        updateColor(passwordField, prop.getName(), getSafeDefault(prop));
        row.inputConstraints = "split 2, growx, height pref!";
        panel.add(passwordField, row.inputConstraints);

        // Help Button
        JButton helpBtn = new HelpButton();
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info");
        helpBtn.addActionListener(e -> showHelp(prop, labelText));
        row.help = helpBtn;
        row.helpConstraints = "wrap";
        panel.add(helpBtn, row.helpConstraints);

        // Show/Hide Checkbox
        JCheckBox showPass = new JCheckBox("Show Password");
        showPass.addActionListener(e -> {
            passwordField.setEchoChar(showPass.isSelected() ? (char) 0 : defaultEchoChar);
        });
        row.extra = showPass;
        row.extraConstraints = "skip 1, wrap";
        panel.add(showPass, row.extraConstraints);

        row.separator = new JSeparator();
        row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";
        panel.add(row.separator, row.separatorConstraints);

        valueSuppliers.put(prop.getName(), () -> new String(passwordField.getPassword()));
        propertyComponents.put(prop.getName(), passwordField);

        row.input = passwordField;
        addPropertyRow(row);
    }

    /**
     * The node's <b>database</b> profile link, read from the JDBC row's
     * "Linked Database Profile" control: {@code "Engine:Profile"} when the
     * checkbox is selected and a profile is chosen, else the empty string
     * (manual mode / no link).
     */
    public String getLinkedDbProfile() {
        JdbcConfigurationPanel p = jdbcPanel();
        return p != null ? p.getLink() : "";
    }

    /**
     * The node's <b>logging</b> profile assignment, read from the SSOT
     * ({@link LoggingAssignmentStore}) — the assignment is managed on the
     * Logging tab. Kept for hosts (e.g. the legacy main-window panel) that
     * still display the link.
     */
    public String getLinkedLoggingProfile() {
        try {
            if (loadedProfileName == null) {
                return "";
            }
            Map<String, String> assignments = new LoggingAssignmentStore(confFolder).getAssignment(loadedProfileName);
            String linked = assignments.get(ModuleIds.NODE);
            return linked != null ? linked : "";
        } catch (Exception e) {
            return "";
        }
    }

    public void setLinkedDbProfile(String value) {
        JdbcConfigurationPanel p = jdbcPanel();
        if (p == null) {
            return;
        }
        p.applyLink(value, p.getJdbcUrl(), p.getUsername());
        saveDatabaseAssignment(loadedProfileName);
    }

    public void setLinkedLoggingProfile(String value) {
        try {
            new LoggingAssignmentStore(confFolder).setAssignmentForModule(loadedProfileName, ModuleIds.NODE, value);
        } catch (Exception e) {
            LOGGER.error("Error setting linked logging profile", e);
        }
    }

    private void addListProperty(JPanel panel, Prop<?> prop, String labelText) {
        // Label — with the exact property key rendered below the name
        // (unified key style, see SearchMatchLabel#setKeyText).
        PropertyRow row = new PropertyRow(prop, labelText, panel, currentAddingTabIndex);
        SearchMatchLabel label = new SearchMatchLabel(labelText);
        label.setKeyText(prop.getName());
        row.label = label;
        row.labelConstraints = "align label, aligny top";
        panel.add(label, row.labelConstraints);

        String savedValue = savedProfile.getProperty(prop.getName());
        if (savedValue == null) {
            savedValue = getSafeDefault(prop);
        }
        defaultValues.put(prop.getName(), normalizeListValue(getSafeDefault(prop), ";"));
        // Split by semicolon and join with newlines for display
        String[] items = savedValue.split(";");
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            String p = item.trim();
            if (!p.isEmpty()) {
                if (sb.length() > 0)
                    sb.append("\n");
                sb.append(p);
            }
        }

        JTextArea textArea = new JTextArea(sb.toString());
        textArea.setRows(4);
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        textArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                if (isProgrammaticChange)
                    return;
                SwingUtilities.invokeLater(() -> {
                    updateColor(textArea, prop.getName(), getSafeDefault(prop));
                    updateDirtyStatus();
                });
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                if (isProgrammaticChange)
                    return;
                SwingUtilities.invokeLater(() -> {
                    updateColor(textArea, prop.getName(), getSafeDefault(prop));
                    updateDirtyStatus();
                });
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                if (isProgrammaticChange)
                    return;
                SwingUtilities.invokeLater(() -> {
                    updateColor(textArea, prop.getName(), getSafeDefault(prop));
                    updateDirtyStatus();
                });
            }
        });
        updateColor(textArea, prop.getName(), getSafeDefault(prop));

        boolean isPkCheck = Props.NODE_PK_CHECKS.getName().equals(prop.getName());
        if (isPkCheck) {
            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.setOpaque(false);
            wrapper.add(scrollPane, BorderLayout.CENTER);

            JButton convertBtn = new JButton(
                    IconFontSwing.buildIcon(FontAwesome.MAGIC, GuiConstants.getHelpIconSize(),
                            GuiColors.getHelpIcon()));
            convertBtn.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
            convertBtn.setContentAreaFilled(false);
            convertBtn.setFocusPainted(false);
            convertBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            convertBtn.setToolTipText("Analyze/Convert Public Keys");
            convertBtn.addActionListener(e -> showPkConversionDialog(textArea));

            JPanel btnContainer = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            btnContainer.setOpaque(false);
            btnContainer.add(convertBtn);
            wrapper.add(btnContainer, BorderLayout.SOUTH);

            panel.add(wrapper, "split 2, growx, hmin 80");
            propertyComponents.put(prop.getName(), wrapper); // Store wrapper for visibility handling
        } else {
            panel.add(scrollPane, "split 2, growx, hmin 80");
            propertyComponents.put(prop.getName(), scrollPane);
        }
        row.inputConstraints = "split 2, growx, hmin 80";

        // Help Button
        JButton helpBtn = new HelpButton();
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info");
        helpBtn.addActionListener(e -> showHelp(prop, labelText));
        row.help = helpBtn;
        row.helpConstraints = "wrap, aligny top";
        panel.add(helpBtn, row.helpConstraints);
        row.separator = new JSeparator();
        row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";
        panel.add(row.separator, row.separatorConstraints);

        row.input = propertyComponents.get(prop.getName());
        addPropertyRow(row);

        valueSuppliers.put(prop.getName(), () -> normalizeListValue(textArea.getText(), "\n"));
    }

    private void showPkConversionDialog(JTextArea textArea) {
        JPanel panel = new JPanel(new MigLayout("fill, insets 5", "[grow]", "[][][grow]"));

        // --- Header with help text ---
        String helpText = "<html><body style='width: 650px;'>"
                + "<b>PK Checks Manager</b><br>"
                + "<p>This tool helps manage the <b>node.pkChecks</b> account freeze list. This is a security feature used to prevent specific accounts from sending transactions when the PK_FREEZE network feature is active.</p>"
                + "<b>How to Use:</b><ul>"
                + "<li><b>Analysis:</b> The text area below analyzes the current configuration, showing the decoded Account ID and RS Address for each 16-character hex entry.</li>"
                + "<li><b>Convert & Add:</b><ol>"
                + "<li>Enter an account identifier (Numeric ID, RS Address, or a 16-char Hex ID) into the input field.</li>"
                + "<li>Click 'Convert'. The tool will generate the correct 16-character Little Endian hexadecimal string required for the configuration.</li>"
                + "<li>A dialog will then prompt you to add the generated hex string to the main configuration list.</li>"
                + "</ol></li></ul>"
                + "</body></html>";
        JLabel helpLabel = new JLabel(helpText);
        helpLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
        panel.add(helpLabel, "wrap, growx, gaptop 5");

        // Input Panel
        JPanel inputPanel = new JPanel(new MigLayout("insets 0, fillx", "[][grow][]", "[]"));

        JTextField inputField = new JTextField();
        inputField.setToolTipText("Enter Account ID (Numeric), RS Address, or 16-char Hex");
        JButton convertButton = new JButton("Convert");

        inputPanel.add(new JLabel("Account:"), "gapright 5");
        inputPanel.add(inputField, "growx");
        inputPanel.add(convertButton, "gapleft 5");
        panel.add(inputPanel, "wrap, growx");

        // Result Area
        JTextArea resultArea = new JTextArea();
        resultArea.setEditable(false);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(resultArea);

        panel.add(scroll, "grow");

        // Analyze current config
        StringBuilder analysis = new StringBuilder("--- Current Configuration Analysis ---\n");
        String content = textArea.getText();
        String[] lines = content.split("\n");
        Pattern pkPattern = Pattern.compile("[0-9a-fA-F]{16}");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty())
                continue;

            Matcher m = pkPattern.matcher(line);
            if (m.find()) {
                String hexId = m.group();
                try {
                    long accountId = getAccountIdFromHex(hexId);
                    String rs = Crypto.rsEncode(accountId);

                    analysis.append(
                            String.format("Hex: %s -> ID: %s (%s)\n", hexId, Convert.toUnsignedLong(accountId), rs));
                } catch (Exception e) {
                    analysis.append("Error processing line: ").append(line).append("\n");
                }
            } else {
                analysis.append("Ignored: ").append(line).append("\n");
            }
        }
        resultArea.setText(analysis.toString());

        // Logic
        convertButton.addActionListener(e -> {
            String input = inputField.getText().trim();
            if (input.isEmpty())
                return;

            try {
                long accountId;
                String hex;
                String type;

                if (input.matches("[0-9a-fA-F]{16}")) {
                    type = "Hex";
                    hex = input;
                    accountId = getAccountIdFromHex(hex);
                } else {
                    try {
                        // Try RS
                        accountId = Crypto.rsDecode(input);
                        type = "RS Address";
                    } catch (Exception ex) {
                        // Try Numeric
                        try {
                            accountId = Convert.parseUnsignedLong(input);
                            type = "Numeric ID";
                        } catch (Exception ex2) {
                            throw new IllegalArgumentException("Invalid Account ID or RS Address");
                        }
                    }
                    hex = getHexFromAccountId(accountId);
                }

                String numeric = Convert.toUnsignedLong(accountId);
                String rs = Crypto.rsEncode(accountId);

                String resultMsg = String.format(
                        "Conversion Result (%s):\n" +
                                "--------------------------------------------------\n" +
                                "Hex (for config): %s\n" +
                                "Account ID:       %s\n" +
                                "RS Address:       %s\n" +
                                "--------------------------------------------------\n\n",
                        type, hex, numeric, rs);

                resultArea.insert(resultMsg, 0);
                resultArea.setCaretPosition(0);

                int choice = JOptionPane.showConfirmDialog(panel,
                        "Conversion successful!\n\nHex: " + hex + "\nAccount: " + rs
                                + "\n\nAdd this to the configuration list?",
                        "Add to Configuration", JOptionPane.YES_NO_OPTION);

                if (choice == JOptionPane.YES_OPTION) {
                    String currentText = textArea.getText();
                    if (!currentText.isEmpty() && !currentText.endsWith("\n")) {
                        textArea.append("\n");
                    }
                    textArea.append(hex);
                }

            } catch (Exception ex) {
                JOptionPane.showMessageDialog(panel, "Could not parse input: " + ex.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        JOptionPane.showMessageDialog(this, panel, "PK Checks Manager", JOptionPane.PLAIN_MESSAGE);
    }

    private long getAccountIdFromHex(String hexId) {
        byte[] bytes = Convert.parseHexString(hexId);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return buffer.getLong();
    }

    private String getHexFromAccountId(long accountId) {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(accountId);
        return Convert.toHexString(buffer.array());
    }

    private String getSafeDefault(Prop<?> prop) {
        Object def = prop.getDefaultValue();
        return def == null ? "" : String.valueOf(def);
    }

    /**
     * The three value-level states that drive the row color coding and the
     * search-panel visibility checkboxes.
     */
    private enum ValueStatus {
        UNSAVED(GuiColors.getUnsaved()), SAVED(GuiColors.getSaved()), APPLIED(GuiColors.getApplied());

        private final Color color;

        ValueStatus(Color color) {
            this.color = color;
        }

        public Color color() {
            return color;
        }
    }

    /**
     * JDK-25-safe copy of a {@link Properties}: the
     * {@code Properties(Properties)} constructor NO LONGER copies entries in
     * JDK 25 (Properties now backs itself with a {@code ConcurrentHashMap}
     * and that constructor treats the argument as a *defaults* fallback
     * chain, leaving the new instance EMPTY), so copy via an explicit
     * {@code putAll} instead.
     *
     * @param source the properties to copy (may be null)
     * @return an independent copy of {@code source} (empty when null)
     */
    private static Properties copyProperties(Properties source) {
        Properties copy = new Properties();
        if (source != null)
            copy.putAll(source);
        return copy;
    }

    /**
     * Resolves the value-level state of a configuration value against the
     * saved (profile-on-disk) and applied (the values loaded from the profile
     * into the editor — the baseline the node runs with) values:
     * {@link ValueStatus#APPLIED} (green) when it equals the applied value,
     * {@link ValueStatus#SAVED} (yellow) when it equals the saved value,
     * otherwise {@link ValueStatus#UNSAVED} (normal text color). Boolean
     * properties compare parsed booleans, all other values compare as trimmed
     * strings — the exact rules the color coding uses.
     */
    private static ValueStatus valueStatus(boolean isCheckbox, String value, String saved, String applied) {
        if (isCheckbox) {
            boolean savedBool = parseValueBool(saved);
            boolean appliedBool = parseValueBool(applied);
            boolean valBool = parseValueBool(value);
            if (valBool == appliedBool) {
                return ValueStatus.APPLIED;
            }
            if (valBool == savedBool) {
                return ValueStatus.SAVED;
            }
            return ValueStatus.UNSAVED;
        }
        String v = value == null ? "" : value.trim();
        String a = applied == null ? "" : applied.trim();
        String s = saved == null ? "" : saved.trim();
        if (v.equals(a)) {
            return ValueStatus.APPLIED;
        }
        if (v.equals(s)) {
            return ValueStatus.SAVED;
        }
        return ValueStatus.UNSAVED;
    }

    /** @return true when the value parses as a boolean "on" state */
    private static boolean parseValueBool(String s) {
        return "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s)
                || "1".equals(s) || "on".equalsIgnoreCase(s);
    }

    /**
     * The saved (profile-on-disk) JDBC tuple: (link, url, username, password).
     */
    private JdbcConfigurationPanel.JdbcState savedJdbcState() {
        return new JdbcConfigurationPanel.JdbcState(savedLinkedDb,
                savedProfile.getProperty(Props.DB_URL.getName(), getSafeDefault(Props.DB_URL)),
                savedProfile.getProperty(Props.DB_USERNAME.getName(), getSafeDefault(Props.DB_USERNAME)),
                savedProfile.getProperty(Props.DB_PASSWORD.getName(), getSafeDefault(Props.DB_PASSWORD)));
    }

    /**
     * The applied (the loaded baseline the node runs with) JDBC tuple:
     * (link, url, username, password).
     */
    private JdbcConfigurationPanel.JdbcState appliedJdbcState() {
        return new JdbcConfigurationPanel.JdbcState(appliedLinkedDb,
                appliedProfile.getProperty(Props.DB_URL.getName(), getSafeDefault(Props.DB_URL)),
                appliedProfile.getProperty(Props.DB_USERNAME.getName(), getSafeDefault(Props.DB_USERNAME)),
                appliedProfile.getProperty(Props.DB_PASSWORD.getName(), getSafeDefault(Props.DB_PASSWORD)));
    }

    /**
     * Whether the JDBC row's effective values (URL / username / password)
     * differ from the saved ones — the row-level (not link) dirty signal.
     */
    private boolean jdbcValuesDirty() {
        JdbcConfigurationPanel p = jdbcPanel();
        if (p == null) {
            return false;
        }
        JdbcConfigurationPanel.JdbcState current = p.getState();
        JdbcConfigurationPanel.JdbcState saved = savedJdbcState();
        return !current.url().equals(saved.url()) || !current.username().equals(saved.username())
                || !current.password().equals(saved.password());
    }

    /**
     * The value status of the JDBC row's effective values against the saved
     * and applied baselines (url/username/password — the link element is the
     * Profile row's own comparison).
     */
    private ValueStatus jdbcRowStatus() {
        JdbcConfigurationPanel p = jdbcPanel();
        if (p == null) {
            return ValueStatus.UNSAVED;
        }
        JdbcConfigurationPanel.JdbcState current = p.getState();
        JdbcConfigurationPanel.JdbcState saved = savedJdbcState();
        JdbcConfigurationPanel.JdbcState applied = appliedJdbcState();
        JdbcConfigurationPanel.JdbcState curVals = new JdbcConfigurationPanel.JdbcState("", current.url(),
                current.username(), current.password());
        JdbcConfigurationPanel.JdbcState savedVals = new JdbcConfigurationPanel.JdbcState("", saved.url(),
                saved.username(), saved.password());
        JdbcConfigurationPanel.JdbcState appliedVals = new JdbcConfigurationPanel.JdbcState("", applied.url(),
                applied.username(), applied.password());
        if (curVals.equals(appliedVals)) {
            return ValueStatus.APPLIED;
        }
        if (curVals.equals(savedVals)) {
            return ValueStatus.SAVED;
        }
        return ValueStatus.UNSAVED;
    }

    /**
     * Resolves the value-level state ({@link ValueStatus}) of the given row's
     * component, mirroring the value resolution and comparison that
     * {@link #updateColor} performs for the color coding — so the search-panel
     * visibility checkboxes and the row colors can never disagree.
     */
    private ValueStatus rowStatus(PropertyRow row) {
        if (row == null || row.prop == null || row.input == null) {
            return ValueStatus.UNSAVED;
        }
        JComponent comp = row.input;
        String key = row.prop.getName();
        String defaultValue = defaultValues.get(key);

        // The JDBC row uses the tuple value-state model (JdbcState) — the same
        // model its color coding uses, so the search filter never disagrees.
        if (comp instanceof JPanel && Props.DB_URL.getName().equals(key)) {
            return jdbcRowStatus();
        }

        JComponent target = comp;
        if (comp instanceof JScrollPane && ((JScrollPane) comp).getViewport().getView() instanceof JTextArea) {
            target = (JComponent) ((JScrollPane) comp).getViewport().getView();
        } else if (comp instanceof JPanel && Props.NODE_PK_CHECKS.getName().equals(key)) {
            for (Component inner : ((JPanel) comp).getComponents()) {
                if (inner instanceof JScrollPane) {
                    Component view = ((JScrollPane) inner).getViewport().getView();
                    if (view instanceof JTextArea) {
                        target = (JComponent) view;
                        break;
                    }
                }
            }
        }

        String value = "";
        if (comp instanceof JPanel && Props.DB_URL.getName().equals(key)) {
            Supplier<String> supplier = valueSuppliers.get(key);
            String supplierVal = supplier != null ? supplier.get() : "";
            value = supplierVal != null ? supplierVal.trim() : "";
        } else if (comp instanceof JCheckBox) {
            value = String.valueOf(((JCheckBox) comp).isSelected());
        } else if (comp instanceof JComboBox) {
            Object item = ((JComboBox<?>) comp).getSelectedItem();
            value = item == null ? "" : item.toString();
        } else if (target instanceof javax.swing.text.JTextComponent) {
            value = ((javax.swing.text.JTextComponent) target).getText();
            if (target instanceof JTextArea) {
                value = normalizeListValue(value, "\n");
            } else {
                value = value.trim();
            }
        }
        String savedValue = savedProfile.getProperty(key, defaultValue);
        String applied = appliedProfile.getProperty(key, defaultValue);
        if (savedValue == null)
            savedValue = "";
        if (applied == null)
            applied = "";
        savedValue = savedValue.trim();
        applied = applied.trim();
        if (target instanceof JTextArea) {
            savedValue = normalizeListValue(savedValue, ";");
            applied = normalizeListValue(applied, ";");
        }
        return valueStatus(comp instanceof JCheckBox, value, savedValue, applied);
    }

    /** @return true when the row's value state is still selected by its search-panel checkbox */
    private boolean isRowStatusVisible(PropertyRow row) {
        switch (rowStatus(row)) {
            case UNSAVED:
                return showUnsavedBox.isSelected();
            case SAVED:
                return showSavedBox.isSelected();
            case APPLIED:
            default:
                return showAppliedBox.isSelected();
        }
    }

    private void updateColor(JComponent comp, String propName, String defaultValue) {
        String value = "";
        JComponent target = comp;

        // Unwrap wrappers for lists and special components
        if (comp instanceof JScrollPane && ((JScrollPane) comp).getViewport().getView() instanceof JTextArea) {
            target = (JComponent) ((JScrollPane) comp).getViewport().getView();
        } else if (comp instanceof JPanel && Props.NODE_PK_CHECKS.getName().equals(propName)) {
            for (Component inner : ((JPanel) comp).getComponents()) {
                if (inner instanceof JScrollPane) {
                    Component view = ((JScrollPane) inner).getViewport().getView();
                    if (view instanceof JTextArea) {
                        target = (JComponent) view;
                        break;
                    }
                }
            }
        }

        if (comp instanceof JPanel && Props.DB_URL.getName().equals(propName)) {
            // The JDBC row is colored by the panel's tuple value-state model
            // (JdbcState baselines) — the URL-segment comparison is gone.
            JdbcConfigurationPanel p = jdbcPanel();
            if (p != null) {
                p.setBaselines(savedJdbcState(), appliedJdbcState());
                p.refreshColors();
            }
            Supplier<String> supplier = valueSuppliers.get(propName);
            String supplierVal = supplier != null ? supplier.get() : "";
            value = supplierVal != null ? supplierVal.trim() : "";
        }

        String savedValue = savedProfile.getProperty(propName, defaultValue);
        String applied = appliedProfile.getProperty(propName, defaultValue);

        if (savedValue == null)
            savedValue = "";
        if (applied == null)
            applied = "";
        savedValue = savedValue.trim();
        applied = applied.trim();

        if (comp instanceof JCheckBox) {
            value = String.valueOf(((JCheckBox) comp).isSelected());
        } else if (comp instanceof JComboBox) {
            Object item = ((JComboBox<?>) comp).getSelectedItem();
            value = item == null ? "" : item.toString();
            if (((JComboBox<?>) comp).isEditable()) {
                target = (JComponent) ((JComboBox<?>) comp).getEditor().getEditorComponent();
            }
        } else if (target instanceof javax.swing.text.JTextComponent) {
            value = ((javax.swing.text.JTextComponent) target).getText();
            if (target instanceof JTextArea) {
                value = normalizeListValue(value, "\n");
                savedValue = normalizeListValue(savedValue, ";");
                applied = normalizeListValue(applied, ";");
            } else {
                value = value.trim();
                savedValue = savedValue.trim();
                applied = applied.trim();
            }
        }

        Color color = valueStatus(comp instanceof JCheckBox, value, savedValue, applied).color();

        PropertyRow row = allPropertyRows.stream()
                .filter(r -> r.prop != null && r.prop.getName().equals(propName))
                .findFirst()
                .orElse(null);
        if (row != null && row.label != null) {
            boolean isDirty;
            if (comp instanceof JCheckBox) {
                boolean savedBool = "true".equalsIgnoreCase(savedValue) || "yes".equalsIgnoreCase(savedValue)
                        || "1".equals(savedValue) || "on".equalsIgnoreCase(savedValue);
                boolean valBool = "true".equalsIgnoreCase(value);
                isDirty = (valBool != savedBool);
            } else if (comp instanceof JPanel && Props.DB_URL.getName().equals(propName)) {
                isDirty = jdbcValuesDirty();
            } else {
                isDirty = !value.equals(savedValue);
            }
            row.label.setText(isDirty ? row.labelText + " *" : row.labelText);
        }

        if (target instanceof JTextPane) {
            JTextPane pane = (JTextPane) target;
            SimpleAttributeSet attrs = new SimpleAttributeSet();
            StyleConstants.setForeground(attrs, color);
            pane.getStyledDocument().setCharacterAttributes(0, pane.getText().length(), attrs, true);
        } else {
            target.setForeground(color);
        }
    }

    private void showColorLegendHelp() {
        String msg = "<html><body style='width: 350px'>" +
                "<h3>Color Coding Legend</h3>" +
                "<p>The configuration values are color-coded to indicate their current status:</p>" +
                "<ul>" +
                "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getUnsaved())
                + "'>\u25A0 Unsaved Values:</font></b> " +
                "These values have been modified in the UI and match neither the values loaded from the profile " +
                "nor the values saved in the configuration file. " +
                "Properties with unsaved changes are marked with an asterisk (*).</li>" +
                "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getSaved())
                + "'>\u25A0 Saved Values:</font></b> " +
                "These values are saved in the currently loaded profile on disk, but they differ from the values " +
                "the profile loaded into the editor (the applied baseline) — saved, but not yet applied.</li>" +
                "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getApplied())
                + "'>\u25A0 Applied Values:</font></b> " +
                "These values match exactly the values loaded from the currently loaded profile — the baseline " +
                "this node runs (or will run) with. Note that most changes require a restart to take effect.</li>"
                +
                "</ul>" +
                "</body></html>";
        JOptionPane.showMessageDialog(this, msg, "Color Legend", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showHelp(Prop<?> prop, String labelText) {
        String description = helpTexts.getOrDefault(prop.getName(), "No detailed description available.");
        String message = "<html><body style='width: 300px'>" +
                "<h2>" + labelText + "</h2>" +
                "<p><b>Property Key:</b> " + prop.getName() + "</p>" +
                "<p><b>Default Value:</b> " + prop.getDefaultValue() + "</p>" +
                "<hr>" +
                "<p>" + description.replace("\n", "<br>") + "</p>" +
                "</body></html>";

        JOptionPane.showMessageDialog(this, message, "Property Information", JOptionPane.INFORMATION_MESSAGE);
    }

    private void performSave() {
        if ("node-default".equals(loadedProfileName))
            return;

        Properties propsToSave = getPropertiesFromUI();
        Path targetFile = ConfigurationUtils.resolveNodeProfilePath(loadedProfileName);

        try {
            ConfigurationUtils.savePropertiesPreservingFormat(targetFile, propsToSave, propertyComponents.keySet());
            // After saving, update savedProfile to reflect the new saved state
            savedProfile.setProperties(propsToSave);
            updateDirtyStatus();
            refreshUIColors();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error saving properties: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private String normalizeListValue(String value, String delimiter) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Arrays.stream(value.split(delimiter))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.joining(";"));
    }

    private void initHelpTexts() {
        // API
        helpTexts.put(Props.API_SERVER.getName(),
                "Enables the HTTP API server. Required for wallets and local tools to communicate with the node.");

        helpTexts.put(Props.API_PORT.getName(),
                "The TCP port number on which the API server listens for incoming HTTP/HTTPS requests."
                        + "<br>Ensure this port is not blocked by firewalls if you intend to access the node from other devices.");

        helpTexts.put(Props.API_LISTEN.getName(),
                "The interface IP or hostname to bind to."
                        + "<br><br><b>Examples:</b>"
                        + "<ul>"
                        + "<li>0.0.0.0: Listen on all IPv4 interfaces.</li>"
                        + "<li>::: Listen on all IPv6 interfaces.</li>"
                        + "<li>127.0.0.1: Listen on local IPv4 loopback only.</li>"
                        + "<li>::1: Listen on local IPv6 loopback only.</li>"
                        + "<li>localhost: Listen on local loopback (resolves to IP).</li>"
                        + "</ul>");

        helpTexts.put(Props.API_ALLOWED.getName(),
                "List of allowed IP addresses, hostnames, or subnets to access the API."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (;)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file."
                        + "<br><br><b>Examples:</b>"
                        + "<ul>"
                        + "<li>*: Allows all IP addresses. <b>Warning:</b> Use with caution on public nodes.</li>"
                        + "<li>127.0.0.1: A specific IPv4 address.</li>"
                        + "<li>localhost: The local machine.</li>"
                        + "<li>[::1]: The IPv6 loopback address.</li>"
                        + "<li>192.168.1.0/24: An IPv4 subnet in CIDR notation.</li>"
                        + "</ul>");

        helpTexts.put(Props.API_ADMIN_KEY_LIST.getName(),
                "List of passwords (API Keys) required to authorize administrative API operations."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (;)."
                        + "<br>These keys are required for the following sensitive actions:"
                        + "<ul>"
                        + "<li><b>Full Reset</b>: Resets the node and forces a resync.</li>"
                        + "<li><b>Backup Database</b>: Dumps the database to a file.</li>"
                        + "<li><b>Clear Unconfirmed Transactions</b>: Prunes unconfirmed transactions.</li>"
                        + "<li><b>Pop Off</b>: Removes recent blocks from the database.</li>"
                        + "<li><b>Get State (Extended)</b>: Retrieves extended blockchain info (with counts).</li>"
                        + "</ul>"
                        + "<b>Security Note:</b> If this field is left empty, administrative API functions are completely disabled.");

        helpTexts.put(Props.API_SSL.getName(),
                "Enables SSL (HTTPS) for the API server."
                        + "<br><br><b>To enable SSL, you have two options:</b>"
                        + "<br><br><b>1. Manual Keystore (e.g., JKS or PKCS12):</b>"
                        + "<ul>"
                        + "<li>Set this property to true.</li>"
                        + "<li>Set SSL KeyStore Path to the path of your keystore file.</li>"
                        + "<li>Set SSL KeyStore Password to the password for your keystore.</li>"
                        + "</ul>"
                        + "<b>2. Automatic Conversion from Let's Encrypt (PEM):</b>"
                        + "<ul>"
                        + "<li>Set this property to true.</li>"
                        + "<li>Set SSL LetsEncrypt Path to the directory containing your privkey.pem and fullchain.pem files (e.g., /etc/letsencrypt/live/your.domain.com).</li>"
                        + "<li>Set SSL KeyStore Path to the desired location for the auto-generated PKCS12 keystore file (e.g., conf/keystore.p12).</li>"
                        + "<li>Set SSL KeyStore Password to a password for the new keystore. The node will create and manage this file.</li>"
                        + "</ul>"
                        + "After enabling, the API will be accessible via https:// on the configured API port.");

        helpTexts.put(Props.API_DOC_MODE.getName(),
                "Controls the built-in API documentation."
                        + "<br><br><b>Available Modes:</b>"
                        + "<ul>"
                        + "<li>modern: Enables the new, interactive Swagger UI documentation. Recommended for better experience.</li>"
                        + "<li>legacy: Enables the old, servlet-based documentation. Use this if you have compatibility issues with the modern UI.</li>"
                        + "<li>off: Disables the API documentation completely.</li>"
                        + "</ul>");

        helpTexts.put(Props.API_UI_DIR.getName(),
                "Specifies the local directory containing the static web files (HTML, JS, CSS) for the node's user interface (e.g., Phoenix Wallet)."
                        + "<br>These files are served at the root URL (e.g. http://localhost:8125/)."
                        + "<br>If left empty, no UI will be served from the root path.");

        helpTexts.put(Props.API_WEBSOCKET_ENABLE.getName(),
                "Enables the WebSocket server for real-time event notifications."
                        + "<br><br><b>Why enable it?</b>"
                        + "<br>WebSockets allow applications (wallets, explorers) to receive immediate updates without inefficient polling."
                        + "<br><br><b>If Enabled:</b>"
                        + "<br>The node pushes events (e.g., new blocks, transactions, balance changes) to connected clients instantly."
                        + "<br><br><b>If Disabled:</b>"
                        + "<br>Applications must repeatedly request data (poll) to detect changes, resulting in higher latency and load.");

        helpTexts.put(Props.API_WEBSOCKET_PORT.getName(),
                "The TCP port dedicated to the WebSocket server."
                        + "<br>This port is separate from the main HTTP API port and is used for real-time event streams."
                        + "<br><br><b>Details:</b>"
                        + "<ul>"
                        + "<li><b>Function:</b> Clients connect here to receive push notifications (blocks, transactions).</li>"
                        + "<li><b>Single Port:</b> Only one port can be configured.</li>"
                        + "<li><b>Firewall:</b> Must be open/forwarded for external access.</li>"
                        + "</ul>");

        helpTexts.put(Props.API_WEBSOCKET_HEARTBEAT_INTERVAL.getName(),
                "The interval in seconds for WebSocket heartbeat messages."
                        + "<br>This setting controls how often the server sends a 'ping' or heartbeat message to connected clients."
                        + "<br><br><b>Details:</b>"
                        + "<ul>"
                        + "<li><b>Unit:</b> Seconds.</li>"
                        + "<li><b>Purpose:</b> Keeps the connection alive through proxies, load balancers, and firewalls that might drop idle connections.</li>"
                        + "<li><b>Role:</b> Ensures both server and client know the connection is still active.</li>"
                        + "</ul>");

        helpTexts.put(Props.API_ALLOWED_ORIGINS.getName(),
                "Configures Cross-Origin Resource Sharing (CORS) allowed origins."
                        + "<br>This setting determines which websites are allowed to access the node's API from a browser."
                        + "<br><br><b>Details:</b>"
                        + "<ul>"
                        + "<li><b>Purpose:</b> Allows web applications (like wallets) hosted on other domains to interact with this node.</li>"
                        + "<li><b>Role:</b> Acts as a security filter for browser-based API requests.</li>"
                        + "</ul>"
                        + "<b>Configuration Options:</b>"
                        + "<ul>"
                        + "<li>*: <b>Wildcard</b>. Allows access from <b>any</b> website. Recommended for public nodes.</li>"
                        + "<li><b>Specific Origin</b>: e.g., https://wallet.signum.network. Restricts access to a specific domain.</li>"
                        + "<li><b>List</b>: Multiple origins can be specified. In this field, you can list entries on new lines, or on a single line separated by semicolons (;).</li>"
                        + "</ul>"
                        + "The configuration is stored as a single semicolon-separated list in the node.properties file.");

        helpTexts.put(Props.API_ACCEPT_SURPLUS_PARAMS.getName(),
                "Controls how the API server handles requests containing unexpected or surplus parameters."
                        + "<br><br><b>If false (default and recommended):</b>"
                        + "<br>The server strictly validates all parameters. If a request includes a parameter not defined for that API call, the request is rejected with an 'incorrect parameter' error. This is the most secure setting."
                        + "<br><br><b>If true:</b>"
                        + "<br>The server will ignore any unknown parameters and process the request using only the recognized ones."
                        + "<br>This may be required for compatibility with older or poorly-written clients that send extra data."
                        + "<br><br><b>Security Note:</b> It is recommended to keep this disabled (false) unless explicitly needed.");

        helpTexts.put(Props.API_SERVER_ENFORCE_POST.getName(),
                "Controls whether the API server enforces the use of HTTP POST method for sensitive or state-changing requests."
                        + "<br><br><b>Why is this important?</b>"
                        + "<ul>"
                        + "<li><b>Security:</b> GET requests include parameters in the URL, which are often logged in server/proxy logs and browser history. This risks exposing sensitive data (like secret phrases). POST requests send data in the request body, which is not logged by default.</li>"
                        + "<li><b>Best Practice:</b> HTTP standards dictate that GET should be used for retrieving data, while POST should be used for actions that modify state (e.g., sending transactions).</li>"
                        + "</ul>"
                        + "<b>Configuration:</b>"
                        + "<ul>"
                        + "<li>true (Recommended): Enforces POST for state-changing API calls. GET requests for these calls will be rejected.</li>"
                        + "<li>false: Allows both GET and POST. <b>Warning:</b> Less secure. Use only for testing or legacy compatibility.</li>"
                        + "</ul>");

        helpTexts.put(Props.API_SERVER_IDLE_TIMEOUT.getName(),
                "The maximum time in milliseconds that an HTTP API connection can remain idle before the server closes it."
                        + "<br><br><b>Details:</b>"
                        + "<ul>"
                        + "<li><b>Unit:</b> Milliseconds.</li>"
                        + "<li><b>Purpose:</b> Frees up server resources by closing inactive connections.</li>"
                        + "<li><b>Impact:</b> Higher values allow for slower clients or long-polling, but consume more resources. Lower values save resources but might disconnect slow clients.</li>"
                        + "</ul>");

        helpTexts.put(Props.API_SSL_KEY_STORE_PATH.getName(),
                "The path to your SSL keystore file (e.g., a .jks or .p12 file)."
                        + "<br>Required if SSL is enabled.");

        helpTexts.put(Props.API_SSL_KEY_STORE_PASSWORD.getName(),
                "The password for your SSL keystore file."
                        + "<br>Required if SSL is enabled.");

        helpTexts.put(Props.API_SSL_LETSENCRYPT_PATH.getName(),
                "The path to your Let's Encrypt live directory (e.g., /etc/letsencrypt/live/your.domain.com)."
                        + "<br>If set, the node will automatically convert the PEM files to a PKCS12 keystore.");

        // Database
        helpTexts.put(Props.DB_URL.getName(),
                "The JDBC connection URL. Examples:"
                        + "<br>SQLite: jdbc:sqlite:file:../database/SQLite/sqlite/signum.sqlite.db"
                        + "<br>MariaDB: jdbc:mariadb://localhost:3306/signum"
                        + "<br>Postgres: jdbc:postgresql://localhost:5432/signum");

        helpTexts.put(Props.DB_USERNAME.getName(),
                "The username for the database connection. Required for MariaDB and PostgreSQL.");

        helpTexts.put(Props.DB_PASSWORD.getName(),
                "The password for the database connection. Required for MariaDB and PostgreSQL.");

        helpTexts.put(Props.DB_SKIP_CHECK.getName(),
                "If enabled, skips the database integrity check on startup."
                        + "<br><b>Warning:</b> This can speed up startup but is risky. Use only if you are sure the database is consistent.");

        helpTexts.put(Props.DB_CONNECTION_RETRY_ENABLED.getName(),
                "When the database is unavailable at startup, retry the connection (with a delay) instead of failing immediately."
                        + "<br>Useful for portable/embedded database setups where the DB engine may still be coming up.");

        helpTexts.put(Props.DB_CONNECTION_RETRY_MAX_ATTEMPTS.getName(),
                "The maximum number of database connection attempts during startup (only used when connection retry is enabled).");

        helpTexts.put(Props.DB_CONNECTION_RETRY_INTERVAL_MS.getName(),
                "The delay between database connection attempts at startup, in milliseconds (only used when connection retry is enabled).");

        helpTexts.put(Props.DB_INSERT_BATCH_MAX_SIZE.getName(),
                "The maximum number of rows to insert in a single database batch operation."
                        + "<br>A larger batch size can improve performance during sync but may use more memory.");

        helpTexts.put(Props.DB_CONNECTIONS.getName(),
                "The maximum number of simultaneous connections in the database connection pool.");

        helpTexts.put(Props.DB_ARCHIVAL_MODE.getName(),
                "Sets the database maintenance and history mode:<br/>"
                        + "<ul>"
                        + "<li><b>ARCHIVE:</b> Full database, no trimming or pruning. Keeps every piece of history and derived data. Uses the most disk space.</li>"
                        + "<li><b>TRIM:</b> (Default) Periodically prunes derived tables (like account balances history) to save space, but <b>keeps all blocks and transactions</b>. The node remains archival.</li>"
                        + "<li><b>PRUNE:</b> Trims derived tables AND physically deletes old blocks and transactions beyond the safe rollback limit ("
                        + Constants.MAX_ROLLBACK
                        + " blocks). ⚠️ The node becomes non-archival and cannot serve old history to peers.</li>"
                        + "</ul>");

        helpTexts.put(Props.DB_OPTIMIZE.getName(),
                "If enabled, the node performs database optimization (e.g., VACUUM for SQLite) during startup or shutdown."
                        + "<br>This helps reduce file size and improve performance but may increase startup/shutdown time.");

        helpTexts.put(Props.DB_SQLITE_JOURNAL_MODE.getName(),
                "SQLite journaling mode. WAL (Write-Ahead Logging) is recommended for performance and concurrency.");

        helpTexts.put(Props.DB_SQLITE_SYNCHRONOUS.getName(),
                "Controls the SQLite synchronization mode."
                        + "<ul>"
                        + "<li><b>NORMAL</b>: Good balance between safety and performance.</li>"
                        + "<li><b>FULL</b>: Safest but slower.</li>"
                        + "<li><b>OFF</b>: Fastest but risky (data corruption on power loss).</li>"
                        + "</ul>");

        helpTexts.put(Props.DB_SQLITE_CACHE_SIZE.getName(),
                "Memory allocated for SQLite cache."
                        + "<br><br><b>Positive Value (N):</b> Sets the number of pages."
                        + "<br>Total Cache Size = N * Page Size (default 4096 bytes)."
                        + "<br><i>Example:</i> 32768 pages * 4KB = ~128 MB."
                        + "<br><br><b>Negative Value (-N):</b> Sets the memory usage in KiB."
                        + "<br>Total Cache Size = abs(N) * 1024 bytes."
                        + "<br><i>Example:</i> -131072 (KiB) = 128 MB."
                        + "<br><br><b>Recommendation:</b> Use negative values for a definitive RAM limit.");

        helpTexts.put(Props.NODE_BLOCK_CACHE_MB.getName(),
                "The size of the in-memory cache for blocks in Megabytes."
                        + "<br>Speeds up block retrieval during syncing and API requests.");

        // P2P
        helpTexts.put(Props.P2P_PORT.getName(),
                "The TCP port used for peer-to-peer communication."
                        + "<br>This port must be open and forwarded to allow other peers to connect to your node.");

        helpTexts.put(Props.P2P_LISTEN.getName(),
                "The interface IP or hostname for P2P communication."
                        + "<br><br><b>Examples:</b>"
                        + "<ul>"
                        + "<li>0.0.0.0: Listen on all IPv4 interfaces.</li>"
                        + "<li>::: Listen on all IPv6 interfaces.</li>"
                        + "<li>127.0.0.1: Listen on local IPv4 loopback only.</li>"
                        + "<li>::1: Listen on local IPv6 loopback only.</li>"
                        + "<li>localhost: Listen on local loopback (resolves to IP).</li>"
                        + "</ul>");

        helpTexts.put(Props.P2P_UPNP.getName(),
                "Attempts to automatically configure port forwarding on your router using UPnP (Universal Plug and Play)."
                        + "<br>Recommended for home users behind a NAT router to make the node reachable from the internet."
                        + "<br>Should be disabled on servers or when port forwarding is configured manually.");

        helpTexts.put(Props.P2P_MY_PLATFORM.getName(),
                "A string identifying your node's platform to peers."
                        + "<br>Useful for network statistics."
                        + "<br>You can enter your Signum address here to be eligible for SNR (Signum Network Reward) awards.");

        helpTexts.put(Props.P2P_MY_ADDRESS.getName(),
                "The externally visible IP address or hostname of this node."
                        + "<br>This is the address that will be announced to other peers.");

        helpTexts.put(Props.P2P_SHARE_MY_ADDRESS.getName(),
                "Whether to announce this node's address to other peers."
                        + "<br>If disabled, your node will not be discoverable by others.");

        helpTexts.put(Props.P2P_BOOTSTRAP_PEERS.getName(),
                "A list of initial peers to connect to when the node starts."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (;)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file."
                        + "<br>This helps the node to quickly find other peers and join the network.");

        helpTexts.put(Props.P2P_REBROADCAST_TO.getName(),
                "A list of peers to which this node will always rebroadcast transactions."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (;)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file."
                        + "<br>Useful for ensuring transactions reach specific nodes (e.g., pools or exchanges).");

        helpTexts.put(Props.P2P_NUM_BOOTSTRAP_CONNECTIONS.getName(),
                "The number of bootstrap peers to connect to when the node starts."
                        + "<br>Increasing this may help with initial connectivity but increases startup load.");

        helpTexts.put(Props.P2P_BLACKLISTED_PEERS.getName(),
                "A list of peer addresses that are permanently banned from connecting to your node."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (;)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file.");

        helpTexts.put(Props.P2P_MAX_CONNECTIONS.getName(),
                "The maximum number of active peer connections the node will maintain."
                        + "<br>Higher values allow more connectivity but consume more resources.");

        helpTexts.put(Props.P2P_MAX_BLOCKS.getName(),
                "The maximum number of blocks to send to a peer in a single request."
                        + "<br>Controls the bandwidth usage for block synchronization.");

        helpTexts.put(Props.P2P_TIMEOUT_CONNECT_MS.getName(),
                "The timeout in milliseconds for establishing a connection to a peer.");

        helpTexts.put(Props.P2P_TIMEOUT_READ_MS.getName(),
                "The timeout in milliseconds for reading data from a peer.");

        helpTexts.put(Props.P2P_TIMEOUT_IDLE_MS.getName(),
                "The timeout in milliseconds after which an idle peer connection is closed.");

        helpTexts.put(Props.P2P_BLACKLISTING_TIME_MS.getName(),
                "The duration in milliseconds for which a peer is blacklisted after misbehavior.");

        helpTexts.put(Props.P2P_ENABLE_TX_REBROADCAST.getName(),
                "Enables the rebroadcasting of new transactions to other peers."
                        + "<br>This helps propagate transactions across the network.");

        helpTexts.put(Props.P2P_USE_PEERS_DB.getName(),
                "Whether to use the database to store and retrieve known peers."
                        + "<br>If disabled, the node will only use bootstrap peers.");

        helpTexts.put(Props.P2P_SAVE_PEERS.getName(),
                "Whether to save discovered peers to the database for future use.");

        helpTexts.put(Props.P2P_GET_MORE_PEERS.getName(),
                "Whether to request lists of known peers from connected peers."
                        + "<br>This helps discover new nodes in the network.");

        helpTexts.put(Props.P2P_GET_MORE_PEERS_THRESHOLD.getName(),
                "The threshold of known peers below which the node will actively request more peers from others.");

        helpTexts.put(Props.P2P_SEND_TO_LIMIT.getName(),
                "The maximum number of peers to which a single transaction will be broadcasted.");

        helpTexts.put(Props.P2P_MAX_UNCONFIRMED_TRANSACTIONS.getName(),
                "The maximum number of unconfirmed transactions to keep in memory."
                        + "<br>Prevents memory exhaustion during high network activity.");

        helpTexts.put(Props.P2P_MAX_PERCENTAGE_UNCONFIRMED_TRANSACTIONS_FULL_HASH_REFERENCE.getName(),
                "The maximum percentage of unconfirmed transactions in memory that can reference another unconfirmed transaction by its full hash."
                        + "<br>This is a memory management setting to prevent complex chains of unconfirmed transactions from consuming too much memory.");

        helpTexts.put(Props.P2P_MAX_UNCONFIRMED_TRANSACTIONS_RAW_SIZE_BYTES_TO_SEND.getName(),
                "The maximum total size (in bytes) of raw unconfirmed transaction data to send to a peer in a single batch."
                        + "<br>This limit helps to prevent network flooding and manage bandwidth usage.");

        // Mining
        helpTexts.put(Props.GPU_ACCELERATION.getName(),
                "Enables GPU acceleration for mining verification."
                        + "<br>This can significantly improve performance when verifying nonces.");

        helpTexts.put(Props.GPU_AUTODETECT.getName(),
                "Automatically detects available GPU devices for acceleration."
                        + "<br>If disabled, you must manually specify the platform and device indices.");

        helpTexts.put(Props.GPU_PLATFORM_IDX.getName(),
                "The index of the OpenCL platform to use for GPU acceleration."
                        + "<br>Only used if auto-detection is disabled.");

        helpTexts.put(Props.GPU_DEVICE_IDX.getName(),
                "The index of the OpenCL device to use for GPU acceleration."
                        + "<br>Only used if auto-detection is disabled.");

        helpTexts.put(Props.GPU_MEM_PERCENT.getName(),
                "The percentage of GPU memory to allocate for mining verification.");

        helpTexts.put(Props.GPU_UNVERIFIED_QUEUE.getName(),
                "The size of the queue for unverified transactions/blocks waiting for GPU processing.");

        helpTexts.put(Props.GPU_DYNAMIC_HASHES_PER_BATCH.getName(),
                "Dynamically adjusts the number of hashes processed per GPU batch based on load.");

        helpTexts.put(Props.GPU_HASHES_PER_BATCH.getName(),
                "The fixed number of hashes to process in a single GPU batch."
                        + "<br>Only used if dynamic adjustment is disabled.");

        helpTexts.put(Props.SOLO_MINING_PASSPHRASES.getName(),
                "A list of secret phrases for accounts that are solo mining on this node."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (;)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file."
                        + "<br>This allows miners to use the 'submitNonce' API without sending their secret phrase over the network."
                        + "<br><b>Security Warning:</b> Do not use on public-facing nodes or nodes accessible by others, as it stores secret phrases in the configuration file.");

        helpTexts.put(Props.REWARD_RECIPIENT_PASSPHRASES.getName(),
                "A list of passphrases for reward recipient accounts, used in pool mining."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (;)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file."
                        + "<br>Format: miner_account_id:reward_recipient_secret_phrase"
                        + "<br>This allows the node to automatically claim mining rewards on behalf of the pool miners.");

        helpTexts.put(Props.ALLOW_OTHER_SOLO_MINERS.getName(),
                "Allows other accounts (not listed in Solo Mining Passphrases) to solo mine using this node."
                        + "<br>If enabled, anyone can submit nonces to this node.");

        // System
        helpTexts.put(Props.APPLICATION.getName(),
                "The name of the application (e.g. BRS). Used for peer identification.");
        helpTexts.put(Props.VERSION.getName(),
                "The version of the node software. Used for peer identification and protocol compatibility.");

        helpTexts.put(Props.NODE_AUTOSTART.getName(),
                "When enabled, this node profile starts automatically when the application launches."
                        + "<br>Read by the boot autostart arbitration (NodeModule) at startup.");

        helpTexts.put(Props.NETWORK_NAME.getName(),
                "The name of the network this node is connected to (e.g., Signum, Testnet).");

        helpTexts.put(Props.CPU_NUM_CORES.getName(),
                "The number of CPU cores to use for processing."
                        + "<br>Set to 0 or negative to use all available cores.");

        helpTexts.put(Props.BLOCK_PROCESS_THREAD_DELAY.getName(),
                "The delay in milliseconds between block processing threads."
                        + "<br>Can be adjusted to manage CPU usage.");

        helpTexts.put(Props.MAX_INDIRECTS_PER_BLOCK.getName(),
                "The maximum number of indirect payments (e.g., from multi-out transactions) allowed per block.");

        helpTexts.put(Props.EXPERIMENTAL.getName(),
                "Enables experimental features that are not yet stable."
                        + "<br>Use with caution."
                        + "<br><br><b>Enabled Features:</b>"
                        + "<ul>"
                        + "<li><b>Time Tracking:</b> Displays <b>Total Time</b> (time since node start) and <b>Sync In Progress</b> (time spent actively syncing blocks) in the footer.</li>"
                        + "</ul>");

        helpTexts.put(Props.MEASUREMENT_ACTIVE.getName(),
                "Enables performance measurement and logging to CSV files in the 'measurement' directory.");

        helpTexts.put(Props.MEASUREMENT_DIR.getName(),
                "The directory where measurement logs are stored.");

        helpTexts.put(Props.SETTINGS_DIR.getName(),
                "The directory where application settings are stored.");

        helpTexts.put(Props.ICON_LOCATION.getName(),
                "The path to the application icon file.");

        helpTexts.put(Props.AUTO_POP_OFF_ENABLED.getName(),
                "Enables automatic block pop-off when a fork is detected."
                        + "<br>Helps the node stay on the correct chain.");

        helpTexts.put(Props.INITIAL_POP_OFF_ENABLED.getName(),
                "If enabled, the node will pop off the last block on every startup as a safety measure against "
                        + "partially committed data from an unclean shutdown. "
                        + "Disabling this saves 1 block of re-sync but is not recommended for production nodes.");

        helpTexts.put(Props.POP_OFF_SKIP_DB_CHECK.getName(),
                "If enabled, skips the database consistency check after each block is removed during a manual pop-off operation."
                        + "<br><b>Enabling this significantly speeds up large pop-offs</b> because the node won't re-calculate the entire database state for every single block removed."
                        + "<br><br><b>Warning:</b> This risks leaving the database in an inconsistent state if an error occurs during the process.");

        helpTexts.put(Props.AUTO_CONSISTENCY_RESOLVE_ENABLED.getName(),
                "Enables automatic resolution of database inconsistencies at startup.");

        helpTexts.put(Props.INDIRECT_INCOMING_SERVICE_ENABLE.getName(),
                "Enables the service to track indirect incoming payments (e.g., from multi-out transactions).");

        helpTexts.put(Props.NODE_AT_PROCESSOR_CACHE_BLOCK_COUNT.getName(),
                "The number of blocks to cache for the Automated Transaction (AT) processor."
                        + "<br>A larger cache can improve AT execution performance but uses more memory.");

        helpTexts.put(Props.NODE_SHUTDOWN_TIMEOUT.getName(),
                "The maximum time in seconds to wait for a graceful shutdown before forcing exit.");

        helpTexts.put(Props.NODE_CHECKPOINT_HEIGHT.getName(),
                "The block height of a known valid checkpoint. Used to verify chain integrity during sync."
                        + "<br>Set to -1 to disable checkpoint verification and check the entire chain from genesis.");

        helpTexts.put(Props.NODE_CHECKPOINT_HASH.getName(),
                "The hash of the checkpoint block.");

        helpTexts.put(Props.NODE_PK_CHECKS.getName(),
                "<b>Public Key Checks (Account Freeze List)</b>"
                        + "<br>This setting allows freezing specific accounts by preventing their public keys from being verified or used."
                        + "<br>This is a security measure used in conjunction with the PK_FREEZE network constant."
                        + "<br><br><b>Format:</b>"
                        + "<br>The list contains 16-character hexadecimal strings. Each string represents an Account ID encoded in Little Endian byte order."
                        + "<br><br><b>How to use:</b>"
                        + "<br>Use the <b>Magic Wand</b> icon next to this field to open the conversion tool. You can enter Account IDs or RS Addresses, and the tool will generate the correct hex code for this list."
                        + "<br><br><b>Effect:</b>"
                        + "<br>If an account ID is in this list and PK_FREEZE is active, the account cannot send transactions.");

        helpTexts.put(Props.ENABLE_AT_DEBUG_LOG.getName(),
                "Enables debug logging for Automated Transactions (ATs).");

        helpTexts.put(Props.CASH_BACK_ID.getName(),
                "The Account ID that receives a cashback for a percentage of the fees from transactions created by this node."
                        + "<br>The percentage is defined by 'Cash Back Factor'.");

        helpTexts.put(Props.CASH_BACK_FACTOR.getName(),
                "The percentage of transaction fees to return as cashback to the Cash Back ID.");

        helpTexts.put(Props.ALIAS_RENEWAL_FREQUENCY.getName(),
                "The frequency at which aliases should be renewed.");

        // Dev
        helpTexts.put(Props.DEV_OFFLINE.getName(),
                "Runs the node in offline mode, disabling P2P networking.");

        helpTexts.put(Props.DEV_TIMEWARP.getName(),
                "Enables time warping for testing purposes."
                        + "<br>Allows simulating future time.");

        helpTexts.put(Props.DEV_MOCK_MINING.getName(),
                "Enables mock mining for testing purposes."
                        + "<br>Simulates mining without actual PoC verification.");

        helpTexts.put(Props.DEV_MOCK_MINING_DEADLINE.getName(),
                "The deadline value to use for mock mining.");

        helpTexts.put(Props.NODE_TEST_UNCONFIRMED_TRANSACTIONS.getName(),
                "Developer setting to test unconfirmed transaction handling."
                        + "<br>Should not be enabled for normal operation.");

        helpTexts.put(Props.DEV_DUMP_PEERS_VERSION.getName(),
                "Dumps the versions of connected peers to the log on exit.");

        helpTexts.put(Props.NODE_DEBUG_TRACE_ENABLED.getName(),
                "Enables debug tracing for detailed logging.");

        helpTexts.put(Props.NODE_DEBUG_TRACE_QUOTE.getName(),
                "The quote character used in debug trace logs.");

        helpTexts.put(Props.NODE_DEBUG_TRACE_SEPARATOR.getName(),
                "The separator character used in debug trace logs.");

        helpTexts.put(Props.NODE_DEBUG_LOG_CONFIRMED.getName(),
                "Logs confirmed transactions for debugging.");

        helpTexts.put(Props.NODE_DEBUG_TRACE_ACCOUNTS.getName(),
                "A list of account IDs to trace in debug logs."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (;)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file.");

        helpTexts.put(Props.NODE_DEBUG_TRACE_LOG.getName(),
                "The file path for the debug trace log.");

        helpTexts.put(Props.NODE_COMMUNICATION_LOGGING_MASK.getName(),
                "A bitmask controlling which P2P communication events are logged.");

        // Jetty
        helpTexts.put(Props.JETTY_API_GZIP_FILTER.getName(),
                "Enables GZIP compression for API server responses to reduce bandwidth usage.");

        helpTexts.put(Props.JETTY_API_GZIP_FILTER_MIN_GZIP_SIZE.getName(),
                "The minimum response size (in bytes) to be eligible for GZIP compression on the API server.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER.getName(),
                "Enables the Denial of Service (DoS) filter for the API server.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_MAX_REQUEST_PER_SEC.getName(),
                "DoS Filter: Maximum number of requests allowed from a single IP per second.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_THROTTLED_REQUESTS.getName(),
                "DoS Filter: Number of requests to throttle (queue) before rejecting.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_DELAY_MS.getName(),
                "DoS Filter: Delay in milliseconds applied to throttled requests.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_MAX_WAIT_MS.getName(),
                "DoS Filter: Maximum time in milliseconds a request will wait in the throttle queue.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_MAX_REQUEST_MS.getName(),
                "DoS Filter: Maximum time in milliseconds to process a request.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_THROTTLE_MS.getName(),
                "DoS Filter: Time in milliseconds to throttle a connection after it exceeds the rate limit.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_MAX_IDLE_TRACKER_MS.getName(),
                "DoS Filter: Maximum time in milliseconds to track an idle connection.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_TRACK_SESSIONS.getName(),
                "DoS Filter: Whether to track requests by session ID instead of IP address.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_INSERT_HEADERS.getName(),
                "DoS Filter: Whether to insert headers indicating the filter status.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_REMOTE_PORT.getName(),
                "DoS Filter: Whether to also track requests by remote port.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_IP_WHITELIST.getName(),
                "DoS Filter: A list of IPs that are exempt from rate limiting."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (;)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_MANAGED_ATTR.getName(),
                "DoS Filter: Whether the filter is managed by a container attribute.");

        helpTexts.put(Props.JETTY_P2P_GZIP_FILTER.getName(),
                "Enables GZIP compression for P2P server responses to reduce bandwidth usage.");

        helpTexts.put(Props.JETTY_P2P_GZIP_FILTER_MIN_GZIP_SIZE.getName(),
                "The minimum response size (in bytes) to be eligible for GZIP compression on the P2P server.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER.getName(),
                "Enables the Denial of Service (DoS) filter for the P2P server.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_MAX_REQUESTS_PER_SEC.getName(),
                "DoS Filter: Maximum number of requests allowed from a single IP per second.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_THROTTLED_REQUESTS.getName(),
                "DoS Filter: Number of requests to throttle (queue) before rejecting.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_DELAY_MS.getName(),
                "DoS Filter: Delay in milliseconds applied to throttled requests.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_MAX_WAIT_MS.getName(),
                "DoS Filter: Maximum time in milliseconds a request will wait in the throttle queue.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_MAX_REQUEST_MS.getName(),
                "DoS Filter: Maximum time in milliseconds to process a request.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_THROTTLE_MS.getName(),
                "DoS Filter: Time in milliseconds to throttle a connection after it exceeds the rate limit.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_MAX_IDLE_TRACKER_MS.getName(),
                "DoS Filter: Maximum time in milliseconds to track an idle connection.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_TRACK_SESSIONS.getName(),
                "DoS Filter: Whether to track requests by session ID instead of IP address.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_INSERT_HEADERS.getName(),
                "DoS Filter: Whether to insert headers indicating the filter status.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_REMOTE_PORT.getName(),
                "DoS Filter: Whether to also track requests by remote port.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_IP_WHITELIST.getName(),
                "DoS Filter: A list of IPs that are exempt from rate limiting."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (;)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_MANAGED_ATTR.getName(),
                "DoS Filter: Whether the filter is managed by a container attribute.");

        // Network Constants
        String networkWarning = "<br><br><b>Warning:</b> These are network-wide constants. Changing them will cause your node to be on a different network (fork) and is not recommended unless you are creating a custom network.";
        helpTexts.put(Props.BLOCK_TIME.getName(),
                "The target time in seconds between blocks." + networkWarning);

        helpTexts.put(Props.DECIMAL_PLACES.getName(),
                "The number of decimal places for the native currency." + networkWarning);

        helpTexts.put(Props.ONE_COIN_NQT.getName(),
                "The value of one coin in the smallest unit (NQT)." + networkWarning);

        helpTexts.put(Props.GENESIS_BLOCK_ID.getName(),
                "The unique ID of the first block (genesis block) of the blockchain." + networkWarning);

        helpTexts.put(Props.GENESIS_TIMESTAMP.getName(),
                "The epoch timestamp of the genesis block." + networkWarning);

        helpTexts.put(Props.ADDRESS_PREFIX.getName(),
                "The prefix used for addresses on this network (e.g., 'S' for Signum)." + networkWarning);

        helpTexts.put(Props.VALUE_SUFIX.getName(),
                "The suffix for the native currency (e.g., 'SIGNA')." + networkWarning);

        helpTexts.put(Props.BLOCK_REWARD_START.getName(),
                "The initial block reward amount in NQT." + networkWarning);

        helpTexts.put(Props.BLOCK_REWARD_CYCLE.getName(),
                "The number of blocks in a reward reduction cycle." + networkWarning);

        helpTexts.put(Props.BLOCK_REWARD_CYCLE_PERCENTAGE.getName(),
                "The percentage by which the block reward is reduced each cycle." + networkWarning);

        helpTexts.put(Props.BLOCK_REWARD_LIMIT_HEIGHT.getName(),
                "The block height at which the block reward reduction stops." + networkWarning);

        helpTexts.put(Props.BLOCK_REWARD_LIMIT_AMOUNT.getName(),
                "The minimum block reward amount after all reductions." + networkWarning);

        helpTexts.put(Props.NETWORK_PARAMETERS.getName(),
                "The Java class that defines all network parameters." + networkWarning);

        helpTexts.put(Props.REWARD_RECIPIENT_ENABLE_BLOCK_HEIGHT.getName(),
                "The block height at which reward recipient assignment becomes active." + networkWarning);

        helpTexts.put(Props.DIGITAL_GOODS_STORE_BLOCK_HEIGHT.getName(),
                "The block height at which the Digital Goods Store becomes active." + networkWarning);

        helpTexts.put(Props.AUTOMATED_TRANSACTION_BLOCK_HEIGHT.getName(),
                "The block height at which Automated Transactions (ATs) become active." + networkWarning);

        helpTexts.put(Props.AT_FIX_BLOCK_2_BLOCK_HEIGHT.getName(),
                "The block height for the 2nd AT fix." + networkWarning);

        helpTexts.put(Props.AT_FIX_BLOCK_3_BLOCK_HEIGHT.getName(),
                "The block height for the 3rd AT fix." + networkWarning);

        helpTexts.put(Props.AT_FIX_BLOCK_4_BLOCK_HEIGHT.getName(),
                "The block height for the 4th AT fix." + networkWarning);

        helpTexts.put(Props.AT_FIX_BLOCK_5_BLOCK_HEIGHT.getName(),
                "The block height for the 5th AT fix." + networkWarning);

        helpTexts.put(Props.PRE_POC2_BLOCK_HEIGHT.getName(),
                "The block height for the pre-PoC2 fork." + networkWarning);

        helpTexts.put(Props.POC2_BLOCK_HEIGHT.getName(),
                "The block height at which PoC2 becomes active." + networkWarning);

        helpTexts.put(Props.SODIUM_BLOCK_HEIGHT.getName(),
                "The block height for the Sodium hard fork." + networkWarning);

        helpTexts.put(Props.SIGNUM_HEIGHT.getName(),
                "The block height for the Signum hard fork." + networkWarning);

        helpTexts.put(Props.POC_PLUS_HEIGHT.getName(),
                "The block height at which PoC+ (Proof of Commitment) becomes active." + networkWarning);

        helpTexts.put(Props.SPEEDWAY_HEIGHT.getName(),
                "The block height for the Speedway hard fork." + networkWarning);

        helpTexts.put(Props.SMART_TOKEN_HEIGHT.getName(),
                "The block height at which Smart Tokens become active." + networkWarning);

        helpTexts.put(Props.SMART_FEES_HEIGHT.getName(),
                "The block height at which Smart Fees become active." + networkWarning);

        helpTexts.put(Props.SMART_ATS_HEIGHT.getName(),
                "The block height for the Smart ATs hard fork." + networkWarning);

        helpTexts.put(Props.DISTRIBUTION_FIX_BLOCK_HEIGHT.getName(),
                "The block height for the asset distribution fix." + networkWarning);

        helpTexts.put(Props.PK_BLOCK_HEIGHT.getName(),
                "The block height for the first Public Key announcement enforcement." + networkWarning);

        helpTexts.put(Props.PK2_BLOCK_HEIGHT.getName(),
                "The block height for the second Public Key announcement enforcement." + networkWarning);

        helpTexts.put(Props.PK_BLOCKS_PAST.getName(),
                "The number of blocks in the past to check for public key announcements." + networkWarning);

        helpTexts.put(Props.PK_API_BLOCK.getName(),
                "The block height at which the API enforces public key announcements." + networkWarning);

        helpTexts.put(Props.SMART_ALIASES_HEIGHT.getName(),
                "The block height at which Smart Aliases become active." + networkWarning);

        helpTexts.put(Props.DEV_NEXT_FORK_BLOCK_HEIGHT.getName(),
                "The block height for the next development fork." + networkWarning);
    }

    static class PropertyRow {
        final Prop<?> prop;
        final String labelText;
        /** The category panel this row's parts live in (they move to the flat
            search-results list while a search is active and come back on clear). */
        final JPanel originalParent;
        /** Index of the category tab this row belongs to (-1 when untracked). */
        final int tabIndex;

        JLabel label;
        String labelConstraints;

        JComponent input;
        String inputConstraints;

        JComponent extra; // e.g. the "Show Password" checkbox of a password row
        String extraConstraints;

        JButton help;
        String helpConstraints;

        JSeparator separator;
        String separatorConstraints;

        // Child indices inside originalParent (saved by addPropertyRow) so the
        // row can be re-inserted contiguously at its original position.
        int labelIndex = -1;
        int inputIndex = -1;
        int extraIndex = -1;
        int helpIndex = -1;
        int separatorIndex = -1;

        PropertyRow(Prop<?> prop, String labelText, JPanel originalParent, int tabIndex) {
            this.prop = prop;
            this.labelText = labelText;
            this.originalParent = originalParent;
            this.tabIndex = tabIndex;
        }
    }
}
