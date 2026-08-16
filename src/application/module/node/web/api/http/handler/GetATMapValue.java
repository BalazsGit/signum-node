package application.module.node.web.api.http.handler;

import application.module.node.db.store.ATStore;
import application.module.node.util.Convert;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.JSONResponses.*;
import static application.module.node.web.api.http.common.Parameters.*;
import static application.module.node.web.api.http.common.ResultFields.VALUE_RESPONSE;

public final class GetATMapValue extends ApiServlet.JsonRequestHandler {

    private final ATStore atStore;

    public GetATMapValue(ATStore atStore) {
        super(new LegacyDocTag[] { LegacyDocTag.AT }, AT_PARAMETER, KEY1_PARAMETER, KEY2_PARAMETER);
        this.atStore = atStore;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {

        String at = req.getParameter(AT_PARAMETER);
        if (at == null) {
            return MISSING_AT;
        }

        String key1 = req.getParameter(KEY1_PARAMETER);
        if (key1 == null) {
            return MISSING_AT_KEY1;
        }

        String key2 = req.getParameter(KEY2_PARAMETER);
        if (key2 == null) {
            return MISSING_AT_KEY2;
        }

        long atId = Convert.parseUnsignedLong(at);
        long k1 = Convert.parseUnsignedLong(key1);
        long k2 = Convert.parseUnsignedLong(key2);

        String value = Convert.toUnsignedLong(atStore.getMapValue(atId, k1, k2));

        JsonObject response = new JsonObject();
        response.addProperty(VALUE_RESPONSE, value);
        return response;
    }

}
