package brs.util;

import brs.Signum;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Handle logging for the Signum node server
 */

public final class LoggerConfigurator {
    // private static final Logger logger =
    // Logger.getLogger(LoggerConfigurator.class.getSimpleName());

    /**
     * No constructor
     */
    private LoggerConfigurator() {
    }

    /**
     * LoggerConfigurator initialization
     *
     * The existing Java logging configuration will be used if the Java logger has
     * already
     * been initialized. Otherwise, we will configure our own log manager and log
     * handlers.
     * The logging-default.properties and logging.properties configuration
     * files will be used from the specified configuration folder. Entries in
     * logging.properties will override entries in
     * logging-default.properties.
     * 
     * @param confFolder The configuration folder path
     */
    public static List<String> init(String confFolder) {
        List<String> logs = new ArrayList<>();
        final String managerPackage = "java.util.logging.manager";

        // Ensure LogManager is initialized. Launcher should have set the property
        // already via static block.
        try {
            LogManager manager = LogManager.getLogManager();
            if (!(manager instanceof SignumLogManager)) {
                logs.add(
                        "WARNING: Custom LogManager (SignumLogManager) could not be loaded. GUI console might not display logs.");
            }
        } catch (Throwable t) {
            logs.add("SEVERE: Critical error during LogManager access: " + t.getMessage());
        }

        if (!Boolean.getBoolean("brs.doNotConfigureLogging")) {
            try {
                Properties loggingProperties = new Properties();

                // 0. Set internal defaults (baseline). These match logging-default.properties
                // This acts like the "Props.java" for logging, providing a fail-safe
                // configuration.
                loggingProperties.setProperty("handlers", "java.util.logging.ConsoleHandler");
                loggingProperties.setProperty(".level", "SEVERE");
                loggingProperties.setProperty("brs.level", "INFO");
                loggingProperties.setProperty("java.util.logging.ConsoleHandler.level", "INFO");
                loggingProperties.setProperty("java.util.logging.ConsoleHandler.formatter",
                        "brs.util.BriefLogFormatter");
                loggingProperties.setProperty("org.eclipse.jetty.level", "OFF");
                loggingProperties.setProperty("javax.servlet.level", "OFF");
                loggingProperties.setProperty("com.zaxxer.hikari.level", "WARNING");
                loggingProperties.setProperty("com.zaxxer.hikari.HikariConfig.level", "INFO");
                loggingProperties.setProperty("sun.rmi.level", "INFO");
                loggingProperties.setProperty("javax.management.level", "INFO");
                loggingProperties.setProperty("brs.db.store.DerivedTableManager.level", "OFF");
                loggingProperties.setProperty("org.jooq.Constants.level", "OFF");

                Path confPath = PathUtils.resolvePath(confFolder);
                Path logConfPath = confPath.resolve(Signum.NODE_LOGGING_SUBFOLDER);

                File fileToLoad = null;
                File propsFile = logConfPath.resolve(Signum.LOGGING_PROPERTIES_NAME).toFile();

                // 1. Priority: Current LOGGING_PROPERTIES_NAME (Profile or logging.properties)
                if (propsFile.exists()) {
                    fileToLoad = propsFile;
                } else {
                    // 2. Fallback to logging.properties if current name was a profile and didn't
                    // exist in logging/
                    if (!Signum.LOGGING_PROPERTIES_NAME.equals("logging.properties")) {
                        File fallbackFile = logConfPath.resolve("logging.properties").toFile();
                        if (fallbackFile.exists()) {
                            fileToLoad = fallbackFile;
                        }
                    }
                    // 3. Fallback to logging-default.properties (Search logging/ then conf/)
                    if (fileToLoad == null) {
                        File defaultInLogging = logConfPath.resolve(Signum.DEFAULT_LOGGING_PROPERTIES_NAME).toFile();
                        if (defaultInLogging.exists()) {
                            fileToLoad = defaultInLogging;
                        } else {
                            File defaultInConf = confPath.resolve(Signum.DEFAULT_LOGGING_PROPERTIES_NAME).toFile();
                            if (defaultInConf.exists()) {
                                fileToLoad = defaultInConf;
                            }
                        }
                    }
                }

                if (fileToLoad != null) {
                    try (InputStream is = new FileInputStream(fileToLoad)) {
                        loggingProperties.load(is);
                        logs.add("INFO: Logging configuration loaded from " + fileToLoad.getAbsolutePath());
                        // Update global variable to reflect actual file used
                        Signum.LOGGING_PROPERTIES_NAME = fileToLoad.getName();
                    }
                } else {
                    logs.add("INFO: No logging configuration files found. Using internal defaults.");
                }

                ByteArrayOutputStream outStream = new ByteArrayOutputStream();
                loggingProperties.store(outStream, "logging properties");
                ByteArrayInputStream inStream = new ByteArrayInputStream(outStream.toByteArray());
                LogManager logManager = java.util.logging.LogManager.getLogManager();
                logManager.readConfiguration(inStream);
                inStream.close();
                outStream.close();
                logs.add("INFO: Logging configuration applied");

                BriefLogFormatter.init();
            } catch (IOException e) {
                throw new RuntimeException("Error loading logging properties", e);
            }
        }

        logs.add("INFO: logging enabled");
        return logs;
    }

    /**
     * LoggerConfigurator shutdown
     */
    public static void shutdown() {
        if (LogManager.getLogManager() instanceof SignumLogManager) {
            ((SignumLogManager) LogManager.getLogManager()).signumShutdown();
        }
    }
}
