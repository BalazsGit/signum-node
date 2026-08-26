package it.common;

import static org.powermock.api.mockito.PowerMockito.mockStatic;

import application.module.node.NodeModule;
import application.module.node.Signum;
import application.module.node.peer.Peers;
import application.module.node.peer.ProcessBlock;
import com.google.gson.JsonObject;
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
        // v4 (P0.1): NodeModule is the sole lifecycle entry point — the legacy
        // Signum.init(CaselessProperties) static was removed (its properties
        // argument was never applied to the node anyway).
        Signum signum = NodeModule.getInstance().startNode(Signum.PROPERTIES_NAME);

        processBlock = new ProcessBlock(signum.getBlockchain(), signum.getBlockchainProcessor());
    }

    @After
    public void shutdown() {
        NodeModule.getInstance().stopAll();
    }

    public void processBlock(JsonObject jsonFirstBlock) {
        processBlock.processRequest(jsonFirstBlock, null);
    }

    public void rollback(int height) {
        NodeModule.getInstance().get("node").getBlockchainProcessor().popOffTo(0);
    }
}
