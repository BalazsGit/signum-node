package application.module.node.instance;

import application.module.node.Signum;
import application.module.node.profile.NodeProfile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style tests for {@link NodeFactory}.
 * <p>
 * Validates the Signum facade registry: registration, lookup, profile isolation,
 * and bulk stop. Follows AAA pattern (Arrange-Act-Assert).
 *
 * @since 4.0 Phase H - Multi-node integration verification
 */
@DisplayName("NodeFactory Tests")
class NodeFactoryTest {

    private NodeFactory factory;

    @BeforeEach
    void setUp() {
        NodeFactory.resetInstance();
        factory = NodeFactory.getInstance();
    }

    @AfterEach
    void tearDown() {
        NodeFactory.resetInstance();
    }

    // =====================================================================
    // Singleton
    // =====================================================================

    @Nested
    @DisplayName("Singleton Access")
    class SingletonTests {

        @Test
        @DisplayName("getInstance returns same instance on repeated calls")
        void getInstance_returnsSameInstance() {
            // Act
            NodeFactory first = NodeFactory.getInstance();
            NodeFactory second = NodeFactory.getInstance();

            // Assert
            assertSame(first, second);
        }

        @Test
        @DisplayName("resetInstance clears registry and resets singleton")
        void resetInstance_clearsAndResets() {
            // Arrange
            registerMockSignum("mainnet");
            assertEquals(1, factory.size());

            // Act
            NodeFactory.resetInstance();
            NodeFactory newFactory = NodeFactory.getInstance();

            // Assert
            assertEquals(0, newFactory.size());
        }
    }

    // =====================================================================
    // Registration
    // =====================================================================

    @Nested
    @DisplayName("Registration")
    class RegistrationTests {

        @Test
        @DisplayName("register adds Signum and makes it queryable by profile name")
        void register_addsSignum() {
            // Arrange
            Signum signum = registerMockSignum("mainnet");

            // Assert
            assertTrue(factory.hasProfile("mainnet"));
            assertSame(signum, factory.get("mainnet"));
            assertEquals(1, factory.size());
        }

        @Test
        @DisplayName("register with null Signum throws IllegalArgumentException")
        void register_nullSignumThrows() {
            // Act & Assert
            assertThrows(IllegalArgumentException.class, () -> factory.register(null));
        }

        @Test
        @DisplayName("unregister removes Signum from registry")
        void unregister_removesSignum() {
            // Arrange
            Signum signum = registerMockSignum("testnet");

            // Act
            Signum removed = factory.unregister("testnet");

            // Assert
            assertSame(signum, removed);
            assertFalse(factory.hasProfile("testnet"));
            assertEquals(0, factory.size());
        }

        @Test
        @DisplayName("unregister non-existent profile returns null")
        void unregister_nonExistentReturnsNull() {
            // Act
            Signum result = factory.unregister("nonexistent");

            // Assert
            assertNull(result);
        }

        @Test
        @DisplayName("multiple profiles can be registered independently")
        void register_multipleProfiles() {
            // Arrange
            Signum signumA = registerMockSignum("mainnet");
            Signum signumB = registerMockSignum("testnet");

            // Assert
            assertEquals(2, factory.size());
            assertSame(signumA, factory.get("mainnet"));
            assertSame(signumB, factory.get("testnet"));
            assertNotSame(signumA, signumB);
        }

        @Test
        @DisplayName("replacing a profile removes the previous instance")
        void register_replacesPrevious() {
            // Arrange
            registerMockSignum("mainnet");
            assertEquals(1, factory.size());

            // Act
            Signum replacement = registerMockSignum("mainnet");

            // Assert
            assertEquals(1, factory.size());
            assertSame(replacement, factory.get("mainnet"));
        }
    }

    // =====================================================================
    // Profile Isolation (Multi-Node Verification)
    // =====================================================================

    @Nested
    @DisplayName("Profile Isolation")
    class ProfileIsolationTests {

        @Test
        @DisplayName("two Signum instances have independent profiles")
        void twoProfilesAreIndependent() {
            // Arrange
            Signum signumA = registerMockSignum("mainnet");
            Signum signumB = registerMockSignum("testnet");

            // Assert
            assertNotSame(signumA, signumB);
        }

        @Test
        @DisplayName("getAll returns all registered Signum instances")
        void getAll_returnsAll() {
            // Arrange
            registerMockSignum("mainnet");
            registerMockSignum("testnet");
            registerMockSignum("devnet");

            // Act
            var all = factory.getAll();

            // Assert
            assertEquals(3, all.size());
        }

        @Test
        @DisplayName("getAll returns unmodifiable collection")
        void getAll_returnsUnmodifiable() {
            // Arrange
            registerMockSignum("mainnet");

            // Act
            var all = factory.getAll();

            // Assert
            assertNotNull(all);
            assertThrows(UnsupportedOperationException.class, () -> all.clear());
        }
    }

    // =====================================================================
    // Backwards-Compatibility Bridge
    // =====================================================================

    @Nested
    @DisplayName("Backwards-Compatibility Bridge")
    class BackwardsCompatTests {

        @Test
        @DisplayName("getActive returns null when no Signums registered")
        void getActive_emptyReturnsNull() {
            // Act
            Signum result = factory.getActive();

            // Assert
            assertNull(result);
        }

        @Test
        @DisplayName("getActive returns the registered Signum when only one exists")
        void getActive_singleSignumReturnsIt() {
            // Arrange
            Signum signum = registerMockSignum("mainnet");

            // Act
            Signum result = factory.getActive();

            // Assert
            assertSame(signum, result);
        }

        @Test
        @DisplayName("getActive returns a Signum when multiple exist")
        void getActive_multipleSignumsReturnsOne() {
            // Arrange
            Signum signumA = registerMockSignum("mainnet");
            Signum signumB = registerMockSignum("testnet");

            // Act
            Signum result = factory.getActive();

            // Assert
            assertNotNull(result);
            assertTrue(result == signumA || result == signumB);
        }

        @Test
        @DisplayName("stopAll clears all registered nodes")
        void stopAll_clearsAllNodes() {
            // Arrange
            registerMockSignum("mainnet");
            registerMockSignum("testnet");
            assertEquals(2, factory.size());

            // Act
            factory.stopAll();

            // Assert
            assertEquals(0, factory.size());
        }
    }

    // =====================================================================
    // Test Helpers
    // =====================================================================

    /**
     * Creates a mocked Signum instance with the given profile name and registers it.
     * Mockito 5+ supports mocking final classes.
     */
    @SuppressWarnings("unchecked")
    private Signum registerMockSignum(String profileName) {
        NodeProfile profile = new NodeProfile(profileName);
        Signum signum = Mockito.mock(Signum.class);
        Mockito.when(signum.getProfileName()).thenReturn(profileName);
        Mockito.when(signum.getProfile()).thenReturn(profile);
        factory.register(signum);
        return signum;
    }
}