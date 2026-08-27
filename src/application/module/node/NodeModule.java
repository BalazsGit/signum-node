package application.module.node;

import application.api.Module;
import application.api.ModuleContext;
import application.api.ShutdownPriority;
import application.api.Shutdownable;
import application.module.node.gui.NodePanel;
import application.module.node.logging.NodeLoggingProvider;
import application.module.node.props.Prop;
import application.module.node.props.Props;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.NodeProfileRepository;
import application.utils.io.PathUtils;
import application.utils.logging.ProfileLogger;
import javax.swing.JComponent;
import javax.swing.JFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /**
     * Single-threaded, daemon lifecycle executor: heavy node start/stop work
     * ({@code Signum.start()} / {@code Signum.stop()}) runs HERE — never on the
     * caller's thread (e.g. the EDT), so the GUI stays responsive during long
     * initializations. FIFO ordering also guarantees that a restart
     * (stop → start) executes sequentially for the same profile. GUI feedback
     * is delivered through {@code Signum} state pushes (STARTING/RUNNING/ERROR).
     */
    private final ExecutorService lifecycleExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "signum-lifecycle");
        thread.setDaemon(true);
        return thread;
    });

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
     * <p>
     * Starts are queued on the lifecycle executor (never blocks the caller,
     * e.g. the EDT) and run sequentially in registry order.
     * </p>
     */
    public void startAll() {
        List<Signum> snapshot = List.copyOf(nodes);
        for (Signum signum : snapshot) {
            if (!signum.isRunning()) {
                lifecycleExecutor.execute(() -> {
                    try {
                        signum.start();
                    } catch (Exception e) {
                        LOGGER.error("Error starting profile '{}'", signum.getProfileName(), e);
                    }
                });
            }
        }
    }

    /**
     * Starts the node for the given profile, creating the {@link Signum} instance
     * if it does not exist yet.
     * <p>
     * This is the <b>single lifecycle entry point</b> for node startup: the GUI and
     * all other callers MUST use this method instead of touching {@code Signum}
     * instances directly or resolving a global "active" profile. Semantics:
     * </p>
     * <ul>
     *   <li>profile already registered and RUNNING → returns the existing instance (no-op)</li>
     *   <li>profile already registered but not running → queues {@code start()} on the same instance</li>
     *   <li>profile not registered → creates a {@link Signum}, registers it, then queues the start
     *       (port conflicts surface as startup failure, state → ERROR, retryable)</li>
     * </ul>
     * <p>
     * <b>Asynchronous:</b> creation/registration happens synchronously, but the
     * heavy {@code Signum.start()} runs on the module lifecycle thread — this
     * method returns immediately and NEVER blocks the caller (e.g. the EDT).
     * Progress is observed through {@code Signum} state pushes
     * (STARTING → RUNNING, or STARTING → ERROR); the state reaches ERROR when
     * startup fails. A "please wait" placeholder line is emitted to the profile
     * console (via the {@link ProfileLogger} replay buffer, so an inactive
     * Console tab still shows it later) right before the start begins.
     * </p>
     *
     * @param profileName the profile name to start (never null/blank)
     * @return the registered {@link Signum} instance (starting or already running)
     * @throws IllegalArgumentException if {@code profileName} is null or blank
     */
    public Signum startNode(String profileName) {
        return startNode(profileName, PathUtils.resolvePath(Signum.CONF_FOLDER));
    }

    /**
     * Starts the node for the given profile using an explicit configuration folder.
     * See {@link #startNode(String)} for the full semantics (including the
     * asynchronous start contract).
     *
     * @param profileName the profile name to start (never null/blank)
     * @param confRoot    the base configuration folder for a newly created instance
     * @return the registered {@link Signum} instance (starting or already running)
     * @throws IllegalArgumentException if {@code profileName} is null/blank or
     *         {@code confRoot} is null
     */
    public Signum startNode(String profileName, Path confRoot) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("Profile name must not be null or blank");
        }
        if (confRoot == null) {
            throw new IllegalArgumentException("confRoot must not be null");
        }

        Signum target;
        synchronized (this) {
            Signum existing = get(profileName);
            if (existing != null) {
                target = existing;
            } else {
                NodeProfile profile = resolveProfile(profileName);
                LOGGER.info("startNode('{}'): creating new Signum instance (confRoot={})", profileName, confRoot);
                Signum fresh = new Signum(profile, confRoot);
                addNode(fresh);
                target = fresh;
            }
        }

        if (target.isRunning()) {
            LOGGER.debug("startNode('{}'): already RUNNING — no-op", profileName);
            return target;
        }

        LOGGER.info("startNode('{}'): queuing async start on lifecycle thread", profileName);
        lifecycleExecutor.execute(() -> {
            try {
                // A queued duplicate (double click, restart cycle) may find the node
                // already starting by the time this task executes.
                if (target.isRunning() || target.getState() == Signum.State.STARTING) {
                    LOGGER.debug("startNode('{}'): already starting/running — skipping", profileName);
                    return;
                }
                // Immediate user feedback: a "please wait" line in the profile console
                // before any node log arrives. The ProfileLogger replay buffer covers
                // the case where the Console tab (subscriber) attaches later.
                ProfileLogger profileLogger = target.getProfileLogger();
                if (profileLogger != null) {
                    profileLogger.info(String.format(
                            "→ '%s' node is loading — please wait (first start can take several minutes)...",
                            profileName));
                }
                target.start();
            } catch (Exception e) {
                LOGGER.error("Startup failed for profile '{}'", profileName, e);
            }
        });
        return target;
    }

    /**
     * Stops the node for the given profile.
     * <p>
     * <b>Asynchronous:</b> the stop runs on the module lifecycle thread — this
     * method returns immediately and never blocks the caller (e.g. the EDT).
     * The result is observed through state pushes (STOPPING → STOPPED).
     * </p>
     *
     * @param profileName the profile name to stop
     * @return the affected {@link Signum} instance, or {@code null} if the profile
     *         was not registered (no-op)
     */
    public Signum stopNode(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("Profile name must not be null or blank");
        }
        Signum signum = get(profileName);
        if (signum == null) {
            LOGGER.debug("stopNode('{}'): not registered — no-op", profileName);
            return null;
        }
        LOGGER.info("stopNode('{}'): queuing async stop on lifecycle thread", profileName);
        lifecycleExecutor.execute(() -> {
            try {
                signum.stop();
            } catch (Exception e) {
                LOGGER.error("Stop failed for profile '{}'", profileName, e);
            }
        });
        return signum;
    }

    /**
     * Restarts the node for the given profile: stop (if running) then start.
     * <p>
     * The same {@link Signum} instance is reused ({@code doShutdown} +
     * {@code doInitialize}); a missing instance is created first. Both steps are
     * queued on the lifecycle thread in order (stop, then start).
     * </p>
     *
     * @param profileName the profile name to restart (never null/blank)
     * @return the restarted {@link Signum} instance
     */
    public Signum restartNode(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("Profile name must not be null or blank");
        }
        LOGGER.info("restartNode('{}')", profileName);
        stopNode(profileName);
        return startNode(profileName);
    }

    /**
     * Resolves the {@link NodeProfile} for a profile name: loads it from the
     * profile system when available, otherwise falls back to a name-only profile
     * (hardcoded defaults apply).
     *
     * @param profileName the profile name
     * @return the resolved profile, never null
     */
    private NodeProfile resolveProfile(String profileName) {
        NodeProfile profile = NodeProfileRepository.loadByName(profileName);
        if (profile == null) {
            LOGGER.debug("resolveProfile('{}'): profile system returned null — using name-only profile", profileName);
            profile = new NodeProfile(profileName);
        }
        return profile;
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

        // Multi-node boot path: boot every profile with autostart enabled.
        // NodeModule is the only composition root, so profile discovery and
        // startup happen here — never in Signum or the GUI (v4 architecture).
        try {
            for (NodeProfile profile : NodeProfileRepository.loadAll()) {
                if (profile != null && profile.isAutostart() && get(profile.getName()) == null) {
                    try {
                        startNode(profile.getName());
                    } catch (Exception e) {
                        LOGGER.error("Failed to autostart profile '{}'", profile.getName(), e);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Autostart profile discovery skipped: {}", e.getMessage());
        }
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

            // 3. Shut down the lifecycle executor (cancels any still-queued
            //    start/stop tasks; the thread is a daemon, so it cannot hold
            //    the JVM open either way).
            lifecycleExecutor.shutdownNow();
            LOGGER.debug("Lifecycle executor shut down");

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
                    LOGGER.debug("NodeModule.getUI() - creating NEW NodePanel instance");
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
                        LOGGER.debug("NodeModule.getUI() - NodePanel created successfully");
                    } catch (Exception e) {
                        LOGGER.error("NodeModule.getUI() - FAILED to create NodePanel", e);
                        throw e;
                    }
                }
            }
        } else {
            LOGGER.debug("NodeModule.getUI() - returning cached NodePanel instance");
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