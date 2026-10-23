
package application.module.node.web.api.ws;

import application.module.logging.context.NodeLoggers;
import application.module.node.web.server.WebServerContext;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.apache.logging.log4j.Logger;

public class WebSocketConnectionAdapter extends Session.Listener.Abstract {
    private final Logger logger;
    private final NodeLoggers loggers;
    private WebSocketConnection connection;
    private final BlockchainEventNotifier notifier;

    public WebSocketConnectionAdapter(WebServerContext context) {
        this.loggers = context.loggers();
        this.logger = loggers.forClass(WebSocketConnectionAdapter.class);
        this.notifier = BlockchainEventNotifier.getInstance(context);
    }

    @Override
    public void onWebSocketOpen(Session session) {
        super.onWebSocketOpen(session);
        logger.debug("Endpoint connected: {}", session);
        this.connection = new WebSocketConnection(session, loggers);
        this.notifier.addConnection(connection);
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason, Callback callback) {
        logger.debug("Socket Closed: [{}] {}", statusCode, reason);
        if (this.connection != null) {
            this.notifier.removeConnection(this.connection);
        }
        callback.succeed();
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        logger.error("Socket Error: {}", cause.getMessage(), cause);
        if (this.connection != null) {
            this.notifier.removeConnection(this.connection);
        }
    }

}
