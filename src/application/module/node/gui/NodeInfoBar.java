package application.module.node.gui;

import application.module.appearance.AppearanceModule;
import application.module.node.lifecycle.NodeInstanceInfo;
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
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, GuiColors.getSeparator()),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)
        ));
        setOpaque(true);

        // Create info chips
        profileNameLabel = createInfoChip("Profile", "--",
                GuiIcons.build(FontAwesome.USER, GuiIcons.sizeTiny(), GuiColors.getButtonIcon()));
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
            NodeInstanceInfo info = manager.getProfileStatus(profile.getName());

            String stateText = "IDLE";
            Icon stateIcon = null;

            if (info != null) {
                NodeLifecycleState state = info.getState();
                stateText = state.name();
                stateIcon = stateIconFor(state);
            }

            updateLabel(stateLabel, "State", formatStateText(stateText), stateIcon);
        });
    }

    /**
     * Gets the appropriate icon for a lifecycle state.
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