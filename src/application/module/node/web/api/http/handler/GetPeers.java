package application.module.node.web.api.http.handler;

import application.module.node.peer.Peer;
import application.module.node.peer.PeerManager;
import application.module.node.util.Convert;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.Collections;

import static application.module.node.web.api.http.common.Parameters.ACTIVE_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.STATE_PARAMETER;

public final class GetPeers extends ApiServlet.JsonRequestHandler {

    private final PeerManager peerManager;

    public GetPeers(PeerManager peerManager) {
        super(new LegacyDocTag[] { LegacyDocTag.INFO }, ACTIVE_PARAMETER, STATE_PARAMETER);
        this.peerManager = peerManager;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {

        boolean active = "true".equalsIgnoreCase(req.getParameter(ACTIVE_PARAMETER));
        String stateValue = Convert.emptyToNull(req.getParameter(STATE_PARAMETER));

        Collection<Peer> peerCollection;
        if (peerManager == null) {
            peerCollection = Collections.emptyList();
        } else if (active) {
            peerCollection = peerManager.getActivePeers();
        } else if (stateValue != null) {
            peerCollection = peerManager.getPeers(Peer.State.valueOf(stateValue));
        } else {
            peerCollection = peerManager.getAllPeers();
        }

        JsonArray peers = new JsonArray();
        for (Peer peer : peerCollection) {
            peers.add(peer.getPeerAddress());
        }

        JsonObject response = new JsonObject();
        response.add("peers", peers);
        return response;
    }

}
