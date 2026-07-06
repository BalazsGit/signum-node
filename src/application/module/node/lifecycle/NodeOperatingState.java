package application.module.node.lifecycle;

/**
 * Represents the operating substates within the RUNNING lifecycle state.
 * Follows a nested state machine pattern with validated transitions.
 *
 * This enum manages the synchronization and operational modes of a running node:
 *
 * State Diagram:
 *
 * SYNC_IDLE <-> SYNCING
 *     │           │
 *     ├────► PAUSED_USER (user command)
 *     │           │
 *     ├────► PAUSED_SYSTEM (auto: DB check / pop-off / trim)
 *     │           │
 *     └───► GENERATING (block generation active)
 *
 * Hysteresis thresholds control SYNC_IDLE ↔ SYNCING transitions:
 *   - SYNC_IDLE → SYNCING: missingBlocks > thresholdHigh (default: 10)
 *   - SYNCING → SYNC_IDLE: missingBlocks <= thresholdLow (default: 1)
 *
 * PAUSED_SYSTEM is transient and auto-resumes after the system operation completes.
 * PAUSED_USER persists until the user explicitly resumes.
 */
public enum NodeOperatingState {

    /** Node is in sync with the network; idle and accepting connections */
    SYNC_IDLE(0, "Sync Idle", new int[] { 1, 3, 4 }), // -> SYNCING, PAUSED_USER, PAUSED_SYSTEM

    /** Node is actively downloading and importing blocks to catch up */
    SYNCING(1, "Syncing", new int[] { 0, 2, 3, 4 }), // -> SYNC_IDLE, GENERATING, PAUSED_USER, PAUSED_SYSTEM

    /** Node is generating its own blocks (mining/forging active) */
    GENERATING(2, "Generating", new int[] { 0, 1, 3, 4 }), // -> SYNC_IDLE, SYNCING, PAUSED_USER, PAUSED_SYSTEM

    /** Synchronization paused by user command (.pause or GUI button) */
    PAUSED_USER(3, "Paused by User", new int[] { 1 }), // -> SYNCING (resume)

    /** Synchronization paused by system operation (DB check / pop-off / trim) - transient */
    PAUSED_SYSTEM(4, "Paused by System", new int[] { 0, 1 }); // -> SYNC_IDLE or SYNCING (auto-resume)

    private final String description;
    private final int[] allowedTransitions;

    NodeOperatingState(int ordinal, String description, int[] allowedTransitions) {
        this.description = description;
        this.allowedTransitions = allowedTransitions;
    }

    /**
     * Human-readable description of this operating state.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Checks if a transition from this state to the target state is valid.
     *
     * @param target the target operating state to transition to
     * @return true if the transition is allowed
     */
    public boolean canTransitionTo(NodeOperatingState target) {
        for (int allowedOrdinal : allowedTransitions) {
            if (allowedOrdinal == target.ordinal()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if this state indicates active block synchronization.
     */
    public boolean isSyncing() {
        return this == SYNCING;
    }

    /**
     * Checks if this state indicates any form of pause.
     */
    public boolean isPaused() {
        return this == PAUSED_USER || this == PAUSED_SYSTEM;
    }

    /**
     * Checks if this is a user-initiated pause (persistent until user resumes).
     */
    public boolean isUserPaused() {
        return this == PAUSED_USER;
    }

    /**
     * Checks if this is a system-initiated pause (transient, auto-resumes).
     */
    public boolean isSystemPaused() {
        return this == PAUSED_SYSTEM;
    }

    /**
     * Checks if the node is in an idle (fully synced) state.
     */
    public boolean isIdle() {
        return this == SYNC_IDLE;
    }
}