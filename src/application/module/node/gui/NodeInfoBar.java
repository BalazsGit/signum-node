package application.module.node.gui;

import application.module.appearance.AppearanceModule;
import application.module.node.lifecycle.NodeLifecycleManager;
import application.module.node.lifecycle.NodeLifecycleState;
import application.module.node.profile.NodeProfile;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiFontManager;
import application.utils.gui.GuiIcons;

import jiconfont.icons.font_awesome.FontAwesome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.HashMap;
import java.util.Map;

/**
 * Information bar displayed at the top of a NodeProfilePanel tab.
 * Shows key runtime information about the node profile in a horizontal strip:
 * - Profile name
 * - Status icon (FontAwesome, dynamic size) with detailed hover tooltip
 * - Network type (Mainnet/Testnet) with color indicator
 * - Lifecycle state with icon
 * - API port
 * - P2P port
 * - Database engine
 * - Database port (if applicable)
 *
 * Follows the Observer pattern: listens for lifecycle state changes via NodeLifecycleManager.
 */
@SuppressWarnings("serial")
public class NodeInfoBar extends JPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeInfoBar.class);

    /** Spacing between info chips */
    private static final int CHIP_GAP = 8;

    private final NodeProfile profile;
    private JLabel profileNameLabel;
    private JLabel statusIconLabel;
    private JLabel networkLabel;
    private JLabel stateLabel;
    private JLabel apiPortLabel;
    private JLabel p2pPortLabel;
    private JLabel databaseEngineLabel;
    private JLabel databasePortLabel;
    private JLabel websocketPortLabel;

    /** Maps info key to chip component for quick access */
    private final Map<String, JLabel> labelMap = new HashMap<>();

    /**
     * Creates a new NodeInfoBar for the given profile.
     *
     * @param profile The NodeProfile to display information for
     */
    public NodeInfoBar(NodeProfile profile) {
        this.profile = profile;
        initialize();
    }

    /**
     * Initializes the info bar layout and components.
     */
    private void initialize() {
        setLayout(new FlowLayout(FlowLayout.LEFT, CHIP_GAP, 5));
        setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        setOpaque(true);

        // Create info chips
        profileNameLabel = createInfoChip("Profile", "--",
                GuiIcons.build(FontAwesome.USER, GuiIcons.sizeTiny(), GuiColors.getButtonIcon()));

        // Status icon label - FontAwesome icon next to profile name representing node state
        // with detailed hover tooltip. Size scales dynamically with font size.
        statusIconLabel = new JLabel();
        statusIconLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        GuiFontManager.applyDefaultFont(statusIconLabel);

        networkLabel = createInfoChip("Network", "--",
                GuiIcons.build(FontAwesome.GLOBE, GuiIcons.sizeTiny(), GuiColors.getButtonIcon()));
        stateLabel = createInfoChip("State", "--", null);
        apiPortLabel = createInfoChip("API Port", "--",
                GuiIcons.build(FontAwesome.LINK, GuiIcons.sizeTiny(), GuiColors.getButtonIcon()));
        p2pPortLabel = createInfoChip("P2P Port", "--",
                GuiIcons.build(FontAwesome.SHARE_ALT, GuiIcons.sizeTiny(), GuiColors.getButtonIcon()));
        databaseEngineLabel = createInfoChip("Database", "--",
                GuiIcons.database(GuiIcons.sizeTiny()));
        databasePortLabel = createInfoChip("DB Port", "--",
                GuiIcons.database(GuiIcons.sizeTiny()));
        websocketPortLabel = createInfoChip("WebSocket", "--",
                GuiIcons.build(FontAwesome.BOLT, GuiIcons.sizeTiny(), GuiColors.getButtonIcon()));

        // Add chips to panel
        add(profileNameLabel);
        add(statusIconLabel);
        add(networkLabel);
        add(stateLabel);
        add(apiPortLabel);
        add(p2pPortLabel);
        add(databaseEngineLabel);
        add(databasePortLabel);
        add(websocketPortLabel);

        // Register for appearance updates
        AppearanceModule.registerAppearanceListener(() -> {
            SwingUtilities.invokeLater(this::refreshStyles);
        });

        // Populate initial data from profile properties
        refreshData();

        LOGGER.debug("Created NodeInfoBar for profile: {}", profile.getName());
    }

    /**
     * Creates an info chip (label with icon, bold key, and value).
     *
     * @param key   The label key (e.g., "Profile", "Network")
     * @param value The initial value
     * @param icon  Optional icon to display
     * @return Configured JLabel component
     */
    private JLabel createInfoChip(String key, String value, Icon icon) {
        String htmlText = buildHtmlText(key, value);
        JLabel label = new JLabel(htmlText);
        label.setIcon(icon);
        GuiFontManager.applyDefaultFont(label);
        label.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        labelMap.put(key.toLowerCase(), label);
        return label;
    }

    /**
     * Builds HTML text for a key-value pair.
     */
    private String buildHtmlText(String key, String value) {
        String safeKey = escapeHtml(key);
        String safeValue = escapeHtml(value);
        StringBuilder sb = new StringBuilder();
        sb.append("<html><b>").append(safeKey).append(":</b> ").append(safeValue).append("</html>");
        return sb.toString();
    }

    /**
     * Refreshes the displayed data from the profile and lifecycle manager.
     */
    public void refreshData() {
        SwingUtilities.invokeLater(() -> {
            // Profile name
            updateLabel(profileNameLabel, "Profile", profile.getName(),
                    GuiIcons.build(FontAwesome.USER, GuiIcons.sizeTiny(), GuiColors.getButtonIcon()));

            // Network type (mainnet/testnet)
            String network = profile.getProperty("network", "mainnet");
            Color networkColor = "testnet".equalsIgnoreCase(network)
                    ? new Color(255, 193, 7) // Amber/yellow for testnet
                    : GuiColors.getPeerActive(); // Green for mainnet
            updateLabel(networkLabel, "Network", network.toUpperCase(),
                    GuiIcons.build(FontAwesome.GLOBE, GuiIcons.sizeTiny(), networkColor));

            // API port
            String apiPort = profile.getProperty("httpport", "8125");
            updateLabel(apiPortLabel, "API Port", apiPort,
                    GuiIcons.build(FontAwesome.LINK, GuiIcons.sizeTiny(), GuiColors.getButtonIcon()));

            // P2P port
            String p2pPort = profile.getProperty("peer.port", "8123");
            updateLabel(p2pPortLabel, "P2P Port", p2pPort,
                    GuiIcons.build(FontAwesome.SHARE_ALT, GuiIcons.sizeTiny(), GuiColors.getButtonIcon()));

            // Database info from profile properties
            String dbEngine = determineDatabaseEngine();
            updateLabel(databaseEngineLabel, "Database", dbEngine,
                    GuiIcons.database(GuiIcons.sizeTiny()));

            String dbPort = determineDatabasePort(dbEngine);
            updateLabel(databasePortLabel, "DB Port", dbPort != null ? dbPort : "N/A",
                    GuiIcons.database(GuiIcons.sizeTiny()));

            // WebSocket port - check multiple possible property keys
            String wsPort = profile.getProperty("API.WebSocketPort");
            if (wsPort == null || wsPort.isEmpty()) {
                wsPort = profile.getProperty("websocketport");
            }
            if (wsPort == null || wsPort.isEmpty()) {
                wsPort = "8126"; // Default from Props.java
            }
            boolean wsEnabled = Boolean.parseBoolean(profile.getProperty("API.WebSocketEnable"));
            updateLabel(websocketPortLabel, "WebSocket", wsEnabled ? wsPort : "Disabled",
                    GuiIcons.build(FontAwesome.BOLT, GuiIcons.sizeTiny(),
                        wsEnabled ? GuiColors.getButtonIcon() : GuiColors.getFaintText()));

            // Refresh state from lifecycle manager
            refreshState();

            revalidate();
            repaint();
        });
    }

    /**
     * Updates the lifecycle state display. Called when node state changes.
     */
    public void refreshState() {
        SwingUtilities.invokeLater(() -> {
            NodeLifecycleManager manager = NodeLifecycleManager.getInstance();
            NodeProfile managedProfile = manager.getProfile(profile.getName());

            NodeLifecycleState state = NodeLifecycleState.IDLE;
            String stateText = "IDLE";
            Icon stateIcon = null;

            if (managedProfile != null) {
                state = managedProfile.getRuntime().getLifecycleState();
                stateText = state.name();
                stateIcon = stateIconFor(state);
            }

            updateLabel(stateLabel, "State", formatStateText(stateText), stateIcon);

            // Update the status icon next to profile name with detailed tooltip
            updateStatusIcon(state, stateText);
        });
    }

    /**
     * Gets the appropriate icon for a lifecycle state (for state chip).
     */
    private Icon stateIconFor(NodeLifecycleState state) {
        return switch (state) {
            case RUNNING -> GuiIcons.running(GuiIcons.sizeTiny());
            case PAUSED -> GuiIcons.paused(GuiIcons.sizeTiny());
            case ERROR -> GuiIcons.error(GuiIcons.sizeTiny());
            case INITIALIZING, STOPPING -> GuiIcons.initializing(GuiIcons.sizeTiny());
            default -> null;
        };
    }

    /**
     * Updates the status icon next to the profile name with a detailed tooltip.
     * Each lifecycle state has a dedicated FontAwesome icon and descriptive tooltip.
     * Icon size scales dynamically with the current font size via GuiIcons.sizeSmall().
     */
    private void updateStatusIcon(NodeLifecycleState state, String stateDescription) {
        int size = GuiIcons.sizeSmall();
        Icon icon = null;
        String tooltip = null;

        switch (state) {
            case IDLE -> {
                icon = GuiIcons.build(FontAwesome.CIRCLE_O, size, GuiColors.getFaintText());
                tooltip = "IDLE: Profile exists but not initialized yet";
            }
            case INITIALIZING -> {
                icon = GuiIcons.build(FontAwesome.SPINNER, size, new Color(255, 193, 7));
                tooltip = "INITIALIZING: Loading configuration and preparing resources...";
            }
            case READY -> {
                icon = GuiIcons.build(FontAwesome.CHECK_CIRCLE_O, size, new Color(100, 149, 237));
                tooltip = "READY: Initialized and ready to start. Click Start to begin.";
            }
            case RUNNING -> {
                icon = GuiIcons.build(FontAwesome.CIRCLE, size, GuiColors.getPeerActive());
                tooltip = "RUNNING: Node is actively running, P2P active, serving API";
            }
            case PAUSED -> {
                icon = GuiIcons.build(FontAwesome.PAUSE, size, new Color(103, 58, 183));
                tooltip = "PAUSED: Synchronization paused by user command";
            }
            case STOPPING -> {
                icon = GuiIcons.build(FontAwesome.SPINNER, size, new Color(255, 193, 7));
                tooltip = "STOPPING: Graceful shutdown in progress...";
            }
            case STOPPED -> {
                icon = GuiIcons.build(FontAwesome.STOP, size, GuiColors.getFaintText());
                tooltip = "STOPPED: Node cleanly stopped. Can be restarted.";
            }
            case ERROR -> {
                icon = GuiIcons.build(FontAwesome.EXCLAMATION_TRIANGLE, size, GuiColors.getContrastRed());
                tooltip = "ERROR: Node failed. Reset or restart required.";
            }
            case WAITING_FOR_DATABASE -> {
                icon = GuiIcons.build(FontAwesome.DATABASE, size, new Color(255, 193, 7));
                tooltip = "WAITING_FOR_DATABASE: Retry loop active until database available";
            }
        }

        statusIconLabel.setIcon(icon);
        statusIconLabel.setToolTipText(tooltip);
    }

    /**
     * Formats the state text for display (human-readable).
     */
    private String formatStateText(String state) {
        return switch (state) {
            case "RUNNING" -> "Running";
            case "STOPPED" -> "Stopped";
            case "READY" -> "Ready";
            case "PAUSED" -> "Paused";
            case "ERROR" -> "Error";
            case "INITIALIZING" -> "Initializing";
            case "STOPPING" -> "Stopping";
            case "WAITING_FOR_DATABASE" -> "Waiting for DB";
            default -> state;
        };
    }

    /**
     * Updates a label with new key, value and icon using HTML formatting.
     */
    private void updateLabel(JLabel label, String key, String value, Icon icon) {
        label.setText(buildHtmlText(key, value));
        label.setIcon(icon);
    }

    /**
     * Escapes HTML special characters in a string.
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        // Use character codes to avoid HTML entity issues in source
        return text.replace("&", new String(new char[]{'&', 'a', 'm', 'p', ';'}))
                   .replace("<", new String(new char[]{'&', 'l', 't', ';'}))
                   .replace(">", new String(new char[]{'&', 'g', 't', ';'}))
                   .replace("\"", new String(new char[]{'&', 'q', 'u', 'o', 't', ';'}));
    }

    /**
     * Determines the database engine from profile properties.
     */
    private String determineDatabaseEngine() {
        // Check for database.type or similar property in profile
        String dbType = profile.getProperty("database.type");
        if (dbType != null && !dbType.isEmpty()) {
            return dbType;
        }

        // Try to infer from JDBC URL pattern
        String jdbcUrl = profile.getProperty("database.jdbc.url", "");
        if (jdbcUrl.contains(":mysql:") || jdbcUrl.contains(":mariadb:")) {
            return "MariaDB";
        } else if (jdbcUrl.contains(":postgresql:") || jdbcUrl.contains(":postgres:")) {
            return "PostgreSQL";
        } else if (jdbcUrl.contains(":sqlite:")) {
            return "SQLite";
        }

        return "Unknown";
    }

    /**
     * Determines the database port from profile properties or JDBC URL.
     */
    private String determineDatabasePort(String engine) {
        // Check for explicit database port property
        String dbPort = profile.getProperty("database.port");
        if (dbPort != null && !dbPort.isEmpty()) {
            return dbPort;
        }

        // Try to extract from JDBC URL
        String jdbcUrl = profile.getProperty("database.jdbc.url", "");
        if (jdbcUrl.isEmpty()) {
            return null;
        }

        // Default ports by engine
        return switch (engine.toLowerCase()) {
            case "mariadb", "mysql" -> "3306";
            case "postgresql", "postgres" -> "5432";
            case "sqlite" -> null; // SQLite is file-based, no port
            default -> null;
        };
    }

    /**
     * Refreshes styles (fonts, colors) when appearance changes.
     */
    private void refreshStyles() {
        for (Component component : getComponents()) {
            if (component instanceof JLabel label) {
                GuiFontManager.applyDefaultFont(label);
            }
        }
        // Rebuild status icon with current font size after style refresh
        refreshState();
        revalidate();
        repaint();
    }

    /**
     * Gets the NodeProfile associated with this info bar.
     */
    public NodeProfile getProfile() {
        return profile;
    }
}