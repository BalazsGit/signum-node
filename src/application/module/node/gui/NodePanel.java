package application.module.node.gui;

import application.module.appearance.AppearanceModule;
import application.module.node.BlockchainProcessor;
import application.module.node.Signum;
import application.module.node.NodeModule;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.NodeProfileRepository;
import application.module.node.profile.ProfileConfig;
import application.module.node.profile.ProfileNameSuggester;
import application.module.node.profile.ProfileRuntimeService;
import application.module.node.gui.wizard.NodeSetupWizardDialog;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiFontManager;
import application.utils.gui.GuiIcons;
import application.utils.gui.GuiUtils;
import application.utils.gui.TabUtils;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
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

    /**
     * The new-profile "+" button at the end of the profile tab row — the row's permanent
     * trailing element (FlatLaf {@code JTabbedPane.trailingComponent}), the same pattern the
     * browser tab strip uses. Clicking it offers the two creation paths (setup wizard / empty
     * default profile) as a popup, so no dedicated "+" tab is needed.
     */
    private final JButton newProfileButton = buildNewProfileButton();
    /** Fixed profile tab width (px): tabs never stretch to fill the window (the browser strip's policy). */
    private static final int TAB_WIDTH = 220;

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
        // Keep the trailing new-profile "+" button's icon in sync with the new size / theme color.
        newProfileButton.setIcon(GuiIcons.plus(GuiIcons.sizeSmall(), GuiColors.getButtonIcon()));
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
                super.setSelectedIndex(index);
                checkAndReplacePlaceholder();
            }
        };
        // Apply application-wide tab layout policy from GuiManager (global, not explicit).
        // (SCROLL by default — the same overflow policy the browser tab strip uses.)
        GuiUtils.applyDefaultTabLayoutPolicy(profileTabbedPane);
        GuiFontManager.applyDefaultFont(profileTabbedPane);
        applyProfileTabStripAppearance();
        add(profileTabbedPane, BorderLayout.CENTER);
        attachTabContextMenu();
        attachTabDragAndDrop();

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

                // (Nothing to enable: the trailing new-profile "+" button is always interactive.)

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
        // Re-sync the name -> index map (every tab is a profile tab now).
        rebuildProfileIndexMap();
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

        // Build the current profile order (every tab is a profile tab now).
        List<String> currentProfiles = new ArrayList<>();
        for (int i = 0; i < tabCount; i++) {
            currentProfiles.add(profileTabbedPane.getTitleAt(i));
        }
        if (currentProfiles.isEmpty()) {
            return; // Nothing to reorder.
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
            LOGGER.debug("Profile tabs already in desired order {}", orderedProfiles);
            return;
        }

        LOGGER.info("Rearranging profile tabs to {}", orderedProfiles);

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

    // ── Profile tab helpers (index map, setup wizard, trailing "+") ───────────

    /**
     * Rebuilds the profile name → tab index map from the current tabs (every tab is a profile tab).
     */
    private void rebuildProfileIndexMap() {
        profileNameToTabIndex.clear();
        for (int i = 0; i < profileTabbedPane.getTabCount(); i++) {
            profileNameToTabIndex.put(profileTabbedPane.getTitleAt(i), i);
        }
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

    // ── New-profile "+" trailing button + close "X" (new-browsertab pattern) ──

    /**
     * Applies the new-browsertab pattern to the profile tab strip (the same client-property
     * setup the browser's {@code BrowserTabPane} uses): a native per-tab close "X", fixed-width
     * tabs, and the new-profile "+" pinned to the end of the row as the trailing component.
     */
    private void applyProfileTabStripAppearance() {
        // Native per-tab close "X" (FlatLaf): clicking the "X" asks to delete the profile
        // (confirm dialog) — mirroring the browser tab strip's close button.
        profileTabbedPane.putClientProperty("TabbedPane.closeIcon", new CloseGlyph());
        profileTabbedPane.putClientProperty("TabbedPane.tabCloseToolTipText", "Delete this profile");
        profileTabbedPane.putClientProperty("JTabbedPane.tabClosable", Boolean.TRUE);
        profileTabbedPane.putClientProperty("JTabbedPane.tabCloseCallback", (java.util.function.IntConsumer) index -> {
            String name = profileTabbedPane.getTitleAt(index);
            if (name != null && !name.isEmpty()) {
                deleteProfileTabFromUi(name);
            }
        });
        // Fixed tab width: tabs never stretch to fill the window (the browser strip's policy);
        // overflow is the L&F's SCROLL arrows.
        profileTabbedPane.putClientProperty("JTabbedPane.minimumTabWidth", TAB_WIDTH);
        profileTabbedPane.putClientProperty("JTabbedPane.maximumTabWidth", TAB_WIDTH);
        // The new-profile "+" pinned to the left end of the trailing band, right after the last
        // tab (WEST, not CENTER: CENTER would stretch the button across the whole trailing band).
        JPanel plusWrap = new JPanel(new BorderLayout());
        plusWrap.setOpaque(false);
        plusWrap.add(newProfileButton, BorderLayout.WEST);
        profileTabbedPane.putClientProperty("JTabbedPane.trailingComponent", plusWrap);
    }

    /**
     * Builds the trailing new-profile "+" button (the tab row's permanent trailing element).
     * Clicking it opens a popup offering the two creation paths.
     */
    private JButton buildNewProfileButton() {
        JButton button = new NewProfileButton();
        button.setIcon(GuiIcons.plus(GuiIcons.sizeSmall(), GuiColors.getButtonIcon()));
        button.setToolTipText("Create a new node profile (setup wizard or empty default profile)");
        button.addActionListener(e -> showNewProfileMenu());
        return button;
    }

    /**
     * Shows the new-profile popup anchored below the "+" button: <b>Launch Setup Wizard…</b>
     * (guided onboarding) and <b>New Empty Default Profile</b> (fast path) — the two creation
     * paths the old dedicated "+" tab offered as cards.
     */
    private void showNewProfileMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem wizard = new javax.swing.JMenuItem("Launch Setup Wizard…");
        wizard.addActionListener(ev -> openSetupWizard());
        menu.add(wizard);
        javax.swing.JMenuItem empty = new javax.swing.JMenuItem("New Empty Default Profile");
        empty.addActionListener(ev -> openEmptyDefaultProfile());
        menu.add(empty);
        menu.show(newProfileButton, 0, newProfileButton.getHeight());
    }

    /**
     * The <b>New Empty Default Profile</b> action card: asks for the name (prefilled
     * via the {@link ProfileNameSuggester} SSOT) and creates a truly empty,
     * zero-override profile with the default logging preset
     * (SSOT: {@link ProfileRuntimeService#createEmptyProfile}).
     * {@link #addProfileTab} registers the new tab and selects it, leaving the "+" tab.
     */
    private void openEmptyDefaultProfile() {
        java.awt.Window parent = SwingUtilities.windowForComponent(this);
        java.util.Set<String> taken = new java.util.HashSet<>(NodeProfileRepository.listProfiles());
        String suggested = ProfileNameSuggester.nextAvailableName("node", taken);
        String input = (String) javax.swing.JOptionPane.showInputDialog(parent,
                "Enter the new profile name:", "New Empty Default Profile",
                javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, suggested);
        String name = input == null ? "" : input.trim();
        if (name.isEmpty()) {
            return; // cancelled
        }
        try {
            ProfileRuntimeService.createEmptyProfile(name);
            addProfileTab(name); // tab + persisted order + selection (leaves the "+" tab)
        } catch (IllegalArgumentException | java.io.IOException e) {
            LOGGER.warn("Failed to create empty default profile '{}': {}", name, e.getMessage());
            javax.swing.JOptionPane.showMessageDialog(parent,
                    "Error creating profile: " + e.getMessage(),
                    "New Empty Default Profile", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
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
        // Select the newly added profile tab.
        Integer tabIdx = profileNameToTabIndex.get(profileName);
        if (tabIdx != null) {
            profileTabbedPane.setSelectedIndex(tabIdx);
        }
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
        // Re-sync the name -> index map (every tab is a profile tab now).
        rebuildProfileIndexMap();
        try {
            NodeProfileRepository.deleteProfile(profileName);
            LOGGER.info("Profile removed: {}", profileName);
        } catch (IllegalArgumentException e) {
            // The profile file is already gone (e.g. removed by the runtime delete chain,
            // SSOT: ProfileRuntimeService.deleteProfile) — the tab cleanup still applies.
            LOGGER.debug("Profile file already deleted for '{}': {}", profileName, e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Failed to delete profile file for '{}'", profileName, e);
        }
        // Fall back to the onboarding empty-state when no profile tabs remain.
        if (profileTabbedPane.getTabCount() == 0) {
            showOnboarding();
        }
    }

    /**
     * Renames a profile tab: renames the profile file + updates the persisted tab order /
     * logging association (SSOT: {@link NodeProfileRepository#renameProfile}) and re-keys
     * the in-memory tab bookkeeping. The loaded profile panel (if any) is kept as-is.
     */
    public void renameProfileTab(String oldName, String newName) {
        renameProfileTab(oldName, newName, false);
    }

    /**
     * Renames a profile tab. With {@code rebuildPanel == true} (the "rebuild" mode of the
     * runtime rename chain, F3):
     * <ul>
     *   <li>the rename is assumed to be <b>already persisted</b> by the caller
     *       (SSOT: {@link application.module.node.profile.ProfileRuntimeService#renameProfile}
     *       — file move + tab order + logging association), so no repository call is made here;</li>
     *   <li>the loaded {@link NodeProfilePanel} is <b>disposed</b> and a placeholder is put back,
     *       so the tab lazy-loads a FRESH panel under the new name — all internal state
     *       (info bar, toolbar, console, configuration panel, logger-registry key) starts
     *       consistent with the new name.</li>
     * </ul>
     * With {@code rebuildPanel == false} (the tab context menu) the repository rename runs here
     * and any loaded panel is kept.
     */
    public void renameProfileTab(String oldName, String newName, boolean rebuildPanel) {
        int index = profileNameToTabIndex.getOrDefault(oldName, -1);
        if (index < 0) {
            LOGGER.warn("renameProfileTab: no tab for profile '{}'", oldName);
            return;
        }
        if (!rebuildPanel) {
            try {
                NodeProfileRepository.renameProfile(oldName, newName);
            } catch (Exception e) {
                LOGGER.error("Failed to rename profile '{}' -> '{}'", oldName, newName, e);
                return;
            }
        }
        profileTabbedPane.setTitleAt(index, newName);
        Integer tabIndex = profileNameToTabIndex.remove(oldName);
        if (tabIndex != null) {
            profileNameToTabIndex.put(newName, tabIndex);
        }
        if (rebuildPanel) {
            // The loaded panel's internal state is keyed by the OLD name — dispose it and
            // reset the placeholder so the tab lazy-loads a fresh NodeProfilePanel.
            NodeProfilePanel panel = loadedProfilePanels.remove(oldName);
            if (panel != null) {
                panel.dispose();
            }
            placeholderReplaced.remove(oldName);
            placeholderReplaced.put(newName, false);
            profileTabbedPane.setComponentAt(index, new NodePlaceholderPanel(newName, () -> {
                SwingUtilities.invokeLater(this::checkAndReplacePlaceholder);
            }));
            updateTabIcon(newName);
            if (index == profileTabbedPane.getSelectedIndex()) {
                checkAndReplacePlaceholder();
            }
            LOGGER.info("Profile tab renamed and rebuilt: {} -> {}", oldName, newName);
        } else {
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

    // ── Tab drag & drop (user reorder — plan §9.1) ───────────────────────

    /** Drag activation threshold (px) — press/release below this stays a normal click. */
    private static final int DRAG_THRESHOLD_PX = 4;

    /**
     * Attaches mouse-drag reordering to the profile tab strip (plan §9.1).
     * <p>
     * Press a profile tab, drag it over another position and release: the tab
     * takes over that position (via {@link TabUtils#moveTo}), the new order is
     * persisted to {@code ProfileConfig.tabOrder} (SSOT: {@code profiles.json}),
     * and the moved tab stays selected. Press/release without movement is left
     * untouched, so normal click-to-select (and the right-click context menu) keep
     * working. The trailing new-profile "+" button is not a tab, so it is never a
     * drag source or drop target.
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
                if (idx >= 0) {
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
                if (from < 0 || from == to || to < 0) {
                    return; // plain click (or no valid target) — the pane selects normally
                }
                String movedName = profileTabbedPane.getTitleAt(from);
                TabUtils.moveTo(profileTabbedPane, from, to);
                // Final index of the moved tab (the "to" slot, per TabUtils.moveTo).
                int movedIndex = to;
                rebuildProfileIndexMap(); // re-sync the name -> index map after the move
                persistTabOrder(); // SSOT: profiles.json
                // Keep the moved tab active (runs after the pane's own click handler,
                // so it wins over the selection landing on the drop position).
                int count = profileTabbedPane.getTabCount();
                if (movedIndex >= 0 && movedIndex < count) {
                    profileTabbedPane.setSelectedIndex(movedIndex);
                }
                LOGGER.info("Profile tab moved: '{}' from index {} to {}", movedName, from, to);
            }
        });
    }

    /**
     * Persists the current tab order (every tab is a profile tab) to
     * {@code ProfileConfig.tabOrder} — the SSOT in {@code profiles.json}, which
     * also drives the autostart order (plan §9.2).
     */
    private void persistTabOrder() {
        List<String> order = new ArrayList<>();
        for (int i = 0; i < profileTabbedPane.getTabCount(); i++) {
            order.add(profileTabbedPane.getTitleAt(i));
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

    // ------------------------------------------------------------------
    // Icons + the trailing new-profile button (new-browsertab pattern)
    // ------------------------------------------------------------------

    /**
     * The trailing new-profile "+" button: the app's plus glyph drawn as a flat button that
     * paints a rounded hover highlight on rollover — the same selection behaviour the browser
     * tab strip's new-tab "+" and close "X" icons get — so it reads as a clickable control.
     */
    private static final class NewProfileButton extends JButton {

        NewProfileButton() {
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            setRolloverEnabled(true);
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            if (getModel().isRollover()) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                java.awt.Color c = javax.swing.UIManager.getColor("TabbedPane.hoverBackground");
                g2.setColor(c != null ? c : new java.awt.Color(0x80, 0x80, 0x80, 48));
                g2.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 10, 10);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** The profile tab's close "X" glyph (theme-aware color), the same size as the new-profile "+". */
    private static final class CloseGlyph implements Icon {

        private static final int SIZE = 16;

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            java.awt.Color color = javax.swing.UIManager.getColor("controlText");
            g2.setColor(color != null ? color : new java.awt.Color(0x6B, 0x6D, 0x72));
            g2.setStroke(new java.awt.BasicStroke(1.4f));
            int cx = x + SIZE / 2;
            int cy = y + SIZE / 2;
            g2.drawLine(cx - 4, cy - 4, cx + 4, cy + 4);
            g2.drawLine(cx - 4, cy + 4, cx + 4, cy - 4);
            g2.dispose();
        }
    }
}