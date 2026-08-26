package application.module.node.util;

import application.module.node.Signum;
import application.module.node.gui.configuration.LoggerProfile;
import application.utils.gui.ConfigurationUtils;

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

    private static final String SHORTHAND_PREFIX = "node.";
    private static final String ACTUAL_PACKAGE_PREFIX = "application.module.node.";
    private static final String LOGGING_PROPERTIES_COMMENT = "logging properties";
    private static final String SYSTEM_PROPERTY_DO_NOT_CONFIGURE = "node.doNotConfigureLogging";
    private static final String LOG_MANAGER_PACKAGE = "java.util.logging.manager";

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

        if (!Boolean.getBoolean(SYSTEM_PROPERTY_DO_NOT_CONFIGURE)) {
            try {
                LoggerProfile effectiveProfile = ConfigurationUtils.loadEffectiveLoggerProfile(confFolder,
                        Signum.LOGGING_PROPERTIES_NAME);
                Properties loggingProperties = effectiveProfile.getProperties();

                // Remap 'node.*' shorthand to actual package 'application.module.node.*'
                Properties mappedProperties = new Properties();
                for (String key : loggingProperties.stringPropertyNames()) {
                    String value = loggingProperties.getProperty(key);
                    mappedProperties.setProperty(key, value);
                    if (key.startsWith(SHORTHAND_PREFIX)) {
                        mappedProperties.setProperty(ACTUAL_PACKAGE_PREFIX + key.substring(SHORTHAND_PREFIX.length()),
                                value);
                    }
                }
                logs.add("INFO: Logging configuration resolved for profile: " + Signum.LOGGING_PROPERTIES_NAME);

                ByteArrayOutputStream outStream = new ByteArrayOutputStream();
                mappedProperties.store(outStream, LOGGING_PROPERTIES_COMMENT);
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
