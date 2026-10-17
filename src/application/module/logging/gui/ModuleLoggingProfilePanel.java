package application.module.logging.gui;

import application.api.ModuleContext;
import application.module.logging.LoggingProfileRepository;
import application.utils.gui.CheckboxGroupPanel;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.ComboBoxItemToolTips;
import application.utils.gui.ComboSearchHighlightRenderer;
import application.utils.gui.FlatIconButton;
import application.utils.gui.FlatPlusButton;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.HelpButton;
import application.utils.gui.HelpDialog;
import application.utils.gui.NewProfileChoiceDialog;
import application.utils.gui.PlusGlyphIcon;
import application.utils.gui.ResponsiveToolbarScrollPane;
import application.utils.gui.SearchMatchLabel;
import application.utils.gui.SearchMatchNavigator;
import application.utils.gui.SearchMatchPanel;
import application.utils.gui.SearchValueHighlight;
import application.utils.logging.ModuleLoggingProvider;
import application.utils.logging.RowState;
import application.utils.logging.RowStateResolver;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.miginfocom.swing.MigLayout;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.LayoutManager2;
import java.awt.Point;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.swing.text.JTextComponent;

/**
 * Generic per-module logging profile panel.
 * <p>
 * Instantiated once per registered logging provider (Node, Database, …) and shown as a tab in
 * the {@link LoggingPanel}. The layout (top to bottom): the icon-only CRUD toolbar
 * (New / Save / Apply / Rename / Delete / Refresh / Reset / Help — the Refresh is the
 * merged reload + refresh action), the search row
 * (the "Show values" row-state filter — the Unsaved / Saved / Applied visibility
 * checkboxes plus the color-legend help button — the profile selector at its natural
 * width DIRECTLY TO THE LEFT of the live row search box, and the reserved host strip),
 * and the "Logger levels" frame — the configured level rows followed by an
 * add-key section (key input on the left; initial-value input and a left-aligned
 * "log level" checkbox below it in the right column, with the flat "+" — the
 * node configuration list editors' plus — beside the input).
 * The log-level rows use a CLASSIC — non-editable — combo over the
 * predefined level list, every other row an EDITABLE combo whose dropdown offers
 * only the row's state-based baselines, every value painted by its own row state).
 * </p>
 * <p>
 * <h3>Row states</h3>
 * Every row carries a derived {@link RowState} with the shared four-state
 * model plus the logging-only Unsaved Init and Saved Init states (see
 * {@link RowStateResolver}): <b>unsaved</b>
 * (differs from saved, applied and init), <b>unsaved applied</b> (differs from
 * saved but matches applied), <b>unsaved init</b> (differs from saved and
 * applied but matches init), <b>saved</b>
 * (equals saved but not init or applied), <b>saved init</b> (equals saved and
 * init but not the applied comparison value) and <b>saved applied</b> (equals
 * saved and applied). The state colors the row
 * (label + editor), feeds the "Show values" visibility filter (one checkbox
 * per state), and — for the dirty states — adds the trailing {@code " *"}
 * star to the label. The
 * live search (enabled by default) matches the label, the key and the
 * displayed value (match bands + chevron/Enter navigation — the node
 * configuration panel's behavior).
 * </p>
 *
 * <h3>Backend</h3>
 * All CRUD operations delegate to the headless {@link LoggingProfileRepository}.
 * This panel has no direct file I/O.
 *
 * @see LoggingPanel
 * @see LoggingProfileRepository
 */
public class ModuleLoggingProfilePanel extends JPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModuleLoggingProfilePanel.class);

    private static final String[] LOG_LEVELS =
            {"SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST", "ALL", "OFF"};

    /**
     * The virtual "Default" profile entry shown at the top of the profile
     * selector. Selecting it loads the application's built-in default values
     * into the editor — it is NOT an on-disk profile file: nothing is read
     * from or written to disk for it, and the reserved sample config on disk
     * ({@code logging-default}) is not listed at all.
     */
    public static final String DEFAULT_PROFILE_ENTRY = "Default";

    /**
     * The ONE uniform gap used between every element of a row (the toolbar
     * buttons, the Profile / Search / filter boxes). The search row carries
     * the node configuration panel's search-row margins (10px left, 5px
     * right, 4px top/bottom) around the strip; within the row the gap is
     * uniform.
     */
    static final int ROW_GAP = 4;

    private final ModuleContext context;
    private final ModuleLoggingProvider provider;
    private final LoggingProfileRepository repo;
    private final String moduleId;

    private JComboBox<String> profileCombo;
    /** key → editor (a CLASSIC — non-editable — {@code JComboBox} over the fixed
     *  {@link #LOG_LEVELS} for log-level rows, an EDITABLE {@code JComboBox} whose
     *  dropdown offers the row's state-based baselines otherwise); insertion order preserved. */
    private final Map<String, JComponent> rowEditors = new LinkedHashMap<>();
    /** key → the human-readable label shown in the editor (may equal the key). */
    private final Map<String, String> rowLabels = new LinkedHashMap<>();
    /** key → the label component currently shown for the row (rebuilt on every full render). */
    private final Map<String, JLabel> rowLabelComponents = new LinkedHashMap<>();
    /** key → the application default value of the row (also the value shown for the virtual "Default" profile). */
    private final Map<String, String> rowDefaults = new LinkedHashMap<>();
    /** key → the SAVED (on-disk) baseline value of the row (kept for the value combos' per-item dropdown coloring). */
    private final Map<String, String> rowSavedValues = new LinkedHashMap<>();
    /** key → the INIT snapshot value of the row (kept for per-item dropdown coloring). */
    private final Map<String, String> rowInitValues = new LinkedHashMap<>();
    /** key → the APPLIED comparison baseline value of the row (kept for per-item dropdown coloring). */
    private final Map<String, String> rowAppliedValues = new LinkedHashMap<>();
    /** The rows whose editor is a log-level combo (the fixed {@link #LOG_LEVELS} dropdown). */
    private final Set<String> levelRowKeys = new HashSet<>();
    /**
     * Guards programmatic editor / model updates (profile loads, baseline
     * model rebuilds): while set, the editor change handlers must NOT run —
     * the L&F fires the combo's own events on model swaps too.
     */
    private boolean isProgrammaticChange;
    /** Keys registered by the host via {@link #addExtraField} — built-in rows, not removable. */
    private final Set<String> hostExtraKeys = new HashSet<>();

    /** The editor row grid; held so rows can be re-rendered for search / dynamic add-key. */
    private JPanel editorGrid;
    /** The add-key row's key input (created ONCE, persistent across re-renders — typed text must survive). */
    private JTextField keyInput;
    /** The add-key row's value cell: holds the text input and the level combo (one visible at a time). */
    private JPanel keyValueCell;
    /** The add-key row's initial-value input (the plain text mode). */
    private JTextField keyValueInput;
    /** The add-key row's initial-value combo (the log-level mode — the classic non-editable level dropdown). */
    private JComboBox<String> keyLevelInput;
    /** The add-key row's value-type checkbox: selected = the added row's value is a log level, unselected = plain text. */
    private JCheckBox keyLevelTypeBox;
    /** The add-key row's flat "+" button (the node-configuration list editors' plus) — the ONE add button for both value kinds. */
    private FlatPlusButton addKeyButton;
    /** The fixed width of the rows' action-icon column (the trash icons and the add row's "+" share it). */
    private int actionColumnWidth;
    /** Live row filter (unified search box); added to the search row by the constructor ({@link #enableSearch()}). */
    private SearchMatchPanel searchPanel;
    /** The search-row filter box: the "Show values" state filter; hosts may add extra controls. */
    private JPanel filterBox;
    /** The search-row box holding the live search box — sits directly LEFT of the profile selector. */
    private JPanel searchBoxHolder;
    /** The shared match-navigation state of the row search (chevron/Enter stepping). */
    private final SearchMatchNavigator searchNav = new SearchMatchNavigator();
    /** The keys of the currently visible rows matching the query (navigation order). */
    private final List<String> searchMatches = new java.util.ArrayList<>();
    /**
     * The original cell renderers of the non-editable combos whose renderer
     * was swapped for a {@link ComboSearchHighlightRenderer} by the search —
     * restored by {@link #clearComboSearchBands()} when the search clears.
     */
    private final Map<JComboBox<?>, ListCellRenderer<?>> originalComboRenderers = new LinkedHashMap<>();
    /** key → the row's derived state (rebuilt by {@link #recomputeRowStates()}). */
    private final Map<String, RowState> rowStates = new LinkedHashMap<>();
    private boolean legacyAppliedSnapshotsCleaned;
    /** Per-profile immutable properties captured when this module editor initializes. */
    private final Map<String, Properties> initProfileValues = new LinkedHashMap<>();

    /**
     * The applied comparison baseline: key → the values associated with the
     * selected applied profile. In the generic module panel it is a dummy
     * comparison reference, not the live logging state of any node.
     */
    private final Map<String, String> appliedValues = new LinkedHashMap<>();

    /**
     * The per-profile in-memory editor state (the workspace): profile name →
     * the full editor dump (all rows, including empty values) held while the
     * user works on another profile. NOT persisted — it lives for the
     * panel's lifetime, so unsaved (dirty) edits survive a profile switch
     * and are restored on the switch back. The virtual "Default" entry is
     * never stored (its content is the immutable built-in defaults).
     */
    private final Map<String, Properties> profileWorkspace = new LinkedHashMap<>();

    /**
     * The profiles whose workspace dump differs from the disk (the dirty
     * set). Drives the profile combo's trailing {@code " *"} star and — on a
     * profile load — whether the workspace (not the disk) is the editor's
     * source. Always a subset of {@link #profileWorkspace}'s keys.
     */
    private final Set<String> dirtyProfiles = new HashSet<>();

    /**
     * The profile the editor currently shows (the last loaded selection;
     * null before the first load). A profile switch persists this
     * profile's dirty editor state into the workspace first.
     */
    private String currentProfileName;

    /**
     * Guards the profile combo's action listener: programmatic selection
     * changes (refresh, rename, delete, create, {@link #selectProfile}) must
     * not trigger a user-style switch.
     */
    private boolean isProgrammaticSelection;

    /**
     * Optional host-provided override of the applied profile name (e.g. the
     * node tab passes its per-node effective profile — the shared module
     * marker is only the fallback). A null supplier (or a null/blank result)
     * falls back to the module marker.
     */
    private java.util.function.Supplier<String> appliedProfileNameSupplier;

    /**
     * The "Show values" state-visibility filter: one checkbox per
     * {@link RowState} plus the color-legend help button, all selected by
     * default (the shared {@link CheckboxGroupPanel} pattern — the same box
     * the node configuration panel's search row carries). Rendered on the
     * search row to the LEFT of the search box (the Search box sits to its
     * right, mirroring the node configuration panel's search row; see
     * {@link #buildStatusFilter()}).
     */
    private final CheckboxGroupPanel statusPanel = new CheckboxGroupPanel("Show values");
    private JCheckBox showUnsavedBox;
    private JCheckBox showUnsavedAppliedBox;
    private JCheckBox showUnsavedInitBox;
    private JCheckBox showSavedBox;
    private JCheckBox showSavedInitBox;
    private JCheckBox showAppliedBox;
    /** The profile currently marked applied for this module (null = fall back to the built-in default). */
    private String appliedProfileName;

    /** Reserved strip for a host-provided control (e.g. the node "link to node profile" checkbox). */
    private JPanel linkStrip;
    /** Help button; created in the constructor, showing the core's structured default help (see {@link #buildDefaultHelpContent()}) until a host overrides it. */
    private JButton helpButton;
    private JButton toolbarNewButton;
    private JButton toolbarCloneButton;
    private JButton toolbarRenameButton;
    private JButton toolbarResetButton;
    private JButton toolbarCopyButton;
    private JButton toolbarRefreshButton;
    private JButton toolbarDeleteButton;

    /**
     * Save / Rename / Delete only make sense for an on-disk profile — the virtual
     * "Default" entry is the application's built-in configuration, so these three
     * buttons are disabled while it is selected (rather than staying active and
     * failing on click).
     */
    private JButton saveButton;
    private JButton applyButton;
    private JButton renameButton;
    private JButton deleteButton;

    // ── Host extension surface (see the set*/enable* methods) ──────────
    private java.util.function.Consumer<String> applyHook;
    /**
     * Optional host override of the Apply confirmation dialog message. The core default is
     * module-centric ("Apply profile 'X' for module 'M'?") — a host whose Apply targets a
     * specific entity (e.g. the node tab applies to a specific NODE PROFILE, and pressing Yes
     * restarts the node) registers a provider so the dialog names that entity and states
     * exactly what pressing Yes will do. {@code null} = the core default message.
     */
    private ApplyConfirmationProvider applyConfirmationProvider;
    /**
     * Help text shown by the Help button. {@code null} means the core shows its own
     * structured default help ({@link #buildDefaultHelpContent()}) in a compact
     * {@link HelpDialog}; a non-null supplier (registered via
     * {@link #setHelpSupplier(java.util.function.Supplier)}) overrides it with host HTML.
     */
    private java.util.function.Supplier<String> helpSupplier;
    /** True while the core's structured default help is in effect (no host supplier registered). */
    private boolean usesDefaultHelp = true;

    /**
     * Creates a generic per-module logging profile editor WITHOUT a host {@link ModuleContext}.
     * The Apply action persists via the repository and then invokes the hook registered through
     * {@link #setApplyHook}; if none is set it only marks the profile applied and informs the user.
     * Use this constructor for host panels that own their own
     * restart/apply behaviour (e.g. the node tab).
     *
     * @param provider the module's logging provider (id, defaults); must not be null
     */
    public ModuleLoggingProfilePanel(ModuleLoggingProvider provider) {
        this(null, provider);
    }

    public ModuleLoggingProfilePanel(ModuleContext context, ModuleLoggingProvider provider) {
        // Defensive icon-font registration (the app registers it at startup in
        // AppearanceModule#init; this covers standalone/test construction).
        IconFontSwing.register(FontAwesome.getIconFont());
        // A 4px content gap matches the configuration tab's spacing between
        // its search row and category content.
        super(new BorderLayout(0, 4));
        this.context = context;
        this.provider = provider;
        this.moduleId = provider.getModuleId();
        this.repo = new LoggingProfileRepository();

        // No outer padding of its own (the node configuration panel has none
        // either): the margins are carried by the rows — the toolbar wrapper's
        // TOOLBAR_INSETS and the search row's border — so both tabs align.
        setBorder(new EmptyBorder(0, 0, 0, 0));
        // BorderLayout accepts exactly ONE component per side: PAGE_START maps
        // to NORTH and PAGE_END to SOUTH, so adding several components to the
        // same side would silently REPLACE each other (0×0, invisible). The
        // entire top area is therefore ONE wrapper, added to NORTH exactly
        // once; the level editor (the "Logger levels" frame) takes CENTER.
        add(buildTopArea(), BorderLayout.NORTH);
        add(buildLevelEditor(), BorderLayout.CENTER);
        // The row search is a core feature: on by default (the method is
        // idempotent, so hosts may still call it for clarity).
        enableSearch();

        // refreshProfileList() also re-reads the applied marker, so the default
        // selection is the module's APPLIED profile (the profile the user applied
        // last) — the applied state is visible the moment the panel opens. With no
        // applied profile (or a stale marker), the virtual "Default" entry (the
        // first combo item) stays selected.
        // The same box-height unification the node configuration panel's search
        // row applies (ConfigurationUtils#unifyTitledBoxHeights — the standard
        // combo height, measured with all search children shown): the two
        // titled boxes of the search row (Search / Show values) stay even when
        // the font / L&F changes. The profile combo lives on the toolbar row
        // (its standard combo height is the same value).
        ConfigurationUtils.unifyTitledBoxHeights(searchPanel, statusPanel);
        refreshProfileList();
        if (profileCombo.getItemCount() > 0 && !preselectModuleAppliedProfile()) {
            selectSilently(0);
            switchProfile();
        }
    }

    /**
     * The panel's entire top area, as ONE wrapper (BorderLayout keeps a single
     * component per side — see the constructor): the icon-only profile action
     * toolbar on the very top (the node/profile/configuration pattern — it
     * replaced the former provider title/description text) with the profile
     * selector combo at its RIGHT end (after the buttons), and directly below
     * it the search row: the "Show values" filter, the live search box, and
     * the reserved host strip.
     */
    private JComponent buildTopArea() {
        // The 5px vgap mirrors the node configuration panel's top area (its
        // north panel is a BorderLayout(0, 5) with the toolbar NORTH and the
        // search row CENTER), so both tabs keep identical toolbar/search
        // row positions.
        JPanel top = new JPanel(new BorderLayout(0, 5));
        top.setOpaque(false);
        top.add(buildActionToolbar(), BorderLayout.NORTH);
        top.add(buildSearchRow(), BorderLayout.CENTER);
        return top;
    }

    /**
     * The search row: ["Show values" filter + live search box + extra
     * filters] [reserved host strip] — ONE left-aligned strip: every box
     * separated by the SAME uniform gap ({@link #ROW_GAP}), every box
     * stretched to the SAME height. The strip carries the node configuration
     * panel's search-row margins (10px left, 5px right, 4px top/bottom), so it sits 10px right of the "Logger levels" frame (the node configuration
     * panel's tab area runs flush, like the rows of its property tabs).
     */
    private JComponent buildSearchRow() {
        // The EXACT structure of the node configuration panel's search row:
        // a left-flow strip of boxes inside the same responsive toolbar
        // scroll wrapper — the wrapper's content insets (0, 10, 0, 5) plus
        // its 4px top/bottom border are the SAME margins the row always had
        // (10px left, 5px right, 4px top/bottom), and when the window narrows
        // past the strip's content width a horizontal scrollbar appears below
        // it (instead of a plain panel clipping the overflow).
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        JPanel strip = new JPanel(new LeftFlow(ROW_GAP));
        strip.setOpaque(false);

        // The row order: the "Show values" state-visibility filter FIRST
        // (buildStatusFilter) and the live search box SECOND (added to its
        // holder by enableSearch). The profile selector is NOT on this row
        // anymore — it sits at the RIGHT end of the toolbar row above
        // (buildActionToolbar), matching the appearance panel's profile row.
        filterBox = new JPanel(new LeftFlow(ROW_GAP));
        filterBox.setOpaque(false);
        strip.add(filterBox);
        buildStatusFilter();

        searchBoxHolder = new JPanel(new LeftFlow(ROW_GAP));
        searchBoxHolder.setOpaque(false);
        strip.add(searchBoxHolder);

        // Reserved host strip (e.g. the node "link to node profile" checkbox).
        linkStrip = new JPanel(new LeftFlow(ROW_GAP));
        linkStrip.setOpaque(false);
        strip.add(linkStrip);
        // The same responsive toolbar scroll wrapper the configuration panel's
        // search row (and the profile toolbar rows) use.
        JScrollPane rowScroll = new ResponsiveToolbarScrollPane(strip, new Insets(0, 10, 0, 5), false);
        rowScroll.setBorder(new EmptyBorder(4, 0, 4, 0));
        row.add(rowScroll, BorderLayout.CENTER);
        return row;
    }

    /**
     * Builds the "Show values" state-visibility filter (rendered on the
     * search row, to the LEFT of the search box — the Search box sits to
     * its right, mirroring the node configuration panel's search row). Each
     * checkbox toggles the visibility of rows in the corresponding
     * {@link RowState}; toggling re-renders the rows.
     */
    private void buildStatusFilter() {
        statusPanel.setChangeListener(button -> reevaluateRowStates());
        // The four shared states use the node configuration panel's model;
        // logging profiles also distinguish both init-matching states.
        showUnsavedBox = statusPanel.addCheckbox("Unsaved", GuiColors.getUnsaved(), true);
        showUnsavedBox.setToolTipText(
                "Show / hide dirty values that differ from the selected profile's saved content, applied "
                        + "baseline, and initialization snapshot.");
        showUnsavedAppliedBox = statusPanel.addCheckbox("Unsaved Applied",
                GuiColors.getUnsavedApplied(), true);
        showUnsavedAppliedBox.setToolTipText(
                "Show / hide the values that differ from the selected profile's saved content but match "
                        + "this view's applied baseline.");
        showUnsavedInitBox = statusPanel.addCheckbox("Unsaved Init", GuiColors.getUnsavedInit(), true);
        showUnsavedInitBox.setToolTipText(
                "Show / hide values that match the profile's initialization snapshot but differ from both "
                        + "the saved profile and this view's applied baseline.");
        showSavedBox = statusPanel.addCheckbox("Saved", GuiColors.getSaved(), true);
        showSavedBox.setToolTipText(
                "Show / hide values saved since module initialization that differ from both the init "
                        + "snapshot and the comparison-only applied baseline.");
        showSavedInitBox = statusPanel.addCheckbox("Saved Init", GuiColors.getSavedInit(), true);
        showSavedInitBox.setToolTipText(
                "Show / hide values that match the saved profile and its initialization snapshot, "
                        + "but differ from this view's applied baseline.");
        showAppliedBox = statusPanel.addCheckbox("Saved Applied", GuiColors.getApplied(), true);
        showAppliedBox.setToolTipText(
                "Show / hide the values that are saved in the selected profile AND match exactly the "
                        + "applied baseline.");
        // The color-legend help button — its legend covers the shared states
        // and both logging-profile init states.
        JButton colorHelpBtn = new HelpButton();
        colorHelpBtn.setToolTipText("Detailed Color Legend");
        colorHelpBtn.addActionListener(e -> showColorLegendHelp());
        statusPanel.add(colorHelpBtn);
        filterBox.add(statusPanel);
    }

    /**
     * The "Show values" box's color-legend help — the same dialog the node
     * configuration panel shows from its legend button: the shared
     * four-state model plus the logging-profile init comparisons.
     */
    private void showColorLegendHelp() {
        String baselineScope = appliedBaselineScopeDescription();
        boolean moduleScope = appliedProfileNameSupplier == null;
        String comparison = moduleScope
                ? "against the selected profile's saved content, its module-init snapshot, and this view's "
                        + "applied comparison baseline. "
                : "against the selected profile's saved content, this editor's initialization snapshot, and "
                        + "this node profile's assigned logging baseline. ";
        String savedDescription = moduleScope
                ? "These values differ from both the module-init snapshot and the applied comparison baseline; "
                        + "they were saved after this module editor initialized."
                : "These values were saved after this editor initialized and differ from this node profile's "
                        + "assigned logging baseline.";
        String savedInitScope = moduleScope
                ? "the module-init snapshot but differ from the module's comparison-only applied reference."
                : "this profile editor's initialization snapshot but differ from this node profile's assigned "
                        + "logging baseline.";
        String savedInitDescription = "<li><b><font color='"
                + ConfigurationUtils.toHex(GuiColors.getSavedInit())
                + "'>\u25A0 Saved Init Values:</font></b> "
                + "These values still match the saved profile and " + savedInitScope + "</li>";
        String unsavedInitDescription = "<li><b><font color='"
                + ConfigurationUtils.toHex(GuiColors.getUnsavedInit())
                + "'>\u25A0 Unsaved Init Values:</font></b> "
                + "These values differ from both the saved profile and applied baseline, but still match "
                + "this profile editor's initialization snapshot.</li>";
        String unsavedDescription = "<li><b><font color='"
                + ConfigurationUtils.toHex(GuiColors.getUnsaved())
                + "'>\u25A0 Unsaved Values:</font></b> "
                + "These values differ from the saved profile, applied baseline, and initialization snapshot.</li>";
        String msg = "<html><body style='width: 350px'>" +
                "<h3>Color Coding Legend</h3>" +
                "<p>The logger rows are color-coded to indicate their current status. Each value is compared " +
                comparison +
                baselineScope + "</p>" +
                "<ul>" +
                unsavedDescription +
                "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getUnsavedApplied())
                + "'>\u25A0 Unsaved Applied Values:</font></b> " +
                "These values differ from the selected profile's saved content but match exactly the " +
                "applied baseline — they are still unsaved edits.</li>" +
                unsavedInitDescription +
                "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getSaved())
                + "'>\u25A0 Saved Values:</font></b> " +
                savedDescription + "</li>" +
                savedInitDescription +
                "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getApplied())
                + "'>\u25A0 Saved and Applied Values:</font></b> " +
                "These values are saved in the profile AND match exactly the applied baseline — the " +
                "profile and applied reference agree.</li>" +
                "</ul>" +
                "</body></html>";
        JOptionPane.showMessageDialog(this, msg, "Color Legend", JOptionPane.INFORMATION_MESSAGE);
    }

    private String appliedBaselineScopeDescription() {
        if (appliedProfileNameSupplier == null) {
            return "In the module logging view the applied state is a dummy comparison reference only: it does "
                    + "not represent or change the logging configuration of any running node profile. Applying "
                    + "a profile uses that profile's module-init values as this reference.";
        }
        return "In this node-profile view it is the logging profile assigned to that node profile.";
    }

    /**
     * The profile selector combo — the RIGHT end of the profile action
     * toolbar row (the former "Profile" titled box with its "Active:" label
     * was removed): it uses the SAME sizing as the appearance panel's
     * profile combo (the same prototype display value plus the shared
     * size-fix), so the two profile selectors look identical — same width,
     * same height and the same uniform gap between the buttons and the
     * combo in both panels.
     */
    private JComboBox<String> buildProfileSelector() {
        profileCombo = new JComboBox<>();
        profileCombo.setRenderer(new ProfileCellRenderer());
        profileCombo.setPrototypeDisplayValue("XXXXXXXXXXXXXXXXXXXX");
        ConfigurationUtils.fixComponentSize(profileCombo);
        profileCombo.addActionListener(e -> {
            if (!isProgrammaticSelection) {
                switchProfile();
            }
        });
        return profileCombo;
    }

    /**
     * Profile combo renderer: the profile currently marked <b>applied</b> for
     * this module is painted in the applied (green) color with a leading check
     * icon, so the active profile is recognizable at a glance. The virtual
     * "Default" entry counts as applied when no explicit profile (or the
     * reserved sample config) is the applied marker.
     * <p>
     * Extends {@link DefaultListCellRenderer} (the L&F-standard cell renderer)
     * rather than a plain {@link JLabel}: the combo measures its size through
     * this renderer, and only the standard renderer measures the L&F-standard
     * cell size — a plain label is a couple of pixels smaller, which would
     * make this combo a different size than the appearance panel's profile
     * selector (the two must look identical).
     */
    private final class ProfileCellRenderer extends DefaultListCellRenderer {
        private final Icon appliedIcon = IconFontSwing.buildIcon(FontAwesome.CHECK,
                GuiConstants.getHelpIconSize(), GuiColors.getApplied());

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            String name = (String) value;
            setIcon(null);
            setText(name);
            // A profile with unsaved (workspace) edits carries the trailing
            // star — the node configuration panel's convention.
            if (name != null && dirtyProfiles.contains(name)) {
                setText(name + " *");
            }
            if (name != null && isAppliedProfile(name)) {
                setIcon(appliedIcon);
                setIconTextGap(4);
                setForeground(isSelected ? list.getSelectionForeground() : GuiColors.getApplied());
            } else {
                setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            }
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            return this;
        }
    }

    /** @return true when the given profile entry is the one currently applied for this module. */
    private boolean isAppliedProfile(String name) {
        if (DEFAULT_PROFILE_ENTRY.equals(name)) {
            return appliedProfileName == null || appliedProfileName.isBlank()
                    || LoggingProfileRepository.RESERVED_PROFILE_NAME.equals(appliedProfileName);
        }
        return name != null && name.equals(appliedProfileName);
    }

    private JComponent buildLevelEditor() {
        // Build the row data model (value-typed: a log level → combo, anything else → text).
        Map<String, String> defaults = provider.getProfile().getDefaults();
        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            addRow(entry.getKey(), entry.getKey(), entry.getValue());
        }
        for (String common : new String[]{"com.zaxxer.hikari", "org.jooq"}) {
            String key = common + ".level";
            if (!rowEditors.containsKey(key)) {
                addRow(key, key, "WARNING");
            }
        }

        // The grid is re-rendered by renderEditorRows(); the initial render happens here.
        editorGrid = new JPanel(new GridLayout(0, 2, 6, 4));
        editorGrid.setOpaque(false);
        buildAddKeyControls();
        renderEditorRows();

        // The live search box lives on the search row ABOVE this frame (the
        // top area, see buildSearchRow) — NOT inside it. It is added to the
        // filter box by enableSearch() (called from the constructor): hiding
        // a panel inside a FlowLayout does not stick (FlowLayout force-shows
        // hidden children), so "enabled" = "present".
        // The row search behaves exactly like the node configuration panel's:
        // it highlights the matching parts, shows the "current/total" counter
        // and the chevron/Enter match navigation, and follows the active match
        // (a stronger band + scroll into view).
        searchPanel = new SearchMatchPanel(
                "Filter the logger rows by label, key, or value (live, case-insensitive, with match navigation)");
        searchPanel.setSearchTextListener(text -> {
            renderEditorRows();
            showActiveSearchMatch(); // a new query follows its first match
        });
        searchPanel.setSearchNavigationListener(this::navigateSearch);
        searchPanel.setEnterListener(() -> navigateSearch(true));
        searchPanel.setChevronsVisible(false);

        // The single outer titled border is the only frame; the frameless
        // scroll pane lets every row, including the add section, remain
        // reachable in a short window.
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setBorder(BorderFactory.createTitledBorder("Logger levels"));
        // Match the configuration category rows' 10px content inset after
        // accounting for the titled frame's own interior inset.
        JPanel gridWrap = new JPanel(new MigLayout("insets 0 5 0 5, fillx", "[grow]", "[]8[]"));
        gridWrap.setOpaque(false);
        gridWrap.add(editorGrid, "growx, top, wrap");
        gridWrap.add(buildAddKeyRowCell(), "growx, top");
        JScrollPane scroll = new JScrollPane(gridWrap);
        // An explicit EMPTY border (not null): the L&F walk of every theme
        // change reinstalls the scroll pane's UI and replaces the border only
        // when it is null (or a UIResource). A null border would therefore get
        // the L&F's default scroll border (a second, inner frame inside the
        // titled panel) on the FIRST theme switch and keep it even after
        // switching back — the borderless state is never restored. The empty
        // border is preserved on every switch, in both directions.
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        wrap.add(scroll, BorderLayout.CENTER);
        return wrap;
    }

    /**
     * Creates the add-key row's controls ONCE (persistent across re-renders —
     * typed text must survive a row re-render): the key input, the initial
     * VALUE input (a plain text field or, while the "log level" checkbox is
     * selected, the non-editable level combo) and the flat "+" button — the
     * node-configuration list editors' plus (no text label, no leading
     * "+" character). The ONE "+" button adds the key with the value kind
     * the checkbox selected.
     */
    private void buildAddKeyControls() {
        keyInput = new JTextField();
        // The SAME unified input metrics as the value fields below (font /
        // border / height / left margin) — the add-key input must read as
        // just another row's input, nothing shorter or taller.
        ConfigurationUtils.styleInputComponent(keyInput);
        keyInput.setToolTipText("Type a key (e.g. com.example.MyApp.level), then press '+' (or Enter) to add it");
        keyInput.addActionListener(e -> addKeyButton.doClick()); // Enter key

        // The key's INITIAL VALUE: two editors share one cell — the plain
        // text input (the default) or, while the "log level" checkbox is
        // selected, the classic non-editable level combo (the same dropdown
        // the log-level rows use).
        keyValueInput = new JTextField();
        ConfigurationUtils.styleInputComponent(keyValueInput);
        keyValueInput.setToolTipText("The key's initial value (plain text)");
        keyValueInput.addActionListener(e -> addKeyButton.doClick()); // Enter key
        keyLevelInput = new JComboBox<>(LOG_LEVELS);
        keyLevelInput.setSelectedItem("INFO");
        keyLevelInput.setToolTipText("The key's initial log level");
        // The JDK this project builds against has NO javax.swing.CardLayout,
        // so the cell sizes its single VISIBLE editor to the whole cell
        // itself (CardLayout semantics, hand-rolled below).
        keyValueCell = new JPanel(null) {
            /**
             * The cell reports the VISIBLE editor's preferred size (the MAX of
             * both would let a taller hidden level combo inflate the row).
             * The visible editor fills the cell (see doLayout), so it renders
             * at exactly the cell's bounds — the same width and height as the
             * level rows' editors beside it.
             */
            @Override
            public Dimension getPreferredSize() {
                return (keyValueInput.isVisible() ? keyValueInput : keyLevelInput).getPreferredSize();
            }

            @Override
            public void doLayout() {
                for (Component child : getComponents()) {
                    if (child.isVisible()) {
                        child.setBounds(0, 0, getWidth(), getHeight());
                    }
                }
            }
        };
        keyValueCell.setOpaque(false);
        // One editor visible at a time; the visible one fills the WHOLE cell,
        // so both states render the editor at exactly the same geometry —
        // the same box the level rows' editors fill.
        keyValueCell.add(keyValueInput);
        keyValueCell.add(keyLevelInput);
        keyValueInput.setVisible(true);
        keyLevelInput.setVisible(false);

        keyLevelTypeBox = new JCheckBox("log level");
        keyLevelTypeBox.setToolTipText(
                "Log level value<br><br>Selected: the key's value is a predefined log level (INFO, FINE, ...); "
                        + "unselected: a plain text value.");
        keyLevelTypeBox.addActionListener(e -> showKeyValueEditor(keyLevelTypeBox.isSelected()));

        addKeyButton = new FlatPlusButton();
        addKeyButton.setIcon(new PlusGlyphIcon(Math.round(GuiConstants.getHelpIconSize())));
        addKeyButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addKeyButton.setToolTipText("Add key");
        addKeyButton.addActionListener(e -> addKeyFromRow());
    }

    /** Shows the value editor of the add-key row the "log level" checkbox selected. */
    private void showKeyValueEditor(boolean level) {
        if (keyValueCell != null) {
            keyValueInput.setVisible(!level);
            keyLevelInput.setVisible(level);
            keyValueCell.revalidate();
        }
    }

    /**
     * Adds the key from the add-key row: the initial value is the level
     * combo's selection while the "log level" checkbox is selected, the
     * typed plain text otherwise. The row's editor kind follows the value
     * itself ({@link #makeEditor}: a level value gets the non-editable
     * level combo, anything else the editable text combo). After adding the
     * key and value inputs reset; the checkbox keeps its state (the mode
     * applies to the next add as well).
     */
    private void addKeyFromRow() {
        String key = keyInput.getText().trim();
        if (key.isEmpty() || rowEditors.containsKey(key)) {
            return;
        }
        String value = keyLevelTypeBox.isSelected()
                ? String.valueOf(keyLevelInput.getSelectedItem())
                : keyValueInput.getText().trim();
        addRow(key, key, value);
        keyInput.setText("");
        keyValueInput.setText("");
        keyLevelInput.setSelectedItem("INFO");
    }

    /**
     * The add-key section sits below the configured rows. The key input is on
     * the left; the initial value and its value-type checkbox are on the right,
     * with the checkbox left-aligned below the input and the add action beside it.
     */
    private JComponent buildAddKeyRowCell() {
        JPanel addSection = new JPanel(new GridLayout(1, 2, 6, 0));
        addSection.setOpaque(false);

        JPanel keyColumn = new JPanel(new MigLayout("insets 0, fillx", "[grow]", "[]2[]2[]"));
        keyColumn.setOpaque(false);
        JLabel keyLabel = new JLabel("Key");
        keyLabel.setLabelFor(keyInput);
        keyColumn.add(keyLabel, "wrap");
        keyColumn.add(keyInput, "growx");

        JPanel valueColumn = new JPanel(new MigLayout("insets 0, fillx",
                "[grow][" + actionColumnWidth() + "px, grow 0]", "[]2[]2[]"));
        valueColumn.setOpaque(false);
        JLabel valueLabel = new JLabel("Initial value");
        valueLabel.setLabelFor(keyValueCell);
        valueColumn.add(valueLabel, "span 2, wrap");
        valueColumn.add(keyValueCell, "growx");
        valueColumn.add(addKeyButton, "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP
                + ", w " + actionColumnWidth() + "!, growx, wrap");
        valueColumn.add(keyLevelTypeBox, "span 2, alignx left");

        addSection.add(keyColumn, "growx");
        addSection.add(valueColumn, "growx");
        return addSection;
    }

    /**
     * The fixed width of the rows' action-icon column: the WIDER of the
     * (flat, borderless) trash icon button and the flat "+" (measured once,
     * from prototypes that mirror the real buttons' exact footprint). Every
     * row shares the column, so the input-to-icon margin is identical on the
     * add row and the level rows and the icons line up in a column.
     */
    private int actionColumnWidth() {
        if (actionColumnWidth == 0) {
            JButton protoTrash = new FlatIconButton();
            protoTrash.setIcon(IconFontSwing.buildIcon(FontAwesome.TRASH,
                    GuiConstants.getHelpIconSize(), GuiColors.getContrastRed()));
            FlatPlusButton protoPlus = new FlatPlusButton();
            protoPlus.setIcon(new PlusGlyphIcon(Math.round(GuiConstants.getHelpIconSize())));
            actionColumnWidth = Math.max(protoTrash.getPreferredSize().width,
                    protoPlus.getPreferredSize().width);
        }
        return actionColumnWidth;
    }

    private void addRow(String label, String key, String defaultValue) {
        String display = (label != null && !label.isBlank()) ? label : key;
        JComponent editor = makeEditor(key, defaultValue);
        rowEditors.put(key, editor);
        rowLabels.put(key, display);
        rowDefaults.put(key, defaultValue == null ? "" : defaultValue);
        if (isLogLevel(defaultValue)) {
            levelRowKeys.add(key);
        }
        addEditorChangeListener(key, editor);
        renderEditorRows();
    }

    /** Registers the "editor changed → re-evaluate derived row state" listener. */
    private void addEditorChangeListener(String key, JComponent editor) {
        if (editor instanceof JComboBox<?> combo) {
            combo.addActionListener(e -> {
                if (isProgrammaticChange) {
                    return;
                }
                handleEditorValueChange(key);
            });
            // The editor text IS the value of an editable combo — track its
            // changes through a DocumentListener (plain typing changes the
            // editor item without firing the combo's action).
            JComponent editorComponent = (JComponent) combo.getEditor().getEditorComponent();
            JTextField textField = editorTextField(editorComponent);
            if (textField != null) {
                textField.getDocument().addDocumentListener(new DocumentListener() {
                    @Override
                    public void insertUpdate(DocumentEvent e) {
                        if (!isProgrammaticChange) {
                            handleEditorValueChange(key);
                        }
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e) {
                        if (!isProgrammaticChange) {
                            handleEditorValueChange(key);
                        }
                    }

                    @Override
                    public void changedUpdate(DocumentEvent e) {
                    }
                });
            }
        } else if (editor instanceof JTextField textField) {
            textField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    handleEditorValueChange(key);
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    handleEditorValueChange(key);
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    handleEditorValueChange(key);
                }
            });
        }
    }

    /**
     * Recomputes the {@link RowState} of every row (no UI updates — the
     * caller re-renders or restyles) with the shared four-state model and
     * the two logging-profile initialization comparisons:
     * <ul>
     * <li><b>Unsaved</b> — the editor value differs from BOTH the selected
     *     profile's saved (on-disk) content and the applied
     *     baseline: a dirty, not yet saved edit (while the virtual Default
     *     entry is selected, anything deviating from the built-in default,
     *     since Default cannot be saved).</li>
     * <li><b>Unsaved Applied</b> — the editor value differs from the saved
     *     content but matches the applied baseline: a dirty edit matching
     *     the current applied reference.</li>
     * <li><b>Unsaved Init</b> — the editor value differs from saved and
     *     applied but still matches the initialization snapshot.</li>
     * <li><b>Saved</b> — the editor value matches the saved content but
     * differs from both the init snapshot and applied baseline.</li>
     * <li><b>Saved Init</b> — the editor value matches saved and the init
     *     snapshot but differs from the applied baseline.</li>
     * <li><b>Saved Applied</b> — the value matches both the saved content
     *     and the applied baseline.</li>
     * </ul>
     * A key the profile does not define falls back to the built-in default
     * for saved, init, and applied references. The virtual Default entry
     * retains the original four-state resolver because it has no profile
     * snapshot. Invoked on every editor value change and
     * before every full re-render.
     */
    protected void recomputeRowStates() {
        rowStates.clear();
        rowSavedValues.clear();
        rowInitValues.clear();
        rowAppliedValues.clear();
        String selected = selectedProfileName();
        boolean selectedIsDefault = DEFAULT_PROFILE_ENTRY.equals(selected);
        Properties selectedProps = selectedIsDefault ? new Properties() : loadProfilePropsSafely(selected);
        Properties selectedInitProps = selectedIsDefault ? new Properties() : initSnapshotFor(selected);

        for (String key : rowKeys()) {
            String value = editorValueOf(key);
            if (value == null) {
                value = "";
            }
            String def = defaultRowValue(key);

            // The saved (on-disk) baseline: the selected profile's content
            // (a key the profile does not define falls back to the built-in
            // default as the reference). For the virtual Default entry the
            // saved content IS the default.
            String saved;
            if (selectedIsDefault) {
                saved = def;
            } else {
                String inProfile = selectedProps.getProperty(key);
                saved = inProfile != null ? inProfile : def;
            }

            String init;
            if (selectedIsDefault) {
                init = def;
            } else {
                String fromInit = selectedInitProps.getProperty(key);
                init = fromInit != null ? fromInit : def;
            }

            // In the generic module panel this is a comparison-only
            // reference. Node-hosted panels use their per-node baseline.
            String applied = appliedValues.get(key);
            if (applied == null) {
                applied = def;
            }

            RowState state = !selectedIsDefault
                    ? RowStateResolver.resolve(value, saved, applied, init)
                    : RowStateResolver.resolve(value, saved, applied);
            rowStates.put(key, state);
            // The baselines stay available for the value combos' per-item
            // dropdown coloring (each entry is judged against THIS row's
            // saved / applied value).
            rowSavedValues.put(key, saved);
            rowInitValues.put(key, init);
            rowAppliedValues.put(key, applied);
        }
    }

    /**
     * Recomputes the derived row states and re-renders the row grid. Use it for
     * <em>structural</em> changes only: profile selection, save/apply/rename/
     * delete/refresh, default reset, filter toggles. The
     * re-render removes and re-adds every row editor, so per-keystroke value
     * changes must go through {@link #handleEditorValueChange(String)} instead,
     * which updates the existing rows in place and keeps the keyboard focus.
     */
    protected void reevaluateRowStates() {
        recomputeRowStates();
        rebuildValueComboModels();
        renderEditorRows();
        updateProfileActionButtons();
    }

    /**
     * @return true when the row's current editor value differs from the
     *         selected profile's saved content — i.e. it has a
     *         pending (unsaved) change (the rows computed as
     *         {@link RowState#UNSAVED}, {@link RowState#UNSAVED_APPLIED}, or
     *         {@link RowState#UNSAVED_INIT} by {@link #recomputeRowStates()} —
     *         all three states are dirty with
     *         respect to the saved content). Such rows are shown with a
     *         trailing {@code " *"} star on their label (the same convention
     *         the node configuration panel uses for its unsaved values).
     */
    protected boolean isRowUnsaved(String key) {
        RowState state = rowStates.get(key);
        return state == RowState.UNSAVED || state == RowState.UNSAVED_APPLIED
                || state == RowState.UNSAVED_INIT;
    }

    /**
     * @return the row label text for the editor grid: the human-readable
     *         label plus the trailing unsaved star when the row has a
     *         pending change.
     */
    private String unsavedLabel(String key) {
        String label = rowLabels.getOrDefault(key, key);
        return isRowUnsaved(key) ? label + " *" : label;
    }

    /**
     * @return the row label tooltip: the raw property key, extended with an
     *         unsaved-change note when the row's value is not yet saved.
     */
    private String unsavedTooltip(String key) {
        RowState state = rowStates.get(key);
        String tooltip = "<html>" + key;
        if (state != null) {
            tooltip += "<br><br>Status: " + stateDisplayName(state);
        }
        if (isRowUnsaved(key)) {
            tooltip += "<br><br>Unsaved change: the value differs from the selected profile's saved content (Save to persist).";
        }
        return tooltip + "</html>";
    }

    /**
     * Handles a single editor value change (per keystroke or combo selection).
     * Deliberately does NOT re-render the grid: removing and re-adding the
     * focused editor makes the follow-up focus request race the AWT focus
     * machinery (the re-added field may not be "showing" yet), and when it
     * loses that race the field silently loses the keyboard focus and every
     * further keystroke is lost. The derived state is therefore applied to the
     * existing row components in place; a full re-render only happens when the
     * structure changed or the row's visibility actually toggled (e.g. a
     * "Show values" filter now hides it).
     */
    private void handleEditorValueChange(String key) {
        recomputeRowStates();
        updateProfileActionButtons(); // Save / Apply availability follows the row states
        JComponent editor = rowEditors.get(key);
        JLabel label = rowLabelComponents.get(key);
        if (editor == null || label == null || label.getParent() == null) {
            reevaluateRowStates();
            return; // structure changed; a full render is needed anyway
        }
        styleRow(key, label, editor);
        // The value change may add or remove the unsaved star — update the
        // visible label in place (no re-render: the grid rebuild would drop
        // the keyboard focus the user is typing into).
        label.setText(unsavedLabel(key));
        label.setToolTipText(unsavedTooltip(key));
        if (!isRowVisible(key)) {
            reevaluateRowStates();
            return; // the row just became hidden by the state filters
        }
        // The value edit can change the match set (a row matching by value
        // appears or drops out) — refresh it in place (no re-render: the
        // grid rebuild would drop the keyboard focus the user is typing in).
        updateSearchMatches(false);
    }

    /**
     * @return true when the row's {@link RowState} is currently shown by the
     *         "Show values" checkboxes (a row without a computed state passes).
     */
    protected boolean isRowVisible(String key) {
        RowState state = rowStates.get(key);
        if (state == null) {
            return true;
        }
        return switch (state) {
            case UNSAVED -> showUnsavedBox.isSelected();
            case UNSAVED_APPLIED -> showUnsavedAppliedBox.isSelected();
            case UNSAVED_INIT -> showUnsavedInitBox.isSelected();
            case SAVED -> showSavedBox.isSelected();
            case SAVED_INIT -> showSavedInitBox.isSelected();
            case SAVED_APPLIED -> showAppliedBox.isSelected();
        };
    }

    /**
     * Colors the row's label and editor with the row state's color (a row
     * without a computed state keeps its default colors). For the editable
     * value combos the EDITOR TEXT is colored as well — a font / L&F change
     * replaces the editor's UI delegate (resetting its foreground to the
     * plain text color), so the state color must be re-applied to the text
     * on every restyle (updateUI → re-render → here).
     */
    protected void styleRow(String key, JLabel label, JComponent editor) {
        RowState state = rowStates.get(key);
        if (state == null) {
            return;
        }
        Color color = stateColor(state);
        label.setForeground(color);
        editor.setForeground(color);
        String tooltip = stateTooltip(state);
        editor.setToolTipText(tooltip);
        if (editor instanceof JComboBox<?> combo && combo.isEditable()) {
            JTextField textField = editorTextField((JComponent) combo.getEditor().getEditorComponent());
            if (textField != null) {
                textField.setForeground(color);
                textField.setToolTipText(tooltip);
            }
        }
    }

    private static String stateTooltip(RowState state) {
        return "Status: " + stateDisplayName(state);
    }

    private static String stateDisplayName(RowState state) {
        return switch (state) {
            case UNSAVED -> "Unsaved";
            case UNSAVED_APPLIED -> "Unsaved Applied";
            case UNSAVED_INIT -> "Unsaved Init";
            case SAVED -> "Saved";
            case SAVED_INIT -> "Saved Init";
            case SAVED_APPLIED -> "Saved Applied";
        };
    }

    /** The (first) text field inside the given component tree (a combo's editor text, may be null). */
    private static JTextField editorTextField(JComponent component) {
        if (component instanceof JTextField textField) {
            return textField;
        }
        if (component instanceof Container) {
            for (Component child : component.getComponents()) {
                if (child instanceof JComponent) {
                    JTextField found = editorTextField((JComponent) child);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    /**
     * The legend color of a row state (the GUI mapping of the headless
     * {@link RowState} — the SAME palette mapping the node profile
     * configuration editor uses for its value states; the palette stays the
     * SSOT for the state colors, read live so a theme change refreshes them).
     */
    private static Color stateColor(RowState state) {
        return switch (state) {
            case UNSAVED -> GuiColors.getUnsaved();
            case UNSAVED_APPLIED -> GuiColors.getUnsavedApplied();
            case UNSAVED_INIT -> GuiColors.getUnsavedInit();
            case SAVED -> GuiColors.getSaved();
            case SAVED_INIT -> GuiColors.getSavedInit();
            case SAVED_APPLIED -> GuiColors.getApplied();
        };
    }

    /**
     * Re-applies the value-state color coding after an appearance change —
     * the requirement: changing the font size, font family or any other
     * appearance setting must never affect the coloring derived from the
     * computed row state. A font / L&F change replaces the UI delegates of
     * every component (and any recreated editor would lose the state
     * foreground), so the row states are re-computed and re-applied with
     * the CURRENT palette colors: when only the font changed the state
     * colors come back untouched, and when the theme changed the state
     * colors are refreshed to the new theme's palette values (the state
     * mapping itself is never lost).
     * <p>
     * (Guarded: this method also runs from the super constructor, before
     * the profile machinery is ready.)
     */
    @Override
    public void updateUI() {
        super.updateUI();
        if (helpButton != null) {
            updateProfileToolbarUI();
        }
        if (keyInput != null && keyValueInput != null) {
            // The L&F walk visits the add-key inputs AFTER this panel, so
            // re-style them once the walk has passed (next EDT pump): their
            // padding was measured at construction, and after a font / theme
            // change it must be recomputed against the CURRENT standard
            // control height — otherwise the text inputs keep the old
            // height while the level combos grow, and the Initial value box
            // would no longer match the Key box's size.
            SwingUtilities.invokeLater(() -> {
                ConfigurationUtils.styleInputComponent(keyInput);
                ConfigurationUtils.styleInputComponent(keyValueInput);
            });
        }
        if (profileCombo != null && editorGrid != null && !rowEditors.isEmpty()) {
            reevaluateRowStates();
            refreshStatusFilterColors();
            // The L&F walk visits the row editors AFTER this panel, and an
            // editable combo's updateUI() REPLACES its editor text field —
            // the state color / status tooltip applied above lands on the
            // old (now detached) field, so the fresh field would paint the
            // plain text color and show no status tooltip on hover (the
            // editable dropdowns of the profile logging tab). Re-apply the
            // row styling once the walk has passed the rows (next EDT pump).
            SwingUtilities.invokeLater(this::reevaluateRowStates);
        }
    }

    /**
     * Re-sets the "Show values" checkbox colors from the CURRENT palette —
     * the state colors are theme-dependent palette values, so a theme
     * change must refresh them (a font-only change is a no-op).
     */
    private void refreshStatusFilterColors() {
        if (showUnsavedBox != null) {
            showUnsavedBox.setForeground(GuiColors.getUnsaved());
            showUnsavedAppliedBox.setForeground(GuiColors.getUnsavedApplied());
            showUnsavedInitBox.setForeground(GuiColors.getUnsavedInit());
            showSavedBox.setForeground(GuiColors.getSaved());
            showSavedInitBox.setForeground(GuiColors.getSavedInit());
            showAppliedBox.setForeground(GuiColors.getApplied());
        }
    }

    protected void renderEditorRows() {
        if (editorGrid == null) {
            return; // construction in progress
        }
        // The re-render removes and re-adds every row editor. If the currently
        // focused editor (e.g. a text field being typed into) is detached, AWT
        // drops the keyboard focus and every following keystroke is lost —
        // "only the first character lands, then nothing" — so capture the
        // focused text component now and restore it (with its caret) after
        // the re-render.
        final Component focusOwner =
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        final JTextComponent focusedText =
                (focusOwner instanceof JTextComponent text && editorGrid.isAncestorOf(text))
                        ? text : null;
        final int caretPosition = focusedText == null ? -1 : focusedText.getCaretPosition();

        String filter = currentFilter();
        editorGrid.removeAll();
        rowLabelComponents.clear();
        for (Map.Entry<String, JComponent> entry : rowEditors.entrySet()) {
            String key = entry.getKey();
            String label = rowLabels.getOrDefault(key, key);
            if (!filter.isEmpty() && !rowMatchesQuery(key, label, filter)) {
                continue;
            }
            if (!isRowVisible(key)) {
                continue;
            }
            // The row shows the (human-readable) label only; the raw property
            // key stays available via the tooltip. (The bracketed <key> text
            // used to be painted after the label — in this narrow two-column
            // grid it read like a stray, broken line, e.g.
            // "<java.util.logging.FileHandler.level>".)
            SearchMatchLabel lbl = new SearchMatchLabel(unsavedLabel(key));
            lbl.setToolTipText(unsavedTooltip(key));
            styleRow(key, lbl, entry.getValue());
            rowLabelComponents.put(key, lbl);
            editorGrid.add(lbl);
            // The value cell: the editor plus the per-row "Remove key" trash
            // icon at the end of the row (the key-specific tooltip says what
            // the icon does; built-in rows carry a disabled icon). The SAME
            // 2-column grid as the add row (fixed-width icon column + the
            // uniform gap), so every input field and icon lines up in
            // columns from the add row down through the level rows.
            JPanel valueCell = new JPanel(new MigLayout("insets 0, fillx",
                    "[grow][" + actionColumnWidth() + "px, grow 0]", "[]"));
            valueCell.setOpaque(false);
            valueCell.add(entry.getValue(), "growx");
            valueCell.add(buildRemoveKeyButton(key), "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP
                    + ", w " + actionColumnWidth() + "!, growx");
            editorGrid.add(valueCell);
        }
        editorGrid.revalidate();
        editorGrid.repaint();

        if (focusedText != null) {
            focusedText.requestFocusInWindow();
            focusedText.setCaretPosition(caretPosition);
        }
        updateSearchMatches(false);
    }

    /**
     * The per-row "Remove key" trash icon (the same flat, content-less red
     * glyph the node/logger configuration panels use for their remove
     * buttons). It is ENABLED for removable rows (the common-logger template
     * rows and the user-added "Add key" rows) and DISABLED for built-in rows
     * (the provider's default template keys and the host-registered extra
     * fields) — the tooltip says which is which.
     */
    private JButton buildRemoveKeyButton(String key) {
        boolean removable = isKeyRemovable(key);
        FlatIconButton removeBtn = new FlatIconButton();
        removeBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.TRASH,
                GuiConstants.getHelpIconSize(), GuiColors.getContrastRed()));
        removeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        removeBtn.setEnabled(removable);
        removeBtn.setToolTipText(removable
                ? "Remove key '" + key + "'<br><br>Removes this logger entry from the editor (unsaved until you Save)."
                : "Built-in row<br><br>This key is part of the module/host template and cannot be removed.");
        removeBtn.addActionListener(e -> removeKeyRow(key));
        return removeBtn;
    }

    /**
     * A row is removable while its key is NOT part of the provider's default
     * template nor a host-registered extra field: the common-logger template
     * rows (Hikari/jOOQ) and the user-added ("Add key") rows can be removed,
     * the built-in rows cannot.
     */
    private boolean isKeyRemovable(String key) {
        return !provider.getProfile().getDefaults().containsKey(key)
                && !hostExtraKeys.contains(key);
    }

    /**
     * Removes an editor row (its key, label, default and current editor) from
     * the panel. The removal takes effect in the editor immediately and is
     * persisted with the next Save (the key is simply absent from the saved
     * profile's properties). Use it for structural changes only.
     */
    private void removeKeyRow(String key) {
        rowEditors.remove(key);
        rowLabels.remove(key);
        rowDefaults.remove(key);
        rowLabelComponents.remove(key);
        reevaluateRowStates();
    }

    /**
     * Recomputes the live row-search state for the current query — the same
     * behavior as the node configuration panel's search:
     * <ul>
     *  <li>the match set is the (host-state-filtered) rows whose label, key,
     *      or value contains the query (case-insensitive) — {@link
     *      #renderEditorRows()} shows exactly these rows while the query is
     *      not empty;</li>
     *  <li>the matching parts get their highlight bands (label/key via the
     *      {@link SearchMatchLabel}, the value's first occurrence via its
     *      document highlighter, and — for a non-editable combo — the selected
     *      value via the swapped {@link ComboSearchHighlightRenderer}); the
     *      ACTIVE match uses the strong palette
     *      color, the others the soft one;</li>
     *  <li>the "current/total" counter and the chevron navigation are shown
     *      while the query has a match (hidden otherwise — an empty query
     *      is a plain list, not a search);</li>
     *  <li>when {@code scrollToActive} is true the active match is also
     *      brought into view (follow). A refresh caused by the user editing
     *      a value must NOT yank the scroll around, so it passes false.</li>
     * </ul>
     * Must run on the EDT.
     */
    private void updateSearchMatches(boolean scrollToActive) {
        if (searchPanel == null) {
            return;
        }
        String query = currentFilter();
        clearValueHighlights();
        clearComboSearchBands();
        if (query.isEmpty()) {
            searchMatches.clear();
            searchNav.setMatchCount(0);
            searchPanel.setChevronsVisible(false);
            searchPanel.setMatchIndicatorText(null);
            return;
        }
        // Match set (row order): the rows matching the query that the host's
        // state filter still shows.
        searchMatches.clear();
        for (String key : rowEditors.keySet()) {
            if (rowMatchesQuery(key, rowLabels.getOrDefault(key, key), query) && isRowVisible(key)) {
                searchMatches.add(key);
            }
        }
        searchNav.setMatchCount(searchMatches.size());
        searchPanel.setChevronsVisible(searchNav.hasMatches());
        String indicator = searchNav.indicator();
        searchPanel.setMatchIndicatorText(indicator.isEmpty() ? null : indicator);
        // The matching part of every matching row (the active one in strong).
        String activeKey = searchNav.hasMatches() ? searchMatches.get(searchNav.activeIndex()) : null;
        for (String key : searchMatches) {
            paintRowMatch(key, rowLabels.getOrDefault(key, key), query, key.equals(activeKey));
        }
        if (scrollToActive && searchNav.hasMatches()) {
            showActiveSearchMatch();
        }
    }

    /**
     * @return true when the query is in the row's visible text: the label,
     *         the key, the value of a text field, or the displayed value of
     *         a combo (its editor text when editable, its selection
     *         otherwise). Non-text inputs without a displayed value
     *         are deliberately NOT searched — only what the user can see
     *         can be a match.
     */
    private boolean rowMatchesQuery(String key, String label, String lowerQuery) {
        if (label.toLowerCase(Locale.ROOT).contains(lowerQuery)
                || key.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        JComponent editor = rowEditors.get(key);
        if (editor instanceof JTextField textField) {
            return textField.getText().toLowerCase(Locale.ROOT).contains(lowerQuery);
        }
        if (editor instanceof JComboBox<?> comboBox) {
            String text = comboBox.isEditable()
                    ? editableComboText(comboBox)
                    : (comboBox.getSelectedItem() == null ? "" : comboBox.getSelectedItem().toString());
            return text.toLowerCase(Locale.ROOT).contains(lowerQuery);
        }
        return false;
    }

    /** The displayed text of an editable combo (its editor text field, the row's actual value). */
    private static String editableComboText(JComboBox<?> combo) {
        JComponent editorComponent = (JComponent) combo.getEditor().getEditorComponent();
        JTextField textField = editorTextField(editorComponent);
        return textField == null ? "" : textField.getText();
    }

    /**
     * Highlights the exact matching part of one row (the same priority as
     * the node configuration panel): the label name wins over the key line;
     * the value's first occurrence gets its document band. The active match
     * uses the strong palette color, the others the soft one.
     */
    private void paintRowMatch(String key, String label, String lowerQuery, boolean active) {
        Color color = active ? GuiColors.getSearchActiveMatch() : GuiColors.getSearchMatch();
        JLabel labelComponent = rowLabelComponents.get(key);
        if (labelComponent instanceof SearchMatchLabel searchLabel) {
            int nameIdx = label.toLowerCase(Locale.ROOT).indexOf(lowerQuery);
            String keyText = searchLabel.getKeyText();
            int keyIdx = keyText == null ? -1 : keyText.toLowerCase(Locale.ROOT).indexOf(lowerQuery);
            if (nameIdx >= 0) {
                searchLabel.setHighlightRange(nameIdx, lowerQuery.length());
                searchLabel.setHighlightColor(color);
            } else if (keyIdx >= 0) {
                searchLabel.setKeyHighlightRange(keyIdx, lowerQuery.length());
                searchLabel.setHighlightColor(color);
            } else {
                searchLabel.clearHighlight();
                searchLabel.clearKeyHighlight();
            }
        }
        JComponent editor = rowEditors.get(key);
        // The value band lives in the row's visible text: a plain text
        // field, or the EDITOR TEXT of an editable value combo.
        JTextField valueField = null;
        if (editor instanceof JTextField textField) {
            valueField = textField;
        } else if (editor instanceof JComboBox<?> combo && combo.isEditable()) {
            valueField = editorTextField((JComponent) combo.getEditor().getEditorComponent());
        }
        if (valueField != null) {
            int idx = valueField.getText().toLowerCase(Locale.ROOT).indexOf(lowerQuery);
            if (idx >= 0) {
                try {
                    valueField.getHighlighter().addHighlight(idx, idx + lowerQuery.length(),
                            active ? SearchValueHighlight.PAINTER_ACTIVE : SearchValueHighlight.PAINTER);
                } catch (BadLocationException ignored) {
                    // the value changed between the lookup and the paint — nothing to band
                }
            }
        } else if (editor instanceof JComboBox<?> comboBox && !comboBox.isEditable()) {
            highlightComboValueMatch(comboBox, lowerQuery, color);
        }
    }

    /**
     * Shows the search-match band behind the selected value of the row's
     * non-editable combo box (a non-editable combo has no text component a
     * document highlight could live in, so its renderer is swapped for a
     * {@link ComboSearchHighlightRenderer} that paints the band) — the same
     * behavior as the node configuration panel's select-value search. Only
     * called when the query matches the selected value; the original
     * renderer is remembered and restored by {@link #clearComboSearchBands()}.
     */
    private void highlightComboValueMatch(JComboBox<?> comboBox, String lowerQuery, Color color) {
        Object selected = comboBox.getSelectedItem();
        if (selected == null) {
            return;
        }
        int idx = selected.toString().toLowerCase(Locale.ROOT).indexOf(lowerQuery);
        if (idx < 0) {
            return;
        }
        if (!originalComboRenderers.containsKey(comboBox)) {
            originalComboRenderers.put(comboBox, comboBox.getRenderer());
        }
        ComboSearchHighlightRenderer bandRenderer;
        if (comboBox.getRenderer() instanceof ComboSearchHighlightRenderer existing) {
            bandRenderer = existing;
        } else {
            bandRenderer = new ComboSearchHighlightRenderer();
            bandRenderer.setDelegateRenderer(comboBox.getRenderer());
            comboBox.setRenderer(bandRenderer);
        }
        bandRenderer.setBand(lowerQuery, color);
        comboBox.repaint();
    }

    /** Restores the original renderer of every combo box that carries a search band. */
    private void clearComboSearchBands() {
        for (Map.Entry<JComboBox<?>, ListCellRenderer<?>> entry : originalComboRenderers.entrySet()) {
            if (entry.getKey().getRenderer() instanceof ComboSearchHighlightRenderer) {
                // The captured wildcard of JComboBox<?> does not line up with
                // setRenderer's bound — the renderer is re-erased at runtime.
                @SuppressWarnings({"unchecked", "rawtypes"})
                ListCellRenderer<Object> original = (ListCellRenderer) entry.getValue();
                entry.getKey().setRenderer(original);
                entry.getKey().repaint();
            }
        }
        originalComboRenderers.clear();
    }

    /** Removes the value-text match bands from every row's value input. */
    private void clearValueHighlights() {
        for (JComponent editor : rowEditors.values()) {
            if (editor instanceof JTextField textField) {
                textField.getHighlighter().removeAllHighlights();
            } else if (editor instanceof JComboBox<?> combo && combo.isEditable()) {
                JTextField textField = editorTextField((JComponent) combo.getEditor().getEditorComponent());
                if (textField != null) {
                    textField.getHighlighter().removeAllHighlights();
                }
            }
        }
    }

    /**
     * Moves the active (strong) highlight to the next/previous match
     * (wrapping) and follows it — the chevron behavior the node
     * configuration panel's search uses. Only a real (non-empty) search
     * supports navigation; an empty query is a no-op.
     */
    private void navigateSearch(boolean next) {
        String query = currentFilter();
        if (query.isEmpty() || !searchNav.hasMatches() || searchMatches.isEmpty()) {
            return;
        }
        String oldKey = searchMatches.get(searchNav.activeIndex());
        searchNav.navigate(next);
        String newKey = searchMatches.get(searchNav.activeIndex());
        paintRowMatch(oldKey, rowLabels.getOrDefault(oldKey, oldKey), query, false);
        paintRowMatch(newKey, rowLabels.getOrDefault(newKey, newKey), query, true);
        searchPanel.setMatchIndicatorText(searchNav.indicator());
        showActiveSearchMatch();
    }

    /**
     * Brings the active match into view (follow): the row's label is
     * scrolled into the grid's viewport — retried for a few event ticks,
     * the same way the node configuration panel does it (after a
     * re-render the viewport gets its size only on the next layout pass).
     */
    private void showActiveSearchMatch() {
        if (!searchNav.hasMatches() || searchMatches.isEmpty()) {
            return;
        }
        JLabel label = rowLabelComponents.get(searchMatches.get(searchNav.activeIndex()));
        if (label == null || label.getParent() == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> revealActiveMatchRow(label, 10));
    }

    /**
     * Scrolls the given row label into the enclosing viewport with the
     * explicit clamp arithmetic (not {@code JViewport#scrollRectToVisible},
     * which can silently do nothing for an unsized/invalid viewport).
     */
    private void revealActiveMatchRow(JComponent rowComponent, int attemptsLeft) {
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
                SwingUtilities.invokeLater(() -> revealActiveMatchRow(rowComponent, attemptsLeft - 1));
            }
            return;
        }
        java.awt.Rectangle bounds = rowComponent.getBounds();
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
    }

    /**
     * The row's editor. A CLASSIC (non-editable) combo wherever the value is
     * chosen from a PREDEFINED list — the log-level rows carry the fixed
     * {@link #LOG_LEVELS} dropdown — and an EDITABLE combo only where there
     * is no predefined value: the editor text is the current value and the
     * dropdown offers only the row's state-based baseline values (the current
     * value + the saved + init + applied values, rebuilt on every state
     * recomputation — see {@link #rebuildValueComboModels()}), exactly the
     * node configuration panel's value-input semantics. EVERY dropdown entry
     * is painted by the state of its OWN value ({@link #valueCellRenderer(String)}).
     */
    private JComponent makeEditor(String key, String defaultValue) {
        String value = defaultValue == null ? "" : defaultValue;
        if (isLogLevel(value)) {
            // The value is chosen from the predefined LOG_LEVELS list — a
            // classic, NON-editable dropdown (no free text entry).
            JComboBox<String> combo = new JComboBox<>(LOG_LEVELS);
            combo.setSelectedItem(canonicalLogLevel(value));
            combo.setRenderer(valueCellRenderer(key));
            return combo;
        }
        // No predefined value list: editable combo, the editor text IS the value.
        JComboBox<String> combo = new JComboBox<>(new String[0]);
        combo.setEditable(true);
        combo.getEditor().setItem(value);
        // the same input styling (font / border / left margin) as every other value input
        ConfigurationUtils.styleComboEditor(combo);
        combo.setRenderer(valueCellRenderer(key));
        return combo;
    }

    /** The canonical (predefined, uppercase) form of a log level for the fixed
     *  {@link #LOG_LEVELS} dropdown's selection (case-insensitive match; the
     *  value itself when no predefined level matches). */
    private static String canonicalLogLevel(String value) {
        for (String level : LOG_LEVELS) {
            if (level.equalsIgnoreCase(value)) {
                return level;
            }
        }
        return value;
    }

    /**
     * The row's dropdown renderer: EVERY entry is painted by the state of
     * ITS OWN value against this row's saved / init / applied baselines.
     * Each state color is applied to the text while preserving the normal
     * dropdown background.
     */
    private ListCellRenderer<String> valueCellRenderer(String key) {
        return (list, value, index, isSelected, cellHasFocus) -> {
            if (list != null && index >= 0) {
                ComboBoxItemToolTips.install(list, item -> item instanceof String text
                        ? stateTooltip(valueState(key, text))
                        : null);
            }
            String text = value == null ? "" : value;
            JLabel cell = new JLabel(text);
            cell.setOpaque(true);
            cell.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            RowState state = valueState(key, text);
            cell.setForeground(stateColor(state));
            cell.setToolTipText(stateTooltip(state));
            return cell;
        };
    }

    private RowState valueState(String key, String value) {
        return !DEFAULT_PROFILE_ENTRY.equals(selectedProfileName())
                ? RowStateResolver.resolve(value,
                        rowSavedValues.getOrDefault(key, ""),
                        rowAppliedValues.getOrDefault(key, ""),
                        rowInitValues.getOrDefault(key, ""))
                : RowStateResolver.resolve(value,
                        rowSavedValues.getOrDefault(key, ""),
                        rowAppliedValues.getOrDefault(key, ""));
    }

    /**
     * Rebuilds the dropdown baselines of the NON-level editable combos (the
     * level rows keep the fixed LOG_LEVELS model): the current value plus
     * the row's saved, init, and applied baselines — an edited value can be
     * reverted by selecting its baseline back. Guarded: the model swap
     * touches the editor text, and its document events must not fire the
     * row's change handler.
     */
    private void rebuildValueComboModels() {
        for (Map.Entry<String, JComponent> entry : rowEditors.entrySet()) {
            if (!levelRowKeys.contains(entry.getKey())
                    && entry.getValue() instanceof JComboBox<?> combo && combo.isEditable()) {
                @SuppressWarnings("unchecked")
                JComboBox<String> valueCombo = (JComboBox<String>) combo;
                rebuildValueComboModel(valueCombo, entry.getKey());
            }
        }
    }

    private void rebuildValueComboModel(JComboBox<String> combo, String key) {
        String current = editorValueOf(key);
        if (current == null) {
            current = "";
        }
        LinkedHashSet<String> items = new LinkedHashSet<>();
        if (!current.isBlank()) {
            items.add(current);
        }
        String saved = rowSavedValues.getOrDefault(key, "");
        String init = rowInitValues.getOrDefault(key, "");
        String applied = rowAppliedValues.getOrDefault(key, "");
        if (!saved.isBlank() && !saved.equals(current)) {
            items.add(saved);
        }
        if (!DEFAULT_PROFILE_ENTRY.equals(selectedProfileName()) && !init.isBlank() && !init.equals(current)) {
            items.add(init);
        }
        if (!applied.isBlank() && !applied.equals(current)) {
            items.add(applied);
        }
        boolean prevFlag = isProgrammaticChange;
        isProgrammaticChange = true;
        try {
            // Keep the (typed) text before the swap — the L&F reconfigures
            // the editor on every model change (would overwrite the text).
            JComponent editorComponent = (JComponent) combo.getEditor().getEditorComponent();
            JTextField textField = editorTextField(editorComponent);
            String typed = textField == null ? "" : textField.getText();
            if (!typed.isEmpty()) {
                combo.setSelectedItem(typed);
            } else {
                combo.setSelectedIndex(-1);
            }
            combo.setModel(new DefaultComboBoxModel<>(items.toArray(new String[0])));
            if (textField != null && !typed.equals(textField.getText())) {
                textField.setText(typed);
            }
        } finally {
            isProgrammaticChange = prevFlag;
        }
    }

    private String currentFilter() {
        return searchPanel == null ? "" : searchPanel.getSearchText().trim().toLowerCase();
    }

    /**
     * The icon-only profile action toolbar (the node/profile/configuration
     * pattern): one glyph per function — no text labels, the tooltip (shown on
     * hover) is the single source of information about each action. The
     * profile selector combo sits at the row's RIGHT end, after the last
     * button (no titled box of its own — see {@link #buildProfileSelector()}).
     */
    private JComponent buildActionToolbar() {
        // The shared profile-action toolbar row (the node/profile/configuration
        // pattern): one glyph per action, the uniform gap and the standard
        // responsive wrapper — identical button size / spacing / margins to
        // the node configuration panel and the node profile toolbar.
        JPanel panel = ConfigurationUtils.createProfileToolbarRow();

        // Icon-only: the tooltip carries the function description (on hover).
        toolbarNewButton = new JButton();
        toolbarNewButton.setToolTipText("<html>New Profile<br><br>Creates a new logging profile initialized "
                + "with the application default values for this module's logger settings.</html>");
        toolbarNewButton.addActionListener(e -> createNewProfile());
        panel.add(toolbarNewButton);

        toolbarCloneButton = new JButton();
        toolbarCloneButton.setToolTipText("<html>Clone Configuration to New Profile<br><br>Creates a new logging "
                + "profile from the selected editor's current values, including unsaved changes. The source "
                + "profile is left untouched.</html>");
        toolbarCloneButton.addActionListener(e -> cloneProfile());
        panel.add(toolbarCloneButton);

        toolbarCopyButton = new JButton();
        toolbarCopyButton.setToolTipText("<html>Copy Existing Configuration to This Profile<br><br>Selects another "
                + "logging profile and loads its values into this editor as unsaved changes. Existing unsaved "
                + "changes are discarded.</html>");
        toolbarCopyButton.addActionListener(e -> copyConfiguration());
        panel.add(toolbarCopyButton);

        toolbarRenameButton = new JButton();
        toolbarRenameButton.setToolTipText("<html>Rename Profile<br><br>Changes the name of the selected "
                + "on-disk logging profile. The built-in Default profile cannot be renamed.</html>");
        toolbarRenameButton.addActionListener(e -> renameProfile());
        panel.add(toolbarRenameButton);

        JButton saveBtn = new JButton();
        saveBtn.setToolTipText("<html>Save<br><br>Saves the current editor state to the selected profile.<br>"
                + "Active only while there are unsaved changes.</html>");
        saveBtn.addActionListener(e -> saveCurrentProfile());
        panel.add(saveBtn);

        JButton applyBtn = new JButton();
        applyBtn.setToolTipText("<html>Apply<br><br>Marks the selected profile as applied for this view. "
                + "In the module logging view this changes only the comparison reference and default "
                + "selection for newly created node profiles; it does not change logging in any running "
                + "node profile.<br>"
                + "Active while the selected profile is not the applied one or there are unsaved or saved, "
                + "not yet applied changes. Apply does not save unsaved editor changes; use Save first.</html>");
        applyBtn.addActionListener(e -> applyProfile());
        panel.add(applyBtn);

        toolbarResetButton = new JButton();
        toolbarResetButton.setToolTipText("<html>Reset to Defaults<br><br>Replaces every logger setting in "
                + "the editor with this module's defaults. The changes are not saved until you choose Save.</html>");
        toolbarResetButton.addActionListener(e -> resetToDefaults());
        panel.add(toolbarResetButton);

        // The MERGED Reload + Refresh action: it re-scans the profiles on
        // disk AND re-reads the selected profile from disk (the standalone
        // Reload button was merged into this one).
        toolbarRefreshButton = new JButton();
        toolbarRefreshButton.setToolTipText("<html>Refresh<br><br>Re-scans the logging profiles on disk and "
                + "re-reads the selected profile<br>"
                + "(discards unsaved edits).</html>");
        toolbarRefreshButton.addActionListener(e -> {
            refreshProfileList();
            discardAndReload();
        });
        panel.add(toolbarRefreshButton);

        toolbarDeleteButton = new JButton();
        toolbarDeleteButton.setToolTipText("<html>Delete<br><br>Permanently deletes the selected on-disk "
                + "logging profile. The built-in Default profile cannot be deleted.</html>");
        toolbarDeleteButton.addActionListener(e -> deleteProfile());
        panel.add(toolbarDeleteButton);

        helpButton = new JButton();
        helpButton.setToolTipText("<html>Help<br><br>Explains logging profiles, logger levels, row colors, "
                + "and the actions available in this toolbar.</html>");
        // Enabled out of the box: the core shows its own structured default help
        // (buildDefaultHelpContent); a host overrides the text via setHelpSupplier.
        helpButton.addActionListener(e -> showHelp());
        panel.add(helpButton);

        // The profile selector at the row's RIGHT end (after the buttons):
        // the former "Profile" titled box was removed — the combo sits at
        // the row's uniform gap (ConfigurationUtils.PROFILE_TOOLBAR_GAP),
        // the SAME geometry the appearance panel's profile selector uses.
        panel.add(buildProfileSelector());

        // Keep references to the buttons whose validity depends on the SELECTED
        // profile / the current row states.
        this.saveButton = saveBtn;
        this.applyButton = applyBtn;
        this.renameButton = toolbarRenameButton;
        this.deleteButton = toolbarDeleteButton;

        // Icon toolbar (same look as the node configuration panel): one glyph
        // per action, sized consistently. (No Reload button: its function is
        // merged into the Refresh one.)
        updateProfileToolbarUI();

        return ConfigurationUtils.wrapProfileToolbarRow(panel);
    }

    private void updateProfileToolbarUI() {
        ConfigurationUtils.configureProfileToolbar(toolbarNewButton, saveButton, applyButton, toolbarRenameButton,
                toolbarDeleteButton, null, toolbarRefreshButton, toolbarResetButton, toolbarCopyButton,
                toolbarCloneButton);
        float iconSize = GuiConstants.getToolBarIconSize();
        ConfigurationUtils.styleProfileIconButton(saveButton, FontAwesome.FLOPPY_O, GuiColors.getSaved(), iconSize);
        ConfigurationUtils.styleProfileIconButton(applyButton, FontAwesome.CHECK_CIRCLE_O, GuiColors.getApplied(),
                iconSize);
        ConfigurationUtils.styleProfileIconButton(toolbarRefreshButton, FontAwesome.REFRESH,
                GuiColors.getButtonIcon(), iconSize);
        ConfigurationUtils.styleProfileIconButton(helpButton, FontAwesome.QUESTION_CIRCLE,
                GuiColors.getButtonIcon(), iconSize);
    }

    // ── Data loading / saving ──────────────────────────────────────────

    private void refreshProfileList() {
        String selected = (String) profileCombo.getSelectedItem();
        List<String> profiles;
        try {
            profiles = repo.listProfiles(moduleId);
        } catch (Exception e) {
            LOGGER.warn("Failed to list profiles for '{}': {}", moduleId, e.getMessage());
            profiles = List.of();
        }
        updateInitSnapshots(profiles);

        // The model rebuild must not trigger the action listener (the
        // guarded, explicit switch happens afterwards, in the caller).
        isProgrammaticSelection = true;
        try {
            // The virtual "Default" entry is listed INSTEAD of the reserved
            // sample config on disk (logging-default): selecting Default uses
            // the application's built-in values and never touches a profile
            // file.
            profileCombo.removeAllItems();
            profileCombo.addItem(DEFAULT_PROFILE_ENTRY);
            for (String name : profiles) {
                profileCombo.addItem(name);
            }
            if (selected != null) {
                for (int i = 0; i < profileCombo.getItemCount(); i++) {
                    if (selected.equals(profileCombo.getItemAt(i))) {
                        profileCombo.setSelectedIndex(i);
                        break;
                    }
                }
            }
        } finally {
            isProgrammaticSelection = false;
        }

        refreshAppliedMarker();
        // Robust even when the selection did not change (the combo's action
        // listener may not fire for a no-op re-selection).
        updateProfileActionButtons();
    }

    private void updateInitSnapshots(List<String> profiles) {
        Set<String> availableProfiles = new HashSet<>(profiles);
        initProfileValues.keySet().retainAll(availableProfiles);
        for (String name : profiles) {
            if (!initProfileValues.containsKey(name)) {
                initProfileValues.put(name, copyProperties(repo.loadProps(moduleId, name)));
            }
        }
    }

    private Properties initSnapshotFor(String profileName) {
        Properties snapshot = initProfileValues.get(profileName);
        if (snapshot == null) {
            snapshot = copyProperties(repo.loadProps(moduleId, profileName));
            initProfileValues.put(profileName, snapshot);
        }
        return copyProperties(snapshot);
    }

    private static Properties copyProperties(Properties properties) {
        Properties copy = new Properties();
        if (properties != null) {
            copy.putAll(properties);
        }
        return copy;
    }

    /**
     * Enables / disables the state-dependent toolbar buttons:
     * <ul>
     *   <li><b>Save</b> — only for an on-disk profile AND while there are
     *       unsaved (dirty) row changes;</li>
     *   <li><b>Apply</b> — for "Default" it restores the built-in defaults
     *       (always meaningful); for an on-disk profile it is usable while
     *       the profile is NOT the applied one (assign it), while there are
     *       saved changes differ from the init/applied comparison references,
     *       or while the editor holds unsaved changes (which Apply does not
     *       save);</li>
     *   <li><b>Rename</b> / <b>Delete</b> — impossible for the virtual
     *       "Default" entry (no file to act on).</li>
     * </ul>
     * New Profile, Clone, Copy, Refresh and Reset stay enabled —
     * they are all meaningful with "Default" selected.
     */
    private void updateProfileActionButtons() {
        if (saveButton == null) {
            return; // toolbar not built yet
        }
        boolean defaultSelected = DEFAULT_PROFILE_ENTRY.equals(profileCombo.getSelectedItem());
        saveButton.setEnabled(!defaultSelected && hasUnsavedRows());
        // Apply is meaningful when the selected profile is not the applied
        // one (assign it — including the "nothing applied yet" case), when
        // saved changes wait for activation, or when the editor holds unsaved
        // changes — Apply then acts as Save and Apply (the editor content is
        // persisted to the selected profile before it is applied).
        Object selected = profileCombo.getSelectedItem();
        boolean selectedIsApplied = !defaultSelected
                && selected != null
                && selected.toString().equals(appliedProfileName);
        applyButton.setEnabled(defaultSelected || !selectedIsApplied || hasUnsavedRows() || hasSavedNotAppliedRows());
        renameButton.setEnabled(!defaultSelected);
        deleteButton.setEnabled(!defaultSelected);
    }

    /**
     * @return {@code true} when at least one row has an unsaved (dirty)
     *         change — the value differs from the selected profile's
     *         saved content (the UNSAVED, UNSAVED_APPLIED, and UNSAVED_INIT states of the
     *         shared four-state model).
     */
    private boolean hasUnsavedRows() {
        for (RowState state : rowStates.values()) {
            if (state == RowState.UNSAVED || state == RowState.UNSAVED_APPLIED
                    || state == RowState.UNSAVED_INIT) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return {@code true} when at least one row differs from the applied
     *         baseline — a saved (SAVED / SAVED_INIT) or dirty (UNSAVED_APPLIED) value
     *         whose applied state is not represented by the current baseline.
     */
    private boolean hasSavedNotAppliedRows() {
        for (RowState state : rowStates.values()) {
            if (state == RowState.SAVED || state == RowState.SAVED_INIT
                    || state == RowState.UNSAVED_APPLIED) {
                return true;
            }
        }
        return false;
    }

    private void loadProfileIntoEditor() {
        String name = (String) profileCombo.getSelectedItem();
        if (name == null) {
            return;
        }
        // The editor's source: the profile's dirty workspace state (the
        // unsaved edits held while the user worked on another profile) wins
        // over the disk content. The virtual "Default" entry has no content
        // of its own (the built-in defaults; nothing is read from or written
        // to disk).
        Properties source;
        if (DEFAULT_PROFILE_ENTRY.equals(name)) {
            source = null;
        } else {
            Properties dirty = dirtyProfiles.contains(name) ? profileWorkspace.get(name) : null;
            if (dirty != null) {
                source = dirty;
            } else {
                try {
                    source = repo.loadProps(moduleId, name);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(this, "Failed to load profile '" + name + "': " + e.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
        }
        // EVERY row gets a value: the source's value when present, otherwise
        // the built-in default — no stale value may survive from another
        // profile (or a previous editor session).
        for (String key : rowKeys()) {
            String value = source == null ? null : source.getProperty(key);
            setEditorValue(rowEditors.get(key), value != null ? value : defaultRowValue(key));
        }
        // The applied baseline is module-level — loading a different profile
        // into the editor does not
        // change it.
        reevaluateRowStates();
        profileCombo.repaint();
    }

    /**
     * Switches the editor to the combo's current selection, persisting the
     * previous selection's unsaved (dirty) editor state into the per-profile
     * workspace first (so it is restored on the switch back). Runs on the EDT.
     */
    private void switchProfile() {
        persistWorkspaceFor(currentProfileName);
        currentProfileName = selectedProfileName();
        loadProfileIntoEditor();
    }

    /**
     * Records the given profile's current editor state in the workspace when
     * it carries unsaved changes (and marks it dirty), or clears both when it
     * is clean. A null or virtual-Default profile is a no-op.
     */
    private void persistWorkspaceFor(String profileName) {
        if (profileName == null || DEFAULT_PROFILE_ENTRY.equals(profileName)) {
            return;
        }
        if (hasUnsavedRows()) {
            profileWorkspace.put(profileName, dumpEditorState());
            dirtyProfiles.add(profileName);
        } else {
            profileWorkspace.remove(profileName);
            dirtyProfiles.remove(profileName);
        }
        profileCombo.repaint();
    }

    /**
     * Like {@link #persistWorkspaceFor(String)} for the currently selected
     * profile (used after in-editor bulk changes: Copy Configuration, Reset).
     */
    private void syncWorkspaceForSelectedProfile() {
        persistWorkspaceFor(selectedProfileName());
    }

    /**
     * Full dump of the editor (all rows, including empty values) — the
     * workspace's storage format. Unlike {@link #collectEditorProps()} (the
     * Save format, which omits empty values), empty values are kept so a
     * cleared row survives a profile switch.
     */
    private Properties dumpEditorState() {
        Properties props = new Properties();
        for (String key : rowEditors.keySet()) {
            String value = editorValueOf(key);
            props.setProperty(key, value == null ? "" : value);
        }
        return props;
    }

    /**
     * Selects the given combo index without triggering the action listener
     * (the guarded switch happens explicitly, in the caller).
     */
    private void selectSilently(int index) {
        isProgrammaticSelection = true;
        try {
            profileCombo.setSelectedIndex(index);
        } finally {
            isProgrammaticSelection = false;
        }
    }

    /** Selects the given combo entry (a no-op when the entry is not in the model). */
    private void selectSilently(String entry) {
        for (int i = 0; i < profileCombo.getItemCount(); i++) {
            if (entry.equals(profileCombo.getItemAt(i))) {
                selectSilently(i);
                return;
            }
        }
    }

    /**
     * Discards the currently shown profile's in-memory (unsaved) editor state
     * and re-reads it from disk — the Reload / Refresh behavior (explicit
     * user-initiated discard, unlike a profile switch, which preserves it).
     */
    private void discardAndReload() {
        String name = currentProfileName;
        if (name != null && !DEFAULT_PROFILE_ENTRY.equals(name)) {
            profileWorkspace.remove(name);
            dirtyProfiles.remove(name);
        }
        loadProfileIntoEditor();
        profileCombo.repaint();
    }

    /**
     * Re-reads the applied profile (never throws): the host-provided override
     * (e.g. the node tab's per-node effective profile) wins, otherwise the
     * shared module marker from the repository. Then re-reads the applied
     * baseline for the resolved profile.
     */
    private void refreshAppliedMarker() {
        if (!legacyAppliedSnapshotsCleaned) {
            repo.cleanupLegacyAppliedSnapshots(moduleId);
            legacyAppliedSnapshotsCleaned = true;
        }
        String overridden = null;
        if (appliedProfileNameSupplier != null) {
            try {
                overridden = appliedProfileNameSupplier.get();
            } catch (Exception e) {
                LOGGER.warn("The applied-profile supplier failed for module '{}': {}", moduleId, e.getMessage());
            }
        }
        if (overridden != null && !overridden.isBlank()) {
            appliedProfileName = overridden;
        } else {
            try {
                appliedProfileName = repo.getApplied(moduleId);
            } catch (Exception e) {
                LOGGER.warn("Failed to read the applied marker for module '{}': {}", moduleId, e.getMessage());
                appliedProfileName = null;
            }
        }
        refreshAppliedBaseline();
    }

    /**
     * Re-reads the applied comparison baseline. A host-provided profile name
     * represents a per-owner assignment (e.g. one node profile), so its own
     * saved profile values are the baseline. The generic module panel uses
     * the selected profile's immutable init values when module-level Apply is
     * used; after startup, its saved values seed the comparison reference.
     * Missing keys fall back to built-in defaults.
     */
    private void refreshAppliedBaseline() {
        appliedValues.clear();
        if (appliedProfileName == null || appliedProfileName.isBlank()) {
            return;
        }
        try {
            Properties baseline = appliedProfileNameSupplier != null
                    ? repo.loadProps(moduleId, appliedProfileName)
                    : repo.loadAppliedBaseline(moduleId, appliedProfileName);
            for (String key : baseline.stringPropertyNames()) {
                String value = baseline.getProperty(key);
                if (value != null) {
                    appliedValues.put(key, value);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to read the applied values for '{}': {}", appliedProfileName, e.getMessage());
        }
    }

    private Properties collectEditorProps() {
        Properties props = new Properties();
        for (Map.Entry<String, JComponent> entry : rowEditors.entrySet()) {
            String value = editorValue(entry.getValue());
            if (value != null && !value.isEmpty()) {
                props.setProperty(entry.getKey(), value);
            }
        }
        return props;
    }

    // ── CRUD actions ───────────────────────────────────────────────────

    private void createNewProfile() {
        // The shared New Profile choice dialog (no setup wizard here — module
        // logging profiles are created as empty, default-initialized profiles).
        NewProfileChoiceDialog.Choice choice = NewProfileChoiceDialog.show(this, false);
        if (choice != NewProfileChoiceDialog.Choice.EMPTY) {
            return;
        }
        Object input = JOptionPane.showInputDialog(this, "New profile name:", "New Empty Profile",
                JOptionPane.PLAIN_MESSAGE, null, null, "my-profile");
        if (input == null) {
            return;
        }
        String name = input.toString().trim();
        if (name.isEmpty() || LoggingProfileRepository.RESERVED_PROFILE_NAME.equals(name)) {
            return;
        }
        createProfileFromDefaults(name);
    }

    /**
     * Creates a new profile initialized with the module's default values (a
     * truly "empty" profile — the current editor state is NOT carried over).
     * Package-private so tests can drive it without the modal dialogs.
     */
    void createProfileFromDefaults(String name) {
        try {
            Properties defaults = new Properties();
            for (Map.Entry<String, JComponent> entry : rowEditors.entrySet()) {
                String d = rowDefaults.get(entry.getKey());
                if (d != null) {
                    defaults.setProperty(entry.getKey(), d);
                }
            }
            repo.create(moduleId, name, defaults);
            refreshProfileList();
            selectSilently(name);
            switchProfile();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Failed to create profile: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Clone Configuration toolbar action: creates a new profile from the
     * current (unsaved, editor) effective state; the source profile is left
     * untouched.
     */
    private void cloneProfile() {
        Object input = JOptionPane.showInputDialog(this, "New profile name:", "Clone Configuration",
                JOptionPane.PLAIN_MESSAGE, null, null, "my-clone");
        if (input == null) {
            return;
        }
        String name = input.toString().trim();
        if (name.isEmpty() || LoggingProfileRepository.RESERVED_PROFILE_NAME.equals(name)) {
            return;
        }
        createProfileFromCurrentState(name);
    }

    /**
     * Copy Configuration toolbar action: opens a dialog to pick a profile
     * (or the virtual "Default") and copies its values into the CURRENT
     * editor as unsaved changes. Unsaved changes in the editor are discarded
     * (with confirmation).
     */
    private void copyConfiguration() {
        List<String> profiles;
        try {
            profiles = repo.listProfiles(moduleId);
        } catch (Exception e) {
            LOGGER.warn("Failed to list profiles for '{}': {}", moduleId, e.getMessage());
            profiles = List.of();
        }
        if (profiles.isEmpty()) {
            JOptionPane.showMessageDialog(this, "There are no other profiles to copy from.",
                    "Copy Configuration", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (hasUnsavedRows()) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "The editor has unsaved changes that will be replaced by the copied values. Continue?",
                    "Copy Configuration", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }
        }
        String current = (String) profileCombo.getSelectedItem();
        Object[] choices = new Object[profiles.size() + 1];
        choices[0] = DEFAULT_PROFILE_ENTRY;
        System.arraycopy(profiles.toArray(), 0, choices, 1, profiles.size());
        Object sel = JOptionPane.showInputDialog(this, "Copy configuration from profile:",
                "Copy Configuration", JOptionPane.QUESTION_MESSAGE, null, choices,
                (current != null && !current.equals(DEFAULT_PROFILE_ENTRY) ? current : choices[0]));
        if (sel == null) {
            return;
        }
        if (DEFAULT_PROFILE_ENTRY.equals(sel)) {
            for (Map.Entry<String, JComponent> entry : rowEditors.entrySet()) {
                String d = rowDefaults.get(entry.getKey());
                if (d != null) {
                    setEditorValue(entry.getValue(), d);
                }
            }
            reevaluateRowStates();
            syncWorkspaceForSelectedProfile();
            return;
        }
        try {
            Properties props = repo.loadProps(moduleId, sel.toString());
            for (Map.Entry<String, JComponent> entry : rowEditors.entrySet()) {
                String value = props.getProperty(entry.getKey());
                if (value != null) {
                    setEditorValue(entry.getValue(), value);
                }
            }
            reevaluateRowStates();
            syncWorkspaceForSelectedProfile();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Failed to copy profile: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Creates a new profile seeded with the editor's CURRENT state — every row's
     * currently set value becomes the new profile's value. Package-private
     * so tests can drive it without the modal name dialog.
     */
    void createProfileFromCurrentState(String name) {
        try {
            repo.create(moduleId, name, collectEditorProps());
            refreshProfileList();
            selectSilently(name);
            switchProfile();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Failed to create profile: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveCurrentProfile() {
        String name = (String) profileCombo.getSelectedItem();
        if (name == null) {
            return;
        }
        if (DEFAULT_PROFILE_ENTRY.equals(name)) {
            JOptionPane.showMessageDialog(this,
                    "The \"Default\" profile is the application's built-in configuration — it cannot be saved.\n"
                            + "Create or select a named profile to persist your changes.",
                    "Default profile", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            Properties props = collectEditorProps();
            repo.saveProps(moduleId, name, props);
            LOGGER.info("Saved profile '{}' for module '{}'", name, moduleId);
            // The editor now matches the disk: the profile is clean again.
            profileWorkspace.remove(name);
            dirtyProfiles.remove(name);
            reevaluateRowStates();
            profileCombo.repaint();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Failed to save profile: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void applyProfile() {
        String name = (String) profileCombo.getSelectedItem();
        if (name == null) {
            return;
        }
        boolean isDefault = DEFAULT_PROFILE_ENTRY.equals(name);
        String message = applyConfirmationProvider != null
                ? applyConfirmationProvider.message(name, isDefault)
                : defaultApplyMessage(name, isDefault);
        boolean optionalRestart = appliedProfileNameSupplier == null && applyHook == null && context != null;
        JCheckBox restartOption = new JCheckBox("Restart application after applying", false);
        Object confirmationContent = optionalRestart
                ? createApplyConfirmationContent(message, restartOption)
                : message;
        int result = JOptionPane.showConfirmDialog(this, confirmationContent,
                "Apply Profile", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            if (isDefault) {
                // Clearing the marker makes the module fall back to the built-in
                // defaults — no profile file is read or written. The node tab
                // (a host with an applied-profile override) records its
                // per-node choice in the apply hook instead.
                if (appliedProfileNameSupplier == null) {
                    repo.setApplied(moduleId, null);
                }
            } else {
                // If restarted, Apply uses the profile's SAVED (on-disk)
                // configuration — it must NOT persist the dirty editor state:
                // Save is what persists, and a dirty value stays UNSAVED after
                // Apply (mirroring the node configuration's apply, which marks
                // the saved values as applied and restarts without saving the
                // dirty editor).
                // The shared module marker is only the GENERIC tab's applied
                // state: the node tab (a host with an applied-profile override)
                // records its per-node assignment in the apply hook, so the
                // marker must not leak across node tabs (the shared green
                // marker defect).
                if (appliedProfileNameSupplier == null) {
                    repo.setApplied(moduleId, name);
                }
                // A host override represents an owner-specific assignment
                // (e.g. one node profile), which must not overwrite the shared
                // module-level comparison reference. The generic module
                // Apply uses the profile's init values, never its later saved
                // values, as that reference.
                if (appliedProfileNameSupplier == null) {
                    repo.rememberAppliedBaseline(moduleId, name, initSnapshotFor(name));
                }
            }
            refreshAppliedMarker();
            reevaluateRowStates();
            if (applyHook != null) {
                applyHook.accept(name);
                // The hook may have persisted the host's applied state (e.g.
                // the node tab's per-node assignment): re-resolve so the
                // marker and the row states reflect it immediately.
                refreshAppliedMarker();
                reevaluateRowStates();
            } else if (context != null) {
                requestRestartAfterApply(optionalRestart, restartOption.isSelected());
            } else if (context == null && applyHook == null) {
                JOptionPane.showMessageDialog(this, "Profile '" + name + "' marked as applied.\n"
                                + "This is the default for newly created node profiles; existing profiles are unchanged.",
                        "Applied", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Failed to apply profile: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    JComponent createApplyConfirmationContent(String message, JCheckBox restartOption) {
        JTextArea messageArea = new JTextArea(message, 12, 52);
        messageArea.setEditable(false);
        messageArea.setFocusable(false);
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);
        messageArea.setOpaque(false);
        messageArea.setBorder(null);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.add(messageArea);
        content.add(Box.createVerticalStrut(8));
        restartOption.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(restartOption);
        return content;
    }

    void requestRestartAfterApply(boolean restartOptionShown, boolean restartSelected) {
        if (context != null && (!restartOptionShown || restartSelected)) {
            context.requestRestart();
        }
    }

    /**
     * The core (module-scoped) Apply confirmation message — a compact, unambiguous
     * three-part statement (the node tab overrides this with a node-centric message):
     * <b>what happens</b> (the module-level default marker is set/reset),
     * <b>the effect on node profiles</b> (only <i>newly created</i> profiles are
     * checked against the module-level applied profile at creation time — their
     * default assignment is bound to it, or left absent when "Default" is applied;
     * <i>existing</i> profiles are entirely untouched: those with their own
     * assignment keep it, and those without an assignment keep running on the
     * built-in default), and <b>when it takes effect</b> (new profiles use the
     * marker when they are created; existing profiles are not affected).
     *
     * @param profileName the profile name being applied (the virtual Default entry included)
     * @param isDefault   whether the virtual {@link #DEFAULT_PROFILE_ENTRY} entry is applied
     * @return the confirmation dialog message
     */
    String defaultApplyMessage(String profileName, boolean isDefault) {
        String defaultName = LoggingProfileRepository.RESERVED_PROFILE_NAME;
        String newlyCreated = isDefault
                ? "Newly created node profiles will have no logging assignment, as before (the built-in '"
                        + defaultName + "' applies to them)"
                : "newly created node profiles will be linked to '" + profileName + "' by default (the module-level "
                        + "applied profile is checked when a node profile is created)";
        String existing = "Existing node profiles are not changed: profiles with their own assignment keep it, and "
                + "profiles without an assignment keep running on the built-in '" + defaultName + "'.";
        return (isDefault
                ? "The module-level applied-profile marker for the '" + moduleId
                        + "' module will be reset to the built-in '"
                        + defaultName + "'.\n\n"
                        + newlyCreated + ".\n\n"
                : "The module-level applied-profile marker for the '" + moduleId
                        + "' module will be set to '"
                        + profileName + "'.\n\n"
                        + newlyCreated + ".\n\n")
                + existing + "\n\n"
                + "This marker is used as the default selection when a node profile is created. The "
                + "module-level applied state is a comparison reference only; it does not change logging for "
                + "any running node profile. Existing node profiles and their assignments are unaffected. "
                + "Restarting the application is optional and can be selected below. Would you like to proceed?";
    }

    private void renameProfile() {
        String oldName = (String) profileCombo.getSelectedItem();
        if (oldName == null || DEFAULT_PROFILE_ENTRY.equals(oldName)) {
            JOptionPane.showMessageDialog(this, "The \"Default\" profile cannot be renamed.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        Object input = JOptionPane.showInputDialog(this, "New name for '" + oldName + "':", "Rename Profile",
                JOptionPane.PLAIN_MESSAGE, null, null, oldName);
        if (input == null) {
            return;
        }
        String newName = input.toString().trim();
        if (newName.isEmpty()) {
            return;
        }
        try {
            // The editor content belongs to the renamed profile from now on:
            // drop the old key from the workspace before the file moves (the
            // switch below re-records the dirty state under the new name).
            profileWorkspace.remove(oldName);
            dirtyProfiles.remove(oldName);
            currentProfileName = newName;
            repo.rename(moduleId, oldName, newName);
            Properties initSnapshot = initProfileValues.remove(oldName);
            if (initSnapshot != null) {
                initProfileValues.put(newName, initSnapshot);
            }
            // refreshProfileList() re-reads the applied marker (the repository
            // follows the rename) before re-loading the editor.
            refreshProfileList();
            selectSilently(newName);
            switchProfile();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Failed to rename profile: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteProfile() {
        String name = (String) profileCombo.getSelectedItem();
        if (name == null || DEFAULT_PROFILE_ENTRY.equals(name)) {
            JOptionPane.showMessageDialog(this, "The \"Default\" profile cannot be deleted.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        int result = JOptionPane.showConfirmDialog(this,
                "Delete profile '" + name + "' for module '" + moduleId + "'?",
                "Delete Profile", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            // The deleted profile's in-memory (unsaved) state is gone with the file.
            profileWorkspace.remove(name);
            dirtyProfiles.remove(name);
            repo.delete(moduleId, name);
            initProfileValues.remove(name);
            refreshProfileList();
            if (profileCombo.getItemCount() > 0) {
                selectSilently(0);
                switchProfile();
            } else {
                currentProfileName = null;
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Failed to delete profile: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── Reset / Help helpers ───────────────────────────────────────────

    private void resetToDefaults() {
        // rowDefaults covers every row (provider defaults, common loggers and
        // host-added extra fields such as the node FileHandler keys).
        for (Map.Entry<String, JComponent> entry : rowEditors.entrySet()) {
            String d = rowDefaults.get(entry.getKey());
            if (d != null) {
                setEditorValue(entry.getValue(), d);
            }
        }
        reevaluateRowStates();
        // Reset may have made the profile clean (or left it dirty): keep the
        // workspace (dirty set) in sync with the editor state.
        syncWorkspaceForSelectedProfile();
    }

    private void showHelp() {
        if (usesDefaultHelp || helpSupplier == null) {
            // The core's own structured default help (compact HelpDialog, palette colors,
            // real toolbar icons) — no host supplier needed.
            HelpDialog.show(this, "Logging Profile Help", buildDefaultHelpContent());
            return;
        }
        String html = helpSupplier.get();
        if (html == null || html.isBlank()) {
            return;
        }
        // Host-supplied HTML help: same compact dialog frame, width-bounded body.
        HelpDialog.showHtml(this, "Logging Profile Help", html);
    }

    /**
     * Builds the core's structured default help content (shown by the Help button when a host
     * does not register its own help via {@link #setHelpSupplier(java.util.function.Supplier)}).
     * Rendered in the compact {@link HelpDialog}: sectioned paragraphs, the row-state legend
     * with the palette's real state colors, and the toolbar actions with their actual icons
     * and colors (the same icon + color pairing as the toolbar itself).
     */
    private JComponent buildDefaultHelpContent() {
        Color iconColor = GuiColors.getButtonIcon();
        JComponent content = HelpDialog.content();
        content.add(HelpDialog.title("Logging Profile Help"));
        content.add(Box.createVerticalStrut(10));
        content.add(HelpDialog.paragraph(
                "This panel edits the logging profile assigned to the current module. Each row is a "
                        + "<b>key/value setting</b> consumed by that module's logging configuration. "
                        + "Logger level rows use the supported level dropdown; other settings accept text. "
                        + "Save writes the profile. Module-level Apply updates only a comparison/default-selection "
                        + "marker and does not change logging for any running node profile. Restart is optional. "
                        + appliedBaselineScopeDescription()));
        content.add(HelpDialog.separator());
        content.add(HelpDialog.heading("Row colors"));
        content.add(HelpDialog.legendRow(GuiColors.getUnsaved(), "Unsaved",
                "The value differs from the selected profile's saved content (a dirty edit). Unsaved rows are "
                        + "marked with a trailing <b>*</b> on their label."));
        content.add(HelpDialog.legendRow(GuiColors.getUnsavedApplied(), "Unsaved Applied",
                "The edited value differs from the selected profile's saved content but matches the applied "
                        + "baseline. It is still an unsaved edit."));
        content.add(HelpDialog.legendRow(GuiColors.getUnsavedInit(), "Unsaved Init",
                "Differs from the saved and applied values but matches the profile editor's initialization "
                        + "snapshot."));
        content.add(HelpDialog.legendRow(GuiColors.getSaved(), "Saved",
                "Saved after module initialization; differs from both the init snapshot and the applied "
                        + "comparison baseline."));
        content.add(HelpDialog.legendRow(GuiColors.getSavedInit(), "Saved Init",
                "Matches the saved profile and its module-init snapshot, but differs from the comparison-only "
                        + "applied reference."));
        content.add(HelpDialog.legendRow(GuiColors.getApplied(), "Applied",
                "The value matches the selected profile's saved content and the applied comparison baseline."));
        content.add(Box.createVerticalStrut(8));
        content.add(HelpDialog.paragraph(
                "The <b>\u201CShow values\u201D</b> checkboxes show or hide rows by state. The "
                        + "<b>Search</b> box matches the logger label, setting key or value. A key's "
                        + "dropdown contains that row's available baseline values; selecting one changes "
                        + "the editor value but does not save it."));
        content.add(HelpDialog.separator());
        content.add(HelpDialog.heading("Toolbar actions"));
        content.add(HelpDialog.actionRow(FontAwesome.FILE_O, iconColor, "New Profile",
                "Creates a new logging profile initialized with this module's default logger settings."));
        content.add(HelpDialog.actionRow(FontAwesome.FLOPPY_O, GuiColors.getSaved(), "Save",
                "Saves the current editor state to the selected profile. "
                        + "Active only while there are unsaved changes."));
        content.add(HelpDialog.actionRow(FontAwesome.CHECK_CIRCLE_O, GuiColors.getApplied(), "Apply",
                "In the module logging view this changes only the comparison/default-selection marker; it does "
                        + "not change the logging configuration of any running node profile. It sets the new "
                        + "profile's init values as the comparison reference. Restart is optional. "
                        + "Active while the selected profile is not the applied one or there are unsaved or "
                        + "saved, not yet applied changes. Apply does not save unsaved editor changes."));
        content.add(HelpDialog.actionRow(FontAwesome.PENCIL_SQUARE_O, iconColor, "Rename",
                "Renames the selected on-disk profile. The built-in Default entry cannot be renamed."));
        content.add(HelpDialog.actionRow(FontAwesome.UNDO, iconColor, "Reset to Defaults",
                "Replaces every editor value with this module's default. The changes remain unsaved until Save."));
        content.add(HelpDialog.actionRow(FontAwesome.CLIPBOARD, iconColor, "Copy Configuration",
                "Copies another profile's values into this editor as unsaved changes; current unsaved edits "
                        + "are discarded."));
        content.add(HelpDialog.actionRow(FontAwesome.FILES_O, iconColor, "Clone Configuration",
                "Creates a new profile from the current (unsaved, editor) effective state."));
        content.add(HelpDialog.actionRow(FontAwesome.REFRESH, iconColor, "Refresh",
                "Re-scans the profiles on disk and re-reads the selected profile (discards unsaved edits)."));
        content.add(HelpDialog.actionRow(FontAwesome.TRASH_O, GuiColors.getContrastRed(), "Delete",
                "Permanently deletes the selected on-disk profile. The built-in Default entry cannot be deleted."));
        content.add(HelpDialog.actionRow(FontAwesome.QUESTION_CIRCLE, iconColor, "Help",
                "Opens this guide to logging settings, row states and profile actions."));
        return content;
    }

    private void setEditorValue(JComponent editor, String value) {
        // Guarded: setting the value touches the editor (the editable combos'
        // document listeners would fire the row's change handler for a
        // programmatic load).
        boolean prev = isProgrammaticChange;
        isProgrammaticChange = true;
        try {
            if (editor instanceof JComboBox<?> combo) {
                combo.setSelectedItem(value);
                if (combo.isEditable()) {
                    // The model event only fires when the selection CHANGES —
                    // loading the already-selected value would leave a typed
                    // editor text untouched. Set the editor text DIRECTLY so
                    // the visible value always equals the loaded one.
                    JComponent editorComponent = (JComponent) combo.getEditor().getEditorComponent();
                    JTextField textField = editorTextField(editorComponent);
                    if (textField != null && !value.equals(textField.getText())) {
                        textField.setText(value);
                    }
                }
            } else if (editor instanceof JTextField textField) {
                textField.setText(value);
            }
        } finally {
            isProgrammaticChange = prev;
        }
    }

    private static String editorValue(JComponent editor) {
        if (editor instanceof JComboBox<?> combo) {
            // The row's value is what is DISPLAYED: for an editable combo the
            // (possibly typed) editor text — the model's selection may lag
            // behind (typed text is not a model entry, and a no-op
            // setSelectedItem leaves the text untouched).
            if (combo.isEditable()) {
                JComponent editorComponent = (JComponent) combo.getEditor().getEditorComponent();
                JTextField textField = editorTextField(editorComponent);
                return textField == null ? "" : textField.getText();
            }
            Object v = combo.getSelectedItem();
            return v == null ? null : v.toString();
        } else if (editor instanceof JTextField textField) {
            return textField.getText();
        }
        return null;
    }

    private static boolean isLogLevel(String value) {
        if (value == null) {
            return false;
        }
        for (String l : LOG_LEVELS) {
            if (l.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    // ── Host extension surface ─────────────────────────────────────────

    /**
     * Adds a configuration row for a key NOT already supplied by the provider's defaults nor
     * present in the selected profile. The row's control type is inferred from
     * {@code defaultValue} (a log level → combo, otherwise → text field). The value is persisted
     * under {@code key} in the profile's {@link java.util.Properties} on save and restored on load.
     *
     * <p><b>Threading:</b> must be called on the EDT.
     *
     * @param key          the {@code Properties} key (e.g. {@code java.util.logging.FileHandler.limit})
     * @param label        the human-readable row label (may be null → use {@code key})
     * @param defaultValue the value shown when the profile does not define the key
     * @return {@code this} for fluent chaining
     * @throws IllegalArgumentException if {@code key} is null/blank
     */
    public ModuleLoggingProfilePanel addExtraField(String key, String label, String defaultValue) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be null/blank");
        }
        hostExtraKeys.add(key); // built-in row: the "Remove key" icon stays disabled
        if (!rowEditors.containsKey(key)) {
            addRow(label, key, defaultValue);
        }
        return this;
    }

    /**
     * Registers a callback invoked AFTER a profile is applied (persisted via the repository's
     * {@code setApplied}); receives the applied profile name. Hosts use it to trigger a restart
     * (e.g. node {@code restartNode}). If none is set, Apply falls back to
     * {@link ModuleContext#requestRestart()} when a context is present, otherwise it only marks
     * the profile applied and informs the user.
     *
     * @param applyHook the apply callback (may be null to clear)
     * @return {@code this} for fluent chaining
     */
    public ModuleLoggingProfilePanel setApplyHook(java.util.function.Consumer<String> applyHook) {
        this.applyHook = applyHook;
        return this;
    }

    /**
     * A host override of the Apply confirmation dialog message. Called (on the EDT) with the
     * selected profile name and whether the virtual "Default" entry is selected, right before
     * the dialog is shown.
     */
    @FunctionalInterface
    public interface ApplyConfirmationProvider {
        String message(String profileName, boolean isDefault);
    }

    /**
     * Registers a host override for the Apply confirmation dialog message (see
     * {@link ApplyConfirmationProvider}). Use it when the core's module-centric default
     * ("Apply profile 'X' for module 'M'?") would be misleading — e.g. the node tab applies
     * to a specific node profile and pressing Yes restarts the node, which the message must
     * state explicitly.
     *
     * @param provider the message provider (may be null to restore the core default)
     * @return {@code this} for fluent chaining
     */
    public ModuleLoggingProfilePanel setApplyConfirmationProvider(ApplyConfirmationProvider provider) {
        this.applyConfirmationProvider = provider;
        return this;
    }

    /**
     * Registers an arbitrary host control rendered in a reserved strip near the toolbar (e.g. the
     * node "link to node profile" checkbox). The core does not inspect or modify the control.
     *
     * @param control the host control (may be null to clear)
     * @return {@code this} for fluent chaining
     */
    public ModuleLoggingProfilePanel setLinkControl(JComponent control) {
        if (linkStrip != null) {
            linkStrip.removeAll();
            if (control != null) {
                linkStrip.add(control);
            }
            linkStrip.revalidate();
            linkStrip.repaint();
        }
        return this;
    }

    /**
     * Registers a supplier of HTML help text shown by the Help button (in the same compact
     * {@link HelpDialog} frame), overriding the core's structured default help. Return
     * {@code null} (or blank) to hide the Help button for this host.
     *
     * @param helpSupplier the help-text supplier (may be null to hide Help)
     * @return {@code this} for fluent chaining
     */
    public ModuleLoggingProfilePanel setHelpSupplier(java.util.function.Supplier<String> helpSupplier) {
        this.helpSupplier = helpSupplier;
        String preview = helpSupplier == null ? null : helpSupplier.get();
        boolean has = preview != null && !preview.isBlank();
        this.usesDefaultHelp = !has;
        if (helpButton != null) {
            helpButton.setEnabled(has);
            helpButton.setVisible(has);
        }
        return this;
    }

    /**
     * Overrides the applied profile name the core resolves from the shared
     * module marker (e.g. the node tab passes its per-node effective
     * profile, so the row-state "applied" baseline and the combo's applied
     * marker follow the node's own assignment). A null supplier (or a
     * null/blank result) falls back to the module marker. Saved Init remains
     * available in either scope; only the applied baseline differs.
     *
     * @param supplier the applied-profile name supplier (may be null)
     * @return {@code this} for fluent chaining
     */
    public ModuleLoggingProfilePanel setAppliedProfileNameSupplier(java.util.function.Supplier<String> supplier) {
        this.appliedProfileNameSupplier = supplier;
        if (showSavedInitBox != null) {
            showSavedBox.setToolTipText(supplier == null
                    ? "Show / hide values saved since module initialization that differ from both the init "
                            + "snapshot and the comparison-only applied baseline."
                    : "Show / hide values saved since this editor initialized that differ from this node profile's "
                            + "assigned logging baseline.");
            showSavedInitBox.setToolTipText(supplier == null
                    ? "Show / hide values that match the saved profile and its module-init snapshot, but differ "
                            + "from the module's comparison-only applied baseline."
                    : "Show / hide values that match the saved profile and this editor's initialization snapshot, "
                            + "but differ from this node profile's assigned logging baseline.");
            statusPanel.revalidate();
            statusPanel.repaint();
        }
        refreshAppliedMarker();
        if (editorGrid != null) {
            reevaluateRowStates();
        }
        return this;
    }

    /**
     * Enables the live, case-insensitive text filter over the key→value editor rows (matches
     * the label, the key and the displayed value, with match navigation). The row search is a
     * core feature: the constructor already calls this method, so every later call is a
     * no-op (kept for hosts that want to make the intent explicit). Clearing the filter
     * shows all rows again.
     *
     * @return {@code this} for fluent chaining
     */
    public ModuleLoggingProfilePanel enableSearch() {
        // Add the search box to the search row (FlowLayout/LeftFlow would
        // force-show a hidden child on the next layout pass, so "enabled" =
        // "present"). It lands in the search box holder — directly to the
        // LEFT of the profile selector (the selector sits at its right).
        if (searchPanel != null && searchPanel.getParent() == null && searchBoxHolder != null) {
            searchBoxHolder.add(searchPanel);
            searchBoxHolder.revalidate();
            searchBoxHolder.repaint();
        }
        return this;
    }

    // ── Subclass surface (protected accessors for hosts) ─────────────────────

    /** @return the currently selected profile entry (may be the virtual {@link #DEFAULT_PROFILE_ENTRY}). */
    protected String selectedProfileName() {
        return (String) profileCombo.getSelectedItem();
    }

    /** @return the profile name currently marked applied for this module (null = built-in default). */
    protected String appliedProfileName() {
        return appliedProfileName;
    }

    /**
     * Pre-selects the module's <b>applied</b> profile in the profile menu: when the
     * applied marker (the shared module marker in {@code conf/{module}/logging/} — or a
     * host-provided override, see {@code setAppliedProfileNameSupplier}) points to an
     * on-disk profile listed in the menu, that entry becomes the default selection,
     * so the applied profile is what the user sees loaded when the panel opens.
     * <p>
     * A missing, blank, reserved ({@code logging-default}) or stale marker (the
     * applied profile was deleted since) selects nothing — the caller then falls
     * back to the virtual {@link #DEFAULT_PROFILE_ENTRY} entry.
     * </p>
     *
     * @return true when the applied profile was selected, false when the caller should
     *         keep the default ("Default") selection
     */
    protected boolean preselectModuleAppliedProfile() {
        String applied = appliedProfileName;
        if (applied == null || applied.isBlank()
                || DEFAULT_PROFILE_ENTRY.equals(applied)
                || LoggingProfileRepository.RESERVED_PROFILE_NAME.equals(applied)) {
            return false;
        }
        return selectProfile(applied);
    }

    /**
     * Programmatically selects the given profile entry and loads it into the
     * editor, preserving the previous selection's unsaved (dirty) state in
     * the per-profile workspace (the plain combo action listener is guarded
     * for programmatic changes, so the switch is explicit here). Used by
     * hosts (e.g. {@code NodeLoggingPanel}) to pre-select the applied
     * profile on construction.
     *
     * @param name the profile entry to select (an on-disk profile name present in the menu)
     * @return true when the entry was found, selected and loaded; false for a null/blank
     *         name or an entry that is not in the menu (the selection is left untouched)
     */
    protected boolean selectProfile(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        for (int i = 0; i < profileCombo.getItemCount(); i++) {
            if (name.equals(profileCombo.getItemAt(i))) {
                selectSilently(i);
                switchProfile();
                return true;
            }
        }
        return false;
    }

    /** @return the application default value of the given row key (never null). */
    protected String defaultRowValue(String key) {
        String d = rowDefaults.get(key);
        return d != null ? d : "";
    }

    /** @return the current editor value of the given row key (null if no such row). */
    protected String editorValueOf(String key) {
        JComponent editor = rowEditors.get(key);
        return editor == null ? null : editorValue(editor);
    }

    /** @return the keys of all editor rows (insertion order preserved). */
    protected java.util.Set<String> rowKeys() {
        return rowEditors.keySet();
    }

    /** Safely loads the given profile from disk (empty Properties for the virtual Default entry or on failure). */
    protected Properties loadProfilePropsSafely(String profileName) {
        if (profileName == null || DEFAULT_PROFILE_ENTRY.equals(profileName)) {
            return new Properties();
        }
        try {
            return repo.loadProps(moduleId, profileName);
        } catch (Exception e) {
            LOGGER.warn("Failed to load profile '{}' for module '{}': {}", profileName, moduleId, e.getMessage());
            return new Properties();
        }
    }

    /** @return the header filter box (hosts may add extra filter controls next to the search / "Show values" groups). */
    protected JPanel getFilterBox() {
        return filterBox;
    }

    /** @return the profile combo (read access for tests and hosts). */
    public JComboBox<String> getProfileCombo() {
        return profileCombo;
    }

    /**
     * The single left-to-right "strip" layout used by every row of this panel
     * (the action toolbar, the search row and its filter/host strips).
     * <p>
     * Every visible child keeps its preferred WIDTH, all children are stretched
     * to the SAME (tallest) height, the children are separated by one uniform
     * gap, and the strip starts at the container's left inset — so the row's
     * own x coordinate is 0 when the container carries no left margin of its own.
     * </p>
     * <p><h3>Thread Safety</h3>
     * All mutations must occur on the Swing EDT.</p>
     */
    private static final class LeftFlow implements LayoutManager2 {

        private final int gap;

        private LeftFlow(int gap) {
            this.gap = gap;
        }

        @Override
        public void addLayoutComponent(Component comp, Object constraints) {
            // no per-component state
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
            // no per-component state
        }

        @Override
        public void removeLayoutComponent(Component comp) {
            // no per-component state
        }

        /** The tallest preferred height among the visible children (0 when there are none). */
        private int stripHeight(Container parent) {
            int h = 0;
            for (Component child : parent.getComponents()) {
                if (child.isVisible()) {
                    h = Math.max(h, child.getPreferredSize().height);
                }
            }
            return h;
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets ins = parent.getInsets();
            // The row's content height is the strip's OWN preferred height
            // (the tallest box). The boxes of this row are height-unified
            // (ConfigurationUtils#unifyTitledBoxHeights), so stretching them
            // to a TALLER parent would distort them: the parent can be
            // transiently taller on the first layout pass, because the
            // responsive scroll wrapper's preferred size counts its
            // horizontal scrollbar before the first layout decides whether
            // it is needed (and with narrow-enough content it never is).
            // The strip is vertically centered in any extra space instead.
            int h = Math.max(0, stripHeight(parent));
            int y = ins.top + Math.max(0, (parent.getHeight() - ins.top - ins.bottom - h) / 2);
            int x = ins.left; // the strip starts at the container's left inset
            boolean first = true;
            for (Component child : parent.getComponents()) {
                if (!child.isVisible()) {
                    continue;
                }
                if (!first) {
                    x += gap;
                }
                Dimension pref = child.getPreferredSize();
                child.setBounds(x, y, pref.width, h);
                x += pref.width;
                first = false;
            }
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            int w = 0;
            int h = 0;
            boolean first = true;
            for (Component child : parent.getComponents()) {
                if (!child.isVisible()) {
                    continue;
                }
                if (!first) {
                    w += gap;
                }
                Dimension pref = child.getPreferredSize();
                w += pref.width;
                h = Math.max(h, pref.height);
                first = false;
            }
            Insets ins = parent.getInsets();
            return new Dimension(w + ins.left + ins.right, h + ins.top + ins.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public Dimension maximumLayoutSize(Container target) {
            // The strip never stretches beyond its preferred width — the row
            // stays left-aligned and the trailing space is left empty.
            return preferredLayoutSize(target);
        }

        @Override
        public float getLayoutAlignmentX(Container target) {
            return 0f;
        }

        @Override
        public float getLayoutAlignmentY(Container target) {
            return 0.5f;
        }

        @Override
        public void invalidateLayout(Container target) {
            // no cached state to invalidate
        }
    }
}