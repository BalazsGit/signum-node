package application.module.brs.db.store;

import application.module.brs.db.BlockDb;
import application.module.brs.db.PeerDb;
import application.module.brs.db.TransactionDb;

public interface Dbs {

    BlockDb getBlockDb();

    TransactionDb getTransactionDb();

    PeerDb getPeerDb();

}
