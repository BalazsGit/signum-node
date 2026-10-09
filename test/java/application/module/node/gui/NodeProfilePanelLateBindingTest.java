package application.module.node.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.Collections;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import application.module.node.BlockchainProcessor;
import application.module.node.NodeModule;
import application.module.node.Signum;
import application.module.node.profile.NodeProfile;

/**
 * Late-binding contract tests for {@link NodeProfilePanel} — the bottom status strip
 * (latest block / peers / upload+download volume) stays frozen forever when a profile
 * is started through a path that does NOT hand the {@link Signum} back to the panel
 * (setup wizard "start immediately", application autostart, CLI {@code profile run}).
 * <p>
 * {@code NodeModule.startNode()} registers the instance in the registry synchronously
 * before the async start task runs, so whenever the start-pending set changes the
 * panel must adopt the registered instance; the single state listener then drives the
 * console's runtime wiring on the RUNNING push (see
 * {@code NodeConsolePanel#ensureRuntimeWiring()}).
 * </p>
 * <p>
 * No real node is started: the {@code BlockchainProcessor} is a mock and the lifecycle
 * transitions are driven directly, keeping the tests headless-safe.
 * </p>
 */
@DisplayName("NodeProfilePanel Late Binding (auto-start wiring) Tests")
class NodeProfilePanelLateBindingTest {

    private static final long WAIT_TIMEOUT_MS = 10_000;

    private NodeProfile profile;
    private Signum signum;
    private NodeProfilePanel panel;
    private BlockchainProcessor processorMock;

    private String newProfileName(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    /**
     * Builds the late-start fixture: a Signum already registered in the NodeModule
     * (as {@code startNode()} does synchronously) plus a panel constructed BEFORE the
     * node existed (lazy tab load → null Signum at construction time).
     */
    private void buildLateStartFixture(String name) {
        profile = new NodeProfile(name);
        signum = new Signum(profile, Paths.get("./conf"));
        NodeModule.getInstance().addNode(signum);
        panel = new NodeProfilePanel(null, profile, null);
    }

    @AfterEach
    void cleanup() {
        if (panel != null) {
            panel.dispose();
            panel = null;
        }
        if (profile != null) {
            NodeModule.getInstance().removeNode(profile.getName());
            profile = null;
        }
        signum = null;
        processorMock = null;
    }

    @Test
    @DisplayName("start queued via another path (wizard/autostart): the panel adopts the Signum from the registry")
    void pendingStart_broadcastAdoptsRegisteredSignum() {
        buildLateStartFixture(newProfileName("late-bind"));
        assertNull(panel.getSignum(), "the panel starts unbound (constructed before the node existed)");

        // The synchronous part of NodeModule.startNode() has already happened (the
        // instance is registered); the start became pending and the broadcast fired.
        invokePrivate(NodeModule.getInstance(), "markStartPending", profile.getName());

        waitFor(() -> panel.getSignum() == signum,
                "the panel must late-bind the Signum from the NodeModule registry on the pending-start broadcast");

        assertEquals(1, signum.getStateListeners().size(),
                "late binding must register exactly one state listener on the Signum");
    }

    @Test
    @DisplayName("late-bound node reaching RUNNING wires the console bottom status strip (download/latest block/peers)")
    void lateBoundNode_runningPushWiresBottomPanel() throws Exception {
        buildLateStartFixture(newProfileName("late-wire"));
        invokePrivate(NodeModule.getInstance(), "markStartPending", profile.getName());
        waitFor(() -> panel.getSignum() == signum,
                "the panel must late-bind the Signum before the RUNNING push");

        runToRunning();

        waitFor(() -> listenerOwnerOf(consoleOf(panel)) == signum,
                "the console must register its runtime listeners when the late-bound node reaches RUNNING");

        // The bottom-panel value sources: download/upload volume, latest block and
        // sync progress must all be live on the bound processor.
        verify(processorMock).addListener(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(BlockchainProcessor.Event.NET_VOLUME_CHANGED));
        verify(processorMock).addListener(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(BlockchainProcessor.Event.BLOCK_PUSHED));
        verify(processorMock).addSyncStateListener(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("wizard 'start immediately' path (panel constructed with the Signum): RUNNING push wires the bottom panel")
    void wizardStartImmediately_runningPushWiresBottomPanel() throws Exception {
        profile = new NodeProfile(newProfileName("wizard-start"));
        signum = new Signum(profile, Paths.get("./conf"));
        NodeModule.getInstance().addNode(signum);
        // NodePanel.addProfileTab → lazy load resolves the registry entry, so the panel
        // is constructed WITH the Signum (startNode registered it before the tab existed).
        panel = new NodeProfilePanel(null, profile, signum);

        runToRunning();

        waitFor(() -> listenerOwnerOf(consoleOf(panel)) == signum,
                "the console must register its runtime listeners when the wizard-started node reaches RUNNING");

        verify(processorMock).addListener(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(BlockchainProcessor.Event.NET_VOLUME_CHANGED));
        verify(processorMock).addListener(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(BlockchainProcessor.Event.BLOCK_PUSHED));
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Simulates the node reaching RUNNING without a real startup: property service
     * ready ({@link Signum#init()}), a mock {@link BlockchainProcessor} in place (the
     * real one only exists after {@code doInitialize()}), then the actual state push.
     */
    private void runToRunning() throws Exception {
        signum.init(); // CREATED -> INITIALIZED (loads the property service)
        processorMock = mock(BlockchainProcessor.class);
        when(processorMock.getAllPeers()).thenReturn(Collections.emptyList());
        when(processorMock.getConsistencyState()).thenReturn(BlockchainProcessor.ConsistencyState.CONSISTENT);
        setField(signum, "blockchainProcessor", processorMock);
        invokePrivate(signum, "setState", Signum.State.RUNNING);
    }

    /** Pumps the EDT until the condition holds (the wiring chain is invokeLater-based). */
    private static void waitFor(java.util.function.Supplier<Boolean> condition, String message) {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                SwingUtilities.invokeAndWait(() -> { });
            } catch (Exception ignored) {
                // EDT pump best-effort
            }
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for: " + message);
            }
        }
        fail("Timed out waiting for: " + message);
    }

    private static NodeConsolePanel consoleOf(NodeProfilePanel panel) {
        try {
            Field f = NodeProfilePanel.class.getDeclaredField("consolePanel");
            f.setAccessible(true);
            return (NodeConsolePanel) f.get(panel);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read the console panel", e);
        }
    }

    private static Signum listenerOwnerOf(NodeConsolePanel console) {
        try {
            Field f = NodeConsolePanel.class.getDeclaredField("listenerOwner");
            f.setAccessible(true);
            return (Signum) f.get(console);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read the console listener owner", e);
        }
    }

    private static void invokePrivate(Object target, String methodName, Object... args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i].getClass();
        }
        try {
            Method m = target.getClass().getDeclaredMethod(methodName, types);
            m.setAccessible(true);
            m.invoke(target, args);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to invoke private " + methodName, e);
        }
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to set field " + fieldName, e);
        }
    }
}