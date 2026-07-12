package application.module.node.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractCellEditor;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import application.utils.logging.ConsoleColorScheme;
import application.utils.logging.ConsoleSettings;

/**
 * Configuration panel for console profile colors.
 * <p>
 * Allows the user to view and customize the color assigned to each logging
 * profile. Changes are applied immediately to the underlying
 * {@link ConsoleColorScheme} and can be persisted via {@link ConsoleSettings}.
 * </p>
 *
 * @see ConsoleColorScheme
 * @see ConsoleSettings
 */
public final class ConsoleColorPanel extends JPanel {

    /** Default settings file path for console color persistence. */
    public static final Path DEFAULT_SETTINGS_PATH = Paths.get("settings", "console-settings.json");

    private final ConsoleColorScheme colorScheme;
    private final ColorTableModel tableModel;
    private JTable colorTable;
    private JTextField searchField;

    // ── Constructor ──────────────────────────────────────────────────────

    /**
     * Creates a new color configuration panel backed by the given scheme.
     *
     * @param colorScheme the shared color scheme (must not be null)
     */
    public ConsoleColorPanel(ConsoleColorScheme colorScheme) {
        if (colorScheme == null) {
            throw new NullPointerException("ConsoleColorScheme must not be null");
        }
        this.colorScheme = colorScheme;
        this.tableModel = new ColorTableModel();
        initUI();
        refreshTable();
    }

    // ── UI Initialization ────────────────────────────────────────────────

    private void initUI() {
        setLayout(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(12, 12, 12, 12));

        add(new JLabel("Profile Color Configuration"), BorderLayout.NORTH);

        colorTable = new JTable(tableModel);
        colorTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        colorTable.setAutoCreateRowSorter(true);
        colorTable.getRowHeight();
        colorTable.getColumn("Color").setCellRenderer(new ColorCellRenderer());
        colorTable.getColumn("Color").setPreferredWidth(80);
        colorTable.getColumn("Profile").setPreferredWidth(200);
        colorTable.getColumn("Action").setPreferredWidth(160);
        colorTable.getColumn("Action").setCellRenderer(new ActionCellRenderer());
        colorTable.getColumn("Action").setCellEditor(new ActionCellEditor(this));

        JScrollPane scrollPane = new JScrollPane(colorTable);
        scrollPane.setBorder(new TitledBorder("Assigned Profiles"));
        add(scrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout(8, 8));
        bottomPanel.setOpaque(false);

        searchField = new JTextField(15);
        searchField.setToolTipText("Filter profiles by name");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void changedUpdate(DocumentEvent e) { filterTable(); }
            @Override public void insertUpdate(DocumentEvent e) { filterTable(); }
            @Override public void removeUpdate(DocumentEvent e) { filterTable(); }
        });
        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        searchPanel.setOpaque(false);
        searchPanel.add(new JLabel("Find:"));
        searchPanel.add(searchField);
        bottomPanel.add(searchPanel, BorderLayout.WEST);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttonPanel.setOpaque(false);
        JButton saveBtn = new JButton("Save Settings");
        saveBtn.addActionListener(e -> saveSettings());
        JButton resetAllBtn = new JButton("Reset All");
        resetAllBtn.addActionListener(e -> resetAllColors());
        buttonPanel.add(saveBtn);
        buttonPanel.add(resetAllBtn);
        bottomPanel.add(buttonPanel, BorderLayout.EAST);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void filterTable() {
        String query = searchField.getText();
        if (query == null || query.trim().isEmpty()) {
            tableModel.setFilter(null);
        } else {
            tableModel.setFilter(query.trim().toLowerCase());
        }
    }

    // ── Public API ───────────────────────────────────────────────────────

    /** Refreshes the table from the current color scheme state. Must be called on EDT. */
    public void refreshTable() {
        tableModel.refreshFromScheme(colorScheme);
    }

    /** Opens a color chooser for the selected row's profile. Must be called on EDT. */
    public void changeColorForRow(int rowIndex) {
        int modelIndex = colorTable.convertRowIndexToModel(rowIndex);
        String profileName = (String) tableModel.getValueAt(modelIndex, 0);
        Color currentColor = (Color) tableModel.getValueAt(modelIndex, 1);

        Color newColor = JColorChooser.showDialog(this, "Choose color for: " + profileName, currentColor);
        if (newColor != null) {
            colorScheme.setCustomColor(profileName, newColor);
            tableModel.fireTableRowUpdated(modelIndex);
        }
    }

    /** Resets the custom color for the selected row's profile. */
    public void resetColorForRow(int rowIndex) {
        int modelIndex = colorTable.convertRowIndexToModel(rowIndex);
        String profileName = (String) tableModel.getValueAt(modelIndex, 0);

        boolean cleared = colorScheme.clearCustomColor(profileName);
        if (cleared) {
            colorScheme.getColorForProfile(profileName);
            tableModel.fireTableRowUpdated(modelIndex);
        }
    }

    /** Resets ALL custom colors, reverting every profile to auto-assigned colors. */
    public void resetAllColors() {
        colorScheme.reset();
        refreshTable();
    }

    /** Persists the current color scheme state to ConsoleSettings JSON file. */
    public void saveSettings() {
        ConsoleSettings settings = new ConsoleSettings();
        settings.syncFrom(colorScheme);
        try {
            settings.save(DEFAULT_SETTINGS_PATH);
        } catch (Exception e) {
            System.err.println("[ConsoleColorPanel] Failed to save settings: " + e.getMessage());
        }
    }

    /** Loads and applies colors from a ConsoleSettings JSON file. */
    public void loadSettings(Path path) {
        ConsoleSettings settings = ConsoleSettings.load(path);
        if (settings != null) {
            settings.applyTo(colorScheme);
            refreshTable();
        }
    }

    /** Returns the underlying color scheme. */
    public ConsoleColorScheme getColorScheme() {
        return colorScheme;
    }

    // ── Inner: Table Model ───────────────────────────────────────────────

    private class ColorTableModel extends AbstractTableModel {

        private static final String[] COLUMN_NAMES = {"Profile", "Color", "Action"};
        private static final int COL_PROFILE = 0;
        private static final int COL_COLOR = 1;
        private static final int COL_ACTION = 2;

        private final List<String> profiles = new ArrayList<>();
        private final List<Color> colors = new ArrayList<>();
        private volatile String filterQuery = null;

        void refreshFromScheme(ConsoleColorScheme scheme) {
            profiles.clear();
            colors.clear();

            List<String> assigned = scheme.getAssignedProfiles();
            if (assigned != null) {
                for (String profile : assigned) {
                    profiles.add(profile);
                    colors.add(scheme.getColorForProfile(profile));
                }
            }
            fireTableDataChanged();
        }

        void setFilter(String query) {
            this.filterQuery = query;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            if (filterQuery == null || filterQuery.isEmpty()) {
                return profiles.size();
            }
            int count = 0;
            for (String p : profiles) {
                if (p.toLowerCase().contains(filterQuery)) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public int getColumnCount() {
            return COLUMN_NAMES.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMN_NAMES[column];
        }

        private int modelIndexForViewRow(int viewRow) {
            if (filterQuery == null || filterQuery.isEmpty()) {
                return viewRow;
            }
            int count = 0;
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).toLowerCase().contains(filterQuery)) {
                    if (count == viewRow) return i;
                    count++;
                }
            }
            return -1;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            int modelIdx = modelIndexForViewRow(rowIndex);
            if (modelIdx < 0) return null;

            switch (columnIndex) {
                case COL_PROFILE:  return profiles.get(modelIdx);
                case COL_COLOR:    return colors.get(modelIdx);
                case COL_ACTION:   return "Change | Reset";
                default:           return null;
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == COL_ACTION;
        }

        void fireTableRowUpdated(int modelIndex) {
            if (modelIndex >= 0 && modelIndex < profiles.size()) {
                String profile = profiles.get(modelIndex);
                colors.set(modelIndex, colorScheme.getColorForProfile(profile));
            }
            fireTableRowsUpdated(modelIndex, modelIndex);
        }
    }

    // ── Inner: Color Cell Renderer ───────────────────────────────────────

    private static final class ColorCellRenderer extends JPanel implements TableCellRenderer {

        private final JLabel colorSwatch = new JLabel();

        ColorCellRenderer() {
            setLayout(new BorderLayout());
            setBorder(new EmptyBorder(2, 4, 2, 4));
            setOpaque(false);
            colorSwatch.setPreferredSize(new java.awt.Dimension(24, 16));
            colorSwatch.setBorder(javax.swing.BorderFactory.createLineBorder(Color.GRAY));
            add(colorSwatch, BorderLayout.CENTER);
        }

        @Override
        public java.awt.Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Color c = (value instanceof Color) ? (Color) value : Color.LIGHT_GRAY;
            colorSwatch.setBackground(c);
            return this;
        }
    }

    // ── Inner: Action Cell Renderer ──────────────────────────────────────

    private static final class ActionCellRenderer extends JPanel implements TableCellRenderer {

        private final JButton changeBtn = new JButton("Change");
        private final JButton resetBtn = new JButton("Reset");

        ActionCellRenderer() {
            setLayout(new FlowLayout(FlowLayout.CENTER, 4, 0));
            setOpaque(false);
            changeBtn.setContentAreaFilled(false);
            changeBtn.setBorderPainted(false);
            changeBtn.setForeground(new Color(70, 130, 180));
            resetBtn.setContentAreaFilled(false);
            resetBtn.setBorderPainted(false);
            resetBtn.setForeground(Color.GRAY);
            add(changeBtn);
            add(resetBtn);
        }

        @Override
        public java.awt.Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            return this;
        }
    }

    // ── Inner: Action Cell Editor ────────────────────────────────────────

    private class ActionCellEditor extends AbstractCellEditor implements TableCellEditor {

        private final ConsoleColorPanel parent;
        private int editedRowIndex;

        ActionCellEditor(ConsoleColorPanel parent) {
            this.parent = parent;
        }

        @Override
        public java.awt.Component getTableCellEditorComponent(JTable table, Object value,
                boolean isSelected, int row, int column) {
            this.editedRowIndex = row;
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
            panel.setOpaque(false);

            JButton changeBtn = new JButton("Change Color");
            changeBtn.addActionListener(e -> {
                parent.changeColorForRow(editedRowIndex);
                fireEditingStopped();
            });

            JButton resetBtn = new JButton("Reset");
            resetBtn.addActionListener(e -> {
                parent.resetColorForRow(editedRowIndex);
                fireEditingStopped();
            });

            panel.add(changeBtn);
            panel.add(resetBtn);
            return panel;
        }

        @Override
        public Object getCellEditorValue() {
            return "Change | Reset";
        }
    }
}