package application.kernel;

import application.api.Module;
import application.api.ModuleContext;
import application.api.Shutdownable;
import application.gui.shell.MainFrame;
import application.gui.shell.TabManager;
import application.launcher.Launcher;
import application.utils.gui.GuiManager;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

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

    public ApplicationKernel(boolean isHeadless, Path configPath) {
        this.isHeadless = isHeadless;
        this.configPath = configPath;
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
                    logger.info("[DIAG] ApplicationKernel - calling getUI() for module: {}", m.getDisplayName());
                    JComponent moduleUI = m.getUI();
                    if (moduleUI != null) {
                        logger.info("[DIAG] ApplicationKernel - got non-null UI for module: {}, type: {}", m.getDisplayName(), moduleUI.getClass().getSimpleName());
                        tabManager.addModuleTab(m.getDisplayName(), moduleUI);
                    } else {
                        logger.warn("[DIAG] ApplicationKernel - module returned null UI: {}", m.getDisplayName());
                    }
                }

                shell.setVisible(true);
            });
        }
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
                // Use the ApplicationShutdown orchestrator instead of direct System.exit
                ApplicationShutdown.getInstance().executeShutdownSequence();
                System.exit(0);
            }
        };
    }
}