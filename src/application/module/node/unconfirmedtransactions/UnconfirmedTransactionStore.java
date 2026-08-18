package application.module.node.unconfirmedtransactions;

import application.module.node.Blockchain;
import application.module.node.SignumException;
import application.module.node.Transaction;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.peer.Peer;

import java.util.List;

public interface UnconfirmedTransactionStore {

    boolean put(Transaction transaction, Peer peer) throws SignumException.ValidationException;

    Transaction get(Long transactionId);

    boolean exists(Long transactionId);

    long getFreeSlot(int numberOfBlocks);

    List<Transaction> getAll();

    List<Transaction> getAllFor(Peer peer);

    void remove(Transaction transaction);

    void clear();

    void resetAccountBalances();

    void markFingerPrintsOf(Peer peer, List<Transaction> transactions);

    void removeForgedTransactions(List<Transaction> transactions);

    int getAmount();

    void setBlockchain(Blockchain blockchain);

    void setFluxCapacitor(FluxCapacitor fluxCapacitor);
}
