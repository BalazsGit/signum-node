package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.common.QuickMocker;
import application.module.node.common.QuickMocker.MockParam;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.services.ParameterService;
import application.module.node.services.SubscriptionService;
import application.module.node.util.JSON;
import application.module.node.web.api.http.common.APITransactionManager;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.TransactionType.AdvancedPayment.SUBSCRIPTION_CANCEL;
import static application.module.node.web.api.http.common.Parameters.SUBSCRIPTION_PARAMETER;
import static application.module.node.web.api.http.common.ResultFields.ERROR_CODE_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class SubscriptionCancelTest extends AbstractTransactionTest {

    private SubscriptionCancel t;

    @Mock
    private ParameterService mockParameterService;
    @Mock
    private SubscriptionService mockSubscriptionService;
    @Mock
    private Blockchain mockBlockchain;
    @Mock
    private APITransactionManager apiTransactionManagerMock;

    @BeforeEach
    void setUp() {
        FluxCapacitor fluxCapacitor = QuickMocker.latestValueFluxCapacitor();
        t = new SubscriptionCancel(mockParameterService, mockSubscriptionService, mockBlockchain,
                apiTransactionManagerMock, fluxCapacitor);
    }

    @Test
    void processRequest() throws SignumException {
        final Long subscriptionIdParameter = 123L;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(SUBSCRIPTION_PARAMETER, subscriptionIdParameter));

        final Account mockSender = mock(Account.class);
        when(mockSender.getId()).thenReturn(1L);

        final Subscription mockSubscription = mock(Subscription.class);
        when(mockSubscription.getId()).thenReturn(subscriptionIdParameter);
        when(mockSubscription.getSenderId()).thenReturn(1L);
        when(mockSubscription.getRecipientId()).thenReturn(2L);

        when(mockParameterService.getSenderAccount(eq(req))).thenReturn(mockSender);
        when(mockSubscriptionService.getSubscription(eq(subscriptionIdParameter))).thenReturn(mockSubscription);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            final FluxCapacitor fluxCapacitor = QuickMocker
                    .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
            doReturn(Constants.FEE_QUANT_SIP3).when(fluxCapacitor).getValue(eq(FluxValues.FEE_QUANT));

            final Attachment.AdvancedPaymentSubscriptionCancel attachment = (Attachment.AdvancedPaymentSubscriptionCancel) attachmentCreatedTransaction(
                    () -> t.processRequest(req), apiTransactionManagerMock);
            assertNotNull(attachment);

            assertEquals(SUBSCRIPTION_CANCEL, attachment.getTransactionType());
            assertEquals(subscriptionIdParameter, attachment.getSubscriptionId());
        }
    }

    @Test
    void processRequest_missingSubscriptionParameter() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest();

        final JsonObject response = (JsonObject) t.processRequest(req);
        assertNotNull(response);

        assertEquals(3, JSON.getAsInt(response.get(ERROR_CODE_RESPONSE)));
    }

    @Test
    void processRequest_failedToParseSubscription() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(SUBSCRIPTION_PARAMETER, "notALong"));

        final JsonObject response = (JsonObject) t.processRequest(req);
        assertNotNull(response);

        assertEquals(4, JSON.getAsInt(response.get(ERROR_CODE_RESPONSE)));
    }

    @Test
    void processRequest_subscriptionNotFound() throws SignumException {
        final long subscriptionId = 123L;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(SUBSCRIPTION_PARAMETER, subscriptionId));

        when(mockSubscriptionService.getSubscription(eq(subscriptionId))).thenReturn(null);

        final JsonObject response = (JsonObject) t.processRequest(req);
        assertNotNull(response);

        assertEquals(5, JSON.getAsInt(response.get(ERROR_CODE_RESPONSE)));
    }

    @Test
    void processRequest_userIsNotSenderOrRecipient() throws SignumException {
        final long subscriptionId = 123L;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(SUBSCRIPTION_PARAMETER, subscriptionId));

        final Account mockSender = mock(Account.class);
        when(mockSender.getId()).thenReturn(1L);

        final Subscription mockSubscription = mock(Subscription.class);
        when(mockSubscription.getSenderId()).thenReturn(2L);
        when(mockSubscription.getRecipientId()).thenReturn(3L);

        when(mockParameterService.getSenderAccount(eq(req))).thenReturn(mockSender);
        when(mockSubscriptionService.getSubscription(eq(subscriptionId))).thenReturn(mockSubscription);

        final JsonObject response = (JsonObject) t.processRequest(req);
        assertNotNull(response);

        assertEquals(7, JSON.getAsInt(response.get(ERROR_CODE_RESPONSE)));
    }
}