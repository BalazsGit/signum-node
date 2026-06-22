package application.module.node.lifecycle;

import java.util.EventListener;

/**
 * Observer interface for lifecycle state changes.
 * Implement this to receive notifications when a Node profile's lifecycle state
 * changes.
 */
public interface LifecycleListener extends EventListener {

    /**
     * Called when a node profile transitions from one state to another.
     *
     * @param instanceInfo the instance that changed
     * @param oldState     the previous state
     * @param newState     the current state
     */
    void onStateChanged(NodeInstanceInfo instanceInfo, NodeLifecycleState oldState, NodeLifecycleState newState);

    /**
     * Called when a node profile emits a status message (optional).
     *
     * @param instanceInfo the instance that emitted the message
     * @param message      the status message
     */
    default void onStatusMessage(NodeInstanceInfo instanceInfo, String message) {
    }

    /**
     * Called when a node profile encounters an error (optional).
     *
     * @param instanceInfo the instance that encountered the error
     * @param errorMessage description of the error
     */
    default void onError(NodeInstanceInfo instanceInfo, String errorMessage) {
    }
}