package application.module.node.db.store;

import application.module.node.db.BlockDb;
import application.module.node.db.PeerDb;
import application.module.node.db.TransactionDb;

public interface Dbs {

    BlockDb getBlockDb();

    TransactionDb getTransactionDb();

    PeerDb getPeerDb();

}
