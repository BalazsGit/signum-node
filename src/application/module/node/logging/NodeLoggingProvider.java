package application.module.node.logging;

import application.utils.logging.ModuleLoggingProvider;
import application.utils.logging.ModuleLoggingProfile;

/**
 * Registers the Node module's logging profile with the global
 * {@link application.utils.logging.LoggingModuleRegistry}.
 * <p>
 * Typically instantiated once during {@code NodeModule.start()} lifecycle.
 * </p>
 *
 * @see NodeLoggingProfile
 * @see application.utils.logging.ModuleLoggingProvider
 */
public class NodeLoggingProvider extends ModuleLoggingProvider {

    private final ModuleLoggingProfile profile;

    /**
     * Creates a provider backed by the default {@link NodeLoggingProfile}.
     */
    public NodeLoggingProvider() {
        this(new NodeLoggingProfile());
    }

    /**
     * Creates a provider with a custom profile implementation (useful for testing).
     *
     * @param profile The profile to expose
     */
    public NodeLoggingProvider(ModuleLoggingProfile profile) {
        this.profile = profile;
    }

    @Override
    public ModuleLoggingProfile getProfile() {
        return profile;
    }

    /**
     * Registers this provider and logs confirmation.
     * Call from {@code NodeModule.start()}.
     */
    @Override
    public String toString() {
        return "NodeLoggingProvider{profile=" + getProfile().getDisplayName() + '}';
    }
}