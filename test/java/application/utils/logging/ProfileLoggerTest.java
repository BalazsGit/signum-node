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

    // ── Constructor & Identity ──────────────────────────────────────────

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

    // ── Logging & Level Filtering ───────────────────────────────────────

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

    // ── Forwarding to SystemLogger ──────────────────────────────────────

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

    // ── Lifecycle ───────────────────────────────────────────────────────

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

    // ── toString ────────────────────────────────────────────────────────

    @Test
    void toString_ContainsProfileInfo() {
        ProfileLogger logger = new ProfileLogger("node", "mainnet");
        String s = logger.toString();
        assertTrue(s.contains("ProfileLogger"));
        assertTrue(s.contains("node"));
        assertTrue(s.contains("mainnet"));
    }

    // ── Test Helper ─────────────────────────────────────────────────────

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