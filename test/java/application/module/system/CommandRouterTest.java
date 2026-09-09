package application.module.system;

import application.cli.CommandContext;
import application.cli.CommandHandler;
import application.cli.CommandRegistry;
import application.cli.CommandResult;
import application.cli.CommandSource;
import application.cli.CommandSpec;
import application.cli.OwnerContext;
import application.module.node.NodeModule;
import application.module.node.Signum;
import application.module.node.profile.NodeProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Routing-contract tests for the universal {@link CommandRouter} (v5 architecture).
 * <p>
 * Two complementary layers:
 * </p>
 * <ol>
 *   <li><b>Integration</b> — real {@link Signum} registration through the default registry,
 *       verifying the node-verb dispatch, arg preservation, and back-compat
 *       ({@code .pause}, {@code -node.<profile> .pause}).</li>
 *   <li><b>Parser isolation</b> — a recording handler injected via the package-private
 *       {@link CommandRouter#execute} overload, verifying the new grammar, owner resolution
 *       and validation without touching a real node or the live {@code conf/} tree.</li>
 * </ol>
 * <p>
 * Unknown commands are now rejected at the router (fail-fast, "see sys.help") rather than
 * being forwarded to a node — an intentional v5 change.
 * </p>
 */
@DisplayName("CommandRouter Tests")
public class CommandRouterTest {

    private static final String PROFILE = "cmd-router-test-" + System.nanoTime();
    private static final Path CONF = Paths.get("./conf");

    private NodeModule module() {
        return NodeModule.getInstance();
    }

    @AfterEach
    void cleanup() {
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

    // ── Integration: node-verb routing through the default registry ───────────────

    @Test
    @DisplayName("blank or null input fails")
    void route_blankOrNullOrInput_fails() {
        assertFalse(CommandRouter.route(null).isOk());
        assertFalse(CommandRouter.route("   ").isOk());
    }

    @Test
    @DisplayName("global sys.help works without any node")
    void route_globalHelp_okWithoutAnyNode() {
        assertTrue(CommandRouter.route("sys.help").isOk(), CommandRouter.route("sys.help").getMessage());
        // back-compat: legacy .help still resolves to sys.help
        assertTrue(CommandRouter.route(".help").isOk(), CommandRouter.route(".help").getMessage());
    }

    @Test
    @DisplayName("a non-global command without a target fails explicitly")
    void route_nonGlobalWithoutTarget_failsExplicitly() {
        assertFalse(CommandRouter.route("node.pause").isOk());
        assertFalse(CommandRouter.route(".pause").isOk());
    }

    @Test
    @DisplayName("a bare word that is not a command fails")
    void route_commandWithoutDot_fails() {
        assertFalse(CommandRouter.route("pause").isOk());
    }

    @Test
    @DisplayName("default profile owner is used when there is no explicit owner")
    void route_defaultProfileUsedWhenNoPrefix() {
        registerTestNode();
        assertTrue(CommandRouter.route("node.pause", PROFILE).isOk(),
                CommandRouter.route("node.pause", PROFILE).getMessage());
    }

    @Test
    @DisplayName("back-compat: '-node.<profile> .pause' still routes")
    void route_legacyAddressedTarget_ok() {
        registerTestNode();
        assertTrue(CommandRouter.route("-node." + PROFILE + " .pause").isOk(),
                CommandRouter.route("-node." + PROFILE + " .pause").getMessage());
    }

    @Test
    @DisplayName("a node command addressed to a non-node owner module fails")
    void route_unknownModule_fails() {
        registerTestNode();
        assertFalse(CommandRouter.route("-logging." + PROFILE + " .pause").isOk());
    }

    @Test
    @DisplayName("a reserved profile name cannot be a command target")
    void route_reservedProfile_fails() {
        assertFalse(CommandRouter.route("node.node-default node.pause").isOk());
        assertFalse(CommandRouter.route("-node.node-default .pause").isOk());
    }

    @Test
    @DisplayName("an unregistered node profile fails explicitly")
    void route_notRegisteredProfile_failsExplicitly() {
        assertFalse(CommandRouter.route("node.does-not-exist-xyz node.pause").isOk());
    }

    @Test
    @DisplayName("an explicit target beats the contextual default")
    void route_explicitTargetBeatsDefault() {
        registerTestNode();
        CommandRouter.Result result = CommandRouter.route("-node.does-not-exist-xyz .pause", PROFILE);
        assertFalse(result.isOk(), result.getMessage());
    }

    @Test
    @DisplayName("the .popoff argument survives routing to the node")
    void route_argsPreservedAndExecuted() {
        registerTestNode();
        assertTrue(CommandRouter.route("-node." + PROFILE + " .popoff 10").isOk(),
                CommandRouter.route("-node." + PROFILE + " .popoff 10").getMessage());
        // canonical form carries the same argument
        assertTrue(CommandRouter.route("node." + PROFILE + " node.popoff 10").isOk(),
                CommandRouter.route("node." + PROFILE + " node.popoff 10").getMessage());
    }

    @Test
    @DisplayName("an unknown command is rejected at the router (v5 fail-fast)")
    void route_unknownCommand_rejectedByRouter() {
        registerTestNode();
        assertFalse(CommandRouter.route("-node." + PROFILE + " .bogus").isOk(),
                CommandRouter.route("-node." + PROFILE + " .bogus").getMessage());
    }

    @Test
    @DisplayName("a target without a command fails")
    void route_targetWithoutCommand_fails() {
        registerTestNode();
        assertFalse(CommandRouter.route("node." + PROFILE).isOk());
    }

    @Test
    @DisplayName("a malformed owner/target fails")
    void route_malformedTarget_fails() {
        assertFalse(CommandRouter.route("node .pause").isOk());
        assertFalse(CommandRouter.route("-node. .pause").isOk());
    }

    // ── Parser isolation: recording handler, temp conf, no real node ───────────────

    @TempDir
    Path tempDir;

    /** Records the last {@link CommandContext} it handled. */
    static class RecordingHandler implements CommandHandler {
        CommandContext last;
        CommandResult result = CommandResult.ok(List.of("ok"));

        @Override
        public CommandResult handle(CommandContext ctx) {
            last = ctx;
            return result;
        }
    }

    private RecordingHandler recording;
    private CommandRegistry parserRegistry;

    private void setupParser() {
        recording = new RecordingHandler();
        parserRegistry = new CommandRegistry()
                .register(new CommandSpec("node", "pause", "pause", 0, 0, true, "node", recording))
                .register(new CommandSpec("logging", "link", "link", 1, 1, true, null, recording));
    }

    private CommandRouter.Result parse(String input, OwnerContext defaultOwner) {
        return CommandRouter.execute(input, defaultOwner, tempDir.toString(),
                CommandSource.CONSOLE_SYSTEM, parserRegistry);
    }

    @Test
    @DisplayName("canonical owner-first grammar resolves owner + command")
    void parser_ownerFirst() {
        setupParser();
        CommandRouter.Result r = parse("node.mainnet node.pause", null);
        assertTrue(r.isOk());
        assertEquals("node", recording.last.owner().module());
        assertEquals("mainnet", recording.last.owner().profile());
        assertTrue(recording.last.args().isEmpty());
    }

    @Test
    @DisplayName("logging.link captures its argument and owner")
    void parser_loggingLinkArgs() {
        setupParser();
        CommandRouter.Result r = parse("node.mainnet logging.link standard", null);
        assertTrue(r.isOk());
        assertEquals(List.of("standard"), recording.last.args());
        assertEquals("node", recording.last.owner().module());
    }

    @Test
    @DisplayName("a node command rejects a non-node owner module")
    void parser_wrongOwnerModuleFails() {
        setupParser();
        CommandRouter.Result r = parse("database.mainnet node.pause", null);
        assertFalse(r.isOk());
        assertTrue(r.getMessage().contains("requires a 'node' owner"));
        assertNull(recording.last);
    }

    @Test
    @DisplayName("too many arguments are rejected")
    void parser_tooManyArgsFails() {
        setupParser();
        assertFalse(parse("node.mainnet node.pause extra", null).isOk());
    }

    @Test
    @DisplayName("a missing required argument is rejected")
    void parser_missingRequiredArgFails() {
        setupParser();
        assertFalse(parse("node.mainnet logging.link", null).isOk());
    }

    @Test
    @DisplayName("a malformed owner token is rejected")
    void parser_malformedOwnerFails() {
        setupParser();
        CommandRouter.Result r = parse("mainnet node.pause", null);
        assertFalse(r.isOk());
        assertTrue(r.getMessage().contains("Malformed owner"));
    }

    @Test
    @DisplayName("a command requiring an owner fails without one")
    void parser_missingOwnerFails() {
        setupParser();
        CommandRouter.Result r = parse("node.pause", null);
        assertFalse(r.isOk());
        assertTrue(r.getMessage().contains("No target"));
    }
}
