package application.module.brs.peer;

import application.module.brs.SignumException;
import application.module.brs.TransactionProcessor;
import application.module.brs.util.JSON;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class ProcessTransactions implements PeerServlet.PeerRequestHandler {

    private final TransactionProcessor transactionProcessor;

    ProcessTransactions(TransactionProcessor transactionProcessor) {
        this.transactionProcessor = transactionProcessor;
    }

    @Override
    public JsonElement processRequest(JsonObject request, Peer peer) {

        try {
            transactionProcessor.processPeerTransactions(request, peer);
            return JSON.emptyJSON;
        } catch (RuntimeException | SignumException.ValidationException e) {
            peer.blacklist(e, "received invalid data via requestType=processTransactions");
            JsonObject response = new JsonObject();
            response.addProperty("error", e.toString());
            return response;
        }
    }
}
