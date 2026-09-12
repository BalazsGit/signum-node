package application.module.node.profile;

import application.module.node.props.Prop;
import application.module.node.props.Props;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Headless CLI for node profile management: {@code create / rename / delete / list / run / info}.
 * <p>
 * Swing-free. Delegates persistence to {@link NodeProfileRepository} (SSOT) and name
 * generation to {@link ProfileNameSuggester}. The {@code profile run} command validates the
 * target profile; the actual kernel boot + {@code NodeModule.startNode(name)} is performed by
 * the {@code Launcher}/{@code ApplicationKernel} flow (Phase 3 integration).
 * </p>
 * <p>
 * The {@code create} command maps CLI flags to the core {@link Props} keys:
 * {@code --network} → {@code node.network}, {@code --api-port} → {@code API.Port},
 * {@code --p2p-port} → {@code P2P.Port}, {@code --ws-port} → {@code API.WebSocketPort}.
 * When the user does not set a database URL, {@code create} writes a <b>per-profile</b>
 * SQLite {@code DB.Url} ({@code ./database/SQLite/<name>/signum.sqlite.db}) so that the new
 * profile is independently runnable out of the box (plan §2.6 isolation contract); server-DB
 * provisioning (MariaDB/PostgreSQL install and configuration) remains the database module's
 * responsibility.
 * </p>
 */
public final class ProfileCli {

    /** Returned by {@link #tryHandleProfileCommand} when the args are not a profile command. */
    public static final int NOT_A_PROFILE_COMMAND = -2;
    /** Exit code for success. */
    public static final int SUCCESS = 0;
    /** Exit code for an error. */
    public static final int ERROR = 1;
    /**
     * Special "exit code" returned by {@link #tryHandleProfileCommand} when a
     * {@code profile run <name>} command is detected and the target profile exists.
     * The {@code Launcher} treats this as a signal to boot the node for that profile
     * (rather than exiting) — see the plan §2.4 (Fázis 3).
     */
    public static final int RUN_REQUESTED = -3;

    private ProfileCli() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Detects and handles a {@code profile <command>} invocation among the positional args.
     *
     * @param positional the non-option args (from {@code CommandLine.getArgs()})
     * @return {@link #NOT_A_PROFILE_COMMAND} if the args are not a profile command,
     *         otherwise the exit code (0 = success, 1 = error)
     */
    public static int tryHandleProfileCommand(String[] positional) {
        return tryHandleProfileCommand(positional, NodeProfile.CONF_ROOT);
    }

    /**
     * Dispatches a {@code profile <command>} invocation, operating on an explicit conf root.
     * <p>Package-private so that unit tests can run the CLI against a sandbox directory.</p>
     */
    static int tryHandleProfileCommand(String[] positional, String confRoot) {
        if (positional == null) {
            return NOT_A_PROFILE_COMMAND;
        }
        int idx = indexOf(positional, "profile");
        if (idx < 0 || idx + 1 >= positional.length) {
            return NOT_A_PROFILE_COMMAND;
        }
        String command = positional[idx + 1];
        String[] rest = Arrays.copyOfRange(positional, idx + 2, positional.length);
        try {
            switch (command) {
                case "create": return doCreate(confRoot, rest);
                case "rename": return doRename(confRoot, rest);
                case "delete": return doDelete(confRoot, rest);
                case "list":   return doList(confRoot);
                case "info":   return doInfo(confRoot, rest);
                case "run":    return doRun(confRoot, rest);
                default:
                    System.err.println("Unknown profile command: " + command);
                    printUsage();
                    return ERROR;
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return ERROR;
        }
    }

    /**
     * Returns the target profile name if {@code positional} is a {@code profile run <name>}
     * invocation, otherwise {@code null}. Used by the {@code Launcher} to boot the node for
     * the target profile after {@link #tryHandleProfileCommand} returns {@link #RUN_REQUESTED}.
     *
     * @param positional the non-option args (from {@code CommandLine.getArgs()})
     * @return the target profile name, or null if not a valid {@code profile run <name>}
     */
    public static String getRunTarget(String[] positional) {
        if (positional == null) {
            return null;
        }
        int idx = indexOf(positional, "profile");
        if (idx < 0 || idx + 2 >= positional.length) {
            return null;
        }
        return "run".equals(positional[idx + 1]) ? positional[idx + 2] : null;
    }

    private static int indexOf(String[] arr, String target) {
        for (int i = 0; i < arr.length; i++) {
            if (target.equals(arr[i])) {
                return i;
            }
        }
        return -1;
    }

    private static int doCreate(String confRoot, String[] args) {
        if (args.length == 0) {
            printCreateUsage();
            return ERROR;
        }
        String name = args[0];
        Properties props = new Properties();
        for (int i = 1; i < args.length; i++) {
            String opt = args[i];
            if (!isCreateOption(opt)) {
                System.err.println("Unknown option for 'profile create': " + opt);
                return ERROR;
            }
            if (i + 1 >= args.length) {
                System.err.println("Option '" + opt + "' requires a value");
                return ERROR;
            }
            applyCreateOption(props, opt, args[i + 1]);
            i++;
        }

        // ── Independent-by-default (plan §2.6) — mapping SSOT: ProfileCreateDefaults ──
        // A profile's default DB and default ports are SHARED across all profiles, so two fresh
        // profiles would collide (and the second would be rejected at start). For every resource
        // the user did NOT set explicitly, assign a per-profile unique value so profiles are
        // fully independent immediately.
        if (!props.containsKey(Props.DB_URL.getName())) {
            ProfileCreateDefaults.applySqliteDatabase(props, name);
        }
        ProfileCreateDefaults.applyPorts(props, name);

        try {
            NodeProfile profile = NodeProfileRepository.createDefaultProfile(confRoot, name, props);
            System.out.println("Created profile '" + profile.getName() + "' ("
                    + profile.getProperties().size() + " properties)");
            System.out.println("  DB:    " + props.getProperty(Props.DB_URL.getName()));
            System.out.println("  Ports: API=" + props.getProperty(Props.API_PORT.getName())
                    + "  P2P=" + props.getProperty(Props.P2P_PORT.getName())
                    + "  WS=" + props.getProperty(Props.API_WEBSOCKET_PORT.getName())
                    + "   (override with --api-port/--p2p-port/--ws-port)");
            return SUCCESS;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return ERROR;
        }
    }

    private static boolean isCreateOption(String opt) {
        return "--network".equals(opt) || "--api-port".equals(opt)
                || "--p2p-port".equals(opt) || "--ws-port".equals(opt);
    }

    private static void applyCreateOption(Properties props, String opt, String value) {
        switch (opt) {
            case "--network":  props.setProperty(Props.NETWORK_PARAMETERS.getName(), value); break;
            case "--api-port": props.setProperty(Props.API_PORT.getName(), value); break;
            case "--p2p-port": props.setProperty(Props.P2P_PORT.getName(), value); break;
            case "--ws-port":  props.setProperty(Props.API_WEBSOCKET_PORT.getName(), value); break;
            default: throw new IllegalArgumentException("Unknown option: " + opt);
        }
    }

    private static int doRename(String confRoot, String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: profile rename <oldName> <newName>");
            return ERROR;
        }
        try {
            NodeProfileRepository.renameProfile(confRoot, args[0], args[1]);
            System.out.println("Renamed: " + args[0] + " -> " + args[1]);
            return SUCCESS;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return ERROR;
        }
    }

    private static int doDelete(String confRoot, String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: profile delete <name> [--force]");
            return ERROR;
        }
        String name = args[0];
        boolean force = false;
        for (String a : args) {
            if ("--force".equals(a)) {
                force = true;
            }
        }
        if (!force) {
            System.out.println("Profile '" + name + "' will be permanently deleted."
                    + " Re-run with --force to confirm.");
            return ERROR;
        }
        try {
            NodeProfileRepository.deleteProfile(confRoot, name);
            System.out.println("Deleted profile: " + name);
            return SUCCESS;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return ERROR;
        }
    }

    private static int doList(String confRoot) {
        List<String> names = NodeProfileRepository.discoverProfileNames(confRoot);
        if (names.isEmpty()) {
            System.out.println("No profiles found.");
            return SUCCESS;
        }
        System.out.println("Profiles:");
        for (String n : names) {
            NodeProfile p = NodeProfileRepository.loadProfile(confRoot, n);
            if (p == null) {
                continue;
            }
            String network = p.getProperties().getProperty(Props.NETWORK_PARAMETERS.getName(), "");
            String api = p.getProperties().getProperty(
                    Props.API_PORT.getName(), String.valueOf(Props.API_PORT.getDefaultValue()));
            System.out.printf("  %-20s network=%-12s api=%s%n", n, network, api);
        }
        return SUCCESS;
    }

    private static int doInfo(String confRoot, String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: profile info <name>");
            return ERROR;
        }
        NodeProfile p = NodeProfileRepository.loadProfile(confRoot, args[0]);
        if (p == null) {
            System.err.println("Profile not found: " + args[0]);
            return ERROR;
        }
        System.out.println("Profile:    " + p.getName());
        System.out.println("Network:    " + valueOf(p, Props.NETWORK_PARAMETERS));
        System.out.println("API port:   " + valueOf(p, Props.API_PORT));
        System.out.println("P2P port:   " + valueOf(p, Props.P2P_PORT));
        System.out.println("WS port:    " + valueOf(p, Props.API_WEBSOCKET_PORT));
        System.out.println("Properties: " + p.getProperties().size() + " entries");
        return SUCCESS;
    }

    private static String valueOf(NodeProfile p, Prop<?> prop) {
        String v = p.getProperties().getProperty(prop.getName());
        if (v != null) {
            return v;
        }
        return prop.getDefaultValue() != null ? String.valueOf(prop.getDefaultValue()) : "(unset)";
    }

    private static int doRun(String confRoot, String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: profile run <name>");
            return ERROR;
        }
        String name = args[0];
        NodeProfile p = NodeProfileRepository.loadProfile(confRoot, name);
        if (p == null) {
            System.err.println("Profile not found: " + name);
            return ERROR;
        }
        // Validated. The actual kernel boot + NodeModule.start(target) is performed by the
        // Launcher (which detects RUN_REQUESTED and boots the node for this profile).
        return RUN_REQUESTED;
    }

    private static void printCreateUsage() {
        System.out.println("Usage: profile create <name> [--network X] [--api-port N] [--p2p-port N] [--ws-port N]");
    }

    private static void printUsage() {
        System.out.println("Usage: profile <command> [options]");
        System.out.println("Commands: create, rename, delete, list, run, info");
        printCreateUsage();
        System.out.println("  rename <oldName> <newName>");
        System.out.println("  delete <name> [--force]");
        System.out.println("  run <name>");
        System.out.println("  info <name>");
    }
}