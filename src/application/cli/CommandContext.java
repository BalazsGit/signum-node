package application.cli;

import java.util.List;

/**
 * Everything a {@link CommandHandler} needs to execute a command.
 * <p>
 * Carries the resolved {@link OwnerContext} (or {@code null} for a global command),
 * the positional {@code args} (tokens after the command key), the {@link CommandSource}
 * and the base configuration root (so headless tests can point it at a temporary
 * {@code conf/} tree).
 * </p>
 */
public final class CommandContext {

    private final OwnerContext owner;
    private final List<String> args;
    private final CommandSource source;
    private final String confRoot;

    public CommandContext(OwnerContext owner, List<String> args, CommandSource source, String confRoot) {
        this.owner = owner;
        this.args = args == null ? List.of() : List.copyOf(args);
        this.source = source == null ? CommandSource.CONSOLE_SYSTEM : source;
        this.confRoot = confRoot;
    }

    /** The command owner, or {@code null} for a global command. */
    public OwnerContext owner() {
        return owner;
    }

    /** Positional arguments following the command key (never null). */
    public List<String> args() {
        return args;
    }

    public CommandSource source() {
        return source;
    }

    /** Base configuration root (e.g. {@code ./conf}). */
    public String confRoot() {
        return confRoot;
    }
}
