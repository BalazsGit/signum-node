package application.launcher;

import application.kernel.ApplicationKernel;
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
import java.util.Scanner;

public class Launcher {

    private static String[] savedArgs;
    private static Logger logger;

    // Globális CLI opciók definíciója
    private static final Options BASE_OPTIONS = new Options()
            .addOption("c", "config", true, "Configuration folder")
            .addOption("h", "help", false, "Print help")
            .addOption("l", "headless", false, "Run in headless mode");

    static {
        // 0. Pre-emptively set the LogManager before JUL is ever touched.
        // This helps avoid IllegalAccessException on modern JREs (17/21+)
        // when the LogManager tries to initialize via LoggerFactory calls.
        if (System.getProperty("java.util.logging.manager") == null) {
            System.setProperty("java.util.logging.manager", "application.module.node.util.SignumLogManager");
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

        String confFolder = "conf"; // Default
        boolean headless = false;

        try {
            CommandLine cmd = new DefaultParser().parse(BASE_OPTIONS, args, true);
            if (cmd.hasOption("c")) {
                confFolder = cmd.getOptionValue("c");
            }
            if (cmd.hasOption("l") || GraphicsEnvironment.isHeadless()) {
                headless = true;
            }
        } catch (ParseException e) {
            System.err.println("Error parsing early arguments: " + e.getMessage());
        }

        Path confPath = PathUtils.resolvePath(confFolder);

        // Logging inicializálás (Ez maradhat a Launcher-ben mint infra)
        List<String> initLogs = new ArrayList<>();
        try {
            initLogs = LoggerConfigurator.init(confFolder);
        } catch (Exception e) {
            System.err.println("Failed to initialize LoggerConfigurator: " + e.getMessage());
        }

        // Install the JUL handler that bridges SLF4J → SystemLogger + per-node ProfileLogger
        application.utils.logging.SystemLoggerJulHandler.install();

        logger = LoggerFactory.getLogger(Launcher.class);
        // Print bootstrap logs to console
        initLogs.forEach(msg -> System.out.println("[Bootstrap] " + msg));

        // Kernel indítása
        ApplicationKernel kernel = new ApplicationKernel(headless, confPath);
        kernel.boot();
    }

    /**
     * Restarts the application by spawning a new process and exiting the current
     * one.
     * This ensures a full reload of the Node and GUI components.
     */
    public static void restart() {
        logger.info("Initiating application restart...");

        // Ideális esetben itt a Kernel-t kérjük meg a leállásra
        // waitForResources(); // Ez maradhat segédmetódusnak

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

}
