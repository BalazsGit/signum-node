package application.module.node.instance;

import application.module.node.Signum;
import application.module.node.profile.NodeProfile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
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

    /** HTTP ports currently in use by registered nodes. */
    private final Set<Integer> httpPortsInUse = ConcurrentHashMap.newKeySet();

    /** P2P ports currently in use by registered nodes. */
    private final Set<Integer> p2pPortsInUse = ConcurrentHashMap.newKeySet();

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
            instance.httpPortsInUse.clear();
            instance.p2pPortsInUse.clear();
        }
        instance = null;
    }

    // =====================================================================
    // Port conflict exception
    // =====================================================================

    /**
     * Exception thrown when a node tries to bind to an HTTP or P2P port
     * already in use by another registered node.
     */
    public static class PortConflictException extends RuntimeException {
        public PortConflictException(String message) {
            super(message);
        }
    }

    // =====================================================================
    // Registration
    // =====================================================================

    /**
     * Registers a Signum instance under its profile name, with port conflict checking.
     * <p>
     * Validates that no other registered node is using the same HTTP or P2P port.
     * Throws {@link PortConflictException} if a conflict is detected.
     *
     * @param signum the running Signum facade (must not be null)
     * @throws IllegalArgumentException if signum is null or profile name is blank
     * @throws PortConflictException    if HTTP or P2P port is already in use
     */
    public void register(Signum signum) {
        if (signum == null) {
            throw new IllegalArgumentException("Signum must not be null");
        }
        String profileName = signum.getProfileName();
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("Signum profile name must not be blank");
        }

        // Check for port conflicts using profile properties
        // Ports are read from profile config (same pattern as NodeLifecycleManager.initializeProfileInternal)
        String httpPortStr = signum.getProfile().getProperty("httpport", "8125");
        String p2pPortStr = signum.getProfile().getProperty("peer.port", "8123");
        int httpPort;
        int p2pPort;
        try {
            httpPort = Integer.parseInt(httpPortStr);
        } catch (NumberFormatException e) {
            httpPort = 8125;
        }
        try {
            p2pPort = Integer.parseInt(p2pPortStr);
        } catch (NumberFormatException e) {
            p2pPort = 8123;
        }

        // Check HTTP port conflict
        if (!httpPortsInUse.add(httpPort)) {
            throw new PortConflictException(
                    "HTTP port " + httpPort + " already in use by another node for profile '" + profileName + "'");
        }

        // Check P2P port conflict
        if (!p2pPortsInUse.add(p2pPort)) {
            httpPortsInUse.remove(httpPort); // rollback
            throw new PortConflictException(
                    "P2P port " + p2pPort + " already in use by another node for profile '" + profileName + "'");
        }

        logger.debug("Registered ports for profile '{}': HTTP={}, P2P={}",
                profileName, httpPort, p2pPort);

        Signum previous = nodes.put(profileName, signum);
        if (previous != null) {
            // Release ports from previous instance
            releasePorts(previous);
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
            releasePorts(removed);
            logger.info("Unregistered Signum for profile '{}'", profileName);
        } else {
            logger.debug("No Signum found to unregister for profile '{}'", profileName);
        }
        return removed;
    }

    /**
     * Releases ports tracked for a Signum instance.
     */
    private void releasePorts(Signum signum) {
        String httpPortStr = signum.getProfile().getProperty("httpport", "8125");
        String p2pPortStr = signum.getProfile().getProperty("peer.port", "8123");
        int httpPort;
        int p2pPort;
        try {
            httpPort = Integer.parseInt(httpPortStr);
        } catch (NumberFormatException e) {
            httpPort = 8125;
        }
        try {
            p2pPort = Integer.parseInt(p2pPortStr);
        } catch (NumberFormatException e) {
            p2pPort = 8123;
        }
        httpPortsInUse.remove(httpPort);
        p2pPortsInUse.remove(p2pPort);
        logger.debug("Released ports for profile '{}': HTTP={}, P2P={}",
                signum.getProfileName(), httpPort, p2pPort);
    }

    // =====================================================================
    // Delete Profile API
    // =====================================================================

    /**
     * Completely removes a node profile, optionally cleaning up its database files.
     * <p>
     * Steps:
     * 1. Stop the node if it is running
     * 2. Unregister from factory (releases ports)
     * 3. Shutdown DbContext (closes DB connections)
     * 4. Delete profile config file
     * 5. If {@code deleteDatabaseFiles} is true, delete database directory/files
     * 6. Cleanup logging configuration
     *
     * @param profileName          the profile to delete
     * @param deleteDatabaseFiles  if true, also remove SQLite/database files
     * @throws IllegalStateException if the node is still running and cannot be stopped
     */
    public void deleteProfile(String profileName, boolean deleteDatabaseFiles) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("Profile name must not be blank");
        }

        logger.info("Deleting profile '{}' (deleteDatabaseFiles={})", profileName, deleteDatabaseFiles);

        // 1. Stop if running
        stopProfileIfRunning(profileName);

        // 2. Unregister from NodeFactory (releases ports)
        Signum signum = unregister(profileName);

        // 3. Shutdown DbContext if we have a reference
        if (signum != null && signum.getContext() != null) {
            try {
                signum.getContext().getDbContext().shutdown();
                logger.debug("Shutdown DbContext for profile '{}'", profileName);
            } catch (Exception e) {
                logger.warn("Failed to shutdown DbContext for profile '{}'", profileName, e);
            }
        }

        // 4. Delete profile config file
        deleteProfileConfig(profileName);

        // 5. Delete database files if requested
        if (deleteDatabaseFiles) {
            deleteDatabaseFiles(profileName);
        }

        // 6. Cleanup logging config
        cleanupLogging(profileName);

        logger.info("Profile '{}' deleted successfully", profileName);
    }

    /**
     * Stops the profile if it is currently running.
     */
    private void stopProfileIfRunning(String profileName) {
        Signum signum = nodes.get(profileName);
        if (signum != null && signum.isRunning()) {
            logger.info("Stopping running profile '{}' before deletion", profileName);
            try {
                signum.stop();
            } catch (Exception e) {
                logger.error("Failed to stop profile '{}' during deletion", profileName, e);
                throw new IllegalStateException(
                        "Cannot delete profile '" + profileName + "': failed to stop node", e);
            }
        }
    }

    /**
     * Deletes the profile configuration file from conf/node/profiles/.
     */
    private void deleteProfileConfig(String profileName) {
        try {
            Path configPath = Path.of("conf", "node", "profiles", profileName + ".properties");
            if (Files.exists(configPath)) {
                Files.delete(configPath);
                logger.info("Deleted profile config: {}", configPath);
            } else {
                logger.debug("No profile config found at {}", configPath);
            }
        } catch (IOException e) {
            logger.warn("Failed to delete profile config for '{}'", profileName, e);
        }
    }

    /**
     * Deletes the database directory/files for the profile.
     */
    private void deleteDatabaseFiles(String profileName) {
        try {
            // Try common database paths
            Path[] possibleDbPaths = {
                    Path.of("database", "SQLite", profileName),
                    Path.of("data", profileName),
                    Path.of("conf", "node", "profiles", profileName + ".db")
            };

            for (Path dbPath : possibleDbPaths) {
                deletePath(dbPath);
            }
        } catch (Exception e) {
            logger.warn("Failed to delete database files for '{}'", profileName, e);
        }
    }

    /**
     * Recursively deletes a path if it exists.
     */
    private void deletePath(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    logger.debug("Deleted file: {}", file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc == null) {
                        Files.delete(dir);
                        logger.debug("Deleted directory: {}", dir);
                        return FileVisitResult.CONTINUE;
                    } else {
                        throw exc;
                    }
                }
            });
        } else {
            Files.delete(path);
            logger.debug("Deleted file: {}", path);
        }
    }

    /**
     * Cleans up logging configuration for the profile.
     */
    private void cleanupLogging(String profileName) {
        try {
            Path logConfigPath = Path.of("conf", "node", "logging", profileName + ".properties");
            if (Files.exists(logConfigPath)) {
                Files.delete(logConfigPath);
                logger.info("Deleted logging config: {}", logConfigPath);
            }
        } catch (IOException e) {
            logger.warn("Failed to delete logging config for '{}'", profileName, e);
        }
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
    // Port registry queries
    // =====================================================================

    /**
     * Checks if an HTTP port is already in use by any registered node.
     *
     * @param port the HTTP port to check
     * @return true if the port is in use
     */
    public boolean isHttpPortInUse(int port) {
        return httpPortsInUse.contains(port);
    }

    /**
     * Checks if a P2P port is already in use by any registered node.
     *
     * @param port the P2P port to check
     * @return true if the port is in use
     */
    public boolean isP2pPortInUse(int port) {
        return p2pPortsInUse.contains(port);
    }

    /**
     * Returns the set of HTTP ports currently in use.
     */
    public Set<Integer> getHttpPortsInUse() {
        return Collections.unmodifiableSet(httpPortsInUse);
    }

    /**
     * Returns the set of P2P ports currently in use.
     */
    public Set<Integer> getP2pPortsInUse() {
        return Collections.unmodifiableSet(p2pPortsInUse);
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
        httpPortsInUse.clear();
        p2pPortsInUse.clear();
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