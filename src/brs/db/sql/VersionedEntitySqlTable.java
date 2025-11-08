package brs.db.sql;

import brs.Signum;
import brs.db.SignumKey;
import brs.db.VersionedEntityTable;
import brs.db.store.DerivedTableManager;
import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jooq.impl.TableImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import brs.Constants;

import java.util.ArrayList;
import java.util.List;

public abstract class VersionedEntitySqlTable<T> extends EntitySqlTable<T> implements VersionedEntityTable<T> {

    private static final Logger logger = LoggerFactory.getLogger(VersionedEntitySqlTable.class);

    VersionedEntitySqlTable(String table, TableImpl<?> tableClass, SignumKey.Factory<T> dbKeyFactory,
            DerivedTableManager derivedTableManager) {
        super(table, tableClass, dbKeyFactory, true, derivedTableManager);
    }

    @Override
    public void rollback(int height) {
        rollback(table, tableClass, heightField, latestField, height, dbKeyFactory);
    }
    /*
     * static void rollback(final String table, final TableImpl<?> tableClass,
     * Field<Integer> heightField,
     * Field<Boolean> latestField, final int height, final DbKey.Factory<?>
     * dbKeyFactory) {
     * if (!Db.isInTransaction()) {
     * throw new IllegalStateException("Not in transaction");
     * }
     * 
     * Db.useDSLContext(ctx -> {
     * // get dbKey's for entries whose stuff newer than height would be deleted, to
     * // allow fixing
     * // their latest flag of the "potential" remaining newest entry
     * SelectQuery<Record> selectForDeleteQuery = ctx.selectQuery();
     * selectForDeleteQuery.addFrom(tableClass);
     * selectForDeleteQuery.addConditions(heightField.gt(height));
     * for (String column : dbKeyFactory.getPKColumns()) {
     * selectForDeleteQuery.addSelect(tableClass.field(column, Long.class));
     * }
     * selectForDeleteQuery.setDistinct(true);
     * List<DbKey> dbKeys = selectForDeleteQuery.fetch(r -> (DbKey)
     * dbKeyFactory.newKey(r));
     * 
     * // delete all entries > height
     * DeleteQuery deleteQuery = ctx.deleteQuery(tableClass);
     * deleteQuery.addConditions(heightField.gt(height));
     * deleteQuery.execute();
     * 
     * // update latest flags for remaining entries, if there any remaining (per
     * // deleted dbKey)
     * for (DbKey dbKey : dbKeys) {
     * SelectQuery<Record> selectMaxHeightQuery = ctx.selectQuery();
     * selectMaxHeightQuery.addFrom(tableClass);
     * selectMaxHeightQuery.addConditions(dbKey.getPKConditions(tableClass));
     * selectMaxHeightQuery.addSelect(DSL.max(heightField));
     * Integer maxHeight =
     * selectMaxHeightQuery.fetchOne().get(DSL.max(heightField));
     * 
     * if (maxHeight != null) {
     * UpdateQuery setLatestQuery = ctx.updateQuery(tableClass);
     * setLatestQuery.addConditions(dbKey.getPKConditions(tableClass));
     * setLatestQuery.addConditions(heightField.eq(maxHeight));
     * setLatestQuery.addValue(latestField, true);
     * setLatestQuery.execute();
     * }
     * }
     * });
     * Db.getCache(table).clear();
     * }
     */

    // ultra-detailed rollback with per-key logging and call path tracing
    static void rollback(final String table, final TableImpl<?> tableClass, Field<Integer> heightField,
            Field<Boolean> latestField, final int height, final DbKey.Factory<?> dbKeyFactory) {
        if (!Db.isInTransaction()) {
            throw new IllegalStateException("Not in transaction");
        }

        logger.info("Rollback: START for table {} ({}) to height {}", table, tableClass.getName(), height);

        Db.useDSLContext(ctx -> {
            // Collect all keys with height > rollback height
            SelectQuery<Record> selectForDeleteQuery = ctx.selectQuery();
            selectForDeleteQuery.addFrom(tableClass);
            selectForDeleteQuery.addConditions(heightField.gt(height));
            for (String column : dbKeyFactory.getPKColumns()) {
                selectForDeleteQuery.addSelect(tableClass.field(column, Long.class));
            }
            selectForDeleteQuery.setDistinct(true);

            List<DbKey> dbKeys = selectForDeleteQuery.fetch(r -> (DbKey) dbKeyFactory.newKey(r));
            logger.info("Rollback: Found {} keys with height > {} in table {}", dbKeys.size(), height, table);
            logger.debug("Rollback: Keys to process: {}", dbKeys);

            // Delete records and log each deletion
            for (DbKey dbKey : dbKeys) {
                logger.debug("Rollback: Attempting deletion for key {} in table {}", dbKey, table);

                DeleteQuery deleteQuery = ctx.deleteQuery(tableClass);
                deleteQuery.addConditions(heightField.gt(height));
                deleteQuery.addConditions(dbKey.getPKConditions(tableClass));
                int deleted = deleteQuery.execute();

                logger.info("Rollback: Deleted {} records for key {} in table {}", deleted, dbKey, table);
                if (deleted == 0) {
                    logger.warn("Rollback: WARNING! No records deleted for key {} in table {}", dbKey, table);
                }
            }

            // Update latest flag for each key
            for (DbKey dbKey : dbKeys) {
                logger.debug("Rollback: Processing latest flag for key {} in table {}", dbKey, table);

                SelectQuery<Record> selectMaxHeightQuery = ctx.selectQuery();
                selectMaxHeightQuery.addFrom(tableClass);
                selectMaxHeightQuery.addConditions(dbKey.getPKConditions(tableClass));
                selectMaxHeightQuery.addSelect(DSL.max(heightField).as("max_height"));

                Integer maxHeight = selectMaxHeightQuery.fetchOne().get("max_height", Integer.class);

                if (maxHeight != null) {
                    UpdateQuery setLatestQuery = ctx.updateQuery(tableClass);
                    setLatestQuery.addConditions(dbKey.getPKConditions(tableClass));
                    setLatestQuery.addConditions(heightField.eq(maxHeight));
                    setLatestQuery.addValue(latestField, true);
                    int updated = setLatestQuery.execute();

                    logger.info("Rollback: Updated latest flag for key {} at height {} ({} rows affected) in table {}",
                            dbKey, maxHeight, updated, table);
                    if (updated == 0) {
                        logger.warn(
                                "Rollback: WARNING! Expected to update latest flag for key {} at height {}, but 0 rows affected in table {}",
                                dbKey, maxHeight, table);
                    }
                } else {
                    logger.info("Rollback: No remaining record for key {}, skipping latest update in table {}", dbKey,
                            table);
                }
            }

            // Flush cache and log
            Db.getCache(table).clear();
            logger.info("Rollback: Cache cleared for table {}", table);
        });

        logger.info("Rollback: FINISH for table {} to height {}", table, height);
    }

    @Override
    public void trim(int height) {
        trim(tableClass, heightField, height, dbKeyFactory);
    }

    static void trim(final TableImpl<?> tableClass, Field<Integer> heightField, final int height,
            final DbKey.Factory dbKeyFactory) {
        if (!Db.isInTransaction()) {
            throw new IllegalStateException("Not in transaction");
        }

        // "accounts" is just an example to make it easier to understand what the code
        // does
        // select all accounts with multiple entries where height < trimToHeight[current
        // height - 1440]
        Db.useDSLContext(ctx -> {
            Field<Boolean> latestField = tableClass.field("latest", Boolean.class);
            SelectQuery<Record> selectMaxHeightQuery = ctx.selectQuery();
            selectMaxHeightQuery.addFrom(tableClass);
            selectMaxHeightQuery.addSelect(DSL.max(heightField).as("max_height"));
            for (String column : dbKeyFactory.getPKColumns()) {
                Field pkField = tableClass.field(column, Long.class);
                selectMaxHeightQuery.addSelect(pkField);
                selectMaxHeightQuery.addGroupBy(pkField);
            }
            selectMaxHeightQuery.addConditions(heightField.lt(height));
            selectMaxHeightQuery.addHaving(DSL.countDistinct(heightField).gt(1));
            // to avoid problems if trimming is enable after sync
            selectMaxHeightQuery.addLimit(Constants.TRIM_BATCH_SIZE);

            // delete all fetched accounts, except if it's height is the max height we
            // figured out
            DeleteQuery deleteLowerHeightQuery = ctx.deleteQuery(tableClass);
            deleteLowerHeightQuery.addConditions(heightField.lt((Integer) null));
            for (String column : dbKeyFactory.getPKColumns()) {
                Field<Long> pkField = tableClass.field(column, Long.class);
                deleteLowerHeightQuery.addConditions(pkField.eq((Long) null));
            }
            BatchBindStep deleteBatch = ctx.batch(deleteLowerHeightQuery);

            for (Record record : selectMaxHeightQuery.fetch()) {
                DbKey dbKey = (DbKey) dbKeyFactory.newKey(record);
                int maxHeight = record.get("max_height", Integer.class);
                List<Long> bindValues = new ArrayList<>();
                bindValues.add((long) maxHeight);
                for (Long pkValue : dbKey.getPKValues()) {
                    bindValues.add(pkValue);
                }
                deleteBatch.bind(bindValues.toArray());
            }
            logger.debug("Trimming {} to height {} by {} elements", tableClass, height, deleteBatch.size());
            if (deleteBatch.size() > 0) {
                deleteBatch.execute();
            }

            int batchSize = Constants.TRIM_BATCH_SIZE;
            int totalDeleted = 0;
            int deletedRows;
            do {
                deletedRows = ctx.deleteFrom(tableClass)
                        .where(heightField.lt(height).and(latestField.isFalse()))
                        .limit(batchSize)
                        .execute();
                totalDeleted += deletedRows;
            } while (deletedRows > 0);

            if (totalDeleted > 0) {
                logger.debug("Trimming {} removed {} obsolete non-latest elements below height {}",
                        tableClass.getName(), totalDeleted, height);
            }
        });
    }

    @Override
    public boolean delete(T t) {
        if (t == null) {
            return false;
        }
        if (!Db.isInTransaction()) {
            throw new IllegalStateException("Not in transaction");
        }
        DbKey dbKey = (DbKey) dbKeyFactory.newKey(t);
        return Db.useDSLContext(ctx -> {
            try {
                SelectQuery<Record> countQuery = ctx.selectQuery();
                countQuery.addFrom(tableClass);
                countQuery.addConditions(dbKey.getPKConditions(tableClass));
                countQuery.addConditions(heightField.lt(Signum.getBlockchain().getHeight()));
                if (ctx.fetchCount(countQuery) > 0) {
                    UpdateQuery updateQuery = ctx.updateQuery(tableClass);
                    updateQuery.addValue(
                            latestField,
                            false);
                    updateQuery.addConditions(dbKey.getPKConditions(tableClass));
                    updateQuery.addConditions(latestField.isTrue());

                    updateQuery.execute();
                    save(ctx, t);
                    // delete after the save
                    updateQuery.execute();

                    return true;
                } else {
                    DeleteQuery deleteQuery = ctx.deleteQuery(tableClass);
                    deleteQuery.addConditions(dbKey.getPKConditions(tableClass));
                    return deleteQuery.execute() > 0;
                }
            } finally {
                Db.getCache(table).remove(dbKey);
            }
        });
    }
}
