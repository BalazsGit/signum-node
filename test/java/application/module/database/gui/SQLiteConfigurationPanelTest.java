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
        assertEquals(dbProfileName, SQLiteDatabaseAssignmentPanel.assignedProfile(
                stored.getProperty(Props.DB_URL.getName())));
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
    @DisplayName("custom SQLite assignments show the resolved database file path")
    void customAssignmentDisplaysDatabaseFilePath() throws Exception {
        temporaryConfRoot = Files.createTempDirectory("sqlite-custom-assignment-conf");
        String nodeProfileName = "node_custom";
        Path nodeProfileFile = PropertiesProfileLoader.resolveProfileFile(
                temporaryConfRoot.toString(), ModuleIds.NODE, ModuleIds.CATEGORY_PROFILES, nodeProfileName);
        Files.createDirectories(nodeProfileFile.getParent());

        Path databaseFile = temporaryConfRoot.resolve("external").resolve("custom.sqlite.db");
        String url = application.module.node.profile.SqliteDatabasePaths.composeUrl(
                databaseFile.getParent(), databaseFile.getFileName().toString());
        Properties properties = new Properties();
        properties.setProperty(Props.DB_URL.getName(), url);
        try (var writer = Files.newBufferedWriter(nodeProfileFile)) {
            properties.store(writer, null);
        }

        AtomicReference<SQLiteDatabaseAssignmentPanel> assignments = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                assignments.set(new SQLiteDatabaseAssignmentPanel(temporaryConfRoot.toString())));

        Field tableField = SQLiteDatabaseAssignmentPanel.class.getDeclaredField("table");
        tableField.setAccessible(true);
        JTable table = (JTable) tableField.get(assignments.get());
        assertEquals(2, table.getColumnCount());
        assertEquals("Profile", table.getColumnModel().getColumn(0).getHeaderValue());
        assertEquals("Node/node_custom", table.getValueAt(0, 0));
        assertEquals("(custom URL)", table.getValueAt(0, 1));
        assertFalse(table.isCellEditable(0, 0), "the tab is read-only");
        assertFalse(table.isCellEditable(0, 1), "the tab is read-only");
    }

    @Test
    @DisplayName("the assignments search filters the rows and navigates the matches")
    void assignmentsSearchFiltersRowsAndNavigatesMatches() throws Exception {
        temporaryConfRoot = Files.createTempDirectory("sqlite-assignment-search-conf");
        for (String name : List.of("alpha_one", "beta_two", "gamma_three")) {
            Path nodeProfileFile = PropertiesProfileLoader.resolveProfileFile(
                    temporaryConfRoot.toString(), ModuleIds.NODE, ModuleIds.CATEGORY_PROFILES, name);
            Files.createDirectories(nodeProfileFile.getParent());
            Properties properties = new Properties();
            properties.setProperty("node.description", name);
            try (var writer = Files.newBufferedWriter(nodeProfileFile)) {
                properties.store(writer, null);
            }
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
        assertEquals(3, table.getRowCount());
        assertEquals("", searchPanel.getMatchIndicatorText());

        // The text filters the rows and shows the current/total counter.
        SwingUtilities.invokeAndWait(() -> searchPanel.setSearchText("beta"));
        assertEquals(1, table.getRowCount(), "only the matching row stays visible");
        assertEquals("Node/beta_two", table.getValueAt(0, 0));
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
        temporaryConfRoot = Files.createTempDirectory("sqlite-assignment-occ-conf");
        for (String name : List.of("node_a", "node_b")) {
            Path nodeProfileFile = PropertiesProfileLoader.resolveProfileFile(
                    temporaryConfRoot.toString(), ModuleIds.NODE, ModuleIds.CATEGORY_PROFILES, name);
            Files.createDirectories(nodeProfileFile.getParent());
            Properties properties = new Properties();
            properties.setProperty("node.description", name);
            try (var writer = Files.newBufferedWriter(nodeProfileFile)) {
                properties.store(writer, null);
            }
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
        Map<Integer, List<application.utils.gui.SearchMatchLabel.HighlightBand>> profileBands =
                (Map<Integer, List<application.utils.gui.SearchMatchLabel.HighlightBand>>)
                        getField(panel, "profileBands");

        // "Node/node_a" holds TWO occurrences of "node" (Node, node_a) and so
        // does "Node/node_b": four real matches, not two rows.
        SwingUtilities.invokeAndWait(() -> searchPanel.setSearchText("node"));
        assertEquals("1/4", searchPanel.getMatchIndicatorText(),
                "every occurrence of the query is its own match");
        List<application.utils.gui.SearchMatchLabel.HighlightBand> firstRowBands =
                profileBands.get(0);
        assertEquals(2, firstRowBands.size(), "both occurrences of the cell carry a band");
        assertEquals(GuiColors.getSearchActiveMatch(), firstRowBands.get(0).color(),
                "the navigated occurrence carries the strong band");
        assertEquals(GuiColors.getSearchMatch(), firstRowBands.get(1).color(),
                "the other occurrence keeps the soft (selected) band");

        // The bands must reach the RENDERED cell component — and the renderer
        // stays non-opaque, so the LAF's background fill cannot paint over
        // them and hide the match.
        SwingUtilities.invokeAndWait(() -> {
            java.awt.Component cell = table.getCellRenderer(0, 0)
                    .getTableCellRendererComponent(table, table.getValueAt(0, 0), false, false, 0, 0);
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
                profileBands.get(0).get(0).color(), "the previous occurrence keeps the soft band");
        assertEquals(GuiColors.getSearchActiveMatch(),
                profileBands.get(0).get(1).color(), "the navigated occurrence carries the strong band");
        assertEquals(GuiColors.getSearchMatch(),
                profileBands.get(1).get(0).color(), "the next row's occurrence stays soft");

        // The navigation wraps around the whole occurrence list.
        SwingUtilities.invokeAndWait(() -> navigateAssignments(panel, false));
        assertEquals("1/4", searchPanel.getMatchIndicatorText(), "previous navigation wraps");
        assertEquals(GuiColors.getSearchActiveMatch(),
                profileBands.get(0).get(0).color(), "the wrapped-to occurrence is strong again");

        // Clearing removes every band and the counter.
        SwingUtilities.invokeAndWait(() -> searchPanel.setSearchText(""));
        assertEquals("", searchPanel.getMatchIndicatorText());
        assertEquals(2, table.getRowCount());
        assertTrue(profileBands.isEmpty(), "the bands are cleared");
    }

    @Test
    @DisplayName("under the app's LAF the search band stays behind the text in the taller table row")
    void assignmentsSearchBandCoversTheTextInFlatLafTable() throws Exception {
        temporaryConfRoot = Files.createTempDirectory("sqlite-assignment-band-conf");
        for (String name : List.of("node", "node_01", "node_02")) {
            Path nodeProfileFile = PropertiesProfileLoader.resolveProfileFile(
                    temporaryConfRoot.toString(), ModuleIds.NODE, ModuleIds.CATEGORY_PROFILES, name);
            Files.createDirectories(nodeProfileFile.getParent());
            Properties properties = new Properties();
            properties.setProperty("node.description", name);
            try (var writer = Files.newBufferedWriter(nodeProfileFile)) {
                properties.store(writer, null);
            }
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
                assertEquals(3, table.getRowCount());
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
                java.awt.Rectangle cell = table.getCellRect(row, 0, true);
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
