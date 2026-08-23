package application.utils.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;

/**
 * Unit tests for {@link ProfileLogger}.
 */
@DisplayName("ProfileLogger Tests")
class ProfileLoggerTest {

    @AfterEach
    void tearDown() {
        SystemLogger.resetInstance();
    }

    // â”€â”€ Constructor & Identity â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        void constructor_ValidArgs_setsFields() {
            ProfileLogger logger = new ProfileLogger("node", "mainnet");

            assertEquals("node", logger.getModuleId());
            assertEquals("mainnet", logger.getProfileName());
            assertEquals("node.mainnet", logger.getName());
        }

        @Test
        void constructor_NullModuleId_throwsNPE() {
            assertThrows(NullPointerException.class, () -> new ProfileLogger(null, "test"));
        }

        @Test
        void constructor_EmptyModuleId_throwsIAE() {
            assertThrows(IllegalArgumentException.class, () -> new ProfileLogger("", "test"));
        }

        @Test
        void constructor_NullProfileName_throwsNPE() {
            assertThrows(NullPointerException.class, () -> new ProfileLogger("node", null));
        }

        @Test
        void constructor_EmptyProfileName_throwsIAE() {
            assertThrows(IllegalArgumentException.class, () -> new ProfileLogger("node", ""));
        }

        @Test
        void defaultForwardToSystem_isTrue() {
            ProfileLogger logger = new ProfileLogger("node", "test");
            assertTrue(logger.isForwardToSystem());
        }
    }

    // â”€â”€ Logging & Level Filtering â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Nested
    @DisplayName("Logging")
    class LoggingTests {

        @Test
        void info_DispatchesToSubscriber() {
            ProfileLogger logger = new ProfileLogger("node", "mainnet");
            AtomicReference<LogEvent> received = new AtomicReference<>();
            logger.addSubscriber(new TestSubscriber(event -> received.set(event)));

            logger.info("hello");

            assertNotNull(received.get());
            assertEquals(LogLevel.INFO, received.get().getLevel());
            assertEquals("hello", received.get().getMessage());
        }

        @Test
        void debug_BelowMinLevel_notDispatched() {
            ProfileLogger logger = new ProfileLogger("node", "mainnet");
            // Default minLevel is INFO
            AtomicInteger count = new AtomicInteger(0);
            logger.addSubscriber(new TestSubscriber(event -> count.incrementAndGet()));

            logger.debug("debug msg");

            assertEquals(0, count.get(), "DEBUG below INFO should not dispatch");
        }

        @Test
        void setLogLevel_TRACE_allowsDebug() {
            ProfileLogger logger = new ProfileLogger("node", "mainnet");
            logger.setLogLevel(LogLevel.TRACE);

            AtomicInteger count = new AtomicInteger(0);
            logger.addSubscriber(new TestSubscriber(event -> count.incrementAndGet()));

            logger.debug("debug msg");

            assertEquals(1, count.get());
        }

        @Test
        void error_WithThrowable_includesCause() {
            ProfileLogger logger = new ProfileLogger("node", "mainnet");
            AtomicReference<LogEvent> received = new AtomicReference<>();
            logger.addSubscriber(new TestSubscriber(event -> received.set(event)));

            RuntimeException cause = new RuntimeException("db error");
            logger.error("fail", cause);

            assertEquals(cause, received.get().getThrowable());
        }
    }

    // â”€â”€ Forwarding to SystemLogger â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Nested
    @DisplayName("Forwarding")
    class ForwardingTests {

        @Test
        void forwardToSystem_DefaultForwardsEvent() {
            // Set up SystemLogger subscriber first
            AtomicReference<LogEvent> systemReceived = new AtomicReference<>();
            SystemLogger.getInstance().addSubscriber(new TestSubscriber(event -> systemReceived.set(event)));

            ProfileLogger logger = new ProfileLogger("node", "mainnet");
            logger.info("forwarded");

            assertNotNull(systemReceived.get());
            assertEquals("forwarded", systemReceived.get().getMessage());
        }

        @Test
        void setForwardToSystem_false_blocksForwarding() {
            AtomicReference<LogEvent> systemReceived = new AtomicReference<>();
            SystemLogger.getInstance().addSubscriber(new TestSubscriber(event -> systemReceived.set(event)));

            ProfileLogger logger = new ProfileLogger("node", "mainnet");
            logger.setForwardToSystem(false);

            logger.info("no forward");

            assertFalse(systemReceived.get() != null && "no forward".equals(systemReceived.get().getMessage()),
                "Event should not reach SystemLogger when forwarding is disabled");
        }

        @Test
        void profileSubscriber_ReceivesBeforeSystemForward() {
            AtomicInteger order = new AtomicInteger(0);
            AtomicReference<Integer> profileOrder = new AtomicReference<>();
            AtomicReference<Integer> systemOrder = new AtomicReference<>();

            SystemLogger.getInstance().addSubscriber(new TestSubscriber(e -> systemOrder.set(order.getAndIncrement())));

            ProfileLogger logger = new ProfileLogger("node", "mainnet");
            logger.addSubscriber(new TestSubscriber(e -> profileOrder.set(order.getAndIncrement())));

            logger.info("test");

            // Profile subscriber should be called first (dispatch overrides calls super.dispatch first)
            assertNotNull(profileOrder.get());
            assertTrue(profileOrder.get() < systemOrder.get(),
                "Profile subscriber should receive event before SystemLogger forwarding");
        }
    }

    // â”€â”€ Lifecycle â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Nested
    @DisplayName("Lifecycle")
    class LifecycleTests {

        @Test
        void closedLogger_doesNotDispatch() {
            ProfileLogger logger = new ProfileLogger("node", "mainnet");
            AtomicInteger count = new AtomicInteger(0);
            logger.addSubscriber(new TestSubscriber(event -> count.incrementAndGet()));

            logger.close();
            logger.info("after close");

            assertEquals(0, count.get());
        }

        @Test
        void isClosed_AfterClose_returnsTrue() {
            ProfileLogger logger = new ProfileLogger("node", "mainnet");
            assertFalse(logger.isClosed());

            logger.close();
            assertTrue(logger.isClosed());
        }
    }

    // â”€â”€ toString â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void toString_ContainsProfileInfo() {
        ProfileLogger logger = new ProfileLogger("node", "mainnet");
        String s = logger.toString();
        assertTrue(s.contains("ProfileLogger"));
        assertTrue(s.contains("node"));
        assertTrue(s.contains("mainnet"));
    }

    // â”€â”€ Replay (late-attaching subscribers) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Nested
    @DisplayName("Replay")
    class ReplayTests {

        @Test
        void lateSubscriber_receivesPreviousEvents_inOrder() {
            ProfileLogger logger = new ProfileLogger("node", "mainnet");
            for (int i = 1; i <= 5; i++) {
                logger.info("line-" + i);
            }

            java.util.List<String> received = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            logger.addSubscriber(new TestSubscriber(e -> received.add(e.getMessage())));

            assertEquals(java.util.Arrays.asList("line-1", "line-2", "line-3", "line-4", "line-5"), received);
        }

        @Test
        void replay_isBoundedByCapacity_keepsMostRecent() {
            ProfileLogger logger = new ProfileLogger("node", "mainnet", 3);
            for (int i = 1; i <= 10; i++) {
                logger.info("line-" + i);
            }
            assertEquals(3, logger.getReplayBufferSize());

            java.util.List<String> received = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            logger.addSubscriber(new TestSubscriber(e -> received.add(e.getMessage())));

            assertEquals(java.util.Arrays.asList("line-8", "line-9", "line-10"), received);
        }

        @Test
        void lateSubscriber_noDuplicatesOrGaps_whenLoggingAfterAttach() {
            ProfileLogger logger = new ProfileLogger("node", "mainnet", 100);
            for (int i = 1; i <= 5; i++) {
                logger.info("line-" + i);
            }
            java.util.List<String> received = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            logger.addSubscriber(new TestSubscriber(e -> received.add(e.getMessage())));
            for (int i = 6; i <= 8; i++) {
                logger.info("line-" + i);
            }

            assertEquals(java.util.Arrays.asList("line-1", "line-2", "line-3", "line-4", "line-5",
                    "line-6", "line-7", "line-8"), received);
        }

        @Test
        void addSubscriber_sameInstanceTwice_deliversOnce() {
            ProfileLogger logger = new ProfileLogger("node", "mainnet");
            logger.info("line-1");
            java.util.List<String> received = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            TestSubscriber subscriber = new TestSubscriber(e -> received.add(e.getMessage()));

            logger.addSubscriber(subscriber); // replay: line-1
            logger.addSubscriber(subscriber); // idempotent no-op
            logger.info("line-2");

            assertEquals(java.util.Arrays.asList("line-1", "line-2"), received);
        }

        @Test
        void closedLogger_addSubscriber_receivesNothing() {
            ProfileLogger logger = new ProfileLogger("node", "mainnet");
            logger.info("line-1");
            logger.close();

            java.util.List<String> received = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            logger.addSubscriber(new TestSubscriber(e -> received.add(e.getMessage())));

            assertTrue(received.isEmpty());
        }

        @Test
        void replayCapacity_invalid_throwsIAE() {
            assertThrows(IllegalArgumentException.class, () -> new ProfileLogger("node", "mainnet", 0));
        }

        @Test
        void defaultReplayCapacity_isAlignedWithConsoleMaxLines() {
            assertEquals(500, ProfileLogger.DEFAULT_REPLAY_CAPACITY);
        }
    }

    // â”€â”€ Test Helper â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private static class TestSubscriber implements LogSubscriber {
        private final Consumer<LogEvent> onEvent;

        TestSubscriber(Consumer<LogEvent> onEvent) {
            this.onEvent = onEvent;
        }

        @Override
        public void onLogEvent(LogEvent event) {
            if (onEvent != null) onEvent.accept(event);
        }

        @Override
        public application.utils.logging.event.LogFilter getFilter() {
            return null;
        }
    }
}
