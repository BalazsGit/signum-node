package application.module.node.lifecycle;

/**
 * Represents the lifecycle states of a Node profile instance.
 * Follows a state machine pattern with validated transitions.
 *
 * State Diagram:
 *
 * IDLE -> INITIALIZING -> READY <-> RUNNING <-> PAUSED -> STOPPING -> STOPPED
 * | | | |
 * +-- error --+-- error --/+-- error -/
 * \ /
 * ERROR --> IDLE (reset)
 */
public enum NodeLifecycleState {

    /** Profile exists but not initialized yet (placeholder state) */
    IDLE(0, "Idle", new int[] { 1 }), // can go to INITIALIZING

    /** Loading configuration, preparing resources (no side effects) */
    INITIALIZING(1, "Initializing", new int[] { 2, 7 }), // READY or ERROR

    /** Initialized and ready to start; waiting for user/start command */
    READY(2, "Ready", new int[] { 3, 5, 7 }), // RUNNING, STOPPING, ERROR

    /** Node is actively running (P2P active, syncing, serving API) */
    RUNNING(3, "Running", new int[] { 4, 5, 7 }), // PAUSED, STOPPING, ERROR

    /** Synchronization paused by user command */
    PAUSED(4, "Paused", new int[] { 3, 5, 7 }), // RUNNING, STOPPING, ERROR

    /** Graceful shutdown in progress */
    STOPPING(5, "Stopping", new int[] { 6, 7 }), // STOPPED or ERROR

    /** Cleanly stopped; resources released, can be re-initialized */
    STOPPED(6, "Stopped", new int[] { 0, 1 }), // IDLE or INITIALIZING

    /** Failed to initialize or start; must be reset to IDLE before retry */
    ERROR(7, "Error", new int[] { 0 }); // IDLE (reset)

    private final String description;
    private final int[] allowedTransitions;

    NodeLifecycleState(int ordinal, String description, int[] allowedTransitions) {
        this.description = description;
        this.allowedTransitions = allowedTransitions;
    }

    /**
     * Human-readable description of this state.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Checks if a transition from this state to the target state is valid.
     *
     * @param target the target state to transition to
     * @return true if the transition is allowed
     */
    public boolean canTransitionTo(NodeLifecycleState target) {
        for (int allowedOrdinal : allowedTransitions) {
            if (allowedOrdinal == target.ordinal()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if this is a terminal state where the node is not accepting work.
     */
    public boolean isTerminal() {
        return this == STOPPED || this == ERROR;
    }

    /**
     * Checks if this is an active/running state.
     */
    public boolean isActive() {
        return this == RUNNING || this == PAUSED;
    }
}