package application.module.logging.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T10 (acceptance, plan §13/P7.1, §14.4-A5): across many node
 * start&#8594;stop&#8594;dispose cycles nothing must leak — each disposed node
 * context is really stopped (its appenders, hence file handles, closed), the
 * manager map drops it, a later create for the same name is a FRESH context,
 * and the JVM-wide global context is untouched. Headless-safe (no Swing).
 */
@DisplayName("T10 - no context/file-handle leak across start&#8594;stop&#8594;dispose cycles")
class ContextLeakTest {

    @TempDir
    Path confRoot;

    /** Writes a config with a real RollingFile appender so start/stop actually open/close a file handle. */
    private Path configWithFileAppender(String name) throws Exception {
        Path logFile = confRoot.resolve(name + ".log");
        Path cfg = confRoot.resolve(name + ".log4j2.properties");
        Files.writeString(cfg,
                "status = ERROR\n"
                        + "configWatch = false\n"
                        + "rootLogger.level = INFO\n"
                        + "appender.rolling.type = RollingFile\n"
                        + "appender.rolling.name = RollingFile\n"
                        + "appender.rolling.fileName = " + logFile.toAbsolutePath() + "\n"
                        + "appender.rolling.filePattern = " + logFile.toAbsolutePath() + ".%i\n"
                        + "appender.rolling.policies.type = Policies\n"
                        + "appender.rolling.policies.size.type = SizeBasedTriggeringPolicy\n"
                        + "appender.rolling.policies.size.size = 10MB\n"
                        + "appender.rolling.strategy.type = DefaultRolloverStrategy\n"
                        + "appender.rolling.strategy.max = 1\n"
                        + "appender.rolling.layout.type = PatternLayout\n"
                        + "appender.rolling.layout.pattern = %msg%n\n"
                        + "rootLogger.appenderRef.rolling.ref = RollingFile\n");
        return cfg;
    }

    @Test
    @DisplayName("N start->stop->dispose cycles: every context stopped, manager clean, global intact")
    void noLeakAcrossStartStopDisposeCycles() throws Exception {
        NodeLoggingContextManager manager = NodeLoggingContextManager.ofCurrentFactory();
        final int cycles = 8;
        List<NodeLoggingContext> disposed = new ArrayList<>();
        for (int i = 0; i < cycles; i++) {
            String name = "leak-" + i;
            NodeLoggingContext ctx = manager.getOrCreate(name, configWithFileAppender(name).toUri());
            assertTrue(ctx.isStarted(), "getOrCreate must return a started context");
            ctx.loggerContext().getLogger("leak.test").info("cycle-{}", i); // force the appender open
            ctx.stop(); // node stop: context stays registered but is stopped
            assertFalse(ctx.isStarted(), "stop() must stop the context (close its appenders)");
            assertTrue(manager.remove(name), "remove() must dispose the context");
            assertNull(manager.get(name), "the manager must not retain a disposed context");
            assertFalse(ctx.isStarted(), "a disposed context must stay stopped");
            disposed.add(ctx);
        }
        // no accumulated still-started contexts across all cycles
        for (NodeLoggingContext ctx : disposed) {
            assertFalse(ctx.isStarted(), "leaked (still started) node context");
        }
        // a fresh create for a previously-disposed name is a NEW started context
        NodeLoggingContext fresh = manager.getOrCreate("leak-0", configWithFileAppender("leak-0").toUri());
        try {
            assertTrue(fresh.isStarted());
        } finally {
            assertTrue(manager.remove("leak-0"));
        }
        // the JVM-wide global context must be untouched by all the node churn
        assertTrue(NodeSystemContext.shared().loggerContext().isStarted(),
                "the shared global context must survive the node dispose cycles");
    }
}