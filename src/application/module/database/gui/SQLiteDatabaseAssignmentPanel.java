package application.module.database.gui;

import application.module.database.profile.SQLiteProfile;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.profile.NodeProfileRepository;
import application.module.node.profile.SqliteDatabasePaths;
import application.module.node.props.Props;
import application.utils.config.ModuleIds;
import application.utils.config.PropertiesProfileLoader;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.IconFontCache;
import application.utils.gui.ResponsiveToolbarScrollPane;
import application.utils.gui.SearchMatchLabel;
import application.utils.gui.SearchMatchPanel;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
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
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * SQLite database-profile assignments for the module profiles.
 *
 * <p>The tab is driven by the DISCOVERED SQLite profiles: every discovered
 * profile that at least one module profile assigns is listed (profiles
 * without an assigned module are not listed). The table has three columns -
 * the SQLite profile, its database file and the assigned module profile:
 * one row per module (a module may share the profile and the file with
 * other modules). The profile value is TOP-aligned in the first row of the
 * profile's rows and the file value in the first row of the file's rows
 * (the JDK's table UI has no merged-cell support, so the span-aware cells
 * and the selection are painted by {@link #paintTable}); selecting a row
 * highlights ONLY that row, never the whole span. A module counts as
 * assigned when its EFFECTIVE
 * database URL - the explicit {@code DB.Url}, or the built-in
 * {@code Props.DB_URL} default the module opens when the property is unset -
 * resolves to the profile's directory; a module with no {@code DB.Url} at
 * all therefore appears under the built-in default profile. Modules whose
 * {@code DB.Url} points outside the discovered profiles are not listed.
 * This tab is READ-ONLY: it lists the current assignments for inspection;
 * the assignments themselves are maintained from the module profile
 * configuration (the database link).</p>
 *
 * <p>The rows carry no unsaved/saved state coloring (what is shown is what
 * is on disk). Every row carries a details (question-mark)
 * icon at the end of the module column that opens a popup with the row's
 * own values - the profile, the database file and the assigned module
 * profile (consumer-less file rows show no module line) - plus the profile
 * settings.
 * The icon is the jiconfont FontAwesome {@code QUESTION_CIRCLE} glyph at the
 * UI-font-proportional help size (see {@link GuiConstants#getHelpIconSize()})
 * — the same glyph the node/profile configuration and the logging panels
 * use, following the font scaling. The search field filters the rows and
 * highlights
 * the matching text with the node/profile/configuration pattern: every
 * occurrence of the query in a visible cell is ONE match (the counter shows
 * the true match count), every occurrence carries the soft band (the
 * selected look) and the navigated one — moved by Enter/chevrons — the
 * strong movable band.</p>
 */
final class SQLiteDatabaseAssignmentPanel extends JPanel {

    private static final String DEFAULT = "(default)";
    private static final String CUSTOM = "(custom URL)";
    private static final String COL_SQLITE_PROFILE = "SQLite Profile";
    private static final String COL_DATABASE_FILE = "Database File";
    private static final String COL_MODULE = "Assigned Module Profile";
    /** The view index of the assigned-module column (the last one). */
    private static final int COL_MODULE_INDEX = 2;

    /** Distance (px) of the details icon from the row's right edge. */
    private static final int DETAILS_ICON_MARGIN = 10;
    /** Horizontal padding (px) between the cell text and the table's grid lines. */
    private static final int CELL_TEXT_MARGIN = 8;
    private static final String DETAILS_TOOLTIP = "Show assignment details";

    private static final Logger logger = LoggerFactory.getLogger(SQLiteDatabaseAssignmentPanel.class);

    private final String confRoot;
    private final DefaultTableModel model;
    private final JTable table;
    private final JScrollPane scroll;
    private final SearchMatchPanel searchMatchPanel;
    private final List<String> options = new ArrayList<>();
    private final List<FlatRow> allRows = new ArrayList<>();
    private final List<SearchTarget> searchTargets = new ArrayList<>();
    /** The search-match bands per table column (one map per column). */
    private final Map<Integer, List<SearchMatchLabel.HighlightBand>> profileBands = new HashMap<>();
    private final Map<Integer, List<SearchMatchLabel.HighlightBand>> fileBands = new HashMap<>();
    private final Map<Integer, List<SearchMatchLabel.HighlightBand>> moduleBands = new HashMap<>();
    /** The rows currently shown in the table (the search-filtered subset of {@link #allRows}, in table order). */
    private final List<FlatRow> visibleRows = new ArrayList<>();
    /**
     * The merged-cell spans of the VISIBLE rows, per column: for the profile
     * column the span length on the first row of each profile group (0 on
     * the group's continuation rows), for the file column the span length on
     * the first row of each file block. Rebuilt by {@link #applySearch}.
     */
    private int[] profileSpan = new int[0];
    private int[] fileSpan = new int[0];
    private int searchActiveIndex = -1;

    /**
     * One flat table row: a (SQLite profile, database file, module profile)
     * combination. The profile cell is merged across all rows of one
     * profile group and the file cell across all rows of one file, so the
     * MODEL carries the profile / file values only on the FIRST row of each
     * span (continuation rows hold null in that cell) - the spans themselves
     * are the {@link #profileSpan}/{@link #fileSpan} arrays, rebuilt on
     * every (filtered) rebuild.
     *
     * @param profile  the SQLite profile the row belongs to (always set - used to group the rows)
     * @param file     the database file the row belongs to (always set; may be blank when the consumer's URL carries none)
     * @param module   the module profile that opens the file, or "" when the file has no consumer
     * @param consumer the consumer behind the module cell (null for a consumer-less file row)
     */
    private record FlatRow(String profile, String file, String module, SqliteConsumer consumer) {
    }

    /** A module profile that uses a database profile, with the database file its effective URL points at. */
    private record SqliteConsumer(String nodeProfile, String databaseFile) {
    }

    /**
     * The full module path of a node profile as shown in the module column
     * (today only the Node module exists; further modules would extend
     * this).
     */
    private static String modulePath(String nodeProfile) {
        return "Node/" + nodeProfile;
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
        //     tab only lists (and searches) what is on disk. Three columns -
        //     SQLite profile / database file / assigned module profile - with
        //     the profile and file cells merged across their rows (one row
        //     per module); the merged spans are painted in {@link #paintTable}. ---
        model = new DefaultTableModel(new Object[] {COL_SQLITE_PROFILE, COL_DATABASE_FILE, COL_MODULE}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(model) {
            @Override
            protected void paintComponent(Graphics g) {
                // The JDK's table UI paints every cell and every grid line -
                // it has no merged-cell support - so the span-aware painting
                // is done here instead of delegating to the UI.
                SQLiteDatabaseAssignmentPanel.this.paintTable(g);
            }

            @Override
            public String getToolTipText(MouseEvent evt) {
                Rectangle bounds = detailsIconBounds(evt.getPoint());
                return (bounds != null && bounds.contains(evt.getPoint())) ? DETAILS_TOOLTIP : null;
            }
        };
        table.getColumn(COL_SQLITE_PROFILE).setCellRenderer(new ProfileCellRenderer(0));
        table.getColumn(COL_DATABASE_FILE).setCellRenderer(new ProfileCellRenderer(1));
        table.getColumn(COL_MODULE).setCellRenderer(new ProfileCellRenderer(2));
        table.setRowHeight(28);
        // The table is a bordered grid: the LAF may default to a borderless
        // table (FlatLaf does - both flags are false there), so enable both
        // grid directions explicitly; the lines are painted by paintGrid.
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(true);
        // No explicit column widths: the default auto-resize mode keeps the
        // three columns EVENLY sized (the resize delta is distributed over
        // all of them), mirroring the old two-column look.
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

        visibleRows.clear();
        for (FlatRow row : allRows) {
            if (!searchActive || isRowSearchMatch(row, lowerQuery)) {
                visibleRows.add(row);
            }
        }
        // The merged cells (profile / file) span the VISIBLE rows of their
        // group: recompute the spans, then rebuild the model - the profile
        // and file cells are filled on the first visible row of each span
        // only (a filtered group of one row shows all three values again).
        int count = visibleRows.size();
        profileSpan = new int[count];
        fileSpan = new int[count];
        int i = 0;
        while (i < count) {
            int j = i;
            while (j + 1 < count && visibleRows.get(j + 1).profile().equals(visibleRows.get(i).profile())) {
                j++;
            }
            profileSpan[i] = j - i + 1;
            int k = i;
            while (k <= j) {
                int m = k;
                while (m + 1 <= j && visibleRows.get(m + 1).file().equalsIgnoreCase(visibleRows.get(k).file())) {
                    m++;
                }
                fileSpan[k] = m - k + 1;
                k = m + 1;
            }
            i = j + 1;
        }
        model.setRowCount(0);
        for (int row = 0; row < count; row++) {
            FlatRow flat = visibleRows.get(row);
            model.addRow(new Object[] {
                    profileSpan[row] > 0 ? flat.profile() : null,
                    fileSpan[row] > 0 ? flat.file() : null,
                    flat.module()
            });
        }
        if (searchActive) {
            // Every occurrence in every visible cell is one real match
            // (rows top-to-bottom, in a row the Profile column first, in a
            // cell left-to-right).
            for (int row = 0; row < count; row++) {
                for (int column = 0; column < model.getColumnCount(); column++) {
                    searchTargets.addAll(cellSearchTargets(row, column, cellText(row, column), lowerQuery));
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

    /** @return the model text of the cell ("" for a merged continuation cell). */
    private String cellText(int row, int column) {
        Object value = model.getValueAt(row, column);
        return value == null ? "" : value.toString();
    }

    /** @return true when the query is in any VISIBLE cell of the row: the profile name, the database file or the module. */
    static boolean isRowSearchMatch(FlatRow row, String lowerQuery) {
        return row.profile().toLowerCase(Locale.ROOT).contains(lowerQuery)
                || row.file().toLowerCase(Locale.ROOT).contains(lowerQuery)
                || row.module().toLowerCase(Locale.ROOT).contains(lowerQuery);
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
        String text = cellText(row, column);
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
        bandsFor(column).put(row, bands);
    }

    /** @return the search-match band map of the given column. */
    private Map<Integer, List<SearchMatchLabel.HighlightBand>> bandsFor(int column) {
        return switch (column) {
            case 0 -> profileBands;
            case 1 -> fileBands;
            default -> moduleBands;
        };
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
        fileBands.clear();
        moduleBands.clear();
    }

    /**
     * The clickable strip at the end of the row holding the details icon, or
     * {@code null} when the point is outside the last column or its strip.
     * Every assigned-module row carries the icon (clicking it opens the
     * details popup of the row's profile group).
     */
    private Rectangle detailsIconBounds(Point location) {
        int row = table.rowAtPoint(location);
        if (row < 0 || table.columnAtPoint(location) != COL_MODULE_INDEX) {
            return null;
        }
        Rectangle cell = table.getCellRect(row, COL_MODULE_INDEX, false);
        int space = detailsIconSpace();
        return new Rectangle(cell.x + cell.width - space, cell.y, space, cell.height);
    }

    /**
     * The trailing module-cell strip (px) reserved for the details icon at
     * the CURRENT font scale: the FontAwesome question-mark's box at the
     * help size (see {@link GuiConstants#getHelpIconSize()}, which follows
     * the UI font) plus the right margin.
     */
    private int detailsIconSpace() {
        return (int) IconFontCache.iconBox(FontAwesome.QUESTION_CIRCLE,
                GuiConstants.getHelpIconSize()).getWidth() + DETAILS_ICON_MARGIN;
    }

    // --- Span-aware painting ---
    //
    // The JDK's BasicTableUI paints EVERY cell and EVERY grid line - it has
    // no merged-cell (rowspan) support - so the cells are painted here
    // instead: a span's value sits in the span's FIRST row (top-aligned)
    // and the span's continuation rows carry no value - a selected
    // continuation row gets the selection background fill, an unselected
    // one stays on the table background. The selection is PER-ROW: only the
    // selected row's band is highlighted, never the whole span (a partial
    // repaint of that band - a selection change or scrolling - repaints
    // exactly the band's cells, so nothing in it can vanish into the
    // background fill). The GRID is painted as a full table grid - every
    // row boundary and every column boundary, including inside the spans -
    // and it is painted AFTER the cells so the opaque selection fills
    // never hide a line.

    /** Paints the table: the background, the span-aware cells and the (full) grid over them. */
    private void paintTable(Graphics g) {
        g.setColor(table.getBackground());
        g.fillRect(0, 0, table.getWidth(), table.getHeight());
        int rowCount = table.getRowCount();
        if (rowCount == 0) {
            return;
        }
        Rectangle clip = g.getClipBounds();
        Rectangle bounds = new Rectangle(0, 0, table.getWidth(), table.getHeight());
        if (clip == null || !bounds.intersects(clip)) {
            return;
        }
        Rectangle visible = clip.intersection(bounds);
        int rMin = table.rowAtPoint(visible.getLocation());
        if (rMin < 0) {
            rMin = 0;
        }
        int rMax = table.rowAtPoint(new Point(visible.x + visible.width - 1, visible.y + visible.height - 1));
        if (rMax < 0) {
            rMax = rowCount - 1;
        }
        paintCellsWithSpans(g, rMin, rMax);
        paintGrid(g, rMin, rMax);
    }

    /**
     * The full table grid over the painted cells: a horizontal line at each
     * visible row boundary in EVERY column (the merged cells keep their
     * inner borders) and a vertical line at each column boundary, so the
     * grid is one continuous table. Painted after the cells: the opaque
     * selection fills of the renderers would otherwise hide the lines.
     */
    private void paintGrid(Graphics g, int rMin, int rMax) {
        g.setColor(table.getGridColor());
        if (table.getShowHorizontalLines()) {
            for (int row = rMin; row <= rMax; row++) {
                Rectangle cell = table.getCellRect(row, 0, true);
                int y = cell.y + cell.height - 1;
                for (int column = 0; column < table.getColumnCount(); column++) {
                    Rectangle cellRect = table.getCellRect(row, column, false);
                    g.drawLine(cellRect.x, y, cellRect.x + cellRect.width - 1, y);
                }
            }
        }
        if (table.getShowVerticalLines()) {
            for (int column = 0; column < table.getColumnCount(); column++) {
                Rectangle top = table.getCellRect(rMin, column, true);
                Rectangle bottom = table.getCellRect(rMax, column, true);
                int x = top.x + top.width - 1;
                g.drawLine(x, top.y, x, bottom.y + bottom.height - 1);
            }
        }
    }

    /** @return the span length at the given (view) row of the column; 0 = a span continuation row. */
    private int spanAt(int row, int column) {
        if (row < 0 || row >= profileSpan.length) {
            return column == COL_MODULE_INDEX ? 1 : 0;
        }
        return switch (column) {
            case 0 -> profileSpan[row];
            case 1 -> fileSpan[row];
            default -> 1;
        };
    }

    /**
     * The cells, span-aware: a span's start cell is painted over its OWN
     * row only - the value TOP-aligned at the span's first row - and the
     * span's continuation rows carry no value: a selected one gets the
     * selection background fill, an unselected one stays on the table
     * background. The selection is PER-ROW: the highlighted band is
     * exactly the selected row, never the whole span.
     */
    private void paintCellsWithSpans(Graphics g, int rMin, int rMax) {
        for (int row = rMin; row <= rMax; row++) {
            for (int column = 0; column < table.getColumnCount(); column++) {
                Rectangle cell = table.getCellRect(row, column, false);
                boolean selected = !table.isPaintingForPrint()
                        && table.getSelectionModel().isSelectedIndex(row);
                if (spanAt(row, column) == 0) {
                    // A span continuation row: no value - only the row's
                    // own selection highlight (the grid is painted over it).
                    if (selected) {
                        g.setColor(table.getSelectionBackground());
                        g.fillRect(cell.x, cell.y, cell.width, cell.height);
                    }
                    continue;
                }
                boolean hasFocus = selected && table.isFocusOwner()
                        && table.getSelectionModel().getLeadSelectionIndex() == row;
                TableCellRenderer renderer = table.getCellRenderer(row, column);
                Component component = renderer.getTableCellRendererComponent(
                        table, table.getValueAt(row, column), selected, hasFocus, row, column);
                component.setBounds(cell);
                component.validate();
                Shape oldClip = g.getClip();
                g.translate(cell.x, cell.y);
                // INTERSECT the cell with the existing (viewport/dirty-band)
                // clip - replacing it would overpaint outside the band.
                // The table is always painted with a Graphics2D.
                ((Graphics2D) g).clip(new Rectangle(0, 0, cell.width, cell.height));
                component.paint(g);
                // Restore the transform BEFORE the clip: setClip interprets
                // its shape in the current coordinate system.
                g.translate(-cell.x, -cell.y);
                g.setClip(oldClip);
            }
        }
    }

    private static JButton iconButton(FontAwesome glyph, String tooltip) {
        JButton button = new JButton();
        button.setToolTipText(tooltip);
        ConfigurationUtils.styleProfileIconButton(button, glyph,
                glyph == FontAwesome.TRASH_O ? GuiColors.getContrastRed() : GuiColors.getButtonIcon(),
                GuiConstants.getHelpIconSize());
        return button;
    }

    /**
     * Reloads the assignments from disk, driven by the DISCOVERED SQLite
     * profiles: every discovered profile that at least one module profile
     * assigns becomes one group (profiles without an assigned module are not
     * listed). Each group's rows are its database files - the discovered
     * {@code *.db} files plus any file a consumer opens - and each file's
     * rows are the module profiles that open it (one row per module; a file
     * no module opens is listed with an empty module cell). The module side
     * is queried in a SINGLE pass - each node profile file is read exactly
     * once and its effective database URL is matched in memory against every
     * discovered profile.
     */
    void refresh() {
        List<String> profiles = DatabaseConfigurationUtils.getProfileNames(confRoot, "SQLite");
        options.clear();
        options.add(DEFAULT);
        options.addAll(profiles);

        Map<String, List<SqliteConsumer>> consumersByProfile =
                findSqliteConsumersByProfile(confRoot, profiles);

        allRows.clear();
        for (String profile : profiles) {
            List<SqliteConsumer> consumers = consumersByProfile.getOrDefault(profile, List.of());
            if (consumers.isEmpty()) {
                continue;
            }
            // The group's database files: the discovered *.db files, plus any
            // file a consumer opens that is not among them (a consumer must
            // never be dropped from the list).
            List<String> files = new ArrayList<>(
                    SqliteDatabasePaths.discoverDbFiles(SqliteDatabasePaths.profileDir(profile)));
            for (SqliteConsumer consumer : consumers) {
                if (consumer.databaseFile() != null && !consumer.databaseFile().isBlank()
                        && files.stream().noneMatch(f -> f.equalsIgnoreCase(consumer.databaseFile()))) {
                    files.add(consumer.databaseFile());
                }
            }
            if (files.isEmpty()) {
                // No file on disk and no file in any URL: one row per module.
                for (SqliteConsumer consumer : consumers) {
                    allRows.add(new FlatRow(profile, consumer.databaseFile(),
                            modulePath(consumer.nodeProfile()), consumer));
                }
                continue;
            }
            for (String file : files) {
                List<SqliteConsumer> fileConsumers = consumers.stream()
                        .filter(c -> file.equalsIgnoreCase(c.databaseFile()))
                        .toList();
                if (fileConsumers.isEmpty()) {
                    allRows.add(new FlatRow(profile, file, "", null));
                } else {
                    for (SqliteConsumer consumer : fileConsumers) {
                        allRows.add(new FlatRow(profile, file,
                                modulePath(consumer.nodeProfile()), consumer));
                    }
                }
            }
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
     * The database URL the profile actually opens: the explicit
     * {@code DB.Url}, or the built-in {@link Props#DB_URL} default when the
     * profile leaves the property unset (the same fallback the runtime
     * applies - see {@code DatabaseInstanceFactory}).
     */
    static String effectiveDbUrl(String explicit) {
        return (explicit == null || explicit.isBlank()) ? Props.DB_URL.getDefaultValue() : explicit;
    }

    /** The database file name of the given URL ("" when the URL carries none). */
    static String databaseFileName(String url) {
        SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(url);
        return parts == null ? "" : parts.fileName();
    }

    /**
     * The module (node) profiles that use the given database profile, with
     * the database file each one's EFFECTIVE database URL (the explicit
     * {@code DB.Url}, or the built-in {@code Props.DB_URL} default when
     * unset) points at. A module with no {@code DB.Url} at all therefore
     * counts as a user of the built-in default profile. This is the
     * assignment direction this tab is built on: the discovered SQLite
     * profiles drive the lookup of the module profiles.
     *
     * <p>A convenience view over the single-pass
     * {@link #findSqliteConsumersByProfile(String, List)} for callers that
     * only need one profile.
     */
    static List<SqliteConsumer> findSqliteConsumers(String confRoot, String databaseProfile) throws IOException {
        return findSqliteConsumersByProfile(confRoot, List.of(databaseProfile))
                .getOrDefault(databaseProfile, List.of());
    }

    /**
     * A SINGLE pass over the module (node) profiles mapping every given
     * database profile to the module profiles that use it, with the
     * database file each one's EFFECTIVE database URL (the explicit
     * {@code DB.Url}, or the built-in {@code Props.DB_URL} default when
     * unset) points at. Every node profile file is read exactly once and
     * the URL matching happens in memory - the efficient form of the
     * lookup for the multi-profile case (one
     * {@link #findSqliteConsumers(String, String)} per discovered profile
     * would re-read all node profiles every time).
     *
     * <p>A node with no {@code DB.Url} at all therefore counts as a user
     * of the built-in default profile. A URL matching none of the given
     * profiles is dropped (there is nothing on the SQLite side to list it
     * under), and a node profile that cannot be read is skipped with a
     * warning so one unreadable file does not lose the other assignments.
     *
     * @param confRoot         the conf root the node profiles are discovered under
     * @param databaseProfiles the database profiles to collect the consumers for
     * @return the database profile names to their consumers (the profiles
     *         no module profile assigns are absent), consumers in node
     *         profile discovery order
     */
    static Map<String, List<SqliteConsumer>> findSqliteConsumersByProfile(
            String confRoot, List<String> databaseProfiles) {
        Map<String, List<SqliteConsumer>> consumersByProfile = new LinkedHashMap<>();
        for (String nodeProfile : NodeProfileRepository.discoverProfileNames(confRoot)) {
            Properties properties;
            try {
                properties = loadProperties(nodeProfileFile(confRoot, nodeProfile));
            } catch (IOException e) {
                logger.warn("Could not read the node profile {} - its database assignment is ignored: {}",
                        nodeProfile, e.getMessage());
                continue;
            }
            String url = effectiveDbUrl(properties.getProperty(Props.DB_URL.getName()));
            for (String databaseProfile : databaseProfiles) {
                if (isAssignedTo(url, databaseProfile)) {
                    consumersByProfile
                            .computeIfAbsent(databaseProfile, profile -> new ArrayList<>())
                            .add(new SqliteConsumer(nodeProfile, databaseFileName(url)));
                    // a URL's path and file name can only belong to ONE
                    // discovered profile
                    break;
                }
            }
        }
        return consumersByProfile;
    }

    /**
     * The node profiles that use the given database profile (profile names
     * only) - see {@link #findSqliteConsumers(String, String)}.
     */
    static List<String> assignedNodeProfiles(String confRoot, String databaseProfile) throws IOException {
        List<String> assigned = new ArrayList<>();
        for (SqliteConsumer consumer : findSqliteConsumers(confRoot, databaseProfile)) {
            assigned.add(consumer.nodeProfile());
        }
        return assigned;
    }

    static void renameAssignments(String confRoot, String oldName, String newName) throws IOException {
        List<ProfileUpdate> updates = new ArrayList<>();
        for (String nodeProfile : NodeProfileRepository.discoverProfileNames(confRoot)) {
            Path file = nodeProfileFile(confRoot, nodeProfile);
            byte[] original = Files.readAllBytes(file);
            Properties properties = loadProperties(file);
            // The effective URL - a profile without an explicit DB.Url uses
            // the built-in default and is re-pointed the same way (its URL is
            // made explicit at the new location).
            String url = effectiveDbUrl(properties.getProperty(Props.DB_URL.getName()));
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
        if (row < 0 || row >= visibleRows.size()) {
            return;
        }
        // ONLY the clicked row's values: its profile, its database file and the
        // assigned module profile that opens it - never the other rows of the
        // profile's group (a consumer-less file row shows no module row).
        FlatRow flat = visibleRows.get(row);
        String profileName = flat.profile();
        String file = flat.file();
        SqliteConsumer consumer = flat.consumer();
        Path profileDir = SqliteDatabasePaths.profileDir(profileName);

        // A two-column form: the bold field name on the left, the row's value
        // on the right. The optional profile settings form a section of their
        // own, divided from the assignment values by a separator.
        JPanel content = new JPanel(new MigLayout("insets 16, gap 6 16", "[right] [grow]"));
        content.add(fieldLabel("SQLite Profile:"));
        content.add(valueLabel(profileName), "wrap");
        content.add(fieldLabel("Database File:"));
        if (file.isBlank()) {
            content.add(valueLabel("(none on disk yet)"), "wrap");
        } else {
            // The file name + its absolute path on a separate, muted line.
            String path = profileDir.resolve(file).toAbsolutePath().normalize().toString();
            content.add(new JLabel("<html>" + escapeHtml(file) + "<br><font color='#888888'>"
                    + escapeHtml(path) + "</font>"), "wrap");
        }
        if (consumer != null) {
            content.add(fieldLabel("Assigned Module Profile:"));
            String module = flat.module();
            if (consumer.databaseFile() != null && !consumer.databaseFile().isBlank()) {
                module += " (" + consumer.databaseFile() + ")";
            }
            content.add(valueLabel(module), "wrap");
        }

        Path profileFile = profileDir.resolve("profile.json");
        if (Files.isRegularFile(profileFile)) {
            try (Reader reader = Files.newBufferedReader(profileFile)) {
                com.google.gson.JsonObject json =
                        com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                boolean settingsSectionStarted = false;
                if (json.has(SQLiteProfile.CFG_JOURNAL_MODE)) {
                    if (!settingsSectionStarted) {
                        content.add(new JSeparator(SwingConstants.HORIZONTAL), "span 2, gaptop 2, gapbottom 4, wrap");
                        settingsSectionStarted = true;
                    }
                    content.add(fieldLabel("Journal Mode:"));
                    content.add(valueLabel(json.get(SQLiteProfile.CFG_JOURNAL_MODE).getAsString()), "wrap");
                }
                if (json.has(SQLiteProfile.CFG_CACHE_SIZE)) {
                    if (!settingsSectionStarted) {
                        content.add(new JSeparator(SwingConstants.HORIZONTAL), "span 2, gaptop 2, gapbottom 4, wrap");
                        settingsSectionStarted = true;
                    }
                    content.add(fieldLabel("Cache Size:"));
                    content.add(valueLabel(json.get(SQLiteProfile.CFG_CACHE_SIZE).getAsString()), "wrap");
                }
            } catch (IOException ignored) {
                // The settings are optional: show the profile and file info without them.
            }
        }

        JDialog dialog = new JDialog(null, "Assignment Details", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setVisible(true);
    }

    /** A bold field-name cell of the details form. */
    private static JLabel fieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    /** A value cell of the details form (plain text, HTML-escaped). */
    private static JLabel valueLabel(String text) {
        return new JLabel(escapeHtml(text));
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // --- Cell renderer ---

    /**
     * The plain (state-free) renderer of the three columns: the default row
     * colors (no unsaved/saved tinting - the tab is read-only), the
     * search-match bands of the row's cell (one per occurrence - the soft
     * band on every plain match, the strong one on the navigated one) and,
     * in the module column, the details (question-mark) icon painted at the
     * end of every row.
     */
    private final class ProfileCellRenderer extends SearchMatchLabel implements TableCellRenderer {
        private final int column;
        private boolean selected;
        private boolean detailsCell;

        ProfileCellRenderer(int column) {
            this.column = column;
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
            // The LAF's Table.cellBorder has (nearly) no horizontal padding -
            // keep the cell text a small step away from the table's grid
            // lines on both sides.
            Insets cellInsets = javax.swing.UIManager.getInsets("Table.cellBorder");
            if (cellInsets == null) {
                cellInsets = new Insets(0, 0, 0, 0);
            }
            this.detailsCell = this.column == COL_MODULE_INDEX;
            int rightInset = cellInsets.right + CELL_TEXT_MARGIN;
            if (detailsCell) {
                // Reserve the trailing strip so the value text never collides
                // with the details icon painted into it.
                rightInset += detailsIconSpace();
            }
            setBorder(BorderFactory.createEmptyBorder(cellInsets.top, cellInsets.left + CELL_TEXT_MARGIN,
                    cellInsets.bottom, rightInset));
            // Non-opaque unless selected: the search-match bands are painted
            // BEFORE the LAF's background fill (SearchMatchLabel), so an
            // opaque label would paint its background over the bands and hide
            // them. For a non-selected cell the table's own row background
            // shows through (the same color), so the look is unchanged.
            setOpaque(isSelected);
            this.selected = isSelected;
            List<SearchMatchLabel.HighlightBand> bands = bandsFor(this.column).get(row);
            if (bands != null && !bands.isEmpty()) {
                setHighlightBands(bands);
            } else {
                clearHighlight();
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (detailsCell) {
                // The same question-mark glyph the node/profile configuration
                // and logging panels use (jiconfont FontAwesome), at the
                // UI-font-proportional help size: the size is re-read on every
                // paint, so the icon follows the font scaling (the built
                // icon is memoized per (code, size, color) in IconFontCache).
                Icon icon = IconFontCache.buildIcon(FontAwesome.QUESTION_CIRCLE,
                        GuiConstants.getHelpIconSize(),
                        selected ? getForeground() : GuiColors.getButtonIcon());
                icon.paintIcon(this, g, getWidth() - DETAILS_ICON_MARGIN - icon.getIconWidth(),
                        (getHeight() - icon.getIconHeight()) / 2);
            }
        }
    }
}
