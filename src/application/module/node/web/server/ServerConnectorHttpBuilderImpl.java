package application.module.node.web.server;

import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ServerConnectorHttpBuilderImpl extends AbstractServerConnectorBuilder {

    private final Logger logger;

    public ServerConnectorHttpBuilderImpl(WebServerContext context) {
        super(context);
        this.logger = context.loggers().forClass(ServerConnectorHttpBuilderImpl.class);
    }

    @Override
    public ServerConnector build(Server server) {
        PropertyService propertyService = context.getPropertyService();
        ServerConnector connector = propertyService.getBoolean(Props.API_SSL) ? this.createSSLConnector(server)
                : new ServerConnector(server);
        connector.setHost(propertyService.getString(Props.API_LISTEN));
        connector.setPort(propertyService.getInt(Props.API_PORT));
        connector.setIdleTimeout(context.getPropertyService().getInt(Props.API_SERVER_IDLE_TIMEOUT));
        // defaultProtocol
        // stopTimeout
        // acceptQueueSize
        connector.setReuseAddress(true);
        logger.info("HTTP API server enabled for {}:{}", connector.getHost(), connector.getPort());
        return connector;
    }

}
