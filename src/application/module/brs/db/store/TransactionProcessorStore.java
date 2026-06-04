package application.module.brs.db.store;

import application.module.brs.Transaction;
import application.module.brs.db.SignumKey;
import application.module.brs.db.sql.EntitySqlTable;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

public interface TransactionProcessorStore {
    // WATCH: BUSINESS-LOGIC
    void processLater(Collection<Transaction> transactions);

    SignumKey.LongKeyFactory<Transaction> getUnconfirmedTransactionDbKeyFactory();

    Set<Transaction> getLostTransactions();

    Map<Long, Integer> getLostTransactionHeights();

    EntitySqlTable<Transaction> getUnconfirmedTransactionTable();

    int deleteTransaction(Transaction transaction);

    boolean hasTransaction(long transactionId);
}
