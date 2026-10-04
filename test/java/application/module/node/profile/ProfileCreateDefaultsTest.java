package application.module.node.profile;

import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.props.Props;
import application.utils.io.PathUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProfileCreateDefaults} — the SSOT "new profile → Props" mapping
 * shared by the CLI and the GUI setup wizard.
 */
@DisplayName("ProfileCreateDefaults (SSOT new-profile mapping)")
class ProfileCreateDefaultsTest {

    @Test
    @DisplayName("sqliteDbUrl is per-profile")
    void sqliteDbUrlPerProfile() {
        assertEquals(
                "jdbc:sqlite:file:./database/SQLite/my-node/signum.sqlite.db",
                ProfileCreateDefaults.sqliteDbUrl("my-node"));
    }

    @Test
    @DisplayName("applyNetwork: mainnet leaves node.network unset (null default), testnet uses TestnetNetwork class")
    void applyNetwork() {
        Properties main = new Properties();
        ProfileCreateDefaults.applyNetwork(main, false);
        assertFalse(main.containsKey(Props.NETWORK_PARAMETERS.getName()));

        Properties test = new Properties();
        ProfileCreateDefaults.applyNetwork(test, true);
        assertEquals(ProfileCreateDefaults.TESTNET_NETWORK_CLASS,
                test.getProperty(Props.NETWORK_PARAMETERS.getName()));
    }

    @Test
    @DisplayName("applyNetwork: mainnet clears a pre-existing node.network value")
    void applyNetwork_ClearsExistingValue() {
        Properties props = new Properties();
        props.setProperty(Props.NETWORK_PARAMETERS.getName(), "signum.net.TestnetNetwork");
        ProfileCreateDefaults.applyNetwork(props, false);
        assertFalse(props.containsKey(Props.NETWORK_PARAMETERS.getName()));
    }

    @Test
    @DisplayName("applySqliteDatabase sets the per-profile DB.Url")
    void applySqliteDatabase() {
        Properties props = new Properties();
        ProfileCreateDefaults.applySqliteDatabase(props, "alpha");
        assertEquals(ProfileCreateDefaults.sqliteDbUrl("alpha"), props.getProperty(Props.DB_URL.getName()));
    }

    @Test
    @DisplayName("applyServerDatabase sets URL + credentials for both engines")
    void applyServerDatabase() {
        Properties maria = new Properties();
        ProfileCreateDefaults.applyServerDatabase(maria, false, "dbhost", 3307, "root", "pw", "signum");
        assertEquals("jdbc:mariadb://dbhost:3307/signum", maria.getProperty(Props.DB_URL.getName()));
        assertEquals("root", maria.getProperty(Props.DB_USERNAME.getName()));
        assertEquals("pw", maria.getProperty(Props.DB_PASSWORD.getName()));

        Properties pg = new Properties();
        ProfileCreateDefaults.applyServerDatabase(pg, true, "localhost", 5433, "u", "", "db");
        assertEquals("jdbc:postgresql://localhost:5433/db", pg.getProperty(Props.DB_URL.getName()));
    }

    @Test
    @DisplayName("applyPorts fills only the missing ports with the deterministic per-profile band")
    void applyPortsFillsOnlyMissing() {
        Properties props = new Properties();
        props.setProperty(Props.API_PORT.getName(), "9999");
        ProfileCreateDefaults.applyPorts(props, "alpha");

        int[] band = ProfileNameSuggester.deriveProfilePorts("alpha");
        assertEquals("9999", props.getProperty(Props.API_PORT.getName()));
        assertEquals(String.valueOf(band[1]), props.getProperty(Props.P2P_PORT.getName()));
        assertEquals(String.valueOf(band[2]), props.getProperty(Props.API_WEBSOCKET_PORT.getName()));
    }

    @Test
    @DisplayName("applyPorts is deterministic for the same profile name")
    void applyPortsDeterministic() {
        Properties a = new Properties();
        Properties b = new Properties();
        ProfileCreateDefaults.applyPorts(a, "beta");
        ProfileCreateDefaults.applyPorts(b, "beta");
        assertEquals(a.getProperty(Props.API_PORT.getName()), b.getProperty(Props.API_PORT.getName()));
        assertEquals(a.getProperty(Props.P2P_PORT.getName()), b.getProperty(Props.P2P_PORT.getName()));
    }

    // ------------------------------------------------------------------
    // Companion SQLite database profile (the save-time auto-creation rule)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ensureSqliteDatabaseProfile creates ./database/SQLite/<name>/profile.json pointing at the URL")
    void ensureSqliteDatabaseProfileCreatesProfile() throws IOException {
        String name = "e2e_probe_" + System.nanoTime();
        Path dir = sqliteProfileDir(name);
        try {
            ProfileCreateDefaults.ensureSqliteDatabaseProfile(name, ProfileCreateDefaults.sqliteDbUrl(name));

            assertTrue(Files.isDirectory(dir), "the profile directory must exist");
            Path profileJson = dir.resolve("profile.json");
            assertTrue(Files.isRegularFile(profileJson), "the profile.json must exist");
            JsonObject json = JsonParser.parseString(Files.readString(profileJson)).getAsJsonObject();
            assertEquals(ProfileCreateDefaults.sqliteDbUrl(name), json.get("DB.Url").getAsString());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("ensureSqliteDatabaseProfile ignores blank names and non-SQLite URLs (never throws)")
    void ensureSqliteDatabaseProfileIgnoresInvalid() {
        String name = "e2e_probe_" + System.nanoTime();
        ProfileCreateDefaults.ensureSqliteDatabaseProfile("", ProfileCreateDefaults.sqliteDbUrl(name));
        ProfileCreateDefaults.ensureSqliteDatabaseProfile(null, ProfileCreateDefaults.sqliteDbUrl(name));
        ProfileCreateDefaults.ensureSqliteDatabaseProfile(name, "jdbc:mariadb://localhost:3306/signum");
        assertFalse(Files.exists(sqliteProfileDir(name)), "no profile may be created for invalid input");
    }

    /** The profile directory of the given name (SSOT: the same base SQLiteProfile resolves). */
    private static Path sqliteProfileDir(String name) {
        return PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                .resolve("SQLite").resolve(name);
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