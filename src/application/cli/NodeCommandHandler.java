package application.cli;

import application.module.node.NodeModule;
import application.module.node.Signum;

import java.util.List;

/**
 * Handler for the {@code node.*} domain — the migrated legacy node verbs.
 * <p>
 * A single action-bound instance is registered per command (one for {@code node.pause},
 * one for {@code node.resume}, …). It resolves the owner's node instance from the
 * {@link NodeModule} registry (the sole composition root) and delegates to the
 * untouched {@link Signum#processCommandInstance(String)} logic — the legacy verb is
 * reconstructed as {@code .<action>} plus the positional args (so {@code node.popoff 20}
 * becomes {@code .popoff 20}).
 * </p>
 * <p>
 * This is the "seam" that keeps the legacy {@code .verb} implementation working under
 * the new {@code node.<verb>} address — back-compat and the new grammar both land here.
 * </p>
 */
public final class NodeCommandHandler implements CommandHandler {

    private final String action;

    public NodeCommandHandler(String action) {
        this.action = action;
    }

    @Override
    public CommandResult handle(CommandContext ctx) {
        OwnerContext owner = ctx.owner();
        if (owner == null) {
            return CommandResult.fail("node." + action + " requires an owner (node profile). "
                    + "Address it as: node.<profile> node." + action);
        }
        Signum signum = NodeModule.getInstance().get(owner.profile());
        if (signum == null) {
            return CommandResult.fail("Profile '" + owner.profile()
                    + "' has no registered node instance. Start it first.");
        }
        String legacy = "." + action;
        if (!ctx.args().isEmpty()) {
            legacy += " " + String.join(" ", ctx.args());
        }
        signum.processCommandInstance(legacy);
        return CommandResult.ok(List.of("OK: node." + action + " on '" + owner.profile() + "'"));
    }
}
