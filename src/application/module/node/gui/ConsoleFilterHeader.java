package application.module.node.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

import application.utils.gui.CheckboxGroupPanel;
import application.utils.gui.ResponsiveToolbarScrollPane;
import application.utils.gui.SearchMatchPanel;
import application.utils.logging.event.CompositeFilter;
import application.utils.logging.event.LevelFilter;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.ModuleFilter;
import application.utils.logging.event.ProfileFilter;

/**
 * Filter toolbar for the SystemConsole panel.
 * <p>
 * Provides interactive controls to filter log events displayed in the aggregated
 * console: level checkboxes, profile selector, module filter, and text search.
 * When any control changes, all active filters are combined into a single
 * {@link CompositeFilter} (AND logic) and delivered to the registered callback.
 * </p>
 * <p>
 * <h3>UI Layout (horizontal toolbar)</h3>
 * <pre>
 * [Level: ☑TRACE ☑DEBUG ☑INFO ☑WARN ☑ERROR] | Profile: [all ▼] | Module: [...] | Search: [...] [X]
 * </pre>
 * </p>
 * <p>
 * <h3>Thread Safety</h3>
 * All mutations must occur on the Swing EDT.
 * </p>
 *
 * @see SystemConsoleSubscriber
 * @see CompositeFilter
 */
public final class ConsoleFilterHeader extends JPanel {

    /** Callback invoked whenever the combined filter expression changes */
    public interface FilterChangeListener {
        /**
         * @param combinedFilter the new combined filter (null = accept all)
         */
        void onFilterChanged(LogFilter combinedFilter);
    }

    private JCheckBox cbTrace;
    private JCheckBox cbDebug;
    private JCheckBox cbInfo;
    private JCheckBox cbWarn;
    private JCheckBox cbError;

    private JComboBox<String> profileCombo;
    private JTextField moduleField;
    /**
     * Unified search box (field, match counter, chevron buttons) — shared
     * with the node configuration panel's search. The header only wires its
     * own listeners and delegates the public search API to the component.
     */
    private SearchMatchPanel searchPanel;
    /**
     * Fired (on the EDT) when the user presses Enter in the search field.
     * Convention (implemented by the owning console panel): the first Enter
     * after a query change scrolls to the active (first) match, each further
     * Enter advances to the next match.
     */
    private Runnable searchEnterListener;

    /** Whether the Profile section is part of this header (node console: false) */
    private final boolean showProfile;
    /** Whether the Module section is part of this header (node console: false) */
    private final boolean showModule;

    private final Consumer<LogFilter> callback;
    private volatile LogFilter currentFilter = null;

    /**
     * Fired (on the EDT) with the current search text after EVERY keystroke.
     * The search is a live "find in console" highlight — it does NOT filter.
     */
    private Consumer<String> searchTextListener;
    /**
     * Fired (on the EDT) for chevron navigation:
     * {@code true} = next match, {@code false} = previous match.
     */
    private Consumer<Boolean> searchNavigationListener;
    /**
     * Fingerprint of the last delivered control state (see {@link #currentStateKey()}).
     * Makes {@link #rebuildFilter()} idempotent: an unchanged state re-fires no callback.
     * EDT-only.
     */
    private volatile String lastStateKey = null;

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * Creates a filter header toolbar with all sections (Level, Profile,
     * Module, Search).
     *
     * @param callback receiver for combined filter changes (may be null)
     */
    public ConsoleFilterHeader(Consumer<LogFilter> callback) {
        this(callback, true, true);
    }

    /**
     * Creates a filter header toolbar with selective sections.
     * <p>
     * The initial filter expression is built eagerly, so
     * {@link #getCurrentFilter()} is non-null right after construction
     * (whenever any section produces a filter).
     * </p>
     *
     * @param callback   receiver for combined filter changes (may be null)
     * @param showProfile whether the Profile section should be shown
     * @param showModule  whether the Module section should be shown
     */
    public ConsoleFilterHeader(Consumer<LogFilter> callback, boolean showProfile, boolean showModule) {
        this.callback = callback;
        this.showProfile = showProfile;
        this.showModule = showModule;
        initUI();
        // Establish the initial filter from the default control state.
        rebuildFilter();
    }

    // ── UI Initialization ────────────────────────────────────────────────

    private void initUI() {
        setOpaque(false);
        setLayout(new BorderLayout(0, 0));

        JPanel mainPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        mainPanel.setOpaque(false);

        mainPanel.add(buildLevelPanel());
        if (showProfile) {
            mainPanel.add(buildProfilePanel());
        }
        if (showModule) {
            mainPanel.add(buildModulePanel());
        }
        mainPanel.add(buildSearchPanel());

        // Wrap in the same responsive toolbar scroll pane the button rows use:
        // when the window is narrowed, a horizontal scrollbar appears and the
        // row height adjusts dynamically (no content overlap, no wrapping).
        // The thin 4px empty border lives on the WRAPPER (not on the search
        // box): it clears the row above and the possibly appearing horizontal
        // scrollbar below. Inner top/bottom insets are 0 so the wrapper's
        // border alone defines the vertical spacing.
        JScrollPane filterScroll = new ResponsiveToolbarScrollPane(mainPanel, new Insets(0, 10, 0, 5), false);
        filterScroll.setBorder(new EmptyBorder(4, 0, 4, 0));
        add(filterScroll, BorderLayout.CENTER);
    }

    private CheckboxGroupPanel buildLevelPanel() {
        // Shared titled checkbox-group box (like the node configuration's
        // "Show values" box): unchecking a level rebuilds the combined filter.
        CheckboxGroupPanel panel = new CheckboxGroupPanel("Level");
        panel.setChangeListener(button -> rebuildFilter());

        panel.add(new JLabel("Show: "));

        cbTrace = addLevelCheckbox(panel, "TRACE", LogLevel.TRACE);
        cbDebug = addLevelCheckbox(panel, "DEBUG", LogLevel.DEBUG);
        cbInfo  = addLevelCheckbox(panel, "INFO", LogLevel.INFO);
        cbWarn  = addLevelCheckbox(panel, "WARN", LogLevel.WARN);
        cbError = addLevelCheckbox(panel, "ERROR", LogLevel.ERROR);

        // Default: all levels visible; unchecking a level hides messages
        // of that level.
        cbTrace.setSelected(true);
        cbDebug.setSelected(true);
        cbInfo.setSelected(true);
        cbWarn.setSelected(true);
        cbError.setSelected(true);

        return panel;
    }

    private JCheckBox addLevelCheckbox(CheckboxGroupPanel panel, String text, LogLevel level) {
        JCheckBox cb = panel.addCheckbox(text);
        cb.putClientProperty("LogLevel", level);
        return cb;
    }

    private JPanel buildProfilePanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);
        panel.setBorder(new TitledBorder("Profile"));

        panel.add(new JLabel("Profile:"));

        profileCombo = new JComboBox<>();
        profileCombo.setEditable(true);
        profileCombo.setPreferredSize(new java.awt.Dimension(130, profileCombo.getPreferredSize().height));

        // ActionListener fires on dropdown selection changes
        profileCombo.addActionListener(e -> rebuildFilter());

        // Listen to text document changes on the editable combo box
        JTextComponent editor = (JTextComponent) profileCombo.getEditor().getEditorComponent();
        editor.getDocument().addDocumentListener(new DocListener(this::rebuildFilter));

        panel.add(profileCombo);
        return panel;
    }

    private JPanel buildModulePanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);
        panel.setBorder(new TitledBorder("Module"));

        panel.add(new JLabel("Module:"));

        moduleField = new JTextField(10);
        moduleField.setToolTipText("Logger name substring to include (empty = all)");
        moduleField.getDocument().addDocumentListener(new DocListener(this::rebuildFilter));

        panel.add(moduleField);
        return panel;
    }

    private SearchMatchPanel buildSearchPanel() {
        // Unified search box shared with the node configuration panel: a
        // compact, left-aligned "Search" titled box (16-column field, match
        // counter, chevron buttons). The header is only a listener forwarder
        // and a delegator of the public search API.
        searchPanel = new SearchMatchPanel("Text to find in the console (live highlighting)");
        searchPanel.setSearchTextListener(text -> fireSearchTextChanged());
        searchPanel.setSearchNavigationListener(this::fireSearchNavigation);
        searchPanel.setEnterListener(this::fireSearchEnter);
        return searchPanel;
    }

    // ── Filter Building ──────────────────────────────────────────────────

    /**
     * Rebuilds the combined {@link LogFilter} from all active control states
     * and invokes the callback.
     * <p>
     * Must be called on the Swing EDT.
     */
    public void rebuildFilter() {
        // Idempotency (v4 P2.2): if the control state is unchanged, nothing was
        // delivered differently — skip building and skip the callback.
        String stateKey = currentStateKey();
        if (stateKey.equals(lastStateKey)) {
            return;
        }

        List<LogFilter> filters = new ArrayList<>();

        // Level filter
        LogFilter levelFilter = buildLevelFilter();
        if (levelFilter != null) {
            filters.add(levelFilter);
        }

        // Profile filter
        LogFilter profileFilter = buildProfileFilter();
        if (profileFilter != null) {
            filters.add(profileFilter);
        }

        // Module filter
        LogFilter moduleFilter = buildModuleFilter();
        if (moduleFilter != null) {
            filters.add(moduleFilter);
        }

        // NOTE: the search field is a live "find in console" highlight, not a
        // filter — it is delivered via the search text listener instead.

        LogFilter combined;
        if (filters.isEmpty()) {
            combined = null;
        } else if (filters.size() == 1) {
            combined = filters.get(0);
        } else {
            combined = CompositeFilter.and(filters.toArray(new LogFilter[0]));
        }

        lastStateKey = stateKey;
        currentFilter = combined;

        if (callback != null) {
            callback.accept(combined);
        }
    }

    /**
     * Canonical fingerprint of the current control state (levels, profile,
     * module). Used by {@link #rebuildFilter()} to
     * detect no-op rebuilds without relying on {@code LogFilter} identity.
     */
    private String currentStateKey() {
        Object selectedProfile = profileCombo != null && profileCombo.getItemCount() > 0
                ? profileCombo.getSelectedItem()
                : null;
        return getSelectedLevels() + "|"
                + String.valueOf(selectedProfile) + "|"
                + (moduleField != null ? moduleField.getText() : "");
    }

    /** Number of level checkboxes shown in the header (TRACE..ERROR) */
    private static final int SELECTABLE_LEVEL_COUNT = 5;

    private LogFilter buildLevelFilter() {
        Set<LogLevel> selectedLevels = new HashSet<>();
        for (AbstractButton btn : new AbstractButton[]{cbTrace, cbDebug, cbInfo, cbWarn, cbError}) {
            if (btn.isSelected()) {
                LogLevel level = (LogLevel) btn.getClientProperty("LogLevel");
                if (level != null) {
                    selectedLevels.add(level);
                }
            }
        }

        if (selectedLevels.isEmpty()) {
            // Nothing selected = block all
            return e -> false;
        }
        if (selectedLevels.size() == SELECTABLE_LEVEL_COUNT) {
            // All selectable levels are visible = no filter needed
            return null;
        }

        // Any subset (including 4 of 5) must produce a filter so that the
        // unselected level(s) are actually excluded.
        return LevelFilter.including(selectedLevels.toArray(new LogLevel[0]));
    }

    private LogFilter buildProfileFilter() {
        if (profileCombo == null) {
            return null;
        }
        Object selected = profileCombo.getSelectedItem();
        if (selected == null) {
            return null;
        }
        String value = selected.toString().trim();
        if (value.isEmpty() || "(all)".equalsIgnoreCase(value)) {
            return null; // Empty or "(all)" = accept all profiles
        }
        return ProfileFilter.including(value);
    }

    private LogFilter buildModuleFilter() {
        if (moduleField == null) {
            return null;
        }
        String value = moduleField.getText();
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return ModuleFilter.including(value.trim());
    }

    // ── Search (live highlight, not a filter) ─────────────────────────────

    /**
     * Registers a listener that is invoked with the current search text after
     * every change (each keystroke). The search does not filter the console;
     * the consumer is expected to highlight the matching text and provide
     * match navigation.
     *
     * @param listener callback (may be null to clear)
     */
    public void setSearchTextListener(Consumer<String> listener) {
        this.searchTextListener = listener;
    }

    /**
     * Registers a listener for chevron navigation:
     * {@code true} = next match, {@code false} = previous match.
     *
     * @param listener callback (may be null to clear)
     */
    public void setSearchNavigationListener(Consumer<Boolean> listener) {
        this.searchNavigationListener = listener;
    }

    /**
     * Registers a listener invoked when the user presses Enter in the search
     * field.
     *
     * @param listener callback (may be null to clear)
     */
    public void setSearchEnterListener(Runnable listener) {
        this.searchEnterListener = listener;
    }

    /**
     * Updates the match counter label shown next to the search field.
     * A non-empty text (e.g. {@code "1/23"}) makes the label visible;
     * a null or empty text hides it.
     * Must be called on the Swing EDT.
     *
     * @param text the indicator text (null or empty hides the label)
     */
    public void setSearchMatchIndicatorText(String text) {
        if (searchPanel != null) {
            searchPanel.setMatchIndicatorText(text);
        }
    }

    /** @return the current match indicator text (empty while the label is hidden) */
    public String getSearchMatchIndicatorText() {
        return searchPanel == null ? "" : searchPanel.getMatchIndicatorText();
    }

    /**
     * Shows or hides the search navigation chevron buttons (previous/next
     * match). They are only useful while the current search has at least one
     * match, so the owning console panel drives this from the match count.
     * Must be called on the Swing EDT.
     *
     * @param visible true while the search has matches
     */
    public void setSearchChevronsVisible(boolean visible) {
        if (searchPanel != null) {
            searchPanel.setChevronsVisible(visible);
        }
    }

    /** Fires the search text listener with the current field text. EDT-only. */
    private void fireSearchTextChanged() {
        if (searchTextListener != null) {
            searchTextListener.accept(getSearchText());
        }
    }

    /** Fires the search navigation listener (true = next, false = previous). EDT-only. */
    private void fireSearchNavigation(boolean next) {
        if (searchNavigationListener != null) {
            searchNavigationListener.accept(next);
        }
    }

    /** Fires the search Enter listener. EDT-only. */
    private void fireSearchEnter() {
        if (searchEnterListener != null) {
            searchEnterListener.run();
        }
    }

    // ── Profile Population ───────────────────────────────────────────────

    /**
     * Populates the profile dropdown with known profile names.
     * Always prepends "(all)" as the first option.
     * No-op when this header has no Profile section.
     *
     * @param profiles known profile names (may be null or empty)
     */
    public void setProfiles(List<String> profiles) {
        if (profileCombo == null) {
            return;
        }
        profileCombo.removeAllItems();
        profileCombo.addItem("(all)");
        if (profiles != null) {
            for (String p : profiles) {
                if (p != null && !p.isEmpty()) {
                    profileCombo.addItem(p);
                }
            }
        }
    }

    /**
     * Convenience overload accepting varargs.
     */
    public void setProfiles(String... profiles) {
        setProfiles(profiles != null ? Arrays.asList(profiles) : null);
    }

    // ── Public Accessors / Mutators ──────────────────────────────────────

    /** @return the current combined filter, or null if accepting all */
    public LogFilter getCurrentFilter() {
        return currentFilter;
    }

    /**
     * Resets all controls to their default state (accept all).
     * Must be called on EDT.
     */
    public void resetToDefaults() {
        cbTrace.setSelected(true);
        cbDebug.setSelected(true);
        cbInfo.setSelected(true);
        cbWarn.setSelected(true);
        cbError.setSelected(true);

        if (profileCombo != null) {
            profileCombo.removeAllItems();
            profileCombo.addItem("(all)");
            profileCombo.setSelectedIndex(0);
        }

        if (moduleField != null) {
            moduleField.setText("");
        }
        // Direct field access (no listener fire): resetToDefaults is a
        // programmatic reset, not a user keystroke.
        if (searchPanel != null) {
            searchPanel.getSearchField().setText("");
        }

        rebuildFilter();
    }

    /**
     * Returns the set of currently selected log levels.
     */
    public Set<LogLevel> getSelectedLevels() {
        Set<LogLevel> levels = new HashSet<>();
        for (AbstractButton btn : new AbstractButton[]{cbTrace, cbDebug, cbInfo, cbWarn, cbError}) {
            if (btn.isSelected()) {
                LogLevel level = (LogLevel) btn.getClientProperty("LogLevel");
                if (level != null) {
                    levels.add(level);
                }
            }
        }
        return levels;
    }

    /**
     * Sets which log levels should be visible.
     * Pass an empty set to block all levels.
     */
    public void setSelectedLevels(Set<LogLevel> levels) {
        cbTrace.setSelected(levels.contains(LogLevel.TRACE));
        cbDebug.setSelected(levels.contains(LogLevel.DEBUG));
        cbInfo.setSelected(levels.contains(LogLevel.INFO));
        cbWarn.setSelected(levels.contains(LogLevel.WARN));
        cbError.setSelected(levels.contains(LogLevel.ERROR));
        rebuildFilter();
    }

    /**
     * Returns the current profile filter text.
     * Returns an empty string when this header has no Profile section.
     */
    public String getProfileText() {
        if (profileCombo == null) {
            return "";
        }
        Object sel = profileCombo.getSelectedItem();
        return sel != null ? sel.toString().trim() : "";
    }

    /**
     * Sets the profile combo selection.
     * No-op when this header has no Profile section.
     *
     * @param name the profile name to select, or "(all)" / empty string for all
     */
    public void setProfileText(String name) {
        if (profileCombo == null) {
            return;
        }
        // Ensure the combo has at least the "(all)" entry — setSelectedIndex(0)
        // throws IllegalArgumentException on an empty combo (before setProfiles()).
        if (profileCombo.getItemCount() == 0) {
            profileCombo.addItem("(all)");
        }
        if (name == null || name.isEmpty()) {
            profileCombo.setSelectedIndex(0);
        } else {
            profileCombo.setSelectedItem(name);
            // If not found, set as text directly (editable combo)
            if (profileCombo.getSelectedItem() == null) {
                profileCombo.getEditor().setItem(name);
            }
        }
        rebuildFilter();
    }

    /**
     * Returns the current module filter text.
     * Returns an empty string when this header has no Module section.
     */
    public String getModuleText() {
        return moduleField != null ? moduleField.getText() : "";
    }

    /**
     * Sets the module filter text.
     * No-op when this header has no Module section.
     */
    public void setModuleText(String name) {
        if (moduleField == null) {
            return;
        }
        moduleField.setText(name != null ? name : "");
        rebuildFilter();
    }

    /**
     * Returns the current search text.
     */
    public String getSearchText() {
        return searchPanel == null ? "" : searchPanel.getSearchText();
    }

    /**
     * Sets the search text (fires the live search listener).
     */
    public void setSearchText(String text) {
        if (searchPanel != null) {
            searchPanel.setSearchText(text);
        }
    }

    @Override
    public String toString() {
        return "ConsoleFilterHeader{filter=" + currentFilter + '}';
    }

    // ── Inner Classes ────────────────────────────────────────────────────

    /**
     * Minimal DocumentListener that delegates all three events to a single Runnable.
     */
    private static final class DocListener implements DocumentListener {
        private final Runnable action;

        DocListener(Runnable action) {
            this.action = action;
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
            action.run();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            action.run();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            action.run();
        }
    }
}