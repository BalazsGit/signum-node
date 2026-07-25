package application.module.node.lifecycle;

import application.module.node.profile.NodeProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static application.module.node.lifecycle.NodeLifecycleState.RUNNING;
import static application.module.node.lifecycle.NodeOperatingState.PAUSED_SYSTEM;
import static application.module.node.lifecycle.NodeOperatingState.PAUSED_USER;
import static application.module.node.lifecycle.NodeOperatingState.SYNC_IDLE;
import static application.module.node.lifecycle.NodeOperatingState.SYNCING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for operating substate management in {@link NodeLifecycleManager}.
 * Follows AAA pattern (Arrange-Act-Assert).
 */
class NodeLifecycleManagerSubstateTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeLifecycleManagerSubstateTest.class);
    private static final String TEST_PROFILE = "test-profile";

    private NodeLifecycleManager manager;
    private NodeProfile profile;
    private NodeProfileRuntime runtime;
    private TestListener listener;

    @BeforeEach
    void setUp() {
        NodeLifecycleManager.resetInstance();
        manager = NodeLifecycleManager.getInstance();
        profile = new NodeProfile(TEST_PROFILE);
        manager.addProfile(profile);
        runtime = profile.getRuntime();
        listener = new TestListener();
        manager.addListener(listener);
    }

    @AfterEach
    void tearDown() {
        // resetInstance() now safely skips profiles that were never started
        // (no active coreContext), so no need for try-catch suppression.
        NodeLifecycleManager.resetInstance();
    }

    // --- reportSyncProgress hysteresis tests ---

    @Test
    void reportSyncProgress_transitionsIdleToSyncingWhenAboveThresholdHi() {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(SYNC_IDLE);
        int thresholdHi = 10;

        // Act
        manager.reportSyncProgress(TEST_PROFILE, thresholdHi + 1);

        // Assert
        assertEquals(SYNCING, runtime.getOperatingState());
        assertTrue(runtime.getSyncStartTime() > 0);
        assertNotNull(runtime.getStatusMessage());
        assertTrue(runtime.getStatusMessage().contains("Syncing"));
    }

    @Test
    void reportSyncProgress_remainsIdleWhenBelowThresholdHi() {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(SYNC_IDLE);

        // Act
        manager.reportSyncProgress(TEST_PROFILE, 10); // exactly at threshold, not above

        // Assert
        assertEquals(SYNC_IDLE, runtime.getOperatingState());
    }

    @Test
    void reportSyncProgress_remainsSyncingWhenAboveThresholdLo() {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(SYNCING);
        runtime.setSyncStartTime(System.currentTimeMillis());

        // Act
        manager.reportSyncProgress(TEST_PROFILE, 2); // above thresholdLo=1

        // Assert
        assertEquals(SYNCING, runtime.getOperatingState());
    }

    @Test
    void reportSyncProgress_transitionsSyncingToIdleWhenAtOrBelowThresholdLo() throws InterruptedException {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(SYNCING);
        long syncStart = System.currentTimeMillis();
        runtime.setSyncStartTime(syncStart);
        Thread.sleep(50); // small delay to measure duration

        // Act
        manager.reportSyncProgress(TEST_PROFILE, 0);

        // Assert
        assertEquals(SYNC_IDLE, runtime.getOperatingState());
        assertNotNull(runtime.getSyncEndTime());
        assertTrue(runtime.getAccumulatedSyncTimeMs() >= 50);
    }

    @Test
    void reportSyncProgress_doesNotChangePausedUserState() {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(PAUSED_USER);

        // Act  - even with huge missing blocks, paused state is preserved
        manager.reportSyncProgress(TEST_PROFILE, 1000);

        // Assert
        assertEquals(PAUSED_USER, runtime.getOperatingState());
    }

    @Test
    void reportSyncProgress_doesNotChangePausedSystemState() {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(PAUSED_SYSTEM);

        // Act
        manager.reportSyncProgress(TEST_PROFILE, 1000);

        // Assert
        assertEquals(PAUSED_SYSTEM, runtime.getOperatingState());
    }

    @Test
    void reportSyncProgress_updatesMissingBlocksRegardlessOfState() {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(SYNC_IDLE);

        // Act
        manager.reportSyncProgress(TEST_PROFILE, 42);

        // Assert
        assertEquals(42, runtime.getMissingBlocks());
    }

    @Test
    void reportSyncProgress_notifiesListenersOnTransition() {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(SYNC_IDLE);
        listener.clear();

        // Act
        manager.reportSyncProgress(TEST_PROFILE, 11);

        // Assert
        assertFalse(listener.operatingStateChanges.isEmpty());
        var change = listener.operatingStateChanges.get(0);
        assertEquals(SYNC_IDLE, change.oldSubstate);
        assertEquals(SYNCING, change.newSubstate);
    }

    @Test
    void reportSyncProgress_noNotificationWhenNoTransition() {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(SYNC_IDLE);
        listener.clear();

        // Act
        manager.reportSyncProgress(TEST_PROFILE, 5); // below threshold, no change

        // Assert
        assertTrue(listener.operatingStateChanges.isEmpty());
    }

    // --- Hysteresis threshold configuration tests ---

    @Test
    void reportSyncProgress_usesCustomHysteresisThresholds() {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(SYNC_IDLE);
        runtime.setHysteresisThresholdHi(50);

        // Act  - 20 blocks missing is below custom threshold of 50
        manager.reportSyncProgress(TEST_PROFILE, 20);

        // Assert
        assertEquals(SYNC_IDLE, runtime.getOperatingState());

        // Now exceed the custom threshold
        manager.reportSyncProgress(TEST_PROFILE, 51);

        // Assert
        assertEquals(SYNCING, runtime.getOperatingState());
    }

    // --- pauseSyncByUser / resumeSyncByUser tests ---

    @Test
    void pauseSyncByUser_transitionsToPausedUser() {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(SYNCING);
        listener.clear();

        // Act
        manager.pauseSyncByUser(TEST_PROFILE);

        // Assert
        assertEquals(PAUSED_USER, runtime.getOperatingState());
    }

    @Test
    void pauseSyncByUser_accumulatesPartialSyncTime() {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(SYNCING);
        runtime.setSyncStartTime(System.currentTimeMillis() - 200);

        // Act
        manager.pauseSyncByUser(TEST_PROFILE);

        // Assert
        assertTrue(runtime.getAccumulatedSyncTimeMs() >= 200);
    }

    @Test
    void resumeSyncByUser_transitionsBackToSyncing() {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(PAUSED_USER);
        listener.clear();

        // Act
        manager.resumeSyncByUser(TEST_PROFILE);

        // Assert
        assertEquals(SYNCING, runtime.getOperatingState());
    }

    // --- pauseSyncBySystem / resumeSyncBySystem tests ---

    @Test
    void pauseSyncBySystem_transitionsToPausedSystem() {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(SYNC_IDLE);
        listener.clear();

        // Act
        manager.pauseSyncBySystem(TEST_PROFILE, "Database consistency check");

        // Assert
        assertEquals(PAUSED_SYSTEM, runtime.getOperatingState());
    }

    @Test
    void resumeSyncBySystem_restoresToIdleWhenCaughtUp() {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(PAUSED_SYSTEM);
        runtime.setMissingBlocks(0);

        // Act
        manager.resumeSyncBySystem(TEST_PROFILE);

        // Assert
        assertEquals(SYNC_IDLE, runtime.getOperatingState());
    }

    @Test
    void resumeSyncBySystem_restoresToSyncingWhenBehind() {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(PAUSED_SYSTEM);
        runtime.setMissingBlocks(100);

        // Act
        manager.resumeSyncBySystem(TEST_PROFILE);

        // Assert
        assertEquals(SYNCING, runtime.getOperatingState());
    }

    // --- No-op tests for already-in-state scenarios ---

    @Test
    void pauseSyncByUser_noOpWhenAlreadyPaused() {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(PAUSED_USER);
        listener.clear();

        // Act
        manager.pauseSyncByUser(TEST_PROFILE);

        // Assert - no duplicate notification
        assertTrue(listener.operatingStateChanges.isEmpty());
    }

    @Test
    void resumeSyncByUser_noOpWhenNotPaused() {
        // Arrange
        runtime.forceLifecycleState(RUNNING);
        runtime.forceOperatingState(SYNC_IDLE);
        listener.clear();

        // Act
        manager.resumeSyncByUser(TEST_PROFILE);

        // Assert - state unchanged
        assertEquals(SYNC_IDLE, runtime.getOperatingState());
    }

    // --- Helper: Capture listener events for assertions ---

    private static class TestListener implements LifecycleListener {
        final java.util.List<OpStateChange> operatingStateChanges = new java.util.ArrayList<>();

        @Override
        public void onStateChanged(NodeProfile profile, NodeLifecycleState oldState, NodeLifecycleState newState) {
            // Not used in these tests
        }

        @Override
        public void onOperatingStateChanged(NodeProfile profile,
                                           NodeOperatingState oldSubstate,
                                           NodeOperatingState newSubstate) {
            operatingStateChanges.add(new OpStateChange(oldSubstate, newSubstate));
        }

        void clear() {
            operatingStateChanges.clear();
        }

        private record OpStateChange(NodeOperatingState oldSubstate, NodeOperatingState newSubstate) {
        }
    }
}