package application.module.node.peer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class GetPeers implements PeerServlet.PeerRequestHandler {

    private final Peers peers;

    GetPeers(Peers peers) {
        this.peers = peers;
    }

    @Override
    public JsonElement processRequest(JsonObject request, Peer peer) {

        JsonObject response = new JsonObject();

        JsonArray peersArray = new JsonArray();
        for (Peer otherPeer : peers.getAllPeers()) {

            if (!otherPeer.isBlacklisted() && otherPeer.getAnnouncedAddress() != null
                    && otherPeer.getState() == Peer.State.CONNECTED && otherPeer.shareAddress()) {

                peersArray.add(otherPeer.getAnnouncedAddress());

            }

        }
        response.add("peers", peersArray);

        return response;
    }

}
