package application.module.logging.spike;

import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 gate Q2a: a NAMED {@link LoggerContext} constructed directly from a generated
 * .properties file ({@code new LoggerContext(name, null, uri)}) must load and apply that
 * configuration — the isolation primitive of the D7 architecture.
 */
@DisplayName("P0 Q2a - named LoggerContext from generated .properties")
class SpikeQ2aContextFromPropertiesTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("named context from generated properties applies the config to its loggers")
    void namedContextLoadsGeneratedProperties() throws Exception {
        Path file = tempDir.resolve("spike-q2a.properties");
        Files.writeString(file, "status = ERROR\nrootLogger.level = DEBUG\n");

        LoggerContext ctx = SpikeSupport.startContext("spike-q2a", file);
        try {
            assertEquals("DEBUG", String.valueOf(ctx.getConfiguration().getRootLogger().getLevel()),
                    "the root level of the named context must come from the generated file");
            assertTrue(ctx.getLogger("spike.q2a").isDebugEnabled(),
                    "a logger of the named context must honor the generated config");
        } finally {
            SpikeSupport.stopQuietly(ctx);
        }
    }
}
