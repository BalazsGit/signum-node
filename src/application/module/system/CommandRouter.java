package application.module.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import application.cli.CommandContext;
import application.cli.CommandRegistry;
import application.cli.CommandResult;
import application.cli.CommandSource;
import application.cli.CommandSpec;
import application.cli.OwnerContext;
import application.module.node.profile.NodeProfileRepository;
import application.utils.config.PropertiesProfileLoader;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Universal command router for console command input (v5 architecture).
 * <p>
 * The canonical grammar is:
 * </p>
 * <pre>
 *   [owner] &lt;domain&gt;.&lt;action&gt; [args]        e.g. "node.mainnet node.pause"
 *   &lt;domain&gt;.&lt;action&gt; [args]                e.g. "node.pause" (owner implicit / global)
 * </pre>
 * where {@code owner = <module>.<profile>} and the command set is data-driven by the
 * {@link CommandRegistry} (see {@code application.cli}).
 * <p>
 * <b>Back-compatibility:</b> the legacy forms still work and are resolved to the new
 * grammar:
 * <ul>
 *   <li>{@code .pause} → {@code node.pause}; {@code .help} → {@code sys.help}.</li>
 *   <li>{@code -node.mainnet .pause} → {@code node.mainnet node.pause}.</li>
 * </ul>
 * <p>
 * Consoles that belong to a specific profile pass that profile as the <em>default
 * owner</em>: a bare command typed there still lands on the right node, while an
 * explicit {@code node.<profile>} prefix always wins. The router dispatches to a
 * {@code CommandHandler} — unknown commands/owners/args produce <b>explicit errors</b>,
 * never a silent no-op and never a "first node" fallback.
 * </p>
 */
public final class CommandRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandRouter.class);

    /** The only module whose profile instances can be node-command owners. */
    public static final String MODULE_NODE = "node";

    /** The v1 command registry (node.* / logging.* / sys.*), shared and immutable. */
    private static final CommandRegistry DEFAULT_REGISTRY = CommandRegistry.defaultRegistry();

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
     * Routes and executes a command without a contextual default owner
     * (e.g. the System Console).
     *
     * @param input raw command text (e.g. {@code "node.mainnet node.pause"} or {@code "sys.profiles"})
     * @return the routing outcome with a user-facing message
     */
    public static Result route(String input) {
        return route(input, (OwnerContext) null);
    }

    /**
     * Routes and executes a command with a contextual default <b>node profile</b> owner
     * (back-compat entry point for the per-profile console).
     *
     * @param input          raw command text
     * @param defaultProfile optional contextual default node profile name; {@code null} for none
     * @return the routing outcome
     */
    public static Result route(String input, String defaultProfile) {
        OwnerContext owner = (defaultProfile == null || defaultProfile.isBlank())
                ? null
                : OwnerContext.of(MODULE_NODE, defaultProfile);
        return route(input, owner);
    }

    /**
     * Routes and executes a command with an explicit contextual default owner.
     *
     * @param input        raw command text
     * @param defaultOwner optional contextual default owner; used only when no explicit
     *                     owner is present; {@code null} for none
     * @return the routing outcome
     */
    public static Result route(String input, OwnerContext defaultOwner) {
        return route(input, defaultOwner, CommandSource.CONSOLE_PROFILE);
    }

    /**
     * Full routing entry point.
     *
     * @param input        raw command text
     * @param defaultOwner contextual default owner, or {@code null}
     * @param source       the originating surface (console / CLI)
     * @return the routing outcome
     */
    public static Result route(String input, OwnerContext defaultOwner, CommandSource source) {
        return execute(input, defaultOwner, PropertiesProfileLoader.DEFAULT_CONF_ROOT, source, DEFAULT_REGISTRY);
    }

    /**
     * Core parse + validate + dispatch. Exposed with an explicit registry so tests can
     * inject a custom one.
     *
     * @param input        raw command text
     * @param defaultOwner contextual default owner, or {@code null}
     * @param confRoot     base configuration root
     * @param source       originating surface
     * @param registry     the command registry to resolve against
     * @return the routing outcome
     */
    static Result execute(String input, OwnerContext defaultOwner, String confRoot,
                          CommandSource source, CommandRegistry registry) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) {
            return Result.fail("Empty command.");
        }
        List<String> tokens = Arrays.asList(text.split("\\s+"));

        OwnerContext owner;
        CommandSpec spec;
        List<String> args;

        if (tokens.get(0).startsWith("-") && tokens.size() >= 2) {
            // Legacy addressed: "-module.profile <command...>"
            String targetToken = tokens.get(0).substring(1);
            OwnerContext parsed = parseOwnerSafe(targetToken);
            if (parsed == null) {
                return Result.fail("Malformed target '-" + targetToken + "'; expected '-module.profile'.");
            }
            CommandSpec s = resolveCommand(registry, tokens.get(1));
            if (s == null) {
                return Result.fail("Unknown command '" + tokens.get(1) + "' (see sys.help).");
            }
            owner = parsed;
            spec = s;
            args = tokens.subList(2, tokens.size());
        } else {
            Optional<CommandSpec> first = resolveCommandSpec(registry, tokens.get(0));
            if (first.isPresent()) {
                // Command first (canonical "node.pause" or legacy ".pause"); owner implicit.
                owner = defaultOwner;
                spec = first.get();
                args = tokens.subList(1, tokens.size());
            } else if (tokens.size() >= 2) {
                // Owner first (canonical "node.mainnet <command> [args]").
                OwnerContext parsed = parseOwnerSafe(tokens.get(0));
                if (parsed == null) {
                    return Result.fail("Malformed owner '" + tokens.get(0) + "'; expected '<module>.<profile>'.");
                }
                CommandSpec s = resolveCommand(registry, tokens.get(1));
                if (s == null) {
                    return Result.fail("Unknown command '" + tokens.get(1) + "' (see sys.help).");
                }
                owner = parsed;
                spec = s;
                args = tokens.subList(2, tokens.size());
            } else {
                return Result.fail("Unknown command '" + tokens.get(0) + "' (see sys.help).");
            }
        }

        // ── Validation ──
        if (spec.requiresOwner() && owner == null) {
            return Result.fail("No target for '" + spec.key() + "'. Address an owner explicitly: "
                    + "<module>.<profile> " + spec.key());
        }
        if (spec.ownerModuleConstraint() != null && owner != null
                && !spec.ownerModuleConstraint().equals(owner.module())) {
            return Result.fail("Command '" + spec.key() + "' requires a '"
                    + spec.ownerModuleConstraint() + "' owner, but got '" + owner.module() + "'.");
        }
        if (owner != null && NodeProfileRepository.isReservedProfileName(owner.profile())) {
            return Result.fail("Reserved profile name '" + owner.profile() + "' cannot be a command target.");
        }
        if (args.size() < spec.minArgs()) {
            return Result.fail("Missing arguments for '" + spec.key() + "'.");
        }
        if (args.size() > spec.maxArgs()) {
            return Result.fail("Too many arguments for '" + spec.key() + "'.");
        }

        LOGGER.info("Routing command '{}' (owner={})", spec.key(), owner);
        CommandResult result = spec.handler().handle(new CommandContext(owner, args, source, confRoot));
        String message = String.join("\n", result.out());
        return result.ok() ? Result.ok(message) : Result.fail(message);
    }

    /**
     * Parses a {@code module.profile} owner token, returning {@code null} if malformed.
     */
    private static OwnerContext parseOwnerSafe(String token) {
        try {
            return OwnerContext.parse(token);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Resolves a command token to a spec: first a direct registry key, then the legacy
     * {@code .verb} forms ({@code .pause} → {@code node.pause}, {@code .help} → {@code sys.help}).
     */
    private static Optional<CommandSpec> resolveCommandSpec(CommandRegistry registry, String token) {
        Optional<CommandSpec> direct = registry.lookup(token);
        if (direct.isPresent()) {
            return direct;
        }
        if (token.startsWith(".")) {
            if (".help".equals(token)) {
                return registry.lookup("sys.help");
            }
            return registry.lookup("node." + token.substring(1));
        }
        return Optional.empty();
    }

    private static CommandSpec resolveCommand(CommandRegistry registry, String token) {
        return resolveCommandSpec(registry, token).orElse(null);
    }
}
