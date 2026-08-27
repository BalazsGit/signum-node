package application.module.node.util;

import application.module.node.props.CaselessProperties;
import application.module.node.props.PropertyServiceImpl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link ThreadPool#running} global flag lifecycle.
 * <p>
 * Regression test for the restart zombie + log flood: the flag is cleared when
 * a pool shuts down and must be re-armed when a pool (re)starts. Previously a
 * single stop cleared the flag permanently, so every pool started afterwards
 * (e.g. the restart sequence) ran with all worker loops short-circuited and
 * every periodic task execution spammed "… stopped." log lines.
 * </p>
 * <p>
 * The flag and the pool reference count are static (JVM-global), so each test
 * keeps the start/shutdown balance to stay deterministic.
 * </p>
 */
@DisplayName("ThreadPool running-flag (restart/multi-node) Tests")
class ThreadPoolRunningFlagTest {

    private ThreadPool newPool() {
        return new ThreadPool(new PropertyServiceImpl(new CaselessProperties()));
    }

    @Test
    @DisplayName("flag is re-armed on start, cleared when the last pool stops")
    void flagLifecycleSinglePool() {
        ThreadPool pool = newPool();
        pool.start(1);
        assertTrue(ThreadPool.running.get(), "running must be true while a pool is active");

        pool.shutdown();
        assertFalse(ThreadPool.running.get(), "running must be false after the last pool stopped");
    }

    @Test
    @DisplayName("restart: pool started after a stop must run with the flag armed")
    void flagRearmedAfterRestart() {
        // First node generation: start + stop (e.g. the stop half of a restart).
        ThreadPool oldPool = newPool();
        oldPool.start(1);
        oldPool.shutdown();
        assertFalse(ThreadPool.running.get(), "precondition: flag cleared after the stop");

        // New pool (what a restart creates): the flag MUST be armed again,
        // otherwise all worker loops gate out and the node is a zombie.
        ThreadPool newPool = newPool();
        newPool.start(1);
        assertTrue(ThreadPool.running.get(),
                "running must be true again after a pool starts (restart case)");
        newPool.shutdown();
    }

    @Test
    @DisplayName("stopping one of two pools must not stop the other's loops")
    void flagStaysArmedWhileAnotherPoolIsRunning() {
        ThreadPool poolA = newPool();
        ThreadPool poolB = newPool();
        poolA.start(1);
        poolB.start(1);

        // Multi-node: stopping profile A must not clear the global flag while
        // profile B is still running.
        poolA.shutdown();
        assertTrue(ThreadPool.running.get(),
                "running must stay true while another pool is still active");

        poolB.shutdown();
        assertFalse(ThreadPool.running.get(), "running must be false after the last pool stopped");
    }
}
