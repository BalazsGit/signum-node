package brs.peer;

import brs.Blockchain;
import brs.services.TimeService;
import brs.util.JSON;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class GetInfo implements PeerServlet.PeerRequestHandler {

    private final TimeService timeService;
    private final Blockchain blockchain;

    GetInfo(TimeService timeService, Blockchain blockchain) {
        this.timeService = timeService;
        this.blockchain = blockchain;
    }

    @Override
    public JsonElement processRequest(JsonObject request, Peer peer) {
        PeerImpl peerImpl = (PeerImpl) peer;
        String announcedAddress = JSON.getAsString(request.get("announcedAddress"));
        if (announcedAddress != null) {
            announcedAddress = announcedAddress.trim();
            if (!announcedAddress.isEmpty()) {
                if (peerImpl.getAnnouncedAddress() != null
                        && !announcedAddress.equals(peerImpl.getAnnouncedAddress())) {
                    // force verification of changed announced address
                    peerImpl.setState(Peer.State.NON_CONNECTED);
                }
                peerImpl.setAnnouncedAddress(announcedAddress);
            }
        }
        String application = JSON.getAsString(request.get("application"));
        if (application == null) {
            application = "?";
        }
        peerImpl.setApplication(application.trim());

        String version = JSON.getAsString(request.get("version"));
        if (version == null) {
            version = "?";
        }
        peerImpl.setVersion(version.trim());

        String platform = JSON.getAsString(request.get("platform"));
        if (platform == null) {
            platform = "?";
        }
        peerImpl.setPlatform(platform.trim());

        String networkName = JSON.getAsString(request.get("networkName"));
        peerImpl.setNetworkName(networkName);

        peerImpl.setShareAddress(Boolean.TRUE.equals(
                JSON.getAsBoolean(request.get("shareAddress"))));
        peerImpl.setLastUpdated(timeService.getEpochTime());

        Peers.notifyListeners(peerImpl, Peers.Event.ADDED_ACTIVE_PEER);

        // Clone the static response and add dynamic height
        JsonObject response = JSON.cloneJson(Peers.myPeerInfoResponse).getAsJsonObject();
        response.addProperty("height", blockchain.getHeight());
        return response;
    }
}
