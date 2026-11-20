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
import application.utils.gui.SearchMatchLabel;
import application.utils.gui.SearchMatchPanel;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import net.miginfocom.swing.MigLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * SQLite database-profile assignments for the module profiles.
 *
 * <p>SQLite assignments are derived from each profile's {@code DB.Url};
 * unlike server database links, they are not stored in {@code profiles.json}.
 * This tab is READ-ONLY: it lists the current assignments as full module
 * paths ({@code Node/name} → {@code SQLite/name}) for inspection; the
 * assignments themselves are maintained from the profile configuration
 * (the database link). The first column is the generic "Profile" because
 * any module (node, pool, explorer, ...) can be assigned to a SQLite
 * profile.</p>
 *
 * <p>The rows carry no unsaved/saved state coloring (what is shown is what
 * is on disk) and end with a details ({@code ⓘ}) button that opens a popup
 * with the resolved URL, database file and profile settings. The search
 * field filters the rows and highlights the matching text with the
 * node/profile/configuration pattern: every occurrence of the query in a
 * visible cell is ONE match (the counter shows the true match count), every
 * occurrence carries the soft band (the selected look) and the navigated one
 * — moved by Enter/chevrons — the strong movable band.</p>
 */
final class SQLiteDatabaseAssignmentPanel extends JPanel {

    private static final String DEFAULT = "(default)";
    private static final String CUSTOM = "(custom URL)";
    private static final String COL_PROFILE = "Profile";
    private static final String COL_SQLITE_PROFILE = "SQLite Profile";
    private static final int COL_SQLITE = 1;

    /** The details glyph painted at the end of each row. */
    private static final String DETAILS_GLYPH = "\u24D8";
    /** Distance (px) of the details glyph from the row's right edge. */
    private static final int DETAILS_ICON_MARGIN = 10;
    /** The trailing cell strip (px) reserved for the details glyph. */
    private static final int DETAILS_ICON_SPACE = 34;
    private static final String DETAILS_TOOLTIP = "Show assignment details";

    private final String confRoot;
    private final DefaultTableModel model;
    private final JTable table;
    private final JScrollPane scroll;
    private final SearchMatchPanel searchMatchPanel;
    private final List<String> options = new ArrayList<>();
    private final List<AssignmentRow> allRows = new ArrayList<>();
    private final List<SearchTarget> searchTargets = new ArrayList<>();
    private final Map<Integer, List<SearchMatchLabel.HighlightBand>> profileBands = new HashMap<>();
    private final Map<Integer, List<SearchMatchLabel.HighlightBand>> sqliteBands = new HashMap<>();
    private int searchActiveIndex = -1;

    /** One assignment row as loaded from disk: the profile display path and the SQLite profile display path. */
    private record AssignmentRow(String profileDisplay, String sqliteDisplay) {
    }

    /**
     * One search match: a single occurrence of the query in ONE visible cell
     * of the (filtered) table. The match counter and the chevron navigation
     * work over these targets — every occurrence is one real match, so "i/n"
     * reflects the true number of matches and the navigated (strong) band
     * moves from one occurrence to the next while every other match keeps
     * its soft (selected) band.
     */
    private record SearchTarget(int row, int column, int start, int length) {
    }

    SQLiteDatabaseAssignmentPanel(String confRoot) {
        super(new BorderLayout(0, ConfigurationUtils.PROPERTY_ROW_GAP));
        this.confRoot = confRoot;
        IconFontSwing.register(FontAwesome.getIconFont());
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // --- Search row (SSOT: same as the profiles tab and the node panel) ---
        searchMatchPanel = new SearchMatchPanel("Search profiles");
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

        // --- Read-only table: the assignments are derived from each profile's
        //     DB.Url and are maintained from the profile configuration, so this
        //     tab only lists (and searches) what is on disk. ---
        model = new DefaultTableModel(new Object[] {COL_PROFILE, COL_SQLITE_PROFILE}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(model) {
            @Override
            public String getToolTipText(MouseEvent evt) {
                Rectangle bounds = detailsIconBounds(evt.getPoint());
                return (bounds != null && bounds.contains(evt.getPoint())) ? DETAILS_TOOLTIP : null;
            }
        };
        table.getColumn(COL_PROFILE).setCellRenderer(new ProfileCellRenderer(false));
        table.getColumn(COL_SQLITE_PROFILE).setCellRenderer(new ProfileCellRenderer(true));
        table.setRowHeight(28);
        // The details glyph (painted at the end of the row) is a real button
        // target: clicking it opens the assignment details popup.
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                Rectangle bounds = detailsIconBounds(event.getPoint());
                if (bounds != null && bounds.contains(event.getPoint())) {
                    showDetailsDialog(table.rowAtPoint(event.getPoint()));
                }
            }
        });
        table.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                Rectangle bounds = detailsIconBounds(event.getPoint());
                boolean overDetails = bounds != null && bounds.contains(event.getPoint());
                table.setCursor(overDetails
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            }
        });

        scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createTitledBorder("SQLite database profile assignments"));
        add(scroll, BorderLayout.CENTER);

        // --- Refresh (the tab is read-only: there is nothing to delete or apply) ---
        JButton refresh = iconButton(FontAwesome.REFRESH, "Reload node profiles and assignments from disk");
        refresh.addActionListener(event -> refresh());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(refresh);
        add(actions, BorderLayout.SOUTH);

        // Search (the node/profile/configuration pattern): the text filters the
        // rows and highlights the matching part; Enter and the chevrons move
        // the active (strong) band, the counter shows current/total.
        searchMatchPanel.setSearchTextListener(text -> applySearch(text, true));
        searchMatchPanel.setSearchNavigationListener(this::navigateSearch);
        searchMatchPanel.setEnterListener(() -> navigateSearch(true));

        refresh();
    }

    /**
     * Re-applies the search: the rows are filtered to the text matches (the
     * table is rebuilt with the visible rows only), then EVERY occurrence of
     * the query in a visible cell becomes one search target — banded soft
     * (the selected look) — and the first target's occurrence the strong
     * (active) one (the node/profile/configuration pattern, refined per
     * occurrence so the counter shows the true match count). With an empty
     * query all rows are visible and the search UI (bands, counter,
     * chevrons) is off. When {@code scrollToActive} is true the first match
     * is also brought into view (search-box input); a refresh passes false.
     */
    private void applySearch(String text, boolean scrollToActive) {
        clearSearchHighlights();
        searchTargets.clear();
        searchActiveIndex = -1;

        String query = text == null ? "" : text.trim();
        boolean searchActive = !query.isEmpty();
        String lowerQuery = query.toLowerCase(Locale.ROOT);

        model.setRowCount(0);
        for (AssignmentRow row : allRows) {
            if (!searchActive || isRowSearchMatch(row, lowerQuery)) {
                model.addRow(new Object[] {row.profileDisplay(), row.sqliteDisplay()});
            }
        }
        if (searchActive) {
            // Every occurrence in every visible cell is one real match
            // (rows top-to-bottom, in a row the Profile column first, in a
            // cell left-to-right).
            for (int row = 0; row < model.getRowCount(); row++) {
                for (int column = 0; column <= COL_SQLITE; column++) {
                    String cellText = String.valueOf(model.getValueAt(row, column));
                    searchTargets.addAll(cellSearchTargets(row, column, cellText, lowerQuery));
                }
            }
            for (SearchTarget target : searchTargets) {
                paintCellBands(target.row(), target.column(), query, null);
            }
            if (!searchTargets.isEmpty()) {
                searchActiveIndex = 0;
                SearchTarget active = searchTargets.get(searchActiveIndex);
                paintCellBands(active.row(), active.column(), query, active);
            }
        }
        updateSearchMatchIndicator(searchActive ? formatSearchIndicator() : "");
        if (scrollToActive && searchActiveIndex >= 0) {
            showActiveSearchMatch();
        }
        table.repaint();
    }

    /** @return true when the query is in the row's visible text: the profile path or the SQLite profile path. */
    static boolean isRowSearchMatch(AssignmentRow row, String lowerQuery) {
        return row.profileDisplay().toLowerCase(Locale.ROOT).contains(lowerQuery)
                || row.sqliteDisplay().toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    /**
     * The occurrence targets of one cell: every hit of the query in the cell's
     * text, left to right (each hit is one real match).
     */
    private static List<SearchTarget> cellSearchTargets(int row, int column, String cellText,
            String lowerQuery) {
        List<SearchTarget> targets = new ArrayList<>();
        int from = 0;
        int index;
        while ((index = cellText.toLowerCase(Locale.ROOT).indexOf(lowerQuery, from)) >= 0) {
            targets.add(new SearchTarget(row, column, index, lowerQuery.length()));
            from = index + 1;
        }
        return targets;
    }

    /**
     * (Re)paints the bands of ONE cell: every occurrence of the query in the
     * cell's text gets the soft band (the selected look) and — when the given
     * {@code active} target is an occurrence of THIS cell — that occurrence
     * gets the strong (movable) band instead. Replaces the cell's previous
     * bands. The same SSOT palette colors as the console "find".
     */
    private void paintCellBands(int row, int column, String query, SearchTarget active) {
        String text = String.valueOf(model.getValueAt(row, column));
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        List<SearchMatchLabel.HighlightBand> bands = new ArrayList<>();
        int from = 0;
        int index;
        while ((index = text.toLowerCase(Locale.ROOT).indexOf(lowerQuery, from)) >= 0) {
            Color color = GuiColors.getSearchMatch();
            if (active != null && active.row() == row && active.column() == column
                    && active.start() == index) {
                color = GuiColors.getSearchActiveMatch();
            }
            bands.add(new SearchMatchLabel.HighlightBand(index, query.length(), color));
            from = index + 1;
        }
        (column == COL_SQLITE ? sqliteBands : profileBands).put(row, bands);
    }

    /**
     * Moves the active (strong) highlight to the next/previous match (wrapping)
     * and scrolls it into view - the console's chevron behavior. Only a real
     * (non-empty) search supports navigation, so an empty query is a no-op
     * (the chevrons are hidden in that case anyway).
     */
    private void navigateSearch(boolean next) {
        String query = searchMatchPanel.getSearchText().trim();
        if (query.isEmpty() || searchTargets.isEmpty()) {
            return;
        }
        int count = searchTargets.size();
        int oldIndex = searchActiveIndex;
        searchActiveIndex = next
                ? (searchActiveIndex + 1) % count
                : (searchActiveIndex - 1 + count) % count;
        if (oldIndex >= 0 && oldIndex < count && oldIndex != searchActiveIndex) {
            SearchTarget old = searchTargets.get(oldIndex);
            paintCellBands(old.row(), old.column(), query, null);
        }
        SearchTarget active = searchTargets.get(searchActiveIndex);
        paintCellBands(active.row(), active.column(), query, active);
        updateSearchMatchIndicator(formatSearchIndicator());
        showActiveSearchMatch();
        table.repaint();
    }

    /** @return the "current/total" indicator text (e.g. "1/23"), or "" when nothing matches */
    private String formatSearchIndicator() {
        return searchTargets.isEmpty() ? "" : (searchActiveIndex + 1) + "/" + searchTargets.size();
    }

    /**
     * Updates the match counter next to the search field (sized to its text,
     * mirroring the node configuration panel); an empty text hides the
     * counter. The chevron buttons share the indicator's visibility: with no
     * match there is nothing to navigate to, so both are hidden until the
     * search produces a match again.
     */
    private void updateSearchMatchIndicator(String text) {
        boolean hasMatches = text != null && !text.isEmpty();
        searchMatchPanel.setChevronsVisible(hasMatches);
        searchMatchPanel.setMatchIndicatorText(text);
    }

    /**
     * Brings the active match into view: the row is scrolled inside the
     * table's viewport (the same clamped arithmetic as the node
     * configuration's reveal, without its parent-chain translation because
     * the table is the viewport's direct view).
     */
    private void showActiveSearchMatch() {
        if (searchActiveIndex < 0 || searchActiveIndex >= searchTargets.size()) {
            return;
        }
        int row = searchTargets.get(searchActiveIndex).row();
        JViewport viewport = scroll.getViewport();
        if (viewport == null || !viewport.isShowing() || viewport.getExtentSize().height <= 0) {
            return;
        }
        Rectangle cell = table.getCellRect(row, 0, true);
        Point position = viewport.getViewPosition();
        int extentHeight = viewport.getExtentSize().height;
        int targetY = position.y;
        if (cell.y < position.y) {
            targetY = cell.y;
        } else if (cell.y + cell.height > position.y + extentHeight) {
            targetY = cell.y + cell.height - extentHeight;
        }
        int maxY = Math.max(0, viewport.getViewSize().height - extentHeight);
        targetY = Math.max(0, Math.min(maxY, targetY));
        viewport.setViewPosition(new Point(position.x, targetY));
    }

    /** Removes every search highlight band from all rows. */
    private void clearSearchHighlights() {
        profileBands.clear();
        sqliteBands.clear();
    }

    /**
     * The clickable strip at the end of the row holding the details glyph, or
     * {@code null} when the point is outside the last column or its strip.
     */
    private Rectangle detailsIconBounds(Point location) {
        int row = table.rowAtPoint(location);
        if (row < 0 || table.columnAtPoint(location) != COL_SQLITE) {
            return null;
        }
        Rectangle cell = table.getCellRect(row, COL_SQLITE, false);
        return new Rectangle(cell.x + cell.width - DETAILS_ICON_SPACE, cell.y,
                DETAILS_ICON_SPACE, cell.height);
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
        options.clear();
        options.add(DEFAULT);
        options.addAll(DatabaseConfigurationUtils.getProfileNames(confRoot, "SQLite"));

        allRows.clear();
        for (String name : NodeProfileRepository.discoverProfileNames(confRoot)) {
            NodeProfile profile = NodeProfileRepository.loadProfile(confRoot, name);
            String url = profile == null ? null : profile.getProperties().getProperty(Props.DB_URL.getName());
            String assignment = assignedProfile(url);
            allRows.add(new AssignmentRow("Node/" + name, toDisplayPath(assignment)));
        }
        applySearch(searchMatchPanel.getSearchText(), false);
    }

    /**
     * Re-reads the assignments from disk. The tab is read-only (there are
     * never unsaved rows to protect), so this is an unconditional refresh;
     * kept as a distinct entry point for the callers that switch into the tab.
     */
    void refreshIfSafe() {
        refresh();
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
        // The profile's database URL — the derived (directory + active file)
        // one; the profile.json carries no URL (see SQLiteProfile).
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

    // --- Cell renderer ---

    /**
     * The plain (state-free) renderer of both columns: the default row colors
     * (no unsaved/saved tinting - the tab is read-only), the search-match
     * bands of the row's cell (one per occurrence - the soft band on every
     * plain match, the strong one on the navigated one) and, in the SQLite
     * column, the details glyph painted at the end of the row.
     */
    private final class ProfileCellRenderer extends SearchMatchLabel implements TableCellRenderer {
        private final boolean detailsColumn;
        private boolean selected;

        ProfileCellRenderer(boolean detailsColumn) {
            this.detailsColumn = detailsColumn;
        }

        @Override
        public Component getTableCellRendererComponent(JTable source, Object value,
                boolean isSelected, boolean focus, int row, int column) {
            // This JDK's JLabel does not implement TableCellRenderer: mirror the
            // standard selection/foreground/background/border handling here.
            if (isSelected) {
                setForeground(source.getSelectionForeground());
                setBackground(source.getSelectionBackground());
            } else {
                setForeground(source.getForeground());
                setBackground(source.getBackground());
            }
            setText(value == null ? "" : value.toString());
            setBorder(javax.swing.UIManager.getBorder("Table.cellBorder"));
            // Non-opaque unless selected: the search-match bands are painted
            // BEFORE the LAF's background fill (SearchMatchLabel), so an
            // opaque label would paint its background over the bands and hide
            // them. For a non-selected cell the table's own row background
            // shows through (the same color), so the look is unchanged.
            setOpaque(isSelected);
            this.selected = isSelected;
            List<SearchMatchLabel.HighlightBand> bands =
                    (detailsColumn ? sqliteBands : profileBands).get(row);
            if (bands != null && !bands.isEmpty()) {
                setHighlightBands(bands);
            } else {
                clearHighlight();
            }
            if (detailsColumn) {
                // Reserve the trailing strip so the value text never collides
                // with the details glyph painted into it.
                Insets insets = getInsets();
                setBorder(BorderFactory.createEmptyBorder(insets.top, insets.left, insets.bottom,
                        insets.right + DETAILS_ICON_SPACE));
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (detailsColumn) {
                Font iconFont = getFont().deriveFont(Font.BOLD);
                g.setFont(iconFont);
                FontMetrics fontMetrics = g.getFontMetrics();
                int glyphWidth = fontMetrics.stringWidth(DETAILS_GLYPH);
                int iconX = getWidth() - DETAILS_ICON_MARGIN - glyphWidth;
                int iconY = (getHeight() - fontMetrics.getHeight()) / 2 + fontMetrics.getAscent();
                g.setColor(selected ? getForeground() : GuiColors.getButtonIcon());
                g.drawString(DETAILS_GLYPH, iconX, iconY);
            }
        }
    }
}
