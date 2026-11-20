package application.module.database.gui;

import application.module.database.profile.SQLiteProfile;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.props.Props;
import application.utils.config.ModuleIds;
import application.utils.config.PropertiesProfileLoader;
import application.utils.gui.GuiColors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.JTabbedPane;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.UIManager;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("SQLite profile configuration")
class SQLiteConfigurationPanelTest {

    private Path createdProfile;
    private Path temporaryConfRoot;

    @AfterEach
    void cleanUp() throws IOException {
        if (createdProfile != null && Files.exists(createdProfile)) {
            deleteRecursively(createdProfile);
        }
        if (temporaryConfRoot != null && Files.exists(temporaryConfRoot)) {
            deleteRecursively(temporaryConfRoot);
        }
    }

    @Test
    @DisplayName("new empty profiles use the SQLite profile.json format")
    void createsEmptyProfileInSqliteFormat() throws Exception {
        String name = "test_sqlite_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", name)
                .toAbsolutePath().normalize();
        AtomicReference<SQLiteConfigurationPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panel.set(new SQLiteConfigurationPanel()));
        Field tabsField = SQLiteConfigurationPanel.class.getDeclaredField("sqliteTabs");
        tabsField.setAccessible(true);
        assertEquals(2, ((JTabbedPane) tabsField.get(panel.get())).getTabCount());
        assertEquals("Profiles", ((JTabbedPane) tabsField.get(panel.get())).getTitleAt(0));
        assertEquals("Assignments", ((JTabbedPane) tabsField.get(panel.get())).getTitleAt(1));

        assertEquals(name, panel.get().createEmptyProfile(name));
        Path profileFile = createdProfile.resolve("profile.json");
        assertTrue(Files.isRegularFile(profileFile));
        SQLiteProfile loaded = new SQLiteProfile(name,
                com.google.gson.JsonParser.parseString(Files.readString(profileFile)).getAsJsonObject());
        assertEquals(new SQLiteProfile(name).getDbUrl(), loaded.getDbUrl());
        assertEquals("WAL", loaded.getConfiguration().get(SQLiteProfile.CFG_JOURNAL_MODE));
        assertEquals("-131072", loaded.getConfiguration().get(SQLiteProfile.CFG_CACHE_SIZE));
        assertTrue(Files.isDirectory(createdProfile));

        assertThrows(IllegalArgumentException.class, () -> panel.get().createEmptyProfile(name));
        assertThrows(IllegalArgumentException.class, () -> panel.get().createEmptyProfile("../invalid"));
    }

    @Test
    @DisplayName("SQLite profile editor exposes all settings and searches edited values")
    void profileEditorEditsAllSettingsAndSearchesValues() throws Exception {
        String name = "test_sqlite_editor_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", name)
                .toAbsolutePath().normalize();
        AtomicReference<SQLiteConfigurationPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IconFontSwing.register(FontAwesome.getIconFont());
            SQLiteConfigurationPanel panel = new SQLiteConfigurationPanel();
            panel.loadProfile(name, new GlobalSettings());
            panelRef.set(panel);
        });
        SQLiteConfigurationPanel panel = panelRef.get();

        @SuppressWarnings("unchecked")
        Map<String, javax.swing.JComponent> components = (Map<String, javax.swing.JComponent>) getField(
                panel, "propertyComponents");
        assertTrue(components.containsKey(SQLiteProfile.CFG_URL));
        assertTrue(components.get(SQLiteProfile.CFG_JOURNAL_MODE) instanceof JComboBox<?>);
        assertTrue(components.get(SQLiteProfile.CFG_CACHE_SIZE) instanceof JComboBox<?>);

        SwingUtilities.invokeAndWait(() -> {
            DatabaseFileListEditor databaseFile = (DatabaseFileListEditor) getField(panel, "databaseFileEditor");
            JComboBox<?> journalMode = (JComboBox<?>) components.get(SQLiteProfile.CFG_JOURNAL_MODE);
            JComboBox<?> cacheSize = (JComboBox<?>) components.get(SQLiteProfile.CFG_CACHE_SIZE);
            databaseFile.setActiveFileText("alternate.db");
            // The journal-mode select is NOT editable (closed value set):
            // select from the catalog instead of typing.
            journalMode.setSelectedItem("DELETE");
            cacheSize.getEditor().setItem("8192");
            assertTrue(panel.hasUnsavedChanges());

            JTextField search = (JTextField) getField(panel, "searchField");
            search.setText("8192");
            JPanel results = (JPanel) getField(panel, "searchResultsPanel");
            // One matching row (the cache-size value "8192"); each search-result row
            // contributes label + input + help + separator = 4 components.
            assertEquals(4, results.getComponentCount(), "the edited value should match its property row");
            java.awt.Container searchParent = cacheSize.getParent();
            cacheSize.getEditor().setItem("8192 WAL");
            assertSame(searchParent, cacheSize.getParent(),
                    "editing a matching value must not reparent its focused control");

            javax.swing.JCheckBox showUnsaved = (javax.swing.JCheckBox) getField(panel, "showUnsavedBox");
            showUnsaved.doClick();
            assertEquals(0, results.getComponentCount(), "hiding unsaved values should remove the match");

            com.google.gson.JsonObject current = panel.getCurrentProfileSettings();
            assertTrue(current.get(SQLiteProfile.CFG_URL).getAsString().endsWith("/alternate.db"));
            assertEquals("DELETE", current.get(SQLiteProfile.CFG_JOURNAL_MODE).getAsString());
            assertEquals("8192 WAL", current.get(SQLiteProfile.CFG_CACHE_SIZE).getAsString());
        });
    }

    @Test
    @DisplayName("search highlights the exact matching text and navigation moves the active band")
    void searchHighlightsMatchingTextAndNavigates() throws Exception {
        String name = "test_sqlite_bands_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", name)
                .toAbsolutePath().normalize();
        AtomicReference<SQLiteConfigurationPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IconFontSwing.register(FontAwesome.getIconFont());
            SQLiteConfigurationPanel panel = new SQLiteConfigurationPanel();
            panel.loadProfile(name, new GlobalSettings());
            panelRef.set(panel);
        });
        SQLiteConfigurationPanel panel = panelRef.get();

        SwingUtilities.invokeAndWait(() -> {
            @SuppressWarnings("unchecked")
            Map<String, javax.swing.JComponent> components = (Map<String, javax.swing.JComponent>) getField(
                    panel, "propertyComponents");
            JComboBox<?> journalMode = (JComboBox<?>) components.get(SQLiteProfile.CFG_JOURNAL_MODE);
            DatabaseFileListEditor databaseFile = (DatabaseFileListEditor) getField(panel, "databaseFileEditor");
            JPanel results = (JPanel) getField(panel, "searchResultsPanel");
            application.utils.gui.SearchMatchPanel searchPanel =
                    (application.utils.gui.SearchMatchPanel) getField(panel, "searchMatchPanel");
            JTextField search = (JTextField) getField(panel, "searchField");

            // A query matching a NON-editable select's value: the selected value
            // carries the band through the swapped band-painting renderer.
            search.setText("wal");
            assertEquals(4, results.getComponentCount(), "the journal-mode value \"WAL\" matches its row");
            assertEquals("1/1", searchPanel.getMatchIndicatorText());
            assertTrue(journalMode.getRenderer() instanceof application.utils.gui.ComboSearchHighlightRenderer,
                    "a non-editable select's matching selected value gets the band renderer");

            // A query matching labels and file-list items: EVERY match is banded
            // (soft), the first one active (strong).
            search.setText("sqlite");
            // All three rows match (two labels, the database file list): two
            // rows contribute label + input + help + separator (4), the file
            // row label + input + separator (3, its help lives inside the
            // editor) = 11.
            assertEquals(11, results.getComponentCount(),
                    "all three rows match (two labels, the database file list)");
            assertEquals("1/3", searchPanel.getMatchIndicatorText());
            Map<String, javax.swing.JLabel> labels = rowLabelComponents(panel);
            javax.swing.JLabel journalLabel = labels.get("SQLite Journal Mode");
            assertTrue(journalLabel instanceof application.utils.gui.SearchMatchLabel,
                    "the row label paints its own match band");
            assertEquals(GuiColors.getSearchActiveMatch(), bandColor(journalLabel),
                    "the first match's label band is the strong (active) color");
            assertEquals(GuiColors.getSearchMatch(), bandColor(labels.get("SQLite Cache Size")),
                    "a plain match's label band is the soft color");
            int bandedItems = 0;
            for (JComboBox<String> item : databaseFile.getItemFields()) {
                bandedItems += ((JTextField) item.getEditor().getEditorComponent())
                        .getHighlighter().getHighlights().length;
            }
            assertEquals(1, bandedItems, "the matching database-file item's editor carries the band");

            // Navigation moves the active (strong) band to the next match; the
            // previous match keeps the soft one.
            navigate(panel, true);
            assertEquals("2/3", searchPanel.getMatchIndicatorText());
            assertEquals(GuiColors.getSearchMatch(), bandColor(journalLabel),
                    "the previous match keeps the soft band");
            assertEquals(GuiColors.getSearchActiveMatch(), bandColor(labels.get("SQLite Cache Size")),
                    "the navigated-to match carries the strong band");

            // Clearing the search removes every band and restores the combos'
            // original renderers.
            search.setText("");
            assertTrue(!(journalMode.getRenderer() instanceof application.utils.gui.ComboSearchHighlightRenderer),
                    "the combo's original renderer is restored");
            assertEquals(0, ((JTextField) databaseFile.getItemFields().get(0).getEditor().getEditorComponent())
                    .getHighlighter().getHighlights().length, "the file-list band is cleared");
            assertEquals(-1, highlightStart(labels.get("SQLite Journal Mode")), "the label band is cleared");
        });
    }

    @Test
    @DisplayName("search matches every database-file list item, not only the active (first) file")
    void searchMatchesNonActiveDatabaseFileItems() throws Exception {
        String name = "test_sqlite_file_search_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", name)
                .toAbsolutePath().normalize();
        Files.createDirectories(createdProfile);
        Files.createFile(createdProfile.resolve("signum.sqlite.db"));
        Files.createFile(createdProfile.resolve("zzz2.db"));
        AtomicReference<SQLiteConfigurationPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IconFontSwing.register(FontAwesome.getIconFont());
            SQLiteConfigurationPanel panel = new SQLiteConfigurationPanel();
            panel.loadProfile(name, new GlobalSettings());
            panelRef.set(panel);
        });
        SQLiteConfigurationPanel panel = panelRef.get();

        SwingUtilities.invokeAndWait(() -> {
            DatabaseFileListEditor databaseFile = (DatabaseFileListEditor) getField(panel, "databaseFileEditor");
            application.utils.gui.SearchMatchPanel searchPanel =
                    (application.utils.gui.SearchMatchPanel) getField(panel, "searchMatchPanel");
            JTextField search = (JTextField) getField(panel, "searchField");
            JPanel results = (JPanel) getField(panel, "searchResultsPanel");

            // The list is sorted: the ACTIVE file is the first (signum.sqlite);
            // "zzz2" is the second item and occurs nowhere else (not in the
            // composed DB.URL value, not in any label or key).
            assertEquals(2, databaseFile.getItemFields().size(), "both database files are listed");
            search.setText("zzz2");
            assertEquals(3, results.getComponentCount(),
                    "the database-file row matches a non-active file's name (label + input + separator)");
            assertEquals("1/1", searchPanel.getMatchIndicatorText());
            JTextField firstField =
                    (JTextField) databaseFile.getItemFields().get(0).getEditor().getEditorComponent();
            JTextField secondField =
                    (JTextField) databaseFile.getItemFields().get(1).getEditor().getEditorComponent();
            assertEquals(0, firstField.getHighlighter().getHighlights().length,
                    "the active (first) file's editor has no band");
            assertEquals(1, secondField.getHighlighter().getHighlights().length,
                    "the matching (second) file item's editor carries the band");

            // Clearing restores the full list and removes the band.
            search.setText("");
            assertEquals(0, secondField.getHighlighter().getHighlights().length,
                    "the file-list band is cleared");
        });
    }

    @Test
    @DisplayName("every matching file item counts as its own match; navigation moves the strong band between them")
    void searchCountsEveryMatchingFileItemAndMovesTheBand() throws Exception {
        String name = "test_sqlite_item_search_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", name)
                .toAbsolutePath().normalize();
        Files.createDirectories(createdProfile);
        Files.createFile(createdProfile.resolve("lami.db"));
        Files.createFile(createdProfile.resolve("lami1.db"));
        Files.createFile(createdProfile.resolve("lami2.db"));
        AtomicReference<SQLiteConfigurationPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IconFontSwing.register(FontAwesome.getIconFont());
            SQLiteConfigurationPanel panel = new SQLiteConfigurationPanel();
            panel.loadProfile(name, new GlobalSettings());
            panelRef.set(panel);
        });
        SQLiteConfigurationPanel panel = panelRef.get();

        SwingUtilities.invokeAndWait(() -> {
            DatabaseFileListEditor databaseFile = (DatabaseFileListEditor) getField(panel, "databaseFileEditor");
            application.utils.gui.SearchMatchPanel searchPanel =
                    (application.utils.gui.SearchMatchPanel) getField(panel, "searchMatchPanel");
            JTextField search = (JTextField) getField(panel, "searchField");
            assertEquals(3, databaseFile.getItemFields().size(), "all three files are listed");

            // Every matching item is ONE real match — the counter shows the
            // true count, not the single row they live in. The navigated
            // (first) match carries the strong (movable) band, the others the
            // soft (selected) one.
            search.setText("lami");
            assertEquals("1/3", searchPanel.getMatchIndicatorText(),
                    "each matching file item is its own match");
            assertEquals(application.utils.gui.SearchValueHighlight.PAINTER_ACTIVE,
                    bandPainter(databaseFile, 0), "the navigated item carries the strong band");
            assertEquals(application.utils.gui.SearchValueHighlight.PAINTER,
                    bandPainter(databaseFile, 1), "the other matches keep the soft band");
            assertEquals(application.utils.gui.SearchValueHighlight.PAINTER,
                    bandPainter(databaseFile, 2), "the other matches keep the soft band");

            // Navigation moves the strong band to the next item; the previous
            // one keeps its soft (selected) band.
            navigate(panel, true);
            assertEquals("2/3", searchPanel.getMatchIndicatorText());
            assertEquals(application.utils.gui.SearchValueHighlight.PAINTER, bandPainter(databaseFile, 0));
            assertEquals(application.utils.gui.SearchValueHighlight.PAINTER_ACTIVE,
                    bandPainter(databaseFile, 1), "the navigated item carries the strong band");
            assertEquals(application.utils.gui.SearchValueHighlight.PAINTER, bandPainter(databaseFile, 2));

            // The navigation wraps around the match list.
            navigate(panel, true);
            assertEquals("3/3", searchPanel.getMatchIndicatorText());
            assertEquals(application.utils.gui.SearchValueHighlight.PAINTER_ACTIVE,
                    bandPainter(databaseFile, 2));
            navigate(panel, true);
            assertEquals("1/3", searchPanel.getMatchIndicatorText(), "the navigation wraps");
            assertEquals(application.utils.gui.SearchValueHighlight.PAINTER_ACTIVE,
                    bandPainter(databaseFile, 0));

            // Clearing removes every band.
            search.setText("");
            for (int i = 0; i < databaseFile.getItemFields().size(); i++) {
                assertEquals(0, itemEditor(databaseFile, i).getHighlighter().getHighlights().length,
                        "the file-list band is cleared");
            }
        });
    }

    private static JTextField itemEditor(DatabaseFileListEditor editor, int index) {
        return (JTextField) editor.getItemFields().get(index).getEditor().getEditorComponent();
    }

    private static Object bandPainter(DatabaseFileListEditor editor, int index) {
        javax.swing.text.Highlighter.Highlight[] highlights =
                itemEditor(editor, index).getHighlighter().getHighlights();
        return highlights.length == 0 ? null : highlights[0].getPainter();
    }

    @Test
    @DisplayName("clearing the search restores every row under its original section header")
    void clearingSearchRestoresOriginalSectionOrder() throws Exception {
        String name = "test_sqlite_restore_order_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", name)
                .toAbsolutePath().normalize();
        AtomicReference<SQLiteConfigurationPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IconFontSwing.register(FontAwesome.getIconFont());
            SQLiteConfigurationPanel panel = new SQLiteConfigurationPanel();
            panel.loadProfile(name, new GlobalSettings());
            panelRef.set(panel);
        });
        SQLiteConfigurationPanel panel = panelRef.get();

        SwingUtilities.invokeAndWait(() -> {
            JPanel settings = (JPanel) getField(panel, "settingsContentPanel");
            @SuppressWarnings("unchecked")
            List<DatabaseConfigurationPanel.PropertyRow> rows =
                    (List<DatabaseConfigurationPanel.PropertyRow>) getField(panel, "allPropertyRows");
            JTextField search = (JTextField) getField(panel, "searchField");

            DatabaseConfigurationPanel.PropertyRow sqliteHeader =
                    rowByLabel(rows, "SQLite Configuration");
            DatabaseConfigurationPanel.PropertyRow journalRow =
                    rowByKey(rows, SQLiteProfile.CFG_JOURNAL_MODE);
            DatabaseConfigurationPanel.PropertyRow cacheRow =
                    rowByKey(rows, SQLiteProfile.CFG_CACHE_SIZE);
            DatabaseConfigurationPanel.PropertyRow storageHeader =
                    rowByLabel(rows, "Database Storage Configuration");
            DatabaseConfigurationPanel.PropertyRow fileRow = rowByKey(rows, SQLiteProfile.CFG_URL);

            // Search and clear: the row parts are re-parented to the results
            // panel and back — the sections must keep their original order.
            search.setText("sqlite");
            search.setText("");

            int headerA = componentIndex(settings, sqliteHeader.label);
            int journal = componentIndex(settings, journalRow.label);
            int cache = componentIndex(settings, cacheRow.label);
            int headerB = componentIndex(settings, storageHeader.label);
            int file = componentIndex(settings, fileRow.label);
            assertTrue(headerA < journal && journal < cache && cache < headerB && headerB < file,
                    "after the search is cleared every row sits under its own section header");
        });
    }

    private static DatabaseConfigurationPanel.PropertyRow rowByKey(
            List<DatabaseConfigurationPanel.PropertyRow> rows, String key) {
        return rows.stream()
                .filter(row -> key.equals(row.propertyKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no property row with key " + key));
    }

    private static DatabaseConfigurationPanel.PropertyRow rowByLabel(
            List<DatabaseConfigurationPanel.PropertyRow> rows, String text) {
        return rows.stream()
                .filter(row -> row.propertyKey == null && text.equals(row.labelText))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no section header with label " + text));
    }

    private static int componentIndex(JPanel panel, JComponent component) {
        for (int i = 0; i < panel.getComponentCount(); i++) {
            if (panel.getComponent(i) == component) {
                return i;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("state-aware selects list the saved and the unsaved value, colored by state")
    void stateAwareSelectsListAndColorSavedAndUnsavedValues() throws Exception {
        String name = "test_sqlite_state_aware_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", name)
                .toAbsolutePath().normalize();
        AtomicReference<SQLiteConfigurationPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IconFontSwing.register(FontAwesome.getIconFont());
            SQLiteConfigurationPanel panel = new SQLiteConfigurationPanel();
            panel.loadProfile(name, new GlobalSettings());
            panelRef.set(panel);
        });
        SQLiteConfigurationPanel panel = panelRef.get();

        @SuppressWarnings("unchecked")
        Map<String, javax.swing.JComponent> components = (Map<String, javax.swing.JComponent>) getField(panel, "propertyComponents");
        JComboBox<String> journalMode = (JComboBox<String>) components.get(SQLiteProfile.CFG_JOURNAL_MODE);
        JComboBox<String> cacheSize = (JComboBox<String>) components.get(SQLiteProfile.CFG_CACHE_SIZE);

        AtomicReference<List<String>> journalItems = new AtomicReference<>();
        AtomicReference<List<String>> cacheItems = new AtomicReference<>();
        AtomicReference<Color> journalSavedColor = new AtomicReference<>();
        AtomicReference<Color> journalUnsavedColor = new AtomicReference<>();
        AtomicReference<Color> cacheSavedColor = new AtomicReference<>();
        AtomicReference<Color> cacheUnsavedColor = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            // edit both values away from their saved baselines
            // The journal-mode select is NOT editable (closed value set).
            journalMode.setSelectedItem("DELETE");
            cacheSize.getEditor().setItem("8192");
        });
        // Let the deferred state-aware refresh (the model rebuild) settle.
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> {
            journalItems.set(comboModelItems(journalMode));
            cacheItems.set(comboModelItems(cacheSize));
            JList<Object> list = new JList<>(new String[0]);
            journalSavedColor.set(rendererColor(journalMode, list, "WAL"));
            journalUnsavedColor.set(rendererColor(journalMode, list, "DELETE"));
            cacheSavedColor.set(rendererColor(cacheSize, list, "-131072"));
            cacheUnsavedColor.set(rendererColor(cacheSize, list, "8192"));
        });
        assertTrue(journalItems.get().containsAll(List.of("DELETE", "WAL")),
                "the journal-mode dropdown lists the unsaved and the saved value");
        assertTrue(cacheItems.get().containsAll(List.of("8192", "-131072")),
                "the cache-size dropdown lists the unsaved and the saved value");
        assertEquals(GuiColors.getSaved(), journalSavedColor.get(),
                "the saved journal-mode baseline is saved-colored");
        assertEquals(GuiColors.getUnsaved(), journalUnsavedColor.get(),
                "the unsaved journal-mode value is unsaved-colored");
        assertEquals(GuiColors.getSaved(), cacheSavedColor.get(),
                "the saved cache-size baseline is saved-colored");
        assertEquals(GuiColors.getUnsaved(), cacheUnsavedColor.get(),
                "the unsaved cache-size value is unsaved-colored");
    }

    @Test
    @DisplayName("unsaved changes are marked with an asterisk on the row label")
    void unsavedChangesAreMarkedWithAsterisk() throws Exception {
        String name = "test_sqlite_dirty_marker_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", name)
                .toAbsolutePath().normalize();
        AtomicReference<SQLiteConfigurationPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IconFontSwing.register(FontAwesome.getIconFont());
            SQLiteConfigurationPanel panel = new SQLiteConfigurationPanel();
            panel.loadProfile(name, new GlobalSettings());
            panelRef.set(panel);
        });
        SQLiteConfigurationPanel panel = panelRef.get();

        Map<String, String> before = new java.util.HashMap<>();
        SwingUtilities.invokeAndWait(() -> before.putAll(rowLabels(panel)));
        assertEquals("SQLite Journal Mode", before.get(SQLiteProfile.CFG_JOURNAL_MODE));
        assertEquals("SQLite Cache Size", before.get(SQLiteProfile.CFG_CACHE_SIZE));
        assertEquals("Database File", before.get(SQLiteProfile.CFG_URL));

        SwingUtilities.invokeAndWait(() -> {
            DatabaseFileListEditor databaseFile = (DatabaseFileListEditor) getField(panel, "databaseFileEditor");
            @SuppressWarnings("unchecked")
            Map<String, javax.swing.JComponent> components =
                    (Map<String, javax.swing.JComponent>) getField(panel, "propertyComponents");
            JComboBox<String> cacheSize = (JComboBox<String>) components.get(SQLiteProfile.CFG_CACHE_SIZE);
            cacheSize.getEditor().setItem("8192");
            databaseFile.setActiveFileText("renamed.db");
        });
        // Let the deferred state-aware refresh (the dirty marker update) settle.
        SwingUtilities.invokeAndWait(() -> { });

        Map<String, String> after = new java.util.HashMap<>();
        SwingUtilities.invokeAndWait(() -> after.putAll(rowLabels(panel)));
        assertEquals("SQLite Cache Size *", after.get(SQLiteProfile.CFG_CACHE_SIZE),
                "an edited value marks its row label with an asterisk");
        assertEquals("Database File *", after.get(SQLiteProfile.CFG_URL),
                "a renamed database file marks the list row with an asterisk");
        assertEquals("SQLite Journal Mode", after.get(SQLiteProfile.CFG_JOURNAL_MODE),
                "an unchanged row keeps no asterisk");
    }

    @Test
    @DisplayName("a stored DB.Url in the profile JSON is ignored on load and dropped on save (settings-only JSON)")
    void storedUrlIsIgnoredAndDroppedOnSave() throws Exception {
        String name = "test_sqlite_legacy_url_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", name)
                .toAbsolutePath().normalize();
        // A profile.json written by an older version: a stored DB.Url pointing
        // at a file that is NOT the first discovered one, plus custom settings.
        Files.createDirectories(createdProfile);
        Files.writeString(createdProfile.resolve("alpha.db"), "alpha content");
        Files.writeString(createdProfile.resolve("zzz.db"), "zzz content");
        Files.writeString(createdProfile.resolve("profile.json"),
                "{\n"
                        + "  \"DB.Url\": \"jdbc:sqlite:file:./database/SQLite/" + name + "/zzz.db\",\n"
                        + "  \"DB.SqliteJournalMode\": \"TRUNCATE\",\n"
                        + "  \"DB.SqliteCacheSize\": \"-8192\"\n"
                        + "}");

        AtomicReference<SQLiteConfigurationPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IconFontSwing.register(FontAwesome.getIconFont());
            SQLiteConfigurationPanel panel = new SQLiteConfigurationPanel();
            panel.loadProfile(name, new GlobalSettings());
            panelRef.set(panel);
        });
        SQLiteConfigurationPanel panel = panelRef.get();

        Map<String, String> labels = new java.util.HashMap<>();
        SwingUtilities.invokeAndWait(() -> labels.putAll(rowLabels(panel)));
        assertEquals("Database File", labels.get(SQLiteProfile.CFG_URL),
                "the stored URL is ignored — the derived URL (the first file on disk) matches the editor");

        AtomicReference<com.google.gson.JsonObject> settingsRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> settingsRef.set(panel.getCurrentProfileSettings()));
        assertTrue(settingsRef.get().get(SQLiteProfile.CFG_URL).getAsString()
                        .endsWith("/" + name + "/alpha.db"),
                "the editor's composed URL points at the first (active) file, not the stored one");

        // Save: the stored URL is dropped, the settings survive.
        SwingUtilities.invokeAndWait(() -> {
            try {
                java.lang.reflect.Method sync = SQLiteConfigurationPanel.class
                        .getDeclaredMethod("syncControlsToProfile");
                sync.setAccessible(true);
                sync.invoke(panel);
                SQLiteProfile profile = (SQLiteProfile) getField(panel, "currentProfile");
                profile.saveToProfileJson();
            } catch (Exception e) {
                throw new AssertionError("Could not save the profile", e);
            }
        });
        com.google.gson.JsonObject saved = com.google.gson.JsonParser.parseString(
                Files.readString(createdProfile.resolve("profile.json"))).getAsJsonObject();
        assertFalse(saved.has(SQLiteProfile.CFG_URL),
                "the save drops the stored URL (the URL is derived)");
        assertEquals("TRUNCATE", saved.get(SQLiteProfile.CFG_JOURNAL_MODE).getAsString(),
                "the existing engine settings survive");
        assertEquals("-8192", saved.get(SQLiteProfile.CFG_CACHE_SIZE).getAsString());
    }

    @Test
    @DisplayName("the profile selector marks the profile with unsaved changes with an asterisk")
    void profileSelectorMarksUnsavedProfile() throws Exception {
        String name = "test_sqlite_profile_star_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", name)
                .toAbsolutePath().normalize();
        AtomicReference<SQLiteConfigurationPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IconFontSwing.register(FontAwesome.getIconFont());
            SQLiteConfigurationPanel panel = new SQLiteConfigurationPanel();
            panel.loadProfile(name, new GlobalSettings());
            panelRef.set(panel);
        });
        SQLiteConfigurationPanel panel = panelRef.get();

        assertEquals(name, profileCellText(panel, name),
                "a freshly loaded profile carries no star");

        SwingUtilities.invokeAndWait(() -> {
            @SuppressWarnings("unchecked")
            Map<String, javax.swing.JComponent> components =
                    (Map<String, javax.swing.JComponent>) getField(panel, "propertyComponents");
            JComboBox<String> cacheSize = (JComboBox<String>) components.get(SQLiteProfile.CFG_CACHE_SIZE);
            cacheSize.getEditor().setItem("8192");
        });
        // Let the deferred state-aware refresh (the marker update) settle.
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(name + " *", profileCellText(panel, name),
                "a profile with unsaved editor changes is starred in the selector");

        SwingUtilities.invokeAndWait(() -> {
            @SuppressWarnings("unchecked")
            Map<String, javax.swing.JComponent> components =
                    (Map<String, javax.swing.JComponent>) getField(panel, "propertyComponents");
            JComboBox<String> cacheSize = (JComboBox<String>) components.get(SQLiteProfile.CFG_CACHE_SIZE);
            cacheSize.getEditor().setItem("-131072");
        });
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(name, profileCellText(panel, name),
                "a profile without unsaved changes loses the star");
    }

    @Test
    @DisplayName("deleting a database file leaves no unsaved marker; deleting the active one does")
    void deletingAFileLeavesNoUnsavedMarker() throws Exception {
        String name = "test_sqlite_delete_marker_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", name)
                .toAbsolutePath().normalize();
        // A stored DB.Url (older-version profile JSON) — ignored on load; the
        // derived URL's active file is the first file on disk (alpha.db), so
        // the profile starts clean (the file list matches the directory on disk).
        SQLiteProfile profile = new SQLiteProfile(name);
        profile.getConfiguration().put(SQLiteProfile.CFG_URL,
                application.module.node.profile.SqliteDatabasePaths.composeUrl(
                        application.module.node.profile.SqliteDatabasePaths.profileDir(name), "alpha.db"));
        profile.saveToProfileJson();
        Files.createDirectories(createdProfile);
        Files.writeString(createdProfile.resolve("alpha.db"), "alpha content");
        Files.writeString(createdProfile.resolve("beta.db"), "beta content");

        AtomicReference<SQLiteConfigurationPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IconFontSwing.register(FontAwesome.getIconFont());
            SQLiteConfigurationPanel panel = new SQLiteConfigurationPanel();
            panel.loadProfile(name, new GlobalSettings());
            panelRef.set(panel);
        });
        SQLiteConfigurationPanel panel = panelRef.get();
        assertEquals("Database File", rowLabels(panel).get(SQLiteProfile.CFG_URL));

        SwingUtilities.invokeAndWait(() -> {
            DatabaseFileListEditor editor = (DatabaseFileListEditor) getField(panel, "databaseFileEditor");
            // The trash confirmation: approve and delete from disk — the real
            // panel confirmation does exactly this (without the dialogs).
            editor.removeConfirmation = fileName -> {
                try {
                    Files.deleteIfExists(createdProfile.resolve(fileName));
                    return true;
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            };
            editor.removeItem(editor.itemCombos().get(1)); // beta (not the active file)
        });
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals("Database File", rowLabels(panel).get(SQLiteProfile.CFG_URL),
                "the disk deletion is immediate — removing a non-active file leaves no unsaved state");

        SwingUtilities.invokeAndWait(() -> {
            DatabaseFileListEditor editor = (DatabaseFileListEditor) getField(panel, "databaseFileEditor");
            editor.removeItem(editor.itemCombos().get(0)); // alpha (the active file)
        });
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals("Database File *", rowLabels(panel).get(SQLiteProfile.CFG_URL),
                "removing the active file re-points the DB.URL — a real unsaved change");
    }

    private static String profileCellText(SQLiteConfigurationPanel panel, String name) throws Exception {
        AtomicReference<String> text = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            @SuppressWarnings("unchecked")
            JComboBox<Object> combo = (JComboBox<Object>) getField(panel, "profileComboBox");
            JList<Object> list = new JList<>(new Object[] { name });
            javax.swing.ListCellRenderer<Object> renderer = combo.getRenderer();
            javax.swing.JLabel cell = (javax.swing.JLabel) renderer
                    .getListCellRendererComponent(list, name, 0, false, false);
            text.set(String.valueOf(cell.getText()));
        });
        return text.get();
    }

    @Test
    @DisplayName("renaming a database file moves the existing file on disk (content kept)")
    void renamingAFileMovesTheExistingFileOnDisk() throws Exception {
        String name = "test_sqlite_rename_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", name)
                .toAbsolutePath().normalize();
        SQLiteProfile profile = new SQLiteProfile(name);
        profile.saveToProfileJson();
        Files.createDirectories(createdProfile);
        Files.writeString(createdProfile.resolve("alpha.db"), "alpha content");
        Files.writeString(createdProfile.resolve("alpha.db-wal"), "alpha wal");
        Files.writeString(createdProfile.resolve("beta.db"), "beta content");

        AtomicReference<SQLiteConfigurationPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IconFontSwing.register(FontAwesome.getIconFont());
            SQLiteConfigurationPanel panel = new SQLiteConfigurationPanel();
            panel.loadProfile(name, new GlobalSettings());
            panelRef.set(panel);
        });
        SQLiteConfigurationPanel panel = panelRef.get();

        SwingUtilities.invokeAndWait(() -> {
            DatabaseFileListEditor editor = (DatabaseFileListEditor) getField(panel, "databaseFileEditor");
            editor.setActiveFileText("gamma.db"); // alpha → gamma
        });
        final IOException[] failure = new IOException[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                panel.renameModifiedDatabaseFiles();
            } catch (IOException e) {
                failure[0] = e;
            }
        });
        assertNull(failure[0]);
        assertTrue(Files.exists(createdProfile.resolve("gamma.db")));
        assertEquals("alpha content", Files.readString(createdProfile.resolve("gamma.db")));
        assertEquals("alpha wal", Files.readString(createdProfile.resolve("gamma.db-wal")),
                "the WAL companion moves with the renamed file");
        assertFalse(Files.exists(createdProfile.resolve("alpha.db")));
        assertFalse(Files.exists(createdProfile.resolve("alpha.db-wal")));
        assertEquals("beta content", Files.readString(createdProfile.resolve("beta.db")));
    }

    @Test
    @DisplayName("a rename onto an existing, unrelated database file aborts with an error")
    void renameConflictAborts() throws Exception {
        String name = "test_sqlite_rename_conflict_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", name)
                .toAbsolutePath().normalize();
        SQLiteProfile profile = new SQLiteProfile(name);
        profile.saveToProfileJson();
        Files.createDirectories(createdProfile);
        Files.writeString(createdProfile.resolve("alpha.db"), "alpha content");
        Files.writeString(createdProfile.resolve("beta.db"), "beta content");

        AtomicReference<SQLiteConfigurationPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IconFontSwing.register(FontAwesome.getIconFont());
            SQLiteConfigurationPanel panel = new SQLiteConfigurationPanel();
            panel.loadProfile(name, new GlobalSettings());
            panelRef.set(panel);
        });
        SQLiteConfigurationPanel panel = panelRef.get();

        SwingUtilities.invokeAndWait(() -> {
            DatabaseFileListEditor editor = (DatabaseFileListEditor) getField(panel, "databaseFileEditor");
            editor.setActiveFileText("beta.db"); // alpha → beta, but beta.db exists and is not renamed
        });
        final IOException[] failure = new IOException[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                panel.renameModifiedDatabaseFiles();
            } catch (IOException e) {
                failure[0] = e;
            }
        });
        assertNotNull(failure[0], "the conflicting rename must fail");
        assertTrue(failure[0].getMessage().contains("beta.db"),
                "the error names the conflicting target");
        assertTrue(Files.exists(createdProfile.resolve("alpha.db")), "nothing is moved on conflict");
        assertEquals("beta content", Files.readString(createdProfile.resolve("beta.db")));
    }

    @Test
    @DisplayName("a rename cycle (alpha to beta, beta to alpha) swaps the files on disk")
    void renameCycleSwapsFiles() throws Exception {
        String name = "test_sqlite_rename_cycle_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", name)
                .toAbsolutePath().normalize();
        SQLiteProfile profile = new SQLiteProfile(name);
        profile.saveToProfileJson();
        Files.createDirectories(createdProfile);
        Files.writeString(createdProfile.resolve("alpha.db"), "alpha content");
        Files.writeString(createdProfile.resolve("beta.db"), "beta content");

        AtomicReference<SQLiteConfigurationPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IconFontSwing.register(FontAwesome.getIconFont());
            SQLiteConfigurationPanel panel = new SQLiteConfigurationPanel();
            panel.loadProfile(name, new GlobalSettings());
            panelRef.set(panel);
        });
        SQLiteConfigurationPanel panel = panelRef.get();

        SwingUtilities.invokeAndWait(() -> {
            DatabaseFileListEditor editor = (DatabaseFileListEditor) getField(panel, "databaseFileEditor");
            editor.setActiveFileText("beta.db"); // alpha → beta
            JComboBox<String> second = editor.itemCombos().get(1);
            javax.swing.text.JTextComponent field =
                    (javax.swing.text.JTextComponent) second.getEditor().getEditorComponent();
            try {
                // "beta" → "albeta" → "al" → "alpha" (never through the empty
                // state — clearing the field removes the row)
                field.getDocument().insertString(0, "al", null);
                field.getDocument().remove(2, 4);
                field.getDocument().insertString(2, "pha", null);
            } catch (javax.swing.text.BadLocationException impossible) {
                throw new IllegalStateException(impossible);
            }
        });
        final IOException[] failure = new IOException[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                panel.renameModifiedDatabaseFiles();
            } catch (IOException e) {
                failure[0] = e;
            }
        });
        assertNull(failure[0]);
        assertEquals("beta content", Files.readString(createdProfile.resolve("alpha.db")));
        assertEquals("alpha content", Files.readString(createdProfile.resolve("beta.db")));
        assertFalse(Files.exists(createdProfile.resolve("alpha.rename-tmp.db")),
                "the temporary name used to break the cycle is not left behind");
    }

    private static Map<String, String> rowLabels(SQLiteConfigurationPanel panel) {
        Map<String, String> labels = new java.util.HashMap<>();
        for (DatabaseConfigurationPanel.PropertyRow row : panel.getAllPropertyRows()) {
            if (row.propertyKey != null && row.label != null) {
                labels.put(row.propertyKey, row.label.getText());
            }
        }
        return labels;
    }

    private static List<String> comboModelItems(JComboBox<?> combo) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < combo.getModel().getSize(); i++) {
            items.add(String.valueOf(combo.getModel().getElementAt(i)));
        }
        return items;
    }

    private static Color rendererColor(JComboBox<?> combo, JList<Object> list, String item) {
        @SuppressWarnings("unchecked")
        javax.swing.ListCellRenderer<Object> renderer = (javax.swing.ListCellRenderer<Object>) combo.getRenderer();
        java.awt.Component cell = renderer.getListCellRendererComponent(list, item, 0, false, false);
        return ((javax.swing.JComponent) cell).getForeground();
    }

    @Test
    @DisplayName("SQLite assignments update only DB.Url in the node profile")
    void assignmentUsesNodeProfilePropertiesAndPreservesUnrelatedValues() throws Exception {
        String dbProfileName = "test_sqlite_assignment_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", dbProfileName)
                .toAbsolutePath().normalize();
        SQLiteProfile dbProfile = new SQLiteProfile(dbProfileName);
        dbProfile.saveToProfileJson();

        temporaryConfRoot = Files.createTempDirectory("sqlite-assignment-conf");
        String nodeProfileName = "node_test";
        Path nodeProfileFile = PropertiesProfileLoader.resolveProfileFile(
                temporaryConfRoot.toString(), ModuleIds.NODE, ModuleIds.CATEGORY_PROFILES, nodeProfileName);
        Files.createDirectories(nodeProfileFile.getParent());
        Files.writeString(nodeProfileFile, "# retained comment\nnode.description=keep me\n");

        AtomicReference<SQLiteDatabaseAssignmentPanel> assignments = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                assignments.set(new SQLiteDatabaseAssignmentPanel(temporaryConfRoot.toString())));
        assignments.get().saveAssignment(nodeProfileName, dbProfileName);

        Properties stored = new Properties();
        try (var reader = Files.newBufferedReader(nodeProfileFile)) {
            stored.load(reader);
        }
        assertEquals(dbProfile.getDbUrl(), stored.getProperty(Props.DB_URL.getName()));
        assertEquals("keep me", stored.getProperty("node.description"));
        assertTrue(Files.readString(nodeProfileFile).contains("# retained comment"));
        assertEquals(List.of(nodeProfileName), SQLiteDatabaseAssignmentPanel.assignedNodeProfiles(
                temporaryConfRoot.toString(), dbProfileName));

        String renamedDatabaseProfile = dbProfileName + "_renamed";
        SQLiteDatabaseAssignmentPanel.renameAssignments(
                temporaryConfRoot.toString(), dbProfileName, renamedDatabaseProfile);
        stored.clear();
        try (var reader = Files.newBufferedReader(nodeProfileFile)) {
            stored.load(reader);
        }
        assertEquals("jdbc:sqlite:file:./database/SQLite/" + renamedDatabaseProfile + "/signum.sqlite.db",
                stored.getProperty(Props.DB_URL.getName()));

        assignments.get().saveAssignment(nodeProfileName, "(default)");
        stored.clear();
        try (var reader = Files.newBufferedReader(nodeProfileFile)) {
            stored.load(reader);
        }
        assertFalse(stored.containsKey(Props.DB_URL.getName()));
        assertEquals("keep me", stored.getProperty("node.description"));

    }

    @Test
    @DisplayName("assignments are driven by the discovered SQLite profiles; a foreign URL is not listed")
    void foreignUrlIsNotListedWithoutADiscoveredProfile() throws Exception {
        String dbProfileName = "test_sqlite_foreign_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", dbProfileName)
                .toAbsolutePath().normalize();
        new SQLiteProfile(dbProfileName).saveToProfileJson();
        Files.createDirectories(createdProfile);
        Files.createFile(createdProfile.resolve("foreign.db"));

        temporaryConfRoot = Files.createTempDirectory("sqlite-foreign-url-conf");
        createAssignmentNodeProfile("node_listed",
                application.module.node.profile.SqliteDatabasePaths.composeUrl(createdProfile, "foreign.db"));
        // Points outside the discovered profiles - there is nothing on the
        // SQLite side to list it under, so it stays out of the tab.
        Path externalFile = temporaryConfRoot.resolve("external").resolve("custom.sqlite.db");
        createAssignmentNodeProfile("node_external",
                application.module.node.profile.SqliteDatabasePaths.composeUrl(
                        externalFile.getParent(), externalFile.getFileName().toString()));

        AtomicReference<SQLiteDatabaseAssignmentPanel> assignments = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                assignments.set(new SQLiteDatabaseAssignmentPanel(temporaryConfRoot.toString())));

        Field tableField = SQLiteDatabaseAssignmentPanel.class.getDeclaredField("table");
        tableField.setAccessible(true);
        JTable table = (JTable) tableField.get(assignments.get());
        assertEquals(3, table.getColumnCount());
        assertEquals("SQLite Profile", table.getColumnModel().getColumn(0).getHeaderValue());
        assertEquals("Database File", table.getColumnModel().getColumn(1).getHeaderValue());
        assertEquals("Assigned Module Profile", table.getColumnModel().getColumn(2).getHeaderValue());
        assertEquals(1, table.getRowCount(), "only the discovered profile with a consumer is listed");
        assertEquals(dbProfileName, table.getValueAt(0, 0));
        assertEquals("foreign.db", table.getValueAt(0, 1));
        assertEquals("Node/node_listed", table.getValueAt(0, 2));
        assertFalse(table.isCellEditable(0, 0), "the tab is read-only");
        assertFalse(table.isCellEditable(0, 2), "the tab is read-only");
    }

    @Test
    @DisplayName("a node without DB.Url is listed as a consumer of the built-in default database profile")
    void blankDbUrlResolvesTheDefaultDatabaseProfile() throws Exception {
        // The built-in default database lives in ./database/SQLite/sqlite -
        // make the profile discoverable when the local workspace has no such
        // folder yet (and clean up only what this test created).
        Path sqliteProfileDir = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", "sqlite");
        boolean createdDefaultProfile = !Files.isDirectory(sqliteProfileDir);
        if (createdDefaultProfile) {
            Files.createDirectories(sqliteProfileDir);
        }
        try {
            temporaryConfRoot = Files.createTempDirectory("sqlite-default-assignment-conf");
            createAssignmentNodeProfile("node_default", null);

            AtomicReference<SQLiteDatabaseAssignmentPanel> assignments = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() ->
                    assignments.set(new SQLiteDatabaseAssignmentPanel(temporaryConfRoot.toString())));

            Field tableField = SQLiteDatabaseAssignmentPanel.class.getDeclaredField("table");
            tableField.setAccessible(true);
            JTable table = (JTable) tableField.get(assignments.get());
            // Every listed profile must be the built-in default one - the
            // blank-URL node consumes no other profile. (One row per database
            // file of the profile: the count depends on the local workspace's
            // files, so assert on the cells, not the row count.)
            for (int row = 0; row < table.getRowCount(); row++) {
                Object profile = table.getValueAt(row, 0);
                if (profile != null) {
                    assertTrue(((String) profile).startsWith("sqlite"),
                            "no other profile is listed: " + profile);
                }
            }
            int consumerRow = -1;
            for (int row = 0; row < table.getRowCount(); row++) {
                if ("Node/node_default".equals(table.getValueAt(row, 2))) {
                    consumerRow = row;
                }
            }
            assertTrue(consumerRow >= 0,
                    "the node is listed as a consumer of the built-in default profile");
            assertEquals("signum.sqlite.db", table.getValueAt(consumerRow, 1),
                    "the consumer row's file is the built-in default database");
        } finally {
            if (createdDefaultProfile) {
                Files.deleteIfExists(sqliteProfileDir);
            }
        }
    }

    @Test
    @DisplayName("a node without DB.Url counts as a user of the built-in default profile (lookup + rename)")
    void blankDbUrlCountsAsDefaultAssignment() throws Exception {
        temporaryConfRoot = Files.createTempDirectory("sqlite-blank-default-conf");
        String nodeProfileName = "node_blank";
        Path nodeProfileFile = PropertiesProfileLoader.resolveProfileFile(
                temporaryConfRoot.toString(), ModuleIds.NODE, ModuleIds.CATEGORY_PROFILES, nodeProfileName);
        Files.createDirectories(nodeProfileFile.getParent());
        Files.writeString(nodeProfileFile, "node.description=blank url node\n");

        // The reverse lookup counts the blank-URL node as a user of the
        // built-in default profile - and of no other profile.
        assertEquals(List.of(nodeProfileName),
                SQLiteDatabaseAssignmentPanel.assignedNodeProfiles(temporaryConfRoot.toString(), "sqlite"));
        assertTrue(SQLiteDatabaseAssignmentPanel.assignedNodeProfiles(
                temporaryConfRoot.toString(), "some_other").isEmpty(),
                "a blank DB.Url assigns no other profile");

        // Renaming the default profile re-points the node (its URL is made
        // explicit at the new location); unrelated properties stay intact.
        SQLiteDatabaseAssignmentPanel.renameAssignments(
                temporaryConfRoot.toString(), "sqlite", "sqlite_moved");
        Properties stored = new Properties();
        try (var reader = Files.newBufferedReader(nodeProfileFile)) {
            stored.load(reader);
        }
        assertEquals("jdbc:sqlite:file:./database/SQLite/sqlite_moved/signum.sqlite.db",
                stored.getProperty(Props.DB_URL.getName()));
        assertEquals("blank url node", stored.getProperty("node.description"));
    }

    private void createAssignmentNodeProfile(String name, String databaseUrl) throws IOException {
        Path nodeProfileFile = PropertiesProfileLoader.resolveProfileFile(
                temporaryConfRoot.toString(), ModuleIds.NODE, ModuleIds.CATEGORY_PROFILES, name);
        Files.createDirectories(nodeProfileFile.getParent());
        Properties properties = new Properties();
        properties.setProperty("node.description", name);
        if (databaseUrl != null) {
            properties.setProperty(Props.DB_URL.getName(), databaseUrl);
        }
        try (var writer = Files.newBufferedWriter(nodeProfileFile)) {
            properties.store(writer, null);
        }
    }

    @Test
    @DisplayName("a discovered SQLite profile without an assigned module is not listed")
    void profileWithoutAssignedModulesIsNotListed() throws Exception {
        String dbProfileName = "test_sqlite_unassigned_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", dbProfileName)
                .toAbsolutePath().normalize();
        new SQLiteProfile(dbProfileName).saveToProfileJson();
        Files.createDirectories(createdProfile);
        Files.createFile(createdProfile.resolve("unassigned.db"));

        temporaryConfRoot = Files.createTempDirectory("sqlite-unassigned-conf");

        AtomicReference<SQLiteDatabaseAssignmentPanel> assignments = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                assignments.set(new SQLiteDatabaseAssignmentPanel(temporaryConfRoot.toString())));

        Field tableField = SQLiteDatabaseAssignmentPanel.class.getDeclaredField("table");
        tableField.setAccessible(true);
        JTable table = (JTable) tableField.get(assignments.get());
        assertEquals(0, table.getRowCount(), "no module profile assigns the profile - it is not listed");
    }

    @Test
    @DisplayName("multiple modules may share one profile and file; the rows merge per profile and per file")
    void multipleModulesShareOneProfileAndFile() throws Exception {
        String dbProfileName = "test_sqlite_multi_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", dbProfileName)
                .toAbsolutePath().normalize();
        new SQLiteProfile(dbProfileName).saveToProfileJson();
        Files.createDirectories(createdProfile);
        Files.createFile(createdProfile.resolve("alpha.db"));
        Files.createFile(createdProfile.resolve("beta.db"));

        temporaryConfRoot = Files.createTempDirectory("sqlite-multi-conf");
        String alphaUrl = application.module.node.profile.SqliteDatabasePaths.composeUrl(createdProfile, "alpha.db");
        String betaUrl = application.module.node.profile.SqliteDatabasePaths.composeUrl(createdProfile, "beta.db");
        createAssignmentNodeProfile("node_x", alphaUrl);
        createAssignmentNodeProfile("node_y", betaUrl);
        createAssignmentNodeProfile("node_z", alphaUrl);

        AtomicReference<SQLiteDatabaseAssignmentPanel> assignments = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                assignments.set(new SQLiteDatabaseAssignmentPanel(temporaryConfRoot.toString())));

        Field tableField = SQLiteDatabaseAssignmentPanel.class.getDeclaredField("table");
        tableField.setAccessible(true);
        JTable table = (JTable) tableField.get(assignments.get());
        assertEquals(3, table.getRowCount(), "one row per consumer module");
        assertEquals(dbProfileName, table.getValueAt(0, 0), "the profile cell starts the merged group");
        assertNull(table.getValueAt(1, 0), "the group's continuation rows merge into the profile cell");
        assertNull(table.getValueAt(2, 0), "the group's continuation rows merge into the profile cell");
        assertEquals("alpha.db", table.getValueAt(0, 1), "the file cell starts its own merged block");
        assertNull(table.getValueAt(1, 1), "both alpha.db consumers share the file cell");
        assertEquals("beta.db", table.getValueAt(2, 1), "the second file starts a new block");
        assertEquals("Node/node_x", table.getValueAt(0, 2));
        assertEquals("Node/node_z", table.getValueAt(1, 2));
        assertEquals("Node/node_y", table.getValueAt(2, 2));
    }

    @Test
    @DisplayName("the merged profile/file cells keep the full table grid (inner borders drawn)")
    void mergedCellsKeepTheFullTableGrid() throws Exception {
        String dbProfileName = "test_sqlite_merge_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", dbProfileName)
                .toAbsolutePath().normalize();
        new SQLiteProfile(dbProfileName).saveToProfileJson();
        Files.createDirectories(createdProfile);
        Files.createFile(createdProfile.resolve("alpha.db"));
        Files.createFile(createdProfile.resolve("beta.db"));

        temporaryConfRoot = Files.createTempDirectory("sqlite-merge-conf");
        String alphaUrl = application.module.node.profile.SqliteDatabasePaths.composeUrl(createdProfile, "alpha.db");
        String betaUrl = application.module.node.profile.SqliteDatabasePaths.composeUrl(createdProfile, "beta.db");
        createAssignmentNodeProfile("node_x", alphaUrl);
        createAssignmentNodeProfile("node_y", betaUrl);
        createAssignmentNodeProfile("node_z", alphaUrl);

        AtomicReference<SQLiteDatabaseAssignmentPanel> assignments = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                assignments.set(new SQLiteDatabaseAssignmentPanel(temporaryConfRoot.toString())));

        Field tableField = SQLiteDatabaseAssignmentPanel.class.getDeclaredField("table");
        tableField.setAccessible(true);
        JTable table = (JTable) tableField.get(assignments.get());
        // 3 rows: profile span 3, file spans 2 (alpha) + 1 (beta), the module
        // column unmerged.
        assertEquals(3, table.getRowCount());
        Color gridColor = table.getGridColor();

        final BufferedImage[] rendered = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            table.setSize(900, 300);
            table.validate();
            BufferedImage img = new BufferedImage(table.getWidth(), table.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            table.paint(g);
            g.dispose();
            rendered[0] = img;
        });

        // The grid is a FULL table grid: every row boundary carries a line in
        // EVERY column - the merged profile / file cells keep their inner
        // borders too (painted over the cells, so even a selected span's
        // opaque fill does not hide them).
        int boundary0 = table.getCellRect(0, 0, true).y + table.getCellRect(0, 0, true).height - 1;
        assertTrue(hasGridLinePixel(rendered[0], boundary0, table.getCellRect(0, 0, false), gridColor),
                "the inner border is drawn inside the merged profile cell");
        assertTrue(hasGridLinePixel(rendered[0], boundary0, table.getCellRect(0, 1, false), gridColor),
                "the inner border is drawn inside the merged file cell");
        assertTrue(hasGridLinePixel(rendered[0], boundary0, table.getCellRect(0, 2, false), gridColor),
                "the module column keeps its row border");

        // The boundary below row 1 still lies inside the profile span, but it
        // also separates the two file blocks: the full grid carries the line
        // in all three columns.
        int boundary1 = table.getCellRect(1, 0, true).y + table.getCellRect(1, 0, true).height - 1;
        assertTrue(hasGridLinePixel(rendered[0], boundary1, table.getCellRect(1, 0, false), gridColor),
                "the profile cell keeps the full grid across the file blocks");
        assertTrue(hasGridLinePixel(rendered[0], boundary1, table.getCellRect(1, 1, false), gridColor),
                "the file blocks keep the border between them");
        assertTrue(hasGridLinePixel(rendered[0], boundary1, table.getCellRect(1, 2, false), gridColor),
                "the module column keeps its row border");

        // The column separators run the FULL table height (through the spans).
        for (int column = 0; column < 3; column++) {
            int x = table.getCellRect(0, column, true).x + table.getCellRect(0, column, true).width - 1;
            int yTop = table.getCellRect(0, 0, true).y + 2;
            int yBottom = table.getCellRect(2, 0, true).y + table.getCellRect(2, 0, true).height - 3;
            int hits = 0;
            for (int y = yTop; y < yBottom; y++) {
                Color c = new Color(rendered[0].getRGB(x, y), true);
                if (Math.abs(c.getRed() - gridColor.getRed()) <= 16
                        && Math.abs(c.getGreen() - gridColor.getGreen()) <= 16
                        && Math.abs(c.getBlue() - gridColor.getBlue()) <= 16) {
                    hits++;
                }
            }
            assertTrue(hits > (yBottom - yTop) / 2,
                    "the vertical separator after column " + column + " runs the full height");
        }
    }

    /**
     * @return true when the pixel line at {@code y} inside the strip carries
     *         most of the table's grid color (a painted horizontal grid line;
     *         the strip's outer 4px are skipped to ignore the vertical lines)
     */
    private static boolean hasGridLinePixel(BufferedImage img, int y, java.awt.Rectangle strip, Color grid) {
        int hits = 0;
        for (int x = strip.x + 4; x < strip.x + strip.width - 4; x++) {
            Color c = new Color(img.getRGB(x, y), true);
            if (Math.abs(c.getRed() - grid.getRed()) <= 16
                    && Math.abs(c.getGreen() - grid.getGreen()) <= 16
                    && Math.abs(c.getBlue() - grid.getBlue()) <= 16) {
                hits++;
            }
        }
        return hits > (strip.width - 8) / 2;
    }

    @Test
    @DisplayName("the three columns stay evenly sized and the profile name survives a row selection")
    void columnsStayEvenAndProfileNameSurvivesSelection() throws Exception {
        // The built-in default profile (./database/SQLite/sqlite) drives the
        // rows - make it discoverable when the local workspace has no such
        // folder yet (and clean up only what this test created).
        Path sqliteProfileDir = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", "sqlite");
        boolean createdDefaultProfile = !Files.isDirectory(sqliteProfileDir);
        if (createdDefaultProfile) {
            Files.createDirectories(sqliteProfileDir);
        }
        try {
            temporaryConfRoot = Files.createTempDirectory("sqlite-even-columns-conf");
            for (String name : List.of("node", "node_01", "node_02")) {
                createAssignmentNodeProfile(name, null);
            }

            AtomicReference<SQLiteDatabaseAssignmentPanel> assignments = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() ->
                    assignments.set(new SQLiteDatabaseAssignmentPanel(temporaryConfRoot.toString())));
            SQLiteDatabaseAssignmentPanel panel = assignments.get();

            Field tableField = SQLiteDatabaseAssignmentPanel.class.getDeclaredField("table");
            tableField.setAccessible(true);
            JTable table = (JTable) tableField.get(panel);
            assertEquals("sqlite", table.getValueAt(0, 0), "the profile cell starts the merged group");

            // Size and lay out the table directly (validate() does not
            // propagate layout in a not-showing tree) and render it
            // offscreen. doLayout() is the code path that distributes the
            // width over the columns per the auto-resize mode.
            final BufferedImage[] rendered = new BufferedImage[1];
            SwingUtilities.invokeAndWait(() -> {
                table.setSize(1200, 600);
                table.doLayout();
                BufferedImage img = new BufferedImage(1200, 600, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                table.paint(g);
                g.dispose();
                rendered[0] = img;
            });

            int width0 = table.getColumnModel().getColumn(0).getWidth();
            int width1 = table.getColumnModel().getColumn(1).getWidth();
            int width2 = table.getColumnModel().getColumn(2).getWidth();
            assertTrue(Math.abs(width0 - width1) <= 4 && Math.abs(width1 - width2) <= 4,
                    "the three columns stay evenly sized: " + width0 + "/" + width1 + "/" + width2);

            java.awt.Rectangle profileStrip = table.getCellRect(0, 0, false);
            profileStrip.height = table.getRowHeight(0) * table.getRowCount();
            assertTrue(hasNonBackgroundPixels(rendered[0], profileStrip, table.getBackground()),
                    "the profile cell shows the (top-aligned) profile name");

            // Selection is PER-ROW: selecting the inner row highlights ONLY
            // that row's band - the span's other rows stay on the table
            // background - and the profile name in the span's first row
            // stays visible.
            final Color selectionBackground = table.getSelectionBackground();
            SwingUtilities.invokeAndWait(() -> {
                table.getSelectionModel().setSelectionInterval(1, 1);
                BufferedImage img = new BufferedImage(table.getWidth(), table.getHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                table.paint(g);
                g.dispose();
                rendered[0] = img;
            });
            java.awt.Rectangle selectedBand = table.getCellRect(1, 0, false);
            assertTrue(hasNearColorPixels(rendered[0], selectedBand, selectionBackground),
                    "the selected row's band is painted with the selection background");
            java.awt.Rectangle topBand = table.getCellRect(0, 0, false);
            assertTrue(hasNearColorPixels(rendered[0], topBand, table.getBackground()),
                    "the span's first row stays on the table background (only the selected row is highlighted)");
            assertTrue(hasNonBackgroundPixels(rendered[0], topBand, table.getBackground()),
                    "the top-aligned profile name stays visible");
        } finally {
            if (createdDefaultProfile) {
                Files.deleteIfExists(sqliteProfileDir);
            }
        }
    }

    @Test
    @DisplayName("every assigned-module row carries the question-mark details icon")
    void everyModuleRowCarriesTheDetailsIcon() throws Exception {
        // The built-in default profile (./database/SQLite/sqlite) drives the
        // rows - make it discoverable when the local workspace has no such
        // folder yet (and clean up only what this test created).
        Path sqliteProfileDir = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", "sqlite");
        boolean createdDefaultProfile = !Files.isDirectory(sqliteProfileDir);
        if (createdDefaultProfile) {
            Files.createDirectories(sqliteProfileDir);
        }
        try {
            temporaryConfRoot = Files.createTempDirectory("sqlite-details-icon-conf");
            for (String name : List.of("node", "node_01", "node_02")) {
                createAssignmentNodeProfile(name, null);
            }

            AtomicReference<SQLiteDatabaseAssignmentPanel> assignments = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() ->
                    assignments.set(new SQLiteDatabaseAssignmentPanel(temporaryConfRoot.toString())));

            Field tableField = SQLiteDatabaseAssignmentPanel.class.getDeclaredField("table");
            tableField.setAccessible(true);
            JTable table = (JTable) tableField.get(assignments.get());
            int rowCount = table.getRowCount();
            assertTrue(rowCount >= 3, "the three nodes are listed, one row each");

            final BufferedImage[] rendered = new BufferedImage[1];
            SwingUtilities.invokeAndWait(() -> {
                table.setSize(1200, 600);
                table.doLayout();
                BufferedImage img = new BufferedImage(1200, 600, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                table.paint(g);
                g.dispose();
                rendered[0] = img;
            });

            // The details icon lives in the trailing strip of the module
            // cell on EVERY row (not just the profile group's first one).
            for (int row = 0; row < rowCount; row++) {
                java.awt.Rectangle cell = table.getCellRect(row, 2, false);
                java.awt.Rectangle strip =
                        new java.awt.Rectangle(cell.x + cell.width - 40, cell.y, 37, cell.height);
                assertTrue(hasNonBackgroundPixels(rendered[0], strip, table.getBackground()),
                        "row " + row + " carries the details icon");
            }
        } finally {
            if (createdDefaultProfile) {
                Files.deleteIfExists(sqliteProfileDir);
            }
        }
    }

    /**
     * @return true when the strip (inset 3px to skip grid lines) contains a
     *         noticeable run of pixels that differ from the given color -
     *         text or a differently filled cell
     */
    private static boolean hasNonBackgroundPixels(BufferedImage img, java.awt.Rectangle strip, Color background) {
        int hits = 0;
        for (int y = strip.y + 3; y < strip.y + strip.height - 3; y++) {
            for (int x = strip.x + 3; x < strip.x + strip.width - 3; x++) {
                Color c = new Color(img.getRGB(x, y), true);
                if (Math.abs(c.getRed() - background.getRed()) > 24
                        || Math.abs(c.getGreen() - background.getGreen()) > 24
                        || Math.abs(c.getBlue() - background.getBlue()) > 24) {
                    hits++;
                }
            }
        }
        return hits > 20;
    }

    /**
     * @return true when most of the strip's pixels (inset 3px) carry the
     *         given color - a filled cell
     */
    private static boolean hasNearColorPixels(BufferedImage img, java.awt.Rectangle strip, Color color) {
        int near = 0;
        int total = 0;
        for (int y = strip.y + 3; y < strip.y + strip.height - 3; y++) {
            for (int x = strip.x + 3; x < strip.x + strip.width - 3; x++) {
                total++;
                Color c = new Color(img.getRGB(x, y), true);
                if (Math.abs(c.getRed() - color.getRed()) <= 16
                        && Math.abs(c.getGreen() - color.getGreen()) <= 16
                        && Math.abs(c.getBlue() - color.getBlue()) <= 16) {
                    near++;
                }
            }
        }
        return near > total / 2;
    }

    @Test
    @DisplayName("the assignments search filters the rows and navigates the matches")
    void assignmentsSearchFiltersRowsAndNavigatesMatches() throws Exception {
        String dbProfileName = "test_sqlite_search_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", dbProfileName)
                .toAbsolutePath().normalize();
        new SQLiteProfile(dbProfileName).saveToProfileJson();
        Files.createDirectories(createdProfile);
        Files.createFile(createdProfile.resolve("search.db"));

        temporaryConfRoot = Files.createTempDirectory("sqlite-assignment-search-conf");
        String url = application.module.node.profile.SqliteDatabasePaths.composeUrl(createdProfile, "search.db");
        for (String name : List.of("alpha_one", "beta_two", "gamma_three")) {
            createAssignmentNodeProfile(name, url);
        }

        AtomicReference<SQLiteDatabaseAssignmentPanel> assignments = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                assignments.set(new SQLiteDatabaseAssignmentPanel(temporaryConfRoot.toString())));
        SQLiteDatabaseAssignmentPanel panel = assignments.get();

        Field tableField = SQLiteDatabaseAssignmentPanel.class.getDeclaredField("table");
        tableField.setAccessible(true);
        JTable table = (JTable) tableField.get(panel);
        application.utils.gui.SearchMatchPanel searchPanel =
                (application.utils.gui.SearchMatchPanel) getField(panel, "searchMatchPanel");
        assertEquals(3, table.getRowCount(), "one row per consumer module");
        assertEquals(dbProfileName, table.getValueAt(0, 0), "the profile cell starts the merged group");
        assertEquals("search.db", table.getValueAt(0, 1), "the file cell starts the merged block");
        assertNull(table.getValueAt(1, 0), "the group's continuation rows merge into the profile cell");
        assertNull(table.getValueAt(2, 0), "the group's continuation rows merge into the profile cell");
        assertEquals("Node/alpha_one", table.getValueAt(0, 2));
        assertEquals("Node/beta_two", table.getValueAt(1, 2));
        assertEquals("Node/gamma_three", table.getValueAt(2, 2));
        assertEquals("", searchPanel.getMatchIndicatorText());

        // The text filters the rows and shows the current/total counter.
        SwingUtilities.invokeAndWait(() -> searchPanel.setSearchText("beta"));
        assertEquals(1, table.getRowCount(), "only the matching row stays visible");
        assertEquals(dbProfileName, table.getValueAt(0, 0),
                "the surviving row re-opens its merged cells (a span of one)");
        assertEquals("search.db", table.getValueAt(0, 1));
        assertEquals("Node/beta_two", table.getValueAt(0, 2));
        assertEquals("1/1", searchPanel.getMatchIndicatorText());

        // A broader query lists every match; navigation moves the active one.
        SwingUtilities.invokeAndWait(() -> searchPanel.setSearchText("node"));
        assertEquals(3, table.getRowCount());
        assertEquals("1/3", searchPanel.getMatchIndicatorText());

        navigateAssignments(panel, true);
        assertEquals("2/3", searchPanel.getMatchIndicatorText());
        navigateAssignments(panel, false);
        assertEquals("1/3", searchPanel.getMatchIndicatorText());
        navigateAssignments(panel, false);
        assertEquals("3/3", searchPanel.getMatchIndicatorText(), "previous navigation wraps");

        // Clearing the search restores all rows and hides the counter.
        SwingUtilities.invokeAndWait(() -> searchPanel.setSearchText(""));
        assertEquals(3, table.getRowCount());
        assertEquals("", searchPanel.getMatchIndicatorText());
    }

    @Test
    @DisplayName("the assignments search counts every occurrence as its own match and moves the strong band")
    void assignmentsSearchCountsEveryOccurrenceAndMovesTheBand() throws Exception {
        String dbProfileName = "test_sqlite_occ_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", dbProfileName)
                .toAbsolutePath().normalize();
        new SQLiteProfile(dbProfileName).saveToProfileJson();
        Files.createDirectories(createdProfile);
        Files.createFile(createdProfile.resolve("occ.db"));

        temporaryConfRoot = Files.createTempDirectory("sqlite-assignment-occ-conf");
        String url = application.module.node.profile.SqliteDatabasePaths.composeUrl(createdProfile, "occ.db");
        for (String name : List.of("node_a", "node_b")) {
            createAssignmentNodeProfile(name, url);
        }

        AtomicReference<SQLiteDatabaseAssignmentPanel> assignments = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                assignments.set(new SQLiteDatabaseAssignmentPanel(temporaryConfRoot.toString())));
        SQLiteDatabaseAssignmentPanel panel = assignments.get();

        Field tableField = SQLiteDatabaseAssignmentPanel.class.getDeclaredField("table");
        tableField.setAccessible(true);
        JTable table = (JTable) tableField.get(panel);
        application.utils.gui.SearchMatchPanel searchPanel =
                (application.utils.gui.SearchMatchPanel) getField(panel, "searchMatchPanel");
        @SuppressWarnings("unchecked")
        Map<Integer, List<application.utils.gui.SearchMatchLabel.HighlightBand>> moduleBands =
                (Map<Integer, List<application.utils.gui.SearchMatchLabel.HighlightBand>>)
                        getField(panel, "moduleBands");

        // Each module cell "Node/node_a" / "Node/node_b" holds TWO occurrences
        // of "node" (Node + node_a): two real matches per row, four in total.
        SwingUtilities.invokeAndWait(() -> searchPanel.setSearchText("node"));
        assertEquals("1/4", searchPanel.getMatchIndicatorText(),
                "every occurrence of the query is its own match");
        List<application.utils.gui.SearchMatchLabel.HighlightBand> cellBands = moduleBands.get(0);
        assertEquals(2, cellBands.size(), "every occurrence of the cell carries a band");
        assertEquals(2, moduleBands.get(1).size(), "the second row's cell carries its own bands");
        assertEquals(GuiColors.getSearchActiveMatch(), cellBands.get(0).color(),
                "the navigated occurrence carries the strong band");
        assertEquals(GuiColors.getSearchMatch(), cellBands.get(1).color(),
                "the other occurrences keep the soft (selected) band");

        // The bands must reach the RENDERED cell component — and the renderer
        // stays non-opaque, so the LAF's background fill cannot paint over
        // them and hide the match.
        SwingUtilities.invokeAndWait(() -> {
            java.awt.Component cell = table.getCellRenderer(0, 2)
                    .getTableCellRendererComponent(table, table.getValueAt(0, 2), false, false, 0, 2);
            assertFalse(cell.isOpaque(),
                    "a non-selected cell renderer is non-opaque so the band stays visible");
            List<application.utils.gui.SearchMatchLabel.HighlightBand> rendered =
                    ((application.utils.gui.SearchMatchLabel) cell).getHighlightBands();
            assertEquals(2, rendered.size(), "the rendered cell carries one band per occurrence");
            assertEquals(GuiColors.getSearchActiveMatch(), rendered.get(0).color(),
                    "the rendered band of the navigated occurrence is strong");
            assertEquals(GuiColors.getSearchMatch(), rendered.get(1).color(),
                    "the rendered band of the other occurrence is soft");
        });

        // Navigation moves the strong band to the next occurrence; the
        // previous one keeps its soft band (even inside the same cell).
        SwingUtilities.invokeAndWait(() -> navigateAssignments(panel, true));
        assertEquals("2/4", searchPanel.getMatchIndicatorText());
        assertEquals(GuiColors.getSearchMatch(),
                moduleBands.get(0).get(0).color(), "the previous occurrence keeps the soft band");
        assertEquals(GuiColors.getSearchActiveMatch(),
                moduleBands.get(0).get(1).color(), "the navigated occurrence carries the strong band");

        // The navigation wraps around the whole occurrence list.
        SwingUtilities.invokeAndWait(() -> navigateAssignments(panel, false));
        assertEquals("1/4", searchPanel.getMatchIndicatorText(), "previous navigation wraps");
        assertEquals(GuiColors.getSearchActiveMatch(),
                moduleBands.get(0).get(0).color(), "the wrapped-to occurrence is strong again");

        // Clearing removes every band and the counter.
        SwingUtilities.invokeAndWait(() -> searchPanel.setSearchText(""));
        assertEquals("", searchPanel.getMatchIndicatorText());
        assertEquals(2, table.getRowCount());
        assertTrue(moduleBands.isEmpty(), "the bands are cleared");
    }

    @Test
    @DisplayName("a selection change (band repaint) keeps the top-aligned name and highlights only the row")
    void partialRepaintKeepsMergedCellText() throws Exception {
        // The screenshot scenario: one profile, one file, three modules.
        String dbProfileName = "test_sqlite_partial_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", dbProfileName)
                .toAbsolutePath().normalize();
        new SQLiteProfile(dbProfileName).saveToProfileJson();
        Files.createDirectories(createdProfile);
        Files.createFile(createdProfile.resolve("partial.db"));

        temporaryConfRoot = Files.createTempDirectory("sqlite-assignment-partial-conf");
        String url = application.module.node.profile.SqliteDatabasePaths.composeUrl(createdProfile, "partial.db");
        for (String name : List.of("node", "node_01", "node_02")) {
            createAssignmentNodeProfile(name, url);
        }

        AtomicReference<SQLiteDatabaseAssignmentPanel> assignments = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                assignments.set(new SQLiteDatabaseAssignmentPanel(temporaryConfRoot.toString())));

        Field tableField = SQLiteDatabaseAssignmentPanel.class.getDeclaredField("table");
        tableField.setAccessible(true);
        JTable table = (JTable) tableField.get(assignments.get());
        assertEquals(3, table.getRowCount());
        assertEquals(dbProfileName, table.getValueAt(0, 0), "the profile cell starts the merged group");

        // A FULL paint: the profile name is TOP-aligned in the span's FIRST
        // row; the span's continuation rows carry no value.
        final BufferedImage[] full = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            table.setSize(1200, 300);
            table.doLayout();
            BufferedImage img = new BufferedImage(table.getWidth(), table.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            table.paint(g);
            g.dispose();
            full[0] = img;
        });
        java.awt.Rectangle firstRowCell = table.getCellRect(0, 0, false);
        assertTrue(hasNonBackgroundPixels(full[0], firstRowCell, table.getBackground()),
                "the full paint shows the profile name in the span's first row (top-aligned)");

        Color selectionBackground = table.getSelectionBackground();

        // A selection change repaints ONLY the changed row band. Select the
        // MIDDLE (span continuation) row and simulate its clipped band paint
        // exactly as the viewport would: the band shows the row's OWN
        // selection highlight, never the whole span.
        final BufferedImage[] partial = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            table.getSelectionModel().setSelectionInterval(1, 1);
            java.awt.Rectangle band = table.getCellRect(1, 0, false);
            BufferedImage img = new BufferedImage(table.getWidth(), table.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setClip(0, band.y, table.getWidth(), band.height);
            table.paint(g);
            g.dispose();
            partial[0] = img;
        });
        java.awt.Rectangle bandProfileCell = table.getCellRect(1, 0, false);
        assertTrue(hasNearColorPixels(partial[0], bandProfileCell, selectionBackground),
                "the continuation row's band is painted selected on its own (per-row selection)");

        // Select the FIRST row (the row that carries the name) and repaint
        // only its band: the name must survive the band repaint (the old bug
        // erased the merged cell's text into the background fill).
        SwingUtilities.invokeAndWait(() -> {
            table.getSelectionModel().setSelectionInterval(0, 0);
            java.awt.Rectangle band = table.getCellRect(0, 0, false);
            BufferedImage img = new BufferedImage(table.getWidth(), table.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setClip(0, band.y, table.getWidth(), band.height);
            table.paint(g);
            g.dispose();
            partial[0] = img;
        });
        java.awt.Rectangle topBand = table.getCellRect(0, 0, false);
        assertTrue(hasNearColorPixels(partial[0], topBand, selectionBackground),
                "the first row's band is painted selected");
        assertTrue(hasNonBackgroundPixels(partial[0], topBand, selectionBackground),
                "the profile name survives the band repaint");
    }

    @Test
    @DisplayName("under the app's LAF the search band stays behind the text in the taller table row")
    void assignmentsSearchBandCoversTheTextInFlatLafTable() throws Exception {
        String dbProfileName = "test_sqlite_band_" + System.nanoTime();
        createdProfile = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", dbProfileName)
                .toAbsolutePath().normalize();
        new SQLiteProfile(dbProfileName).saveToProfileJson();
        Files.createDirectories(createdProfile);
        Files.createFile(createdProfile.resolve("band.db"));

        temporaryConfRoot = Files.createTempDirectory("sqlite-assignment-band-conf");
        String url = application.module.node.profile.SqliteDatabasePaths.composeUrl(createdProfile, "band.db");
        for (String name : List.of("node", "node_01", "node_02")) {
            createAssignmentNodeProfile(name, url);
        }

        final javax.swing.LookAndFeel previousLaf = UIManager.getLookAndFeel();
        AtomicReference<SQLiteDatabaseAssignmentPanel> panelRef = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatDarkLaf());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                panelRef.set(new SQLiteDatabaseAssignmentPanel(temporaryConfRoot.toString()));
            });
            Field tableField = SQLiteDatabaseAssignmentPanel.class.getDeclaredField("table");
            tableField.setAccessible(true);
            JTable table = (JTable) tableField.get(panelRef.get());
            application.utils.gui.SearchMatchPanel searchPanel =
                    (application.utils.gui.SearchMatchPanel) getField(panelRef.get(), "searchMatchPanel");

            // Render the whole table once, then measure - in every matching
            // row the band rows must CONTAIN the glyph rows: a band stuck to
            // the top of the taller row (the SwingConstants-reenumbering
            // misalignment) leaves the glyph tops uncovered and fails here.
            final BufferedImage[] rendered = new BufferedImage[1];
            SwingUtilities.invokeAndWait(() -> {
                searchPanel.setSearchText("node");
                assertEquals(3, table.getRowCount(), "one row per consumer module");
                assertEquals("1/6", searchPanel.getMatchIndicatorText());
                table.setSize(900, 300);
                table.validate();
                BufferedImage img = new BufferedImage(table.getWidth(), table.getHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                table.paint(g);
                g.dispose();
                rendered[0] = img;
            });

            for (int row = 0; row < table.getRowCount(); row++) {
                java.awt.Rectangle cell = table.getCellRect(row, 2, true);
                int[] glyph = scanCellRows(rendered[0], cell, true);
                int[] band = scanCellRows(rendered[0], cell, false);
                assertTrue(band[1] >= 0, "row " + row + " carries a search band");
                assertTrue(glyph[1] >= 0, "row " + row + " carries the profile text");
                assertTrue(band[0] <= glyph[0],
                        "row " + row + ": the band top (" + band[0] + ") must cover the glyph top ("
                                + glyph[0] + ")");
                assertTrue(band[1] >= glyph[1],
                        "row " + row + ": the band bottom (" + band[1] + ") must cover the glyph bottom ("
                                + glyph[1] + ")");
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    UIManager.setLookAndFeel(previousLaf);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    /**
     * Scans the column-0 cell strip of a rendered table image.
     *
     * @param glyph true = rows containing the (white) profile text glyphs;
     *              false = rows containing the bluish search-match band
     */
    private static int[] scanCellRows(BufferedImage img, java.awt.Rectangle cell, boolean glyph) {
        int[] rows = new int[] {999, -1};
        for (int y = cell.y; y < cell.y + cell.height; y++) {
            for (int x = cell.x; x < cell.x + cell.width; x++) {
                Color c = new Color(img.getRGB(x, y), true);
                boolean hit = glyph
                        ? (c.getRed() > 150 && c.getGreen() > 150 && c.getBlue() > 150)
                        : (c.getBlue() - c.getRed() > 25 && c.getAlpha() > 0);
                if (hit) {
                    rows[0] = Math.min(rows[0], y - cell.y);
                    rows[1] = Math.max(rows[1], y - cell.y);
                }
            }
        }
        return rows;
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void navigate(Object panel, boolean next) {
        try {
            java.lang.reflect.Method method = SQLiteConfigurationPanel.class
                    .getDeclaredMethod("navigateSearch", boolean.class);
            method.setAccessible(true);
            method.invoke(panel, next);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not invoke navigateSearch", e);
        }
    }

    private static void navigateAssignments(Object panel, boolean next) {
        try {
            java.lang.reflect.Method method = SQLiteDatabaseAssignmentPanel.class
                    .getDeclaredMethod("navigateSearch", boolean.class);
            method.setAccessible(true);
            method.invoke(panel, next);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not invoke navigateSearch", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, javax.swing.JLabel> rowLabelComponents(Object panel) {
        List<DatabaseConfigurationPanel.PropertyRow> rows = (List<DatabaseConfigurationPanel.PropertyRow>)
                getField(panel, "allPropertyRows");
        Map<String, javax.swing.JLabel> labels = new java.util.LinkedHashMap<>();
        for (DatabaseConfigurationPanel.PropertyRow row : rows) {
            labels.put(row.labelText, row.label);
        }
        return labels;
    }

    private static Color bandColor(javax.swing.JLabel label) {
        try {
            Field field = application.utils.gui.SearchMatchLabel.class.getDeclaredField("highlightColor");
            field.setAccessible(true);
            return (Color) field.get(label);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not read the label band color", e);
        }
    }

    private static int highlightStart(javax.swing.JLabel label) {
        try {
            Field field = application.utils.gui.SearchMatchLabel.class.getDeclaredField("highlightStart");
            field.setAccessible(true);
            return (int) field.get(label);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not read the label highlight start", e);
        }
    }

    private static Object getField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not read " + name, e);
        }
    }
}
