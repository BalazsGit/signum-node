package application.module.node.at;

import application.module.node.util.Convert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class AtControllerTest {
    @Before
    public void setUp() {
        AtTestHelper.setupMocks();
    }

    @After
    public void tearDown() {
        AtTestHelper.closeStatics();
    }

    @Test
    public void testCheckCreationBytes() throws AtException {
        AtTestHelper.clearAddedAts();
        assertEquals(4, AtController.checkCreationBytes(AtTestHelper.HELLO_WORLD_CREATION_BYTES, Integer.MAX_VALUE, 1));
        assertEquals(4, AtController.checkCreationBytes(AtTestHelper.ECHO_CREATION_BYTES, Integer.MAX_VALUE, 1));
        assertEquals(5, AtController.checkCreationBytes(AtTestHelper.TIP_THANKS_CREATION_BYTES, Integer.MAX_VALUE, 1));
    }

    @Test
    public void testRunSteps() {
        AtTestHelper.clearAddedAts();
        AtTestHelper.addHelloWorldAT();
        AtTestHelper.addEchoAT();
        AtTestHelper.addTipThanksAT();
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        assertEquals(3, AT.getOrderedATs(ctx).size());
        AtBlock atBlock = AtController.getCurrentBlockATs(ctx, Integer.MAX_VALUE, Integer.MAX_VALUE, 0L, 0);
        assertNotNull(atBlock);
        assertNotNull(atBlock.getBytesForBlock());
        assertEquals(
                "010000000000000097c1d1e5b25c1d109f2ba522d1dda248020000000000000014ea12712c274caebc49ccd7fff0b0b703000000000000009f1af5443c8d1e7b492f848e91fccb1f",
                Convert.toHexString(atBlock.getBytesForBlock()));
    }

    @Test
    public void testValidateAts() throws AtException {
        AtTestHelper.clearAddedAts();
        AtTestHelper.addHelloWorldAT();
        AtTestHelper.addEchoAT();
        AtTestHelper.addTipThanksAT();
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        assertEquals(3, AT.getOrderedATs(ctx).size());
        AtBlock atBlock = AtController.validateATs(ctx, Convert.parseHexString(
                "010000000000000097c1d1e5b25c1d109f2ba522d1dda248020000000000000014ea12712c274caebc49ccd7fff0b0b703000000000000009f1af5443c8d1e7b492f848e91fccb1f"),
                Integer.MAX_VALUE, 0L);
        assertNotNull(atBlock);
        assertEquals(0, atBlock.getTotalAmount());
        assertEquals(5439000, atBlock.getTotalFees());
    }

    @Test
    public void testValidateAtsV3() throws AtException {
        AtTestHelper.clearAddedAts();
        AtTestHelper.addHelloWorldATV3();
        AtTestHelper.addEchoATV3();
        AtTestHelper.addTipThanksATV3();
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        assertEquals(3, AT.getOrderedATs(ctx).size());
        AtBlock atBlock = AtController.validateATs(ctx, Convert.parseHexString(
                "010000000000000097c1d1e5b25c1d109f2ba522d1dda248020000000000000014ea12712c274caebc49ccd7fff0b0b703000000000000009f1af5443c8d1e7b492f848e91fccb1f"),
                Integer.MAX_VALUE, 0L);
        assertNotNull(atBlock);
        assertEquals(0, atBlock.getTotalAmount());
        assertEquals(7400000, atBlock.getTotalFees());
    }
}