package application.module.node.web.api.http.handler;

import application.module.node.services.ATService;
import application.module.node.util.Convert;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.ResultFields.AT_IDS_RESPONSE;
import static application.module.node.web.api.http.common.JSONResponses.ERROR_INCORRECT_REQUEST;
import static application.module.node.web.api.http.common.Parameters.MACHINE_CODE_HASH_ID_PARAMETER;

public final class GetATIds extends ApiServlet.JsonRequestHandler {

    private final ATService atService;

    public GetATIds(ATService atService) {
        super(new LegacyDocTag[] { LegacyDocTag.AT }, MACHINE_CODE_HASH_ID_PARAMETER);
        this.atService = atService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {

        Long codeHashId = null;
        String codeHashIdString = Convert.emptyToNull(req.getParameter(MACHINE_CODE_HASH_ID_PARAMETER));
        if (codeHashIdString != null) {
            try {
                codeHashId = Convert.parseUnsignedLong(codeHashIdString);
            } catch (RuntimeException e) {
                return ERROR_INCORRECT_REQUEST;
            }
        }

        JsonArray atIds = new JsonArray();
        for (Long id : atService.getAllATIds(codeHashId)) {
            atIds.add(Convert.toUnsignedLong(id));
        }

        JsonObject response = new JsonObject();
        response.add(AT_IDS_RESPONSE, atIds);
        return response;
    }

}
