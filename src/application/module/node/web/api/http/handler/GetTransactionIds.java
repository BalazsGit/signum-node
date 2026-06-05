package application.module.node.web.api.http.handler;

import application.module.node.Blockchain;
import application.module.node.SignumException;
import application.module.node.services.ParameterService;
import application.module.node.util.Convert;

import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.*;

public final class GetTransactionIds extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;
    private final Blockchain blockchain;

    public GetTransactionIds(ParameterService parameterService, Blockchain blockchain) {
        super(new LegacyDocTag[] { LegacyDocTag.ACCOUNTS }, RECIPIENT_PARAMETER, SENDER_PARAMETER,
                TIMESTAMP_PARAMETER, TYPE_PARAMETER, SUBTYPE_PARAMETER, FIRST_INDEX_PARAMETER, LAST_INDEX_PARAMETER,
                NUMBER_OF_CONFIRMATIONS_PARAMETER, INCLUDE_INDIRECT_PARAMETER);
        this.parameterService = parameterService;
        this.blockchain = blockchain;
    }

    @Override
    public JsonElement processRequest(HttpServletRequest req) throws SignumException {

        Long senderId = null, recipientId = null;
        String senderParameter = Convert.emptyToNull(req.getParameter(SENDER_PARAMETER));
        if (senderParameter != null) {
            senderId = Convert.parseUnsignedLong(senderParameter);
        }
        String recipientParameter = Convert.emptyToNull(req.getParameter(RECIPIENT_PARAMETER));
        if (recipientParameter != null) {
            recipientId = Convert.parseUnsignedLong(recipientParameter);
        }

        int timestamp = ParameterParser.getTimestamp(req);
        int numberOfConfirmations = parameterService.getNumberOfConfirmations(req);

        byte type;
        byte subtype;
        try {
            type = Byte.parseByte(req.getParameter(TYPE_PARAMETER));
        } catch (NumberFormatException e) {
            type = -1;
        }
        try {
            subtype = Byte.parseByte(req.getParameter(SUBTYPE_PARAMETER));
        } catch (NumberFormatException e) {
            subtype = -1;
        }

        int firstIndex = ParameterParser.getFirstIndex(req);
        int lastIndex = ParameterParser.getLastIndex(req);

        JsonArray transactionIds = new JsonArray();
        for (Long transactionId : blockchain.getTransactionIds(senderId, recipientId, numberOfConfirmations,
                type, subtype, timestamp, firstIndex, lastIndex, parameterService.getIncludeIndirect(req))) {
            transactionIds.add(Convert.toUnsignedLong(transactionId));
        }

        JsonObject response = new JsonObject();
        response.add("transactionIds", transactionIds);
        return response;
    }

}
