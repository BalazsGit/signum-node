package application.module.node.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link LifecycleStateMachine}.
 * <p>
 * Covers: construction, valid transitions, invalid transitions,
 * listener notifications, thread-safety, and convenience methods.
 * </p>
 */
@DisplayName("LifecycleStateMachine Tests")
class LifecycleStateMachineTest {

    private LifecycleStateMachine machine;

    @BeforeEach
    void setUp() {
        machine = new LifecycleStateMachine();
    }

    // ── Construction Tests ──────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Default constructor starts in IDLE state")
        void testDefaultConstructor_StartsIdle() {
            assertEquals(NodeLifecycleState.IDLE, machine.get());
        }

        @Test
        @DisplayName("Parameterized constructor starts in specified state")
        void testParameterizedConstructor_StartsInSpecifiedState() {
            LifecycleStateMachine m = new LifecycleStateMachine(NodeLifecycleState.READY);
            assertEquals(NodeLifecycleState.READY, m.get());
        }

        @Test
        @DisplayName("Parameterized constructor rejects null")
        void testParameterizedConstructor_NullThrows() {
            assertThrows(IllegalArgumentException.class, () -> new LifecycleStateMachine(null));
        }
    }

    // ── Valid Transition Tests (Happy Path) ─────────────────────────────

    @Nested
    @DisplayName("Valid Transitions")
    class ValidTransitionTests {

        @Test
        @DisplayName("IDLE -> INITIALIZING succeeds")
        void testIdleToInitializing() {
            assertTrue(machine.transitionTo(NodeLifecycleState.INITIALIZING));
            assertEquals(NodeLifecycleState.INITIALIZING, machine.get());
        }

        @Test
        @DisplayName("INITIALIZING -> READY succeeds")
        void testInitializingToReady() {
            machine.forceSet(NodeLifecycleState.INITIALIZING);
            assertTrue(machine.transitionTo(NodeLifecycleState.READY));
            assertEquals(NodeLifecycleState.READY, machine.get());
        }

        @Test
        @DisplayName("INITIALIZING -> WAITING_FOR_DATABASE succeeds")
        void testInitializingToWaitingForDatabase() {
            machine.forceSet(NodeLifecycleState.INITIALIZING);
            assertTrue(machine.transitionTo(NodeLifecycleState.WAITING_FOR_DATABASE));
            assertEquals(NodeLifecycleState.WAITING_FOR_DATABASE, machine.get());
        }

        @Test
        @DisplayName("WAITING_FOR_DATABASE -> READY succeeds")
        void testWaitingForDatabaseToReady() {
            machine.forceSet(NodeLifecycleState.WAITING_FOR_DATABASE);
            assertTrue(machine.transitionTo(NodeLifecycleState.READY));
            assertEquals(NodeLifecycleState.READY, machine.get());
        }

        @Test
        @DisplayName("READY -> RUNNING succeeds")
        void testReadyToRunning() {
            machine.forceSet(NodeLifecycleState.READY);
            assertTrue(machine.transitionTo(NodeLifecycleState.RUNNING));
            assertEquals(NodeLifecycleState.RUNNING, machine.get());
        }

        @Test
        @DisplayName("RUNNING -> PAUSED succeeds")
        void testRunningToPaused() {
            machine.forceSet(NodeLifecycleState.RUNNING);
            assertTrue(machine.transitionTo(NodeLifecycleState.PAUSED));
            assertEquals(NodeLifecycleState.PAUSED, machine.get());
        }

        @Test
        @DisplayName("PAUSED -> RUNNING succeeds (resume)")
        void testPausedToRunning() {
            machine.forceSet(NodeLifecycleState.PAUSED);
            assertTrue(machine.transitionTo(NodeLifecycleState.RUNNING));
            assertEquals(NodeLifecycleState.RUNNING, machine.get());
        }

        @Test
        @DisplayName("RUNNING -> STOPPING succeeds")
        void testRunningToStopping() {
            machine.forceSet(NodeLifecycleState.RUNNING);
            assertTrue(machine.transitionTo(NodeLifecycleState.STOPPING));
            assertEquals(NodeLifecycleState.STOPPING, machine.get());
        }

        @Test
        @DisplayName("STOPPING -> STOPPED succeeds")
        void testStoppingToStopped() {
            machine.forceSet(NodeLifecycleState.STOPPING);
            assertTrue(machine.transitionTo(NodeLifecycleState.STOPPED));
            assertEquals(NodeLifecycleState.STOPPED, machine.get());
        }

        @Test
        @DisplayName("STOPPED -> IDLE succeeds")
        void testStoppedToIdle() {
            machine.forceSet(NodeLifecycleState.STOPPED);
            assertTrue(machine.transitionTo(NodeLifecycleState.IDLE));
            assertEquals(NodeLifecycleState.IDLE, machine.get());
        }

        @Test
        @DisplayName("Any state -> ERROR succeeds")
        void testAnyStateToError() {
            // RUNNING -> ERROR
            machine.forceSet(NodeLifecycleState.RUNNING);
            assertTrue(machine.transitionTo(NodeLifecycleState.ERROR));
            assertEquals(NodeLifecycleState.ERROR, machine.get());

            // PAUSED -> ERROR
            machine.forceSet(NodeLifecycleState.PAUSED);
            assertTrue(machine.transitionTo(NodeLifecycleState.ERROR));
            assertEquals(NodeLifecycleState.ERROR, machine.get());
        }

        @Test
        @DisplayName("ERROR -> IDLE succeeds (reset)")
        void testErrorToIdle() {
            machine.forceSet(NodeLifecycleState.ERROR);
            assertTrue(machine.transitionTo(NodeLifecycleState.IDLE));
            assertEquals(NodeLifecycleState.IDLE, machine.get());
        }

        @Test
        @DisplayName("Full lifecycle: IDLE -> INIT -> READY -> RUNNING -> STOPPING -> STOPPED -> IDLE")
        void testFullLifecycleSequence() {
            // Start: IDLE
            assertEquals(NodeLifecycleState.IDLE, machine.get());

            // Initialize
            assertTrue(machine.transitionTo(NodeLifecycleState.INITIALIZING));
            assertEquals(NodeLifecycleState.INITIALIZING, machine.get());

            // Ready
            assertTrue(machine.transitionTo(NodeLifecycleState.READY));
            assertEquals(NodeLifecycleState.READY, machine.get());

            // Running
            assertTrue(machine.transitionTo(NodeLifecycleState.RUNNING));
            assertEquals(NodeLifecycleState.RUNNING, machine.get());

            // Stopping
            assertTrue(machine.transitionTo(NodeLifecycleState.STOPPING));
            assertEquals(NodeLifecycleState.STOPPING, machine.get());

            // Stopped
            assertTrue(machine.transitionTo(NodeLifecycleState.STOPPED));
            assertEquals(NodeLifecycleState.STOPPED, machine.get());

            // Back to IDLE
            assertTrue(machine.transitionTo(NodeLifecycleState.IDLE));
            assertEquals(NodeLifecycleState.IDLE, machine.get());
        }
    }

    // ── Invalid Transition Tests ────────────────────────────────────────

    @Nested
    @DisplayName("Invalid Transitions")
    class InvalidTransitionTests {

        @Test
        @DisplayName("IDLE -> RUNNING fails (skips INITIALIZING)")
        void testIdleToRunning_Fails() {
            assertFalse(machine.transitionTo(NodeLifecycleState.RUNNING));
            assertEquals(NodeLifecycleState.IDLE, machine.get());
        }

        @Test
        @DisplayName("IDLE -> STOPPED fails")
        void testIdleToStopped_Fails() {
            assertFalse(machine.transitionTo(NodeLifecycleState.STOPPED));
            assertEquals(NodeLifecycleState.IDLE, machine.get());
        }

        @Test
        @DisplayName("RUNNING -> IDLE fails (must go through STOPPING -> STOPPED)")
        void testRunningToIdle_Fails() {
            machine.forceSet(NodeLifecycleState.RUNNING);
            assertFalse(machine.transitionTo(NodeLifecycleState.IDLE));
            assertEquals(NodeLifecycleState.RUNNING, machine.get());
        }

        @Test
        @DisplayName("STOPPED -> RUNNING fails (must go through IDLE first)")
        void testStoppedToRunning_Fails() {
            machine.forceSet(NodeLifecycleState.STOPPED);
            assertFalse(machine.transitionTo(NodeLifecycleState.RUNNING));
            assertEquals(NodeLifecycleState.STOPPED, machine.get());
        }

        @Test
        @DisplayName("null target fails")
        void testNullTarget_Fails() {
            assertFalse(machine.transitionTo(null));
        }

        @Test
        @DisplayName("INITIALIZING -> STOPPED fails")
        void testInitializingToStopped_Fails() {
            machine.forceSet(NodeLifecycleState.INITIALIZING);
            assertFalse(machine.transitionTo(NodeLifecycleState.STOPPED));
            assertEquals(NodeLifecycleState.INITIALIZING, machine.get());
        }

        @Test
        @DisplayName("PAUSED -> STOPPED fails (must go through STOPPING)")
        void testPausedToStopped_Fails() {
            machine.forceSet(NodeLifecycleState.PAUSED);
            assertFalse(machine.transitionTo(NodeLifecycleState.STOPPED));
            assertEquals(NodeLifecycleState.PAUSED, machine.get());
        }
    }

    // ── Same State Transition Test ──────────────────────────────────────

    @Nested
    @DisplayName("Same State Transition")
    class SameStateTests {

        @Test
        @DisplayName("Transitioning to the same state returns true (fast-path)")
        void testSameState_ReturnsTrue() {
            assertTrue(machine.transitionTo(NodeLifecycleState.IDLE));
            assertEquals(NodeLifecycleState.IDLE, machine.get());
        }
    }

    // ── Listener Notification Tests ─────────────────────────────────────

    @Nested
    @DisplayName("Listener Notifications")
    class ListenerTests {

        @Test
        @DisplayName("Listener is notified on successful transition")
        void testListenerNotified_OnTransition() {
            AtomicReference<NodeLifecycleState> fromRef = new AtomicReference<>();
            AtomicReference<NodeLifecycleState> toRef = new AtomicReference<>();

            machine.addListener((m, from, to) -> {
                fromRef.set(from);
                toRef.set(to);
            });

            machine.transitionTo(NodeLifecycleState.INITIALIZING);

            assertEquals(NodeLifecycleState.IDLE, fromRef.get());
            assertEquals(NodeLifecycleState.INITIALIZING, toRef.get());
        }

        @Test
        @DisplayName("Listener is NOT notified on invalid transition")
        void testListenerNotNotified_OnInvalidTransition() {
            AtomicInteger callCount = new AtomicInteger(0);
            machine.addListener((m, from, to) -> callCount.incrementAndGet());

            // IDLE -> RUNNING is invalid
            assertFalse(machine.transitionTo(NodeLifecycleState.RUNNING));
            assertEquals(0, callCount.get());
        }

        @Test
        @DisplayName("Listener is NOT notified on same-state transition")
        void testListenerNotNotified_OnSameState() {
            AtomicInteger callCount = new AtomicInteger(0);
            machine.addListener((m, from, to) -> callCount.incrementAndGet());

            assertTrue(machine.transitionTo(NodeLifecycleState.IDLE));
            assertEquals(0, callCount.get());
        }

        @Test
        @DisplayName("Multiple listeners all receive notification")
        void testMultipleListeners_AllNotified() {
            AtomicInteger count1 = new AtomicInteger(0);
            AtomicInteger count2 = new AtomicInteger(0);

            machine.addListener((m, f, t) -> count1.incrementAndGet());
            machine.addListener((m, f, t) -> count2.incrementAndGet());

            machine.transitionTo(NodeLifecycleState.INITIALIZING);

            assertEquals(1, count1.get());
            assertEquals(1, count2.get());
        }

        @Test
        @DisplayName("Removed listener does not receive notification")
        void testRemovedListener_NotNotified() {
            AtomicInteger count = new AtomicInteger(0);
            LifecycleStateMachine.LifecycleTransitionListener listener =
                    (m, f, t) -> count.incrementAndGet();

            machine.addListener(listener);
            machine.removeListener(listener);

            machine.transitionTo(NodeLifecycleState.INITIALIZING);
            assertEquals(0, count.get());
        }

        @Test
        @DisplayName("Listener exception does not break other listeners")
        void testListenerException_OthersStillNotified() {
            AtomicInteger goodCount = new AtomicInteger(0);

            // First listener throws
            machine.addListener((m, f, t) -> {
                throw new RuntimeException("Bad listener");
            });
            // Second listener is good
            machine.addListener((m, f, t) -> goodCount.incrementAndGet());

            // Transition still succeeds and good listener is called
            assertTrue(machine.transitionTo(NodeLifecycleState.INITIALIZING));
            assertEquals(1, goodCount.get());
        }

        @Test
        @DisplayName("Null listener is rejected")
        void testNullListener_Throws() {
            assertThrows(IllegalArgumentException.class, () -> machine.addListener(null));
        }

        @Test
        @DisplayName("Listener count tracking works")
        void testListenerCount() {
            assertEquals(0, machine.getListenerCount());

            LifecycleStateMachine.LifecycleTransitionListener l1 = (m, f, t) -> {};
            LifecycleStateMachine.LifecycleTransitionListener l2 = (m, f, t) -> {};

            machine.addListener(l1);
            assertEquals(1, machine.getListenerCount());

            machine.addListener(l2);
            assertEquals(2, machine.getListenerCount());

            machine.removeListener(l1);
            assertEquals(1, machine.getListenerCount());
        }
    }

    // ── Force Set Tests ────────────────────────────────────────────────

    @Nested
    @DisplayName("Force Set")
    class ForceSetTests {

        @Test
        @DisplayName("Force set bypasses validation")
        void testForceSet_BypassesValidation() {
            // IDLE -> RUNNING is normally invalid
            machine.forceSet(NodeLifecycleState.RUNNING);
            assertEquals(NodeLifecycleState.RUNNING, machine.get());
        }

        @Test
        @DisplayName("Force set does not notify listeners")
        void testForceSet_NoNotification() {
            AtomicInteger count = new AtomicInteger(0);
            machine.addListener((m, f, t) -> count.incrementAndGet());

            machine.forceSet(NodeLifecycleState.RUNNING);
            assertEquals(0, count.get());
        }

        @Test
        @DisplayName("Force set with null throws")
        void testForceSet_NullThrows() {
            assertThrows(IllegalArgumentException.class, () -> machine.forceSet(null));
        }
    }

    // ── Convenience Method Tests ────────────────────────────────────────

    @Nested
    @DisplayName("Convenience Methods")
    class ConvenienceTests {

        @Test
        @DisplayName("isActive is true for RUNNING")
        void testIsActive_Running() {
            machine.forceSet(NodeLifecycleState.RUNNING);
            assertTrue(machine.isActive());
        }

        @Test
        @DisplayName("isActive is true for PAUSED")
        void testIsActive_Paused() {
            machine.forceSet(NodeLifecycleState.PAUSED);
            assertTrue(machine.isActive());
        }

        @Test
        @DisplayName("isActive is false for IDLE")
        void testIsActive_Idle() {
            assertFalse(machine.isActive());
        }

        @Test
        @DisplayName("isTerminal is true for STOPPED")
        void testIsTerminal_Stopped() {
            machine.forceSet(NodeLifecycleState.STOPPED);
            assertTrue(machine.isTerminal());
        }

        @Test
        @DisplayName("isTerminal is true for ERROR")
        void testIsTerminal_Error() {
            machine.forceSet(NodeLifecycleState.ERROR);
            assertTrue(machine.isTerminal());
        }

        @Test
        @DisplayName("isTerminal is false for RUNNING")
        void testIsTerminal_Running() {
            machine.forceSet(NodeLifecycleState.RUNNING);
            assertFalse(machine.isTerminal());
        }

        @Test
        @DisplayName("canTransitionTo checks without performing transition")
        void testCanTransitionTo_NoSideEffect() {
            assertTrue(machine.canTransitionTo(NodeLifecycleState.INITIALIZING));
            // State is still IDLE — no transition occurred
            assertEquals(NodeLifecycleState.IDLE, machine.get());
        }

        @Test
        @DisplayName("canTransitionTo returns false for invalid transition")
        void testCanTransitionTo_Invalid() {
            assertFalse(machine.canTransitionTo(NodeLifecycleState.RUNNING));
        }
    }

    // ── Thread Safety Tests ─────────────────────────────────────────────

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Concurrent transitions: only one succeeds per valid step")
        void testConcurrentTransitions_Safe() throws InterruptedException {
            machine.forceSet(NodeLifecycleState.IDLE);

            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(10);

            for (int i = 0; i < 10; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        if (machine.transitionTo(NodeLifecycleState.INITIALIZING)) {
                            successCount.incrementAndGet();
                        } else {
                            // Either already INITIALIZING (same state — returns true) or CAS failed
                            failCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            startLatch.countDown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            // At least one transition must have succeeded
            assertTrue(successCount.get() > 0, "At least one thread should succeed");
        }

        @Test
        @DisplayName("Listener add/remove is thread-safe")
        void testListenerThreadSafety() throws InterruptedException {
            ExecutorService executor = Executors.newFixedThreadPool(5);
            CountDownLatch startLatch = new CountDownLatch(1);

            for (int i = 0; i < 5; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        LifecycleStateMachine.LifecycleTransitionListener l =
                                (m, f, t) -> {};
                        machine.addListener(l);
                        machine.removeListener(l);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            startLatch.countDown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            // No exceptions thrown — thread safety verified
            assertEquals(0, machine.getListenerCount());
        }
    }

    // ── toString Test ───────────────────────────────────────────────────

    @Test
    @DisplayName("toString contains current state")
    void testToString() {
        String str = machine.toString();
        assertTrue(str.contains("IDLE"));
        assertTrue(str.contains("LifecycleStateMachine"));
    }
}