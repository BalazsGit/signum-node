package application.module.node.services.impl;

import java.util.HashMap;

import application.module.node.*;
import application.module.node.services.AccountService;
import application.module.node.services.TransactionService;

public class TransactionServiceImpl implements TransactionService {

    private final AccountService accountService;
    private final Blockchain blockchain;
    private final HashMap<Long, Transaction> accountCommitmentRemovals = new HashMap<>();

    public TransactionServiceImpl(AccountService accountService, Blockchain blockchain) {
        this.accountService = accountService;
        this.blockchain = blockchain;
    }

    @Override
    public boolean verifyPublicKey(Transaction transaction) {
        Account account = accountService.getAccount(transaction.getSenderId());
        if (account == null) {
            return false;
        }
        if (transaction.getSignature() == null) {
            return false;
        }
        return account.setOrVerify(transaction.getSenderPublicKey(), transaction.getHeight());
    }

    @Override
    public void validate(Transaction transaction) throws SignumException.ValidationException {
        for (Appendix.AbstractAppendix appendage : transaction.getAppendages()) {
            appendage.validate(transaction);
        }
        long minimumFeeNQT = transaction.getType().minimumFeeNQT(blockchain.getHeight(), transaction);
        if (transaction.getFeeNqt() < minimumFeeNQT) {
            throw new SignumException.NotCurrentlyValidException(
                    String.format("Transaction fee %d less than minimum fee %d at height %d",
                            transaction.getFeeNqt(), minimumFeeNQT, blockchain.getHeight()));
        }
    }

    @Override
    public void startNewBlock() {
        accountCommitmentRemovals.clear();
    }

    @Override
    public ApplyResult applyUnconfirmed(Transaction transaction) {
        if (transaction.getType() == TransactionType.SignaMining.COMMITMENT_REMOVE) {
            // we only accept one removal per account per block
            if (accountCommitmentRemovals.get(transaction.getSenderId()) != null)
                return new ApplyResult(ApplyResult.Reason.DUPLICATE_COMMITMENT_REMOVAL, 0L,
                        transaction.getType().calculateTotalAmountNQT(transaction));
            accountCommitmentRemovals.put(transaction.getSenderId(), transaction);
        }
        Account senderAccount = accountService.getAccount(transaction.getSenderId());
        if (senderAccount == null) {
            return new ApplyResult(ApplyResult.Reason.MISSING_SENDER_ACCOUNT, 0L,
                    transaction.getType().calculateTotalAmountNQT(transaction));
        }
        boolean applied = transaction.getType().applyUnconfirmed(transaction, senderAccount);
        if (applied) {
            return new ApplyResult(ApplyResult.Reason.APPLIED, 0L, 0L);
        }
        // After a failed apply the unconfirmed balance is restored (or untouched),
        // so it still reflects the sender's balance before this transaction.
        return new ApplyResult(ApplyResult.Reason.REJECTED, senderAccount.getUnconfirmedBalanceNqt(),
                transaction.getType().calculateTotalAmountNQT(transaction));
    }

    @Override
    public void apply(Transaction transaction) {
        Account senderAccount = accountService.getAccount(transaction.getSenderId());
        senderAccount.apply(transaction.getSenderPublicKey(), transaction.getHeight());
        Account recipientAccount = accountService.getOrAddAccount(transaction.getRecipientId());
        for (Appendix.AbstractAppendix appendage : transaction.getAppendages()) {
            appendage.apply(transaction, senderAccount, recipientAccount);
        }
    }

    @Override
    public void undoUnconfirmed(Transaction transaction) {
        final Account senderAccount = accountService.getAccount(transaction.getSenderId());
        transaction.getType().undoUnconfirmed(transaction, senderAccount);
    }

}
