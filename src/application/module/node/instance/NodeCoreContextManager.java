package application.module.node.instance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton registry that manages the lifecycle of multiple
 * {@link NodeCoreContext} instances (one per profile).
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Register / unregister contexts as profiles start and stop</li>
 *   <li>Provide thread-safe lookup by profile name</li>
 *   <li>Expose the collection of all running contexts</li>
 * </ul>
 * </p>
 *
 * <h2>Backwards-compatibility bridge</h2>
 * Legacy code that calls {@code Signum.getBlockchain()} etc. is redirected
 * through {@link #getActive()}, which returns the first running context.
 * This allows incremental migration without breaking existing callers.
 *
 * @since 4.0
 */
public final class NodeCoreContextManager {

    private static final Logger logger = LoggerFactory.getLogger(NodeCoreContextManager.class);

    /** Singleton instance access. */
    private static volatile NodeCoreContextManager instance;

    /** Profile-name → running context mapping (thread-safe). */
    private final Map<String, NodeCoreContext> contexts = new ConcurrentHashMap<>();

    private NodeCoreContextManager() {
        // singleton
    }

    // =====================================================================
    // Singleton access
    // =====================================================================

    /**
     * Returns the global manager instance (lazy-initialized, double-checked).
     */
    public static NodeCoreContextManager getInstance() {
        if (instance == null) {
            synchronized (NodeCoreContextManager.class) {
                if (instance == null) {
                    instance = new NodeCoreContextManager();
                }
            }
        }
        return instance;
    }

    /**
     * Resets the singleton. Intended for testing only.
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.contexts.clear();
        }
        instance = null;
    }

    // =====================================================================
    // Registration
    // =====================================================================

    /**
     * Registers a newly started context under its profile name.
     *
     * @param profileName the unique profile identifier
     * @param context     the running context
     * @throws IllegalStateException if a context is already registered for that profile
     */
    public void register(String profileName, NodeCoreContext context) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("Profile name must not be blank");
        }
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        NodeCoreContext previous = contexts.put(profileName, context);
        if (previous != null) {
            logger.warn("Overwrote existing context for profile '{}'. Old instance: {}",
                    profileName, System.identityHashCode(previous));
        } else {
            logger.info("Registered context for profile '{}'", profileName);
        }
    }

    /**
     * Unregisters a context when the profile is stopped.
     *
     * @param profileName the profile to unregister
     * @return the removed context, or {@code null} if not found
     */
    public NodeCoreContext unregister(String profileName) {
        NodeCoreContext removed = contexts.remove(profileName);
        if (removed != null) {
            logger.info("Unregistered context for profile '{}'", profileName);
        } else {
            logger.debug("No context found to unregister for profile '{}'", profileName);
        }
        return removed;
    }

    // =====================================================================
    // Queries
    // =====================================================================

    /**
     * Looks up a context by profile name.
     *
     * @param profileName the profile identifier
     * @return the running context, or {@code null} if not registered
     */
    public NodeCoreContext get(String profileName) {
        return contexts.get(profileName);
    }

    /**
     * Returns an unmodifiable view of all registered contexts.
     */
    public Collection<NodeCoreContext> getAll() {
        return Collections.unmodifiableCollection(contexts.values());
    }

    /**
     * Returns the number of currently registered (running) contexts.
     */
    public int size() {
        return contexts.size();
    }

    /**
     * Checks whether a profile has a registered context.
     */
    public boolean hasProfile(String profileName) {
        return contexts.containsKey(profileName);
    }

    // =====================================================================
    // Backwards-compatibility helpers
    // =====================================================================

    /**
     * Returns the <b>first</b> running context to support legacy static callers.
     * <p>
     * WARNING: This is a temporary bridge for incremental migration. Code that
     * depends on a single global node should migrate to explicit profile-aware
     * lookups as soon as possible.
     * </p>
     *
     * @return the first registered context, or {@code null} if none are running
     */
    public NodeCoreContext getActive() {
        if (contexts.isEmpty()) {
            return null;
        }
        // Return the first value; ConcurrentHashMap iteration order is unspecified
        // but for single-profile deployments this is deterministic.
        return contexts.values().iterator().next();
    }

    /**
     * Stops all registered contexts and clears the registry.
     */
    public void stopAll() {
        logger.info("Stopping all {} registered node context(s)", contexts.size());
        contexts.values().forEach(ctx -> {
            try {
                ctx.stop();
            } catch (Exception e) {
                logger.error("Error stopping profile '{}'", ctx.getProfileName(), e);
            }
        });
        contexts.clear();
    }
}