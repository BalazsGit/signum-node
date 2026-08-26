package application.module.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import application.module.node.NodeModule;
import application.module.node.Signum;
import application.module.node.profile.NodeProfileRepository;

import java.util.Set;

/**
 * Universal command router for console command input (v4 architecture, P1.8).
 * <p>
 * Console commands are addressed explicitly:
 * </p>
 * <pre>
 *   -module.profile &lt;command&gt;   (e.g. "-node.testnet .pause")
 *   &lt;global command&gt;            (whitelist: ".help")
 * </pre>
 * <p>
 * The router resolves the target through the {@link NodeModule} registry (the
 * sole composition root) and dispatches to the corresponding
 * {@link Signum#processCommandInstance(String)} instance. Unknown modules,
 * unknown/reserved profiles and missing targets produce <b>explicit errors</b> —
 * never a silent no-op and never a fallback to "the first node" (the P0-1 /
 * P0-2 failure modes, now architecturally impossible).
 * </p>
 * <p>
 * Consoles that belong to a specific profile (e.g. a profile console tab) pass
 * that profile as the <em>default target</em>: a bare command typed there still
 * lands on the right node, while an explicit {@code -module.profile} prefix
 * always wins.
 * </p>
 */
public final class CommandRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandRouter.class);

    /** The only currently routable module (node profiles). */
    public static final String MODULE_NODE = "node";

    /** Commands executable without any target address. */
    private static final Set<String> GLOBAL_COMMANDS = Set.of(".help");

    private CommandRouter() {
        // static utility
    }

    /**
     * Outcome of a routing attempt. Always carries a user-facing message.
     */
    public static final class Result {

        private final boolean ok;
        private final String message;

        private Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }

        public boolean isOk() {
            return ok;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * Routes and executes a command without a contextual default target
     * (e.g. the System Console).
     *
     * @param input raw command text (e.g. {@code "-node.node .pause"} or {@code ".help"})
     * @return the routing outcome with a user-facing message
     */
    public static Result route(String input) {
        return route(input, null);
    }

    /**
     * Routes and executes a console command.
     *
     * @param input          raw command text; optional explicit target
     *                       ({@code "-module.profile"}) followed by the command
     * @param defaultProfile optional contextual default profile (e.g. the profile
     *                       console the command was typed in) — used only when no
     *                       explicit target is present and the command is not
     *                       globally whitelisted; {@code null} for none
     * @return the routing outcome with a user-facing message
     */
    public static Result route(String input, String defaultProfile) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) {
            return Result.fail("Empty command.");
        }

        String target = null;
        String command;
        int space = text.indexOf(' ');
        if (text.startsWith("-") && space > 0) {
            // Addressed command: -module.profile <command...>
            String targetToken = text.substring(1, space);
            command = text.substring(space + 1).trim();
            int dot = targetToken.indexOf('.');
            if (dot <= 0 || dot == targetToken.length() - 1) {
                return Result.fail("Malformed target '-" + targetToken
                        + "'; expected '-module.profile' (e.g. '-node.node .pause').");
            }
            String module = targetToken.substring(0, dot);
            String profile = targetToken.substring(dot + 1);
            if (!MODULE_NODE.equals(module)) {
                return Result.fail("Unknown module '" + module + "'; routable modules: " + MODULE_NODE + ".");
            }
            if (NodeProfileRepository.isReservedProfileName(profile)) {
                return Result.fail("Reserved profile name '" + profile + "' cannot be a command target.");
            }
            target = profile;
        } else {
            command = text;
        }

        if (command.isEmpty()) {
            return Result.fail("Missing command after target; expected e.g. '-node.node .pause'.");
        }
        if (!command.startsWith(".")) {
            return Result.fail("Unknown command '" + command + "'; commands start with '.' (see .help).");
        }

        // No explicit target: global whitelist first, then the contextual default profile.
        if (target == null) {
            if (GLOBAL_COMMANDS.contains(command)) {
                printGlobalHelp();
                return Result.ok("Executed global command '" + command + "'.");
            }
            if (defaultProfile != null && !defaultProfile.isEmpty()) {
                target = defaultProfile;
            } else {
                return Result.fail("No target for '" + command + "'. Address a node profile explicitly: "
                        + "'-node.<profile> " + command + "'. Global commands: " + GLOBAL_COMMANDS + ".");
            }
        }

        Signum signum = NodeModule.getInstance().get(target);
        if (signum == null) {
            return Result.fail("Profile '" + target + "' has no registered node instance. "
                    + "Start it first (NodeModule.startNode or the GUI Start button).");
        }

        LOGGER.info("Routing command '{}' to node profile '{}'", command, target);
        signum.processCommandInstance(command);
        return Result.ok("Routed '" + command + "' to node profile '" + target + "'.");
    }

    /**
     * Prints the global (routing-level) help: syntax + the node command set.
     */
    private static void printGlobalHelp() {
        LOGGER.info("Command syntax: [-module.profile] <command>");
        LOGGER.info("  -node.<profile> .pause  - routes .pause to the given node profile");
        LOGGER.info("  .help                   - shows this help (global command)");
        LOGGER.info("Node commands: .help .pause .resume .shutdown .restart .autoresolve .trim .dbcheck .popoff <n>");
    }
}