package application.module.node.web.api.ws;

import application.module.logging.context.NodeLoggers;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.apache.logging.log4j.Logger;

import java.net.SocketAddress;

public class WebSocketConnection {
    private final Logger logger;

    private final Session session;

    public WebSocketConnection(Session session, NodeLoggers loggers) {
        this.session = session;
        this.logger = loggers.forClass(WebSocketConnection.class);
    }

    public String getId() {
        SocketAddress remoteAddress = session.getRemoteSocketAddress();
        return remoteAddress != null ? remoteAddress.toString() : "unknown";
    }

    public Session getSession() {
        return session;
    }

    public void sendMessage(String message) {
        if (!session.isOpen()) {
            logger.debug("Skipping message send to {} because session is closed", getId());
            return;
        }

        SocketAddress remoteAddress = session.getRemoteSocketAddress();
        session.sendText(message, Callback.from(
                () -> logger.trace("Sent message to {}", remoteAddress),
                throwable -> logger.warn("Error sending message to {}: {}", remoteAddress, throwable.getMessage(),
                        throwable)));
    }

    public void close() {
        session.close();
    }
}
