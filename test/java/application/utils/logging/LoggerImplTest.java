package application.utils.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;

/**
 * Unit tests for {@link LoggerImpl} abstract implementation.
 * <p>
 * Because {@link LoggerImpl} is abstract, these tests use a concrete test subclass:
 * {@link TestLogger}.
 * </p>
 */
@DisplayName("LoggerImpl Tests")
class LoggerImplTest {

    // ── Constructor Tests ────────────────────────────────────────────────

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        void constructor_ValidName_setsFields() {
            TestLogger logger = new TestLogger("test");

            assertEquals("test", logger.getName());
            assertFalse(logger.isClosed());
        }

        @Test
        void constructor_NullName_throwsNPE() {
            assertThrows(NullPointerException.class, () -> new TestLogger(null));
        }

        @Test
        void constructor_EmptyName_throwsIAE() {
            assertThrows(IllegalArgumentException.class, () -> new TestLogger(""));
        }

        @Test
        @DisplayName("GH-2026-0715: Default log level is INFO")
        void constructor_DefaultLogLevel_isInfo() {
            TestLogger logger = new TestLogger("test");
            assertEquals(LogLevel.INFO, logger.getLogLevel());
        }

        @Test
        void constructor_DefaultSubscribers_emptyList() {
            TestLogger logger = new TestLogger("test");
            assertTrue(logger.getSubscribers().isEmpty());
        }
    }

    // ── Identity Tests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Identity")
    class IdentityTests {

        @Test
        void getName_returnsProvidedName() {
            TestLogger logger = new TestLogger("myModule.subSystem");
            assertEquals("myModule.subSystem", logger.getName());
        }

        @Test
        void isClosed_BeforeClose_returnsFalse() {
            TestLogger logger = new TestLogger("test");
            assertFalse(logger.isClosed());
        }

        @Test
        void isClosed_AfterClose_returnsTrue() {
            TestLogger logger = new TestLogger("test");
            logger.close();
            assertTrue(logger.isClosed());
        }
    }

    // ── Log Level Tests ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Log Level")
    class LogLevelTests {

        @Test
        void setLogLevel_ValidLevel_updatesLevel() {
            TestLogger logger = new TestLogger("test");
            logger.setLogLevel(LogLevel.DEBUG);
            assertEquals(LogLevel.DEBUG, logger.getLogLevel());
        }

        @Test
        void setLogLevel_NullLevel_throwsNPE() {
            TestLogger logger = new TestLogger("test");
            assertThrows(NullPointerException.class, () -> logger.setLogLevel(null));
        }

        @Test
        @DisplayName("GH-2026-0715: All log levels can be set")
        void setLogLevel_AllLevels_work() {
            TestLogger logger = new TestLogger("test");
            for (LogLevel level : LogLevel.values()) {
                logger.setLogLevel(level);
                assertEquals(level, logger.getLogLevel(), "Should accept " + level);
            }
        }
    }

    // ── Logging Method Tests ─────────────────────────────────────────────

    @Nested
    @DisplayName("Logging Methods")
    class LoggingMethodTests {

        @Test
        void trace_DispatchesCorrectEvent() {
            TestLogger logger = new TestLogger("test");
            logger.setLogLevel(LogLevel.TRACE);
            AtomicReference<LogEvent> received = new AtomicReference<>();
            logger.addSubscriber(new CapturingSubscriber(received::set));

            logger.trace("trace msg");

            assertNotNull(received.get());
            assertEquals(LogLevel.TRACE, received.get().getLevel());
            assertEquals("trace msg", received.get().getMessage());
            assertEquals("test", received.get().getLoggerName());
        }

        @Test
        void debug_DispatchesCorrectEvent() {
            TestLogger logger = new TestLogger("test");
            logger.setLogLevel(LogLevel.DEBUG);
            AtomicReference<LogEvent> received = new AtomicReference<>();
            logger.addSubscriber(new CapturingSubscriber(received::set));

            logger.debug("debug msg");

            assertNotNull(received.get());
            assertEquals(LogLevel.DEBUG, received.get().getLevel());
        }

        @Test
        void info_DispatchesCorrectEvent() {
            TestLogger logger = new TestLogger("test");
            AtomicReference<LogEvent> received = new AtomicReference<>();
            logger.addSubscriber(new CapturingSubscriber(received::set));

            logger.info("info msg");

            assertNotNull(received.get());
            assertEquals(LogLevel.INFO, received.get().getLevel());
        }

        @Test
        void warn_DispatchesCorrectEvent() {
            TestLogger logger = new TestLogger("test");
            AtomicReference<LogEvent> received = new AtomicReference<>();
            logger.addSubscriber(new CapturingSubscriber(received::set));

            logger.warn("warn msg");

            assertNotNull(received.get());
            assertEquals(LogLevel.WARN, received.get().getLevel());
        }

        @Test
        void warn_WithCause_includesThrowable() {
            TestLogger logger = new TestLogger("test");
            AtomicReference<LogEvent> received = new AtomicReference<>();
            logger.addSubscriber(new CapturingSubscriber(received::set));

            RuntimeException cause = new RuntimeException("failure");
            logger.warn("warn with cause", cause);

            assertNotNull(received.get());
            assertEquals(LogLevel.WARN, received.get().getLevel());
            assertSame(cause, received.get().getThrowable());
        }

        @Test
        void error_DispatchesCorrectEvent() {
            TestLogger logger = new TestLogger("test");
            AtomicReference<LogEvent> received = new AtomicReference<>();
            logger.addSubscriber(new CapturingSubscriber(received::set));

            logger.error("error msg");

            assertNotNull(received.get());
            assertEquals(LogLevel.ERROR, received.get().getLevel());
        }

        @Test
        void error_WithCause_includesThrowable() {
            TestLogger logger = new TestLogger("test");
            AtomicReference<LogEvent> received = new AtomicReference<>();
            logger.addSubscriber(new CapturingSubscriber(received::set));

            RuntimeException cause = new RuntimeException("error");
            logger.error("error with cause", cause);

            assertNotNull(received.get());
            assertEquals(LogLevel.ERROR, received.get().getLevel());
            assertSame(cause, received.get().getThrowable());
        }

        @Test
        void log_WithoutCause_works() {
            TestLogger logger = new TestLogger("test");
            AtomicReference<LogEvent> received = new AtomicReference<>();
            logger.addSubscriber(new CapturingSubscriber(received::set));

            logger.log(LogLevel.WARN, "dynamic warn");

            assertNotNull(received.get());
            assertEquals(LogLevel.WARN, received.get().getLevel());
            assertNullThrowable(received.get());
        }

        @Test
        void log_WithCause_includesThrowable() {
            TestLogger logger = new TestLogger("test");
            AtomicReference<LogEvent> received = new AtomicReference<>();
            logger.addSubscriber(new CapturingSubscriber(received::set));

            RuntimeException cause = new RuntimeException("x");
            logger.log(LogLevel.ERROR, "msg", cause);

            assertSame(cause, received.get().getThrowable());
        }
    }

    // ── Level Filtering Tests ────────────────────────────────────────────

    @Nested
    @DisplayName("Level Filtering")
    class LevelFilteringTests {

        @Test
        @DisplayName("TRACE below INFO default → not dispatched")
        void trace_BelowDefaultInfo_notDispatched() {
            TestLogger logger = new TestLogger("test");
            // Default is INFO
            AtomicInteger count = new AtomicInteger(0);
            logger.addSubscriber(new CountingSubscriber(count::incrementAndGet));

            logger.trace("trace");

            assertEquals(0, count.get());
        }

        @Test
        @DisplayName("DEBUG below INFO default → not dispatched")
        void debug_BelowDefaultInfo_notDispatched() {
            TestLogger logger = new TestLogger("test");
            AtomicInteger count = new AtomicInteger(0);
            logger.addSubscriber(new CountingSubscriber(count::incrementAndGet));

            logger.debug("debug");

            assertEquals(0, count.get());
        }

        @Test
        void info_AtDefaultInfo_isDispatched() {
            TestLogger logger = new TestLogger("test");
            AtomicInteger count = new AtomicInteger(0);
            logger.addSubscriber(new CountingSubscriber(count::incrementAndGet));

            logger.info("info");

            assertEquals(1, count.get());
        }

        @Test
        void warn_AboveDefaultInfo_isDispatched() {
            TestLogger logger = new TestLogger("test");
            AtomicInteger count = new AtomicInteger(0);
            logger.addSubscriber(new CountingSubscriber(count::incrementAndGet));

            logger.warn("warn");

            assertEquals(1, count.get());
        }

        @Test
        void error_AboveDefaultInfo_isDispatched() {
            TestLogger logger = new TestLogger("test");
            AtomicInteger count = new AtomicInteger(0);
            logger.addSubscriber(new CountingSubscriber(count::incrementAndGet));

            logger.error("error");

            assertEquals(1, count.get());
        }

        @Test
        @DisplayName("setLogLevel ERROR → only ERROR dispatched")
        void setErrorLevel_onlyErrorDispatched() {
            TestLogger logger = new TestLogger("test");
            logger.setLogLevel(LogLevel.ERROR);
            AtomicInteger count = new AtomicInteger(0);
            logger.addSubscriber(new CountingSubscriber(count::incrementAndGet));

            logger.trace("t");
            logger.debug("d");
            logger.info("i");
            logger.warn("w");
            logger.error("e");

            assertEquals(1, count.get(), "Only ERROR should pass");
        }

        @Test
        @DisplayName("setLogLevel TRACE → all levels dispatched")
        void setTraceLevel_allDispatched() {
            TestLogger logger = new TestLogger("test");
            logger.setLogLevel(LogLevel.TRACE);
            AtomicInteger count = new AtomicInteger(0);
            logger.addSubscriber(new CountingSubscriber(count::incrementAndGet));

            logger.trace("t");
            logger.debug("d");
            logger.info("i");
            logger.warn("w");
            logger.error("e");

            assertEquals(5, count.get(), "All 5 levels should pass");
        }

        @Test
        void nullLevel_notDispatched() {
            TestLogger logger = new TestLogger("test");
            AtomicInteger count = new AtomicInteger(0);
            logger.addSubscriber(new CountingSubscriber(count::incrementAndGet));

            logger.log(null, "null level");

            assertEquals(0, count.get());
        }

        @Test
        void nullMessage_notDispatched() {
            TestLogger logger = new TestLogger("test");
            AtomicInteger count = new AtomicInteger(0);
            logger.addSubscriber(new CountingSubscriber(count::incrementAndGet));

            logger.info(null);

            assertEquals(0, count.get());
        }
    }

    // ── Subscriber Management Tests ──────────────────────────────────────

    @Nested
    @DisplayName("Subscriber Management")
    class SubscriberManagementTests {

        @Test
        void addNullSubscriber_throwsNPE() {
            TestLogger logger = new TestLogger("test");
            assertThrows(NullPointerException.class, () -> logger.addSubscriber(null));
        }

        @Test
        void addSubscriber_includesInList() {
            TestLogger logger = new TestLogger("test");
            LogSubscriber sub = new CapturingSubscriber(null);

            logger.addSubscriber(sub);

            List<LogSubscriber> list = logger.getSubscribers();
            assertEquals(1, list.size());
            assertSame(sub, list.get(0));
        }

        @Test
        void addMultipleSubscribers_allIncluded() {
            TestLogger logger = new TestLogger("test");
            LogSubscriber s1 = new CapturingSubscriber(null);
            LogSubscriber s2 = new CapturingSubscriber(null);
            LogSubscriber s3 = new CapturingSubscriber(null);

            logger.addSubscriber(s1);
            logger.addSubscriber(s2);
            logger.addSubscriber(s3);

            assertEquals(3, logger.getSubscribers().size());
        }

        @Test
        void getSubscribers_returnsUnmodifiableList() {
            TestLogger logger = new TestLogger("test");
            logger.addSubscriber(new CapturingSubscriber(null));

            List<LogSubscriber> list = logger.getSubscribers();
            assertThrows(UnsupportedOperationException.class, () -> list.add(new CapturingSubscriber(null)));
        }

        @Test
        void removeExistingSubscriber_returnsTrue() {
            TestLogger logger = new TestLogger("test");
            LogSubscriber sub = new CapturingSubscriber(null);
            logger.addSubscriber(sub);

            boolean removed = logger.removeSubscriber(sub);

            assertTrue(removed);
            assertTrue(logger.getSubscribers().isEmpty());
        }

        @Test
        void removeNonExistentSubscriber_returnsFalse() {
            TestLogger logger = new TestLogger("test");
            LogSubscriber sub = new CapturingSubscriber(null);

            boolean removed = logger.removeSubscriber(sub);

            assertFalse(removed);
        }

        @Test
        void removeSubscriber_callsDispose() {
            TestLogger logger = new TestLogger("test");
            AtomicInteger disposed = new AtomicInteger(0);
            LogSubscriber sub = new CapturingSubscriber(null, () -> disposed.incrementAndGet());
            logger.addSubscriber(sub);

            logger.removeSubscriber(sub);

            assertEquals(1, disposed.get());
        }

        @Test
        void removeSubscriber_doesNotDisposeIfNotRegistered() {
            TestLogger logger = new TestLogger("test");
            AtomicInteger disposed = new AtomicInteger(0);
            LogSubscriber sub = new CapturingSubscriber(null, () -> disposed.incrementAndGet());

            logger.removeSubscriber(sub);

            assertEquals(0, disposed.get(), "dispose should not be called for unregistered subscriber");
        }
    }

    // ── Dispatch Isolation Tests ─────────────────────────────────────────

    @Nested
    @DisplayName("Dispatch Isolation")
    class DispatchIsolationTests {

        @Test
        @DisplayName("One failing subscriber does not prevent others from receiving events")
        void failingSubscriber_doesNotBlockOthers() {
            TestLogger logger = new TestLogger("test");
            AtomicInteger goodCount = new AtomicInteger(0);
            AtomicInteger badCount = new AtomicInteger(0);

            // Bad subscriber throws on every event
            LogSubscriber badSub = new CapturingSubscriber(event -> {
                badCount.incrementAndGet();
                throw new RuntimeException("I always fail");
            });
            // Good subscriber counts events
            LogSubscriber goodSub = new CapturingSubscriber(e -> goodCount.incrementAndGet());

            logger.addSubscriber(badSub);
            logger.addSubscriber(goodSub);

            logger.info("msg1");
            logger.info("msg2");
            logger.info("msg3");

            assertEquals(3, badCount.get(), "Bad subscriber was called 3 times before throwing");
            assertEquals(3, goodCount.get(), "Good subscriber received all 3 events despite failures");
        }

        @Test
        void filteredSubscriber_doesNotReceiveNonMatchingEvents() {
            TestLogger logger = new TestLogger("test");
            AtomicInteger count = new AtomicInteger(0);

            // Only accept ERROR level
            LogSubscriber sub = new CapturingSubscriber(
                event -> count.incrementAndGet(),
                () -> {},
                event -> event.getLevel() == LogLevel.ERROR
            );
            logger.addSubscriber(sub);

            logger.info("info");
            logger.warn("warn");
            logger.error("error");

            assertEquals(1, count.get(), "Only ERROR should pass the filter");
        }

        @Test
        void subscriberWithNullFilter_receivesAllEvents() {
            TestLogger logger = new TestLogger("test");
            AtomicInteger count = new AtomicInteger(0);
            // null filter → accept all
            LogSubscriber sub = new CapturingSubscriber(event -> count.incrementAndGet(), () -> {}, null);
            logger.addSubscriber(sub);

            logger.info("a");
            logger.warn("b");
            logger.error("c");

            assertEquals(3, count.get());
        }
    }

    // ── Closed State Tests ───────────────────────────────────────────────

    @Nested
    @DisplayName("Closed State")
    class ClosedStateTests {

        @Test
        void closedLogger_doesNotDispatch() {
            TestLogger logger = new TestLogger("test");
            AtomicInteger count = new AtomicInteger(0);
            logger.addSubscriber(new CountingSubscriber(count::incrementAndGet));

            logger.close();
            logger.info("after close");

            assertEquals(0, count.get());
        }

        @Test
        void addSubscriber_AfterClose_doesNotAdd() {
            TestLogger logger = new TestLogger("test");
            logger.close();
            LogSubscriber sub = new CapturingSubscriber(null);

            logger.addSubscriber(sub);

            assertTrue(logger.getSubscribers().isEmpty());
        }

        @Test
        void close_DisposesAllSubscribers() {
            TestLogger logger = new TestLogger("test");
            AtomicInteger disposed = new AtomicInteger(0);
            LogSubscriber s1 = new CapturingSubscriber(null, () -> disposed.incrementAndGet());
            LogSubscriber s2 = new CapturingSubscriber(null, () -> disposed.incrementAndGet());
            LogSubscriber s3 = new CapturingSubscriber(null, () -> disposed.incrementAndGet());

            logger.addSubscriber(s1);
            logger.addSubscriber(s2);
            logger.addSubscriber(s3);
            logger.close();

            assertEquals(3, disposed.get());
        }

        @Test
        void close_ClearsSubscriberList() {
            TestLogger logger = new TestLogger("test");
            logger.addSubscriber(new CapturingSubscriber(null));
            logger.addSubscriber(new CapturingSubscriber(null));

            logger.close();

            assertTrue(logger.getSubscribers().isEmpty());
        }

        @Test
        @DisplayName("Double close is safe")
        void doubleClose_doesNotThrow() {
            TestLogger logger = new TestLogger("test");
            logger.addSubscriber(new CapturingSubscriber(null));

            assertDoesNotThrow(() -> {
                logger.close();
                logger.close();
            });
        }

        @Test
        void close_DisposeException_doesNotBreakOtherSubscribers() {
            TestLogger logger = new TestLogger("test");
            AtomicInteger goodDisposed = new AtomicInteger(0);

            LogSubscriber badSub = new CapturingSubscriber(
                null,
                () -> { throw new RuntimeException("Dispose failure"); }
            );
            LogSubscriber goodSub = new CapturingSubscriber(
                null,
                () -> goodDisposed.incrementAndGet()
            );

            logger.addSubscriber(badSub);
            logger.addSubscriber(goodSub);

            // Close must not throw even if one subscriber's dispose fails
            assertDoesNotThrow(logger::close);
            assertEquals(1, goodDisposed.get(), "Good subscriber was still disposed");
        }
    }

    // ── toString Test ────────────────────────────────────────────────────

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        void toString_ContainsClassNameAndName() {
            TestLogger logger = new TestLogger("myName");
            String s = logger.toString();

            assertTrue(s.contains("TestLogger"));
            assertTrue(s.contains("myName"));
            assertTrue(s.contains("closed=false"));
        }

        @Test
        void toString_AfterClose_showsClosed() {
            TestLogger logger = new TestLogger("x");
            logger.addSubscriber(new CapturingSubscriber(null));
            logger.close();

            String s = logger.toString();
            assertTrue(s.contains("closed=true"));
            assertTrue(s.contains("subscribers=0"));
        }
    }

    // ── Helper: Concrete Test Subclass of LoggerImpl ─────────────────────

    /**
     * Minimal concrete implementation of {@link LoggerImpl} for testing.
     */
    private static class TestLogger extends LoggerImpl {
        protected TestLogger(String name) {
            super(name);
        }
    }

    // ── Helper: Capturing Subscriber with optional filter & dispose ──────

    /**
     * A {@link LogSubscriber} that captures events via a Consumer callback,
     * supports an optional dispose action and an optional filter.
     */
    private static class CapturingSubscriber implements LogSubscriber {
        private final Consumer<LogEvent> onEvent;
        private final Runnable onDispose;
        private final LogFilter filter;

        CapturingSubscriber(Consumer<LogEvent> onEvent) {
            this(onEvent, null, null);
        }

        CapturingSubscriber(Consumer<LogEvent> onEvent, Runnable onDispose) {
            this(onEvent, onDispose, null);
        }

        CapturingSubscriber(Consumer<LogEvent> onEvent, Runnable onDispose, LogFilter filter) {
            this.onEvent = onEvent;
            this.onDispose = onDispose;
            this.filter = filter;
        }

        @Override
        public void onLogEvent(LogEvent event) {
            if (onEvent != null) {
                onEvent.accept(event);
            }
        }

        @Override
        public LogFilter getFilter() {
            return filter;
        }

        @Override
        public void dispose() {
            if (onDispose != null) {
                onDispose.run();
            }
        }
    }

    /**
     * A simple subscriber that only counts events.
     */
    private static class CountingSubscriber implements LogSubscriber {
        private final Runnable onEvent;

        CountingSubscriber(Runnable onEvent) {
            this.onEvent = onEvent;
        }

        @Override
        public void onLogEvent(LogEvent event) {
            onEvent.run();
        }

        @Override
        public LogFilter getFilter() {
            return null;
        }
    }

    // ── Assertion Helpers ────────────────────────────────────────────────

    private static void assertNullThrowable(LogEvent event) {
        assertEquals(null, event.getThrowable(), "Throwable should be null when not provided");
    }

    private static void assertDoesNotThrow(Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            throw new AssertionError("Expected no exception but got: " + t.getClass().getName() + ": " + t.getMessage(), t);
        }
    }
}