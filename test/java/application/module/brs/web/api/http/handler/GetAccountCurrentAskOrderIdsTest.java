package application.module.brs.web.api.http.handler;

import application.module.brs.Account;
import application.module.brs.SignumException;
import application.module.brs.Order;
import application.module.brs.Order.Ask;
import application.module.brs.assetexchange.AssetExchange;
import application.module.brs.common.AbstractUnitTest;
import application.module.brs.common.QuickMocker;
import application.module.brs.common.QuickMocker.MockParam;
import application.module.brs.services.ParameterService;
import application.module.brs.util.CollectionWithIndex;
import application.module.brs.util.JSON;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;

import static application.module.brs.web.api.http.common.Parameters.*;
import static application.module.brs.web.api.http.common.ResultFields.ASK_ORDER_IDS_RESPONSE;
import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

;

public class GetAccountCurrentAskOrderIdsTest extends AbstractUnitTest {

    private GetAccountCurrentAskOrderIds t;

    private ParameterService mockParameterService;
    private AssetExchange mockAssetExchange;

    @Before
    public void setUp() {
        mockParameterService = mock(ParameterService.class);
        mockAssetExchange = mock(AssetExchange.class);

        t = new GetAccountCurrentAskOrderIds(mockParameterService, mockAssetExchange);
    }

    @Test
    public void processRequest_getAskOrdersByAccount() throws SignumException {
        final long accountId = 2L;
        final int firstIndex = 1;
        final int lastIndex = 2;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ACCOUNT_PARAMETER, accountId),
                new MockParam(FIRST_INDEX_PARAMETER, firstIndex),
                new MockParam(LAST_INDEX_PARAMETER, lastIndex));

        final Account mockAccount = mock(Account.class);
        when(mockAccount.getId()).thenReturn(accountId);
        when(mockParameterService.getAccount(eq(req))).thenReturn(mockAccount);

        final Ask mockAsk = mock(Ask.class);
        when(mockAsk.getId()).thenReturn(1L);

        final Collection<Ask> mockAskIterator = mockCollection(mockAsk);

        when(mockAssetExchange.getAskOrdersByAccount(eq(accountId), eq(firstIndex), eq(lastIndex)))
                .thenReturn(new CollectionWithIndex<Order.Ask>(mockAskIterator, -1));

        final JsonObject result = (JsonObject) t.processRequest(req);

        assertNotNull(result);

        final JsonArray resultList = (JsonArray) result.get(ASK_ORDER_IDS_RESPONSE);
        assertNotNull(resultList);
        assertEquals(1, (resultList).size());

        assertEquals("" + mockAsk.getId(), JSON.getAsString(resultList.get(0)));
    }

    @Test
    public void processRequest_getAskOrdersByAccountAsset() throws SignumException {
        final long assetId = 1L;
        final long accountId = 2L;
        final int firstIndex = 1;
        final int lastIndex = 2;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ACCOUNT_PARAMETER, accountId),
                new MockParam(ASSET_PARAMETER, assetId),
                new MockParam(FIRST_INDEX_PARAMETER, firstIndex),
                new MockParam(LAST_INDEX_PARAMETER, lastIndex));

        final Account mockAccount = mock(Account.class);
        when(mockAccount.getId()).thenReturn(accountId);
        when(mockParameterService.getAccount(eq(req))).thenReturn(mockAccount);

        final Ask mockAsk = mock(Ask.class);
        when(mockAsk.getId()).thenReturn(1L);

        final Collection<Ask> mockAskIterator = mockCollection(mockAsk);

        when(mockAssetExchange.getAskOrdersByAccountAsset(eq(accountId), eq(assetId), eq(firstIndex), eq(lastIndex)))
                .thenReturn(new CollectionWithIndex<Order.Ask>(mockAskIterator, -1));

        final JsonObject result = (JsonObject) t.processRequest(req);

        assertNotNull(result);

        final JsonArray resultList = (JsonArray) result.get(ASK_ORDER_IDS_RESPONSE);
        assertNotNull(resultList);
        assertEquals(1, (resultList).size());

        assertEquals("" + mockAsk.getId(), JSON.getAsString(resultList.get(0)));
    }

}
