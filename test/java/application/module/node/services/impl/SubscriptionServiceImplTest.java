package application.module.node.services.impl;

import application.module.node.Account;
import application.module.node.Block;
import application.module.node.Blockchain;
import application.module.node.Constants;
import application.module.node.Subscription;
import application.module.node.TransactionApplyContext;
import application.module.node.TransactionType;
import application.module.node.common.AbstractUnitTest;
import application.module.node.common.QuickMocker;
import application.module.node.db.SignumKey;
import application.module.node.db.SignumKey.LongKeyFactory;
import application.module.node.db.TransactionDb;
import application.module.node.db.VersionedEntityTable;
import application.module.node.db.store.AliasStore;
import application.module.node.db.store.SubscriptionStore;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.services.AccountService;
import application.module.node.services.AliasService;
import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SubscriptionServiceImplTest extends AbstractUnitTest {

    private SubscriptionServiceImpl t;

    private SubscriptionStore mockSubscriptionStore;
    private VersionedEntityTable<Subscription> mockSubscriptionTable;
    private LongKeyFactory<Subscription> mockSubscriptionDbKeyFactory;
    private TransactionDb transactionDb;
    private Blockchain blockchain;
    private FluxCapacitor fluxCapacitor;
    private AliasService aliasService;
    private AliasStore aliasStore;
    private AccountService accountService;

    @Before
    public void setUp() {
        mockSubscriptionStore = mock(SubscriptionStore.class);
        mockSubscriptionTable = mock(VersionedEntityTable.class);
        mockSubscriptionDbKeyFactory = mock(LongKeyFactory.class);

        when(mockSubscriptionStore.getSubscriptionTable()).thenReturn(mockSubscriptionTable);
        when(mockSubscriptionStore.getSubscriptionDbKeyFactory()).thenReturn(mockSubscriptionDbKeyFactory);

        transactionDb = mock(TransactionDb.class);
        blockchain = mock(Blockchain.class);
        fluxCapacitor = QuickMocker.fluxCapacitorEnabledFunctionalities(FluxValues.PRE_POC2, FluxValues.DIGITAL_GOODS_STORE);
        aliasService = mock(AliasService.class);
        aliasStore = mock(AliasStore.class);
        accountService = mock(AccountService.class);

        t = new SubscriptionServiceImpl(mockSubscriptionStore, transactionDb, blockchain, fluxCapacitor, aliasService, aliasStore, accountService);
    }

    @Test
    public void getSubscription() {
        final long subscriptionId = 123L;

        final SignumKey mockSubscriptionKey = mock(SignumKey.class);

        final Subscription mockSubscription = mock(Subscription.class);

        when(mockSubscriptionDbKeyFactory.newKey(eq(subscriptionId))).thenReturn(mockSubscriptionKey);
        when(mockSubscriptionTable.get(eq(mockSubscriptionKey))).thenReturn(mockSubscription);

        assertEquals(mockSubscription, t.getSubscription(subscriptionId));
    }

    @Test
    public void getSubscriptionsByParticipant() {
        long accountId = 123L;

        Collection<Subscription> mockSubscriptionIterator = mockCollection();
        when(mockSubscriptionStore.getSubscriptionsByParticipant(eq(accountId))).thenReturn(mockSubscriptionIterator);

        assertEquals(mockSubscriptionIterator, t.getSubscriptionsByParticipant(accountId));
    }

    @Test
    public void getSubscriptionsToId() {
        long accountId = 123L;

        Collection<Subscription> mockSubscriptionIterator = mockCollection();
        when(mockSubscriptionStore.getSubscriptionsToId(eq(accountId))).thenReturn(mockSubscriptionIterator);

        assertEquals(mockSubscriptionIterator, t.getSubscriptionsToId(accountId));
    }

    // ── Multi-profile isolation regression (v5) ─────────────────────────────
    // The working-state collections used to be `static`, i.e. shared across ALL
    // profiles in the JVM. Two concurrent profiles could then clobber each
    // other's pending subscription lists (profile A applying profile B's
    // subscriptions against A's own accounts → NPE / cross-profile writes).

    @Test
    public void applyConfirmed_appliesOnlyOwnProfileSubscriptions() {
        int timestamp = 100_000;
        int height = 5_000;
        Block block = mock(Block.class);
        when(block.getId()).thenReturn(1L);
        when(block.getHeight()).thenReturn(height);
        when(block.getTimestamp()).thenReturn(timestamp);

        // ── Profile A ──
        Subscription subA = new Subscription(111L, 333L, 9001L, 1_000L, 1440, timestamp, mock(SignumKey.class));
        SubscriptionStore storeA = mock(SubscriptionStore.class);
        when(storeA.getSubscriptionTable()).thenReturn(mock(VersionedEntityTable.class));
        when(storeA.getSubscriptionDbKeyFactory()).thenReturn(mock(LongKeyFactory.class));
        when(storeA.getUpdateSubscriptions(timestamp)).thenReturn(List.of(subA));

        Account senderA = mock(Account.class);
        when(senderA.getId()).thenReturn(111L);
        when(senderA.getPublicKey()).thenReturn(new byte[32]);
        Account.Balance balanceA = mock(Account.Balance.class);
        when(balanceA.getUnconfirmedBalanceNqt()).thenReturn(Constants.MAX_BALANCE_NQT);
        Account recipientA = mock(Account.class);
        when(recipientA.getId()).thenReturn(333L);

        AccountService accountServiceA = mock(AccountService.class);
        when(accountServiceA.getAccount(111L)).thenReturn(senderA);
        when(accountServiceA.getAccountBalance(111L)).thenReturn(balanceA);
        when(accountServiceA.getOrAddAccount(333L)).thenReturn(recipientA);

        SubscriptionServiceImpl profileA = new SubscriptionServiceImpl(
                storeA, transactionDb, blockchain, fluxCapacitor, aliasService, aliasStore, accountServiceA);

        // ── Profile B (different accounts + account service) ──
        Subscription subB = new Subscription(222L, 444L, 9002L, 2_000L, 1440, timestamp, mock(SignumKey.class));
        SubscriptionStore storeB = mock(SubscriptionStore.class);
        when(storeB.getSubscriptionTable()).thenReturn(mock(VersionedEntityTable.class));
        when(storeB.getSubscriptionDbKeyFactory()).thenReturn(mock(LongKeyFactory.class));
        when(storeB.getUpdateSubscriptions(timestamp)).thenReturn(List.of(subB));

        Account senderB = mock(Account.class);
        when(senderB.getId()).thenReturn(222L);
        Account.Balance balanceB = mock(Account.Balance.class);
        when(balanceB.getUnconfirmedBalanceNqt()).thenReturn(Constants.MAX_BALANCE_NQT);
        Account recipientB = mock(Account.class);
        when(recipientB.getId()).thenReturn(444L);

        AccountService accountServiceB = mock(AccountService.class);
        when(accountServiceB.getAccount(222L)).thenReturn(senderB);
        when(accountServiceB.getAccountBalance(222L)).thenReturn(balanceB);
        when(accountServiceB.getOrAddAccount(444L)).thenReturn(recipientB);

        SubscriptionServiceImpl profileB = new SubscriptionServiceImpl(
                storeB, transactionDb, blockchain, fluxCapacitor, aliasService, aliasStore, accountServiceB);

        // Bind the per-thread transaction apply context required by Transaction ID
        // computation (production binds this on the import threads via ThreadPool).
        TransactionType.bindContext(new TransactionApplyContext(
                blockchain, fluxCapacitor, accountServiceA, null, null, null, null, null,
                null, null, null, null, null));
        try {
            // Simulate the cross-profile import interleaving: A applies unconfirmed,
            // then B applies unconfirmed (with the old `static` shared lists this
            // wiped profile A's pending list), then A applies confirmed.
            profileA.applyUnconfirmed(timestamp, height);
            profileB.applyUnconfirmed(timestamp, height);
            profileA.applyConfirmed(block, height);

            // Profile A must apply ITS OWN subscription against ITS OWN accounts…
            verify(accountServiceA).addToBalanceNQT(senderA, -(1_000L + Constants.ONE_SIGNA));
            verify(accountServiceA).addToBalanceAndUnconfirmedBalanceNQT(recipientA, 1_000L);
            // …and must never touch profile B's accounts.
            verify(accountServiceA, never()).addToBalanceNQT(eq(senderB), anyLong());
            verify(accountServiceA, never()).addToBalanceAndUnconfirmedBalanceNQT(eq(recipientB), anyLong());
        } finally {
            TransactionType.clearContext();
        }
    }

    @Test
    public void applyConfirmed_senderAccountMissing_failsLoudlyWithSubscriptionContext() {
        int timestamp = 100_000;
        int height = 5_000;
        Block block = mock(Block.class);
        when(block.getId()).thenReturn(1L);
        when(block.getHeight()).thenReturn(height);
        when(block.getTimestamp()).thenReturn(timestamp);

        Subscription subA = new Subscription(111L, 333L, 9001L, 1_000L, 1440, timestamp, mock(SignumKey.class));
        SubscriptionStore storeA = mock(SubscriptionStore.class);
        when(storeA.getSubscriptionTable()).thenReturn(mock(VersionedEntityTable.class));
        when(storeA.getSubscriptionDbKeyFactory()).thenReturn(mock(LongKeyFactory.class));
        when(storeA.getUpdateSubscriptions(timestamp)).thenReturn(List.of(subA));

        Account senderA = mock(Account.class);
        when(senderA.getId()).thenReturn(111L);
        Account.Balance balanceA = mock(Account.Balance.class);
        when(balanceA.getUnconfirmedBalanceNqt()).thenReturn(Constants.MAX_BALANCE_NQT);
        AccountService accountServiceA = mock(AccountService.class);
        when(accountServiceA.getAccount(111L)).thenReturn(senderA);
        when(accountServiceA.getAccountBalance(111L)).thenReturn(balanceA);

        SubscriptionServiceImpl profileA = new SubscriptionServiceImpl(
                storeA, transactionDb, blockchain, fluxCapacitor, aliasService, aliasStore, accountServiceA);

        profileA.applyUnconfirmed(timestamp, height);

        // The sender account vanishes from the local state between the unconfirmed
        // and the confirmed apply (e.g. after a partial rollback).
        when(accountServiceA.getAccount(111L)).thenReturn(null);

        try {
            profileA.applyConfirmed(block, height);
            fail("expected IllegalStateException with subscription context");
        } catch (IllegalStateException e) {
            if (!e.getMessage().contains("subscriptionId=9001") || !e.getMessage().contains("senderId=111")) {
                fail("message must carry the subscription + sender ids: " + e.getMessage());
            }
        }
    }
}
