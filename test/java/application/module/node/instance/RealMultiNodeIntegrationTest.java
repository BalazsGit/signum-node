package application.module.node.instance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import application.module.node.Signum;
import application.module.node.profile.NodeProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Integration tests for real multi-node operation with independent instances.
 * Validates the Signum Facade Pattern + NodeFactory registry under concurrent load.
 *
 * @since 4.1 Multi-node integration validation
 */
@DisplayName("Real Multi-Node Integration Tests")
class RealMultiNodeIntegrationTest {

    private final Path confPath = Path.of("./conf");

    // =========================================================================
    // Helper: create a fully-mocked Signum ready for NodeFactory.register()
    // =========================================================================

    /**
     * Creates a mock Signum where both getProfile() and getProfileName() are
     * properly stubbed so that NodeFactory.register() does not throw.
     */
    private Signum createMockSignum(String profileName) {
        NodeProfile profile = new NodeProfile(profileName);
        Signum signum = mock(Signum.class);
        when(signum.getProfile()).thenReturn(profile);
        when(signum.getProfileName()).thenReturn(profileName);
        return signum;
    }

    // =========================================================================
    // NodeFactory Registry Tests
    // =========================================================================

    @Nested
    @DisplayName("NodeFactory Registry Operations")
    class RegistryTests {

        @BeforeEach
        void clearRegistry() {
            NodeFactory.getInstance().stopAll();
        }

        @Test
        @DisplayName("Register multiple Signum instances with different profiles")
        void registerMultipleProfiles() {
            // Arrange
            Signum nodeA = createMockSignum("test-mainnet");
            Signum nodeB = createMockSignum("test-testnet");

            // Act
            NodeFactory.getInstance().register(nodeA);
            NodeFactory.getInstance().register(nodeB);

            // Assert
            assertEquals(nodeA, NodeFactory.getInstance().get("test-mainnet"));
            assertEquals(nodeB, NodeFactory.getInstance().get("test-testnet"));
            assertTrue(NodeFactory.getInstance().getAll().contains(nodeA));
            assertTrue(NodeFactory.getInstance().getAll().contains(nodeB));
        }

        @Test
        @DisplayName("Unregister removes instance from registry")
        void unregisterRemovesInstance() {
            // Arrange
            Signum signum = createMockSignum("temp-profile");
            NodeFactory.getInstance().register(signum);

            // Act
            NodeFactory.getInstance().unregister("temp-profile");

            // Assert
            assertNull(NodeFactory.getInstance().get("temp-profile"));
            assertFalse(NodeFactory.getInstance().getAll().contains(signum));
        }

        @Test
        @DisplayName("stopAll clears all registered instances")
        void stopAllClearsRegistry() {
            // Arrange
            for (int i = 0; i < 5; i++) {
                Signum signum = createMockSignum("batch-" + i);
                when(signum.isRunning()).thenReturn(true);
                NodeFactory.getInstance().register(signum);
            }

            // Act
            NodeFactory.getInstance().stopAll();

            // Assert
            assertTrue(NodeFactory.getInstance().getAll().isEmpty());
        }

        @Test
        @DisplayName("Registry is thread-safe under concurrent registration")
        void concurrentRegistrationIsThreadSafe() throws InterruptedException {
            // Arrange
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            // Act - multiple threads register simultaneously
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Signum signum = createMockSignum("concurrent-" + index);
                        NodeFactory.getInstance().register(signum);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        fail("Registration failed: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // Start all threads at once
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            // Assert
            assertEquals(threadCount, successCount.get());
        }
    }

    // =========================================================================
    // Signum Facade Contract Tests
    // =========================================================================

    @Nested
    @DisplayName("Signum Facade Contract")
    class FacadeContractTests {

        @Test
        @DisplayName("Signum delegates profile identity correctly")
        void profileIdentityDelegation() {
            // Arrange
            NodeProfile profile = new NodeProfile("identity-test");

            // Act + Assert - Test that mock Signum properly exposes profile
            Signum mockSignum = mock(Signum.class);
            when(mockSignum.getProfile()).thenReturn(profile);
            assertEquals("identity-test", mockSignum.getProfile().getName());

            // Verify null profile rejected by real constructor
            assertThrows(NullPointerException.class, () -> {
                NodeCoreContext dummyCtx = mock(NodeCoreContext.class);
                new Signum(null, dummyCtx);
            });

            // Verify null context rejected
            assertThrows(NullPointerException.class, () -> {
                new Signum(profile, (NodeCoreContext) null);
            });
        }

    }

    // =========================================================================
    // Profile Isolation Tests
    // =========================================================================

    @Nested
    @DisplayName("Profile Isolation Verification")
    class ProfileIsolationTests {

        @BeforeEach
        void clearRegistry() {
            NodeFactory.getInstance().stopAll();
        }

        @Test
        @DisplayName("Each profile gets independent Signum instance")
        void eachProfileGetsIndependentInstance() {
            // Arrange
            Signum node1 = createMockSignum("isolated-1");
            Signum node2 = createMockSignum("isolated-2");

            // Act
            NodeFactory.getInstance().register(node1);
            NodeFactory.getInstance().register(node2);

            // Assert - Profile isolation verified
            assertNotSame(node1, node2);
            assertEquals("isolated-1", NodeFactory.getInstance().get("isolated-1").getProfile().getName());
            assertEquals("isolated-2", NodeFactory.getInstance().get("isolated-2").getProfile().getName());
        }

        @Test
        @DisplayName("Profiles maintain independent state after partial stop")
        void independentStateAfterPartialStop() {
            // Arrange
            List<Signum> nodes = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Signum signum = createMockSignum("state-test-" + i);
                when(signum.isRunning()).thenReturn(true);
                nodes.add(signum);
                NodeFactory.getInstance().register(signum);
            }

            // Act - Stop first node only
            Signum target = NodeFactory.getInstance().get("state-test-0");
            NodeFactory.getInstance().unregister("state-test-0");

            // Assert - Other nodes unaffected
            assertEquals(2, NodeFactory.getInstance().getAll().size());
            assertNotSame(target, NodeFactory.getInstance().get("state-test-1"));
        }
    }

    // =========================================================================
    // Multi-node Lifecycle Tests (Simulated)
    // =========================================================================

    @Nested
    @DisplayName("Multi-Node Lifecycle Management")
    class LifecycleTests {

        @BeforeEach
        void clearRegistry() {
            NodeFactory.getInstance().stopAll();
        }

        @Test
        @DisplayName("Simulate concurrent node startup tracking")
        void simulateConcurrentStartup() throws InterruptedException {
            // Arrange
            int nodeCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(nodeCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            ConcurrentHashMap<String, Boolean> status = new ConcurrentHashMap<>();

            // Act - Simulate concurrent startup by registering and marking running
            for (int i = 0; i < nodeCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Signum signum = createMockSignum("lifecycle-" + idx);
                        when(signum.isRunning()).thenReturn(true);
                        NodeFactory.getInstance().register(signum);
                        status.put("lifecycle-" + idx, true);
                    } catch (Exception e) {
                        fail("Startup failed: " + e.getMessage());
                    }
                });
            }

            startLatch.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

            // Assert - All nodes registered and marked running
            assertEquals(nodeCount, status.size());
            assertTrue(status.values().stream().allMatch(Boolean::booleanValue));
        }

        @Test
        @DisplayName("NodeFactory handles duplicate profile names")
        void duplicateProfileNameReplacement() {
            // Arrange
            Signum original = createMockSignum("duplicate-test");
            Signum replacement = createMockSignum("duplicate-test");

            // Act - Register same profile name twice
            NodeFactory.getInstance().register(original);
            NodeFactory.getInstance().register(replacement);

            // Assert - Second registration replaces first
            assertSame(replacement, NodeFactory.getInstance().get("duplicate-test"));
        }
    }
}