package application.module.brs.web.api.http.handler;

import application.module.brs.*;
import application.module.brs.Order.Bid;
import application.module.brs.assetexchange.AssetExchange;
import application.module.brs.common.QuickMocker;
import application.module.brs.common.QuickMocker.MockParam;
import application.module.brs.fluxcapacitor.FluxCapacitor;
import application.module.brs.fluxcapacitor.FluxValues;
import application.module.brs.services.ParameterService;
import application.module.brs.web.api.http.common.APITransactionManager;
import application.module.brs.web.api.http.common.ParameterException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.TransactionType.ColoredCoins.BID_ORDER_CANCELLATION;
import static application.module.brs.web.api.http.common.JSONResponses.UNKNOWN_ORDER;
import static application.module.brs.web.api.http.common.Parameters.ORDER_PARAMETER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

@RunWith(PowerMockRunner.class)
@PrepareForTest(Signum.class)
public class CancelBidOrderTest extends AbstractTransactionTest {

    private CancelBidOrder t;

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

        t = new CancelBidOrder(parameterServiceMock, blockchainMock, assetExchangeMock, apiTransactionManagerMock);
    }

    @Test
    public void processRequest() throws SignumException {
        final int orderId = 123;
        final long orderAccountId = 1;
        final long senderAccountId = orderAccountId;

        HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ORDER_PARAMETER, orderId));

        final Bid mockBidOrder = mock(Bid.class);
        when(mockBidOrder.getAccountId()).thenReturn(orderAccountId);
        when(assetExchangeMock.getBidOrder(eq(123L))).thenReturn(mockBidOrder);

        final Account mockAccount = mock(Account.class);
        when(mockAccount.getId()).thenReturn(senderAccountId);
        when(parameterServiceMock.getSenderAccount(eq(req))).thenReturn(mockAccount);

        mockStatic(Signum.class);
        final FluxCapacitor fluxCapacitor = QuickMocker
                .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
        when(Signum.getFluxCapacitor()).thenReturn(fluxCapacitor);
        doReturn(Constants.FEE_QUANT_SIP3).when(fluxCapacitor).getValue(eq(FluxValues.FEE_QUANT));

        final Attachment.ColoredCoinsBidOrderCancellation attachment = (application.module.brs.Attachment.ColoredCoinsBidOrderCancellation) attachmentCreatedTransaction(
                () -> t.processRequest(req), apiTransactionManagerMock);
        assertNotNull(attachment);

        assertEquals(BID_ORDER_CANCELLATION, attachment.getTransactionType());
        assertEquals(orderId, attachment.getOrderId());
    }

    @Test(expected = ParameterException.class)
    public void processRequest_orderParameterMissing() throws SignumException {
        t.processRequest(QuickMocker.httpServletRequest());
    }

    @Test
    public void processRequest_orderDataMissingUnkownOrder() throws SignumException {
        final int orderId = 123;
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ORDER_PARAMETER, orderId));

        when(assetExchangeMock.getBidOrder(eq(123L))).thenReturn(null);

        assertEquals(UNKNOWN_ORDER, t.processRequest(req));
    }

    @Test
    public void processRequest_accountIdNotSameAsOrder() throws SignumException {
        final int orderId = 123;
        final long orderAccountId = 1;
        final long senderAccountId = 2;

        HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ORDER_PARAMETER, orderId));

        final Bid mockBidOrder = mock(Bid.class);
        when(mockBidOrder.getAccountId()).thenReturn(orderAccountId);
        when(assetExchangeMock.getBidOrder(eq(123L))).thenReturn(mockBidOrder);

        final Account mockAccount = mock(Account.class);
        when(mockAccount.getId()).thenReturn(senderAccountId);

        when(parameterServiceMock.getSenderAccount(eq(req))).thenReturn(mockAccount);

        assertEquals(UNKNOWN_ORDER, t.processRequest(req));
    }

}
