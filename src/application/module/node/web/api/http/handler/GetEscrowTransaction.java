package application.module.node.web.api.http.handler;

import application.module.node.Escrow;
import application.module.node.services.EscrowService;
import application.module.node.util.Convert;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.ESCROW_PARAMETER;
import static application.module.node.web.api.http.common.ResultFields.ERROR_CODE_RESPONSE;
import static application.module.node.web.api.http.common.ResultFields.ERROR_DESCRIPTION_RESPONSE;

public final class GetEscrowTransaction extends ApiServlet.JsonRequestHandler {

    private final EscrowService escrowService;

    public GetEscrowTransaction(EscrowService escrowService) {
        super(new LegacyDocTag[] { LegacyDocTag.ACCOUNTS }, ESCROW_PARAMETER);
        this.escrowService = escrowService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {
        long escrowId;
        try {
            escrowId = Convert.parseUnsignedLong(Convert.emptyToNull(req.getParameter(ESCROW_PARAMETER)));
        } catch (Exception e) {
            JsonObject response = new JsonObject();
            response.addProperty(ERROR_CODE_RESPONSE, 3);
            response.addProperty(ERROR_DESCRIPTION_RESPONSE, "Invalid or not specified escrow");
            return response;
        }

        Escrow escrow = escrowService.getEscrowTransaction(escrowId);
        if (escrow == null) {
            JsonObject response = new JsonObject();
            response.addProperty(ERROR_CODE_RESPONSE, 5);
            response.addProperty(ERROR_DESCRIPTION_RESPONSE, "Escrow transaction not found");
            return response;
        }

        return JSONData.escrowTransaction(escrow);
    }
}
