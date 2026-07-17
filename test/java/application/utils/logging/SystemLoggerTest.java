package application.utils.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;

/**
 * Unit tests for {@link SystemLogger}.
 */
@DisplayName("SystemLogger Tests")
class SystemLoggerTest {

    @AfterEach
    void tearDown() {
        SystemLogger.resetInstance();
    }

    // ── Singleton Pattern ───────────────────────────────────────────────

    @Nested
    @DisplayName("Singleton")
    class SingletonTests {

        @Test
        void getInstance_ReturnsSameInstance() {
            SystemLogger first = SystemLogger.getInstance();
            SystemLogger second = SystemLogger.getInstance();
            assertNotNull(first);
            assertSame(first, second);
        }

        @Test
        void isInitialized_BeforeGet_returnsFalse() {
            assertFalse(SystemLogger.isInitialized());
        }

        @Test
        void isInitialized_AfterGet_returnsTrue() {
            SystemLogger.getInstance();
            assertTrue(SystemLogger.isInitialized());
        }

        @Test
        void resetInstance_clearsSingleton() {
            SystemLogger.getInstance();
            SystemLogger.resetInstance();
            assertFalse(SystemLogger.isInitialized());
        }
    }

    // ── Identity & Defaults ─────────────────────────────────────────────

    @Nested
    @DisplayName("Identity")
    class IdentityTests {

        @Test
        void getName_returnsSystem() {
            assertEquals("system", SystemLogger.getInstance().getName());
        }

        @Test
        void defaultLogLevel_isTrace() {
            assertEquals(LogLevel.TRACE, SystemLogger.getInstance().getLogLevel());
        }

        @Test
        void setLogLevel_alwaysStaysAtTrace() {
            SystemLogger logger = SystemLogger.getInstance();
            logger.setLogLevel(LogLevel.ERROR);
            assertEquals(LogLevel.TRACE, logger.getLogLevel());
        }
    }

    // ── Logging Methods ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Logging")
    class LoggingTests {

        @Test
        void info_DispatchesToSubscriber() {
            SystemLogger logger = SystemLogger.getInstance();
            AtomicReference<LogEvent> received = new AtomicReference<>();
            logger.addSubscriber(new TestSubscriber(event -> received.set(event)));

            logger.info("hello");

            assertNotNull(received.get());
            assertEquals(LogLevel.INFO, received.get().getLevel());
            assertEquals("hello", received.get().getMessage());
        }

        @Test
        void trace_DispatchesToSubscriber() {
            SystemLogger logger = SystemLogger.getInstance();
            AtomicReference<LogEvent> received = new AtomicReference<>();
            logger.addSubscriber(new TestSubscriber(event -> received.set(event)));

            logger.trace("trace msg");

            assertNotNull(received.get());
            assertEquals(LogLevel.TRACE, received.get().getLevel());
        }

        @Test
        void error_WithThrowable_includesCause() {
            SystemLogger logger = SystemLogger.getInstance();
            AtomicReference<LogEvent> received = new AtomicReference<>();
            logger.addSubscriber(new TestSubscriber(event -> received.set(event)));

            RuntimeException cause = new RuntimeException("test");
            logger.error("fail", cause);

            assertNotNull(received.get());
            assertEquals(LogLevel.ERROR, received.get().getLevel());
            assertEquals(cause, received.get().getThrowable());
        }

        @Test
        void closedLogger_doesNotDispatch() {
            SystemLogger logger = SystemLogger.getInstance();
            AtomicInteger count = new AtomicInteger(0);
            logger.addSubscriber(new TestSubscriber(event -> count.incrementAndGet()));

            logger.close();

            logger.info("after close");
            assertEquals(0, count.get());
        }

        @Test
        void addSubscriber_AfterClose_doesNotAdd() {
            SystemLogger logger = SystemLogger.getInstance();
            logger.close();

            AtomicInteger count = new AtomicInteger(0);
            logger.addSubscriber(new TestSubscriber(event -> count.incrementAndGet()));

            logger.info("test");
            assertEquals(0, count.get());
        }

        @Test
        void nullMessage_doesNotDispatch() {
            SystemLogger logger = SystemLogger.getInstance();
            AtomicInteger count = new AtomicInteger(0);
            logger.addSubscriber(new TestSubscriber(event -> count.incrementAndGet()));

            logger.info(null);
            assertEquals(0, count.get());
        }
    }

    // ── Subscriber Management ───────────────────────────────────────────

    @Nested
    @DisplayName("Subscribers")
    class SubscriberTests {

        @Test
        void addNullSubscriber_throwsNPE() {
            SystemLogger logger = SystemLogger.getInstance();
            assertThrows(NullPointerException.class, () -> logger.addSubscriber(null));
        }

        @Test
        void removeSubscriber_disposesIt() {
            SystemLogger logger = SystemLogger.getInstance();
            AtomicInteger disposed = new AtomicInteger(0);
            LogSubscriber sub = new TestSubscriber(null, () -> disposed.incrementAndGet());

            logger.addSubscriber(sub);
            assertTrue(logger.removeSubscriber(sub));
            assertEquals(1, disposed.get());
        }

        @Test
        void close_DisposesAllSubscribers() {
            SystemLogger logger = SystemLogger.getInstance();
            AtomicInteger disposed = new AtomicInteger(0);
            LogSubscriber sub1 = new TestSubscriber(null, () -> disposed.incrementAndGet());
            LogSubscriber sub2 = new TestSubscriber(null, () -> disposed.incrementAndGet());

            logger.addSubscriber(sub1);
            logger.addSubscriber(sub2);
            logger.close();

            assertEquals(2, disposed.get());
        }
    }

    // ── toString ────────────────────────────────────────────────────────

    @Test
    void toString_ContainsInfo() {
        SystemLogger logger = SystemLogger.getInstance();
        String s = logger.toString();
        assertTrue(s.contains("SystemLogger"));
        assertTrue(s.contains("system"));
    }

    // ── Test Helper ─────────────────────────────────────────────────────

    /**
     * Simple test subscriber that accepts a Consumer callback and optional dispose action.
     */
    private static class TestSubscriber implements LogSubscriber {
        private final Consumer<LogEvent> onEvent;
        private final Runnable onDispose;

        TestSubscriber(Consumer<LogEvent> onEvent) {
            this(onEvent, null);
        }

        TestSubscriber(Consumer<LogEvent> onEvent, Runnable onDispose) {
            this.onEvent = onEvent;
            this.onDispose = onDispose;
        }

        @Override
        public void onLogEvent(LogEvent event) {
            if (onEvent != null) {
                onEvent.accept(event);
            }
        }

        @Override
        public application.utils.logging.event.LogFilter getFilter() {
            return null;
        }

        @Override
        public void dispose() {
            if (onDispose != null) {
                onDispose.run();
            }
        }
    }
}