package application.module.database.profile;

import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.ProfileCreateDefaults;
import application.module.node.profile.SqliteDatabasePaths;
import application.module.node.props.Props;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SQLiteProfile}: the settings-only profile JSON, the derived database
 * URL (file-based by definition), and the live assignment derivation from the
 * node profile (SSOT, no separate store).
 */
@DisplayName("SQLiteProfile (settings-only JSON + derived URL + live assignment)")
class SQLiteProfileTest {

    @Test
    @DisplayName("a new profile's URL is derived — the per-profile convention URL (identical to the SSOT form)")
    void derivedUrlForNewProfile() {
        String name = "derived_probe_" + System.nanoTime();
        SQLiteProfile profile = new SQLiteProfile(name);
        assertFalse(profile.getConfiguration().containsKey(SQLiteProfile.CFG_URL),
                "a new profile must not carry a stored URL");
        assertEquals(ProfileCreateDefaults.sqliteDbUrl(name), profile.getDbUrl(),
                "the derived URL of a file-less profile is the per-profile default URL");
        assertEquals(SqliteDatabasePaths.DEFAULT_DATABASE_FILE_NAME, profile.activeDatabaseFile(),
                "a file-less profile's active file is the conventional default");
    }

    @Test
    @DisplayName("a stored DB.Url in the profile JSON is ignored — the URL is always derived")
    void storedUrlIsIgnored() {
        String name = "legacy_probe_" + System.nanoTime();
        JsonObject json = new JsonObject();
        json.addProperty(SQLiteProfile.CFG_URL, "jdbc:sqlite:file:./database/SQLite/" + name + "/legacy.db");
        SQLiteProfile profile = new SQLiteProfile(name, json);
        assertFalse(profile.getConfiguration().containsKey(SQLiteProfile.CFG_URL),
                "the stored URL is not loaded into the configuration");
        assertEquals(profile.derivedDbUrl(), profile.getDbUrl(),
                "the URL is derived — a stored one is ignored");
    }

    @Test
    @DisplayName("saving drops a legacy stored DB.Url — the JSON holds engine settings only")
    void saveDropsLegacyUrl() throws IOException {
        String name = "save_probe_" + System.nanoTime();
        Path dir = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", name)
                .toAbsolutePath().normalize();
        try {
            JsonObject json = new JsonObject();
            json.addProperty(SQLiteProfile.CFG_URL, "jdbc:sqlite:file:./database/SQLite/" + name + "/legacy.db");
            json.addProperty(SQLiteProfile.CFG_JOURNAL_MODE, "TRUNCATE");
            SQLiteProfile profile = new SQLiteProfile(name, json);
            profile.saveToProfileJson();

            JsonObject saved = JsonParser.parseString(
                    Files.readString(dir.resolve("profile.json"))).getAsJsonObject();
            assertFalse(saved.has(SQLiteProfile.CFG_URL),
                    "the stored URL must be dropped on save (the URL is derived)");
            assertEquals("TRUNCATE", saved.get(SQLiteProfile.CFG_JOURNAL_MODE).getAsString(),
                    "the engine settings survive");

            SQLiteProfile reloaded = new SQLiteProfile(name);
            reloaded.loadFromDisk();
            assertEquals(profile.derivedDbUrl(), reloaded.getDbUrl(),
                    "the URL is the derived one");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("the active database file is the first discovered *.db (case-insensitive), else the conventional default")
    void activeDatabaseFileFollowsDisk() throws IOException {
        String name = "active_probe_" + System.nanoTime();
        Path dir = Path.of(DatabaseConfigurationUtils.DATABASE_BASE_DIR, "SQLite", name)
                .toAbsolutePath().normalize();
        try {
            SQLiteProfile profile = new SQLiteProfile(name);
            assertEquals(SqliteDatabasePaths.DEFAULT_DATABASE_FILE_NAME, profile.activeDatabaseFile(),
                    "no file on disk yet → the conventional default (created lazily)");

            Files.createDirectories(dir);
            Files.writeString(dir.resolve("zeta.db"), "z");
            Files.writeString(dir.resolve("alpha.db"), "a");
            Files.writeString(dir.resolve("alpha.db-wal"), "wal");
            assertEquals("alpha.db", profile.activeDatabaseFile(),
                    "the first discovered *.db is the active file (companion files excluded)");
            assertEquals("jdbc:sqlite:file:./database/SQLite/" + name + "/alpha.db",
                    profile.derivedDbUrl(),
                    "the derived URL follows the active file in the conventional relative form");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("assignedDatabaseProfile derives the assignment live from the node profile's DB.Url (no separate store)")
    void assignedDatabaseProfileFromNodeProfile() {
        NodeProfile assigned = new NodeProfile("n1");
        assigned.setProperty(Props.DB_URL.getName(),
                "jdbc:sqlite:file:./database/SQLite/mainnet/signum.sqlite.db");
        assertEquals("mainnet", SQLiteProfile.assignedDatabaseProfile(assigned),
                "a convention SQLite URL assigns the database profile of its directory");

        NodeProfile custom = new NodeProfile("n2");
        custom.setProperty(Props.DB_URL.getName(), "jdbc:sqlite:file:./elsewhere/other.db");
        assertNull(SQLiteProfile.assignedDatabaseProfile(custom),
                "a non-convention (custom) URL assigns no database profile");

        NodeProfile server = new NodeProfile("n3");
        server.setProperty(Props.DB_URL.getName(), "jdbc:mariadb://localhost:3306/signum");
        assertNull(SQLiteProfile.assignedDatabaseProfile(server),
                "a server-DB URL assigns no database profile");

        NodeProfile none = new NodeProfile("n4");
        assertNull(SQLiteProfile.assignedDatabaseProfile(none),
                "a node profile without a DB.Url (its default database) assigns no database profile");
        assertNull(SQLiteProfile.assignedDatabaseProfile(null));
    }

    /** Deletes the given root recursively (test cleanup). */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
