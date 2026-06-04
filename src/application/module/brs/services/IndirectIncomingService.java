package application.module.brs.services;

import application.module.brs.Transaction;

public interface IndirectIncomingService {
    void processTransaction(Transaction transaction);

    boolean isIndirectlyReceiving(Transaction transaction, long accountId);

    public void rollback(int height);
}
