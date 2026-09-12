package application.module.node.profile;

import application.module.node.props.Props;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    @DisplayName("applyNetwork maps mainnet/testnet to node.network")
    void applyNetwork() {
        Properties main = new Properties();
        ProfileCreateDefaults.applyNetwork(main, false);
        assertEquals("mainnet", main.getProperty(Props.NETWORK_PARAMETERS.getName()));

        Properties test = new Properties();
        ProfileCreateDefaults.applyNetwork(test, true);
        assertEquals("testnet", test.getProperty(Props.NETWORK_PARAMETERS.getName()));
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
}