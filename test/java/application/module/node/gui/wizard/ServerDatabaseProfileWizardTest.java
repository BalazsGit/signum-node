package application.module.node.gui.wizard;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.utils.io.PathUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ServerDatabaseProfileWizard} — the headless creation core
 * (validation, engine/database extraction from the URL, existence check, single
 * file-write entry point that produces a linkable {@code databases} profile) plus a
 * lightweight GUI-construction check of the dialog's defaults.
 */
@DisplayName("ServerDatabaseProfileWizard (server database profile wizard)")
class ServerDatabaseProfileWizardTest {

    private static final String MARIADB_URL = "jdbc:mariadb://db.example:3306/appdb";
    private static final String POSTGRES_URL = "jdbc:postgresql://db.example:5432/appdb";

    @Test
    @DisplayName("validateInput rejects blank / invalid name, blank / non-server URL and blank user")
    void validateInputRejectsInvalid() {
        assertNotNull(ServerDatabaseProfileWizard.validateInput("", MARIADB_URL, "user"), "blank name");
        assertNotNull(ServerDatabaseProfileWizard.validateInput(null, MARIADB_URL, "user"), "null name");
        assertNotNull(ServerDatabaseProfileWizard.validateInput("1abc", MARIADB_URL, "user"), "digit start");
        assertNotNull(ServerDatabaseProfileWizard.validateInput("a b", MARIADB_URL, "user"), "space");
        assertNotNull(ServerDatabaseProfileWizard.validateInput("a/b", MARIADB_URL, "user"), "slash");
        assertNotNull(ServerDatabaseProfileWizard.validateInput("alpha", "", "user"), "blank url");
        assertNotNull(ServerDatabaseProfileWizard.validateInput("alpha", "not-a-url", "user"), "garbage url");
        assertNotNull(ServerDatabaseProfileWizard.validateInput("alpha", "jdbc:sqlite:file:./x.db", "user"),
                "sqlite url is not a server url");
        assertNotNull(ServerDatabaseProfileWizard.validateInput("alpha", MARIADB_URL, ""), "blank user");
        assertNotNull(ServerDatabaseProfileWizard.validateInput("alpha", MARIADB_URL, null), "null user");
    }

    @Test
    @DisplayName("validateInput accepts a valid (name, server URL, user) pair")
    void validateInputAcceptsValid() {
        assertNull(ServerDatabaseProfileWizard.validateInput("alpha", MARIADB_URL, "user"));
        assertNull(ServerDatabaseProfileWizard.validateInput("alpha", POSTGRES_URL, "user"));
        assertNull(ServerDatabaseProfileWizard.validateInput("  alpha  ", "  " + MARIADB_URL + "  ", "  user  "));
    }

    @Test
    @DisplayName("engineForUrl maps the JDBC protocol to the engine profile directory name")
    void engineForUrlMapsProtocol() {
        assertEquals("MariaDB", ServerDatabaseProfileWizard.engineForUrl(MARIADB_URL));
        assertEquals("PostgreSQL", ServerDatabaseProfileWizard.engineForUrl(POSTGRES_URL));
        assertNull(ServerDatabaseProfileWizard.engineForUrl("jdbc:sqlite:file:./x.db"));
        assertNull(ServerDatabaseProfileWizard.engineForUrl(""));
    }

    @Test
    @DisplayName("databaseForUrl extracts the database (last path segment) of a JDBC URL")
    void databaseForUrlExtractsName() {
        assertEquals("appdb", ServerDatabaseProfileWizard.databaseForUrl(MARIADB_URL));
        assertEquals("appdb", ServerDatabaseProfileWizard.databaseForUrl(POSTGRES_URL));
        assertEquals("", ServerDatabaseProfileWizard.databaseForUrl("not-a-url"));
    }

    @Test
    @DisplayName("createProfile writes a linkable ./database/MariaDB/<name>/profile.json (generic databases format)")
    void createProfileCreatesMariaDbProfileJson() throws IOException {
        String name = "wiz_md_" + System.nanoTime();
        Path dir = serverProfileDir("MariaDB", name);
        try {
            assertEquals(name, ServerDatabaseProfileWizard.createProfile(name, MARIADB_URL, "appuser", "apppass"));

            assertTrue(Files.isDirectory(dir), "the profile directory must exist");
            Path profileJson = dir.resolve("profile.json");
            assertTrue(Files.isRegularFile(profileJson), "the profile.json must exist");
            JsonObject json = JsonParser.parseString(Files.readString(profileJson)).getAsJsonObject();
            JsonObject instance = json.getAsJsonArray("databases").get(0).getAsJsonObject();
            assertEquals(MARIADB_URL, instance.get("url").getAsString());
            assertEquals("appdb", instance.get("name").getAsString());
            assertEquals("appuser", instance.getAsJsonArray("users").get(0).getAsJsonObject()
                    .get("username").getAsString());

            // Linkable through the discovery/link SSOT (loadProfile):
            DatabaseConfigurationUtils.DbProfile loaded = DatabaseConfigurationUtils.loadProfile(null, "MariaDB", name);
            assertNotNull(loaded, "loadProfile must resolve the new profile");
            assertEquals(MARIADB_URL, loaded.databases.get(0).url);
            assertEquals("appuser", loaded.databases.get(0).users.get(0).username);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("createProfile writes a linkable ./database/PostgreSQL/<name>/profile.json")
    void createProfileCreatesPostgresProfileJson() throws IOException {
        String name = "wiz_pg_" + System.nanoTime();
        Path dir = serverProfileDir("PostgreSQL", name);
        try {
            assertEquals(name, ServerDatabaseProfileWizard.createProfile(name, POSTGRES_URL, "pguser", ""));
            DatabaseConfigurationUtils.DbProfile loaded = DatabaseConfigurationUtils.loadProfile(null, "PostgreSQL",
                    name);
            assertNotNull(loaded, "loadProfile must resolve the new profile");
            assertEquals(POSTGRES_URL, loaded.databases.get(0).url);
            assertEquals("pguser", loaded.databases.get(0).users.get(0).username);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("createProfile rejects invalid input and creates nothing")
    void createProfileRejectsInvalidInput() throws IOException {
        String name = "wiz_invalid_" + System.nanoTime();
        Path dir = serverProfileDir("MariaDB", name);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> ServerDatabaseProfileWizard.createProfile("", MARIADB_URL, "user", ""));
            assertThrows(IllegalArgumentException.class,
                    () -> ServerDatabaseProfileWizard.createProfile("a/b", MARIADB_URL, "user", ""));
            assertThrows(IllegalArgumentException.class,
                    () -> ServerDatabaseProfileWizard.createProfile(name, "not-a-url", "user", ""));
            assertThrows(IllegalArgumentException.class,
                    () -> ServerDatabaseProfileWizard.createProfile(name, MARIADB_URL, "", ""));
            assertFalse(Files.exists(dir), "no profile may be created for invalid input");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("profileExists is false before and true after createProfile (discovery SSOT)")
    void profileExistsReflectsCreation() throws IOException {
        String name = "wiz_exists_" + System.nanoTime();
        Path dir = serverProfileDir("MariaDB", name);
        try {
            assertFalse(ServerDatabaseProfileWizard.profileExists("MariaDB", name), "must not exist yet");
            ServerDatabaseProfileWizard.createProfile(name, MARIADB_URL, "appuser", "");
            assertTrue(ServerDatabaseProfileWizard.profileExists("MariaDB", name),
                    "must be discoverable after creation");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("the dialog constructs with the engine title, an empty name and the manual-mode default URL (on the EDT)")
    void dialogConstructsWithDefaults() throws Exception {
        final ServerDatabaseProfileWizard[] holder = new ServerDatabaseProfileWizard[1];
        final Object[] out = new Object[4];
        SwingUtilities.invokeAndWait(() -> {
            ServerDatabaseProfileWizard wizard = new ServerDatabaseProfileWizard(null, DatabaseEngine.MARIADB);
            holder[0] = wizard;
            out[0] = wizard.getTitle();
            out[1] = wizard.getNameField().getText();
            out[2] = wizard.getJdbcPanel().getJdbcUrl();
            out[3] = wizard.getCreatedProfileName();
        });
        try {
            assertEquals(ServerDatabaseProfileWizard.titleFor(DatabaseEngine.MARIADB), out[0]);
            assertEquals("", out[1]);
            assertEquals("jdbc:mariadb://localhost:3306/signum", out[2]);
            assertNull(out[3], "no profile is created until Create is pressed");
        } finally {
            SwingUtilities.invokeAndWait(holder[0]::dispose);
        }
    }

    /** The profile directory of the given (engine, name) — the same base loadProfile resolves. */
    private static Path serverProfileDir(String engineName, String name) {
        return PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                .resolve(engineName)
                .resolve(name);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // best-effort cleanup: the unique name keeps the tree clean
                }
            });
        }
    }
}