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

public final class Db {

    private static final Logger logger = LoggerFactory.getLogger(Db.class);
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

    public static void init(PropertyService propertyService, DBCacheManagerImpl dbCacheManager) {
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
                // Ask the UI or Console if we should attempt a repair
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

            logger.info("Running flyway migration");
            flyway.migrate();
            databaseInstance.onStartup();
        } catch (Exception e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

    public static void setRepairConfirmationHandler(Predicate<String> handler) {
        repairConfirmationHandler = handler;
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

    public static Dbs getDbsByDatabaseType() {
        return new SqlDbs();
    }

    public static void analyzeTables() {
        // currently no-op
    }

    public static void shutdown() {
        Throwable firstException = null;
        if (databaseInstance != null) {
            try {
                databaseInstance.onShutdown();
            } catch (Throwable t) {
                logger.error("Error shutting down database instance", t);
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
        logger.error("Backup not yet implemented for {}", databaseInstance.getDialect());
    }

    private static Connection getPooledConnection() throws SQLException {
        return databaseInstance.getDataSource().getConnection();
    }

    public static Connection getConnection() throws SQLException {
        Connection con = localConnection.get();
        if (con != null) {
            return con;
        }

        con = getPooledConnection();
        con.setAutoCommit(true);

        return con;
    }

    public static <T> T fetchWithDSLContext(Function<DSLContext, T> function) {
        return function.apply(getDSLContext());
    }

    public static void useDSLContext(Consumer<DSLContext> consumer) { // TODO RxJava
        consumer.accept(getDSLContext());
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
        // noinspection unchecked
        return (Map<SignumKey, V>) transactionCaches.get().computeIfAbsent(tableName, k -> new LinkedHashMap<>());
    }

    static <V> Map<SignumKey, V> getBatch(String tableName) {
        if (!isInTransaction()) {
            throw new IllegalStateException("Not in transaction");
        }
        // noinspection unchecked
        return (Map<SignumKey, V>) transactionBatches.get().computeIfAbsent(tableName, k -> new LinkedHashMap<>());
    }

    public static boolean isInTransaction() {
        return localConnection.get() != null;
    }

    public static SQLDialect getDialect() {
        return getDSLContext().dialect();
    }

    public static Connection beginTransaction() {
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
