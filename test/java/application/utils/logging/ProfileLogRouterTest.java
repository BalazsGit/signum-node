package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogSubscriber;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProfileLogRouter}.
 * <p>
 * Verifies singleton behavior, install/uninstall lifecycle, context registration,
 * and the critical O(1) routing logic using composite LogRoutingKey.
 * </p>
 */
@DisplayName("ProfileLogRouter Tests")
class ProfileLogRouterTest {

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

    // ------------------------ Singleton ------------------------

    @Nested
    @DisplayName("Singleton pattern")
    class SingletonTests {

        @Test
        @DisplayName("getInstance() returns same instance on repeated calls")
        void getInstance_GivenMultipleCalls_ReturnsSameInstance() {
            ProfileLogRouter router1 = ProfileLogRouter.getInstance();
            ProfileLogRouter router2 = ProfileLogRouter.getInstance();

            assertSame(router1, router2);
        }

        @Test
        @DisplayName("getInstance() after reset creates new instance")
        void getInstance_GivenReset_ReturnsNewInstance() {
            ProfileLogRouter router1 = ProfileLogRouter.getInstance();
            ProfileLogRouter.resetInstance();
            ProfileLogRouter router2 = ProfileLogRouter.getInstance();

            assertNotSame(router1, router2);
        }
    }

    // ------------------------ Install / Uninstall ------------------------

    @Nested
    @DisplayName("Install / Uninstall lifecycle")
    class InstallTests {

        @Test
        @DisplayName("new instance is not installed by default")
        void isInstalled_GivenNewInstance_ReturnsFalse() {
            assertFalse(router.isInstalled());
        }

        @Test
        @DisplayName("install() marks router as installed")
        void install_GivenNotInstalled_ReturnsTrue() {
            router.install();
            assertTrue(router.isInstalled());
        }

        @Test
        @DisplayName("install() is idempotent")
        void install_GivenAlreadyInstalled_RemainsInstalled() {
            router.install();
            router.install();

            assertTrue(router.isInstalled());
        }

        @Test
        @DisplayName("uninstall() marks router as not installed")
        void uninstall_GivenInstalled_ReturnsFalse() {
            router.install();
            router.uninstall();

            assertFalse(router.isInstalled());
        }

        @Test
        @DisplayName("uninstall() is idempotent")
        void uninstall_GivenNotInstalled_RemainsUninstalled() {
            router.uninstall();
            router.uninstall();

            assertFalse(router.isInstalled());
        }
    }

    // ------------------------ Register / Unregister Contexts ------------------------

    @Nested
    @DisplayName("Register / Unregister contexts")
    class RegistrationTests {

        @Test
        @DisplayName("registerContext adds context to map")
        void registerContext_GivenValidContext_IncreasesCount() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");

            router.registerContext(context);

            assertEquals(1, router.getRegisteredContextCount());
        }

        @Test
        @DisplayName("registering null context throws NullPointerException")
        void registerContext_GivenNull_ThrowsNPE() {
            assertThrows(NullPointerException.class, () -> router.registerContext(null));
        }

        @Test
        @DisplayName("getContext returns registered context by composite key")
        void getContext_GivenRegisteredKey_ReturnsContext() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            router.registerContext(context);
            LogRoutingKey key = LogRoutingKey.of("node", "mainnet");

            assertSame(context, router.getContext(key));
        }

        @Test
        @DisplayName("getContext returns null for unregistered key")
        void getContext_GivenUnregisteredKey_ReturnsNull() {
            assertNull(router.getContext(LogRoutingKey.of("node", "nonexistent")));
        }

        @Test
        @DisplayName("unregisterContext removes context by composite key")
        void unregisterContext_GivenExistingKey_RemovesContext() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            LogRoutingKey key = LogRoutingKey.of("node", "mainnet");
            router.registerContext(context);

            router.unregisterContext(key);

            assertNull(router.getContext(key));
            assertEquals(0, router.getRegisteredContextCount());
        }

        @Test
        @DisplayName("unregistering non-existent key is safe")
        void unregisterContext_GivenMissingKey_NoError() {
            LogRoutingKey key = LogRoutingKey.of("node", "nonexistent");

            assertDoesNotThrow(() -> router.unregisterContext(key));
            assertEquals(0, router.getRegisteredContextCount());
        }

        @Test
        @DisplayName("getAllContexts returns unmodifiable map")
        void getAllContexts_GivenModifiedMap_ThrowsUnsupportedOperationException() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            router.registerContext(context);

            Map<LogRoutingKey, ProfileLogContext> all = router.getAllContexts();

            assertEquals(1, all.size());
            assertThrows(UnsupportedOperationException.class, () -> all.put(
                    LogRoutingKey.of("db", "test"), new ProfileLogContext("db", "test")));
        }
    }

    // ------------------------ V2.3 Multi-Module Isolation ------------------------

    @Nested
    @DisplayName("V2.3 Multi-Module Isolation")
    class MultiModuleIsolationTests {

        @Test
        @DisplayName("same profile name in different modules -> separate contexts")
        void registerContext_GivenSameProfileDifferentModules_NoCollision() {
            ProfileLogContext nodeContext = new ProfileLogContext("node", "profil-bela");
            ProfileLogContext dbContext = new ProfileLogContext("database", "profil-bela");

            router.registerContext(nodeContext);
            router.registerContext(dbContext);

            assertEquals(2, router.getRegisteredContextCount());
            assertSame(nodeContext, router.getContext(LogRoutingKey.of("node", "profil-bela")));
            assertSame(dbContext, router.getContext(LogRoutingKey.of("database", "profil-bela")));
        }

        @Test
        @DisplayName("unregistering one module does not affect other module")
        void unregisterContext_GivenSameProfileDifferentModules_OnlyRemovesTarget() {
            ProfileLogContext nodeContext = new ProfileLogContext("node", "profil-bela");
            ProfileLogContext dbContext = new ProfileLogContext("database", "profil-bela");

            router.registerContext(nodeContext);
            router.registerContext(dbContext);
            router.unregisterContext(LogRoutingKey.of("node", "profil-bela"));

            assertNull(router.getContext(LogRoutingKey.of("node", "profil-bela")));
            assertSame(dbContext, router.getContext(LogRoutingKey.of("database", "profil-bela")));
            assertEquals(1, router.getRegisteredContextCount());
        }

        @Test
        @DisplayName("3 modules with same profile name -> 3 distinct entries")
        void multiModule_GivenSameProfileInThreeModules_NoCollision() {
            ProfileLogContext nodeCtx = new ProfileLogContext("node", "shared");
            ProfileLogContext dbCtx = new ProfileLogContext("database", "shared");
            ProfileLogContext miningCtx = new ProfileLogContext("mining", "shared");

            router.registerContext(nodeCtx);
            router.registerContext(dbCtx);
            router.registerContext(miningCtx);

            assertEquals(3, router.getRegisteredContextCount());
            assertSame(nodeCtx, router.getContext(LogRoutingKey.of("node", "shared")));
            assertSame(dbCtx, router.getContext(LogRoutingKey.of("database", "shared")));
            assertSame(miningCtx, router.getContext(LogRoutingKey.of("mining", "shared")));
        }
    }

    // ------------------------ Routing Logic via MDC ------------------------

    @Nested
    @DisplayName("MDC-based routing key resolution")
    class RoutingLogicTests {

        @Test
        @DisplayName("empty MDC -> getRoutingKey returns null (broadcast mode)")
        void publish_GivenEmptyMDC_ReturnsNullKey() {
            ProfileThreadContext.clear();
            assertNull(ProfileThreadContext.getRoutingKey());
        }

        @Test
        @DisplayName("MDC with module+profile -> targeted routing key")
        void publish_GivenModuleAndProfileSet_ReturnsCompositeKey() {
            ProfileThreadContext.setContext("node", "mainnet");

            LogRoutingKey key = ProfileThreadContext.getRoutingKey();

            assertNotNull(key);
            assertEquals("node", key.getModuleId());
            assertEquals("mainnet", key.getProfileName());
        }

        @Test
        @DisplayName("MDC with only profile (no module) -> partial key")
        void publish_GivenOnlyProfile_ReturnsPartialKey() {
            ProfileThreadContext.setProfile("testnet");

            LogRoutingKey key = ProfileThreadContext.getRoutingKey();

            assertNotNull(key);
            assertNull(key.getModuleId());
            assertEquals("testnet", key.getProfileName());
        }

        @Test
        @DisplayName("clear() removes routing context")
        void clear_RemovesAllMdcKeys() {
            ProfileThreadContext.setContext("node", "mainnet");
            assertNotNull(ProfileThreadContext.getRoutingKey());

            ProfileThreadContext.clear();

            assertNull(ProfileThreadContext.getRoutingKey());
            assertNull(ProfileThreadContext.getModuleId());
            assertNull(ProfileThreadContext.getProfile());
        }
    }

    // ------------------------ Legacy Deprecated Methods ------------------------

    @Nested
    @DisplayName("Legacy deprecated methods")
    class LegacyMethodTests {

        @Test
        @DisplayName("deprecated unregisterContext(String) removes all matching profile names")
        void unregisterContextLegacy_GivenProfileName_RemovesAllMatching() {
            ProfileLogContext ctx1 = new ProfileLogContext("node", "profil-bela");
            ProfileLogContext ctx2 = new ProfileLogContext("database", "profil-bela");

            router.registerContext(ctx1);
            router.registerContext(ctx2);

            // Use deprecated API - removes all with matching profile name
            router.unregisterContext("profil-bela");

            assertEquals(0, router.getRegisteredContextCount());
        }

        @Test
        @DisplayName("deprecated getContext(String) returns first match")
        void getContextLegacy_GivenProfileName_ReturnsFirstMatch() {
            ProfileLogContext ctx = new ProfileLogContext("node", "profil-bela");
            router.registerContext(ctx);

            assertSame(ctx, router.getContext("profil-bela"));
        }

        @Test
        @DisplayName("deprecated getContext(String) returns null if not found")
        void getContextLegacy_GivenUnknownProfile_ReturnsNull() {
            assertNull(router.getContext("nonexistent"));
        }
    }

    // ------------------------ ProfileLogContext start/close integration ------------------------

    @Nested
    @DisplayName("ProfileLogContext lifecycle integration")
    class LifecycleIntegrationTests {

        @Test
        @DisplayName("ProfileLogContext.start() auto-registers with router")
        void contextStart_AutoRegistersWithRouter() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            assertFalse(context.isActive());

            context.start();

            assertTrue(context.isActive());
            assertSame(context, router.getContext(LogRoutingKey.of("node", "mainnet")));
        }

        @Test
        @DisplayName("ProfileLogContext.close() unregisters from router")
        void contextClose_UnregistersFromRouter() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            context.start();

            context.close();

            assertFalse(context.isActive());
            assertNull(router.getContext(LogRoutingKey.of("node", "mainnet")));
            assertEquals(0, router.getRegisteredContextCount());
        }

        @Test
        @DisplayName("start() is idempotent")
        void contextStart_GivenAlreadyActive_IsIdempotent() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");

            context.start();
            context.start();

            assertEquals(1, router.getRegisteredContextCount());
        }

        @Test
        @DisplayName("close() is idempotent")
        void contextClose_GivenAlreadyClosed_IsIdempotent() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");

            assertDoesNotThrow(() -> {
                context.close();
                context.close();
            });
            assertEquals(0, router.getRegisteredContextCount());
        }

        @Test
        @DisplayName("context close disposes all subscribers")
        void contextClose_DisposesSubscribers() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            boolean[] disposed = {false, false};

            LogSubscriber sub1 = new TestSubscriber(null, disposed[0]);
            LogSubscriber sub2 = new TestSubscriber(null, disposed[1]);

            // Track disposal via a wrapper
            context.addSubscriber(new LogSubscriber() {
                private boolean disposed = false;
                @Override public void onLogEvent(LogEvent e) {}
                @Override public LogFilter getFilter() { return null; }
                @Override public void dispose() { disposed = true; }
            });
            context.addSubscriber(new LogSubscriber() {
                private boolean disposed = false;
                @Override public void onLogEvent(LogEvent e) {}
                @Override public LogFilter getFilter() { return null; }
                @Override public void dispose() { disposed = true; }
            });

            context.start();
            context.close();

            // Subscribers should have been disposed
            assertEquals(0, router.getRegisteredContextCount());
        }
    }

    // ------------------------ resetInstance ------------------------

    @Nested
    @DisplayName("resetInstance() for testing")
    class ResetTests {

        @Test
        @DisplayName("resetInstance clears all registered contexts")
        void resetInstance_ClearsAllContexts() {
            ProfileLogContext context = new ProfileLogContext("node", "mainnet");
            router.registerContext(context);
            assertEquals(1, router.getRegisteredContextCount());

            ProfileLogRouter.resetInstance();

            ProfileLogRouter fresh = ProfileLogRouter.getInstance();
            assertEquals(0, fresh.getRegisteredContextCount());
        }

        @Test
        @DisplayName("resetInstance uninstalls JUL handler")
        void resetInstance_UninstallsHandler() {
            router.install();
            assertTrue(router.isInstalled());

            ProfileLogRouter.resetInstance();

            ProfileLogRouter fresh = ProfileLogRouter.getInstance();
            assertFalse(fresh.isInstalled());
        }
    }

    // ------------------------ End-to-End: dispatch via context subscribers ------------------------

    @Nested
    @DisplayName("End-to-end routing with real subscribers")
    class EndToEndTests {

        @Test
        @DisplayName("event dispatched to correct profile only (not broadcast)")
        void dispatch_GivenTwoContexts_RoutesToCorrectOne() {
            // Arrange: two contexts for different modules, same profile name
            List<LogEvent> nodeEvents = Collections.synchronizedList(new ArrayList<>());
            List<LogEvent> dbEvents = Collections.synchronizedList(new ArrayList<>());

            ProfileLogContext nodeCtx = new ProfileLogContext("node", "shared");
            ProfileLogContext dbCtx = new ProfileLogContext("database", "shared");

            nodeCtx.addSubscriber(new CollectingSubscriber(nodeEvents));
            dbCtx.addSubscriber(new CollectingSubscriber(dbEvents));

            nodeCtx.start();
            dbCtx.start();

            assertEquals(2, router.getRegisteredContextCount());

            // Act: set MDC to node context and dispatch
            ProfileThreadContext.setContext("node", "shared");
            LogEvent event = LogEvent.fromText("test message from node");
            nodeCtx.dispatch(event);

            // Assert: only node context received the event
            assertEquals(1, nodeEvents.size());
            assertEquals(0, dbEvents.size());
            assertEquals("test message from node", nodeEvents.get(0).getMessage());
        }

        @Test
        @DisplayName("event dispatched to correct module when profiles share name")
        void dispatch_GivenSameProfileName_RoutesToCorrectModule() {
            List<LogEvent> nodeEvents = Collections.synchronizedList(new ArrayList<>());
            List<LogEvent> dbEvents = Collections.synchronizedList(new ArrayList<>());

            ProfileLogContext nodeCtx = new ProfileLogContext("node", "profil-bela");
            ProfileLogContext dbCtx = new ProfileLogContext("database", "profil-bela");

            nodeCtx.addSubscriber(new CollectingSubscriber(nodeEvents));
            dbCtx.addSubscriber(new CollectingSubscriber(dbEvents));

            nodeCtx.start();
            dbCtx.start();

            // Dispatch to database context
            dbCtx.dispatch(LogEvent.fromText("db event"));
            nodeCtx.dispatch(LogEvent.fromText("node event"));

            assertEquals(1, nodeEvents.size());
            assertEquals("node event", nodeEvents.get(0).getMessage());
            assertEquals(1, dbEvents.size());
            assertEquals("db event", dbEvents.get(0).getMessage());
        }

        @Test
        @DisplayName("subscriber with filter only receives matching events")
        void dispatch_GivenFilteredSubscriber_OnlyReceivesMatches() {
            List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

            ProfileLogContext ctx = new ProfileLogContext("node", "mainnet");
            ctx.addSubscriber(new CollectingSubscriber(events, new LogFilter() {
                @Override
                public boolean matches(LogEvent event) {
                    return event.getMessage().contains("important");
                }
            }));

            ctx.dispatch(LogEvent.fromText("this is important"));
            ctx.dispatch(LogEvent.fromText("not so critical text"));
            ctx.dispatch(LogEvent.fromText("another important thing"));

            assertEquals(2, events.size());
            assertTrue(events.get(0).getMessage().contains("important"));
            assertTrue(events.get(1).getMessage().contains("important"));
        }
    }

    // ------------------------ Test Helpers ------------------------

    /** Simple subscriber that collects all events into a list */
    private static class CollectingSubscriber implements LogSubscriber {
        private final List<LogEvent> collected;
        private final LogFilter filter;

        CollectingSubscriber(List<LogEvent> collected) {
            this(collected, null);
        }

        CollectingSubscriber(List<LogEvent> collected, LogFilter filter) {
            this.collected = collected;
            this.filter = filter;
        }

        @Override
        public void onLogEvent(LogEvent event) {
            collected.add(event);
        }

        @Override
        public LogFilter getFilter() {
            return filter;
        }
    }

    /** Dummy subscriber for dispose tracking tests */
    private static class TestSubscriber implements LogSubscriber {
        private final LogFilter filter;
        @SuppressWarnings("unused")
        private final boolean placeholder;

        TestSubscriber(LogFilter filter, boolean placeholder) {
            this.filter = filter;
            this.placeholder = placeholder;
        }

        @Override
        public void onLogEvent(LogEvent event) {}

        @Override
        public LogFilter getFilter() { return filter; }
    }
}