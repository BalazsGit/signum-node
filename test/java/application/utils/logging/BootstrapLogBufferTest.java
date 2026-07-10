package application.utils.logging;

import application.utils.logging.event.LogEvent;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BootstrapLogBuffer}.
 * <p>
 * Verifies bounded circular buffer behavior, capacity management, flush operations,
 * and thread-safety of the bootstrap log capture mechanism.
 * </p>
 */
@DisplayName("BootstrapLogBuffer Tests")
class BootstrapLogBufferTest {

    private ProfileLogRouter router;

    @BeforeEach
    void setUp() {
        ProfileLogRouter.resetInstance();
        ProfileThreadContext.clear();
        router = ProfileLogRouter.getInstance();
    }

    @AfterEach
    void tearDown() {
        ProfileThreadContext.clear();
        ProfileLogRouter.resetInstance();
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

    // ------------------------ flushToContext() ------------------------

    @Nested
    @DisplayName("flushToContext(ProfileLogContext)")
    class FlushToContextTests {

        @Test
        @DisplayName("flush delivers all entries to context")
        void flushToContext_GivenEntries_DeliversAll() {
            List<String> received = Collections.synchronizedList(new ArrayList<>());
            ProfileLogContext context = createCollectingContext(received);
            context.start();

            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);
            buffer.add("log line 1");
            buffer.add("log line 2");

            buffer.flushToContext(context);

            assertEquals(2, received.size());
            assertTrue(received.contains("log line 1"));
            assertTrue(received.contains("log line 2"));
        }

        @Test
        @DisplayName("flush with empty buffer is safe")
        void flushToContext_GivenEmptyBuffer_IsSafe() {
            List<String> received = Collections.synchronizedList(new ArrayList<>());
            ProfileLogContext context = createCollectingContext(received);
            context.start();

            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);

            assertDoesNotThrow(() -> buffer.flushToContext(context));
            assertEquals(0, received.size());
        }

        @Test
        @DisplayName("flush with null context throws")
        void flushToContext_GivenNullContext_ThrowsIllegalArgumentException() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);

            assertThrows(IllegalArgumentException.class, () -> buffer.flushToContext(null));
        }
    }

    // ------------------------ flushAllToContexts() ------------------------

    @Nested
    @DisplayName("flushAllToContexts(ProfileLogRouter)")
    class FlushAllToContextsTests {

        @Test
        @DisplayName("flush distributes to all registered contexts")
        void flushAllToContexts_GivenMultipleContexts_DistributesToAll() {
            List<String> nodeReceived = Collections.synchronizedList(new ArrayList<>());
            List<String> dbReceived = Collections.synchronizedList(new ArrayList<>());

            ProfileLogContext nodeCtx = createCollectingContext("node", "mainnet", nodeReceived);
            ProfileLogContext dbCtx = createCollectingContext("database", "mainnet", dbReceived);

            router.registerContext(nodeCtx);
            router.registerContext(dbCtx);

            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);
            buffer.add("shared log");

            buffer.flushAllToContexts(router);

            assertEquals(1, nodeReceived.size());
            assertEquals(1, dbReceived.size());
            assertEquals("shared log", nodeReceived.get(0));
            assertEquals("shared log", dbReceived.get(0));
        }

        @Test
        @DisplayName("flush with no registered contexts is safe")
        void flushAllToContexts_GivenNoContexts_IsSafe() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);
            buffer.add("orphan log");

            assertDoesNotThrow(() -> buffer.flushAllToContexts(router));
        }

        @Test
        @DisplayName("flush with null router throws")
        void flushAllToContexts_GivenNullRouter_ThrowsIllegalArgumentException() {
            BootstrapLogBuffer buffer = new BootstrapLogBuffer(10);

            assertThrows(IllegalArgumentException.class, () -> buffer.flushAllToContexts(null));
        }
    }

    // ------------------------ Test Helpers ------------------------

    private ProfileLogContext createCollectingContext(List<String> received) {
        return createCollectingContext("test", "default", received);
    }

    private ProfileLogContext createCollectingContext(String moduleId, String profileName, List<String> received) {
        ProfileLogContext context = new ProfileLogContext(moduleId, profileName);
        context.addSubscriber(new LogSubscriber() {
            @Override
            public void onLogEvent(LogEvent event) {
                received.add(event.getMessage());
            }

            @Override
            public LogFilter getFilter() {
                return null;
            }
        });
        return context;
    }
}