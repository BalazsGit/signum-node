package application.module.node;

import application.module.node.profile.NodeProfile;
import application.module.node.props.Props;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the start-time resource-conflict enforcement in {@link NodeModule}:
 * when two profiles collide on a resource (API port, P2P port, WebSocket port or the
 * database), the <b>later</b> start must be rejected (not started) while the first one
 * keeps its reservation. This is what makes two conflicting autostart profiles start in
 * order — the second one does not start.
 * <p>
 * The reservation and rejection happen synchronously inside {@code startNode}; the heavy
 * async start is a side effect that fails fast in this headless environment and is caught,
 * so the assertions below are deterministic.
 * </p>
 */
@DisplayName("NodeModule start-time conflict enforcement Tests")
class NodeModuleConflictTest {

    private static final String NAME_A = "conflict-a-" + System.nanoTime();
    private static final String NAME_B = "conflict-b-" + System.nanoTime();
    private static final java.nio.file.Path CONF = Paths.get("./conf");

    private NodeModule module() {
        return NodeModule.getInstance();
    }

    private NodeProfile profile(String name, String api, String p2p, String db) {
        NodeProfile p = new NodeProfile(name);
        if (api != null) p.setProperty(Props.API_PORT.getName(), api);
        if (p2p != null) p.setProperty(Props.P2P_PORT.getName(), p2p);
        if (db != null) p.setProperty(Props.DB_URL.getName(), db);
        // WebSocket is enabled by default and would otherwise make every pair collide on
        // the shared default WS port — disable it so these tests isolate API/P2P/DB.
        p.setProperty(Props.API_WEBSOCKET_ENABLE.getName(), "false");
        return p;
    }

    private Signum register(String name, String api, String p2p, String db) {
        Signum signum = new Signum(profile(name, api, p2p, db), CONF);
        module().addNode(signum);
        return signum;
    }

    @BeforeEach
    void clean() {
        for (String name : new String[]{NAME_A, NAME_B}) {
            Signum s = module().get(name);
            if (s != null) {
                if (s.isRunning()) {
                    try { s.stop(); } catch (Exception ignored) { }
                }
                module().removeNode(name);
            }
        }
    }

    @AfterEach
    void cleanup() {
        clean();
    }

    @Test
    @DisplayName("second profile on the same API.Port is rejected; the first keeps its reservation")
    void sameApiPort_secondIsRejected() {
        // Arrange: A and B share API.Port 18125 (different P2P ports and different DBs)
        register(NAME_A, "18125", "19001", "jdbc:mariadb://localhost:19999/aaa");
        register(NAME_B, "18125", "19002", "jdbc:mariadb://localhost:19999/bbb");

        // Act: start in order (A first)
        Signum a = module().startNode(NAME_A);
        Signum b = module().startNode(NAME_B);

        // Assert
        assertNotNull(a);
        assertNotNull(b);
        assertTrue(module().isHttpPortInUse(18125), "A must own the shared API port");
        assertTrue(module().isP2pPortInUse(19001), "A's P2P port must be reserved");
        assertFalse(module().isP2pPortInUse(19002),
                "B must be REJECTED before reserving its own resources (conflict on the shared API port)");
        assertFalse(b.isRunning(), "the conflicting second profile must not be running");
    }

    @Test
    @DisplayName("second profile sharing the same database is rejected even on different ports")
    void sameDatabase_secondIsRejected() {
        // Arrange: same database, different API/P2P ports
        register(NAME_A, "28125", "29001", "jdbc:mariadb://localhost:19999/shared");
        register(NAME_B, "28126", "29002", "jdbc:mariadb://localhost:19999/shared");

        // Act
        module().startNode(NAME_A);
        Signum b = module().startNode(NAME_B);

        // Assert
        assertTrue(module().isHttpPortInUse(28125), "A must own its API port");
        assertFalse(module().isHttpPortInUse(28126), "B must be REJECTED (shared database)");
        assertFalse(module().isP2pPortInUse(29002), "B must not reserve resources after rejection");
        assertFalse(b.isRunning(), "the conflicting second profile must not be running");
    }

    @Test
    @DisplayName("non-conflicting profiles are both accepted (no false rejection)")
    void disjointProfiles_bothReserved() {
        // Arrange: fully disjoint resources
        register(NAME_A, "38125", "39001", "jdbc:mariadb://localhost:19999/aaa");
        register(NAME_B, "38126", "39002", "jdbc:mariadb://localhost:19999/bbb");

        // Act
        module().startNode(NAME_A);
        module().startNode(NAME_B);

        // Assert: both reserved, none rejected
        assertTrue(module().isHttpPortInUse(38125), "A must own its API port");
        assertTrue(module().isHttpPortInUse(38126), "B must own its API port (no conflict)");
        assertTrue(module().isP2pPortInUse(39001), "A's P2P must be reserved");
        assertTrue(module().isP2pPortInUse(39002), "B's P2P must be reserved (no conflict)");
    }
}