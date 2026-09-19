package application.module.node.at;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ATTest {
    @BeforeEach
    public void setUp() {
        AtTestHelper.setupMocks();
    }

    @AfterEach
    public void tearDown() {
        AtTestHelper.closeStatics();
    }

    @Test
    public void testAddAt() {
        AtTestHelper.clearAddedAts();
        AtomicBoolean helloWorldReceived = new AtomicBoolean(false);
        AtTestHelper.setOnAtAdded(at -> {
            assertEquals("HelloWorld", at.getName());
            helloWorldReceived.set(true);
        });
        AtTestHelper.addHelloWorldAT();
        assertTrue(helloWorldReceived.get());

        AtomicBoolean echoReceived = new AtomicBoolean(false);
        AtTestHelper.setOnAtAdded(at -> {
            assertEquals("Echo", at.getName());
            echoReceived.set(true);
        });
        AtTestHelper.addEchoAT();
        assertTrue(echoReceived.get());

        AtomicBoolean tipThanksReceived = new AtomicBoolean(false);
        AtTestHelper.setOnAtAdded(at -> {
            assertEquals("TipThanks", at.getName());
            tipThanksReceived.set(true);
        });
        AtTestHelper.addTipThanksAT();
        assertTrue(tipThanksReceived.get());
        assertEquals(3, AT.getOrderedATs(AtTestHelper.getTestContext()).size());
    }
}