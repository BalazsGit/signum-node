package application.module.brs.web.api.http.handler;

import application.module.brs.common.QuickMocker;
import application.module.brs.services.TimeService;
import application.module.brs.util.JSON;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.ResultFields.TIME_RESPONSE;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GetTimeTest {

    private GetTime t;

    private TimeService mockTimeService;

    @Before
    public void setUp() {
        mockTimeService = mock(TimeService.class);

        t = new GetTime(mockTimeService);
    }

    @Test
    public void processRequest() {
        HttpServletRequest req = QuickMocker.httpServletRequest();

        final int currentEpochTime = 123;

        when(mockTimeService.getEpochTime()).thenReturn(currentEpochTime);

        final JsonObject result = (JsonObject) t.processRequest(req);

        assertEquals(currentEpochTime, JSON.getAsInt(result.get(TIME_RESPONSE)));
    }

}
