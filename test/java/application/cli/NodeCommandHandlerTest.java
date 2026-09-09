package application.cli;

import application.module.node.NodeModule;
import application.module.node.Signum;
import application.module.node.profile.NodeProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NodeCommandHandler}: it must resolve the registered {@link Signum}
 * for the owner's profile, reconstruct the legacy {@code .verb arg} form and forward it,
 * failing explicitly (no silent no-op) when no node is registered.
 */
@DisplayName("NodeCommandHandler Tests")
class NodeCommandHandlerTest {

    private static final String PROFILE = "node-cmd-handler-test-" + System.nanoTime();

    private NodeModule module() {
        return NodeModule.getInstance();
    }

    @AfterEach
    void cleanup() {
        Signum s = module().get(PROFILE);
        if (s != null) {
            if (s.isRunning()) {
                try {
                    s.stop();
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
            module().removeNode(PROFILE);
        }
    }

    private OwnerContext owner() {
        return OwnerContext.of("node", PROFILE);
    }

    private CommandContext ctx(OwnerContext o, List<String> args) {
        return new CommandContext(o, args, CommandSource.CONSOLE_SYSTEM, null);
    }

    @Test
    @DisplayName("handle() dispatches a known verb to the registered node")
    void dispatchKnownVerb() {
        module().addNode(new Signum(new NodeProfile(PROFILE), Paths.get("./conf")));

        CommandResult r = new NodeCommandHandler("pause").handle(ctx(owner(), List.of()));

        assertTrue(r.ok(), String.join(" ", r.out()));
    }

    @Test
    @DisplayName("handle() forwards the argument in the legacy '.verb arg' form")
    void forwardsArgument() {
        module().addNode(new Signum(new NodeProfile(PROFILE), Paths.get("./conf")));

        CommandResult r = new NodeCommandHandler("popoff").handle(ctx(owner(), List.of("10")));

        assertTrue(r.ok(), String.join(" ", r.out()));
    }

    @Test
    @DisplayName("handle() fails explicitly when no node is registered")
    void unregisteredNodeFails() {
        CommandResult r = new NodeCommandHandler("pause")
                .handle(ctx(OwnerContext.of("node", "no-such-node-xyz"), List.of()));

        assertFalse(r.ok());
        assertTrue(r.out().get(0).contains("no registered node instance"));
    }

    @Test
    @DisplayName("handle() fails when there is no owner")
    void noOwnerFails() {
        assertFalse(new NodeCommandHandler("pause").handle(ctx(null, List.of())).ok());
    }
}
