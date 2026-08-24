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

    /**
     * Returns the registered ProfileLogger for the given profile, or creates and
     * registers a new one if none exists yet.
     * <p>
     * This lets early components — e.g. the per-profile GUI panel, which is built on
     * the Swing EDT <b>before</b> the node is started — route their logs to the profile
     * console. The {@code Signum} adopts this same instance at startup
     * (see {@code Signum(NodeProfile, Path)}), so no log line emitted before the
     * Signum exists is lost.
     * </p>
     * <p>
     * The created logger has forwarding to {@link SystemLogger} disabled: the
     * {@link SystemLoggerJulHandler} already dispatches every event to the System
     * Console, so a second forward from the ProfileLogger would duplicate lines.
     * </p>
     *
     * @param moduleId    the module identifier (e.g. "node"), never null
     * @param profileName the profile name, never null or empty
     * @return the (possibly newly created) ProfileLogger for the profile
     * @throws IllegalArgumentException if profileName is null or empty
     */
    public static ProfileLogger getOrCreate(String moduleId, String profileName) {
        if (profileName == null || profileName.isEmpty()) {
            throw new IllegalArgumentException("profileName must not be null or empty");
        }
        ProfileLogger existing = REGISTRY.get(profileName);
        if (existing != null) {
            return existing;
        }
        ProfileLogger created = new ProfileLogger(moduleId, profileName);
        created.setForwardToSystem(false);
        ProfileLogger previous = REGISTRY.putIfAbsent(profileName, created);
        return previous != null ? previous : created;
    }
}