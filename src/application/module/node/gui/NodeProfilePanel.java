package application.module.node.gui;

import application.module.appearance.AppearanceModule;
import application.module.node.BlockchainProcessor;
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
 * BlockchainProcessor, PropertyService, Blockchain, etc. The Signum facade owns
 * the {@link NodeCoreContext} implementation detail (Facade Pattern).
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

    /** Tracks sync pause state locally since BlockchainProcessor has no getter */
    private boolean syncPaused = false;

    /**
     * Creates a profile panel with an injected Signum facade.
     * The facade provides per-instance access to BlockchainProcessor, PropertyService, etc.,
     * replacing both static Signum.getXxx() calls and direct NodeCoreContext access.
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
        LOGGER.info("[DIAG] NodeProfilePanel constructor START for profile: {}", profile.getName());
        
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

            LOGGER.info("[DIAG] Creating NodeInfoBar for profile: {}", profile.getName());
            infoBar = new NodeInfoBar(profile);
            
            LOGGER.info("[DIAG] Creating NodeToolbar for profile: {}", profile.getName());
            toolbar = new NodeToolbar(profile);

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

            LOGGER.info("[DIAG] Creating NodeConsolePanel for profile: {}", profile.getName());
            consolePanel = new NodeConsolePanel(parentFrame, profile);
            if (signum != null) {
                consolePanel.setSignum(signum);
            }
            consolePanel.setSwitchToConsoleAction(() -> switchToConsoleTab());
            // Late binding: if the console panel starts the node itself (this panel
            // was constructed before the Signum existed), adopt the started instance.
            consolePanel.setOnSignumStarted(this::adoptSignum);
            innerTabbedPane.addTab("Console", consolePanel);
            LOGGER.info("[DIAG] Console tab added successfully");

            LOGGER.info("[DIAG] Creating NodeConfigurationPanel for profile: {}", profile.getName());
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
            LOGGER.info("[DIAG] Configuration tab added successfully");

            LOGGER.info("[DIAG] Creating LoggerConfigurationPanel for profile: {}", profile.getName());
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
            LOGGER.info("[DIAG] Logging tab added successfully");

            LOGGER.info("[DIAG] Adding innerTabbedPane to CENTER (total tabs: {})", innerTabbedPane.getTabCount());
            add(innerTabbedPane, BorderLayout.CENTER);
            
            LOGGER.info("[DIAG] Wiring toolbar callbacks for profile: {}", profile.getName());
            wireToolbarCallbacks();
            
            LOGGER.info("[DIAG] Wiring console visibility tracking for profile: {}", profile.getName());
            wireConsoleVisibilityTracking();

            // Adopt the Signum if it already exists at construction time:
            // registers the single state listener (PUSH) + performs the initial
            // refresh. No-op if the node does not exist yet — in that case it is
            // adopted later via the console panel's onSignumStarted callback.
            adoptSignum(this.signum);

            AppearanceModule.registerAppearanceListener(() -> {
                GuiFontManager.applyDefaultFont(innerTabbedPane);
            });

            LOGGER.info("[DIAG] NodeProfilePanel constructor COMPLETED SUCCESSFULLY for profile: {} (tabs: {})", 
                    profile.getName(), innerTabbedPane.getTabCount());
        } catch (Exception e) {
            LOGGER.error("[DIAG] NodeProfilePanel constructor FAILED for profile: {}", profile.getName(), e);
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
     * {@code Signum.startNode()} and hands the instance back. Idempotent for the
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
        stateListener = (s, oldState, newState) -> onNodeStateChanged(oldState, newState);
        newSignum.addStateListener(stateListener);
        newSignum.setGuiPanel(this);
        // Initial refresh so the UI immediately reflects the current node state.
        SwingUtilities.invokeLater(() -> refreshFromSignum(null, newSignum.getState()));
        LOGGER.info("Adopted Signum for profile: {} (state={})", profile.getName(), newSignum.getState());
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
        toolbar.setOnSyncToggle(() -> {
            toggleSync();
            toolbar.updateSyncIcon(syncPaused);
        });
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

    /** Copy from NodeConsolePanel.syncButtonAction */
    public void toggleSync() {
        Signum node = signum;
        BlockchainProcessor blockchainProcessor = node != null ? node.getBlockchainProcessor() : null;
        if (blockchainProcessor != null) {
            syncPaused = !syncPaused;
            blockchainProcessor.setSyncPaused(syncPaused);
        }
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

    /** Copy from NodeConsolePanel.popOff */
    public void popOff(int count) {
        Signum node = signum;
        BlockchainProcessor blockchainProcessor = node != null ? node.getBlockchainProcessor() : null;
        if (blockchainProcessor == null) {
            return;
        }
        int height = (node != null && node.getBlockchain() != null) 
                ? node.getBlockchain().getHeight() : 0;
        int targetHeight = Math.max(0, height - count);
        if (!blockchainProcessor.isSkipDbCheckOnManualPopOff()) {
            blockchainProcessor.checkDatabaseStateRequest();
        }
        blockchainProcessor.popOffTo(targetHeight);
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
        if (consolePanel != null) {
            consolePanel.stopNode();
        }
        LOGGER.info("Stop requested for profile: {}", profile.getName());
    }

    public void startNode() {
        Signum s = this.signum; if (s != null) s.start();
        LOGGER.info("Start requested for profile: {}", profile.getName());
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