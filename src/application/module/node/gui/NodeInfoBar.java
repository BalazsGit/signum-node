package application.module.node.gui;

import application.module.appearance.AppearanceModule;
import application.module.node.BlockchainProcessor;
import application.module.node.NodeModule;
import application.module.node.Signum;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.NodeProfileRepository;
import application.module.node.profile.ProfileConflictDetector;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * Follows the Observer pattern: the owning {@link NodeProfilePanel} listens for state
 * changes via {@code Signum.StateListener} and, on each change, broadcasts a cross-profile
 * conflict refresh to every visible bar (see {@link #refreshAllConflicts()}).
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
     * Monotonic token for the background conflict computation: only the LATEST
     * computation's result is rendered, so a slow older read cannot overwrite a
     * newer one (both run on the single GUI-prep thread, the token guards the
     * EDT render step).
     */
    private final java.util.concurrent.atomic.AtomicLong chipToken = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Registry of all live info bars, used by {@link #refreshAllConflicts()}.
     * <p>
     * A profile's cross-profile resource conflicts depend on which <b>other</b> profiles
     * are RUNNING (their API/P2P/WebSocket ports and database). That set changes whenever
     * any profile starts or stops, but an info bar only re-evaluates its conflicts when
     * it is refreshed — so without a broadcast, starting one profile would leave every
     * other already-shown bar stale (missing / wrong red warnings). Every bar registers
     * itself here (in {@link #initialize()}) and is unregistered in {@link #dispose()}.
     * </p>
     */
    private static final Set<NodeInfoBar> LIVE_BARS = ConcurrentHashMap.newKeySet();

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

        // Register for the cross-profile conflict broadcast: any profile's start/stop
        // changes which resources are held, so every visible bar must be refreshable.
        LIVE_BARS.add(this);

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
     * <p>
     * All values are read from the canonical {@link Props} keys (API.Port, P2P.Port,
     * API.WebSocketPort, DB.Url) so they always match the runtime configuration, and
     * any resource conflict with another profile is surfaced as a red warning + tooltip.
     * </p>
     */
    public void refreshData() {
        SwingUtilities.invokeLater(() -> {
            refreshChips();
            // Refresh state from lifecycle manager
            refreshState();
            revalidate();
            repaint();
        });
    }

    /**
     * Renders the data chips (profile, network, ports, database) using canonical
     * property keys, overlaying a red warning + tooltip on any chip that conflicts
     * with another profile.
     * <p>
     * v5 (EDT cleanup): the cross-profile conflict computation (profile discovery,
     * properties-file disk reads in {@link #conflictByField()}) runs on the shared
     * GUI-prep background thread; only the label rendering happens on the EDT.
     * A token ensures a slow older computation cannot overwrite a newer one.
     * </p>
     */
    private void refreshChips() {
        final long token = chipToken.incrementAndGet();
        application.utils.gui.GuiExecutors.prepare().execute(() -> {
            Map<ProfileConflictDetector.ConflictField, ProfileConflictDetector.Conflict> conflicts;
            try {
                conflicts = conflictByField();
            } catch (Exception e) {
                LOGGER.debug("Conflict computation failed for profile: {}", profile.getName(), e);
                conflicts = new HashMap<>();
            }
            final Map<ProfileConflictDetector.ConflictField, ProfileConflictDetector.Conflict> finalConflicts = conflicts;
            SwingUtilities.invokeLater(() -> {
                if (token == chipToken.get()) {
                    renderChips(finalConflicts);
                }
            });
        });
    }

    /**
     * EDT-only rendering of the data chips from a pre-computed conflict map.
     */
    private void renderChips(Map<ProfileConflictDetector.ConflictField, ProfileConflictDetector.Conflict> conflicts) {

        // Profile name
        updateLabel(profileNameLabel, "Profile", profile.getName(),
                GuiIcons.build(FontAwesome.USER, GuiIcons.sizeTiny(), GuiColors.getButtonIcon()), null);

        // Network type (mainnet/testnet)
        String network = profile.getProperty("network", "mainnet");
        Color networkColor = "testnet".equalsIgnoreCase(network)
                ? new Color(255, 193, 7) // Amber/yellow for testnet
                : GuiColors.getPeerActive(); // Green for mainnet
        updateLabel(networkLabel, "Network", network.toUpperCase(),
                GuiIcons.build(FontAwesome.GLOBE, GuiIcons.sizeTiny(), networkColor), null);

        // API port (canonical key: API.Port)
        String apiPort = ProfileConflictDetector.apiPort(profile);
        updateLabel(apiPortLabel, "API Port", apiPort,
                GuiIcons.build(FontAwesome.LINK, GuiIcons.sizeTiny(), GuiColors.getButtonIcon()),
                conflicts.get(ProfileConflictDetector.ConflictField.API_PORT));

        // P2P port (canonical key: P2P.Port)
        String p2pPort = ProfileConflictDetector.p2pPort(profile);
        updateLabel(p2pPortLabel, "P2P Port", p2pPort,
                GuiIcons.build(FontAwesome.SHARE_ALT, GuiIcons.sizeTiny(), GuiColors.getButtonIcon()),
                conflicts.get(ProfileConflictDetector.ConflictField.P2P_PORT));

        // Database (engine + database/file name) parsed from the canonical DB.Url
        String dbLabel = ProfileConflictDetector.dbDisplayName(profile);
        updateLabel(databaseEngineLabel, "Database", dbLabel,
                GuiIcons.database(GuiIcons.sizeTiny()),
                conflicts.get(ProfileConflictDetector.ConflictField.DATABASE));

        // Database port (derived from the JDBC URL for server engines; N/A for SQLite)
        int dbPort = ProfileConflictDetector.dbPort(profile);
        updateLabel(databasePortLabel, "DB Port", dbPort > 0 ? String.valueOf(dbPort) : "N/A",
                GuiIcons.database(GuiIcons.sizeTiny()), null);

        // WebSocket port (canonical keys: API.WebSocketPort / API.WebSocketEnable)
        boolean wsEnabled = ProfileConflictDetector.wsEnabled(profile);
        String wsPort = ProfileConflictDetector.wsPort(profile);
        updateLabel(websocketPortLabel, "WebSocket", wsEnabled ? wsPort : "Disabled",
                GuiIcons.build(FontAwesome.BOLT, GuiIcons.sizeTiny(),
                        wsEnabled ? GuiColors.getButtonIcon() : GuiColors.getFaintText()),
                wsEnabled ? conflicts.get(ProfileConflictDetector.ConflictField.WEBSOCKET_PORT) : null);
    }

    /**
     * Updates the lifecycle state display. Called when node state changes.
     */
    public void refreshState() {
        SwingUtilities.invokeLater(() -> {
            Signum signum = NodeModule.getInstance().get(profile.getName());
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
            boolean startPending = NodeModule.getInstance().isStartPending(profile.getName());

            // SSOT: resolve the combined node state to one icon + one label, so the info
            // bar always matches the tab header and the toolbar.
            NodeStateIcon.Res res = NodeStateIcon.resolve(state, operating, maintenance, startPending);

            // State chip (label + icon)
            updateLabel(stateLabel, "State", res.label(), res.icon(), null);

            // Status icon next to the profile name (same SSOT icon + label).
            statusIconLabel.setIcon(res.icon());
            statusIconLabel.setToolTipText(res.label());

            // Re-sync the data chips so conflict indicators reflect the latest
            // running set (a node starting/stopping can change whether a conflict
            // is "currently running" or merely "configured").
            refreshChips();
        });
    }

    /**
     * Updates a label with new key, value and icon using HTML formatting.
     */
    private void updateLabel(JLabel label, String key, String value, Icon icon) {
        updateLabel(label, key, value, icon, null);
    }

    /**
     * Updates a label; when a {@link ProfileConflictDetector.Conflict} is supplied the
     * chip is flagged red (warning icon + red value) with a detailed hover tooltip,
     * otherwise it renders normally.
     */
    private void updateLabel(JLabel label, String key, String value, Icon icon,
                             ProfileConflictDetector.Conflict conflict) {
        if (conflict != null) {
            label.setIcon(GuiIcons.build(FontAwesome.EXCLAMATION_TRIANGLE, GuiIcons.sizeTiny(), GuiColors.getContrastRed()));
            label.setText(buildConflictText(key, value, conflict));
            label.setToolTipText(buildConflictTooltip(conflict));
        } else {
            label.setIcon(icon);
            label.setText(buildHtmlText(key, value));
            label.setToolTipText(null);
        }
    }

    /**
     * Computes the conflicts of this profile against all other profiles, indexed by the
     * conflicting resource field (first conflict per field wins).
     */
    private Map<ProfileConflictDetector.ConflictField, ProfileConflictDetector.Conflict> conflictByField() {
        Map<ProfileConflictDetector.ConflictField, ProfileConflictDetector.Conflict> byField = new HashMap<>();
        try {
            List<NodeProfile> others = new ArrayList<>();
            for (NodeProfile p : NodeProfileRepository.loadAll()) {
                if (p != null && !p.getName().equals(profile.getName())) {
                    others.add(p);
                }
            }
            // The "active" set is the set of profiles that have actually claimed a resource
            // (starting up or running, not yet released). Read straight from the same ownership
            // maps that enforce start-time conflicts, so the GUI warning always reflects what a
            // start would actually reject (see NodeModule.getClaimingProfileNames()).
            Set<String> running = NodeModule.getInstance().getClaimingProfileNames();
            for (ProfileConflictDetector.Conflict c : ProfileConflictDetector.detect(profile, others, running)) {
                // A profile that is merely *configured* (has not claimed a resource) does not hold any
                // port/database — it does not block a start. Only a conflict with a
                // profile that has actually claimed a resource is meaningful here (and is exactly
                // rejection actually enforces), so surface those and ignore the rest.
                if (!c.isOtherRunning()) {
                    continue;
                }
                byField.putIfAbsent(c.getField(), c);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to compute profile conflicts: {}", e.getMessage());
        }
        return byField;
    }

    /**
     * Builds red, highlighted conflict text for a chip value.
     */
    private String buildConflictText(String key, String value, ProfileConflictDetector.Conflict conflict) {
        String safeKey = escapeHtml(key);
        String safeValue = escapeHtml(value);
        String red = Integer.toHexString(GuiColors.getContrastRed().getRGB() & 0xFFFFFF);
        return "<html><b>" + safeKey + ":</b> <font color=\"#" + red + "\"><b>" + safeValue + " !</b></font></html>";
    }

    /**
     * Builds a detailed, actionable hover tooltip for a conflict.
     */
    private String buildConflictTooltip(ProfileConflictDetector.Conflict conflict) {
        String status = conflict.isOtherRunning() ? "starting or running" : "configured";
        return "Konfliktus: a(z) " + fieldLabel(conflict.getField()) + " (" + conflict.getOwnValue() + ")"
                + " a(z) '" + conflict.getOtherProfile() + "' profiléval ütközik (" + status + ")."
                + "\n\nOldal: írd át a portot vagy az adatbázist, vagy futtasd az első profilt"
                + " egyedül — a konfliktusos indítást a rendszer elutasítja.";
    }

    /**
     * Human-readable (Hungarian) name of a conflicting resource.
     */
    private String fieldLabel(ProfileConflictDetector.ConflictField field) {
        return switch (field) {
            case API_PORT -> "API.Port";
            case P2P_PORT -> "P2P.Port";
            case WEBSOCKET_PORT -> "WebSocket port";
            case DATABASE -> "adatbázis";
        };
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
     * Removes this info bar from the live registry. Called by the owning
     * {@link NodeProfilePanel} when it is disposed, so a bar that is no longer shown
     * is no longer refreshed by {@link #refreshAllConflicts()} (prevents a leak).
     */
    public void dispose() {
        LIVE_BARS.remove(this);
    }

    /**
     * Re-evaluates the cross-profile resource conflicts on <b>every</b> live info bar.
     * <p>
     * A profile's red conflict warnings depend on which other profiles are currently
     * RUNNING (their API/P2P/WebSocket ports and database). When any profile starts or
     * stops, the set of held resources changes, so every already-shown bar may need to
     * re-render — e.g. starting profile A must immediately surface A's conflict on an
     * already-open, conflicting profile B without waiting for B to be (re)started.
     * Each bar re-reads the live running set via {@link #refreshData()}, so the result
     * is always current.
     * </p>
     * <p>Thread-safe: {@link #refreshData()} dispatches to the EDT as needed.</p>
     */
    public static void refreshAllConflicts() {
        for (NodeInfoBar bar : LIVE_BARS) {
            bar.refreshData();
        }
    }

    /**
     * Gets the NodeProfile associated with this info bar.
     */
    public NodeProfile getProfile() {
        return profile;
    }
}