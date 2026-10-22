package application.module.logging.spike;

import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 gate Q6 (risk K8): {@code stop()} then {@code start()} on the SAME named context
 * instance must work for a node restart, followed by {@code reconfigure()} re-applying the
 * generated configuration — with NO duplicate appenders. If this fails, the fallback
 * (create a fresh context on every restart) is triggered in P1.
 */
@DisplayName("P0 Q6 - stop()/start() restart cycle of a named context")
class SpikeQ6StopRestartTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("stop() then start() + reconfigure() reopens appenders without duplicates")
    void stopThenStartCycle() throws Exception {
        // Forward slashes: backslash is the escape character in .properties VALUES and the
        // log4j2 properties builder hands them to the File appender un-escaped (P1 generator
        // must emit forward slashes or double-backslashes).
        String logFilePath = tempDir.resolve("spike-q6.log").toString().replace('\\', '/');
        Path file = tempDir.resolve("spike-q6.properties");
        String config = String.join("\n",
                "status = ERROR",
                "rootLogger.level = DEBUG",
                "appender.file.name = file",
                "appender.file.type = File",
                "appender.file.fileName = " + logFilePath,
                "appender.file.layout.type = PatternLayout",
                "appender.file.layout.pattern = %m%n",
                "rootLogger.appenderRef.file.ref = file");
        Files.writeString(file, config);

        LoggerContext ctx = SpikeSupport.startContext("spike-q6", file);
        try {
            ctx.getLogger("spike.q6").info("first");
            Thread.sleep(500);

            ctx.stop();
            assertFalse(ctx.isStarted(), "stop() must stop the context");

            ctx.start();
            assertTrue(ctx.isStarted(), "start() after stop() must restart the same context instance");
            ctx.reconfigure();
            assertTrue(ctx.getLogger("spike.q6").isDebugEnabled(),
                    "after restart + reconfigure the configuration must be active again");

            ctx.getLogger("spike.q6").info("second");
            Thread.sleep(500);

            Path logFile = tempDir.resolve("spike-q6.log");
            String content = Files.readString(logFile);
            assertTrue(content.contains("first"), "the line logged before stop must be in the file");
            assertTrue(content.contains("second"),
                    "the line logged after restart must reach the reopened file appender");

            assertEquals(1, ctx.getConfiguration().getRootLogger().getAppenders().size(),
                    "the restart must not duplicate the file appender");
        } finally {
            SpikeSupport.stopQuietly(ctx);
        }
    }
}
