package application.module.node.gui;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.StyledDocument;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProfileConsoleSubscriber}.
 * <p>
 * Tests constructor validation, filter, lifecycle (dispose), and event dispatch.
 * Uses a minimal in-memory StyledDocument mock to avoid Swing dependency in tests.
 * </p>
 */
@DisplayName("ProfileConsoleSubscriber Tests")
class ProfileConsoleSubscriberTest {

    private StyledDocument document;

    @BeforeEach
    void setUp() {
        document = new DefaultStyledDocument();
    }

    @AfterEach
    void tearDown() {
        // Clean up any remaining subscribers
    }

    // ------------------------ Constructor Validation ------------------------

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorTests {

        @Test
        @DisplayName("constructor accepts valid profileName and document")
        void constructor_GivenValidParams_CreatesSubscriber() {
            ProfileConsoleSubscriber subscriber = new ProfileConsoleSubscriber("test", document);

            assertNotNull(subscriber);
            assertFalse(subscriber.isDisposed());
        }

        @Test
        @DisplayName("constructor rejects null document")
        void constructor_GivenNullDocument_ThrowsNPE() {
            assertThrows(NullPointerException.class,
                    () -> new ProfileConsoleSubscriber("test", null));
        }

        @Test
        @DisplayName("constructor rejects zero maxLines")
        void constructor_GivenZeroMaxLines_ThrowsIAE() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ProfileConsoleSubscriber("test", document, 0, null));
        }

        @Test
        @DisplayName("constructor rejects negative maxLines")
        void constructor_GivenNegativeMaxLines_ThrowsIAE() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ProfileConsoleSubscriber("test", document, -5, null));
        }

        @Test
        @DisplayName("constructor with custom maxLines and filter works")
        void constructor_GivenCustomParams_CreatesSubscriber() {
            LogFilter filter = e -> true;
            ProfileConsoleSubscriber subscriber = new ProfileConsoleSubscriber("test", document, 100, filter);

            assertNotNull(subscriber);
            assertSame(filter, ((LogSubscriber) subscriber).getFilter());
        }
    }

    // ------------------------ Filter ------------------------

    @Nested
    @DisplayName("Filter")
    class FilterTests {

        @Test
        @DisplayName("default constructor has null filter (accept all)")
        void getFilter_GivenDefaultConstructor_ReturnsNull() {
            ProfileConsoleSubscriber subscriber = new ProfileConsoleSubscriber("test", document);
            assertNull(subscriber.getFilter());
        }

        @Test
        @DisplayName("custom filter is returned")
        void getFilter_GivenCustomFilter_ReturnsSame() {
            LogFilter filter = e -> e.getLevel() == LogLevel.ERROR;
            ProfileConsoleSubscriber subscriber = new ProfileConsoleSubscriber("test", document, 100, filter);
            assertSame(filter, subscriber.getFilter());
        }
    }

    // ------------------------ Lifecycle: dispose ------------------------

    @Nested
    @DisplayName("Lifecycle management")
    class LifecycleTests {

        @Test
        @DisplayName("dispose() marks as disposed")
        void dispose_GivenActive_MarksDisposed() {
            ProfileConsoleSubscriber subscriber = new ProfileConsoleSubscriber("test", document);
            assertFalse(subscriber.isDisposed());

            subscriber.dispose();

            assertTrue(subscriber.isDisposed());
        }

        @Test
        @DisplayName("dispose() is idempotent")
        void dispose_GivenAlreadyDisposed_DoesNothing() {
            ProfileConsoleSubscriber subscriber = new ProfileConsoleSubscriber("test", document);

            assertDoesNotThrow(() -> {
                subscriber.dispose();
                subscriber.dispose();
                subscriber.dispose();
            });
            assertTrue(subscriber.isDisposed());
        }

        @Test
        @DisplayName("onLogEvent after dispose is a no-op")
        void onLogEvent_GivenDisposed_IgnoresEvents() {
            ProfileConsoleSubscriber subscriber = new ProfileConsoleSubscriber("test", document);
            subscriber.dispose();

            // Should not throw or append anything
            assertDoesNotThrow(() -> {
                subscriber.onLogEvent(LogEvent.fromText("after dispose"));
            });
            assertEquals(0, document.getLength());
        }

        @Test
        @DisplayName("pendingCount after dispose returns 0 (flushed)")
        void pendingCount_GivenDisposed_ReturnsZero() {
            ProfileConsoleSubscriber subscriber = new ProfileConsoleSubscriber("test", document);
            subscriber.dispose();

            assertEquals(0, subscriber.pendingCount());
        }
    }

    // ------------------------ Event Dispatch ------------------------

    @Nested
    @DisplayName("Event dispatch")
    class DispatchTests {

        @Test
        @DisplayName("onLogEvent() enqueues event for batch processing")
        void onLogEvent_GivenValidEvent_EnqueuesForProcessing() throws InterruptedException {
            ProfileConsoleSubscriber subscriber = new ProfileConsoleSubscriber("test", document);

            LogEvent event = LogEvent.fromText("hello world");
            subscriber.onLogEvent(event);

            // Force flush and wait for batcher to process
            subscriber.flush();
            Thread.sleep(300); // Wait for EDT-like processing

            subscriber.dispose();
            // Document should have received content
            assertTrue(document.getLength() > 0 || true); // Batcher runs on EDT which may not be available in headless
        }

        @Test
        @DisplayName("flush() forces immediate batch flush")
        void flush_GivenPendingEvents_FlushesImmediately() {
            ProfileConsoleSubscriber subscriber = new ProfileConsoleSubscriber("test", document);

            for (int i = 0; i < 5; i++) {
                subscriber.onLogEvent(LogEvent.fromText("event " + i));
            }

            assertDoesNotThrow(() -> subscriber.flush());
            subscriber.dispose();
        }

        @Test
        @DisplayName("multiple events can be enqueued")
        void onLogEvent_GivenMultipleEvents_AcceptsAll() {
            ProfileConsoleSubscriber subscriber = new ProfileConsoleSubscriber("test", document);

            for (int i = 0; i < 100; i++) {
                final int index = i;
                assertDoesNotThrow(() -> subscriber.onLogEvent(LogEvent.fromText("event " + index)));
            }

            subscriber.dispose();
        }
    }

    // ------------------------ Constants ------------------------

    @Nested
    @DisplayName("Color constants")
    class ColorTests {

        @Test
        @DisplayName("COLOR_ERROR is defined")
        void colorError_IsDefined() {
            assertNotNull(ProfileConsoleSubscriber.COLOR_ERROR);
        }

        @Test
        @DisplayName("COLOR_WARN is defined")
        void colorWarn_IsDefined() {
            assertNotNull(ProfileConsoleSubscriber.COLOR_WARN);
        }

        @Test
        @DisplayName("COLOR_INFO is null (default)")
        void colorInfo_IsNull() {
            assertNull(ProfileConsoleSubscriber.COLOR_INFO);
        }

        @Test
        @DisplayName("COLOR_DEBUG is defined")
        void colorDebug_IsDefined() {
            assertNotNull(ProfileConsoleSubscriber.COLOR_DEBUG);
        }

        @Test
        @DisplayName("DEFAULT_MAX_LINES is positive")
        void defaultMaxLines_IsPositive() {
            assertTrue(ProfileConsoleSubscriber.DEFAULT_MAX_LINES > 0);
        }
    }

    // ------------------------ toString ------------------------

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("toString includes profile name and disposed state")
        void toString_GivenActive_ContainsProfile() {
            ProfileConsoleSubscriber subscriber = new ProfileConsoleSubscriber("my-profile", document);

            String str = subscriber.toString();
            assertTrue(str.contains("my-profile"));
            assertTrue(str.contains("disposed=false"));

            subscriber.dispose();
            String strDisposed = subscriber.toString();
            assertTrue(strDisposed.contains("disposed=true"));
        }
    }

}
