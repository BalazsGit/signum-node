package application.module.node.lifecycle;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for operating substate management in {@link NodeLifecycleManager}.
 * Follows AAA pattern (Arrange-Act-Assert).
 */
class NodeLifecycleManagerSubstateTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeLifecycleManagerSubstateTest.class);
    private static final String TEST_PROFILE = "test-profile";

    private NodeLifecycleManager manager;
    private TestListener listener;

    @BeforeEach
    void setUp() {
        NodeLifecycleManager.resetInstance();
        manager = NodeLifecycleManager.getInstance();
        manager.registerProfile(TEST_PROFILE);
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
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(SYNC_IDLE);
        int thresholdHi = 10;

        // Act
        manager.reportSyncProgress(TEST_PROFILE, thresholdHi + 1);

        // Assert
        assertEquals(SYNCING, info.getOperatingState());
        assertTrue(info.getSyncStartTime() > 0);
        assertNotNull(info.getStatusMessage());
        assertTrue(info.getStatusMessage().contains("Syncing"));
    }

    @Test
    void reportSyncProgress_remainsIdleWhenBelowThresholdHi() {
        // Arrange
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(SYNC_IDLE);

        // Act
        manager.reportSyncProgress(TEST_PROFILE, 10); // exactly at threshold, not above

        // Assert
        assertEquals(SYNC_IDLE, info.getOperatingState());
    }

    @Test
    void reportSyncProgress_remainsSyncingWhenAboveThresholdLo() {
        // Arrange
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(SYNCING);
        info.setSyncStartTime(System.currentTimeMillis());

        // Act
        manager.reportSyncProgress(TEST_PROFILE, 2); // above thresholdLo=1

        // Assert
        assertEquals(SYNCING, info.getOperatingState());
    }

    @Test
    void reportSyncProgress_transitionsSyncingToIdleWhenAtOrBelowThresholdLo() throws InterruptedException {
        // Arrange
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(SYNCING);
        long syncStart = System.currentTimeMillis();
        info.setSyncStartTime(syncStart);
        Thread.sleep(50); // small delay to measure duration

        // Act
        manager.reportSyncProgress(TEST_PROFILE, 0);

        // Assert
        assertEquals(SYNC_IDLE, info.getOperatingState());
        assertNotNull(info.getSyncEndTime());
        assertTrue(info.getAccumulatedSyncTimeMs() >= 50);
    }

    @Test
    void reportSyncProgress_doesNotChangePausedUserState() {
        // Arrange
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(PAUSED_USER);

        // Act  - even with huge missing blocks, paused state is preserved
        manager.reportSyncProgress(TEST_PROFILE, 1000);

        // Assert
        assertEquals(PAUSED_USER, info.getOperatingState());
    }

    @Test
    void reportSyncProgress_doesNotChangePausedSystemState() {
        // Arrange
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(PAUSED_SYSTEM);

        // Act
        manager.reportSyncProgress(TEST_PROFILE, 1000);

        // Assert
        assertEquals(PAUSED_SYSTEM, info.getOperatingState());
    }

    @Test
    void reportSyncProgress_updatesMissingBlocksRegardlessOfState() {
        // Arrange
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(SYNC_IDLE);

        // Act
        manager.reportSyncProgress(TEST_PROFILE, 42);

        // Assert
        assertEquals(42, info.getMissingBlocks());
    }

    @Test
    void reportSyncProgress_notifiesListenersOnTransition() {
        // Arrange
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(SYNC_IDLE);
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
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(SYNC_IDLE);
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
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(SYNC_IDLE);
        info.setHysteresisThresholdHi(50);

        // Act  - 20 blocks missing is below custom threshold of 50
        manager.reportSyncProgress(TEST_PROFILE, 20);

        // Assert
        assertEquals(SYNC_IDLE, info.getOperatingState());

        // Now exceed the custom threshold
        manager.reportSyncProgress(TEST_PROFILE, 51);

        // Assert
        assertEquals(SYNCING, info.getOperatingState());
    }

    // --- pauseSyncByUser / resumeSyncByUser tests ---

    @Test
    void pauseSyncByUser_transitionsToPausedUser() {
        // Arrange
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(SYNCING);
        listener.clear();

        // Act
        manager.pauseSyncByUser(TEST_PROFILE);

        // Assert
        assertEquals(PAUSED_USER, info.getOperatingState());
    }

    @Test
    void pauseSyncByUser_accumulatesPartialSyncTime() {
        // Arrange
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(SYNCING);
        info.setSyncStartTime(System.currentTimeMillis() - 200);

        // Act
        manager.pauseSyncByUser(TEST_PROFILE);

        // Assert
        assertTrue(info.getAccumulatedSyncTimeMs() >= 200);
    }

    @Test
    void resumeSyncByUser_transitionsBackToSyncing() {
        // Arrange
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(PAUSED_USER);
        listener.clear();

        // Act
        manager.resumeSyncByUser(TEST_PROFILE);

        // Assert
        assertEquals(SYNCING, info.getOperatingState());
    }

    // --- pauseSyncBySystem / resumeSyncBySystem tests ---

    @Test
    void pauseSyncBySystem_transitionsToPausedSystem() {
        // Arrange
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(SYNC_IDLE);
        listener.clear();

        // Act
        manager.pauseSyncBySystem(TEST_PROFILE, "Database consistency check");

        // Assert
        assertEquals(PAUSED_SYSTEM, info.getOperatingState());
    }

    @Test
    void resumeSyncBySystem_restoresToIdleWhenCaughtUp() {
        // Arrange
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(PAUSED_SYSTEM);
        info.setMissingBlocks(0);

        // Act
        manager.resumeSyncBySystem(TEST_PROFILE);

        // Assert
        assertEquals(SYNC_IDLE, info.getOperatingState());
    }

    @Test
    void resumeSyncBySystem_restoresToSyncingWhenBehind() {
        // Arrange
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(PAUSED_SYSTEM);
        info.setMissingBlocks(100);

        // Act
        manager.resumeSyncBySystem(TEST_PROFILE);

        // Assert
        assertEquals(SYNCING, info.getOperatingState());
    }

    // --- No-op tests for already-in-state scenarios ---

    @Test
    void pauseSyncByUser_noOpWhenAlreadyPaused() {
        // Arrange
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(PAUSED_USER);
        listener.clear();

        // Act
        manager.pauseSyncByUser(TEST_PROFILE);

        // Assert - no duplicate notification
        assertTrue(listener.operatingStateChanges.isEmpty());
    }

    @Test
    void resumeSyncByUser_noOpWhenNotPaused() {
        // Arrange
        NodeInstanceInfo info = manager.getProfileStatus(TEST_PROFILE);
        info.forceState(RUNNING);
        info.forceOperatingState(SYNC_IDLE);
        listener.clear();

        // Act
        manager.resumeSyncByUser(TEST_PROFILE);

        // Assert - state unchanged
        assertEquals(SYNC_IDLE, info.getOperatingState());
    }

    // --- Helper: Capture listener events for assertions ---

    private static class TestListener implements LifecycleListener {
        final java.util.List<OpStateChange> operatingStateChanges = new java.util.ArrayList<>();

        @Override
        public void onStateChanged(NodeInstanceInfo instanceInfo, NodeLifecycleState oldState, NodeLifecycleState newState) {
            // Not used in these tests
        }

        @Override
        public void onOperatingStateChanged(NodeInstanceInfo instanceInfo,
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