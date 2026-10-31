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

        // ── Node root level (log4j2 vocabulary) ──
        defaults.put("node.level", "INFO");

        // ── Library noise suppression ──
        defaults.put("org.eclipse.jetty.level", "OFF");
        defaults.put("javax.servlet.level", "OFF");
        defaults.put("com.zaxxer.hikari.level", "WARN");
        defaults.put("com.zaxxer.hikari.HikariConfig.level", "INFO");
        defaults.put("sun.rmi.level", "INFO");
        defaults.put("javax.management.level", "INFO");
        defaults.put("application.module.node.db.store.DerivedTableManager.level", "OFF");
        defaults.put("org.jooq.Constants.level", "OFF");

        // ── GUI console buffer size (char cap) ──
        defaults.put("node.gui.consoleLogSize", "100000");
        // ── GUI console line buffer (the SSOT for "how many lines the node console keeps",
        //    F4 / decision D5). The char cap and the replay buffer are derived from this. ──
        defaults.put("node.console.maxLines", "3000");

        // ── Per-node file logging (the Log4j2 RollingFile appender of the
        //    isolated node context; consumed by NodeLoggingConfigResolver) ──
        // dir is a RELATIVE (portable) path — the installation can be moved
        // to another disk and the log layout survives (LoggingPaths SSOT).
        defaults.put("node.fileLog.enable", "true");
        defaults.put("node.fileLog.dir", "logs/node");
        defaults.put("node.fileLog.level", "INFO");
        defaults.put("node.fileLog.size", "10MB");
        defaults.put("node.fileLog.max", "5");

        LOGGER.debug("NodeLoggingProfile defaults initialized with {} entries", defaults.size());
        return Collections.unmodifiableMap(defaults);
    }

    @Override
    public String toString() {
        return "NodeLoggingProfile{moduleId=" + getModuleId() + '}';
    }
}