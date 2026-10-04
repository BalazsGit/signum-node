package application.module.database.logging;
import application.utils.config.ModuleIds;

import application.utils.logging.ModuleLoggingProfile;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Database module logging profile that encapsulates all built-in logger keys
 * and defaults specific to database connectivity (HikariCP pool,
 * JOOQ queries, MariaDB/SQLite/PostgreSQL drivers).
 * <p>
 * Config files are resolved from {@code conf/database/logging/*.properties}.
 * </p>
 *
 * @see application.utils.logging.ModuleLoggingProfile
 * @see DatabaseLoggingProvider
 */
public class DatabaseLoggingProfile extends ModuleLoggingProfile {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseLoggingProfile.class);

    public static final String MODULE_ID = ModuleIds.DATABASE;
    public static final String DISPLAY_NAME = "Database Engine";
    public static final String DESCRIPTION = "Controls logging for database connectivity: HikariCP connection pool, JOOQ query engine, and database drivers (MariaDB, SQLite, PostgreSQL).";

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

    @Override
    public String toString() {
        return "DatabaseLoggingProfile{moduleId=" + getModuleId() + '}';
    }
}