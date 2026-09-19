package application.module.node;

import application.module.node.profile.NodeProfile;
import application.module.node.profile.NodeProfileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Lifecycle contract tests for the {@link NodeModule} registry/factory API (v4 architecture).
 * <p>
 * NodeModule is the sole composition root and lifecycle entry point for {@link Signum}
 * instances: {@code startNode(name)} / {@code stopNode(name)} / {@code restartNode(name)}.
 * These tests verify the API contract without starting a real node (full startup is
 * covered by the integration test matrix).
 * </p>
 */
class NodeModuleLifecycleTest {

    private static final String PROFILE = "lifecycle-test-" + System.nanoTime();
    private static final java.nio.file.Path CONF = Paths.get("./conf");

    private NodeModule module() {
        return NodeModule.getInstance();
    }

    @AfterEach
    public void cleanup() {
        // Never leak a registered (or running) test node into other tests.
        Signum signum = module().get(PROFILE);
        if (signum != null) {
            Signum.State state = signum.getState();
            if (signum.isRunning() || state == Signum.State.ERROR) {
                try {
                    signum.stop();
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
            module().removeNode(PROFILE);
        }
    }

    @Test
    public void getInstance_returnsSingleton() {
        assertSame(NodeModule.getInstance(), NodeModule.getInstance(), "NodeModule must be a singleton");
    }

    @Test
    public void startNode_nullName_throwsIAE() {
        try {
            module().startNode((String) null);
            fail("Expected IllegalArgumentException for null profile name");
        } catch (IllegalArgumentException expected) {
            // contract
        }
    }

    @Test
    public void startNode_blankName_throwsIAE() {
        try {
            module().startNode("   ");
            fail("Expected IllegalArgumentException for blank profile name");
        } catch (IllegalArgumentException expected) {
            // contract
        }
    }

    @Test
    public void startNode_nullConfRoot_throwsIAE() {
        try {
            module().startNode(PROFILE, (java.nio.file.Path) null);
            fail("Expected IllegalArgumentException for null confRoot");
        } catch (IllegalArgumentException expected) {
            // contract
        }
    }

    @Test
    public void stopNode_unknownProfile_isNoOpAndReturnsNull() {
        assertNull(module().stopNode("no-such-profile-" + System.nanoTime()));
    }

    @Test
    public void restartNode_nullName_throwsIAE() {
        try {
            module().restartNode(null);
            fail("Expected IllegalArgumentException for null profile name");
        } catch (IllegalArgumentException expected) {
            // contract (stopNode(null) is a no-op; startNode(null) rejects)
        }
    }

    @Test
    public void restartNode_runningNode_stopsThenStartsInOrder() throws Exception {
        // Regression: restart used to queue an async stop and then call startNode(), whose
        // "already RUNNING" no-op saw the still-running node and bailed BEFORE queueing a
        // start — leaving the node STOPPED. The restart must be a true stop-then-start.
        String name = "restart-order-" + System.nanoTime();
        // Signum is final (cannot be subclassed); Mockito 5's inline mock maker can mock it.
        // Only getProfileName() is stubbed so addNode/get/removeNode key correctly; getProfile()
        // stays null, which skips the (orthogonal) resource-reservation logic in this test.
        Signum fake = Mockito.mock(Signum.class);
        Mockito.when(fake.getProfileName()).thenReturn(name);

        module().addNode(fake);
        try {
            module().restartNode(name);
            // restartNode runs stop->start asynchronously on the lifecycle executor; verify
            // (bounded wait) that BOTH were invoked on the SAME instance, in order: stop() first.
            InOrder inOrder = Mockito.inOrder(fake);
            inOrder.verify(fake, Mockito.timeout(5000)).stop();
            inOrder.verify(fake, Mockito.timeout(5000)).start();
        } finally {
            module().removeNode(name);
        }
    }

    @Test
    public void addNode_registersInstance_andGetReturnsIt() {
        Signum signum = new Signum(new NodeProfile(PROFILE), CONF);
        module().addNode(signum);
        try {
            assertSame(signum, module().get(PROFILE), "get(profile) must return the registered instance");
            assertTrue(module().hasProfile(PROFILE), "hasProfile must be true after addNode");
            assertTrue(module().getAll().contains(signum), "getAll must contain the registered instance");
        } finally {
            module().removeNode(PROFILE);
        }
        assertNull(module().get(PROFILE), "get(profile) must be null after removeNode");
        assertFalse(module().hasProfile(PROFILE), "hasProfile must be false after removeNode");
    }

    @Test
    public void addNode_sameProfile_replacesExistingInstance() {
        Signum first = new Signum(new NodeProfile(PROFILE), CONF);
        module().addNode(first);
        try {
            Signum second = new Signum(new NodeProfile(PROFILE), CONF);
            module().addNode(second);
            assertSame(second, module().get(PROFILE), "re-adding the same profile must replace the registered instance");
            assertEquals(1, module().getAll().stream().filter(s -> PROFILE.equals(s.getProfileName())).count(),
                    "size must not grow when replacing the same profile");
        } finally {
            module().removeNode(PROFILE);
        }
    }

    @Test
    public void startNode_registeredButNotRunning_reusesExistingInstance() {
        // A registered instance in CREATED state must be reused by startNode — the
        // method must resolve the SAME instance (it may fail to fully start without
        // valid configuration, but it must never create a second instance).
        Signum signum = new Signum(new NodeProfile(PROFILE), CONF);
        module().addNode(signum);
        int sizeBefore = module().size();
        try {
            try {
                Signum result = module().startNode(PROFILE);
                assertSame(signum, result, "startNode must reuse the registered instance");
            } catch (Exception expected) {
                // startup may fail in a headless test environment — acceptable —
                // the contract under test is instance reuse, not node startup.
            }
            assertEquals(sizeBefore, module().size(),
                    "startNode must not create a duplicate instance for the same profile");
        } finally {
            module().removeNode(PROFILE);
        }
    }

    // =====================================================================
    // ERROR-state recovery contract
    // (a node that failed to start must never dead-end: Stop works from ERROR,
    // Restart = Stop + Start therefore always works; only a direct Start from
    // ERROR is rejected by design — the user must acknowledge the failure first)
    // =====================================================================

    @Test
    public void stop_fromErrorState_reachesStopped() {
        Signum signum = new Signum(new NodeProfile(PROFILE), CONF);
        module().addNode(signum);
        try {
            setStateForTest(signum, Signum.State.ERROR);
            signum.stop();
            assertEquals(Signum.State.STOPPED, signum.getState(),
                    "stop() from ERROR must tear down and reach STOPPED");
        } finally {
            module().removeNode(PROFILE);
        }
    }

    @Test
    public void start_fromErrorState_isRejectedUntilStopped() {
        Signum signum = new Signum(new NodeProfile(PROFILE), CONF);
        try {
            setStateForTest(signum, Signum.State.ERROR);
            try {
                signum.start();
                fail("Expected IllegalStateException for a direct start() from ERROR");
            } catch (IllegalStateException expected) {
                // contract: a failed start must be acknowledged with an explicit
                // stop() before a new start can be attempted
            }
        } finally {
            if (signum.isRunning()) {
                try {
                    signum.stop();
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        }
    }

    @Test
    public void stop_fromCreatedState_isNoOp() {
        Signum signum = new Signum(new NodeProfile(PROFILE), CONF);
        signum.stop();
        assertEquals(Signum.State.CREATED, signum.getState(), "stop() from CREATED must be a no-op");
    }

    @Test
    public void stop_fromStoppedState_isNoOp() {
        Signum signum = new Signum(new NodeProfile(PROFILE), CONF);
        try {
            setStateForTest(signum, Signum.State.STOPPED);
            signum.stop();
            assertEquals(Signum.State.STOPPED, signum.getState(), "stop() from STOPPED must be a no-op");
        } finally {
            if (signum.isRunning()) {
                try {
                    signum.stop();
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        }
    }

    /**
     * A user/system pause is only meaningful for a RUNNING node's blockchain sync.
     * When the lifecycle leaves the running state (stop / start / error) the pause
     * flag must be cleared, so a (re)started node resumes syncing and the GUI sync
     * button returns to the PAUSE (not RESUME) icon.
     * <p>
     * Regression test (v5): pausing a node and then stopping + starting it left the
     * node appearing PAUSED and the toolbar stuck on a wrong "Resume" button. The
     * fix clears the pause inside {@code Signum.setState()} whenever the lifecycle
     * transitions to a non-RUNNING state — this test drives that path via the real
     * {@link Signum#init()} transition (CREATED -> INITIALIZED).
     * </p>
     */
    @Test
    public void pause_isCleared_whenLifecycleLeavesRunning() {
        Signum signum = new Signum(new NodeProfile(PROFILE), CONF);
        try {
            // A freshly created node is not paused.
            assertFalse(signum.getOperatingState() == Signum.OperatingState.PAUSED_USER
                            || signum.getOperatingState() == Signum.OperatingState.PAUSED_SYSTEM,
                    "fresh node must not be paused");

            // Simulate the user pausing the (running) node.
            signum.pauseByUser();
            assertEquals(Signum.OperatingState.PAUSED_USER, signum.getOperatingState(),
                    "pauseByUser() must set the paused state");

            // Drive the lifecycle out of the running state via a real state
            // transition (init: CREATED -> INITIALIZED), which must clear the pause.
            signum.init();

            assertFalse(signum.getOperatingState() == Signum.OperatingState.PAUSED_USER
                            || signum.getOperatingState() == Signum.OperatingState.PAUSED_SYSTEM,
                    "pause must be cleared once the lifecycle leaves RUNNING");
        } finally {
            if (signum.isRunning()) {
                try {
                    signum.stop();
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        }
    }

    // =====================================================================
    // Startup (arbitration) order — the tabOrder doubles as the autostart order.
    // The "first profile wins the resource" invariant depends on a single,
    // deterministic order shared by the GUI tab display and NodeModule.start().
    // =====================================================================

    @Test
    public void inStartupOrder_preferredOrderComesFirst_thenMissingAppended() {
        NodeProfile alpha = new NodeProfile("alpha");
        NodeProfile beta = new NodeProfile("beta");
        NodeProfile mainnet = new NodeProfile("mainnet");
        NodeProfile[] discovered = new NodeProfile[]{alpha, beta, mainnet};

        NodeProfile[] ordered = NodeProfileRepository.inStartupOrder(discovered, List.of("mainnet", "alpha"));

        assertEquals(3, ordered.length);
        assertEquals("mainnet", ordered[0].getName());
        assertEquals("alpha", ordered[1].getName());
        assertEquals("beta", ordered[2].getName());
    }

    @Test
    public void inStartupOrder_ignoresUnknownNamesInPreferredOrder() {
        NodeProfile alpha = new NodeProfile("alpha");
        NodeProfile beta = new NodeProfile("beta");
        NodeProfile[] discovered = new NodeProfile[]{alpha, beta};

        NodeProfile[] ordered = NodeProfileRepository.inStartupOrder(discovered, List.of("ghost", "beta"));

        assertEquals(2, ordered.length);
        assertEquals("beta", ordered[0].getName());
        assertEquals("alpha", ordered[1].getName());
    }

    @Test
    public void inStartupOrder_nullPreferredFallsBackToDiscoveryOrder() {
        NodeProfile alpha = new NodeProfile("alpha");
        NodeProfile beta = new NodeProfile("beta");
        NodeProfile[] discovered = new NodeProfile[]{alpha, beta};

        NodeProfile[] ordered = NodeProfileRepository.inStartupOrder(discovered, null);

        assertEquals("alpha", ordered[0].getName());
        assertEquals("beta", ordered[1].getName());
    }

    /**
     * Test helper: forces a lifecycle state directly (the state machine's
     * transitions to ERROR are only reachable through a real failed startup,
     * which is environment-dependent and therefore not used here).
     */
    private static void setStateForTest(Signum signum, Signum.State state) {
        try {
            java.lang.reflect.Field field = Signum.class.getDeclaredField("state");
            field.setAccessible(true);
            field.set(signum, state);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to force test state " + state, e);
        }
    }
}