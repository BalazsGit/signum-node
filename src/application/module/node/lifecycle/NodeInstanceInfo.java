package application.module.node.lifecycle;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds runtime information about a single Node profile instance.
 * Thread-safe state transitions are managed via AtomicReference.
 */
public class NodeInstanceInfo {

    private final String profileName;
    private final AtomicReference<NodeLifecycleState> state;
    private volatile long startTime;
    private volatile Long stopTime;
    private volatile String errorMessage;
    private volatile String statusMessage;
    private int apiPort;
    private int p2pPort;

    public NodeInstanceInfo(String profileName) {
        this.profileName = profileName;
        this.state = new AtomicReference<>(NodeLifecycleState.IDLE);
        this.startTime = 0;
        this.stopTime = null;
        this.errorMessage = null;
        this.statusMessage = "Not initialized";
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
     * Attempts to transition to a new state. Validates the transition first.
     *
     * @param newState the target state
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
     * Force set state (bypasses transition validation). Use only for initial setup.
     */
    public void forceState(NodeLifecycleState newState) {
        state.set(newState);
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

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

    @Override
    public String toString() {
        return "NodeInstanceInfo{" +
                "profileName='" + profileName + '\'' +
                ", state=" + state.get() +
                ", apiPort=" + apiPort +
                ", p2pPort=" + p2pPort +
                '}';
    }
}