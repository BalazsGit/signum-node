package application.module.logging.gui;

import application.api.ModuleContext;
import application.module.logging.LoggingAssignmentStore;
import application.module.logging.LoggingProfileRepository;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.logging.ModuleLoggingProvider;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The "Assignments" sub-tab of a module tab in the Logging module.
 * <p>
 * Shows, in one place, the logging profiles associated with the node profiles for this
 * module (plan §2.5). Each row is a node profile (e.g. "mainnet"); the "Logging profile"
 * column is a select menu holding the module's on-disk logging profiles under
 * {@code conf/{module}/logging/}. An association can be
 * edited or replaced by picking another entry, or removed by choosing "(default)" /
 * pressing "Delete selected". Changes are persisted via the headless
 * {@link LoggingAssignmentStore} (SSOT: {@code conf/node/profiles.json}) and require a
 * node restart to take effect (plan §4.5/2).
 * </p>
 *
 * @see LoggingPanel
 * @see ModuleLoggingProfilePanel
 * @see LoggingAssignmentStore
 */
public class AssignmentPanel extends JPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(AssignmentPanel.class);
    private static final String DEFAULT = "(default)";
    private static final String COL_NODE_PROFILE = "Node profile";
    private static final String COL_LOGGING_PROFILE = "Logging profile";

    private final ModuleContext context;
    private final String moduleId;
    private final LoggingAssignmentStore store;
    private final JTable table;
    private final DefaultTableModel model;
    private final List<String> options = new ArrayList<>();
    /**
     * The "last persisted" reference of every row (node profile → the logging
     * profile value as loaded from / saved to the store). A row whose cell
     * differs from this baseline is UNSAVED and is shown with a trailing
     * {@code " *"} star on the "Node profile" cell (the node configuration
     * convention) — see {@link #isRowUnsaved(int)}.
     */
    private final Map<String, String> baselineValues = new LinkedHashMap<>();

    /**
     * Creates a module-scoped assignments panel.
     *
     * @param context  the host module context (used for the restart request); may be null
     * @param provider the module's logging provider (module id); must not be null
     * @throws IllegalArgumentException if {@code provider} is null
     */
    public AssignmentPanel(ModuleContext context, ModuleLoggingProvider provider) {
        // Defensive icon-font registration (the app registers it at startup in
        // AppearanceModule#init; this covers standalone/test construction).
        IconFontSwing.register(FontAwesome.getIconFont());
        super(new BorderLayout(8, 8));
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        this.context = context;
        this.moduleId = provider.getModuleId();
        this.store = new LoggingAssignmentStore();
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        collectOptions();

        model = new DefaultTableModel(new Object[]{COL_NODE_PROFILE, COL_LOGGING_PROFILE}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 1;
            }
        };
        table = new JTable(model);
        table.setDefaultEditor(Object.class, new ProfileCellEditor());
        // Rows with a pending (unsaved) change are marked with the unsaved star
        // + palette color on the "Node profile" cell (the row's identity — the
        // same convention the node configuration panel uses). The star is
        // DISPLAY-ONLY (applied by the renderer): the model values stay clean,
        // because the CRUD actions read them back by name.
        table.getColumn(COL_NODE_PROFILE).setCellRenderer(new NodeProfileCellRenderer());
        // Any cell edit (the combo commit, a delete reset) makes the row
        // unsaved — repaint it with the new marker state.
        model.addTableModelListener(e -> table.repaint());

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createTitledBorder(
                "Logging profile assignments for module '" + moduleId + "'"));

        JButton deleteBtn = new JButton("Delete selected");
        deleteBtn.setToolTipText("Remove the logging-profile association of the selected node profile");
        deleteBtn.addActionListener(e -> deleteSelected());
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.setToolTipText("Re-scan the node profiles and their logging assignments on disk");
        refreshBtn.addActionListener(e -> refresh());
        JButton applyBtn = new JButton("Apply (restart node)");
        applyBtn.setToolTipText("Persist all associations; a node restart is required for them to take effect");
        applyBtn.addActionListener(e -> applyAssignments());

        // Icon toolbar (same look as the node configuration panel).
        float iconSize = GuiConstants.getHelpIconSize();
        Color iconColor = GuiColors.getButtonIcon();
        deleteBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.TRASH_O, iconSize, iconColor));
        refreshBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.REFRESH, iconSize, iconColor));
        applyBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.CHECK_CIRCLE_O, iconSize, iconColor));
        ConfigurationUtils.fixComponentSize(deleteBtn);
        ConfigurationUtils.fixComponentSize(refreshBtn);
        ConfigurationUtils.fixComponentSize(applyBtn);
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        toolbar.setOpaque(false);
        toolbar.add(deleteBtn);
        toolbar.add(refreshBtn);
        toolbar.add(applyBtn);

        add(scroll, BorderLayout.CENTER);
        add(toolbar, BorderLayout.SOUTH);

        refresh();
    }

    /**
     * Builds the select-menu options in display order: the "(default)" sentinel, then the
     * module's on-disk logging profiles (deduplicated, first occurrence wins).
     */
    private void collectOptions() {
        Set<String> known = new LinkedHashSet<>();
        known.add(DEFAULT);
        try {
            known.addAll(new LoggingProfileRepository().listProfiles(moduleId));
        } catch (Exception e) {
            LOGGER.warn("Failed to list on-disk logging profiles for module '{}': {}",
                    moduleId, e.getMessage());
        }
        options.addAll(known);
    }

    private void refresh() {
        model.setRowCount(0);
        List<String> profiles;
        try {
            profiles = store.listNodeProfiles();
        } catch (Exception e) {
            LOGGER.warn("Failed to list node profiles: {}", e.getMessage());
            profiles = List.of();
        }
        for (String profile : profiles) {
            Map<String, String> assignment;
            try {
                assignment = store.getAssignment(profile);
            } catch (Exception e) {
                assignment = Map.of();
            }
            String loggingProfile = assignment.get(moduleId);
            model.addRow(new Object[]{profile, loggingProfile != null ? loggingProfile : DEFAULT});
        }
        snapshotBaseline();
    }

    /**
     * Whether the row's logging-profile cell differs from what is persisted in
     * the store — i.e. the row has a pending (unsaved) change.
     */
    private boolean isRowUnsaved(int row) {
        if (row < 0 || row >= model.getRowCount()) {
            return false;
        }
        String profile = (String) model.getValueAt(row, 0);
        Object current = model.getValueAt(row, 1);
        String baseline = baselineValues.get(profile);
        return !String.valueOf(current).equals(baseline == null ? "" : baseline);
    }

    /** Re-baselines every row from the model (called after load/save). */
    private void snapshotBaseline() {
        baselineValues.clear();
        for (int row = 0; row < model.getRowCount(); row++) {
            baselineValues.put((String) model.getValueAt(row, 0), String.valueOf(model.getValueAt(row, 1)));
        }
    }

    /** Re-reads the persisted baseline of every row from the store (defensive; never throws). */
    private void reloadBaselineFromStore() {
        for (int row = 0; row < model.getRowCount(); row++) {
            String profile = (String) model.getValueAt(row, 0);
            try {
                String value = store.getAssignment(profile).get(moduleId);
                baselineValues.put(profile, value != null ? value : DEFAULT);
            } catch (Exception e) {
                baselineValues.put(profile, DEFAULT);
            }
        }
    }

    /**
     * Removes the association of the selected row for this module immediately (persisted
     * via the store) and resets the cell to "(default)".
     */
    private void deleteSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Select a node profile row first.",
                    "Delete Assignment", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String profile = (String) model.getValueAt(row, 0);
        try {
            store.setAssignmentForModule(profile, moduleId, null);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "Failed to delete the assignment for '" + profile + "': " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        model.setValueAt(DEFAULT, row, 1);
        baselineValues.put(profile, DEFAULT); // just persisted — the row is not unsaved anymore
        LOGGER.info("Deleted logging assignment for node profile '{}' (module '{}')", profile, moduleId);
    }

    private void applyAssignments() {
        for (int row = 0; row < model.getRowCount(); row++) {
            String profile = (String) model.getValueAt(row, 0);
            String loggingProfile = (String) model.getValueAt(row, 1);
            String value = (loggingProfile == null || DEFAULT.equals(loggingProfile)) ? null : loggingProfile;
            try {
                store.setAssignmentForModule(profile, moduleId, value);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this,
                        "Failed to save assignment for '" + profile + "': " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                // The rows before this one may already be persisted — re-read
                // the baseline from the store so the stars match reality.
                reloadBaselineFromStore();
                return;
            }
        }
        snapshotBaseline(); // everything persisted — no row is unsaved anymore
        int result = JOptionPane.showConfirmDialog(this,
                "Assignments saved.\n\nA node restart is required for the changes to take effect.\nRestart now?",
                "Apply Assignments", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result == JOptionPane.YES_OPTION && context != null) {
            context.requestRestart();
        }
    }

    private class NodeProfileCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
            boolean unsaved = !isSelected && isRowUnsaved(row);
            String text = value == null ? "" : value.toString();
            setText(unsaved ? text + " *" : text);
            // The node configuration panel's semantics: a row that matches
            // the loaded (baseline) value is APPLIED (green); a row with a
            // pending change is UNSAVED (unsaved color + the star).
            setForeground(isSelected
                    ? t.getSelectionForeground()
                    : (unsaved ? GuiColors.getUnsaved() : GuiColors.getApplied()));
            return this;
        }
    }

    private class ProfileCellEditor extends AbstractCellEditor implements TableCellEditor {
        private final JComboBox<String> combo = new JComboBox<>();

        public boolean isCellEditable(Object value, javax.swing.table.TableModel model, int row, int column) {
            return column == 1;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                boolean isSelected, int row, int column) {
            combo.removeAllItems();
            combo.setSelectedItem(null);
            for (String option : options) {
                combo.addItem(option);
            }
            combo.setSelectedItem(value);
            return combo;
        }

        @Override
        public Object getCellEditorValue() {
            return combo.getSelectedItem();
        }
    }
}