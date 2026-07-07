package application.module.node.instance;

import application.module.node.props.PropertyService;
import application.utils.io.PathUtils;

import java.nio.file.Path;

/**
 * Builder for {@link NodeCoreContext} instances.
 * <p>
 * Encapsulates the complex setup required to construct a properly configured
 * node context: profile resolution, property loading, and validation.
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * NodeCoreContext ctx = new NodeCoreContextBuilder("mainnet", confPath)
 *         .build();
 * ctx.start();
 * }</pre>
 *
 * @since 4.0
 */
public final class NodeCoreContextBuilder {

    private final String profileName;
    private final Path confFolder;

    /**
     * Creates a new builder for the specified profile and configuration folder.
     *
     * @param profileName  human-readable profile identifier (e.g. "mainnet")
     * @param confFolder   base configuration folder path
     */
    public NodeCoreContextBuilder(String profileName, Path confFolder) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("Profile name must not be null or blank");
        }
        this.profileName = profileName.trim();
        this.confFolder = confFolder != null ? confFolder.toAbsolutePath() : PathUtils.resolvePath("conf");
    }

    /**
     * Creates a builder that resolves the configuration folder from the default.
     *
     * @param profileName human-readable profile identifier
     */
    public NodeCoreContextBuilder(String profileName) {
        this(profileName, null);
    }

    /**
     * Builds and returns a fully configured {@link NodeCoreContext}.
     * <p>
     * The context is <b>not</b> auto-started; the caller must explicitly invoke
     * {@link NodeCoreContext#start()} to begin initialisation.
     * </p>
     *
     * @return a new NodeCoreContext ready to be started
     * @throws NodeStartupException if property loading fails
     */
    public NodeCoreContext build() {
        PropertyService propertyService = loadProperties();
        return new NodeCoreContext(profileName, confFolder, propertyService);
    }

    /**
     * Loads properties for this profile using the Signum helper.
     */
    private PropertyService loadProperties() {
        String confPath = confFolder.toString();
        try {
            // Use existing Signum utility to load per-profile properties
            return application.module.node.Signum.loadPropertiesForProfile(confPath, profileName);
        } catch (Exception e) {
            throw new NodeStartupException(
                    "Failed to load properties for profile '" + profileName + "' from " + confPath, e);
        }
    }

    /**
     * Returns the configured profile name.
     */
    public String getProfileName() {
        return profileName;
    }

    /**
     * Returns the resolved configuration folder.
     */
    public Path getConfFolder() {
        return confFolder;
    }
}