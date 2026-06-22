package application.module.node.lifecycle;

import application.module.node.Signum;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.ProfileConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /**
     * Resets the singleton. Use only for testing.
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.getAllProfiles().forEach(info -> {
                try {
                    instance.stopProfile(info.getProfileName());
                } catch (Exception e) {
                    LOGGER.warn("Failed to stop profile {} during reset", info.getProfileName(), e);
                }
            });
        }
        instance = null;
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
     * Starts the node for a profile. This delegates to Signum core.
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

        try {
            // Start Signum in a background thread - only the first call actually
            // initializes
            NodeProfile profile = NodeProfile.loadByName(profileName);
            if (profile != null) {
                String confFolder = profile.getProperty("conf.folder", "conf");
                String[] args = new String[] { "--c", confFolder };

                Thread startThread = new Thread(() -> Signum.main(args), "Node-" + profileName);
                startThread.setDaemon(true);
                startThread.start();
            }

            info.setStatusMessage("Running");
            notifyStatusMessage(info, "Node started successfully");
            LOGGER.info("Profile '{}' started", profileName);
        } catch (Exception e) {
            LOGGER.error("Failed to start profile '{}'", profileName, e);
            info.setState(NodeLifecycleState.ERROR);
            info.setErrorMessage(e.getMessage());
            notifyStateChanged(info, NodeLifecycleState.RUNNING, NodeLifecycleState.ERROR);
            notifyError(info, e.getMessage());
        }
    }

    /**
     * Stops the node for a profile gracefully.
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
            Signum.shutdownNode();
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

    private void notifyStateChanged(NodeInstanceInfo info, NodeLifecycleState oldState, NodeLifecycleState newState) {
        for (LifecycleListener listener : listeners) {
            try {
                listener.onStateChanged(info, oldState, newState);
            } catch (Exception e) {
                LOGGER.error("Error notifying listener {}", listener.getClass().getSimpleName(), e);
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