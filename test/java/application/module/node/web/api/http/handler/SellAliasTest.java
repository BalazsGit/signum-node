package application.module.node.web.api.http.handler;

import application.module.node.*;
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

import static application.module.node.Constants.MAX_BALANCE_NQT;
import static application.module.node.TransactionType.Messaging.ALIAS_SELL;
import static application.module.node.web.api.http.common.JSONResponses.*;
import static application.module.node.web.api.http.common.Parameters.PRICE_NQT_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.RECIPIENT_PARAMETER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class SellAliasTest extends AbstractTransactionTest {

    private SellAlias t;

    @Mock
    private ParameterService mockParameterService;
    @Mock
    private Blockchain mockBlockchain;
    @Mock
    private APITransactionManager apiTransactionManagerMock;

    @BeforeEach
    void setUp() {
        FluxCapacitor fluxCapacitor = QuickMocker.latestValueFluxCapacitor();
        t = new SellAlias(mockParameterService, mockBlockchain, apiTransactionManagerMock, fluxCapacitor);
    }

    @Test
    void processRequest() throws SignumException {
        final int priceParameter = 10;
        final int recipientId = 5;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(PRICE_NQT_PARAMETER, priceParameter),
                new MockParam(RECIPIENT_PARAMETER, recipientId));

        final long aliasAccountId = 1L;
        final Alias mockAlias = mock(Alias.class);
        when(mockAlias.getAccountId()).thenReturn(aliasAccountId);

        final Account mockSender = mock(Account.class);
        when(mockSender.getId()).thenReturn(aliasAccountId);

        when(mockParameterService.getSenderAccount(req)).thenReturn(mockSender);
        when(mockParameterService.getAlias(req)).thenReturn(mockAlias);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            final FluxCapacitor fluxCapacitor = QuickMocker
                    .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
            mocked.when(Signum::getFluxCapacitor).thenReturn(fluxCapacitor);
            doReturn(Constants.FEE_QUANT_SIP3).when(fluxCapacitor).getValue(eq(FluxValues.FEE_QUANT));

            final Attachment.MessagingAliasSell attachment = (Attachment.MessagingAliasSell) attachmentCreatedTransaction(
                    () -> t.processRequest(req), apiTransactionManagerMock);
            assertNotNull(attachment);

            assertEquals(ALIAS_SELL, attachment.getTransactionType());
            assertEquals(priceParameter, attachment.getPriceNqt());
        }
    }

    @Test
    void processRequest_missingPrice() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest();

        assertEquals(MISSING_PRICE, t.processRequest(req));
    }

    @Test
    void processRequest_incorrectPrice_unParsable() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(PRICE_NQT_PARAMETER, "unParsable"));

        assertEquals(INCORRECT_PRICE, t.processRequest(req));
    }

    @Test
    void processRequest_incorrectPrice_negative() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(PRICE_NQT_PARAMETER, -10));

        try {
            t.processRequest(req);
        } catch (Exception e) {
            assertEquals(ParameterException.class, e.getClass());
        }
    }

    @Test
    void processRequest_incorrectPrice_overMaxBalance() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(PRICE_NQT_PARAMETER, MAX_BALANCE_NQT + 1));

        try {
            t.processRequest(req);
        } catch (Exception e) {
            assertEquals(ParameterException.class, e.getClass());
        }
    }

    @Test
    void processRequest_incorrectRecipient_unparsable() throws SignumException {
        final int price = 10;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(PRICE_NQT_PARAMETER, price),
                new MockParam(RECIPIENT_PARAMETER, "unParsable"));

        assertEquals(INCORRECT_RECIPIENT, t.processRequest(req));
    }

    @Test
    void processRequest_incorrectRecipient_zero() throws SignumException {
        final int price = 10;
        final int recipientId = 0;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(PRICE_NQT_PARAMETER, price),
                new MockParam(RECIPIENT_PARAMETER, recipientId));

        assertEquals(INCORRECT_RECIPIENT, t.processRequest(req));
    }

    @Test
    void processRequest_incorrectAliasOwner() throws SignumException {
        final int price = 10;
        final int recipientId = 5;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(PRICE_NQT_PARAMETER, price),
                new MockParam(RECIPIENT_PARAMETER, recipientId));

        final long aliasAccountId = 1L;
        final Alias mockAlias = mock(Alias.class);
        when(mockAlias.getAccountId()).thenReturn(aliasAccountId);

        final long mockSenderId = 2l;
        final Account mockSender = mock(Account.class);
        when(mockSender.getId()).thenReturn(mockSenderId);

        when(mockParameterService.getSenderAccount(req)).thenReturn(mockSender);
        when(mockParameterService.getAlias(req)).thenReturn(mockAlias);

        assertEquals(INCORRECT_ALIAS_OWNER, t.processRequest(req));
    }
}