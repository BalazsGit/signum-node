package application.module.brs.web.api.ws.emitter;

import application.module.brs.web.api.ws.WebSocketConnection;
import application.module.brs.web.api.ws.emitter.data.ConnectedEventData;
import application.module.brs.web.api.ws.common.JSONWebSocketResponse;
import application.module.brs.web.api.ws.common.WebsocketEventNames;

public class ConnectedEventEmitter extends AbstractWebSocketEventEmitterImpl<ConnectedEventData> {

    public ConnectedEventEmitter(WebSocketConnection connection) {
        super(connection);
    }

    @Override
    public void emit(ConnectedEventData data) {
        JSONWebSocketResponse<ConnectedPayload> response = new JSONWebSocketResponse<>(
                WebsocketEventNames.CONNECTED,
                new ConnectedPayload(data));
        this.getConnection().sendMessage(response.toString());
    }

    private static class ConnectedPayload {

        private final String version;
        private final String networkName;
        private final int globalHeight;
        private final int localHeight;
        private final boolean isConnected;
        private final boolean isSyncing;

        public ConnectedPayload(ConnectedEventData data) {
            this.version = data.version;
            this.globalHeight = data.globalHeight;
            this.localHeight = data.localHeight;
            this.networkName = data.networkName;
            this.isConnected = data.globalHeight > 0;
            this.isSyncing = data.globalHeight != data.localHeight;
        }
    }
}
