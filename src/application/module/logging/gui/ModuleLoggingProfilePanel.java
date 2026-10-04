package application.module.logging.gui;

import application.api.ModuleContext;
import application.module.logging.LoggingProfileRepository;
import application.utils.gui.CheckboxGroupPanel;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.ComboSearchHighlightRenderer;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.HelpDialog;
import application.utils.gui.SearchMatchLabel;
import application.utils.gui.SearchMatchNavigator;
import application.utils.gui.SearchMatchPanel;
import application.utils.gui.SearchValueHighlight;
import application.utils.logging.ModuleLoggingProvider;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
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
import javax.swing.JTextField;
import javax.swing.JViewport;
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
 * (New / Save / Apply / Rename / Delete / Refresh / Reset / Reload / Help), the search row
 * (the profile selector at its natural width, the live row search box, the "Show values"
 * row-state filter — the Unsaved / Saved / Applied visibility checkboxes — and the reserved
 * host strip), and the "Logger levels" frame — the add-key row at its top and the configured
 * level rows below it.
 * </p>
 * <p>
 * <h3>Row states</h3>
 * Every row carries a derived {@link RowState} computed against the two reference profiles:
 * <b>unsaved</b> (differs from the selected profile's saved content), <b>saved</b> (saved in
 * the selected profile but not what is applied) and <b>applied</b> (matches the running
 * configuration). The state colors the row (label + editor), feeds the "Show values"
 * visibility filter, and — for unsaved rows — adds the trailing {@code " *"} star to the
 * label. The live search (enabled by default) matches the label, the key and the displayed
 * value (match bands + chevron/Enter navigation — the node configuration panel's behavior).
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
     * buttons, the Profile / Search / filter boxes). Every row starts flush
     * left (no left inset), so the row's left edge lines up with the "Logger
     * levels" frame below it.
     */
    static final int ROW_GAP = 4;

    private final ModuleContext context;
    private final ModuleLoggingProvider provider;
    private final LoggingProfileRepository repo;
    private final String moduleId;

    private JComboBox<String> profileCombo;
    /** key → editor (a {@code JComboBox} for log levels, a {@code JTextField} otherwise); insertion order preserved. */
    private final Map<String, JComponent> rowEditors = new LinkedHashMap<>();
    /** key → the human-readable label shown in the editor (may equal the key). */
    private final Map<String, String> rowLabels = new LinkedHashMap<>();
    /** key → the label component currently shown for the row (rebuilt on every full render). */
    private final Map<String, JLabel> rowLabelComponents = new LinkedHashMap<>();
    /** key → the application default value of the row (also the value shown for the virtual "Default" profile). */
    private final Map<String, String> rowDefaults = new LinkedHashMap<>();
    /** Keys registered by the host via {@link #addExtraField} — built-in rows, not removable. */
    private final Set<String> hostExtraKeys = new HashSet<>();

    /** The editor row grid; held so rows can be re-rendered for search / dynamic add-key. */
    private JPanel editorGrid;
    /** Live row filter (unified search box); added to the search row by the constructor ({@link #enableSearch()}). */
    private SearchMatchPanel searchPanel;
    /** The search-row filter box: the search box and the "Show values" state filter; hosts may add extra controls. */
    private JPanel filterBox;
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
    /**
     * The per-row value state relative to the two reference profiles (the same
     * model the node configuration panel uses for its value legend): computed
     * by {@link #recomputeRowStates()}, shown by the "Show values" checkboxes
     * and painted by {@link #styleRow(String, JLabel, JComponent)}.
     */
    public enum RowState {
        /** The editor value differs from the selected profile's saved content (dirty edit). */
        UNSAVED(GuiColors.getUnsaved()),
        /** Saved in the selected profile, but not what is applied. */
        SAVED(GuiColors.getSaved()),
        /** Matches what the applied profile (the running configuration) uses. */
        APPLIED(GuiColors.getApplied());

        private final Color color;

        RowState(Color color) {
            this.color = color;
        }

        /** @return the state's legend color (the palette's SSOT) */
        public Color color() {
            return color;
        }
    }

    /** key → the row's derived state (rebuilt by {@link #recomputeRowStates()}). */
    private final Map<String, RowState> rowStates = new LinkedHashMap<>();

    /**
     * The "Show values" state-visibility filter: one checkbox per
     * {@link RowState}, all selected by default (the shared
     * {@link CheckboxGroupPanel} pattern). Rendered on the search row, to the
     * right of the search box (see {@link #buildStatusFilter()}).
     */
    private final CheckboxGroupPanel statusPanel = new CheckboxGroupPanel("Show values");
    private JCheckBox showUnsavedBox;
    private JCheckBox showSavedBox;
    private JCheckBox showAppliedBox;
    /** The profile currently marked applied for this module (null = fall back to the built-in default). */
    private String appliedProfileName;

    /** Reserved strip for a host-provided control (e.g. the node "link to node profile" checkbox). */
    private JPanel linkStrip;
    /** Help button; created in the constructor, showing the core's structured default help (see {@link #buildDefaultHelpContent()}) until a host overrides it. */
    private JButton helpButton;

    /**
     * Save / Rename / Delete only make sense for an on-disk profile — the virtual
     * "Default" entry is the application's built-in configuration, so these three
     * buttons are disabled while it is selected (rather than staying active and
     * failing on click).
     */
    private JButton saveButton;
    private JButton renameButton;
    private JButton deleteButton;

    // ── Host extension surface (see the set*/enable* methods) ──────────
    private java.util.function.Consumer<String> applyHook;
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
     * {@link #setApplyHook}; if none is set it only marks the profile applied and informs the user
     * that a restart is required. Use this constructor for host panels that own their own
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
        super(new BorderLayout(8, 8));
        this.context = context;
        this.provider = provider;
        this.moduleId = provider.getModuleId();
        this.repo = new LoggingProfileRepository();

        setBorder(new EmptyBorder(12, 12, 12, 12));
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

        refreshProfileList();
        if (profileCombo.getItemCount() > 0) {
            profileCombo.setSelectedIndex(0);
            loadProfileIntoEditor();
        }
    }

    /**
     * The panel's entire top area, as ONE wrapper (BorderLayout keeps a single
     * component per side — see the constructor): the icon-only profile action
     * toolbar on the very top (the node/profile/configuration pattern — it
     * replaced the former provider title/description text), and directly below
     * it the search row: the profile selector at its NATURAL width to the LEFT
     * of the search box, the live search box with the host's extra filters,
     * and the reserved host strip.
     */
    private JComponent buildTopArea() {
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.add(buildActionToolbar(), BorderLayout.NORTH);
        top.add(buildSearchRow(), BorderLayout.CENTER);
        return top;
    }

    /**
     * The search row: [profile selector (natural width)] [search box + extra
     * filters] [reserved host strip] — ONE left-aligned strip: every box
     * separated by the SAME uniform gap ({@link #ROW_GAP}), every box
     * stretched to the SAME height, and the strip starts flush left (no left
     * inset) — the same left edge as the "Logger levels" frame below, so the
     * panels line up column-wise.
     */
    private JComponent buildSearchRow() {
        JPanel row = new JPanel(new LeftFlow(ROW_GAP));
        row.setOpaque(false);
        row.add(buildProfileSelector());

        // The live search box (added at index 0 by the constructor, see
        // enableSearch) and the "Show values" state-visibility filter sit to
        // the RIGHT of the profile selector.
        filterBox = new JPanel(new LeftFlow(ROW_GAP));
        filterBox.setOpaque(false);
        row.add(filterBox);
        buildStatusFilter();

        // Reserved host strip (e.g. the node "link to node profile" checkbox).
        linkStrip = new JPanel(new LeftFlow(ROW_GAP));
        linkStrip.setOpaque(false);
        row.add(linkStrip);
        return row;
    }

    /**
     * Builds the "Show values" state-visibility filter (rendered on the
     * search row, to the right of the search box). Each checkbox toggles the
     * visibility of rows in the corresponding {@link RowState}; toggling
     * re-renders the rows.
     */
    private void buildStatusFilter() {
        statusPanel.setChangeListener(button -> reevaluateRowStates());
        showUnsavedBox = statusPanel.addCheckbox("Unsaved values", GuiColors.getUnsaved(), true);
        showUnsavedBox.setToolTipText(
                "Show / hide the values that differ from the selected profile's saved content (dirty edits).");
        showSavedBox = statusPanel.addCheckbox("Saved values", GuiColors.getSaved(), true);
        showSavedBox.setToolTipText(
                "Show / hide the values that are saved in the selected profile but differ from the applied profile.");
        showAppliedBox = statusPanel.addCheckbox("Applied values", GuiColors.getApplied(), true);
        showAppliedBox.setToolTipText(
                "Show / hide the values that match exactly what the applied profile (the running configuration) uses.");
        filterBox.add(statusPanel);
    }

    private JComponent buildProfileSelector() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setBorder(BorderFactory.createTitledBorder("Profile"));
        profileCombo = new JComboBox<>();
        profileCombo.setRenderer(new ProfileCellRenderer());
        profileCombo.addActionListener(e -> {
            loadProfileIntoEditor();
            updateProfileActionButtons();
        });
        panel.add(new JLabel("Active:"), BorderLayout.WEST);
        panel.add(profileCombo, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Profile combo renderer: the profile currently marked <b>applied</b> for
     * this module is painted in the applied (green) color with a leading check
     * icon, so the active profile is recognizable at a glance. The virtual
     * "Default" entry counts as applied when no explicit profile (or the
     * reserved sample config) is the applied marker.
     */
    private final class ProfileCellRenderer extends JLabel implements ListCellRenderer<String> {
        private final Icon appliedIcon = IconFontSwing.buildIcon(FontAwesome.CHECK,
                GuiConstants.getHelpIconSize(), GuiColors.getApplied());

        ProfileCellRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends String> list, String value,
                int index, boolean isSelected, boolean cellHasFocus) {
            setIcon(null);
            setText(value);
            if (value != null && isAppliedProfile(value)) {
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

        // The titled frame wraps the level editor ONLY: the add-key row at
        // the TOP inside the border, the already-added level rows (the
        // scrolling grid) BELOW it. (The grid sits at the TOP of its own
        // wrapper: a raw GridLayout inside the viewport stretches its (few)
        // remaining rows to fill the whole vertical space when the search
        // filters them — the wrapper gives the grid its preferred (text)
        // height only, so an input field always keeps its size fitted to
        // the text.)
        JPanel wrap = new JPanel(new BorderLayout(0, 4));
        wrap.setOpaque(false);
        wrap.setBorder(BorderFactory.createTitledBorder("Logger levels"));
        wrap.add(buildAddKeyRow(), BorderLayout.NORTH);
        JPanel gridWrap = new JPanel(new BorderLayout());
        gridWrap.setOpaque(false);
        gridWrap.add(editorGrid, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(gridWrap);
        // No inner frame around the grid — the single outer "Logger levels"
        // titled border is the only frame here.
        scroll.setBorder(null);
        wrap.add(scroll, BorderLayout.CENTER);
        return wrap;
    }

    /**
     * The "+ add key" row: sits at the TOP INSIDE the "Logger levels" frame,
     * the already-added level rows follow below it in the grid.
     */
    private JComponent buildAddKeyRow() {
        JPanel addRowPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        addRowPanel.setOpaque(false);
        JTextField keyInput = new JTextField(20);
        JButton addBtn = new JButton("Add key");
        addBtn.addActionListener(e -> {
            String key = keyInput.getText().trim();
            if (!key.isEmpty() && !rowEditors.containsKey(key)) {
                addRow(key, key, "INFO");
                keyInput.setText("");
            }
        });
        addRowPanel.add(new JLabel("+"));
        addRowPanel.add(keyInput);
        addRowPanel.add(addBtn);
        return addRowPanel;
    }

    private void addRow(String label, String key, String defaultValue) {
        String display = (label != null && !label.isBlank()) ? label : key;
        JComponent editor = makeEditor(defaultValue);
        rowEditors.put(key, editor);
        rowLabels.put(key, display);
        rowDefaults.put(key, defaultValue == null ? "" : defaultValue);
        addEditorChangeListener(key, editor);
        renderEditorRows();
    }

    /** Registers the "editor changed → re-evaluate derived row state" listener. */
    private void addEditorChangeListener(String key, JComponent editor) {
        if (editor instanceof JComboBox) {
            ((JComboBox<?>) editor).addActionListener(e -> handleEditorValueChange(key));
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
     * Recomputes the {@link RowState} of every row against the two reference
     * profiles (no UI updates — the caller re-renders or restyles):
     * <ul>
     * <li><b>Unsaved</b> — the editor value differs from the selected
     *     profile's saved content (or differs from the built-in default while
     *     the virtual {@link #DEFAULT_PROFILE_ENTRY} entry is selected —
     *     Default cannot be saved).</li>
     * <li><b>Applied</b> — the editor value matches the applied profile's
     *     value (or the built-in default when no profile — or only the
     *     reserved sample config — is the applied marker).</li>
     * <li><b>Saved</b> — otherwise: saved in the selected profile, but not
     *     what is applied.</li>
     * </ul>
     * Invoked on every editor value change and before every full re-render.
     */
    protected void recomputeRowStates() {
        rowStates.clear();
        String selected = selectedProfileName();
        boolean selectedIsDefault = DEFAULT_PROFILE_ENTRY.equals(selected);
        Properties selectedProps = selectedIsDefault ? new Properties() : loadProfilePropsSafely(selected);

        String applied = appliedProfileName();
        boolean appliedIsDefault = applied == null || applied.isBlank()
                || LoggingProfileRepository.RESERVED_PROFILE_NAME.equals(applied);
        Properties appliedProps = appliedIsDefault ? null : loadProfilePropsSafely(applied);

        for (String key : rowKeys()) {
            String value = editorValueOf(key);
            if (value == null) {
                value = "";
            }
            String def = defaultRowValue(key);

            // 1) Unsaved: dirty against the selected profile's saved content
            //    (a key the selected profile does not define falls back to
            //    the built-in default as the reference).
            boolean dirty;
            if (selectedIsDefault) {
                dirty = !value.equals(def);
            } else {
                String saved = selectedProps.getProperty(key);
                dirty = saved == null ? !value.equals(def) : !value.equals(saved);
            }
            if (dirty) {
                rowStates.put(key, RowState.UNSAVED);
                continue;
            }

            // 2) Applied: matches the applied profile's value (built-in
            //    defaults when nothing — or only the sample config — is applied).
            String appliedValue = appliedProps == null ? def : appliedProps.getProperty(key, def);
            if (value.equals(appliedValue)) {
                rowStates.put(key, RowState.APPLIED);
                continue;
            }

            // 3) Saved: persisted in the selected profile, but not applied.
            rowStates.put(key, RowState.SAVED);
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
        renderEditorRows();
    }

    /**
     * @return true when the row's current editor value differs from the
     *         selected profile's saved content — i.e. it has a pending
     *         (unsaved) change (exactly the rows computed as
     *         {@link RowState#UNSAVED} by {@link #recomputeRowStates()}).
     *         Such rows are shown with a trailing {@code " *"} star on their
     *         label (the same convention the node configuration panel uses
     *         for its unsaved values).
     */
    protected boolean isRowUnsaved(String key) {
        return rowStates.get(key) == RowState.UNSAVED;
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
        return isRowUnsaved(key)
                ? "<html>" + key
                        + "<br><br>Unsaved change: the value differs from the saved content of the selected profile (Save to persist)."
                : key;
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
            case SAVED -> showSavedBox.isSelected();
            case APPLIED -> showAppliedBox.isSelected();
        };
    }

    /**
     * Colors the row's label and editor with the row state's color (a row
     * without a computed state keeps its default colors).
     */
    protected void styleRow(String key, JLabel label, JComponent editor) {
        RowState state = rowStates.get(key);
        if (state == null) {
            return;
        }
        label.setForeground(state.color());
        editor.setForeground(state.color());
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
            // the icon does; built-in rows carry a disabled icon).
            JPanel valueCell = new JPanel(new BorderLayout());
            valueCell.setOpaque(false);
            valueCell.add(entry.getValue(), BorderLayout.CENTER);
            valueCell.add(buildRemoveKeyButton(key), BorderLayout.EAST);
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
        JButton removeBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.TRASH,
                GuiConstants.getHelpIconSize(), GuiColors.getContrastRed()));
        removeBtn.setContentAreaFilled(false);
        removeBtn.setBorderPainted(false);
        removeBtn.setFocusPainted(false);
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
     *         the key, the value of a text field, or the selection of a
     *         non-editable combo. Non-text inputs without a displayed value
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
        if (editor instanceof JComboBox<?> comboBox && !comboBox.isEditable()) {
            Object selected = comboBox.getSelectedItem();
            return selected != null && selected.toString().toLowerCase(Locale.ROOT).contains(lowerQuery);
        }
        return false;
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
        if (editor instanceof JTextField textField) {
            int idx = textField.getText().toLowerCase(Locale.ROOT).indexOf(lowerQuery);
            if (idx >= 0) {
                try {
                    textField.getHighlighter().addHighlight(idx, idx + lowerQuery.length(),
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

    /** Removes the value-text match bands from every text field row. */
    private void clearValueHighlights() {
        for (JComponent editor : rowEditors.values()) {
            if (editor instanceof JTextField textField) {
                textField.getHighlighter().removeAllHighlights();
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

    private JComponent makeEditor(String defaultValue) {
        if (isLogLevel(defaultValue)) {
            JComboBox<String> combo = new JComboBox<>(LOG_LEVELS);
            combo.setSelectedItem(defaultValue);
            return combo;
        }
        return new JTextField(defaultValue == null ? "" : defaultValue);
    }

    private String currentFilter() {
        return searchPanel == null ? "" : searchPanel.getSearchText().trim().toLowerCase();
    }

    /**
     * The icon-only profile action toolbar (the node/profile/configuration
     * pattern): one glyph per function — no text labels, the tooltip (shown on
     * hover) is the single source of information about each action.
     */
    private JComponent buildActionToolbar() {
        // One LEFT-aligned strip of icon-only action buttons, separated by
        // the uniform {@link #ROW_GAP}.
        JPanel panel = new JPanel(new LeftFlow(ROW_GAP));
        panel.setOpaque(false);

        // Icon-only: the tooltip carries the function description (on hover).
        JButton newBtn = new JButton();
        newBtn.setToolTipText("<html>New Profile<br><br>Creates a new profile "
                + "(the new profile's values will be the values currently set in the editor).</html>");
        newBtn.addActionListener(e -> createNewProfile());
        panel.add(newBtn);

        JButton saveBtn = new JButton();
        saveBtn.setToolTipText("<html>Save<br><br>Saves the current editor state to the selected profile.</html>");
        saveBtn.addActionListener(e -> saveCurrentProfile());
        panel.add(saveBtn);

        JButton applyBtn = new JButton();
        applyBtn.setToolTipText("<html>Apply<br><br>Marks the selected profile as applied (requires a node restart).</html>");
        applyBtn.addActionListener(e -> applyProfile());
        panel.add(applyBtn);

        JButton renameBtn = new JButton();
        renameBtn.setToolTipText("<html>Rename<br><br>Renames the selected profile.</html>");
        renameBtn.addActionListener(e -> renameProfile());
        panel.add(renameBtn);

        JButton deleteBtn = new JButton();
        deleteBtn.setToolTipText("<html>Delete<br><br>Deletes the selected profile.</html>");
        deleteBtn.addActionListener(e -> deleteProfile());
        panel.add(deleteBtn);

        JButton refreshBtn = new JButton();
        refreshBtn.setToolTipText("<html>Refresh<br><br>Re-scans the profiles on disk.</html>");
        refreshBtn.addActionListener(e -> {
            refreshProfileList();
            loadProfileIntoEditor();
        });
        panel.add(refreshBtn);

        JButton resetBtn = new JButton();
        resetBtn.setToolTipText("<html>Reset to Defaults<br><br>Sets every row back to the module's default value (not saved until you Save).</html>");
        resetBtn.addActionListener(e -> resetToDefaults());
        panel.add(resetBtn);

        JButton reloadBtn = new JButton();
        reloadBtn.setToolTipText("<html>Reload<br><br>Re-reads the selected profile from disk (discards unsaved edits).</html>");
        reloadBtn.addActionListener(e -> loadProfileIntoEditor());
        panel.add(reloadBtn);

        helpButton = new JButton();
        helpButton.setToolTipText("<html>Help<br><br>Shows the profile editor help (module hosts may customize it).</html>");
        // Enabled out of the box: the core shows its own structured default help
        // (buildDefaultHelpContent); a host overrides the text via setHelpSupplier.
        helpButton.addActionListener(e -> showHelp());
        // The question mark is an icon in the SAME flat style as the other
        // toolbar glyphs (same size, color, hover-grow) — no text.
        ConfigurationUtils.styleProfileIconButton(helpButton, FontAwesome.QUESTION_CIRCLE,
                GuiColors.getButtonIcon(), GuiConstants.getToolBarIconSize());
        panel.add(helpButton);

        // Keep references to the buttons whose validity depends on the SELECTED
        // profile (the virtual "Default" entry has no file to act on).
        this.saveButton = saveBtn;
        this.renameButton = renameBtn;
        this.deleteButton = deleteBtn;

        // Icon toolbar (same look as the node configuration panel): one glyph
        // per action, sized consistently.
        ConfigurationUtils.configureProfileToolbar(
                newBtn, saveBtn, applyBtn, renameBtn, deleteBtn, reloadBtn, refreshBtn, resetBtn, null, null);

        return panel;
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

        profileCombo.removeAllItems();
        // The virtual "Default" entry is listed INSTEAD of the reserved sample
        // config on disk (logging-default): selecting Default uses the
        // application's built-in values and never touches a profile file.
        profileCombo.addItem(DEFAULT_PROFILE_ENTRY);
        for (String name : profiles) {
            profileCombo.addItem(name);
        }

        refreshAppliedMarker();

        if (selected != null) {
            for (int i = 0; i < profileCombo.getItemCount(); i++) {
                if (selected.equals(profileCombo.getItemAt(i))) {
                    profileCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
        // Robust even when the selection did not change (the combo's action
        // listener may not fire for a no-op re-selection).
        updateProfileActionButtons();
    }

    /**
     * Enables / disables Save, Rename and Delete according to the currently
     * selected profile: for the virtual "Default" entry these actions are
     * impossible (there is no file to save, rename or delete), so the buttons
     * are DISABLED instead of being shown active and only failing on click.
     * New Profile, Apply, Reload, Refresh and Reset remain enabled — they are
     * all meaningful with "Default" selected.
     */
    private void updateProfileActionButtons() {
        if (saveButton == null) {
            return; // toolbar not built yet
        }
        boolean defaultSelected = DEFAULT_PROFILE_ENTRY.equals(profileCombo.getSelectedItem());
        saveButton.setEnabled(!defaultSelected);
        renameButton.setEnabled(!defaultSelected);
        deleteButton.setEnabled(!defaultSelected);
    }

    private void loadProfileIntoEditor() {
        String name = (String) profileCombo.getSelectedItem();
        if (name == null) {
            return;
        }
        if (DEFAULT_PROFILE_ENTRY.equals(name)) {
            // Virtual entry: load the application's built-in defaults. Nothing
            // is read from (or written to) disk.
            for (Map.Entry<String, JComponent> entry : rowEditors.entrySet()) {
                String d = rowDefaults.get(entry.getKey());
                if (d != null) {
                    setEditorValue(entry.getValue(), d);
                }
            }
            reevaluateRowStates();
            return;
        }
        Properties props;
        try {
            props = repo.loadProps(moduleId, name);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Failed to load profile '" + name + "': " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        for (Map.Entry<String, JComponent> entry : rowEditors.entrySet()) {
            String value = props.getProperty(entry.getKey());
            if (value != null) {
                setEditorValue(entry.getValue(), value);
            }
        }
        reevaluateRowStates();
    }

    /** Re-reads the module's applied-profile marker from the repository (never throws). */
    private void refreshAppliedMarker() {
        try {
            appliedProfileName = repo.getApplied(moduleId);
        } catch (Exception e) {
            LOGGER.warn("Failed to read the applied marker for module '{}': {}", moduleId, e.getMessage());
            appliedProfileName = null;
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
        Object input = JOptionPane.showInputDialog(this, "New profile name:", "New Profile",
                JOptionPane.PLAIN_MESSAGE, null, null, "my-profile");
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
     * Creates a new profile seeded with the editor's CURRENT state — every row's
     * currently set value becomes the new profile's value. Package-private
     * so tests can drive it without the modal name dialog.
     */
    void createProfileFromCurrentState(String name) {
        try {
            repo.create(moduleId, name, collectEditorProps());
            refreshProfileList();
            profileCombo.setSelectedItem(name);
            loadProfileIntoEditor();
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
            reevaluateRowStates();
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
        int result = JOptionPane.showConfirmDialog(this,
                "Apply profile '" + name + "' for module '" + moduleId + "'?\n\n"
                + (isDefault
                        ? "This restores the application's built-in default configuration."
                        : "This marks it as applied.")
                + " A node restart is required for the change to take effect.",
                "Apply Profile", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            if (isDefault) {
                // Clearing the marker makes the module fall back to the built-in
                // defaults — no profile file is read or written.
                repo.setApplied(moduleId, null);
            } else {
                saveCurrentProfile();
                repo.setApplied(moduleId, name);
            }
            refreshAppliedMarker();
            reevaluateRowStates();
            if (applyHook != null) {
                applyHook.accept(name);
            } else if (context != null) {
                context.requestRestart();
            } else {
                JOptionPane.showMessageDialog(this, "Profile '" + name + "' marked as applied.\nA node restart is required.",
                        "Applied", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Failed to apply profile: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
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
            repo.rename(moduleId, oldName, newName);
            // refreshProfileList() re-reads the applied marker (the repository
            // follows the rename) before re-loading the editor.
            refreshProfileList();
            profileCombo.setSelectedItem(newName);
            loadProfileIntoEditor();
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
            repo.delete(moduleId, name);
            refreshProfileList();
            if (profileCombo.getItemCount() > 0) {
                profileCombo.setSelectedIndex(0);
                loadProfileIntoEditor();
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
                "Each row is a <b>key/value entry</b> of this module's logging profile. "
                        + "Rows with a log level are dropdowns; the others are free text."));
        content.add(HelpDialog.separator());
        content.add(HelpDialog.heading("Row colors"));
        content.add(HelpDialog.legendRow(GuiColors.getUnsaved(), "Unsaved",
                "The value differs from the selected profile's saved content. Unsaved rows are "
                        + "marked with a trailing <b>*</b> on their label."));
        content.add(HelpDialog.legendRow(GuiColors.getSaved(), "Saved",
                "Saved in the selected profile, but differs from the applied profile."));
        content.add(HelpDialog.legendRow(GuiColors.getApplied(), "Applied",
                "Matches what the applied profile (the running configuration) uses."));
        content.add(Box.createVerticalStrut(8));
        content.add(HelpDialog.paragraph(
                "The <b>\u201CShow values\u201D</b> boxes filter rows by these states, and the "
                        + "<b>Search</b> box filters rows by label, key or value."));
        content.add(HelpDialog.separator());
        content.add(HelpDialog.heading("Toolbar actions"));
        content.add(HelpDialog.actionRow(FontAwesome.FILE_O, iconColor, "New Profile",
                "Creates a new profile (the new profile's values will be the values currently set in the editor)."));
        content.add(HelpDialog.actionRow(FontAwesome.FLOPPY_O, iconColor, "Save",
                "Saves the current editor state to the selected profile."));
        content.add(HelpDialog.actionRow(FontAwesome.CHECK_CIRCLE_O, GuiColors.getApplied(), "Apply",
                "Marks the selected profile as applied; a node restart is required for the change to take effect."));
        content.add(HelpDialog.actionRow(FontAwesome.PENCIL_SQUARE_O, iconColor, "Rename",
                "Renames the selected profile."));
        content.add(HelpDialog.actionRow(FontAwesome.TRASH_O, GuiColors.getContrastRed(), "Delete",
                "Deletes the selected profile (destructive)."));
        content.add(HelpDialog.actionRow(FontAwesome.REFRESH, iconColor, "Refresh",
                "Re-scans the profiles on disk."));
        content.add(HelpDialog.actionRow(FontAwesome.UNDO, iconColor, "Reset to Defaults",
                "Sets every row back to the module's default value (not saved until you Save)."));
        content.add(HelpDialog.actionRow(FontAwesome.RECYCLE, iconColor, "Reload",
                "Re-reads the selected profile from disk (discards unsaved edits)."));
        content.add(HelpDialog.actionRow(FontAwesome.QUESTION_CIRCLE, iconColor, "Help",
                "Shows this help."));
        return content;
    }

    private static void setEditorValue(JComponent editor, String value) {
        if (editor instanceof JComboBox) {
            ((JComboBox<?>) editor).setSelectedItem(value);
        } else if (editor instanceof JTextField) {
            ((JTextField) editor).setText(value);
        }
    }

    private static String editorValue(JComponent editor) {
        if (editor instanceof JComboBox) {
            Object v = ((JComboBox<?>) editor).getSelectedItem();
            return v == null ? null : v.toString();
        } else if (editor instanceof JTextField) {
            return ((JTextField) editor).getText();
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
     * the profile applied and informs the user that a restart is required.
     *
     * @param applyHook the apply callback (may be null to clear)
     * @return {@code this} for fluent chaining
     */
    public ModuleLoggingProfilePanel setApplyHook(java.util.function.Consumer<String> applyHook) {
        this.applyHook = applyHook;
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
        // "present"). It is inserted at index 0 so it always sits directly to
        // the right of the profile selector, ahead of any host-added filters
        // registered in the filter box.
        if (searchPanel != null && searchPanel.getParent() == null) {
            filterBox.add(searchPanel, 0);
            filterBox.revalidate();
            filterBox.repaint();
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
     * gap, and the strip starts flush at x=0 (no left inset) — so the row's
     * left edge lines up exactly with the "Logger levels" frame below it.
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
            // Fill the parent's height (never less than the strip's preferred
            // height) so every box of the row — including nested strip boxes —
            // ends up at the SAME uniform height.
            int h = Math.max(stripHeight(parent), parent.getHeight() - ins.top - ins.bottom);
            if (h < 0) {
                h = 0;
            }
            int y = ins.top + Math.max(0, (parent.getHeight() - ins.top - ins.bottom - h) / 2);
            int x = 0; // flush left: no left inset
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