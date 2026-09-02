package application.module.node.gui;

import application.module.appearance.AppearanceModule;
import application.module.node.BlockchainProcessor;
import application.module.node.NodeModule;
import application.module.node.Signum;
import application.module.node.gui.configuration.LoggerConfigurationPanel;
import application.module.node.gui.configuration.NodeConfigurationPanel;
import application.module.node.profile.NodeProfile;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiFontManager;
import application.utils.gui.GuiIcons;
import application.utils.gui.GuiUtils;
import application.utils.gui.ResponsiveToolbarScrollPane;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.Properties;

import javax.swing.BorderFactory;
import javax.swing.DefaultSingleSelectionModel;
import javax.swing.Icon;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Profile panel for a single NodeProfile instance.
 * Layout structure:
 * - NORTH: NodeInfoBar (profile name, network, state, ports)
 * - BELOW NORTH: NodeToolbar (action buttons: Start/Stop/Restart/Sync/etc.)
 * - CENTER: JTabbedPane with Console, Configuration, Logging tabs
 * <p>
 * Uses constructor-injected {@link Signum} facade for per-instance access to
 * BlockchainProcessor, PropertyService, Blockchain, etc. The Signum facade is
 * the single per-instance entry point (Facade Pattern).
 */
@SuppressWarnings("serial")
public class NodeProfilePanel extends JPanel {
    private static final Logger LOGGER = LoggerFactory.getLogger(NodeProfilePanel.class);

    private final JFrame parentFrame;
    private final NodeProfile profile;
    /**
     * Per-instance Signum facade (may be null if the node has not been started yet).
     * Not final: the console panel may start the node after panel construction
     * (late binding) and then hand the instance back via {@link #adoptSignum(Signum)}.
     */
    private volatile Signum signum;

    /**
     * The single {@link Signum.StateListener} (PUSH trigger) registered on the
     * adopted Signum. It only calls {@link #onNodeStateChanged(Signum.State, Signum.State)};
     * all data is always re-read from the Signum (single source of truth).
     */
    private volatile Signum.StateListener stateListener;
    private final JTabbedPane innerTabbedPane;
    private final NodeConsolePanel consolePanel;
    private final NodeConfigurationPanel configurationPanel;
    private final LoggerConfigurationPanel loggingPanel;
    private final NodeInfoBar infoBar;
    private final NodeToolbar toolbar;
    private final String confFolder;

    // v4 (P1.3): the local syncPaused shadow flag was removed — pause state is
    // owned by the Signum instance (getOperatingState()/getPauseReason(), PUSH
    // via onOperatingStateChanged).

    /**
     * Creates a profile panel with an injected Signum facade.
     * The facade provides per-instance access to BlockchainProcessor, PropertyService, etc.,
     * replacing the former static Signum.getXxx() access.
     *
     * @param parentFrame Parent JFrame for dialogs
     * @param profile     The node profile
     * @param signum      Per-instance Signum facade (may be null if not yet started)
     * @since 4.0 Phase G - Greenfield wiring
     */
    public NodeProfilePanel(JFrame parentFrame, NodeProfile profile, Signum signum) {
        // Bind the profile log context EARLY so this panel's construction logs (emitted on
        // the Swing EDT, before the node has even started) are routed to the node's
        // ProfileLogger (Node Console tab) as well as the System Console. The profile is
        // known here, so we also ensure the ProfileLogger exists now; the Signum adopts
        // this same instance when the node starts, so nothing logged before then is lost.
        application.utils.logging.NodeLoggerRegistry.getOrCreate("node", profile.getName());
        application.utils.logging.LogScope previousContext = application.utils.logging.NodeLogContext.current();
        application.utils.logging.NodeLogContext.set("node", profile.getName());
        LOGGER.debug("NodeProfilePanel constructor START for profile: {}", profile.getName());
        
        try {
            this.parentFrame = parentFrame;
            this.profile = profile;
            this.signum = signum;
            this.confFolder = determineConfFolder();

            // In the multi-node architecture every GUI element belongs to its specific
            // Signum. Register this panel on the node so the Signum "knows" its GUI
            // (non-null in GUI mode, null in headless mode). This removes any need for
            // a global "active node" lookup.
            if (signum != null) {
                signum.setGuiPanel(this);
            }

            setLayout(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

            LOGGER.debug("Creating NodeInfoBar for profile: {}", profile.getName());
            infoBar = new NodeInfoBar(profile);
            
            LOGGER.debug("Creating NodeToolbar for profile: {}", profile.getName());
            toolbar = new NodeToolbar(profile);
            // v4: when the toolbar starts the node, hand the instance back so this
            // panel can adopt it (single state listener) and attach the console.
            toolbar.setOnNodeStarted(this::onNodeStarted);

            // Wrap infoBar in a responsive scroll pane so info chips are accessible when window is narrow
            ResponsiveToolbarScrollPane infoBarScrollPane = new ResponsiveToolbarScrollPane(infoBar,
                    new java.awt.Insets(2, 4, 0, 4));
            infoBarScrollPane.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, GuiColors.getSeparator()),
                    BorderFactory.createEmptyBorder(0, 0, 2, 0)
            ));

            JPanel toolbarWrapper = new JPanel(new BorderLayout());
            toolbarWrapper.setOpaque(false);
            toolbarWrapper.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            toolbarWrapper.add(toolbar, BorderLayout.CENTER);

            JPanel northPanel = new JPanel(new BorderLayout());
            northPanel.setOpaque(false);
            northPanel.add(infoBarScrollPane, BorderLayout.NORTH);
            northPanel.add(toolbarWrapper, BorderLayout.SOUTH);
            add(northPanel, BorderLayout.NORTH);

            innerTabbedPane = new JTabbedPane(SwingConstants.TOP);
            GuiFontManager.applyDefaultFont(innerTabbedPane);
            GuiUtils.applyDefaultTabLayoutPolicy(innerTabbedPane);

            LOGGER.debug("Creating NodeConsolePanel for profile: {}", profile.getName());
            consolePanel = new NodeConsolePanel(parentFrame, profile);
            if (signum != null) {
                consolePanel.setSignum(signum);
            }
            consolePanel.setSwitchToConsoleAction(() -> switchToConsoleTab());
            // Late binding: if the console panel starts the node itself (this panel
            // was constructed before the Signum existed), adopt the started instance.
            consolePanel.setOnSignumStarted(this::adoptSignum);
            innerTabbedPane.addTab("Console", consolePanel);
            LOGGER.debug("Console tab added successfully");

            LOGGER.debug("Creating NodeConfigurationPanel for profile: {}", profile.getName());
            // Pass this node's own profile name explicitly so the panel does not rely
            // on the deprecated global "active node" lookup.
            configurationPanel = new NodeConfigurationPanel(
                    this::restartNode,
                    this.confFolder,
                    () -> {
                    },
                    null,
                    profile.getName()
            );
            if (signum != null) {
                configurationPanel.setSignum(signum);
            }
            innerTabbedPane.addTab("Configuration", configurationPanel);
            LOGGER.debug("Configuration tab added successfully");

            LOGGER.debug("Creating LoggerConfigurationPanel for profile: {}", profile.getName());
            loggingPanel = new LoggerConfigurationPanel(
                    this::restartNode,
                    this.confFolder,
                    () -> {
                    },
                    null,
                    null,
                    () -> profile.getName(),
                    () -> "logging"
            );
            innerTabbedPane.addTab("Logging", loggingPanel);
            LOGGER.debug("Logging tab added successfully");

            LOGGER.debug("Adding innerTabbedPane to CENTER (total tabs: {})", innerTabbedPane.getTabCount());
            add(innerTabbedPane, BorderLayout.CENTER);
            
            LOGGER.debug("Wiring toolbar callbacks for profile: {}", profile.getName());
            wireToolbarCallbacks();
            
            LOGGER.debug("Wiring console visibility tracking for profile: {}", profile.getName());
            wireConsoleVisibilityTracking();

            // Adopt the Signum if it already exists at construction time:
            // registers the single state listener (PUSH) + performs the initial
            // refresh. No-op if the node does not exist yet — in that case it is
            // adopted later via the console panel's onSignumStarted callback.
            adoptSignum(this.signum);

            AppearanceModule.registerAppearanceListener(() -> {
                GuiFontManager.applyDefaultFont(innerTabbedPane);
            });

            LOGGER.debug("NodeProfilePanel constructor COMPLETED SUCCESSFULLY for profile: {} (tabs: {})", 
                    profile.getName(), innerTabbedPane.getTabCount());
        } catch (Exception e) {
            LOGGER.error("NodeProfilePanel constructor FAILED for profile: {}", profile.getName(), e);
            throw e;
        } finally {
            // Restore the thread-local log context — the EDT is shared, so it must never
            // leak past this panel's construction.
            if (previousContext != null) {
                application.utils.logging.NodeLogContext.set(previousContext);
            } else {
                application.utils.logging.NodeLogContext.clear();
            }
        }
    }

    /**
     * Single convergence point for binding this panel to a Signum instance.
     * <p>
     * Called with the Signum at construction time (if it already exists) and —
     * for late binding — when the console panel starts the node via
     * {@code NodeModule.startNode(name)} and hands the instance back. Idempotent for the
     * same instance; when a <i>different</i> instance is adopted (restart flow)
     * the previous state listener is unregistered first, so this panel always
     * has exactly one {@link Signum.StateListener} (PUSH trigger) and it only
     * invokes {@link #refreshFromSignum(Signum.State, Signum.State)}.
     * </p>
     *
     * @param newSignum the Signum instance to bind (null is ignored)
     */
    public void adoptSignum(Signum newSignum) {
        if (newSignum == null) {
            // The node has not been started yet in this session: there is no Signum to
            // observe, so no state pushes will ever arrive. Present the toolbar in a
            // startable state — the Start button is constructed disabled
            // (NodeToolbar.createButtons) and without this refresh it would stay gray
            // forever for a never-started profile (lazy-load flow: panel created with
            // a null Signum).
            if (toolbar != null) {
                toolbar.updateButtonStates(Signum.State.STOPPED);
            }
            return;
        }
        if (newSignum == this.signum) {
            return; // already bound to this instance
        }
        Signum previous = this.signum;
        if (previous != null && stateListener != null) {
            previous.removeStateListener(stateListener);
            stateListener = null;
        }
        this.signum = newSignum;
        stateListener = new Signum.StateListener() {
            @Override
            public void onStateChanged(Signum s, Signum.State oldState, Signum.State newState) {
                onNodeStateChanged(oldState, newState);
            }

            @Override
            public void onOperatingStateChanged(Signum s, Signum.OperatingState oldState, Signum.OperatingState newState) {
                boolean paused = newState == Signum.OperatingState.PAUSED_USER
                        || newState == Signum.OperatingState.PAUSED_SYSTEM;
                SwingUtilities.invokeLater(() -> {
                    if (toolbar != null) {
                        toolbar.updateSyncIcon(paused);
                    }
                });
            }
        };
        newSignum.addStateListener(stateListener);
        newSignum.setGuiPanel(this);
        // Initial refresh so the UI immediately reflects the current node state.
        SwingUtilities.invokeLater(() -> refreshFromSignum(null, newSignum.getState()));
        LOGGER.info("Adopted Signum for profile: {} (state={})", profile.getName(), newSignum.getState());
    }

    /**
     * Unbinds this panel from the Signum instance it currently holds: the single
     * {@link Signum.StateListener} is removed so the (possibly still running) Signum
     * no longer pushes state to a dead panel. The Console tab cleans itself up via
     * {@code removeNotify()} (ProfileLogger subscriber disposal).
     * <p>
     * Safe to call multiple times; does not stop the node (lifecycle is owned by
     * {@code NodeModule} — v4 principle 2).
     * </p>
     */
    public void dispose() {
        Signum current = this.signum;
        if (current != null && stateListener != null) {
            current.removeStateListener(stateListener);
            stateListener = null;
        }
        if (toolbar != null) {
            // Stop the Start/Stop spinner animation so its Timer cannot outlive
            // the toolbar.
            toolbar.stopSpinnerAnimation();
        }
        LOGGER.info("NodeProfilePanel disposed for profile: {}", profile.getName());

        // Unregister the info bar from the cross-profile conflict broadcast so a disposed
        // panel is no longer refreshed by NodeInfoBar.refreshAllConflicts() (avoids a leak).
        if (infoBar != null) {
            infoBar.dispose();
        }
    }

    /**
     * Wires a ChangeListener to the inner tabbed pane to track when the Console tab
     * is selected/deselected. When visible, auto-scrolling is enabled; when hidden,
     * scrolling is suppressed to prevent layout jumping and reduce background CPU usage.
     */
    private void wireConsoleVisibilityTracking() {
        int consoleIndex = innerTabbedPane.indexOfTab("Console");
        if (consoleIndex < 0) {
            return;
        }

        ChangeListener listener = e -> {
            boolean consoleSelected = innerTabbedPane.getSelectedIndex() == consoleIndex;
            if (consolePanel != null && consolePanel.getUnifiedConsole() != null) {
                if (consoleSelected) {
                    consolePanel.getUnifiedConsole().onPanelActivated();
                } else {
                    consolePanel.getUnifiedConsole().onPanelDeactivated();
                }
            }
        };
        innerTabbedPane.addChangeListener(listener);
    }

    private void wireToolbarCallbacks() {
        toolbar.setOnRestart(this::restartNode);
        toolbar.setOnSyncToggle(this::toggleSync);
        toolbar.setOpenPhoenix(() -> openWebUi("/phoenix"));
        toolbar.setOpenClassic(() -> openWebUi("/classic"));
        toolbar.setOpenApi(() -> openWebUi("/api-doc"));
        toolbar.setOnEditConf(this::editConf);
        toolbar.setOnPopOff10(() -> popOff(10));
        toolbar.setOnPopOff100(() -> popOff(100));
        toolbar.setOnDbCheck(() -> dbCheckAction());
        // Wire hamburger menu button: delegate to console panel, passing toolbar's menuButton for popup positioning
        toolbar.setOnMenuToggle(() -> consolePanel.toggleMenu(toolbar.getMenuButton()));
    }

    /**
     * v4 (P1.3): pause/resume is owned by the Signum instance (PUSH).
     * The GUI no longer keeps a shadow syncPaused flag — the toolbar icon is
     * driven by onOperatingStateChanged (and by the explicit update below).
     */
    public void toggleSync() {
        Signum node = signum;
        if (node == null) {
            return;
        }
        Signum.OperatingState os = node.getOperatingState();
        if (os == Signum.OperatingState.PAUSED_USER || os == Signum.OperatingState.PAUSED_SYSTEM) {
            node.resumeByUser();
        } else {
            node.pauseByUser();
        }
        Signum.OperatingState after = node.getOperatingState();
        boolean paused = after == Signum.OperatingState.PAUSED_USER || after == Signum.OperatingState.PAUSED_SYSTEM;
        toolbar.updateSyncIcon(paused);
    }

    /** Copy from NodeConsolePanel.editConf */
    public void editConf() {
        Path nodeFolder = application.utils.io.PathUtils.resolvePath(confFolder).resolve("node");
        Path path = nodeFolder.resolve(Signum.PROPERTIES_NAME);
        if (!java.nio.file.Files.exists(path)) {
            path = nodeFolder.resolve(Signum.DEFAULT_PROPERTIES_NAME);
        }
        File file = path.toFile();
        if (!file.exists()) {
            JOptionPane.showMessageDialog(this,
                    "Could not find conf file: " + Signum.PROPERTIES_NAME + " or " + Signum.DEFAULT_PROPERTIES_NAME,
                    "File not found", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            Desktop.getDesktop().open(file);
        } catch (java.io.IOException e) {
            LOGGER.error("Could not open conf file with default editor", e);
        }
    }

    /** Copy from NodeConsolePanel.openWebUi */
    public void openWebUi(String path) {
        try {
            Signum node = signum;
            PropertyService propertyService = node != null ? node.getPropertyService() : null;
            if (propertyService == null) {
                JOptionPane.showMessageDialog(this,
                        "PropertyService not available. Node may not be started.",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            int port = propertyService.getInt(Props.API_PORT);
            String httpPrefix = propertyService.getBoolean(Props.API_SSL) ? "https://" : "http://";
            String address = httpPrefix + "localhost:" + port + path;
            try {
                Desktop.getDesktop().browse(new URI(address));
            } catch (Exception e) {
                LOGGER.error("Could not open browser", e);
            }
        } catch (Exception e) {
            LOGGER.error("Could not access PropertyService", e);
        }
    }

    /**
     * Requests a manual pop-off of the last {@code count} blocks.
     * <p>
     * Same behavior as the original SignumGUI: the <b>manual</b> pop-off path
     * ({@code BlockchainProcessor.popOff(int)}) on a dedicated background
     * thread — never on the EDT, since a pop-off can take a while. The work
     * runs inside this profile's {@code NodeLogContext} so all progress log
     * lines ("Request adds N blocks to pop off.", "Block processing threads
     * paused for pop-off.", "Pop-off height to X from Y", ...) are routed to
     * this profile's Node Console. Repeated clicks while a pop-off is already
     * queued add more blocks ("Request adds N blocks to pop off.").
     * </p>
     */
    public void popOff(int count) {
        Signum node = signum;
        BlockchainProcessor blockchainProcessor = node != null ? node.getBlockchainProcessor() : null;
        if (blockchainProcessor == null) {
            return;
        }
        new Thread(() -> application.utils.logging.NodeLogContext
                .runIn("node", profile.getName(), () -> blockchainProcessor.popOff(count))).start();
    }

    /** Copy from NodeConsolePanel.dbCheckAction */
    public void dbCheckAction() {
        Signum node = signum;
        BlockchainProcessor blockchainProcessor = node != null ? node.getBlockchainProcessor() : null;
        if (blockchainProcessor == null) {
            JOptionPane.showMessageDialog(this, "Blockchain processor not initialized.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        blockchainProcessor.checkDatabaseStateRequest();
    }

    private String determineConfFolder() {
        Properties props = profile.getProperties();
        String network = props.getProperty("network", "mainnet");
        return "conf/" + network;
    }

    private void restartNode() {
        // v4 (P1.6): the node lifecycle restart is owned by NodeModule
        // (restartNode(name) = stop + start on the same Signum instance).
        // The console panel provides the user-facing progress dialog and
        // delegates the actual restart to NodeModule.
        LOGGER.info("Restart requested for profile: {}", profile.getName());
        if (consolePanel != null) {
            consolePanel.restartNode();
        }
        if (infoBar != null) {
            infoBar.refreshData();
        }
    }

    public NodeProfile getProfile() { return profile; }
    public NodeConsolePanel getConsolePanel() { return consolePanel; }
    public NodeConfigurationPanel getConfigurationPanel() { return configurationPanel; }
    public LoggerConfigurationPanel getLoggingPanel() { return loggingPanel; }
    public JTabbedPane getInnerTabbedPane() { return innerTabbedPane; }
    public NodeInfoBar getInfoBar() { return infoBar; }
    public NodeToolbar getToolbar() { return toolbar; }

    /**
     * Re-evaluates this profile's information bar so its cross-profile resource-conflict
     * warnings (API/P2P/WebSocket port, database) reflect the profiles that are RUNNING
     * at the moment it is viewed.
     * <p>
     * A {@link NodeInfoBar} computes its conflicts only when it is built and when its own
     * profile restarts — it does not observe the other profiles. Because the set of running
     * profiles can change afterwards (e.g. another profile auto-starts later), the red
     * conflict chips would otherwise go stale. This is invoked when the profile's tab is
     * (re)selected so that switching from a running profile to a conflicting one surfaces
     * the warning immediately.
     * </p>
     * <p>No-op when the info bar is not present.</p>
     */
    public void refreshConflictWarnings() {
        if (infoBar != null) {
            infoBar.refreshData();
        }
    }

    /** Returns the injected Signum facade (null if node not started). */
    public Signum getSignum() { return signum; }

    /**
     * Switches the inner tabbed pane to the Console tab.
     * Used by NodeConsolePanel to ensure command panel animation is visible.
     */
    public void switchToConsoleTab() {
        int consoleIndex = innerTabbedPane.indexOfTab("Console");
        if (consoleIndex >= 0 && innerTabbedPane.getSelectedIndex() != consoleIndex) {
            innerTabbedPane.setSelectedIndex(consoleIndex);
            LOGGER.debug("Switched to Console tab for panel visibility");
        }
    }

    public void stopNode() {
        // Single lifecycle entry point (v4): NodeModule stops this profile's node
        // asynchronously on the lifecycle thread (never blocks the EDT).
        NodeModule.getInstance().stopNode(profile.getName());
        LOGGER.info("Stop requested for profile: {}", profile.getName());
    }

    public void startNode() {
        // Single lifecycle entry point (v4): NodeModule creates (if missing) and
        // starts the node for this profile.
        Signum started = null;
        try {
            started = NodeModule.getInstance().startNode(profile.getName());
        } catch (Exception e) {
            LOGGER.error("Start failed for profile: {}", profile.getName(), e);
        }
        LOGGER.info("Start requested for profile: {}", profile.getName());
        onNodeStarted(started);
    }

    /**
     * Called after this profile's node was started (toolbar Start, this panel's
     * {@link #startNode()}, or the console's Start). Binds the panel to the
     * returned instance — single {@link Signum.StateListener} (v4 D3, idempotent)
     * — and attaches the console subscriber so the profile console receives the
     * node's logs (replayed from the ProfileLogger buffer if attached late).
     */
    private void onNodeStarted(Signum signum) {
        if (signum == null) {
            return;
        }
        adoptSignum(signum);
        if (consolePanel != null) {
            consolePanel.ensureProfileLoggerAttached();
        }
    }

    /**
     * Push trigger (PUSH, called by the single {@link Signum.StateListener} or by
     * {@link NodePanel}). Threads the update to the EDT and delegates to
     * {@link #refreshFromSignum(Signum.State, Signum.State)} — it never carries
     * data itself.
     */
    public void onNodeStateChanged(Signum.State oldState, Signum.State newState) {
        SwingUtilities.invokeLater(() -> refreshFromSignum(oldState, newState));
    }

    /**
     * Single refresh point (D2/D3): this panel owns <b>no</b> node state — it
     * re-reads everything from the Signum (single source of truth):
     * <pre>
     *   signum == null  → child panels render placeholders / inactive
     *   otherwise       → child panels render the real values from the Signum
     * </pre>
     * Must be called on the EDT (see {@link #onNodeStateChanged}).
     *
     * @param oldState  previous node state (null on initial refresh)
     * @param newState  current node state, as reported by the Signum
     */
    public void refreshFromSignum(Signum.State oldState, Signum.State newState) {
        String profileName = profile.getName();
        LOGGER.info("[{}] State change: {} -> {}", profileName, oldState, newState);

        int consoleIndex = innerTabbedPane.indexOfTab("Console");
        if (consoleIndex >= 0) {
            // Use name() instead of Unicode symbols to avoid square characters
            String stateText = newState.name().toLowerCase();
            innerTabbedPane.setTitleAt(consoleIndex,
                    "Console" + (stateText.isEmpty() ? "" : " [" + stateText + "]"));
            // Add tooltip with detailed state information for hover
            innerTabbedPane.setToolTipTextAt(consoleIndex,
                    "Node State: " + stateText + "\nProfile: " + profile.getName());
            // Set state icon in the Console tab title so the user can see node state at a glance
            Icon stateIcon = getStateIcon(newState);
            innerTabbedPane.setIconAt(consoleIndex, stateIcon);
        }

        if (infoBar != null) {
            infoBar.refreshState();
        }

        // PUSH: this profile's state change (start/stop) alters which resources are held
        // by running profiles, so EVERY other visible info bar must re-evaluate its
        // cross-profile conflict warnings. This surfaces the red conflict on an already-open,
        // conflicting profile the moment another profile starts (and clears it when one stops).
        NodeInfoBar.refreshAllConflicts();

        if (toolbar != null) {
            toolbar.updateButtonStates(newState);
        }

        // Forward lifecycle events to the console panel so it can manage MetricsPanel
        // visibility in sync with the node state. When the node reaches READY/RUNNING,
        // Signum.getPropertyService() is available and the MetricsPanel can initialize.
        if (consolePanel != null) {
            consolePanel.onNodeStateChanged(oldState, newState);
        }
    }

    /**
     * Returns a FontAwesome-based icon for the given node lifecycle state.
     * Used to display state indicators in the inner JTabbedPane tab titles.
     */
    private Icon getStateIcon(Signum.State state) {
        int size = GuiIcons.sizeTiny();
        return switch (state) {
            case RUNNING -> GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.CIRCLE, size, GuiColors.getPeerActive());
            case ERROR -> GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.EXCLAMATION_TRIANGLE, size, GuiColors.getContrastRed());
            case STARTING -> GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.SPINNER, size, new java.awt.Color(255, 193, 7));
            case STOPPED -> GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.STOP, size, GuiColors.getFaintText());
            case CREATED -> GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.CIRCLE_O, size, GuiColors.getFaintText());
            case INITIALIZED -> GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.CHECK_CIRCLE_O, size, new java.awt.Color(100, 149, 237));
            case STOPPING -> GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.PAUSE, size, new java.awt.Color(103, 58, 183));
        };
    }

    public void onStatusMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            LOGGER.debug("[{}] Status: {}", profile.getName(), message);
        });
    }

    public void onError(String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            LOGGER.error("[{}] Error: {}", profile.getName(), errorMessage);
            JOptionPane.showMessageDialog(
                    this,
                    "Node error: " + errorMessage,
                    "Error - " + profile.getName(),
                    JOptionPane.ERROR_MESSAGE
            );
        });
    }
}