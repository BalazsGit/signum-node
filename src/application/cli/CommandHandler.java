package application.cli;

/**
 * Executes a single registered command.
 * <p>
 * A handler is bound to one {@code domain.action} (see {@link CommandSpec}); it is
 * a pure function of the {@link CommandContext} and returns a {@link CommandResult}.
 * Handlers must be headless-safe (no Swing) so the same command can run from the
 * console tabs, the profile console, or the CLI.
 * </p>
 */
public interface CommandHandler {

    /**
     * Executes the command.
     *
     * @param ctx the resolved command context
     * @return the outcome (never {@code null})
     */
    CommandResult handle(CommandContext ctx);
}
