package application.module.node.profile;

import application.module.node.props.Props;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProfileConflictDetector} — the shared source of truth for
 * port/DB parsing and cross-profile conflict detection (used by the GUI info bar and
 * by {@code NodeModule} start-time enforcement).
 * <p>Headless-safe: only pure logic is exercised (no Swing, no node startup).</p>
 */
@DisplayName("ProfileConflictDetector Tests")
class ProfileConflictDetectorTest {

    private NodeProfile profile(String name, String apiPort, String p2pPort, String dbUrl) {
        NodeProfile p = new NodeProfile(name);
        if (apiPort != null) p.setProperty(Props.API_PORT.getName(), apiPort);
        if (p2pPort != null) p.setProperty(Props.P2P_PORT.getName(), p2pPort);
        if (dbUrl != null) p.setProperty(Props.DB_URL.getName(), dbUrl);
        // WebSocket is ENABLED by default and would otherwise make every pair of
        // profiles collide on the shared default WS port — disable it here so the
        // non-WebSocket tests isolate the API/P2P/DB dimensions.
        p.setProperty(Props.API_WEBSOCKET_ENABLE.getName(), "false");
        return p;
    }

    // ── Database display / port / identity ───────────────────────────────

    @Test
    @DisplayName("MariaDB URL shows engine/name and default port 3306")
    void mariadb_displayAndPort() {
        NodeProfile p = new NodeProfile("mariadb");
        p.setProperty(Props.DB_URL.getName(), "jdbc:mariadb://localhost:3306/signum_main");

        assertEquals("MariaDB/signum_main", ProfileConflictDetector.dbDisplayName(p));
        assertEquals(3306, ProfileConflictDetector.dbPort(p));
        assertEquals("mariadb|localhost|3306|signum_main", ProfileConflictDetector.dbIdentity(p));
    }

    @Test
    @DisplayName("PostgreSQL URL shows engine/name and default port 5432")
    void postgresql_displayAndPort() {
        NodeProfile p = new NodeProfile("pg");
        p.setProperty(Props.DB_URL.getName(), "jdbc:postgresql://localhost:5432/signum");

        assertEquals("PostgreSQL/signum", ProfileConflictDetector.dbDisplayName(p));
        assertEquals(5432, ProfileConflictDetector.dbPort(p));
    }

    @Test
    @DisplayName("SQLite URL shows the file base name and port 0 (file-based)")
    void sqlite_displayAndPort() {
        NodeProfile p = new NodeProfile("sqlite");
        p.setProperty(Props.DB_URL.getName(), "jdbc:sqlite:file:./database/SQLite/sqlite/signum.sqlite.db");

        String display = ProfileConflictDetector.dbDisplayName(p);
        assertTrue(display.startsWith("SQLite/"), "SQLite display should start with SQLite/: " + display);
        assertTrue(display.endsWith("signum.sqlite.db"), "SQLite display should end with the file name: " + display);
        assertEquals(0, ProfileConflictDetector.dbPort(p), "SQLite is file-based → port 0");
    }

    @Test
    @DisplayName("two profiles on the same server DB collide; different DBs do not")
    void dbIdentity_collision() {
        NodeProfile a = new NodeProfile("a");
        a.setProperty(Props.DB_URL.getName(), "jdbc:mariadb://localhost:3306/signum");
        NodeProfile b = new NodeProfile("b");
        b.setProperty(Props.DB_URL.getName(), "jdbc:mariadb://localhost:3306/signum");
        NodeProfile c = new NodeProfile("c");
        c.setProperty(Props.DB_URL.getName(), "jdbc:mariadb://localhost:3306/otherdb");

        String ia = ProfileConflictDetector.dbIdentity(a);
        assertFalse(ia.isEmpty(), "recognizable DB must have a non-empty identity");
        assertEquals(ia, ProfileConflictDetector.dbIdentity(b), "identical server DBs must share an identity");
        assertFalse(ia.equals(ProfileConflictDetector.dbIdentity(c)), "different DB names must not share an identity");
    }

    // ── Port accessors (defaults + overrides) ─────────────────────────────

    @Test
    @DisplayName("ports fall back to declared defaults when unset")
    void port_defaults() {
        NodeProfile p = new NodeProfile("defaults");
        assertEquals(String.valueOf(Props.API_PORT.getDefaultValue()), ProfileConflictDetector.apiPort(p));
        assertEquals(String.valueOf(Props.P2P_PORT.getDefaultValue()), ProfileConflictDetector.p2pPort(p));
        assertEquals(String.valueOf(Props.API_WEBSOCKET_PORT.getDefaultValue()), ProfileConflictDetector.wsPort(p));
    }

    @Test
    @DisplayName("ports reflect explicit overrides")
    void port_overrides() {
        NodeProfile p = new NodeProfile("overrides");
        p.setProperty(Props.API_PORT.getName(), "8500");
        p.setProperty(Props.P2P_PORT.getName(), "8501");
        assertEquals("8500", ProfileConflictDetector.apiPort(p));
        assertEquals("8501", ProfileConflictDetector.p2pPort(p));
    }

    // ── Conflict detection ────────────────────────────────────────────────

    @Test
    @DisplayName("same API.Port across profiles is reported as an API_PORT conflict")
    void detect_apiPortConflict() {
        NodeProfile a = profile("a", "8125", "9001", "jdbc:mariadb://localhost:3306/a");
        NodeProfile b = profile("b", "8125", "9002", "jdbc:mariadb://localhost:3306/b");

        List<ProfileConflictDetector.Conflict> conflicts =
                ProfileConflictDetector.detect(a, List.of(b), Set.of("a"));

        ProfileConflictDetector.Conflict api = conflicts.stream()
                .filter(c -> c.getField() == ProfileConflictDetector.ConflictField.API_PORT).findFirst().orElseThrow(
                        () -> new AssertionError("an API.Port collision must be reported: " + conflicts));
        assertEquals("b", api.getOtherProfile());
        assertTrue(conflicts.stream().noneMatch(c -> c.getField() == ProfileConflictDetector.ConflictField.DATABASE),
                "different DBs must not be reported as a DB conflict");
    }

    @Test
    @DisplayName("same database (different ports) is reported as a DATABASE conflict only")
    void detect_dbConflict() {
        NodeProfile a = profile("a", "8125", "9001", "jdbc:mariadb://localhost:3306/shared");
        NodeProfile b = profile("b", "8126", "9002", "jdbc:mariadb://localhost:3306/shared");

        List<ProfileConflictDetector.Conflict> conflicts =
                ProfileConflictDetector.detect(a, List.of(b), Set.of());

        assertTrue(conflicts.stream().anyMatch(c -> c.getField() == ProfileConflictDetector.ConflictField.DATABASE),
                "a shared database must be reported");
        assertTrue(conflicts.stream().noneMatch(c -> c.getField() == ProfileConflictDetector.ConflictField.API_PORT),
                "different API.Ports must not conflict");
        assertTrue(conflicts.stream().noneMatch(c -> c.getField() == ProfileConflictDetector.ConflictField.P2P_PORT),
                "different P2P.Ports must not conflict");
    }

    @Test
    @DisplayName("disjoint ports and database produce no conflict")
    void detect_noConflict() {
        NodeProfile a = profile("a", "8125", "9001", "jdbc:mariadb://localhost:3306/a");
        NodeProfile b = profile("b", "8126", "9002", "jdbc:mariadb://localhost:3306/b");

        List<ProfileConflictDetector.Conflict> conflicts =
                ProfileConflictDetector.detect(a, List.of(b), Set.of());

        assertTrue(conflicts.isEmpty(), "no conflicts expected but got: " + conflicts);
    }

    @Test
    @DisplayName("WebSocket conflict is reported when both profiles enable it on the same port")
    void detect_websocketConflict() {
        NodeProfile a = profile("a", "8125", "9001", "jdbc:mariadb://localhost:3306/a");
        a.setProperty(Props.API_WEBSOCKET_PORT.getName(), "8500");
        a.setProperty(Props.API_WEBSOCKET_ENABLE.getName(), "true");
        NodeProfile b = profile("b", "8126", "9002", "jdbc:mariadb://localhost:3306/b");
        b.setProperty(Props.API_WEBSOCKET_PORT.getName(), "8500");
        b.setProperty(Props.API_WEBSOCKET_ENABLE.getName(), "true");

        List<ProfileConflictDetector.Conflict> conflicts =
                ProfileConflictDetector.detect(a, List.of(b), Set.of());

        assertTrue(conflicts.stream().anyMatch(c -> c.getField() == ProfileConflictDetector.ConflictField.WEBSOCKET_PORT),
                "a shared enabled WebSocket port must be reported");
    }

    @Test
    @DisplayName("WebSocket is ignored when disabled on either side")
    void detect_websocketDisabled_noConflict() {
        NodeProfile a = profile("a", "8125", "9001", "jdbc:mariadb://localhost:3306/a");
        a.setProperty(Props.API_WEBSOCKET_PORT.getName(), "8500");
        a.setProperty(Props.API_WEBSOCKET_ENABLE.getName(), "true");
        NodeProfile b = profile("b", "8126", "9002", "jdbc:mariadb://localhost:3306/b");
        b.setProperty(Props.API_WEBSOCKET_PORT.getName(), "8500");
        b.setProperty(Props.API_WEBSOCKET_ENABLE.getName(), "false");

        List<ProfileConflictDetector.Conflict> conflicts =
                ProfileConflictDetector.detect(a, List.of(b), Set.of());

        assertTrue(conflicts.stream().noneMatch(c -> c.getField() == ProfileConflictDetector.ConflictField.WEBSOCKET_PORT),
                "a disabled WebSocket must not conflict");
    }

    @Test
    @DisplayName("detect is null/empty-safe")
    void detect_nullSafe() {
        assertTrue(ProfileConflictDetector.detect(null, List.of(), Set.of()).isEmpty());
        NodeProfile a = profile("a", "8125", "9001", null);
        assertTrue(ProfileConflictDetector.detect(a, null, null).isEmpty());
    }

    // ── Default database + running annotation ─────────────────────────────

    @Test
    @DisplayName("unset DB.Url falls back to the declared default (SQLite), not Unknown")
    void db_unsetFallsBackToDefaultSqlite() {
        NodeProfile p = new NodeProfile("default-db");
        p.setProperty(Props.API_WEBSOCKET_ENABLE.getName(), "false");
        // Deliberately no DB.Url set — mirrors a profile relying on the node default.

        String display = ProfileConflictDetector.dbDisplayName(p);
        assertTrue(display.startsWith("SQLite/"),
                "a profile with no DB.Url must report the default SQLite db, got: " + display);
        assertFalse(display.equalsIgnoreCase("Unknown"), "must not be Unknown");
    }

    @Test
    @DisplayName("conflicts are annotated running vs not — the GUI surfaces only the running ones")
    void detect_runningAnnotation() {
        NodeProfile a = profile("a", "8125", "9001", "jdbc:mariadb://localhost:3306/shared");
        NodeProfile b = profile("b", "8125", "9002", "jdbc:mariadb://localhost:3306/shared");

        List<ProfileConflictDetector.Conflict> whenRunning =
                ProfileConflictDetector.detect(a, List.of(b), Set.of("b"));
        assertTrue(whenRunning.stream().allMatch(ProfileConflictDetector.Conflict::isOtherRunning),
                "a conflict with a RUNNING profile must be annotated running");

        List<ProfileConflictDetector.Conflict> whenStopped =
                ProfileConflictDetector.detect(a, List.of(b), Set.of());
        assertFalse(whenStopped.stream().anyMatch(ProfileConflictDetector.Conflict::isOtherRunning),
                "a conflict with a stopped profile must be annotated not-running (the GUI hides these)");
    }
}