package application.module.brs.web.api.http.handler;

import application.module.brs.feesuggestions.FeeSuggestion;
import application.module.brs.feesuggestions.FeeSuggestionCalculator;
import application.module.brs.web.api.http.ApiServlet;
import application.module.brs.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.ResultFields.*;

public class SuggestFee extends ApiServlet.JsonRequestHandler {

    private final FeeSuggestionCalculator feeSuggestionCalculator;

    public SuggestFee(FeeSuggestionCalculator feeSuggestionCalculator) {
        super(new LegacyDocTag[] { LegacyDocTag.FEES });
        this.feeSuggestionCalculator = feeSuggestionCalculator;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {
        final FeeSuggestion feeSuggestion = feeSuggestionCalculator.giveFeeSuggestion();

        final JsonObject response = new JsonObject();

        response.addProperty(CHEAP_FEE_RESPONSE, feeSuggestion.getCheapFee());
        response.addProperty(STANDARD_FEE_RESPONSE, feeSuggestion.getStandardFee());
        response.addProperty(PRIORITY_FEE_RESPONSE, feeSuggestion.getPriorityFee());

        return response;
    }

}
