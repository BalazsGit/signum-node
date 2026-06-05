package application.module.node.web.api.http.handler;

import application.module.node.Order;
import application.module.node.Order.Ask;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.common.AbstractUnitTest;
import application.module.node.common.QuickMocker;
import application.module.node.common.QuickMocker.MockParam;
import application.module.node.util.CollectionWithIndex;
import application.module.node.util.JSON;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import java.util.Collection;

import static application.module.node.web.api.http.common.Parameters.FIRST_INDEX_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.LAST_INDEX_PARAMETER;
import static application.module.node.web.api.http.common.ResultFields.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

;

public class GetAllOpenAskOrdersTest extends AbstractUnitTest {

    private GetAllOpenAskOrders t;

    private AssetExchange mockAssetExchange;

    @Before
    public void setUp() {
        mockAssetExchange = mock(AssetExchange.class);

        t = new GetAllOpenAskOrders(mockAssetExchange);
    }

    @Test
    public void processRequest() {
        final Ask mockAskOrder = mock(Ask.class);
        when(mockAskOrder.getId()).thenReturn(1L);
        when(mockAskOrder.getAssetId()).thenReturn(2L);
        when(mockAskOrder.getQuantityQNT()).thenReturn(3L);
        when(mockAskOrder.getPriceNQT()).thenReturn(4L);
        when(mockAskOrder.getHeight()).thenReturn(5);

        final int firstIndex = 1;
        final int lastIndex = 2;

        final Collection<Ask> mockIterator = mockCollection(mockAskOrder);
        when(mockAssetExchange.getAllAskOrders(eq(firstIndex), eq(lastIndex)))
                .thenReturn(new CollectionWithIndex<Order.Ask>(mockIterator, -1));

        final JsonObject result = (JsonObject) t.processRequest(QuickMocker.httpServletRequest(
                new MockParam(FIRST_INDEX_PARAMETER, "" + firstIndex),
                new MockParam(LAST_INDEX_PARAMETER, "" + lastIndex)));

        assertNotNull(result);
        final JsonArray openOrdersResult = (JsonArray) result.get(OPEN_ORDERS_RESPONSE);

        assertNotNull(openOrdersResult);
        assertEquals(1, openOrdersResult.size());

        final JsonObject openOrderResult = (JsonObject) openOrdersResult.get(0);
        assertEquals("" + mockAskOrder.getId(), JSON.getAsString(openOrderResult.get(ORDER_RESPONSE)));
        assertEquals("" + mockAskOrder.getAssetId(), JSON.getAsString(openOrderResult.get(ASSET_RESPONSE)));
        assertEquals("" + mockAskOrder.getQuantityQNT(), JSON.getAsString(openOrderResult.get(QUANTITY_QNT_RESPONSE)));
        assertEquals("" + mockAskOrder.getPriceNQT(), JSON.getAsString(openOrderResult.get(PRICE_NQT_RESPONSE)));
        assertEquals(mockAskOrder.getHeight(), JSON.getAsInt(openOrderResult.get(HEIGHT_RESPONSE)));
    }
}
