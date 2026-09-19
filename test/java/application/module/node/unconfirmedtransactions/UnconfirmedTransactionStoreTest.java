package application.module.node.unconfirmedtransactions;

import application.module.node.*;
import application.module.node.Attachment.MessagingAliasSell;
import application.module.node.SignumException.NotCurrentlyValidException;
import application.module.node.SignumException.ValidationException;
import application.module.node.Transaction.Builder;
import application.module.node.common.QuickMocker;
import application.module.node.common.TestConstants;
import application.module.node.db.SignumKey;
import application.module.node.db.SignumKey.LongKeyFactory;
import application.module.node.db.TransactionDb;
import application.module.node.db.VersionedBatchEntityTable;
import application.module.node.db.store.AccountStore;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.peer.Peer;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.module.node.services.AccountService;
import application.module.node.services.TimeService;
import application.module.node.services.impl.TimeServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;

import java.util.List;

import static application.module.node.Attachment.ORDINARY_PAYMENT;
import static application.module.node.Constants.FEE_QUANT_SIP3;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class UnconfirmedTransactionStoreTest {

    private BlockchainImpl mockBlockChain;

    private AccountStore accountStoreMock;
    private VersionedBatchEntityTable<Account> accountTableMock;
    private LongKeyFactory<Account> accountSignumKeyFactoryMock;

    private TimeService timeService = new TimeServiceImpl();
    private UnconfirmedTransactionStore t;
    private FluxCapacitor fluxCapacitor;
    private MockedStatic<Signum> signumStatic;

    @BeforeEach
    public void setUp() {
        // Mock the Signum statics with un-stubbed defaults so the store stays
        // hermetic (same semantics as the former PowerMock setup).
        signumStatic = mockStatic(Signum.class);

        final PropertyService mockPropertyService = mock(PropertyService.class);
        when(mockPropertyService.getInt(eq(Props.P2P_MAX_UNCONFIRMED_TRANSACTIONS))).thenReturn(8192);
        when(mockPropertyService.getInt(eq(Props.P2P_MAX_PERCENTAGE_UNCONFIRMED_TRANSACTIONS_FULL_HASH_REFERENCE)))
                .thenReturn(5);
        when(mockPropertyService.getInt(eq(Props.P2P_MAX_UNCONFIRMED_TRANSACTIONS_RAW_SIZE_BYTES_TO_SEND)))
                .thenReturn(175000);

        mockBlockChain = mock(BlockchainImpl.class);
        final Block mockLastBlock = mock(Block.class);
        when(mockLastBlock.getHeight()).thenReturn(20);
        when(mockBlockChain.getLastBlock()).thenReturn(mockLastBlock);

        accountStoreMock = mock(AccountStore.class);
        accountTableMock = mock(VersionedBatchEntityTable.class);
        accountSignumKeyFactoryMock = mock(LongKeyFactory.class);
        TransactionDb transactionDbMock = mock(TransactionDb.class, Answers.RETURNS_DEFAULTS);
        when(accountStoreMock.getAccountTable()).thenReturn(accountTableMock);
        when(accountStoreMock.getAccountKeyFactory()).thenReturn(accountSignumKeyFactoryMock);

        final Account mockAccount = mock(Account.class);
        final SignumKey mockAccountKey = mock(SignumKey.class);
        when(accountSignumKeyFactoryMock.newKey(eq(123L))).thenReturn(mockAccountKey);
        when(accountTableMock.get(eq(mockAccountKey))).thenReturn(mockAccount);
        when(mockAccount.getUnconfirmedBalanceNqt()).thenReturn(Constants.MAX_BALANCE_NQT);

        fluxCapacitor = QuickMocker.fluxCapacitorEnabledFunctionalities(FluxValues.PRE_POC2,
                FluxValues.DIGITAL_GOODS_STORE);

        doReturn(Constants.FEE_QUANT_SIP3).when(fluxCapacitor).getValue(eq(FluxValues.FEE_QUANT), anyInt());
        doReturn(Constants.FEE_QUANT_SIP3).when(fluxCapacitor).getValue(eq(FluxValues.FEE_QUANT));

        // Transaction.sign()/getBytes() and transaction type validation resolve the
        // TransactionApplyContext from the current thread (production binds it on the
        // import threads via ThreadPool); bind an equivalent test context here.
        TransactionType.bindContext(new TransactionApplyContext(
                mockBlockChain, fluxCapacitor, mock(AccountService.class),
                null, null, null, null, null, null, null, null, null, null));

        t = new UnconfirmedTransactionStoreImpl(timeService, mockPropertyService, accountStoreMock, transactionDbMock,
                null);
        t.setFluxCapacitor(fluxCapacitor);
        t.setBlockchain(mockBlockChain);
    }

    @AfterEach
    public void tearDown() {
        signumStatic.close();
        TransactionType.clearContext();
    }

    @DisplayName("When we add Unconfirmed Transactions to the store, they can be retrieved")
    @Test
    public void transactionsCanGetRetrievedAfterAddingThemToStore() throws ValidationException {

        when(mockBlockChain.getHeight()).thenReturn(20);

        for (int i = 1; i <= 100; i++) {
            Transaction transaction = new Transaction.Builder((byte) 1, TestConstants.TEST_PUBLIC_KEY_BYTES, i,
                    FEE_QUANT_SIP3 * 100, timeService.getEpochTime() + 50000, (short) 500, ORDINARY_PAYMENT)
                    .id(i).senderId(123L).build();
            transaction.sign(TestConstants.TEST_SECRET_PHRASE);
            t.put(transaction, null);
        }

        assertEquals(100, t.getAll().size());
        assertNotNull(t.get(1L));
    }

    @DisplayName("When a transaction got added by a peer, he won't get it reflected at him when getting unconfirmed transactions")
    @Test
    public void transactionsGivenByPeerWontGetReturnedToPeer() throws ValidationException {
        Peer mockPeer = mock(Peer.class);
        Peer otherMockPeer = mock(Peer.class);

        when(mockBlockChain.getHeight()).thenReturn(20);

        for (int i = 1; i <= 100; i++) {
            Transaction transaction = new Transaction.Builder((byte) 1, TestConstants.TEST_PUBLIC_KEY_BYTES, i,
                    FEE_QUANT_SIP3 * 100, timeService.getEpochTime() + 50000, (short) 500, ORDINARY_PAYMENT)
                    .id(i).senderId(123L).build();
            transaction.sign(TestConstants.TEST_SECRET_PHRASE);
            t.put(transaction, mockPeer);
        }

        assertEquals(0, t.getAllFor(mockPeer).size());
        assertEquals(100, t.getAllFor(otherMockPeer).size());
    }

    @DisplayName("When a transactions got handed by a peer and we mark his fingerprints, he won't get it back a second time")
    @Test
    public void transactionsMarkedWithPeerFingerPrintsWontGetReturnedToPeer() throws ValidationException {
        Peer mockPeer = mock(Peer.class);

        when(mockBlockChain.getHeight()).thenReturn(20);

        for (int i = 1; i <= 100; i++) {
            Transaction transaction = new Transaction.Builder((byte) 1, TestConstants.TEST_PUBLIC_KEY_BYTES, i,
                    FEE_QUANT_SIP3 * 100, timeService.getEpochTime() + 50000, (short) 500, ORDINARY_PAYMENT)
                    .id(i).senderId(123L).build();
            transaction.sign(TestConstants.TEST_SECRET_PHRASE);
            t.put(transaction, null);
        }

        List<Transaction> mockPeerObtainedTransactions = t.getAllFor(mockPeer);
        assertEquals(100, mockPeerObtainedTransactions.size());

        t.markFingerPrintsOf(mockPeer, mockPeerObtainedTransactions);
        assertEquals(0, t.getAllFor(mockPeer).size());
    }

    @DisplayName("When The amount of unconfirmed transactions exceeds max size, and adding another then the cache size stays the same")
    @Test
    public void numberOfUnconfirmedTransactionsOfSameSlotExceedsMaxSizeAddAnotherThenCacheSizeStaysMaxSize()
            throws ValidationException {

        when(mockBlockChain.getHeight()).thenReturn(20);

        for (int i = 1; i <= 8192; i++) {
            Transaction transaction = new Transaction.Builder((byte) 1, TestConstants.TEST_PUBLIC_KEY_BYTES, i,
                    FEE_QUANT_SIP3 * 100, timeService.getEpochTime() + 50000, (short) 500, ORDINARY_PAYMENT)
                    .id(i).senderId(123L).build();
            transaction.sign(TestConstants.TEST_SECRET_PHRASE);
            t.put(transaction, null);
        }

        assertEquals(8192, t.getAll().size());
        assertNotNull(t.get(1L));

        final Transaction oneTransactionTooMany = new Transaction.Builder((byte) 1, TestConstants.TEST_PUBLIC_KEY_BYTES,
                9999, FEE_QUANT_SIP3 * 100, timeService.getEpochTime() + 50000, (short) 500, ORDINARY_PAYMENT)
                .id(8193L).senderId(123L).build();
        oneTransactionTooMany.sign(TestConstants.TEST_SECRET_PHRASE);
        t.put(oneTransactionTooMany, null);

        assertEquals(8192, t.getAll().size());
        assertNull(t.get(1L));
    }

    @DisplayName("When the amount of unconfirmed transactions exceeds max size, and adding another of a higher slot, the cache size stays the same, and a lower slot transaction gets removed")
    @Test
    public void numberOfUnconfirmedTransactionsOfSameSlotExceedsMaxSizeAddAnotherThenCacheSizeStaysMaxSizeAndLowerSlotTransactionGetsRemoved()
            throws ValidationException {

        when(mockBlockChain.getHeight()).thenReturn(20);

        for (int i = 1; i <= 8192; i++) {
            Transaction transaction = new Transaction.Builder((byte) 1, TestConstants.TEST_PUBLIC_KEY_BYTES, i,
                    FEE_QUANT_SIP3 * 100, timeService.getEpochTime() + 50000, (short) 500, ORDINARY_PAYMENT)
                    .id(i).senderId(123L).build();
            transaction.sign(TestConstants.TEST_SECRET_PHRASE);
            t.put(transaction, null);
        }

        assertEquals(8192, t.getAll().size());
        assertEquals(8192, t.getAll().stream().filter(t -> t.getFeeNqt() == FEE_QUANT_SIP3 * 100).count());
        assertNotNull(t.get(1L));

        final Transaction oneTransactionTooMany = new Transaction.Builder((byte) 1, TestConstants.TEST_PUBLIC_KEY_BYTES,
                9999, FEE_QUANT_SIP3 * 200, timeService.getEpochTime() + 50000, (short) 500, ORDINARY_PAYMENT)
                .id(8193L).senderId(123L).build();
        oneTransactionTooMany.sign(TestConstants.TEST_SECRET_PHRASE);
        t.put(oneTransactionTooMany, null);

        assertEquals(8192, t.getAll().size());
        assertEquals(8192 - 1, t.getAll().stream().filter(t -> t.getFeeNqt() == FEE_QUANT_SIP3 * 100).count());
        assertEquals(1, t.getAll().stream().filter(t -> t.getFeeNqt() == FEE_QUANT_SIP3 * 200).count());
    }

    @DisplayName("The unconfirmed transaction gets denied in case the account is unknown")
    @Test
    public void unconfirmedTransactionGetsDeniedForUnknownAccount() throws ValidationException {
        when(mockBlockChain.getHeight()).thenReturn(20);

        Transaction transaction = new Transaction.Builder((byte) 1, TestConstants.TEST_PUBLIC_KEY_BYTES, 1, 735000,
                timeService.getEpochTime() + 50000, (short) 500, ORDINARY_PAYMENT)
                .id(1).senderId(124L).build();
        transaction.sign(TestConstants.TEST_SECRET_PHRASE);
        assertThrows(NotCurrentlyValidException.class, () -> t.put(transaction, null));
    }

    @DisplayName("The unconfirmed transaction gets denied in case the account does not have enough unconfirmed balance")
    @Test
    public void unconfirmedTransactionGetsDeniedForNotEnoughUnconfirmedBalance() throws ValidationException {
        when(mockBlockChain.getHeight()).thenReturn(20);

        Transaction transaction = new Transaction.Builder((byte) 1, TestConstants.TEST_PUBLIC_KEY_BYTES, 1,
                Constants.MAX_BALANCE_NQT, timeService.getEpochTime() + 50000, (short) 500, ORDINARY_PAYMENT)
                .id(1).senderId(123L).build();
        transaction.sign(TestConstants.TEST_SECRET_PHRASE);

        assertThrows(NotCurrentlyValidException.class, () -> t.put(transaction, null));
        assertTrue(t.getAll().isEmpty());
    }

    @DisplayName("When adding the same unconfirmed transaction, nothing changes")
    @Test
    public void addingNewUnconfirmedTransactionWithSameIDResultsInNothingChanging() throws ValidationException {
        when(mockBlockChain.getHeight()).thenReturn(20);

        Peer mockPeer = mock(Peer.class);

        when(mockPeer.getPeerAddress()).thenReturn("mockPeer");

        Builder transactionBuilder = new Builder((byte) 1, TestConstants.TEST_PUBLIC_KEY_BYTES, 1,
                Constants.MAX_BALANCE_NQT - 100000, timeService.getEpochTime() + 50000,
                (short) 500, ORDINARY_PAYMENT)
                .id(1).senderId(123L);

        Transaction transaction1 = transactionBuilder.build();
        transaction1.sign(TestConstants.TEST_SECRET_PHRASE);
        t.put(transaction1, mockPeer);

        Transaction transaction2 = transactionBuilder.build();
        transaction2.sign(TestConstants.TEST_SECRET_PHRASE);

        t.put(transaction2, mockPeer);

        assertEquals(1, t.getAll().size());
    }

    @DisplayName("When the maximum number of transactions with full hash reference is reached, following ones are ignored")
    @Test
    public void whenMaximumNumberOfTransactionsWithFullHashReferenceIsReachedFollowingOnesAreIgnored()
            throws ValidationException {

        when(mockBlockChain.getHeight()).thenReturn(20);

        for (int i = 1; i <= 414; i++) {
            Transaction transaction = new Transaction.Builder((byte) 1, TestConstants.TEST_PUBLIC_KEY_BYTES, i,
                    FEE_QUANT_SIP3 * 2, timeService.getEpochTime() + 50000, (short) 500, ORDINARY_PAYMENT)
                    .id(i).senderId(123L).referencedTransactionFullHash("b33f").build();
            transaction.sign(TestConstants.TEST_SECRET_PHRASE);
            t.put(transaction, null);
        }

        assertEquals(409, t.getAll().size());
    }

    @DisplayName("When the maximum number of transactions for a slot size is reached, following ones are ignored")
    @Test
    public void whenMaximumNumberOfTransactionsForSlotSizeIsReachedFollowingOnesAreIgnored()
            throws ValidationException {

        when(mockBlockChain.getHeight()).thenReturn(20);

        for (int i = 1; i <= 365; i++) {
            Transaction transaction = new Transaction.Builder((byte) 1, TestConstants.TEST_PUBLIC_KEY_BYTES, i,
                    FEE_QUANT_SIP3, timeService.getEpochTime() + 50000, (short) 500, ORDINARY_PAYMENT)
                    .id(i).senderId(123L).build();
            transaction.sign(TestConstants.TEST_SECRET_PHRASE);
            t.put(transaction, null);
        }

        assertEquals(360, t.getAll().size());

        for (int i = 1; i <= 725; i++) {
            Transaction transaction = new Transaction.Builder((byte) 1, TestConstants.TEST_PUBLIC_KEY_BYTES, i,
                    FEE_QUANT_SIP3 * 2, timeService.getEpochTime() + 50000, (short) 500, ORDINARY_PAYMENT)
                    .id(i + 1000).senderId(123L).build();
            transaction.sign(TestConstants.TEST_SECRET_PHRASE);
            t.put(transaction, null);
        }

        assertEquals(1080, t.getAll().size());
    }

    @Test
    public void cheaperDuplicateTransactionGetsRemoved() throws ValidationException {
        Transaction cheap = new Transaction.Builder((byte) 1, TestConstants.TEST_PUBLIC_KEY_BYTES, 1, FEE_QUANT_SIP3,
                timeService.getEpochTime() + 50000, (short) 500,
                new MessagingAliasSell(fluxCapacitor, "aliasName", 123L, 5))
                .id(1).senderId(123L).build();

        Transaction expensive = new Transaction.Builder((byte) 1, TestConstants.TEST_PUBLIC_KEY_BYTES, 1,
                FEE_QUANT_SIP3 * 2, timeService.getEpochTime() + 50000, (short) 500,
                new MessagingAliasSell(fluxCapacitor, "aliasName", 123L, 5))
                .id(2).senderId(123L).build();

        t.put(cheap, null);

        assertEquals(1, t.getAll().size());
        assertNotNull(t.get(cheap.getId()));

        t.put(expensive, null);

        assertEquals(1, t.getAll().size());
        assertNull(t.get(cheap.getId()));
        assertNotNull(t.get(expensive.getId()));
    }

    @Test
    public void cheaperDuplicateTransactionNeverGetsAdded() throws ValidationException {
        Transaction cheap = new Transaction.Builder((byte) 1, TestConstants.TEST_PUBLIC_KEY_BYTES, 1, FEE_QUANT_SIP3,
                timeService.getEpochTime() + 50000, (short) 500,
                new MessagingAliasSell(fluxCapacitor, "aliasName", 123L, 5))
                .id(1).senderId(123L).build();

        Transaction expensive = new Transaction.Builder((byte) 1, TestConstants.TEST_PUBLIC_KEY_BYTES, 1,
                FEE_QUANT_SIP3 * 2, timeService.getEpochTime() + 50000, (short) 500,
                new MessagingAliasSell(fluxCapacitor, "aliasName", 123L, 5))
                .id(2).senderId(123L).build();

        t.put(expensive, null);

        assertEquals(1, t.getAll().size());
        assertNotNull(t.get(expensive.getId()));

        t.put(cheap, null);

        assertEquals(1, t.getAll().size());
        assertNull(t.get(cheap.getId()));
        assertNotNull(t.get(expensive.getId()));
    }

}
