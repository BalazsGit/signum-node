package application.module.node.services;

import application.module.node.IndirectIncoming;
import application.module.node.Transaction;

public interface IndirectIncomingService {
    void processTransaction(Transaction transaction);

    boolean isIndirectlyReceiving(Transaction transaction, long accountId);

    public void rollback(int height);

    /**
     * Retrieves an indirect incoming record for the given account and transaction.
     *
     * @param accountId     the recipient account ID
     * @param transactionId the transaction ID
     * @return the IndirectIncoming record, or null if not found
     */
    IndirectIncoming getIndirectIncoming(long accountId, long transactionId);
}
