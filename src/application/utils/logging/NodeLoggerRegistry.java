package application.utils.logging;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

    private static final Map<LogScope, ProfileLogger> REGISTRY = new ConcurrentHashMap<>();

    private NodeLoggerRegistry() {
        // Utility class
    }

    /**
     * Registers a ProfileLogger for the given profile name.
     *
     * @param profileName   the node profile name (e.g. "mainnet")
     * @param profileLogger the logger to associate (never null)
     */
    public static void register(LogScope scope, ProfileLogger profileLogger) {
        if (scope == null || profileLogger == null) {
            return;
        }
        REGISTRY.put(scope, profileLogger);
    }

    public static void register(String module, String profile, ProfileLogger profileLogger) {
        register(LogScope.of(module, profile), profileLogger);
    }

    /**
     * Unregisters the ProfileLogger for the given profile name.
     *
     * @param profileName the node profile name
     */
    public static void unregister(LogScope scope) {
        if (scope != null) {
            REGISTRY.remove(scope);
        }
    }

    public static void unregister(String module, String profile) {
        unregister(LogScope.of(module, profile));
    }

    /**
     * Returns the registered ProfileLogger for the given profile name,
     * or null if no logger is registered.
     *
     * @param profileName the node profile name
     * @return the ProfileLogger, or null
     */
    public static ProfileLogger get(LogScope scope) {
        return scope != null ? REGISTRY.get(scope) : null;
    }

    public static ProfileLogger get(String module, String profile) {
        return get(LogScope.of(module, profile));
    }

    /**
     * Returns all registered {@link ProfileLogger}s of the given module (e.g. "node"),
     * in no particular order.
     * <p>
     * Exposed as a public utility for introspection/tooling. The
     * {@link SystemLoggerJulHandler} does <b>not</b> use this for routing: context-less
     * log events are deliberately never broadcast to every profile of a module (that
     * fan-out is what leaked one profile's lines into another profile's console).
     * Profile-aware emitters set their {@link NodeLogContext} and are routed to a single
     * profile via {@link #get(LogScope)}.
     * </p>
     *
     * @param module the module id (e.g. "node"), never null
     * @return the registered loggers of that module (possibly empty, never null)
     */
    public static Collection<ProfileLogger> loggersForModule(String module) {
        if (module == null) {
            return java.util.Collections.emptyList();
        }
        List<ProfileLogger> result = new ArrayList<>();
        for (Map.Entry<LogScope, ProfileLogger> entry : REGISTRY.entrySet()) {
            if (module.equals(entry.getKey().module())) {
                result.add(entry.getValue());
            }
        }
        return result;
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
        LogScope scope = LogScope.of(moduleId, profileName);
        ProfileLogger existing = REGISTRY.get(scope);
        if (existing != null) {
            return existing;
        }
        ProfileLogger created = new ProfileLogger(moduleId, profileName);
        created.setForwardToSystem(false);
        ProfileLogger previous = REGISTRY.putIfAbsent(scope, created);
        return previous != null ? previous : created;
    }
}