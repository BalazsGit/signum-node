package application.utils.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProfileThreadContext}.
 */
@DisplayName("ProfileThreadContext Tests")
class ProfileThreadContextTest {

    @BeforeEach
    void setUp() {
        ProfileThreadContext.clear();
    }

    @AfterEach
    void tearDown() {
        ProfileThreadContext.clear();
    }

    // ------------------------ setContext / getModuleId / getProfile ------------------------

    @Nested
    @DisplayName("setContext and getters")
    class SetContextTests {

        @Test
        @DisplayName("setContext() sets both moduleId and profileName")
        void setContext_GivenValidParams_SetsBothValues() {
            ProfileThreadContext.setContext("node", "mainnet");
            assertEquals("node", ProfileThreadContext.getModuleId());
            assertEquals("mainnet", ProfileThreadContext.getProfile());
        }

        @Test
        @DisplayName("setContext(null, profile) sets only profile")
        void setContext_GivenNullModule_SetsProfileOnly() {
            ProfileThreadContext.setContext(null, "testnet");
            assertNull(ProfileThreadContext.getModuleId());
            assertEquals("testnet", ProfileThreadContext.getProfile());
        }

        @Test
        @DisplayName("setContext(module, null) sets only module")
        void setContext_GivenNullProfile_SetsModuleOnly() {
            ProfileThreadContext.setContext("database", null);
            assertEquals("database", ProfileThreadContext.getModuleId());
            assertNull(ProfileThreadContext.getProfile());
        }

        @Test
        @DisplayName("setContext(null, null) clears both")
        void setContext_GivenBothNull_ClearsAll() {
            ProfileThreadContext.setContext("node", "mainnet");
            ProfileThreadContext.setContext(null, null);
            assertNull(ProfileThreadContext.getModuleId());
            assertNull(ProfileThreadContext.getProfile());
        }

        @Test
        @DisplayName("setContext with empty strings clears values")
        void setContext_GivenEmptyStrings_ClearsValues() {
            ProfileThreadContext.setContext("node", "mainnet");
            ProfileThreadContext.setContext("", "");
            assertNull(ProfileThreadContext.getModuleId());
            assertNull(ProfileThreadContext.getProfile());
        }

        @Test
        @DisplayName("setContext overwrites previous values")
        void setContext_GivenExistingValues_Overwrites() {
            ProfileThreadContext.setContext("node", "mainnet");
            ProfileThreadContext.setContext("database", "sqlite");
            assertEquals("database", ProfileThreadContext.getModuleId());
            assertEquals("sqlite", ProfileThreadContext.getProfile());
        }

        @Test
        @DisplayName("fresh context has null values")
        void clear_GivenEmptyMDC_ReturnsNulls() {
            assertNull(ProfileThreadContext.getModuleId());
            assertNull(ProfileThreadContext.getProfile());
        }
    }

    // ------------------------ getRoutingKey ------------------------

    @Nested
    @DisplayName("getRoutingKey")
    class RoutingKeyTests {

        @Test
        @DisplayName("getRoutingKey() with no context returns null")
        void getRoutingKey_GivenEmptyContext_ReturnsNull() {
            assertNull(ProfileThreadContext.getRoutingKey());
        }

        @Test
        @DisplayName("getRoutingKey() with module+profile returns composite key")
        void getRoutingKey_GivenFullContext_ReturnsCompositeKey() {
            ProfileThreadContext.setContext("node", "mainnet");
            LogRoutingKey key = ProfileThreadContext.getRoutingKey();
            assertNotNull(key);
            assertEquals("node", key.getModuleId());
            assertEquals("mainnet", key.getProfileName());
        }

        @Test
        @DisplayName("getRoutingKey() with only profile returns partial key")
        void getRoutingKey_GivenOnlyProfile_ReturnsPartialKey() {
            ProfileThreadContext.setContext(null, "testnet");
            LogRoutingKey key = ProfileThreadContext.getRoutingKey();
            assertNotNull(key);
            assertNull(key.getModuleId());
            assertEquals("testnet", key.getProfileName());
        }

        @Test
        @DisplayName("getRoutingKey() with only module returns partial key")
        void getRoutingKey_GivenOnlyModule_ReturnsPartialKey() {
            ProfileThreadContext.setContext("database", null);
            LogRoutingKey key = ProfileThreadContext.getRoutingKey();
            assertNotNull(key);
            assertEquals("database", key.getModuleId());
            assertNull(key.getProfileName());
        }
    }

    // ------------------------ clear ------------------------

    @Nested
    @DisplayName("clear()")
    class ClearTests {

        @Test
        @DisplayName("clear() removes all context")
        void clear_GivenSetContext_RemovesAllKeys() {
            ProfileThreadContext.setContext("node", "mainnet");
            ProfileThreadContext.clear();
            assertNull(ProfileThreadContext.getRoutingKey());
            assertNull(ProfileThreadContext.getModuleId());
            assertNull(ProfileThreadContext.getProfile());
        }

        @Test
        @DisplayName("clear() is idempotent")
        void clear_GivenAlreadyClear_DoesNothing() {
            assertDoesNotThrow(() -> {
                ProfileThreadContext.clear();
                ProfileThreadContext.clear();
            });
        }
    }

    // ------------------------ wrap(Runnable) ------------------------

    @Nested
    @DisplayName("wrap(Runnable)")
    class WrapRunnableTests {

        @Test
        @DisplayName("wrap() sets context during execution")
        void wrap_GivenRunnable_ContextSetDuringExecution() {
            LogRoutingKey[] capturedKey = new LogRoutingKey[1];
            Runnable wrapped = ProfileThreadContext.wrap(() -> {
                capturedKey[0] = ProfileThreadContext.getRoutingKey();
            }, "node", "mainnet");
            wrapped.run();
            assertNotNull(capturedKey[0]);
            assertEquals("node", capturedKey[0].getModuleId());
            assertEquals("mainnet", capturedKey[0].getProfileName());
        }

        @Test
        @DisplayName("wrap() restores previous context after execution")
        void wrap_GivenExistingContext_RestoresPrevious() {
            ProfileThreadContext.setContext("database", "sqlite");
            Runnable wrapped = ProfileThreadContext.wrap(() -> {
                assertEquals("node", ProfileThreadContext.getModuleId());
            }, "node", "mainnet");
            wrapped.run();
            assertEquals("database", ProfileThreadContext.getModuleId());
            assertEquals("sqlite", ProfileThreadContext.getProfile());
        }

        @Test
        @DisplayName("wrap() restores empty context when none existed before")
        void wrap_GivenNoPreviousContext_ClearsAfter() {
            Runnable wrapped = ProfileThreadContext.wrap(() -> {
                assertEquals("node", ProfileThreadContext.getModuleId());
            }, "node", "mainnet");
            wrapped.run();
            assertNull(ProfileThreadContext.getModuleId());
            assertNull(ProfileThreadContext.getProfile());
        }

        @Test
        @DisplayName("wrap() restores context even when task throws")
        void wrap_GivenTaskThrows_RestoresContextInFinally() {
            ProfileThreadContext.setContext("database", "sqlite");
            Runnable inner = () -> { throw new RuntimeException("task failure"); };
            Runnable wrapped = ProfileThreadContext.wrap(inner, "node", "mainnet");
            assertThrows(RuntimeException.class, () -> wrapped.run());
            assertEquals("database", ProfileThreadContext.getModuleId());
            assertEquals("sqlite", ProfileThreadContext.getProfile());
        }
    }

    // ------------------------ wrap(Callable) ------------------------

    @Nested
    @DisplayName("wrap(Callable)")
    class WrapCallableTests {

        @Test
        @DisplayName("wrap() sets context and returns result")
        void wrap_GivenCallable_ReturnsResultWithContext() throws Exception {
            Callable<String> wrapped = ProfileThreadContext.wrap(() -> {
                assertEquals("node", ProfileThreadContext.getModuleId());
                return "result";
            }, "node", "mainnet");
            String result = wrapped.call();
            assertEquals("result", result);
        }

        @Test
        @DisplayName("wrap() restores previous context after callable")
        void wrap_GivenExistingContext_RestoresAfterCall() throws Exception {
            ProfileThreadContext.setContext("database", "sqlite");
            Callable<Integer> wrapped = ProfileThreadContext.wrap(() -> {
                assertEquals("node", ProfileThreadContext.getModuleId());
                return 42;
            }, "node", "mainnet");
            wrapped.call();
            assertEquals("database", ProfileThreadContext.getModuleId());
            assertEquals("sqlite", ProfileThreadContext.getProfile());
        }

        @Test
        @DisplayName("wrap() propagates exception from callable")
        void wrap_GivenCallableThrows_PropagatesException() throws Exception {
            Callable<String> inner = () -> { throw new IllegalStateException("callable error"); };
            Callable<String> wrapped = ProfileThreadContext.wrap(inner, "node", "mainnet");
            assertThrows(IllegalStateException.class, () -> wrapped.call());
        }

        @Test
        @DisplayName("wrap() restores context even when callable throws")
        void wrap_GivenCallableThrows_RestoresContext() throws Exception {
            ProfileThreadContext.setContext("database", "sqlite");
            Callable<String> inner = () -> { throw new RuntimeException("boom"); };
            Callable<String> wrapped = ProfileThreadContext.wrap(inner, "node", "mainnet");
            assertThrows(RuntimeException.class, () -> wrapped.call());
            assertEquals("database", ProfileThreadContext.getModuleId());
        }

        @Test
        @DisplayName("wrap() works with ExecutorService in separate thread")
        void wrap_GivenExecutorService_ContextIsolatedPerThread() throws InterruptedException, ExecutionException {
            ProfileThreadContext.setContext("main", "main-thread");
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Callable<String> task = ProfileThreadContext.wrap(() -> {
                    assertEquals("node", ProfileThreadContext.getModuleId());
                    assertEquals("mainnet", ProfileThreadContext.getProfile());
                    return "done";
                }, "node", "mainnet");
                Future<String> future = executor.submit(task);
                assertEquals("done", future.get());
            } finally {
                executor.shutdown();
            }
            assertEquals("main", ProfileThreadContext.getModuleId());
            assertEquals("main-thread", ProfileThreadContext.getProfile());
        }
    }

    // ------------------------ Legacy API ------------------------

    @Nested
    @DisplayName("Legacy API (deprecated)")
    class LegacyApiTests {

        @SuppressWarnings("deprecation")
        @Test
        @DisplayName("setProfile() sets profile only")
        void setProfile_GivenProfileName_SetsProfileOnly() {
            ProfileThreadContext.setProfile("testnet");
            assertNull(ProfileThreadContext.getModuleId());
            assertEquals("testnet", ProfileThreadContext.getProfile());
        }

        @SuppressWarnings("deprecation")
        @Test
        @DisplayName("wrap(Runnable, profileName) works")
        void wrapLegacy_GivenRunnable_SetsProfileOnly() {
            Runnable inner = () -> {
                assertNull(ProfileThreadContext.getModuleId());
                assertEquals("testnet", ProfileThreadContext.getProfile());
            };
            Runnable wrapped = ProfileThreadContext.wrap(inner, "testnet");
            wrapped.run();
            assertNull(ProfileThreadContext.getModuleId());
            assertNull(ProfileThreadContext.getProfile());
        }

        @SuppressWarnings("deprecation")
        @Test
        @DisplayName("wrap(Callable, profileName) works")
        void wrapLegacyCallable_GivenProfileName_Works() throws Exception {
            Callable<String> inner = () -> {
                assertEquals("testnet", ProfileThreadContext.getProfile());
                return "ok";
            };
            Callable<String> wrapped = ProfileThreadContext.wrap(inner, "testnet");
            String result = wrapped.call();
            assertEquals("ok", result);
        }
    }

    // ------------------------ MDC Key Constants ------------------------

    @Nested
    @DisplayName("MDC Key constants")
    class KeyConstantTests {

        @Test
        @DisplayName("KEY_MODULE and KEY_PROFILE are accessible")
        void keys_ArePublicAndNonEmpty() {
            assertNotNull(ProfileThreadContext.KEY_MODULE);
            assertNotNull(ProfileThreadContext.KEY_PROFILE);
            assertFalse(ProfileThreadContext.KEY_MODULE.isEmpty());
            assertFalse(ProfileThreadContext.KEY_PROFILE.isEmpty());
            assertNotEquals(ProfileThreadContext.KEY_MODULE, ProfileThreadContext.KEY_PROFILE);
        }
    }

}
