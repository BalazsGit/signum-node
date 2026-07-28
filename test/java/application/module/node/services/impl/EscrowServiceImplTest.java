package application.module.node.services.impl;

import application.module.node.Blockchain;
import application.module.node.Escrow;
import application.module.node.Escrow.Decision;
import application.module.node.db.SignumKey;
import application.module.node.db.SignumKey.LongKeyFactory;
import application.module.node.db.TransactionDb;
import application.module.node.db.sql.DbKey;
import application.module.node.db.VersionedEntityTable;
import application.module.node.db.store.EscrowStore;
import application.module.node.services.AccountService;
import application.module.node.services.AliasService;
import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class EscrowServiceImplTest {

    private EscrowServiceImpl t;

    private EscrowStore mockEscrowStore;
    private VersionedEntityTable<Escrow> mockEscrowTable;
    private LongKeyFactory<Escrow> mockEscrowDbKeyFactory;
    private VersionedEntityTable<Decision> mockDecisionTable;
    private DbKey.LinkKeyFactory<Decision> mockDecisionDbKeyFactory;
    private Blockchain blockchainMock;
    private AliasService aliasServiceMock;
    private AccountService accountServiceMock;
    private TransactionDb transactionDbMock;

    @Before
    public void setUp() {
        mockEscrowStore = mock(EscrowStore.class);
        mockEscrowTable = mock(VersionedEntityTable.class);
        mockEscrowDbKeyFactory = mock(LongKeyFactory.class);
        mockDecisionTable = mock(VersionedEntityTable.class);
        mockDecisionDbKeyFactory = mock(DbKey.LinkKeyFactory.class);

        blockchainMock = mock(Blockchain.class);
        aliasServiceMock = mock(AliasService.class);
        accountServiceMock = mock(AccountService.class);
        transactionDbMock = mock(TransactionDb.class);

        when(mockEscrowStore.getEscrowTable()).thenReturn(mockEscrowTable);
        when(mockEscrowStore.getEscrowDbKeyFactory()).thenReturn(mockEscrowDbKeyFactory);
        when(mockEscrowStore.getDecisionTable()).thenReturn(mockDecisionTable);
        when(mockEscrowStore.getDecisionDbKeyFactory()).thenReturn(mockDecisionDbKeyFactory);
        when(mockEscrowStore.getResultTransactions()).thenReturn(mock(List.class));

        t = new EscrowServiceImpl(mockEscrowStore, blockchainMock, aliasServiceMock, accountServiceMock, transactionDbMock);
    }

    @Test
    public void getAllEscrowTransactions() {
        final Collection<Escrow> mockEscrowIterator = mock(Collection.class);

        when(mockEscrowTable.getAll(eq(0), eq(-1))).thenReturn(mockEscrowIterator);

        assertEquals(mockEscrowIterator, t.getAllEscrowTransactions());
    }

    @Test
    public void getEscrowTransaction() {
        final long escrowId = 123L;

        final SignumKey mockEscrowKey = mock(SignumKey.class);
        final Escrow mockEscrow = mock(Escrow.class);

        when(mockEscrowDbKeyFactory.newKey(eq(escrowId))).thenReturn(mockEscrowKey);
        when(mockEscrowTable.get(eq(mockEscrowKey))).thenReturn(mockEscrow);

        assertEquals(mockEscrow, t.getEscrowTransaction(escrowId));
    }
}
