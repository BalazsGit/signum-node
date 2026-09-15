package application.module.node.gui;

import application.module.appearance.AppearanceModule;
import application.module.node.BlockchainProcessor;
import application.module.node.Signum;
import application.module.node.NodeModule;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.NodeProfileRepository;
import application.module.node.profile.ProfileConfig;
import application.module.node.profile.ProfileNameSuggester;
import application.module.node.gui.wizard.NodeSetupWizardDialog;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiFontManager;
import application.utils.gui.GuiIcons;
import application.utils.gui.GuiUtils;
import application.utils.gui.TabUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
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
    /** Shown instead of the (empty) tabbed pane when no profiles exist yet (onboarding, plan §1.3). */
    private JPanel onboardingPanel;

    /** Last selected profile tab index (used to revert the never-openable "+" tab to a real profile tab). */
    private int lastProfileTabIndex = -1;
    /** When true, activating the "+" tab opens the setup wizard (enabled once the UI is interactive). */
    private boolean wizardInteractionEnabled = false;
    /**
     * Dedicated content component of the persistent "add profile" tab (always the last tab,
     * icon-only, never opened — its header's only job is to open the setup wizard).
     * The tab is recognized by this component's identity; no profile tab can hold it.
     */
    private final JPanel addProfileTabComponent = new JPanel();
    /**
     * Thin-line "+" icon shown on the add-profile tab (recreated on appearance changes so it
     * keeps tracking the global UI font size and the theme's icon color).
     */
    private Icon addProfileTabIcon = GuiIcons.plus(GuiIcons.sizeSmall(), GuiColors.getButtonIcon());

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
        // Keep the add-profile tab's "+" icon in sync with the new size / theme color.
        addProfileTabIcon = GuiIcons.plus(GuiIcons.sizeSmall(), GuiColors.getButtonIcon());
        applyAddTabIcon();
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

        // Create tabbed pane for profiles with application-wide tab layout policy.
        // Policy is read from GuiManager which loads from gui-settings.json at startup.
        this.profileTabbedPane = new JTabbedPane(SwingConstants.TOP) {
            @Override
            public void setSelectedIndex(int index) {
                if (handlePlusTabSelection(index)) {
                    return; // The "+" tab never opens; selection already reverted (and wizard opened).
                }
                super.setSelectedIndex(index);
                if (index >= 0) {
                    lastProfileTabIndex = index;
                }
                checkAndReplacePlaceholder();
            }
        };
        // Apply application-wide tab layout policy from GuiManager (global, not explicit)
        GuiUtils.applyDefaultTabLayoutPolicy(profileTabbedPane);
        GuiFontManager.applyDefaultFont(profileTabbedPane);
        add(profileTabbedPane, BorderLayout.CENTER);
        attachTabContextMenu();
        attachTabDragAndDrop();
        attachPlusTabGuard();

        // Keep every profile's cross-profile conflict warnings current in real time. A profile's
        // info bar reads the authoritative claiming set (NodeModule resource ownership), and that
        // set only changes when a profile reserves (start) or releases (stop / failed start /
        // shutdown) its resources. NodeModule fires a claiming-set event on exactly those
        // transitions; subscribing here re-evaluates every live info bar immediately — including
        // a profile whose own tab is not open (headless autostart) — without any tab switch.
        NodeModule.getInstance().addClaimingSetListener(NodeInfoBar::refreshAllConflicts);

        // v5 (multi-node): a pending start (queued, state push not yet arrived) renders
        // the profile's tab icon like STARTING — so the pending state is visible on the
        // tab strip immediately, consistent with the toolbar/info bar of the panel.
        NodeModule.getInstance().addPendingListener(() -> SwingUtilities.invokeLater(() -> {
            // Refresh EVERY profile tab (loaded or not): a pending start is state-relevant
            // for the tab icon whether or not its panel is currently open.
            for (String name : new java.util.HashSet<>(profileNameToTabIndex.keySet())) {
                updateTabIcon(name);
            }
        }));

        // (v4: per-profile push notifications are owned by NodeProfilePanel)
        

        // Register for appearance updates
        AppearanceModule.registerAppearanceListener(appearanceListener);

        // Start async profile loading
        startAsyncProfileLoading();

        LOGGER.info("NodePanel created, starting async profile loading");
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

                if (profiles.length == 0) {
                    SwingUtilities.invokeLater(() -> showOnboarding());
                    return;
                }

                for (NodeProfile profile : profiles) {
                    final String profileName = profile.getName();
                    SwingUtilities.invokeLater(() -> createPlaceholderTab(profileName));
                }

                // Apply tab order from ProfileConfig (user-defined or default filesystem order)
                final NodeProfile[] loadedProfiles = profiles;
                SwingUtilities.invokeLater(() -> applyTabOrder(loadedProfiles));

                // UI is now interactive: the "+" tab can open the wizard
                SwingUtilities.invokeLater(() -> wizardInteractionEnabled = true);

                LOGGER.info("Async profile loading completed: {} profiles loaded", profiles.length);
            } catch (Exception e) {
                LOGGER.error("Error during async profile loading", e);
            }
        }, "ProfileLoader");
        loaderThread.setDaemon(true);
        loaderThread.start();
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
        // Keep the persistent "+" tab as the last tab and re-sync the name->index map.
        ensureAddTabLast();
        // Show the initial state icon so the tab is not blank until the first change: a
        // never-started profile resolves (via the SSOT) to the CREATED green check,
        // matching the info bar.
        updateTabIcon(profileName);

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

        if (profileTabbedPane.getComponentAt(selectedIndex) == addProfileTabComponent) {
            return; // The "+" tab has no profile content to lazy-load.
        }

        String profileName = profileTabbedPane.getTitleAt(selectedIndex);

        if (Boolean.TRUE.equals(placeholderReplaced.get(profileName))) {
            return; // Already loaded
        }

        LOGGER.info("Lazy-loading profile panel for: {}", profileName);

        // v5 (EDT cleanup): the profile load (disk I/O) AND the heavy NodeProfilePanel
        // construction (console panel, toolbar, info bar) run on a BACKGROUND thread;
        // only the actual tab replacement happens on the EDT. The lightweight
        // placeholder keeps showing until the real panel is ready, so the user
        // perceives a fast tab switch instead of a UI freeze. (The GUI-prep executor
        // is a single daemon thread preserving submission order; all Swing access is
        // marshalled to the EDT below.)
        application.utils.gui.GuiExecutors.prepare().execute(() -> {
            // Load the profile and create the actual panel (background thread)
            NodeProfile profile = NodeProfileRepository.loadByName(profileName);
            if (profile == null) {
                profile = new NodeProfile(profileName);
            }

            // Wire the per-instance Signum facade from the NodeFactory registry.
            // If the node hasn't been started yet, signum will be null - that's fine,
            // the panel handles null signum gracefully (profile not yet started).
            Signum signum = NodeModule.getInstance().get(profileName);

            NodeProfilePanel actualPanel = new NodeProfilePanel(null, profile, signum);
            // Refresh this profile's tab icon from the SSOT whenever a state-relevant
            // condition changes (pause/resume via the panel, trim/prune via the toolbar).
            actualPanel.setTabIconRefresher(() -> updateTabIcon(profileName));

            // Swap in the real panel on the EDT. Re-check the placeholder state and the
            // tab index: the user may have navigated away or the tab may have been
            // removed/renamed while the panel was being built in the background.
            SwingUtilities.invokeLater(() -> {
                if (Boolean.TRUE.equals(placeholderReplaced.get(profileName))) {
                    return; // Already loaded in the meantime
                }
                Integer index = profileNameToTabIndex.get(profileName);
                if (index == null || index < 0 || index >= profileTabbedPane.getTabCount()) {
                    return; // Tab was removed in the meantime
                }
                loadedProfilePanels.put(profileName, actualPanel);

                // Replace placeholder with actual panel
                Component oldComponent = profileTabbedPane.getComponentAt(index);
                if (oldComponent instanceof NodePlaceholderPanel) {
                    ((NodePlaceholderPanel) oldComponent).markAsLoaded();
                }

                profileTabbedPane.setComponentAt(index, actualPanel);
                placeholderReplaced.put(profileName, true);

                application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profileName,
                        () -> LOGGER.info("Profile panel loaded for: {}", profileName));
            });
        });
    }

    // ====================================================================
    // LifecycleListener implementation (push-based)
    // ====================================================================

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
     * The icon is resolved through the SSOT resolver (NodeStateIcon), so it is always
     * consistent with the info bar and the toolbar. A never-started profile (no Signum
     * registered yet) resolves as CREATED — the same green check the info bar shows.
     * Tooltip shows the current node state description on hover.
     */
    private void updateTabIcon(String profileName) {
        Integer tabIndex = profileNameToTabIndex.get(profileName);
        if (tabIndex == null) {
            return; // Tab not found
        }

        // Pull the node's CURRENT combined state (lifecycle + operating + archival
        // maintenance + start-pending) and resolve it through the SSOT resolver so the
        // tab icon is always consistent with the info bar and the toolbar.
        //
        // A never-started profile has no Signum registered yet (get() → null) → treat it
        // as CREATED, matching the info bar. A genuinely stopped node keeps its Signum in
        // the registry with state STOPPED, so it still resolves to the shutdown icon.
        Signum signum = NodeModule.getInstance().get(profileName);
        Signum.State state = (signum != null) ? signum.getState() : Signum.State.CREATED;
        Signum.OperatingState operating = (signum != null) ? signum.getOperatingState() : null;
        BlockchainProcessor.ArchivalMaintenanceState maintenance = null;
        if (signum != null) {
            try {
                BlockchainProcessor bp = signum.getBlockchainProcessor();
                if (bp != null) {
                    maintenance = bp.getArchivalMaintenanceState();
                }
            } catch (Exception ignored) {
                // facade not ready (node not started) → no maintenance state
            }
        }
        boolean startPending = NodeModule.getInstance().isStartPending(profileName);

        NodeStateIcon.Res res = NodeStateIcon.resolve(state, operating, maintenance, startPending);

        // Keep the profile name as the tab title (no Unicode suffixes)
        profileTabbedPane.setTitleAt(tabIndex, profileName);
        profileTabbedPane.setIconAt(tabIndex, res.icon());

        // Set tooltip with the resolved status on hover
        profileTabbedPane.setToolTipTextAt(tabIndex,
                "Profile: " + profileName + "\nStatus: " + res.label());
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

        // Build the current profile order (excluding the persistent "+" tab).
        List<String> currentProfiles = new ArrayList<>();
        for (int i = 0; i < tabCount; i++) {
            if (profileTabbedPane.getComponentAt(i) != addProfileTabComponent) {
                currentProfiles.add(profileTabbedPane.getTitleAt(i));
            }
        }
        if (currentProfiles.isEmpty()) {
            return; // Nothing to reorder (only the "+" tab, if any).
        }

        // Desired order = requested order (existing profiles only), then any remaining profiles.
        List<String> orderedProfiles = new ArrayList<>();
        for (String name : desiredOrder) {
            if (currentProfiles.contains(name) && !orderedProfiles.contains(name)) {
                orderedProfiles.add(name);
            }
        }
        for (String name : currentProfiles) {
            if (!orderedProfiles.contains(name)) {
                orderedProfiles.add(name);
            }
        }

        // Nothing to do when the current order already matches the target. Moving
        // tabs detaches/re-attaches their components (removeNotify()/addNotify()),
        // which disposes their console subscribers — so we must not touch the tabs
        // at all when they are already in place.
        if (currentProfiles.equals(orderedProfiles)) {
            ensureAddTabLast(); // idempotent; only creates/moves the "+" tab when needed
            LOGGER.debug("Profile tabs already in desired order {}", orderedProfiles);
            return;
        }

        LOGGER.info("Rearranging profile tabs to {} (keeping '+' tab last)", orderedProfiles);

        // Pin the "+" tab to the end first (a dummy panel — safe to detach), so the
        // profile tabs occupy indices 0..n-1 and target slots are unambiguous.
        ensureAddTabLast();

        // Move each profile into its target slot with TabUtils.moveTo(): only tabs
        // whose position actually changes are touched (minimal removeNotify churn);
        // already-correct tabs stay untouched, and the selection follows by identity.
        for (int target = 0; target < orderedProfiles.size(); target++) {
            String name = orderedProfiles.get(target);
            int current = -1;
            for (int i = 0; i < profileTabbedPane.getTabCount(); i++) {
                if (name.equals(profileTabbedPane.getTitleAt(i))) {
                    current = i;
                    break;
                }
            }
            if (current >= 0 && current != target) {
                TabUtils.moveTo(profileTabbedPane, current, target);
            }
        }

        rebuildProfileIndexMap();

        LOGGER.debug("Tab order rearranged. New mapping: {}", profileNameToTabIndex);
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

    // ── Persistent "+" (add profile) tab — always the last tab ───────────

    /**
     * Index of the persistent "+" (add profile) tab, or -1 if it does not exist yet.
     */
    private int getAddTabIndex() {
        for (int i = profileTabbedPane.getTabCount() - 1; i >= 0; i--) {
            if (profileTabbedPane.getComponentAt(i) == addProfileTabComponent) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Index of the first profile tab (skipping the "+" tab), or -1 if there are none.
     */
    private int firstProfileTabIndex() {
        for (int i = 0; i < profileTabbedPane.getTabCount(); i++) {
            if (profileTabbedPane.getComponentAt(i) != addProfileTabComponent) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Ensures the persistent "+" (add profile) tab exists and is the last tab:
     * creates it when missing, moves it to the end when it has drifted, and re-syncs
     * the name→index map (which never contains the "+" tab).
     */
    private void ensureAddTabLast() {
        int addIdx = getAddTabIndex();
        if (addIdx < 0) {
            profileTabbedPane.addTab("", addProfileTabComponent);
        } else if (addIdx != profileTabbedPane.getTabCount() - 1) {
            // The "+" tab is a dummy panel (no state, no subscriber) — moving it
            // with TabUtils.moveTo only churns that one component, not the profiles.
            TabUtils.moveTo(profileTabbedPane, addIdx, profileTabbedPane.getTabCount() - 1);
        }
        applyAddTabIcon();
        // The "+" tab is now guaranteed to be the last tab.
        profileTabbedPane.setToolTipTextAt(profileTabbedPane.getTabCount() - 1, "Create node profile...");
        rebuildProfileIndexMap();
    }

    /**
     * Applies the current thin-line "+" icon to the add-profile tab (no-op when the tab is absent).
     */
    private void applyAddTabIcon() {
        int idx = getAddTabIndex();
        if (idx >= 0) {
            profileTabbedPane.setIconAt(idx, addProfileTabIcon);
        }
    }

    /**
     * Rebuilds the profile name → tab index map from the current tabs, excluding the "+" tab.
     */
    private void rebuildProfileIndexMap() {
        profileNameToTabIndex.clear();
        for (int i = 0; i < profileTabbedPane.getTabCount(); i++) {
            if (profileTabbedPane.getComponentAt(i) != addProfileTabComponent) {
                profileNameToTabIndex.put(profileTabbedPane.getTitleAt(i), i);
            }
        }
    }

    /**
     * Intercepts a selection of the persistent "+" (add profile) tab. The "+" tab must never
     * be opened: the selection is reverted to the last real profile tab and (once the UI is
     * interactive) the setup wizard is opened.
     *
     * @param index the index that was about to be selected
     * @return true if the index was the "+" tab (already handled; caller must not select it),
     *         false otherwise
     */
    private boolean handlePlusTabSelection(int index) {
        int addIdx = getAddTabIndex();
        if (addIdx < 0 || index != addIdx) {
            return false; // Not the "+" tab; select normally.
        }
        // Revert to a valid profile tab (never the "+" tab).
        int revertTo = lastProfileTabIndex;
        if (revertTo < 0 || revertTo >= profileTabbedPane.getTabCount() || revertTo == addIdx) {
            revertTo = firstProfileTabIndex();
        }
        if (revertTo >= 0 && revertTo != index) {
            profileTabbedPane.setSelectedIndex(revertTo);
        }
        if (wizardInteractionEnabled) {
            openSetupWizard();
        }
        return true;
    }

    /**
     * Safety net so the "+" tab is never left selected, even if a selection change bypasses
     * the {@code setSelectedIndex} override (e.g. an internal {@code changeSelection} call).
     * Only reverts the selection; the wizard is opened by the {@code setSelectedIndex}
     * interception on user interaction, so it is not opened here (avoids double-opening).
     */
    private void attachPlusTabGuard() {
        profileTabbedPane.addChangeListener(e -> {
            int sel = profileTabbedPane.getSelectedIndex();
            int addIdx = getAddTabIndex();
            if (sel == addIdx && addIdx >= 0) {
                int revertTo = (lastProfileTabIndex >= 0 && lastProfileTabIndex < profileTabbedPane.getTabCount()
                        && lastProfileTabIndex != addIdx) ? lastProfileTabIndex : firstProfileTabIndex();
                if (revertTo >= 0) {
                    profileTabbedPane.setSelectedIndex(revertTo);
                }
            }
        });
    }

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
        // Select the newly added profile tab (never the persistent "+" tab, which is last).
        Integer tabIdx = profileNameToTabIndex.get(profileName);
        if (tabIdx != null) {
            profileTabbedPane.setSelectedIndex(tabIdx);
        }
        wizardInteractionEnabled = true; // UI is interactive: the "+" tab can now open the wizard
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
        // Keep the persistent "+" tab as the last tab and re-sync the name->index map.
        ensureAddTabLast();
        try {
            NodeProfileRepository.deleteProfile(profileName);
            LOGGER.info("Profile removed: {}", profileName);
        } catch (Exception e) {
            LOGGER.error("Failed to delete profile file for '{}'", profileName, e);
        }
        // Fall back to onboarding when no profile tabs remain (the "+" tab is not a profile).
        if (firstProfileTabIndex() < 0) {
            showOnboarding();
        } else if (profileTabbedPane.getSelectedIndex() == getAddTabIndex()) {
            // The removed tab was selected; Swing may have moved the selection onto the "+" tab.
            profileTabbedPane.setSelectedIndex(firstProfileTabIndex());
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
                // NOTE: in this JDK/FlatLaf build e.isPopupTrigger() is *not* set for
                // right-clicks, so we also accept a raw right-button click (BUTTON3).
                boolean popup = e.isPopupTrigger()
                        || e.getButton() == java.awt.event.MouseEvent.BUTTON3;
                if (popup) {
                    int idx = profileTabbedPane.indexAtLocation(e.getX(), e.getY());
                    if (idx >= 0 && idx < profileTabbedPane.getTabCount()
                            && profileTabbedPane.getComponentAt(idx) != addProfileTabComponent) {
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

    // ── Tab drag & drop (user reorder — plan §9.1) ───────────────────────

    /** Drag activation threshold (px) — press/release below this stays a normal click. */
    private static final int DRAG_THRESHOLD_PX = 4;

    /**
     * Attaches mouse-drag reordering to the profile tab strip (plan §9.1).
     * <p>
     * Press a profile tab, drag it over another position and release: the tab
     * takes over that position (via {@link TabUtils#moveTo}), the new order is
     * persisted to {@code ProfileConfig.tabOrder} (SSOT: {@code profiles.json}),
     * and the persistent "+" tab is forced back to the end. The moved tab stays
     * selected. Press/release without movement is left untouched, so normal
     * click-to-select (and the right-click context menu) keep working.
     * </p>
     * <p>
     * Hit-testing uses the standard {@link JTabbedPane#indexAtLocation(int, int)}
     * API; the "+" tab is neither draggable nor a drop target.
     * </p>
     */
    private void attachTabDragAndDrop() {
        if (Boolean.TRUE.equals(profileTabbedPane.getClientProperty("dragDnDAttached"))) {
            return;
        }
        profileTabbedPane.putClientProperty("dragDnDAttached", true);

        int[] dragFrom = {-1};
        java.awt.Point[] pressPoint = {null};

        profileTabbedPane.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    dragFrom[0] = -1;
                    pressPoint[0] = null;
                    return;
                }
                int idx = profileTabbedPane.indexAtLocation(e.getX(), e.getY());
                if (idx >= 0 && profileTabbedPane.getComponentAt(idx) != addProfileTabComponent) {
                    dragFrom[0] = idx;
                    pressPoint[0] = e.getPoint();
                } else {
                    dragFrom[0] = -1;
                    pressPoint[0] = null;
                }
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                int from = dragFrom[0];
                dragFrom[0] = -1;
                pressPoint[0] = null;
                // Drop target from the RELEASE location (mouseDragged is not delivered in
                // this JDK/FlatLaf build, so it cannot be used to track the drag).
                int to = (from < 0) ? -1 : profileTabbedPane.indexAtLocation(e.getX(), e.getY());
                if (from >= 0 && to >= 0 && profileTabbedPane.getComponentAt(to) == addProfileTabComponent) {
                    to = Math.max(0, profileTabbedPane.getTabCount() - 2); // "+" never a drop target
                }
                if (from < 0 || from == to || to < 0) {
                    return; // plain click (or no valid target) — the pane selects normally
                }
                String movedName = profileTabbedPane.getTitleAt(from);
                TabUtils.moveTo(profileTabbedPane, from, to);
                // Final index of the moved tab (the "to" slot, per TabUtils.moveTo).
                int movedIndex = to;
                ensureAddTabLast(); // "+" back to the end + name->index map re-sync
                persistTabOrder(); // SSOT: profiles.json
                // Keep the moved tab active (runs after the pane's own click handler,
                // so it wins over the selection landing on the drop position).
                int count = profileTabbedPane.getTabCount();
                if (movedIndex >= 0 && movedIndex < count
                        && profileTabbedPane.getComponentAt(movedIndex) != addProfileTabComponent) {
                    profileTabbedPane.setSelectedIndex(movedIndex);
                }
                LOGGER.info("Profile tab moved: '{}' from index {} to {}", movedName, from, to);
            }
        });
    }

    /**
     * Persists the current tab order (profile tabs only, the "+" tab excluded) to
     * {@code ProfileConfig.tabOrder} — the SSOT in {@code profiles.json}, which
     * also drives the autostart order (plan §9.2).
     */
    private void persistTabOrder() {
        List<String> order = new ArrayList<>();
        for (int i = 0; i < profileTabbedPane.getTabCount(); i++) {
            if (profileTabbedPane.getComponentAt(i) != addProfileTabComponent) {
                order.add(profileTabbedPane.getTitleAt(i));
            }
        }
        try {
            profileConfig.setTabOrder(order);
        } catch (Exception e) {
            LOGGER.error("Failed to persist tab order {}", order, e);
        }
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