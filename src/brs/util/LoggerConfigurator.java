package brs.util;

import brs.Signum;
import brs.gui.configuration.ConfigurationUtils;
import brs.gui.configuration.LoggerProfile;
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
                LoggerProfile effectiveProfile = ConfigurationUtils.loadEffectiveLoggerProfile(confFolder,
                        Signum.getActiveLoggingProfile());
                Properties loggingProperties = effectiveProfile.getProperties();
                logs.add("INFO: Logging configuration resolved for profile: " + Signum.getActiveLoggingProfile());

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
