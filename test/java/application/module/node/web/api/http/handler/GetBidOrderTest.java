package application.module.node.web.api.http.handler;

import application.module.node.SignumException;
import application.module.node.Order.Bid;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.common.QuickMocker;
import application.module.node.common.QuickMocker.MockParam;
import application.module.node.util.JSON;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.JSONResponses.UNKNOWN_ORDER;
import static application.module.node.web.api.http.common.Parameters.ORDER_PARAMETER;
import static application.module.node.web.api.http.common.ResultFields.ORDER_RESPONSE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GetBidOrderTest {

    private GetBidOrder t;

    private AssetExchange mockAssetExchange;

    @Before
    public void setUp() {
        mockAssetExchange = mock(AssetExchange.class);

        t = new GetBidOrder(mockAssetExchange);
    }

    @Test
    public void processRequest() throws SignumException {
        final long bidOrderId = 123L;
        Bid mockBid = mock(Bid.class);
        when(mockBid.getId()).thenReturn(bidOrderId);

        when(mockAssetExchange.getBidOrder(eq(bidOrderId))).thenReturn(mockBid);

        HttpServletRequest req = QuickMocker.httpServletRequest(new MockParam(ORDER_PARAMETER, bidOrderId));

        final JsonObject result = (JsonObject) t.processRequest(req);
        assertNotNull(result);
        assertEquals("" + bidOrderId, JSON.getAsString(result.get(ORDER_RESPONSE)));
    }

    @Test
    public void processRequest_orderNotFoundUnknownOrder() throws SignumException {
        final long bidOrderId = 123L;

        HttpServletRequest req = QuickMocker.httpServletRequest(new MockParam(ORDER_PARAMETER, bidOrderId));

        assertEquals(UNKNOWN_ORDER, t.processRequest(req));
    }

}
