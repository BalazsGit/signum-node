package application.module.node.web.api.http.handler;

import application.module.node.peer.Peer;
import application.module.node.peer.PeerManager;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.JSONResponses.MISSING_PEER;
import static application.module.node.web.api.http.common.JSONResponses.UNKNOWN_PEER;
import static application.module.node.web.api.http.common.Parameters.PEER_PARAMETER;

public final class GetPeer extends ApiServlet.JsonRequestHandler {

    private final PeerManager peerManager;

    public GetPeer(PeerManager peerManager) {
        super(new LegacyDocTag[] { LegacyDocTag.INFO }, PEER_PARAMETER);
        this.peerManager = peerManager;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {

        String peerAddress = req.getParameter(PEER_PARAMETER);
        if (peerAddress == null) {
            return MISSING_PEER;
        }

        Peer peer = peerManager != null ? peerManager.getPeer(peerAddress) : null;
        if (peer == null) {
            return UNKNOWN_PEER;
        }

        return JSONData.peer(peer);

    }

}
