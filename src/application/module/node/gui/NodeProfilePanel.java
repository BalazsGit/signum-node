package application.module.node.gui;

import application.module.appearance.AppearanceModule;
import application.module.node.gui.configuration.NodeConfigurationPanel;
import application.module.node.profile.NodeProfile;
import application.utils.gui.GuiFontManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.util.Properties;

/**
 * Profile panel for a single NodeProfile instance.
 * Acts as a JTabbedPane container with tabs for:
 * - Console (NodeConsolePanel)
 * - Configuration (NodeConfigurationPanel)
 */
@SuppressWarnings("serial")
public class NodeProfilePanel extends JPanel {
    private static final Logger LOGGER = LoggerFactory.getLogger(NodeProfilePanel.class);

    private final JFrame parentFrame;
    private final NodeProfile profile;
    private final JTabbedPane innerTabbedPane;
    private final NodeConsolePanel consolePanel;
    private final NodeConfigurationPanel configurationPanel;

    /**
     * Creates a new NodeProfilePanel for the given profile.
     * 
     * @param parentFrame The parent JFrame for dialogs
     * @param profile     The NodeProfile to manage
     */
    public NodeProfilePanel(JFrame parentFrame, NodeProfile profile) {
        this.parentFrame = parentFrame;
        this.profile = profile;

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        innerTabbedPane = new JTabbedPane(SwingConstants.TOP);
        GuiFontManager.applyDefaultFont(innerTabbedPane);

        // Determine conf folder from profile properties or use default
        String confFolder = determineConfFolder();

        // Create Console tab
        consolePanel = new NodeConsolePanel(parentFrame, profile);
        innerTabbedPane.addTab("Console", consolePanel);

        // Create Configuration tab
        configurationPanel = new NodeConfigurationPanel(
                this::restartNode,
                confFolder,
                () -> {
                }, // backAction - not needed in tab mode
                null // switchAction - not needed in tab mode
        );
        innerTabbedPane.addTab("Configuration", configurationPanel);

        add(innerTabbedPane, BorderLayout.CENTER);

        // Register for appearance updates
        AppearanceModule.registerAppearanceListener(() -> {
            GuiFontManager.applyDefaultFont(innerTabbedPane);
        });

        LOGGER.info("Created NodeProfilePanel for profile: {}", profile.getName());
    }

    /**
     * Determines the configuration folder path from the profile properties.
     * 
     * @return The conf folder path, defaults to "conf/mainnet"
     */
    private String determineConfFolder() {
        Properties props = profile.getProperties();

        // Check if the profile specifies a custom conf folder
        String network = props.getProperty("network", "mainnet");
        return "conf/" + network;
    }

    /**
     * Restarts the node for this profile.
     */
    private void restartNode() {
        LOGGER.info("Restart requested for profile: {}", profile.getName());
        // TODO: Implement actual restart logic
        if (consolePanel != null) {
            consolePanel.restartNode();
        }
    }

    /**
     * Gets the NodeProfile associated with this panel.
     * 
     * @return The NodeProfile
     */
    public NodeProfile getProfile() {
        return profile;
    }

    /**
     * Gets the console panel for this profile.
     * 
     * @return The NodeConsolePanel
     */
    public NodeConsolePanel getConsolePanel() {
        return consolePanel;
    }

    /**
     * Gets the configuration panel for this profile.
     * 
     * @return The NodeConfigurationPanel
     */
    public NodeConfigurationPanel getConfigurationPanel() {
        return configurationPanel;
    }

    /**
     * Gets the inner tabbed pane.
     * 
     * @return The JTabbedPane containing Console and Configuration tabs
     */
    public JTabbedPane getInnerTabbedPane() {
        return innerTabbedPane;
    }

    /**
     * Stops the node for this profile.
     */
    public void stopNode() {
        if (consolePanel != null) {
            consolePanel.stopNode();
        }
        LOGGER.info("Stop requested for profile: {}", profile.getName());
    }

    /**
     * Starts the node for this profile.
     */
    public void startNode() {
        if (consolePanel != null) {
            consolePanel.startNode();
        }
        LOGGER.info("Start requested for profile: {}", profile.getName());
    }
}