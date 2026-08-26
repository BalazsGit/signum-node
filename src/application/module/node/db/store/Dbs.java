package application.module.node.db.store;

import application.module.node.db.BlockDb;
import application.module.node.db.PeerDb;
import application.module.node.db.TransactionDb;
import application.module.node.fluxcapacitor.FluxCapacitor;

public interface Dbs {

    BlockDb getBlockDb();

    TransactionDb getTransactionDb();

    PeerDb getPeerDb();

    /**
     * Wires the node-scoped FluxCapacitor into the underlying DB
     * implementations. Must be called after the FluxCapacitor is created
     * (it depends on Blockchain, which depends on these DBs).
     */
    void setFluxCapacitor(FluxCapacitor fluxCapacitor);

}
