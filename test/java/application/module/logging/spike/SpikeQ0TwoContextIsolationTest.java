package application.module.logging.spike;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 gates (1)+(2): two independently created contexts (object references, D7) that use the
 * SAME logger name must be isolated — different effective levels, and reconfiguring one
 * context must NOT affect the other.
 */
@DisplayName("P0 gates 1+2 - two-context isolation (same logger name)")
class SpikeQ0TwoContextIsolationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("same logger name in two contexts: independent levels, independent reconfigure")
    void twoContextsSameLoggerNameAreIsolated() throws Exception {
        Path infoFile = tempDir.resolve("spike-iso-a.properties");
        Path debugFile = tempDir.resolve("spike-iso-b.properties");
        Files.writeString(infoFile, "status = ERROR\nrootLogger.level = INFO\n");
        Files.writeString(debugFile, "status = ERROR\nrootLogger.level = DEBUG\n");

        LoggerContext a = SpikeSupport.startContext("spike-iso-a", infoFile);
        LoggerContext b = SpikeSupport.startContext("spike-iso-b", debugFile);
        try {
            Logger loggerA = a.getLogger("spike.shared.name");
            Logger loggerB = b.getLogger("spike.shared.name");

            // Gate 1: same logger name, different contexts -> different effective level.
            assertFalse(loggerA.isDebugEnabled(), "context A (INFO root): debug must be off");
            assertTrue(loggerB.isDebugEnabled(), "context B (DEBUG root): debug must be on");

            // Gate 2: reconfiguring A does not affect B (and vice versa).
            Files.writeString(infoFile, "status = ERROR\nrootLogger.level = DEBUG\n");
            a.reconfigure();
            assertTrue(loggerA.isDebugEnabled(), "A picks up its own new level");

            Files.writeString(debugFile, "status = ERROR\nrootLogger.level = INFO\n");
            b.reconfigure();
            assertFalse(loggerB.isDebugEnabled(), "B picks up its own new level");
            assertTrue(loggerA.isDebugEnabled(), "A must be unaffected by B's reconfigure");
        } finally {
            SpikeSupport.stopQuietly(a);
            SpikeSupport.stopQuietly(b);
        }
    }
}