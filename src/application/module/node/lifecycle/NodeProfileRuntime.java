package application.module.node.lifecycle;

import application.module.node.instance.NodeCoreContext;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds all runtime state for a single Node profile instance.
 * <p>
 * Implements the <b>Composition over Inheritance</b> principle by being a separate
 * component from {@link application.module.node.profile.NodeProfile}. The profile
 * config entity is stable and largely immutable, while this runtime container
 * manages frequently-changing operational data (lifecycle state, sync tracking,
 * port info, timing markers).
 * </p>
 * <p>
 * <h3>Design Decisions</h3>
 * <ul>
 *   <li><b>Single Responsibility:</b> ONLY runtime state. Configuration belongs
 *       in the parent NodeProfile.</li>
 *   <li><b>Thread-safety:</b> Lifecycle uses {@link LifecycleStateMachine} (atomic).
 *       Operating state uses {@link AtomicReference}. Simple markers are volatile.</li>
 *   <li><b>No database access:</b> This class is a pure state container. All I/O
 *       happens through the referenced {@link NodeCoreContext}.</li>
 * </ul>
 *
 * <h3>Composition Model</h3>
 * <pre>
 *   NodeProfile (config)
 *     └─ runtime: NodeProfileRuntime ← this class
 *          ├─ stateMachine: LifecycleStateMachine
 *          ├─ operatingState: AtomicReference<NodeOperatingState>
 *          ├─ coreContext: NodeCoreContext
 *          ├─ sync tracking fields
 *          └─ timing markers
 * </pre>
 *
 * @since 4.0
 */
public final class NodeProfileRuntime {

    // ── Lifecycle State (State Pattern) ─────────────────────────────────

    /** Parent lifecycle state machine — manages validated transitions. */
    private final LifecycleStateMachine stateMachine;

    /**
     * Operating substate — only meaningful when parent state is RUNNING.
     * Tracks sync progress, user/system pauses, and block generation.
     */
    private final AtomicReference<NodeOperatingState> operatingState =
            new AtomicReference<>(NodeOperatingState.SYNC_IDLE);

    // ── Status Messages ─────────────────────────────────────────────────

    /** Human-readable status message for UI display. */
    private volatile String statusMessage = "Not initialized";

    /** Last error message encountered during lifecycle operations. */
    private volatile String errorMessage;

    // ── Timing Markers ──────────────────────────────────────────────────

    /** Timestamp when the node was started (ms). 0 means never started. */
    private volatile long startTime;

    /** Timestamp when the node was last stopped (ms). Null if currently running. */
    private volatile Long stopTime;

    // ── Port Configuration (Runtime) ────────────────────────────────────

    /** API server port assigned at runtime. */
    private volatile int apiPort;

    /** P2P network port assigned at runtime. */
    private volatile int p2pPort;

    // ── Synchronization Tracking ────────────────────────────────────────

    /** Timestamp when current sync session started (ms). 0 if never synced. */
    private volatile long syncStartTime;

    /** Timestamp when last sync session ended (ms). Null if currently syncing. */
    private volatile Long syncEndTime;

    /** Total accumulated time spent syncing across all sessions (ms). */
    private volatile long accumulatedSyncTimeMs;

    /** Current number of missing blocks behind the network. */
    private volatile long missingBlocks;

    // ── Hysteresis Thresholds ───────────────────────────────────────────

    /**
     * Upper threshold: SYNC_IDLE → SYNCING when missingBlocks exceeds this.
     * Default: 10.
     */
    private volatile int hysteresisThresholdHi = 10;

    /**
     * Lower threshold: SYNCING → SYNC_IDLE when missingBlocks drops to or below this.
     * Default: 1.
     */
    private volatile int hysteresisThresholdLo = 1;

    // ── Runtime Context ────────────────────────────────────────────────

    /**
     * Reference to the running NodeCoreContext for this profile.
     * Null if the profile has not been started yet.
     */
    private volatile NodeCoreContext coreContext;

    // ── Construction ────────────────────────────────────────────────────

    /**
     * Creates a new runtime state container with default-initialized values.
     * Lifecycle starts in IDLE, operating state starts in SYNC_IDLE.
     */
    public NodeProfileRuntime() {
        this.stateMachine = new LifecycleStateMachine();
        this.startTime = 0;
        this.stopTime = null;
        this.errorMessage = null;
        this.syncStartTime = 0;
        this.syncEndTime = null;
        this.accumulatedSyncTimeMs = 0;
        this.missingBlocks = 0;
    }

    // ── Lifecycle State Access ──────────────────────────────────────────

    /**
     * Returns the lifecycle state machine for this profile.
     * Use this to query current state, perform transitions, or register listeners.
     *
     * @return the {@link LifecycleStateMachine} (never null)
     */
    public LifecycleStateMachine getStateMachine() {
        return stateMachine;
    }

    /**
     * Convenience: returns the current lifecycle state.
     * Equivalent to {@code getStateMachine().get()}.
     *
     * @return the current {@link NodeLifecycleState}
     */
    public NodeLifecycleState getLifecycleState() {
        return stateMachine.get();
    }

    /**
     * Attempts a lifecycle state transition.
     *
     * @param newState the target state
     * @return true if the transition was applied
     */
    public boolean setLifecycleState(NodeLifecycleState newState) {
        return stateMachine.transitionTo(newState);
    }

    /**
     * Force-set lifecycle state (bypasses validation). Use only for initial setup.
     *
     * @param newState the state to force-set
     */
    public void forceLifecycleState(NodeLifecycleState newState) {
        stateMachine.forceSet(newState);
    }

    // ── Operating Substate Access ───────────────────────────────────────

    /**
     * Returns the current operating substate.
     * Only meaningful when the parent lifecycle state is RUNNING.
     *
     * @return the current {@link NodeOperatingState}
     */
    public NodeOperatingState getOperatingState() {
        return operatingState.get();
    }

    /**
     * Attempts to transition to a new operating substate.
     * Validates the transition using {@link NodeOperatingState#canTransitionTo(NodeOperatingState)}.
     *
     * @param newOperatingState the target operating state
     * @return true if the transition was applied
     */
    public boolean setOperatingState(NodeOperatingState newOperatingState) {
        NodeOperatingState current = operatingState.get();
        if (!current.canTransitionTo(newOperatingState)) {
            return false;
        }
        return operatingState.compareAndSet(current, newOperatingState);
    }

    /**
     * Force-set operating substate (bypasses validation). Use only for initial setup.
     *
     * @param newOperatingState the state to force-set
     */
    public void forceOperatingState(NodeOperatingState newOperatingState) {
        operatingState.set(newOperatingState);
    }

    // ── Status Messages ─────────────────────────────────────────────────

    /**
     * Returns the current status message.
     *
     * @return the status message (never null)
     */
    public String getStatusMessage() {
        return statusMessage;
    }

    /**
     * Sets a new status message.
     *
     * @param statusMessage the message to display
     */
    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    /**
     * Returns the last error message, or null if no error has occurred.
     *
     * @return the error message, or null
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Sets an error message.
     *
     * @param errorMessage description of the error
     */
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    // ── Timing Markers ──────────────────────────────────────────────────

    /** Marks the node as started, recording the current timestamp. */
    public void markStarted() {
        this.startTime = System.currentTimeMillis();
    }

    /** Marks the node as stopped, recording the current timestamp. */
    public void markStopped() {
        this.stopTime = System.currentTimeMillis();
    }

    /**
     * Returns the start timestamp (ms). 0 if never started.
     */
    public long getStartTime() {
        return startTime;
    }

    /**
     * Returns the stop timestamp (ms). Null if currently running.
     */
    public Long getStopTime() {
        return stopTime;
    }

    /**
     * Returns uptime in seconds if the node is active, otherwise 0.
     *
     * @return uptime in seconds
     */
    public long getUptimeSeconds() {
        if (isActive()) {
            return (System.currentTimeMillis() - startTime) / 1000;
        }
        return 0;
    }

    // ── Port Access ─────────────────────────────────────────────────────

    /** Gets the API server port. */
    public int getApiPort() {
        return apiPort;
    }

    /** Sets the API server port. */
    public void setApiPort(int apiPort) {
        this.apiPort = apiPort;
    }

    /** Gets the P2P network port. */
    public int getP2pPort() {
        return p2pPort;
    }

    /** Sets the P2P network port. */
    public void setP2pPort(int p2pPort) {
        this.p2pPort = p2pPort;
    }

    // ── Synchronization Tracking ────────────────────────────────────────

    /** Gets timestamp when current sync session started (ms). 0 if never synced. */
    public long getSyncStartTime() {
        return syncStartTime;
    }

    /** Sets timestamp when a sync session starts. */
    public void setSyncStartTime(long syncStartTime) {
        this.syncStartTime = syncStartTime;
    }

    /** Gets timestamp when last sync session ended (ms). Null if currently syncing. */
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

    // ── Hysteresis Thresholds ───────────────────────────────────────────

    /** Gets upper hysteresis threshold for SYNC_IDLE → SYNCING transition. Default: 10. */
    public int getHysteresisThresholdHi() {
        return hysteresisThresholdHi;
    }

    /** Sets upper hysteresis threshold. */
    public void setHysteresisThresholdHi(int hysteresisThresholdHi) {
        this.hysteresisThresholdHi = hysteresisThresholdHi;
    }

    /** Gets lower hysteresis threshold for SYNCING → SYNC_IDLE transition. Default: 1. */
    public int getHysteresisThresholdLo() {
        return hysteresisThresholdLo;
    }

    /** Sets lower hysteresis threshold. */
    public void setHysteresisThresholdLo(int hysteresisThresholdLo) {
        this.hysteresisThresholdLo = hysteresisThresholdLo;
    }

    // ── NodeCoreContext Access ──────────────────────────────────────────

    /**
     * Gets the NodeCoreContext for this profile instance.
     * Returns null if the profile has not been started yet.
     *
     * @return the core context, or null
     */
    public NodeCoreContext getCoreContext() {
        return coreContext;
    }

    /**
     * Sets the NodeCoreContext reference when the profile is started.
     *
     * @param coreContext the runtime context to associate with this profile
     */
    public void setCoreContext(NodeCoreContext coreContext) {
        this.coreContext = coreContext;
    }

    // ── Computed Sync Duration Helpers ──────────────────────────────────

    /**
     * Returns the current sync session duration in seconds if actively syncing,
     * otherwise returns 0.
     *
     * @return current sync duration in seconds
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
     *
     * @return last sync duration in seconds
     */
    public long getLastSyncDurationSeconds() {
        if (syncEndTime != null && syncStartTime > 0) {
            return (syncEndTime - syncStartTime) / 1000;
        }
        return 0;
    }

    // ── Convenience Checks ──────────────────────────────────────────────

    /**
     * Returns true if the node is currently active (RUNNING or PAUSED).
     * Delegates to the lifecycle state machine.
     */
    public boolean isActive() {
        return stateMachine.isActive();
    }

    /**
     * Returns true if the node is in a terminal state (STOPPED or ERROR).
     * Delegates to the lifecycle state machine.
     */
    public boolean isTerminal() {
        return stateMachine.isTerminal();
    }

    // ── Object Contract ────────────────────────────────────────────────

    @Override
    public String toString() {
        return "NodeProfileRuntime{" +
                "lifecycleState=" + stateMachine.get() +
                ", operatingState=" + operatingState.get() +
                ", apiPort=" + apiPort +
                ", p2pPort=" + p2pPort +
                ", missingBlocks=" + missingBlocks +
                ", active=" + isActive() +
                ", coreContext=" + (coreContext != null ? "present" : "null") +
                '}';
    }
}