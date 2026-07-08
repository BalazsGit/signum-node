package application.module.node.db.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DbContext}.
 * <p>
 * These tests verify that a {@code DbContext} instance properly encapsulates
 * per-instance database state before and after initialization.
 * </p>
 *
 * @since 4.0
 */
@DisplayName("DbContext Tests")
class DbContextTest {

    // =====================================================================
    // Construction
    // =====================================================================

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("new DbContext has null database instance")
        void constructor_databaseInstanceIsNull() {
            // Act
            DbContext dbContext = new DbContext();

            // Assert
            assertNull(dbContext.getDatabaseInstance());
        }

        @Test
        @DisplayName("new DbContext has null cache manager")
        void constructor_cacheManagerIsNull() {
            // Act
            DbContext dbContext = new DbContext();

            // Assert
            assertNull(dbContext.getCacheManager());
        }

        @Test
        @DisplayName("new DbContext is not in transaction")
        void constructor_notInTransaction() {
            // Act
            DbContext dbContext = new DbContext();

            // Assert
            assertFalse(dbContext.isInTransaction());
        }

        @Test
        @DisplayName("two DbContext instances are independent objects")
        void twoContexts_areIndependent() {
            // Act
            DbContext ctx1 = new DbContext();
            DbContext ctx2 = new DbContext();

            // Assert
            assertNotSame(ctx1, ctx2);
            assertNull(ctx1.getDatabaseInstance());
            assertNull(ctx2.getDatabaseInstance());
        }
    }

    // =====================================================================
    // Repair handler
    // =====================================================================

    @Nested
    @DisplayName("Repair Confirmation Handler")
    class RepairHandlerTests {

        @Test
        @DisplayName("setRepairConfirmationHandler accepts a handler without throwing")
        void setRepairConfirmationHandler_acceptsHandler() {
            // Arrange
            DbContext dbContext = new DbContext();

            // Act & Assert
            assertDoesNotThrow(() -> dbContext.setRepairConfirmationHandler(msg -> true));
        }

        @Test
        @DisplayName("setRepairConfirmationHandler can be called multiple times")
        void setRepairConfirmationHandler_callableMultipleTimes() {
            // Arrange
            DbContext dbContext = new DbContext();

            // Act & Assert
            assertDoesNotThrow(() -> {
                dbContext.setRepairConfirmationHandler(msg -> true);
                dbContext.setRepairConfirmationHandler(msg -> false);
                dbContext.setRepairConfirmationHandler(msg -> msg.length() > 10);
            });
        }
    }

    // =====================================================================
    // Shutdown
    // =====================================================================

    @Nested
    @DisplayName("Shutdown")
    class ShutdownTests {

        @Test
        @DisplayName("shutdown on uninitialized context does not throw")
        void shutdown_uninitializedDoesNotThrow() {
            // Arrange
            DbContext dbContext = new DbContext();

            // Act & Assert
            assertDoesNotThrow(() -> dbContext.shutdown());
        }

        @Test
        @DisplayName("shutdown is idempotent")
        void shutdown_idempotent() {
            // Arrange
            DbContext dbContext = new DbContext();

            // Act & Assert
            assertDoesNotThrow(() -> {
                dbContext.shutdown();
                dbContext.shutdown();
            });
        }
    }

    // =====================================================================
    // Uninitialized access
    // =====================================================================

    @Nested
    @DisplayName("Uninitialized Access")
    class UninitializedAccessTests {

        @Test
        @DisplayName("beginTransaction throws RuntimeException wrapping NPE when not initialized")
        void beginTransaction_uninitializedThrows() {
            // Arrange
            DbContext dbContext = new DbContext();

            // Act & Assert
            // The NPE from null databaseInstance is wrapped in RuntimeException
            Exception ex = assertThrows(RuntimeException.class, () -> dbContext.beginTransaction());
            assertTrue(ex.getCause() instanceof NullPointerException);
        }

        @Test
        @DisplayName("getConnection throws NullPointerException when not initialized")
        void getConnection_uninitializedThrows() {
            // Arrange
            DbContext dbContext = new DbContext();

            // Act & Assert
            assertThrows(NullPointerException.class, () -> dbContext.getConnection());
        }

        @Test
        @DisplayName("getDialect throws NullPointerException when not initialized")
        void getDialect_uninitializedThrows() {
            // Arrange
            DbContext dbContext = new DbContext();

            // Act & Assert
            assertThrows(NullPointerException.class, () -> dbContext.getDialect());
        }

        @Test
        @DisplayName("commitTransaction throws IllegalStateException when not in transaction")
        void commitTransaction_notInTransactionThrows() {
            // Arrange
            DbContext dbContext = new DbContext();

            // Act & Assert
            // isInTransaction() returns false since localConnection is null,
            // so "Not in transaction" is thrown before we reach databaseInstance access
            assertThrows(IllegalStateException.class, () -> dbContext.commitTransaction());
        }

        @Test
        @DisplayName("rollbackTransaction throws IllegalStateException when not in transaction")
        void rollbackTransaction_notInTransactionThrows() {
            // Arrange
            DbContext dbContext = new DbContext();

            // Act & Assert
            assertThrows(IllegalStateException.class, () -> dbContext.rollbackTransaction());
        }

        @Test
        @DisplayName("endTransaction throws IllegalStateException when not in transaction")
        void endTransaction_notInTransactionThrows() {
            // Arrange
            DbContext dbContext = new DbContext();

            // Act & Assert
            assertThrows(IllegalStateException.class, () -> dbContext.endTransaction());
        }
    }
}