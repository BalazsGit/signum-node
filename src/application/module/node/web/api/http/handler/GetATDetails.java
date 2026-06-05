package application.module.node.web.api.http.handler;

import application.module.node.SignumException;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.AT_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.HEIGHT_PARAMETER;

public class GetATDetails extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;

    public GetATDetails(ParameterService parameterService) {
        super(new LegacyDocTag[] { LegacyDocTag.AT }, AT_PARAMETER, HEIGHT_PARAMETER);
        this.parameterService = parameterService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        return JSONData.at(parameterService.getAT(req));
    }
}
