package application.module.database.logging;

import application.utils.logging.ModuleLoggingProvider;
import application.utils.logging.ModuleLoggingProfile;

/**
 * Registers the Database module's logging profile with the global
 * {@link application.utils.logging.LoggingModuleRegistry}.
 * <p>
 * Typically instantiated once during application startup.
 * </p>
 *
 * @see DatabaseLoggingProfile
 * @see application.utils.logging.ModuleLoggingProvider
 */
public class DatabaseLoggingProvider extends ModuleLoggingProvider {

    private final ModuleLoggingProfile profile;

    /**
     * Creates a provider backed by the default {@link DatabaseLoggingProfile}.
     */
    public DatabaseLoggingProvider() {
        this(new DatabaseLoggingProfile());
    }

    /**
     * Creates a provider with a custom profile implementation (useful for testing).
     *
     * @param profile The profile to expose
     */
    public DatabaseLoggingProvider(ModuleLoggingProfile profile) {
        this.profile = profile;
    }

    @Override
    public ModuleLoggingProfile getProfile() {
        return profile;
    }

    @Override
    public String toString() {
        return "DatabaseLoggingProvider{profile=" + getProfile().getDisplayName() + '}';
    }
}