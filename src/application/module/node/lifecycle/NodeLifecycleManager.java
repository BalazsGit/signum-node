package application.module.node.lifecycle;

import application.module.node.profile.NodeProfile;
import application.module.node.profile.ProfileConfig;
import application.module.node.instance.NodeCoreContext;
import application.module.node.instance.NodeCoreContextBuilder;
import application.module.node.instance.NodeCoreContextManager;
import application.utils.logging.ProfileThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central manager for Node profile lifecycle operations.
 * Handles initialization, start, stop, pause and state tracking per profile.
 * Follows the Observer pattern: GUI panels register as LifecycleListeners to
 * receive status updates.
 *
 * This class separates business logic (node startup/shutdown) from the GUI
 * layer, enabling clean headless mode support and testability.
 */
public class NodeLifecycleManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeLifecycleManager.class);

    /**
     * Singleton instance - accessed via getInstance().
     */
    private static volatile NodeLifecycleManager instance;

    private final Map<String, NodeInstanceInfo> profiles;
    private final List<LifecycleListener> listeners;
    private final ProfileConfig profileConfig;

    private NodeLifecycleManager() {
        this.profiles = new LinkedHashMap<>();
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
    // Lifecycle operations
    // ====================================================================

    /**
     * Discovers all node profiles from conf/node/*.properties and registers them.
     */
    public void discoverProfiles() {
        NodeProfile[] discoveredProfiles = NodeProfile.loadAll();
        for (NodeProfile profile : discoveredProfiles) {
            if (!profiles.containsKey(profile.getName())) {
                NodeInstanceInfo info = new NodeInstanceInfo(profile.getName());
                profiles.put(profile.getName(), info);
                LOGGER.debug("Registered node profile: {}", profile.getName());
            }
        }
        profileConfig.load();
    }

    /**
     * Registers a single profile without discovery.
     */
    public void registerProfile(String profileName) {
        NodeInstanceInfo info = new NodeInstanceInfo(profileName);
        profiles.put(profileName, info);
        LOGGER.debug("Registered node profile: {}", profileName);
    }

    /**
     * Initializes a profile (loads config, prepares resources - no side effects).
     */
    public void initializeProfile(String profileName) {
        NodeInstanceInfo info = getProfileStatus(profileName);
        if (info == null) {
            LOGGER.warn("Profile '{}' not found", profileName);
            return;
        }

        NodeLifecycleState oldState = info.getState();
        if (!info.setState(NodeLifecycleState.INITIALIZING)) {
            LOGGER.debug("Cannot initialize '{}', current state: {}", profileName, oldState);
            return;
        }

        notifyStateChanged(info, oldState, NodeLifecycleState.INITIALIZING);
        info.setStatusMessage("Initializing...");

        try {
            // Load profile properties for lightweight validation
            NodeProfile profile = NodeProfile.loadByName(profileName);
            if (profile != null) {
                String apiPortStr = profile.getProperty("httpport", "8125");
                try {
                    info.setApiPort(Integer.parseInt(apiPortStr));
                } catch (NumberFormatException ignored) {
                }
                String p2pPortStr = profile.getProperty("peer.port", "8123");
                try {
                    info.setP2pPort(Integer.parseInt(p2pPortStr));
                } catch (NumberFormatException ignored) {
                }
            }

            info.setState(NodeLifecycleState.READY);
            info.setStatusMessage("Ready to start");
            notifyStateChanged(info, NodeLifecycleState.INITIALIZING, NodeLifecycleState.READY);
            LOGGER.info("Profile '{}' initialized successfully", profileName);
        } catch (Exception e) {
            LOGGER.error("Failed to initialize profile '{}'", profileName, e);
            info.setState(NodeLifecycleState.ERROR);
            info.setErrorMessage(e.getMessage());
            notifyStateChanged(info, NodeLifecycleState.INITIALIZING, NodeLifecycleState.ERROR);
            notifyError(info, e.getMessage());
        }
    }

    /**
     * Starts the node for a profile using NodeCoreContext.
     * The actual initialization runs asynchronously in a background thread to
     * avoid blocking the caller (e.g., GUI). Lifecycle listeners are notified
     * when startup completes or fails.
     */
    public void startProfile(String profileName) {
        NodeInstanceInfo info = getProfileStatus(profileName);
        if (info == null) {
            LOGGER.warn("Profile '{}' not found", profileName);
            return;
        }

        // Check max concurrent nodes
        long runningCount = profiles.values().stream()
                .filter(NodeInstanceInfo::isActive).count();
        int maxConcurrent = profileConfig.getMaxConcurrentNodes();
        if (runningCount >= maxConcurrent) {
            String errorMsg = "Maximum concurrent nodes (" + maxConcurrent + ") reached. Stop another node first.";
            LOGGER.warn(errorMsg);
            info.setState(NodeLifecycleState.ERROR);
            info.setErrorMessage(errorMsg);
            notifyError(info, errorMsg);
            return;
        }

        NodeLifecycleState oldState = info.getState();
        if (!info.setState(NodeLifecycleState.RUNNING)) {
            LOGGER.debug("Cannot start '{}', current state: {}", profileName, oldState);
            return;
        }

        notifyStateChanged(info, oldState, NodeLifecycleState.RUNNING);
        info.setStatusMessage("Starting node...");
        info.markStarted();

        // Resolve profile configuration (final for lambda capture)
        final String confFolderPath;
        NodeProfile profile = NodeProfile.loadByName(profileName);
        if (profile != null) {
            confFolderPath = profile.getProperty("conf.folder", "conf");
        } else {
            confFolderPath = "conf";
        }

        // Build the NodeCoreContext asynchronously to avoid blocking the caller
        // (e.g., GUI thread). Use a dedicated daemon thread.
        // Wrap with ProfileThreadContext so all logs emitted during startup are
        // routed to the correct profile's console panel via MDC routing.
        Runnable startupTask = () -> {
            try {
                NodeCoreContext context = new NodeCoreContextBuilder(profileName, Paths.get(confFolderPath)).build();
                context.start();

                // Register with the global manager and store reference in info
                NodeCoreContextManager.getInstance().register(profileName, context);
                info.setCoreContext(context);

                info.setStatusMessage("Running");
                notifyStatusMessage(info, "Node started successfully");
                LOGGER.info("Profile '{}' started with NodeCoreContext", profileName);
            } catch (Exception e) {
                LOGGER.error("Failed to start profile '{}'", profileName, e);
                info.setState(NodeLifecycleState.ERROR);
                info.setErrorMessage(e.getMessage());
                notifyStateChanged(info, NodeLifecycleState.RUNNING, NodeLifecycleState.ERROR);
                notifyError(info, e.getMessage());
            }
        };

        Thread starterThread = new Thread(
                ProfileThreadContext.wrap(startupTask, "node", profileName),
                "Node-Starter-" + profileName
        );
        starterThread.setDaemon(true);
        starterThread.start();
    }

    /**
     * Stops the node for a profile gracefully.
     * Delegates to the NodeCoreContext.stop() if a context exists,
     * otherwise performs a no-op (profile was never truly started).
     */
    public void stopProfile(String profileName) {
        NodeInstanceInfo info = getProfileStatus(profileName);
        if (info == null) {
            LOGGER.warn("Profile '{}' not found", profileName);
            return;
        }

        NodeLifecycleState oldState = info.getState();
        if (!info.setState(NodeLifecycleState.STOPPING)) {
            LOGGER.debug("Cannot stop '{}', current state: {}", profileName, oldState);
            return;
        }

        notifyStateChanged(info, oldState, NodeLifecycleState.STOPPING);
        info.setStatusMessage("Stopping...");

        try {
            NodeCoreContext context = info.getCoreContext();
            if (context != null) {
                context.stop();
                NodeCoreContextManager.getInstance().unregister(profileName);
                info.setCoreContext(null);
            } else {
                LOGGER.debug("Profile '{}' had no running context, skipping shutdown", profileName);
            }

            info.setState(NodeLifecycleState.STOPPED);
            info.markStopped();
            info.setStatusMessage("Stopped");
            notifyStateChanged(info, NodeLifecycleState.STOPPING, NodeLifecycleState.STOPPED);
            LOGGER.info("Profile '{}' stopped", profileName);
        } catch (Exception e) {
            LOGGER.error("Failed to stop profile '{}'", profileName, e);
            info.setState(NodeLifecycleState.ERROR);
            info.setErrorMessage(e.getMessage());
            notifyStateChanged(info, NodeLifecycleState.STOPPING, NodeLifecycleState.ERROR);
            notifyError(info, e.getMessage());
        }
    }

    /**
     * Pauses block processing for a running node.
     */
    public void pauseProfile(String profileName) {
        NodeInstanceInfo info = getProfileStatus(profileName);
        if (info == null) {
            return;
        }

        NodeLifecycleState oldState = info.getState();
        if (!info.setState(NodeLifecycleState.PAUSED)) {
            LOGGER.debug("Cannot pause '{}', current state: {}", profileName, oldState);
            return;
        }

        notifyStateChanged(info, oldState, NodeLifecycleState.PAUSED);
        info.setStatusMessage("Paused");
        LOGGER.info("Profile '{}' marked as paused", profileName);
    }

    /**
     * Resumes block processing for a paused node.
     */
    public void resumeProfile(String profileName) {
        NodeInstanceInfo info = getProfileStatus(profileName);
        if (info == null) {
            return;
        }

        NodeLifecycleState oldState = info.getState();
        if (!info.setState(NodeLifecycleState.RUNNING)) {
            LOGGER.debug("Cannot resume '{}', current state: {}", profileName, oldState);
            return;
        }

        notifyStateChanged(info, oldState, NodeLifecycleState.RUNNING);
        info.setStatusMessage("Running");
        LOGGER.info("Profile '{}' marked as resumed", profileName);
    }

    // ====================================================================
    // Batch operations
    // ====================================================================

    /**
     * Initializes all discovered profiles.
     */
    public void initializeAllProfiles() {
        for (String profileName : profiles.keySet()) {
            initializeProfile(profileName);
        }
    }

    /**
     * Starts all profiles that have autostart enabled in profiles.json.
     */
    public void startAutostartProfiles() {
        List<String> autoStartNames = profileConfig.getAutoStartProfileNames();
        LOGGER.info("Starting {} autostart profiles", autoStartNames.size());

        for (String profileName : autoStartNames) {
            NodeInstanceInfo info = getProfileStatus(profileName);
            if (info != null && info.getState() == NodeLifecycleState.READY) {
                startProfile(profileName);
            }
        }
    }

    /**
     * Stops all running profiles.
     */
    public void stopAllProfiles() {
        profiles.values().stream()
                .filter(NodeInstanceInfo::isActive)
                .forEach(info -> stopProfile(info.getProfileName()));
    }

    // ====================================================================
    // Queries
    // ====================================================================

    /**
     * Gets the status of a specific profile.
     */
    public NodeInstanceInfo getProfileStatus(String profileName) {
        return profiles.get(profileName);
    }

    /**
     * Gets all registered profiles.
     */
    public List<NodeInstanceInfo> getAllProfiles() {
        return new ArrayList<>(profiles.values());
    }

    /**
     * Gets the count of currently running profiles.
     */
    public int getRunningCount() {
        return (int) profiles.values().stream().filter(NodeInstanceInfo::isActive).count();
    }

    /**
     * Checks if a profile is running.
     */
    public boolean isProfileRunning(String profileName) {
        NodeInstanceInfo info = profiles.get(profileName);
        return info != null && info.isActive();
    }

    /**
     * Gets the ProfileConfig instance for reading/writing settings.
     */
    public ProfileConfig getProfileConfig() {
        return profileConfig;
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
     *   <li>SYNC_IDLE → SYNCING: missingBlocks > thresholdHigh (default: 10)</li>
     *   <li>SYNCING → SYNC_IDLE: missingBlocks <= thresholdLow (default: 1)</li>
     * </ul>
     * This prevents rapid state oscillation when the node is near the sync boundary.
     *
     * @param profileName   the node profile to update
     * @param missingBlocks number of blocks behind the network
     */
    public void reportSyncProgress(String profileName, long missingBlocks) {
        NodeInstanceInfo info = getProfileStatus(profileName);
        if (info == null) {
            LOGGER.warn("Cannot report sync progress: profile '{}' not found", profileName);
            return;
        }

        // Always update the missing blocks count for UI queries
        info.setMissingBlocks(missingBlocks);

        NodeOperatingState currentSubstate = info.getOperatingState();

        // Don't change substate while paused (user or system)
        if (currentSubstate.isPaused()) {
            LOGGER.debug("Profile '{}' sync progress reported but ignored: currently {}", profileName, currentSubstate);
            return;
        }

        NodeOperatingState newSubstate = evaluateSyncSubstate(info, missingBlocks);
        if (newSubstate != currentSubstate) {
            applyOperatingStateTransition(info, currentSubstate, newSubstate, missingBlocks);
        }
    }

    /**
     * Evaluates what the operating substate should be given the current state
     * and missing block count. Uses hysteresis to prevent rapid oscillation.
     */
    private NodeOperatingState evaluateSyncSubstate(NodeInstanceInfo info, long missingBlocks) {
        NodeOperatingState current = info.getOperatingState();
        int thresholdHi = info.getHysteresisThresholdHi();
        int thresholdLo = info.getHysteresisThresholdLo();

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
    private void applyOperatingStateTransition(NodeInstanceInfo info,
                                               NodeOperatingState oldSubstate,
                                               NodeOperatingState newSubstate,
                                               long missingBlocks) {
        LOGGER.info("Profile '{}' operating state: {} → {} (missingBlocks={})",
                info.getProfileName(), oldSubstate, newSubstate, missingBlocks);

        if (newSubstate == NodeOperatingState.SYNCING) {
            // Starting a sync session
            info.setSyncStartTime(System.currentTimeMillis());
            info.setStatusMessage("Syncing... " + missingBlocks + " blocks behind");
        } else if (oldSubstate == NodeOperatingState.SYNCING && newSubstate == NodeOperatingState.SYNC_IDLE) {
            // Finished a sync session - accumulate time
            long sessionDuration = System.currentTimeMillis() - info.getSyncStartTime();
            info.setAccumulatedSyncTimeMs(info.getAccumulatedSyncTimeMs() + sessionDuration);
            info.setSyncEndTime(System.currentTimeMillis());
            info.setStatusMessage("Fully synchronized");
        }

        if (info.setOperatingState(newSubstate)) {
            notifyOperatingStateChanged(info, oldSubstate, newSubstate);
        } else {
            LOGGER.warn("Failed to transition operating state for '{}': {} → {}",
                    info.getProfileName(), oldSubstate, newSubstate);
        }
    }

    /**
     * Pauses blockchain synchronization by user command (.pause or GUI button).
     *
     * @param profileName the node profile to pause
     */
    public void pauseSyncByUser(String profileName) {
        NodeInstanceInfo info = getProfileStatus(profileName);
        if (info == null) {
            LOGGER.warn("Cannot pause sync: profile '{}' not found", profileName);
            return;
        }

        NodeOperatingState current = info.getOperatingState();
        if (current.isUserPaused()) {
            LOGGER.debug("Profile '{}' already paused by user", profileName);
            return;
        }

        NodeOperatingState previous = current;
        if (info.setOperatingState(NodeOperatingState.PAUSED_USER)) {
            // If we were syncing, accumulate the partial session time
            if (current == NodeOperatingState.SYNCING) {
                long partialSession = System.currentTimeMillis() - info.getSyncStartTime();
                info.setAccumulatedSyncTimeMs(info.getAccumulatedSyncTimeMs() + partialSession);
                info.setSyncEndTime(System.currentTimeMillis());
            }
            info.setStatusMessage("Sync paused by user");
            notifyOperatingStateChanged(info, previous, NodeOperatingState.PAUSED_USER);
            LOGGER.info("Profile '{}' sync paused by user", profileName);
        }
    }

    /**
     * Resumes blockchain synchronization after user-initiated pause.
     *
     * @param profileName the node profile to resume
     */
    public void resumeSyncByUser(String profileName) {
        NodeInstanceInfo info = getProfileStatus(profileName);
        if (info == null) {
            LOGGER.warn("Cannot resume sync: profile '{}' not found", profileName);
            return;
        }

        NodeOperatingState current = info.getOperatingState();
        if (!current.isUserPaused()) {
            LOGGER.debug("Profile '{}' not paused by user, current state: {}", profileName, current);
            return;
        }

        // Resume to SYNCING; reportSyncProgress will correct if already caught up
        if (info.setOperatingState(NodeOperatingState.SYNCING)) {
            info.setSyncStartTime(System.currentTimeMillis());
            info.setStatusMessage("Resuming sync...");
            notifyOperatingStateChanged(info, current, NodeOperatingState.SYNCING);
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
        NodeInstanceInfo info = getProfileStatus(profileName);
        if (info == null) {
            LOGGER.warn("Cannot system-pause sync: profile '{}' not found", profileName);
            return;
        }

        NodeOperatingState current = info.getOperatingState();
        if (current.isSystemPaused()) {
            LOGGER.debug("Profile '{}' already system-paused", profileName);
            return;
        }

        NodeOperatingState previous = current;
        if (info.setOperatingState(NodeOperatingState.PAUSED_SYSTEM)) {
            // If we were syncing, accumulate partial session
            if (current == NodeOperatingState.SYNCING) {
                long partialSession = System.currentTimeMillis() - info.getSyncStartTime();
                info.setAccumulatedSyncTimeMs(info.getAccumulatedSyncTimeMs() + partialSession);
                info.setSyncEndTime(System.currentTimeMillis());
            }
            info.setStatusMessage("System pause: " + reason);
            notifyOperatingStateChanged(info, previous, NodeOperatingState.PAUSED_SYSTEM);
            LOGGER.info("Profile '{}' sync paused by system: {}", profileName, reason);
        }
    }

    /**
     * Resumes synchronization after a system operation completes.
     * Restores to the appropriate substate based on current sync lag.
     *
     * @param profileName the node profile to resume
     */
    public void resumeSyncBySystem(String profileName) {
        NodeInstanceInfo info = getProfileStatus(profileName);
        if (info == null) {
            LOGGER.warn("Cannot system-resume sync: profile '{}' not found", profileName);
            return;
        }

        NodeOperatingState current = info.getOperatingState();
        if (!current.isSystemPaused()) {
            LOGGER.debug("Profile '{}' not system-paused, current state: {}", profileName, current);
            return;
        }

        // Restore based on current missing blocks (same logic as reportSyncProgress)
        long missingBlocks = info.getMissingBlocks();
        NodeOperatingState restoredState = evaluateSyncSubstate(info, missingBlocks);
        // Temporarily set current to allow transition from PAUSED_SYSTEM
        info.forceOperatingState(current); // no-op, just ensure consistency

        if (info.setOperatingState(restoredState)) {
            if (restoredState == NodeOperatingState.SYNCING) {
                info.setSyncStartTime(System.currentTimeMillis());
                info.setStatusMessage("Sync resumed... " + missingBlocks + " blocks behind");
            } else {
                info.setStatusMessage("Fully synchronized");
            }
            notifyOperatingStateChanged(info, current, restoredState);
            LOGGER.info("Profile '{}' sync resumed by system: PAUSED_SYSTEM → {}", profileName, restoredState);
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
     * Only stops profiles that have an active NodeCoreContext (i.e., were truly started).
     * Profiles that were merely registered but never started are safely ignored.
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.getAllProfiles().forEach(info -> {
                try {
                    // Only stop profiles with an active context to avoid NPE
                    // when Signum was never initialized
                    if (info.getCoreContext() != null) {
                        instance.stopProfile(info.getProfileName());
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to stop profile {} during reset", info.getProfileName(), e);
                }
            });
        }
        instance = null;
        NodeCoreContextManager.resetInstance();
    }

    private void notifyStateChanged(NodeInstanceInfo info, NodeLifecycleState oldState, NodeLifecycleState newState) {
        for (LifecycleListener listener : listeners) {
            try {
                listener.onStateChanged(info, oldState, newState);
            } catch (Exception e) {
                LOGGER.error("Error notifying listener {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    private void notifyOperatingStateChanged(NodeInstanceInfo info,
                                             NodeOperatingState oldSubstate,
                                             NodeOperatingState newSubstate) {
        for (LifecycleListener listener : listeners) {
            try {
                listener.onOperatingStateChanged(info, oldSubstate, newSubstate);
            } catch (Exception e) {
                LOGGER.error("Error notifying operating state change to {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    private void notifyStatusMessage(NodeInstanceInfo info, String message) {
        for (LifecycleListener listener : listeners) {
            try {
                listener.onStatusMessage(info, message);
            } catch (Exception e) {
                LOGGER.error("Error notifying listener {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    private void notifyError(NodeInstanceInfo info, String errorMessage) {
        for (LifecycleListener listener : listeners) {
            try {
                listener.onError(info, errorMessage);
            } catch (Exception e) {
                LOGGER.error("Error notifying listener {}", listener.getClass().getSimpleName(), e);
            }
        }
    }
}