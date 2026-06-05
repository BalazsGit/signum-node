package application.module.node.web.api.http.common;

import application.module.node.SignumException;
import com.google.gson.JsonElement;

public final class ParameterException extends SignumException {

    private transient final JsonElement errorResponse;

    public ParameterException(JsonElement errorResponse) {
        this.errorResponse = errorResponse;
    }

    public JsonElement getErrorResponse() {
        return errorResponse;
    }

}
