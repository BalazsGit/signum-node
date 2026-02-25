package brs.util;

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
        String oldManager = System.getProperty(managerPackage);
        System.setProperty(managerPackage, "brs.util.SignumLogManager");
        if (!(LogManager.getLogManager() instanceof SignumLogManager)) {
            System.setProperty(managerPackage,
                    (oldManager != null ? oldManager : "java.util.logging.LogManager"));
        }
        if (!Boolean.getBoolean("brs.doNotConfigureLogging")) {
            try {
                Properties loggingProperties = new Properties();
                Path confPath = PathUtils.resolvePath(confFolder);
                File defaultProps = confPath.resolve("logging-default.properties").toFile();
                try (InputStream is = new FileInputStream(defaultProps)) {
                    loggingProperties.load(is);
                    logs.add("INFO: Logging configuration loaded from " + defaultProps.getAbsolutePath());
                }
                File customProps = confPath.resolve("logging.properties").toFile();
                if (customProps.exists()) {
                    try (InputStream is = new FileInputStream(customProps)) {
                        loggingProperties.load(is);
                        logs.add("INFO: Logging configuration loaded from " + customProps.getAbsolutePath());
                    } catch (Exception e) {
                        logs.add("INFO: Custom user logging.properties found but not loaded: " + e.getMessage());
                    }
                } else {
                    logs.add("INFO: Custom user logging.properties not loaded");
                }

                ByteArrayOutputStream outStream = new ByteArrayOutputStream();
                loggingProperties.store(outStream, "logging properties");
                ByteArrayInputStream inStream = new ByteArrayInputStream(outStream.toByteArray());
                LogManager logManager = java.util.logging.LogManager.getLogManager();
                logManager.readConfiguration(inStream);

                // Force update of loggers defined in properties to ensure levels are applied
                // immediately
                for (String name : loggingProperties.stringPropertyNames()) {
                    if (name.endsWith(".level")) {
                        String loggerName = name.substring(0, name.length() - 6);
                        java.util.logging.Logger.getLogger(loggerName);
                    }
                }

                // Force flush all handlers to ensure early messages are written
                for (Handler handler : logManager.getLogger("").getHandlers()) {
                    handler.flush();
                }
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
