package application.module.node.lifecycle;

import java.util.EventListener;

/**
 * Observer interface for lifecycle and operating state changes.
 * Implement this to receive notifications when a Node profile's lifecycle state
 * or operating substate changes.
 * <p>
 * The nested state machine model separates concerns:
 * <ul>
 *   <li>{@link #onStateChanged} - Parent lifecycle transitions (IDLE, RUNNING, STOPPED, etc.)</li>
 *   <li>{@link #onOperatingStateChanged} - Operating substate transitions within RUNNING
 *       (SYNCING, SYNC_IDLE, PAUSED_USER, PAUSED_SYSTEM, GENERATING)</li>
 * </ul>
 */
public interface LifecycleListener extends EventListener {

    /**
     * Called when a node profile transitions from one lifecycle state to another.
     *
     * @param instanceInfo the instance that changed
     * @param oldState     the previous lifecycle state
     * @param newState     the current lifecycle state
     */
    void onStateChanged(NodeInstanceInfo instanceInfo, NodeLifecycleState oldState, NodeLifecycleState newState);

    /**
     * Called when a node profile's operating substate changes within the RUNNING
     * lifecycle state. This includes sync progress, user pause/resume, and
     * system-initiated pauses (DB check, pop-off, trim).
     *
     * @param instanceInfo  the instance that changed
     * @param oldSubstate   the previous operating substate
     * @param newSubstate   the current operating substate
     */
    default void onOperatingStateChanged(NodeInstanceInfo instanceInfo,
                                         NodeOperatingState oldSubstate,
                                         NodeOperatingState newSubstate) {
    }

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
