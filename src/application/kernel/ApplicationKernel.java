package application.kernel;

import application.api.Module;
import application.api.ModuleContext;
import application.gui.shell.MainFrame;
import application.gui.shell.TabManager;
import application.launcher.Launcher;
import java.nio.file.Path;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApplicationKernel {
    private static final Logger logger = LoggerFactory.getLogger(ApplicationKernel.class);
    private final ModuleRegistry registry = new ModuleRegistry();
    private final boolean isHeadless;
    private final Path configPath;

    public ApplicationKernel(boolean isHeadless, Path configPath) {
        this.isHeadless = isHeadless;
        this.configPath = configPath;
    }

    public void boot() {
        logger.info("Kernel booting...");

        // 1. Modulok felfedezése
        registry.discoverModules();

        List<Module> modules = registry.getModules();
        if (modules.isEmpty()) {
            logger.warn("No modules discovered by ModuleRegistry! Check META-INF/services location.");
        }

        // 2. Modulok inicializálása
        ModuleContext context = createModuleContext();
        for (Module m : modules) {
            logger.info("Initializing module: {}", m.getDisplayName());
            m.init(context);
            m.start();
        }

        // 3. UI indítása, ha nem headless
        if (!isHeadless) {
            SwingUtilities.invokeLater(() -> {
                logger.info("Starting GUI Shell...");
                MainFrame shell = new MainFrame();
                TabManager tabManager = shell.getTabManager();

                // Dinamikus tab hozzáadás a regisztrált modulok alapján
                for (Module m : registry.getModules()) {
                    JComponent moduleUI = m.getUI();
                    if (moduleUI != null) {
                        logger.info("Mounting module UI: {}", m.getDisplayName());
                        tabManager.addModuleTab(m.getDisplayName(), moduleUI);
                    }
                }

                shell.setVisible(true);
            });
        }
    }

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
                System.exit(0);
            }
        };
    }
}