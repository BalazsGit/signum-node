package application.utils.logging;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry that maps node profile names to their {@link ProfileLogger}.
 * <p>
 * Each {@link application.module.node.Signum} instance registers its
 * {@link ProfileLogger} here at startup and unregisters it at shutdown.
 * The {@link SystemLoggerJulHandler} consults this registry to route log
 * events (that carry a {@link NodeLogContext} profile) to the correct
 * per-profile logger.
 * </p>
 * <p>
 * <b>Lifecycle:</b>
 * <ol>
 *   <li>Signum.doInitialize() → register(profileName, profileLogger)</li>
 *   <li>Signum.doShutdown()  → unregister(profileName)</li>
 * </ol>
 *
 * @see NodeLogContext
 * @see ProfileLogger
 */
public final class NodeLoggerRegistry {

    private static final Map<String, ProfileLogger> REGISTRY = new ConcurrentHashMap<>();

    private NodeLoggerRegistry() {
        // Utility class
    }

    /**
     * Registers a ProfileLogger for the given profile name.
     *
     * @param profileName   the node profile name (e.g. "mainnet")
     * @param profileLogger the logger to associate (never null)
     */
    public static void register(String profileName, ProfileLogger profileLogger) {
        if (profileName == null || profileName.isEmpty()) {
            return;
        }
        REGISTRY.put(profileName, profileLogger);
    }

    /**
     * Unregisters the ProfileLogger for the given profile name.
     *
     * @param profileName the node profile name
     */
    public static void unregister(String profileName) {
        if (profileName != null) {
            REGISTRY.remove(profileName);
        }
    }

    /**
     * Returns the registered ProfileLogger for the given profile name,
     * or null if no logger is registered.
     *
     * @param profileName the node profile name
     * @return the ProfileLogger, or null
     */
    public static ProfileLogger get(String profileName) {
        return profileName != null ? REGISTRY.get(profileName) : null;
    }
}