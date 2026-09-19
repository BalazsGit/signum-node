package application.kernel;

import application.api.Module;
import application.api.ModuleContext;
import application.api.Shutdownable;
import application.gui.shell.MainFrame;
import application.gui.shell.ShutdownProgressDialog;
import application.gui.shell.TabManager;
import application.gui.tray.TrayIconManager;
import application.launcher.Launcher;
import application.module.node.NodeModule;
import application.utils.gui.GuiManager;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central kernel that orchestrates application startup and shutdown.
 * 
 * Startup flow:
 * 1. Discover modules via ServiceLoader
 * 2. Initialize and start each module
 * 3. Register each module with ApplicationShutdown for graceful teardown
 * 4. Launch GUI shell (or run headless)
 * 
 * Shutdown flow (triggered by Shutdown button or JVM shutdown hook):
 * 1. ApplicationShutdown executes modules in priority order (HIGHEST first)
 * 2. Each module's stop() method is called via Shutdownable contract
 * 3. Completion hooks execute (JVM exit)
 * 
 * Design note for Solution B migration: The kernel currently manages Module
 * instances. In the future multi-instance architecture, each NodeInstance will
 * be registered as a Shutdownable component. The boot/shutdown orchestration
 * logic remains identical since both paths go through ApplicationShutdown.
 */
public class ApplicationKernel {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationKernel.class);

    private final ModuleRegistry registry = new ModuleRegistry();
    private final boolean isHeadless;
    private final Path configPath;
    private final String targetProfile;

    public ApplicationKernel(boolean isHeadless, Path configPath) {
        this(isHeadless, configPath, null);
    }

    /**
     * @param isHeadless    whether to boot without the GUI shell
     * @param configPath    the configuration directory
     * @param targetProfile the profile to start in single-profile mode (headless
     *                      {@code profile run <name>}); {@code null} for the normal
     *                      multi-profile / autostart boot
     */
    public ApplicationKernel(boolean isHeadless, Path configPath, String targetProfile) {
        this.isHeadless = isHeadless;
        this.configPath = configPath;
        this.targetProfile = targetProfile;
    }

    /**
     * Boots the application: discovers modules, initializes them,
     * registers shutdown handlers, and launches the GUI.
     */
    public void boot() {
        logger.info("Kernel booting...");

        // 1. Discover modules via ServiceLoader
        registry.discoverModules();

        List<Module> modules = registry.getModules();
        if (modules.isEmpty()) {
            logger.warn("No modules discovered by ModuleRegistry! Check META-INF/services location.");
        }

        // 2. Initialize and start each module
        ModuleContext context = createModuleContext();
        for (Module m : modules) {
            logger.info("Initializing module: {}", m.getDisplayName());
            m.init(context);
            m.start();
        }

        // Resolve the node module (used for the system tray wiring)
        final NodeModule nodeModule = findNodeModule(modules);

        // 3. Register all modules with ApplicationShutdown orchestrator
        //    Modules implement Shutdownable, so they can be managed by the shutdown system
        ApplicationShutdown shutdown = ApplicationShutdown.getInstance();
        for (Module m : modules) {
            if (m instanceof Shutdownable) {
                shutdown.register((Shutdownable) m);
                logger.info("Registered '{}' with ApplicationShutdown (priority: {})",
                        m.getDisplayName(), ((Shutdownable) m).getShutdownPriority());
            }
        }

        // 4. Register completion hook to persist GUI settings before JVM exit
        //    This ensures GuiManager settings (tabLayoutPolicy, colorOverrides) are saved
        //    regardless of whether the user clicked Shutdown or the process is killed
        shutdown.addOnCompleteHook(() -> {
            try {
                GuiManager.getInstance().saveToJson();
                logger.info("GUI settings persisted on shutdown via completion hook");
            } catch (Exception e) {
                logger.warn("Failed to save GUI settings on shutdown", e);
            }
        });

        // 4.1 Remove the system tray icon once the shutdown sequence completes
        //     (only if a node module and thus a tray icon exist)
        if (nodeModule != null) {
            shutdown.addOnCompleteHook(() -> {
                try {
                    TrayIconManager.getInstance(nodeModule).dispose();
                    logger.info("Tray icon removed on shutdown");
                } catch (Exception e) {
                    logger.warn("Failed to remove tray icon on shutdown", e);
                }
            });
        }

        // 5. Add JVM shutdown hook as fallback safety net
        //    If the application is killed without going through the Shutdown button,
        //    this hook ensures modules still get their stop() called
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("JVM shutdown hook triggered - executing graceful shutdown");
            shutdown.executeShutdownSequence();
        }, "ApplicationShutdown-Hook"));

        // 6. Launch UI in EDT (Event Dispatch Thread) if not headless
        if (!isHeadless) {
            SwingUtilities.invokeLater(() -> {
                logger.info("Starting GUI Shell...");
                MainFrame shell = new MainFrame();
                TabManager tabManager = shell.getTabManager();

                // Dynamically add tabs for each module's UI
                for (Module m : registry.getModules()) {
                    logger.debug("ApplicationKernel - calling getUI() for module: {}", m.getDisplayName());
                    JComponent moduleUI = m.getUI();
                    if (moduleUI != null) {
                        logger.debug("ApplicationKernel - got non-null UI for module: {}, type: {}", m.getDisplayName(), moduleUI.getClass().getSimpleName());
                        tabManager.addModuleTab(m.getDisplayName(), moduleUI);
                    } else {
                        logger.warn("ApplicationKernel - module returned null UI: {}", m.getDisplayName());
                    }
                }

                shell.setVisible(true);

                // 6.1 Wire the system tray icon (when supported): the X button
                //     then merely hides the window, and the app stays reachable
                //     from the tray (Show application / Shutdown application).
                wireTrayIcon(shell, nodeModule);
            });
        }
    }

    /**
     * Finds the node module among the discovered modules.
     *
     * @param modules the discovered module list
     * @return the {@link NodeModule} instance, or {@code null} when absent
     */
    private static NodeModule findNodeModule(List<Module> modules) {
        for (Module m : modules) {
            if (m instanceof NodeModule) {
                return (NodeModule) m;
            }
        }
        return null;
    }

    /**
     * Wires the system tray icon to the main shell (must run on the EDT).
     * <p>
     * When the tray is available: "Show application" restores the main frame,
     * "Shutdown application" goes through the same confirm + rotating-popup shutdown
     * path as the toolbar button, and closing the main window (X) hides it
     * into the tray. Without tray support the X keeps its default
     * confirm-and-exit behavior.
     * </p>
     *
     * @param shell      the main application frame
     * @param nodeModule the node module (may be {@code null} when absent)
     */
    private void wireTrayIcon(MainFrame shell, NodeModule nodeModule) {
        if (nodeModule == null) {
            logger.debug("No node module present - tray icon not wired");
            return;
        }
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            logger.info("SystemTray not supported - closing the main window will exit the application");
            return;
        }

        TrayIconManager tray = TrayIconManager.getInstance(nodeModule);
        tray.setShowWindowAction(() -> {
            shell.setState(Frame.NORMAL);
            shell.setVisible(true);
            shell.toFront();
        });
        tray.setShutdownAction(shell::confirmAndShutdown);
        tray.initialize();

        // X close = minimize to tray (the app keeps running in the background)
        shell.setWindowCloseHandler(() -> shell.setVisible(false));
        logger.info("Tray icon wired - closing the main window minimizes to the tray");
    }

    /**
     * Creates the ModuleContext that provides shared services to all modules.
     */
    private ModuleContext createModuleContext() {
        return new ModuleContext() {
            @Override
            public Path getConfigDirectory() {
                return configPath;
            }

            @Override
            public void requestRestart() {
                Launcher.restart();
            }

            @Override
            public void shutdown() {
                // Use the ApplicationShutdown orchestrator instead of direct System.exit.
                // Shows the rotating-icon "shutting down" popup (when a GUI is
                // available) and runs the sequence off the EDT, then exits the JVM.
                ShutdownProgressDialog.showAndExecuteShutdown(null);
            }

            @Override
            public String getTargetProfileName() {
                return targetProfile;
            }
        };
    }
}