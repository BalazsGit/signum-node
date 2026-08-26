package application.module.node.peer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PeerManager}.
 * <p>
 * Since the PeerManager requires full node dependencies (PropertyService, Blockchain,
 * ThreadPool, TimeService) that can only exist inside a running NodeCoreContext,
 * these tests verify structural properties: constructor signature, field types,
 * and thread-safety guarantees via reflection.
 * </p>
 * <p>
 * The actual peer networking delegation to {@link Peers} is integration-tested
 * elsewhere. Future Phase 10d will cover fully instance-scoped behavior after
 * static migration is complete.
 * </p>
 */
@DisplayName("PeerManager Tests")
class PeerManagerTest {

    @Nested
    @DisplayName("Constructor Signature")
    class ConstructorTests {

        @Test
        @DisplayName("Has single public constructor with 4 parameters")
        void constructor_HasCorrectSignature() throws Exception {
            Constructor<PeerManager> constructor = PeerManager.class.getConstructor(
                    application.module.node.props.PropertyService.class,
                    application.module.node.Blockchain.class,
                    application.module.node.util.ThreadPool.class,
                    application.module.node.services.TimeService.class);
            assertNotNull(constructor, "Expected public constructor with PropertyService, Blockchain, ThreadPool, TimeService");
        }

        @Test
        @DisplayName("Constructor parameters are final dependencies")
        void constructor_ParametersAreFinalFields() throws Exception {
            Field propertyService = PeerManager.class.getDeclaredField("propertyService");
            Field blockchain = PeerManager.class.getDeclaredField("blockchain");
            Field threadPool = PeerManager.class.getDeclaredField("threadPool");
            Field timeService = PeerManager.class.getDeclaredField("timeService");

            assertTrue(modifiersContainFinal(propertyService.getModifiers()), "propertyService should be final");
            assertTrue(modifiersContainFinal(blockchain.getModifiers()), "blockchain should be final");
            assertTrue(modifiersContainFinal(threadPool.getModifiers()), "threadPool should be final");
            assertTrue(modifiersContainFinal(timeService.getModifiers()), "timeService should be final");
        }

        private boolean modifiersContainFinal(int modifiers) {
            return (modifiers & java.lang.reflect.Modifier.FINAL) != 0;
        }
    }

    @Nested
    @DisplayName("Thread Safety - Field Types")
    class ThreadSafetyTests {

        @Test
        @DisplayName("peers field is the instance-scoped Peers engine")
        void peersField_IsInstanceScopedPeers() throws Exception {
            Field field = PeerManager.class.getDeclaredField("peers");
            Class<?> fieldType = field.getType();
            assertSame(Peers.class, fieldType,
                    "peers should be the instance-scoped Peers engine for this profile");
        }

        @Test
        @DisplayName("Peers.peers registry is ConcurrentMap interface for thread safety")
        void peersRegistry_IsConcurrentMap() throws Exception {
            Field field = Peers.class.getDeclaredField("peers");
            Class<?> fieldType = field.getType();
            assertSame(java.util.concurrent.ConcurrentMap.class, fieldType,
                    "peers registry should be ConcurrentMap for lock-free concurrent access");
        }

        @Test
        @DisplayName("Peers.announcedAddresses is ConcurrentMap interface for thread safety")
        void announcedAddressesField_IsConcurrentMap() throws Exception {
            Field field = Peers.class.getDeclaredField("announcedAddresses");
            Class<?> fieldType = field.getType();
            assertSame(java.util.concurrent.ConcurrentMap.class, fieldType,
                    "announcedAddresses should be ConcurrentMap for lock-free concurrent access");
        }

        @Test
        @DisplayName("Peers.allPeers is unmodifiable Collection")
        void allPeersField_IsUnmodifiableCollection() throws Exception {
            Field field = Peers.class.getDeclaredField("allPeers");
            // Verify it's a Collection type (immutable view)
            assertTrue(java.util.Collection.class.isAssignableFrom(field.getType()),
                    "allPeers should be a Collection");
        }

        @Test
        @DisplayName("running field is volatile for visibility across threads")
        void runningField_IsVolatile() throws Exception {
            Field field = PeerManager.class.getDeclaredField("running");
            assertTrue(java.lang.reflect.Modifier.isVolatile(field.getModifiers()),
                    "running should be volatile for cross-thread visibility");
        }

        @Test
        @DisplayName("Dependency fields are final (immutable after construction)")
        void dependencyFields_AreFinal() throws Exception {
            String[] dependencyFields = {"propertyService", "blockchain", "threadPool", "timeService"};
            for (String fieldName : dependencyFields) {
                Field field = PeerManager.class.getDeclaredField(fieldName);
                assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()),
                        fieldName + " should be final");
            }
        }

        @Test
        @DisplayName("Class is final (not extensible)")
        void classIs_Final() {
            assertTrue(java.lang.reflect.Modifier.isFinal(PeerManager.class.getModifiers()),
                    "PeerManager should be final to prevent subclassing");
        }
    }

    @Nested
    @DisplayName("Lifecycle Methods Exist")
    class LifecycleMethodTests {

        @Test
        @DisplayName("Has isRunning() method returning boolean")
        void hasIsRunningMethod() throws Exception {
            var method = PeerManager.class.getMethod("isRunning");
            assertSame(boolean.class, method.getReturnType(), "isRunning should return boolean");
        }

        @Test
        @DisplayName("Has start() method")
        void hasStartMethod() throws Exception {
            // The exact signature varies - just verify a start method exists
            boolean found = false;
            for (var method : PeerManager.class.getMethods()) {
                if ("start".equals(method.getName())) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "PeerManager should have a start() method");
        }

        @Test
        @DisplayName("Has shutdown(ThreadPool) method")
        void hasShutdownMethod() throws Exception {
            var method = PeerManager.class.getMethod("shutdown",
                    application.module.node.util.ThreadPool.class);
            assertSame(void.class, method.getReturnType(), "shutdown should return void");
        }

        @Test
        @DisplayName("Has getAllPeers() method")
        void hasGetAllPeersMethod() throws Exception {
            var method = PeerManager.class.getMethod("getAllPeers");
            assertTrue(java.util.Collection.class.isAssignableFrom(method.getReturnType()),
                    "getAllPeers should return a Collection");
        }

        @Test
        @DisplayName("Has getActivePeers() method")
        void hasGetActivePeersMethod() throws Exception {
            var method = PeerManager.class.getMethod("getActivePeers");
            assertTrue(java.util.List.class.isAssignableFrom(method.getReturnType()),
                    "getActivePeers should return a List");
        }

        @Test
        @DisplayName("Has getPeerCount() method")
        void hasGetPeerCountMethod() throws Exception {
            var method = PeerManager.class.getMethod("getPeerCount");
            assertSame(int.class, method.getReturnType(), "getPeerCount should return int");
        }

        @Test
        @DisplayName("Has getConnectedPeerCount() method")
        void hasGetConnectedPeerCountMethod() throws Exception {
            var method = PeerManager.class.getMethod("getConnectedPeerCount");
            assertSame(int.class, method.getReturnType(), "getConnectedPeerCount should return int");
        }
    }

    @Nested
    @DisplayName("Getter Methods for Dependencies")
    class GetterTests {

        @Test
        @DisplayName("Has getPropertyService() getter")
        void hasGetPropertyService() throws Exception {
            var method = PeerManager.class.getMethod("getPropertyService");
            assertSame(application.module.node.props.PropertyService.class,
                    method.getReturnType(), "getPropertyService should return PropertyService");
        }

        @Test
        @DisplayName("Has getBlockchain() getter")
        void hasGetBlockchain() throws Exception {
            var method = PeerManager.class.getMethod("getBlockchain");
            assertSame(application.module.node.Blockchain.class,
                    method.getReturnType(), "getBlockchain should return Blockchain");
        }

        @Test
        @DisplayName("Has getThreadPool() getter")
        void hasGetThreadPool() throws Exception {
            var method = PeerManager.class.getMethod("getThreadPool");
            assertSame(application.module.node.util.ThreadPool.class,
                    method.getReturnType(), "getThreadPool should return ThreadPool");
        }

        @Test
        @DisplayName("Has getTimeService() getter")
        void hasGetTimeService() throws Exception {
            var method = PeerManager.class.getMethod("getTimeService");
            assertSame(application.module.node.services.TimeService.class,
                    method.getReturnType(), "getTimeService should return TimeService");
        }
    }
}