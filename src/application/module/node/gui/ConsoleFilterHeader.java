package application.module.node.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.AbstractButton;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

import application.utils.logging.event.CompositeFilter;
import application.utils.logging.event.LevelFilter;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.ModuleFilter;
import application.utils.logging.event.ProfileFilter;
import application.utils.logging.event.TextSearchFilter;

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
    private JTextField searchField;

    /** Radio buttons: Include / Exclude mode for text search */
    private JRadioButton searchIncludeBtn;
    private JRadioButton searchExcludeBtn;

    /** Radio buttons: Contains / Regex mode for text search */
    private JRadioButton searchContainsBtn;
    private JRadioButton searchRegexBtn;

    private final Consumer<LogFilter> callback;
    private volatile LogFilter currentFilter = null;

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * Creates a filter header toolbar.
     *
     * @param callback receiver for combined filter changes (may be null)
     */
    public ConsoleFilterHeader(Consumer<LogFilter> callback) {
        this.callback = callback;
        initUI();
    }

    // ── UI Initialization ────────────────────────────────────────────────

    private void initUI() {
        setLayout(new BorderLayout(0, 0));
        setBorder(new EmptyBorder(4, 8, 4, 8));

        JPanel mainPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        mainPanel.setOpaque(false);

        mainPanel.add(buildLevelPanel());
        mainPanel.add(buildProfilePanel());
        mainPanel.add(buildModulePanel());
        mainPanel.add(buildSearchPanel());

        add(mainPanel, BorderLayout.CENTER);
    }

    private JPanel buildLevelPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);
        panel.setBorder(new TitledBorder("Level"));

        panel.add(new JLabel("Show: "));

        cbTrace = addLevelCheckbox(panel, "TRACE", LogLevel.TRACE);
        cbDebug = addLevelCheckbox(panel, "DEBUG", LogLevel.DEBUG);
        cbInfo  = addLevelCheckbox(panel, "INFO", LogLevel.INFO);
        cbWarn  = addLevelCheckbox(panel, "WARN", LogLevel.WARN);
        cbError = addLevelCheckbox(panel, "ERROR", LogLevel.ERROR);

        // Default: show INFO and above (TRACE/DEBUG unchecked)
        cbTrace.setSelected(false);
        cbDebug.setSelected(false);
        cbInfo.setSelected(true);
        cbWarn.setSelected(true);
        cbError.setSelected(true);

        return panel;
    }

    private JCheckBox addLevelCheckbox(JPanel panel, String text, LogLevel level) {
        JCheckBox cb = new JCheckBox(text);
        cb.putClientProperty("LogLevel", level);
        cb.addActionListener(e -> rebuildFilter());
        panel.add(cb);
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

    private JPanel buildSearchPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);
        panel.setBorder(new TitledBorder("Search"));

        // Mode selector: Include / Exclude
        ButtonGroup modeGroup = new ButtonGroup();
        searchIncludeBtn = new JRadioButton("Inc", true);
        searchExcludeBtn = new JRadioButton("Exc", false);
        searchIncludeBtn.setToolTipText("Include matching messages");
        searchExcludeBtn.setToolTipText("Exclude matching messages");
        modeGroup.add(searchIncludeBtn);
        modeGroup.add(searchExcludeBtn);

        searchIncludeBtn.addActionListener(e -> rebuildFilter());
        searchExcludeBtn.addActionListener(e -> rebuildFilter());

        panel.add(searchIncludeBtn);
        panel.add(searchExcludeBtn);

        // Pattern type: Contains / Regex
        ButtonGroup patternGroup = new ButtonGroup();
        searchContainsBtn = new JRadioButton("Txt", true);
        searchRegexBtn = new JRadioButton("Rx", false);
        searchContainsBtn.setToolTipText("Plain text (literal) search");
        searchRegexBtn.setToolTipText("Regular expression search");
        patternGroup.add(searchContainsBtn);
        patternGroup.add(searchRegexBtn);

        searchContainsBtn.addActionListener(e -> rebuildFilter());
        searchRegexBtn.addActionListener(e -> rebuildFilter());

        panel.add(searchContainsBtn);
        panel.add(searchRegexBtn);

        searchField = new JTextField(16);
        searchField.setToolTipText("Text to search in log messages");
        searchField.getDocument().addDocumentListener(new DocListener(this::rebuildFilter));

        panel.add(searchField);
        return panel;
    }

    // ── Filter Building ──────────────────────────────────────────────────

    /**
     * Rebuilds the combined {@link LogFilter} from all active control states
     * and invokes the callback.
     * <p>
     * Must be called on the Swing EDT.
     */
    public void rebuildFilter() {
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

        // Text search filter
        LogFilter searchFilter = buildSearchFilter();
        if (searchFilter != null) {
            filters.add(searchFilter);
        }

        LogFilter combined;
        if (filters.isEmpty()) {
            combined = null;
        } else if (filters.size() == 1) {
            combined = filters.get(0);
        } else {
            combined = CompositeFilter.and(filters.toArray(new LogFilter[0]));
        }

        // Only invoke callback if the filter actually changed
        if (areFiltersEqual(combined, currentFilter)) {
            return;
        }
        currentFilter = combined;

        if (callback != null) {
            callback.accept(combined);
        }
    }

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
        if (selectedLevels.size() >= LogLevel.values().length - 1) {
            // Almost all / all selected = no filter needed (OFF is special)
            return null;
        }

        return LevelFilter.including(selectedLevels.toArray(new LogLevel[0]));
    }

    private LogFilter buildProfileFilter() {
        Object selected = profileCombo.getSelectedItem();
        if (selected == null) {
            return null;
        }
        String value = selected.toString().trim();
        if (value.isEmpty()) {
            return null; // Empty = accept all profiles
        }
        return ProfileFilter.including(value);
    }

    private LogFilter buildModuleFilter() {
        String value = moduleField.getText();
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return ModuleFilter.including(value.trim());
    }

    private LogFilter buildSearchFilter() {
        String value = searchField.getText();
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        value = value.trim();

        boolean include = searchIncludeBtn.isSelected();
        boolean regex = searchRegexBtn.isSelected();

        try {
            if (include) {
                if (regex) {
                    return TextSearchFilter.including(value);
                } else {
                    return TextSearchFilter.includingLiteral(value);
                }
            } else {
                if (regex) {
                    return TextSearchFilter.excluding(value);
                } else {
                    // For literal exclude, use Pattern.quote and call excluding with regex string
                    String escaped = java.util.regex.Pattern.quote(value);
                    return TextSearchFilter.excluding(escaped);
                }
            }
        } catch (IllegalArgumentException e) {
            // Invalid regex: ignore the invalid search
            return null;
        }
    }

    // ── Profile Population ───────────────────────────────────────────────

    /**
     * Populates the profile dropdown with known profile names.
     * Always prepends "(all)" as the first option.
     *
     * @param profiles known profile names (may be null or empty)
     */
    public void setProfiles(List<String> profiles) {
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
        cbTrace.setSelected(false);
        cbDebug.setSelected(false);
        cbInfo.setSelected(true);
        cbWarn.setSelected(true);
        cbError.setSelected(true);

        profileCombo.removeAllItems();
        profileCombo.addItem("(all)");
        profileCombo.setSelectedIndex(0);

        moduleField.setText("");
        searchField.setText("");

        searchIncludeBtn.setSelected(true);
        searchContainsBtn.setSelected(true);

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
     */
    public String getProfileText() {
        Object sel = profileCombo.getSelectedItem();
        return sel != null ? sel.toString().trim() : "";
    }

    /**
     * Sets the profile combo selection.
     * @param name the profile name to select, or "(all)" / empty string for all
     */
    public void setProfileText(String name) {
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
     */
    public String getModuleText() {
        return moduleField.getText();
    }

    /**
     * Sets the module filter text.
     */
    public void setModuleText(String name) {
        moduleField.setText(name != null ? name : "");
        rebuildFilter();
    }

    /**
     * Returns the current search text.
     */
    public String getSearchText() {
        return searchField.getText();
    }

    /**
     * Sets the search text.
     */
    public void setSearchText(String text) {
        searchField.setText(text != null ? text : "");
        rebuildFilter();
    }

    /**
     * Returns true if search is in include mode (false = exclude).
     */
    public boolean isSearchIncludeMode() {
        return searchIncludeBtn.isSelected();
    }

    /**
     * Sets the search mode.
     * @param include true for include, false for exclude
     */
    public void setSearchIncludeMode(boolean include) {
        if (include) {
            searchIncludeBtn.setSelected(true);
        } else {
            searchExcludeBtn.setSelected(true);
        }
        rebuildFilter();
    }

    /**
     * Returns true if search uses regex pattern (false = literal text).
     */
    public boolean isSearchRegexMode() {
        return searchRegexBtn.isSelected();
    }

    /**
     * Sets the search pattern type.
     * @param regex true for regex, false for literal text
     */
    public void setSearchRegexMode(boolean regex) {
        if (regex) {
            searchRegexBtn.setSelected(true);
        } else {
            searchContainsBtn.setSelected(true);
        }
        rebuildFilter();
    }

    // ── Utility ──────────────────────────────────────────────────────────

    private static boolean areFiltersEqual(LogFilter a, LogFilter b) {
        if (a == null && b == null) {
            return true;
        }
        return a == b;
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