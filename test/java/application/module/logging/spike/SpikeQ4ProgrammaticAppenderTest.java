package application.module.logging.spike;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 gate Q4: the GUI console buffer (P2) will attach a programmatic
 * {@code AbstractAppender} to the node's context root. These tests pin down the
 * log4j 2.26.1 contract:
 * <ul>
 *   <li>the appender receives events after {@code updateLoggers()},</li>
 *   <li>{@code reconfigure()} DETACHES programmatic appenders from the regenerated
 *       configuration but does NOT stop them, and</li>
 *   <li>re-attaching the same (still started) instance delivers EXACTLY ONCE —
 *       {@code addAppender} de-duplicates by name, so P2 can safely re-attach the
 *       buffer appender after every reconfigure without starting a new one.</li>
 * </ul>
 */
@DisplayName("P0 Q4 - programmatic appenders vs reconfigure()")
class SpikeQ4ProgrammaticAppenderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a programmatic appender receives events after updateLoggers()")
    void programmaticAppenderReceivesEvents() throws Exception {
        Path file = tempDir.resolve("spike-q4a.properties");
        Files.writeString(file, "status = ERROR\nrootLogger.level = DEBUG\n");
        LoggerContext ctx = SpikeSupport.startContext("spike-q4a", file);
        try {
            CountingAppender appender = new CountingAppender("spike-q4a");
            appender.start();
            ctx.getConfiguration().getRootLogger().addAppender(appender, Level.DEBUG, null);
            ctx.updateLoggers();

            ctx.getLogger("spike.q4").info("message-1");
            assertEquals(1, appender.count.get(),
                    "the programmatic appender must receive the event exactly once");
        } finally {
            SpikeSupport.stopQuietly(ctx);
        }
    }

    @Test
    @DisplayName("reconfigure() detaches (but keeps running) the programmatic appender; re-attach delivers once")
    void reconfigureDetachesButKeepsProgrammaticAppenderRunning() throws Exception {
        Path file = tempDir.resolve("spike-q4b.properties");
        Files.writeString(file, "status = ERROR\nrootLogger.level = DEBUG\n");
        LoggerContext ctx = SpikeSupport.startContext("spike-q4b", file);
        try {
            CountingAppender appender = new CountingAppender("spike-q4b");
            appender.start();
            ctx.getConfiguration().getRootLogger().addAppender(appender, Level.DEBUG, null);
            ctx.updateLoggers();

            ctx.reconfigure();
            boolean stillStarted = appender.isStarted();
            boolean stillAttached = ctx.getConfiguration().getRootLogger().getAppenders().containsKey("spike-q4b");
            System.out.println("SPIKE Q4 RECONFIGURE: stillStarted = " + stillStarted
                    + ", stillAttached = " + stillAttached);
            // Learned 2.26.1 contract (P2 must honor it): reconfigure() DETACHES programmatic
            // appenders from the regenerated configuration but does NOT stop them.
            assertTrue(stillStarted,
                    "2.26.1: reconfigure() keeps the detached programmatic appender started (P2 reuses the instance)");
            assertFalse(stillAttached,
                    "2.26.1: the appender is NOT part of the regenerated configuration (P2 must re-attach)");

            ctx.getLogger("spike.q4").info("after-reconfigure");
            assertEquals(0, appender.count.get(),
                    "a detached appender receives nothing until P2 re-attaches it after reconfigure()");

            // P2 pattern: re-attach the SAME (still started) instance — no re-start, no duplicate.
            ctx.getConfiguration().getRootLogger().addAppender(appender, Level.DEBUG, null);
            ctx.updateLoggers();
            ctx.getLogger("spike.q4").info("after-reattach");
            assertEquals(1, appender.count.get(),
                    "re-attaching the same started instance delivers exactly once (no duplicate)");
        } finally {
            SpikeSupport.stopQuietly(ctx);
        }
    }

    @Test
    @DisplayName("EVIDENCE: re-adding an already-attached appender must not duplicate deliveries")
    void reattachWhileAttachedDuplicateCheck() throws Exception {
        Path file = tempDir.resolve("spike-q4c.properties");
        Files.writeString(file, "status = ERROR\nrootLogger.level = DEBUG\n");
        LoggerContext ctx = SpikeSupport.startContext("spike-q4c", file);
        try {
            CountingAppender appender = new CountingAppender("spike-q4c");
            appender.start();
            ctx.getConfiguration().getRootLogger().addAppender(appender, Level.DEBUG, null);
            ctx.updateLoggers();

            // Re-add the SAME appender without detaching (what P2 must avoid or detect).
            ctx.getConfiguration().getRootLogger().addAppender(appender, Level.DEBUG, null);
            ctx.updateLoggers();

            ctx.getLogger("spike.q4").info("once");
            System.out.println("SPIKE Q4 REATTACH: deliveries = " + appender.count.get());
            assertEquals(1, appender.count.get(),
                    "re-adding the same appender must not duplicate deliveries");
        } finally {
            SpikeSupport.stopQuietly(ctx);
        }
    }

    /** Minimal recording appender used to observe deliveries. */
    static final class CountingAppender extends AbstractAppender {

        final AtomicLong count = new AtomicLong();

        CountingAppender(String name) {
            super(name, null, PatternLayout.createDefaultLayout(), false);
        }

        @Override
        public void append(LogEvent event) {
            count.incrementAndGet();
        }
    }
}
