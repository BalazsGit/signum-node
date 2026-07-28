package application.module.node.db.sql;

import application.module.node.db.SignumKey;
import application.module.node.db.cache.DBCacheManagerImpl;
import application.module.node.db.sql.dialects.DatabaseInstance;
import application.module.node.db.sql.dialects.DatabaseInstanceFactory;
import application.module.node.db.store.Dbs;
import application.module.node.props.PropertyService;
import com.zaxxer.hikari.HikariConfig;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.jooq.*;
import org.jooq.conf.Settings;
import org.jooq.conf.StatementType;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Database utility that provides static access to the currently active
 * {@link DbContext}.
 * <p>
 * <h2>Backwards Compatibility</h2>
 * All static methods delegate to the active {@link DbContext} instance so that
 * existing code continues to work without changes. New code should prefer
 * direct {@link DbContext} access for true multi-instance support.
 * </p>
 *
 * <h2>Migration Path</h2>
 * <pre>{@code
 * // OLD (still works via static delegate):
 * Db.getConnection();
 * Db.beginTransaction();
 *
 * // NEW (instance-scoped, supports multiple profiles):
 * DbContext dbContext = nodeCoreContext.getDbContext();
 * dbContext.getConnection();
 * dbContext.beginTransaction();
 * }</pre>
 *
 * @since 4.0
 */
public final class Db {

    private static final Logger logger = LoggerFactory.getLogger(Db.class);

    // ========================================================================
    // Static delegate target
    // ========================================================================

    /**
     * The currently active database context. Set during node initialization.
     * Multiple nodes can each have their own DbContext; this field points to
     * the last-initialized one for backwards compatibility.
     */
    private static volatile DbContext activeContext;

    /**
     * Sets the active {@link DbContext} that all static methods will delegate to.
     * Call this once during node bootstrap.
     *
     * @param context the database context to activate
     */
    public static void setActiveContext(DbContext context) {
        activeContext = context;
    }

    /**
     * Returns the currently active {@link DbContext}.
     *
     * @throws IllegalStateException if no context has been set
     */
    public static DbContext getActiveContext() {
        if (activeContext == null) {
            throw new IllegalStateException(
                    "No active DbContext. Call Db.setActiveContext() during initialization.");
        }
        return activeContext;
    }

    // ========================================================================
    // Legacy static state (kept for backwards compatibility bridge)
    // ========================================================================

    private static final ThreadLocal<Connection> localConnection = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, Map<SignumKey, Object>>> transactionCaches = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, Map<SignumKey, Object>>> transactionBatches = new ThreadLocal<>();
    private static DBCacheManagerImpl dbCacheManager;
    private static Flyway flyway;
    private static DatabaseInstance databaseInstance;

    private static Predicate<String> repairConfirmationHandler = msg -> {
        logger.error(
                "Database validation failed, but no confirmation handler is registered. Skipping automated repair.");
        return false;
    };

    // ========================================================================
    // Legacy init (backwards-compat bridge)
    // ========================================================================

    /**
     * Initialises the database using the legacy static API.
     * <p>
     * This method populates both the static fields AND creates a new
     * {@link DbContext} so that new code path also works.
     * </p>
     *
     * @param propertyService  resolved properties for this profile
     * @param dbCacheManager   cache manager to associate with this context
     * @return the newly created {@link DbContext} for this initialization call
     * @since 4.0 return type changed from void to DbContext (Multi-profile: per-instance DbContext)
     */
    public static DbContext init(PropertyService propertyService, DBCacheManagerImpl dbCacheManager) {
        try {
            Db.dbCacheManager = dbCacheManager;
            Db.databaseInstance = DatabaseInstanceFactory.createInstance(propertyService);
            logger.info("Using SQL Backend with Dialect {} - Version {}", databaseInstance.getDialect().getName(),
                    getDatabaseVersion());

            String javaMigrations = databaseInstance.getMigrationClassPath();
            String sqlMigrations = databaseInstance.getMigrationSqlScriptPath();
            logger.info("Flyway scanning locations: Java: [{}], SQL: [{}]", javaMigrations, sqlMigrations);

            HikariConfig config = databaseInstance.getConfig();
            flyway = Flyway.configure()
                    .dataSource(config.getJdbcUrl(), config.getUsername(), config.getPassword())
                    .baselineOnMigrate(true)
                    .locations(javaMigrations, sqlMigrations)
                    .load();

            try {
                logger.info("Validating database migrations...");
                flyway.validate();
            } catch (FlywayValidateException e) {
                logger.warn("Database migration validation failed!");
                logger.info("Validation details: {}", e.getMessage());

                // Check if the error is due to pending migrations on a fresh/new database.
                // In this case, we can skip validation and proceed directly to migration,
                // which will apply all pending migrations automatically.
                boolean isFreshDbError = e.getMessage().contains("not applied to database");

                if (isFreshDbError) {
                    logger.info(
                            "Detected fresh database with pending migrations — skipping validation, proceeding to migrate...");
                } else {
                    // For other validation errors (e.g., checksum mismatch), ask for repair confirmation
                    if (repairConfirmationHandler.test(e.getMessage())) {
                        logger.info("Repairing Flyway metadata as authorized by user...");
                        logger.debug("Flyway repair initiated.");
                        flyway.repair();
                    } else {
                        throw new RuntimeException(
                                "Database validation failed and repair was not authorized. Please check your migration files.",
                                e);
                    }
                }
            }

            logger.info("Running flyway migration");
            flyway.migrate();
            databaseInstance.onStartup();

            // Also create and set a DbContext instance for new code paths
            DbContext dbContext = new DbContext();
            dbContext.init(propertyService, dbCacheManager);
            setActiveContext(dbContext);
            return dbContext; // Multi-profile: per-instance DbContext

        } catch (Exception e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

    public static void setRepairConfirmationHandler(Predicate<String> handler) {
        repairConfirmationHandler = handler;
        // Also propagate to active context if available
        if (activeContext != null) {
            activeContext.setRepairConfirmationHandler(handler);
        }
    }

    public static void clean() {
        try {
            flyway.clean();
            flyway.migrate();
        } catch (Exception e) {
            logger.error(e.getMessage());
        }
    }

    private Db() {
    } // never

    // ========================================================================
    // Static delegates to active DbContext (backwards compatibility)
    // ========================================================================

    public static Dbs getDbsByDatabaseType() {
        if (activeContext != null) {
            return activeContext.getDbsByDatabaseType();
        }
        return new application.module.node.db.sql.SqlDbs();
    }

    public static void analyzeTables() {
        // currently no-op
    }

    public static void shutdown() {
        if (activeContext != null) {
            activeContext.shutdown();
        }
        // Also shutdown legacy static state if it was used directly
        Throwable firstException = null;
        if (databaseInstance != null && (activeContext == null ||
                activeContext.getDatabaseInstance() != databaseInstance)) {
            try {
                databaseInstance.onShutdown();
            } catch (Throwable t) {
                logger.error("Error shutting down legacy database instance", t);
                firstException = t;
            }
        }
        if (firstException != null) {
            if (firstException instanceof RuntimeException) {
                throw (RuntimeException) firstException;
            }
            throw new RuntimeException("Error during Db shutdown", firstException);
        }
    }

    private static void executeStatement(String statement) {
        try {
            Connection con = databaseInstance.getDataSource().getConnection();
            Statement stmt = con.createStatement();
            stmt.execute(statement);
        } catch (SQLException e) {
            logger.error(e.toString(), e);
        }
    }

    public static void backup(String filename) {
        if (activeContext != null) {
            activeContext.backup(filename);
        } else {
            logger.error("Backup not yet implemented for {}", databaseInstance.getDialect());
        }
    }

    private static Connection getPooledConnection() throws SQLException {
        if (activeContext != null) {
            return activeContext.getConnection();
        }
        return databaseInstance.getDataSource().getConnection();
    }

    public static Connection getConnection() throws SQLException {
        if (activeContext != null) {
            return activeContext.getConnection();
        }
        Connection con = localConnection.get();
        if (con != null) {
            return con;
        }

        con = getPooledConnection();
        con.setAutoCommit(true);

        return con;
    }

    public static <T> T fetchWithDSLContext(Function<DSLContext, T> function) {
        if (activeContext != null) {
            return activeContext.fetchWithDSLContext(function);
        }
        return function.apply(getDSLContext());
    }

    public static void useDSLContext(Consumer<DSLContext> consumer) {
        if (activeContext != null) {
            activeContext.useDSLContext(consumer);
        } else {
            consumer.accept(getDSLContext());
        }
    }

    private static DSLContext getDSLContext() {
        Connection con = localConnection.get();
        Settings settings = new Settings();
        settings.setRenderSchema(Boolean.FALSE);
        SQLDialect dialect = databaseInstance.getDialect();
        if (con == null) {
            return DSL.using(databaseInstance.getDataSource(), dialect, settings);
        } else {
            return DSL.using(con, dialect, settings);
        }
    }

    static <V> Map<SignumKey, V> getCache(String tableName) {
        if (!isInTransaction()) {
            throw new IllegalStateException("Not in transaction");
        }
        // Delegate to active DbContext if available (new code path uses instance-level ThreadLocals)
        if (activeContext != null) {
            return activeContext.getCache(tableName);
        }
        // noinspection unchecked
        Map<String, Map<SignumKey, Object>> caches = transactionCaches.get();
        if (caches == null) {
            throw new IllegalStateException("Transaction cache not initialized. Did you call beginTransaction()?");
        }
        return (Map<SignumKey, V>) caches.computeIfAbsent(tableName, k -> new LinkedHashMap<>());
    }

    static <V> Map<SignumKey, V> getBatch(String tableName) {
        if (!isInTransaction()) {
            throw new IllegalStateException("Not in transaction");
        }
        // Delegate to active DbContext if available
        if (activeContext != null) {
            return activeContext.getBatch(tableName);
        }
        // noinspection unchecked
        Map<String, Map<SignumKey, Object>> batches = transactionBatches.get();
        if (batches == null) {
            throw new IllegalStateException("Transaction batch not initialized. Did you call beginTransaction()?");
        }
        return (Map<SignumKey, V>) batches.computeIfAbsent(tableName, k -> new LinkedHashMap<>());
    }

    public static boolean isInTransaction() {
        if (activeContext != null) {
            return activeContext.isInTransaction();
        }
        return localConnection.get() != null;
    }

    public static SQLDialect getDialect() {
        if (activeContext != null) {
            return activeContext.getDialect();
        }
        return getDSLContext().dialect();
    }

    public static Connection beginTransaction() {
        if (activeContext != null) {
            return activeContext.beginTransaction();
        }
        if (localConnection.get() != null) {
            throw new IllegalStateException("Transaction already in progress");
        }
        try {
            Connection con = databaseInstance.getDataSource().getConnection();
            con.setAutoCommit(false);
            localConnection.set(con);
            transactionCaches.set(new LinkedHashMap<>());
            transactionBatches.set(new LinkedHashMap<>());
            return con;
        } catch (Exception e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

    public static void commitTransaction() {
        if (activeContext != null) {
            activeContext.commitTransaction();
            return;
        }
        Connection con = localConnection.get();
        if (con == null) {
            throw new IllegalStateException("Not in transaction");
        }
        try {
            con.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

    public static void rollbackTransaction() {
        if (activeContext != null) {
            activeContext.rollbackTransaction();
            return;
        }
        Connection con = localConnection.get();
        if (con == null) {
            throw new IllegalStateException("Not in transaction");
        }
        try {
            con.rollback();
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
        transactionCaches.get().clear();
        transactionBatches.get().clear();
        dbCacheManager.flushCache();
    }

    public static void endTransaction() {
        if (activeContext != null) {
            activeContext.endTransaction();
            return;
        }
        Connection con = localConnection.get();
        if (con == null) {
            throw new IllegalStateException("Not in transaction");
        }
        localConnection.remove();
        transactionCaches.get().clear();
        transactionCaches.remove();
        transactionBatches.get().clear();
        transactionBatches.remove();
        DbUtils.close(con);
    }

    public static void optimizeTable(String tableName) {
        if (activeContext != null) {
            activeContext.optimizeTable(tableName);
            return;
        }
        useDSLContext(ctx -> {
            try {
                switch (ctx.dialect()) {
                    case MYSQL:
                    case MARIADB:
                        ctx.execute("OPTIMIZE NO_WRITE_TO_BINLOG TABLE " + tableName);
                        break;
                    case SQLITE:
                        ctx.execute("VACUUM");
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                logger.debug("Failed to optimize table {}", tableName, e);
                throw new RuntimeException(e);
            }
        });
    }

    private static String getDatabaseVersion() {
        String version = "N/A";
        try {
            DSLContext ctx = getDSLContext();
            ResultQuery queryVersion = ctx.resultQuery(databaseInstance.getDatabaseVersionSQLScript());
            org.jooq.Record record = queryVersion.fetchOne();

            if (record != null) {
                version = record.get(0, String.class);
                if (databaseInstance.getSupportStatus() != DatabaseInstance.SupportStatus.STABLE) {
                    version += " (" + databaseInstance.getSupportStatus().toString() + ")";
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch version");
        }
        return version;
    }
}