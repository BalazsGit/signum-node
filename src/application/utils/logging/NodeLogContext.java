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

    private static final ThreadLocal<LogScope> CURRENT = new ThreadLocal<>();

    private NodeLogContext() {
        // Utility class
    }

    /**
     * Sets the active node profile name for the current thread.
     *
     * @param profileName the profile name (e.g. "mainnet", "testnet")
     */
    public static void set(LogScope scope) {
        CURRENT.set(Objects.requireNonNull(scope, "scope must not be null"));
    }

    /**
     * Binds a (module, profile) scope to the current thread.
     *
     * @param module  the module id (e.g. "node")
     * @param profile the profile name within the module (e.g. "mainnet")
     */
    public static void set(String module, String profile) {
        set(LogScope.of(module, profile));
    }

    /**
     * Returns the active node profile name for the current thread, or null
     * if no node context is active.
     *
     * @return the profile name, or null
     */
    public static LogScope current() {
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
    public static void runIn(LogScope scope, Runnable task) {
        LogScope previous = CURRENT.get();
        set(scope);
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

    /**
     * Runs the given task within a (module, profile) scope.
     *
     * @param module  the module id
     * @param profile the profile name within the module
     * @param task    the task to execute
     */
    public static void runIn(String module, String profile, Runnable task) {
        runIn(LogScope.of(module, profile), task);
    }
}