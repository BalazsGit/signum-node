package signum;

import brs.Signum;
import brs.props.Props;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

public class Launcher {

    private static final Logger logger = LoggerFactory.getLogger(Launcher.class);
    private static String[] savedArgs;

    /**
     * The main entry point for the application.
     * Determines whether to launch in GUI or Headless mode based on arguments and
     * environment.
     *
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        savedArgs = args;
        boolean canRunGui = true;

        try {
            // Parse command line arguments to check for help or headless mode
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
            logger.error("Error parsing arguments", e);
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
