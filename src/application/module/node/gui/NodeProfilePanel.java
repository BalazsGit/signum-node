package application.module.node.gui;

import application.module.appearance.AppearanceModule;
import application.module.node.BlockchainProcessor;
import application.module.node.Signum;
import application.module.node.gui.configuration.LoggerConfigurationPanel;
import application.module.node.gui.configuration.NodeConfigurationPanel;
import application.module.node.instance.NodeCoreContext;
import application.module.node.lifecycle.NodeLifecycleManager;
import application.module.node.lifecycle.NodeLifecycleState;
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
    /** Per-instance Signum facade (may be null if the node has not been started yet). */
    private final Signum signum;
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
        LOGGER.info("[DIAG] NodeProfilePanel constructor START for profile: {}", profile.getName());
        
        try {
            this.parentFrame = parentFrame;
            this.profile = profile;
            this.signum = signum;
            this.confFolder = determineConfFolder();

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
            innerTabbedPane.addTab("Console", consolePanel);
            LOGGER.info("[DIAG] Console tab added successfully");

            LOGGER.info("[DIAG] Creating NodeConfigurationPanel for profile: {}", profile.getName());
            configurationPanel = new NodeConfigurationPanel(
                    this::restartNode,
                    this.confFolder,
                    () -> {
                    },
                    null
            );
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

            AppearanceModule.registerAppearanceListener(() -> {
                GuiFontManager.applyDefaultFont(innerTabbedPane);
            });

            LOGGER.info("[DIAG] NodeProfilePanel constructor COMPLETED SUCCESSFULLY for profile: {} (tabs: {})", 
                    profile.getName(), innerTabbedPane.getTabCount());
        } catch (Exception e) {
            LOGGER.error("[DIAG] NodeProfilePanel constructor FAILED for profile: {}", profile.getName(), e);
            throw e;
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
        NodeCoreContext ctx = signum != null ? signum.getContext() : null;
        BlockchainProcessor blockchainProcessor = ctx != null ? ctx.getBlockchainProcessor() : null;
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
            NodeCoreContext ctx = signum != null ? signum.getContext() : null;
            PropertyService propertyService = ctx != null ? ctx.getPropertyService() : null;
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
        NodeCoreContext ctx = signum != null ? signum.getContext() : null;
        BlockchainProcessor blockchainProcessor = ctx != null ? ctx.getBlockchainProcessor() : null;
        if (blockchainProcessor == null) {
            return;
        }
        int height = (ctx != null && ctx.getBlockchain() != null) 
                ? ctx.getBlockchain().getHeight() : 0;
        int targetHeight = Math.max(0, height - count);
        if (!blockchainProcessor.isSkipDbCheckOnManualPopOff()) {
            blockchainProcessor.checkDatabaseStateRequest();
        }
        blockchainProcessor.popOffTo(targetHeight);
    }

    /** Copy from NodeConsolePanel.dbCheckAction */
    public void dbCheckAction() {
        NodeCoreContext ctx = signum != null ? signum.getContext() : null;
        BlockchainProcessor blockchainProcessor = ctx != null ? ctx.getBlockchainProcessor() : null;
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
    /** Returns the injected NodeCoreContext (null if node not started). */
    public NodeCoreContext getContext() { return signum != null ? signum.getContext() : null; }

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
        NodeLifecycleManager.getInstance().startProfile(profile.getName());
        LOGGER.info("Start requested for profile: {}", profile.getName());
    }

    public void onNodeStateChanged(NodeLifecycleState oldState, NodeLifecycleState newState) {
        SwingUtilities.invokeLater(() -> {
            String profileName = profile.getName();
            LOGGER.info("[{}] State change: {} -> {}", profileName, oldState, newState);

            int consoleIndex = innerTabbedPane.indexOfTab("Console");
            if (consoleIndex >= 0) {
                // Use getDescription() instead of Unicode symbols to avoid square characters
                String stateText = newState.getDescription();
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
        });
    }

    /**
     * Returns a FontAwesome-based icon for the given node lifecycle state.
     * Used to display state indicators in the inner JTabbedPane tab titles.
     */
    private Icon getStateIcon(NodeLifecycleState state) {
        int size = GuiIcons.sizeTiny();
        return switch (state) {
            case RUNNING -> GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.CIRCLE, size, GuiColors.getPeerActive());
            case PAUSED -> GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.PAUSE, size, new java.awt.Color(103, 58, 183));
            case ERROR -> GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.EXCLAMATION_TRIANGLE, size, GuiColors.getContrastRed());
            case INITIALIZING, STOPPING -> GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.SPINNER, size, new java.awt.Color(255, 193, 7));
            case READY -> GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.CHECK_CIRCLE_O, size, new java.awt.Color(100, 149, 237));
            case STOPPED -> GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.STOP, size, GuiColors.getFaintText());
            case IDLE -> GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.CIRCLE_O, size, GuiColors.getFaintText());
            case WAITING_FOR_DATABASE -> GuiIcons.build(jiconfont.icons.font_awesome.FontAwesome.DATABASE, size, new java.awt.Color(255, 193, 7));
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