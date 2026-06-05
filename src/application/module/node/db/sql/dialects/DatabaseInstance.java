package application.module.node.db.sql.dialects;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jooq.SQLDialect;

public interface DatabaseInstance {
    enum SupportStatus {
        STABLE,
        DEPRECATED,
        EXPERIMENTAL
    }

    /**
     * Default classpath for Java-based migrations.
     * Note: This must match the actual package structure of the migration classes.
     */
    String DEFAULT_MIGRATION_PACKAGE = "application/module/node/db/sql/migration";

    /**
     * Default base path for SQL-based migrations in the resources folder.
     */
    String DEFAULT_SQL_MIGRATION_PATH = "db/node/migration_";

    void onStartup();

    void onShutdown();

    HikariConfig getConfig();

    HikariDataSource getDataSource();

    String getMigrationSqlScriptPath();

    String getMigrationClassPath();

    String getDatabaseVersionSQLScript();

    SQLDialect getDialect();

    SupportStatus getSupportStatus();
}
