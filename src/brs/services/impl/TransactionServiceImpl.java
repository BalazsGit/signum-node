package brs.services.impl;

import java.util.HashMap;

import brs.*;
import brs.services.AccountService;
import brs.services.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransactionServiceImpl implements TransactionService {

  private final AccountService accountService;
  private final Blockchain blockchain;
  private final HashMap<Long, Transaction> accountCommitmentRemovals = new HashMap<>();
  private final Logger logger = LoggerFactory.getLogger(TransactionServiceImpl.class);

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
      throw new SignumException.NotCurrentlyValidException(String.format("Transaction fee %d less than minimum fee %d at height %d",
          transaction.getFeeNqt(), minimumFeeNQT, blockchain.getHeight()));
    }
  }

  @Override
  public void startNewBlock() {
    accountCommitmentRemovals.clear();
  }

  @Override
  // this should be call only once per transaction
  public boolean applyUnconfirmed(Transaction transaction) {
    logger.warn("applyUnconfirmed -> transaction.getStringId(): " + transaction.getStringId() + "\n");
    if(transaction.getType() == TransactionType.SignaMining.COMMITMENT_REMOVE) {
      // we only accept one removal per account per block
      logger.warn("transaction.getType() == COMMITMENT_REMOVE\n");
      if(accountCommitmentRemovals.get(transaction.getSenderId()) != null) {
        logger.warn("accountCommitmentRemovals.get(transaction.getSenderId()): " + accountCommitmentRemovals.get(transaction.getSenderId()) + "\n");
        return false;
      }
      accountCommitmentRemovals.put(transaction.getSenderId(), transaction);
    }
    Account senderAccount = accountService.getAccount(transaction.getSenderId());
/*
    if(senderAccount == null) {
      logger.warn("Sender Account is null");
    }
    else {
      logger.warn("Sender Account is not null");
    }
*/
/*
    if(transaction.getType().applyUnconfirmed(transaction, senderAccount)) {
      logger.warn("Transection Apply Unconfirmed: true");
    }
    else {
      logger.warn("Transection Apply Unconfirmed: false");
    }

    if(senderAccount != null && transaction.getType().applyUnconfirmed(transaction, senderAccount)){
      logger.warn("return true");
    }
    else {
      logger.warn("return false");
    }

    if(senderAccount != null && transaction.getType().applyUnconfirmed(transaction, senderAccount)){
      logger.warn("return2 true");
    }
    else {
      logger.warn("return2 false");
    }
*/
    logger.warn("senderAccount: " + senderAccount + "\n");
    logger.warn("transaction.getType().applyUnconfirmed(transaction, senderAccount): " + transaction.getType().applyUnconfirmed(transaction, senderAccount) + "\n");
    return senderAccount != null && transaction.getType().applyUnconfirmed(transaction, senderAccount);
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
