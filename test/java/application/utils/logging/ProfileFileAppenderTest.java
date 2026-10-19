package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the per-profile {@link ProfileFileAppender} (per-node file
 * logging). Follows AAA + JUnit 5 conventions.
 */
@DisplayName("ProfileFileAppender Tests")
class ProfileFileAppenderTest {

    @TempDir
    Path tempDir;

    private static LogEvent event(LogLevel level, String message) {
        return new LogEvent.Builder()
                .level(level)
                .message(message)
                .threadName(Thread.currentThread().getName())
                .build();
    }

    @Test
    @DisplayName("writes INFO+ events to {logDir}/{profile}.log; events below the minimum level are dropped")
    void writesInfoAndAbove_only() throws IOException {
        ProfileFileAppender appender = new ProfileFileAppender("mainnet", tempDir, LogLevel.INFO, 0, 1);
        assertTrue(appender.isActive());
        assertEquals(tempDir.resolve("mainnet.log"), appender.getFile());

        appender.onLogEvent(event(LogLevel.DEBUG, "debug-line"));
        appender.onLogEvent(event(LogLevel.INFO, "info-line"));
        appender.onLogEvent(event(LogLevel.ERROR, "error-line"));
        appender.dispose();

        List<String> lines = Files.readAllLines(appender.getFile(), StandardCharsets.UTF_8);
        assertTrue(lines.stream().anyMatch(l -> l.contains("info-line")), "INFO must be written");
        assertTrue(lines.stream().anyMatch(l -> l.contains("error-line")), "ERROR must be written");
        assertFalse(lines.stream().anyMatch(l -> l.contains("debug-line")), "DEBUG must be dropped at INFO");
    }

    @Test
    @DisplayName("size-based rotation: the full file is moved to .1 and a fresh file starts")
    void rotatesWhenLimitReached() throws IOException {
        // Measure the exact rendered line length once (all subsequent lines are
        // the same length: fixed prefix + fixed-width message).
        ProfileFileAppender measurer = new ProfileFileAppender("measure", tempDir, LogLevel.INFO, 0, 1);
        measurer.onLogEvent(event(LogLevel.INFO, String.format("rotation-line-%02d", 0)));
        measurer.dispose();
        long lineLen = Files.size(tempDir.resolve("measure.log"));
        assertTrue(lineLen > 0, "the measurement line must have been written");

        // limit = 3 lines: the 4th write rotates (3 full lines -> .1), then two
        // more lines land in the fresh file.
        ProfileFileAppender appender = new ProfileFileAppender("rotator", tempDir, LogLevel.INFO, 3 * lineLen, 1);
        for (int i = 0; i < 5; i++) {
            appender.onLogEvent(event(LogLevel.INFO, String.format("rotation-line-%02d", i)));
        }
        appender.dispose();

        Path current = tempDir.resolve("rotator.log");
        Path rotated = tempDir.resolve("rotator.log.1");
        assertTrue(Files.exists(rotated), "the rotated file must exist after the limit is reached");
        String old = String.join("\n", Files.readAllLines(rotated, StandardCharsets.UTF_8));
        String fresh = String.join("\n", Files.readAllLines(current, StandardCharsets.UTF_8));
        assertTrue(old.contains("rotation-line-00"), "the first lines must be in the rotated file");
        assertTrue(old.contains("rotation-line-02"), "all 3 pre-rotation lines must be in the rotated file");
        assertTrue(fresh.contains("rotation-line-03"), "the 4th line must be in the fresh file");
        assertTrue(fresh.contains("rotation-line-04"), "the latest line must be in the fresh file");
        assertFalse(fresh.contains("rotation-line-00"), "pre-rotation lines must NOT be in the fresh file");
    }

    @Test
    @DisplayName("dispose closes the file; no further writes happen")
    void disposeStopsWriting() throws IOException {
        ProfileFileAppender appender = new ProfileFileAppender("oncemore", tempDir, LogLevel.INFO, 0, 1);
        appender.onLogEvent(event(LogLevel.INFO, "before-dispose"));
        appender.dispose();
        appender.onLogEvent(event(LogLevel.INFO, "after-dispose"));
        appender.dispose(); // idempotent

        List<String> lines = Files.readAllLines(appender.getFile(), StandardCharsets.UTF_8);
        assertTrue(lines.stream().anyMatch(l -> l.contains("before-dispose")));
        assertFalse(lines.stream().anyMatch(l -> l.contains("after-dispose")), "no writes after dispose");
    }

    @Test
    @DisplayName("concurrent writes from many threads lose no lines (smoke)")
    void concurrentWrites_loseNoLines() throws Exception {
        int threads = 4;
        int perThread = 25;
        ProfileFileAppender appender = new ProfileFileAppender("concurrent", tempDir, LogLevel.INFO, 0, 1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            appender.onLogEvent(event(LogLevel.INFO, "marker-t" + threadId + "-" + i));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "all writers must finish");
        } finally {
            pool.shutdownNow();
            appender.dispose();
        }

        String all = String.join("\n", Files.readAllLines(appender.getFile(), StandardCharsets.UTF_8));
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < perThread; i++) {
                assertTrue(all.contains("marker-t" + t + "-" + i), "every marker must be present");
            }
        }
    }

    @Test
    @DisplayName("an unopenable target goes inactive (fail-soft): no exception, no events written")
    void unopenableTarget_goesInactive() throws IOException {
        // A regular FILE at the logDir position makes directory creation impossible.
        Path blockingFile = tempDir.resolve("blocked");
        Files.writeString(blockingFile, "not a directory");

        ProfileFileAppender appender = new ProfileFileAppender("blockedprofile", blockingFile, LogLevel.INFO, 0, 1);

        assertFalse(appender.isActive(), "the appender must report inactivity instead of throwing");
        appender.onLogEvent(event(LogLevel.INFO, "should-not-appear")); // no throw
        appender.dispose();
    }

    @Test
    @DisplayName("parseLevel maps config names onto LogLevel (case-insensitive, INFO fallback)")
    void parseLevel_mapping() {
        assertEquals(LogLevel.TRACE, ProfileFileAppender.parseLevel("TRACE"));
        assertEquals(LogLevel.DEBUG, ProfileFileAppender.parseLevel("debug"));
        assertEquals(LogLevel.INFO, ProfileFileAppender.parseLevel("INFO"));
        assertEquals(LogLevel.WARN, ProfileFileAppender.parseLevel("WARNING"));
        assertEquals(LogLevel.ERROR, ProfileFileAppender.parseLevel("error"));
        assertEquals(LogLevel.OFF, ProfileFileAppender.parseLevel("off"));
        assertEquals(LogLevel.INFO, ProfileFileAppender.parseLevel(null));
        assertEquals(LogLevel.INFO, ProfileFileAppender.parseLevel("  "));
        assertEquals(LogLevel.INFO, ProfileFileAppender.parseLevel("bogus"));
    }
}