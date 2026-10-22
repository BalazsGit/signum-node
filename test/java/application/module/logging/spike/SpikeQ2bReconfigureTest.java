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
 * P0 gate Q2b: {@code ctx.reconfigure()} on a named context must pick up the changed
 * generated .properties file AND the ALREADY EXISTING logger instances (held by node
 * components) must reflect the new level without re-fetching — the per-node hot reapply
 * (GUI "Apply") depends on it.
 */
@DisplayName("P0 Q2b - reconfigure() rebinds existing logger instances")
class SpikeQ2bReconfigureTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("the same logger instance reflects the new level after reconfigure()")
    void reconfigureAppliesToExistingLoggerInstances() throws Exception {
        Path file = tempDir.resolve("spike-q2b.properties");
        Files.writeString(file, "status = ERROR\nrootLogger.level = INFO\n");

        LoggerContext ctx = SpikeSupport.startContext("spike-q2b", file);
        try {
            Logger logger = ctx.getLogger("spike.q2b");
            assertFalse(logger.isDebugEnabled(), "INFO root: debug must be off before reconfigure");

            Files.writeString(file, "status = ERROR\nrootLogger.level = DEBUG\n");
            ctx.reconfigure();

            assertTrue(logger.isDebugEnabled(),
                    "the SAME logger instance must reflect the new level after reconfigure()");
        } finally {
            SpikeSupport.stopQuietly(ctx);
        }
    }
}
