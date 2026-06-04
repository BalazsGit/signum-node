package application.module.brs.web.api.http.handler;

import application.module.brs.peer.Peer;
import application.module.brs.peer.Peers;
import application.module.brs.util.Convert;
import application.module.brs.web.api.http.ApiServlet;
import application.module.brs.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.Parameters.ACTIVE_PARAMETER;
import static application.module.brs.web.api.http.common.Parameters.STATE_PARAMETER;

public final class GetPeers extends ApiServlet.JsonRequestHandler {

    public static final GetPeers instance = new GetPeers();

    private GetPeers() {
        super(new LegacyDocTag[] { LegacyDocTag.INFO }, ACTIVE_PARAMETER, STATE_PARAMETER);
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {

        boolean active = "true".equalsIgnoreCase(req.getParameter(ACTIVE_PARAMETER));
        String stateValue = Convert.emptyToNull(req.getParameter(STATE_PARAMETER));

        JsonArray peers = new JsonArray();
        for (Peer peer : active ? Peers.getActivePeers()
                : stateValue != null ? Peers.getPeers(Peer.State.valueOf(stateValue)) : Peers.getAllPeers()) {
            peers.add(peer.getPeerAddress());
        }

        JsonObject response = new JsonObject();
        response.add("peers", peers);
        return response;
    }

}
