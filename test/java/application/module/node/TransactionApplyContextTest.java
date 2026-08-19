package application.module.node;

import application.module.node.at.AtConstants;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.db.store.AccountStore;
import application.module.node.db.store.ATStore;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.props.PropertyService;
import application.module.node.services.AccountService;
import application.module.node.services.AliasService;
import application.module.node.services.DGSGoodsStoreService;
import application.module.node.services.EscrowService;
import application.module.node.services.SubscriptionService;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link TransactionApplyContext}.
 * Validates constructor injection, immutability, and null safety.
 */
public class TransactionApplyContextTest {

    private Blockchain blockchainMock;
    private FluxCapacitor fluxCapacitorMock;
    private AccountService accountServiceMock;
    private DGSGoodsStoreService dgsGoodsStoreServiceMock;
    private AliasService aliasServiceMock;
    private AssetExchange assetExchangeMock;
    private SubscriptionService subscriptionServiceMock;
    private EscrowService escrowServiceMock;
    private PropertyService propertyServiceMock;
    private ATStore atStoreMock;
    private AtConstants atConstantsMock;
    private AccountStore accountStoreMock;

    private TransactionApplyContext context;

    @Before
    public void setUp() {
        blockchainMock = mock(Blockchain.class);
        fluxCapacitorMock = mock(FluxCapacitor.class);
        accountServiceMock = mock(AccountService.class);
        dgsGoodsStoreServiceMock = mock(DGSGoodsStoreService.class);
        aliasServiceMock = mock(AliasService.class);
        assetExchangeMock = mock(AssetExchange.class);
        subscriptionServiceMock = mock(SubscriptionService.class);
        escrowServiceMock = mock(EscrowService.class);
        propertyServiceMock = mock(PropertyService.class);
        atStoreMock = mock(ATStore.class);
        atConstantsMock = mock(AtConstants.class);
        accountStoreMock = mock(AccountStore.class);

        context = new TransactionApplyContext(
                blockchainMock,
                fluxCapacitorMock,
                accountServiceMock,
                dgsGoodsStoreServiceMock,
                aliasServiceMock,
                assetExchangeMock,
                subscriptionServiceMock,
                escrowServiceMock,
                propertyServiceMock,
                atStoreMock,
                atConstantsMock,
                accountStoreMock);
    }

    @Test
    public void constructor_GivenAllDependencies_ReturnsNonNull() {
        assertNotNull(context);
    }

    @Test
    public void getBlockchain_GivenMockReturns_MatchesInjectedValue() {
        assertSame(blockchainMock, context.getBlockchain());
    }

    @Test
    public void getFluxCapacitor_GivenMockReturns_MatchesInjectedValue() {
        assertSame(fluxCapacitorMock, context.getFluxCapacitor());
    }

    @Test
    public void getAccountService_GivenMockReturns_MatchesInjectedValue() {
        assertSame(accountServiceMock, context.getAccountService());
    }

    @Test
    public void getDgsGoodsStoreService_GivenMockReturns_MatchesInjectedValue() {
        assertSame(dgsGoodsStoreServiceMock, context.getDgsGoodsStoreService());
    }

    @Test
    public void getAliasService_GivenMockReturns_MatchesInjectedValue() {
        assertSame(aliasServiceMock, context.getAliasService());
    }

    @Test
    public void getAssetExchange_GivenMockReturns_MatchesInjectedValue() {
        assertSame(assetExchangeMock, context.getAssetExchange());
    }

    @Test
    public void getSubscriptionService_GivenMockReturns_MatchesInjectedValue() {
        assertSame(subscriptionServiceMock, context.getSubscriptionService());
    }

    @Test
    public void getEscrowService_GivenMockReturns_MatchesInjectedValue() {
        assertSame(escrowServiceMock, context.getEscrowService());
    }

    @Test
    public void getPropertyService_GivenMockReturns_MatchesInjectedValue() {
        assertSame(propertyServiceMock, context.getPropertyService());
    }

    @Test
    public void getAtStore_GivenMockReturns_MatchesInjectedValue() {
        assertSame(atStoreMock, context.getAtStore());
    }

    @Test
    public void getAtConstants_GivenMockReturns_MatchesInjectedValue() {
        assertSame(atConstantsMock, context.getAtConstants());
    }

    @Test
    public void constructor_NullAtConstants_StoresNullSafely() {
        TransactionApplyContext nullCtx = new TransactionApplyContext(
                blockchainMock, fluxCapacitorMock, accountServiceMock, dgsGoodsStoreServiceMock,
                aliasServiceMock, assetExchangeMock, subscriptionServiceMock, escrowServiceMock,
                propertyServiceMock, atStoreMock, null, accountStoreMock);
        assertNull(nullCtx.getAtConstants());
    }

    @Test
    public void toString_GivenValidContext_DoesNotThrow() {
        String result = context.toString();
        assertNotNull(result);
        assertTrue(result.contains("TransactionApplyContext"));
    }

    @Test
    public void constructor_NullBlockchain_StoresNullSafely() {
        TransactionApplyContext nullCtx = new TransactionApplyContext(
                null, fluxCapacitorMock, accountServiceMock, dgsGoodsStoreServiceMock,
                aliasServiceMock, assetExchangeMock, subscriptionServiceMock, escrowServiceMock,
                propertyServiceMock, atStoreMock, atConstantsMock, accountStoreMock);
        assertNull(nullCtx.getBlockchain());
    }

    @Test
    public void constructor_NullAtStore_StoresNullSafely() {
        TransactionApplyContext nullCtx = new TransactionApplyContext(
                blockchainMock, fluxCapacitorMock, accountServiceMock, dgsGoodsStoreServiceMock,
                aliasServiceMock, assetExchangeMock, subscriptionServiceMock, escrowServiceMock,
                propertyServiceMock, null, atConstantsMock, accountStoreMock);
        assertNull(nullCtx.getAtStore());
    }

    @Test
    public void multipleInstances_GivenSameDependencies_AreIndependent() {
        TransactionApplyContext second = new TransactionApplyContext(
                blockchainMock, fluxCapacitorMock, accountServiceMock, dgsGoodsStoreServiceMock,
                aliasServiceMock, assetExchangeMock, subscriptionServiceMock, escrowServiceMock,
                propertyServiceMock, atStoreMock, atConstantsMock, accountStoreMock);

        assertNotSame(context, second);
        assertSame(context.getBlockchain(), second.getBlockchain());
    }
}
