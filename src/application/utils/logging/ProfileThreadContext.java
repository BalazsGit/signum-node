package application.utils.logging;

import org.slf4j.MDC;

/**
 * Wrapper around SLF4J MDC for profile-based log routing.
 * <p>
 * After unifying the codebase to SLF4J-only logging, this class provides a clean API
 * for setting/clearing the current profile name in the MDC context. The {@link ProfileLogRouter}
 * reads this value from MDC (same thread) during JUL Handler publish() to route log events.
 * </p>
 * <p>
 * Architecture:
 * <pre>
 *   [SLF4J Loggers] → slf4j-jdk14 bridge → [JUL LogManager]
 *                                          ↓
 *                                   ProfileLogRouter.RouterJULHandler
 *                                         publish(LogRecord record) {
 *                                           profileName = MDC.get(KEY_PROFILE);
 *                                           // route to correct ProfileLogContext...
 *                                         }
 * </pre>
 * </p>
 * <p>
 * Thread-safe: Uses SLF4J MDC (backed by InheritableThreadLocal internally in logback-classic).
 * Memory-safe: Must call {@link #clear()} in finally blocks to prevent leaks.
 * </p>
 *
 * @see ProfileLogRouter
 * @see org.slf4j.MDC
 */
public final class ProfileThreadContext {

    private ProfileThreadContext() {
        // Utility class - no instantiation
    }

    /** MDC key used to store the current profile name for log routing */
    public static final String KEY_PROFILE = "profileName";

    /**
     * Sets the current profile name in the MDC context.
     *
     * @param profileName the profile name to set, or null to clear
     */
    public static void setProfile(String profileName) {
        if (profileName != null) {
            MDC.put(KEY_PROFILE, profileName);
        } else {
            MDC.remove(KEY_PROFILE);
        }
    }

    /**
     * Returns the current profile name from the MDC context.
     *
     * @return the profile name, or null if not set
     */
    public static String getProfile() {
        return MDC.get(KEY_PROFILE);
    }

    /**
     * Clears the profile name from the MDC context.
     * Should be called in finally blocks to prevent memory leaks.
     */
    public static void clear() {
        MDC.remove(KEY_PROFILE);
    }

    /**
     * Wraps a Runnable to set profile context for its execution.
     * <p>
     * Use this pattern when executing tasks with thread pools to ensure
     * the profile context is properly set and restored:
     * </p>
     * <pre>{@code
     *   executor.execute(ProfileThreadContext.wrap(() -> {
     *       // This code runs with the specified profile context
     *       logger.info("Node operation");
     *   }, "mainnet-prune"));
     * }</pre>
     *
     * @param task         the runnable to wrap
     * @param profileName  the profile name to set during execution
     * @return a new Runnable that manages profile context automatically
     */
    public static Runnable wrap(Runnable task, String profileName) {
        return () -> {
            String previous = getProfile();
            setProfile(profileName);
            try {
                task.run();
            } finally {
                if (previous != null) {
                    setProfile(previous); // Restore previous context
                } else {
                    clear();
                }
            }
        };
    }

    /**
     * Wraps a Callable to set profile context for its execution.
     *
     * @param task         the callable to wrap
     * @param profileName  the profile name to set during execution
     * @param <T>          the return type of the callable
     * @return a new Callable that manages profile context automatically
     * @throws Exception if the underlying callable throws an exception
     */
    public static <T> java.util.concurrent.Callable<T> wrap(
            java.util.concurrent.Callable<T> task, String profileName) {
        return () -> {
            String previous = getProfile();
            setProfile(profileName);
            try {
                return task.call();
            } finally {
                if (previous != null) {
                    setProfile(previous); // Restore previous context
                } else {
                    clear();
                }
            }
        };
    }
}