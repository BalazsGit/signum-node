package application.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The data-driven registry of all console commands ({@code <domain>.<action>}).
 * <p>
 * This replaces the hard-coded if-else routing: a command is a {@link CommandSpec}
 * (declared, not coded) wired to a {@link CommandHandler}. The router
 * ({@code application.module.system.CommandRouter}) only parses the input and dispatches
 * to a spec — so adding a new command (e.g. a future {@code pool.*} or
 * {@code database.*} domain) is a one-line {@link #register(CommandSpec)} here with no
 * grammar change anywhere else.
 * </p>
 * <p>
 * Registration order is preserved (so {@code sys.help} prints a stable order) and
 * {@link #lookup(String)} is O(1).
 * </p>
 *
 * @see CommandSpec
 * @see CommandHandler
 */
public final class CommandRegistry {

    private final Map<String, CommandSpec> specs = new LinkedHashMap<>();

    /**
     * Registers a command (idempotent: re-registering the same key replaces it).
     *
     * @param spec the command to register (must not be null)
     * @return this registry (fluent)
     */
    public CommandRegistry register(CommandSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        specs.put(spec.key(), spec);
        return this;
    }

    /**
     * Looks up a command by its canonical key.
     *
     * @param key the command key (e.g. {@code "node.pause"})
     * @return the spec, or empty if not registered
     */
    public Optional<CommandSpec> lookup(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(specs.get(key));
    }

    /** All registered commands in registration order (unmodifiable snapshot). */
    public List<CommandSpec> all() {
        return Collections.unmodifiableList(new ArrayList<>(specs.values()));
    }

    /**
     * All action names — the reserved profile-name space (a node profile should not be
     * named after a command action, which would be ambiguous in the grammar).
     *
     * @return an unmodifiable set of reserved names
     */
    public Set<String> reservedProfileNames() {
        Set<String> reserved = new LinkedHashSet<>();
        for (CommandSpec spec : specs.values()) {
            reserved.add(spec.action());
        }
        return Collections.unmodifiableSet(reserved);
    }

    /**
     * Builds the v1 registry with the three initial domains:
     * <ul>
     *   <li>{@code node.*} — the migrated legacy node verbs (delegated to
     *       {@code Signum.processCommandInstance});</li>
     *   <li>{@code logging.*} — logging-profile management;
     *   <li>{@code sys.*} — system-level commands ({@code profiles}, {@code help}).</li>
     * </ul>
     *
     * @return a fully-populated registry
     */
    public static CommandRegistry defaultRegistry() {
        CommandRegistry reg = new CommandRegistry();

        // ── node.* (migrated legacy verbs; owner module must be "node") ──
        reg.register(new CommandSpec("node", "pause", "Pauses blockchain synchronization",
                0, 0, true, "node", new NodeCommandHandler("pause")));
        reg.register(new CommandSpec("node", "resume", "Resumes blockchain synchronization",
                0, 0, true, "node", new NodeCommandHandler("resume")));
        reg.register(new CommandSpec("node", "shutdown", "Gracefully shuts down the node",
                0, 0, true, "node", new NodeCommandHandler("shutdown")));
        reg.register(new CommandSpec("node", "restart", "Restarts the node application",
                0, 0, true, "node", new NodeCommandHandler("restart")));
        reg.register(new CommandSpec("node", "autoresolve", "Triggers manual database consistency resolution",
                0, 0, true, "node", new NodeCommandHandler("autoresolve")));
        reg.register(new CommandSpec("node", "trim", "Schedules a database trim",
                0, 0, true, "node", new NodeCommandHandler("trim")));
        reg.register(new CommandSpec("node", "dbcheck", "Performs a database consistency check",
                0, 0, true, "node", new NodeCommandHandler("dbcheck")));
        reg.register(new CommandSpec("node", "popoff", "Pops off the last N blocks (e.g. node.popoff 20)",
                1, 1, true, "node", new NodeCommandHandler("popoff")));

        // ── logging.* (logging-profile management; owner = a node profile) ──
        reg.register(new CommandSpec("logging", "link", "Links a logging profile to the owner",
                1, 1, true, null, new LoggingCommandsHandler("link")));
        reg.register(new CommandSpec("logging", "unlink", "Unlinks the owner's logging profile",
                0, 0, true, null, new LoggingCommandsHandler("unlink")));
        reg.register(new CommandSpec("logging", "show", "Shows the owner's logging assignment",
                0, 0, true, null, new LoggingCommandsHandler("show")));
        reg.register(new CommandSpec("logging", "list", "Lists a module's logging profiles",
                0, 1, false, null, new LoggingCommandsHandler("list")));

        // ── sys.* (system-level, owner-agnostic) ──
        reg.register(new CommandSpec("sys", "profiles", "Lists a module's node-profiles",
                0, 1, false, null, new SystemCommandsHandler("profiles", reg)));
        reg.register(new CommandSpec("sys", "help", "Shows this command reference",
                0, 0, false, null, new SystemCommandsHandler("help", reg)));

        return reg;
    }
}
