package application.module.node.db.store;

import application.module.node.at.AT;
import application.module.node.at.AT.AtMapEntry;
import application.module.node.db.SignumKey;
import application.module.node.db.VersionedEntityTable;
import application.module.node.util.CollectionWithIndex;

import java.util.Collection;
import java.util.List;

public interface ATStore {

    boolean isATAccountId(Long id);

    List<Long> getOrderedATs();

    AT getAT(Long id);

    AT getAT(Long id, int height);

    Collection<AT> getATs(Collection<Long> ids);

    List<Long> getATsIssuedBy(Long accountId, Long codeHashId, int from, int to);

    Collection<Long> getAllATIds(Long codeHashId);

    SignumKey.LongKeyFactory<AT> getAtDbKeyFactory();

    VersionedEntityTable<AT> getAtTable();

    SignumKey.LongKeyFactory<AT.ATState> getAtStateDbKeyFactory();

    VersionedEntityTable<AT.ATState> getAtStateTable();

    VersionedEntityTable<application.module.node.at.AT.AtMapEntry> getAtMapTable();

    Long findTransaction(int startHeight, int endHeight, Long atID, int numOfTx, long minAmount);

    int findTransactionHeight(Long transactionId, int height, Long atID, long minAmount);

    AtMapEntry getMapValueEntry(long atId, long key1, long key2);

    long getMapValue(long atId, long key1, long key2);

    CollectionWithIndex<AtMapEntry> getMapValues(long atId, long key1, Long value, int from, int to);
}
