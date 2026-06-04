package application.module.brs.web.api.http.handler;

import application.module.brs.Account;
import application.module.brs.SignumException;
import application.module.brs.services.ParameterService;
import application.module.brs.util.Convert;
import application.module.brs.util.JSON;
import application.module.brs.web.api.http.ApiServlet;
import application.module.brs.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.Parameters.ACCOUNT_PARAMETER;
import static application.module.brs.web.api.http.common.ResultFields.PUBLIC_KEY_RESPONSE;

public final class GetAccountPublicKey extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;

    public GetAccountPublicKey(ParameterService parameterService) {
        super(new LegacyDocTag[] { LegacyDocTag.ACCOUNTS }, ACCOUNT_PARAMETER);
        this.parameterService = parameterService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        Account account = parameterService.getAccount(req);

        if (account.getPublicKey() != null) {
            JsonObject response = new JsonObject();
            response.addProperty(PUBLIC_KEY_RESPONSE, Convert.toHexString(account.getPublicKey()));
            return response;
        } else {
            return JSON.emptyJSON;
        }
    }

}
