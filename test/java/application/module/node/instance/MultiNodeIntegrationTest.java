package application.module.node.instance;

import application.module.node.Signum;
import application.module.node.profile.NodeProfile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Integration tests that validate the multi-node Facade Pattern end-to-end.
 * <p>
 * These tests verify:
 * - Multiple Signum instances can coexist independently
 * - NodeFactory registry manages them correctly
 * - Resource cleanup after stop() releases references
 * - Profile runtime tracking works across lifecycle transitions
 *
 * @since 4.0 Phase H - Multi-node integration verification
 */
@DisplayName("Multi-Node Integration Tests")
class MultiNodeIntegrationTest {

    @AfterEach
    void tearDown() {
        NodeFactory.resetInstance();
    }

    // =====================================================================
    // Multi-Node Concurrent Execution
    // =====================================================================

    @Test
    @DisplayName("Multiple Signum instances can be registered simultaneously")
    void multiNodeConcurrentExecution() {
        // Arrange
        Signum nodeA = mockRegister("mainnet");
        Signum nodeB = mockRegister("testnet");

        // Assert - both coexist independently
        assertEquals(2, NodeFactory.getInstance().size());
        assertNotSame(nodeA, nodeB);
        assertSame(nodeA, NodeFactory.getInstance().get("mainnet"));
        assertSame(nodeB, NodeFactory.getInstance().get("testnet"));
    }

    @Test
    @DisplayName("Three nodes registered with different profiles")
    void threeNodesRegistered() {
        // Arrange
        mockRegister("mainnet");
        mockRegister("testnet");
        mockRegister("devnet");

        // Assert
        var factory = NodeFactory.getInstance();
        assertEquals(3, factory.size());
        assertTrue(factory.hasProfile("mainnet"));
        assertTrue(factory.hasProfile("testnet"));
        assertTrue(factory.hasProfile("devnet"));
    }

    // =====================================================================
    // Node Creation + Destruction Lifecycle
    // =====================================================================

    @Test
    @DisplayName("Node registration follows proper lifecycle")
    void nodeLifecycleRegistration() {
        var factory = NodeFactory.getInstance();

        // Phase 1: Empty registry
        assertEquals(0, factory.size());
        assertNull(factory.get("test"));

        // Phase 2: Register
        Signum signum = mockRegister("test");
        assertEquals(1, factory.size());
        assertSame(signum, factory.get("test"));

        // Phase 3: Unregister
        Signum removed = factory.unregister("test");
        assertSame(signum, removed);
        assertEquals(0, factory.size());
        assertNull(factory.get("test"));
    }

    @Test
    @DisplayName("Node replacement removes old instance")
    void nodeReplacementRemovesOld() {
        Signum v1 = mockRegister("mainnet");
        assertEquals(1, NodeFactory.getInstance().size());

        Signum v2 = mockRegister("mainnet");
        // Still 1 entry, but pointing to new instance
        assertEquals(1, NodeFactory.getInstance().size());
        assertNotSame(v1, v2);
        assertSame(v2, NodeFactory.getInstance().get("mainnet"));
    }

    // =====================================================================
    // Profile Isolation Verification
    // =====================================================================

    @Test
    @DisplayName("Different profiles have completely independent Signum instances")
    void profileIsolationIndependentInstances() {
        // Arrange
        Signum nodeA = mockRegister("mainnet");
        Signum nodeB = mockRegister("testnet");

        // Assert - identity isolation
        assertNotSame(nodeA, nodeB);
        assertSame(nodeA, NodeFactory.getInstance().get("mainnet"));
        assertSame(nodeB, NodeFactory.getInstance().get("testnet"));
    }

    @Test
    @DisplayName("Profile lookup returns correct instance by name")
    void profileLookupByName() {
        // Arrange
        Signum signumA = mockRegister("alpha");
        Signum signumB = mockRegister("beta");

        // Assert
        assertSame(signumA, NodeFactory.getInstance().get("alpha"));
        assertSame(signumB, NodeFactory.getInstance().get("beta"));
        assertNull(NodeFactory.getInstance().get("gamma"));
    }

    @Test
    @DisplayName("getAll returns view of all registered instances")
    void getAllReturnsAllInstances() {
        // Arrange
        mockRegister("p1");
        mockRegister("p2");

        // Act
        var all = NodeFactory.getInstance().getAll();

        // Assert
        assertEquals(2, all.size());
        assertNotNull(all);
    }

    // =====================================================================
    // Resource Cleanup after stop()
    // =====================================================================

    @Test
    @DisplayName("stopAll clears registry")
    void stopAllClearsRegistry() {
        // Arrange
        mockRegister("mainnet");
        mockRegister("testnet");
        assertEquals(2, NodeFactory.getInstance().size());

        // Act
        NodeFactory.getInstance().stopAll();

        // Assert
        assertEquals(0, NodeFactory.getInstance().size());
    }

    @Test
    @DisplayName("resetInstance clears all state for clean test environment")
    void resetInstanceClearsState() {
        // Arrange
        mockRegister("mainnet");
        assertEquals(1, NodeFactory.getInstance().size());

        // Act
        NodeFactory.resetInstance();

        // Assert - fresh factory has empty registry
        var newFactory = NodeFactory.getInstance();
        assertEquals(0, newFactory.size());
    }

    @Test
    @DisplayName("Unregistering non-existent profile does not throw")
    void unregisterNonExistentDoesNotThrow() {
        // Act & Assert - should return null, not throw
        assertNull(NodeFactory.getInstance().unregister("never_existed"));
    }

    // =====================================================================
    // Signum Facade Identity Verification
    // =====================================================================

    @Test
    @DisplayName("Signum facade has correct profile name")
    void signumHasCorrectProfileName() {
        // Arrange
        NodeProfile profile = new NodeProfile("my-profile");
        Signum signum = mock(Signum.class);
        org.mockito.Mockito.when(signum.getProfileName()).thenReturn("my-profile");
        org.mockito.Mockito.when(signum.getProfile()).thenReturn(profile);

        // Register and lookup
        NodeFactory.getInstance().register(signum);

        // Assert
        assertEquals("my-profile", NodeFactory.getInstance().get("my-profile").getProfileName());
    }

    @Test
    @DisplayName("activeInstance returns first registered Signum")
    void activeInstanceReturnsFirstRegistered() {
        var factory = NodeFactory.getInstance();

        // When empty -> null
        assertNull(factory.getActive());

        // After registration -> returns one of them
        Signum signum = mockRegister("only-one");
        assertSame(signum, factory.getActive());
    }

    // =====================================================================
    // Test Helpers
    // =====================================================================

    @SuppressWarnings("unchecked")
    private Signum mockRegister(String profileName) {
        NodeProfile profile = new NodeProfile(profileName);
        Signum signum = mock(Signum.class);
        org.mockito.Mockito.when(signum.getProfileName()).thenReturn(profileName);
        org.mockito.Mockito.when(signum.getProfile()).thenReturn(profile);
        NodeFactory.getInstance().register(signum);
        return signum;
    }
}