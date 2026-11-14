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
import application.utils.gui.ResponsiveToolbarScrollPane;
import application.utils.gui.SearchMatchPanel;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import net.miginfocom.swing.MigLayout;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
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
 *
 * <p>The table shows full module paths ({@code Node/name} → {@code SQLite/name})
 * with per-row state coloring (Unsaved/Saved) and a details ({@code ?}) button
 * that opens a popup with the resolved URL, database file and profile settings.</p>
 */
final class SQLiteDatabaseAssignmentPanel extends JPanel {

    private static final String DEFAULT = "(default)";
    private static final String CUSTOM = "(custom URL)";
    private static final String COL_NODE_PROFILE = "Node Profile";
    private static final String COL_SQLITE_PROFILE = "SQLite Profile";
    private static final String COL_DETAILS = " ";

    private final String confRoot;
    private final DefaultTableModel model;
    private final JTable table;
    private final List<String> options = new ArrayList<>();
    private final Map<String, String> baseline = new LinkedHashMap<>();

    SQLiteDatabaseAssignmentPanel(String confRoot) {
        super(new BorderLayout(0, ConfigurationUtils.PROPERTY_ROW_GAP));
        this.confRoot = confRoot;
        IconFontSwing.register(FontAwesome.getIconFont());
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // --- Search row (SSOT: same as the profiles tab and the node panel) ---
        SearchMatchPanel searchMatchPanel = new SearchMatchPanel("Search node profiles");
        JPanel searchStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        searchStrip.setOpaque(false);
        searchStrip.add(searchMatchPanel);
        JScrollPane searchScroll = new ResponsiveToolbarScrollPane(
                searchStrip, new Insets(0, 10, 0, 5), false);
        searchScroll.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        JPanel searchRow = new JPanel(new BorderLayout());
        searchRow.setOpaque(false);
        searchRow.add(searchScroll, BorderLayout.CENTER);
        add(searchRow, BorderLayout.NORTH);

        // --- Table ---
        model = new DefaultTableModel(
                new Object[] {COL_NODE_PROFILE, COL_SQLITE_PROFILE, COL_DETAILS}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 1;
            }
        };
        table = new JTable(model);
        table.setDefaultEditor(Object.class, new ProfileCellEditor());
        table.getColumn(COL_NODE_PROFILE).setCellRenderer(new NodeProfileCellRenderer());
        table.getColumn(COL_SQLITE_PROFILE).setCellRenderer(new SQLiteProfileCellRenderer());
        table.getColumn(COL_DETAILS).setCellRenderer(new DetailsCellRenderer());
        table.getColumn(COL_DETAILS).setPreferredWidth(40);
        table.setRowHeight(28);

        model.addTableModelListener(event -> {
            if (event.getType() == javax.swing.event.TableModelEvent.UPDATE
                    && event.getColumn() == 1
                    && event.getFirstRow() >= 0
                    && event.getFirstRow() < model.getRowCount()) {
                table.repaint();
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createTitledBorder("SQLite database profile assignments"));
        add(scroll, BorderLayout.CENTER);

        // --- Action buttons (SOUTH) ---
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

        // Search filtering
        searchMatchPanel.setSearchTextListener(text -> filterTable(text));
        searchMatchPanel.setEnterListener(() -> { });

        refresh();
    }

    private void filterTable(String text) {
        String query = text == null ? "" : text.toLowerCase().trim();
        for (int row = 0; row < model.getRowCount(); row++) {
            String nodeProfile = String.valueOf(model.getValueAt(row, 0));
            String sqliteProfile = String.valueOf(model.getValueAt(row, 1));
            boolean match = query.isEmpty()
                    || nodeProfile.toLowerCase().contains(query)
                    || sqliteProfile.toLowerCase().contains(query);
            table.setRowHeight(row, match ? 28 : 1);
        }
        table.repaint();
    }

    private static JButton iconButton(FontAwesome glyph, String tooltip) {
        JButton button = new JButton();
        button.setToolTipText(tooltip);
        ConfigurationUtils.styleProfileIconButton(button, glyph,
                glyph == FontAwesome.TRASH_O ? GuiColors.getContrastRed() : GuiColors.getButtonIcon(),
                GuiConstants.getHelpIconSize());
        return button;
    }

    void refresh() {
        model.setRowCount(0);
        options.clear();
        options.add(DEFAULT);
        options.addAll(DatabaseConfigurationUtils.getProfileNames(confRoot, "SQLite"));

        for (String name : NodeProfileRepository.discoverProfileNames(confRoot)) {
            NodeProfile profile = NodeProfileRepository.loadProfile(confRoot, name);
            String url = profile == null ? null : profile.getProperties().getProperty(Props.DB_URL.getName());
            String assignment = assignedProfile(url);
            String nodePath = "Node/" + name;
            String sqlitePath = toDisplayPath(assignment);
            model.addRow(new Object[] {nodePath, sqlitePath, ""});
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

    /**
     * Converts a raw assignment value to its full module path display form.
     */
    static String toDisplayPath(String assignment) {
        if (DEFAULT.equals(assignment) || CUSTOM.equals(assignment)) {
            return assignment;
        }
        return "SQLite/" + assignment;
    }

    /**
     * Extracts the raw profile name from a display path.
     */
    static String fromDisplayPath(String display) {
        if (display == null) {
            return "";
        }
        if (display.startsWith("SQLite/")) {
            return display.substring("SQLite/".length());
        }
        return display;
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
        String nodeProfile = String.valueOf(model.getValueAt(row, 0));
        String rawName = nodeProfile.startsWith("Node/") ? nodeProfile.substring("Node/".length()) : nodeProfile;
        String value = String.valueOf(model.getValueAt(row, 1));
        return !value.equals(baseline.getOrDefault(rawName, ""));
    }

    private void snapshotBaseline() {
        baseline.clear();
        for (int row = 0; row < model.getRowCount(); row++) {
            String nodeProfile = String.valueOf(model.getValueAt(row, 0));
            String rawName = nodeProfile.startsWith("Node/") ? nodeProfile.substring("Node/".length()) : nodeProfile;
            baseline.put(rawName, String.valueOf(model.getValueAt(row, 1)));
        }
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
            String rawName = nodeProfile.startsWith("Node/") ? nodeProfile.substring("Node/".length()) : nodeProfile;
            String assignment = fromDisplayPath(String.valueOf(model.getValueAt(row, 1)));
            try {
                saveAssignment(rawName, assignment);
                changed.add(rawName);
                baseline.put(rawName, String.valueOf(model.getValueAt(row, 1)));
            } catch (IOException | IllegalArgumentException e) {
                JOptionPane.showMessageDialog(this,
                        "Failed to save the SQLite assignment for '" + rawName + "': " + e.getMessage(),
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

    // --- Details popup ---

    private void showDetailsDialog(int row) {
        String nodeDisplay = String.valueOf(model.getValueAt(row, 0));
        String sqliteDisplay = String.valueOf(model.getValueAt(row, 1));
        String rawNodeName = nodeDisplay.startsWith("Node/") ? nodeDisplay.substring("Node/".length()) : nodeDisplay;

        NodeProfile profile = NodeProfileRepository.loadProfile(confRoot, rawNodeName);
        String url = profile == null ? null : profile.getProperties().getProperty(Props.DB_URL.getName());

        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='width: 420px'>");
        sb.append("<b>Node Profile:</b> ").append(escapeHtml(nodeDisplay)).append("<br>");
        sb.append("<b>SQLite Profile:</b> ").append(escapeHtml(sqliteDisplay)).append("<br>");
        sb.append("<b>DB URL:</b> ").append(escapeHtml(url == null || url.isBlank() ? "(none)" : url)).append("<br>");

        if (url != null && !url.isBlank()) {
            String filePath = databaseFilePath(url);
            sb.append("<b>Database File:</b> ").append(escapeHtml(filePath)).append("<br>");
            SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(url);
            if (parts != null) {
                Path profileFile = parts.dir().resolve("profile.json");
                if (Files.isRegularFile(profileFile)) {
                    try (Reader reader = Files.newBufferedReader(profileFile)) {
                        com.google.gson.JsonObject json =
                                com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                        if (json.has(SQLiteProfile.CFG_JOURNAL_MODE)) {
                            sb.append("<b>Journal Mode:</b> ")
                                    .append(escapeHtml(json.get(SQLiteProfile.CFG_JOURNAL_MODE).getAsString())).append("<br>");
                        }
                        if (json.has(SQLiteProfile.CFG_CACHE_SIZE)) {
                            sb.append("<b>Cache Size:</b> ")
                                    .append(escapeHtml(json.get(SQLiteProfile.CFG_CACHE_SIZE).getAsString())).append("<br>");
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        sb.append("</body></html>");

        JDialog dialog = new JDialog(null, "Assignment Details", Dialog.ModalityType.APPLICATION_MODAL);
        JPanel content = new JPanel(new MigLayout("wrap, gap 8", "[grow]"));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        content.add(new JLabel(sb.toString()), "growx");
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setVisible(true);
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // --- Cell renderers ---

    private final class NodeProfileCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable source, Object value,
                boolean selected, boolean focus, int row, int column) {
            super.getTableCellRendererComponent(source, value, selected, focus, row, column);
            String text = value == null ? "" : value.toString();
            boolean unsaved = !selected && isUnsaved(row);
            setForeground(selected ? source.getSelectionForeground()
                    : unsaved ? GuiColors.getUnsaved() : GuiColors.getSaved());
            setText(text);
            return this;
        }
    }

    private final class SQLiteProfileCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable source, Object value,
                boolean selected, boolean focus, int row, int column) {
            super.getTableCellRendererComponent(source, value, selected, focus, row, column);
            String text = value == null ? "" : value.toString();
            boolean unsaved = !selected && isUnsaved(row);
            setForeground(selected ? source.getSelectionForeground()
                    : unsaved ? GuiColors.getUnsaved() : GuiColors.getSaved());
            setText(text);
            return this;
        }
    }

    private final class DetailsCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable source, Object value,
                boolean selected, boolean focus, int row, int column) {
            setOpaque(false);
            setText("\u24D8");
            setToolTipText("Show assignment details");
            setFont(getFont().deriveFont(Font.BOLD));
            setHorizontalAlignment(CENTER);
            setForeground(selected ? source.getSelectionForeground() : GuiColors.getButtonIcon());
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
                combo.addItem(toDisplayPath(option));
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
