package application.module.brs.web.api.http.handler;

import application.module.brs.SignumException;
import application.module.brs.services.ParameterService;
import application.module.brs.web.api.http.ApiServlet;
import application.module.brs.web.api.http.common.JSONData;
import application.module.brs.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.Parameters.PURCHASE_PARAMETER;

public final class GetDGSPurchase extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;

    public GetDGSPurchase(ParameterService parameterService) {
        super(new LegacyDocTag[] { LegacyDocTag.DGS }, PURCHASE_PARAMETER);
        this.parameterService = parameterService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        return JSONData.purchase(parameterService.getPurchase(req));
    }

}
