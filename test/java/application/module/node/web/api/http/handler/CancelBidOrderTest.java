package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.Order.Bid;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.common.QuickMocker;
import application.module.node.common.QuickMocker.MockParam;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.ParameterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.TransactionType.ColoredCoins.BID_ORDER_CANCELLATION;
import static application.module.node.web.api.http.common.JSONResponses.UNKNOWN_ORDER;
import static application.module.node.web.api.http.common.Parameters.ORDER_PARAMETER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class CancelBidOrderTest extends AbstractTransactionTest {

    private CancelBidOrder t;

    @Mock
    private ParameterService parameterServiceMock;
    @Mock
    private Blockchain blockchainMock;
    @Mock
    private AssetExchange assetExchangeMock;
    @Mock
    private APITransactionManager apiTransactionManagerMock;

    @BeforeEach
    void setUp() {
        FluxCapacitor fluxCapacitor = QuickMocker.latestValueFluxCapacitor();
        t = new CancelBidOrder(parameterServiceMock, blockchainMock, assetExchangeMock, apiTransactionManagerMock,
                fluxCapacitor);
    }

    @Test
    void processRequest() throws SignumException {
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

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            final FluxCapacitor fluxCapacitor = QuickMocker
                    .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
            mocked.when(Signum::getFluxCapacitor).thenReturn(fluxCapacitor);
            doReturn(Constants.FEE_QUANT_SIP3).when(fluxCapacitor).getValue(eq(FluxValues.FEE_QUANT));

            final Attachment.ColoredCoinsBidOrderCancellation attachment = (Attachment.ColoredCoinsBidOrderCancellation) attachmentCreatedTransaction(
                    () -> t.processRequest(req), apiTransactionManagerMock);
            assertNotNull(attachment);

            assertEquals(BID_ORDER_CANCELLATION, attachment.getTransactionType());
            assertEquals(orderId, attachment.getOrderId());
        }
    }

    @Test
    void processRequest_orderParameterMissing() throws SignumException {
        assertThrows(ParameterException.class, () -> t.processRequest(QuickMocker.httpServletRequest()));
    }

    @Test
    void processRequest_orderDataMissingUnkownOrder() throws SignumException {
        final int orderId = 123;
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ORDER_PARAMETER, orderId));

        when(assetExchangeMock.getBidOrder(eq(123L))).thenReturn(null);

        assertEquals(UNKNOWN_ORDER, t.processRequest(req));
    }

    @Test
    void processRequest_accountIdNotSameAsOrder() throws SignumException {
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