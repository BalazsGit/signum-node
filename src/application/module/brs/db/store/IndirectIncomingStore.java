package application.module.brs.db.store;

import java.util.Collection;

import application.module.brs.IndirectIncoming;

public interface IndirectIncomingStore {
    void addIndirectIncomings(Collection<IndirectIncoming> indirectIncomings);

    Collection<IndirectIncoming> getIndirectIncomings(long accountId, int from, int to);

    public IndirectIncoming getIndirectIncoming(long accountId, long transactionId);

    public void rollback(int height);
}
