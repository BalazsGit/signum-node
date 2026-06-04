package application.module.brs.web.api.http.handler;

import application.module.brs.Account;
import application.module.brs.Block;
import application.module.brs.Blockchain;
import application.module.brs.SignumException;
import application.module.brs.common.AbstractUnitTest;
import application.module.brs.common.QuickMocker;
import application.module.brs.common.QuickMocker.MockParam;
import application.module.brs.services.ParameterService;
import application.module.brs.util.CollectionWithIndex;
import application.module.brs.util.JSON;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;

import static application.module.brs.web.api.http.common.Parameters.*;
import static application.module.brs.web.api.http.common.ResultFields.BLOCK_IDS_RESPONSE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

;

public class GetAccountBlockIdsTest extends AbstractUnitTest {

    private GetAccountBlockIds t;

    private ParameterService mockParameterService;
    private Blockchain mockBlockchain;

    @Before
    public void setUp() {
        mockParameterService = mock(ParameterService.class);
        mockBlockchain = mock(Blockchain.class);

        t = new GetAccountBlockIds(mockParameterService, mockBlockchain);
    }

    @Test
    public void processRequest() throws SignumException {
        final int timestamp = 1;
        final int firstIndex = 0;
        final int lastIndex = 1;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(TIMESTAMP_PARAMETER, timestamp),
                new MockParam(FIRST_INDEX_PARAMETER, firstIndex),
                new MockParam(LAST_INDEX_PARAMETER, lastIndex));

        final Account mockAccount = mock(Account.class);

        String mockBlockStringId = "mockBlockStringId";
        final Block mockBlock = mock(Block.class);
        when(mockBlock.getStringId()).thenReturn(mockBlockStringId);
        final Collection<Block> mockBlocksIterator = mockCollection(mockBlock);

        when(mockParameterService.getAccount(req)).thenReturn(mockAccount);
        when(mockBlockchain.getBlocks(eq(mockAccount), eq(timestamp), eq(firstIndex), eq(lastIndex)))
                .thenReturn(new CollectionWithIndex<Block>(mockBlocksIterator, -1));

        final JsonObject result = (JsonObject) t.processRequest(req);
        assertNotNull(result);

        final JsonArray blockIds = (JsonArray) result.get(BLOCK_IDS_RESPONSE);
        assertNotNull(blockIds);
        assertEquals(1, blockIds.size());
        assertEquals(mockBlockStringId, JSON.getAsString(blockIds.get(0)));
    }
}
