package application.module.node.gui;

import application.module.appearance.AppearanceModule;
import application.module.node.Signum;
import application.module.node.NodeModule;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.NodeProfileRepository;
import application.module.node.profile.ProfileConfig;
import application.module.node.profile.ProfileNameSuggester;
import application.module.node.gui.wizard.NodeSetupWizardDialog;
import application.utils.gui.GuiFontManager;
import application.utils.gui.GuiIcons;
import application.utils.gui.GuiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Main Node panel that acts as a JTabbedPane container.
 * Dynamically loads profiles asynchronously with progress feedback.
 * Heavy NodeProfilePanel instances are lazy-loaded on first tab selection.
 * <p>
 * Per-profile push-based lifecycle notifications are owned by each
 * {@link NodeProfilePanel} (a single {@code Signum.StateListener} per panel);
 * this container only manages tab lifecycle.
 * Supports user-defined tab order via ProfileConfig.tabOrder (saved to profiles.json).
 */
@SuppressWarnings("serial")
public class NodePanel extends JPanel  {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodePanel.class);

    private JTabbedPane profileTabbedPane;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    /** Shown instead of the (empty) tabbed pane when no profiles exist yet (onboarding, plan §1.3). */
    private JPanel onboardingPanel;

    /** Maps profile name -> actual NodeProfilePanel (after lazy-load) */
    private final Map<String, NodeProfilePanel> loadedProfilePanels = new LinkedHashMap<>();
    /** Tracks which placeholders have been replaced */
    private final Map<String, Boolean> placeholderReplaced = new LinkedHashMap<>();
    /** Reverse lookup: profile name -> tab index for O(1) access by name */
    private final Map<String, Integer> profileNameToTabIndex = new LinkedHashMap<>();
    /** Sole composition root / lifecycle entry point (v4) */
    private final NodeModule nodeModule = NodeModule.getInstance();
    /** ProfileConfig for tab order management */
    private final ProfileConfig profileConfig = new ProfileConfig();
    /**
     * Appearance listener (kept as a field so {@link #dispose()} can unregister
     * it — AppearanceModule listeners are identity-based).
     */
    private final Runnable appearanceListener = () -> {
        GuiFontManager.applyDefaultFont(profileTabbedPane);
        GuiFontManager.applyDefaultFont(statusLabel);
    };

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
        attachTabContextMenu();

        // Keep the selected profile's cross-profile conflict warnings current. A profile's
        // info bar computes its conflicts only when it is built / its own profile restarts,
        // so the set of RUNNING profiles can change afterwards (e.g. another profile
        // auto-starts) and leave a stale or missing warning. Re-evaluating on every tab
        // activation means switching from a running profile to a conflicting one surfaces
        // the red warning immediately.
        profileTabbedPane.addChangeListener(e -> refreshSelectedProfileInfoBar(profileTabbedPane));

        // (v4: per-profile push notifications are owned by NodeProfilePanel)
        

        // Register for appearance updates
        AppearanceModule.registerAppearanceListener(appearanceListener);

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
        JButton addProfileButton = new JButton("+");
        addProfileButton.setToolTipText("Create node profile...");
        addProfileButton.setFocusable(false);
        addProfileButton.setAlignmentY(CENTER_ALIGNMENT);
        GuiFontManager.applyDefaultFont(addProfileButton);
        addProfileButton.addActionListener(e -> openSetupWizard());
        headerPanel.add(addProfileButton);
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
                // Initialize profiles first: sync defaults, create fallback placeholders if needed
                NodeProfileRepository.initialize();

                // Discover profiles from filesystem
                NodeProfile[] profiles = NodeProfileRepository.loadAll();
                int total = profiles.length;

                if (total == 0) {
                    SwingUtilities.invokeLater(() -> {
                        updateProgress(100, "No profiles configured");
                        showOnboarding();
                    });
                    return;
                }

                // Register profiles first
                

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

                

                // Start autostart profiles
                SwingUtilities.invokeLater(() -> {
                    updateProgress(95, "Starting autostart nodes...");
                });

                

                // Apply tab order from ProfileConfig (user-defined or default filesystem order)
                final NodeProfile[] loadedProfiles = profiles;
                SwingUtilities.invokeLater(() -> applyTabOrder(loadedProfiles));

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
        NodeProfile profile = NodeProfileRepository.loadByName(profileName);
        if (profile == null) {
            profile = new NodeProfile(profileName);
        }

        // Wire the per-instance Signum facade from the NodeFactory registry.
        // If the node hasn't been started yet, signum will be null - that's fine,
        // the panel handles null signum gracefully (profile not yet started).
        Signum signum = NodeModule.getInstance().get(profileName);

        NodeProfilePanel actualPanel = new NodeProfilePanel(null, profile, signum);
        loadedProfilePanels.put(profileName, actualPanel);

        // Replace placeholder with actual panel
        Component oldComponent = profileTabbedPane.getComponentAt(selectedIndex);
        if (oldComponent instanceof NodePlaceholderPanel) {
            ((NodePlaceholderPanel) oldComponent).markAsLoaded();
        }

        profileTabbedPane.setComponentAt(selectedIndex, actualPanel);
        placeholderReplaced.put(profileName, true);

        application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profileName,
                () -> LOGGER.info("Profile panel loaded for: {}", profileName));
    }

    /**
     * Refreshes the currently selected profile's information bar so its cross-profile
     * resource-conflict warnings (API/P2P/WebSocket port, database) reflect the profiles
     * that are RUNNING at the moment the tab is viewed.
     * <p>
     * A {@link NodeInfoBar} computes its conflicts only when it is built and when its own
     * profile restarts — it does not observe the other profiles. Since the set of running
     * profiles can change after a panel was first shown (e.g. another profile auto-starts
     * later), its conflict chips would otherwise go stale. Re-running the refresh on tab
     * activation fixes that: switching from a running profile to a conflicting one now
     * surfaces the warning at once.
     * </p>
     * <p>No-op when the selected tab is a placeholder (not yet lazy-loaded).
     * Package-private static so it can be exercised directly in tests without building the
     * full (asynchronous, heavy) {@link NodePanel}.</p>
     *
     * @param tabs the profile tabbed pane (null is a safe no-op)
     */
    static void refreshSelectedProfileInfoBar(JTabbedPane tabs) {
        if (tabs == null) {
            return;
        }
        Component selected = tabs.getSelectedComponent();
        if (selected instanceof NodeProfilePanel panel) {
            panel.refreshConflictWarnings();
        }
    }

    // ====================================================================
    // LifecycleListener implementation (push-based)
    // ====================================================================

    public void onStateChanged(NodeProfile profile, Signum.State oldState, Signum.State newState) {
        SwingUtilities.invokeLater(() -> {
            NodeProfilePanel panel = loadedProfilePanels.get(profile.getName());
            if (panel != null) {
                panel.onNodeStateChanged(oldState, newState);
            }
            updateTabIcon(profile.getName(), newState);
            application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profile.getName(),
                    () -> LOGGER.info("State change: {} -> {} for profile {}", oldState, newState, profile.getName()));
        });
    }

    public void onStatusMessage(NodeProfile profile, String message) {
        SwingUtilities.invokeLater(() -> {
            NodeProfilePanel panel = loadedProfilePanels.get(profile.getName());
            if (panel != null) {
                panel.onStatusMessage(message);
            }
            LOGGER.debug("Status [{}]: {}", profile.getName(), message);
        });
    }

    public void onError(NodeProfile profile, String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            NodeProfilePanel panel = loadedProfilePanels.get(profile.getName());
            if (panel != null) {
                panel.onError(errorMessage);
            }
            LOGGER.error("Error [{}]: {}", profile.getName(), errorMessage);
        });
    }

    public void onShutdownRequested(NodeProfile profile) {
        // Forward shutdown request to the corresponding profile panel so it can
        // save GUI settings (metrics panel state, command input visibility, etc.)
        NodeProfilePanel panel = loadedProfilePanels.get(profile.getName());
        if (panel != null && panel.getConsolePanel() != null) {
            panel.getConsolePanel().saveGuiSettings();
            LOGGER.info("Shutdown requested for profile '{}' - GUI settings saved", profile.getName());
        } else {
            LOGGER.debug("Shutdown requested for profile '{}', no console panel found to save settings",
                    profile.getName());
        }
    }

    /**
     * Updates the tab icon and tooltip based on the node state.
     * Uses O(1) name-based lookup via profileNameToTabIndex map.
     * Icons are shown for active states (RUNNING, PAUSED, INITIALIZING, STOPPING, ERROR).
     * Stopped/Ready/Idle profiles have no icon.
     * Icon sizes scale dynamically with the global UI font size.
     * Tooltip shows the current node state description on hover.
     */
    private void updateTabIcon(String profileName, Signum.State state) {
        Integer tabIndex = profileNameToTabIndex.get(profileName);
        if (tabIndex == null) {
            return; // Tab not found
        }

        Icon icon;

        switch (state) {
            case RUNNING:
                icon = GuiIcons.running(GuiIcons.sizeTiny());
                break;
            case STARTING:
            case STOPPING:
                icon = GuiIcons.initializing(GuiIcons.sizeSmall());
                break;
            case ERROR:
                icon = GuiIcons.error(GuiIcons.sizeSmall());
                break;
            case INITIALIZED:
                icon = GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.CHECK_CIRCLE_O, GuiIcons.sizeTiny(), new Color(100, 149, 237));
                break;
            case STOPPED:
                icon = GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.STOP, GuiIcons.sizeTiny(), new Color(150, 150, 150));
                break;
            case CREATED:
                icon = GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.CIRCLE_O, GuiIcons.sizeTiny(), new Color(150, 150, 150));
                break;
            default:
                icon = null;
                break;
        }

        // Keep the profile name as the tab title (no Unicode suffixes)
        profileTabbedPane.setTitleAt(tabIndex, profileName);
        profileTabbedPane.setIconAt(tabIndex, icon);

        // Set tooltip with node state information for hover display
        String tooltip = "Profile: " + profileName + "\nNode State: " + state.name().toLowerCase();
        profileTabbedPane.setToolTipTextAt(tabIndex, tooltip);
    }

    // ====================================================================
    // Tab Order Management (based on ProfileConfig.tabOrder / guiSettings)
    // ====================================================================

    /**
     * Applies tab order to the profile tabbed pane.
     * <p>
     * Priority: User-defined order from ProfileConfig.tabOrder > filesystem discovery order.
     * When user reorders tabs (drag-drop), the new order is persisted via ProfileConfig.setTabOrder().
     * <p>
     * This method rearranges existing tabs in the tabbed pane to match the desired order,
     * updating internal tracking maps accordingly.
     *
     * @param profiles the array of discovered NodeProfiles (used as fallback order)
     */
    private void applyTabOrder(NodeProfile[] profiles) {
        // Build a set of all loaded profile names for quick lookup
        List<String> desiredOrder = new ArrayList<>();

        // First try user-defined order from ProfileConfig
        List<String> userOrder = profileConfig.getTabOrder();
        if (userOrder != null && !userOrder.isEmpty()) {
            // Filter to only include profiles that actually exist
            for (String name : userOrder) {
                if (placeholderReplaced.containsKey(name)) {
                    desiredOrder.add(name);
                }
            }
            // Add any remaining profiles not in user order (at end, in filesystem order)
            for (NodeProfile p : profiles) {
                if (!desiredOrder.contains(p.getName())) {
                    desiredOrder.add(p.getName());
                }
            }
        } else {
            // No user-defined order: use filesystem discovery order
            for (NodeProfile p : profiles) {
                desiredOrder.add(p.getName());
            }
        }

        if (desiredOrder.isEmpty()) {
            return; // Nothing to reorder
        }

        LOGGER.info("Applying tab order: {}", desiredOrder);

        // Save this order back to ProfileConfig so user's preferred order is persisted
        profileConfig.setTabOrder(desiredOrder);

        // Now rearrange tabs in the tabbed pane to match desiredOrder
        rearrangeTabs(desiredOrder);
    }

    /**
     * Rearranges the tabs in the tabbed pane to match the desired order.
     * Tabs not in the desired list are appended at the end in their current position.
     *
     * @param desiredOrder ordered list of profile names
     */
    private void rearrangeTabs(List<String> desiredOrder) {
        int tabCount = profileTabbedPane.getTabCount();
        if (tabCount == 0) {
            return;
        }

        // Build current order from tabbed pane
        List<String> currentOrder = new ArrayList<>();
        for (int i = 0; i < tabCount; i++) {
            currentOrder.add(profileTabbedPane.getTitleAt(i));
        }

        // If already in desired order, skip
        if (currentOrder.equals(desiredOrder)) {
            LOGGER.debug("Tab order already matches desired order");
            return;
        }

        LOGGER.info("Rearranging tabs from {} to {}", currentOrder, desiredOrder);

        // Collect all tab components and titles
        List<Component> components = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < tabCount; i++) {
            components.add(profileTabbedPane.getComponentAt(i));
            titles.add(profileTabbedPane.getTitleAt(i));
            // Also collect tooltips and icons for preservation
        }

        // Clear all tabs
        while (profileTabbedPane.getTabCount() > 0) {
            profileTabbedPane.removeTabAt(0);
        }

        // Rebuild in desired order
        Map<String, Integer> newIndexMap = new LinkedHashMap<>();
        int index = 0;

        // First add tabs in desired order
        for (String name : desiredOrder) {
            int origIndex = currentOrder.indexOf(name);
            if (origIndex >= 0) {
                profileTabbedPane.addTab(name, components.get(origIndex));
                newIndexMap.put(name, index++);
                // Remove from current order so we don't add it again
                currentOrder.remove(Integer.valueOf(origIndex));
                components.remove(Integer.valueOf(origIndex));
            }
        }

        // Add any remaining tabs (shouldn't happen if desiredOrder is complete)
        for (int i = 0; i < titles.size(); i++) {
            String name = titles.get(i);
            if (!newIndexMap.containsKey(name)) {
                profileTabbedPane.addTab(name, components.get(i));
                newIndexMap.put(name, index++);
            }
        }

        // Update internal tracking map
        this.profileNameToTabIndex.clear();
        for (int i = 0; i < profileTabbedPane.getTabCount(); i++) {
            String name = profileTabbedPane.getTitleAt(i);
            this.profileNameToTabIndex.put(name, i);
        }

        LOGGER.debug("Tab order rearranged. New mapping: {}", newIndexMap);
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
    public NodeModule getNodeModule() {
        return nodeModule;
    }

    /**
     * Cleanup when the panel is closed: unregisters the appearance listener and
     * disposes every loaded profile panel (unregistering its Signum StateListener).
     * Does not stop any node — node lifecycle is owned by {@link NodeModule} (v4).
     * <p>
     * Idempotent: safe to call multiple times.
     * </p>
     */
    public void dispose() {
        AppearanceModule.removeAppearanceListener(appearanceListener);
        for (NodeProfilePanel panel : loadedProfilePanels.values()) {
            panel.dispose();
        }
        LOGGER.info("NodePanel disposed: appearance listener unregistered, {} profile panel(s) disposed",
                loadedProfilePanels.size());
    }

    // ====================================================================
    // Setup wizard + dynamic profile tabs (plan §1.3-1.4)
    // ====================================================================

    /**
     * Opens the modal setup wizard; on success the new profile tab is added.
     */
    private void openSetupWizard() {
        java.awt.Window window = SwingUtilities.windowForComponent(this);
        java.awt.Frame parent = window instanceof java.awt.Frame ? (java.awt.Frame) window : null;
        NodeSetupWizardDialog dialog = new NodeSetupWizardDialog(parent, this::addProfileTab);
        dialog.setVisible(true);
    }

    /**
     * Shows the onboarding empty-state panel instead of the (empty) tabbed pane
     * (plan §1.3). Called from the async loader when zero profiles are discovered.
     */
    private void showOnboarding() {
        profileTabbedPane.setVisible(false);
        if (onboardingPanel == null) {
            onboardingPanel = new JPanel();
            onboardingPanel.setLayout(new BoxLayout(onboardingPanel, BoxLayout.Y_AXIS));
            onboardingPanel.setOpaque(false);

            JLabel title = new JLabel("No node profiles yet");
            title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 22f));
            GuiFontManager.applyDefaultFont(title);

            JLabel text = new JLabel(
                    "Create your first profile to start the node — or headlessly:  signum-node profile create <name>");
            GuiFontManager.applyDefaultFont(text);

            JButton button = new JButton("Create Node Profile");
            button.setFocusable(false);
            button.addActionListener(e -> openSetupWizard());
            GuiFontManager.applyDefaultFont(button);

            onboardingPanel.add(Box.createVerticalGlue());
            onboardingPanel.add(title);
            onboardingPanel.add(Box.createVerticalStrut(14));
            onboardingPanel.add(text);
            onboardingPanel.add(Box.createVerticalStrut(28));
            onboardingPanel.add(button);
            onboardingPanel.add(Box.createVerticalGlue());
        }
        onboardingPanel.setVisible(true);
        add(onboardingPanel, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    /**
     * Adds a freshly created profile as a new (placeholder) tab, restores the tabbed
     * pane from onboarding when needed, and appends the profile to the persisted tab
     * order (SSOT: {@code profiles.json} via {@link ProfileConfig}).
     *
     * @param profileName the created profile name (file already exists)
     */
    public void addProfileTab(String profileName) {
        if (profileNameToTabIndex.containsKey(profileName)) {
            return; // already visible
        }
        if (onboardingPanel != null && onboardingPanel.isVisible()) {
            onboardingPanel.setVisible(false);
            profileTabbedPane.setVisible(true);
        }
        createPlaceholderTab(profileName);
        List<String> order = profileConfig.getTabOrder();
        order = order == null ? new ArrayList<>() : new ArrayList<>(order);
        if (!order.contains(profileName)) {
            order.add(profileName);
            profileConfig.setTabOrder(order);
        }
        profileTabbedPane.setSelectedIndex(profileTabbedPane.getTabCount() - 1);
        statusLabel.setForeground(new Color(76, 175, 80));
        statusLabel.setText("Profile added: " + profileName);
        LOGGER.info("Profile tab added: {}", profileName);
    }

    /**
     * Removes a profile tab: stops the node (if any), disposes its loaded panel,
     * removes the tab and persists the removal (file + profiles.json via the
     * repository SSOT). Falls back to the onboarding panel when no tabs remain.
     */
    public void removeProfileTab(String profileName) {
        int index = profileNameToTabIndex.getOrDefault(profileName, -1);
        if (index < 0) {
            LOGGER.warn("removeProfileTab: no tab for profile '{}'", profileName);
            return;
        }
        NodeModule module = NodeModule.getInstance();
        if (module.get(profileName) != null) {
            module.stopNode(profileName);
        }
        NodeProfilePanel panel = loadedProfilePanels.remove(profileName);
        if (panel != null) {
            panel.dispose();
        }
        placeholderReplaced.remove(profileName);
        profileNameToTabIndex.remove(profileName);
        profileTabbedPane.removeTabAt(index);
        for (int i = index; i < profileTabbedPane.getTabCount(); i++) {
            profileNameToTabIndex.put(profileTabbedPane.getTitleAt(i), i);
        }
        try {
            NodeProfileRepository.deleteProfile(profileName);
            LOGGER.info("Profile removed: {}", profileName);
        } catch (Exception e) {
            LOGGER.error("Failed to delete profile file for '{}'", profileName, e);
        }
        if (profileTabbedPane.getTabCount() == 0) {
            showOnboarding();
        }
    }

    /**
     * Renames a profile tab: renames the profile file + updates the persisted tab order /
     * logging association (SSOT: {@link NodeProfileRepository#renameProfile}) and re-keys
     * the in-memory tab bookkeeping.
     */
    public void renameProfileTab(String oldName, String newName) {
        int index = profileNameToTabIndex.getOrDefault(oldName, -1);
        if (index < 0) {
            LOGGER.warn("renameProfileTab: no tab for profile '{}'", oldName);
            return;
        }
        try {
            NodeProfileRepository.renameProfile(oldName, newName);
        } catch (Exception e) {
            LOGGER.error("Failed to rename profile '{}' -> '{}'", oldName, newName, e);
            return;
        }
        profileTabbedPane.setTitleAt(index, newName);
        Integer tabIndex = profileNameToTabIndex.remove(oldName);
        if (tabIndex != null) {
            profileNameToTabIndex.put(newName, tabIndex);
        }
        Boolean replaced = placeholderReplaced.remove(oldName);
        if (replaced != null) {
            placeholderReplaced.put(newName, replaced);
        }
        NodeProfilePanel panel = loadedProfilePanels.remove(oldName);
        if (panel != null) {
            loadedProfilePanels.put(newName, panel);
        }
        LOGGER.info("Profile tab renamed: {} -> {}", oldName, newName);
    }

    // ── Tab context menu (rename / delete) ──────────────────────────────

    /**
     * Attaches a right-click context menu (Rename… / Delete…) to the profile tab strip.
     * Attached once (guarded); the target tab is resolved from the mouse position.
     */
    private void attachTabContextMenu() {
        if (Boolean.TRUE.equals(profileTabbedPane.getClientProperty("contextMenuAttached"))) {
            return;
        }
        profileTabbedPane.putClientProperty("contextMenuAttached", true);
        profileTabbedPane.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int idx = profileTabbedPane.indexAtLocation(e.getX(), e.getY());
                    if (idx >= 0 && idx < profileTabbedPane.getTabCount()) {
                        showTabContextMenu(idx, e);
                    }
                }
            }
        });
    }

    private void showTabContextMenu(int tabIndex, java.awt.event.MouseEvent e) {
        String name = profileTabbedPane.getTitleAt(tabIndex);
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();

        javax.swing.JMenuItem rename = new javax.swing.JMenuItem("Rename…");
        rename.addActionListener(ev -> renameProfileTabFromUi(name));
        menu.add(rename);

        menu.addSeparator();

        javax.swing.JMenuItem delete = new javax.swing.JMenuItem("Delete…");
        delete.addActionListener(ev -> deleteProfileTabFromUi(name));
        menu.add(delete);

        menu.show(profileTabbedPane, e.getX(), e.getY());
    }

    /**
     * Rename flow: asks for the new name (suggested via the {@link ProfileNameSuggester}
     * SSOT), validates (pattern / reserved / taken — same rules as the wizard) and then
     * delegates to {@link #renameProfileTab(String, String)}.
     */
    private void renameProfileTabFromUi(String name) {
        java.awt.Window parent = SwingUtilities.windowForComponent(this);
        java.util.Set<String> taken = new java.util.HashSet<>(NodeProfileRepository.listProfiles());
        String suggested = ProfileNameSuggester.nextAvailableName(name + "_copy", taken);
        String input = (String) javax.swing.JOptionPane.showInputDialog(parent,
                "Enter new name for profile '" + name + "':",
                "Rename Profile", javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, suggested);
        if (input == null) {
            return; // cancelled
        }
        String newName = input.trim();
        if (newName.isEmpty() || !java.util.regex.Pattern.matches("[a-zA-Z0-9_-]{2,32}", newName)) {
            javax.swing.JOptionPane.showMessageDialog(parent,
                    "Profile name must be 2-32 characters: a-z, 0-9, '_' or '-'.",
                    "Invalid name", javax.swing.JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (NodeProfileRepository.isReservedProfileName(newName)) {
            javax.swing.JOptionPane.showMessageDialog(parent, "Profile name '" + newName + "' is reserved.",
                    "Invalid name", javax.swing.JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (taken.contains(newName)) {
            javax.swing.JOptionPane.showMessageDialog(parent, "A profile named '" + newName + "' already exists.",
                    "Name taken", javax.swing.JOptionPane.ERROR_MESSAGE);
            return;
        }
        renameProfileTab(name, newName);
    }

    /**
     * Delete flow: confirmation guard (extra warning when the node is running), then
     * delegates to {@link #removeProfileTab(String)} (stop → dispose → file + tabOrder).
     */
    private void deleteProfileTabFromUi(String name) {
        java.awt.Window parent = SwingUtilities.windowForComponent(this);
        boolean running = NodeModule.getInstance().get(name) != null;
        StringBuilder msg = new StringBuilder("Delete profile '").append(name).append("'?");
        if (running) {
            msg.append("\nIts node is RUNNING — it will be stopped first.");
        }
        msg.append("\n\nThe profile file and its settings will be removed.");
        int choice = javax.swing.JOptionPane.showConfirmDialog(parent, msg.toString(),
                "Delete profile", javax.swing.JOptionPane.YES_NO_OPTION,
                running ? javax.swing.JOptionPane.WARNING_MESSAGE : javax.swing.JOptionPane.QUESTION_MESSAGE);
        if (choice == javax.swing.JOptionPane.YES_OPTION) {
            removeProfileTab(name);
        }
    }
}