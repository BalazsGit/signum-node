package application.module.brs.common;

import application.module.brs.util.JSON;
import com.google.gson.JsonElement;

import static application.module.brs.web.api.http.common.ResultFields.ERROR_CODE_RESPONSE;

;

public class JSONTestHelper {

    public static int errorCode(JsonElement json) {
        return JSON.getAsInt(JSON.getAsJsonObject(json).get(ERROR_CODE_RESPONSE));
    }
}
