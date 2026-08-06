package application.module.node.db.sql;

import application.module.node.db.VersionedValuesTable;
import application.module.node.db.store.DerivedTableManager;
import org.jooq.impl.TableImpl;

public abstract class VersionedValuesSqlTable<T, V> extends ValuesSqlTable<T, V> implements VersionedValuesTable<T, V> {
    VersionedValuesSqlTable(String table, TableImpl<?> tableClass, DbKey.Factory<T> dbKeyFactory,
            DerivedTableManager derivedTableManager, DbContext dbContext) {
        super(table, tableClass, dbKeyFactory, true, derivedTableManager, dbContext);
    }

    @Override
    public final void rollback(int height) {
        VersionedEntitySqlTable.rollback(dbContext, table, tableClass, heightField, latestField, height, dbKeyFactory);
    }

    @Override
    public final void trim(int height) {
        VersionedEntitySqlTable.trim(dbContext, tableClass, heightField, height, dbKeyFactory);
    }
}