package application.utils.logging;

import org.slf4j.MDC;

/**
 * Wrapper around SLF4J MDC for module-aware, profile-based log routing.
 *
 * <p>After unifying the codebase to SLF4J-only logging, this class provides a clean API
 * for setting/clearing the current module ID and profile name in the MDC context.
 * The {@link ProfileLogRouter} reads these values from MDC (same thread) during
 * JUL Handler {@code publish()} to route log events using a composite {@link LogRoutingKey}.</p>
 *
 * <h3>Architecture Flow</h3>
 * <pre>
 *   [SLF4J Loggers] → slf4j-jdk14 bridge → [JUL LogManager]
 *                                          ↓
 *                                   ProfileLogRouter.RouterJULHandler
 *                                         publish(LogRecord record) {
 *                                           key = ProfileThreadContext.getRoutingKey();
 *                                           // route to correct ProfileLogContext...
 *                                         }
 * </pre>
 *
 * <h3>Multi-Module Support (V2.3)</h3>
 * <p>V2.3 introduces a two-dimensional routing key ({@code moduleId} + {@code profileName})
 * to prevent collisions when different modules use identical profile names.</p>
 * <pre>
 *   Module "node"    + Profile "profil-bela" → LogRoutingKey("node":"profil-bela")
 *   Module "database" + Profile "profil-bela" → LogRoutingKey("database":"profil-bela")
 *   (Különböző kulcs → különböző console panel)
 * </pre>
 *
 * <p><b>Thread-safe:</b> Uses SLF4J MDC (backed by InheritableThreadLocal internally in logback-classic).</p>
 * <p><b>Memory-safe:</b> Must call {@link #clear()} in finally blocks to prevent leaks.</p>
 *
 * @see ProfileLogRouter
 * @see ProfileLogContext
 * @see LogRoutingKey
 * @see org.slf4j.MDC
 *
 * @deprecated This class is part of the legacy MDC-based logging architecture.
 * New code should use explicit {@link application.utils.logging.ProfileLogger} instances
 * held by each profile (NodeProfile, DatabaseProfile, etc.) rather than relying on
 * thread-local MDC context for log routing.
 * <p>This class is retained for backward compatibility during the migration period
 * (LOGGER_ARCHITECTURE_PLAN Phase 4) and will be removed in a future release.</p>
 * @see application.utils.logging.ProfileLogger
 * @see application.utils.logging.SystemLogger
 */
@Deprecated
public final class ProfileThreadContext {

    private ProfileThreadContext() {
        // Utility class - no instantiation
    }

    /** MDC key for the module identifier (e.g., "node", "database", "mining") */
    public static final String KEY_MODULE = "logModule";

    /** MDC key for the profile name within a module */
    public static final String KEY_PROFILE = "profileName";

    // -------------------------- Primary API (V2.3) --------------------------

    /**
     * Sets both module ID and profile name in the MDC context.
     * This is the primary API for V2.3+ logging with full module isolation.
     *
     * @param moduleId    the module identifier (e.g., "node", "database", "mining"), or null to clear
     * @param profileName the profile name within that module, or null to clear
     */
    public static void setContext(String moduleId, String profileName) {
        if (moduleId != null && !moduleId.isEmpty()) {
            MDC.put(KEY_MODULE, moduleId);
        } else {
            MDC.remove(KEY_MODULE);
        }
        if (profileName != null && !profileName.isEmpty()) {
            MDC.put(KEY_PROFILE, profileName);
        } else {
            MDC.remove(KEY_PROFILE);
        }
    }

    /**
     * Returns the current module ID from the MDC context.
     *
     * @return the module ID, or null if not set
     */
    public static String getModuleId() {
        return MDC.get(KEY_MODULE);
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
     * Returns the composite {@link LogRoutingKey} built from the current MDC context.
     * This is the key method used by {@link ProfileLogRouter} for O(1) routing lookups.
     *
     * @return the routing key, or null if no module/profile context is set (bootstrap/broadcast mode)
     */
    public static LogRoutingKey getRoutingKey() {
        String moduleId = getModuleId();
        String profileName = getProfile();
        return LogRoutingKey.of(moduleId, profileName);
    }

    /**
     * Clears all routing context from the MDC.
     * Should be called in finally blocks to prevent memory leaks in thread pools.
     */
    public static void clear() {
        MDC.remove(KEY_MODULE);
        MDC.remove(KEY_PROFILE);
    }

    // -------------------------- Wrap Patterns --------------------------

    /**
     * Wraps a Runnable to set full context (module + profile) for its execution.
     * <p>
     * Use this pattern when executing tasks with thread pools to ensure
     * the routing context is properly set and restored:
     * </p>
     * <pre>{@code
     *   executor.execute(ProfileThreadContext.wrap(() -> {
     *       // This code runs with the specified module+profile context
     *       logger.info("Node operation");
     *   }, "node", "mainnet-prune"));
     * }</pre>
     *
     * @param task         the runnable to wrap
     * @param moduleId     the module ID to set during execution
     * @param profileName  the profile name to set during execution
     * @return a new Runnable that manages routing context automatically
     */
    public static Runnable wrap(Runnable task, String moduleId, String profileName) {
        return () -> {
            String prevModule = getModuleId();
            String prevProfile = getProfile();
            setContext(moduleId, profileName);
            try {
                task.run();
            } finally {
                // Restore previous context (or clear if none existed)
                setContext(
                        prevModule != null ? prevModule : null,
                        prevProfile != null ? prevProfile : null
                );
            }
        };
    }

    /**
     * Wraps a Callable to set full context (module + profile) for its execution.
     *
     * @param task         the callable to wrap
     * @param moduleId     the module ID to set during execution
     * @param profileName  the profile name to set during execution
     * @param <T>          the return type of the callable
     * @return a new Callable that manages routing context automatically
     * @throws Exception if the underlying callable throws an exception
     */
    public static <T> java.util.concurrent.Callable<T> wrap(
            java.util.concurrent.Callable<T> task, String moduleId, String profileName) {
        return () -> {
            String prevModule = getModuleId();
            String prevProfile = getProfile();
            setContext(moduleId, profileName);
            try {
                return task.call();
            } finally {
                // Restore previous context (or clear if none existed)
                setContext(
                        prevModule != null ? prevModule : null,
                        prevProfile != null ? prevProfile : null
                );
            }
        };
    }

    // -------------------------- Legacy API (Deprecated) --------------------------

    /**
     * Legacy convenience method – sets only profile name (module inferred as null).
     * <p><b>Warning:</b> Without a module ID, profiles with the same name in different
     * modules will collide. Use {@link #setContext(String, String)} for proper isolation.</p>
     *
     * @param profileName the profile name to set, or null to clear
     * @deprecated Use {@link #setContext(String, String)} for proper module isolation
     */
    @Deprecated
    public static void setProfile(String profileName) {
        setContext(null, profileName);
    }

    /**
     * Legacy wrap – profile-only (no module isolation).
     * <p><b>Warning:</b> Without a module ID, profiles with the same name in different
     * modules will collide.</p>
     *
     * @param task         the runnable to wrap
     * @param profileName  the profile name to set during execution
     * @return a new Runnable that manages profile context automatically
     * @deprecated Use {@link #wrap(Runnable, String, String)} for proper module isolation
     */
    @Deprecated
    public static Runnable wrap(Runnable task, String profileName) {
        return wrap(task, null, profileName);
    }

    /**
     * Legacy wrap – profile-only Callable (no module isolation).
     *
     * @param task         the callable to wrap
     * @param profileName  the profile name to set during execution
     * @param <T>          the return type of the callable
     * @return a new Callable that manages profile context automatically
     * @throws Exception if the underlying callable throws an exception
     * @deprecated Use {@link #wrap(java.util.concurrent.Callable, String, String)} for proper module isolation
     */
    @Deprecated
    public static <T> java.util.concurrent.Callable<T> wrap(
            java.util.concurrent.Callable<T> task, String profileName) {
        return wrap(task, null, profileName);
    }
}