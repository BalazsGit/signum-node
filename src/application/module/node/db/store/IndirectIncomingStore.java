package application.module.node.db.store;

import java.util.Collection;

import application.module.node.IndirectIncoming;

public interface IndirectIncomingStore {
    void addIndirectIncomings(Collection<IndirectIncoming> indirectIncomings);

    Collection<IndirectIncoming> getIndirectIncomings(long accountId, int from, int to);

    public IndirectIncoming getIndirectIncoming(long accountId, long transactionId);

    public void rollback(int height);
}
