package application.module.node.web.api.http.handler;

import application.module.node.SignumException;
import application.module.node.common.QuickMocker;
import application.module.node.feesuggestions.FeeSuggestion;
import application.module.node.feesuggestions.FeeSuggestionCalculator;
import application.module.node.util.JSON;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.Constants.FEE_QUANT_SIP3;
import static application.module.node.web.api.http.common.ResultFields.*;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SuggestFeeTest {

    private SuggestFee t;

    private FeeSuggestionCalculator feeSuggestionCalculator;

    @Before
    public void setUp() {
        feeSuggestionCalculator = mock(FeeSuggestionCalculator.class);

        t = new SuggestFee(feeSuggestionCalculator);
    }

    @Test
    public void processRequest() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest();

        final long cheap = 1 * FEE_QUANT_SIP3;
        final long standard = 5 * FEE_QUANT_SIP3;
        final long priority = 10 * FEE_QUANT_SIP3;
        final FeeSuggestion feeSuggestion = new FeeSuggestion(cheap, standard, priority);

        when(feeSuggestionCalculator.giveFeeSuggestion()).thenReturn(feeSuggestion);

        final JsonObject result = (JsonObject) t.processRequest(req);

        assertEquals(cheap, JSON.getAsLong(result.get(CHEAP_FEE_RESPONSE)));
        assertEquals(standard, JSON.getAsLong(result.get(STANDARD_FEE_RESPONSE)));
        assertEquals(priority, JSON.getAsLong(result.get(PRIORITY_FEE_RESPONSE)));
    }
}
