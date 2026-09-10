package application.module.database;
import application.utils.config.ModuleIds;

import application.api.Module;
import application.api.ModuleContext;
import application.module.database.gui.DatabaseConfigurationPanel;
import application.module.database.logging.DatabaseLoggingProvider;

import javax.swing.JComponent;

public class DatabaseModule implements Module {

    private DatabaseLoggingProvider loggingProvider;

    @Override
    public String getId() {
        return ModuleIds.DATABASE;
    }

    @Override
    public String getDisplayName() {
        return "Database"; // This can be customized as per your preference.
    }

    @Override
    public void init(ModuleContext context) {
        // Initialize database configurations here
        // Create directory structure and load profiles from files as per the specified
        // guidelines.
        // Example: createDirectoryStructure();
        // loadProfilesFromFileSystem();
    }

    @Override
    public void start() {
        // Register the logging provider so the composite logging infrastructure
        // knows about the Database module's built-in defaults & presets. This is
        // what makes the "Database Engine" tab appear in the Logging module GUI.
        if (loggingProvider == null) {
            loggingProvider = new DatabaseLoggingProvider();
        }
        loggingProvider.register();
    }

    @Override
    public void stop() {
        // Unregister the logging provider (cleanup registration).
        if (loggingProvider != null) {
            loggingProvider.unregister();
            loggingProvider = null;
        }
    }

    @Override
    public JComponent getUI() {
        // Provide the UI component for configuring and managing databases
        return new DatabaseConfigurationPanel(); // Adjust this according to your requirement
    }
}