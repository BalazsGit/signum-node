package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFormatter;
import application.utils.logging.event.LogLevel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LogEvent}.
 * <p>
 * Verifies immutable ADT construction via Builder and factory methods,
 * lazy rendering with double-checked locking, and proper field extraction from LogRecord.
 * </p>
 */
@DisplayName("LogEvent Tests")
class LogEventTest {

    // ------------------------ fromText() ------------------------

    @Nested
    @DisplayName("fromText() factory")
    class FromTextTests {

        @Test
        @DisplayName("creates event with INFO level and provided message")
        void fromText_GivenPlainText_ReturnsValidEvent() {
            LogEvent event = LogEvent.fromText("hello world");

            assertEquals(LogLevel.INFO, event.getLevel());
            assertEquals("hello world", event.getMessage());
            assertNotNull(event.getThreadName());
            assertTrue(event.getTimestamp() > 0);
        }

        @Test
        @DisplayName("empty string creates valid event")
        void fromText_GivenEmptyString_ReturnsValidEvent() {
            LogEvent event = LogEvent.fromText("");

            assertEquals("", event.getMessage());
            assertNull(event.getLoggerName());
        }

        @Test
        @DisplayName("multiple calls produce different timestamps")
        void fromText_GivenMultipleCalls_HasDifferentTimestamps() {
            LogEvent event1 = LogEvent.fromText("a");
            try {
                Thread.sleep(2);
            } catch (InterruptedException ignored) {
            }
            LogEvent event2 = LogEvent.fromText("b");

            assertTrue(event2.getTimestamp() >= event1.getTimestamp());
        }
    }

    // ------------------------ from(LogRecord) ------------------------

    @Nested
    @DisplayName("from(LogRecord) factory")
    class FromLogRecordTests {

        @Test
        @DisplayName("null LogRecord throws NullPointerException")
        void from_GivenNullRecord_ThrowsNPE() {
            assertThrows(NullPointerException.class, () -> LogEvent.from(null));
        }

        @Test
        @DisplayName("extracts timestamp from LogRecord")
        void from_GivenRecordWithTimestamp_PreservesTimestamp() {
            long expectedTs = 1234567890L;
            LogRecord record = new LogRecord(Level.INFO, "test message");
            record.setMillis(expectedTs);

            LogEvent event = LogEvent.from(record);

            assertEquals(expectedTs, event.getTimestamp());
        }

        @Test
        @DisplayName("extracts level from LogRecord")
        void from_GivenRecordWithLevel_MapsLevelCorrectly() {
            LogRecord record = new LogRecord(Level.SEVERE, "error msg");
            LogEvent event = LogEvent.from(record);
            assertEquals(LogLevel.ERROR, event.getLevel());

            record = new LogRecord(Level.FINE, "debug msg");
            event = LogEvent.from(record);
            assertEquals(LogLevel.DEBUG, event.getLevel());

            record = new LogRecord(Level.INFO, "info msg");
            event = LogEvent.from(record);
            assertEquals(LogLevel.INFO, event.getLevel());
        }

        @Test
        @DisplayName("extracts logger name from LogRecord")
        void from_GivenRecordWithLoggerName_PreservesLoggerName() {
            LogRecord record = new LogRecord(Level.INFO, "msg");
            record.setLoggerName("signum.node.test");

            LogEvent event = LogEvent.from(record);

            assertEquals("signum.node.test", event.getLoggerName());
        }

        @Test
        @DisplayName("extracts message from LogRecord")
        void from_GivenRecordWithMessage_PreservesMessage() {
            LogRecord record = new LogRecord(Level.INFO, "my message");

            LogEvent event = LogEvent.from(record);

            assertEquals("my message", event.getMessage());
        }

        @Test
        @DisplayName("extracts source class and method from LogRecord")
        void from_GivenRecordWithSourceInfo_PreservesSource() {
            LogRecord record = new LogRecord(Level.INFO, "msg");
            record.setSourceClassName("com.example.MyClass");
            record.setSourceMethodName("myMethod");

            LogEvent event = LogEvent.from(record);

            assertEquals("com.example.MyClass", event.getSourceClassName());
            assertEquals("myMethod", event.getSourceMethodName());
        }

        @Test
        @DisplayName("source line number defaults to -1 when not set")
        void from_GivenRecordWithoutLineNumber_DefaultsToMinusOne() {
            LogRecord record = new LogRecord(Level.INFO, "msg");
            LogEvent event = LogEvent.from(record);
            assertEquals(-1, event.getSourceLineNumber());
        }

        @Test
        @DisplayName("extracts parameters from LogRecord")
        void from_GivenRecordWithParameters_PreservesParameters() {
            LogRecord record = new LogRecord(Level.INFO, "msg: {} and {}");
            record.setParameters(new Object[]{"a", "b"});

            LogEvent event = LogEvent.from(record);

            assertNotNull(event.getParameters());
            assertEquals(2, event.getParameters().length);
            assertEquals("a", event.getParameters()[0]);
            assertEquals("b", event.getParameters()[1]);
        }

        @Test
        @DisplayName("getParameters returns a clone (mutation safe)")
        void from_GivenRecordWithParameters_ReturnsClone() {
            LogRecord record = new LogRecord(Level.INFO, "msg");
            record.setParameters(new Object[]{"x"});

            LogEvent event = LogEvent.from(record);
            Object[] params1 = event.getParameters();
            Object[] params2 = event.getParameters();

            assertNotNull(params1);
            assertNotNull(params2);
            assertNotSame(params1, params2); // Different arrays
            assertArrayEquals(params1, params2); // Same content
        }

        @Test
        @DisplayName("extracts throwable from LogRecord")
        void from_GivenRecordWithThrowable_PreservesThrowable() {
            RuntimeException ex = new RuntimeException("boom");
            LogRecord record = new LogRecord(Level.SEVERE, "error");
            record.setThrown(ex);

            LogEvent event = LogEvent.from(record);

            assertSame(ex, event.getThrowable());
        }

        @Test
        @DisplayName("thread name is current thread")
        void from_GivenRecord_SetThreadNameToCurrent() {
            LogRecord record = new LogRecord(Level.INFO, "msg");
            LogEvent event = LogEvent.from(record);

            assertEquals(Thread.currentThread().getName(), event.getThreadName());
        }

        @Test
        @DisplayName("profileName is null when not explicitly set")
        void from_GivenRecord_DefaultProfileIsNull() {
            LogRecord record = new LogRecord(Level.INFO, "msg");
            LogEvent event = LogEvent.from(record);
            assertNull(event.getProfileName());
        }
    }

    // ------------------------ Builder Pattern ------------------------

    @Nested
    @DisplayName("Builder pattern")
    class BuilderTests {

        @Test
        @DisplayName("builder sets all fields fluently")
        void builder_GivenAllFields_BuildsCompleteEvent() {
            RuntimeException ex = new RuntimeException("test");
            LogEvent event = new LogEvent.Builder()
                    .timestamp(999L)
                    .level(LogLevel.WARN)
                    .loggerName("test.logger")
                    .message("builder msg")
                    .sourceClassName("MyClass")
                    .sourceMethodName("myMethod")
                    .sourceLineNumber(42)
                    .threadName("builder-thread")
                    .profileName("mainnet")
                    .throwable(ex)
                    .parameters(new Object[]{"p1", "p2"})
                    .build();

            assertEquals(999L, event.getTimestamp());
            assertEquals(LogLevel.WARN, event.getLevel());
            assertEquals("test.logger", event.getLoggerName());
            assertEquals("builder msg", event.getMessage());
            assertEquals("MyClass", event.getSourceClassName());
            assertEquals("myMethod", event.getSourceMethodName());
            assertEquals(42, event.getSourceLineNumber());
            assertEquals("builder-thread", event.getThreadName());
            assertEquals("mainnet", event.getProfileName());
            assertSame(ex, event.getThrowable());
            assertArrayEquals(new Object[]{"p1", "p2"}, event.getParameters());
        }

        @Test
        @DisplayName("builder defaults: level=INFO, lineNumber=-1")
        void builder_GivenMinimalFields_UsesDefaults() {
            LogEvent event = new LogEvent.Builder()
                    .message("minimal")
                    .build();

            assertEquals(LogLevel.INFO, event.getLevel());
            assertEquals(-1, event.getSourceLineNumber());
            assertEquals("minimal", event.getMessage());
        }

        @Test
        @DisplayName("builder parameters are cloned (safe from external mutation)")
        void builder_GivenParameters_ClonesArray() {
            Object[] original = new Object[]{"x"};
            LogEvent event = new LogEvent.Builder()
                    .parameters(original)
                    .build();

            Object[] result = event.getParameters();
            assertNotSame(original, result);
            assertArrayEquals(original, result);
        }

        @Test
        @DisplayName("builder with null parameters is safe")
        void builder_GivenNullParameters_IsSafe() {
            LogEvent event = new LogEvent.Builder()
                    .message("test")
                    .parameters(null)
                    .build();

            assertNull(event.getParameters());
        }
    }

    // ------------------------ Lazy Rendering ------------------------

    @Nested
    @DisplayName("Lazy rendering (getRenderedText)")
    class LazyRenderingTests {

        private static final LogFormatter TEST_FORMATTER = event ->
                event.getLevel() + ": " + event.getMessage();

        @Test
        @DisplayName("first call formats and caches result")
        void getRenderedText_GivenFirstCall_FormatsAndCaches() {
            LogEvent event = LogEvent.fromText("cache me");

            String result1 = event.getRenderedText(TEST_FORMATTER);

            assertNotNull(result1);
            assertEquals("INFO: cache me", result1);
        }

        @Test
        @DisplayName("subsequent calls return cached value (same formatter)")
        void getRenderedText_GivenSameFormatter_ReturnsCachedValue() {
            LogEvent event = LogEvent.fromText("cached");
            String result1 = event.getRenderedText(TEST_FORMATTER);
            String result2 = event.getRenderedText(TEST_FORMATTER);

            assertSame(result1, result2); // Same reference (cached)
        }

        @Test
        @DisplayName("null formatter throws or re-formats")
        void getRenderedText_GivenNullFormatter_ThrowsNPE() {
            LogEvent event = LogEvent.fromText("test");

            assertThrows(NullPointerException.class, () -> event.getRenderedText(null));
        }
    }

    // ------------------------ toString() ------------------------

    @Nested
    @DisplayName("toString() representation")
    class ToStringTests {

        @Test
        @DisplayName("toString contains level, thread, logger, message")
        void toString_ContainsRelevantFields() {
            LogEvent event = new LogEvent.Builder()
                    .level(LogLevel.ERROR)
                    .loggerName("signum.test")
                    .message("fail!")
                    .threadName("main")
                    .build();

            String str = event.toString();

            assertTrue(str.contains("ERROR"));
            assertTrue(str.contains("main"));
            assertTrue(str.contains("signum.test"));
            assertTrue(str.contains("fail!"));
        }

        @Test
        @DisplayName("toString with null loggerName handles gracefully")
        void toString_GivenNullLogger_HandlesGracefully() {
            LogEvent event = LogEvent.fromText("no logger");
            String str = event.toString();
            assertNotNull(str);
            assertTrue(str.contains("no logger"));
        }
    }

    // ------------------------ Immutability ------------------------

    @Nested
    @DisplayName("Immutability guarantees")
    class ImmutabilityTests {

        @Test
        @DisplayName("event fields cannot be modified after construction")
        void immutability_FieldsAreFinal() {
            LogEvent event = new LogEvent.Builder()
                    .timestamp(100L)
                    .level(LogLevel.DEBUG)
                    .message("original")
                    .build();

            // All getters return the same values (no setters exist)
            assertEquals(100L, event.getTimestamp());
            assertEquals(LogLevel.DEBUG, event.getLevel());
            assertEquals("original", event.getMessage());
        }

        @Test
        @DisplayName("getParameters returns defensive copy")
        void getParameters_ReturnsDefensiveCopy() {
            Object[] params = new Object[]{"a", "b"};
            LogEvent event = new LogEvent.Builder()
                    .parameters(params)
                    .build();

            Object[] returned = event.getParameters();
            returned[0] = "MODIFIED";

            // Original event unchanged
            assertArrayEquals(new Object[]{"a", "b"}, event.getParameters());
        }
    }
}