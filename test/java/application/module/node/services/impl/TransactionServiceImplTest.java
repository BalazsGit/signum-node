package application.module.node.services.impl;

import application.module.node.Account;
import application.module.node.Blockchain;
import application.module.node.Transaction;
import application.module.node.TransactionType;
import application.module.node.common.AbstractUnitTest;
import application.module.node.services.AccountService;
import application.module.node.services.TransactionService;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TransactionServiceImplTest extends AbstractUnitTest {

    private static final long SENDER_ID = 12345L;
    private static final long REQUIRED_AMOUNT_NQT = 1001000000L;
    private static final long UNCONFIRMED_BALANCE_NQT = 500000000L;

    private TransactionServiceImpl transactionService;

    private AccountService accountService;
    private Transaction transaction;
    private TransactionType transactionType;

    @Before
    public void setUp() {
        accountService = mock(AccountService.class);
        Blockchain blockchain = mock(Blockchain.class);
        transactionService = new TransactionServiceImpl(accountService, blockchain);

        transaction = mock(Transaction.class);
        transactionType = mock(TransactionType.class);
        lenient().when(transaction.getType()).thenReturn(transactionType);
        lenient().when(transaction.getSenderId()).thenReturn(SENDER_ID);
        lenient().when(transaction.getAmountNqt()).thenReturn(1000000000L);
        lenient().when(transaction.getFeeNqt()).thenReturn(1000000L);
        lenient().when(transaction.getReferencedTransactionFullHash()).thenReturn(null);

        lenient().when(transactionType.calculateTotalAmountNQT(transaction)).thenReturn(REQUIRED_AMOUNT_NQT);
        lenient().when(transactionType.applyUnconfirmed(eq(transaction), any(Account.class))).thenReturn(false);
    }

    @Test
    public void applyUnconfirmedApplied() {
        Account senderAccount = mock(Account.class);
        when(senderAccount.getUnconfirmedBalanceNqt()).thenReturn(REQUIRED_AMOUNT_NQT);
        when(accountService.getAccount(SENDER_ID)).thenReturn(senderAccount);
        when(transactionType.applyUnconfirmed(transaction, senderAccount)).thenReturn(true);

        TransactionService.ApplyResult result = transactionService.applyUnconfirmed(transaction);

        assertTrue(result.isApplied());
        assertEquals(TransactionService.ApplyResult.Reason.APPLIED, result.getReason());
    }

    @Test
    public void applyUnconfirmedMissingSenderAccount() {
        when(accountService.getAccount(SENDER_ID)).thenReturn(null);

        TransactionService.ApplyResult result = transactionService.applyUnconfirmed(transaction);

        assertFalse(result.isApplied());
        assertEquals(TransactionService.ApplyResult.Reason.MISSING_SENDER_ACCOUNT, result.getReason());
        assertEquals(REQUIRED_AMOUNT_NQT, result.getRequiredAmountNqt());
    }

    @Test
    public void applyUnconfirmedRejectedInsufficientBalance() {
        Account senderAccount = mock(Account.class);
        when(senderAccount.getUnconfirmedBalanceNqt()).thenReturn(UNCONFIRMED_BALANCE_NQT);
        when(accountService.getAccount(SENDER_ID)).thenReturn(senderAccount);
        when(transactionType.applyUnconfirmed(transaction, senderAccount)).thenReturn(false);

        TransactionService.ApplyResult result = transactionService.applyUnconfirmed(transaction);

        assertFalse(result.isApplied());
        assertEquals(TransactionService.ApplyResult.Reason.REJECTED, result.getReason());
        assertEquals(UNCONFIRMED_BALANCE_NQT, result.getUnconfirmedBalanceNqt());
        assertEquals(REQUIRED_AMOUNT_NQT, result.getRequiredAmountNqt());
    }

    @Test
    public void applyUnconfirmedDuplicateCommitmentRemoval() {
        // The duplicate check is an identity check, so the real type constant must be used.
        when(transaction.getType()).thenReturn(TransactionType.SignaMining.COMMITMENT_REMOVE);

        // First call: no account yet, the removal is registered, the type is not applied.
        when(accountService.getAccount(anyLong())).thenReturn(null);
        TransactionService.ApplyResult firstResult = transactionService.applyUnconfirmed(transaction);
        assertFalse(firstResult.isApplied());
        assertEquals(TransactionService.ApplyResult.Reason.MISSING_SENDER_ACCOUNT, firstResult.getReason());

        // Second call: the removal of the same sender in the same block is a duplicate.
        TransactionService.ApplyResult secondResult = transactionService.applyUnconfirmed(transaction);

        assertFalse(secondResult.isApplied());
        assertEquals(TransactionService.ApplyResult.Reason.DUPLICATE_COMMITMENT_REMOVAL, secondResult.getReason());
        // amount (1 000 000 000) + fee (1 000 000), no attachment, no referenced transaction hash
        assertEquals(1001000000L, secondResult.getRequiredAmountNqt());
    }
}
