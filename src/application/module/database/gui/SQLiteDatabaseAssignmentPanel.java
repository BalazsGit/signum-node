package application.module.database.gui;

import application.module.database.profile.SQLiteProfile;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.NodeProfileRepository;
import application.module.node.profile.SqliteDatabasePaths;
import application.module.node.props.Props;
import application.utils.config.ModuleIds;
import application.utils.config.PropertiesProfileLoader;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

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
import java.awt.Component;
import java.awt.FlowLayout;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * SQLite database-profile assignments for node profiles.
 *
 * <p>SQLite assignments are derived from each node profile's {@code DB.Url};
 * unlike server database links, they are not stored in {@code profiles.json}.
 * Changes are staged in the table and written to the node properties files
 * when applied.</p>
 */
final class SQLiteDatabaseAssignmentPanel extends JPanel {

    private static final String DEFAULT = "(default)";
    private static final String CUSTOM = "(custom URL)";
    private static final String COL_NODE_PROFILE = "Node profile";
    private static final String COL_SQLITE_PROFILE = "SQLite profile";
    private static final String COL_DATABASE_FILE = "Database file / URL";

    private final String confRoot;
    private final DefaultTableModel model;
    private final JTable table;
    private final List<String> options = new ArrayList<>();
    private final Map<String, String> baseline = new LinkedHashMap<>();

    SQLiteDatabaseAssignmentPanel(String confRoot) {
        super(new BorderLayout(8, 8));
        this.confRoot = confRoot;
        IconFontSwing.register(FontAwesome.getIconFont());
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        model = new DefaultTableModel(
                new Object[] {COL_NODE_PROFILE, COL_SQLITE_PROFILE, COL_DATABASE_FILE}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 1;
            }
        };
        table = new JTable(model);
        table.setDefaultEditor(Object.class, new ProfileCellEditor());
        table.getColumn(COL_NODE_PROFILE).setCellRenderer(new NodeProfileCellRenderer());
        model.addTableModelListener(event -> {
            if (event.getType() == javax.swing.event.TableModelEvent.UPDATE
                    && event.getColumn() == 1
                    && event.getFirstRow() >= 0
                    && event.getFirstRow() < model.getRowCount()) {
                updateDatabaseFileCell(event.getFirstRow());
            }
            table.repaint();
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createTitledBorder("SQLite database profile assignments"));
        add(scroll, BorderLayout.CENTER);

        JButton delete = iconButton(FontAwesome.TRASH_O, "Remove the selected SQLite profile assignment");
        delete.addActionListener(event -> removeSelectedAssignment());
        JButton refresh = iconButton(FontAwesome.REFRESH, "Reload node profiles and assignments from disk");
        refresh.addActionListener(event -> refresh());
        JButton apply = iconButton(FontAwesome.CHECK_CIRCLE_O, "Save assignments and restart affected nodes");
        apply.addActionListener(event -> applyAssignments());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(delete);
        actions.add(refresh);
        actions.add(apply);
        add(actions, BorderLayout.SOUTH);

        refresh();
    }

    private static JButton iconButton(FontAwesome glyph, String tooltip) {
        JButton button = new JButton();
        button.setToolTipText(tooltip);
        ConfigurationUtils.styleProfileIconButton(button, glyph,
                glyph == FontAwesome.TRASH_O ? GuiColors.getContrastRed() : GuiColors.getButtonIcon(),
                GuiConstants.getHelpIconSize());
        return button;
    }

    private void refresh() {
        model.setRowCount(0);
        options.clear();
        options.add(DEFAULT);
        options.addAll(DatabaseConfigurationUtils.getProfileNames(confRoot, "SQLite"));

        for (String name : NodeProfileRepository.discoverProfileNames(confRoot)) {
            NodeProfile profile = NodeProfileRepository.loadProfile(confRoot, name);
            String url = profile == null ? null : profile.getProperties().getProperty(Props.DB_URL.getName());
            String assignment = assignedProfile(url);
            model.addRow(new Object[] {name, assignment,
                    CUSTOM.equals(assignment) ? databaseFilePath(url) : ""});
        }
        snapshotBaseline();
    }

    void refreshIfSafe() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        if (!hasUnsavedRows()) {
            refresh();
        }
    }

    private boolean hasUnsavedRows() {
        for (int row = 0; row < model.getRowCount(); row++) {
            if (isUnsaved(row)) {
                return true;
            }
        }
        return false;
    }

    static String assignedProfile(String url) {
        if (url == null || url.isBlank()) {
            return DEFAULT;
        }
        SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(url);
        if (parts == null || !SqliteDatabasePaths.isDbFile(parts.fileName())) {
            return CUSTOM;
        }
        String profile = SqliteDatabasePaths.deriveProfileName(parts.dir());
        List<String> profiles = DatabaseConfigurationUtils.getProfileNames(null, "SQLite");
        if (profile != null && profiles.contains(profile)) {
            return profile;
        }
        Path urlDirectory = parts.dir().toAbsolutePath().normalize();
        for (String candidate : profiles) {
            if (SqliteDatabasePaths.profileDir(candidate).toAbsolutePath().normalize().equals(urlDirectory)) {
                return candidate;
            }
        }
        return CUSTOM;
    }

    static String databaseFilePath(String url) {
        SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(url);
        if (parts == null) {
            return url == null ? "" : url;
        }
        Path path = parts.fileName().isEmpty()
                ? parts.dir()
                : parts.dir().resolve(parts.fileName());
        return path.toAbsolutePath().normalize().toString();
    }

    static List<String> assignedNodeProfiles(String confRoot, String databaseProfile) throws IOException {
        List<String> assigned = new ArrayList<>();
        for (String nodeProfile : NodeProfileRepository.discoverProfileNames(confRoot)) {
            Path file = nodeProfileFile(confRoot, nodeProfile);
            Properties properties = loadProperties(file);
            if (isAssignedTo(properties.getProperty(Props.DB_URL.getName()), databaseProfile)) {
                assigned.add(nodeProfile);
            }
        }
        return assigned;
    }

    static void renameAssignments(String confRoot, String oldName, String newName) throws IOException {
        List<ProfileUpdate> updates = new ArrayList<>();
        for (String nodeProfile : NodeProfileRepository.discoverProfileNames(confRoot)) {
            Path file = nodeProfileFile(confRoot, nodeProfile);
            byte[] original = Files.readAllBytes(file);
            Properties properties = loadProperties(file);
            String url = properties.getProperty(Props.DB_URL.getName());
            if (isAssignedTo(url, oldName)) {
                SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(url);
                String updatedUrl = SqliteDatabasePaths.composeUrl(
                        SqliteDatabasePaths.profileDir(newName), parts.fileName());
                int queryIndex = url.indexOf('?');
                if (queryIndex >= 0) {
                    updatedUrl += url.substring(queryIndex);
                }
                properties.setProperty(Props.DB_URL.getName(), updatedUrl);
                updates.add(new ProfileUpdate(file, original, properties));
            }
        }

        List<ProfileUpdate> written = new ArrayList<>();
        try {
            for (ProfileUpdate update : updates) {
                ConfigurationUtils.savePropertiesPreservingFormat(
                        update.file, update.properties, Set.of(Props.DB_URL.getName()));
                written.add(update);
            }
        } catch (IOException e) {
            for (ProfileUpdate update : written) {
                try {
                    Files.write(update.file, update.original);
                } catch (IOException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
            }
            throw e;
        }
    }

    private static boolean isAssignedTo(String url, String profileName) {
        SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(url);
        if (parts == null || !SqliteDatabasePaths.isDbFile(parts.fileName())) {
            return false;
        }
        if (profileName.equals(SqliteDatabasePaths.deriveProfileName(parts.dir()))) {
            return true;
        }
        return SqliteDatabasePaths.profileDir(profileName).toAbsolutePath().normalize()
                .equals(parts.dir().toAbsolutePath().normalize());
    }

    private static Path nodeProfileFile(String confRoot, String nodeProfile) {
        return PropertiesProfileLoader.resolveProfileFile(
                confRoot, ModuleIds.NODE, ModuleIds.CATEGORY_PROFILES, nodeProfile);
    }

    private static Properties loadProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        return properties;
    }

    private record ProfileUpdate(Path file, byte[] original, Properties properties) {
    }

    private boolean isUnsaved(int row) {
        if (row < 0 || row >= model.getRowCount()) {
            return false;
        }
        String name = String.valueOf(model.getValueAt(row, 0));
        String value = String.valueOf(model.getValueAt(row, 1));
        return !value.equals(baseline.getOrDefault(name, ""));
    }

    private void snapshotBaseline() {
        baseline.clear();
        for (int row = 0; row < model.getRowCount(); row++) {
            baseline.put(String.valueOf(model.getValueAt(row, 0)),
                    String.valueOf(model.getValueAt(row, 1)));
        }
    }

    private void updateDatabaseFileCell(int row) {
        String nodeProfile = String.valueOf(model.getValueAt(row, 0));
        String assignment = String.valueOf(model.getValueAt(row, 1));
        String path = "";
        if (CUSTOM.equals(assignment)) {
            NodeProfile profile = NodeProfileRepository.loadProfile(confRoot, nodeProfile);
            String url = profile == null ? null
                    : profile.getProperties().getProperty(Props.DB_URL.getName());
            path = databaseFilePath(url);
        }
        model.setValueAt(path, row, 2);
    }

    private void removeSelectedAssignment() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Select a node profile first.",
                    "Remove SQLite Assignment", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        model.setValueAt(DEFAULT, row, 1);
    }

    private void applyAssignments() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        Set<String> changed = new LinkedHashSet<>();
        for (int row = 0; row < model.getRowCount(); row++) {
            if (!isUnsaved(row)) {
                continue;
            }
            String nodeProfile = String.valueOf(model.getValueAt(row, 0));
            String assignment = String.valueOf(model.getValueAt(row, 1));
            try {
                saveAssignment(nodeProfile, assignment);
                changed.add(nodeProfile);
                baseline.put(nodeProfile, assignment);
            } catch (IOException | IllegalArgumentException e) {
                JOptionPane.showMessageDialog(this,
                        "Failed to save the SQLite assignment for '" + nodeProfile + "': " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        table.repaint();
        if (changed.isEmpty()) {
            JOptionPane.showMessageDialog(this, "There are no assignment changes to save.",
                    "SQLite Assignments", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int result = JOptionPane.showConfirmDialog(this,
                "SQLite assignments saved.\n\nA node restart is required for changes to take effect.\n"
                        + "Restart the affected nodes now?",
                "Apply SQLite Assignments", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result == JOptionPane.YES_OPTION) {
            application.module.node.NodeModule nodeModule =
                    application.module.node.NodeModule.getInstance();
            for (String profileName : changed) {
                application.module.node.Signum node =
                        nodeModule.get(profileName);
                if (node != null && node.isRunning()) {
                    nodeModule.restartNode(profileName);
                }
            }
        }
    }

    void saveAssignment(String nodeProfile, String assignment) throws IOException {
        Path file = PropertiesProfileLoader.resolveProfileFile(confRoot,
                ModuleIds.NODE, ModuleIds.CATEGORY_PROFILES, nodeProfile);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Node profile file not found: " + file);
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        if (DEFAULT.equals(assignment)) {
            properties.remove(Props.DB_URL.getName());
        } else if (CUSTOM.equals(assignment)) {
            return;
        } else {
            if (!options.contains(assignment)) {
                throw new IllegalArgumentException("Unknown SQLite profile: " + assignment);
            }
            properties.setProperty(Props.DB_URL.getName(), profileUrl(assignment));
        }
        ConfigurationUtils.savePropertiesPreservingFormat(file, properties, Set.of(Props.DB_URL.getName()));
    }

    private static String profileUrl(String profileName) throws IOException {
        Path profileFile = SqliteDatabasePaths.profileDir(profileName).resolve("profile.json");
        if (Files.isRegularFile(profileFile)) {
            try (Reader reader = Files.newBufferedReader(profileFile)) {
                com.google.gson.JsonObject json =
                        com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                if (json.has(SQLiteProfile.CFG_URL) && !json.get(SQLiteProfile.CFG_URL).getAsString().isBlank()) {
                    return json.get(SQLiteProfile.CFG_URL).getAsString();
                }
            } catch (RuntimeException e) {
                throw new IOException("Invalid SQLite profile file: " + profileFile, e);
            }
        }
        return new SQLiteProfile(profileName).getDbUrl();
    }

    private final class NodeProfileCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable source, Object value,
                boolean selected, boolean focus, int row, int column) {
            super.getTableCellRendererComponent(source, value, selected, focus, row, column);
            String text = value == null ? "" : value.toString();
            boolean unsaved = !selected && isUnsaved(row);
            setText(unsaved ? text + " *" : text);
            setForeground(selected ? source.getSelectionForeground()
                    : unsaved ? GuiColors.getUnsaved() : GuiColors.getApplied());
            return this;
        }
    }

    private final class ProfileCellEditor extends AbstractCellEditor implements TableCellEditor {
        private final JComboBox<String> combo = new JComboBox<>();

        @Override
        public Component getTableCellEditorComponent(JTable source, Object value,
                boolean selected, int row, int column) {
            combo.removeAllItems();
            for (String option : options) {
                combo.addItem(option);
            }
            if (CUSTOM.equals(value) && !options.contains(CUSTOM)) {
                combo.addItem(CUSTOM);
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
