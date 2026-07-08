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
import javax.swing.Icon;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
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
 * Uses constructor-injected {@link NodeCoreContext} for per-instance access to
 * BlockchainProcessor, PropertyService, Blockchain, etc., replacing the previous
 * static {@code Signum.getXxx()} calls.
 */
@SuppressWarnings("serial")
public class NodeProfilePanel extends JPanel {
    private static final Logger LOGGER = LoggerFactory.getLogger(NodeProfilePanel.class);

    private final JFrame parentFrame;
    private final NodeProfile profile;
    /** Per-instance node context (may be null if the node has not been started yet). */
    private final NodeCoreContext context;
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
     * Creates a profile panel with an injected NodeCoreContext.
     * The context provides per-instance access to BlockchainProcessor, PropertyService, etc.,
     * replacing the previous static Signum.getXxx() calls.
     *
     * @param parentFrame Parent JFrame for dialogs
     * @param profile     The node profile
     * @param context     Per-instance node context (may be null if not yet started)
     */
    public NodeProfilePanel(JFrame parentFrame, NodeProfile profile, NodeCoreContext context) {
        this.parentFrame = parentFrame;
        this.profile = profile;
        this.context = context;
        this.confFolder = determineConfFolder();

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        infoBar = new NodeInfoBar(profile);
        toolbar = new NodeToolbar(profile);

        // Wrap infoBar in a responsive scroll pane so info chips are accessible when window is narrow
        // The separator border is applied to the scroll pane (not NodeInfoBar) so it appears below the scrollbar
        ResponsiveToolbarScrollPane infoBarScrollPane = new ResponsiveToolbarScrollPane(infoBar,
                new java.awt.Insets(2, 4, 0, 4));
        infoBarScrollPane.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, GuiColors.getSeparator()),
                BorderFactory.createEmptyBorder(0, 0, 2, 0)
        ));

        // Toolbar now includes its own internal ResponsiveToolbarScrollPane (matching ConsolePanel pattern).
        // Only add a thin border spacer panel to separate toolbar from infoBar visually.
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

        consolePanel = new NodeConsolePanel(parentFrame, profile);
        // Inject context into console panel for per-instance access
        if (context != null) {
            consolePanel.setNodeContext(context);
        }
        // Wire callback so console panel can switch to Console tab when showing command input
        consolePanel.setSwitchToConsoleAction(() -> switchToConsoleTab());
        innerTabbedPane.addTab("Console", consolePanel);

        configurationPanel = new NodeConfigurationPanel(
                this::restartNode,
                this.confFolder,
                () -> {
                },
                null
        );
        innerTabbedPane.addTab("Configuration", configurationPanel);

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

        add(innerTabbedPane, BorderLayout.CENTER);
        wireToolbarCallbacks();

        AppearanceModule.registerAppearanceListener(() -> {
            GuiFontManager.applyDefaultFont(innerTabbedPane);
        });

        LOGGER.info("Created NodeProfilePanel for profile: {}", profile.getName());
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
        BlockchainProcessor blockchainProcessor = context != null ? context.getBlockchainProcessor() : null;
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
            PropertyService propertyService = context != null ? context.getPropertyService() : null;
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
        BlockchainProcessor blockchainProcessor = context != null ? context.getBlockchainProcessor() : null;
        if (blockchainProcessor == null) {
            return;
        }
        int height = context != null && context.getBlockchain() != null 
                ? context.getBlockchain().getHeight() : 0;
        int targetHeight = Math.max(0, height - count);
        if (!blockchainProcessor.isSkipDbCheckOnManualPopOff()) {
            blockchainProcessor.checkDatabaseStateRequest();
        }
        blockchainProcessor.popOffTo(targetHeight);
    }

    /** Copy from NodeConsolePanel.dbCheckAction */
    public void dbCheckAction() {
        BlockchainProcessor blockchainProcessor = context != null ? context.getBlockchainProcessor() : null;
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
    public NodeCoreContext getContext() { return context; }

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