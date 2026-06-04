package application.module.brs.web.api.http.handler;

import application.module.brs.peer.Peer;
import application.module.brs.peer.Peers;
import application.module.brs.web.api.http.ApiServlet;
import application.module.brs.web.api.http.common.JSONData;
import application.module.brs.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.JSONResponses.MISSING_PEER;
import static application.module.brs.web.api.http.common.JSONResponses.UNKNOWN_PEER;
import static application.module.brs.web.api.http.common.Parameters.PEER_PARAMETER;

public final class GetPeer extends ApiServlet.JsonRequestHandler {

    public static final GetPeer instance = new GetPeer();

    private GetPeer() {
        super(new LegacyDocTag[] { LegacyDocTag.INFO }, PEER_PARAMETER);
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {

        String peerAddress = req.getParameter(PEER_PARAMETER);
        if (peerAddress == null) {
            return MISSING_PEER;
        }

        Peer peer = Peers.getPeer(peerAddress);
        if (peer == null) {
            return UNKNOWN_PEER;
        }

        return JSONData.peer(peer);

    }

}
