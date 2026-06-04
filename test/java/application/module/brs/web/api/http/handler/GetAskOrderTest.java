package application.module.brs.web.api.http.handler;

import application.module.brs.SignumException;
import application.module.brs.Order.Ask;
import application.module.brs.assetexchange.AssetExchange;
import application.module.brs.common.QuickMocker;
import application.module.brs.common.QuickMocker.MockParam;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.JSONResponses.UNKNOWN_ORDER;
import static application.module.brs.web.api.http.common.Parameters.ORDER_PARAMETER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GetAskOrderTest {

    private GetAskOrder t;

    private AssetExchange mockAssetExchange;

    @Before
    public void setUp() {
        mockAssetExchange = mock(AssetExchange.class);

        t = new GetAskOrder(mockAssetExchange);
    }

    @Test
    public void processRequest() throws SignumException {
        final long orderId = 123L;

        final Ask mockOrder = mock(Ask.class);

        when(mockAssetExchange.getAskOrder(eq(orderId))).thenReturn(mockOrder);

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ORDER_PARAMETER, orderId));

        final JsonObject result = (JsonObject) t.processRequest(req);
        assertNotNull(result);
    }

    @Test
    public void processRequest_unknownOrder() throws SignumException {
        final long orderId = 123L;

        when(mockAssetExchange.getAskOrder(eq(orderId))).thenReturn(null);

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ORDER_PARAMETER, orderId));

        assertEquals(UNKNOWN_ORDER, t.processRequest(req));
    }

}
