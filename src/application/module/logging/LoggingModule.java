package application.module.logging;

import application.api.Module;
import application.api.ModuleContext;
import application.module.logging.gui.LoggingPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JComponent;

/**
 * The Logging module — a first-class GUI module that centralizes the management of
 * per-module logging profiles, presets, and node-profile assignments.
 * <p>
 * This module follows the standard {@link Module} lifecycle: {@code init → start → running → stop}.
 * Its UI is a {@link LoggingPanel} that contains an internal {@code JTabbedPane} with one tab
 * per registered logging provider (Node, Database, …). Each module tab contains a
 * "Logging Profiles" and an "Assignments" sub-tab.
 * </p>
 *
 * <h3>Boot order</h3>
 * This module is registered in {@code resources/META-INF/services/application.api.Module} in the
 * <b>2nd position</b> (after {@code AppearanceModule}, before {@code SystemConsoleModule}). This
 * ensures the LAF/appearance is set up before the logging UI is created, while the logging module
 * is still available before the node and database modules register their providers.
 *
 * <h3>Dynamic tabs</h3>
 * The {@link LoggingPanel} listens to {@code LoggingModuleRegistry} registration events and adds
 * a tab for each module as it registers (Node and Database register in their {@code start()}).
 *
 * @see LoggingPanel
 * @see application.module.logging.gui.ModuleLoggingProfilePanel
 */
public class LoggingModule implements Module {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingModule.class);

    private ModuleContext context;
    private LoggingPanel loggingPanel;

    @Override
    public String getId() {
        return "logging";
    }

    @Override
    public String getDisplayName() {
        return "Logging";
    }

    @Override
    public void init(ModuleContext context) {
        this.context = context;
        LOGGER.info("Initializing Logging Module...");
        // Create the panel eagerly so that registry listeners are active before
        // Node/Database modules register their providers in their start().
        this.loggingPanel = new LoggingPanel(context);
    }

    @Override
    public void start() {
        LOGGER.info("Logging Module started.");
    }

    @Override
    public void stop() {
        // The LoggingPanel's registry listeners are removed in its dispose(); no further cleanup here.
        LOGGER.info("Logging Module stopped.");
    }

    @Override
    public JComponent getUI() {
        return loggingPanel;
    }
}