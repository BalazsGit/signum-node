package application.utils.sqlite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the SQLite JDBC native library can be loaded through
 * {@link SqliteNativeSupport} in the test JVM (the same real native-load path
 * the application uses at startup).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("SqliteNativeSupport Tests")
class SqliteNativeSupportTest {

    @Test
    @Order(1)
    @DisplayName("ensureLoaded loads the native library")
    void ensureLoaded_LoadsNativeLibrary() {
        assertTrue(SqliteNativeSupport.ensureLoaded());
    }

    @Test
    @Order(2)
    @DisplayName("ensureLoaded is idempotent once the library is loaded")
    void ensureLoaded_IsIdempotent() {
        assertTrue(SqliteNativeSupport.ensureLoaded());
        assertTrue(SqliteNativeSupport.ensureLoaded());
    }

    @Test
    @DisplayName("prewarm never throws")
    void prewarm_NeverThrows() {
        assertDoesNotThrow(SqliteNativeSupport::prewarm);
    }
}
