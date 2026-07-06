package application.module.node.lifecycle;

import org.junit.jupiter.api.Test;

import static application.module.node.lifecycle.NodeOperatingState.GENERATING;
import static application.module.node.lifecycle.NodeOperatingState.PAUSED_SYSTEM;
import static application.module.node.lifecycle.NodeOperatingState.PAUSED_USER;
import static application.module.node.lifecycle.NodeOperatingState.SYNC_IDLE;
import static application.module.node.lifecycle.NodeOperatingState.SYNCING;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NodeOperatingState} enum.
 * Validates transition rules and helper methods following AAA pattern.
 */
class NodeOperatingStateTest {

    // --- Transition matrix tests ---

    @Test
    void canTransitionTo_selfTransitionNotAllowed_forAllStates() {
        for (NodeOperatingState state : NodeOperatingState.values()) {
            assertFalse(state.canTransitionTo(state),
                    () -> state.name() + " should not allow self-transition");
        }
    }

    @Test
    void syncIdle_canGoToSyncingAndPausedStates() {
        assertTrue(SYNC_IDLE.canTransitionTo(SYNCING));
        assertTrue(SYNC_IDLE.canTransitionTo(PAUSED_USER));
        assertTrue(SYNC_IDLE.canTransitionTo(PAUSED_SYSTEM));
        assertFalse(SYNC_IDLE.canTransitionTo(GENERATING));
    }

    @Test
    void syncing_canGoAnywhere() {
        assertTrue(SYNCING.canTransitionTo(SYNC_IDLE));
        assertTrue(SYNCING.canTransitionTo(GENERATING));
        assertTrue(SYNCING.canTransitionTo(PAUSED_USER));
        assertTrue(SYNCING.canTransitionTo(PAUSED_SYSTEM));
    }

    @Test
    void generating_canGoToIdleSyncingAndPaused() {
        assertTrue(GENERATING.canTransitionTo(SYNC_IDLE));
        assertTrue(GENERATING.canTransitionTo(SYNCING));
        assertTrue(GENERATING.canTransitionTo(PAUSED_USER));
        assertTrue(GENERATING.canTransitionTo(PAUSED_SYSTEM));
    }

    @Test
    void pausedUser_canOnlyResume() {
        assertTrue(PAUSED_USER.canTransitionTo(SYNCING));
        assertFalse(PAUSED_USER.canTransitionTo(SYNC_IDLE));
        assertFalse(PAUSED_USER.canTransitionTo(GENERATING));
        assertFalse(PAUSED_USER.canTransitionTo(PAUSED_SYSTEM));
    }

    @Test
    void pausedSystem_canRestore() {
        assertTrue(PAUSED_SYSTEM.canTransitionTo(SYNC_IDLE));
        assertTrue(PAUSED_SYSTEM.canTransitionTo(SYNCING));
        assertFalse(PAUSED_SYSTEM.canTransitionTo(GENERATING));
        assertFalse(PAUSED_SYSTEM.canTransitionTo(PAUSED_USER));
    }

    // --- Helper method tests ---

    @Test
    void isSyncing_onlyTrueForSyncing() {
        assertTrue(SYNCING.isSyncing());
        assertFalse(SYNC_IDLE.isSyncing());
        assertFalse(GENERATING.isSyncing());
        assertFalse(PAUSED_USER.isSyncing());
        assertFalse(PAUSED_SYSTEM.isSyncing());
    }

    @Test
    void isPaused_trueForBothPauseTypes() {
        assertTrue(PAUSED_USER.isPaused());
        assertTrue(PAUSED_SYSTEM.isPaused());
        assertFalse(SYNC_IDLE.isPaused());
        assertFalse(SYNCING.isPaused());
        assertFalse(GENERATING.isPaused());
    }

    @Test
    void isUserPaused_onlyForPausedUser() {
        assertTrue(PAUSED_USER.isUserPaused());
        assertFalse(PAUSED_SYSTEM.isUserPaused());
        assertFalse(SYNC_IDLE.isUserPaused());
        assertFalse(SYNCING.isUserPaused());
        assertFalse(GENERATING.isUserPaused());
    }

    @Test
    void isSystemPaused_onlyForPausedSystem() {
        assertTrue(PAUSED_SYSTEM.isSystemPaused());
        assertFalse(PAUSED_USER.isSystemPaused());
        assertFalse(SYNC_IDLE.isSystemPaused());
        assertFalse(SYNCING.isSystemPaused());
        assertFalse(GENERATING.isSystemPaused());
    }

    @Test
    void isIdle_onlyForSyncIdle() {
        assertTrue(SYNC_IDLE.isIdle());
        assertFalse(SYNCING.isIdle());
        assertFalse(GENERATING.isIdle());
        assertFalse(PAUSED_USER.isIdle());
        assertFalse(PAUSED_SYSTEM.isIdle());
    }

    // --- Description test ---

    @Test
    void getDescription_returnsNonEmptyString_forAllStates() {
        for (NodeOperatingState state : NodeOperatingState.values()) {
            assertNotNull(state.getDescription());
            assertTrue(state.getDescription().length() > 0,
                    () -> state.name() + " has empty description");
        }
    }
}
