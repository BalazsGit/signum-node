package application.module.node;

import application.module.node.profile.NodeProfile;
import org.junit.After;
import org.junit.Test;

import java.nio.file.Paths;

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
            if (signum.isRunning()) {
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
}