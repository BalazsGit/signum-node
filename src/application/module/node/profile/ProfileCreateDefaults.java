package application.module.node.profile;

import application.module.database.profile.SQLiteProfile;
import application.module.node.props.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Properties;

/**
 * SSOT mapping from "new profile" input to the canonical {@link Props} keys.
 * <p>
 * Used by BOTH the headless CLI ({@code profile create}) and the GUI setup wizard so that the
 * "independent-by-default" assignment (plan §2.6) lives in exactly one place:
 * </p>
 * <ul>
 *   <li>network → {@code node.network}</li>
 *   <li>database → {@code DB.Url} (+ {@code DB.Username}/{@code DB.Password} for server engines)</li>
 *   <li>ports → {@code API.Port} / {@code P2P.Port} / {@code API.WebSocketPort}, with the
 *       deterministic per-profile band ({@link ProfileNameSuggester#deriveProfilePorts}) for
 *       every port the caller did not set</li>
 * </ul>
 */
public final class ProfileCreateDefaults {

    private static final Logger logger = LoggerFactory.getLogger(ProfileCreateDefaults.class);

    private ProfileCreateDefaults() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Per-profile SQLite data directory (SSOT): {@code ./database/SQLite/<name>} (CWD-relative,
     * the same base {@link #sqliteDbUrl} is derived from).
     */
    public static Path sqliteDataDir(String profileName) {
        return Path.of("database", "SQLite", profileName);
    }

    /** Default SQLite database file name used by per-profile databases. */
    public static final String DEFAULT_SQLITE_FILE_NAME = "signum.sqlite.db";

    /** Per-profile SQLite JDBC URL (SSOT): {@code ./database/SQLite/<name>/signum.sqlite.db}. */
    public static String sqliteDbUrl(String profileName) {
        return "jdbc:sqlite:file:./database/SQLite/" + profileName + "/" + DEFAULT_SQLITE_FILE_NAME;
    }

    /**
     * Whether the given DB URL is the per-profile default SQLite URL for the named profile
     * (SSOT: {@link #sqliteDbUrl}) — the signal that the database lives in the profile's own
     * data directory (and belongs to it). A server-DB or manually set URL belongs elsewhere
     * and must never be renamed/deleted by profile operations.
     */
    public static boolean isPerProfileSqliteUrl(String profileName, String dbUrl) {
        return profileName != null && dbUrl != null && sqliteDbUrl(profileName).equals(dbUrl);
    }

    /**
     * Creates (or updates) the SQLite database profile of the given name in the
     * database module's profile area ({@code ./database/SQLite/<name>/profile.json})
     * pointing at the given JDBC URL, and ensures the database's own directory
     * exists. This is the single rule both node-profile save paths apply (the
     * configuration panel's save and the setup wizard): a new/changed SQLite
     * configuration is discoverable as a database profile right after saving.
     * <p>
     * Never throws (D17): an invalid name/URL or a write failure is logged and
     * swallowed — the node-profile save itself must never fail because of the
     * companion database profile.
     */
    public static void ensureSqliteDatabaseProfile(String profileName, String dbUrl) {
        try {
            if (profileName == null || profileName.isBlank()
                    || dbUrl == null || !dbUrl.startsWith("jdbc:sqlite:")) {
                return;
            }
            SQLiteProfile profile = new SQLiteProfile(profileName.trim());
            profile.getConfiguration().put(SQLiteProfile.CFG_URL, dbUrl);
            profile.saveToProfileJson();
            profile.ensureDatabaseDirectory();
        } catch (Exception e) {
            logger.warn("Could not create/update the SQLite database profile '{}': {}",
                    profileName, e.toString());
        }
    }

    /** Fully-qualified testnet network-parameters class ({@code signum.net.TestnetNetwork}). */
    public static final String TESTNET_NETWORK_CLASS = "signum.net.TestnetNetwork";

    /**
     * Maps the network selection to {@code node.network} — the fully-qualified
     * {@code NetworkParameters} class name loaded via reflection at node start.
     * <p>
     * Mainnet is the default network: the property is left unset (its default is
     * {@code null}) so the node falls back to the built-in mainnet parameters.
     * Writing a non-class value (or a mainnet marker) here would make
     * {@code Class.forName(...)} fail at start-up.
     * </p>
     */
    public static Properties applyNetwork(Properties props, boolean testnet) {
        if (testnet) {
            props.setProperty(Props.NETWORK_PARAMETERS.getName(), TESTNET_NETWORK_CLASS);
        } else {
            props.remove(Props.NETWORK_PARAMETERS.getName());
        }
        return props;
    }

    /** Assigns a per-profile (file-based) SQLite database — independently runnable out of the box. */
    public static Properties applySqliteDatabase(Properties props, String profileName) {
        props.setProperty(Props.DB_URL.getName(), sqliteDbUrl(profileName));
        return props;
    }

    /**
     * Assigns a server-DB connection (MariaDB or PostgreSQL) — host/port/user/password/db name.
     * The JDBC URL format mirrors the database module's portable profiles
     * ({@code jdbc:mariadb://host:port/db}, {@code jdbc:postgresql://host:port/db}).
     */
    public static Properties applyServerDatabase(Properties props, boolean postgres,
                                                  String host, int port,
                                                  String user, String password, String db) {
        String engine = postgres ? "postgresql" : "mariadb";
        props.setProperty(Props.DB_URL.getName(), "jdbc:" + engine + "://" + host + ":" + port + "/" + db);
        props.setProperty(Props.DB_USERNAME.getName(), user == null ? "" : user);
        props.setProperty(Props.DB_PASSWORD.getName(), password == null ? "" : password);
        return props;
    }

    /**
     * Independent-by-default port assignment (plan §2.6): every port the caller did NOT set
     * gets the deterministic per-profile band derived from the profile name
     * ({@link ProfileNameSuggester#deriveProfilePorts} — SSOT). Explicit values are preserved.
     */
    public static Properties applyPorts(Properties props, String profileName) {
        int[] ports = ProfileNameSuggester.deriveProfilePorts(profileName);
        if (!props.containsKey(Props.API_PORT.getName())) {
            props.setProperty(Props.API_PORT.getName(), String.valueOf(ports[0]));
        }
        if (!props.containsKey(Props.P2P_PORT.getName())) {
            props.setProperty(Props.P2P_PORT.getName(), String.valueOf(ports[1]));
        }
        if (!props.containsKey(Props.API_WEBSOCKET_PORT.getName())) {
            props.setProperty(Props.API_WEBSOCKET_PORT.getName(), String.valueOf(ports[2]));
        }
        return props;
    }
}