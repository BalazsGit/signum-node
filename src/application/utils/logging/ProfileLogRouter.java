package application.utils.logging;

import application.utils.logging.event.LogEvent;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;

/**
 * Single-application JUL Handler that routes log events to profile-specific contexts
 * using a composite {@link LogRoutingKey} (moduleId + profileName).
 *
 * <p>Replaces the legacy approach of adding handlers to root logger (which broadcasts
 * all logs to every console). Instead, uses {@link ProfileThreadContext} to identify
 * the calling module and profile, then dispatches the {@link LogEvent} only to that
 * profile's subscribers via an O(1) HashMap lookup.</p>
 *
 * <h3>Architecture Flow</h3>
 * <pre>
 *   [SLF4J Logger.info()] → slf4j-jdk14 bridge → JUL LogManager
 *                                              ↓
 *                                       Root Logger handlers
 *                                              ↓
 *                                  ProfileLogRouter.RouterJULHandler
 *                                        publish(LogRecord) {
 *                                          key = MDC.get("logModule") + ":" + MDC.get("profileName");
 *                                          context = profileMap.get(key);
 *                                          context.dispatch(LogEvent.from(record));
 *                                        }
 * </pre>
 *
 * <h3>Routing Rules</h3>
 * <ul>
 *   <li>If MDC has module+profile set → route to that specific context only (O(1) HashMap lookup)</li>
 *   <li>If MDC is empty (bootstrap/system logs) → broadcast to ALL registered contexts</li>
 * </ul>
 *
 * <h3>Multi-Module Support (V2.3)</h3>
 * <p>Uses {@link LogRoutingKey} as the HashMap key, so different modules with identical
 * profile names (e.g., "node:profil-bela" vs "database:profil-bela") are routed correctly
 * without any collision.</p>
 *
 * <p><b>Performance:</b> O(1) HashMap lookup per event vs. O(n) global broadcast in legacy system.</p>
 * <p><b>Thread-safe:</b> ConcurrentHashMap for profile map, Handler is thread-safe by design.</p>
 *
 * @see ProfileThreadContext
 * @see ProfileLogContext
 * @see LogRoutingKey
 * @see LogEvent
 */
public final class ProfileLogRouter {

    private static volatile ProfileLogRouter instance;

    /**
     * Composite-key map: LogRoutingKey (moduleId:profileName) → ProfileLogContext.
     * Using ConcurrentHashMap for thread-safe O(1) lookups from any logging thread.
     */
    private final Map<LogRoutingKey, ProfileLogContext> profileMap = new ConcurrentHashMap<>();
    private final Handler julHandler;
    private volatile boolean installed = false;

    private ProfileLogRouter() {
        this.julHandler = new RouterJULHandler();
    }

    /**
     * Returns the singleton instance (double-checked locking).
     */
    public static ProfileLogRouter getInstance() {
        if (instance == null) {
            synchronized (ProfileLogRouter.class) {
                if (instance == null) {
                    instance = new ProfileLogRouter();
                }
            }
        }
        return instance;
    }

    /**
     * Installs the global JUL handler. Idempotent: safe to call multiple times.
     * <p>
     * Adds the RouterJULHandler to the root JUL logger so all SLF4J→JUL bridged
     * log events flow through this router.
     * </p>
     */
    public void install() {
        if (installed) {
            return;
        }
        LogManager.getLogManager().getLogger("").addHandler(julHandler);
        julHandler.setLevel(Level.ALL);
        installed = true;
    }

    /**
     * Uninstalls the global JUL handler. Idempotent: safe to call multiple times.
     */
    public void uninstall() {
        if (!installed) {
            return;
        }
        LogManager.getLogManager().getLogger("").removeHandler(julHandler);
        installed = false;
    }

    /** @return true if the JUL handler is currently installed on the root logger */
    public boolean isInstalled() {
        return installed;
    }

    /**
     * Registers a profile context for routing. Called internally by {@link ProfileLogContext#start()}.
     *
     * @param context the context to register (never null)
     * @throws NullPointerException if context is null
     * @throws IllegalArgumentException if the context has a null routing key
     */
    void registerContext(ProfileLogContext context) {
        if (context == null) {
            throw new NullPointerException("ProfileLogContext must not be null");
        }
        LogRoutingKey key = context.getRoutingKey();
        if (key == null) {
            throw new IllegalArgumentException("Context must have a valid routing key: " + context);
        }
        profileMap.put(key, context);
    }

    /**
     * Unregisters a profile context by its composite routing key.
     * Called internally by {@link ProfileLogContext#close()}.
     *
     * @param key the composite routing key to unregister
     */
    void unregisterContext(LogRoutingKey key) {
        profileMap.remove(key);
    }

    /**
     * Looks up the active context for a composite routing key.
     *
     * @param key the routing key (moduleId:profileName)
     * @return the context, or null if not registered
     */
    public ProfileLogContext getContext(LogRoutingKey key) {
        return profileMap.get(key);
    }

    /**
     * Returns an unmodifiable view of all registered profile contexts.
     */
    public Map<LogRoutingKey, ProfileLogContext> getAllContexts() {
        return Collections.unmodifiableMap(profileMap);
    }

    /**
     * Returns the number of currently registered profile contexts.
     */
    public int getRegisteredContextCount() {
        return profileMap.size();
    }

    /**
     * Resets the singleton instance. Intended for testing only.
     */
    static void resetInstance() {
        synchronized (ProfileLogRouter.class) {
            if (instance != null) {
                instance.uninstall();
                instance.profileMap.clear();
                instance = null;
            }
        }
    }

    // -------------------------- Legacy Deprecated Methods --------------------------

    /**
     * Unregisters all contexts that match the given profile name (legacy String-based API).
     * <p><b>Warning:</b> This may remove contexts from multiple modules if they share
     * the same profile name. Use {@link #unregisterContext(LogRoutingKey)} for precision.</p>
     *
     * @param profileName the profile name to unregister
     * @deprecated Use {@link #unregisterContext(LogRoutingKey)} for proper module isolation
     */
    @Deprecated
    void unregisterContext(String profileName) {
        profileMap.entrySet().removeIf(entry ->
                profileName.equals(entry.getKey().getProfileName()));
    }

    /**
     * Looks up context by profile name only (legacy String-based API).
     * <p><b>Warning:</b> If multiple modules register the same profile name,
     * the result is non-deterministic. Use {@link #getContext(LogRoutingKey)} for precision.</p>
     *
     * @param profileName the profile name
     * @return the first matching context, or null if not found
     * @deprecated Use {@link #getContext(LogRoutingKey)} for proper module isolation
     */
    @Deprecated
    public ProfileLogContext getContext(String profileName) {
        for (Map.Entry<LogRoutingKey, ProfileLogContext> entry : profileMap.entrySet()) {
            if (profileName.equals(entry.getKey().getProfileName())) {
                return entry.getValue();
            }
        }
        return null;
    }

    // -------------------------- Internal JUL Handler --------------------------

    /**
     * Internal JUL handler that intercepts all log records and routes them
     * to the appropriate profile-specific context based on MDC module+profile.
     */
    private final class RouterJULHandler extends Handler {

        @Override
        public void publish(LogRecord record) {
            LogRoutingKey key = ProfileThreadContext.getRoutingKey();

            if (key == null || key.isEmpty()) {
                // Unassigned log (bootstrap, system-level) → broadcast to ALL contexts
                for (ProfileLogContext ctx : profileMap.values()) {
                    ctx.dispatch(LogEvent.from(record));
                }
                return;
            }

            // Targeted routing using composite key (moduleId:profileName)
            ProfileLogContext context = profileMap.get(key);
            if (context != null) {
                context.dispatch(LogEvent.from(record));
            }
            // If context is null, the module+profile combination is not yet started
            // or already stopped → drop silently
        }

        @Override
        public void flush() {
            // No buffering in the router itself - subscribers handle their own flushing
        }

        @Override
        public void close() {
            // Lifecycle managed by install/uninstall - no resources to close here
        }
    }
}