package brs.db.sql.migration;

import brs.db.sql.Db;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.jooq.SQLDialect;

import java.sql.Statement;

public class V14__CreatePrunedBlockTable extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement s = context.getConnection().createStatement()) {
            String bin32 = "VARBINARY(32)";
            String bin64 = "VARBINARY(64)";
            String longBin = "LONGBLOB";

            if (Db.getDialect() == SQLDialect.POSTGRES) {
                bin32 = "BYTEA";
                bin64 = "BYTEA";
                longBin = "BYTEA";
            } else if (Db.getDialect() == SQLDialect.SQLITE) {
                bin32 = "BLOB";
                bin64 = "BLOB";
                longBin = "BLOB";
            }

            // Create the table with columns matching the PRUNE archival process
            s.execute("CREATE TABLE IF NOT EXISTS pruned_block (" +
                    "id BIGINT PRIMARY KEY, " +
                    "height INT NOT NULL, " +
                    "version INT NOT NULL, " +
                    "timestamp INT NOT NULL, " +
                    "previous_block_id BIGINT, " +
                    "total_amount BIGINT NOT NULL, " +
                    "total_fee BIGINT NOT NULL, " +
                    "payload_length INT NOT NULL, " +
                    "generator_public_key " + bin32 + " NOT NULL, " +
                    "previous_block_hash " + bin32 + ", " +
                    "cumulative_difficulty " + longBin + " NOT NULL, " +
                    "base_target BIGINT NOT NULL, " +
                    "next_block_id BIGINT, " +
                    "nonce BIGINT NOT NULL, " +
                    "generator_id BIGINT NOT NULL, " +
                    "generation_signature " + bin64 + " NOT NULL, " +
                    "block_signature " + bin64 + " NOT NULL, " +
                    "payload_hash " + bin32 + " NOT NULL, " +
                    "total_fee_cash_back BIGINT NOT NULL DEFAULT 0, " +
                    "total_fee_burnt BIGINT NOT NULL DEFAULT 0, " +
                    "ats " + longBin +
                    ")");

            s.execute("CREATE INDEX IF NOT EXISTS pruned_block_height_idx ON pruned_block(height)");

            // Remove block foreign keys that block pruning/insertion
            if (Db.getDialect() == SQLDialect.POSTGRES) {
                s.execute("ALTER TABLE block DROP CONSTRAINT IF EXISTS constraint_3c");
                s.execute("ALTER TABLE block DROP CONSTRAINT IF EXISTS constraint_3c5");
                s.execute("ALTER TABLE transaction DROP CONSTRAINT IF EXISTS constraint_2");
                // Also try common Postgres naming patterns just in case
                s.execute("ALTER TABLE block DROP CONSTRAINT IF EXISTS block_previous_block_id_fkey");
                s.execute("ALTER TABLE block DROP CONSTRAINT IF EXISTS block_next_block_id_fkey");
                s.execute("ALTER TABLE transaction DROP CONSTRAINT IF EXISTS transaction_block_id_fkey");
            } else {
                // MariaDB / H2
                try {
                    s.execute("ALTER TABLE block DROP FOREIGN KEY constraint_3c");
                } catch (Exception ignored) {
                }
                try {
                    s.execute("ALTER TABLE block DROP FOREIGN KEY constraint_3c5");
                } catch (Exception ignored) {
                }
                try {
                    s.execute("ALTER TABLE transaction DROP FOREIGN KEY constraint_2");
                } catch (Exception ignored) {
                }
            }
        }
    }
}