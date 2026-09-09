package application.cli;

/**
 * Declarative description of one command ({@code <domain>.<action>}).
 * <p>
 * This is the unit of the data-driven {@link CommandRegistry}: adding a new command
 * means declaring a {@code CommandSpec} and a {@link CommandHandler} — no grammar
 * change in the router is ever required.
 * </p>
 * <ul>
 *   <li>{@code domain} — the namespace (e.g. {@code node}, {@code logging}, {@code sys}).</li>
 *   <li>{@code action} — the verb within the domain (e.g. {@code pause}, {@code link}, {@code profiles}).</li>
 *   <li>{@code minArgs}/{@code maxArgs} — positional-argument bounds ({@code maxArgs}
 *       of {@link Integer#MAX_VALUE} means "variable").</li>
 *   <li>{@code requiresOwner} — the command must be addressed to an owner.</li>
 *   <li>{@code ownerModuleConstraint} — if set, the owner's module must equal it
 *       (e.g. {@code "node"} for all {@code node.*} commands); {@code null} = any owner.</li>
 * </ul>
 */
public final class CommandSpec {

    private final String domain;
    private final String action;
    private final String description;
    private final int minArgs;
    private final int maxArgs;
    private final boolean requiresOwner;
    private final String ownerModuleConstraint;
    private final CommandHandler handler;

    public CommandSpec(String domain, String action, String description,
                       int minArgs, int maxArgs, boolean requiresOwner,
                       String ownerModuleConstraint, CommandHandler handler) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain must not be null or blank");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be null or blank");
        }
        this.domain = domain;
        this.action = action;
        this.description = description;
        this.minArgs = minArgs;
        this.maxArgs = maxArgs;
        this.requiresOwner = requiresOwner;
        this.ownerModuleConstraint = ownerModuleConstraint;
        this.handler = handler;
    }

    public String domain() {
        return domain;
    }

    public String action() {
        return action;
    }

    public String description() {
        return description;
    }

    public int minArgs() {
        return minArgs;
    }

    public int maxArgs() {
        return maxArgs;
    }

    public boolean requiresOwner() {
        return requiresOwner;
    }

    /** The required owner module, or {@code null} if any owner is accepted. */
    public String ownerModuleConstraint() {
        return ownerModuleConstraint;
    }

    public CommandHandler handler() {
        return handler;
    }

    /** The canonical command key, e.g. {@code "node.pause"}. */
    public String key() {
        return domain + "." + action;
    }

    @Override
    public String toString() {
        return key();
    }
}
