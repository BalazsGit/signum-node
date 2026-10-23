package application.module.node.web.server;

import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.module.node.web.api.ws.WebSocketConnectionCreator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.apache.logging.log4j.Logger;

import java.time.Duration;

public class ServerConnectorWebsocketBuilderImpl extends AbstractServerConnectorBuilder {

    private final Logger logger;
    private final ServletContextHandler servletContextHandler;

    public ServerConnectorWebsocketBuilderImpl(WebServerContext context, ServletContextHandler servletContextHandler) {
        super(context);
        this.servletContextHandler = servletContextHandler;
        this.logger = context.loggers().forClass(ServerConnectorWebsocketBuilderImpl.class);
    }

    @Override
    public ServerConnector build(Server server) {
        PropertyService propertyService = context.getPropertyService();
        ServerConnector connector = propertyService.getBoolean(Props.API_SSL) ? this.createSSLConnector(server)
                : new ServerConnector(server);
        connector.setHost(propertyService.getString(Props.API_LISTEN));
        connector.setPort(propertyService.getInt(Props.API_WEBSOCKET_PORT));
        connector.setReuseAddress(true);
        JettyWebSocketServletContainerInitializer.configure(servletContextHandler, (servletContext, wsContainer) -> {
            wsContainer.setMaxTextMessageSize(2 * 1024);
            wsContainer.setIdleTimeout(Duration.ofSeconds(propertyService.getInt(Props.BLOCK_TIME)));
            wsContainer.addMapping("/events", new WebSocketConnectionCreator(context));
        });
        logger.info("[Experimental] WebSockets server enabled for {}:{}", connector.getHost(), connector.getPort());
        return connector;
    }

}
