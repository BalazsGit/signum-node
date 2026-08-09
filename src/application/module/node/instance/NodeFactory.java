package application.module.node.instance;

import application.module.node.Signum;
import application.module.node.profile.NodeProfile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton registry that manages the lifecycle of multiple
 * {@link Signum} instances (one per profile).
 * <p>
 * Replaces {@link NodeCoreContextManager}: instead of storing raw
 * {@code NodeCoreContext}, the factory stores complete {@code Signum}
 * facade instances so every registered node exposes the full public API
 * (lifecycle, services, identity) rather than an internal implementation
 * detail.
 * </p>
 *
 * @since 4.0
 */
public final class NodeFactory {

    private static final Logger logger = LoggerFactory.getLogger(NodeFactory.class);

    /** Singleton instance access. */
    private static volatile NodeFactory instance;

    /** Profile-name -> running Signum mapping (thread-safe). */
    private final Map<String, Signum> nodes = new ConcurrentHashMap<>();

    private NodeFactory() {
        // singleton
    }

    // =====================================================================
    // Singleton access
    // =====================================================================

    /**
     * Returns the global factory instance (lazy-initialized, double-checked).
     */
    public static NodeFactory getInstance() {
        if (instance == null) {
            synchronized (NodeFactory.class) {
                if (instance == null) {
                    instance = new NodeFactory();
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
            instance.nodes.clear();
        }
        instance = null;
    }

    // =====================================================================
    // Registration
    // =====================================================================

    /**
     * Registers a Signum instance under its profile name.
     *
     * @param signum the running Signum facade (must not be null)
     */
    public void register(Signum signum) {
        if (signum == null) {
            throw new IllegalArgumentException("Signum must not be null");
        }
        String profileName = signum.getProfileName();
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("Signum profile name must not be blank");
        }
        Signum previous = nodes.put(profileName, signum);
        if (previous != null) {
            logger.warn("Overwrote existing Signum for profile '{}'. Old instance: {}",
                    profileName, System.identityHashCode(previous));
        } else {
            logger.info("Registered Signum for profile '{}'", profileName);
        }
    }

    /**
     * Unregisters a Signum instance when the profile is stopped.
     *
     * @param profileName the profile to unregister
     * @return the removed Signum, or {@code null} if not found
     */
    public Signum unregister(String profileName) {
        Signum removed = nodes.remove(profileName);
        if (removed != null) {
            logger.info("Unregistered Signum for profile '{}'", profileName);
        } else {
            logger.debug("No Signum found to unregister for profile '{}'", profileName);
        }
        return removed;
    }

    // =====================================================================
    // Queries
    // =====================================================================

    /**
     * Looks up a Signum instance by profile name.
     *
     * @param profileName the profile identifier
     * @return the running Signum, or {@code null} if not registered
     */
    public Signum get(String profileName) {
        return nodes.get(profileName);
    }

    /**
     * Returns an unmodifiable view of all registered Signum instances.
     */
    public Collection<Signum> getAll() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    /**
     * Returns the number of currently registered (running) nodes.
     */
    public int size() {
        return nodes.size();
    }

    /**
     * Checks whether a profile has a registered Signum instance.
     */
    public boolean hasProfile(String profileName) {
        return nodes.containsKey(profileName);
    }

    // =====================================================================
    // Backwards-compatibility helpers
    // =====================================================================

    /**
     * Returns the first running Signum to support legacy static callers.
     *
     * @return the first registered Signum, or {@code null} if none are running
     */
    public Signum getActive() {
        if (nodes.isEmpty()) {
            return null;
        }
        return nodes.values().iterator().next();
    }

    /**
     * Stops all registered Signum instances and clears the registry.
     */
    public void stopAll() {
        logger.info("Stopping all {} registered node(s)", nodes.size());
        nodes.values().forEach(signum -> {
            try {
                signum.stop();
            } catch (Exception e) {
                logger.error("Error stopping profile '{}'", signum.getProfileName(), e);
            }
        });
        nodes.clear();
    }

    // =====================================================================
    // Legacy delegation bridge (deprecated)
    // =====================================================================

    /**
     * @deprecated Use {@link #register(Signum)} directly.
     */
    @Deprecated
    public void registerContext(String profileName, NodeCoreContext context) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("Profile name must not be blank");
        }
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        Signum existing = nodes.get(profileName);
        if (existing != null && existing.getContext() == context) {
            return;
        }
        Signum signum = new Signum(new NodeProfile(profileName), context);
        register(signum);
    }

    /**
     * @deprecated Use {@link #get(String)} and then call {@code .getContext()}.
     */
    @Deprecated
    public NodeCoreContext getContext(String profileName) {
        Signum signum = get(profileName);
        return signum != null ? signum.getContext() : null;
    }

    /**
     * @deprecated Use {@link #getActive()} and then call {@code .getContext()}.
     */
    @Deprecated
    public NodeCoreContext getActiveContext() {
        Signum active = getActive();
        return active != null ? active.getContext() : null;
    }
}