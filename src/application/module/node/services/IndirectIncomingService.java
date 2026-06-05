package application.module.node.services;

import application.module.node.Transaction;

public interface IndirectIncomingService {
    void processTransaction(Transaction transaction);

    boolean isIndirectlyReceiving(Transaction transaction, long accountId);

    public void rollback(int height);
}
