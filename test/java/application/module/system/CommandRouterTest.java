package application.module.system;

import application.module.node.NodeModule;
import application.module.node.Signum;
import application.module.node.profile.NodeProfile;
import org.junit.After;
import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Routing-contract tests for the universal {@link CommandRouter} (v4 architecture, P1.8).
 * <p>
 * Verifies the command grammar ({@code -module.profile <command>} vs. global commands),
 * explicit-error semantics (no silent no-op, no "first node" fallback) and registry-based
 * target resolution — all without any GUI involvement.
 * </p>
 */
public class CommandRouterTest {

    private static final String PROFILE = "cmd-router-test-" + System.nanoTime();
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

    private void registerTestNode() {
        module().addNode(new Signum(new NodeProfile(PROFILE), CONF));
    }

    @Test
    public void route_blankOrNullOrInput_fails() {
        assertFalse(CommandRouter.route(null).isOk());
        assertFalse(CommandRouter.route("   ").isOk());
    }

    @Test
    public void route_globalHelp_okWithoutAnyNode() {
        CommandRouter.Result result = CommandRouter.route(".help");
        assertTrue(result.getMessage(), result.isOk());
    }

    @Test
    public void route_nonGlobalWithoutTarget_failsExplicitly() {
        CommandRouter.Result result = CommandRouter.route(".pause");
        assertFalse(result.isOk());
    }

    @Test
    public void route_commandWithoutDot_fails() {
        assertFalse(CommandRouter.route("pause").isOk());
    }

    @Test
    public void route_defaultProfileUsedWhenNoPrefix() {
        registerTestNode();
        CommandRouter.Result result = CommandRouter.route(".help", PROFILE);
        assertTrue(result.getMessage(), result.isOk());
    }

    @Test
    public void route_explicitTarget_ok() {
        registerTestNode();
        CommandRouter.Result result = CommandRouter.route("-node." + PROFILE + " .help");
        assertTrue(result.getMessage(), result.isOk());
    }

    @Test
    public void route_unknownModule_fails() {
        assertFalse(CommandRouter.route("-logging.node .pause").isOk());
    }

    @Test
    public void route_reservedProfile_fails() {
        assertFalse(CommandRouter.route("-node.node-default .pause").isOk());
    }

    @Test
    public void route_notRegisteredProfile_failsExplicitly() {
        CommandRouter.Result result = CommandRouter.route("-node.does-not-exist-xyz .pause");
        assertFalse(result.getMessage(), result.isOk());
    }

    @Test
    public void route_explicitTargetBeatsDefault() {
        registerTestNode();
        // The explicit (unresolvable) target must win over the resolvable default.
        CommandRouter.Result result = CommandRouter.route("-node.does-not-exist-xyz .pause", PROFILE);
        assertFalse(result.getMessage(), result.isOk());
    }

    @Test
    public void route_argsPreservedAndExecuted() {
        registerTestNode();
        // ".popoff 10" — argument must survive routing; node is not running, so it is a safe no-op.
        CommandRouter.Result result = CommandRouter.route("-node." + PROFILE + " .popoff 10");
        assertTrue(result.getMessage(), result.isOk());
    }

    @Test
    public void route_unknownCommandIsRouted_nodeReportsIt() {
        registerTestNode();
        // Routing is the router's job; the node itself reports the unknown command.
        CommandRouter.Result result = CommandRouter.route("-node." + PROFILE + " .bogus");
        assertTrue(result.getMessage(), result.isOk());
    }

    @Test
    public void route_targetWithoutCommand_fails() {
        registerTestNode();
        assertFalse(CommandRouter.route("-node." + PROFILE).isOk());
    }

    @Test
    public void route_malformedTarget_fails() {
        assertFalse(CommandRouter.route("-node .pause").isOk());
        assertFalse(CommandRouter.route("-node. .pause").isOk());
    }
}