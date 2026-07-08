package application.module.node.lifecycle;

import application.module.node.instance.NodeCoreContext;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds runtime information about a single Node profile instance.
 * Thread-safe state transitions are managed via AtomicReference.
 * <p>
 * Supports nested state machines: the parent {@link NodeLifecycleState} tracks
 * high-level lifecycle (IDLE, RUNNING, STOPPED, etc.), while the child
 * {@link NodeOperatingState} tracks operational substates within RUNNING
 * (SYNCING, SYNC_IDLE, PAUSED_USER, PAUSED_SYSTEM, GENERATING).
 */
public class NodeInstanceInfo {

    private final String profileName;
    private final AtomicReference<NodeLifecycleState> state;
    /** Operating substate - only meaningful when parent state is RUNNING */
    private final AtomicReference<NodeOperatingState> operatingState;
    private volatile long startTime;
    private volatile Long stopTime;
    private volatile String errorMessage;
    private volatile String statusMessage;
    private int apiPort;
    private int p2pPort;

    // --- Synchronization tracking ---
    /** Timestamp when current sync session started (ms) */
    private volatile long syncStartTime;
    /** Timestamp when last sync session ended (ms) */
    private volatile Long syncEndTime;
    /** Total accumulated time spent syncing (ms) across all sessions */
    private volatile long accumulatedSyncTimeMs;
    /** Current number of missing blocks behind the network */
    private volatile long missingBlocks;

    // --- Hysteresis thresholds for sync state transitions ---
    /** Upper threshold: SYNC_IDLE -> SYNCING when missingBlocks exceeds this (default: 10) */
    private volatile int hysteresisThresholdHi = 10;
    /** Lower threshold: SYNCING -> SYNC_IDLE when missingBlocks drops to or below this (default: 1) */
    private volatile int hysteresisThresholdLo = 1;

    // --- NodeCoreContext reference ---
    /** Reference to the running NodeCoreContext for this profile (null if not started) */
    private volatile NodeCoreContext coreContext;

    public NodeInstanceInfo(String profileName) {
        this.profileName = profileName;
        this.state = new AtomicReference<>(NodeLifecycleState.IDLE);
        this.operatingState = new AtomicReference<>(NodeOperatingState.SYNC_IDLE);
        this.startTime = 0;
        this.stopTime = null;
        this.errorMessage = null;
        this.statusMessage = "Not initialized";
        this.syncStartTime = 0;
        this.syncEndTime = null;
        this.accumulatedSyncTimeMs = 0;
        this.missingBlocks = 0;
    }

    // --- Getters ---

    public String getProfileName() {
        return profileName;
    }

    public NodeLifecycleState getState() {
        return state.get();
    }

    public long getStartTime() {
        return startTime;
    }

    public Long getStopTime() {
        return stopTime;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public int getApiPort() {
        return apiPort;
    }

    public void setApiPort(int apiPort) {
        this.apiPort = apiPort;
    }

    public int getP2pPort() {
        return p2pPort;
    }

    public void setP2pPort(int p2pPort) {
        this.p2pPort = p2pPort;
    }

    // --- State transitions (thread-safe) ---

    /**
     * Attempts to transition the parent lifecycle state. Validates the transition first.
     *
     * @param newState the target lifecycle state
     * @return true if transition was successful
     */
    public boolean setState(NodeLifecycleState newState) {
        NodeLifecycleState currentState = state.get();
        if (!currentState.canTransitionTo(newState)) {
            return false;
        }
        return state.compareAndSet(currentState, newState);
    }

    /**
     * Force set parent lifecycle state (bypasses transition validation). Use only for initial setup.
     */
    public void forceState(NodeLifecycleState newState) {
        state.set(newState);
    }

    // --- Operating substate transitions (thread-safe) ---

    /**
     * Gets the current operating substate.
     */
    public NodeOperatingState getOperatingState() {
        return operatingState.get();
    }

    /**
     * Attempts to transition to a new operating substate. Validates the transition first.
     *
     * @param newOperatingState the target operating state
     * @return true if transition was successful
     */
    public boolean setOperatingState(NodeOperatingState newOperatingState) {
        NodeOperatingState currentOperatingState = operatingState.get();
        if (!currentOperatingState.canTransitionTo(newOperatingState)) {
            return false;
        }
        return operatingState.compareAndSet(currentOperatingState, newOperatingState);
    }

    /**
     * Force set operating substate (bypasses transition validation). Use only for initial setup.
     */
    public void forceOperatingState(NodeOperatingState newOperatingState) {
        operatingState.set(newOperatingState);
    }

    // --- Status messages ---

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    // --- Timing markers ---

    public void markStarted() {
        this.startTime = System.currentTimeMillis();
    }

    public void markStopped() {
        this.stopTime = System.currentTimeMillis();
    }

    /**
     * Returns uptime in seconds if running, otherwise 0.
     */
    public long getUptimeSeconds() {
        if (isActive()) {
            return (System.currentTimeMillis() - startTime) / 1000;
        }
        return 0;
    }

    /**
     * Convenience check if the node is currently active (RUNNING or PAUSED).
     */
    public boolean isActive() {
        return state.get().isActive();
    }

    // --- Synchronization tracking getters/setters ---

    /** Gets timestamp when current sync session started (ms), 0 if never synced. */
    public long getSyncStartTime() {
        return syncStartTime;
    }

    /** Sets timestamp when a sync session starts. */
    public void setSyncStartTime(long syncStartTime) {
        this.syncStartTime = syncStartTime;
    }

    /** Gets timestamp when last sync session ended (ms), null if currently syncing. */
    public Long getSyncEndTime() {
        return syncEndTime;
    }

    /** Sets timestamp when a sync session ends. */
    public void setSyncEndTime(Long syncEndTime) {
        this.syncEndTime = syncEndTime;
    }

    /** Gets total accumulated time spent syncing across all sessions (ms). */
    public long getAccumulatedSyncTimeMs() {
        return accumulatedSyncTimeMs;
    }

    /** Sets total accumulated time spent syncing (ms). */
    public void setAccumulatedSyncTimeMs(long accumulatedSyncTimeMs) {
        this.accumulatedSyncTimeMs = accumulatedSyncTimeMs;
    }

    /** Gets current number of missing blocks behind the network. */
    public long getMissingBlocks() {
        return missingBlocks;
    }

    /** Sets current number of missing blocks behind the network. */
    public void setMissingBlocks(long missingBlocks) {
        this.missingBlocks = missingBlocks;
    }

    // --- Hysteresis threshold accessors ---

    /** Gets upper hysteresis threshold for SYNC_IDLE -> SYNCING transition. Default: 10. */
    public int getHysteresisThresholdHi() {
        return hysteresisThresholdHi;
    }

    /** Sets upper hysteresis threshold for SYNC_IDLE -> SYNCING transition. */
    public void setHysteresisThresholdHi(int hysteresisThresholdHi) {
        this.hysteresisThresholdHi = hysteresisThresholdHi;
    }

    /** Gets lower hysteresis threshold for SYNCING -> SYNC_IDLE transition. Default: 1. */
    public int getHysteresisThresholdLo() {
        return hysteresisThresholdLo;
    }

    /** Sets lower hysteresis threshold for SYNCING -> SYNC_IDLE transition. */
    public void setHysteresisThresholdLo(int hysteresisThresholdLo) {
        this.hysteresisThresholdLo = hysteresisThresholdLo;
    }

    // --- NodeCoreContext accessors ---

    /**
     * Gets the NodeCoreContext for this profile instance.
     * Returns null if the profile has not been started yet.
     */
    public NodeCoreContext getCoreContext() {
        return coreContext;
    }

    /**
     * Sets the NodeCoreContext reference when the profile is started.
     */
    public void setCoreContext(NodeCoreContext coreContext) {
        this.coreContext = coreContext;
    }

    // --- Computed sync duration helpers ---

    /**
     * Returns the current sync session duration in seconds if actively syncing,
     * otherwise returns 0.
     */
    public long getCurrentSyncDurationSeconds() {
        NodeOperatingState opState = operatingState.get();
        if (opState == NodeOperatingState.SYNCING && syncStartTime > 0) {
            return (System.currentTimeMillis() - syncStartTime) / 1000;
        }
        return 0;
    }

    /**
     * Returns the last completed sync session duration in seconds, or 0 if none.
     */
    public long getLastSyncDurationSeconds() {
        if (syncEndTime != null && syncStartTime > 0) {
            return (syncEndTime - syncStartTime) / 1000;
        }
        return 0;
    }

    @Override
    public String toString() {
        return "NodeInstanceInfo{" +
                "profileName='" + profileName + '\'' +
                ", state=" + state.get() +
                ", operatingState=" + operatingState.get() +
                ", apiPort=" + apiPort +
                ", p2pPort=" + p2pPort +
                ", missingBlocks=" + missingBlocks +
                ", coreContext=" + (coreContext != null ? "present" : "null") +
                '}';
    }
}
