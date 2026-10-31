package application.module.logging;

import application.utils.logging.ModuleLoggingProfile;
import application.utils.logging.ModuleLoggingProvider;

/**
 * Registers the SYSTEM (JVM-global) logging profile with the global
 * {@link application.utils.logging.LoggingModuleRegistry} so that it appears as
 * a first-class "System" tab in the Logging module.
 * <p>
 * Instantiated and {@link #register() registered} by the {@link LoggingModule}
 * during its {@code start()} lifecycle (unregistered on {@code stop()}), which
 * places the System tab alongside the dynamically-registered Node/Database
 * tabs.
 * </p>
 *
 * @see SystemLoggingProfile
 * @see ModuleLoggingProvider
 */
public class SystemLoggingProvider extends ModuleLoggingProvider {

    private final ModuleLoggingProfile profile;

    /**
     * Creates a provider backed by the default {@link SystemLoggingProfile}.
     */
    public SystemLoggingProvider() {
        this(new SystemLoggingProfile());
    }

    /**
     * Creates a provider with a custom profile implementation (useful for testing).
     *
     * @param profile The profile to expose
     */
    public SystemLoggingProvider(ModuleLoggingProfile profile) {
        this.profile = profile;
    }

    @Override
    public ModuleLoggingProfile getProfile() {
        return profile;
    }

    @Override
    public String toString() {
        return "SystemLoggingProvider{profile=" + getProfile().getDisplayName() + '}';
    }
}
