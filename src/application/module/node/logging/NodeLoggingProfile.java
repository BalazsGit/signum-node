package application.module.node.logging;

import application.utils.logging.ModuleLoggingProfile;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Node module logging profile that encapsulates all built-in logger keys,
 * defaults, and presets specific to the Signum node.
 * <p>
 * This class extracts the hardcoded values previously embedded in
 * {@code LoggerProfile.applyInternalDefaults()} and makes them
 * discoverable, testable, and composable with other module profiles.
 * </p>
 *
 * <h3>Built-in Presets</h3>
 * <ul>
 *   <li><b>minimal</b>     — Production-ready, suppress library noise</li>
 *   <li><b>standard</b>    — Balanced visibility for day-to-day operations</li>
 *   <li><b>verbose</b>     — Detailed node + peer diagnostics</li>
 *   <li><b>debug</b>       — Maximum verbosity for development & troubleshooting</li>
 * </ul>
 *
 * @see application.utils.logging.ModuleLoggingProfile
 * @see NodeLoggingProvider
 */
public class NodeLoggingProfile extends ModuleLoggingProfile {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeLoggingProfile.class);

    public static final String MODULE_ID = "node";
    public static final String DISPLAY_NAME = "Signum Node";
    public static final String DESCRIPTION = "Controls logging for the core Signum node: blockchain, peers, HTTP API, console, and file handlers.";

    // Supported preset names (exposed for UI binding)
    public static final String PRESET_MINIMAL = "minimal";
    public static final String PRESET_STANDARD = "standard";
    public static final String PRESET_VERBOSE = "verbose";
    public static final String PRESET_DEBUG = "debug";

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

    /**
     * Returns the preset override maps for the node module.
     */
    @Override
    public Map<String, Map<String, String>> getPresetOverrides() {
        Map<String, Map<String, String>> presets = new LinkedHashMap<>();

        // ── Minimal: aggressive suppression ──
        Map<String, String> minimal = new LinkedHashMap<>();
        minimal.put(".level", "WARNING");
        minimal.put("node.level", "WARNING");
        minimal.put("java.util.logging.ConsoleHandler.level", "WARNING");
        presets.put(PRESET_MINIMAL, Collections.unmodifiableMap(minimal));

        // ── Standard: balanced ──
        Map<String, String> standard = new LinkedHashMap<>();
        standard.put(".level", "INFO");
        standard.put("node.level", "INFO");
        standard.put("java.util.logging.ConsoleHandler.level", "INFO");
        presets.put(PRESET_STANDARD, Collections.unmodifiableMap(standard));

        // ── Verbose: detailed node diagnostics ──
        Map<String, String> verbose = new LinkedHashMap<>();
        verbose.put(".level", "CONFIG");
        verbose.put("node.level", "FINE");
        verbose.put("java.util.logging.ConsoleHandler.level", "CONFIG");
        presets.put(PRESET_VERBOSE, Collections.unmodifiableMap(verbose));

        // ── Debug: maximum verbosity ──
        Map<String, String> debug = new LinkedHashMap<>();
        debug.put(".level", "ALL");
        debug.put("node.level", "FINEST");
        debug.put("java.util.logging.ConsoleHandler.level", "ALL");
        debug.put("application.module.node.db.store.DerivedTableManager.level", "FINE");
        debug.put("org.jooq.Constants.level", "CONFIG");
        debug.put("com.zaxxer.hikari.HikariConfig.level", "FINE");
        presets.put(PRESET_DEBUG, Collections.unmodifiableMap(debug));

        return Collections.unmodifiableMap(presets);
    }

    @Override
    public String toString() {
        return "NodeLoggingProfile{presets=" + getPresetOverrides().keySet() + '}';
    }
}