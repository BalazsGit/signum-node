package application.module.node.lifecycle;

import application.module.node.Signum;
import application.module.node.logging.NodeLoggingProfile;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.ProfileConfig;
import application.module.node.instance.NodeCoreContext;
import application.module.node.instance.NodeFactory;
import application.utils.logging.LoggingModuleRegistry;
import application.utils.logging.ModuleLoggingProvider;
import application.utils.logging.ProfileThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Central manager for Node profile lifecycle operations.
 * Handles initialization, start, stop, pause and state tracking per profile.
 * Follows the Observer pattern: GUI panels register as LifecycleListeners to
 * receive status updates.
 * <p>
 * Stores {@link NodeProfile} objects directly; runtime state is accessed via
 * {@link NodeProfile#getRuntime()}. This class separates business logic
 * (node startup/shutdown) from the GUI layer, enabling clean headless mode
 * support and testability.
 *
 * @since 4.0
 */
public class NodeLifecycleManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeLifecycleManager.class);

    /**
     * Singleton instance - accessed via getInstance().
     */
    private static volatile NodeLifecycleManager instance;

    /** Registered node profiles. */
    private final List<NodeProfile> profiles;

    /** Observer listeners for lifecycle events. Thread-safe list. */
    private final List<LifecycleListener> listeners;

    /** Profile configuration (autostart, max concurrent nodes, etc.). */
    private final ProfileConfig profileConfig;

    private NodeLifecycleManager() {
        this.profiles = new ArrayList<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.profileConfig = new ProfileConfig();
    }

    /**
     * Gets or creates the singleton instance.
     */
    public static synchronized NodeLifecycleManager getInstance() {
        if (instance == null) {
            instance = new NodeLifecycleManager();
        }
        return instance;
    }

    // ====================================================================
    // Lifecycle operations (String-based API - backward compatible)
    // ====================================================================

    /**
     * Discovers all node profiles from conf/node/profiles/*.properties and registers them.
     */
    public void discoverProfiles() {
        NodeProfile[] discoveredProfiles = NodeProfile.loadAll();
        for (NodeProfile profile : discoveredProfiles) {
            if (!isProfileRegistered(profile.getName())) {
                profiles.add(profile);
                LOGGER.debug("Registered node profile: {}", profile.getName());
            }
        }
        profileConfig.load();
    }

    /**
     * Initializes a profile (loads config, prepares resources - no side effects).
     */
    public void initializeProfile(String profileName) {
        NodeProfile profile = getProfile(profileName);
        if (profile == null) {
            LOGGER.warn("Profile '{}' not found", profileName);
            return;
        }

        NodeProfileRuntime runtime = profile.getRuntime();
        NodeLifecycleState oldState = runtime.getLifecycleState();
        if (!runtime.setLifecycleState(NodeLifecycleState.INITIALIZING)) {
            LOGGER.debug("Cannot initialize '{}', current state: {}", profileName, oldState);
            return;
        }

        notifyStateChanged(profile, oldState, NodeLifecycleState.INITIALIZING);
        runtime.setStatusMessage("Initializing...");

        try {
            // Extract port configuration from properties
            String apiPortStr = profile.getProperty("httpport", "8125");
            try {
                runtime.setApiPort(Integer.parseInt(apiPortStr));
            } catch (NumberFormatException ignored) {
            }
            String p2pPortStr = profile.getProperty("peer.port", "8123");
            try {
                runtime.setP2pPort(Integer.parseInt(p2pPortStr));
            } catch (NumberFormatException ignored) {
            }

            // Apply logging association from profiles.json if configured
            String loggingProfileName = profileConfig.getLoggingProfile(profileName);
            if (loggingProfileName != null && !loggingProfileName.isEmpty()) {
                profile.setLoggingPreset(loggingProfileName);
                LOGGER.debug("Applied logging preset '{}' to profile '{}'", loggingProfileName, profileName);
            }

            runtime.setLifecycleState(NodeLifecycleState.READY);
            runtime.setStatusMessage("Ready to start");
            notifyStateChanged(profile, NodeLifecycleState.INITIALIZING, NodeLifecycleState.READY);
            LOGGER.info("Profile '{}' initialized successfully", profileName);
        } catch (Exception e) {
            LOGGER.error("Failed to initialize profile '{}'", profileName, e);
            runtime.setLifecycleState(NodeLifecycleState.ERROR);
            runtime.setErrorMessage(e.getMessage());
            notifyStateChanged(profile, NodeLifecycleState.INITIALIZING, NodeLifecycleState.ERROR);
            notifyError(profile, e.getMessage());
        }
    }

    /**
     * Starts the node for a profile by name.
     */
    public void startProfile(String profileName) {
        NodeProfile profile = getProfile(profileName);
        if (profile == null) {
            LOGGER.warn("Profile '{}' not found", profileName);
            return;
        }
        startProfileInternal(profile);
    }

    /**
     * Stops the node for a profile by name gracefully.
     */
    public void stopProfile(String profileName) {
        NodeProfile profile = getProfile(profileName);
        if (profile == null) {
            LOGGER.warn("Profile '{}' not found", profileName);
            return;
        }
        stopProfileInternal(profile);
    }

    // ====================================================================
    // Lifecycle operations (NodeProfile-based API - preferred)
    // ====================================================================

    /**
     * Starts the node for the given profile directly.
     * Preferred over {@link #startProfile(String)} as it avoids the string lookup.
     *
     * @param profile the profile to start (must not be null)
     * @throws NullPointerException if profile is null
     */
    public void startProfile(NodeProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        startProfileInternal(profile);
    }

    /**
     * Stops the node for the given profile directly.
     * Preferred over {@link #stopProfile(String)} as it avoids the string lookup.
     *
     * @param profile the profile to stop (must not be null)
     * @throws NullPointerException if profile is null
     */
    public void stopProfile(NodeProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        stopProfileInternal(profile);
    }

    /**
     * Pauses block processing for the given profile.
     * Preferred over {@link #pauseProfile(String)}.
     *
     * @param profile the profile to pause (must not be null)
     * @throws NullPointerException if profile is null
     */
    public void pauseProfile(NodeProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        pauseProfileInternal(profile);
    }

    /**
     * Resumes block processing for the given profile.
     * Preferred over {@link #resumeProfile(String)}.
     *
     * @param profile the profile to resume (must not be null)
     * @throws NullPointerException if profile is null
     */
    public void resumeProfile(NodeProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        resumeProfileInternal(profile);
    }

    /**
     * Initializes the given profile directly.
     * Preferred over {@link #initializeProfile(String)}.
     *
     * @param profile the profile to initialize (must not be null)
     * @throws NullPointerException if profile is null
     */
    public void initializeProfile(NodeProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        initializeProfileInternal(profile);
    }

    /**
     * Pauses block processing for a running node.
     */
    public void pauseProfile(String profileName) {
        NodeProfile profile = getProfile(profileName);
        if (profile == null) {
            return;
        }

        NodeProfileRuntime runtime = profile.getRuntime();

        NodeLifecycleState oldState = runtime.getLifecycleState();
        if (!runtime.setLifecycleState(NodeLifecycleState.PAUSED)) {
            LOGGER.debug("Cannot pause '{}', current state: {}", profileName, oldState);
            return;
        }

        notifyStateChanged(profile, oldState, NodeLifecycleState.PAUSED);
        runtime.setStatusMessage("Paused");
        LOGGER.info("Profile '{}' marked as paused", profileName);
    }

    /**
     * Resumes block processing for a paused node.
     *
     * @param profileName the name of the profile to resume
     */
    public void resumeProfile(String profileName) {
        NodeProfile profile = getProfile(profileName);
        if (profile == null) {
            return;
        }

        NodeProfileRuntime runtime = profile.getRuntime();

        NodeLifecycleState oldState = runtime.getLifecycleState();
        if (!runtime.setLifecycleState(NodeLifecycleState.RUNNING)) {
            LOGGER.debug("Cannot resume '{}', current state: {}", profileName, oldState);
            return;
        }

        notifyStateChanged(profile, oldState, NodeLifecycleState.RUNNING);
        runtime.setStatusMessage("Running");
        LOGGER.info("Profile '{}' marked as resumed", profileName);
    }

    // ====================================================================
    // Internal lifecycle implementations (called by both String and Profile overloads)
    // ====================================================================

    private void initializeProfileInternal(NodeProfile profile) {
        NodeProfileRuntime runtime = profile.getRuntime();
        NodeLifecycleState oldState = runtime.getLifecycleState();
        if (!runtime.setLifecycleState(NodeLifecycleState.INITIALIZING)) {
            LOGGER.debug("Cannot initialize '{}', current state: {}", profile.getName(), oldState);
            return;
        }

        notifyStateChanged(profile, oldState, NodeLifecycleState.INITIALIZING);
        runtime.setStatusMessage("Initializing...");

        try {
            // Extract port configuration from properties
            String apiPortStr = profile.getProperty("httpport", "8125");
            try {
                runtime.setApiPort(Integer.parseInt(apiPortStr));
            } catch (NumberFormatException ignored) {
            }
            String p2pPortStr = profile.getProperty("peer.port", "8123");
            try {
                runtime.setP2pPort(Integer.parseInt(p2pPortStr));
            } catch (NumberFormatException ignored) {
            }

            // Apply logging association from profiles.json if configured
            String loggingProfileName = profileConfig.getLoggingProfile(profile.getName());
            if (loggingProfileName != null && !loggingProfileName.isEmpty()) {
                profile.setLoggingPreset(loggingProfileName);
                LOGGER.debug("Applied logging preset '{}' to profile '{}'", loggingProfileName, profile.getName());
            }

            runtime.setLifecycleState(NodeLifecycleState.READY);
            runtime.setStatusMessage("Ready to start");
            notifyStateChanged(profile, NodeLifecycleState.INITIALIZING, NodeLifecycleState.READY);
            LOGGER.info("Profile '{}' initialized successfully", profile.getName());
        } catch (Exception e) {
            LOGGER.error("Failed to initialize profile '{}'", profile.getName(), e);
            runtime.setLifecycleState(NodeLifecycleState.ERROR);
            runtime.setErrorMessage(e.getMessage());
            notifyStateChanged(profile, NodeLifecycleState.INITIALIZING, NodeLifecycleState.ERROR);
            notifyError(profile, e.getMessage());
        }
    }

    private void startProfileInternal(NodeProfile profile) {
        NodeProfileRuntime runtime = profile.getRuntime();

        // Check max concurrent nodes
        long runningCount = profiles.stream()
                .map(NodeProfile::getRuntime)
                .filter(NodeProfileRuntime::isActive)
                .count();
        int maxConcurrent = profileConfig.getMaxConcurrentNodes();
        if (runningCount >= maxConcurrent) {
            String errorMsg = "Maximum concurrent nodes (" + maxConcurrent + ") reached. Stop another node first.";
            LOGGER.warn(errorMsg);
            runtime.setLifecycleState(NodeLifecycleState.ERROR);
            runtime.setErrorMessage(errorMsg);
            notifyError(profile, errorMsg);
            return;
        }

        NodeLifecycleState oldState = runtime.getLifecycleState();
        if (!runtime.setLifecycleState(NodeLifecycleState.RUNNING)) {
            LOGGER.debug("Cannot start '{}', current state: {}", profile.getName(), oldState);
            return;
        }

        notifyStateChanged(profile, oldState, NodeLifecycleState.RUNNING);
        runtime.setStatusMessage("Starting node...");
        runtime.markStarted();

        // Resolve profile configuration (final for lambda capture)
        final String confFolderPath = profile.getProperty("conf.folder", "conf");

        // Build the Signum facade asynchronously to avoid blocking the caller
        // (e.g., GUI thread). Use a dedicated daemon thread.
        // The Signum facade owns the NodeCoreContext and provides instance-scoped access
        // to all node services. This enables multi-node isolation.
        Runnable startupTask = () -> {
            try {
                Signum signum = new Signum(profile, Paths.get(confFolderPath));
                // Register with NodeFactory first. The factory tracks instances and provides
                // instance lookup via get(profileName) - no singleton "active" needed.
                // Note: legacy bootstrap path (Signum.init()) still calls setActive() for
                // backwards compatibility. Multi-node startup does not use it to avoid
                // cross-node state mutation when multiple nodes start concurrently.
                NodeFactory.getInstance().register(signum);

                signum.start();

                // Set Signum on runtime (also updates legacy coreContext for backwards compat)
                runtime.setSignum(signum);

                runtime.setStatusMessage("Running");
                notifyStatusMessage(profile, "Node started successfully");
                LOGGER.info("Profile '{}' started with Signum facade", profile.getName());
            } catch (Exception e) {
                LOGGER.error("Failed to start profile '{}'", profile.getName(), e);
                runtime.setLifecycleState(NodeLifecycleState.ERROR);
                runtime.setErrorMessage(e.getMessage());
                notifyStateChanged(profile, NodeLifecycleState.RUNNING, NodeLifecycleState.ERROR);
                notifyError(profile, e.getMessage());
            }
        };

        Thread starterThread = new Thread(
                ProfileThreadContext.wrap(startupTask, "node", profile.getName()),
                "Node-Starter-" + profile.getName()
        );
        starterThread.setDaemon(true);
        starterThread.start();
    }

    private void stopProfileInternal(NodeProfile profile) {
        NodeProfileRuntime runtime = profile.getRuntime();

        NodeLifecycleState oldState = runtime.getLifecycleState();
        if (!runtime.setLifecycleState(NodeLifecycleState.STOPPING)) {
            LOGGER.debug("Cannot stop '{}', current state: {}", profile.getName(), oldState);
            return;
        }

        notifyStateChanged(profile, oldState, NodeLifecycleState.STOPPING);
        runtime.setStatusMessage("Stopping...");

        // Notify listeners BEFORE stopping so they can save GUI state, etc.
        notifyShutdownRequested(profile);

        try {
            Signum signum = runtime.getSignum();
            if (signum != null) {
                signum.stop();
                NodeFactory.getInstance().unregister(profile.getName());
                runtime.setSignum(null);
            } else {
                NodeCoreContext context = runtime.getCoreContext();
                if (context != null) {
                    context.stop();
                    NodeFactory.getInstance().unregister(profile.getName());
                    runtime.setCoreContext(null);
                } else {
                    LOGGER.debug("Profile '{}' had no running Signum or context, skipping shutdown", profile.getName());
                }
            }

            runtime.setLifecycleState(NodeLifecycleState.STOPPED);
            runtime.markStopped();
            runtime.setStatusMessage("Stopped");
            notifyStateChanged(profile, NodeLifecycleState.STOPPING, NodeLifecycleState.STOPPED);
            LOGGER.info("Profile '{}' stopped", profile.getName());
        } catch (Exception e) {
            LOGGER.error("Failed to stop profile '{}'", profile.getName(), e);
            runtime.setLifecycleState(NodeLifecycleState.ERROR);
            runtime.setErrorMessage(e.getMessage());
            notifyStateChanged(profile, NodeLifecycleState.STOPPING, NodeLifecycleState.ERROR);
            notifyError(profile, e.getMessage());
        }
    }

    private void pauseProfileInternal(NodeProfile profile) {
        NodeProfileRuntime runtime = profile.getRuntime();

        NodeLifecycleState oldState = runtime.getLifecycleState();
        if (!runtime.setLifecycleState(NodeLifecycleState.PAUSED)) {
            LOGGER.debug("Cannot pause '{}', current state: {}", profile.getName(), oldState);
            return;
        }

        notifyStateChanged(profile, oldState, NodeLifecycleState.PAUSED);
        runtime.setStatusMessage("Paused");
        LOGGER.info("Profile '{}' marked as paused", profile.getName());
    }

    private void resumeProfileInternal(NodeProfile profile) {
        NodeProfileRuntime runtime = profile.getRuntime();

        NodeLifecycleState oldState = runtime.getLifecycleState();
        if (!runtime.setLifecycleState(NodeLifecycleState.RUNNING)) {
            LOGGER.debug("Cannot resume '{}', current state: {}", profile.getName(), oldState);
            return;
        }

        notifyStateChanged(profile, oldState, NodeLifecycleState.RUNNING);
        runtime.setStatusMessage("Running");
        LOGGER.info("Profile '{}' marked as resumed", profile.getName());
    }

    // ====================================================================
    // Batch operations
    // ====================================================================

    /**
     * Initializes all discovered profiles.
     */
    public void initializeAllProfiles() {
        for (NodeProfile profile : profiles) {
            initializeProfile(profile.getName());
        }
    }

    /**
     * Starts all profiles that have autostart enabled in their .properties config.
     */
    public void startAutostartProfiles() {
        List<NodeProfile> autoStartProfiles = profiles.stream()
                .filter(profile -> {
                    // Read autostart from profile properties (Single Source of Truth)
                    if (!profile.isAutostart()) {
                        return false;
                    }
                    // Only start if the profile is READY (initialized but not running)
                    NodeLifecycleState state = profile.getRuntime().getLifecycleState();
                    return state == NodeLifecycleState.READY || state == NodeLifecycleState.INITIALIZING;
                })
                .collect(Collectors.toList());

        LOGGER.info("Starting {} autostart profiles: {}", autoStartProfiles.size(),
                autoStartProfiles.stream().map(NodeProfile::getName).collect(Collectors.joining(", ")));

        for (NodeProfile profile : autoStartProfiles) {
            startProfile(profile.getName());
        }
    }

    /**
     * Stops all running profiles.
     */
    public void stopAllProfiles() {
        profiles.stream()
                .filter(p -> p.getRuntime().isActive())
                .forEach(p -> stopProfile(p.getName()));
    }

    // ====================================================================
    // Queries
    // ====================================================================

    /**
     * Gets the NodeProfile for a given name, or null if not registered.
     *
     * @param profileName the profile name to look up
     * @return the {@link NodeProfile}, or null if not found
     */
    public NodeProfile getProfile(String profileName) {
        return profiles.stream()
                .filter(p -> p.getName().equals(profileName))
                .findFirst()
                .orElse(null);
    }

    /**
     * Gets all registered profiles as an unmodifiable list.
     *
     * @return unmodifiable list of {@link NodeProfile} objects
     */
    public List<NodeProfile> getAllProfiles() {
        return Collections.unmodifiableList(new ArrayList<>(profiles));
    }

    /**
     * Gets the count of currently running profiles.
     */
    public int getRunningCount() {
        return (int) profiles.stream()
                .map(NodeProfile::getRuntime)
                .filter(NodeProfileRuntime::isActive)
                .count();
    }

    /**
     * Checks if a profile is running.
     */
    public boolean isProfileRunning(String profileName) {
        NodeProfile profile = getProfile(profileName);
        return profile != null && profile.getRuntime().isActive();
    }

    /**
     * Gets the ProfileConfig instance for reading/writing settings.
     */
    public ProfileConfig getProfileConfig() {
        return profileConfig;
    }

    /**
     * Checks if a profile is registered by name.
     *
     * @param profileName the name to check
     * @return true if a profile with this name is registered
     */
    private boolean isProfileRegistered(String profileName) {
        return profiles.stream().anyMatch(p -> p.getName().equals(profileName));
    }

    /**
     * Registers a NodeProfile directly. Package-private for testing purposes.
     * In production, profiles are discovered via {@link #discoverProfiles()}.
     *
     * @param profile the profile to register
     */
    void addProfile(NodeProfile profile) {
        if (profile != null && !isProfileRegistered(profile.getName())) {
            profiles.add(profile);
            LOGGER.debug("Manually registered node profile: {}", profile.getName());
        }
    }

    // ====================================================================
    // Operating substate management (nested state machine within RUNNING)
    // ====================================================================

    /**
     * Reports current sync progress. The manager evaluates hysteresis thresholds
     * and updates the operating substate accordingly.
     * <p>
     * Hysteresis logic (moved from GUI layer to enforce proper architecture):
     * <ul>
     *   <li>SYNC_IDLE -> SYNCING: missingBlocks > thresholdHigh (default: 10)</li>
     *   <li>SYNCING -> SYNC_IDLE: missingBlocks <= thresholdLow (default: 1)</li>
     * </ul>
     * This prevents rapid state oscillation when the node is near the sync boundary.
     *
     * @param profileName   the node profile to update
     * @param missingBlocks number of blocks behind the network
     */
    public void reportSyncProgress(String profileName, long missingBlocks) {
        NodeProfile profile = getProfile(profileName);
        if (profile == null) {
            LOGGER.warn("Cannot report sync progress: profile '{}' not found", profileName);
            return;
        }

        NodeProfileRuntime runtime = profile.getRuntime();

        // Always update the missing blocks count for UI queries
        runtime.setMissingBlocks(missingBlocks);

        NodeOperatingState currentSubstate = runtime.getOperatingState();

        // Don't change substate while paused (user or system)
        if (currentSubstate.isPaused()) {
            LOGGER.debug("Profile '{}' sync progress reported but ignored: currently {}", profileName, currentSubstate);
            return;
        }

        NodeOperatingState newSubstate = evaluateSyncSubstate(runtime, missingBlocks);
        if (newSubstate != currentSubstate) {
            applyOperatingStateTransition(profile, currentSubstate, newSubstate, missingBlocks);
        }
    }

    /**
     * Evaluates what the operating substate should be given the current state
     * and missing block count. Uses hysteresis to prevent rapid oscillation.
     */
    private NodeOperatingState evaluateSyncSubstate(NodeProfileRuntime runtime, long missingBlocks) {
        NodeOperatingState current = runtime.getOperatingState();
        int thresholdHi = runtime.getHysteresisThresholdHi();
        int thresholdLo = runtime.getHysteresisThresholdLo();

        switch (current) {
            case SYNC_IDLE:
            case GENERATING:
                // Transition to SYNCING only when significantly behind
                return missingBlocks > thresholdHi ? NodeOperatingState.SYNCING : current;

            case SYNCING:
                // Transition back to SYNC_IDLE only when essentially caught up
                return missingBlocks <= thresholdLo ? NodeOperatingState.SYNC_IDLE : current;

            default:
                // PAUSED_USER, PAUSED_SYSTEM should not reach here (guarded above)
                return current;
        }
    }

    /**
     * Applies an operating substate transition with proper timing tracking
     * and listener notification.
     */
    private void applyOperatingStateTransition(NodeProfile profile,
                                               NodeOperatingState oldSubstate,
                                               NodeOperatingState newSubstate,
                                               long missingBlocks) {
        NodeProfileRuntime runtime = profile.getRuntime();

        LOGGER.info("Profile '{}' operating state: {} -> {} (missingBlocks={})",
                profile.getName(), oldSubstate, newSubstate, missingBlocks);

        if (newSubstate == NodeOperatingState.SYNCING) {
            // Starting a sync session
            runtime.setSyncStartTime(System.currentTimeMillis());
            runtime.setStatusMessage("Syncing... " + missingBlocks + " blocks behind");
        } else if (oldSubstate == NodeOperatingState.SYNCING && newSubstate == NodeOperatingState.SYNC_IDLE) {
            // Finished a sync session - accumulate time
            long sessionDuration = System.currentTimeMillis() - runtime.getSyncStartTime();
            runtime.setAccumulatedSyncTimeMs(runtime.getAccumulatedSyncTimeMs() + sessionDuration);
            runtime.setSyncEndTime(System.currentTimeMillis());
            runtime.setStatusMessage("Fully synchronized");
        }

        if (runtime.setOperatingState(newSubstate)) {
            notifyOperatingStateChanged(profile, oldSubstate, newSubstate);
        } else {
            LOGGER.warn("Failed to transition operating state for '{}': {} -> {}",
                    profile.getName(), oldSubstate, newSubstate);
        }
    }

    /**
     * Pauses blockchain synchronization by user command (.pause or GUI button).
     *
     * @param profileName the node profile to pause
     */
    public void pauseSyncByUser(String profileName) {
        NodeProfile profile = getProfile(profileName);
        if (profile == null) {
            LOGGER.warn("Cannot pause sync: profile '{}' not found", profileName);
            return;
        }

        NodeProfileRuntime runtime = profile.getRuntime();
        NodeOperatingState current = runtime.getOperatingState();
        if (current.isUserPaused()) {
            LOGGER.debug("Profile '{}' already paused by user", profileName);
            return;
        }

        NodeOperatingState previous = current;
        if (runtime.setOperatingState(NodeOperatingState.PAUSED_USER)) {
            // If we were syncing, accumulate the partial session time
            if (current == NodeOperatingState.SYNCING) {
                long partialSession = System.currentTimeMillis() - runtime.getSyncStartTime();
                runtime.setAccumulatedSyncTimeMs(runtime.getAccumulatedSyncTimeMs() + partialSession);
                runtime.setSyncEndTime(System.currentTimeMillis());
            }
            runtime.setStatusMessage("Sync paused by user");
            notifyOperatingStateChanged(profile, previous, NodeOperatingState.PAUSED_USER);
            LOGGER.info("Profile '{}' sync paused by user", profileName);
        }
    }

    /**
     * Resumes blockchain synchronization after user-initiated pause.
     *
     * @param profileName the node profile to resume
     */
    public void resumeSyncByUser(String profileName) {
        NodeProfile profile = getProfile(profileName);
        if (profile == null) {
            LOGGER.warn("Cannot resume sync: profile '{}' not found", profileName);
            return;
        }

        NodeProfileRuntime runtime = profile.getRuntime();
        NodeOperatingState current = runtime.getOperatingState();
        if (!current.isUserPaused()) {
            LOGGER.debug("Profile '{}' not paused by user, current state: {}", profileName, current);
            return;
        }

        // Resume to SYNCING; reportSyncProgress will correct if already caught up
        if (runtime.setOperatingState(NodeOperatingState.SYNCING)) {
            runtime.setSyncStartTime(System.currentTimeMillis());
            runtime.setStatusMessage("Resuming sync...");
            notifyOperatingStateChanged(profile, current, NodeOperatingState.SYNCING);
            LOGGER.info("Profile '{}' sync resumed by user", profileName);
        }
    }

    /**
     * Temporarily pauses synchronization for a system operation (DB check, pop-off, trim).
     * The previous substate is saved so {@link #resumeSyncBySystem(String)} can restore it.
     *
     * @param profileName the node profile to pause
     * @param reason      description of the system operation causing the pause (for logging)
     */
    public void pauseSyncBySystem(String profileName, String reason) {
        NodeProfile profile = getProfile(profileName);
        if (profile == null) {
            LOGGER.warn("Cannot system-pause sync: profile '{}' not found", profileName);
            return;
        }

        NodeProfileRuntime runtime = profile.getRuntime();
        NodeOperatingState current = runtime.getOperatingState();
        if (current.isSystemPaused()) {
            LOGGER.debug("Profile '{}' already system-paused", profileName);
            return;
        }

        NodeOperatingState previous = current;
        if (runtime.setOperatingState(NodeOperatingState.PAUSED_SYSTEM)) {
            // If we were syncing, accumulate partial session
            if (current == NodeOperatingState.SYNCING) {
                long partialSession = System.currentTimeMillis() - runtime.getSyncStartTime();
                runtime.setAccumulatedSyncTimeMs(runtime.getAccumulatedSyncTimeMs() + partialSession);
                runtime.setSyncEndTime(System.currentTimeMillis());
            }
            runtime.setStatusMessage("System pause: " + reason);
            notifyOperatingStateChanged(profile, previous, NodeOperatingState.PAUSED_SYSTEM);
            LOGGER.info("Profile '{}' sync paused by system: {}", profileName, reason);
        }
    }

    /**
     * Resumes synchronization after a system operation completes.
     * Restores to the appropriate substate based on current sync lag.
     * <p>
     * Note: We cannot use {@link #evaluateSyncSubstate(NodeProfileRuntime, long)} directly
     * because it treats PAUSED_SYSTEM as a default case (no transition). Instead we evaluate
     * the restored state using hysteresis thresholds against the current missing blocks.
     * </p>
     *
     * @param profileName the node profile to resume
     */
    public void resumeSyncBySystem(String profileName) {
        NodeProfile profile = getProfile(profileName);
        if (profile == null) {
            LOGGER.warn("Cannot system-resume sync: profile '{}' not found", profileName);
            return;
        }

        NodeProfileRuntime runtime = profile.getRuntime();
        NodeOperatingState current = runtime.getOperatingState();
        if (!current.isSystemPaused()) {
            LOGGER.debug("Profile '{}' not system-paused, current state: {}", profileName, current);
            return;
        }

        // Restore based on current missing blocks using hysteresis thresholds
        // (evaluateSyncSubstate cannot be used here because PAUSED_SYSTEM falls through
        // its default case and returns the paused state unchanged)
        long missingBlocks = runtime.getMissingBlocks();
        int thresholdHi = runtime.getHysteresisThresholdHi();
        NodeOperatingState restoredState = (missingBlocks > thresholdHi)
                ? NodeOperatingState.SYNCING
                : NodeOperatingState.SYNC_IDLE;

        if (runtime.setOperatingState(restoredState)) {
            if (restoredState == NodeOperatingState.SYNCING) {
                runtime.setSyncStartTime(System.currentTimeMillis());
                runtime.setStatusMessage("Sync resumed... " + missingBlocks + " blocks behind");
            } else {
                runtime.setStatusMessage("Fully synchronized");
            }
            notifyOperatingStateChanged(profile, current, restoredState);
            LOGGER.info("Profile '{}' sync resumed by system: PAUSED_SYSTEM -> {}", profileName, restoredState);
        }
    }

    // ====================================================================
    // Observer pattern - Lifecycle listeners
    // ====================================================================

    /**
     * Registers a listener to receive lifecycle events.
     */
    public void addListener(LifecycleListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a previously registered listener.
     */
    public void removeListener(LifecycleListener listener) {
        listeners.remove(listener);
    }

    /**
     * Resets the singleton. Use only for testing.
     * Only stops profiles that have an active Signum or NodeCoreContext (i.e., were truly started).
     * Profiles that were merely registered but never started are safely ignored.
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.getAllProfiles().forEach(profile -> {
                try {
                    // Only stop profiles with an active Signum or context to avoid NPE
                    // when neither was initialized
                    NodeProfileRuntime runtime = profile.getRuntime();
                    if (runtime.getSignum() != null || runtime.getCoreContext() != null) {
                        instance.stopProfile(profile.getName());
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to stop profile {} during reset", profile.getName(), e);
                }
            });
        }
        instance = null;
    }

    private void notifyStateChanged(NodeProfile profile, NodeLifecycleState oldState, NodeLifecycleState newState) {
        for (LifecycleListener listener : listeners) {
            try {
                listener.onStateChanged(profile, oldState, newState);
            } catch (Exception e) {
                LOGGER.error("Error notifying listener {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    private void notifyOperatingStateChanged(NodeProfile profile,
                                             NodeOperatingState oldSubstate,
                                             NodeOperatingState newSubstate) {
        for (LifecycleListener listener : listeners) {
            try {
                listener.onOperatingStateChanged(profile, oldSubstate, newSubstate);
            } catch (Exception e) {
                LOGGER.error("Error notifying operating state change to {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    private void notifyStatusMessage(NodeProfile profile, String message) {
        for (LifecycleListener listener : listeners) {
            try {
                listener.onStatusMessage(profile, message);
            } catch (Exception e) {
                LOGGER.error("Error notifying listener {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    private void notifyError(NodeProfile profile, String errorMessage) {
        for (LifecycleListener listener : listeners) {
            try {
                listener.onError(profile, errorMessage);
            } catch (Exception e) {
                LOGGER.error("Error notifying listener {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * Notifies all listeners that a profile is about to be stopped.
     * Listeners can use this to save GUI state, persist settings, etc.
     */
    private void notifyShutdownRequested(NodeProfile profile) {
        for (LifecycleListener listener : listeners) {
            try {
                listener.onShutdownRequested(profile);
            } catch (Exception e) {
                LOGGER.error("Error notifying shutdown requested to {}", listener.getClass().getSimpleName(), e);
            }
        }
    }
}