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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.TransactionType.ColoredCoins.ASK_ORDER_CANCELLATION;
import static application.module.node.web.api.http.common.JSONResponses.UNKNOWN_ORDER;
import static application.module.node.web.api.http.common.Parameters.ORDER_PARAMETER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class CancelAskOrderTest extends AbstractTransactionTest {

    private CancelAskOrder t;

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
        t = new CancelAskOrder(parameterServiceMock, blockchainMock, assetExchangeMock, apiTransactionManagerMock,
                fluxCapacitor);
    }

    @Test
    void processRequest() throws SignumException {
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

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            final FluxCapacitor fluxCapacitor = QuickMocker
                    .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
            doReturn(Constants.FEE_QUANT_SIP3).when(fluxCapacitor).getValue(eq(FluxValues.FEE_QUANT));

            final Attachment.ColoredCoinsAskOrderCancellation attachment = (Attachment.ColoredCoinsAskOrderCancellation) attachmentCreatedTransaction(
                    () -> t.processRequest(req),
                    apiTransactionManagerMock);
            assertNotNull(attachment);

            assertEquals(ASK_ORDER_CANCELLATION, attachment.getTransactionType());
            assertEquals(orderId, attachment.getOrderId());
        }
    }

    @Test
    void processRequest_orderDataNotFound() throws SignumException {
        int orderId = 5;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ORDER_PARAMETER, orderId));

        when(assetExchangeMock.getAskOrder(eq(orderId))).thenReturn(null);

        assertEquals(UNKNOWN_ORDER, t.processRequest(req));
    }

    @Test
    void processRequest_orderOtherAccount() throws SignumException {
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