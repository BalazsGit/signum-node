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
 * Single-application JUL Handler that routes log events to profile-specific contexts.
 * <p>
 * Replaces the legacy approach of adding handlers to root logger (which broadcasts
 * all logs to every console). Instead, uses {@link ProfileThreadContext} to identify
 * the calling profile and dispatches the {@link LogEvent} only to that profile's subscribers.
 * </p>
 * <p>
 * <h3>Architecture Flow</h3>
 * <pre>
 *   [SLF4J Logger.info()] → slf4j-jdk14 bridge → JUL LogManager
 *                                              ↓
 *                                       Root Logger handlers
 *                                              ↓
 *                                  ProfileLogRouter.RouterJULHandler
 *                                        publish(LogRecord) {
 *                                          profile = MDC.get("profileName");
 *                                          context = profileMap.get(profile);
 *                                          context.dispatch(LogEvent.from(record));
 *                                        }
 * </pre>
 * </p>
 * <p>
 * <h3>Routing Rules</h3>
 * <ul>
 *   <li>If MDC profile name is set → route to that profile's context only (O(1) HashMap lookup)</li>
 *   <li>If MDC profile name is null (bootstrap/system logs) → broadcast to ALL registered contexts</li>
 * </ul>
 * </p>
 * <p>
 * Performance: O(1) HashMap lookup per event vs. O(n) global broadcast in legacy system.
 * Thread-safe: ConcurrentHashMap for profile map, Handler is thread-safe by design.
 * </p>
 *
 * @see ProfileThreadContext
 * @see ProfileLogContext
 * @see LogEvent
 */
public final class ProfileLogRouter {

    private static volatile ProfileLogRouter instance;

    private final Map<String, ProfileLogContext> profileMap = new ConcurrentHashMap<>();
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
     * Registers a profile context for routing. Called internally by ProfileLogContext.start().
     *
     * @param context the context to register
     */
    void registerContext(ProfileLogContext context) {
        if (context == null) {
            throw new NullPointerException("ProfileLogContext must not be null");
        }
        profileMap.put(context.getProfileName(), context);
    }

    /**
     * Unregisters a profile context. Called internally by ProfileLogContext.close().
     *
     * @param profileName the profile name to unregister
     */
    void unregisterContext(String profileName) {
        profileMap.remove(profileName);
    }

    /**
     * Looks up the active context for a profile name.
     *
     * @param profileName the profile name
     * @return the context, or null if not registered
     */
    public ProfileLogContext getContext(String profileName) {
        return profileMap.get(profileName);
    }

    /**
     * Returns an unmodifiable view of all registered profile contexts.
     */
    public Map<String, ProfileLogContext> getAllContexts() {
        return Collections.unmodifiableMap(profileMap);
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

    /**
     * Internal JUL handler that intercepts all log records and routes them
     * to the appropriate profile-specific context based on MDC profile name.
     */
    private final class RouterJULHandler extends Handler {

        @Override
        public void publish(LogRecord record) {
            String profileName = ProfileThreadContext.getProfile();

            if (profileName == null) {
                // Unassigned log (bootstrap, system-level) → broadcast to ALL contexts
                for (ProfileLogContext ctx : profileMap.values()) {
                    ctx.dispatch(LogEvent.from(record));
                }
                return;
            }

            // Targeted routing: dispatch only to the matching profile context
            ProfileLogContext context = profileMap.get(profileName);
            if (context != null) {
                context.dispatch(LogEvent.from(record));
            }
            // If context is null, the profile is not yet started or already stopped → drop silently
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