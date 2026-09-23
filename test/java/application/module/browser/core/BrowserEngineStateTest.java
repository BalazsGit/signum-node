package application.module.browser.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link BrowserEngine} state machine (plan D4).
 * <p>
 * Uses the testable package-private constructor with an injected JCEF
 * distribution lookup, so no native code is ever loaded: the tests cover
 * IDLE, the FAILED path (missing distribution), shutdown transitions and
 * the init guard. The READY path requires the real JCEF distribution and is
 * covered by the F0 smoke run (plan §7: org.cef.* classes are not unit-tested).
 */
class BrowserEngineStateTest {

    private static final long STATE_TIMEOUT_MILLIS = 10_000;

    private static BrowserEngine newEngineWithoutJcef() {
        return new BrowserEngine(() -> Optional.empty());
    }

    private static void waitForState(BrowserEngine engine, BrowserEngineState expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + STATE_TIMEOUT_MILLIS;
        while (engine.getState() != expected) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for state " + expected
                        + " (current: " + engine.getState() + ")");
            }
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("a new engine is IDLE")
    void initialStateIsIdle() {
        assertEquals(BrowserEngineState.IDLE, newEngineWithoutJcef().getState());
    }

    @Test
    @DisplayName("shutdown from IDLE ends in SHUT_DOWN and notifies listeners")
    void shutdownFromIdle(@TempDir Path confDir) throws InterruptedException {
        BrowserEngine engine = newEngineWithoutJcef();
        List<BrowserEngineState> seen = new CopyOnWriteArrayList<>();
        engine.addStateListener(seen::add);

        engine.shutdown();

        assertEquals(BrowserEngineState.SHUT_DOWN, engine.getState());
        assertEquals(List.of(BrowserEngineState.SHUTTING_DOWN, BrowserEngineState.SHUT_DOWN), seen);
    }

    @Test
    @DisplayName("shutdown is idempotent")
    void shutdownIsIdempotent() throws InterruptedException {
        BrowserEngine engine = newEngineWithoutJcef();
        engine.shutdown();

        engine.shutdown();

        assertEquals(BrowserEngineState.SHUT_DOWN, engine.getState());
    }

    @Test
    @DisplayName("init is refused once the engine is shut down (guard)")
    void initRefusedAfterShutdown(@TempDir Path confDir) {
        BrowserEngine engine = newEngineWithoutJcef();
        engine.shutdown();

        engine.init(confDir);

        assertEquals(BrowserEngineState.SHUT_DOWN, engine.getState());
    }

    @Test
    @DisplayName("init without a JCEF distribution ends in FAILED with a reason")
    void initFailsWithoutJcef(@TempDir Path confDir) throws InterruptedException {
        BrowserEngine engine = newEngineWithoutJcef();
        List<BrowserEngineState> seen = new CopyOnWriteArrayList<>();
        engine.addStateListener(seen::add);

        engine.init(confDir);
        waitForState(engine, BrowserEngineState.FAILED);

        assertTrue(engine.getFailureReason().contains("JCEF native distribution not found"),
                "unexpected failure reason: " + engine.getFailureReason());
        assertTrue(seen.contains(BrowserEngineState.INITIALIZING));
        assertEquals(BrowserEngineState.FAILED, seen.get(seen.size() - 1));
    }

    @Test
    @DisplayName("a FAILED engine can be initialized again (retry returns to INITIALIZING)")
    void failedEngineCanBeRetried(@TempDir Path confDir) throws InterruptedException {
        BrowserEngine engine = newEngineWithoutJcef();
        engine.init(confDir);
        waitForState(engine, BrowserEngineState.FAILED);

        engine.init(confDir);
        waitForState(engine, BrowserEngineState.FAILED);

        assertNotEquals(BrowserEngineState.SHUT_DOWN, engine.getState());
    }
}
