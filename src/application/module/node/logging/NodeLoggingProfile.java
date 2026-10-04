package application.module.node.logging;
import application.AppInfo;
import application.utils.config.ModuleIds;

import application.utils.logging.ModuleLoggingProfile;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Node module logging profile that encapsulates all built-in logger keys and
 * defaults specific to the Signum node.
 * <p>
 * This class extracts the hardcoded values previously embedded in
 * {@code LoggerProfile.applyInternalDefaults()} and makes them
 * discoverable, testable, and composable with other module profiles.
 * </p>
 *
 * @see application.utils.logging.ModuleLoggingProfile
 * @see NodeLoggingProvider
 */
public class NodeLoggingProfile extends ModuleLoggingProfile {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeLoggingProfile.class);

    public static final String MODULE_ID = ModuleIds.NODE;
    public static final String DISPLAY_NAME = AppInfo.NAME + " Node";
    public static final String DESCRIPTION = "Controls logging for the core Signum node: blockchain, peers, HTTP API, console, and file handlers.";

    // ── Abstract overrides ────────────────────────────────────────────

    @Override
    public String getModuleId() {
        return MODULE_ID;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    /**
     * Returns the default logger level mappings.
     * Values are migrated from the original {@code LoggerProfile.applyInternalDefaults()}
     * to maintain full backward compatibility.
     */
    @Override
    public Map<String, String> getDefaults() {
        Map<String, String> defaults = new LinkedHashMap<>();

        // ── Handlers & global settings ──
        defaults.put("handlers", "java.util.logging.ConsoleHandler");
        defaults.put(".level", "SEVERE");
        defaults.put("node.level", "INFO");

        // ── Console handler ──
        defaults.put("java.util.logging.ConsoleHandler.level", "ALL");
        defaults.put("java.util.logging.ConsoleHandler.formatter", "application.module.node.util.BriefLogFormatter");

        // ── Library noise suppression ──
        defaults.put("org.eclipse.jetty.level", "OFF");
        defaults.put("javax.servlet.level", "OFF");
        defaults.put("com.zaxxer.hikari.level", "WARNING");
        defaults.put("com.zaxxer.hikari.HikariConfig.level", "INFO");
        defaults.put("sun.rmi.level", "INFO");
        defaults.put("javax.management.level", "INFO");
        defaults.put("application.module.node.db.store.DerivedTableManager.level", "OFF");
        defaults.put("org.jooq.Constants.level", "OFF");

        // ── GUI console buffer size ──
        defaults.put("node.gui.consoleLogSize", "100000");

        LOGGER.debug("NodeLoggingProfile defaults initialized with {} entries", defaults.size());
        return Collections.unmodifiableMap(defaults);
    }

    @Override
    public String toString() {
        return "NodeLoggingProfile{moduleId=" + getModuleId() + '}';
    }
}