package application.module.node.web.api.http.handler;

import application.module.node.TransactionProcessor;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

public final class GetMyPeerInfo extends ApiServlet.JsonRequestHandler {

    private final TransactionProcessor transactionProcessor;

    public GetMyPeerInfo(TransactionProcessor transactionProcessor) {
        super(new LegacyDocTag[] { LegacyDocTag.PEER_INFO });
        this.transactionProcessor = transactionProcessor;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {

        JsonObject response = new JsonObject();
        response.addProperty("utsInStore", transactionProcessor.getAmountUnconfirmedTransactions());
        return response;
    }

}
