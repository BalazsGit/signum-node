package application.module.logging.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T19 (acceptance, plan §13/P7.1): a malformed / corrupt node logging configuration
 * must NOT break the node. The generated {@code .log4j2.properties} files are the
 * Log4j2 inputs of a node context, and Log4j2's configuration loader is defensive:
 * a bad file (a truncated atomic write, disk corruption, a manual edit) degrades to
 * Log4j2's default configuration instead of throwing out of node start or an Apply.
 * So a corrupted config is a degraded (but alive, logging) node — never a crashed one.
 * <p>
 * Headless-safe (no Swing).
 */
@DisplayName("T19 - a corrupt node logging config degrades gracefully (never breaks the node)")
class BadConfigRecoveryTest {

    @TempDir
    Path confRoot;

    private final NodeLoggingContextManager manager = NodeLoggingContextManager.ofCurrentFactory();

    /** Writes the (generated) config file for a profile and returns its path. */
    private Path config(final String name, final String content) throws Exception {
        Path cfg = confRoot.resolve(name + ".log4j2.properties");
        Files.writeString(cfg, content);
        return cfg;
    }

    /** A minimal but valid Log4j2 properties config (root INFO + console appender). */
    private static final String VALID_CONSOLE_CONFIG =
            "status = ERROR\n"
                    + "configWatch = false\n"
                    + "rootLogger.level = INFO\n"
                    + "appender.console.type = Console\n"
                    + "appender.console.name = console\n"
                    + "appender.console.layout.type = PatternLayout\n"
                    + "appender.console.layout.pattern = %msg%n\n"
                    + "rootLogger.appenderRef.console.ref = console\n";

    /** Readable but not a valid Log4j2 config: no parsable key = default configuration. */
    private static final String CORRUPT =
            "%%%CORRUPT%%% this is not a valid log4j2.properties file\n### broken ###\n";

    @Test
    @DisplayName("corrupt config file at node start: the node still starts and can log (Log4j2 fallback)")
    void corruptConfigAtStart_nodeStartsAndLogs() throws Exception {
        final String name = "t19-start-" + System.nanoTime();
        Path bad = config(name, CORRUPT);
        NodeLoggingContext ctx = assertDoesNotThrow(() -> manager.getOrCreate(name, bad.toUri()),
                "a corrupt config file must not throw out of node start");
        try {
            assertTrue(ctx.isStarted(), "the context must be started despite the corrupt config");
            assertNotNull(ctx.loggerContext().getConfiguration().getRootLogger(),
                    "a (fallback) root logger must exist so the node can still log");
            assertDoesNotThrow(
                    () -> ctx.loggerContext().getLogger("t19.test").info("survived a corrupt config"),
                    "logging must not throw after starting from a corrupt config");
        } finally {
            manager.remove(name);
        }
    }

    @Test
    @DisplayName("an Apply that reloads a corrupt file into a RUNNING node: the node survives and keeps logging")
    void corruptConfigAtApply_runningNodeSurvives() throws Exception {
        final String name = "t19-apply-" + System.nanoTime();
        Path file = config(name, VALID_CONSOLE_CONFIG);
        NodeLoggingContext ctx = manager.getOrCreate(name, file.toUri());
        try {
            assertTrue(ctx.isStarted(), "precondition: the node starts with the valid config");
            // capture the effective root level of the VALID config (audit §4.2 case a)
            assertEquals(org.apache.logging.log4j.Level.INFO,
                    ctx.loggerContext().getConfiguration().getRootLogger().getLevel(),
                    "precondition: the valid config resolves the root to INFO");
            // simulate the (regenerated) config file becoming corrupt (truncated atomic write / disk error)
            Files.writeString(file, CORRUPT);
            // an Apply reloads the corrupt file into the SAME running context
            assertDoesNotThrow(ctx::reconfigure,
                    "an Apply that reloads a corrupt config must not break the running node");
            assertNotNull(ctx.loggerContext().getConfiguration().getRootLogger(),
                    "a (fallback) root logger must still exist after a corrupt Apply");
            // audit §4.2 case (a): a FAILED reconfigure must KEEP the old working config
            // active (root level unchanged) — NOT degrade the node to Log4j2's default config
            assertEquals(org.apache.logging.log4j.Level.INFO,
                    ctx.loggerContext().getConfiguration().getRootLogger().getLevel(),
                    "a corrupt reconfigure must keep the OLD (valid) config — root level must stay INFO");
            assertDoesNotThrow(
                    () -> ctx.loggerContext().getLogger("t19.test").warn("still alive after a corrupt Apply"),
                    "the node must still be able to log after a corrupt Apply");
        } finally {
            manager.remove(name);
        }
    }
}