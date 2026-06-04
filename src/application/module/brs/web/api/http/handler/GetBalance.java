package application.module.brs.web.api.http.handler;

import application.module.brs.SignumException;
import application.module.brs.web.api.http.common.LegacyDocTag;
import application.module.brs.web.api.http.common.Parameters;
import application.module.brs.services.ParameterService;
import application.module.brs.web.api.http.ApiServlet;
import application.module.brs.web.api.http.common.JSONData;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

public final class GetBalance extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;

    public GetBalance(ParameterService parameterService) {
        super(new LegacyDocTag[] { LegacyDocTag.ACCOUNTS }, Parameters.ACCOUNT_PARAMETER);
        this.parameterService = parameterService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        return JSONData.accountBalance(parameterService.getAccount(req));
    }

}
