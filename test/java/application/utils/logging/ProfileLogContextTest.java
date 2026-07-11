package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;

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
 * Unit tests for {@link ProfileLogContext}.
 * <p>
 * Tests constructor validation, lifecycle management, subscriber operations,
 * dispatch logic with filtering, and error isolation between subscribers.
 * </p>
 */
@DisplayName("ProfileLogContext Tests")
class ProfileLogContextTest {

    @BeforeEach
    void setUp() {
        ProfileLogRouter.resetInstance();
    }

    @AfterEach
    void tearDown() {
        ProfileLogRouter.resetInstance();
    }

    // ------------------------ Constructor Validation ------------------------

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorTests {

        @Test
        @DisplayName("constructor accepts valid moduleId and profileName")
        void constructor_GivenValidParams_CreatesContext() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");

            assertNotNull(context);
            assertEquals("node", context.getModuleId());
            assertEquals("mainnet", context.getProfileName());
            assertFalse(context.isActive());
        }

        @Test
        @DisplayName("constructor rejects null moduleId")
        void constructor_GivenNullModuleId_ThrowsNPE() {
            NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> new ProfileLogContext(null, "mainnet"));
            assertTrue(ex.getMessage().contains("Module ID"));
        }

        @Test
        @DisplayName("constructor rejects empty moduleId")
        void constructor_GivenEmptyModuleId_ThrowsIAE() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ProfileLogContext("", "mainnet"));
        }

        @Test
        @DisplayName("constructor rejects null profileName")
        void constructor_GivenNullProfileName_ThrowsNPE() {
            NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> new ProfileLogContext("node", null));
            assertTrue(ex.getMessage().contains("Profile name"));
        }

        @Test
        @DisplayName("constructor rejects empty profileName")
        void constructor_GivenEmptyProfileName_ThrowsIAE() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ProfileLogContext("node", ""));
        }

        @SuppressWarnings("deprecation")
        @Test
        @DisplayName("deprecated single-arg constructor sets moduleId to 'default'")
        void deprecatedConstructor_GivenProfileName_SetsDefaultModule() {
            ProfileLogContext context = new ProfileLogContext("testnet");

            assertEquals("default", context.getModuleId());
            assertEquals("testnet", context.getProfileName());
        }

        @Test
        @DisplayName("routing key is created correctly")
        void constructor_GivenValidParams_CreatesCorrectRoutingKey() {
            ProfileLogContext context = new ProfileLogContext("database", "sqlite-profile");

            LogRoutingKey key = context.getRoutingKey();
            assertNotNull(key);
            assertEquals("database", key.getModuleId());
            assertEquals("sqlite-profile", key.getProfileName());
        }

        @Test
        @DisplayName("toString includes module, profile, active state, subscriber count")
        void toString_GivenDefaultContext_ContainsAllFields() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");

            String str = context.toString();
            assertTrue(str.contains("module='node'"));
            assertTrue(str.contains("profile='mainnet'"));
            assertTrue(str.contains("active=false"));
            assertTrue(str.contains("subscribers=0"));
        }
    }

    // ------------------------ Lifecycle: start/close ------------------------

    @Nested
    @DisplayName("Lifecycle management")
    class LifecycleTests {

        @Test
        @DisplayName("start() registers with router and sets active flag")
        void start_GivenInactive_RegistersAndActivates() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");

            context.start();

            assertTrue(context.isActive());
        }

        @Test
        @DisplayName("start() calls router.registerContext(this)")
        void start_GivenFreshRouter_RegistersWithRouter() {
            ProfileLogRouter router = ProfileLogRouter.getInstance();
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");

            context.start();

            assertSame(context, router.getContext(LogRoutingKey.of("node", "mainnet")));
        }

        @Test
        @DisplayName("start() is idempotent")
        void start_GivenAlreadyActive_DoesNotReregister() {
            ProfileLogRouter router = ProfileLogRouter.getInstance();
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");

            context.start();
            context.start();
            context.start();

            assertEquals(1, router.getRegisteredContextCount());
        }

        @Test
        @DisplayName("close() unregisters from router and deactivates")
        void close_GivenActive_UnregistersAndDeactivates() {
            ProfileLogRouter router = ProfileLogRouter.getInstance();
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            context.start();

            context.close();

            assertFalse(context.isActive());
            assertNull(router.getContext(LogRoutingKey.of("node", "mainnet")));
        }

        @Test
        @DisplayName("close() is idempotent")
        void close_GivenInactive_DoesNothing() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");

            assertDoesNotThrow(() -> {
                context.close();
                context.close();
                context.close();
            });
            assertFalse(context.isActive());
        }

        @Test
        @DisplayName("close() can be used via try-with-resources")
        void close_GivenTryWithResources_AutoCloses() {
            ProfileLogRouter router = ProfileLogRouter.getInstance();

            try (ProfileLogContext context = new ProfileLogContext("node", "mainnet")) {
                context.start();
                assertTrue(context.isActive());
            }

            assertNull(router.getContext(LogRoutingKey.of("node", "mainnet")),
                    "Context should be unregistered after close");
        }
    }

    // ------------------------ Subscriber Management ------------------------

    @Nested
    @DisplayName("Subscriber management")
    class SubscriberTests {

        @Test
        @DisplayName("addSubscriber() adds subscriber to list")
        void addSubscriber_GivenValidSubscriber_IncreasesCount() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            LogSubscriber subscriber = new CollectingSubscriber();

            context.addSubscriber(subscriber);

            assertEquals(1, context.getSubscribers().size());
        }

        @Test
        @DisplayName("addSubscriber(null) throws NullPointerException")
        void addSubscriber_GivenNull_ThrowsNPE() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");

            assertThrows(NullPointerException.class, () -> context.addSubscriber(null));
        }

        @Test
        @DisplayName("removeSubscriber() removes and calls dispose()")
        void removeSubscriber_GivenExisting_RemovesAndDisposes() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            DisposableSubscriber subscriber = new DisposableSubscriber();
            context.addSubscriber(subscriber);

            boolean removed = context.removeSubscriber(subscriber);

            assertTrue(removed);
            assertEquals(0, context.getSubscribers().size());
            assertTrue(subscriber.isDisposed());
        }

        @Test
        @DisplayName("removeSubscriber() returns false for non-existent subscriber")
        void removeSubscriber_GivenNonExistent_ReturnsFalse() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            LogSubscriber other = new CollectingSubscriber();

            boolean removed = context.removeSubscriber(other);

            assertFalse(removed);
        }

        @Test
        @DisplayName("getSubscribers() returns unmodifiable list")
        void getSubscribers_GivenModified_ThrowsUnsupportedOperationException() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            context.addSubscriber(new CollectingSubscriber());

            List<LogSubscriber> subs = context.getSubscribers();

            assertThrows(UnsupportedOperationException.class, () -> subs.add(new CollectingSubscriber()));
        }

        @Test
        @DisplayName("getSubscribers() returns snapshot (not live view)")
        void getSubscribers_GivenChangedInternally_ReturnsSnapshot() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            CollectingSubscriber sub1 = new CollectingSubscriber();
            CollectingSubscriber sub2 = new CollectingSubscriber();
            context.addSubscriber(sub1);

            List<LogSubscriber> snapshot = context.getSubscribers();
            assertEquals(1, snapshot.size());

            context.addSubscriber(sub2);

            // Snapshot should still show 1
            assertEquals(1, snapshot.size());
            // Live context should show 2
            assertEquals(2, context.getSubscribers().size());
        }

        @Test
        @DisplayName("multiple subscribers can be added")
        void addMultipleSubscribers_GivenValid_IncreasesCount() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");

            for (int i = 0; i < 5; i++) {
                context.addSubscriber(new CollectingSubscriber());
            }

            assertEquals(5, context.getSubscribers().size());
        }
    }

    // ------------------------ Dispatch Logic ------------------------

    @Nested
    @DisplayName("Dispatch logic")
    class DispatchTests {

        @Test
        @DisplayName("dispatch() delivers event to all subscribers")
        void dispatch_GivenMultipleSubscribers_AllReceiveEvent() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            CollectingSubscriber sub1 = new CollectingSubscriber();
            CollectingSubscriber sub2 = new CollectingSubscriber();
            context.addSubscriber(sub1);
            context.addSubscriber(sub2);

            LogEvent event = LogEvent.fromText("hello world");
            context.dispatch(event);

            assertEquals(1, sub1.getReceived().size());
            assertEquals(1, sub2.getReceived().size());
            assertSame(event, sub1.getReceived().get(0));
        }

        @Test
        @DisplayName("dispatch() respects subscriber filter (null = accept all)")
        void dispatch_GivenNullFilter_AcceptAll() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            CollectingSubscriber sub = new CollectingSubscriber(null);
            context.addSubscriber(sub);

            context.dispatch(LogEvent.fromText("any message"));

            assertEquals(1, sub.getReceived().size());
        }

        @Test
        @DisplayName("dispatch() respects subscriber filter (matches = true)")
        void dispatch_GivenFilterMatches_DeliversEvent() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            CollectingSubscriber sub = new CollectingSubscriber(event -> event.getMessage().contains("error"));
            context.addSubscriber(sub);

            context.dispatch(LogEvent.fromText("critical error occurred"));

            assertEquals(1, sub.getReceived().size());
        }

        @Test
        @DisplayName("dispatch() respects subscriber filter (matches = false)")
        void dispatch_GivenFilterDoesNotMatch_DropsEvent() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            CollectingSubscriber sub = new CollectingSubscriber(event -> event.getMessage().contains("error"));
            context.addSubscriber(sub);

            context.dispatch(LogEvent.fromText("all is well"));

            assertEquals(0, sub.getReceived().size());
        }

        @Test
        @DisplayName("dispatch() with mixed filters isolates events per subscriber")
        void dispatch_GivenMixedFilters_EachGetsOwnEvents() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            CollectingSubscriber errorOnly = new CollectingSubscriber(e -> e.getLevel() == LogLevel.ERROR);
            CollectingSubscriber allEvents = new CollectingSubscriber(null);
            context.addSubscriber(errorOnly);
            context.addSubscriber(allEvents);

            LogEvent errorEvent = new LogEvent.Builder()
                    .timestamp(System.currentTimeMillis())
                    .level(LogLevel.ERROR)
                    .message("error msg")
                    .threadName("test")
                    .build();
            LogEvent infoEvent = new LogEvent.Builder()
                    .timestamp(System.currentTimeMillis())
                    .level(LogLevel.INFO)
                    .message("info msg")
                    .threadName("test")
                    .build();

            context.dispatch(errorEvent);
            context.dispatch(infoEvent);

            assertEquals(1, errorOnly.getReceived().size());
            assertEquals(2, allEvents.getReceived().size());
        }

        @Test
        @DisplayName("dispatch() isolates subscriber errors")
        void dispatch_GivenOneSubscriberThrows_OthersStillReceive() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            CollectingSubscriber goodSub = new CollectingSubscriber();
            LogSubscriber badSub = new LogSubscriber() {
                @Override public void onLogEvent(LogEvent event) {
                    throw new RuntimeException("Subscriber error!");
                }
                @Override public LogFilter getFilter() { return null; }
            };
            context.addSubscriber(badSub);
            context.addSubscriber(goodSub);

            // Should not throw even though one subscriber fails
            assertDoesNotThrow(() -> context.dispatch(LogEvent.fromText("test")));

            // Good subscriber should still receive the event
            assertEquals(1, goodSub.getReceived().size());
        }

        @Test
        @DisplayName("dispatch() with no subscribers does nothing")
        void dispatch_GivenNoSubscribers_NoError() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");

            assertDoesNotThrow(() -> context.dispatch(LogEvent.fromText("test")));
        }

        @Test
        @DisplayName("dispatch() with duplicate subscribers delivers event to each")
        void dispatch_GivenDuplicateSubscribers_EachReceivesOnce() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            CollectingSubscriber sub = new CollectingSubscriber();
            context.addSubscriber(sub);
            context.addSubscriber(sub);

            context.dispatch(LogEvent.fromText("test"));

            // Same subscriber added twice receives twice
            assertEquals(2, sub.getReceived().size());
        }
    }

    // ------------------------ dispatchText ------------------------

    @Nested
    @DisplayName("dispatchText method")
    class DispatchTextTests {

        @Test
        @DisplayName("dispatchText() creates event from text string")
        void dispatchText_GivenValidText_DeliversToSubscribers() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            CollectingSubscriber sub = new CollectingSubscriber();
            context.addSubscriber(sub);

            context.dispatchText("bootstrap log line");

            assertEquals(1, sub.getReceived().size());
            assertEquals("bootstrap log line", sub.getReceived().get(0).getMessage());
        }

        @Test
        @DisplayName("dispatchText(null) is a no-op")
        void dispatchText_GivenNull_NoError() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            CollectingSubscriber sub = new CollectingSubscriber();
            context.addSubscriber(sub);

            context.dispatchText(null);

            assertEquals(0, sub.getReceived().size());
        }

        @Test
        @DisplayName("dispatchText(empty string) is a no-op")
        void dispatchText_GivenEmpty_NoError() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            CollectingSubscriber sub = new CollectingSubscriber();
            context.addSubscriber(sub);

            context.dispatchText("");

            assertEquals(0, sub.getReceived().size());
        }
    }

    // ------------------------ Close Disposes Subscribers ------------------------

    @Nested
    @DisplayName("Close disposes subscribers")
    class CloseDisposalTests {

        @Test
        @DisplayName("close() disposes all subscribers")
        void close_GivenActiveWithSubscribers_DisposesAll() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            DisposableSubscriber sub1 = new DisposableSubscriber();
            DisposableSubscriber sub2 = new DisposableSubscriber();
            context.addSubscriber(sub1);
            context.addSubscriber(sub2);
            context.start();

            context.close();

            assertTrue(sub1.isDisposed());
            assertTrue(sub2.isDisposed());
            assertEquals(0, context.getSubscribers().size());
        }

        @Test
        @DisplayName("close() isolates dispose errors")
        void close_GivenOneSubscriberThrowsOnDispose_OthersStillDisposed() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            DisposableSubscriber good = new DisposableSubscriber();
            LogSubscriber bad = new LogSubscriber() {
                @Override public void onLogEvent(LogEvent event) {}
                @Override public LogFilter getFilter() { return null; }
                @Override public void dispose() {
                    throw new RuntimeException("Dispose error!");
                }
            };
            context.addSubscriber(bad);
            context.addSubscriber(good);
            context.start();

            assertDoesNotThrow(() -> context.close());
            assertTrue(good.isDisposed());
        }
    }

    // ------------------------ Test Helpers ------------------------

    private static class CollectingSubscriber implements LogSubscriber {
        private final List<LogEvent> received = Collections.synchronizedList(new ArrayList<>());
        private final LogFilter filter;

        CollectingSubscriber() {
            this(null);
        }

        CollectingSubscriber(LogFilter filter) {
            this.filter = filter;
        }

        @Override
        public void onLogEvent(LogEvent event) {
            received.add(event);
        }

        @Override
        public LogFilter getFilter() {
            return filter;
        }

        public List<LogEvent> getReceived() {
            return received;
        }
    }

    private static class DisposableSubscriber implements LogSubscriber {
        private boolean disposed = false;

        @Override
        public void onLogEvent(LogEvent event) {}

        @Override
        public LogFilter getFilter() {
            return null;
        }

        @Override
        public void dispose() {
            disposed = true;
        }

        public boolean isDisposed() {
            return disposed;
        }
    }
}