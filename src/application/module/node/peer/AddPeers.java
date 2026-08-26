package application.module.node.peer;

import application.module.node.util.JSON;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class AddPeers implements PeerServlet.PeerRequestHandler {

    private final Peers peers;

    AddPeers(Peers peers) {
        this.peers = peers;
    }

    @Override
    public JsonElement processRequest(JsonObject request, Peer peer) {
        JsonArray peersJson = JSON.getAsJsonArray(request.get("peers"));
        if (peersJson != null && peers.getMorePeers) {
            for (JsonElement announcedAddress : peersJson) {
                peers.addPeer(JSON.getAsString(announcedAddress));
            }
        }
        return JSON.emptyJSON;
    }

}
