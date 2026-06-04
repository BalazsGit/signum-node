package application.module.brs.web.api.http.handler;

import application.module.brs.Asset;
import application.module.brs.SignumException;
import application.module.brs.Order;
import application.module.brs.Order.Bid;
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

import static application.module.brs.web.api.http.common.Parameters.FIRST_INDEX_PARAMETER;
import static application.module.brs.web.api.http.common.Parameters.LAST_INDEX_PARAMETER;
import static application.module.brs.web.api.http.common.ResultFields.BID_ORDERS_RESPONSE;
import static application.module.brs.web.api.http.common.ResultFields.ORDER_RESPONSE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

;

public class GetBidOrdersTest extends AbstractUnitTest {

    private GetBidOrders t;

    private ParameterService mockParameterService;
    private AssetExchange mockAssetExchange;

    @Before
    public void setUp() {
        mockParameterService = mock(ParameterService.class);
        mockAssetExchange = mock(AssetExchange.class);

        t = new GetBidOrders(mockParameterService, mockAssetExchange);
    }

    @Test
    public void processRequest() throws SignumException {
        final long assetId = 123L;
        final int firstIndex = 0;
        final int lastIndex = 1;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(FIRST_INDEX_PARAMETER, firstIndex),
                new MockParam(LAST_INDEX_PARAMETER, lastIndex));

        final Asset mockAsset = mock(Asset.class);
        when(mockAsset.getId()).thenReturn(assetId);

        long mockOrderId = 345L;
        final Bid mockBid = mock(Bid.class);
        when(mockBid.getId()).thenReturn(mockOrderId);

        final Collection<Bid> mockBidIterator = mockCollection(mockBid);

        when(mockParameterService.getAsset(req)).thenReturn(mockAsset);
        when(mockAssetExchange.getSortedBidOrders(eq(assetId), eq(firstIndex), eq(lastIndex)))
                .thenReturn(new CollectionWithIndex<Order.Bid>(mockBidIterator, -1));

        final JsonObject result = (JsonObject) t.processRequest(req);
        assertNotNull(result);

        final JsonArray resultBidOrdersList = (JsonArray) result.get(BID_ORDERS_RESPONSE);
        assertNotNull(resultBidOrdersList);
        assertEquals(1, resultBidOrdersList.size());

        final JsonObject resultBidOrder = (JsonObject) resultBidOrdersList.get(0);
        assertNotNull(resultBidOrder);

        assertEquals("" + mockOrderId, JSON.getAsString(resultBidOrder.get(ORDER_RESPONSE)));
    }

}
