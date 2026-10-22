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
 * P0 gate Q3: does the .properties configurator auto-reload on its own, and is the
 * {@code configWatch} key recognized?
 *
 * <ul>
 *   <li>The DEFAULT test is deliberately non-asserting: it RECORDS the observed behavior
 *       (printed as {@code SPIKE Q3 DEFAULT: auto-reload observed = true|false}) which is
 *       the actual gate answer.</li>
 *   <li>The {@code configWatch=false} test asserts the contract we need: an explicit
 *       {@code configWatch = false} line suppresses auto-reload while manual
 *       {@code reconfigure()} still works (the node regenerates the file itself, so we
 *       must opt out of the built-in watcher).</li>
 * </ul>
 */
@DisplayName("P0 Q3 - properties configurator auto-reload (configWatch)")
class SpikeQ3ConfigWatchTest {

    private static final int OBSERVE_WINDOW_MS = 15_000;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("records whether the properties configurator auto-reloads by default")
    void defaultAutoReloadBehavior() throws Exception {
        Path file = tempDir.resolve("spike-q3-default.properties");
        Files.writeString(file, "status = ERROR\nrootLogger.level = INFO\n");
        LoggerContext ctx = SpikeSupport.startContext("spike-q3-default", file);
        try {
            Logger logger = ctx.getLogger("spike.q3");
            assertFalse(logger.isDebugEnabled(), "INFO root: debug must be off before the file change");

            Files.writeString(file, "status = ERROR\nrootLogger.level = DEBUG\n");
            long deadline = System.currentTimeMillis() + OBSERVE_WINDOW_MS;
            while (System.currentTimeMillis() < deadline && !logger.isDebugEnabled()) {
                Thread.sleep(250);
            }
            boolean reloaded = logger.isDebugEnabled();
            System.out.println("SPIKE Q3 DEFAULT: auto-reload observed = " + reloaded);
            // Non-asserting on purpose: this test is the gate evidence, not a contract.
            assertTrue(true, "recorded auto-reload=" + reloaded);
        } finally {
            SpikeSupport.stopQuietly(ctx);
        }
    }

    @Test
    @DisplayName("configWatch=false suppresses auto-reload; manual reconfigure() still works")
    void explicitConfigWatchFalseDisablesAutoReload() throws Exception {
        Path file = tempDir.resolve("spike-q3-off.properties");
        Files.writeString(file, "status = ERROR\nconfigWatch = false\nrootLogger.level = INFO\n");
        LoggerContext ctx = SpikeSupport.startContext("spike-q3-off", file);
        try {
            Logger logger = ctx.getLogger("spike.q3");
            assertFalse(logger.isDebugEnabled());

            Files.writeString(file, "status = ERROR\nconfigWatch = false\nrootLogger.level = DEBUG\n");
            Thread.sleep(5_000);
            assertFalse(logger.isDebugEnabled(),
                    "with configWatch=false the level must NOT change on its own");

            ctx.reconfigure();
            assertTrue(logger.isDebugEnabled(), "manual reconfigure() must still apply the new level");
        } finally {
            SpikeSupport.stopQuietly(ctx);
        }
    }
}
