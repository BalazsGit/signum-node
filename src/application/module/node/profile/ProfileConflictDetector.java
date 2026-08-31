package application.module.node.profile;

import application.module.node.props.Prop;
import application.module.node.props.Props;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects resource conflicts between node profiles: HTTP (API) port, P2P port,
 * WebSocket port and the target database (server engine+host+port+name, or the
 * SQLite file path).
 * <p>
 * Single, headless source of truth for "what would collide" between two profiles.
 * Used by the GUI info bar ({@code NodeInfoBar}) to render a red warning + tooltip,
 * and by {@code NodeModule} to <b>reject at start time</b> a profile whose resources
 * are already claimed by another (e.g. two autostart profiles on the same port/DB —
 * the later one must not start).
 * </p>
 * <p>
 * All keys are read from the canonical {@link Props} names ({@code API.Port},
 * {@code P2P.Port}, {@code API.WebSocketPort}, {@code DB.Url}), falling back to the
 * declared defaults when a profile does not override them.
 * </p>
 */
public final class ProfileConflictDetector {

    /** The kind of resource that is in conflict. */
    public enum ConflictField {
        /** HTTP / JSON API port ({@code API.Port}). */
        API_PORT,
        /** Peer-to-peer port ({@code P2P.Port}). */
        P2P_PORT,
        /** WebSocket port ({@code API.WebSocketPort}). */
        WEBSOCKET_PORT,
        /** Target database (server identity or SQLite file). */
        DATABASE
    }

    /**
     * A single detected conflict between the target profile and another profile.
     * Immutable value object consumed by the GUI and by start-time enforcement.
     */
    public static final class Conflict {
        private final ConflictField field;
        private final String ownValue;
        private final String otherProfile;
        private final String otherValue;
        private final boolean otherRunning;

        public Conflict(ConflictField field, String ownValue, String otherProfile,
                        String otherValue, boolean otherRunning) {
            this.field = field;
            this.ownValue = ownValue;
            this.otherProfile = otherProfile;
            this.otherValue = otherValue;
            this.otherRunning = otherRunning;
        }

        public ConflictField getField() {
            return field;
        }

        public String getOwnValue() {
            return ownValue;
        }

        public String getOtherProfile() {
            return otherProfile;
        }

        public String getOtherValue() {
            return otherValue;
        }

        /** @return true if the conflicting profile is currently RUNNING. */
        public boolean isOtherRunning() {
            return otherRunning;
        }

        @Override
        public String toString() {
            return field + " conflict with '" + otherProfile + "' (" + ownValue + ")";
        }
    }

    /** Parsed representation of a profile's {@code DB.Url}. */
    private static final class DbInfo {
        final String engine;    // "SQLite" | "MariaDB" | "MySQL" | "PostgreSQL" | "Unknown"
        final String name;      // server DB name, or SQLite file base name (may be empty)
        final String identity;  // canonical collision key ("" => nothing to compare)
        final int port;         // server port, or 0 for SQLite (file-based)
        final String display;   // human-friendly "Engine/name" (or "Unknown")

        DbInfo(String engine, String name, String identity, int port, String display) {
            this.engine = engine;
            this.name = name;
            this.identity = identity;
            this.port = port;
            this.display = display;
        }
    }

    /** Matches server JDBC URLs: {@code jdbc:(mariadb|mysql|postgresql)://[user@]host[:port]/db[?params]}. */
    private static final Pattern SERVER_DB_URL = Pattern.compile(
            "jdbc:(?<engine>mariadb|mysql|postgresql)://(?:(?<user>[^@/]*)@)?"
                    + "(?<host>[^:/]+)(?::(?<port>\\d+))?/(?<db>[^?]*)(?:\\?.*)?");

    private ProfileConflictDetector() {
        // static utility
    }

    // ── Port accessors (canonical Props keys + declared defaults) ─────────────

    /** @return the profile's API/HTTP port as a string (never null). */
    public static String apiPort(NodeProfile p) {
        return intProp(p, Props.API_PORT);
    }

    /** @return the profile's P2P port as a string (never null). */
    public static String p2pPort(NodeProfile p) {
        return intProp(p, Props.P2P_PORT);
    }

    /** @return the profile's WebSocket port as a string (never null). */
    public static String wsPort(NodeProfile p) {
        return intProp(p, Props.API_WEBSOCKET_PORT);
    }

    /** @return true if the profile has WebSocket enabled. */
    public static boolean wsEnabled(NodeProfile p) {
        String v = p.getProperty(Props.API_WEBSOCKET_ENABLE.getName());
        if (v == null || v.isBlank()) {
            return Props.API_WEBSOCKET_ENABLE.getDefaultValue();
        }
        return Boolean.parseBoolean(v.trim());
    }

    // ── Database accessors ──────────────────────────────────────────────────

    /** @return a human-friendly database label, e.g. {@code MariaDB/signum_main} or {@code SQLite/signum.sqlite.db}. */
    public static String dbDisplayName(NodeProfile p) {
        return parseDb(p).display;
    }

    /** @return the database port (3306/5432 for server engines), or {@code 0} for SQLite / not applicable. */
    public static int dbPort(NodeProfile p) {
        return parseDb(p).port;
    }

    /** @return a canonical DB collision key, or an empty string if not recognizable. */
    public static String dbIdentity(NodeProfile p) {
        return parseDb(p).identity;
    }

    // ── Detection ───────────────────────────────────────────────────────────

    /**
     * Detects all resource conflicts of {@code target} against the given {@code others}.
     * <p>
     * A conflict is reported for every matching resource. The {@code runningProfiles}
     * set (profile names currently RUNNING) is used only to annotate each conflict with
     * {@link Conflict#isOtherRunning()} for display/severity — a configuration collision
     * is reported regardless of run state.
     * </p>
     *
     * @param target          the profile to check
     * @param others          the other profiles to compare against (may be empty)
     * @param runningProfiles profile names currently running (may be null)
     * @return an unmodifiable list of conflicts, empty when there are none
     */
    public static List<Conflict> detect(NodeProfile target,
                                        Collection<? extends NodeProfile> others,
                                        Set<String> runningProfiles) {
        if (target == null || others == null || others.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> running = runningProfiles == null ? Collections.emptySet() : runningProfiles;

        String tApi = apiPort(target);
        String tP2p = p2pPort(target);
        boolean tWsEnabled = wsEnabled(target);
        String tWs = wsPort(target);
        String tDb = dbIdentity(target);
        String tDbDisplay = dbDisplayName(target);

        List<Conflict> out = new ArrayList<>();
        for (NodeProfile other : others) {
            if (other == null || other == target) {
                continue;
            }
            String on = other.getName();
            if (on != null && on.equals(target.getName())) {
                continue;
            }
            boolean otherRunning = on != null && running.contains(on);

            if (tApi.equals(apiPort(other))) {
                out.add(new Conflict(ConflictField.API_PORT, tApi, on, apiPort(other), otherRunning));
            }
            if (tP2p.equals(p2pPort(other))) {
                out.add(new Conflict(ConflictField.P2P_PORT, tP2p, on, p2pPort(other), otherRunning));
            }
            if (tWsEnabled && wsEnabled(other) && tWs.equals(wsPort(other))) {
                out.add(new Conflict(ConflictField.WEBSOCKET_PORT, tWs, on, wsPort(other), otherRunning));
            }
            if (!tDb.isEmpty() && tDb.equals(dbIdentity(other))) {
                out.add(new Conflict(ConflictField.DATABASE, tDbDisplay, on,
                        dbDisplayName(other), otherRunning));
            }
        }
        return Collections.unmodifiableList(out);
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private static String intProp(NodeProfile p, Prop<Integer> prop) {
        String v = p.getProperty(prop.getName());
        if (v == null || v.isBlank()) {
            v = String.valueOf(prop.getDefaultValue());
        }
        return v.trim();
    }

    private static DbInfo parseDb(NodeProfile p) {
        String url = p.getProperty(Props.DB_URL.getName());
        if (url == null || url.isBlank()) {
            // The node resolves the profile's database via the canonical DB.Url, falling
            // back to its declared default (SQLite) when the profile does not override it
            // (see DatabaseInstanceFactory). Mirror that here so a profile that leaves
            // DB.Url unset still reports "SQLite/<default file>" instead of "Unknown".
            url = Props.DB_URL.getDefaultValue();
        }
        url = url.trim();
        if (url.isEmpty()) {
            return new DbInfo("Unknown", "", "", 0, "Unknown");
        }

        if (url.toLowerCase().startsWith("jdbc:sqlite:")) {
            String path = url.substring("jdbc:sqlite:".length());
            if (path.startsWith("file:")) {
                path = path.substring("file:".length());
            }
            String display;
            String identity;
            try {
                java.nio.file.Path abs = java.nio.file.Paths.get(path).toAbsolutePath().normalize();
                java.nio.file.Path fileName = abs.getFileName();
                display = fileName != null ? fileName.toString() : abs.toString();
                identity = "sqlite|" + abs.toString().replace('\\', '/').toLowerCase();
            } catch (Exception e) {
                display = path;
                identity = "sqlite|" + path.replace('\\', '/').toLowerCase();
            }
            return new DbInfo("SQLite", display, identity, 0, "SQLite/" + display);
        }

        java.util.regex.Matcher m = SERVER_DB_URL.matcher(url);
        if (m.find()) {
            String engineRaw = m.group("engine").toLowerCase();
            String engine;
            int defaultPort;
            if (engineRaw.equals("postgresql")) {
                engine = "PostgreSQL";
                defaultPort = 5432;
            } else if (engineRaw.equals("mysql")) {
                engine = "MySQL";
                defaultPort = 3306;
            } else {
                engine = "MariaDB";
                defaultPort = 3306;
            }
            String host = m.group("host").toLowerCase();
            String portStr = m.group("port");
            int port = portStr != null ? Integer.parseInt(portStr) : defaultPort;
            String db = m.group("db") == null ? "" : m.group("db");
            String identity = engineRaw + "|" + host + "|" + port + "|" + db.toLowerCase();
            String label = db.isEmpty() ? host : db;
            return new DbInfo(engine, db, identity, port, engine + "/" + label);
        }

        // Unrecognized URL — still compare by the raw URL so identical configs collide.
        return new DbInfo("Unknown", "", "raw|" + url.toLowerCase(), 0, "Unknown");
    }
}