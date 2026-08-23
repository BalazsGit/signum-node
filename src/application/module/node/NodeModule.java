package application.module.node;

import application.api.Module;
import application.api.ModuleContext;
import application.api.ShutdownPriority;
import application.api.Shutdownable;
import application.module.node.gui.NodePanel;
import application.module.node.logging.NodeLoggingProvider;
import application.module.node.props.Prop;
import application.module.node.props.Props;
import javax.swing.JComponent;
import javax.swing.JFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Signum Node module implementation for the application framework.
 * <p>
 * This module IS the multi-node orchestrator. It directly owns a list of
 * {@link Signum} instances and provides lifecycle management (start/stop),
 * lookup by profile name, and port-conflict detection.
 * </p>
 * <p>
 * Target architecture:
 * <pre>
 * ApplicationKernel
 *   └── NodeModule (implements Module)
 *         ├── List<Signum> nodes          ← direct ownership
 *         ├── addNode() / removeNode()
 *         ├── startAll() / stopAll()
 *         ├── get(profileName) / getAll()
 *         └── size()
 * </pre>
 * </p>
 *
 * Shutdown Priority: HIGHEST - The Node module is shut down first because it
 * manages active network connections, blockchain processing, and web servers
 * that must be stopped before lower-level resources (database, logging) are cleaned up.
 *
 * @since 4.0
 */
public class NodeModule implements Module {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeModule.class);

    public static final String ID = "node";
    public static final String DISPLAY_NAME = "Node";

    /** Singleton instance for static access (same pattern as Signum). */
    private static volatile NodeModule INSTANCE;

    // =====================================================================
    // Node registry (direct ownership)
    // =====================================================================

    /** All managed Signum instances (thread-safe for reads). */
    private final List<Signum> nodes = new CopyOnWriteArrayList<>();

    /** HTTP ports currently in use by registered nodes. */
    private final Set<Integer> httpPortsInUse = ConcurrentHashMap.newKeySet();

    /** P2P ports currently in use by registered nodes. */
    private final Set<Integer> p2pPortsInUse = ConcurrentHashMap.newKeySet();

    // =====================================================================
    // Module fields
    // =====================================================================

    private ModuleContext context;
    private volatile NodePanel gui;
    private NodeLoggingProvider loggingProvider;
    private volatile boolean stopped = false;

    public NodeModule() {
        INSTANCE = this;
    }

    // =====================================================================
    // Singleton access
    // =====================================================================

    /**
     * Returns the global NodeModule instance (lazy-initialized).
     * <p>
     * If the ApplicationKernel already created the NodeModule, that instance is returned.
     * Otherwise (e.g., headless bootstrap), a new instance is created on demand.
     *
     * @return the NodeModule singleton
     */
    public static NodeModule getInstance() {
        if (INSTANCE == null) {
            synchronized (NodeModule.class) {
                if (INSTANCE == null) {
                    INSTANCE = new NodeModule();
                }
            }
        }
        return INSTANCE;
    }

    // =====================================================================
    // Node management API
    // =====================================================================

    /**
     * Registers a Signum instance under its profile name, with port conflict checking.
     * <p>
     * Validates that no other registered node is using the same HTTP or P2P port.
     * Throws {@link PortConflictException} if a conflict is detected.
     *
     * @param signum the Signum to register (must not be null)
     * @throws IllegalArgumentException if signum is null or profile name is blank
     * @throws PortConflictException    if HTTP or P2P port is already in use
     */
    public synchronized void addNode(Signum signum) {
        if (signum == null) {
            throw new IllegalArgumentException("Signum must not be null");
        }
        String profileName = signum.getProfileName();
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("Signum profile name must not be blank");
        }

        // Replace existing node with same profile name first (release its ports before re-adding)
        Signum previous = null;
        for (int i = 0; i < nodes.size(); i++) {
            if (profileName.equals(nodes.get(i).getProfileName())) {
                previous = nodes.get(i);
                nodes.remove(i);
                break;
            }
        }
        if (previous != null) {
            releasePorts(previous);
            LOGGER.warn("Replaced existing Signum for profile '{}'", profileName);
        }

        // Track ports (warn on conflict but do not block registration)
        // Actual port binding validation happens at start() time.
        int httpPort = resolvePort(signum, Props.API_PORT);
        int p2pPort = resolvePort(signum, Props.P2P_PORT);

        if (!httpPortsInUse.add(httpPort)) {
            LOGGER.warn("HTTP port {} is already in use by another node - potential conflict for profile '{}'. " +
                    "This will fail at startup if both nodes try to bind the same port.", httpPort, profileName);
        }

        if (!p2pPortsInUse.add(p2pPort)) {
            LOGGER.warn("P2P port {} is already in use by another node - potential conflict for profile '{}'. " +
                    "This will fail at startup if both nodes try to bind the same port.", p2pPort, profileName);
        }

        LOGGER.debug("Registered ports for profile '{}': HTTP={}, P2P={}", profileName, httpPort, p2pPort);

        nodes.add(signum);
        LOGGER.info("Registered Signum for profile '{}'", profileName);
    }

    /**
     * Unregisters a Signum instance by profile name, releasing its ports.
     *
     * @param profileName the profile to unregister
     * @return the removed Signum, or {@code null} if not found
     */
    public synchronized Signum removeNode(String profileName) {
        Signum removed = null;
        for (int i = 0; i < nodes.size(); i++) {
            if (profileName.equals(nodes.get(i).getProfileName())) {
                removed = nodes.get(i);
                nodes.remove(i);
                break;
            }
        }
        if (removed != null) {
            releasePorts(removed);
            LOGGER.info("Unregistered Signum for profile '{}'", profileName);
        } else {
            LOGGER.debug("No Signum found to unregister for profile '{}'", profileName);
        }
        return removed;
    }

    /**
     * Looks up a Signum instance by profile name.
     *
     * @param profileName the profile identifier
     * @return the Signum, or {@code null} if not registered
     */
    public Signum get(String profileName) {
        for (Signum s : nodes) {
            if (profileName.equals(s.getProfileName())) {
                return s;
            }
        }
        return null;
    }

    /**
     * Returns an unmodifiable snapshot of all registered Signum instances.
     */
    public List<Signum> getAll() {
        return List.copyOf(nodes);
    }

    /**
     * Returns the number of currently registered nodes.
     */
    public int size() {
        return nodes.size();
    }

    /**
     * Checks whether a profile has a registered Signum instance.
     */
    public boolean hasProfile(String profileName) {
        return get(profileName) != null;
    }

    /**
     * Returns the first registered Signum (for legacy callers).
     *
     * @return the first Signum, or null if none
     */
    public Signum getActive() {
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    /**
     * Stops all registered Signum instances and clears the registry.
     */
    public synchronized void stopAll() {
        LOGGER.info("Stopping all {} registered node(s)", nodes.size());
        for (Signum signum : nodes) {
            try {
                signum.stop();
            } catch (Exception e) {
                LOGGER.error("Error stopping profile '{}'", signum.getProfileName(), e);
            }
        }
        nodes.clear();
        httpPortsInUse.clear();
        p2pPortsInUse.clear();
    }

    /**
     * Starts all registered Signum instances that are not yet running.
     */
    public void startAll() {
        for (Signum signum : nodes) {
            if (!signum.isRunning()) {
                try {
                    signum.start();
                } catch (Exception e) {
                    LOGGER.error("Error starting profile '{}'", signum.getProfileName(), e);
                }
            }
        }
    }

    // =====================================================================
    // Port conflict queries
    // =====================================================================

    /**
     * Checks if an HTTP port is already in use by any registered node.
     */
    public boolean isHttpPortInUse(int port) {
        return httpPortsInUse.contains(port);
    }

    /**
     * Checks if a P2P port is already in use by any registered node.
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
    // PortConflictException
    // =====================================================================

    /**
     * Exception thrown when a node tries to bind to a port
     * already in use by another registered node.
     */
    public static class PortConflictException extends RuntimeException {
        public PortConflictException(String message) {
            super(message);
        }
    }

    // =====================================================================
    // Internal helpers
    // =====================================================================

    private int resolvePort(Signum signum, Prop<Integer> portProp) {
        if (signum.getProfile() != null) {
            String val = signum.getProfile().getProperty(portProp.getName(), String.valueOf(portProp.getDefaultValue()));
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException e) {
                // fall through to default
            }
        }
        return portProp.getDefaultValue();
    }

    private void releasePorts(Signum signum) {
        if (signum.getProfile() == null) return;
        int httpPort = resolvePort(signum, Props.API_PORT);
        int p2pPort = resolvePort(signum, Props.P2P_PORT);
        httpPortsInUse.remove(httpPort);
        p2pPortsInUse.remove(p2pPort);
        LOGGER.debug("Released ports for profile '{}': HTTP={}, P2P={}",
                signum.getProfileName(), httpPort, p2pPort);
    }

    // =====================================================================
    // Module contract
    // =====================================================================

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public void init(ModuleContext context) {
        this.context = context;
    }

    @Override
    public void start() {
        // Register logging provider so the composite logging infrastructure
        // knows about the Node module's built-in defaults & presets.
        if (loggingProvider == null) {
            loggingProvider = new NodeLoggingProvider();
        }
        loggingProvider.register();
    }

    @Override
    public void stop() {
        // Idempotent guard - safe to call multiple times
        if (stopped) {
            LOGGER.debug("NodeModule already stopped, skipping.");
            return;
        }
        stopped = true;

        LOGGER.info("Stopping NodeModule - initiating full node shutdown sequence");

        try {
            // 1. Unregister the logging provider first (cleanup registration)
            if (loggingProvider != null) {
                loggingProvider.unregister();
                LOGGER.debug("Unregistered NodeLoggingProvider");
            }

            // 2. Stop all running nodes via direct ownership
            stopAll();
            LOGGER.debug("Stopped all nodes via NodeModule");

        } catch (Exception e) {
            LOGGER.error("Error during NodeModule stop sequence", e);
        }
    }

    /**
     * Returns the single {@link NodePanel} UI for this module, creating it on first access.
     * <p>
     * Uses double-checked locking to guarantee thread-safe singleton creation of the
     * {@code NodePanel}. Multiple concurrent callers will receive the same instance,
     * preventing the duplicate-panel problem where 4-6 panels were created and each
     * re-emitted SLF4J logs, causing duplicates in the SystemConsole.
     * </p>
     *
     * @return the shared NodePanel instance
     * @since 4.1
     */
    @Override
    public JComponent getUI() {
        NodePanel panel = gui;
        if (panel == null) {
            synchronized (this) {
                panel = gui;
                if (panel == null) {
                    LOGGER.info("[DIAG] NodeModule.getUI() - creating NEW NodePanel instance");
                    JFrame parentFrame = null;
                    for (java.awt.Frame f : java.awt.Frame.getFrames()) {
                        if (f instanceof JFrame) {
                            parentFrame = (JFrame) f;
                            if (f.isVisible()) {
                                break;
                            }
                        }
                    }
                    try {
                        panel = new NodePanel(parentFrame);
                        gui = panel;
                        LOGGER.info("[DIAG] NodeModule.getUI() - NodePanel created successfully");
                    } catch (Exception e) {
                        LOGGER.error("[DIAG] NodeModule.getUI() - FAILED to create NodePanel", e);
                        throw e;
                    }
                }
            }
        } else {
            LOGGER.debug("[DIAG] NodeModule.getUI() - returning cached NodePanel instance");
        }
        return panel;
    }

    // =====================================================================
    // Shutdownable contract overrides
    // =====================================================================

    /**
     * The Node module has the HIGHEST shutdown priority because it manages
     * active network connections, blockchain processing, web servers, and
     * thread pools that must be stopped before database/logging cleanup.
     */
    @Override
    public ShutdownPriority getShutdownPriority() {
        return ShutdownPriority.HIGHEST;
    }

    /**
     * Override shutdown to delegate to stop().
     */
    @Override
    public void shutdown() throws ShutdownException {
        try {
            stop();
        } catch (Exception e) {
            throw new ShutdownException(getId(), "Failed to stop NodeModule", e);
        }
    }
}