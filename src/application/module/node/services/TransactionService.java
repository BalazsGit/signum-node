package application.module.node.services;

import application.module.node.SignumException;
import application.module.node.Transaction;

public interface TransactionService {

    /**
     * Result of applying an unconfirmed transaction during block processing.
     * <p>
     * Replaces the former {@code boolean} return value of
     * {@link #applyUnconfirmed(Transaction)} so that rejections can be
     * diagnosed from the logs (missing sender account, insufficient balance,
     * duplicate commitment removal) instead of a plain "not accepted".
     */
    class ApplyResult {

        public enum Reason {
            /** The transaction was applied to the unconfirmed state. */
            APPLIED,
            /** The sender account is not present in the local database. */
            MISSING_SENDER_ACCOUNT,
            /** A second commitment removal of the same sender in this block. */
            DUPLICATE_COMMITMENT_REMOVAL,
            /** The transaction type rejected it (insufficient unconfirmed balance
             * or rejected attachment). */
            REJECTED
        }

        private final Reason reason;
        private final long unconfirmedBalanceNqt;
        private final long requiredAmountNqt;

        public ApplyResult(Reason reason, long unconfirmedBalanceNqt, long requiredAmountNqt) {
            this.reason = reason;
            this.unconfirmedBalanceNqt = unconfirmedBalanceNqt;
            this.requiredAmountNqt = requiredAmountNqt;
        }

        public boolean isApplied() {
            return reason == Reason.APPLIED;
        }

        public Reason getReason() {
            return reason;
        }

        /** The sender's unconfirmed balance (NQT) at the time of the rejection. */
        public long getUnconfirmedBalanceNqt() {
            return unconfirmedBalanceNqt;
        }

        /** The amount (NQT, including fee and attachment) the transaction required. */
        public long getRequiredAmountNqt() {
            return requiredAmountNqt;
        }
    }

    boolean verifyPublicKey(Transaction transaction);

    void validate(Transaction transaction) throws SignumException.ValidationException;

    void startNewBlock();

    ApplyResult applyUnconfirmed(Transaction transaction);

    void apply(Transaction transaction);

    void undoUnconfirmed(Transaction transaction);
}
