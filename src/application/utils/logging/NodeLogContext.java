package application.utils.logging;

import java.util.Objects;

/**
 * Thread-local scope that carries the active node profile name.
 * <p>
 * Each node runs its core work on its own dedicated threads. By setting this
 * context at the entry points (node start, GUI panel construction), the
 * {@link SystemLoggerJulHandler} can route log events to the correct
 * per-profile {@link ProfileLogger}.
 * </p>
 * <p>
 * <b>Usage:</b>
 * <pre>{@code
 * NodeLogContext.set("mainnet");
 * try {
 *     // ... node work ...
 * } finally {
 *     NodeLogContext.clear();
 * }
 * }</pre>
 * or the convenience method:
 * <pre>{@code
 * NodeLogContext.runIn("mainnet", () -> {
 *     // ... node work ...
 * });
 * }</pre>
 *
 * @see SystemLoggerJulHandler
 * @see NodeLoggerRegistry
 */
public final class NodeLogContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private NodeLogContext() {
        // Utility class
    }

    /**
     * Sets the active node profile name for the current thread.
     *
     * @param profileName the profile name (e.g. "mainnet", "testnet")
     */
    public static void set(String profileName) {
        CURRENT.set(Objects.requireNonNull(profileName, "profileName must not be null"));
    }

    /**
     * Returns the active node profile name for the current thread, or null
     * if no node context is active.
     *
     * @return the profile name, or null
     */
    public static String current() {
        return CURRENT.get();
    }

    /**
     * Clears the node profile context for the current thread.
     * <p>
     * <b>Must be called in a finally block</b> after {@link #set(String)} to
     * prevent leaking the context to subsequent tasks on pooled threads.
     * </p>
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Runs the given task within a node profile context.
     * The context is automatically cleared after the task completes (even on exception).
     *
     * @param profileName the profile name to scope
     * @param task        the task to execute
     */
    public static void runIn(String profileName, Runnable task) {
        String previous = CURRENT.get();
        set(profileName);
        try {
            task.run();
        } finally {
            if (previous != null) {
                CURRENT.set(previous);
            } else {
                clear();
            }
        }
    }
}