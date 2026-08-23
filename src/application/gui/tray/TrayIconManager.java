package application.gui.tray;

import application.module.node.profile.NodeProfile;

import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.TrayIcon.MessageType;
import java.awt.Toolkit;
import java.awt.event.ActionListener;
import java.io.File;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import application.module.node.NodeModule;
import application.module.node.Signum;

/**
 * Manages the application SystemTray icon with lifecycle integration.
 * <p>
 * This is a singleton that registers as a {@link LifecycleListener} to reactively
 * update the tray icon tooltip and behavior when node profiles change state.
 * <p>
 * Follows the Observer pattern: receives push notifications from
 * {@link NodeLifecycleManager} instead of polling for state changes.
 *
 * <h3>Features</h3>
 * <ul>
 *   <li>Singleton tray icon managed application-wide</li>
 *   <li>Context menu with profile switching, wallet links, shutdown</li>
 *   <li>Tooltip reflects operating substate (SYNCING / SYNC_IDLE / PAUSED)</li>
 *   <li>Graceful degradation when SystemTray is not supported</li>
 * </ul>
 */
public class TrayIconManager  {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrayIconManager.class);

    /** Default icon path relative to classpath */
    private static final String DEFAULT_ICON_PATH = "/images/logo.png";

    /** Default tooltip text */
    private static final String DEFAULT_TOOLTIP = "Signum Node";

    // Singleton instance
    private static volatile TrayIconManager instance;

    // Dependencies (constructor-injected)
    private final NodeModule nodeModule;

    // Tray state
    private final boolean traySupported;
    private volatile TrayIcon trayIcon;
    private final PopupMenu popupMenu;

    // Callback hooks provided by the host application
    private Runnable onShowWindow;
    private Runnable onShutdown;
    private ActionListener onPhoenixWallet;
    private ActionListener onClassicWallet;

    /**
     * Private constructor. Use {@link #getInstance(NodeLifecycleManager)} to obtain an instance.
     */
    private TrayIconManager(NodeModule nodeModule) {
        this.nodeModule = nodeModule;
        this.traySupported = SystemTray.isSupported();
        this.popupMenu = new PopupMenu();

        if (traySupported) {
            buildPopupMenu();
        }
    }

    /**
     * Gets or creates the singleton instance.
     *
     * @param lifecycleManager the lifecycle manager to observe
     * @return the singleton TrayIconManager
     */
    public static synchronized TrayIconManager getInstance(NodeModule nodeModule) {
        if (instance == null) {
            instance = new TrayIconManager(nodeModule);
        }
        return instance;
    }

    /**
     * Resets the singleton. Use only for testing.
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.dispose();
        }
        instance = null;
    }

    // ====================================================================
    // Initialization
    // ====================================================================

    /**
     * Initializes the tray icon and registers as a lifecycle listener.
     * Must be called on the AWT EventQueue (use {@link SwingUtilities#invokeLater(Runnable)}).
     */
    public void initialize() {
        if (!traySupported) {
            LOGGER.info("SystemTray is not supported on this platform - tray icon will not be shown");
            return;
        }

        try {
            trayIcon = createTrayIcon();
            SystemTray.getSystemTray().add(trayIcon);
            

            trayIcon.displayMessage("Signum Running",
                    "Signum is running in background, use this icon to interact with it.",
                    MessageType.INFO);

            LOGGER.info("TrayIcon initialized successfully");
        } catch (Exception e) {
            LOGGER.warn("Could not initialize tray icon", e);
        }
    }

    // ====================================================================
    // Callback setters
    // ====================================================================

    /** Sets the action performed when "Show window" is selected from tray menu. */
    public void setShowWindowAction(Runnable onShowWindow) {
        this.onShowWindow = onShowWindow;
    }

    /** Sets the action performed when "Shutdown node" is selected from tray menu. */
    public void setShutdownAction(Runnable onShutdown) {
        this.onShutdown = onShutdown;
    }

    /** Sets the action performed when "Phoenix Wallet" is selected from tray menu. */
    public void setPhoenixWalletAction(ActionListener onPhoenixWallet) {
        this.onPhoenixWallet = onPhoenixWallet;
    }

    /** Sets the action performed when "Classic Wallet" is selected from tray menu. */
    public void setClassicWalletAction(ActionListener onClassicWallet) {
        this.onClassicWallet = onClassicWallet;
    }

    // ====================================================================
    // Tray icon creation
    // ====================================================================

    private TrayIcon createTrayIcon() {
        Image image = loadIconImage();
        TrayIcon icon = new TrayIcon(image, DEFAULT_TOOLTIP, popupMenu);
        icon.setImage(icon.getImage().getScaledInstance(
                icon.getSize().width, -1, Image.SCALE_SMOOTH));

        // Double-click opens Phoenix wallet if available
        File phoenixIndex = new File("html/ui/phoenix/index.html");
        if (phoenixIndex.isFile() && phoenixIndex.exists() && onPhoenixWallet != null) {
            icon.addActionListener(e -> onPhoenixWallet.actionPerformed(null));
        }

        return icon;
    }

    private Image loadIconImage() {
        try {
            java.io.InputStream stream = TrayIconManager.class.getResourceAsStream(DEFAULT_ICON_PATH);
            if (stream != null) {
                return Toolkit.getDefaultToolkit().createImage(stream.readAllBytes());
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to load custom icon, using fallback", e);
        }

        // Fallback: use default 16x16 color icon
        return new javax.swing.ImageIcon(TrayIconManager.class.getResource(DEFAULT_ICON_PATH)).getImage();
    }

    private void buildPopupMenu() {
        MenuItem phoenixItem = new MenuItem("Phoenix Wallet");
        MenuItem classicItem = new MenuItem("Classic Wallet");
        MenuItem showItem = new MenuItem("Show window");
        MenuItem shutdownItem = new MenuItem("Shutdown node");

        phoenixItem.addActionListener(e -> {
            if (onPhoenixWallet != null) {
                SwingUtilities.invokeLater(() -> onPhoenixWallet.actionPerformed(null));
            }
        });
        classicItem.addActionListener(e -> {
            if (onClassicWallet != null) {
                SwingUtilities.invokeLater(() -> onClassicWallet.actionPerformed(null));
            }
        });
        showItem.addActionListener(e -> {
            if (onShowWindow != null) {
                SwingUtilities.invokeLater(onShowWindow);
            }
        });
        shutdownItem.addActionListener(e -> {
            if (onShutdown != null) {
                SwingUtilities.invokeLater(onShutdown);
            }
        });

        popupMenu.add(phoenixItem);
        popupMenu.add(classicItem);
        popupMenu.add(showItem);
        popupMenu.add(shutdownItem);
    }

    // ====================================================================
    // LifecycleListener implementation (Observer pattern)
    // ====================================================================

    public void onStateChanged(NodeProfile profile, Signum.State oldState, Signum.State newState) {
        SwingUtilities.invokeLater(() -> updateTrayForState(profile, newState));
    }

    public void onOperatingStateChanged(NodeProfile profile,
                                        Signum.OperatingState oldSubstate,
                                        Signum.OperatingState newSubstate) {
        SwingUtilities.invokeLater(() -> updateTrayTooltip(profile, newSubstate));
    }

    public void onStatusMessage(NodeProfile profile, String message) {
        // Optionally show status messages via tray tooltip
        SwingUtilities.invokeLater(() -> {
            if (trayIcon != null) {
                String tooltip = buildTooltip(profile);
                trayIcon.setToolTip(tooltip);
            }
        });
    }

    public void onError(NodeProfile profile, String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            if (trayIcon != null) {
                trayIcon.displayMessage(
                        "Signum Error: " + profile.getName(),
                        errorMessage,
                        MessageType.ERROR);
            }
        });
    }

    // ====================================================================
    // Tray state update helpers
    // ====================================================================

    private void updateTrayForState(NodeProfile profile, Signum.State newState) {
        if (trayIcon == null) {
            return;
        }

        var signum = NodeModule.getInstance().get(profile.getName());

        if (newState == Signum.State.STOPPED || newState == Signum.State.ERROR) {
            String tooltip = trayIcon.getToolTip();
            if (tooltip != null && !tooltip.endsWith(" (STOPPED)")) {
                trayIcon.setToolTip(tooltip + " (STOPPED)");
            }
        } else if (newState == Signum.State.RUNNING) {
            updateTrayTooltip(profile, (signum != null) ? signum.getOperatingState() : Signum.OperatingState.SYNC_IDLE);
        }
    }

    private void updateTrayTooltip(NodeProfile profile, Signum.OperatingState operatingState) {
        if (trayIcon == null) {
            return;
        }
        trayIcon.setToolTip(buildTooltip(profile));
    }

    private String buildTooltip(NodeProfile profile) {
        var signum = NodeModule.getInstance().get(profile.getName());

        StringBuilder sb = new StringBuilder();
        sb.append(DEFAULT_TOOLTIP);
        sb.append(" [").append(profile.getName()).append("]");

        Signum.State state = (signum != null) ? signum.getState() : Signum.State.CREATED;
        if (state == Signum.State.RUNNING) {
            Signum.OperatingState substate = (signum != null) ? signum.getOperatingState() : Signum.OperatingState.SYNC_IDLE;
            sb.append(" - ").append(substate.name().toLowerCase());
            if (substate == Signum.OperatingState.SYNCING && ((signum != null) ? signum.getMissingBlocks() : 0) > 0) {
                sb.append(" (").append((signum != null) ? signum.getMissingBlocks() : 0).append(" blocks behind)");
            }
        } else if (state == Signum.State.STOPPED) {
            sb.append(" - STOPPED");
        } else if (state == Signum.State.ERROR) {
            sb.append(" - ERROR");
        }

        return sb.toString();
    }

    // ====================================================================
    // Cleanup
    // ====================================================================

    /**
     * Removes the tray icon and unregisters as a lifecycle listener.
     * Call during application shutdown.
     */
    public void dispose() {
        if (trayIcon != null && traySupported) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
                LOGGER.debug("TrayIcon removed");
            } catch (Exception e) {
                LOGGER.warn("Error removing tray icon", e);
            }
        }
        
        trayIcon = null;
    }

    /**
     * Displays a message notification via the tray icon.
     *
     * @param title   notification title
     * @param message notification body
     * @param type    message type (INFO, WARNING, ERROR)
     */
    public void displayMessage(String title, String message, MessageType type) {
        if (trayIcon != null && traySupported) {
            SwingUtilities.invokeLater(() -> trayIcon.displayMessage(title, message, type));
        }
    }

    /** Returns true if SystemTray is supported on this platform. */
    public boolean isTraySupported() {
        return traySupported;
    }

    /** Returns the current TrayIcon instance, or null if not initialized. */
    public TrayIcon getTrayIcon() {
        return trayIcon;
    }
}