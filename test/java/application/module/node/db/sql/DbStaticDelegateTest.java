package application.module.node.db.sql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the static delegate pattern in {@link Db}.
 * <p>
 * Verifies that {@code Db.getActiveContext()}, {@code Db.setActiveContext()}
 * and the fallback behaviour work correctly.
 * </p>
 *
 * @since 4.0
 */
@DisplayName("Db Static Delegate Tests")
class DbStaticDelegateTest {

    @BeforeEach
    void setUp() {
        // Ensure clean state before each test
        Db.setActiveContext(null);
    }

    @AfterEach
    void tearDown() {
        Db.setActiveContext(null);
    }

    // =====================================================================
    // Active context management
    // =====================================================================

    @Nested
    @DisplayName("Active Context Management")
    class ActiveContextTests {

        @Test
        @DisplayName("getActiveContext throws IllegalStateException when no context set")
        void getActiveContext_nullThrowsIllegalState() {
            // Act & Assert
            assertThrows(IllegalStateException.class, () -> Db.getActiveContext());
        }

        @Test
        @DisplayName("setActiveContext stores the context")
        void setActiveContext_storesContext() {
            // Arrange
            DbContext dbContext = new DbContext();

            // Act
            Db.setActiveContext(dbContext);

            // Assert
            assertSame(dbContext, Db.getActiveContext());
        }

        @Test
        @DisplayName("setActiveContext can be called multiple times")
        void setActiveContext_callableMultipleTimes() {
            // Arrange
            DbContext ctx1 = new DbContext();
            DbContext ctx2 = new DbContext();

            // Act
            Db.setActiveContext(ctx1);
            assertSame(ctx1, Db.getActiveContext());

            Db.setActiveContext(ctx2);
            assertSame(ctx2, Db.getActiveContext());
        }

        @Test
        @DisplayName("setActiveContext with null clears the active context")
        void setActiveContext_nullClearsContext() {
            // Arrange
            DbContext dbContext = new DbContext();
            Db.setActiveContext(dbContext);

            // Act
            Db.setActiveContext(null);

            // Assert
            assertThrows(IllegalStateException.class, () -> Db.getActiveContext());
        }
    }

    // =====================================================================
    // Delegation to active context
    // =====================================================================

    @Nested
    @DisplayName("Delegation to Active Context")
    class DelegationTests {

        @Test
        @DisplayName("isInTransaction delegates to active context")
        void isInTransaction_delegatesToActiveContext() {
            // Arrange
            DbContext dbContext = new DbContext();
            Db.setActiveContext(dbContext);

            // Act & Assert
            assertFalse(Db.isInTransaction()); // Delegates to dbContext.isInTransaction()
        }

        @Test
        @DisplayName("beginTransaction delegates to active context")
        void beginTransaction_delegatesToActiveContext() {
            // Arrange
            DbContext dbContext = new DbContext();
            Db.setActiveContext(dbContext);

            // Act & Assert
            // NPE from null databaseInstance is wrapped in RuntimeException
            Exception ex = assertThrows(RuntimeException.class, () -> Db.beginTransaction());
            assertTrue(ex.getCause() instanceof NullPointerException);
        }

        @Test
        @DisplayName("getConnection delegates to active context")
        void getConnection_delegatesToActiveContext() {
            // Arrange
            DbContext dbContext = new DbContext();
            Db.setActiveContext(dbContext);

            // Act & Assert
            assertThrows(NullPointerException.class, () -> Db.getConnection());
        }

        @Test
        @DisplayName("commitTransaction delegates to active context")
        void commitTransaction_delegatesToActiveContext() {
            // Arrange
            DbContext dbContext = new DbContext();
            Db.setActiveContext(dbContext);

            // Act & Assert
            // Delegates to dbContext which throws IllegalStateException (not in transaction)
            assertThrows(IllegalStateException.class, () -> Db.commitTransaction());
        }

        @Test
        @DisplayName("rollbackTransaction delegates to active context")
        void rollbackTransaction_delegatesToActiveContext() {
            // Arrange
            DbContext dbContext = new DbContext();
            Db.setActiveContext(dbContext);

            // Act & Assert
            assertThrows(IllegalStateException.class, () -> Db.rollbackTransaction());
        }

        @Test
        @DisplayName("endTransaction delegates to active context")
        void endTransaction_delegatesToActiveContext() {
            // Arrange
            DbContext dbContext = new DbContext();
            Db.setActiveContext(dbContext);

            // Act & Assert
            assertThrows(IllegalStateException.class, () -> Db.endTransaction());
        }

        @Test
        @DisplayName("getDialect delegates to active context")
        void getDialect_delegatesToActiveContext() {
            // Arrange
            DbContext dbContext = new DbContext();
            Db.setActiveContext(dbContext);

            // Act & Assert
            assertThrows(NullPointerException.class, () -> Db.getDialect());
        }

        @Test
        @DisplayName("optimizeTable delegates to active context")
        void optimizeTable_delegatesToActiveContext() {
            // Arrange
            DbContext dbContext = new DbContext();
            Db.setActiveContext(dbContext);

            // Act & Assert
            assertThrows(NullPointerException.class, () -> Db.optimizeTable("test_table"));
        }

        @Test
        @DisplayName("shutdown delegates to active context")
        void shutdown_delegatesToActiveContext() {
            // Arrange
            DbContext dbContext = new DbContext();
            Db.setActiveContext(dbContext);

            // Act & Assert - shutdown on uninitialized context does not throw
            assertDoesNotThrow(() -> Db.shutdown());
        }

        @Test
        @DisplayName("fetchWithDSLContext delegates to active context")
        void fetchWithDSLContext_delegatesToActiveContext() {
            // Arrange
            DbContext dbContext = new DbContext();
            Db.setActiveContext(dbContext);

            // Act & Assert
            assertThrows(NullPointerException.class, () ->
                Db.fetchWithDSLContext(ctx -> null)
            );
        }

        @Test
        @DisplayName("useDSLContext delegates to active context")
        void useDSLContext_delegatesToActiveContext() {
            // Arrange
            DbContext dbContext = new DbContext();
            Db.setActiveContext(dbContext);

            // Act & Assert
            assertThrows(NullPointerException.class, () ->
                Db.useDSLContext(ctx -> {})
            );
        }

        @Test
        @DisplayName("backup delegates to active context")
        void backup_delegatesToActiveContext() {
            // Arrange
            DbContext dbContext = new DbContext();
            Db.setActiveContext(dbContext);

            // Act & Assert
            assertThrows(NullPointerException.class, () -> Db.backup("test.sql"));
        }

        @Test
        @DisplayName("setRepairConfirmationHandler propagates to active context")
        void setRepairConfirmationHandler_propagatesToActiveContext() {
            // Arrange
            DbContext dbContext = new DbContext();
            Db.setActiveContext(dbContext);

            // Act & Assert
            assertDoesNotThrow(() -> Db.setRepairConfirmationHandler(msg -> true));
        }
    }
}