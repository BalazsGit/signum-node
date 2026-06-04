package signum;

import application.module.brs.Signum;
import application.module.brs.props.Props;
import application.module.brs.util.LoggerConfigurator;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import application.module.brs.util.PathUtils;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.net.ServerSocket;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

public class Launcher {

    private static String[] savedArgs;
    private static Logger logger;

    static {
        // 0. Pre-emptively set the LogManager before JUL is ever touched.
        // This helps avoid IllegalAccessException on modern JREs (17/21+)
        // when the LogManager tries to initialize via LoggerFactory calls.
        if (System.getProperty("java.util.logging.manager") == null) {
            System.setProperty("java.util.logging.manager", "brs.util.SignumLogManager");
        }
    }

    /**
     * The main entry point for the application.
     * Determines whether to launch in GUI or Headless mode based on arguments and
     * environment.
     *
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        savedArgs = args;

        // 1. Determine configuration folder early
        String confFolder = Signum.CONF_FOLDER;
        try {
            CommandLine cmd = new DefaultParser().parse(Signum.CLI_OPTIONS, args, true);
            if (cmd.hasOption(Signum.CONF_FOLDER_OPTION.getOpt())) {
                confFolder = cmd.getOptionValue(Signum.CONF_FOLDER_OPTION.getOpt());
            }
        } catch (ParseException e) {
            System.err.println("Error parsing early arguments: " + e.getMessage());
        }

        // 2. Resolve paths and detect profiles before SLF4J loads
        Path confPath = PathUtils.resolvePath(confFolder);
        Path nodePath = confPath.resolve(Signum.NODE_SUBFOLDER);
        Path loggingPath = confPath.resolve(Signum.NODE_LOGGING_SUBFOLDER);

        detectProfiles(nodePath, loggingPath);

        // 5. Activate Logging system
        List<String> initLogs = null;
        try {
            initLogs = LoggerConfigurator.init(confFolder);
        } catch (Exception e) {
            System.err.println("Failed to initialize LoggerConfigurator: " + e.getMessage());
        }

        // Initialize loggers immediately after configuration is applied
        logger = LoggerFactory.getLogger(Launcher.class);
        Logger bootLogger = LoggerFactory.getLogger(LoggerConfigurator.class);
        Signum.setLogger(LoggerFactory.getLogger(Signum.class));

        if (initLogs != null) {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            for (String message : initLogs) {
                String content = message;
                String level = "INFO";

                // Parse level markers for proper logger routing
                if (message.startsWith("SEVERE:")) {
                    content = message.substring(7).trim();
                    level = "ERROR";
                    bootLogger.error(content);
                } else if (message.startsWith("WARNING:")) {
                    content = message.substring(8).trim();
                    level = "WARN";
                    bootLogger.warn(content);
                } else if (message.startsWith("INFO:")) {
                    content = message.substring(5).trim();
                    bootLogger.info(content);
                } else {
                    bootLogger.info(content);
                }
                // Add formatted string to GUI buffer to ensure uniform appearance in console
                Signum.BOOTSTRAP_LOGS
                        .add(String.format("[%s] %s application.module.brs.util.LoggerConfigurator - %s", level, ts,
                                content));
            }
        }

        boolean canRunGui = true;
        try {
            CommandLine cmd = new DefaultParser().parse(Signum.CLI_OPTIONS, args);
            if (cmd.hasOption("h")) {
                printHelp();
                return;
            }
            if (cmd.hasOption("l")) {
                logger.info("Running in headless mode as specified by argument");
                canRunGui = false;
            }
        } catch (ParseException e) {
            logger.error("Error parsing arguments: {}", e.getMessage());
            printHelp();
            System.exit(1);
        }

        // Check if the environment supports a GUI (e.g., not a server without display)
        if (canRunGui && GraphicsEnvironment.isHeadless()) {
            logger.error("Cannot start GUI as running in headless environment");
            canRunGui = false;
        }

        if (canRunGui) {
            launchGui(args);
        } else {
            Signum.main(args);
        }
    }

    /**
     * Detects applied configuration profiles from metadata before initializing
     * logging or properties.
     */
    private static void detectProfiles(Path nodePath, Path loggingPath) {
        try {
            Path nodeProfilePath = nodePath.resolve("profile.json");
            if (Files.exists(nodeProfilePath)) {
                try (BufferedReader reader = Files.newBufferedReader(nodeProfilePath, StandardCharsets.UTF_8)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("appliedProfile")) {
                        Signum.setActiveNodeProfile(settings.get("appliedProfile").getAsString().trim());
                    }
                }
            }

            Path loggingProfilePath = loggingPath.resolve("profile.json");
            if (Files.exists(loggingProfilePath)) {
                try (BufferedReader reader = Files.newBufferedReader(loggingProfilePath, StandardCharsets.UTF_8)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("appliedProfile")) {
                        Signum.setActiveLoggingProfile(settings.get("appliedProfile").getAsString().trim());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: Error detecting applied profiles: " + e.getMessage());
        }
    }

    /**
     * Prints the help message to the console.
     */
    private static void printHelp() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("java -jar signum-node.jar", "Signum Node version " + Signum.VERSION,
                Signum.CLI_OPTIONS,
                "Check for updates at https://github.com/signum-network/signum-node", true);
    }

    /**
     * Attempts to launch the GUI version of the node.
     * Falls back to headless mode if the GUI class is not found or cannot be
     * loaded.
     *
     * @param args Command line arguments to pass to the GUI
     */
    private static void launchGui(String[] args) {
        try {
            // Use reflection to load SignumGUI to avoid hard dependency if the GUI module
            // is missing
            Class.forName("brs.gui.SignumGUI")
                    .getDeclaredMethod("main", String[].class)
                    .invoke(null, (Object) args);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                | InvocationTargetException e) {
            logger.warn(
                    "Your build does not seem to include the SignumGUI extension or it cannot be run. Running as headless...",
                    e);
            Signum.main(args);
        }
    }

    /**
     * Restarts the application by spawning a new process and exiting the current
     * one.
     * This ensures a full reload of the Node and GUI components.
     */
    public static void restart() {
        logger.info("Initiating application restart...");

        // 1. Graceful shutdown of the current node instance to release ports and DB
        // locks
        Signum.shutdown(false);

        // Wait for resources to be fully released (OS/DB) to prevent "Stopped" state in
        // new process
        waitForResources();

        try {
            // 2. Reconstruct the command line to start a new process
            String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            File currentJar = new File(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());

            if (!currentJar.getName().endsWith(".jar")) {
                logger.warn("Restart is only supported when running from a JAR file.");
                return;
            }

            List<String> command = new ArrayList<>();
            command.add(javaBin);

            // Add VM arguments (like -Xmx, -Dproperties, etc.)
            command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());

            command.add("-jar");
            command.add(currentJar.getPath());

            // Add original application arguments
            if (savedArgs != null) {
                for (String arg : savedArgs) {
                    command.add(arg);
                }
            }

            // 3. Spawn the new process
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.inheritIO(); // Share the console output
            builder.start();

            logger.info("New process spawned. Exiting current process...");

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

            // 4. Terminate the current process
            System.exit(0);

        } catch (Exception e) {
            logger.error("Failed to restart application", e);
        }
    }

    /**
     * Waits for critical resources (like the API port) to be released by the OS.
     * This prevents the new process from failing to bind to the port during
     * startup.
     */
    private static void waitForResources() {
        int port = 0;
        try {
            port = Signum.getPropertyService().getInt(Props.API_PORT);
        } catch (Exception e) {
            logger.warn("Could not determine API port, falling back to fixed wait.");
            try {
                TimeUnit.SECONDS.sleep(3);
            } catch (InterruptedException ie) {
                logger.warn("Restart sleep interrupted", ie);
            }
            return;
        }

        long deadline = System.currentTimeMillis() + 10000; // 10 seconds timeout
        while (System.currentTimeMillis() < deadline) {
            try (ServerSocket ss = new ServerSocket(port)) {
                ss.setReuseAddress(true);
                return; // Port is free, proceed immediately
            } catch (IOException e) {
                // Port taken, wait a bit
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        logger.warn("Port {} was not released in time, proceeding with restart...", port);
    }
}
