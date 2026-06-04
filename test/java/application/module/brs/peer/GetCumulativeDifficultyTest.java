package application.module.brs.peer;

import application.module.brs.Block;
import application.module.brs.Blockchain;
import application.module.brs.common.QuickMocker;
import application.module.brs.util.JSON;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import java.math.BigInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GetCumulativeDifficultyTest {

    private GetCumulativeDifficulty t;

    private Blockchain mockBlockchain;

    @Before
    public void setUp() {
        mockBlockchain = mock(Blockchain.class);

        t = new GetCumulativeDifficulty(mockBlockchain);
    }

    @Test
    public void processRequest() {
        final BigInteger cumulativeDifficulty = BigInteger.TEN;
        final int blockchainHeight = 50;

        final JsonObject request = QuickMocker.jsonObject();

        final Block mockLastBlock = mock(Block.class);
        when(mockLastBlock.getHeight()).thenReturn(blockchainHeight);
        when(mockLastBlock.getCumulativeDifficulty()).thenReturn(cumulativeDifficulty);

        when(mockBlockchain.getLastBlock()).thenReturn(mockLastBlock);

        final JsonObject result = (JsonObject) t.processRequest(request, mock(Peer.class));
        assertNotNull(result);

        assertEquals(cumulativeDifficulty.toString(), JSON.getAsString(result.get("cumulativeDifficulty")));
        assertEquals(blockchainHeight, JSON.getAsInt(result.get("blockchainHeight")));
    }

}
