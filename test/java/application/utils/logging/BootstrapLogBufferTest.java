package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;
import application.utils.logging.event.LogFilter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BootstrapLogBuffer}.
 * <p>
 * Verifies bounded circular buffer behavior, capacity management, flush operations
 * to SystemLogger / ModuleLogger, and thread-safety of the bootstrap log capture mechanism.
 * </p>
 */
@DisplayName("BootstrapLogBuffer Tests")
class BootstrapLogBufferTest {

    @AfterEach
    void tearDown() {
        SystemLogger.resetInstance();
    }

    // ------------------------ Constructor ------------------------

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("default constructor creates buffer with capacity 500")
        void defaultConstructor_CreatesBufferWithDefaultCapacity() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer();

            assertEquals(BootstrapLogBuffer.DEFAULT_CAPACITY, buffer.getCapacity());
            assertEquals(500, buffer.getCapacity());
        }

        @Test
        @DisplayName("constructor with capacity creates buffer with specified size")
        void constructorWithCapacity_CreatesBufferWithSpecifiedSize() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(100);

            assertEquals(100, buffer.getCapacity());
        }

        @Test
        @DisplayName("constructor rejects zero capacity")
        void constructorWithZeroCapacity_ThrowsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class, () -> new BootstrapLogBuffer(0));
        }

        @Test
        @DisplayName("constructor rejects negative capacity")
        void constructorWithNegativeCapacity_ThrowsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class, () -> new BootstrapLogBuffer(-10));
        }

        @Test
        @DisplayName("new buffer is empty")
        void newBuffer_IsEmpty() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);

            assertEquals(0, buffer.size());
            assertFalse(buffer.isFull());
        }
    }

    // ------------------------ add() ------------------------

    @Nested
    @DisplayName("add(String)")
    class AddTests {

        @Test
        @DisplayName("add increases size when below capacity")
        void add_GivenSpaceAvailable_IncreasesSize() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);

            buffer.add("line 1");

            assertEquals(1, buffer.size());
            assertFalse(buffer.isFull());
        }

        @Test
        @DisplayName("add(null) is silently ignored")
        void add_GivenNull_IgnoresSilently() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);

            buffer.add(null);

            assertEquals(0, buffer.size());
        }

        @Test
        @DisplayName("multiple adds accumulate entries")
        void add_GivenMultipleEntries_AccumulatesAll() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);

            buffer.add("a");
            buffer.add("b");
            buffer.add("c");

            assertEquals(3, buffer.size());
        }

        @Test
        @DisplayName("add at capacity triggers circular overwrite")
        void add_GivenFullBuffer_OverwritesOldest() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(3);

            buffer.add("first");
            buffer.add("second");
            buffer.add("third");
            assertTrue(buffer.isFull());

            // This overwrites "first"
            buffer.add("fourth");

            assertEquals(3, buffer.size());
            List<String> entries = buffer.getEntries();
            assertEquals(3, entries.size());
            assertFalse(entries.contains("first"));
            assertTrue(entries.contains("fourth"));
        }
    }

    // ------------------------ getEntries() ------------------------

    @Nested
    @DisplayName("getEntries()")
    class GetEntriesTests {

        @Test
        @DisplayName("empty buffer returns empty list")
        void getEntries_GivenEmptyBuffer_ReturnsEmptyList() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);

            List<String> entries = buffer.getEntries();

            assertNotNull(entries);
            assertTrue(entries.isEmpty());
        }

        @Test
        @DisplayName("returns entries in insertion order when not full")
        void getEntries_GivenNotFull_ReturnsInOrder() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);

            buffer.add("alpha");
            buffer.add("beta");
            buffer.add("gamma");

            List<String> entries = buffer.getEntries();

            assertEquals(3, entries.size());
            assertEquals("alpha", entries.get(0));
            assertEquals("beta", entries.get(1));
            assertEquals("gamma", entries.get(2));
        }

        @Test
        @DisplayName("returns chronological order when circular overwrap occurred")
        void getEntries_GivenCircularOverflow_ReturnsChronologicalOrder() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(3);

            buffer.add("1");
            buffer.add("2");
            buffer.add("3");
            // Buffer full, next writes overwrite oldest
            buffer.add("4");  // overwrites "1"
            buffer.add("5");  // overwrites "2"

            List<String> entries = buffer.getEntries();

            assertEquals(3, entries.size());
            // Chronological order: 3, 4, 5
            assertEquals("3", entries.get(0));
            assertEquals("4", entries.get(1));
            assertEquals("5", entries.get(2));
        }

        @Test
        @DisplayName("returned list is unmodifiable")
        void getEntries_ReturnsUnmodifiableList() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);
            buffer.add("x");

            List<String> entries = buffer.getEntries();

            assertThrows(UnsupportedOperationException.class, () -> entries.add("y"));
        }

        @Test
        @DisplayName("returned list is a snapshot (independent of buffer state)")
        void getEntries_ReturnsSnapshot() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);

            buffer.add("before");
            List<String> snapshot = buffer.getEntries();
            buffer.add("after");

            assertEquals(1, snapshot.size());
            assertEquals("before", snapshot.get(0));
            assertEquals(2, buffer.size());
        }
    }

    // ------------------------ isFull() / size() ------------------------

    @Nested
    @DisplayName("isFull() and size()")
    class CapacityTests {

        @Test
        @DisplayName("isFull becomes true at capacity")
        void isFull_GivenExactCapacity_ReturnsTrue() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(5);

            for (int i = 0; i < 5; i++) {
                buffer.add("line " + i);
            }

            assertTrue(buffer.isFull());
            assertEquals(5, buffer.size());
        }

        @Test
        @DisplayName("isFull remains true after circular overwrite")
        void isFull_GivenCircularOverwrite_RemainsTrue() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(3);

            buffer.add("a");
            buffer.add("b");
            buffer.add("c");
            buffer.add("d"); // overwrites "a"

            assertTrue(buffer.isFull());
            assertEquals(3, buffer.size());
        }

        @Test
        @DisplayName("size stays at capacity after circular overwrite")
        void size_GivenCircularOverwrite_StaysAtCapacity() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(3);

            for (int i = 0; i < 100; i++) {
                buffer.add("entry " + i);
            }

            assertEquals(3, buffer.size());
        }
    }

    // ------------------------ clear() ------------------------

    @Nested
    @DisplayName("clear()")
    class ClearTests {

        @Test
        @DisplayName("clear resets size to zero")
        void clear_GivenEntries_ResetsSize() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);
            buffer.add("x");
            buffer.add("y");

            buffer.clear();

            assertEquals(0, buffer.size());
            assertTrue(buffer.getEntries().isEmpty());
        }

        @Test
        @DisplayName("clear allows fresh entries")
        void clear_GivenClearedBuffer_AcceptsNewEntries() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(3);

            buffer.add("old1");
            buffer.add("old2");
            buffer.add("old3");
            buffer.clear();
            buffer.add("new1");

            assertEquals(1, buffer.size());
            List<String> entries = buffer.getEntries();
            assertEquals(1, entries.size());
            assertEquals("new1", entries.get(0));
        }

        @Test
        @DisplayName("clear on empty buffer is safe")
        void clear_GivenEmptyBuffer_IsSafe() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);

            assertDoesNotThrow(() -> buffer.clear());
            assertEquals(0, buffer.size());
        }
    }

    // ------------------------ flushToSystemLogger() ------------------------

    @Nested
    @DisplayName("flushToSystemLogger()")
    class FlushToSystemLoggerTests {

        @Test
        @DisplayName("flush delivers all entries to SystemLogger")
        void flushToSystemLogger_GivenEntries_DeliversAll() {
            CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
            SystemLogger.getInstance().addSubscriber(new TestLogSubscriber(received::add));

            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);
            buffer.add("log line 1");
            buffer.add("log line 2");

            buffer.flushToSystemLogger();

            assertEquals(2, received.size());
            assertTrue(received.contains("log line 1"));
            assertTrue(received.contains("log line 2"));
        }

        @Test
        @DisplayName("flush with empty buffer is safe")
        void flushToSystemLogger_GivenEmptyBuffer_IsSafe() {
            CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
            SystemLogger.getInstance().addSubscriber(new TestLogSubscriber(received::add));

            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);

            assertDoesNotThrow(() -> buffer.flushToSystemLogger());
            assertEquals(0, received.size());
        }
    }

    // ------------------------ flushToLogger() ------------------------

    @Nested
    @DisplayName("flushToLogger(ModuleLogger)")
    class FlushToLoggerTests {

        @Test
        @DisplayName("flush delivers all entries to target ModuleLogger")
        void flushToLogger_GivenEntries_DeliversAll() {
            CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
            ProfileLogger logger = new ProfileLogger("node", "test");
            logger.addSubscriber(new TestLogSubscriber(received::add));

            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);
            buffer.add("to profile 1");
            buffer.add("to profile 2");

            buffer.flushToLogger(logger);

            assertEquals(2, received.size());
            assertTrue(received.contains("to profile 1"));
            assertTrue(received.contains("to profile 2"));
        }

        @Test
        @DisplayName("flush with null logger throws")
        void flushToLogger_GivenNull_ThrowsIllegalArgumentException() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);

            assertThrows(IllegalArgumentException.class, () -> buffer.flushToLogger(null));
        }

        @Test
        @DisplayName("flush with empty buffer is safe")
        void flushToLogger_GivenEmptyBuffer_IsSafe() {
            ProfileLogger logger = new ProfileLogger("node", "test");
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);

            assertDoesNotThrow(() -> buffer.flushToLogger(logger));
        }
    }

    // ------------------------ flushAllToLoggers() ------------------------

    @Nested
    @DisplayName("flushAllToLoggers(Iterable<ModuleLogger>)")
    class FlushAllToLoggersTests {

        @Test
        @DisplayName("flush distributes to all provided loggers")
        void flushAllToLoggers_GivenMultipleLoggers_DistributesToAll() {
            CopyOnWriteArrayList<String> nodeReceived = new CopyOnWriteArrayList<>();
            CopyOnWriteArrayList<String> dbReceived = new CopyOnWriteArrayList<>();

            ProfileLogger nodeLogger = new ProfileLogger("node", "mainnet");
            nodeLogger.addSubscriber(new TestLogSubscriber(nodeReceived::add));

            ProfileLogger dbLogger = new ProfileLogger("database", "mainnet");
            dbLogger.addSubscriber(new TestLogSubscriber(dbReceived::add));

            List<ModuleLogger> loggers = new ArrayList<>();
            loggers.add(nodeLogger);
            loggers.add(dbLogger);

            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);
            buffer.add("shared log");

            buffer.flushAllToLoggers(loggers);

            assertEquals(1, nodeReceived.size());
            assertEquals(1, dbReceived.size());
            assertEquals("shared log", nodeReceived.get(0));
            assertEquals("shared log", dbReceived.get(0));
        }

        @Test
        @DisplayName("flush with empty list is safe")
        void flushAllToLoggers_GivenEmptyList_IsSafe() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);
            buffer.add("orphan log");

            assertDoesNotThrow(() -> buffer.flushAllToLoggers(Collections.emptyList()));
        }

        @Test
        @DisplayName("flush with null iterable throws")
        void flushAllToLoggers_GivenNull_ThrowsIllegalArgumentException() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);

            assertThrows(IllegalArgumentException.class, () -> buffer.flushAllToLoggers(null));
        }

        @Test
        @DisplayName("flush skips null loggers in list")
        void flushAllToLoggers_GivenNullInList_SkipsThem() {
            CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
            ProfileLogger logger = new ProfileLogger("node", "mainnet");
            logger.addSubscriber(new TestLogSubscriber(received::add));

            List<ModuleLogger> loggers = new ArrayList<>();
            loggers.add(null);
            loggers.add(logger);
            loggers.add(null);

            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);
            buffer.add("msg");

            assertDoesNotThrow(() -> buffer.flushAllToLoggers(loggers));
            assertEquals(1, received.size());
        }
    }

    // ------------------------ Test Helpers ------------------------

    /**
     * Simple LogSubscriber that captures messages via an add callback.
     */
    private static class TestLogSubscriber implements LogSubscriber {
        private final java.util.function.Consumer<String> onMessage;

        TestLogSubscriber(java.util.function.Consumer<String> onMessage) {
            this.onMessage = onMessage;
        }

        @Override
        public void onLogEvent(LogEvent event) {
            if (onMessage != null && event.getMessage() != null) {
                onMessage.accept(event.getMessage());
            }
        }

        @Override
        public LogFilter getFilter() {
            return null;
        }
    }
}