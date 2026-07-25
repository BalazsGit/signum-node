package application.module.node.lifecycle;

import application.module.node.profile.NodeProfile;

import java.util.EventListener;

/**
 * Observer interface for lifecycle and operating state changes.
 * Implement this to receive notifications when a Node profile's lifecycle state
 * or operating substate changes.
 * <p>
 * The nested state machine model separates concerns:
 * <ul>
 *   <li>{@link #onStateChanged} - Parent lifecycle transitions (STOPPED, RUNNING, FAILED, etc.)</li>
 *   <li>{@link #onOperatingStateChanged} - Operating substate transitions within RUNNING
 *       (SYNCING, SYNC_IDLE, PAUSED_USER, PAUSED_SYSTEM, GENERATING)</li>
 * </ul>
 *
 * @since 4.0
 */
public interface LifecycleListener extends EventListener {

    /**
     * Called when a node profile transitions from one lifecycle state to another.
     *
     * @param profile  the profile that changed (runtime state via {@link NodeProfile#getRuntime()})
     * @param oldState the previous lifecycle state
     * @param newState the current lifecycle state
     */
    void onStateChanged(NodeProfile profile, NodeLifecycleState oldState, NodeLifecycleState newState);

    /**
     * Called when a node profile's operating substate changes within the RUNNING
     * lifecycle state. This includes sync progress, user pause/resume, and
     * system-initiated pauses (DB check, pop-off, trim).
     *
     * @param profile     the profile that changed
     * @param oldSubstate the previous operating substate
     * @param newSubstate the current operating substate
     */
    default void onOperatingStateChanged(NodeProfile profile,
                                         NodeOperatingState oldSubstate,
                                         NodeOperatingState newSubstate) {
    }

    /**
     * Called when a node profile emits a status message (optional).
     *
     * @param profile the profile that emitted the message
     * @param message the status message
     */
    default void onStatusMessage(NodeProfile profile, String message) {
    }

    /**
     * Called when a node profile encounters an error (optional).
     *
     * @param profile      the profile that encountered the error
     * @param errorMessage description of the error
     */
    default void onError(NodeProfile profile, String errorMessage) {
    }

    /**
     * Called RIGHT BEFORE a node profile is stopped.
     * This is the centralized shutdown hook - all shutdown paths (ApplicationShutdown,
     * button clicks, restart, etc.) go through NodeLifecycleManager which calls this
     * before performing the actual stop.
     *
     * Use this callback to save GUI state, persist settings, or perform cleanup
     * that must happen before the node stops.
     *
     * @param profile the profile that is about to be stopped
     */
    default void onShutdownRequested(NodeProfile profile) {
    }
}
