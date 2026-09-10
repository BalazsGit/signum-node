package application.module.logging.gui;

import application.api.ModuleContext;
import application.utils.logging.LoggingModuleRegistry;
import application.utils.logging.ModuleLoggingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The main panel of the Logging module. Contains an internal {@link JTabbedPane} with one
 * tab per registered logging provider (Node, Database, …), added dynamically via
 * {@link LoggingModuleRegistry} listeners.
 * <p>
 * Each module tab is itself a {@link JTabbedPane} with:
 * <ul>
 *   <li>a "Logging Profiles" sub-tab ({@link ModuleLoggingProfilePanel}: profile CRUD);</li>
 *   <li>an "Assignments" sub-tab ({@link AssignmentPanel}: the node-profile → logging-profile
 *       associations for this module).</li>
 * </ul>
 *
 * <h3>EDT-safety</h3>
 * Provider registration may occur on the main thread (during {@code Module.start()}), so all
 * {@link JTabbedPane} mutations are dispatched to the EDT via {@link SwingUtilities#invokeLater}.
 * The panel is constructed on the main thread (during {@code Module.init()}), which is safe
 * because it is not yet attached to a frame at that point.
 *
 * @see ModuleLoggingProfilePanel
 * @see AssignmentPanel
 */
public class LoggingPanel extends JPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingPanel.class);

    private final ModuleContext context;
    private final JTabbedPane tabbedPane;

    /** moduleId → the tab component, for idempotent add / removal. */
    private final Map<String, JComponent> moduleTabs = new ConcurrentHashMap<>();

    private final Consumer<ModuleLoggingProvider> addListener;
    private final Consumer<String> removeListener;

    public LoggingPanel(ModuleContext context) {
        super(new BorderLayout());
        this.context = context;
        this.tabbedPane = new JTabbedPane();

        // Wire the registry listeners for dynamic module tabs.
        this.addListener = provider -> addModuleTab(provider);
        this.removeListener = moduleId -> removeModuleTab(moduleId);

        LoggingModuleRegistry registry = LoggingModuleRegistry.getInstance();
        registry.addRegisteredListener(addListener);
        registry.addUnregisteredListener(removeListener);

        // Seed tabs for providers already registered (e.g. if constructed after node/db start).
        for (ModuleLoggingProvider provider : registry.getAllProviders()) {
            addModuleTab(provider);
        }

        add(tabbedPane, BorderLayout.CENTER);
    }

    private void addModuleTab(ModuleLoggingProvider provider) {
        String moduleId = provider.getModuleId();
        if (moduleTabs.containsKey(moduleId)) {
            return; // already added (idempotent)
        }
        JComponent panel = buildModuleTab(provider);
        moduleTabs.put(moduleId, panel);
        SwingUtilities.invokeLater(() -> {
            tabbedPane.addTab(provider.getProfile().getDisplayName(), panel);
            LOGGER.info("Added logging tab for module '{}' ({})", moduleId, provider.getProfile().getDisplayName());
        });
    }

    /**
     * Builds the composite tab content for a module: an inner {@link JTabbedPane} with a
     * "Logging Profiles" sub-tab (profile CRUD) and an "Assignments" sub-tab (the
     * node-profile → logging-profile associations for this module).
     *
     * @param provider the module's logging provider
     * @return the composite tab component
     */
    private JComponent buildModuleTab(ModuleLoggingProvider provider) {
        JTabbedPane inner = new JTabbedPane();
        inner.addTab("Logging Profiles", new ModuleLoggingProfilePanel(context, provider));
        inner.addTab("Assignments", new AssignmentPanel(context, provider));
        return inner;
    }

    private void removeModuleTab(String moduleId) {
        JComponent panel = moduleTabs.remove(moduleId);
        if (panel == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (tabbedPane.isAncestorOf(panel)) {
                tabbedPane.remove(panel);
            }
            LOGGER.info("Removed logging tab for module '{}'", moduleId);
        });
    }

    /**
     * Removes the registry listeners. Call during module shutdown to avoid leaks.
     */
    public void dispose() {
        LoggingModuleRegistry registry = LoggingModuleRegistry.getInstance();
        registry.removeRegisteredListener(addListener);
        registry.removeUnregisteredListener(removeListener);
    }
}