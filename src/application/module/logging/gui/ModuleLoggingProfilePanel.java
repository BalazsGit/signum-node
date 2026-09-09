package application.module.logging.gui;

import application.api.ModuleContext;
import application.module.logging.EffectiveProfileResolver;
import application.module.logging.LoggingProfileRepository;
import application.utils.logging.ModuleLoggingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Generic per-module logging profile panel.
 * <p>
 * Instantiated once per registered logging provider (Node, Database, …) and shown as a tab in
 * the {@link LoggingPanel}. Provides a profile selector, a logger-key editor, a preset selector,
 * a CRUD toolbar (New / Save / Apply / Rename / Delete / Refresh), and a read-only effective
 * view (via {@link EffectiveProfileResolver}).
 * </p>
 *
 * <h3>Backend</h3>
 * All CRUD operations delegate to the headless {@link LoggingProfileRepository}; the effective
 * view delegates to {@link EffectiveProfileResolver}. This panel has no direct file I/O.
 *
 * @see LoggingPanel
 * @see LoggingProfileRepository
 */
public class ModuleLoggingProfilePanel extends JPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModuleLoggingProfilePanel.class);

    private static final String[] LOG_LEVELS =
            {"SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST", "ALL", "OFF"};

    private final ModuleContext context;
    private final ModuleLoggingProvider provider;
    private final LoggingProfileRepository repo;
    private final String moduleId;

    private JComboBox<String> profileCombo;
    /** key → editor (a {@code JComboBox} for log levels, a {@code JTextField} otherwise); insertion order preserved. */
    private final Map<String, JComponent> rowEditors = new LinkedHashMap<>();
    /** key → the human-readable label shown in the editor (may equal the key). */
    private final Map<String, String> rowLabels = new LinkedHashMap<>();
    private JComboBox<String> presetCombo;
    private JTable effectiveTable;
    private DefaultTableModel effectiveModel;

    /** The editor row grid; held so rows can be re-rendered for search / dynamic add-key. */
    private JPanel editorGrid;
    /** Live row filter; created in the constructor, hidden until {@link #enableSearch()} is called. */
    private JTextField searchField;
    /** Reserved strip for a host-provided control (e.g. the node "link to node profile" checkbox). */
    private JPanel linkStrip;
    /** Help button; created in the constructor, disabled until {@link #setHelpSupplier} is called. */
    private JButton helpButton;

    // ── Host extension surface (see the set*/enable* methods) ──────────
    private java.util.function.Consumer<String> applyHook;
    private java.util.function.Supplier<String> helpSupplier;

    /**
     * Creates a generic per-module logging profile editor WITHOUT a host {@link ModuleContext}.
     * The Apply action persists via the repository and then invokes the hook registered through
     * {@link #setApplyHook}; if none is set it only marks the profile applied and informs the user
     * that a restart is required. Use this constructor for host panels that own their own
     * restart/apply behaviour (e.g. the node tab).
     *
     * @param provider the module's logging provider (id, defaults, presets); must not be null
     */
    public ModuleLoggingProfilePanel(ModuleLoggingProvider provider) {
        this(null, provider);
    }

    public ModuleLoggingProfilePanel(ModuleContext context, ModuleLoggingProvider provider) {
        super(new BorderLayout(8, 8));
        this.context = context;
        this.provider = provider;
        this.moduleId = provider.getModuleId();
        this.repo = new LoggingProfileRepository();

        setBorder(new EmptyBorder(12, 12, 12, 12));
        add(buildHeader(), BorderLayout.NORTH);
        add(buildProfileSelector(), BorderLayout.PAGE_START);
        add(buildLevelEditor(), BorderLayout.CENTER);
        add(buildPresetAndToolbar(), BorderLayout.PAGE_END);
        add(buildEffectiveView(), BorderLayout.SOUTH);

        refreshProfileList();
        if (profileCombo.getItemCount() > 0) {
            profileCombo.setSelectedIndex(0);
            loadProfileIntoEditor();
        }
    }

    private JComponent buildHeader() {
        JPanel box = new JPanel(new GridLayout(0, 1, 0, 4));
        box.setOpaque(false);
        JLabel title = new JLabel(provider.getProfile().getDisplayName());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        JLabel desc = new JLabel(provider.getProfile().getDescription());
        desc.setFont(desc.getFont().deriveFont(Font.PLAIN, 12f));
        box.add(title);
        box.add(desc);
        box.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        return box;
    }

    private JComponent buildProfileSelector() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setBorder(BorderFactory.createTitledBorder("Profile"));
        profileCombo = new JComboBox<>();
        profileCombo.addActionListener(e -> loadProfileIntoEditor());
        panel.add(new JLabel("Active:"), BorderLayout.WEST);
        panel.add(profileCombo, BorderLayout.CENTER);
        return panel;
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

        // Header strip: a (hidden) live filter + a "+ add key" affordance.
        searchField = new JTextField(16);
        searchField.setVisible(false);
        searchField.addActionListener(e -> renderEditorRows());

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

        JPanel filterBox = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        filterBox.setOpaque(false);
        filterBox.add(new JLabel("Filter:"));
        filterBox.add(searchField);

        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        header.add(filterBox, BorderLayout.WEST);
        header.add(addRowPanel, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(editorGrid);
        scroll.setBorder(BorderFactory.createTitledBorder("Logger levels"));

        JPanel wrap = new JPanel(new BorderLayout(0, 4));
        wrap.setOpaque(false);
        wrap.add(header, BorderLayout.NORTH);
        wrap.add(scroll, BorderLayout.CENTER);
        return wrap;
    }

    private void addRow(String label, String key, String defaultValue) {
        String display = (label != null && !label.isBlank()) ? label : key;
        JComponent editor = makeEditor(defaultValue);
        rowEditors.put(key, editor);
        rowLabels.put(key, display);
        renderEditorRows();
    }

    private void renderEditorRows() {
        if (editorGrid == null) {
            return; // construction in progress
        }
        String filter = currentFilter();
        editorGrid.removeAll();
        for (Map.Entry<String, JComponent> entry : rowEditors.entrySet()) {
            String key = entry.getKey();
            String label = rowLabels.getOrDefault(key, key);
            if (!filter.isEmpty()
                    && !(label.toLowerCase().contains(filter) || key.toLowerCase().contains(filter))) {
                continue;
            }
            JLabel lbl = new JLabel(label);
            lbl.setToolTipText(key);
            editorGrid.add(lbl);
            editorGrid.add(entry.getValue());
        }
        editorGrid.revalidate();
        editorGrid.repaint();
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
        return searchField == null ? "" : searchField.getText().trim().toLowerCase();
    }

    private JComponent buildPresetAndToolbar() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setBorder(BorderFactory.createTitledBorder("Preset & actions"));

        Map<String, Map<String, String>> presets = provider.getProfile().getPresetOverrides();
        JPanel presetPanel = new JPanel(new BorderLayout(6, 0));
        presetPanel.add(new JLabel("Preset:"), BorderLayout.WEST);
        presetCombo = new JComboBox<>();
        presetCombo.addItem("(none)");
        for (String name : presets.keySet()) {
            presetCombo.addItem(name);
        }
        presetCombo.addActionListener(e -> applyPresetToEditor());
        presetPanel.add(presetCombo, BorderLayout.CENTER);
        panel.add(presetPanel, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        buttons.setOpaque(false);

        JButton newBtn = new JButton("New");
        newBtn.setToolTipText("Create a new profile (optionally seeded from the selected preset)");
        newBtn.addActionListener(e -> createNewProfile());
        buttons.add(newBtn);

        JButton saveBtn = new JButton("Save");
        saveBtn.setToolTipText("Save the current editor state to the selected profile");
        saveBtn.addActionListener(e -> saveCurrentProfile());
        buttons.add(saveBtn);

        JButton applyBtn = new JButton("Apply");
        applyBtn.setToolTipText("Mark the selected profile as applied (requires node restart)");
        applyBtn.addActionListener(e -> applyProfile());
        buttons.add(applyBtn);

        JButton renameBtn = new JButton("Rename");
        renameBtn.setToolTipText("Rename the selected profile");
        renameBtn.addActionListener(e -> renameProfile());
        buttons.add(renameBtn);

        JButton deleteBtn = new JButton("Delete");
        deleteBtn.setToolTipText("Delete the selected profile");
        deleteBtn.addActionListener(e -> deleteProfile());
        buttons.add(deleteBtn);

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.setToolTipText("Re-scan profiles on disk");
        refreshBtn.addActionListener(e -> {
            refreshProfileList();
            loadProfileIntoEditor();
        });
        buttons.add(refreshBtn);

        JButton resetBtn = new JButton("Reset to Defaults");
        resetBtn.setToolTipText("Set every row back to the module's default value (not saved until you Save)");
        resetBtn.addActionListener(e -> resetToDefaults());
        buttons.add(resetBtn);

        JButton reloadBtn = new JButton("Reload");
        reloadBtn.setToolTipText("Re-read the selected profile from disk (discards unsaved edits)");
        reloadBtn.addActionListener(e -> loadProfileIntoEditor());
        buttons.add(reloadBtn);

        helpButton = new JButton("Help");
        helpButton.setToolTipText("Show module-specific help");
        helpButton.setEnabled(false);
        helpButton.addActionListener(e -> showHelp());
        buttons.add(helpButton);

        panel.add(buttons, BorderLayout.CENTER);

        // Reserved host strip (e.g. the node "link to node profile" checkbox).
        linkStrip = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        linkStrip.setOpaque(false);
        panel.add(linkStrip, BorderLayout.SOUTH);

        return panel;
    }

    private JComponent buildEffectiveView() {
        effectiveModel = new DefaultTableModel(new Object[]{"Key", "Effective value", "Source"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        effectiveTable = new JTable(effectiveModel);
        effectiveTable.setFillsViewportHeight(true);
        effectiveTable.getColumnModel().getColumn(0).setPreferredWidth(260);
        effectiveTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        effectiveTable.getColumnModel().getColumn(2).setPreferredWidth(100);

        JScrollPane scroll = new JScrollPane(effectiveTable);
        scroll.setPreferredSize(new java.awt.Dimension(0, 120));
        scroll.setBorder(BorderFactory.createTitledBorder("Effective composition (read-only)"));

        JButton refreshEffective = new JButton("Recompute");
        refreshEffective.addActionListener(e -> refreshEffectiveView());
        JPanel south = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        south.setOpaque(false);
        south.add(refreshEffective);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(scroll, BorderLayout.CENTER);
        wrap.add(south, BorderLayout.SOUTH);
        return wrap;
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
        profileCombo.addItem(LoggingProfileRepository.RESERVED_PROFILE_NAME);
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
    }

    private void loadProfileIntoEditor() {
        String name = (String) profileCombo.getSelectedItem();
        if (name == null) {
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
        refreshEffectiveView();
    }

    private void applyPresetToEditor() {
        String preset = (String) presetCombo.getSelectedItem();
        if (preset == null || "(none)".equals(preset)) {
            return;
        }
        Map<String, Map<String, String>> presets = provider.getProfile().getPresetOverrides();
        Map<String, String> overrides = presets.get(preset);
        if (overrides == null) {
            return;
        }
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            JComponent editor = rowEditors.get(entry.getKey());
            if (editor != null) {
                setEditorValue(editor, entry.getValue());
            }
        }
        refreshEffectiveView();
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

    private void refreshEffectiveView() {
        effectiveModel.setRowCount(0);
        String name = (String) profileCombo.getSelectedItem();
        if (name == null) {
            return;
        }
        try {
            EffectiveProfileResolver resolver = new EffectiveProfileResolver();
            List<EffectiveProfileResolver.EffectiveKey> keys = resolver.previewComposition(name);
            for (EffectiveProfileResolver.EffectiveKey key : keys) {
                effectiveModel.addRow(new Object[]{key.key(), key.value(), key.source().label()});
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to compute effective view: {}", e.getMessage());
            effectiveModel.addRow(new Object[]{"(error)", e.getMessage(), ""});
        }
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
        String preset = (String) presetCombo.getSelectedItem();
        String presetName = "(none)".equals(preset) ? null : preset;
        try {
            repo.create(moduleId, name, presetName);
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
        try {
            repo.saveProps(moduleId, name, collectEditorProps());
            LOGGER.info("Saved profile '{}' for module '{}'", name, moduleId);
            refreshEffectiveView();
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
        int result = JOptionPane.showConfirmDialog(this,
                "Apply profile '" + name + "' for module '" + moduleId + "'?\n\n"
                + "This marks it as applied. A node restart is required for the change to take effect.",
                "Apply Profile", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            saveCurrentProfile();
            repo.setApplied(moduleId, name);
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
        if (oldName == null || LoggingProfileRepository.RESERVED_PROFILE_NAME.equals(oldName)) {
            JOptionPane.showMessageDialog(this, "The reserved profile cannot be renamed.",
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
        if (name == null || LoggingProfileRepository.RESERVED_PROFILE_NAME.equals(name)) {
            JOptionPane.showMessageDialog(this, "The reserved profile cannot be deleted.",
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
        Map<String, String> defaults = provider.getProfile().getDefaults();
        for (Map.Entry<String, JComponent> entry : rowEditors.entrySet()) {
            String d = defaults.get(entry.getKey());
            if (d != null) {
                setEditorValue(entry.getValue(), d);
            }
        }
        refreshEffectiveView();
    }

    private void showHelp() {
        if (helpSupplier == null) {
            return;
        }
        String html = helpSupplier.get();
        if (html == null || html.isBlank()) {
            return;
        }
        JOptionPane.showMessageDialog(this, html, "Help", JOptionPane.INFORMATION_MESSAGE);
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
     * Registers a supplier of HTML help text shown by the Help button. Return {@code null} (or
     * blank) to hide the Help button for this host.
     *
     * @param helpSupplier the help-text supplier (may be null to hide Help)
     * @return {@code this} for fluent chaining
     */
    public ModuleLoggingProfilePanel setHelpSupplier(java.util.function.Supplier<String> helpSupplier) {
        this.helpSupplier = helpSupplier;
        if (helpButton != null) {
            String preview = helpSupplier == null ? null : helpSupplier.get();
            boolean has = preview != null && !preview.isBlank();
            helpButton.setEnabled(has);
            helpButton.setVisible(has);
        }
        return this;
    }

    /**
     * Enables the live, case-insensitive text filter over the key→value editor rows (matches both
     * the label and the key). Clearing the filter shows all rows again.
     *
     * @return {@code this} for fluent chaining
     */
    public ModuleLoggingProfilePanel enableSearch() {
        if (searchField != null) {
            searchField.setVisible(true);
            searchField.revalidate();
            searchField.repaint();
        }
        return this;
    }
}