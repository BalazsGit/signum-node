package application.launcher;

import application.kernel.ApplicationKernel;
import application.module.browser.core.JcefProcessBootstrap;
import application.module.node.profile.NodeProfileRepository;
import application.module.node.profile.ProfileCli;
import application.module.node.profile.ProfileConfig;
import application.module.node.util.LoggerConfigurator;
import application.utils.io.PathUtils;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.net.ServerSocket;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.Scanner;

public class Launcher {

    private static Logger logger;

    // Globális CLI opciók definíciója
    private static final Options BASE_OPTIONS = new Options()
            .addOption("c", "config", true, "Configuration folder")
            .addOption("h", "help", false, "Print help")
            .addOption("l", "headless", false, "Run in headless mode")
            .addOption("o", "order", true, "Set the node start/tab order (comma-separated profile names) and exit");

    static {
        // 0. Pre-emptively set the LogManager before JUL is ever touched.
        // This helps avoid IllegalAccessException on modern JREs (17/21+)
        // when the LogManager tries to initialize via LoggerFactory calls.
        if (System.getProperty("java.util.logging.manager") == null) {
            System.setProperty("java.util.logging.manager", "application.module.node.util.SignumLogManager");
        }
        // Note (JCEF/browser module): no early setup is needed here. The JCEF
        // process bootstrap (JcefProcessBootstrap) runs at the very start of
        // main() — before any application logic — and installs the native
        // library loader before the first org.cef.* use.
    }

    /**
     * The main entry point for the application.
     * Determines whether to launch in GUI or Headless mode based on arguments and
     * environment.
     *
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        String confFolder = "conf"; // Default
        boolean headless = false;
        String orderArg = null;
        String runTarget = null; // set when "profile run <name>" is requested

        try {
            CommandLine cmd = new DefaultParser().parse(BASE_OPTIONS, args, true);
            if (cmd.hasOption("c")) {
                confFolder = cmd.getOptionValue("c");
            }
            if (cmd.hasOption("l") || GraphicsEnvironment.isHeadless()) {
                headless = true;
            }
            if (cmd.hasOption("o")) {
                orderArg = cmd.getOptionValue("o");
            }
            // ── profile subcommand (Phase 0 + Phase 3) ──
            int profileCode = ProfileCli.tryHandleProfileCommand(cmd.getArgs());
            if (profileCode == ProfileCli.RUN_REQUESTED) {
                // "profile run <name>": boot the node for that profile (headless). Handled
                // after logging init below; the node's non-daemon threads keep the JVM alive.
                runTarget = ProfileCli.getRunTarget(cmd.getArgs());
            } else if (profileCode != ProfileCli.NOT_A_PROFILE_COMMAND) {
                System.exit(profileCode);
            }
        } catch (ParseException e) {
            System.err.println("Error parsing early arguments: " + e.getMessage());
        }

        Path confPath = PathUtils.resolvePath(confFolder);

        // Install the GUI log bridge as EARLY as possible (before the JCEF
        // bootstrap and the logging reconfiguration). The SystemLogger keeps a
        // bounded ring buffer, so the startup lines logged before the GUI (and
        // before the logging configuration) are replayed to the System Console
        // when the tab attaches. Idempotent; SignumLogManager re-installs the
        // bridge after readConfiguration() resets the root logger's handlers.
        application.utils.logging.SystemLoggerJulHandler.install();

        // ── JCEF process bootstrap (must precede ALL application logic) ──
        // JCEF renderer/utility/GPU processes re-run this main() with an extra
        // CEF --type= switch; the subprocess must not initialize logging, the
        // kernel or any module — it goes straight into the CEF process loop and
        // exits through CEF. In the browser process this pre-initializes the
        // engine (fast) so the custom-scheme handler is installed before the
        // first CEF subprocess launches.
        JcefProcessBootstrap.bootstrap(args, confPath, headless);

        // Capture how this JVM was launched so a restart (Restart button,
        // .restart, requestRestart) can relaunch the application with a verified
        // command line. Only the main process reaches this point: a JCEF
        // subprocess has already terminated inside bootstrap().
        LaunchCommand.capture(args);

        // Logging inicializálás (Ez maradhat a Launcher-ben mint infra).
        // (The SystemLoggerJulHandler bridge was already installed above; the
        // reconfiguration re-applies it internally via SignumLogManager.)
        List<String> initLogs = new ArrayList<>();
        try {
            initLogs = LoggerConfigurator.init(confFolder);
        } catch (Exception e) {
            System.err.println("Failed to initialize LoggerConfigurator: " + e.getMessage());
        }

        logger = LoggerFactory.getLogger(Launcher.class);
        // Print bootstrap logs to console AND the System Console (through the
        // log bridge installed above), so the GUI shows the same lines as the
        // terminal.
        initLogs.forEach(msg -> logger.info("[Bootstrap] {}", msg));

        // Headless "set node order" command: define the node start/tab order and exit.
        // In headless mode there is no GUI to drag tabs, so this is how the
        // autostart/arbitration order is set from the command line.
        if (orderArg != null) {
            System.exit(applyNodeOrder(orderArg));
        }

        // ── profile run <name> (Phase 3): boot a single profile headless ──
        if (runTarget != null) {
            logger.info("Starting profile '{}' (headless, profile run)...", runTarget);
            System.out.println("Starting profile '" + runTarget + "' (headless). Ctrl+C to stop.");
            new ApplicationKernel(true, confPath, runTarget).boot();
            return; // JVM stays alive via the node's threads; graceful shutdown on Ctrl+C
        }

        // Kernel indítása
        ApplicationKernel kernel = new ApplicationKernel(headless, confPath);
        kernel.boot();
    }

    /**
     * Applies (persists) the node start/tab order from a comma-separated list of
     * profile names, validates them against the discovered profiles, and returns a
     * process exit code (0 = success, non-zero = error).
     * <p>
     * The order is stored in {@code conf/node/profiles.json} — the same source the
     * GUI tab order and the {@code NodeModule} autostart arbitration read — so it
     * takes effect both for the GUI tab display and for the autostart order.
     *
     * @param csv comma-separated profile names (in the desired order)
     * @return 0 on success, non-zero on error
     */
    private static int applyNodeOrder(String csv) {
        List<String> order = new ArrayList<>();
        for (String part : csv.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                order.add(name);
            }
        }
        if (order.isEmpty()) {
            System.err.println("Error: --order requires at least one profile name (comma-separated).");
            return 2;
        }
        List<String> discovered = NodeProfileRepository.discoverProfileNames();
        List<String> unknown = new ArrayList<>();
        for (String name : order) {
            if (!discovered.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            System.err.println("Error: unknown profile name(s): " + unknown);
            System.err.println("Discovered profiles: " + discovered);
            return 2;
        }
        new ProfileConfig().setTabOrder(order);
        System.out.println("Node start order set to: " + order);
        System.out.println("(applied to both the GUI tab order and the autostart/arbitration order)");
        return 0;
    }

    /**
     * Restarts the application by spawning a new process and exiting the current
     * one.
     * This ensures a full reload of the Node and GUI components.
     * <p>
     * The command line comes from {@link LaunchCommand} (exact launch command
     * captured at startup, with a classpath-based fallback), so a restart works
     * both from a fat jar and from a class directory (IDE/Gradle) and never
     * fails silently: if the replacement cannot be started, the failure is
     * reported to both the log and the console.
     */
    public static void restart() {
        logger.info("Initiating application restart...");
        try {
            // Spawn the replacement first: if this fails, nothing below runs and
            // the current process keeps running (no "restart" that is a plain
            // shutdown).
            Process child = LaunchCommand.startNewInstance();
            logger.info("New process spawned (pid {}), exiting current process...", child.pid());

            // Close stdin to stop the current process from stealing input from the new one
            try {
                System.in.close();
            } catch (Exception e) {
                // ignore
            }

            // Ensure process termination if System.exit hangs (fixes console input
            // contention)
            new Timer("Shutdown-Watchdog", true).schedule(new TimerTask() {
                @Override
                public void run() {
                    Runtime.getRuntime().halt(0);
                }
            }, 5000);

            // Terminate the current process
            System.exit(0);

        } catch (Exception e) {
            logger.error("Failed to restart application: {}", e.getMessage(), e);
            System.err.println("Failed to restart application: " + e.getMessage());
        }
    }

}
