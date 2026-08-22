package it.common;

import static org.powermock.api.mockito.PowerMockito.mockStatic;

import application.module.node.Signum;
import application.module.node.instance.NodeFactory;
import application.module.node.common.TestInfrastructure;
import application.module.node.peer.Peers;
import application.module.node.peer.ProcessBlock;
import application.module.node.props.CaselessProperties;
import application.module.node.props.Props;
import com.google.gson.JsonObject;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

// TODO: Remove this and add javadoc and rename type
@SuppressWarnings({
        "checkstyle:MissingJavadocTypeCheck",
        "checkstyle:AbbreviationAsWordInNameCheck" })

@RunWith(PowerMockRunner.class)
@PrepareForTest(Peers.class)
@PowerMockIgnore("javax.net.ssl.*")
public abstract class AbstractIT {

    private ProcessBlock processBlock;

    protected APISender apiSender = new APISender();

    // TODO: Remove suppression and add javadoc
    @SuppressWarnings("checkstyle:MissingJavadocMethodCheck")
    @Before
    public void setUp() {
        mockStatic(Peers.class);
        Signum.init(testProperties());

        // Access the specific node by profile name (multi-node: no "active" concept)
        var ctx = NodeFactory.getInstance().get("node").getContext();
        processBlock = new ProcessBlock(ctx.getBlockchain(), ctx.getBlockchainProcessor());
    }

    @After
    public void shutdown() {
        Signum.shutdown(true);
    }

    private CaselessProperties testProperties() {
        final CaselessProperties props = new CaselessProperties();

        props.setProperty(Props.DEV_OFFLINE.getName(), "true");
        props.setProperty(Props.NETWORK_NAME.getName(), "Unit tests");
        props.setProperty(Props.DB_URL.getName(), TestInfrastructure.IN_MEMORY_DB_URL);
        props.setProperty(Props.DB_CONNECTIONS.getName(), "1");

        props.setProperty(Props.API_SERVER.getName(), "on");
        props.setProperty(Props.API_LISTEN.getName(), "127.0.0.1");
        props.setProperty(Props.API_PORT.getName(), "" + TestInfrastructure.TEST_API_PORT);
        props.setProperty(Props.API_ALLOWED.getName(), "*");
        props.setProperty(Props.API_UI_DIR.getName(), "html/ui");

        return props;
    }

    public void processBlock(JsonObject jsonFirstBlock) {
        processBlock.processRequest(jsonFirstBlock, null);
    }

    public void rollback(int height) {
        NodeFactory.getInstance().get("node").getContext().getBlockchainProcessor().popOffTo(0);
    }
}
