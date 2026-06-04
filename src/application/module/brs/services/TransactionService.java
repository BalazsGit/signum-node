package application.module.brs.services;

import application.module.brs.SignumException;
import application.module.brs.Transaction;

public interface TransactionService {

    boolean verifyPublicKey(Transaction transaction);

    void validate(Transaction transaction) throws SignumException.ValidationException;

    void startNewBlock();

    boolean applyUnconfirmed(Transaction transaction);

    void apply(Transaction transaction);

    void undoUnconfirmed(Transaction transaction);
}
