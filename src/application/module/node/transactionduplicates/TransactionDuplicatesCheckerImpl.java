package application.module.node.transactionduplicates;

import application.module.logging.context.NodeLoggers;
import application.module.node.Transaction;
import application.module.node.TransactionType;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Objects;

public class TransactionDuplicatesCheckerImpl {

    private final Logger logger;

    private final HashMap<TransactionType, HashMap<String, Transaction>> duplicates = new HashMap<>();

    public TransactionDuplicatesCheckerImpl() {
        this(NodeLoggers.global());
    }

    public TransactionDuplicatesCheckerImpl(NodeLoggers loggers) {
        this.logger = loggers.forClass(TransactionDuplicatesCheckerImpl.class);
    }

    public void clear() {
        duplicates.clear();
    }

    public TransactionDuplicationResult removeCheaperDuplicate(Transaction transaction) {
        final TransactionDuplicationKey transactionDuplicateKey = transaction.getDuplicationKey();

        if (transactionDuplicateKey.equals(TransactionDuplicationKey.IS_ALWAYS_DUPLICATE)) {
            return new TransactionDuplicationResult(true, null);
        } else if (transactionDuplicateKey.equals(TransactionDuplicationKey.IS_NEVER_DUPLICATE)) {
            return new TransactionDuplicationResult(false, null);
        }

        duplicates.computeIfAbsent(transactionDuplicateKey.transactionType, n -> new HashMap<>());

        final HashMap<String, Transaction> transactionOverview = duplicates
                .get(transactionDuplicateKey.transactionType);

        final Transaction possiblyExistingTransaction = transactionOverview.get(transactionDuplicateKey.key);

        if (possiblyExistingTransaction != null && possiblyExistingTransaction.getFeeNqt() >= transaction.getFeeNqt()) {
            logger.debug("Transaction {}: is a duplicate of {} (Type: {})", transaction.getId(),
                    possiblyExistingTransaction.getId(), transaction.getType());
            return new TransactionDuplicationResult(true, transaction);
        } else {
            transactionOverview.put(transactionDuplicateKey.key, transaction);
            return new TransactionDuplicationResult(possiblyExistingTransaction != null, possiblyExistingTransaction);
        }
    }

    public boolean hasAnyDuplicate(Transaction transaction) {
        final TransactionDuplicationKey transactionDuplicateKey = transaction.getDuplicationKey();

        if (transactionDuplicateKey.equals(TransactionDuplicationKey.IS_ALWAYS_DUPLICATE)) {
            return true;
        } else if (transactionDuplicateKey.equals(TransactionDuplicationKey.IS_NEVER_DUPLICATE)) {
            return false;
        }

        duplicates.computeIfAbsent(transactionDuplicateKey.transactionType, n -> new HashMap<>());

        final HashMap<String, Transaction> transactionOverview = duplicates
                .get(transactionDuplicateKey.transactionType);

        if (transactionOverview.containsKey(transactionDuplicateKey.key)) {
            return true;
        } else {
            transactionOverview.put(transactionDuplicateKey.key, transaction);
            return false;
        }
    }

    public void removeTransaction(Transaction transaction) {
        final TransactionDuplicationKey transactionDuplicateKey = transaction.getDuplicationKey();

        if (!transactionDuplicateKey.equals(TransactionDuplicationKey.IS_ALWAYS_DUPLICATE)
                && !transactionDuplicateKey.equals(TransactionDuplicationKey.IS_NEVER_DUPLICATE)
                && duplicates.containsKey(transactionDuplicateKey.transactionType)
                && Objects.equals(
                        duplicates.get(transactionDuplicateKey.transactionType).get(transactionDuplicateKey.key),
                        transaction)) {
            duplicates.get(transactionDuplicateKey.transactionType).remove(transactionDuplicateKey.key);
        }
    }
}
