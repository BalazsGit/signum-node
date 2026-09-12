package application.module.node;

import application.module.node.profile.NodeProfile;
import application.module.node.props.Props;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the ownership-event mechanism in {@link NodeModule}: a claiming-set change listener
 * is notified (synchronously) whenever the set of claiming profiles changes — a profile
 * reserves at start, releases at stop / failed start, or the store is cleared on shutdown.
 * This is the event the GUI subscribes to keep every info bar's conflict chips current
 * (including a profile whose tab is not open). Reservations are driven through the public
 * start/stop path ({@code startNode}/{@code stopAll}), exactly how the GUI triggers them.
 * <p>
 * The heavy async start is a side effect that fails fast in this headless environment and is
 * caught, so the assertions are deterministic: the reserve/release notification runs
 * synchronously inside the public call.
 * </p>
 */
@DisplayName("NodeModule claiming-set ownership event Tests")
class NodeModuleOwnershipEventTest {

    private static final String NAME_A = "ownership-a-" + System.nanoTime();
    private static final java.nio.file.Path CONF = java.nio.file.Paths.get("./conf");

    private NodeModule module() {
        return NodeModule.getInstance();
    }

    private void register() {
        NodeProfile p = new NodeProfile(NAME_A);
        p.setProperty(Props.API_PORT.getName(), "18125");
        p.setProperty(Props.P2P_PORT.getName(), "19001");
        p.setProperty(Props.DB_URL.getName(), "jdbc:mariadb://localhost:19999/aaa");
        p.setProperty(Props.API_WEBSOCKET_ENABLE.getName(), "false");
        module().addNode(new Signum(p, CONF));
    }

    private void clean() {
        Signum s = module().get(NAME_A);
        if (s != null) {
            if (s.isRunning()) {
                try { s.stop(); } catch (Exception ignored) { }
            }
            module().removeNode(NAME_A);
        }
    }

    @BeforeEach
    void before() { clean(); }

    @AfterEach
    void after() {
        module().stopAll();
        clean();
    }

    @Test
    @DisplayName("reserving (startNode) fires the ownership event with the profile in the claiming set")
    void reserveFiresOwnershipEvent() {
        // Arrange: capture every claiming-set snapshot the listener observes.
        List<Set<String>> snapshots = Collections.synchronizedList(new ArrayList<>());
        Runnable listener = () -> snapshots.add(module().getClaimingProfileNames());
        module().addClaimingSetListener(listener);

        try {
            // Act: reserve through the public start path.
            register();
            module().startNode(NAME_A);

            // Assert: the reserve event fired with A in the claiming set.
            assertTrue(snapshots.stream().anyMatch(s -> s.contains(NAME_A)),
                    "an ownership event must fire with the reserving profile in the claiming set");
        } finally {
            module().removeClaimingSetListener(listener);
        }
    }

    @Test
    @DisplayName("releasing (stopAll) fires the ownership event with the profile out of the claiming set")
    void releaseFiresOwnershipEvent() {
        // Arrange: reserve first (the async start is a side effect that fails fast and is caught).
        register();
        module().startNode(NAME_A);
        List<Set<String>> snapshots = Collections.synchronizedList(new ArrayList<>());
        Runnable listener = () -> snapshots.add(module().getClaimingProfileNames());
        module().addClaimingSetListener(listener);

        try {
            // Act: release through the synchronous stop path.
            module().stopAll();

            // Assert: a release event fired with A out of the claiming set.
            assertTrue(snapshots.stream().anyMatch(s -> !s.contains(NAME_A)),
                    "an ownership event must fire with the released profile out of the claiming set");
        } finally {
            module().removeClaimingSetListener(listener);
        }
    }

    @Test
    @DisplayName("every registered listener fires and a throwing listener is isolated")
    void allListenersFire_andThrowingOneIsIsolated() {
        // Arrange: a throwing listener registered before a normal one.
        AtomicBoolean secondFired = new AtomicBoolean(false);
        Runnable throwing = () -> { throw new IllegalStateException("boom"); };
        Runnable second = () -> secondFired.set(true);
        module().addClaimingSetListener(throwing);
        module().addClaimingSetListener(second);

        try {
            // Act
            register();
            module().startNode(NAME_A);

            // Assert: the second listener still fired despite the first throwing.
            assertTrue(secondFired.get(),
                    "a throwing listener must not prevent the remaining listeners from firing");
        } finally {
            module().removeClaimingSetListener(throwing);
            module().removeClaimingSetListener(second);
        }
    }

    @Test
    @DisplayName("removing a listener stops it from firing")
    void removeListener_stopsFiring() {
        // Arrange
        List<Set<String>> snapshots = Collections.synchronizedList(new ArrayList<>());
        Runnable listener = () -> snapshots.add(module().getClaimingProfileNames());
        module().addClaimingSetListener(listener);
        module().removeClaimingSetListener(listener);

        // Act
        register();
        module().startNode(NAME_A);

        // Assert: the removed listener never fired.
        assertTrue(snapshots.isEmpty(), "a removed listener must not fire on a claim change");
    }
}