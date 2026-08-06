package application.module.node.web.api.http.handler;

import application.module.node.Block;
import application.module.node.Blockchain;
import application.module.node.Signum;
import application.module.node.Constants;
import application.module.node.common.QuickMocker;
import application.module.node.common.QuickMocker.MockParam;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.module.node.services.BlockService;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.JSONResponses.*;
import static application.module.node.web.api.http.common.Parameters.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

import java.math.BigInteger;

@ExtendWith(MockitoExtension.class)
class GetBlockTest {

    private GetBlock t;

    private Blockchain blockchainMock;
    private BlockService blockServiceMock;

    @Before
    public void setUp() {
        blockchainMock = mock(Blockchain.class);
        blockServiceMock = mock(BlockService.class);
    }

    @Test
    public void processRequest_withBlockId() {
        long blockId = 2L;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(BLOCK_PARAMETER, blockId));

        final Block mockBlock = mock(Block.class);
        when(mockBlock.getCumulativeDifficulty()).thenReturn(BigInteger.valueOf(123456789L));

        when(blockchainMock.getBlock(eq(blockId))).thenReturn(mockBlock);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            PropertyService propertyService = mock(PropertyService.class);
            mocked.when(Signum::getPropertyService).thenReturn(propertyService);
            doReturn((int) Constants.ONE_SIGNA).when(propertyService).getInt(eq(Props.ONE_COIN_NQT));

            t = new GetBlock(blockchainMock, blockServiceMock);

            final JsonObject result = (JsonObject) t.processRequest(req);

            assertNotNull(result);
        }
    }

    @Test
    public void processRequest_withBlockId_incorrectBlock() {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(BLOCK_PARAMETER, "notALong"));

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            PropertyService propertyService = mock(PropertyService.class);
            mocked.when(Signum::getPropertyService).thenReturn(propertyService);
            doReturn((int) Constants.ONE_SIGNA).when(propertyService).getInt(eq(Props.ONE_COIN_NQT));

            t = new GetBlock(blockchainMock, blockServiceMock);

            assertEquals(INCORRECT_BLOCK, t.processRequest(req));
        }
    }

    @Test
    public void processRequest_withHeight() {
        int blockHeight = 2;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(HEIGHT_PARAMETER, blockHeight));

        final Block mockBlock = mock(Block.class);
        when(mockBlock.getCumulativeDifficulty()).thenReturn(BigInteger.valueOf(123456789L));

        when(blockchainMock.getHeight()).thenReturn(100);
        when(blockchainMock.getBlockAtHeight(eq(blockHeight))).thenReturn(mockBlock);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            PropertyService propertyService = mock(PropertyService.class);
            mocked.when(Signum::getPropertyService).thenReturn(propertyService);
            doReturn((int) Constants.ONE_SIGNA).when(propertyService).getInt(eq(Props.ONE_COIN_NQT));

            t = new GetBlock(blockchainMock, blockServiceMock);

            final JsonObject result = (JsonObject) t.processRequest(req);

            assertNotNull(result);
        }
    }

    @Test
    public void processRequest_withHeight_incorrectHeight_unParsable() {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(HEIGHT_PARAMETER, "unParsable"));

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            PropertyService propertyService = mock(PropertyService.class);
            mocked.when(Signum::getPropertyService).thenReturn(propertyService);
            doReturn((int) Constants.ONE_SIGNA).when(propertyService).getInt(eq(Props.ONE_COIN_NQT));

            t = new GetBlock(blockchainMock, blockServiceMock);

            assertEquals(INCORRECT_HEIGHT, t.processRequest(req));
        }
    }

    @Test
    public void processRequest_withHeight_incorrectHeight_isNegative() {
        final long heightValue = -1L;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(HEIGHT_PARAMETER, heightValue));

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            PropertyService propertyService = mock(PropertyService.class);
            mocked.when(Signum::getPropertyService).thenReturn(propertyService);
            doReturn((int) Constants.ONE_SIGNA).when(propertyService).getInt(eq(Props.ONE_COIN_NQT));

            t = new GetBlock(blockchainMock, blockServiceMock);

            assertEquals(INCORRECT_HEIGHT, t.processRequest(req));
        }
    }

    @Test
    public void processRequest_withHeight_incorrectHeight_overCurrentBlockHeight() {
        final long heightValue = 10L;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(HEIGHT_PARAMETER, heightValue));

        when(blockchainMock.getHeight()).thenReturn(5);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            PropertyService propertyService = mock(PropertyService.class);
            mocked.when(Signum::getPropertyService).thenReturn(propertyService);
            doReturn((int) Constants.ONE_SIGNA).when(propertyService).getInt(eq(Props.ONE_COIN_NQT));

            t = new GetBlock(blockchainMock, blockServiceMock);

            assertEquals(INCORRECT_HEIGHT, t.processRequest(req));
        }
    }

    @Test
    public void processRequest_withTimestamp() {
        int timestamp = 2;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(TIMESTAMP_PARAMETER, timestamp));

        final Block mockBlock = mock(Block.class);
        when(mockBlock.getCumulativeDifficulty()).thenReturn(BigInteger.valueOf(123456789L));

        when(blockchainMock.getLastBlock(eq(timestamp))).thenReturn(mockBlock);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            PropertyService propertyService = mock(PropertyService.class);
            mocked.when(Signum::getPropertyService).thenReturn(propertyService);
            doReturn((int) Constants.ONE_SIGNA).when(propertyService).getInt(eq(Props.ONE_COIN_NQT));

            t = new GetBlock(blockchainMock, blockServiceMock);

            final JsonObject result = (JsonObject) t.processRequest(req);

            assertNotNull(result);
        }
    }

    @Test
    public void processRequest_withTimestamp_incorrectTimeStamp_unParsable() {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(TIMESTAMP_PARAMETER, "unParsable"));

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            PropertyService propertyService = mock(PropertyService.class);
            mocked.when(Signum::getPropertyService).thenReturn(propertyService);
            doReturn((int) Constants.ONE_SIGNA).when(propertyService).getInt(eq(Props.ONE_COIN_NQT));

            t = new GetBlock(blockchainMock, blockServiceMock);

            assertEquals(INCORRECT_TIMESTAMP, t.processRequest(req));
        }
    }

    @Test
    public void processRequest_withTimestamp_incorrectTimeStamp_negative() {
        final int timestamp = -1;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(TIMESTAMP_PARAMETER, timestamp));

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            PropertyService propertyService = mock(PropertyService.class);
            mocked.when(Signum::getPropertyService).thenReturn(propertyService);
            doReturn((int) Constants.ONE_SIGNA).when(propertyService).getInt(eq(Props.ONE_COIN_NQT));

            t = new GetBlock(blockchainMock, blockServiceMock);

            assertEquals(INCORRECT_TIMESTAMP, t.processRequest(req));
        }
    }

    @Test
    public void processRequest_unknownBlock() {
        final HttpServletRequest req = QuickMocker.httpServletRequest();

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            PropertyService propertyService = mock(PropertyService.class);
            mocked.when(Signum::getPropertyService).thenReturn(propertyService);
            doReturn((int) Constants.ONE_SIGNA).when(propertyService).getInt(eq(Props.ONE_COIN_NQT));

            t = new GetBlock(blockchainMock, blockServiceMock);

            assertEquals(UNKNOWN_BLOCK, t.processRequest(req));
        }
    }

}