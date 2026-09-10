package application.module.node;

import application.module.node.profile.NodeProfile;
import application.module.node.profile.NodeProfileRepository;
import org.junit.After;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Lifecycle contract tests for the {@link NodeModule} registry/factory API (v4 architecture).
 * <p>
 * NodeModule is the sole composition root and lifecycle entry point for {@link Signum}
 * instances: {@code startNode(name)} / {@code stopNode(name)} / {@code restartNode(name)}.
 * These tests verify the API contract without starting a real node (full startup is
 * covered by the integration test matrix).
 * </p>
 */
public class NodeModuleLifecycleTest {

    private static final String PROFILE = "lifecycle-test-" + System.nanoTime();
    private static final java.nio.file.Path CONF = Paths.get("./conf");

    private NodeModule module() {
        return NodeModule.getInstance();
    }

    @After
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
        assertSame("NodeModule must be a singleton", NodeModule.getInstance(), NodeModule.getInstance());
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
    public void addNode_registersInstance_andGetReturnsIt() {
        Signum signum = new Signum(new NodeProfile(PROFILE), CONF);
        module().addNode(signum);
        try {
            assertSame("get(profile) must return the registered instance", signum, module().get(PROFILE));
            assertTrue("hasProfile must be true after addNode", module().hasProfile(PROFILE));
            assertTrue("getAll must contain the registered instance", module().getAll().contains(signum));
        } finally {
            module().removeNode(PROFILE);
        }
        assertNull("get(profile) must be null after removeNode", module().get(PROFILE));
        assertFalse("hasProfile must be false after removeNode", module().hasProfile(PROFILE));
    }

    @Test
    public void addNode_sameProfile_replacesExistingInstance() {
        Signum first = new Signum(new NodeProfile(PROFILE), CONF);
        module().addNode(first);
        try {
            Signum second = new Signum(new NodeProfile(PROFILE), CONF);
            module().addNode(second);
            assertSame("re-adding the same profile must replace the registered instance",
                    second, module().get(PROFILE));
            assertEquals("size must not grow when replacing the same profile",
                    1, module().getAll().stream().filter(s -> PROFILE.equals(s.getProfileName())).count());
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
                assertSame("startNode must reuse the registered instance", signum, result);
            } catch (Exception expected) {
                // startup may fail in a headless test environment — acceptable —
                // the contract under test is instance reuse, not node startup.
            }
            assertEquals("startNode must not create a duplicate instance for the same profile",
                    sizeBefore, module().size());
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
            assertEquals("stop() from ERROR must tear down and reach STOPPED",
                    Signum.State.STOPPED, signum.getState());
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
        assertEquals("stop() from CREATED must be a no-op",
                Signum.State.CREATED, signum.getState());
    }

    @Test
    public void stop_fromStoppedState_isNoOp() {
        Signum signum = new Signum(new NodeProfile(PROFILE), CONF);
        try {
            setStateForTest(signum, Signum.State.STOPPED);
            signum.stop();
            assertEquals("stop() from STOPPED must be a no-op",
                    Signum.State.STOPPED, signum.getState());
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