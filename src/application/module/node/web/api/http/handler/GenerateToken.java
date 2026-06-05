package application.module.node.web.api.http.handler;

import application.module.node.Token;
import application.module.node.services.TimeService;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.Constants.TOKEN;
import static application.module.node.Constants.WEBSITE;
import static application.module.node.web.api.http.common.JSONResponses.*;
import static application.module.node.web.api.http.common.Parameters.SECRET_PHRASE_PARAMETER;

public final class GenerateToken extends ApiServlet.JsonRequestHandler {

    private final TimeService timeService;

    public GenerateToken(TimeService timeService) {
        super(new LegacyDocTag[] { LegacyDocTag.TOKENS }, WEBSITE, SECRET_PHRASE_PARAMETER);
        this.timeService = timeService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {

        String secretPhrase = req.getParameter(SECRET_PHRASE_PARAMETER);
        String website = req.getParameter(WEBSITE);
        if (secretPhrase == null) {
            return MISSING_SECRET_PHRASE;
        } else if (website == null) {
            return MISSING_WEBSITE;
        }

        try {

            String tokenString = Token.generateToken(secretPhrase, website.trim(), timeService.getEpochTime());

            JsonObject response = new JsonObject();
            response.addProperty(TOKEN, tokenString);

            return response;

        } catch (RuntimeException e) {
            return INCORRECT_WEBSITE;
        }

    }

    // @Override
    // boolean requirePost() {
    // return true;
    // }

}
