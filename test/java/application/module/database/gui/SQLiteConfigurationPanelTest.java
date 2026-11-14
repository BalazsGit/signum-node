package application.module.database.gui;

import application.module.database.profile.SQLiteProfile;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.props.Props;
import application.utils.config.ModuleIds;
import application.utils.config.PropertiesProfileLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTextField;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            journalMode.getEditor().setItem("DELETE");
            cacheSize.getEditor().setItem("8192");
            assertTrue(panel.hasUnsavedChanges());

            JTextField search = (JTextField) getField(panel, "searchField");
            search.setText("DELETE");
            JPanel results = (JPanel) getField(panel, "searchResultsPanel");
            // One matching row (the journal-mode value "DELETE"); each search-result row
            // contributes label + input + help + separator = 4 components.
            assertEquals(4, results.getComponentCount(), "the edited value should match its property row");
            java.awt.Container searchParent = journalMode.getParent();
            journalMode.getEditor().setItem("DELETE WAL");
            assertSame(searchParent, journalMode.getParent(),
                    "editing a matching value must not reparent its focused control");

            javax.swing.JCheckBox showUnsaved = (javax.swing.JCheckBox) getField(panel, "showUnsavedBox");
            showUnsaved.doClick();
            assertEquals(0, results.getComponentCount(), "hiding unsaved values should remove the match");

            com.google.gson.JsonObject current = panel.getCurrentProfileSettings();
            assertTrue(current.get(SQLiteProfile.CFG_URL).getAsString().endsWith("/alternate.db"));
            assertEquals("DELETE WAL", current.get(SQLiteProfile.CFG_JOURNAL_MODE).getAsString());
            assertEquals("8192", current.get(SQLiteProfile.CFG_CACHE_SIZE).getAsString());
        });
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
        assertEquals(3, table.getColumnCount());
        assertEquals("Node/node_custom", table.getValueAt(0, 0));
        assertEquals("(custom URL)", table.getValueAt(0, 1));
        assertEquals("", table.getValueAt(0, 2));
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
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
