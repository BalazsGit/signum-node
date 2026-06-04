package application.module.brs.db.store;

import application.module.brs.Escrow;
import application.module.brs.Transaction;
import application.module.brs.db.SignumKey;
import application.module.brs.db.VersionedEntityTable;
import application.module.brs.db.sql.DbKey;

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
