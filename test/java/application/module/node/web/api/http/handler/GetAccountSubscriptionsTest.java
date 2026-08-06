package application.module.node.web.api.http.handler;

import application.module.node.Account;
import application.module.node.Blockchain;
import application.module.node.Signum;
import application.module.node.SignumException;
import application.module.node.Subscription;
import application.module.node.common.AbstractUnitTest;
import application.module.node.common.QuickMocker;
import application.module.node.common.QuickMocker.MockParam;
import application.module.node.services.AliasService;
import application.module.node.services.ParameterService;
import application.module.node.services.SubscriptionService;
import application.module.node.util.JSON;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;

import static application.module.node.web.api.http.common.Parameters.ACCOUNT_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.SUBSCRIPTIONS_RESPONSE;
import static application.module.node.web.api.http.common.ResultFields.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class GetAccountSubscriptionsTest extends AbstractUnitTest {

    private ParameterService parameterServiceMock;
    private SubscriptionService subscriptionServiceMock;
    private AliasService aliasServiceMock;

    private GetAccountSubscriptions t;

    @Before
    public void setUp() {
        parameterServiceMock = mock(ParameterService.class);
        subscriptionServiceMock = mock(SubscriptionService.class);
        aliasServiceMock = mock(AliasService.class);
    }

    @Test
    public void processRequest() throws SignumException {
        final long userId = 123L;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ACCOUNT_PARAMETER, userId));

        final Account account = mock(Account.class);
        when(account.getId()).thenReturn(userId);
        when(parameterServiceMock.getAccount(eq(req))).thenReturn(account);

        final Subscription subscription = mock(Subscription.class);
        when(subscription.getId()).thenReturn(1L);
        when(subscription.getAmountNQT()).thenReturn(2L);
        when(subscription.getFrequency()).thenReturn(3);
        when(subscription.getTimeNext()).thenReturn(4);

        final Collection<Subscription> subscriptionIterator = this.mockCollection(subscription);
        when(subscriptionServiceMock.getSubscriptionsByParticipant(eq(userId))).thenReturn(subscriptionIterator);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            Blockchain mockBlockchain = mock(Blockchain.class);
            mocked.when(Signum::getBlockchain).thenReturn(mockBlockchain);

            t = new GetAccountSubscriptions(parameterServiceMock, subscriptionServiceMock, aliasServiceMock);

            final JsonObject result = (JsonObject) t.processRequest(req);
            assertNotNull(result);

            final JsonArray resultSubscriptions = (JsonArray) result.get(SUBSCRIPTIONS_RESPONSE);
            assertNotNull(resultSubscriptions);
            assertEquals(1, resultSubscriptions.size());

            final JsonObject resultSubscription = (JsonObject) resultSubscriptions.get(0);
            assertNotNull(resultSubscription);

            assertEquals("" + subscription.getId(), JSON.getAsString(resultSubscription.get(ID_RESPONSE)));
            assertEquals("" + subscription.getAmountNQT(), JSON.getAsString(resultSubscription.get(AMOUNT_NQT_RESPONSE)));
            assertEquals(subscription.getFrequency(), JSON.getAsInt(resultSubscription.get(FREQUENCY_RESPONSE)));
            assertEquals(subscription.getTimeNext(), JSON.getAsInt(resultSubscription.get(TIME_NEXT_RESPONSE)));
        }
    }

}