package application.module.node.profile;

import application.module.node.props.Props;

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

    private ProfileCreateDefaults() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Per-profile SQLite JDBC URL (SSOT): {@code ./database/SQLite/<name>/signum.sqlite.db}. */
    public static String sqliteDbUrl(String profileName) {
        return "jdbc:sqlite:file:./database/SQLite/" + profileName + "/signum.sqlite.db";
    }

    /** Maps the network selection to {@code node.network} ({@code mainnet}/{@code testnet}). */
    public static Properties applyNetwork(Properties props, boolean testnet) {
        props.setProperty(Props.NETWORK_PARAMETERS.getName(), testnet ? "testnet" : "mainnet");
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