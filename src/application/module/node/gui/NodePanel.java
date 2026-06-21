package application.module.node.gui;

import application.module.appearance.AppearanceModule;
import application.module.node.profile.NodeProfile;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiFontManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Main Node panel that acts as a JTabbedPane container.
 * Discovers all NodeProfile configurations from conf/node/*.properties
 * and creates a profile tab for each one.
 */
@SuppressWarnings("serial")
public class NodePanel extends JPanel {
    private static final Logger LOGGER = LoggerFactory.getLogger(NodePanel.class);
    private static final Path NODE_CONF_DIR = Paths.get("conf", "node");

    private final JFrame parentFrame;
    private final JTabbedPane profileTabbedPane;
    private final Map<String, NodeProfilePanel> profilePanels;

    public NodePanel(JFrame parentFrame) {
        this.parentFrame = parentFrame;
        this.profilePanels = new LinkedHashMap<>();

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        profileTabbedPane = new JTabbedPane(SwingConstants.TOP);
        GuiFontManager.applyDefaultFont(profileTabbedPane);
        add(profileTabbedPane, BorderLayout.CENTER);

        // Discover and load all node profiles
        List<NodeProfile> profiles = discoverNodeProfiles();

        if (profiles.isEmpty()) {
            LOGGER.warn("No node profiles found in {}", NODE_CONF_DIR);
            // Create a default profile if none exists
            NodeProfile defaultProfile = new NodeProfile("default");
            addProfileTab(defaultProfile);
            profiles.add(defaultProfile);
        }

        for (NodeProfile profile : profiles) {
            addProfileTab(profile);
        }

        // Register for appearance updates
        AppearanceModule.registerAppearanceListener(() -> {
            GuiFontManager.applyDefaultFont(profileTabbedPane);
        });
    }

    /**
     * Discovers all node profiles from conf/node/*.properties files.
     * 
     * @return List of discovered NodeProfile objects
     */
    private List<NodeProfile> discoverNodeProfiles() {
        List<NodeProfile> profiles = new ArrayList<>();

        if (!Files.exists(NODE_CONF_DIR)) {
            try {
                Files.createDirectories(NODE_CONF_DIR);
                LOGGER.info("Created node profiles directory: {}", NODE_CONF_DIR);
            } catch (Exception e) {
                LOGGER.error("Could not create node profiles directory", e);
            }
            return profiles;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(NODE_CONF_DIR, "*.properties")) {
            for (Path file : stream) {
                try {
                    String profileName = file.getFileName().toString().replace(".properties", "");
                    NodeProfile profile = new NodeProfile(profileName);

                    // Load properties from file
                    try (InputStream is = Files.newInputStream(file)) {
                        profile.getProperties().load(is);
                    }

                    profiles.add(profile);
                    LOGGER.info("Loaded node profile: {} from {}", profileName, file.getFileName());
                } catch (Exception e) {
                    LOGGER.error("Error loading profile from {}", file.getFileName(), e);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error scanning node profiles directory", e);
        }

        return profiles;
    }

    /**
     * Creates and adds a new tab for the given NodeProfile.
     * 
     * @param profile The NodeProfile to create a tab for
     */
    private void addProfileTab(NodeProfile profile) {
        NodeProfilePanel panel = new NodeProfilePanel(parentFrame, profile);
        String displayName = profile.getName();
        profileTabbedPane.addTab(displayName, panel);
        profilePanels.put(displayName, panel);
    }

    /**
     * Gets the NodeProfilePanel for a specific profile.
     * 
     * @param profileName The name of the profile
     * @return The NodeProfilePanel or null if not found
     */
    public NodeProfilePanel getProfilePanel(String profileName) {
        return profilePanels.get(profileName);
    }

    /**
     * Gets all profile panels.
     * 
     * @return Map of profile name to NodeProfilePanel
     */
    public Map<String, NodeProfilePanel> getAllProfilePanels() {
        return new LinkedHashMap<>(profilePanels);
    }

    /**
     * Gets the profile tabbed pane.
     * 
     * @return The JTabbedPane containing all profile tabs
     */
    public JTabbedPane getProfileTabbedPane() {
        return profileTabbedPane;
    }

    /**
     * Gets the parent JFrame.
     * 
     * @return The parent JFrame
     */
    public JFrame getParentFrame() {
        return parentFrame;
    }
}