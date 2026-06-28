package application.module.database.logging;

import application.utils.logging.ModuleLoggingProfile;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Database module logging profile that encapsulates all built-in logger keys,
 * defaults, and presets specific to database connectivity (HikariCP pool,
 * JOOQ queries, MariaDB/SQLite/PostgreSQL drivers).
 * <p>
 * Config files are resolved from {@code conf/database/logging/*.properties}.
 * </p>
 *
 * <h3>Built-in Presets</h3>
 * <ul>
 *   <li><b>minimal</b>  — Suppress all SQL/pool noise (production default)</li>
 *   <li><b>standard</b> — Show connection pool stats at WARNING level</li>
 *   <li><b>verbose</b>  — Log SQL statements for debugging queries</li>
 *   <li><b>debug</b>    — Maximum visibility: every query + pool internals</li>
 * </ul>
 *
 * @see application.utils.logging.ModuleLoggingProfile
 * @see DatabaseLoggingProvider
 */
public class DatabaseLoggingProfile extends ModuleLoggingProfile {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseLoggingProfile.class);

    public static final String MODULE_ID = "database";
    public static final String DISPLAY_NAME = "Database Engine";
    public static final String DESCRIPTION = "Controls logging for database connectivity: HikariCP connection pool, JOOQ query engine, and database drivers (MariaDB, SQLite, PostgreSQL).";

    // Supported preset names
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
     * Returns the default logger level mappings for database-related loggers.
     */
    @Override
    public Map<String, String> getDefaults() {
        Map<String, String> defaults = new LinkedHashMap<>();

        // ── HikariCP connection pool ──
        defaults.put("com.zaxxer.hikari.level", "WARNING");
        defaults.put("com.zaxxer.hikari.HikariConfig.level", "INFO");
        defaults.put("com.zaxxer.hikari.pool.PoolBase.level", "SEVERE");

        // ── JOOQ query engine ──
        defaults.put("org.jooq.Constants.level", "OFF");
        defaults.put("org.jooq.tools.LoggerListener.level", "OFF");

        // ── Derived table manager (Signum-specific DB layer) ──
        defaults.put("application.module.node.db.store.DerivedTableManager.level", "OFF");

        // ── Database drivers ──
        defaults.put("org.marijb.jdbc.level", "WARNING");       // MariaDB
        defaults.put("org.sqlite.level", "WARNING");             // SQLite
        defaults.put("org.postgresql.level", "WARNING");          // PostgreSQL

        LOGGER.debug("DatabaseLoggingProfile defaults initialized with {} entries", defaults.size());
        return Collections.unmodifiableMap(defaults);
    }

    /**
     * Returns the preset override maps for the database module.
     */
    @Override
    public Map<String, Map<String, String>> getPresetOverrides() {
        Map<String, Map<String, String>> presets = new LinkedHashMap<>();

        // ── Minimal: suppress everything ──
        Map<String, String> minimal = new LinkedHashMap<>();
        minimal.put("com.zaxxer.hikari.level", "OFF");
        minimal.put("org.jooq.Constants.level", "OFF");
        minimal.put("application.module.node.db.store.DerivedTableManager.level", "OFF");
        presets.put(PRESET_MINIMAL, Collections.unmodifiableMap(minimal));

        // ── Standard: show pool warnings ──
        Map<String, String> standard = new LinkedHashMap<>();
        standard.put("com.zaxxer.hikari.level", "WARNING");
        standard.put("com.zaxxer.hikari.HikariConfig.level", "WARNING");
        standard.put("org.jooq.Constants.level", "OFF");
        presets.put(PRESET_STANDARD, Collections.unmodifiableMap(standard));

        // ── Verbose: log SQL statements ──
        Map<String, String> verbose = new LinkedHashMap<>();
        verbose.put("com.zaxxer.hikari.level", "INFO");
        verbose.put("com.zaxxer.hikari.HikariConfig.level", "INFO");
        verbose.put("org.jooq.Constants.level", "CONFIG");
        verbose.put("application.module.node.db.store.DerivedTableManager.level", "WARNING");
        presets.put(PRESET_VERBOSE, Collections.unmodifiableMap(verbose));

        // ── Debug: maximum visibility ──
        Map<String, String> debug = new LinkedHashMap<>();
        debug.put("com.zaxxer.hikari.level", "FINE");
        debug.put("com.zaxxer.hikari.HikariConfig.level", "FINE");
        debug.put("com.zaxxer.hikari.pool.PoolBase.level", "FINE");
        debug.put("org.jooq.Constants.level", "FINE");
        debug.put("org.jooq.tools.LoggerListener.level", "CONFIG");
        debug.put("application.module.node.db.store.DerivedTableManager.level", "FINE");
        presets.put(PRESET_DEBUG, Collections.unmodifiableMap(debug));

        return Collections.unmodifiableMap(presets);
    }

    @Override
    public String toString() {
        return "DatabaseLoggingProfile{presets=" + getPresetOverrides().keySet() + '}';
    }
}