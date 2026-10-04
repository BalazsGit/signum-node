package application.module.node.gui.configuration;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link JdbcManualConfigurationPanel} — the engine-aware manual
 * JDBC editor of the node's DB.Url: only the fields relevant for the selected
 * engine are visible, the SQLite connection is composed from the pre-filled
 * Profile/Path/DB-file trio, and the untouched trio preserves the original
 * URL string (no spurious unsaved change after a load).
 */
@DisplayName("JdbcManualConfigurationPanel (engine-aware fields + SQLite trio)")
class JdbcManualConfigurationPanelTest {

    private static JdbcManualConfigurationPanel buildOnEdt(String defaultSqliteProfile) throws Exception {
        final AtomicReference<JdbcManualConfigurationPanel> ref = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                ref.set(new JdbcManualConfigurationPanel(() -> { }, defaultSqliteProfile));
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() != null) {
            throw new AssertionError("panel construction failed", error.get());
        }
        return ref.get();
    }

    private static void onEdt(Runnable action) throws Exception {
        final AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @Test
    @DisplayName("the SQLite trio is pre-filled from the node profile name and composes the per-profile URL")
    void sqliteTrio_defaultsAndComposition() throws Exception {
        JdbcManualConfigurationPanel panel = buildOnEdt("my-node");
        assertEquals("my-node", panel.getSqliteProfile());
        // the Path field holds the base directory only — the profile name is
        // the last folder of the COMPOSED path (the preview concatenates it)
        assertEquals("./database/SQLite", panel.getSqlitePath());
        assertEquals("signum.sqlite.db", panel.getSqliteFile());
        assertEquals("jdbc:sqlite:file:./database/SQLite/my-node/signum.sqlite.db",
                panel.getJdbcUrl());
    }

    @Test
    @DisplayName("only the fields relevant for the selected engine are visible")
    void engineAwareVisibility() throws Exception {
        JdbcManualConfigurationPanel panel = buildOnEdt("my-node");
        // the default engine is SQLITE — the trio is visible, the server
        // fields are hidden
        assertTrue(panel.getSqlProfileField().isVisible(), "the profile field must be visible for SQLite");
        assertTrue(panel.getSqlPathField().isVisible(), "the path field must be visible for SQLite");
        assertTrue(panel.getSqlFileField().isVisible(), "the db file field must be visible for SQLite");
        assertFalse(panel.getHostField().isVisible(), "the host field must be hidden for SQLite");
        assertFalse(panel.getPortField().isVisible(), "the port field must be hidden for SQLite");
        assertFalse(panel.getDbNameField().isVisible(), "the database field must be hidden for SQLite");
        assertFalse(panel.getSuffixField().isVisible(), "the suffix field must be hidden for SQLite");
        assertFalse(panel.getUserField().isVisible(), "the username field must be hidden for SQLite");
        assertFalse(panel.getPassField().isVisible(), "the password field must be hidden for SQLite");

        onEdt(() -> ((JComboBox<?>) panel.getEngineCombo())
                .setSelectedItem(DatabaseEngine.MARIADB));
        assertFalse(panel.getSqlProfileField().isVisible(), "the profile field must be hidden for a server engine");
        assertFalse(panel.getSqlPathField().isVisible(), "the path field must be hidden for a server engine");
        assertFalse(panel.getSqlFileField().isVisible(), "the db file field must be hidden for a server engine");
        assertTrue(panel.getHostField().isVisible(), "the host field must be visible for a server engine");
        assertTrue(panel.getPortField().isVisible(), "the port field must be visible for a server engine");
        assertTrue(panel.getDbNameField().isVisible(), "the database field must be visible for a server engine");
        assertTrue(panel.getSuffixField().isVisible(), "the suffix field must be visible for a server engine");
        assertTrue(panel.getUserField().isVisible(), "the username field must be visible for a server engine");
        assertTrue(panel.getPassField().isVisible(), "the password field must be visible for a server engine");
    }
    @Test
    @DisplayName("loading a SQLite URL splits path and file name and preserves the original URL")
    void updateFromUrl_sqlite_preservesOriginal() throws Exception {
        JdbcManualConfigurationPanel panel = buildOnEdt("my-node");
        onEdt(() -> panel.updateFromUrl("jdbc:sqlite:./data/my.db"));
        assertEquals("./data", panel.getSqlitePath());
        assertEquals("my.db", panel.getSqliteFile());
        // untouched trio → the original URL string is reported (no spurious
        // unsaved change)
        assertEquals("jdbc:sqlite:./data/my.db", panel.getJdbcUrl());
    }

    @Test
    @DisplayName("a per-profile SQLite URL derives the database profile name from its path")
    void updateFromUrl_sqlite_derivesProfile() throws Exception {
        JdbcManualConfigurationPanel panel = buildOnEdt("my-node");
        onEdt(() -> panel.updateFromUrl("jdbc:sqlite:file:./database/SQLite/other-node/signum.sqlite.db"));
        assertEquals("other-node", panel.getSqliteProfile());
        // the Path field is reduced to the convention base — the profile
        // name (the last folder) is re-added by the composition
        assertEquals("./database/SQLite", panel.getSqlitePath());
        assertEquals("signum.sqlite.db", panel.getSqliteFile());
        assertEquals("jdbc:sqlite:file:./database/SQLite/other-node/signum.sqlite.db",
                panel.getJdbcUrl());
    }

    @Test
    @DisplayName("editing the trio composes the URL from the fields (the original is no longer reported)")
    void editedTrio_composesUrl() throws Exception {
        JdbcManualConfigurationPanel panel = buildOnEdt("my-node");
        onEdt(() -> {
            panel.updateFromUrl("jdbc:sqlite:file:./database/SQLite/my-node/signum.sqlite.db");
            ((javax.swing.JTextField) panel.getSqlFileField()).setText("custom.db");
        });
        assertEquals("custom.db", panel.getSqliteFile());
        assertEquals("jdbc:sqlite:file:./database/SQLite/my-node/custom.db", panel.getJdbcUrl());
    }

    @Test
    @DisplayName("typing a new profile name updates the composed path (the Path field stays at the base)")
    void profileTyped_composedPathFollowsProfile() throws Exception {
        JdbcManualConfigurationPanel panel = buildOnEdt("my-node");
        onEdt(() -> ((javax.swing.JTextField) panel.getSqlProfileField()).setText("renamed"));
        assertEquals("renamed", panel.getSqliteProfile());
        // the profile name is the last folder of the path — the Path field
        // itself is not rewritten, the composition (preview/URL) carries it
        assertEquals("./database/SQLite", panel.getSqlitePath());
        assertEquals("jdbc:sqlite:file:./database/SQLite/renamed/signum.sqlite.db", panel.getJdbcUrl());
    }

    @Test
    @DisplayName("the discovery callback fires when the trio follows the convention and the profile exists")
    void discovery_callbackFiresForConventionProfile() throws Exception {
        Path dir = Path.of("database", "SQLite", "disc-prof");
        Path json = dir.resolve("profile.json");
        boolean jsonExisted = Files.exists(json);
        byte[] jsonBackup = jsonExisted ? Files.readAllBytes(json) : null;
        Files.createDirectories(dir);
        Files.writeString(json,
                "{\"DB.Url\": \"jdbc:sqlite:file:./database/SQLite/disc-prof/signum.sqlite.db\"}");
        try {
            JdbcManualConfigurationPanel panel = buildOnEdt("disc-prof");
            final AtomicReference<String> discovered = new AtomicReference<>();
            onEdt(() -> panel.setOnProfileDiscovered(discovered::set));
            // touching the trio while it sits on the convention base fires
            // the discovery (the profile's profile.json exists)
            onEdt(() -> ((javax.swing.JTextField) panel.getSqlFileField()).setText("custom.db"));
            assertEquals("disc-prof", discovered.get(),
                    "the discoverable convention profile must be reported");

            // a custom path is NOT a discovery
            discovered.set(null);
            onEdt(() -> ((javax.swing.JTextField) panel.getSqlPathField()).setText("./data"));
            assertNull(discovered.get(), "a custom path must not trigger the discovery");
        } finally {
            if (jsonExisted) {
                Files.write(json, jsonBackup);
            } else {
                Files.deleteIfExists(json);
            }
            Files.deleteIfExists(dir);
        }
    }

    @Test
    @DisplayName("the discovery callback stays silent when the profile is not discoverable")
    void discovery_silentWithoutProfile() throws Exception {
        JdbcManualConfigurationPanel panel = buildOnEdt("no-such-profile");
        final AtomicReference<String> discovered = new AtomicReference<>();
        onEdt(() -> panel.setOnProfileDiscovered(discovered::set));
        onEdt(() -> ((javax.swing.JTextField) panel.getSqlFileField()).setText("custom.db"));
        assertNull(discovered.get(),
                "a profile without profile.json must not be reported as discovered");
    }

    @Test
    @DisplayName("SQLite carries no credentials; the server engines keep them")
    void credentials_sqliteEmpty() throws Exception {
        JdbcManualConfigurationPanel panel = buildOnEdt("my-node");
        onEdt(() -> {
            panel.updateFromUrl("jdbc:sqlite:file:./database/SQLite/my-node/signum.sqlite.db");
            panel.setCredentials("user", "secret");
        });
        assertEquals("", panel.getUsername(), "SQLite must not carry a username");
        assertEquals("", panel.getPassword(), "SQLite must not carry a password");

        onEdt(() -> {
            ((JComboBox<?>) panel.getEngineCombo())
                    .setSelectedItem(DatabaseEngine.POSTGRESQL);
            panel.setCredentials("user", "secret");
        });
        assertEquals("user", panel.getUsername());
        assertEquals("secret", panel.getPassword());
    }

    @Test
    @DisplayName("a server URL round-trips through the fields unchanged")
    void updateFromUrl_server_roundTrip() throws Exception {
        JdbcManualConfigurationPanel panel = buildOnEdt("my-node");
        onEdt(() -> panel.updateFromUrl("jdbc:postgresql://dbhost:5432/signum?reconnect=true"));
        assertEquals(DatabaseEngine.POSTGRESQL, ((JComboBox<?>) panel.getEngineCombo()).getSelectedItem());
        assertEquals("jdbc:postgresql://dbhost:5432/signum?reconnect=true", panel.getJdbcUrl());
    }
}

