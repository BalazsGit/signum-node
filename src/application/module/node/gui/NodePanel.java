package application.module.node.gui;

import application.module.appearance.AppearanceModule;
import application.module.node.lifecycle.LifecycleListener;
import application.module.node.lifecycle.NodeInstanceInfo;
import application.module.node.lifecycle.NodeLifecycleManager;
import application.module.node.lifecycle.NodeLifecycleState;
import application.module.node.profile.NodeProfile;
import application.utils.gui.GuiFontManager;
import application.utils.gui.GuiIcons;
import application.utils.gui.GuiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Main Node panel that acts as a JTabbedPane container.
 * Dynamically loads profiles asynchronously with progress feedback.
 * Heavy NodeProfilePanel instances are lazy-loaded on first tab selection.
 *
 * Integrates with NodeLifecycleManager for push-based lifecycle notifications.
 */
@SuppressWarnings("serial")
public class NodePanel extends JPanel implements LifecycleListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodePanel.class);

    private JTabbedPane profileTabbedPane;
    private JProgressBar progressBar;
    private JLabel statusLabel;

    /** Maps profile name -> actual NodeProfilePanel (after lazy-load) */
    private final Map<String, NodeProfilePanel> loadedProfilePanels = new LinkedHashMap<>();
    /** Tracks which placeholders have been replaced */
    private final Map<String, Boolean> placeholderReplaced = new LinkedHashMap<>();
    /** Reverse lookup: profile name -> tab index for O(1) access by name */
    private final Map<String, Integer> profileNameToTabIndex = new LinkedHashMap<>();
    /** Singleton lifecycle manager */
    private final NodeLifecycleManager lifecycleManager = NodeLifecycleManager.getInstance();

    /**
     * Creates the main Node panel with dynamic profile loading and progress feedback.
     */
    public NodePanel() {
        initialize();
    }

    /**
     * Backward-compatible constructor accepting a parent JFrame (ignored, kept for API compatibility).
     * @param parentFrame Parent frame (deprecated, no longer used)
     */
    public NodePanel(javax.swing.JFrame parentFrame) {
        initialize();
    }

    /**
     * Common initialization logic for all constructors.
     */
    private void initialize() {

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Create header panel with progress bar
        JPanel headerPanel = createHeaderPanel();
        add(headerPanel, BorderLayout.NORTH);

        // Create tabbed pane for profiles with application-wide tab layout policy.
        // Policy is read from GuiManager which loads from gui-settings.json at startup.
        this.profileTabbedPane = new JTabbedPane(SwingConstants.TOP) {
            @Override
            public void setSelectedIndex(int index) {
                super.setSelectedIndex(index);
                checkAndReplacePlaceholder();
            }
        };
        // Apply application-wide tab layout policy from GuiManager (global, not explicit)
        GuiUtils.applyDefaultTabLayoutPolicy(profileTabbedPane);
        GuiFontManager.applyDefaultFont(profileTabbedPane);
        add(profileTabbedPane, BorderLayout.CENTER);

        // Register as lifecycle listener for push-based updates
        lifecycleManager.addListener(this);

        // Register for appearance updates
        AppearanceModule.registerAppearanceListener(() -> {
            GuiFontManager.applyDefaultFont(profileTabbedPane);
            GuiFontManager.applyDefaultFont(statusLabel);
        });

        // Start async profile loading
        startAsyncProfileLoading();

        LOGGER.info("NodePanel created, starting async profile loading");
    }

    /**
     * Creates the header panel containing status label and progress bar.
     */
    private JPanel createHeaderPanel() {
        this.statusLabel = new JLabel("Loading profiles...");
        GuiFontManager.applyDefaultFont(statusLabel);

        this.progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("");
        progressBar.setPreferredSize(new java.awt.Dimension(200, 20));

        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.X_AXIS));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));

        statusLabel.setAlignmentY(CENTER_ALIGNMENT);
        progressBar.setAlignmentY(CENTER_ALIGNMENT);

        headerPanel.add(statusLabel);
        headerPanel.add(Box.createHorizontalStrut(15));
        headerPanel.add(Box.createHorizontalGlue());
        headerPanel.add(progressBar);

        return headerPanel;
    }

    /**
     * Starts the async profile loading process in a background thread.
     * Profiles are discovered, registered, initialized, and tabs are created dynamically.
     */
    private void startAsyncProfileLoading() {
        Thread loaderThread = new Thread(() -> {
            try {
                // Discover profiles from filesystem
                NodeProfile[] profiles = NodeProfile.loadAll();
                int total = profiles.length;

                if (total == 0) {
                    SwingUtilities.invokeLater(() -> {
                        updateProgress(100, "No profiles found");
                        lifecycleManager.registerProfile("default");
                        createPlaceholderTab("default");
                    });
                    return;
                }

                // Register profiles first
                lifecycleManager.discoverProfiles();

                int count = 0;
                for (NodeProfile profile : profiles) {
                    count++;
                    int percentage = (count * 100) / total;

                    final String profileName = profile.getName();
                    final int currentCount = count;
                    SwingUtilities.invokeLater(() -> {
                        updateProgress(percentage, "Loading: " + profileName + " (" + currentCount + "/" + total + ")");
                        createPlaceholderTab(profileName);
                    });

                    // Small delay for smooth animation effect
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                // Initialize all profiles
                SwingUtilities.invokeLater(() -> {
                    updateProgress(90, "Initializing nodes...");
                    progressBar.setIndeterminate(true);
                });

                lifecycleManager.initializeAllProfiles();

                // Start autostart profiles
                SwingUtilities.invokeLater(() -> {
                    updateProgress(95, "Starting autostart nodes...");
                });

                lifecycleManager.startAutostartProfiles();

                // Final update - ready state
                SwingUtilities.invokeLater(() -> {
                    updateProgress(100, "Ready - " + total + " profiles loaded");
                    progressBar.setIndeterminate(false);
                    statusLabel.setForeground(new Color(76, 175, 80)); // Green
                });

                LOGGER.info("Async profile loading completed: {} profiles loaded", count);
            } catch (Exception e) {
                LOGGER.error("Error during async profile loading", e);
                SwingUtilities.invokeLater(() -> {
                    updateProgress(0, "Error loading profiles");
                    progressBar.setForeground(Color.RED);
                    statusLabel.setForeground(Color.RED);
                });
            }
        }, "ProfileLoader");
        loaderThread.setDaemon(true);
        loaderThread.start();
    }

    /**
     * Updates the progress bar and status label.
     */
    private void updateProgress(int percentage, String message) {
        progressBar.setValue(percentage);
        progressBar.setString(message + " (" + percentage + "%)");
        statusLabel.setText(message);
    }

    /**
     * Creates a lightweight placeholder tab for a profile.
     */
    private void createPlaceholderTab(String profileName) {
        NodePlaceholderPanel placeholder = new NodePlaceholderPanel(profileName, () -> {
            SwingUtilities.invokeLater(() -> checkAndReplacePlaceholder());
        });

        placeholderReplaced.put(profileName, false);
        profileTabbedPane.addTab(profileName, placeholder);
        int tabIndex = profileTabbedPane.getTabCount() - 1;
        profileNameToTabIndex.put(profileName, tabIndex);

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
            profile = new NodeProfile(profileName);
        }

        NodeProfilePanel actualPanel = new NodeProfilePanel(null, profile);
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
     * Updates the tab icon and tooltip based on the node state.
     * Uses O(1) name-based lookup via profileNameToTabIndex map.
     * Icons are shown for active states (RUNNING, PAUSED, INITIALIZING, STOPPING, ERROR).
     * Stopped/Ready/Idle profiles have no icon.
     * Icon sizes scale dynamically with the global UI font size.
     * Tooltip shows the current node state description on hover.
     */
    private void updateTabIcon(String profileName, NodeLifecycleState state) {
        Integer tabIndex = profileNameToTabIndex.get(profileName);
        if (tabIndex == null) {
            return; // Tab not found
        }

        Icon icon;

        switch (state) {
            case RUNNING:
                icon = GuiIcons.running(GuiIcons.sizeTiny());
                break;
            case INITIALIZING:
            case STOPPING:
                icon = GuiIcons.initializing(GuiIcons.sizeSmall());
                break;
            case ERROR:
                icon = GuiIcons.error(GuiIcons.sizeSmall());
                break;
            case PAUSED:
                icon = GuiIcons.paused(GuiIcons.sizeSmall());
                break;
            case READY:
                icon = GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.CHECK_CIRCLE_O, GuiIcons.sizeTiny(), new Color(100, 149, 237));
                break;
            case STOPPED:
                icon = GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.STOP, GuiIcons.sizeTiny(), new Color(150, 150, 150));
                break;
            case IDLE:
                icon = GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.CIRCLE_O, GuiIcons.sizeTiny(), new Color(150, 150, 150));
                break;
            case WAITING_FOR_DATABASE:
                icon = GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.DATABASE, GuiIcons.sizeSmall(), new Color(255, 193, 7));
                break;
            default:
                icon = null;
                break;
        }

        // Keep the profile name as the tab title (no Unicode suffixes)
        profileTabbedPane.setTitleAt(tabIndex, profileName);
        profileTabbedPane.setIconAt(tabIndex, icon);
        
        // Set tooltip with node state information for hover display
        String tooltip = "Profile: " + profileName + "\nNode State: " + state.getDescription();
        profileTabbedPane.setToolTipTextAt(tabIndex, tooltip);
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