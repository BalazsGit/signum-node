package application.module.logging.gui;

import application.api.ModuleContext;
import application.module.logging.LoggingAssignmentStore;
import application.utils.logging.LoggingModuleRegistry;
import application.utils.logging.ModuleLoggingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Assignments" tab of the Logging module.
 * <p>
 * Manages the node-profile → module-preset assignment table (plan §2.5). Each row is a node
 * profile (e.g. "mainnet"); each module column holds a preset selector. Changes are persisted via
 * the headless {@link LoggingAssignmentStore} and require a node restart to take effect
 * (plan §4.5/2).
 * </p>
 *
 * @see LoggingPanel
 * @see LoggingAssignmentStore
 */
public class AssignmentPanel extends JPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(AssignmentPanel.class);
    private static final String DEFAULT = "(default)";

    private final ModuleContext context;
    private final LoggingAssignmentStore store;
    private final JTable table;
    private final DefaultTableModel model;
    private final List<String> moduleIds = new ArrayList<>();
    private final Map<String, List<String>> modulePresets = new HashMap<>();

    public AssignmentPanel(ModuleContext context) {
        super(new BorderLayout(8, 8));
        this.context = context;
        this.store = new LoggingAssignmentStore();
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        collectModules();

        List<String> columnNames = new ArrayList<>();
        columnNames.add("Node profile");
        columnNames.addAll(moduleIds);
        model = new DefaultTableModel(columnNames.toArray(new String[0]), 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column > 0;
            }
        };
        table = new JTable(model);
        table.setDefaultEditor(Object.class, new PresetCellEditor());

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createTitledBorder("Node profile → module preset assignments"));

        JButton applyBtn = new JButton("Apply (restart node)");
        applyBtn.addActionListener(e -> applyAssignments());
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> refresh());
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        toolbar.setOpaque(false);
        toolbar.add(refreshBtn);
        toolbar.add(applyBtn);

        add(scroll, BorderLayout.CENTER);
        add(toolbar, BorderLayout.SOUTH);

        refresh();
    }

    private void collectModules() {
        for (ModuleLoggingProvider provider : LoggingModuleRegistry.getInstance().getAllProviders()) {
            String id = provider.getModuleId();
            if (!moduleIds.contains(id)) {
                moduleIds.add(id);
                modulePresets.put(id, new ArrayList<>(provider.getProfile().getPresetOverrides().keySet()));
            }
        }
        if (!moduleIds.contains("node")) {
            moduleIds.add("node");
            modulePresets.put("node", new ArrayList<>());
        }
        if (!moduleIds.contains("database")) {
            moduleIds.add("database");
            modulePresets.put("database", new ArrayList<>());
        }
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
            Object[] row = new Object[moduleIds.size() + 1];
            row[0] = profile;
            for (int i = 0; i < moduleIds.size(); i++) {
                String preset = assignment.get(moduleIds.get(i));
                row[i + 1] = preset != null ? preset : DEFAULT;
            }
            model.addRow(row);
        }
    }

    private void applyAssignments() {
        for (int row = 0; row < model.getRowCount(); row++) {
            String profile = (String) model.getValueAt(row, 0);
            Map<String, String> assignment = new HashMap<>();
            for (int col = 1; col < model.getColumnCount(); col++) {
                String moduleId = moduleIds.get(col - 1);
                String preset = (String) model.getValueAt(row, col);
                if (preset != null && !DEFAULT.equals(preset)) {
                    assignment.put(moduleId, preset);
                }
            }
            try {
                store.setAssignment(profile, assignment);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this,
                        "Failed to save assignment for '" + profile + "': " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        int result = JOptionPane.showConfirmDialog(this,
                "Assignments saved.\n\nA node restart is required for the changes to take effect.\nRestart now?",
                "Apply Assignments", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result == JOptionPane.YES_OPTION) {
            context.requestRestart();
        }
    }

    private class PresetCellEditor extends javax.swing.AbstractCellEditor implements javax.swing.table.TableCellEditor {
        private final JComboBox<String> combo = new JComboBox<>();

        public boolean isCellEditable(Object value, javax.swing.table.TableModel model, int row, int column) {
            return column > 0;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                boolean isSelected, int row, int column) {
            combo.removeAllItems();
            combo.addItem(DEFAULT);
            if (column > 0) {
                String moduleId = moduleIds.get(column - 1);
                for (String preset : modulePresets.getOrDefault(moduleId, List.of())) {
                    combo.addItem(preset);
                }
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