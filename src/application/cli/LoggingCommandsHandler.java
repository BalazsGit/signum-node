package application.cli;

import application.module.logging.LoggingAssignmentStore;
import application.module.logging.LoggingProfileRepository;
import application.module.logging.NodeLoggingApplier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handler for the {@code logging.*} domain — logging-profile management.
 * <p>
 * Action-bound: a single instance is registered per command ({@code logging.link},
 * {@code logging.unlink}, {@code logging.show}, {@code logging.list}). All operations are
 * headless-safe and drive the existing, already-tested logging backend
 * ({@link LoggingAssignmentStore} / {@link LoggingProfileRepository}); no new storage logic.
 * </p>
 * <p>
 * {@code link}/{@code unlink}/{@code show} are addressed to an owner (a node profile) and
 * operate on the owner's module. {@code list} lists a module's logging profiles (module
 * from the arg, or the owner's module). After a link/unlink, {@link NodeLoggingApplier}
 * re-applies the effective level if the node profile is running (defensive no-op otherwise).
 * </p>
 */
public final class LoggingCommandsHandler implements CommandHandler {

    private final String action;

    public LoggingCommandsHandler(String action) {
        this.action = action;
    }

    @Override
    public CommandResult handle(CommandContext ctx) {
        OwnerContext owner = ctx.owner();
        switch (action) {
            case "link":
                return link(ctx, owner);
            case "unlink":
                return unlink(ctx, owner);
            case "show":
                return show(ctx, owner);
            case "list":
                return list(ctx, owner);
            default:
                return CommandResult.fail("Unknown logging action '" + action + "'.");
        }
    }

    private CommandResult link(CommandContext ctx, OwnerContext owner) {
        if (owner == null) {
            return CommandResult.fail("logging.link requires an owner: node.<profile> logging.link <profile>");
        }
        if (ctx.args().isEmpty()) {
            return CommandResult.fail("Usage: logging.link <logging-profile>");
        }
        String profileName = ctx.args().get(0);
        LoggingProfileRepository repo = new LoggingProfileRepository(ctx.confRoot());
        if (!repo.hasProfile(owner.module(), profileName)) {
            return CommandResult.fail("Unknown logging profile '" + profileName + "' for module '" + owner.module() + "'. "
                    + "See: " + owner + " logging.list");
        }
        LoggingAssignmentStore store = new LoggingAssignmentStore(ctx.confRoot());
        store.setAssignmentForModule(owner.profile(), owner.module(), profileName);
        NodeLoggingApplier.applyForNodeProfile(ctx.confRoot(), owner.profile(), owner.module());
        return CommandResult.ok(List.of("Linked logging profile '" + profileName + "' to '" + owner
                + "' (module '" + owner.module() + "')"));
    }

    private CommandResult unlink(CommandContext ctx, OwnerContext owner) {
        if (owner == null) {
            return CommandResult.fail("logging.unlink requires an owner: node.<profile> logging.unlink");
        }
        LoggingAssignmentStore store = new LoggingAssignmentStore(ctx.confRoot());
        store.setAssignmentForModule(owner.profile(), owner.module(), null);
        NodeLoggingApplier.applyForNodeProfile(ctx.confRoot(), owner.profile(), owner.module());
        return CommandResult.ok(List.of("Unlinked module '" + owner.module() + "' from '" + owner
                + "' (effective level reverts to default)"));
    }

    private CommandResult show(CommandContext ctx, OwnerContext owner) {
        if (owner == null) {
            return CommandResult.fail("logging.show requires an owner: node.<profile> logging.show");
        }
        LoggingAssignmentStore store = new LoggingAssignmentStore(ctx.confRoot());
        String assigned = store.getAssignment(owner.profile()).get(owner.module());
        String effective = store.resolveEffectiveForModule(owner.profile(), owner.module());
        List<String> out = new ArrayList<>();
        out.add("owner    : " + owner);
        out.add("module   : " + owner.module());
        out.add("assigned : " + (assigned != null ? assigned : "(none)"));
        out.add("effective: " + (effective != null ? effective : "(default)"));
        return CommandResult.ok(out);
    }

    private CommandResult list(CommandContext ctx, OwnerContext owner) {
        String module = !ctx.args().isEmpty() ? ctx.args().get(0)
                : (owner != null ? owner.module() : null);
        if (module == null) {
            return CommandResult.fail("Usage: logging.list [module]");
        }
        LoggingProfileRepository repo = new LoggingProfileRepository(ctx.confRoot());
        List<String> profiles = repo.listProfiles(module);
        String assigned = (owner != null)
                ? new LoggingAssignmentStore(ctx.confRoot()).getAssignment(owner.profile()).get(module)
                : null;
        List<String> out = new ArrayList<>();
        out.add("Logging profiles for module '" + module + "':");
        if (profiles.isEmpty()) {
            out.add("  (none)");
        } else {
            for (String p : profiles) {
                out.add("  " + (p.equals(assigned) ? "* " : "  ") + p);
            }
        }
        return CommandResult.ok(out);
    }
}
