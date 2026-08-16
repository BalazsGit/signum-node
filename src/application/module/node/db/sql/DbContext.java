package application.module.node.db.sql;

import application.module.node.db.SignumKey;
import application.module.node.db.cache.DBCacheManagerImpl;
import application.module.node.db.sql.dialects.DatabaseInstance;
import application.module.node.db.sql.dialects.DatabaseInstanceFactory;
import application.module.node.db.store.Dbs;
import application.module.node.db.sql.SqlDbs;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.props.PropertyService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.jooq.*;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Per-instance database context that encapsulates all state previously held
 * as static fields in {@link Db}.
 * <p>
 * Each {@code DbContext} manages its own:
 * <ul>
 *   <li>{@link DatabaseInstance} with isolated Hikari connection pool</li>
 *   <li>{@link Flyway} migration instance</li>
 *   <li>{@link DBCacheManagerImpl} cache manager</li>
 *   <li>Thread-local transaction state (connection, caches, batches)</li>
 * </ul>
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * DbContext dbContext = new DbContext();
 * dbContext.init(propertyService, cacheManager);
 * // ... use dbContext.getConnection(), dbContext.beginTransaction(), etc.
 * dbContext.shutdown();
 * }</pre>
 *
 * @since 4.0
 */
public final class DbContext {

    private static final Logger logger = LoggerFactory.getLogger(DbContext.class);

    // ========================================================================
    // Instance state (was static in Db.java)
    // ========================================================================

    /** Per-instance connection pool managed by the database dialect implementation. */
    private DatabaseInstance databaseInstance;

    /** Flyway migration runner for this instance. */
    private Flyway flyway;

    /** Cache manager associated with this database context. */
    private DBCacheManagerImpl dbCacheManager;

    /** Cached Dbs instance for this context (created lazily on first getDbsByDatabaseType call). */
    private SqlDbs sqlDbs;

    /** Thread-local connection for transaction scoping. */
    private final ThreadLocal<Connection> localConnection = new ThreadLocal<>();

    /** Thread-local per-table cache inside a transaction. */
    private final ThreadLocal<Map<String, Map<SignumKey, Object>>> transactionCaches = new ThreadLocal<>();

    /** Thread-local per-table batch inside a transaction. */
    private final ThreadLocal<Map<String, Map<SignumKey, Object>>> transactionBatches = new ThreadLocal<>();

    /** Handler that confirms whether to auto-repair Flyway validation failures. */
    private Predicate<String> repairConfirmationHandler = msg -> {
        logger.error(
                "Database validation failed, but no confirmation handler is registered. Skipping automated repair.");
        return false;
    };

    // ========================================================================
    // Construction
    // ========================================================================

    /** Creates a new, uninitialized context. Call {@link #init(PropertyService, DBCacheManagerImpl)} to bootstrap. */
    public DbContext() {
    }

    /**
     * Pure instance factory that creates and initializes a new {@code DbContext}
     * without touching any static state in {@link Db}.
     * <p>
     * This is the recommended entry point for multi-profile operation. Each call
     * returns an isolated context with its own connection pool, Flyway instance,
     * and thread-local transaction state.
     * </p>
     *
     * @param propertyService  resolved properties for this profile
     * @param dbCacheManager   cache manager to associate with this context
     * @return a fully initialized DbContext ready for use
     * @throws RuntimeException if database initialization fails
     * @since 4.1
     */
    public static DbContext create(PropertyService propertyService, DBCacheManagerImpl dbCacheManager) {
        DbContext ctx = new DbContext();
        ctx.init(propertyService, dbCacheManager);
        return ctx;
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Initialises the database context: creates the dialect-specific instance,
     * runs Flyway migrations, and stores the cache manager reference.
     *
     * @param propertyService  resolved properties for this profile
     * @param dbCacheManager   cache manager to associate with this context
     */
    public void init(PropertyService propertyService, DBCacheManagerImpl dbCacheManager) {
        try {
            this.dbCacheManager = dbCacheManager;
            this.databaseInstance = DatabaseInstanceFactory.createInstance(propertyService);
            logger.info("Using SQL Backend with Dialect {} - Version {}", databaseInstance.getDialect().getName(),
                    getDatabaseVersion());

            String javaMigrations = databaseInstance.getMigrationClassPath();
            String sqlMigrations = databaseInstance.getMigrationSqlScriptPath();
            logger.info("Flyway scanning locations: Java: [{}], SQL: [{}]", javaMigrations, sqlMigrations);

            var config = databaseInstance.getConfig();
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
        } catch (Exception e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

    /**
     * Sets a custom handler that is invoked when Flyway validation fails.
     *
     * @param handler returns {@code true} to authorise automatic repair
     */
    public void setRepairConfirmationHandler(Predicate<String> handler) {
        repairConfirmationHandler = handler;
    }

    /** Drops and re-creates the schema (development utility). */
    public void clean() {
        try {
            flyway.clean();
            flyway.migrate();
        } catch (Exception e) {
            logger.error(e.getMessage());
        }
    }

    /**
     * Gracefully shuts down this database context, closing the connection pool.
     */
    public void shutdown() {
        Throwable firstException = null;
        if (databaseInstance != null) {
            try {
                databaseInstance.onShutdown();
            } catch (Throwable t) {
                logger.error("Error shutting down database instance", t);
                firstException = t;
            }
        }
        // Clear all ThreadLocals to prevent memory leaks
        localConnection.remove();
        transactionCaches.remove();
        transactionBatches.remove();
        if (firstException != null) {
            if (firstException instanceof RuntimeException) {
                throw (RuntimeException) firstException;
            }
            throw new RuntimeException("Error during DbContext shutdown", firstException);
        }
    }

    // ========================================================================
    // Database access helpers
    // ========================================================================

    /**
     * Returns the cached {@link Dbs} instance for the current dialect.
     * The underlying SqlDbs is created lazily and reused across calls.
     */
    public Dbs getDbsByDatabaseType() {
        if (this.sqlDbs == null) {
            this.sqlDbs = new SqlDbs(this);
        }
        return this.sqlDbs;
    }

    /**
     * Sets the FluxCapacitor on the underlying SqlDbs for instance-scoped Block construction.
     * Called by NodeCoreContext after fluxCapacitor is initialized.
     *
     * @param fluxCapacitor the node-scoped FluxCapacitor instance
     */
    public void setFluxCapacitor(FluxCapacitor fluxCapacitor) {
        if (this.sqlDbs != null) {
            this.sqlDbs.setFluxCapacitor(fluxCapacitor);
        }
    }

    /** Currently a no-op placeholder for future query analysis support. */
    public void analyzeTables() {
        // currently no-op
    }

    /** Executes a raw SQL statement (internal utility). */
    private void executeStatement(String statement) {
        try {
            Connection con = databaseInstance.getDataSource().getConnection();
            Statement stmt = con.createStatement();
            stmt.execute(statement);
        } catch (SQLException e) {
            logger.error(e.toString(), e);
        }
    }

    /** Backup support (not yet implemented for SQL backends). */
    public void backup(String filename) {
        logger.error("Backup not yet implemented for {}", databaseInstance.getDialect());
    }

    // ========================================================================
    // Connection management
    // ========================================================================

    /** Retrieves a connection from the Hikari pool. */
    private Connection getPooledConnection() throws SQLException {
        return databaseInstance.getDataSource().getConnection();
    }

    /**
     * Returns the current thread's transaction connection if one exists,
     * otherwise a new pooled connection with auto-commit enabled.
     */
    public Connection getConnection() throws SQLException {
        Connection con = localConnection.get();
        if (con != null) {
            return con;
        }

        con = getPooledConnection();
        con.setAutoCommit(true);

        return con;
    }

    // ========================================================================
    // JOOQ DSL context helpers
    // ========================================================================

    /** Executes a function inside a fresh {@link DSLContext} and returns the result. */
    public <T> T fetchWithDSLContext(Function<DSLContext, T> function) {
        return function.apply(getDSLContext());
    }

    /** Provides a {@link DSLContext} to the given consumer. */
    public void useDSLContext(Consumer<DSLContext> consumer) {
        consumer.accept(getDSLContext());
    }

    /** Creates a {@link DSLContext} bound to the current transaction or pool. */
    private DSLContext getDSLContext() {
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

    // ========================================================================
    // Transaction-scoped cache / batch access
    // ========================================================================

    /** Returns the per-table cache map for the current transaction. */
    <V> Map<SignumKey, V> getCache(String tableName) {
        if (!isInTransaction()) {
            throw new IllegalStateException("Not in transaction");
        }
        // noinspection unchecked
        return (Map<SignumKey, V>) transactionCaches.get().computeIfAbsent(tableName, k -> new LinkedHashMap<>());
    }

    /** Returns the per-table batch map for the current transaction. */
    <V> Map<SignumKey, V> getBatch(String tableName) {
        if (!isInTransaction()) {
            throw new IllegalStateException("Not in transaction");
        }
        // noinspection unchecked
        return (Map<SignumKey, V>) transactionBatches.get().computeIfAbsent(tableName, k -> new LinkedHashMap<>());
    }

    /** Returns {@code true} if the current thread is inside an active transaction. */
    public boolean isInTransaction() {
        return localConnection.get() != null;
    }

    /** Returns the SQL dialect in use. */
    public SQLDialect getDialect() {
        return getDSLContext().dialect();
    }

    // ========================================================================
    // Transaction lifecycle
    // ========================================================================

    /** Begins a new transaction on the current thread. */
    public Connection beginTransaction() {
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

    /** Commits the current transaction. */
    public void commitTransaction() {
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

    /** Rolls back the current transaction and flushes caches. */
    public void rollbackTransaction() {
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

    /** Ends the current transaction, releasing all thread-local state. */
    public void endTransaction() {
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

    // ========================================================================
    // Maintenance
    // ========================================================================

    /** Optimizes / vacuums the given table (dialect-specific). */
    public void optimizeTable(String tableName) {
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

    // ========================================================================
    // Diagnostics
    // ========================================================================

    /** Returns the human-readable database version string. */
    private String getDatabaseVersion() {
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

    /** Returns the underlying {@link DatabaseInstance}. */
    public DatabaseInstance getDatabaseInstance() {
        return databaseInstance;
    }

    /** Returns the associated cache manager. */
    public DBCacheManagerImpl getCacheManager() {
        return dbCacheManager;
    }
}