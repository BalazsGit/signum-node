package application.module.node.db.sql;

import application.module.node.db.BlockDb;
import application.module.node.db.PeerDb;
import application.module.node.db.TransactionDb;
import application.module.node.db.store.Dbs;
import application.module.node.fluxcapacitor.FluxCapacitor;

public class SqlDbs implements Dbs {

    private final BlockDb blockDb;
    private final TransactionDb transactionDb;
    private final PeerDb peerDb;

    /** @deprecated Use {@link #SqlDbs(DbContext)} instead */
    @Deprecated
    public SqlDbs() {
        this.blockDb = new SqlBlockDb();
        this.transactionDb = new SqlTransactionDb();
        this.peerDb = new SqlPeerDb();
        // Wire TransactionDb into SqlBlockDb to break circular dependency
        ((SqlBlockDb) this.blockDb).setTransactionDb(this.transactionDb);
    }

    public SqlDbs(DbContext dbContext) {
        this.blockDb = new SqlBlockDb(dbContext);
        this.transactionDb = new SqlTransactionDb(dbContext);
        this.peerDb = new SqlPeerDb(dbContext);
        // Wire TransactionDb into SqlBlockDb to break circular dependency
        ((SqlBlockDb) this.blockDb).setTransactionDb(this.transactionDb);
    }

    /**
     * Sets the FluxCapacitor on SqlBlockDb for instance-scoped Block construction.
     * Called by NodeCoreContext during initialization.
     *
     * @param fluxCapacitor the node-scoped FluxCapacitor instance
     */
    @Override
    public void setFluxCapacitor(FluxCapacitor fluxCapacitor) {
        ((SqlBlockDb) this.blockDb).setFluxCapacitor(fluxCapacitor);
    }

    @Override
    public BlockDb getBlockDb() {
        return blockDb;
    }

    @Override
    public TransactionDb getTransactionDb() {
        return transactionDb;
    }

    @Override
    public PeerDb getPeerDb() {
        return peerDb;
    }
}