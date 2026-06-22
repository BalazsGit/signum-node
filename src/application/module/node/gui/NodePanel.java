package application.module.node.gui;

import application.module.appearance.AppearanceModule;
import application.module.node.lifecycle.LifecycleListener;
import application.module.node.lifecycle.NodeInstanceInfo;
import application.module.node.lifecycle.NodeLifecycleManager;
import application.module.node.lifecycle.NodeLifecycleState;
import application.module.node.profile.NodeProfile;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiFontManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Main Node panel that acts as a JTabbedPane container.
 * Discovers all NodeProfile configurations and creates lightweight placeholder tabs.
 * Heavy NodeProfilePanel instances are lazy-loaded on first tab selection.
 *
 * Integrates with NodeLifecycleManager for push-based lifecycle notifications.
 */
@SuppressWarnings("serial")
public class NodePanel extends JPanel implements LifecycleListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodePanel.class);

    private final JFrame parentFrame;
    private final JTabbedPane profileTabbedPane;
    /** Maps profile name -> actual NodeProfilePanel (after lazy-load) */
    private final Map<String, NodeProfilePanel> loadedProfilePanels;
    /** Tracks which placeholders have been replaced */
    private final Map<String, Boolean> placeholderReplaced;
    /** Singleton lifecycle manager */
    private final NodeLifecycleManager lifecycleManager;

    /**
     * Creates the main Node panel with lazy-loaded profile tabs.
     *
     * @param parentFrame The parent JFrame for dialogs
     */
    public NodePanel(JFrame parentFrame) {
        this.parentFrame = parentFrame;
        this.lifecycleManager = NodeLifecycleManager.getInstance();
        this.loadedProfilePanels = new LinkedHashMap<>();
        this.placeholderReplaced = new LinkedHashMap<>();

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        profileTabbedPane = new JTabbedPane(SwingConstants.TOP) {
            @Override
            public void setSelectedIndex(int index) {
                super.setSelectedIndex(index);
                // Trigger lazy-load on tab selection
                String selectedProfileName = getTitleAt(0); // first title is the key
                checkAndReplacePlaceholder();
            }
        };
        GuiFontManager.applyDefaultFont(profileTabbedPane);
        add(profileTabbedPane, BorderLayout.CENTER);

        // Discover profiles via lifecycle manager
        lifecycleManager.discoverProfiles();

        // Create placeholder tabs for each profile
        lifecycleManager.getAllProfiles().forEach(info -> {
            createPlaceholderTab(info.getProfileName());
        });

        if (profileTabbedPane.getTabCount() == 0) {
            LOGGER.warn("No node profiles found");
            NodeProfile defaultProfile = new NodeProfile("default");
            createPlaceholderTab(defaultProfile.getName());
            lifecycleManager.registerProfile("default");
        }

        // Register as lifecycle listener for push-based updates
        lifecycleManager.addListener(this);

        // Initialize all profiles (lightweight, no side effects)
        lifecycleManager.initializeAllProfiles();

        // Start autostart profiles if configured
        lifecycleManager.startAutostartProfiles();

        // Register for appearance updates
        AppearanceModule.registerAppearanceListener(() -> {
            GuiFontManager.applyDefaultFont(profileTabbedPane);
        });

        LOGGER.info("NodePanel created with {} profile tabs", profileTabbedPane.getTabCount());
    }

    /**
     * Creates a lightweight placeholder tab for a profile.
     */
    private void createPlaceholderTab(String profileName) {
        NodePlaceholderPanel placeholder = new NodePlaceholderPanel(profileName, () -> {
            // This callback is triggered when the placeholder becomes visible
            SwingUtilities.invokeLater(() -> checkAndReplacePlaceholder());
        });

        placeholderReplaced.put(profileName, false);
        profileTabbedPane.addTab(profileName, placeholder);

        LOGGER.debug("Created placeholder tab for profile: {}", profileName);
    }

    /**
     * Checks if the currently selected tab is a placeholder and replaces it
     * with the actual NodeProfilePanel (lazy-loading).
     */
    private void checkAndReplacePlaceholder() {
        int selectedIndex = profileTabbedPane.getSelectedIndex();
        if (selectedIndex < 0) {
            return;
        }

        String profileName = profileTabbedPane.getTitleAt(selectedIndex);
        
        if (Boolean.TRUE.equals(placeholderReplaced.get(profileName))) {
            return; // Already loaded
        }

        LOGGER.info("Lazy-loading profile panel for: {}", profileName);

        // Load the profile and create the actual panel
        NodeProfile profile = NodeProfile.loadByName(profileName);
        if (profile == null) {
            // Create empty profile if file doesn't exist
            profile = new NodeProfile(profileName);
        }

        NodeProfilePanel actualPanel = new NodeProfilePanel(parentFrame, profile);
        loadedProfilePanels.put(profileName, actualPanel);

        // Replace placeholder with actual panel
        Component oldComponent = profileTabbedPane.getComponentAt(selectedIndex);
        if (oldComponent instanceof NodePlaceholderPanel) {
            ((NodePlaceholderPanel) oldComponent).markAsLoaded();
        }

        profileTabbedPane.setComponentAt(selectedIndex, actualPanel);
        placeholderReplaced.put(profileName, true);

        LOGGER.info("Profile panel loaded for: {}", profileName);
    }

    // ====================================================================
    // LifecycleListener implementation (push-based)
    // ====================================================================

    @Override
    public void onStateChanged(NodeInstanceInfo instanceInfo, NodeLifecycleState oldState, NodeLifecycleState newState) {
        SwingUtilities.invokeLater(() -> {
            NodeProfilePanel panel = loadedProfilePanels.get(instanceInfo.getProfileName());
            if (panel != null) {
                panel.onNodeStateChanged(oldState, newState);
            }
            updateTabIcon(instanceInfo.getProfileName(), newState);
            LOGGER.info("State change: {} -> {} for profile {}", oldState, newState, instanceInfo.getProfileName());
        });
    }

    @Override
    public void onStatusMessage(NodeInstanceInfo instanceInfo, String message) {
        SwingUtilities.invokeLater(() -> {
            NodeProfilePanel panel = loadedProfilePanels.get(instanceInfo.getProfileName());
            if (panel != null) {
                panel.onStatusMessage(message);
            }
            LOGGER.debug("Status [{}]: {}", instanceInfo.getProfileName(), message);
        });
    }

    @Override
    public void onError(NodeInstanceInfo instanceInfo, String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            NodeProfilePanel panel = loadedProfilePanels.get(instanceInfo.getProfileName());
            if (panel != null) {
                panel.onError(errorMessage);
            }
            LOGGER.error("Error [{}]: {}", instanceInfo.getProfileName(), errorMessage);
        });
    }

    /**
     * Updates the tab icon based on the node state.
     */
    private void updateTabIcon(String profileName, NodeLifecycleState state) {
        for (int i = 0; i < profileTabbedPane.getTabCount(); i++) {
            if (profileTabbedPane.getTitleAt(i).equals(profileName)) {
                Icon icon = null;
                if (state == NodeLifecycleState.ERROR) {
                    // Use tab text with error indicator
                    profileTabbedPane.setTitleAt(i, profileName + " ⚠");
                } else {
                    profileTabbedPane.setTitleAt(i, profileName);
                }
                profileTabbedPane.setIconAt(i, icon);
                break;
            }
        }
    }

    // ====================================================================
    // Public API
    // ====================================================================

    /**
     * Gets the NodeProfilePanel for a specific profile (null if not yet loaded).
     */
    public NodeProfilePanel getProfilePanel(String profileName) {
        return loadedProfilePanels.get(profileName);
    }

    /**
     * Gets all currently loaded profile panels.
     */
    public Map<String, NodeProfilePanel> getAllLoadedPanels() {
        return new LinkedHashMap<>(loadedProfilePanels);
    }

    /**
     * Gets the profile tabbed pane.
     */
    public JTabbedPane getProfileTabbedPane() {
        return profileTabbedPane;
    }

    /**
     * Gets the parent JFrame.
     */
    public JFrame getParentFrame() {
        return parentFrame;
    }

    /**
     * Gets the lifecycle manager instance.
     */
    public NodeLifecycleManager getLifecycleManager() {
        return lifecycleManager;
    }

    /**
     * Cleanup: unregister listener when panel is closed.
     */
    public void dispose() {
        lifecycleManager.removeListener(this);
        LOGGER.info("NodePanel disposed, listener unregistered");
    }
}