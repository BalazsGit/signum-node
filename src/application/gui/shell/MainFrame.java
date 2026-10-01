package application.gui.shell;

import application.AppInfo;
import application.gui.glassPanel.GlassPanelManager;
import application.kernel.ApplicationShutdown;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiIcons;
import application.utils.gui.HoverScaleIcon;

import jiconfont.icons.font_awesome.FontAwesome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Main application frame containing the (optional) toolbar and tabbed
 * module area.
 * 
 * The top toolbar is reserved for global application actions; it is
 * currently empty and is attached only while it has a component, so it
 * costs no pixels for now. The shutdown icon lives at the right end of the
 * module tab row (trailing content, see
 * {@link TabManager#setTrailingComponent(JComponent)}).
 */
public class MainFrame extends JFrame {

    /** Translucent veil over the frame's glass pane, shown while shutting down. */
    private final JPanel dimOverlay;

    private final TabManager tabManager;
    private final JToolBar mainToolbar;

    /**
     * Handler for the window-close (X) request. When set (system tray
     * available) the window is merely hidden so the app stays reachable from
     * the tray icon; when not set, the application asks for confirmation and
     * exits gracefully.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(MainFrame.class);

    private Runnable windowCloseHandler;

    public MainFrame() {
        setTitle(AppInfo.PLATFORM_NAME);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // Initialize the GlassPanel at the application level.
        GlassPanelManager.initialize(this);

        // Pre-create the shutdown dim veil (hidden by default). It is hosted on
        // the glass pane, above all content but below nothing else: the
        // GlassPanel is a transparent container that never claims mouse hits,
        // so the disabled overlay cannot steal input from the content.
        dimOverlay = createDimOverlay();
        dimOverlay.setVisible(false);
        JPanel glassPane = (JPanel) getGlassPane();
        if (glassPane != null) {
            // BorderLayout: the veil fills the (resizable) glass pane, which
            // is resized to the frame size by JRootPane. The glass pane (the
            // app GlassPanel or the default one) never claims mouse hits, so
            // the disabled overlay cannot steal input from the content.
            glassPane.setLayout(new BorderLayout());
            glassPane.add(dimOverlay, BorderLayout.CENTER);
        }

        // The global-actions toolbar (reserved for future global operations):
        // it is attached only while it actually has a component — an empty
        // strip would be a dead pixel row.
        mainToolbar = createMainToolbar();
        if (mainToolbar.getComponentCount() > 0) {
            add(mainToolbar, BorderLayout.NORTH);
        }

        // Tab area for modules
        this.tabManager = new TabManager();
        // The shutdown icon lives at the right end of the module tab row:
        // FlatLaf renders it inside the row via the JTabbedPane.trailingComponent
        // client property (see TabManager), so it leaves no dead band below.
        tabManager.setTrailingComponent(createTrailingButtons());
        add(tabManager.getComponent(), BorderLayout.CENTER);

        // X close: hide to tray (when wired) or confirm + graceful exit
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleCloseRequest();
            }
        });
    }

    /**
     * Sets the handler for the window-close (X) request.
     * <p>
     * Typically installed by the kernel when the system tray is available,
     * hiding the window instead of exiting. Pass {@code null} to restore the
     * default confirm-and-exit behavior.
     * </p>
     *
     * @param handler the close handler, or {@code null}
     */
    public void setWindowCloseHandler(Runnable handler) {
        this.windowCloseHandler = handler;
    }

    /**
     * Routes the window-close (X) request to the installed handler, or to the
     * default confirm-and-exit behavior when no handler is installed.
     */
    private void handleCloseRequest() {
        if (windowCloseHandler != null) {
            windowCloseHandler.run();
        } else {
            confirmExitOnClose();
        }
    }

    /**
     * Fallback close path when no system tray is available: confirms with the
     * user and shuts the application down gracefully (rotating-icon popup).
     */
    private void confirmExitOnClose() {
        int result = JOptionPane.showConfirmDialog(
                this,
                "No system tray is available on this platform.\n"
                        + "Closing the window will exit the application.",
                "Exit Signum",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                GuiIcons.build(FontAwesome.POWER_OFF, 48, GuiColors.getContrastRed())
        );

        if (result == JOptionPane.YES_OPTION) {
            initiateShutdown();
        }
    }

    /**
     * Creates the main application toolbar (reserved for future global
     * application actions). It is intentionally empty for now — the shutdown
     * icon lives at the right end of the module tab row — and the frame
     * attaches it only while it has a component.
     * </p>
     */
    private JToolBar createMainToolbar() {
        JToolBar toolbar = new JToolBar("");
        toolbar.setRollover(true);
        toolbar.setFloatable(false);
        toolbar.setLayout(new BorderLayout());
        toolbar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        return toolbar;
    }

    /**
     * Creates the trailing row of the module tab row: the restart and shutdown
     * icon buttons, right-aligned so they sit directly under the window's close
     * X. Restart comes first (left of shutdown).
     */
    private JComponent createTrailingButtons() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        row.setOpaque(false);
        row.add(createRestartButton());
        row.add(createShutdownButton());
        return row;
    }

    /**
     * Creates the green restart icon button (a REFRESH glyph). It sits to the
     * left of the shutdown button and offers a graceful "restart the whole
     * application" action (see {@link #confirmAndRestart()}).
     * <p>
     * On mouse rollover the glyph grows by {@link HoverScaleIcon#DEFAULT_SCALE}
     * (15%) inside the same fixed bounding box — no layout shift — using the
     * shared hover-scale behaviour of the node toolbar icon buttons.
     * </p>
     */
    private JButton createRestartButton() {
        JButton button = new JButton();

        float iconSize = GuiConstants.getToolBarIconSize();
        Color green = GuiColors.getReady();
        Icon glyph = GuiIcons.build(FontAwesome.REFRESH, Math.round(iconSize), green);
        Icon hoveredGlyph = GuiIcons.build(FontAwesome.REFRESH,
                Math.round(iconSize * HoverScaleIcon.DEFAULT_SCALE), green);
        HoverScaleIcon.install(button, glyph, hoveredGlyph);

        button.setToolTipText("Restart the entire application (all nodes restart)");
        button.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        button.setOpaque(false);
        button.setContentAreaFilled(false);

        button.addActionListener(e -> confirmAndRestart());

        return button;
    }

    /**
     * Creates the red shutdown icon button (color palette POWER_OFF glyph).
     * <p>
     * On mouse rollover the glyph grows by {@link HoverScaleIcon#DEFAULT_SCALE}
     * (15%) inside the same fixed bounding box — no layout shift — using the
     * shared hover-scale behaviour of the node toolbar icon buttons.
     * </p>
     */
    private JButton createShutdownButton() {
        JButton button = new JButton();

        float iconSize = GuiConstants.getToolBarIconSize();
        Color red = GuiColors.getContrastRed();
        Icon glyph = GuiIcons.build(FontAwesome.POWER_OFF, Math.round(iconSize), red);
        Icon hoveredGlyph = GuiIcons.build(FontAwesome.POWER_OFF,
                Math.round(iconSize * HoverScaleIcon.DEFAULT_SCALE), red);
        HoverScaleIcon.install(button, glyph, hoveredGlyph);

        button.setToolTipText("Gracefully shut down all components and exit the application");
        button.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        button.setOpaque(false);
        button.setContentAreaFilled(false);

        button.addActionListener(e -> confirmAndShutdown());

        return button;
    }

    /**
     * Shows a confirmation dialog before initiating the shutdown sequence.
     * <p>
     * A large red POWER_OFF glyph is used as the dialog symbol (instead of
     * the generic warning triangle) so it is immediately clear that a
     * shutdown is being requested.
     * </p>
     */
    public void confirmAndShutdown() {
        // While the shutdown sequence runs, do not offer (or start) another one.
        if (ApplicationShutdown.getInstance().isShutdownInitiated()) {
            LOGGER.warn("Shutdown already in progress - ignoring duplicate shutdown request");
            return;
        }
        int result = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to shut down the entire application?\n" +
                "All running nodes will be stopped.",
                "Confirm Application Shutdown",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                GuiIcons.build(FontAwesome.POWER_OFF, 48, GuiColors.getContrastRed())
        );

        if (result == JOptionPane.YES_OPTION) {
            initiateShutdown();
        }
    }

    /**
     * Shows a confirmation dialog before restarting the application. On
     * confirm, relaunches a fresh instance and then runs the graceful shutdown
     * (the new instance starts only after the current modules — and the
     * database lock — are released, so the two never contend for the chain).
     */
    public void confirmAndRestart() {
        if (ApplicationShutdown.getInstance().isShutdownInitiated()) {
            LOGGER.warn("Shutdown already in progress - ignoring duplicate restart request");
            return;
        }
        int result = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to restart the entire application?\n" +
                "All running nodes will be stopped and started again.",
                "Confirm Application Restart",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                GuiIcons.build(FontAwesome.REFRESH, 48, GuiColors.getReady())
        );

        if (result == JOptionPane.YES_OPTION) {
            initiateRestart();
        }
    }

    /**
     * Initiates the graceful restart: registers a one-shot completion hook that
     * launches a new application instance once every module has shut down (the
     * database lock is released), then runs the normal shutdown sequence (which
     * exits the JVM). On Windows the child process is independent and survives
     * the parent's exit, so the fresh instance comes up on a released database.
     */
    private void initiateRestart() {
        ApplicationShutdown shutdown = ApplicationShutdown.getInstance();
        boolean[] relaunched = {false};
        shutdown.addOnCompleteHook(() -> {
            if (relaunched[0]) {
                return; // one-shot: never relaunch twice
            }
            relaunched[0] = true;
            relaunchNewInstance();
        });
        // Reuse the exact shutdown UX (dim + rotating popup + JVM exit).
        initiateShutdown();
    }

    /**
     * Launches a fresh application instance using the current process's own
     * launch command (works both for a {@code java -cp ...} start and a native
     * launcher). Any failure is logged and swallowed: the app then simply
     * shuts down without relaunching, which is the safe default.
     */
    private void relaunchNewInstance() {
        try {
            ProcessHandle current = ProcessHandle.current();
            String command = current.info().command().orElse(null);
            if (command == null || command.isBlank()) {
                LOGGER.warn("Restart: could not determine the launch command - not relaunching");
                return;
            }
            List<String> commandLine = new ArrayList<>();
            commandLine.add(command);
            for (String arg : current.info().arguments().orElse(new String[0])) {
                commandLine.add(arg);
            }

            ProcessBuilder builder = new ProcessBuilder(commandLine);
            builder.directory(new File("").getAbsoluteFile());
            builder.redirectErrorStream(true);
            Process child = builder.start();
            LOGGER.info("Restart: launched a new application instance (pid {})", child.pid());
        } catch (IOException e) {
            LOGGER.error("Restart: failed to relaunch the application", e);
        } catch (Exception e) {
            LOGGER.error("Restart: unexpected error while relaunching the application", e);
        }
    }

    /**
     * Initiates the graceful shutdown sequence.
     * 1. Disable this frame to prevent further user interaction.
     * 2. Dim the frame so it is visually obvious the UI is no longer active.
     * 3. Show the rotating-icon "shutting down" popup.
     * 4. Execute the ApplicationShutdown orchestrator (on a background thread
     *    so the popup animation keeps running), then exit the JVM.
     */
    private void initiateShutdown() {
        // Disable the frame to prevent further interaction
        this.setEnabled(false);
        setDimmed(true);

        // Show shutdown progress feedback on the title bar
        this.setTitle(AppInfo.PLATFORM_NAME + " - Shutting down...");

        // Popup + background shutdown sequence + JVM exit
        ShutdownProgressDialog.showAndExecuteShutdown(this);
    }

    /**
     * Shows or hides a light gray veil over the whole frame, signalling that
     * the interface is inactive (used while the shutdown sequence runs).
     *
     * @param dimmed {@code true} to gray the frame out, {@code false} to clear
     */
    public void setDimmed(boolean dimmed) {
        dimOverlay.setVisible(dimmed);
        dimOverlay.revalidate();
        dimOverlay.repaint();
    }

    /**
     * Creates the translucent gray veil painted over the frame content.
     * It is disabled (not enabled) so the glass-pane hit-testing is untouched
     * and normal clicks pass straight through to the content below.
     */
    private JPanel createDimOverlay() {
        JPanel overlay = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                // Light gray wash ("enyhén besürkítve"): neutral in both the
                // light and the dark theme, low alpha keeps content readable.
                g.setColor(new Color(211, 211, 211, 96));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        overlay.setOpaque(false);
        overlay.setFocusable(false);
        overlay.setEnabled(false);
        return overlay;
    }

    public TabManager getTabManager() {
        return tabManager;
    }

    public JToolBar getMainToolbar() {
        return mainToolbar;
    }
}
