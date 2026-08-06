package application.module.node.lifecycle;

import application.module.node.metrics.ProfileMetric;
import application.module.node.metrics.ProfileMetricCollector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link NodeProfileRuntime}.
 * Covers construction, lifecycle state delegation, operating state transitions,
 * sync tracking, timing markers, ports, and convenience methods.
 */
@DisplayName("NodeProfileRuntime Tests")
class NodeProfileRuntimeTest {

    private NodeProfileRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = new NodeProfileRuntime("test-profile");
    }

    // ── Construction Tests ──────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("New runtime starts in IDLE lifecycle state")
        void testLifecycleStartsIdle() {
            assertEquals(NodeLifecycleState.IDLE, runtime.getLifecycleState());
        }

        @Test
        @DisplayName("New runtime starts in SYNC_IDLE operating state")
        void testOperatingStateStartsSyncIdle() {
            assertEquals(NodeOperatingState.SYNC_IDLE, runtime.getOperatingState());
        }

        @Test
        @DisplayName("Default status message is 'Not initialized'")
        void testDefaultStatusMessage() {
            assertEquals("Not initialized", runtime.getStatusMessage());
        }

        @Test
        @DisplayName("Error message is null by default")
        void testDefaultErrorMessageNull() {
            assertNull(runtime.getErrorMessage());
        }

        @Test
        @DisplayName("Timing values are zero/null by default")
        void testDefaultTimingValues() {
            assertEquals(0, runtime.getStartTime());
            assertNull(runtime.getStopTime());
            assertEquals(0, runtime.getSyncStartTime());
            assertNull(runtime.getSyncEndTime());
            assertEquals(0, runtime.getAccumulatedSyncTimeMs());
            assertEquals(0, runtime.getMissingBlocks());
        }

        @Test
        @DisplayName("Port values are zero by default")
        void testDefaultPorts() {
            assertEquals(0, runtime.getApiPort());
            assertEquals(0, runtime.getP2pPort());
        }

        @Test
        @DisplayName("Hysteresis defaults: hi=10, lo=1")
        void testDefaultHysteresisThresholds() {
            assertEquals(10, runtime.getHysteresisThresholdHi());
            assertEquals(1, runtime.getHysteresisThresholdLo());
        }

        @Test
        @DisplayName("CoreContext is null by default")
        void testDefaultCoreContextNull() {
            assertNull(runtime.getCoreContext());
        }

        @Test
        @DisplayName("State machine is never null")
        void testStateMachineNeverNull() {
            assertNotNull(runtime.getStateMachine());
        }
    }

    // ── Lifecycle State Delegation Tests ────────────────────────────────

    @Nested
    @DisplayName("Lifecycle State Delegation")
    class LifecycleStateTests {

        @Test
        @DisplayName("setLifecycleState delegates to state machine")
        void testSetLifecycleState_Delegates() {
            assertTrue(runtime.setLifecycleState(NodeLifecycleState.INITIALIZING));
            assertEquals(NodeLifecycleState.INITIALIZING, runtime.getLifecycleState());
        }

        @Test
        @DisplayName("Invalid lifecycle transition is rejected")
        void testInvalidLifecycleTransition_Rejected() {
            // IDLE -> RUNNING is invalid
            assertFalse(runtime.setLifecycleState(NodeLifecycleState.RUNNING));
            assertEquals(NodeLifecycleState.IDLE, runtime.getLifecycleState());
        }

        @Test
        @DisplayName("forceLifecycleState bypasses validation")
        void testForceLifecycleState_Bypasses() {
            runtime.forceLifecycleState(NodeLifecycleState.RUNNING);
            assertEquals(NodeLifecycleState.RUNNING, runtime.getLifecycleState());
        }

        @Test
        @DisplayName("Full lifecycle sequence through runtime")
        void testFullLifecycleSequence() {
            assertTrue(runtime.setLifecycleState(NodeLifecycleState.INITIALIZING));
            assertTrue(runtime.setLifecycleState(NodeLifecycleState.READY));
            assertTrue(runtime.setLifecycleState(NodeLifecycleState.RUNNING));
            assertTrue(runtime.setLifecycleState(NodeLifecycleState.STOPPING));
            assertTrue(runtime.setLifecycleState(NodeLifecycleState.STOPPED));
            assertTrue(runtime.setLifecycleState(NodeLifecycleState.IDLE));
            assertEquals(NodeLifecycleState.IDLE, runtime.getLifecycleState());
        }

        @Test
        @DisplayName("Lifecycle listener registration through state machine works")
        void testListenerRegistration() {
            AtomicInteger count = new AtomicInteger(0);
            runtime.getStateMachine().addListener((m, f, t) -> count.incrementAndGet());

            assertTrue(runtime.setLifecycleState(NodeLifecycleState.INITIALIZING));
            assertEquals(1, count.get());
        }
    }

    // ── Operating State Tests ───────────────────────────────────────────

    @Nested
    @DisplayName("Operating State")
    class OperatingStateTests {

        @Test
        @DisplayName("Valid operating state transition succeeds")
        void testValidTransition_Succeeds() {
            assertTrue(runtime.setOperatingState(NodeOperatingState.SYNCING));
            assertEquals(NodeOperatingState.SYNCING, runtime.getOperatingState());
        }

        @Test
        @DisplayName("Invalid operating state transition is rejected")
        void testInvalidTransition_Rejected() {
            // SYNC_IDLE -> GENERATING is invalid (must go through SYNCING)
            assertFalse(runtime.setOperatingState(NodeOperatingState.GENERATING));
            assertEquals(NodeOperatingState.SYNC_IDLE, runtime.getOperatingState());
        }

        @Test
        @DisplayName("forceOperatingState bypasses validation")
        void testForceOperatingState_Bypasses() {
            runtime.forceOperatingState(NodeOperatingState.PAUSED_USER);
            assertEquals(NodeOperatingState.PAUSED_USER, runtime.getOperatingState());
        }

        @Test
        @DisplayName("Sync workflow: SYNC_IDLE → SYNCING → SYNC_IDLE")
        void testSyncWorkflow() {
            assertTrue(runtime.setOperatingState(NodeOperatingState.SYNCING));
            assertEquals(NodeOperatingState.SYNCING, runtime.getOperatingState());

            assertTrue(runtime.setOperatingState(NodeOperatingState.SYNC_IDLE));
            assertEquals(NodeOperatingState.SYNC_IDLE, runtime.getOperatingState());
        }
    }

    // ── Status Message Tests ────────────────────────────────────────────

    @Nested
    @DisplayName("Status Messages")
    class StatusMessageTests {

        @Test
        @DisplayName("setStatusMessage updates message")
        void testSetStatusMessage() {
            runtime.setStatusMessage("Syncing blocks...");
            assertEquals("Syncing blocks...", runtime.getStatusMessage());
        }

        @Test
        @DisplayName("setErrorMessage stores error")
        void testSetErrorMessage() {
            runtime.setErrorMessage("Connection refused");
            assertEquals("Connection refused", runtime.getErrorMessage());
        }
    }

    // ── Timing Marker Tests ────────────────────────────────────────────

    @Nested
    @DisplayName("Timing Markers")
    class TimingMarkerTests {

        @Test
        @DisplayName("markStarted records current time")
        void testMarkStarted() {
            long before = System.currentTimeMillis();
            runtime.markStarted();
            long after = System.currentTimeMillis();

            long start = runtime.getStartTime();
            assertTrue(start >= before && start <= after, "Start time should be in range");
        }

        @Test
        @DisplayName("markStopped records current time")
        void testMarkStopped() {
            long before = System.currentTimeMillis();
            runtime.markStopped();
            long after = System.currentTimeMillis();

            Long stop = runtime.getStopTime();
            assertNotNull(stop);
            assertTrue(stop >= before && stop <= after, "Stop time should be in range");
        }

        @Test
        @DisplayName("getUptimeSeconds returns 0 when not active")
        void testUptimeWhenInactive() {
            runtime.markStarted();
            // Not in RUNNING/PAUSED state so inactive
            assertEquals(0, runtime.getUptimeSeconds());
        }

        @Test
        @DisplayName("getUptimeSeconds returns elapsed time when active")
        void testUptimeWhenActive() throws InterruptedException {
            // Force to RUNNING state for isActive check
            runtime.forceLifecycleState(NodeLifecycleState.RUNNING);
            runtime.markStarted();

            Thread.sleep(1100); // Wait ~1 second

            long uptime = runtime.getUptimeSeconds();
            assertTrue(uptime >= 1, "Uptime should be at least 1 second: was " + uptime);
        }
    }

    // ── Port Tests ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Ports")
    class PortTests {

        @Test
        @DisplayName("set/get API port")
        void testApiPort() {
            runtime.setApiPort(6876);
            assertEquals(6876, runtime.getApiPort());
        }

        @Test
        @DisplayName("set/get P2P port")
        void testP2pPort() {
            runtime.setP2pPort(6875);
            assertEquals(6875, runtime.getP2pPort());
        }

        @Test
        @DisplayName("Ports can be set independently")
        void testPortsIndependent() {
            runtime.setApiPort(9000);
            runtime.setP2pPort(8000);
            assertEquals(9000, runtime.getApiPort());
            assertEquals(8000, runtime.getP2pPort());
        }
    }

    // ── Sync Tracking Tests ────────────────────────────────────────────

    @Nested
    @DisplayName("Sync Tracking")
    class SyncTrackingTests {

        @Test
        @DisplayName("set/get missing blocks")
        void testMissingBlocks() {
            runtime.setMissingBlocks(42);
            assertEquals(42, runtime.getMissingBlocks());
        }

        @Test
        @DisplayName("sync start/end time tracking")
        void testSyncTiming() {
            long syncStart = 1000;
            Long syncEnd = 5000L;

            runtime.setSyncStartTime(syncStart);
            assertEquals(syncStart, runtime.getSyncStartTime());

            runtime.setSyncEndTime(syncEnd);
            assertEquals(syncEnd, runtime.getSyncEndTime());
        }

        @Test
        @DisplayName("accumulated sync time tracking")
        void testAccumulatedSyncTime() {
            runtime.setAccumulatedSyncTimeMs(12345);
            assertEquals(12345, runtime.getAccumulatedSyncTimeMs());
        }

        @Test
        @DisplayName("getCurrentSyncDurationSeconds returns 0 when not syncing")
        void testCurrentSyncDuration_NotSyncing() {
            // SYNC_IDLE state, so not syncing
            assertEquals(0, runtime.getCurrentSyncDurationSeconds());
        }

        @Test
        @DisplayName("getLastSyncDurationSeconds calculates from start/end")
        void testLastSyncDuration() {
            runtime.setSyncStartTime(1000);
            runtime.setSyncEndTime(4000L); // 3000ms = 3 seconds

            assertEquals(3, runtime.getLastSyncDurationSeconds());
        }

        @Test
        @DisplayName("getLastSyncDurationSeconds returns 0 with no data")
        void testLastSyncDuration_NoData() {
            assertEquals(0, runtime.getLastSyncDurationSeconds());
        }
    }

    // ── Hysteresis Threshold Tests ──────────────────────────────────────

    @Nested
    @DisplayName("Hysteresis Thresholds")
    class HysteresisTests {

        @Test
        @DisplayName("set/get hysteresis threshold hi")
        void testHysteresisHi() {
            runtime.setHysteresisThresholdHi(20);
            assertEquals(20, runtime.getHysteresisThresholdHi());
        }

        @Test
        @DisplayName("set/get hysteresis threshold lo")
        void testHysteresisLo() {
            runtime.setHysteresisThresholdLo(2);
            assertEquals(2, runtime.getHysteresisThresholdLo());
        }

        @Test
        @DisplayName("Thresholds can be set independently")
        void testThresholdsIndependent() {
            runtime.setHysteresisThresholdHi(50);
            runtime.setHysteresisThresholdLo(5);
            assertEquals(50, runtime.getHysteresisThresholdHi());
            assertEquals(5, runtime.getHysteresisThresholdLo());
        }
    }

    // ── CoreContext Tests ───────────────────────────────────────────────

    @Nested
    @DisplayName("CoreContext")
    class CoreContextTests {

        @Test
        @DisplayName("set/get core context (null check)")
        void testCoreContext() {
            assertNull(runtime.getCoreContext());
            runtime.setCoreContext(null);
            assertNull(runtime.getCoreContext());
        }
    }

    // ── Metrics Integration Tests ────────────────────────────────────────

    @Nested
    @DisplayName("Metrics Integration")
    class MetricsTests {

        @Test
        @DisplayName("getMetrics returns non-null collector")
        void testMetricsNeverNull() {
            assertNotNull(runtime.getMetrics());
        }

        @Test
        @DisplayName("Collector is bound to correct profile ID")
        void testCollectorBoundToProfileId() {
            assertEquals("test-profile", runtime.getMetrics().getProfileId());
        }

        @Test
        @DisplayName("Can record and retrieve metrics via runtime")
        void testRecordAndRetrieveMetric() {
            runtime.getMetrics().record("blockchain", "height", 12345);
            ProfileMetric metric = runtime.getMetrics().get("blockchain", "height");
            assertNotNull(metric);
            assertEquals(12345.0, metric.getValue());
        }

        @Test
        @DisplayName("Cannot record with null moduleId")
        void testRecordNullModuleIdThrows() {
            assertThrows(NullPointerException.class,
                () -> runtime.getMetrics().record(null, "height", 1));
        }

        @Test
        @DisplayName("Cannot record with null metricName")
        void testRecordNullMetricNameThrows() {
            assertThrows(NullPointerException.class,
                () -> runtime.getMetrics().record("blockchain", null, 1));
        }

        @Test
        @DisplayName("getByModule returns only that module's metrics")
        void testGetByModule() {
            runtime.getMetrics().record("blockchain", "height", 100);
            runtime.getMetrics().record("peer", "count", 5);
            Collection<ProfileMetric> blockchain = runtime.getMetrics().getByModule("blockchain");
            assertEquals(1, blockchain.size());
        }

        @Test
        @DisplayName("Clear removes all metrics")
        void testClear() {
            runtime.getMetrics().record("blockchain", "height", 100);
            runtime.getMetrics().clear();
            assertEquals(0, runtime.getMetrics().size());
        }
    }

    // ── Convenience Method Tests ────────────────────────────────────────

    @Nested
    @DisplayName("Convenience Methods")
    class ConvenienceTests {

        @Test
        @DisplayName("isActive is false when in IDLE")
        void testIsActive_Idle() {
            assertFalse(runtime.isActive());
        }

        @Test
        @DisplayName("isActive is true when in RUNNING")
        void testIsActive_Running() {
            runtime.forceLifecycleState(NodeLifecycleState.RUNNING);
            assertTrue(runtime.isActive());
        }

        @Test
        @DisplayName("isActive is true when in PAUSED")
        void testIsActive_Paused() {
            runtime.forceLifecycleState(NodeLifecycleState.PAUSED);
            assertTrue(runtime.isActive());
        }

        @Test
        @DisplayName("isTerminal is false when in IDLE")
        void testIsTerminal_Idle() {
            assertFalse(runtime.isTerminal());
        }

        @Test
        @DisplayName("isTerminal is true when in STOPPED")
        void testIsTerminal_Stopped() {
            runtime.forceLifecycleState(NodeLifecycleState.STOPPED);
            assertTrue(runtime.isTerminal());
        }

        @Test
        @DisplayName("isTerminal is true when in ERROR")
        void testIsTerminal_Error() {
            runtime.forceLifecycleState(NodeLifecycleState.ERROR);
            assertTrue(runtime.isTerminal());
        }
    }

    // ── Thread Safety Tests ─────────────────────────────────────────────

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Concurrent operating state transitions are safe")
        void testConcurrentOperatingStateTransitions() throws InterruptedException {
            ExecutorService executor = Executors.newFixedThreadPool(5);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < 5; i++) {
                executor.submit(() -> {
                    try {
                        latch.await();
                        if (runtime.setOperatingState(NodeOperatingState.SYNCING)) {
                            successCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            latch.countDown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            // At least one transition succeeded; no exceptions thrown = thread-safe
            assertTrue(successCount.get() >= 1);
        }

        @Test
        @DisplayName("Concurrent port writes are safe")
        void testConcurrentPortWrites() throws InterruptedException {
            ExecutorService executor = Executors.newFixedThreadPool(5);
            CountDownLatch latch = new CountDownLatch(1);

            for (int i = 0; i < 5; i++) {
                final int port = 6876 + i;
                executor.submit(() -> {
                    try {
                        latch.await();
                        runtime.setApiPort(port);
                        runtime.setP2pPort(port + 1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            latch.countDown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            // Ports have some valid value — no exceptions = thread-safe
            assertTrue(runtime.getApiPort() > 0);
            assertTrue(runtime.getP2pPort() > 0);
        }
    }

    // ── toString Test ───────────────────────────────────────────────────

    @Test
    @DisplayName("toString contains key fields")
    void testToString() {
        runtime.setApiPort(6876);
        runtime.setMissingBlocks(42);

        String str = runtime.toString();
        assertTrue(str.contains("NodeProfileRuntime"));
        assertTrue(str.contains("IDLE"));
        assertTrue(str.contains("apiPort=6876"));
        assertTrue(str.contains("missingBlocks=42"));
    }
}