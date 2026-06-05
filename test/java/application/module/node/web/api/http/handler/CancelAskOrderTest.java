package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.Order.Ask;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.common.QuickMocker;
import application.module.node.common.QuickMocker.MockParam;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.common.APITransactionManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.TransactionType.ColoredCoins.ASK_ORDER_CANCELLATION;
import static application.module.node.web.api.http.common.JSONResponses.UNKNOWN_ORDER;
import static application.module.node.web.api.http.common.Parameters.ORDER_PARAMETER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

@RunWith(PowerMockRunner.class)
@PrepareForTest(Signum.class)
public class CancelAskOrderTest extends AbstractTransactionTest {

    private CancelAskOrder t;

    private ParameterService parameterServiceMock;
    private Blockchain blockchainMock;
    private AssetExchange assetExchangeMock;
    private APITransactionManager apiTransactionManagerMock;

    @Before
    public void setUp() {
        parameterServiceMock = mock(ParameterService.class);
        blockchainMock = mock(Blockchain.class);
        assetExchangeMock = mock(AssetExchange.class);
        apiTransactionManagerMock = mock(APITransactionManager.class);

        t = new CancelAskOrder(parameterServiceMock, blockchainMock, assetExchangeMock, apiTransactionManagerMock);
    }

    @Test
    public void processRequest() throws SignumException {
        final long orderId = 5;
        final long sellerId = 6;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ORDER_PARAMETER, orderId));

        final Account sellerAccount = mock(Account.class);
        when(sellerAccount.getId()).thenReturn(sellerId);

        final Ask order = mock(Ask.class);
        when(order.getAccountId()).thenReturn(sellerId);

        when(assetExchangeMock.getAskOrder(eq(orderId))).thenReturn(order);
        when(parameterServiceMock.getSenderAccount(eq(req))).thenReturn(sellerAccount);

        mockStatic(Signum.class);
        final FluxCapacitor fluxCapacitor = QuickMocker
                .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
        when(Signum.getFluxCapacitor()).thenReturn(fluxCapacitor);
        doReturn(Constants.FEE_QUANT_SIP3).when(fluxCapacitor).getValue(eq(FluxValues.FEE_QUANT));

        final Attachment.ColoredCoinsAskOrderCancellation attachment = (application.module.node.Attachment.ColoredCoinsAskOrderCancellation) attachmentCreatedTransaction(
                () -> t.processRequest(req),
                apiTransactionManagerMock);
        assertNotNull(attachment);

        assertEquals(ASK_ORDER_CANCELLATION, attachment.getTransactionType());
        assertEquals(orderId, attachment.getOrderId());
    }

    @Test
    public void processRequest_orderDataNotFound() throws SignumException {
        int orderId = 5;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ORDER_PARAMETER, orderId));

        when(assetExchangeMock.getAskOrder(eq(orderId))).thenReturn(null);

        assertEquals(UNKNOWN_ORDER, t.processRequest(req));
    }

    @Test
    public void processRequest_orderOtherAccount() throws SignumException {
        final long orderId = 5;
        final long accountId = 6;
        final long otherAccountId = 7;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ORDER_PARAMETER, orderId));

        final Account sellerAccount = mock(Account.class);
        when(sellerAccount.getId()).thenReturn(accountId);

        final Ask order = mock(Ask.class);
        when(order.getAccountId()).thenReturn(otherAccountId);

        when(assetExchangeMock.getAskOrder(eq(orderId))).thenReturn(order);
        when(parameterServiceMock.getSenderAccount(eq(req))).thenReturn(sellerAccount);

        assertEquals(UNKNOWN_ORDER, t.processRequest(req));
    }
}
