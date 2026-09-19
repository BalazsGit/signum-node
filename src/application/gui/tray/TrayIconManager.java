package application.gui.tray;

import application.AppInfo;
import application.module.node.NodeModule;
import application.module.node.Signum;
import application.module.node.profile.NodeProfile;

import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.TrayIcon.MessageType;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.InputStream;

import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the application SystemTray icon with lifecycle integration.
 * <p>
 * This is a singleton that keeps the tray icon tooltip in sync with the node
 * state via push-based lifecycle callbacks (see the {@code onState...}
 * methods below).
 *
 * <h3>Behavior</h3>
 * <ul>
 *   <li>Singleton tray icon managed application-wide</li>
 *   <li><b>Right-click</b> on the icon opens a small native context menu at
 *       the pointer location (exactly where the icon was clicked), offering
 *       exactly two actions:
 *       <pre>
 *       Show application
 *       Shutdown application
 *       </pre>
 *       The menu is the platform popup ({@link TrayIcon#setPopupMenu}), so
 *       it closes automatically on an outside click, on item selection, or
 *       on Escape. Left-click is deliberately inert (no action listener).</li>
 *   <li>"Show application" restores the main frame; "Shutdown application"
 *       goes through the same confirm + rotating-popup shutdown path as the
 *       toolbar button (both are plain callback hooks set by the host)</li>
 *   <li>Tooltip reflects operating substate (SYNCING / SYNC_IDLE / PAUSED)</li>
 *   <li>Graceful degradation when SystemTray is not supported</li>
 * </ul>
 *
 * <h3>Why a native AWT popup?</h3>
 * With only plain-text items, the platform popup is the simplest reliable
 * option: Windows shows it on right-click at the click position and dismisses
 * it on click-away with no extra plumbing. If the tray menu ever grows icons
 * or submenus again, a Swing {@code JPopupMenu} shown at the pointer location
 * can be reintroduced.
 */
public class TrayIconManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrayIconManager.class);

    /** Tray icon resource candidates, in priority order (classpath-relative). */
    private static final String[] ICON_PATHS = {
            "/images/logo.png",
            "/images/signum_overlay_logo.png",
            "/images/signum_testnet_logo.png"
    };

    /** Default tooltip text (the full platform name, from the app-level SSOT). */
    private static final String DEFAULT_TOOLTIP = AppInfo.PLATFORM_NAME;

    /** Label of the menu item that restores the main application window. */
    static final String SHOW_APPLICATION_LABEL = "Show application";

    /** Label of the menu item that starts the application shutdown sequence. */
    static final String SHUTDOWN_APPLICATION_LABEL = "Shutdown application";

    // Singleton instance
    private static volatile TrayIconManager instance;

    // Dependencies (constructor-injected)
    private final NodeModule nodeModule;

    // Tray state
    private final boolean traySupported;
    private volatile TrayIcon trayIcon;

    // Callback hooks provided by the host application (resolved at click time)
    private Runnable onShowWindow;
    private Runnable onShutdown;

    /**
     * Private constructor. Use {@link #getInstance(NodeModule)} to obtain an instance.
     */
    private TrayIconManager(NodeModule nodeModule) {
        this.nodeModule = nodeModule;
        this.traySupported = SystemTray.isSupported();
    }

    /**
     * Gets or creates the singleton instance.
     *
     * @param nodeModule the node module (used to resolve node states for the tooltip)
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
     * Initializes the tray icon.
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

            trayIcon.displayMessage(AppInfo.NAME + " Running",
                    AppInfo.NAME + " is running in the background, use this icon to interact with it.",
                    MessageType.INFO);

            LOGGER.info("TrayIcon initialized successfully");
        } catch (Exception e) {
            LOGGER.warn("Could not initialize tray icon", e);
        }
    }

    // ====================================================================
    // Callback setters
    // ====================================================================

    /** Sets the action performed when "Show application" is selected from the tray menu. */
    public void setShowWindowAction(Runnable onShowWindow) {
        this.onShowWindow = onShowWindow;
    }

    /** Sets the action performed when "Shutdown application" is selected from the tray menu. */
    public void setShutdownAction(Runnable onShutdown) {
        this.onShutdown = onShutdown;
    }

    // ====================================================================
    // Tray icon creation
    // ====================================================================

    private TrayIcon createTrayIcon() {
        Image image = loadIconImage();
        TrayIcon icon = new TrayIcon(image, DEFAULT_TOOLTIP);
        icon.setImage(icon.getImage().getScaledInstance(
                icon.getSize().width, -1, Image.SCALE_SMOOTH));

        // The platform shows this popup on right-click, at the pointer
        // location, and dismisses it automatically on click-away. Left-click
        // is intentionally not handled (no action listener).
        icon.setPopupMenu(buildPopupMenu());
        return icon;
    }

    /**
     * Builds the tray context menu shown on right-click: the two plain items
     * "Show application" and "Shutdown application". The callback hooks are
     * resolved at click time, so the host may wire them after the menu was
     * built. Package-private so tests can inspect the menu without a real
     * system tray.
     */
    PopupMenu buildPopupMenu() {
        PopupMenu menu = new PopupMenu();
        MenuItem showItem = new MenuItem(SHOW_APPLICATION_LABEL);
        showItem.addActionListener(e -> runOnEdt(onShowWindow));
        MenuItem shutdownItem = new MenuItem(SHUTDOWN_APPLICATION_LABEL);
        shutdownItem.addActionListener(e -> runOnEdt(onShutdown));
        menu.add(showItem);
        menu.add(shutdownItem);
        return menu;
    }

    /** Runs the callback (if any) on the EDT. */
    private void runOnEdt(Runnable action) {
        if (action == null) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private Image loadIconImage() {
        for (String path : ICON_PATHS) {
            try {
                InputStream stream = TrayIconManager.class.getResourceAsStream(path);
                if (stream != null) {
                    LOGGER.debug("Tray icon loaded from {}", path);
                    return Toolkit.getDefaultToolkit().createImage(stream.readAllBytes());
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to load tray icon from {}", path, e);
            }
        }

        // Last resort: a generated image, so the tray icon always has *something*
        // to render instead of failing the whole initialization.
        LOGGER.warn("No tray icon resource found - using generated fallback image");
        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

    // ====================================================================
    // Lifecycle callbacks (Observer pattern, push-based)
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
                        AppInfo.NAME + " Error: " + profile.getName(),
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
     * Removes the tray icon.
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