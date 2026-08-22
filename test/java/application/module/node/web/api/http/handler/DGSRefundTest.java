package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.DigitalGoodsStore.Purchase;
import application.module.node.common.QuickMocker;
import application.module.node.common.QuickMocker.MockParam;
import application.module.node.crypto.EncryptedData;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.services.AccountService;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.common.APITransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.TransactionType.DigitalGoods.REFUND;
import static application.module.node.web.api.http.common.JSONResponses.*;
import static application.module.node.web.api.http.common.Parameters.REFUND_NQT_PARAMETER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class DGSRefundTest extends AbstractTransactionTest {

    private DGSRefund t;

    @Mock
    private ParameterService mockParameterService;
    @Mock
    private Blockchain mockBlockchain;
    @Mock
    private AccountService mockAccountService;
    @Mock
    private APITransactionManager apiTransactionManagerMock;

    @BeforeEach
    void setUp() {
        FluxCapacitor fluxCapacitor = QuickMocker.latestValueFluxCapacitor();
        t = new DGSRefund(mockParameterService, mockBlockchain, mockAccountService, apiTransactionManagerMock, fluxCapacitor);
    }

    @Test
    void processRequest() throws SignumException {
        final long refundNQTParameter = 5;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(REFUND_NQT_PARAMETER, refundNQTParameter));

        final Account mockSellerAccount = mock(Account.class);
        when(mockSellerAccount.getId()).thenReturn(1L);

        long mockPurchaseId = 123L;
        final Purchase mockPurchase = mock(Purchase.class);
        when(mockPurchase.getId()).thenReturn(mockPurchaseId);
        when(mockPurchase.getSellerId()).thenReturn(1L);
        when(mockPurchase.getBuyerId()).thenReturn(2L);
        when(mockPurchase.getRefundNote()).thenReturn(null);
        when(mockPurchase.getEncryptedGoods()).thenReturn(mock(EncryptedData.class));

        when(mockParameterService.getSenderAccount(eq(req))).thenReturn(mockSellerAccount);
        when(mockParameterService.getPurchase(eq(req))).thenReturn(mockPurchase);

        final Account mockBuyerAccount = mock(Account.class);
        when(mockAccountService.getAccount(eq(mockPurchase.getBuyerId()))).thenReturn(mockBuyerAccount);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            final FluxCapacitor fluxCapacitor = QuickMocker
                    .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
            doReturn(Constants.FEE_QUANT_SIP3).when(fluxCapacitor).getValue(eq(FluxValues.FEE_QUANT));

            final Attachment.DigitalGoodsRefund attachment = (Attachment.DigitalGoodsRefund) attachmentCreatedTransaction(
                    () -> t.processRequest(req), apiTransactionManagerMock);
            assertNotNull(attachment);

            assertEquals(REFUND, attachment.getTransactionType());
            assertEquals(refundNQTParameter, attachment.getRefundNqt());
            assertEquals(mockPurchaseId, attachment.getPurchaseId());
        }
    }

    @Test
    void processRequest_incorrectPurchase() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest();

        final Account mockSellerAccount = mock(Account.class);
        when(mockSellerAccount.getId()).thenReturn(1L);

        final Purchase mockPurchase = mock(Purchase.class);
        when(mockPurchase.getSellerId()).thenReturn(2L);

        when(mockParameterService.getSenderAccount(eq(req))).thenReturn(mockSellerAccount);
        when(mockParameterService.getPurchase(eq(req))).thenReturn(mockPurchase);

        assertEquals(INCORRECT_PURCHASE, t.processRequest(req));
    }

    @Test
    void processRequest_duplicateRefund() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest();

        final Account mockSellerAccount = mock(Account.class);
        when(mockSellerAccount.getId()).thenReturn(1L);

        final Purchase mockPurchase = mock(Purchase.class);
        when(mockPurchase.getSellerId()).thenReturn(1L);
        when(mockPurchase.getRefundNote()).thenReturn(mock(EncryptedData.class));

        when(mockParameterService.getSenderAccount(eq(req))).thenReturn(mockSellerAccount);
        when(mockParameterService.getPurchase(eq(req))).thenReturn(mockPurchase);

        assertEquals(DUPLICATE_REFUND, t.processRequest(req));
    }

    @Test
    void processRequest_goodsNotDelivered() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest();

        final Account mockSellerAccount = mock(Account.class);
        when(mockSellerAccount.getId()).thenReturn(1L);

        final Purchase mockPurchase = mock(Purchase.class);
        when(mockPurchase.getSellerId()).thenReturn(1L);
        when(mockPurchase.getRefundNote()).thenReturn(null);
        when(mockPurchase.getEncryptedGoods()).thenReturn(null);

        when(mockParameterService.getSenderAccount(eq(req))).thenReturn(mockSellerAccount);
        when(mockParameterService.getPurchase(eq(req))).thenReturn(mockPurchase);

        assertEquals(GOODS_NOT_DELIVERED, t.processRequest(req));
    }

    @Test
    void processRequest_incorrectDgsRefundWrongFormat() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(REFUND_NQT_PARAMETER, "Bob"));

        final Account mockSellerAccount = mock(Account.class);
        when(mockSellerAccount.getId()).thenReturn(1L);

        final Purchase mockPurchase = mock(Purchase.class);
        when(mockPurchase.getSellerId()).thenReturn(1L);
        when(mockPurchase.getRefundNote()).thenReturn(null);
        when(mockPurchase.getEncryptedGoods()).thenReturn(mock(EncryptedData.class));

        when(mockParameterService.getSenderAccount(eq(req))).thenReturn(mockSellerAccount);
        when(mockParameterService.getPurchase(eq(req))).thenReturn(mockPurchase);

        assertEquals(INCORRECT_DGS_REFUND, t.processRequest(req));
    }

    @Test
    void processRequest_negativeIncorrectDGSRefund() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(REFUND_NQT_PARAMETER, -5));

        final Account mockSellerAccount = mock(Account.class);
        when(mockSellerAccount.getId()).thenReturn(1L);

        final Purchase mockPurchase = mock(Purchase.class);
        when(mockPurchase.getSellerId()).thenReturn(1L);
        when(mockPurchase.getRefundNote()).thenReturn(null);
        when(mockPurchase.getEncryptedGoods()).thenReturn(mock(EncryptedData.class));

        when(mockParameterService.getSenderAccount(eq(req))).thenReturn(mockSellerAccount);
        when(mockParameterService.getPurchase(eq(req))).thenReturn(mockPurchase);

        assertEquals(INCORRECT_DGS_REFUND, t.processRequest(req));
    }

    @Test
    void processRequest_overMaxBalanceNQTIncorrectDGSRefund() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(REFUND_NQT_PARAMETER, Constants.MAX_BALANCE_NQT + 1));

        final Account mockSellerAccount = mock(Account.class);
        when(mockSellerAccount.getId()).thenReturn(1L);

        final Purchase mockPurchase = mock(Purchase.class);
        when(mockPurchase.getSellerId()).thenReturn(1L);
        when(mockPurchase.getRefundNote()).thenReturn(null);
        when(mockPurchase.getEncryptedGoods()).thenReturn(mock(EncryptedData.class));

        when(mockParameterService.getSenderAccount(eq(req))).thenReturn(mockSellerAccount);
        when(mockParameterService.getPurchase(eq(req))).thenReturn(mockPurchase);

        assertEquals(INCORRECT_DGS_REFUND, t.processRequest(req));
    }
}