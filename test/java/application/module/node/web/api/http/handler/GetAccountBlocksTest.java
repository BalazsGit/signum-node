package application.module.node.web.api.http.handler;

import application.module.node.Account;
import application.module.node.Block;
import application.module.node.Blockchain;
import application.module.node.Signum;
import application.module.node.SignumException;
import application.module.node.Constants;
import application.module.node.common.AbstractUnitTest;
import application.module.node.common.QuickMocker;
import application.module.node.common.QuickMocker.MockParam;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.module.node.services.BlockService;
import application.module.node.services.ParameterService;
import application.module.node.util.CollectionWithIndex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;

import java.math.BigInteger;
import java.util.Collection;

import static application.module.node.web.api.http.common.Parameters.*;
import static application.module.node.web.api.http.common.ResultFields.BLOCKS_RESPONSE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class GetAccountBlocksTest extends AbstractUnitTest {

    private GetAccountBlocks t;

    private Blockchain blockchainMock;
    private ParameterService parameterServiceMock;
    private BlockService blockServiceMock;

    @Before
    public void setUp() {
        blockchainMock = mock(Blockchain.class);
        parameterServiceMock = mock(ParameterService.class);
        blockServiceMock = mock(BlockService.class);
    }

    @Test
    public void processRequest() throws SignumException {
        final int mockTimestamp = 1;
        final int mockFirstIndex = 2;
        final int mockLastIndex = 3;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(FIRST_INDEX_PARAMETER, "" + mockFirstIndex),
                new MockParam(LAST_INDEX_PARAMETER, "" + mockLastIndex),
                new MockParam(TIMESTAMP_PARAMETER, "" + mockTimestamp));

        final Account mockAccount = mock(Account.class);
        final Block mockBlock = mock(Block.class);
        when(mockBlock.getCumulativeDifficulty()).thenReturn(BigInteger.valueOf(123456789L));

        when(parameterServiceMock.getAccount(req)).thenReturn(mockAccount);

        final Collection<Block> mockBlockIterator = mockCollection(mockBlock);
        when(blockchainMock.getBlocks(eq(mockAccount), eq(mockTimestamp), eq(mockFirstIndex), eq(mockLastIndex)))
                .thenReturn(new CollectionWithIndex<Block>(
                        mockBlockIterator, -1));

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            PropertyService propertyService = mock(PropertyService.class);
            mocked.when(Signum::getPropertyService).thenReturn(propertyService);
            doReturn((int) Constants.ONE_SIGNA).when(propertyService).getInt(eq(Props.ONE_COIN_NQT));

            t = new GetAccountBlocks(blockchainMock, parameterServiceMock, blockServiceMock);

            final JsonObject result = (JsonObject) t.processRequest(req);

            final JsonArray blocks = (JsonArray) result.get(BLOCKS_RESPONSE);
            assertNotNull(blocks);
            assertEquals(1, blocks.size());

            final JsonObject resultBlock = (JsonObject) blocks.get(0);
            assertNotNull(resultBlock);

            // TODO validate all fields
        }
    }
}