package application.module.node.db.sql.migration;

import java.sql.Statement;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Migration to copy balance entries to account_balance table.
 * Skipped for SQLite and PostgreSQL as they handle this differently.
 * <p>
 * Phase 10e: Replaced static {@code Db.getDialect()} with instance-based
 * dialect detection via JDBC connection metadata from Flyway context.
 */
public class V7_3__MigrateBalances extends BaseJavaMigration {

    /**
     * Determines if the current database is SQLite or PostgreSQL using JDBC URL.
     * <p>
     * This replaces the static {@code Db.getDialect()} call to support multi-profile
     * operation where each profile has its own isolated DbContext.
     */
    private static boolean isSqliteOrPostgres(Context context) throws java.sql.SQLException {
        String url = context.getConnection().getMetaData().getURL();
        return url != null && (url.startsWith("jdbc:sqlite:") || url.startsWith("jdbc:postgresql:"));
    }

    @Override
    public void migrate(Context context) throws Exception {

        if (isSqliteOrPostgres(context)) {
            return;
        }

        // copy all balance entries
        try (Statement selectTx = context.getConnection().createStatement()) {
            selectTx.executeUpdate(
                    "INSERT INTO account_balance(id, balance, unconfirmed_balance, forged_balance, height, latest) " +
                            "SELECT id, balance, unconfirmed_balance, forged_balance, height, latest FROM account");
        }
    }
}
