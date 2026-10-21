package application.module.node.gui.configuration;
import application.utils.config.ModuleIds;

import application.module.logging.LoggingAssignmentStore;
import application.module.node.NodeModule;
import application.module.node.Signum;
import application.module.node.crypto.Crypto;
import application.module.node.gui.NodePanel;
import application.module.node.profile.EffectiveNodeProps;
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
import application.utils.gui.ComboBoxItemToolTips;
import application.utils.gui.ComboSearchHighlightRenderer;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.DialogUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.ProfileCopyDialog;
import application.utils.gui.GuiUtils;
import application.utils.gui.HelpButton;
import application.utils.gui.HelpDialog;
import application.utils.gui.NewProfileChoiceDialog;
import application.utils.gui.FlatPlusButton;
import application.utils.gui.FlatIconButton;
import application.utils.gui.PlusGlyphIcon;
import application.utils.gui.ResponsiveToolbarScrollPane;
import application.utils.gui.SearchMatchLabel;
import application.utils.gui.SearchMatchPanel;
import application.utils.gui.SearchValueHighlight;
import application.utils.io.PathUtils;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionAdapter;
import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
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
     * The editable, state-aware select menus of the value rows, keyed by the
     * combo itself: every regular input row is an EDITABLE select whose editor
     * text is the row's current value and whose dropdown model is rebuilt on
     * every state change ({@link #rebuildStateAwareComboModel}) with the
     * current value plus exactly the baseline values the state keeps
     * selectable ({@link ProfileValueState#dropdownBaselines}). The mapped
     * value carries the row's property and the fixed option catalog (selects
     * with one) or a {@code null} catalog for free-form value rows.
     */
    private final Map<JComboBox<String>, StateAwareCombo> stateAwareCombos = new HashMap<>();

    /**
     * The state-aware selects whose dropdown is currently being rebuilt — the
     * re-entrancy guard of {@link #rebuildStateAwareComboModel} (the combo's
     * own event chain must not re-trigger a rebuild for a select that is
     * mid-rebuild).
     */
    private final Set<JComboBox<?>> rebuildingCombos = new HashSet<>();

    /**
     * The property and the fixed option catalog (may be {@code null} — no
     * catalog, only the state-driven current/saved/applied entries) of one
     * state-aware select.
     */
    private record StateAwareCombo(Prop<?> prop, String[] options) {
    }

    /**
     * Per-item editors for the list-typed properties, keyed by property name. A
     * list value is shown as one single-line field per stored item (each with a
     * trash icon) plus an "add" row; its value (de)serializes by the property's
     * on-disk delimiter (',' for the Jetty DoS whitelists, ';' otherwise).
     */
    private final Map<String, ListEditor> listEditors = new HashMap<>();

    /**
     * The multi-line list input components (the PK-conversion wrapper around
     * the pkChecks list editor — the bare editors are already {@link ListEditor}
     * instances) that must NOT be pinned to a one-line height by
     * {@link ConfigurationUtils#fixComponentSize} in {@link #updateUI()}: that
     * call caps the component's MAXIMUM size, which would squash a
     * multi-item list to a single line after an L&F change.
     */
    private final Set<JComponent> multilineInputs = new HashSet<>();

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
     * "Show levels" box). While all of its checkboxes are selected the group
     * filters nothing (tabbed view); any unchecked box switches to the flat
     * results view listing only the still-selected states.
     */
    private CheckboxGroupPanel statusPanel;
    /**
     * Value-status visibility checkboxes of the search panel (moved here from
     * the bottom legend row, which listed them as plain color boxes). Each
     * one is colored with its value-level state color and is selected by
     * default. While all four are selected everything is visible (tabbed
     * view); when any of them is not selected, the rows of the deselected
     * state(s) are neither listed nor displayed (flat results view, combined
     * with the search text when it is not empty).
     */
    private JCheckBox showUnsavedBox;
    private JCheckBox showUnsavedAppliedBox;
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
        // information about each action.
        this.newProfileBtn = new JButton();
        this.saveBtn = new JButton();
        this.applyBtn = new JButton();
        this.helpBtn = new JButton();
        
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

        newProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip(
                "New Profile<br><br>Creates a new, independent node profile. "
                        + "Choose the setup wizard or start with an empty profile."));
        newProfileBtn.addActionListener(e -> runProfileAction("New Profile", this::newProfile));
        profilePanel.add(newProfileBtn);

        cloneProfileBtn = new JButton();
        cloneProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip(
                "Clone Configuration to New Profile<br><br>Creates a new profile from this editor's "
                        + "current configuration, including unsaved changes. The existing profile is not changed, "
                        + "and the new profile is not started."));
        cloneProfileBtn.addActionListener(e -> runProfileAction("Clone Configuration", this::cloneProfile));
        profilePanel.add(cloneProfileBtn);

        copyProfileDataBtn = new JButton();
        copyProfileDataBtn.setToolTipText(ConfigurationUtils.profileTooltip(
                "Copy Existing Configuration to This Profile<br><br>Selects another node profile and "
                        + "loads its configuration into this editor. This replaces the current editor values; "
                        + "unsaved changes are discarded after confirmation. Use Save to write the copied "
                        + "configuration to this profile."));
        copyProfileDataBtn.addActionListener(e -> runProfileAction("Copy Configuration", this::copyProfileData));
        profilePanel.add(copyProfileDataBtn);

        renameProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip(
                "Rename Profile<br><br>Renames this node profile and its data paths. If the node is "
                        + "running, it is stopped during the rename and returned to its previous state "
                        + "(running, paused, or stopped)."));
        renameProfileBtn.addActionListener(e -> runProfileAction("Rename Profile",
                () -> renameProfile(loadedProfileName)));
        profilePanel.add(renameProfileBtn);

        saveBtn.setToolTipText(ConfigurationUtils.profileTooltip(
                "Save<br><br>Saves all unsaved changes to the current profile<br>"
                        + "(reviewing them in a dialog first). Does NOT restart the node.<br>"
                        + "Active only while there are unsaved changes."));
        saveBtn.setEnabled(false);
        saveBtn.addActionListener(e -> runProfileAction("Save", this::saveProfile));
        profilePanel.add(saveBtn);

        applyBtn.setToolTipText(ConfigurationUtils.profileTooltip(
                "Apply<br><br>Applies the saved changes, marks the profile as applied<br>"
                        + "and restarts the node so they take effect.<br>"
                        + "Active only while there are saved, not yet applied changes."));
        applyBtn.setEnabled(false);
        applyBtn.addActionListener(e -> runProfileAction("Apply", this::applyProfile));
        profilePanel.add(applyBtn);

        reloadProfileBtn = new JButton();
        reloadProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip(
                "Reload Configuration<br><br>Re-reads the current profile file from disk (e.g. after external edits).<br>"
                        + "Unsaved changes in the editor are discarded."));
        reloadProfileBtn.addActionListener(e -> runProfileAction("Reload Configuration", this::reloadProfile));
        profilePanel.add(reloadProfileBtn);

        deleteProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip(
                "Delete Profile<br><br>Permanently deletes this profile, its settings<br>"
                        + "(optionally its database files) and closes its tab.<br>"
                        + "If the node is running it will be stopped (and not restarted)."));
        deleteProfileBtn.addActionListener(e -> runProfileAction("Delete Profile",
                () -> deleteProfile(loadedProfileName)));
        profilePanel.add(deleteProfileBtn);

        updateProfileButtonsUI();

        helpBtn.setToolTipText(ConfigurationUtils.profileTooltip("Help<br><br>Explains profile management, "
                + "configuration value states, and the actions available in this toolbar."));
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
        // "Show levels" box), placed BEFORE the "Search" box: moved here from the
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

        // Compact labels (no trailing "value(s)"): the pane title "Show values"
        // already says what is being filtered, so the boxes save the space.
        showUnsavedBox = statusPanel.addCheckbox("Unsaved", GuiColors.getUnsaved(), true);
        showUnsavedBox.setToolTipText(
                "Show / hide the values that match neither the saved profile file nor the running node. "
                        + "Combined with the search text when it is not empty.");

        showUnsavedAppliedBox = statusPanel.addCheckbox("Unsaved Applied",
                GuiColors.getUnsavedApplied(), true);
        showUnsavedAppliedBox.setToolTipText(
                "Show / hide the values that differ from the saved profile file but match exactly what "
                        + "the running node is using.");

        showSavedBox = statusPanel.addCheckbox("Saved", GuiColors.getSaved(), true);
        showSavedBox.setToolTipText(
                "Show / hide the values that are saved in the profile file but differ from the running node.");

        showAppliedBox = statusPanel.addCheckbox("Saved Applied", GuiColors.getApplied(), true);
        showAppliedBox.setToolTipText(
                "Show / hide the values that are saved in the profile file AND match exactly what the "
                        + "running node is using.");

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
        JPanel searchPanelWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        searchPanelWrap.setOpaque(false);
        // Unify the two boxes' heights (tracking the standard combo height,
        // measured with all search children shown) so the row stays even
        // when the font / L&F changes — the box-level counterpart of the
        // dropdown unification of styleComboEditor.
        ConfigurationUtils.unifyTitledBoxHeights(statusPanel, searchMatchPanel);
        // The first box aligns with the row's 10px left inset; only the gap
        // BETWEEN the status and search boxes is 4px.
        searchPanelWrap.add(statusPanel);
        searchPanelWrap.add(Box.createHorizontalStrut(4));
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
        addSecretListProperty(apiPanel, Props.API_ADMIN_KEY_LIST, "Admin Password (API Key)");
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
        addSecretListProperty(miningPanel, Props.SOLO_MINING_PASSPHRASES, "Solo Mining Passphrases");
        addSecretListProperty(miningPanel, Props.REWARD_RECIPIENT_PASSPHRASES, "Reward Recipient Passphrases");
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
        addProperty(systemPanel, Props.FILE_LOG_ENABLE, "Per-Node File Log Enabled");
        addProperty(systemPanel, Props.FILE_LOG_LEVEL, "Per-Node File Log Level");
        addProperty(systemPanel, Props.FILE_LOG_DIR, "Per-Node File Log Dir");
        addProperty(systemPanel, Props.FILE_LOG_LIMIT, "Per-Node File Log Size Limit (bytes)");
        addProperty(systemPanel, Props.FILE_LOG_COUNT, "Per-Node File Log Rotations");
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
                    if (row.input instanceof JComboBox cb && cb.isEditable()) {
                        // An L&F change replaces the combo's editor component —
                        // the padding styleComboEditor computed for the old
                        // editor is gone, so re-pad: the editable select would
                        // otherwise revert to the new L&F's natural (shorter)
                        // height and render lower than the plain selects.
                        ConfigurationUtils.styleComboEditor(cb);
                    }
                    // The multi-line list inputs (the per-item list editor and
                    // its PK-conversion wrapper) are NOT pinned to a one-line
                    // height: fixComponentSize caps the component's MAXIMUM
                    // size, which would squash a multi-item list to a single
                    // line.
                    if (!(row.input instanceof ListEditor) && !multilineInputs.contains(row.input)) {
                        ConfigurationUtils.fixComponentSize(row.input);
                    }
                }
            });
        }
        // The list editors' own combos (item rows + add row) live INSIDE the
        // editor panel, so the row loop above never reaches their editors —
        // re-pad them explicitly after an L&F change (the same plain-vs-
        // editable height unification as at construction). (Guarded: this
        // method also runs from the super constructor, before the subclass
        // fields exist — the same reason the row loop above is guarded.)
        if (listEditors != null) {
            for (ListEditor le : listEditors.values()) {
                ConfigurationUtils.styleComboEditor(le.addField);
                le.installAddEnterAction();
                for (JComboBox<String> item : le.itemFields) {
                    ConfigurationUtils.styleComboEditor(item);
                }
                le.refreshInputWidths();
            }
        }
        // The db.URL section's fields live INSIDE the JDBC panel (the row's
        // input is the wrapper, so the row loop above never reaches them) —
        // re-derive their unified border / height / editor padding from the
        // new metrics, so a font growth moves them exactly like the rest of
        // the configuration. (Guarded: this method also runs from the super
        // constructor, before the subclass fields exist.)
        if (propertyComponents != null) {
            JdbcConfigurationPanel jdbc = jdbcPanel();
            if (jdbc != null) {
                jdbc.reunifyControlStyles();
            }
        }
        // Re-apply the value-state COLOR coding after the re-style: a font /
        // L&F change replaces the UI delegates, and every EDITABLE combo's
        // editor component is recreated by the new delegate — the
        // updateColor() foreground set on the old editor (and, with some
        // delegates, on plain text inputs) is gone, so the unsaved / applied
        // colors would silently disappear until the next edit. Refreshing the
        // colors LAST (after every re-style above) re-sets them on the NEW
        // editor components, the list-editor item fields and the JDBC
        // section, and re-resolves the dirty-label asterisks. (Guarded:
        // this method also runs from the super constructor, before the
        // profiles are loaded.)
        if (propertyComponents != null && savedProfile != null && appliedProfile != null) {
            refreshUIColors();
            // updateComponentTreeUI refreshes this panel before it refreshes
            // descendant combo-box UIs. Their editors are recreated after the
            // synchronous color pass above, so re-apply colors once the full
            // tree update has completed.
            SwingUtilities.invokeLater(() -> {
                if (propertyComponents != null && savedProfile != null && appliedProfile != null) {
                    refreshUIColors();
                }
            });
        }
        refreshStatusFilterColors();
        updateProfileButtonsUI();
    }

    /**
     * Re-sets the "Show values" checkbox colors from the CURRENT palette —
     * the state colors are theme-dependent palette values, so a theme
     * change must refresh them (a font-only change is a no-op). (Guarded:
     * this method also runs from the super constructor, before the boxes
     * exist.)
     */
    private void refreshStatusFilterColors() {
        if (showUnsavedBox == null) {
            return;
        }
        showUnsavedBox.setForeground(GuiColors.getUnsaved());
        showUnsavedAppliedBox.setForeground(GuiColors.getUnsavedApplied());
        showSavedBox.setForeground(GuiColors.getSaved());
        showAppliedBox.setForeground(GuiColors.getApplied());
    }

    private void updateProfileButtonsUI() {
        ConfigurationUtils.configureProfileToolbar(newProfileBtn, saveBtn, applyBtn, renameProfileBtn,
                deleteProfileBtn, reloadProfileBtn, null, null,
                copyProfileDataBtn, cloneProfileBtn);
        float iconSize = GuiConstants.getToolBarIconSize();
        // Reload carries the refresh glyph's swapped counterpart (see
        // ConfigurationUtils#configureProfileToolbar): it is node-config's
        // "refresh" action, so it moves to the same UNDO glyph as the other panels.
        ConfigurationUtils.styleProfileIconButton(reloadProfileBtn, FontAwesome.UNDO,
                GuiColors.getButtonIcon(), iconSize);
        // Save / Apply are tinted with their value-state colors (the "saved"
        // and "applied" palette colors) so they read as the two halves of the
        // save-then-apply flow instead of two identical floppy icons.
        ConfigurationUtils.styleProfileIconButton(saveBtn, FontAwesome.FLOPPY_O, GuiColors.getSaved(), iconSize);
        ConfigurationUtils.styleProfileIconButton(applyBtn, FontAwesome.CHECK_CIRCLE_O, GuiColors.getApplied(), iconSize);
        // The help button keeps the same flat toolbar style (size, border) as
        // the other profile-action buttons.
        ConfigurationUtils.styleProfileIconButton(helpBtn, FontAwesome.QUESTION_CIRCLE, GuiColors.getButtonIcon(),
                iconSize);
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
     * substring highlighted; an empty query together with all four
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
        // filter: while all four are selected every row is visible (tabbed
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
                // The JDBC row carries its ? icon INSIDE the row's input
                // wrapper (the checkbox row) — its row.help is null.
                if (row.help != null) {
                    searchResultsPanel.add(row.help, row.helpConstraints);
                }
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
        if (row.help != null) {
            row.helpIndex = row.originalParent.getComponentZOrder(row.help);
        }
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
        renderer.setDelegateRenderer(comboBox.getRenderer());
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
     *   <li>legacy node metadata is migrated centrally into {@code profiles.json}
     *       before these values are read.</li>
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
     * The host-side half of a link change (the panel's
     * {@link JdbcConfigurationPanel.LinkChangeListener}): keeps the "Linked
     * Database Profile" checkbox in sync with the panel's mode (the panel may
     * auto-unlink itself, e.g. an unmaterializable link at load) and persists the
     * effective link into profiles.json — unless the change is
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
        int choice = DialogUtils.confirm(this,
                DialogUtils.actionIcon(FontAwesome.CHECK_CIRCLE_O, GuiColors.getApplied()),
                "Apply Configuration",
                "Apply the saved configuration of profile '" + name + "'? It will restart the node.",
                JOptionPane.YES_NO_OPTION, null, null);
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
                getProfilesConfigPath(),
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

                boolean isList = listEditors.containsKey(row.prop.getName());

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
        String source = ProfileCopyDialog.show(this, profiles, selected,
                ProfileCopyDialog.DEFAULT_CONFIGURATION);
        if (source == null || source.trim().isEmpty()) {
            return;
        }
        // "Default configuration" (or the dialog's Reset to Defaults button) is the
        // relocated reset-to-defaults action, replacing the former toolbar button.
        if (ProfileCopyDialog.DEFAULT_CONFIGURATION.equals(source)) {
            resetToDefaults();
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
                    // A reload re-reads the file into the editor and the saved
                    // baseline only. It must NOT move the applied baseline: the
                    // running node keeps its configuration until a restart, so a
                    // value that was "saved, not yet applied" stays SAVED (yellow)
                    // after a reload instead of silently turning APPLIED (green).
                    // The applied baseline is re-anchored only on a restart
                    // (onNodeRestarted) or when the user Applies.
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
        // A restart (re)loads the on-disk configuration into the node. When
        // there is no unsaved work the editor is refreshed too (a full reload
        // from disk — exactly the Reload button's refresh); while the user is
        // mid-edit the unsaved work is left untouched. In BOTH cases the
        // applied baseline — the one thing that moves on a restart — is
        // re-anchored to the on-disk state (what the node now runs with).
        if (!hasUnsavedChanges()) {
            reloadFromDisk();
        }
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
                deleteProfileBtn, copyProfileDataBtn, cloneProfileBtn,
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
                "A node configuration profile is a set of settings for one node profile. The selected profile "
                        + "is edited here; Save writes changes to its configuration file, while Apply selects "
                        + "it for the node and restarts the node when required. Use the toolbar buttons to "
                        + "manage profiles and review their state."));
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
        content.add(HelpDialog.actionRow(FontAwesome.REFRESH, iconColor, "Reset to Defaults",
                "In the Copy Configuration dialog: resets all current settings to their application "
                        + "default values without saving (via the \"Default configuration\" entry or the "
                        + "Reset to Defaults button)."));
        content.add(HelpDialog.actionRow(FontAwesome.CLIPBOARD, iconColor, "Copy Configuration",
                "Opens a dialog to copy another profile's configuration — or the built-in \"Default "
                        + "configuration\" — into the editor of the current profile. The copied values "
                        + "become unsaved changes you can adjust before saving."));
        content.add(HelpDialog.actionRow(FontAwesome.FILES_O, iconColor, "Clone Configuration",
                "Creates a new profile from the current (unsaved, editor) effective state; the "
                        + "source profile is left untouched."));
        content.add(HelpDialog.actionRow(FontAwesome.UNDO, iconColor, "Reload Configuration",
                "Reloads the current profile file from disk (e.g. after external edits), discarding "
                        + "any unsaved changes in the UI."));
        content.add(HelpDialog.actionRow(FontAwesome.TRASH_O, GuiColors.getContrastRed(),
                "Delete Profile",
                "Permanently deletes the selected profile — its node is stopped (if running, and "
                        + "not restarted), the profile settings are removed (optionally its SQLite "
                        + "database data) and its tab is closed."));
        content.add(HelpDialog.actionRow(FontAwesome.QUESTION_CIRCLE, iconColor, "Help",
                "Opens this guide to profile actions, value-state colors and profile assignments."));
        content.add(HelpDialog.separator());
        content.add(HelpDialog.paragraph(
                "The <b>Unsaved</b> state means the editor value differs from both the saved file and the "
                        + "loaded profile baseline; <b>Saved</b> means the value is saved but differs from "
                        + "that baseline; <b>Applied</b> means it matches the loaded profile value. "
                        + "The search field matches property names, keys and values, while the Show values "
                        + "checkboxes filter rows by state.<br><br>"
                        + "The <b>database</b> profile link is managed on the Database tab's "
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

        // List property detection: the row has a per-item editor (addListProperty).
        boolean isList = listEditors.containsKey(row.prop.getName());

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
        saveBtn.setToolTipText(ConfigurationUtils.profileTooltip(overallDirty
                ? "Save — you have unsaved changes"
                : "Save"));
        saveBtn.setEnabled(overallDirty);

        boolean savedNotApplied = hasSavedNotAppliedChanges();
        applyBtn.setToolTipText(ConfigurationUtils.profileTooltip(savedNotApplied
                ? "Apply<br><br>Applies the saved changes, marks the profile as applied<br>"
                        + "and restarts the node so they take effect."
                : "Apply<br><br>Applies the saved changes, marks the profile as applied<br>"
                        + "and restarts the node so they take effect.<br>"
                        + "Active only while there are saved, not yet applied changes."));
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


    private Path getProfilesConfigPath() {
        return PathUtils.resolvePath(confFolder).resolve(Signum.NODE_SUBFOLDER).resolve("profiles.json");
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

            ListEditor editor = listEditors.get(key);
            if (editor != null) {
                editor.setItems(splitList(val));
                updateColor(comp, key, defaultValues.get(key));
                continue;
            }

            if (comp instanceof JCheckBox) {
                boolean isSelected = "true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val) || "1".equals(val)
                        || "on".equalsIgnoreCase(val);
                ((JCheckBox) comp).setSelected(isSelected);
            } else if (comp instanceof JComboBox) {
                ((JComboBox<?>) comp).setSelectedItem(val);
            } else if (comp instanceof javax.swing.text.JTextComponent) {
                ((javax.swing.text.JTextComponent) comp).setText(val);
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
            JComboBox<String> comboBox;
            if (savedValue != null && Arrays.asList(options).contains(savedValue)) {
                // The ORIGINAL non-editable select: the fixed option catalog.
                // Every dropdown entry is colored by its own value state
                // (applied green, saved yellow, the current value in the row's
                // own state — see the renderer).
                comboBox = new JComboBox<>(options);
                comboBox.setPrototypeDisplayValue("Prototype");
                comboBox.setSelectedItem(savedValue);
                valueSuppliers.put(prop.getName(), () -> (String) comboBox.getSelectedItem());
            } else {
                // The saved value is not in the fixed options (a custom value):
                // the select joins the state-aware value model — editable, with
                // the option catalog plus the state's baseline values in its
                // dropdown, exactly like the regular input rows.
                comboBox = createStateAwareValueCombo(prop, savedValue, options);
                valueSuppliers.put(prop.getName(), () -> stateAwareComboText(comboBox));
            }
            ConfigurationUtils.fixComponentSize(comboBox);
            inputComponent = comboBox;
            if (!comboBox.isEditable()) {
                // the state-aware factory already wired the renderer of the editable case
                comboBox.setRenderer(stateAwareItemRenderer(prop,
                        () -> (String) comboBox.getSelectedItem()));
            }
            comboBox.addActionListener(e -> {
                if (isProgrammaticChange)
                    return;
                updateColor(comboBox, prop.getName(), getSafeDefault(prop));
                updateDirtyStatus();
            });
        } else {
            // A regular input row is an EDITABLE select (not a plain text
            // field): its editor text is the row's current value (styled
            // exactly like the former text field) and its dropdown carries the
            // state's baseline values (the saved and/or applied value), so the
            // user can pick a baseline back in one click.
            JComboBox<String> comboBox = createStateAwareValueCombo(prop, savedValue, null);
            inputComponent = comboBox;
            valueSuppliers.put(prop.getName(), () -> stateAwareComboText(comboBox));
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

    /**
     * The EDITABLE, state-aware select that is the input of every regular
     * value row (and of a select whose saved value is not in its fixed
     * options): the editor text is the row's current value (styled exactly
     * like the former plain text field) and the dropdown model is rebuilt on
     * every state change by {@link #rebuildStateAwareComboModel} with the
     * option catalog (selects with one, {@code null} for free-form value
     * rows) plus exactly the baseline values the row's state keeps
     * selectable. The dropdown entries are colored per item by
     * {@link #stateAwareItemRenderer} and the row's own value coloring stays
     * driven by the editor text (the value supplier reads it too).
     */
    private JComboBox<String> createStateAwareValueCombo(Prop<?> prop, String savedValue, String[] options) {
        JComboBox<String> comboBox = new JComboBox<>(new String[0]);
        comboBox.setEditable(true);
        stateAwareCombos.put(comboBox, new StateAwareCombo(prop, options));
        JComponent editorComponent = (JComponent) comboBox.getEditor().getEditorComponent();
        // the editor TEXT FIELD (wrapped by a panel with the Metal L&F) is
        // styled like a regular input, with the same left margin as the
        // regular (non-editable) selects.
        ConfigurationUtils.styleComboEditor(comboBox);
        // The editor text is the row's current value — seed it with the
        // (default-resolved) saved value, exactly like the former plain text
        // fields did. Flagged: the seed fires document events that must not
        // queue a refresh. (Without an explicit seed the initial rebuild's
        // model swap would leave the editor EMPTY — before the typed-text
        // restore existed, that swap silently left it at the model's first
        // entry, which happened to be the saved baseline, and that accident
        // is what used to fill the inputs on load.)
        boolean prevFlag = isProgrammaticChange;
        isProgrammaticChange = true;
        try {
            if (savedValue != null && !savedValue.trim().isEmpty()) {
                comboBox.getEditor().setItem(savedValue);
            }
            rebuildStateAwareComboModel(comboBox, prop, savedValue);
        } finally {
            isProgrammaticChange = prevFlag;
        }
        comboBox.setRenderer(stateAwareItemRenderer(prop, () -> stateAwareComboText(comboBox)));
        // The editor text IS the value — track its changes exactly like the old
        // plain text fields did. The L&F alone is not reliable for this: the
        // Metal editor's text field is NOT the editor component, so Swing does
        // not propagate typed (or programmatically set) characters to an action
        // event synchronously — the dirty state and colors would lag the typing.
        javax.swing.text.JTextComponent editorText = findEditorText(editorComponent);
        if (editorText != null) {
            javax.swing.event.DocumentListener listener = new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    onEditorTextChange(comboBox, prop);
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    onEditorTextChange(comboBox, prop);
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                }
            };
            editorText.getDocument().addDocumentListener(listener);
        }
        comboBox.addActionListener(e -> {
            if (isProgrammaticChange)
                return;
            updateColor(comboBox, prop.getName(), getSafeDefault(prop));
            updateDirtyStatus();
        });
        return comboBox;
    }

    /**
     * The color / dirty-state refresh for a state-aware select's typed text —
     * the document-listener twin of the combo's action listener (selections
     * arrive through the action, typing through the document).
     * <p>The refresh is DEFERRED: the listener runs inside a document-change
     * notification, and the model rebuild touches the editor text (the L&F
     * reconfigures the editor on every model swap) — mutating a document while
     * its own notification is in flight is forbidden by Swing ("Attempt to
     * mutate in notification"). Deferred, the refresh settles after the
     * pending document events (exactly like the old plain text fields did).
     */
    private void onEditorTextChange(JComboBox<String> comboBox, Prop<?> prop) {
        if (isProgrammaticChange)
            return;
        SwingUtilities.invokeLater(() -> {
            // Flagged: the refresh rebuilds the dropdown (a model swap, and the
            // typed-text restore after it, both touch the editor text) — the
            // restore's own document events must not reschedule this refresh
            // again (an endless deferred cycle).
            isProgrammaticChange = true;
            try {
                updateColor(comboBox, prop.getName(), getSafeDefault(prop));
                updateDirtyStatus();
            } finally {
                isProgrammaticChange = false;
            }
        });
    }

    /**
     * The text component of a combo editor (the editor component itself with
     * the Basic L&F, its child text field with the Metal one — the editor
     * component there is a panel wrapping the field).
     */
    private static javax.swing.text.JTextComponent findEditorText(JComponent c) {
        if (c instanceof javax.swing.text.JTextComponent t) {
            return t;
        }
        if (c instanceof java.awt.Container cont) {
            for (java.awt.Component child : cont.getComponents()) {
                if (child instanceof javax.swing.text.JTextComponent t) {
                    return t;
                }
            }
        }
        return null;
    }

    /**
     * The current (live) text of an editable state-aware combo — what the
     * user typed or selected into the editor, the row's current value
     * (trimmed, empty string for a null editor item).
     */
    private static String stateAwareComboText(JComboBox<String> comboBox) {
        // During an L&F update (updateUI) the combo's UI is swapped and the
        // editor's client-property is briefly NULL — fall back to the selected
        // item so renderers and size computations cannot NPE in that window.
        var editor = comboBox.getEditor();
        Object item = editor != null ? editor.getItem() : comboBox.getSelectedItem();
        return item == null ? "" : item.toString().trim();
    }

    /**
     * Rebuilds the dropdown of one state-aware select from the row's current
     * 4-state ({@link ProfileValueState}): the option catalog (when the row
     * has one) is always present, the row's current value is always part of
     * the model (the dropdown is never empty) and the baseline entries are
     * exactly {@link ProfileValueState#dropdownBaselines} — the saved value
     * (SAVED / UNSAVED_APPLIED / UNSAVED), the applied value (UNSAVED), or
     * none (SAVED_APPLIED).
     */
    private void rebuildStateAwareComboModel(JComboBox<String> comboBox, Prop<?> prop, String savedValue) {
        // Re-entrancy guard: the combo's own event chain (editor text change,
        // model selection change) can re-trigger this rebuild while a rebuild
        // for the same combo is already in flight — the in-flight rebuild has
        // (or is about to have) the correct items, so a nested one is
        // redundant. Without the guard the chain (setSelectedItem → editor
        // setText → document listener → updateColor → rebuild) never settles.
        if (rebuildingCombos.contains(comboBox)) {
            return;
        }
        rebuildingCombos.add(comboBox);
        try {
            String current = stateAwareComboText(comboBox);
            String saved = savedValue == null ? getSafeDefault(prop) : savedValue;
            String applied = appliedProfile.getProperty(prop.getName());
            if (applied == null)
                applied = getSafeDefault(prop);
            LinkedHashSet<String> items = new LinkedHashSet<>();
            StateAwareCombo info = stateAwareCombos.get(comboBox);
            String[] options = info == null ? null : info.options();
            if (options != null) {
                for (String option : options)
                    items.add(option);
            }
            if (!current.isEmpty())
                items.add(current);
            for (String baseline : ProfileValueState.dropdownBaselines(false, current, saved, applied)) {
                items.add(baseline);
            }
            // Every editor / model touch below is PROGRAMMATIC, so it runs
            // flagged: the restore fires document events (and the pre-swap
            // selection can fire the combo's action), and neither may schedule
            // a refresh of its own. Without the flag, the INITIAL rebuild of
            // every row (empty editor, before the load fills the values) would
            // queue a deferred refresh that paints the row UNSAVED — the empty
            // editor against the saved baseline — and sticks the dirty star on
            // every tab (a reported regression: the JDBC tests match their tab
            // by the EXACT unmarked title).
            boolean prevFlag = isProgrammaticChange;
            isProgrammaticChange = true;
            try {
                // Keep the (typed) current value as the editor item before
                // swapping the model; when the user has typed something that is
                // not among the entries the selection simply drops (the editor
                // text stays as typed).
                String typed = comboBox.getEditor().getItem() instanceof String s ? s : "";
                if (!typed.isEmpty()) {
                    comboBox.setSelectedItem(typed);
                } else {
                    comboBox.setSelectedIndex(-1);
                }
                comboBox.setModel(new DefaultComboBoxModel<>(items.toArray(new String[0])));
                // Keep the model selection in sync with the editor text.
                // Restoring only the text leaves the model selected on its
                // first item; a later UI-delegate refresh can then overwrite
                // the editor with that unrelated value.
                comboBox.setSelectedItem(typed);
                // An emptied input stays EMPTY (it is an unsaved value, not a
                // reason to fill a baseline back in).
                if (!typed.equals(stateAwareComboText(comboBox))) {
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
     * The dropdown renderer of the state-aware (and the original non-editable)
     * selects: every entry is colored by the state of its OWN value — the
     * entry equal to the row's current value displays the row's own state
     * (gray for unsaved-but-applied, the state that is not visible on the
     * row's label/input itself), an applied baseline green only when it is
     * also saved (otherwise gray unsaved-applied), a saved baseline yellow,
     * everything else normal text color.
     */
    /**
     * When sizing a non-editable combo's DISPLAY area the L&F invokes the
     * renderer with a NULL list (BasicComboBoxUI.getDisplaySize), and this
     * JDK's DefaultListCellRenderer does not tolerate that (NPE) — a shared
     * throwaway list stands in; the renderer overwrites the colors it would
     * copy from the list for every value entry anyway.
     */
    private static final JList<?> RENDERER_FALLBACK_LIST = new JList<>(new String[0]);

    private DefaultListCellRenderer stateAwareItemRenderer(Prop<?> prop, Supplier<String> currentValue) {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                if (list != null && index >= 0) {
                    ComboBoxItemToolTips.install(list, item -> {
                        if (!(item instanceof String text)) {
                            return null;
                        }
                        String saved = savedProfile.getProperty(prop.getName());
                        if (saved == null) {
                            saved = getSafeDefault(prop);
                        }
                        String applied = appliedProfile.getProperty(prop.getName());
                        if (applied == null) {
                            applied = getSafeDefault(prop);
                        }
                        return itemStatus(text, currentValue.get(), saved, applied).tooltip();
                    });
                }
                Component c = super.getListCellRendererComponent(
                        list == null ? RENDERER_FALLBACK_LIST : list, value, index, isSelected, cellHasFocus);
                if (value instanceof String) {
                    String saved = savedProfile.getProperty(prop.getName());
                    if (saved == null)
                        saved = getSafeDefault(prop);
                    String applied = appliedProfile.getProperty(prop.getName());
                    if (applied == null)
                        applied = getSafeDefault(prop);
                    ValueStatus status = itemStatus((String) value, currentValue.get(), saved, applied);
                    c.setForeground(status.color());
                    ((JComponent) c).setToolTipText(status.tooltip());
                }
                return c;
            }
        };
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

        // Two columns: the checkbox row is [Linked Database Profile][?] —
        // when a question-mark help icon follows a check box, the two must
        // sit on the SAME row, the ? vertically centered on that row (its
        // MigLayout default center alignment). The editor + auto start/stop
        // rows span both columns.
        JPanel wrapper = new JPanel(new MigLayout("insets 0, fillx, gap 2", "[grow][]", "[]5[]"));
        wrapper.setOpaque(false);

        JCheckBox useProfileCheck = new JCheckBox("Linked Database Profile");
        useProfileCheck.setOpaque(false);
        wrapper.add(useProfileCheck);

        // The row's ? help icon lives in the checkbox row (NOT in the
        // separate help column): on a tall JDBC card the help column would
        // span the whole section and the ? could not be centered on the
        // checkbox row alone. Row model: row.help stays null (search view
        // and restore skip null parts) — the button travels inside the
        // wrapper with the row.
        JButton helpBtn = new HelpButton();
        helpBtn.addActionListener(e -> showHelp(prop, labelText));
        wrapper.add(helpBtn, "wrap");

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
        wrapper.add(jdbcPanel, "span 2, growx");

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
        wrapper.add(autoDbRow, "span 2, gaptop 4");

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
        // The trailing "wrap" is load-bearing: this row has NO help button
        // in the OUTER grid (the ? travels inside the wrapper's checkbox
        // row), so the help column is still free on the input row — without
        // the wrap, the next component (the row separator, "span") would
        // land in that free column (a short line to the RIGHT of the JDBC
        // card, beside "Path:") instead of dropping to its own full-width
        // row under the "JDBC URL:" preview.
        row.inputConstraints = "split 2, growx, wrap";
        panel.add(wrapper, row.inputConstraints);
        propertyComponents.put(prop.getName(), wrapper);
        // Initial enablement of the auto start/stop boxes follows the current
        // (manual card's) engine.
        updateAutoDbCheckboxesState();

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
        addListProperty(panel, prop, labelText, false);
    }

    /**
     * A list property whose items are SECRETS (the API admin key, the
     * mining passphrases): identical to {@link #addListProperty} except the
     * per-item dropdowns mask every entry by default and give each one its
     * own eye toggle to reveal / hide the value before selecting it.
     */
    private void addSecretListProperty(JPanel panel, Prop<?> prop, String labelText) {
        addListProperty(panel, prop, labelText, true);
    }

    private void addListProperty(JPanel panel, Prop<?> prop, String labelText, boolean secretItems) {
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
        defaultValues.put(prop.getName(), serializeListValue(getSafeDefault(prop), prop.getName()));
        // Per-item editor: one editable COMBO per stored item, stacked one below
        // the other (each with a trash icon; the dropdown offers the item's
        // baseline values from the saved / applied sets), plus a final "add" row
        // (editable combo + flat '+' button styled like the new-profile toolbar
        // button). Emptying an item input removes that item. Every item is
        // colored by its own state (unsaved/saved/applied) and the row reads
        // DIRTY whenever the item set differs from saved.
        ListEditor editor = new ListEditor(prop.getName(), secretItems);
        listEditors.put(prop.getName(), editor);
        editor.onChange = () -> {
            updateColor(editor, prop.getName(), getSafeDefault(prop));
            updateDirtyStatus();
        };
        editor.setItems(splitList(savedValue));
        updateColor(editor, prop.getName(), getSafeDefault(prop));

        boolean isPkCheck = Props.NODE_PK_CHECKS.getName().equals(prop.getName());
        JComponent valueComponent;
        if (isPkCheck) {
            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.setOpaque(false);
            wrapper.add(editor, BorderLayout.CENTER);

            JButton convertBtn = new JButton(
                    IconFontSwing.buildIcon(FontAwesome.MAGIC, GuiConstants.getHelpIconSize(),
                            GuiColors.getHelpIcon()));
            convertBtn.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
            convertBtn.setContentAreaFilled(false);
            convertBtn.setFocusPainted(false);
            convertBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            convertBtn.setToolTipText("Analyze/Convert Public Keys");
            convertBtn.addActionListener(e -> showPkConversionDialog(editor));

            JPanel btnContainer = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            btnContainer.setOpaque(false);
            btnContainer.add(convertBtn);
            wrapper.add(btnContainer, BorderLayout.SOUTH);

            valueComponent = wrapper;
            // exempt from the one-line height pin in updateUI() — see multilineInputs
            multilineInputs.add(wrapper);
        } else {
            valueComponent = editor;
        }
        // No growx: unlike the single-value inputs, the list editor must
        // occupy only as much horizontal space as it needs (its widest row)
        // instead of stretching to the column width. The wmin keeps an EMPTY
        // list from collapsing to a stub, and the height pref! (the regular
        // inputs' own constraint) pins the row to the editor's own preferred
        // height — an empty list must not leave a blank strip above the add
        // row (the former hmin 80 floor). The trailing "wrap" is load-bearing
        // (no help button in the OUTER grid): without it the next component
        // (the "span" row separator) would land in the still-free help
        // column — a short line to the RIGHT of the editor — instead of
        // dropping to its own full-width row.
        panel.add(valueComponent, "split 2, wmin 280, height pref!, align left, wrap");
        propertyComponents.put(prop.getName(), valueComponent);
        row.inputConstraints = "split 2, wmin 280, height pref!, align left, wrap";

        // The "?" info icon travels INSIDE the editor's FIRST item row (right
        // of that row's trash icon, the uniform gap) — its row.help stays
        // null (the search-results view and the row restore never see a
        // separate help column for list rows).
        editor.helpAction = () -> showHelp(prop, labelText);
        row.separator = new JSeparator();
        row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";
        panel.add(row.separator, row.separatorConstraints);

        row.input = valueComponent;
        addPropertyRow(row);

        valueSuppliers.put(prop.getName(), () -> serializeListValue(editor.getJoinedValue(), prop.getName()));
    }

    private void showPkConversionDialog(ListEditor editor) {
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
        String content = editor.getJoinedValue();
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
                    editor.addItem(hex);
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

    /**
     * The effective "Default" baseline of a property: the user-editable
     * {@code default.properties} L1 layer value when the user
     * activated a line there, otherwise the hardcoded L0 default — the SSOT
     * is {@link EffectiveNodeProps#baselineValue}. Every row state, the
     * reset-to-defaults action and the clone diff run against this baseline,
     * so a profile file only ever needs to store the non-L1 overrides.
     */
    private String getSafeDefault(Prop<?> prop) {
        return EffectiveNodeProps.baselineValue(prop.getName(), prop.getDefaultValue());
    }

    /**
     * The four value-level states that drive the row color coding and the
     * search-panel visibility checkboxes. The state of a value is the relation
     * between the THREE values the profile editor tracks for every input —
     * current (the editor value), saved (the profile file) and applied (the
     * init-loaded baseline the node runs with; all three identical at
     * initialization) — and the derivation's SSOT is the headless
     * {@link ProfileValueState} (2×2 matrix over saved/applied agreement).
     */
    private enum ValueStatus {
        UNSAVED, UNSAVED_APPLIED, SAVED, SAVED_APPLIED;

        String displayName() {
            return switch (this) {
                case UNSAVED -> "Unsaved";
                case UNSAVED_APPLIED -> "Unsaved Applied";
                case SAVED -> "Saved";
                case SAVED_APPLIED -> "Saved Applied";
            };
        }

        String tooltip() {
            return "Status: " + displayName();
        }

        /**
         * The palette color of this state — read LIVE from the palette on
         * every call (never cached): the state colors are theme-dependent
         * GUI color constants, so a theme / appearance change must refresh
         * them to the new palette values while the font and the computed
         * state itself are left untouched.
         */
        public Color color() {
            return switch (this) {
                case UNSAVED -> GuiColors.getUnsaved();
                case UNSAVED_APPLIED -> GuiColors.getUnsavedApplied();
                case SAVED -> GuiColors.getSaved();
                case SAVED_APPLIED -> GuiColors.getApplied();
            };
        }

        /** @param state the headless resolved state (same names) */
        static ValueStatus fromState(ProfileValueState.State state) {
            return ValueStatus.valueOf(state.name());
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
     * saved (profile-on-disk) and applied (the init-loaded baseline the node
     * runs with) values — the 2×2 matrix (current vs. saved, current vs.
     * applied), delegated to the headless {@link ProfileValueState} SSOT:
     * {@link ValueStatus#UNSAVED} (normal text color) when the value differs
     * from BOTH baselines, {@link ValueStatus#UNSAVED_APPLIED} (gray) when it
     * differs from the saved file but matches the applied baseline,
     * {@link ValueStatus#SAVED} (yellow) when it equals the saved file but
     * differs from the applied baseline, otherwise
     * {@link ValueStatus#SAVED_APPLIED} (green). A dirty edit is never
     * "saved" even when the runtime happens to match it. Boolean properties
     * compare parsed booleans, all other values compare as trimmed strings —
     * the exact rules the color coding uses.
     */
    private static ValueStatus valueStatus(boolean isCheckbox, String value, String saved, String applied) {
        return ValueStatus.fromState(ProfileValueState.resolve(isCheckbox, value, saved, applied));
    }

    /**
     * The display state of one select dropdown item for the value coloring:
     * every item displays the state of its OWN value — the item equal to the
     * row's current value displays the row's own state (making the gray
     * UNSAVED_APPLIED state visible in the dropdown), an applied value green
     * only when it is also saved (otherwise gray unsaved-applied), a saved
     * value yellow, everything else unsaved (normal) — see
     * {@link ProfileValueState#itemState}.
     */
    private static ValueStatus itemStatus(String item, String current, String saved, String applied) {
        return ValueStatus.fromState(ProfileValueState.itemState(false, item,
                ProfileValueState.resolve(false, current, saved, applied), current, saved, applied));
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
     * Profile row's own comparison), with the same 2×2 matrix as
     * {@link #valueStatus} (matching the row's own per-value color coding).
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
        boolean eqSaved = curVals.equals(savedVals);
        boolean eqApplied = curVals.equals(appliedVals);
        if (eqSaved && eqApplied) {
            return ValueStatus.SAVED_APPLIED;
        }
        if (eqSaved) {
            return ValueStatus.SAVED;
        }
        if (eqApplied) {
            return ValueStatus.UNSAVED_APPLIED;
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

        // List properties compare as item sets (order-insensitive, de-duplicated).
        ListEditor editor = listEditors.get(key);
        if (editor != null) {
            String cur = normalizeListValue(editor.getJoinedValue(), "\n");
            String saved = normalizeListValue(savedProfile.getProperty(key, defaultValue), ";");
            String applied = normalizeListValue(appliedProfile.getProperty(key, defaultValue), ";");
            return valueStatus(false, cur, saved, applied);
        }

        JComponent target = comp;
        String value = "";
        if (comp instanceof JPanel && Props.DB_URL.getName().equals(key)) {
            Supplier<String> supplier = valueSuppliers.get(key);
            String supplierVal = supplier != null ? supplier.get() : "";
            value = supplierVal != null ? supplierVal.trim() : "";
        } else if (comp instanceof JCheckBox) {
            value = String.valueOf(((JCheckBox) comp).isSelected());
        } else if (comp instanceof JComboBox) {
            JComboBox<?> comboBox = (JComboBox<?>) comp;
            if (comboBox.isEditable()) {
                // State-aware editable select: the CURRENT value is the editor
                // text (typed or picked), not the selected model item.
                value = stateAwareCombos.containsKey(comboBox)
                        ? stateAwareComboText((JComboBox<String>) comboBox)
                        : String.valueOf(comboBox.getSelectedItem());
            } else {
                Object item = comboBox.getSelectedItem();
                value = item == null ? "" : item.toString();
            }
        } else if (target instanceof javax.swing.text.JTextComponent) {
            value = ((javax.swing.text.JTextComponent) target).getText().trim();
        }
        String savedValue = savedProfile.getProperty(key, defaultValue);
        String applied = appliedProfile.getProperty(key, defaultValue);
        if (savedValue == null)
            savedValue = "";
        if (applied == null)
            applied = "";
        savedValue = savedValue.trim();
        applied = applied.trim();
        return valueStatus(comp instanceof JCheckBox, value, savedValue, applied);
    }

    /** @return true when the row's value state is still selected by its search-panel checkbox */
    private boolean isRowStatusVisible(PropertyRow row) {
        switch (rowStatus(row)) {
            case UNSAVED:
                return showUnsavedBox.isSelected();
            case UNSAVED_APPLIED:
                return showUnsavedAppliedBox.isSelected();
            case SAVED:
                return showSavedBox.isSelected();
            case SAVED_APPLIED:
            default:
                return showAppliedBox.isSelected();
        }
    }

    private void updateColor(JComponent comp, String propName, String defaultValue) {
        // List properties are colored per-item (each item field by its own state).
        ListEditor editor = listEditors.get(propName);
        if (editor != null) {
            colorListEditor(propName, defaultValue, editor);
            return;
        }

        String value = "";
        JComponent target = comp;

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
            JComboBox<?> comboBox = (JComboBox<?>) comp;
            if (comboBox.isEditable()) {
                // State-aware editable select: the CURRENT value is the editor
                // text (typed or picked), not the selected model item.
                target = (JComponent) comboBox.getEditor().getEditorComponent();
                value = stateAwareCombos.containsKey(comboBox)
                        ? stateAwareComboText((JComboBox<String>) comboBox)
                        : String.valueOf(comboBox.getSelectedItem());
            } else {
                Object item = comboBox.getSelectedItem();
                value = item == null ? "" : item.toString();
            }
        } else if (target instanceof javax.swing.text.JTextComponent) {
            value = ((javax.swing.text.JTextComponent) target).getText().trim();
            savedValue = savedValue.trim();
            applied = applied.trim();
        }

        ValueStatus status = valueStatus(comp instanceof JCheckBox, value, savedValue, applied);
        Color color = status.color();
        String tooltip = status.tooltip();
        // The state-aware select's dropdown follows the state: rebuild its
        // selectable baseline entries (saved / applied) after every
        // re-resolution so they can never disagree with the row color.
        if (comp instanceof JComboBox<?> comboBox && stateAwareCombos.containsKey(comboBox)) {
            StateAwareCombo info = stateAwareCombos.get(comboBox);
            if (info != null) {
                rebuildStateAwareComboModel((JComboBox<String>) comboBox, info.prop(), savedValue);
            }
        }

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

        target.setForeground(color);
        comp.setToolTipText(tooltip);
        if (comp instanceof JComboBox<?> comboBox && comboBox.isEditable()) {
            JComponent editorComponent = (JComponent) comboBox.getEditor().getEditorComponent();
            editorComponent.setToolTipText(tooltip);
            javax.swing.text.JTextComponent currentEditorText = findEditorText(editorComponent);
            if (currentEditorText != null) {
                currentEditorText.setToolTipText(tooltip);
            }
        } else if (target != comp) {
            target.setToolTipText(tooltip);
        }
    }

    /**
     * Recolors a list property: the row label gets the DIRTY asterisk when the
     * current item set differs from the saved one, and every item field is
     * colored by its own state (the 2×2 saved/applied matrix of
     * {@link ValueStatus}), not a single uniform color for the whole list.
     */
    private void colorListEditor(String propName, String defaultValue, ListEditor editor) {
        String savedValue = savedProfile.getProperty(propName, defaultValue);
        String applied = appliedProfile.getProperty(propName, defaultValue);
        String value = normalizeListValue(editor.getJoinedValue(), "\n");
        String savedNorm = normalizeListValue(savedValue, ";");

        PropertyRow row = allPropertyRows.stream()
                .filter(r -> r.prop != null && r.prop.getName().equals(propName))
                .findFirst()
                .orElse(null);
        if (row != null && row.label != null) {
            boolean isDirty = !value.equals(savedNorm);
            row.label.setText(isDirty ? row.labelText + " *" : row.labelText);
        }

        // The ordered (profile-order) saved / applied item lists back the
        // per-item dropdown baselines (row i pairs with the i-th saved and
        // the i-th applied item) — a membership-only set would lose the
        // order the pairing needs.
        editor.refreshItemColors(splitList(savedValue), splitList(applied));
    }

    private void showColorLegendHelp() {
        String msg = "<html><body style='width: 350px'>" +
                "<h3>Color Coding Legend</h3>" +
                "<p>The configuration values are color-coded to indicate their current status. Each value is compared " +
                "against the profile file (SAVED) and the baseline this node runs with (APPLIED):</p>" +
                "<ul>" +
                "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getUnsaved())
                + "'>\u25A0 Unsaved Values:</font></b> " +
                "These values differ from BOTH the profile file and the applied baseline — they are new, " +
                "not-yet-saved changes. Properties with unsaved changes are marked with an asterisk (*).</li>" +
                "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getUnsavedApplied())
                + "'>\u25A0 Unsaved and Applied Values:</font></b> " +
                "These values differ from the profile file but match exactly the applied baseline — the running " +
                "node already uses them, they are only missing from the saved file.</li>" +
                "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getSaved())
                + "'>\u25A0 Saved Values:</font></b> " +
                "These values are saved in the currently loaded profile on disk, but they differ from the applied " +
                "baseline — saved, but not yet applied (a restart or apply is needed to take effect).</li>" +
                "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getApplied())
                + "'>\u25A0 Saved and Applied Values:</font></b> " +
                "These values are saved in the profile file AND match exactly the applied baseline — this node " +
                "already runs with them.</li>" +
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

    /**
     * Canonical comparison form of a list value (order-insensitive, de-duplicated):
     * items joined by ';'. The parse is robust to every list delimiter (comma,
     * semicolon, newline) — the {@code delimiter} argument is kept for signature
     * compatibility and no longer controls the split.
     */
    private String normalizeListValue(String value, String delimiter) {
        return String.join(";", listItems(value));
    }

    /**
     * The list properties whose on-disk value is a comma-separated list (the Jetty
     * DoS IP whitelists, Jetty's format). Every other list property is
     * semicolon-separated (the {@code getStringList} format).
     */
    private static boolean isCommaListProperty(String propName) {
        return Props.JETTY_API_DOS_FILTER_IP_WHITELIST.getName().equals(propName)
                || Props.JETTY_P2P_DOS_FILTER_IP_WHITELIST.getName().equals(propName);
    }

    /**
     * Parses a list value into trimmed, de-duplicated, sorted items. Robust to any
     * of the list delimiters (comma, semicolon, newline), so the same items compare
     * equal regardless of which delimiter stored or edits them.
     */
    private static List<String> listItems(String value) {
        if (value == null || value.isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(value.split("[,;\\n]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Ordered parse of a list value into its items (trimmed, non-empty), preserving
     * the stored order and duplicates — used to populate the per-item editor fields
     * so they read exactly as saved. Robust to any list delimiter (comma, semicolon,
     * newline). Unlike {@link #listItems} it does not sort or de-duplicate.
     */
    private static List<String> splitList(String value) {
        if (value == null || value.isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(value.split("[,;\\n]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Serialized form of a list value for SAVING: items joined by the property's
     * on-disk delimiter (',' for the Jetty DoS whitelists, ';' otherwise), so the
     * stored value keeps the exact format the runtime (Jetty / getStringList) expects.
     */
    private String serializeListValue(String value, String propName) {
        return String.join(isCommaListProperty(propName) ? "," : ";", listItems(value));
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

        JComponent extra; // optional extra component rendered after the row's input field
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

    /**
     * Per-item editor for a list-typed property: one single-line field per stored
     * item (each with a trash icon) plus an "add" row (empty field + '+'). Emptying
     * an item's field removes that item; every item is colored by its own state and
     * the row is DIRTY when the item set differs from the saved one. For SECRET
     * lists (the API admin key, the mining passphrases) the editable inputs are
     * masked password fields (the L&F's own reveal eye) and the dropdown
     * entries are masked by default, each with its own eye toggle that reveals
     * / hides that one entry before it is selected (see
     * {@link #secretListItemRenderer}).
     */
    private final class ListEditor extends JPanel {
        private final String propName;
        /**
         * True when this list's items are secrets (API admin key, mining
         * passphrases): the per-item dropdowns then mask every entry and
         * carry the per-entry eye toggle (see
         * {@link #secretListItemRenderer}).
         */
        private final boolean secretItems;
        /** The echo character the secret list inputs mask with (dots). */
        private static final char SECRET_ECHO_CHAR = '\u2022';
        /**
         * The width in px of the painted eye's hit region at the right edge
         * of each secret row — the rendered icon label sits in exactly this
         * area, and a click inside it toggles the reveal instead of
         * selecting the row (see {@link #installSecretRowHooks}).
         */
        private static final int REVEAL_EYE_HIT_WIDTH = 26;
        private final JPanel itemsPanel;
        private final JComboBox<String> addField;
        private final FlatPlusButton addButton;
        private final List<JComboBox<String>> itemFields = new ArrayList<>();
        private int inputColumnWidth;

        /**
         * The row's "?" info button: it sits INSIDE the FIRST item row, right
         * of that row's trash icon (the uniform {@code INPUT_ACTION_GAP}),
         * instead of a separate help column — so it always shares the first
         * item row's line and alignment. The help action is registered by
         * the host row builder ({@link #helpAction}).
         */
        private final HelpButton helpBtn = new HelpButton();
        private Runnable helpAction;

        /**
         * The fixed width of the row's icon columns (the trash icons and the
         * add row's "+" share the SAME column, so every input field and every
         * icon lines up in columns across the item rows and the add row).
         */
        private final int iconColumnWidth;

        /** The item baselines feeding the per-item (and add-row) dropdowns —
         *  rebuilt on every recolor by {@link #refreshItemColors}. Ordered
         *  LISTS in profile order: an item row's dropdown offers the saved /
         *  applied items at THAT row's position (row i ↔ the i-th saved and
         *  i-th applied item) — never the whole lists. */
        private final List<String> savedItems = new ArrayList<>();
        private final List<String> appliedItems = new ArrayList<>();

        /** Invoked on every structural change (add/remove) and item edit. */
        private Runnable onChange;

        ListEditor(String propName, boolean secretItems) {
            super(new BorderLayout());
            this.propName = propName;
            this.secretItems = secretItems;
            setOpaque(false);

            // The "?" info button of the whole list row (it is added to the
            // FIRST item row, right of that row's trash icon).
            helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            helpBtn.setToolTipText("Click for more info");
            helpBtn.addActionListener(e -> {
                if (helpAction != null) {
                    helpAction.run();
                }
            });
            // The trash / plus action columns are fixed-width and shared by
            // every row. Their width includes the uniform input-to-action gap
            // plus the wider icon, so both icons line up without expanding
            // the input column when the first row also carries the help icon.
            // The prototypes mirror the REAL (flat, borderless) row buttons:
            // a trash icon with no margin and no border plus the flat "+",
            // so the measured width is exactly the glyphs' own width (the
            // icon column stays as narrow as the icons are).
            JButton trashProto = new FlatIconButton();
            trashProto.setIcon(IconFontSwing.buildIcon(FontAwesome.TRASH,
                    GuiConstants.getHelpIconSize(), GuiColors.getContrastRed()));
            FlatPlusButton plusProto = new FlatPlusButton();
            plusProto.setIcon(rowPlusIcon());
            iconColumnWidth = Math.max(trashProto.getPreferredSize().width,
                    Math.max(plusProto.getPreferredSize().width, helpBtn.getPreferredSize().width));
            int actionColumnWidth = iconColumnWidth + ConfigurationUtils.INPUT_ACTION_GAP;

            // All item and add-row controls share this one grid, so layout
            // rounding cannot move the plus away from the trash column.
            itemsPanel = new JPanel(new MigLayout("insets 0, fillx",
                    "[grow][" + actionColumnWidth + "px, grow 0]["
                            + actionColumnWidth + "px, grow 0]", "[]"));
            itemsPanel.setOpaque(false);
            add(itemsPanel, BorderLayout.CENTER);

            // The add row: the same editable, state-styled input as the item
            // rows (no plain text box) and a flat "+" that reads exactly like
            // the node-profile toolbar's "create a new profile" button (the
            // thin plus glyph + the rounded rollover highlight of
            // NodePanel#NewProfileButton).
            addField = new JComboBox<>(new String[0]);
            addField.setEditable(true);
            if (secretItems) {
                addField.setEditor(secretComboEditor());
            }
            addField.setPrototypeDisplayValue("Prototype");
            // same input styling (font / border / left margin) as the regular selects
            ConfigurationUtils.styleComboEditor(addField);
            if (secretItems) {
                reserveRevealButtonSpace(addField);
            }
            refreshInputWidths();
            // the baseline entries of the dropdown carry the same per-item
            // state coloring as the item rows' dropdowns (masked + per-entry
            // eye toggle for the secret lists)
            addField.setRenderer(secretItems ? secretListItemRenderer(addField)
                    : listItemRenderer());
            addField.setToolTipText("Type an item, then press '+' (or Enter) to add it");

            addButton = new FlatPlusButton();
            addButton.setIcon(rowPlusIcon());
            addButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addButton.setToolTipText("Add item");

            addButton.addActionListener(e -> addItem(stateAwareComboText(addField)));
            installAddEnterAction();
            rebuildItems();
        }

        private void installAddEnterAction() {
            javax.swing.text.JTextComponent addEditorText =
                    findEditorText((JComponent) addField.getEditor().getEditorComponent());
            if (addEditorText instanceof javax.swing.JTextField textField) {
                String enterAction = "addListItem";
                textField.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), enterAction);
                textField.getActionMap().put(enterAction, new javax.swing.AbstractAction() {
                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent e) {
                        addItem(stateAwareComboText(addField));
                    }
                });
            }
        }

        /** The current items (trimmed, non-empty), in display order. */
        List<String> getItems() {
            return itemFields.stream()
                    .map(NodeConfigurationPanel::stateAwareComboText)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }

        /** Newline-joined form (a neutral separator; {@link #serializeListValue} re-normalizes). */
        String getJoinedValue() {
            return String.join("\n", getItems());
        }

        /** Bulk-load items (e.g. on profile load). Does not fire the change hook. */
        void setItems(List<String> values) {
            isProgrammaticChange = true;
            try {
                itemFields.clear();
                for (String v : values) {
                    itemFields.add(makeItemCombo(v));
                }
                rebuildItems();
            } finally {
                isProgrammaticChange = false;
            }
        }

        /** Append an item (from the "add" row or the PK-conversion dialog). No-op if empty. */
        void addItem(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (value.isEmpty()) {
                return;
            }
            isProgrammaticChange = true;
            try {
                itemFields.add(makeItemCombo(value));
                rebuildItems();
                addField.getEditor().setItem("");
            } finally {
                isProgrammaticChange = false;
            }
            fireChange();
        }

        /** Remove one item row. No-op if the field is not in the list. */
        void removeItem(JComboBox<String> field) {
            if (!itemFields.remove(field)) {
                return;
            }
            isProgrammaticChange = true;
            try {
                rebuildItems();
            } finally {
                isProgrammaticChange = false;
            }
            fireChange();
        }

        /** Recolors every item by its own state (the 2×2 saved/applied matrix)
         *  and rebuilds the per-item (and add-row) dropdown baselines. */
        void refreshItemColors(List<String> saved, List<String> applied) {
            this.savedItems.clear();
            this.savedItems.addAll(saved);
            this.appliedItems.clear();
            this.appliedItems.addAll(applied);
            isProgrammaticChange = true;
            try {
                for (JComboBox<String> combo : itemFields) {
                    rebuildItemComboModel(combo);
                    ValueStatus status = statusOf(stateAwareComboText(combo));
                    combo.setToolTipText(status.tooltip());
                    javax.swing.text.JTextComponent text =
                            findEditorText((JComponent) combo.getEditor().getEditorComponent());
                    if (text != null) {
                        text.setForeground(status.color());
                        text.setToolTipText(status.tooltip());
                    }
                }
                rebuildAddComboModel();
            } finally {
                isProgrammaticChange = false;
            }
        }

        /** The 2×2 membership state of one item line against the saved / applied sets. */
        private ValueStatus statusOf(String line) {
            if (line.isEmpty()) {
                return ValueStatus.SAVED_APPLIED;
            }
            boolean inSaved = savedItems.contains(line);
            boolean inApplied = appliedItems.contains(line);
            if (inSaved && inApplied) {
                return ValueStatus.SAVED_APPLIED;
            }
            if (inSaved) {
                return ValueStatus.SAVED;
            }
            if (inApplied) {
                return ValueStatus.UNSAVED_APPLIED;
            }
            return ValueStatus.UNSAVED;
        }

        /**
         * One item row's EDITABLE combo: the editor text is the item's current
         * value (styled exactly like every other value input) and the dropdown
         * offers the item's OWN baseline values (the saved / applied items at
         * this row's position, colored by their state) so an edited item can
         * be reverted by selecting its baseline back.
         */
        private JComboBox<String> makeItemCombo(String value) {
            JComboBox<String> combo = new JComboBox<>(new String[0]);
            combo.setEditable(true);
            if (secretItems) {
                combo.setEditor(secretComboEditor());
            }
            combo.setPrototypeDisplayValue("Prototype");
            combo.getEditor().setItem(value);
            JComponent editorComponent = (JComponent) combo.getEditor().getEditorComponent();
            // same input styling (font / border / left margin) as the regular selects
            ConfigurationUtils.styleComboEditor(combo);
            if (secretItems) {
                reserveRevealButtonSpace(combo);
            }
            combo.setRenderer(secretItems ? secretListItemRenderer(combo)
                    : listItemRenderer());
            // The editor text IS the value — track its changes through a
            // DocumentListener (the L&F alone is not reliable, the same quirk
            // as the state-aware value combos — see createStateAwareValueCombo).
            javax.swing.text.JTextComponent editorText = findEditorText(editorComponent);
            if (editorText != null) {
                editorText.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                    public void insertUpdate(javax.swing.event.DocumentEvent e) {
                        onItemComboChange(combo);
                    }

                    public void removeUpdate(javax.swing.event.DocumentEvent e) {
                        onItemComboChange(combo);
                    }

                    public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    }
                });
            }
            combo.addActionListener(e -> {
                if (isProgrammaticChange) {
                    return;
                }
                fireChange();
            });
            rebuildItemComboModel(combo);
            setInputWidth(combo);
            return combo;
        }

        private void refreshInputWidths() {
            JComboBox<String> prototype = new JComboBox<>(new String[0]);
            prototype.setEditable(true);
            prototype.setPrototypeDisplayValue("Prototype");
            ConfigurationUtils.styleComboEditor(prototype);
            inputColumnWidth = prototype.getPreferredSize().width;
            setInputWidth(addField);
            itemFields.forEach(this::setInputWidth);
        }

        private void setInputWidth(JComboBox<String> combo) {
            java.awt.Dimension preferred = combo.getPreferredSize();
            java.awt.Dimension minimum = combo.getMinimumSize();
            combo.setPreferredSize(new java.awt.Dimension(inputColumnWidth, preferred.height));
            combo.setMinimumSize(new java.awt.Dimension(inputColumnWidth, minimum.height));
        }

        /**
         * Rebuilds one item combo's dropdown: the item's current value (when
         * non-empty) plus the item's OWN baseline values — the saved item
         * and the applied item the profiles list at this row's position
         * (each kept when it differs from the current one, so an edited
         * item can be reverted by selecting its baseline back).
         */
        private void rebuildItemComboModel(JComboBox<String> combo) {
            if (rebuildingCombos.contains(combo)) {
                return;
            }
            rebuildingCombos.add(combo);
            try {
                String current = stateAwareComboText(combo);
                LinkedHashSet<String> items = new LinkedHashSet<>();
                if (!current.isEmpty()) {
                    items.add(current);
                }
                // ONLY this row's own baseline values: the saved and the
                // applied item listed at this row's POSITION in the profiles
                // (row i ↔ the i-th saved / applied item) — the other rows'
                // items are not this row's baseline (they used to pollute
                // every dropdown with the whole lists).
                int position = itemFields.indexOf(combo);
                addOwnBaseline(items, position, savedItems, current);
                addOwnBaseline(items, position, appliedItems, current);
                // Flagged like the value combos' rebuild: the swap and the
                // restore below touch the editor text, and their document
                // events must not fire the item's change handler (a spurious
                // "cleared input" would delete the row — see
                // rebuildStateAwareComboModel).
                boolean prevFlag = isProgrammaticChange;
                isProgrammaticChange = true;
                try {
                    // Keep the (typed) current value as the editor item before
                    // swapping the model; when it is not among the entries the
                    // selection drops (the editor text stays as typed).
                    String typed = combo.getEditor().getItem() instanceof String s ? s : "";
                    if (!typed.isEmpty()) {
                        combo.setSelectedItem(typed);
                    } else {
                        combo.setSelectedIndex(-1);
                    }
                    combo.setModel(new DefaultComboBoxModel<>(items.toArray(new String[0])));
                    // The model swap makes the L&F reconfigure the editor to
                    // the new model's first entry (a model change selects
                    // index 0) — restore the typed text the swap clobbered.
                    if (!typed.equals(stateAwareComboText(combo))) {
                        combo.getEditor().setItem(typed);
                    }
                } finally {
                    isProgrammaticChange = prevFlag;
                }
            } finally {
                rebuildingCombos.remove(combo);
            }
        }

        /**
         * Adds the row's own baseline at one position (the i-th saved /
         * applied item) when it exists and differs from the current value.
         */
        private void addOwnBaseline(LinkedHashSet<String> items, int position, List<String> baselines, String current) {
            if (position >= 0 && position < baselines.size() && !baselines.get(position).equals(current)) {
                items.add(baselines.get(position));
            }
        }

        /**
         * The add row's dropdown: the baseline items (the saved / applied
         * sets) — a removed item can be re-added by selecting it instead of
         * retyping it.
         */
        private void rebuildAddComboModel() {
            if (rebuildingCombos.contains(addField)) {
                return;
            }
            rebuildingCombos.add(addField);
            try {
                LinkedHashSet<String> items = new LinkedHashSet<>();
                items.addAll(savedItems);
                items.addAll(appliedItems);
                // Flagged like the value combos' rebuild: the swap and the
                // restore below touch the editor text, and their events must
                // not fire the add-row's action (would ADD the clobbered text).
                boolean prevFlag = isProgrammaticChange;
                isProgrammaticChange = true;
                try {
                    // Keep the (typed) text before the swap — the L&F
                    // reconfigures the editor on every model change (would
                    // overwrite the text).
                    String typed = addField.getEditor().getItem() instanceof String s ? s : "";
                    if (!typed.isEmpty()) {
                        addField.setSelectedItem(typed);
                    } else {
                        addField.setSelectedIndex(-1);
                    }
                    addField.setModel(new DefaultComboBoxModel<>(items.toArray(new String[0])));
                    // The model swap makes the L&F reconfigure the editor to
                    // the new model's first entry (a model change selects
                    // index 0) — restore the typed text the swap clobbered.
                    if (!typed.equals(stateAwareComboText(addField))) {
                        addField.getEditor().setItem(typed);
                    }
                } finally {
                    isProgrammaticChange = prevFlag;
                }
            } finally {
                rebuildingCombos.remove(addField);
            }
        }

        /**
         * The per-item dropdown renderer (shared by the item rows' and the
         * add row's combos): EVERY entry is colored by the state of its OWN
         * value — the same 2×2 membership state ({@link #statusOf}) the item
         * editor text uses, so the two can never disagree: in both sets
         * applied (green), in the saved set only saved (yellow), in the
         * applied set only unsaved-applied (gray), in neither unsaved (normal
         * text color).
         */
        private DefaultListCellRenderer listItemRenderer() {
            return new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                        boolean isSelected, boolean cellHasFocus) {
                    if (list != null && index >= 0) {
                        ComboBoxItemToolTips.install(list, item -> item instanceof String text
                                ? statusOf(text).tooltip()
                                : null);
                    }
                    Component c = super.getListCellRendererComponent(
                            list == null ? RENDERER_FALLBACK_LIST : list, value, index,
                            isSelected, cellHasFocus);
                    if (value instanceof String s) {
                        ValueStatus status = statusOf(s);
                        c.setForeground(status.color());
                        ((JComponent) c).setToolTipText(status.tooltip());
                    }
                    return c;
                }
            };
        }

        /**
         * The per-item dropdown renderer for SECRET list editors (the API
         * admin key, the mining passphrases): every entry is MASKED (one
         * echo dot per character) by default, and each row carries its own
         * flat eye icon at its right that reveals / hides THAT ONE entry —
         * the user can read and compare the stored values and pick one, all
         * before selecting anything and WITHOUT the dropdown closing.
         *
         * <p>JDK 25's {@link JList} PAINTS its renderer cells instead of
         * hosting them as child components, so the eye is a painted icon,
         * not an interactive component: the list-level hook (see
         * {@link #installSecretRowHooks}) hit-tests the icon's area and
         * handles clicks there — the eye toggles only the reveal (no row
         * selection, no popup close), while clicking the ROW itself
         * selects the entry as usual. On selection the owning combo's
         * editor mirrors the entry's revealed state at that moment: an
         * entry selected while revealed stays readable, one selected while
         * masked stays hidden (see {@link #syncEditorRevealWithSelection}).
         * Revealing is per entry and is dropped when the popup closes, so
         * a reopened dropdown is masked again.
         */
        private ListCellRenderer<Object> secretListItemRenderer(final JComboBox<String> owner) {
            // The entries revealed in THIS dropdown's rows (per value).
            // Cleared when the popup closes, so a reopened dropdown is
            // masked again.
            final Set<String> revealed = new LinkedHashSet<>();
            final boolean[] hooksInstalled = { false };
            return (list, value, index, isSelected, cellHasFocus) -> {
                if (list != null && index >= 0) {
                    ComboBoxItemToolTips.install(list, item -> item instanceof String text
                            ? statusOf(text).tooltip()
                            : null);
                    // Install the hooks once, on the first render from the
                    // LIVE list (the popup is the list's ancestor;
                    // JComboBox has no public accessor to it).
                    if (!hooksInstalled[0]) {
                        installSecretRowHooks(list, owner, revealed, hooksInstalled);
                    }
                }
                String text = value instanceof String s ? s : String.valueOf(value);
                boolean revealedNow = value instanceof String && revealed.contains(text);
                JPanel cell = new JPanel(new BorderLayout());
                cell.setOpaque(true);
                if (list != null) {
                    cell.setBackground(isSelected
                            ? list.getSelectionBackground()
                            : list.getBackground());
                }
                JLabel label = new JLabel(revealedNow ? text : maskSecret(text));
                label.setBorder(new EmptyBorder(0, 2, 0, 0));
                if (value instanceof String s) {
                    ValueStatus status = statusOf(s);
                    label.setForeground(status.color());
                    label.setToolTipText(status.tooltip());
                }
                cell.add(label, BorderLayout.CENTER);
                // The eye icon at the row's right edge: painted, not a
                // button (the list's hit-tested hook handles its clicks —
                // see installSecretRowHooks and the method javadoc).
                JLabel eye = new JLabel();
                eye.setBorder(new EmptyBorder(0, 2, 0, 6));
                applyRevealEyeState(eye, revealedNow);
                cell.add(eye, BorderLayout.EAST);
                return cell;
            };
        }

        /**
         * The list-level input hook for the secret rows' painted eyes.
         * JDK 25's JList paints the renderer's cells instead of hosting
         * them as child components, so EVERY click on a row — the eye
         * included — is received by the LIST itself. This hook wraps the
         * list's existing (UI-installed) mouse listeners so it runs FIRST:
         * a click inside the eye icon's area toggles the reveal of that
         * one entry only (no row selection, no popup close), while every
         * other click is delegated to the original handler unchanged
         * (normal row selection). It also records the revealed state of
         * the entry selected while the popup is open: when the popup
         * closes, the owning combo's editor mirrors it.
         */
        private void installSecretRowHooks(final JList<?> list, final JComboBox<String> owner,
                                           final Set<String> revealed, final boolean[] hooksInstalled) {
            // Shared per-list state, on the list itself, so the row mouse
            // hook and the popup close hook always agree on one instance
            // even if the renderer (re)installs while the popup is not yet
            // attached.
            list.putClientProperty("secretRowRevealed", revealed);
            list.putClientProperty("secretRowOwner", owner);
            if (list.getClientProperty("secretRowSelChanged") == null) {
                list.putClientProperty("secretRowSelChanged", new boolean[] { false });
                list.putClientProperty("secretRowSelRevealed", new boolean[] { false });
            }
            // The mouse hook wraps the list's UI listener exactly once.
            if (!Boolean.TRUE.equals(list.getClientProperty("secretRowMouseHook"))
                    && list.getMouseListeners().length > 0) {
                list.putClientProperty("secretRowMouseHook", Boolean.TRUE);
                MouseListener[] originals = list.getMouseListeners();
                for (MouseListener original : originals) {
                    list.removeMouseListener(original);
                }
                // A single hook wraps every original listener: an eye click
                // toggles the reveal once and skips them all, while any other
                // click is delegated to all of them (normal row selection).
                list.addMouseListener(secretRowMouseHook(list, originals));
                // Hand cursor over the eye's area, the default cursor over
                // the row's text.
                list.addMouseMotionListener(new MouseMotionAdapter() {
                    @Override
                    public void mouseMoved(MouseEvent e) {
                        list.setCursor(secretRowEyeAt(list, e.getPoint()) >= 0
                                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                : Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                    }
                });
            }
            // The re-mask-on-close hook + editor mirroring: the popup is
            // the list's ancestor (JComboBox has no public accessor to
            // it), so install it from the first live render.
            java.awt.Container ancestor =
                    SwingUtilities.getAncestorOfClass(JPopupMenu.class, list);
            if (ancestor instanceof JPopupMenu popup) {
                if (!Boolean.TRUE.equals(popup.getClientProperty("secretRowPopupHook"))) {
                    popup.putClientProperty("secretRowPopupHook", Boolean.TRUE);
                    popup.addPopupMenuListener(new PopupMenuListener() {
                        @Override
                        public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                            resetSecretRowSelection(list);
                        }

                        @Override
                        public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                            @SuppressWarnings("unchecked")
                            boolean[] selChanged = (boolean[]) list.getClientProperty("secretRowSelChanged");
                            @SuppressWarnings("unchecked")
                            boolean[] selRevealed = (boolean[]) list.getClientProperty("secretRowSelRevealed");
                            @SuppressWarnings("unchecked")
                            JComboBox<String> ow = (JComboBox<String>) list.getClientProperty("secretRowOwner");
                            @SuppressWarnings("unchecked")
                            Set<String> rev = (Set<String>) list.getClientProperty("secretRowRevealed");
                            if (selChanged != null && selRevealed != null && selChanged[0]) {
                                syncEditorRevealWithSelection(ow, selRevealed[0]);
                            }
                            resetSecretRowSelection(list);
                            // A closed dropdown re-masks everything, so the
                            // secrets are only visible while the popup is
                            // open.
                            if (rev != null) {
                                rev.clear();
                            }
                        }

                        @Override
                        public void popupMenuCanceled(PopupMenuEvent e) {
                            @SuppressWarnings("unchecked")
                            Set<String> rev = (Set<String>) list.getClientProperty("secretRowRevealed");
                            if (rev != null) {
                                rev.clear();
                            }
                        }
                    });
                }
                hooksInstalled[0] = true;
            }
        }

        /**
         * The row whose cell (in LIST coordinates) contains the point, or
         * -1. Cell-bounds hit-testing is used instead of
         * JList.locationToIndex: the latter mixes in the list's screen
         * position and needs a laid-out visible rect, while the cell bounds
         * are computed straight from the model — reliable for a posted or a
         * real click.
         */
        private static int secretRowIndex(JList<?> list, Point p) {
            int count = list.getModel().getSize();
            for (int i = 0; i < count; i++) {
                Rectangle b = list.getCellBounds(i, i);
                if (b != null && b.contains(p)) {
                    return i;
                }
            }
            return -1;
        }

        /** The row under the point when it is over that row's eye area, else -1. */
        private static int secretRowEyeAt(JList<?> list, Point p) {
            int idx = secretRowIndex(list, p);
            if (idx < 0) {
                return -1;
            }
            Rectangle b = list.getCellBounds(idx, idx);
            return b != null && p.x >= b.getMaxX() - REVEAL_EYE_HIT_WIDTH ? idx : -1;
        }

        private static void resetSecretRowSelection(JList<?> list) {
            @SuppressWarnings("unchecked")
            boolean[] selChanged = (boolean[]) list.getClientProperty("secretRowSelChanged");
            @SuppressWarnings("unchecked")
            boolean[] selRevealed = (boolean[]) list.getClientProperty("secretRowSelRevealed");
            if (selChanged != null) {
                selChanged[0] = false;
            }
            if (selRevealed != null) {
                selRevealed[0] = false;
            }
        }


        /**
         * Wraps the list's UI mouse listener so a press over a row's eye
         * toggles THAT entry only (no selection, no popup close) while any
         * other press falls through to the original listener unchanged.
         */
        private static MouseListener secretRowMouseHook(final JList<?> list, final MouseListener[] originals) {
            return new MouseListener() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (secretRowEyeAt(list, e.getPoint()) >= 0) {
                        // Toggle THIS entry only and repaint: the list
                        // re-renders its visible rows and the row's text and
                        // icon update. No selection, no close.
                        int idx = secretRowIndex(list, e.getPoint());
                        Object item = idx < 0 ? null : list.getModel().getElementAt(idx);
                        if (item instanceof String text) {
                            @SuppressWarnings("unchecked")
                            Set<String> revealed = (Set<String>) list.getClientProperty("secretRowRevealed");
                            if (revealed != null) {
                                if (!revealed.add(text)) {
                                    revealed.remove(text);
                                }
                                list.repaint();
                            }
                        }
                        return; // consume the press
                    }
                    // A regular row click: record the clicked entry's
                    // revealed state, then behave exactly as the UI would
                    // (select the row, close the popup, ...).
                    int idx = secretRowIndex(list, e.getPoint());
                    if (idx >= 0) {
                        Object item = list.getModel().getElementAt(idx);
                        @SuppressWarnings("unchecked")
                        Set<String> revealed = (Set<String>) list.getClientProperty("secretRowRevealed");
                        @SuppressWarnings("unchecked")
                        boolean[] selChanged = (boolean[]) list.getClientProperty("secretRowSelChanged");
                        @SuppressWarnings("unchecked")
                        boolean[] selRevealed = (boolean[]) list.getClientProperty("secretRowSelRevealed");
                        if (selChanged != null && selRevealed != null) {
                            selChanged[0] = true;
                            selRevealed[0] = revealed != null
                                    && item instanceof String s && revealed.contains(s);
                        }
                    }
                    for (MouseListener original : originals) {
                        original.mousePressed(e);
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (secretRowEyeAt(list, e.getPoint()) < 0) {
                        for (MouseListener original : originals) {
                            original.mouseReleased(e);
                        }
                    }
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (secretRowEyeAt(list, e.getPoint()) < 0) {
                        for (MouseListener original : originals) {
                            original.mouseClicked(e);
                        }
                    }
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    for (MouseListener original : originals) {
                        original.mouseEntered(e);
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    for (MouseListener original : originals) {
                        original.mouseExited(e);
                    }
                }
            };
        }


        /** The masked form of a secret: one echo dot per character (the length stays visible). */
        private static String maskSecret(String secret) {
            StringBuilder dots = new StringBuilder(secret.length());
            for (int i = 0; i < secret.length(); i++) {
                dots.append(SECRET_ECHO_CHAR);
            }
            return dots.toString();
        }

        /**
         * Mirrors the revealed state of the entry selected in the dropdown
         * into the owning combo's editor: an entry selected while REVEALED
         * stays readable in the editor, one selected while MASKED is masked
         * there (hidden by default). The L&F's built-in reveal button (a
         * JToggleButton child of the field, when present) is kept in step
         * with the echo state it was not told about.
         */
        private static void syncEditorRevealWithSelection(JComboBox<String> combo, boolean revealed) {
            JComponent editorComponent = (JComponent) combo.getEditor().getEditorComponent();
            if (!(editorComponent instanceof JPasswordField field)) {
                return;
            }
            field.setEchoChar(revealed ? 0 : SECRET_ECHO_CHAR);
            for (Component child : field.getComponents()) {
                if (child instanceof JToggleButton toggle) {
                    toggle.setSelected(revealed);
                }
            }
        }

        /** The eye's revealed / hidden face (icon + tooltip). */
        private static void applyRevealEyeState(JLabel eye, boolean revealed) {
            eye.setIcon(IconFontSwing.buildIcon(
                    revealed ? FontAwesome.EYE_SLASH : FontAwesome.EYE,
                    GuiConstants.getHelpIconSize(), GuiColors.getHelpIcon()));
            eye.setToolTipText(revealed ? "Hide password" : "Show password");
        }

        /**
         * The EDITOR of one secret list input: a {@link JPasswordField}
         * wrapped as a combo editor. A password field is MASKED by its
         * nature (hidden by default, under every L&F), and the app's L&F
         * (FlatLaf, with the app-wide "PasswordField.showRevealButton"
         * property — see GuiManager#applyDefaultsAfterLaf) gives it its
         * OWN eye button: the very same reveal control every password
         * field of the app gets, so no custom eye is needed here. A
         * JPasswordField IS a JTextField (its subclass), so the shared
         * editor-styling and text-finding helpers keep working unchanged.
         */
        private ComboBoxEditor secretComboEditor() {
            JPasswordField field = new JPasswordField();
            final List<ActionListener> actionListeners = new ArrayList<>();
            // The combo registers action listeners on the editor (the
            // Enter-key action): forward the field's own action events to
            // them, the same way the standard editor does.
            field.addActionListener(e -> {
                for (ActionListener listener : List.copyOf(actionListeners)) {
                    listener.actionPerformed(e);
                }
            });
            return new ComboBoxEditor() {
                @Override
                public void setItem(Object anItem) {
                    field.setText(anItem == null ? "" : String.valueOf(anItem));
                }

                @Override
                public Object getItem() {
                    return field.getText();
                }

                @Override
                public Component getEditorComponent() {
                    return field;
                }

                @Override
                public void selectAll() {
                    field.selectAll();
                }

                @Override
                public void addActionListener(ActionListener listener) {
                    actionListeners.add(listener);
                }

                @Override
                public void removeActionListener(ActionListener listener) {
                    actionListeners.remove(listener);
                }
            };
        }

        /**
         * Gives a secret editor's field room on the RIGHT: the L&F's
         * built-in reveal button sits at the field's right edge, and the
         * shared editor styling (ConfigurationUtils.styleComboEditor)
         * leaves no space for it — widen the right inset so long values
         * never slide under the button (the right inset does not affect
         * the measured height).
         */
        private static void reserveRevealButtonSpace(JComboBox<String> combo) {
            JComponent editorComponent = (JComponent) combo.getEditor().getEditorComponent();
            if (!(editorComponent instanceof JTextField field)) {
                return;
            }
            Border border = field.getBorder();
            if (border instanceof EmptyBorder) {
                Insets insets = border.getBorderInsets(field);
                field.setBorder(BorderFactory.createEmptyBorder(
                        insets.top, insets.left, insets.bottom, insets.right + 28));
            }
        }

        private void onItemComboChange(JComboBox<String> combo) {
            if (isProgrammaticChange) {
                return;
            }
            // DEFERRED refresh: the listener runs inside a document-change
            // notification, and the recolor path touches the editor text (the
            // L&F reconfigures the editor on every model swap) — same rule as
            // onEditorTextChange (no mutating a document in its own
            // notification).
            SwingUtilities.invokeLater(() -> {
                if (stateAwareComboText(combo).isEmpty()) {
                    // "Clearing the string deletes it": an emptied item input is removed.
                    removeItem(combo);
                } else {
                    fireChange();
                }
            });
        }

        private void rebuildItems() {
            itemsPanel.removeAll();
            for (int i = 0; i < itemFields.size(); i++) {
                // "wrap" is mandatory: MigLayout's implicit flow is
                // HORIZONTAL — without it the item rows would sit side by
                // side (a reported regression) instead of stacking one under
                // the other (regression-asserted by the list-editor test).
                addItemRow(itemFields.get(i), i == 0);
            }
            itemsPanel.add(addField, "growx");
            itemsPanel.add(addButton, "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP
                    + ", w " + iconColumnWidth + "!, growx");
            itemsPanel.add(rowColumnSpacer(), "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP + ", wrap");
            itemsPanel.revalidate();
            itemsPanel.repaint();
        }

        /**
         * The rows' "+" glyph: the SAME size as the rows' trash glyph
         * ({@code GuiConstants#getHelpIconSize()}, font-relative), so every
         * row icon of every list editor carries the same size and weight.
         */
        private PlusGlyphIcon rowPlusIcon() {
            return new PlusGlyphIcon(Math.round(GuiConstants.getHelpIconSize()));
        }

        private void addItemRow(JComboBox<String> combo, boolean firstRow) {
            itemsPanel.add(combo, "growx");
            FlatIconButton removeBtn = new FlatIconButton();
            removeBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.TRASH,
                    GuiConstants.getHelpIconSize(), GuiColors.getContrastRed()));
            removeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            removeBtn.setToolTipText("Remove this item");
            removeBtn.addActionListener(e -> removeItem(combo));
            itemsPanel.add(removeBtn, "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP
                    + ", w " + iconColumnWidth + "!, growx");
            if (firstRow) {
                itemsPanel.add(helpBtn, "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP + ", wrap");
            } else {
                itemsPanel.add(rowColumnSpacer(), "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP + ", wrap");
            }
        }

        /**
         * The invisible 0×0 placeholder that reserves a fixed-width icon
         * column of the rows' shared 3-column grid on the rows that carry no
         * icon there (every item row but the first: the help icon column,
         * the add row: both icon columns but the "+" one). Without it the
         * column would collapse and the row's input would be WIDER than the
         * other rows' inputs, the icons would no longer line up in columns
         * and the input-to-icon gaps would no longer be uniform.
         */
        private JComponent rowColumnSpacer() {
            JPanel spacer = new JPanel(null);
            spacer.setOpaque(false);
            spacer.setPreferredSize(new java.awt.Dimension(0, 0));
            return spacer;
        }

        private void fireChange() {
            if (onChange != null) {
                onChange.run();
            }
        }
    }
}
