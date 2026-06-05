package application.module.node.db.store;

import application.module.node.Escrow;
import application.module.node.Transaction;
import application.module.node.db.SignumKey;
import application.module.node.db.VersionedEntityTable;
import application.module.node.db.sql.DbKey;

import java.util.Collection;
import java.util.List;

public interface EscrowStore {

    SignumKey.LongKeyFactory<Escrow> getEscrowDbKeyFactory();

    VersionedEntityTable<Escrow> getEscrowTable();

    DbKey.LinkKeyFactory<Escrow.Decision> getDecisionDbKeyFactory();

    VersionedEntityTable<Escrow.Decision> getDecisionTable();

    Collection<Escrow> getEscrowTransactionsByParticipant(Long accountId);

    List<Transaction> getResultTransactions();

    Collection<Escrow.Decision> getDecisions(Long id);
}
