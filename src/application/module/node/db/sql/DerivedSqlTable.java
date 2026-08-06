package application.module.node.db.sql;

import application.module.node.db.DerivedTable;
import application.module.node.db.store.DerivedTableManager;
import org.jooq.Field;
import org.jooq.impl.TableImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class DerivedSqlTable implements DerivedTable {
    private static final Logger logger = LoggerFactory.getLogger(DerivedSqlTable.class);
    final String table;
    final TableImpl<?> tableClass;

    final Field<Integer> heightField;
    final Field<Boolean> latestField;

    /** Reference to the DerivedTableManager for accessing runtime-configurable values. */
    protected final DerivedTableManager derivedTableManager;

    /** Reference to the instance-scoped DbContext for database operations. */
    protected final DbContext dbContext;

    DerivedSqlTable(String table, TableImpl<?> tableClass, DerivedTableManager derivedTableManager, DbContext dbContext) {
        this.table = table;
        this.tableClass = tableClass;
        this.derivedTableManager = derivedTableManager;
        this.dbContext = dbContext;
        logger.trace("Creating derived table for {}", table);
        derivedTableManager.registerDerivedTable(this);
        this.heightField = tableClass.field("height", Integer.class);
        this.latestField = tableClass.field("latest", Boolean.class);
    }

    @Override
    public String getTable() {
        return table;
    }

    @Override
    public void rollback(int height) {
        if (!dbContext.isInTransaction()) {
            throw new IllegalStateException("Not in transaction");
        }
        dbContext.useDSLContext(ctx -> {
            ctx.delete(tableClass).where(heightField.gt(height)).execute();
        });
    }

    @Override
    public void truncate() {
        if (!dbContext.isInTransaction()) {
            throw new IllegalStateException("Not in transaction");
        }
        dbContext.useDSLContext(ctx -> {
            ctx.delete(tableClass).execute();
        });
    }

    @Override
    public void trim(int height) {
        // nothing to trim
    }

    @Override
    public void finish() {

    }

    @Override
    public void optimize() {
        dbContext.optimizeTable(table);
    }
}
