package application.module.brs.web.api.http.handler;

import application.module.brs.SignumException;
import application.module.brs.services.ParameterService;
import application.module.brs.web.api.http.ApiServlet;
import application.module.brs.web.api.http.common.JSONData;
import application.module.brs.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.Parameters.GOODS_PARAMETER;

public final class GetDGSGood extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;

    public GetDGSGood(ParameterService parameterService) {
        super(new LegacyDocTag[] { LegacyDocTag.DGS }, GOODS_PARAMETER);
        this.parameterService = parameterService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        return JSONData.goods(parameterService.getGoods(req));
    }

}
