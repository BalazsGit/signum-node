package application.module.node.db.sql.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import application.module.node.crypto.Crypto;
import application.module.node.util.Convert;

import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Migration to generate AT code hashes.
 * Skipped for SQLite and PostgreSQL as they handle this differently.
 * <p>
 * Phase 10e: Replaced static {@code Db.getDialect()} with instance-based
 * dialect detection via JDBC connection metadata from Flyway context.
 */
public class V5_1__GenerateAtHashes extends BaseJavaMigration {

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

        try (Statement select = context.getConnection().createStatement()) {
            try (ResultSet rows = select.executeQuery("SELECT id,ap_code FROM at ORDER BY id")) {
                while (rows.next()) {
                    long id = rows.getLong(1);
                    byte[] gzipCode = rows.getBytes(2);
                    byte[] apCode = application.module.node.at.AT.decompressState(gzipCode);

                    byte[] atCodeHash = Crypto.sha256().digest(apCode);
                    long atCodeHashId = Convert.fullHashToId(atCodeHash);

                    try (Statement update = context.getConnection().createStatement()) {
                        update.execute("UPDATE at SET ap_code_hash_id=" + atCodeHashId + " WHERE id=" + id);
                    }
                }
            }
        }
    }
}
