package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.DigitalGoodsStore.Purchase;
import application.module.node.common.QuickMocker;
import application.module.node.common.QuickMocker.MockParam;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.services.AccountService;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.common.APITransactionManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.Constants.MAX_BALANCE_NQT;
import static application.module.node.TransactionType.DigitalGoods.DELIVERY;
import static application.module.node.common.TestConstants.TEST_SECRET_PHRASE;
import static application.module.node.web.api.http.common.JSONResponses.*;
import static application.module.node.web.api.http.common.Parameters.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class DGSDeliveryTest extends AbstractTransactionTest {

    private DGSDelivery t;

    private ParameterService parameterServiceMock;
    private Blockchain blockchainMock;
    private AccountService accountServiceMock;
    private APITransactionManager apiTransactionManagerMock;

    @Before
    public void setUp() {
        parameterServiceMock = mock(ParameterService.class);
        blockchainMock = mock(Blockchain.class);
        accountServiceMock = mock(AccountService.class);
        apiTransactionManagerMock = mock(APITransactionManager.class);

        t = new DGSDelivery(parameterServiceMock, blockchainMock, accountServiceMock, apiTransactionManagerMock, QuickMocker.latestValueFluxCapacitor());
    }

    @Test
    public void processRequest() throws SignumException {
        final long discountNQTParameter = 1;
        final String goodsToEncryptParameter = "beef";

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(DISCOUNT_NQT_PARAMETER, discountNQTParameter),
                new MockParam(GOODS_TO_ENCRYPT_PARAMETER, goodsToEncryptParameter),
                new MockParam(SECRET_PHRASE_PARAMETER, TEST_SECRET_PHRASE));

        final Account mockSellerAccount = mock(Account.class);
        final Account mockBuyerAccount = mock(Account.class);
        final Purchase mockPurchase = mock(Purchase.class);

        when(mockSellerAccount.getId()).thenReturn(1L);
        when(mockPurchase.getSellerId()).thenReturn(1L);
        when(mockPurchase.getBuyerId()).thenReturn(2L);
        when(mockPurchase.getQuantity()).thenReturn(9);
        when(mockPurchase.getPriceNQT()).thenReturn(1L);

        when(mockPurchase.isPending()).thenReturn(true);

        when(parameterServiceMock.getSenderAccount(eq(req))).thenReturn(mockSellerAccount);
        when(parameterServiceMock.getPurchase(eq(req))).thenReturn(mockPurchase);
        when(accountServiceMock.getAccount(eq(mockPurchase.getBuyerId()))).thenReturn(mockBuyerAccount);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            final FluxCapacitor fluxCapacitor = QuickMocker
                    .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
            mocked.when(Signum::getFluxCapacitor).thenReturn(fluxCapacitor);
            doReturn(Constants.FEE_QUANT_SIP3).when(fluxCapacitor).getValue(eq(FluxValues.FEE_QUANT));

            final Attachment.DigitalGoodsDelivery attachment = (Attachment.DigitalGoodsDelivery) attachmentCreatedTransaction(
                    () -> t.processRequest(req), apiTransactionManagerMock);
            assertNotNull(attachment);

            assertEquals(DELIVERY, attachment.getTransactionType());
            assertEquals(discountNQTParameter, attachment.getDiscountNqt());
        }
    }

    @Test
    public void processRequest_sellerAccountIdDifferentFromAccountSellerIdIsIncorrectPurchase() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest();

        final Account mockSellerAccount = mock(Account.class);
        final Purchase mockPurchase = mock(Purchase.class);

        when(mockSellerAccount.getId()).thenReturn(1L);
        when(mockPurchase.getSellerId()).thenReturn(2L);

        when(parameterServiceMock.getSenderAccount(eq(req))).thenReturn(mockSellerAccount);
        when(parameterServiceMock.getPurchase(eq(req))).thenReturn(mockPurchase);

        assertEquals(INCORRECT_PURCHASE, t.processRequest(req));
    }

    @Test
    public void processRequest_purchaseNotPendingIsAlreadyDelivered() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest();

        final Account mockSellerAccount = mock(Account.class);
        final Purchase mockPurchase = mock(Purchase.class);

        when(mockSellerAccount.getId()).thenReturn(1L);
        when(mockPurchase.getSellerId()).thenReturn(1L);

        when(mockPurchase.isPending()).thenReturn(false);

        when(parameterServiceMock.getSenderAccount(eq(req))).thenReturn(mockSellerAccount);
        when(parameterServiceMock.getPurchase(eq(req))).thenReturn(mockPurchase);

        assertEquals(ALREADY_DELIVERED, t.processRequest(req));
    }

    @Test
    public void processRequest_dgsDiscountNotAValidNumberIsIncorrectDGSDiscount() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(DISCOUNT_NQT_PARAMETER, "Bob"));

        final Account mockSellerAccount = mock(Account.class);
        final Purchase mockPurchase = mock(Purchase.class);

        when(mockSellerAccount.getId()).thenReturn(1L);
        when(mockPurchase.getSellerId()).thenReturn(1L);

        when(mockPurchase.isPending()).thenReturn(true);

        when(parameterServiceMock.getSenderAccount(eq(req))).thenReturn(mockSellerAccount);
        when(parameterServiceMock.getPurchase(eq(req))).thenReturn(mockPurchase);

        assertEquals(INCORRECT_DGS_DISCOUNT, t.processRequest(req));
    }

    @Test
    public void processRequest_dgsDiscountNegativeIsIncorrectDGSDiscount() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(DISCOUNT_NQT_PARAMETER, "-1"));

        final Account mockSellerAccount = mock(Account.class);
        final Purchase mockPurchase = mock(Purchase.class);

        when(mockSellerAccount.getId()).thenReturn(1L);
        when(mockPurchase.getSellerId()).thenReturn(1L);

        when(mockPurchase.isPending()).thenReturn(true);

        when(parameterServiceMock.getSenderAccount(eq(req))).thenReturn(mockSellerAccount);
        when(parameterServiceMock.getPurchase(eq(req))).thenReturn(mockPurchase);

        assertEquals(INCORRECT_DGS_DISCOUNT, t.processRequest(req));
    }

    @Test
    public void processRequest_dgsDiscountOverMaxBalanceNQTIsIncorrectDGSDiscount() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(DISCOUNT_NQT_PARAMETER, "" + (MAX_BALANCE_NQT + 1)));

        final Account mockSellerAccount = mock(Account.class);
        final Purchase mockPurchase = mock(Purchase.class);

        when(mockSellerAccount.getId()).thenReturn(1L);
        when(mockPurchase.getSellerId()).thenReturn(1L);

        when(mockPurchase.isPending()).thenReturn(true);

        when(parameterServiceMock.getSenderAccount(eq(req))).thenReturn(mockSellerAccount);
        when(parameterServiceMock.getPurchase(eq(req))).thenReturn(mockPurchase);

        assertEquals(INCORRECT_DGS_DISCOUNT, t.processRequest(req));
    }

    @Test
    public void processRequest_dgsDiscountNegativeIsNotSafeMultiply() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(DISCOUNT_NQT_PARAMETER, "99999999999"));

        final Account mockSellerAccount = mock(Account.class);
        final Purchase mockPurchase = mock(Purchase.class);

        when(mockSellerAccount.getId()).thenReturn(1L);
        when(mockPurchase.getSellerId()).thenReturn(1L);
        when(mockPurchase.getQuantity()).thenReturn(999999999);
        when(mockPurchase.getPriceNQT()).thenReturn(1L);

        when(mockPurchase.isPending()).thenReturn(true);

        when(parameterServiceMock.getSenderAccount(eq(req))).thenReturn(mockSellerAccount);
        when(parameterServiceMock.getPurchase(eq(req))).thenReturn(mockPurchase);

        assertEquals(INCORRECT_DGS_DISCOUNT, t.processRequest(req));
    }

    @Test
    public void processRequest_goodsToEncryptIsEmptyIsIncorrectDGSGoods() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(DISCOUNT_NQT_PARAMETER, "9"),
                new MockParam(GOODS_TO_ENCRYPT_PARAMETER, ""),
                new MockParam(SECRET_PHRASE_PARAMETER, TEST_SECRET_PHRASE));

        final Account mockSellerAccount = mock(Account.class);
        final Purchase mockPurchase = mock(Purchase.class);

        when(mockSellerAccount.getId()).thenReturn(1L);
        when(mockPurchase.getSellerId()).thenReturn(1L);
        when(mockPurchase.getQuantity()).thenReturn(9);
        when(mockPurchase.getPriceNQT()).thenReturn(1L);

        when(mockPurchase.isPending()).thenReturn(true);

        when(parameterServiceMock.getSenderAccount(eq(req))).thenReturn(mockSellerAccount);
        when(parameterServiceMock.getPurchase(eq(req))).thenReturn(mockPurchase);

        assertEquals(INCORRECT_DGS_GOODS, t.processRequest(req));
    }

}
