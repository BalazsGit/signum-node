package application.module.node.web.api.ws.emitter;

import application.module.node.web.api.ws.WebSocketConnection;
import application.module.node.web.api.ws.common.JSONWebSocketResponse;
import application.module.node.web.api.ws.common.WebsocketEventNames;

public class HeartBeatEventEmitter extends AbstractWebSocketEventEmitterImpl<Void> {

    public HeartBeatEventEmitter(WebSocketConnection connection) {
        super(connection);
    }

    @Override
    public void emit(Void noop) {
        // no op
    }

    @Override
    public void emit() {
        JSONWebSocketResponse<Void> response = new JSONWebSocketResponse<>(
                WebsocketEventNames.HEARTBEAT);
        this.getConnection().sendMessage(response.toString());
    }
}
