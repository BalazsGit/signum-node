package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.DigitalGoodsStore.Goods;
import application.module.node.common.QuickMocker;
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

import static application.module.node.TransactionType.DigitalGoods.DELISTING;
import static application.module.node.web.api.http.common.JSONResponses.UNKNOWN_GOODS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class DGSDelistingTest extends AbstractTransactionTest {

    private DGSDelisting t;

    @Mock
    private ParameterService mockParameterService;
    @Mock
    private Blockchain mockBlockchain;
    @Mock
    private APITransactionManager apiTransactionManagerMock;

    @BeforeEach
    void setUp() {
        FluxCapacitor fluxCapacitor = QuickMocker.latestValueFluxCapacitor();
        t = new DGSDelisting(mockParameterService, mockBlockchain, apiTransactionManagerMock, fluxCapacitor);
    }

    @Test
    void processRequest() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest();

        final Account mockAccount = mock(Account.class);
        final Goods mockGoods = mock(Goods.class);

        when(mockGoods.isDelisted()).thenReturn(false);
        when(mockGoods.getSellerId()).thenReturn(1L);
        when(mockAccount.getId()).thenReturn(1L);

        when(mockParameterService.getSenderAccount(eq(req))).thenReturn(mockAccount);
        when(mockParameterService.getGoods(eq(req))).thenReturn(mockGoods);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            final FluxCapacitor fluxCapacitor = QuickMocker
                    .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
            mocked.when(Signum::getFluxCapacitor).thenReturn(fluxCapacitor);
            doReturn(Constants.FEE_QUANT_SIP3).when(fluxCapacitor).getValue(eq(FluxValues.FEE_QUANT));

            final Attachment.DigitalGoodsDelisting attachment = (Attachment.DigitalGoodsDelisting) attachmentCreatedTransaction(
                    () -> t.processRequest(req), apiTransactionManagerMock);
            assertNotNull(attachment);

            assertEquals(DELISTING, attachment.getTransactionType());
            assertEquals(mockGoods.getId(), attachment.getGoodsId());
        }
    }

    @Test
    void processRequest_goodsDelistedUnknownGoods() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest();

        final Account mockAccount = mock(Account.class);
        final Goods mockGoods = mock(Goods.class);

        when(mockGoods.isDelisted()).thenReturn(true);

        when(mockParameterService.getSenderAccount(eq(req))).thenReturn(mockAccount);
        when(mockParameterService.getGoods(eq(req))).thenReturn(mockGoods);

        assertEquals(UNKNOWN_GOODS, t.processRequest(req));
    }

    @Test
    void processRequest_otherSellerIdUnknownGoods() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest();

        final Account mockAccount = mock(Account.class);
        final Goods mockGoods = mock(Goods.class);

        when(mockGoods.isDelisted()).thenReturn(false);
        when(mockGoods.getSellerId()).thenReturn(1L);
        when(mockAccount.getId()).thenReturn(2L);

        when(mockParameterService.getSenderAccount(eq(req))).thenReturn(mockAccount);
        when(mockParameterService.getGoods(eq(req))).thenReturn(mockGoods);

        assertEquals(UNKNOWN_GOODS, t.processRequest(req));
    }
}