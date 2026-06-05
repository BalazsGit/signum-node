package application.module.node.web.api.http.handler;

import application.module.node.services.TimeService;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.ResultFields.TIME_RESPONSE;

public final class GetTime extends ApiServlet.JsonRequestHandler {

    private final TimeService timeService;

    public GetTime(TimeService timeService) {
        super(new LegacyDocTag[] { LegacyDocTag.INFO });
        this.timeService = timeService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {
        JsonObject response = new JsonObject();
        response.addProperty(TIME_RESPONSE, timeService.getEpochTime());

        return response;
    }

}
