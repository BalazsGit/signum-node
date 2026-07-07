package application.module.node.instance;

import application.module.node.props.PropertyService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link NodeCoreContextManager}.
 * <p>
 * Follows AAA pattern (Arrange-Act-Assert) with JUnit 5 + Mockito.
 *
 * @since 4.0
 */
@DisplayName("NodeCoreContextManager Tests")
class NodeCoreContextManagerTest {

    private NodeCoreContextManager manager;

    @BeforeEach
    void setUp() {
        NodeCoreContextManager.resetInstance();
        manager = NodeCoreContextManager.getInstance();
    }

    @AfterEach
    void tearDown() {
        NodeCoreContextManager.resetInstance();
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
            NodeCoreContextManager first = NodeCoreContextManager.getInstance();
            NodeCoreContextManager second = NodeCoreContextManager.getInstance();

            // Assert
            assertSame(first, second);
        }

        @Test
        @DisplayName("resetInstance clears singleton and subsequent getInstance creates new")
        void resetInstance_createsNewOnNextCall() {
            // Arrange
            NodeCoreContextManager first = NodeCoreContextManager.getInstance();

            // Act
            NodeCoreContextManager.resetInstance();
            NodeCoreContextManager second = NodeCoreContextManager.getInstance();

            // Assert
            assertNotSame(first, second);
            assertEquals(0, second.size());
        }
    }

    // =====================================================================
    // Registration
    // =====================================================================

    @Nested
    @DisplayName("Registration")
    class RegistrationTests {

        @Test
        @DisplayName("register adds context and makes it queryable")
        void register_addsContext() {
            // Arrange
            NodeCoreContext context = mockNodeCoreContext();

            // Act
            manager.register("mainnet", context);

            // Assert
            assertTrue(manager.hasProfile("mainnet"));
            assertSame(context, manager.get("mainnet"));
            assertEquals(1, manager.size());
        }

        @Test
        @DisplayName("register with null profileName throws IllegalArgumentException")
        void register_nullProfileThrows() {
            // Arrange
            NodeCoreContext context = mockNodeCoreContext();

            // Act & Assert
            assertThrows(IllegalArgumentException.class, () -> manager.register(null, context));
        }

        @Test
        @DisplayName("register with blank profileName throws IllegalArgumentException")
        void register_blankProfileThrows() {
            // Arrange
            NodeCoreContext context = mockNodeCoreContext();

            // Act & Assert
            assertThrows(IllegalArgumentException.class, () -> manager.register("  ", context));
        }

        @Test
        @DisplayName("register with null context throws IllegalArgumentException")
        void register_nullContextThrows() {
            // Act & Assert
            assertThrows(IllegalArgumentException.class, () -> manager.register("mainnet", null));
        }

        @Test
        @DisplayName("unregister removes context from registry")
        void unregister_removesContext() {
            // Arrange
            NodeCoreContext context = mockNodeCoreContext();
            manager.register("testnet", context);

            // Act
            NodeCoreContext removed = manager.unregister("testnet");

            // Assert
            assertSame(context, removed);
            assertFalse(manager.hasProfile("testnet"));
            assertEquals(0, manager.size());
        }

        @Test
        @DisplayName("unregister non-existent profile returns null")
        void unregister_nonExistentReturnsNull() {
            // Act
            NodeCoreContext result = manager.unregister("nonexistent");

            // Assert
            assertNull(result);
        }

        @Test
        @DisplayName("multiple profiles can be registered independently")
        void register_multipleProfiles() {
            // Arrange
            NodeCoreContext ctx1 = mockNodeCoreContext();
            NodeCoreContext ctx2 = mockNodeCoreContext();

            // Act
            manager.register("mainnet", ctx1);
            manager.register("testnet", ctx2);

            // Assert
            assertEquals(2, manager.size());
            assertSame(ctx1, manager.get("mainnet"));
            assertSame(ctx2, manager.get("testnet"));
        }
    }

    // =====================================================================
    // Backwards-compatibility bridge
    // =====================================================================

    @Nested
    @DisplayName("Backwards-Compatibility Bridge")
    class BackwardsCompatTests {

        @Test
        @DisplayName("getActive returns null when no contexts registered")
        void getActive_emptyReturnsNull() {
            // Act
            NodeCoreContext result = manager.getActive();

            // Assert
            assertNull(result);
        }

        @Test
        @DisplayName("getActive returns the registered context when only one exists")
        void getActive_singleContextReturnsIt() {
            // Arrange
            NodeCoreContext context = mockNodeCoreContext();
            manager.register("mainnet", context);

            // Act
            NodeCoreContext result = manager.getActive();

            // Assert
            assertSame(context, result);
        }

        @Test
        @DisplayName("getActive returns a context when multiple exist")
        void getActive_multipleContextsReturnsOne() {
            // Arrange
            NodeCoreContext ctx1 = mockNodeCoreContext();
            NodeCoreContext ctx2 = mockNodeCoreContext();
            manager.register("mainnet", ctx1);
            manager.register("testnet", ctx2);

            // Act
            NodeCoreContext result = manager.getActive();

            // Assert
            assertNotNull(result);
            assertTrue(result == ctx1 || result == ctx2);
        }
    }

    // =====================================================================
    // Queries
    // =====================================================================

    @Nested
    @DisplayName("Queries")
    class QueryTests {

        @Test
        @DisplayName("get returns null for non-existent profile")
        void get_nonExistentReturnsNull() {
            // Act
            NodeCoreContext result = manager.get("unknown");

            // Assert
            assertNull(result);
        }

        @Test
        @DisplayName("getAll returns unmodifiable collection")
        void getAll_returnsUnmodifiable() {
            // Arrange
            manager.register("mainnet", mockNodeCoreContext());

            // Act
            var collection = manager.getAll();

            // Assert
            assertNotNull(collection);
            assertThrows(UnsupportedOperationException.class, () -> collection.add(mockNodeCoreContext()));
        }

        @Test
        @DisplayName("hasProfile returns false for empty registry")
        void hasProfile_emptyReturnsFalse() {
            // Act & Assert
            assertFalse(manager.hasProfile("any"));
        }
    }

    // =====================================================================
    // Test Helpers
    // =====================================================================

    /**
     * Creates a Mockito mock of the final NodeCoreContext class.
     * Uses Mockito.mock() which supports mocking final classes since Mockito 5+.
     */
    @SuppressWarnings("unchecked")
    private static NodeCoreContext mockNodeCoreContext() {
        return mock(NodeCoreContext.class);
    }
}