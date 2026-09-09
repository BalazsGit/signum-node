package application.cli;

import application.utils.config.ModuleIds;
import application.utils.config.PropertiesProfileLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Handler for the {@code sys.*} domain — system-level, module-agnostic commands.
 * <p>
 * Action-bound: {@code sys.profiles} lists a module's node-profiles (discovery via
 * {@link PropertiesProfileLoader#discoverProfiles}, default module {@code node});
 * {@code sys.help} renders the full command list straight from the {@link CommandRegistry},
 * so it is always data-driven — adding a command automatically appears in the help.
 * </p>
 */
public final class SystemCommandsHandler implements CommandHandler {

    /** Reserved node-profile names excluded from {@code sys.profiles} (matches the SSOT loader). */
    private static final Set<String> RESERVED_NODE_PROFILES = Set.of("node-default", "node");

    private final String action;
    private final CommandRegistry registry;

    public SystemCommandsHandler(String action, CommandRegistry registry) {
        this.action = action;
        this.registry = registry;
    }

    @Override
    public CommandResult handle(CommandContext ctx) {
        switch (action) {
            case "profiles":
                return profiles(ctx);
            case "help":
                return help();
            default:
                return CommandResult.fail("Unknown system action '" + action + "'.");
        }
    }

    private CommandResult profiles(CommandContext ctx) {
        String module = !ctx.args().isEmpty() ? ctx.args().get(0) : ModuleIds.NODE;
        List<String> profiles = PropertiesProfileLoader.discoverProfiles(
                ctx.confRoot(), module, ModuleIds.CATEGORY_PROFILES, RESERVED_NODE_PROFILES);
        List<String> out = new ArrayList<>();
        out.add("Profiles for module '" + module + "':");
        if (profiles.isEmpty()) {
            out.add("  (none)");
        } else {
            for (String p : profiles) {
                out.add("  " + p);
            }
        }
        return CommandResult.ok(out);
    }

    private CommandResult help() {
        List<String> out = new ArrayList<>();
        out.add("Command syntax:  [owner] <domain>.<action> [args]     (owner = <module>.<profile>)");
        out.add("");
        if (registry != null) {
            for (CommandSpec spec : registry.all()) {
                String argHint = spec.minArgs() > 0 ? " <args>" : (spec.maxArgs() > 0 ? " [args]" : "");
                String desc = spec.description() != null ? "  — " + spec.description() : "";
                out.add("  " + spec.key() + argHint + desc);
                if (spec.requiresOwner()) {
                    out.add("                     (owner required)");
                }
            }
        }
        return CommandResult.ok(out);
    }
}
