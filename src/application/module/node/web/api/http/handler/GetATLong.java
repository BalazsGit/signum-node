package application.module.node.web.api.http.handler;

import static application.module.node.web.api.http.common.Parameters.HEX_STRING_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.ID_PARAMETER;

import jakarta.servlet.http.HttpServletRequest;

import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import application.module.node.util.Convert;
import signumj.crypto.SignumCrypto;

public final class GetATLong extends ApiServlet.JsonRequestHandler {

    public static final GetATLong instance = new GetATLong();

    private GetATLong() {
        super(new LegacyDocTag[] { LegacyDocTag.AT }, HEX_STRING_PARAMETER, ID_PARAMETER);
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {
        String id = Convert.emptyToNull(req.getParameter(ID_PARAMETER));
        if (id != null) {
            JsonObject json = new JsonObject();
            json.addProperty("long2hex",
                    Convert.toHexString(SignumCrypto.getInstance().longToBytesLE(Convert.parseUnsignedLong(id))));
            return json;
        }

        return JSONData.hex2long(ParameterParser.getATLong(req));
    }

}
