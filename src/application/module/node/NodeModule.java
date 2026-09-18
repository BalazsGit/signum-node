package application.module.node;
import application.utils.config.ModuleIds;

import application.api.Module;
import application.api.ModuleContext;
import application.api.ShutdownPriority;
import application.api.Shutdownable;
import application.module.node.gui.NodePanel;
import application.module.node.logging.NodeLoggingProvider;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.NodeProfileRepository;
import application.module.node.profile.ProfileConflictDetector;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.utils.io.PathUtils;
import application.utils.logging.ProfileLogger;
import javax.swing.JComponent;
import javax.swing.JFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
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
 *         ├── stopAll()
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

    public static final String ID = ModuleIds.NODE;
    public static final String DISPLAY_NAME = "Node";

    /** Singleton instance for static access (same pattern as Signum). */
    private static volatile NodeModule INSTANCE;

    // =====================================================================
    // Node registry (direct ownership)
    // =====================================================================

    /** All managed Signum instances (thread-safe for reads). */
    private final List<Signum> nodes = new CopyOnWriteArrayList<>();

    /**
     * Resource ownership: namespaced resource key to owning profile name. A profile reserves
     * its claimable resources (see {@link ProfileConflictDetector#RESOURCES}) at start and
     * releases them at stop / failed start. Keys are namespaced per resource type so, e.g., an
     * API port and a P2P port with the same number never collide. Single ownership store:
     * reserve, release, conflict detection and the claiming-profile set all use this one map.
     */
    private final java.util.Map<String, String> resourceOwner = new ConcurrentHashMap<>();

    /**
     * Listeners notified (synchronously) whenever the set of claiming profiles changes — after
     * a profile reserves its resources at start, releases them at stop / failed start, or the
     * ownership store is cleared on shutdown. Swing-free by design: the core exposes a plain
     * {@link Runnable} event so it never depends on the GUI (GUI → core direction only). The
     * single GUI subscriber (NodePanel) uses it to re-evaluate every live NodeInfoBar's
     * cross-profile conflict chips, keeping the red warnings current without a tab switch.
     */
    private final java.util.List<Runnable> claimingSetListeners = new CopyOnWriteArrayList<>();

    /**
     * Daemon lifecycle executor: heavy node start/stop work
     * ({@code Signum.start()} / {@code Signum.stop()}) runs HERE — never on the
     * caller's thread (e.g. the EDT), so the GUI stays responsive during long
     * initializations.
     * <p>
     * v5 (multi-node): a small daemon thread POOL so that different profiles can
     * start/stop in PARALLEL — a profile's heavy init (Flyway, SQLite VACUUM,
     * service startup) no longer head-of-line-blocks another profile's start.
     * Ordering guarantees: per-profile tasks are serialized under that profile's
     * gate lock ({@link #serialize(String, Runnable)}), so a restart (stop →
     * start) for the SAME profile still executes sequentially in submission
     * order, and resource reservation (first-come-first-served) still happens
     * synchronously on the caller thread in {@code synchronized(this)} blocks.
     * GUI feedback is delivered through {@code Signum} state pushes
     * (STARTING/RUNNING/ERROR) plus the start-pending set (see
     * {@link #isStartPending(String)}).
     * </p>
     */
    private final ExecutorService lifecycleExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "signum-lifecycle");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Per-profile lifecycle gate: one FAIR {@link Semaphore}(1) per profile name.
     * Every start/stop/restart task acquires its profile's gate BEFORE it runs
     * ({@link #serialize(String, Runnable)}), which (a) serializes a profile's own
     * operations (no two operations on the same profile ever run concurrently) and
     * (b) — because the semaphore is FAIR — grants them in acquisition (submission)
     * order, preserving the original FIFO guarantee (e.g. Start-then-Stop cannot be
     * reordered) while still letting DIFFERENT profiles proceed in parallel.
     */
    private final ConcurrentHashMap<String, java.util.concurrent.Semaphore> profileGates = new ConcurrentHashMap<>();

    /**
     * Returns (creating on demand) the fair lifecycle gate for a profile.
     */
    private java.util.concurrent.Semaphore profileGate(String profileName) {
        return profileGates.computeIfAbsent(profileName, k -> new java.util.concurrent.Semaphore(1, true));
    }

    /**
     * Wraps a lifecycle task so it runs under its profile's gate (per-profile
     * serialization, fair/FIFO order, parallel across profiles). The permit is
     * always released, even if the task throws.
     */
    private Runnable serialize(String profileName, Runnable task) {
        return () -> {
            java.util.concurrent.Semaphore gate = profileGate(profileName);
            gate.acquireUninterruptibly();
            try {
                task.run();
            } finally {
                gate.release();
            }
        };
    }

    /**
     * Profiles whose start has been requested (reserved + queued) but whose start
     * task has not yet finished — the "start pending" set. A profile is added when
     * its start is queued and removed when the queued task completes (success,
     * failure, or rejection). The GUI treats a pending profile exactly like a
     * STARTING one: spinner icon, Start button disabled — immediately, without
     * waiting for the (possibly queue-blocked) state push. Swing-free by design:
     * listeners only schedule an EDT refresh.
     */
    private final Set<String> startPending = ConcurrentHashMap.newKeySet();

    /**
     * Listeners notified (synchronously) whenever the start-pending set changes —
     * a start is queued, or its task finishes / is rejected. Swing-free by design:
     * the GUI subscriber only schedules an EDT refresh.
     */
    private final List<Runnable> pendingListeners = new CopyOnWriteArrayList<>();

    /**
     * True if a start has been requested for the given profile and its start task
     * has not yet completed (queued or running). The GUI shows this as the
     * STARTING transition state (spinner + disabled Start) immediately on the
     * click, regardless of where the task sits in the lifecycle queue.
     *
     * @param profileName the profile name (null-safe: returns false)
     * @return true while a start for the profile is pending
     */
    public boolean isStartPending(String profileName) {
        return profileName != null && startPending.contains(profileName);
    }

    /**
     * Registers a callback invoked whenever the start-pending set changes (a start
     * is queued, or its task finishes / is rejected). Runs synchronously on the
     * thread that caused the change and must be lightweight — the GUI subscriber
     * only schedules an EDT refresh.
     *
     * @param listener the callback (null is ignored)
     */
    public void addPendingListener(Runnable listener) {
        if (listener != null) {
            pendingListeners.add(listener);
        }
    }

    /**
     * Removes a previously registered start-pending change callback.
     *
     * @param listener the callback to remove (null is a no-op)
     */
    public void removePendingListener(Runnable listener) {
        pendingListeners.remove(listener);
    }

    private void notifyPendingChanged() {
        for (Runnable listener : pendingListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                LOGGER.warn("Start-pending change listener failed: {}", e.getMessage());
            }
        }
    }

    /** Marks the profile as start-pending (idempotent) and notifies listeners if it changed. */
    private void markStartPending(String profileName) {
        if (startPending.add(profileName)) {
            notifyPendingChanged();
        }
    }

    /** Clears the profile's start-pending mark (idempotent) and notifies listeners if it changed. */
    private void unmarkStartPending(String profileName) {
        if (startPending.remove(profileName)) {
            notifyPendingChanged();
        }
    }

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
     * Registers a Signum instance under its profile name.
     * <p>
     * Registration only records the instance; it does <b>not</b> claim any resources
     * (ports/DB). Resource claiming (and conflict enforcement) happens at start time in
     * {@link #startNode(String, Path)}, where ordering is deterministic. If an existing
     * instance is registered for the same profile it is replaced and its resources released.
     * </p>
     *
     * @param signum the Signum to register (must not be null)
     * @throws IllegalArgumentException if signum is null or profile name is blank
     */
    public synchronized void addNode(Signum signum) {
        if (signum == null) {
            throw new IllegalArgumentException("Signum must not be null");
        }
        String profileName = signum.getProfileName();
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("Signum profile name must not be blank");
        }

        // Replace existing node with same profile name first (release its resources before re-adding)
        Signum previous = null;
        for (int i = 0; i < nodes.size(); i++) {
            if (profileName.equals(nodes.get(i).getProfileName())) {
                previous = nodes.get(i);
                nodes.remove(i);
                break;
            }
        }
        if (previous != null) {
            releaseResources(previous);
            LOGGER.warn("Replaced existing Signum for profile '{}'", profileName);
        }

        nodes.add(signum);
        application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profileName,
                () -> LOGGER.info("Registered Signum for profile '{}'", profileName));
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
            // Permanent removal: this Signum is gone from the registry, so release its
            // per-Signum logging resources too (closes the ProfileLogger + its GUI
            // console subscriber). The normal stop/start (restart) path never does this —
            // only true removal/app-shutdown does, so the console survives restarts.
            removed.dispose();
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
            } finally {
                // stopAll() permanently clears the registry (nodes.clear() below), so this
                // is a teardown path — release each Signum's per-Signum logging resources
                // (closes the ProfileLogger + its GUI console subscriber). This is safe
                // here because the instances are being removed; a single-profile restart
                // (stopNode + startNode) keeps its instance registered and never disposes.
                signum.dispose();
            }
        }
        nodes.clear();
        resourceOwner.clear();
        // Full teardown freed every reservation: notify subscribers to clear all conflict chips.
        notifyClaimingSetChanged();
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

        // ── Registry section: narrow lock around instance lookup/creation only ──
        // The disk I/O below runs OUTSIDE the lock; the conflict check + reservation
        // re-enter the lock as ONE atomic check-then-act section, keeping the
        // first-come-first-served reservation order deterministic.
        Signum target;
        synchronized (this) {
            Signum existing = get(profileName);
            if (existing != null) {
                target = existing;
            } else {
                NodeProfile profile = resolveProfile(profileName);
                application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profileName,
                        () -> LOGGER.info("startNode('{}'): creating new Signum instance (confRoot={})", profileName, confRoot));
                Signum fresh = new Signum(profile, confRoot);
                addNode(fresh);
                target = fresh;
            }
        }

        if (target.isRunning()) {
            LOGGER.debug("startNode('{}'): already RUNNING — no-op", profileName);
            return target;
        }

        // ── Apply the latest on-disk configuration before the conflict check ──
        // An existing (stopped / created / failed) instance may still hold the profile
        // snapshot it was created with. Re-reading it from disk here lets an in-session
        // config edit (e.g. a changed API/P2P/WS port or database) be honored both by
        // the pre-check below and by the node's own PropertyService — so "fix the port
        // → save → Start/Restart" finally takes effect without an app restart. A freshly
        // created instance is already built from disk, and a transitional (STARTING /
        // STOPPING) instance is skipped, so this is a safe no-op in both cases.
        // NOTE: kept synchronous on the caller thread — GUI code (NodeConsolePanel)
        // relies on getPropertyService() being populated immediately after startNode()
        // returns, before the queued start task has a chance to run.
        if (target.getState() != Signum.State.STARTING && target.getState() != Signum.State.STOPPING) {
            target.refreshConfiguration();
        }

        // ── Conflict pre-check + reservation (ONE atomic section inside the lock) ──
        // If another live profile already claims one of this profile's resources
        // (API / P2P / WebSocket port, or the same database), reject the start now
        // rather than letting it fail later at OS port-bind time. This enforces the
        // autostart order: the profile queued first wins; a conflicting later one is
        // simply not started (and stays startable once the conflict is resolved).
        String conflict;
        synchronized (this) {
            conflict = findResourceConflict(target);
            if (conflict != null) {
                application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profileName,
                        () -> LOGGER.warn("startNode('{}'): REJECTED — {}", profileName, conflict));
                target.reportStartRejected(conflict);
                return target;
            }

            // ── Reserve resources (idempotent; putIfAbsent never steals another owner) ──
            reserveResources(target);
        }

        // ── Start pending: the GUI shows the STARTING transition (spinner + disabled
        //    Start button) IMMEDIATELY, even while the task waits in the lifecycle
        //    queue — the mark is cleared when the task completes (see finally). ──
        markStartPending(profileName);

        application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profileName,
                () -> LOGGER.info("startNode('{}'): queuing async start on lifecycle thread", profileName));
        lifecycleExecutor.execute(serialize(profileName, () -> {
            try {
                // A queued duplicate (double click, restart cycle) may find the node
                // already starting by the time this task executes.
                if (target.isRunning() || target.getState() == Signum.State.STARTING
                        || target.getState() == Signum.State.STOPPING) {
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
                application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profileName,
                        () -> LOGGER.error("Startup failed for profile '{}'", profileName, e));
                // A failed start (e.g. the OS port is already bound by a non-profile
                // process) leaves the node in ERROR, i.e. NOT running — so it must not
                // keep holding its API/P2P/WS port or database reservation. Releasing it
                // keeps the "only running nodes conflict" invariant intact and lets
                // another profile (or a retry) take the resources. releaseResources() is
                // owner-scoped (removeOwnerIf), so a queued duplicate can never free a
                // DIFFERENT profile's reservation.
                releaseResources(target);
            } finally {
                // The queued start has completed (success / failure / skip): clear the
                // pending mark so the GUI restores the button state from the real state.
                unmarkStartPending(profileName);
            }
        }));
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
        application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profileName,
                () -> LOGGER.info("stopNode('{}'): queuing async stop on lifecycle thread", profileName));
        lifecycleExecutor.execute(serialize(profileName, () -> {
            try {
                signum.stop();
            } catch (Exception e) {
                LOGGER.error("Stop failed for profile '{}'", profileName, e);
            } finally {
                // Release the resources this profile claimed (API/P2P/WebSocket ports,
                // database) once the stop has run, so another profile can take them and a
                // later restart of this profile re-reserves them cleanly.
                releaseResources(signum);
            }
        }));
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
        application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profileName,
                () -> LOGGER.info("restartNode('{}')", profileName));

        Signum target;
        synchronized (this) {
            Signum existing = get(profileName);
            if (existing == null) {
                NodeProfile profile = resolveProfile(profileName);
                existing = new Signum(profile, PathUtils.resolvePath(Signum.CONF_FOLDER));
                addNode(existing);
            }
            target = existing;
        }

        // Restart = stop then start as ONE ordered unit under the profile gate
        // (serialized in submission order, parallel to other profiles), so the
        // start runs strictly after the stop (and its resource release).
        // The previous implementation queued an async stop and then called startNode(),
        // whose "already RUNNING" no-op saw the still-running node and bailed before
        // queueing a start — leaving the node STOPPED after the queued stop ran.
        // Pending mark: the GUI shows the STARTING transition (spinner + disabled
        // buttons) for the whole stop→start window; cleared in finally below.
        markStartPending(profileName);
        lifecycleExecutor.execute(serialize(profileName, () -> {
            try {
                try {
                    target.stop();
                } catch (Exception e) {
                    application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profileName,
                            () -> LOGGER.error("restartNode('{}'): stop failed", profileName, e));
                } finally {
                    releaseResources(target);
                }
                // Apply the latest on-disk configuration (an in-session config edit is picked up).
                target.refreshConfiguration();
                String conflict;
                synchronized (this) {
                    conflict = findResourceConflict(target);
                    if (conflict == null) {
                        reserveResources(target);
                    }
                }
                if (conflict != null) {
                    application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profileName,
                            () -> LOGGER.warn("restartNode('{}'): REJECTED — {}", profileName, conflict));
                    target.reportStartRejected(conflict);
                    return;
                }
                ProfileLogger profileLogger = target.getProfileLogger();
                if (profileLogger != null) {
                    profileLogger.info(String.format("→ '%s' node restarting — please wait…", profileName));
                }
                try {
                    target.start();
                } catch (Exception e) {
                    application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profileName,
                            () -> LOGGER.error("restartNode('{}'): start failed", profileName, e));
                    releaseResources(target);
                }
            } finally {
                // The restart (stop→start) has completed in all paths (success,
                // failure, or rejection): clear the pending mark so the GUI restores
                // the button state from the real state.
                unmarkStartPending(profileName);
            }
        }));
        return target;
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
     * Checks whether the given resource (identified by its field and collision key) is currently
     * claimed by a live profile. This is the generic, extensible query behind the port helpers.
     */
    public boolean isResourceClaimed(ProfileConflictDetector.ConflictField field, String resourceKey) {
        return resourceOwner.containsKey(nsKey(field, resourceKey));
    }

    /**
     * Checks if an API/HTTP port is currently claimed by any live node.
     */
    public boolean isHttpPortInUse(int port) {
        return isResourceClaimed(ProfileConflictDetector.ConflictField.API_PORT, String.valueOf(port));
    }

    /**
     * Checks if a P2P port is currently claimed by any live node.
     */
    public boolean isP2pPortInUse(int port) {
        return isResourceClaimed(ProfileConflictDetector.ConflictField.P2P_PORT, String.valueOf(port));
    }

    /**
     * Returns the set of profile names that currently claim at least one resource (an
     * API/P2P/WebSocket port or the database). A profile enters this set when it reserves its
     * resources at start (i.e. it is starting up or running) and leaves it when it releases
     * them (stopped, or a failed start).
     * <p>
     * This is the authoritative "active" set for cross-profile conflict detection: it is backed
     * by the same ownership store that enforces start-time conflicts, so a profile is considered
     * to hold its resources exactly while its reservation is live. The GUI reads this set, so
     * its conflict warning always reflects what a start would actually reject.
     * </p>
     *
     * @return an unmodifiable set of claiming profile names (may be empty)
     */
    public Set<String> getClaimingProfileNames() {
        return Collections.unmodifiableSet(new HashSet<>(resourceOwner.values()));
    }

    /**
     * Registers a callback invoked whenever the set of claiming profiles changes (a profile
     * reserves at start, releases at stop / failed start, or the store is cleared on shutdown).
     * The callback runs synchronously on the thread that caused the change and must be
     * lightweight — the single GUI subscriber only schedules an EDT refresh.
     *
     * @param listener the callback to invoke (null is ignored)
     */
    public void addClaimingSetListener(Runnable listener) {
        if (listener != null) {
            claimingSetListeners.add(listener);
        }
    }

    /**
     * Removes a previously registered claiming-set change callback.
     *
     * @param listener the callback to remove (null is ignored)
     */
    public void removeClaimingSetListener(Runnable listener) {
        if (listener != null) {
            claimingSetListeners.remove(listener);
        }
    }

    /**
     * Notifies all registered claiming-set change callbacks. A listener that throws is isolated
     * (logged) so it cannot prevent the remaining listeners from running.
     */
    private void notifyClaimingSetChanged() {
        for (Runnable listener : claimingSetListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                LOGGER.warn("Claiming-set change listener failed: {}", e.getMessage());
            }
        }
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

    private void releasePorts(Signum signum) {
        releaseResources(signum);
    }

    /**
     * Reserves the resources (API/P2P/WebSocket ports and database) that the given
     * node's profile requires. Idempotent: {@code putIfAbsent} never overwrites a key
     * already owned by a different profile. Must be called from within the
     * {@code synchronized(this)} lifecycle section, <i>after</i> {@link #findResourceConflict}.
     */
    private void reserveResources(Signum signum) {
        NodeProfile profile = signum.getProfile();
        if (profile == null) {
            return;
        }
        String owner = signum.getProfileName();
        for (ProfileConflictDetector.Resource r : ProfileConflictDetector.RESOURCES) {
            String key = r.key(profile);
            if (key.isEmpty()) {
                continue; // resource does not apply (e.g. WebSocket disabled, no recognizable DB)
            }
            // Idempotent: putIfAbsent never overwrites a key already owned by another profile.
            resourceOwner.putIfAbsent(nsKey(r.getField(), key), owner);
        }
        LOGGER.debug("Reserved resources for profile '{}' (owner count={})", owner, resourceOwner.size());
        // Ownership changed: notify subscribers (the GUI re-evaluates every live conflict chip)
        // so the red warning surfaces on every visible bar immediately, without a tab switch.
        notifyClaimingSetChanged();
    }

    /**
     * Releases the resources the given node's profile reserved. Only removes entries
     * that this node actually owns (so it never frees another profile's reservation).
     */
    private void releaseResources(Signum signum) {
        NodeProfile profile = signum.getProfile();
        if (profile == null) {
            return;
        }
        String owner = signum.getProfileName();
        for (ProfileConflictDetector.Resource r : ProfileConflictDetector.RESOURCES) {
            String key = r.key(profile);
            if (key.isEmpty()) {
                continue;
            }
            // Only removes an entry this node actually owns (so it never frees another's).
            removeOwnerIf(resourceOwner, nsKey(r.getField(), key), owner);
        }
        LOGGER.debug("Released resources for profile '{}'", owner);
        // Ownership changed: notify subscribers so the now-freed resources clear the red
        // warnings on every visible bar.
        notifyClaimingSetChanged();
    }

    /**
     * Returns a human-readable reason if the given node's resources are already claimed
     * by another live profile, or {@code null} when there is no conflict.
     * <p>
     * Used by {@link #startNode} to reject a start (e.g. the second of two conflicting
     * autostart profiles) before it can fail at the OS port-bind stage.
     * </p>
     */
    private String findResourceConflict(Signum target) {
        NodeProfile profile = target.getProfile();
        if (profile == null) {
            return null;
        }
        String me = target.getProfileName();
        for (ProfileConflictDetector.Resource r : ProfileConflictDetector.RESOURCES) {
            String key = r.key(profile);
            if (key.isEmpty()) {
                continue;
            }
            String owner = resourceOwner.get(nsKey(r.getField(), key));
            if (owner != null && !owner.equals(me)) {
                return r.message(r.display(profile), owner);
            }
        }
        return null;
    }

    private static String nsKey(ProfileConflictDetector.ConflictField field, String key) {
        return field.name() + ":" + key;
    }

    private static <K> void removeOwnerIf(java.util.Map<K, String> owners, K key, String expectedOwner) {
        owners.computeIfPresent(key, (k, current) -> current.equals(expectedOwner) ? null : current);
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

        // ── Single-profile mode (headless "profile run <name>") ──
        // Start ONLY the target profile (regardless of its autostart flag), and nothing else.
        String target = context != null ? context.getTargetProfileName() : null;
        if (target != null && !target.isBlank()) {
            if (get(target) == null) {
                startNode(target);
            }
            LOGGER.info("profile run mode: started target profile '{}'", target);
            return;
        }

        // Multi-node boot path: boot every profile with autostart enabled.
        // NodeModule is the only composition root, so profile discovery and
        // startup happen here — never in Signum or the GUI (v4 architecture).
        try {
            // Boot autostart profiles in the canonical startup order (the user-defined
            // tab/start order when present, else filesystem discovery order), so the
            // first profile wins any conflicting resource — identical to the GUI tab order.
            NodeProfile[] profiles = NodeProfileRepository.inStartupOrder(NodeProfileRepository.loadAll());
            if (profiles.length == 0) {
                LOGGER.warn("No node profiles configured. Create one: 'signum-node profile create <name>' (CLI) "
                        + "or use the setup wizard in the GUI.");
                return;
            }
            int started = 0;
            for (NodeProfile profile : profiles) {
                if (profile != null && profile.isAutostart() && get(profile.getName()) == null) {
                    try {
                        startNode(profile.getName());
                        started++;
                    } catch (Exception e) {
                        LOGGER.error("Failed to autostart profile '{}'", profile.getName(), e);
                    }
                }
            }
            if (started == 0) {
                LOGGER.warn("No autostart-enabled profiles found (discovered {} profile(s)). "
                        + "Start one from the GUI or run: signum-node profile run <name>", profiles.length);
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

    /**
     * Opens the given web-UI path (e.g. {@code "/phoenix"} or {@code "/classic"})
     * in the default browser, using the first node that exposes a
     * {@code PropertyService} (i.e. a started node).
     * <p>
     * Used by the system tray menu, where there is no per-profile context:
     * the wallet link simply targets the first available node's API port.
     * </p>
     *
     * @param path the web-UI path to open (must start with '/')
     */
    public void openWebUi(String path) {
        for (Signum node : getAll()) {
            if (node == null) {
                continue;
            }
            PropertyService propertyService = node.getPropertyService();
            if (propertyService == null) {
                continue;
            }
            try {
                int port = propertyService.getInt(Props.API_PORT);
                boolean ssl = propertyService.getBoolean(Props.API_SSL);
                String address = (ssl ? "https://" : "http://") + "localhost:" + port + path;
                Desktop.getDesktop().browse(new URI(address));
                return;
            } catch (Exception e) {
                LOGGER.warn("Could not open web UI '{}': {}", path, e.getMessage());
            }
        }
        LOGGER.warn("No node with an available PropertyService to open web UI '{}'", path);
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