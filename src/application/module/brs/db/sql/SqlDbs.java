package application.module.brs.db.sql;

import application.module.brs.db.BlockDb;
import application.module.brs.db.PeerDb;
import application.module.brs.db.TransactionDb;
import application.module.brs.db.store.Dbs;

public class SqlDbs implements Dbs {

    private final BlockDb blockDb;
    private final TransactionDb transactionDb;
    private final PeerDb peerDb;

    public SqlDbs() {
        this.blockDb = new SqlBlockDb();
        this.transactionDb = new SqlTransactionDb();
        this.peerDb = new SqlPeerDb();
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
