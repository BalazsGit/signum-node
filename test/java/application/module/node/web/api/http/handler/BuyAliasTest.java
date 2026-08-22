package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.Alias.Offer;
import application.module.node.common.QuickMocker;
import application.module.node.common.QuickMocker.MockParam;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.services.AliasService;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.common.APITransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.TransactionType.Messaging.ALIAS_BUY;
import static application.module.node.web.api.http.common.JSONResponses.INCORRECT_ALIAS_NOTFORSALE;
import static application.module.node.web.api.http.common.Parameters.AMOUNT_NQT_PARAMETER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class BuyAliasTest extends AbstractTransactionTest {

    private BuyAlias t;

    @Mock
    private ParameterService parameterServiceMock;
    @Mock
    private Blockchain blockchain;
    @Mock
    private AliasService aliasService;
    @Mock
    private APITransactionManager apiTransactionManagerMock;

    @BeforeEach
    void init() {
        FluxCapacitor fluxCapacitor = QuickMocker.latestValueFluxCapacitor();
        t = new BuyAlias(parameterServiceMock, blockchain, aliasService, apiTransactionManagerMock, fluxCapacitor);
    }

    @Test
    void processRequest() throws SignumException {
        final HttpServletRequest req = QuickMocker
                .httpServletRequestDefaultKeys(new MockParam(AMOUNT_NQT_PARAMETER, "" + Constants.ONE_SIGNA));

        final Offer mockOfferOnAlias = mock(Offer.class);

        final String mockAliasName = "mockAliasName";
        final Alias mockAlias = mock(Alias.class);
        final long mockSellerId = 123L;

        when(mockAlias.getAccountId()).thenReturn(mockSellerId);
        when(mockAlias.getAliasName()).thenReturn(mockAliasName);

        when(aliasService.getOffer(eq(mockAlias))).thenReturn(mockOfferOnAlias);

        when(parameterServiceMock.getAlias(eq(req))).thenReturn(mockAlias);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            final FluxCapacitor fluxCapacitor = QuickMocker
                    .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
            doReturn(Constants.FEE_QUANT_SIP3).when(fluxCapacitor).getValue(eq(FluxValues.FEE_QUANT));

            final Attachment.MessagingAliasBuy attachment = (Attachment.MessagingAliasBuy) attachmentCreatedTransaction(
                    () -> t.processRequest(req), apiTransactionManagerMock);
            assertNotNull(attachment);

            assertEquals(ALIAS_BUY, attachment.getTransactionType());
            assertEquals(mockAliasName, attachment.getAliasName());
        }
    }

    @Test
    void processRequest_aliasNotForSale() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(new MockParam(AMOUNT_NQT_PARAMETER, "3"));
        final Alias mockAlias = mock(Alias.class);

        when(parameterServiceMock.getAlias(eq(req))).thenReturn(mockAlias);

        when(aliasService.getOffer(eq(mockAlias))).thenReturn(null);

        assertEquals(INCORRECT_ALIAS_NOTFORSALE, t.processRequest(req));
    }
}