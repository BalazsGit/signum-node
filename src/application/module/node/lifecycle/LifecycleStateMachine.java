package application.module.node.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe state machine that manages lifecycle transitions for a Node profile instance.
 * <p>
 * Implements the <b>State Pattern</b> (GoF) by encapsulating transition validation
 * and observer notification in a single, focused component. The actual transition rules
 * are defined in {@link NodeLifecycleState#canTransitionTo(NodeLifecycleState)}, keeping
 * this class thin and well-documented.
 * </p>
 * <p>
 * <h3>Design Decisions</h3>
 * <ul>
 *   <li><b>Delegate validation to enum:</b> Transition rules live in the state enum itself
 *       (closed principle — new states add their own rules). This class orchestrates.</li>
 *   <li><b>CopyOnWriteArrayList for listeners:</b> Safe iteration during concurrent add/remove
 *       without explicit synchronization.</li>
 *   <li><b>AtomicReference for state:</b> Lock-free, thread-safe state transitions using
 *       compare-and-set semantics.</li>
 *   <li><b>Never throws:</b> All public methods return boolean success indicators. Callers
 *       are responsible for handling rejection.</li>
 * </ul>
 *
 * <h3>State Diagram</h3>
 * <pre>
 * IDLE → INITIALIZING → READY ↔ RUNNING ↔ PAUSED → STOPPING → STOPPED
 *                \      /                          |
 *                 → WAITING_FOR_DATABASE ──────────┘
 *                  |                              |
 *                  +→ ERROR ←─────────────────────+
 * </pre>
 *
 * @see NodeLifecycleState
 * @see LifecycleListener
 * @since 4.0
 */
public final class LifecycleStateMachine {

    private final AtomicReference<NodeLifecycleState> state =
            new AtomicReference<>(NodeLifecycleState.IDLE);

    /** Observer list — safe for concurrent modification. */
    private final List<LifecycleTransitionListener> listeners = new CopyOnWriteArrayList<>();

    // ── Construction ────────────────────────────────────────────────────

    /**
     * Creates a new state machine starting in the {@link NodeLifecycleState#IDLE} state.
     */
    public LifecycleStateMachine() {
        // Default initial state is IDLE (set in field initializer)
    }

    /**
     * Creates a new state machine starting in the specified state.
     *
     * @param initialState the initial state; must not be null
     */
    public LifecycleStateMachine(NodeLifecycleState initialState) {
        if (initialState == null) {
            throw new IllegalArgumentException("Initial state must not be null");
        }
        this.state.set(initialState);
    }

    // ── State Access ────────────────────────────────────────────────────

    /**
     * Returns the current lifecycle state.
     *
     * @return the current {@link NodeLifecycleState} (never null)
     */
    public NodeLifecycleState get() {
        return state.get();
    }

    // ── Transitions ─────────────────────────────────────────────────────

    /**
     * Attempts to transition to the requested state.
     * <p>
     * The transition is validated by delegating to
     * {@link NodeLifecycleState#canTransitionTo(NodeLifecycleState)}. If valid,
     * a compare-and-set operation atomically updates the state and notifies all
     * registered listeners.
     * </p>
     *
     * @param requested the target state to transition to
     * @return true if the transition was successfully applied; false if the
     *         transition is not allowed per the current state, or if a concurrent
     *         modification prevented the update
     */
    public boolean transitionTo(NodeLifecycleState requested) {
        if (requested == null) {
            return false;
        }

        NodeLifecycleState current = state.get();

        // Fast-path: already in requested state
        if (current == requested) {
            return true;
        }

        // Validate transition via the enum's built-in rules
        if (!current.canTransitionTo(requested)) {
            return false;
        }

        // Atomically attempt the transition
        if (!state.compareAndSet(current, requested)) {
            return false; // Concurrent modification — another thread changed state
        }

        // Notify all observers after successful transition
        notifyListeners(current, requested);
        return true;
    }

    /**
     * Forcefully sets the state without transition validation.
     * <p>
     * Use only during initial setup or recovery scenarios where the normal
     * transition rules should be bypassed. This method does NOT notify listeners.
     * </p>
     *
     * @param newState the state to force-set
     */
    public void forceSet(NodeLifecycleState newState) {
        if (newState == null) {
            throw new IllegalArgumentException("State must not be null");
        }
        state.set(newState);
    }

    // ── Convenience Checks ──────────────────────────────────────────────

    /**
     * Returns true if the current state is active (RUNNING or PAUSED).
     */
    public boolean isActive() {
        return state.get().isActive();
    }

    /**
     * Returns true if the current state is terminal (STOPPED or ERROR).
     */
    public boolean isTerminal() {
        return state.get().isTerminal();
    }

    /**
     * Returns true if a transition to the requested state is currently allowed.
     * Does NOT perform the transition — only checks validity.
     *
     * @param requested the target state to check
     * @return true if the transition would be valid from the current state
     */
    public boolean canTransitionTo(NodeLifecycleState requested) {
        return state.get().canTransitionTo(requested);
    }

    // ── Observer Management ─────────────────────────────────────────────

    /**
     * Registers a listener to receive notifications on state transitions.
     *
     * @param listener the listener to add; must not be null
     */
    public void addListener(LifecycleTransitionListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener must not be null");
        }
        listeners.add(listener);
    }

    /**
     * Removes a previously registered listener.
     *
     * @param listener the listener to remove
     * @return true if the listener was found and removed
     */
    public boolean removeListener(LifecycleTransitionListener listener) {
        return listeners.remove(listener);
    }

    /**
     * Returns the current number of registered listeners.
     * Useful for testing to verify listener registration.
     *
     * @return listener count
     */
    public int getListenerCount() {
        return listeners.size();
    }

    // ── Internal Notification ───────────────────────────────────────────

    /**
     * Notifies all registered listeners of a state transition.
     * Called internally after a successful transition.
     *
     * @param from the previous state
     * @param to   the new state
     */
    private void notifyListeners(NodeLifecycleState from, NodeLifecycleState to) {
        for (LifecycleTransitionListener listener : listeners) {
            try {
                listener.onTransition(this, from, to);
            } catch (Throwable ex) {
                // Listener exceptions must not break the state machine.
                // Log and continue notifying remaining listeners.
                // TODO: Replace with proper SLF4J logging once profile logger is available
                System.err.println("LifecycleStateMachine listener error: " + ex.getMessage());
            }
        }
    }

    // ── Object Contract ────────────────────────────────────────────────

    @Override
    public String toString() {
        return "LifecycleStateMachine{state=" + state.get() + "}";
    }

    // ── Nested Listener Interface ───────────────────────────────────────

    /**
     * Callback interface for observing lifecycle state transitions.
     * <p>
     * Implementations receive notifications after a successful state change.
     * Methods are invoked synchronously on the thread that performed the transition.
     * </p>
     */
    public interface LifecycleTransitionListener {

        /**
         * Called after a successful state transition.
         *
         * @param machine  the state machine that changed
         * @param fromState the previous lifecycle state
         * @param toState   the new lifecycle state
         */
        void onTransition(LifecycleStateMachine machine,
                         NodeLifecycleState fromState,
                         NodeLifecycleState toState);
    }
}