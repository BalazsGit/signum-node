package brs.web.api.ws;

import brs.web.server.WebServerContext;
import org.eclipse.jetty.ee10.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.ee10.websocket.server.JettyServerUpgradeResponse;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketCreator;

public class WebSocketConnectionCreator implements JettyWebSocketCreator {

  private final WebServerContext context;

  public WebSocketConnectionCreator(WebServerContext context) {
    this.context = context;
  }

  @Override
  public Object createWebSocket(JettyServerUpgradeRequest jettyServerUpgradeRequest, JettyServerUpgradeResponse jettyServerUpgradeResponse) {
    return new WebSocketConnectionAdapter(this.context);
  }
}
