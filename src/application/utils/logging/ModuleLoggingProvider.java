package application.utils.logging;

import application.utils.config.PropertiesProfileLoader;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contract that each module implements to participate in the composite
 * logging infrastructure.
 * <p>
 * A provider supplies:
 * <ul>
 *   <li>A {@link ModuleLoggingProfile} that describes the module's logger keys,
 *       defaults, presets, and metadata.</li>
 *   <li>Optional on-disk profile files located under {@code conf/{moduleId}/logging/}.</li>
 * </ul>
 * </p>
 *
 * <h3>Design Pattern: Factory + Strategy</h3>
 * Each provider acts as a factory for its module's logging data and provides
 * swappable strategies (presets) the user can select at runtime.
 * <p>
 * Profile discovery and loading are delegated to {@link PropertiesProfileLoader},
 * which is specifically designed for Java {@code .properties}-based logging presets.
 * Other profile formats (e.g., JSON-based database profiles) use their own loaders.
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * public class DatabaseLoggingProvider extends ModuleLoggingProvider {
 *     private final ModuleLoggingProfile profile = new DatabaseLoggingProfile();
 *
 *     @Override
 *     public ModuleLoggingProfile getProfile() { return profile; }
 * }
 *
 * // At startup:
 * LoggingModuleRegistry.getInstance().register(new DatabaseLoggingProvider());
 * }</pre>
 *
 * @see ModuleLoggingProfile
 * @see PropertiesProfileLoader
 * @see LoggingModuleRegistry
 * @see LoggingProfileManager
 */
public abstract class ModuleLoggingProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModuleLoggingProvider.class);

    /** Reserved logging profile names excluded from discovery. */
    private static final java.util.Set<String> RESERVED_LOGGING_NAMES =
            Collections.singleton("logging-default");

    // ── Mandatory ─────────────────────────────────────────────────────

    /**
     * Returns the logging profile (metadata + defaults) for this module.
     *
     * @return The module's {@link ModuleLoggingProfile} implementation
     */
    public abstract ModuleLoggingProfile getProfile();

    // ── Convenience delegates ─────────────────────────────────────────

    /**
     * Returns the unique module ID. Delegates to {@link #getProfile()}.
     */
    public String getModuleId() {
        return getProfile().getModuleId();
    }

    /**
     * Returns the config path for this module's logging files.
     * Resolves to: {@code confFolder/{moduleId}/{loggingSubDir}/}
     *
     * @param confFolder Base configuration folder (e.g. "conf/mainnet")
     * @return Full path to the module's logging subdirectory
     */
    public Path getLoggingConfigPath(String confFolder) {
        return application.utils.io.PathUtils.resolvePath(confFolder)
                .resolve(getModuleId())
                .resolve(getProfile().getLoggingSubDir());
    }

    /**
     * Returns the logging category name used with {@link PropertiesProfileLoader}.
     * Defaults to the module's logging subdirectory from the profile.
     */
    protected String getLoggingCategory() {
        return getProfile().getLoggingSubDir();
    }

    /**
     * Scans the module's logging directory and returns discovered profile names.
     * Delegates to {@link PropertiesProfileLoader#discoverProfiles}.
     * <p>
     * Profile name is the file stem (without {@code .properties} extension).
     * Reserved names (e.g., {@code logging-default}) are excluded.
     *
     * @param confFolder Base configuration folder
     * @return Unmodifiable list of profile names found on disk (never null)
     */
    public List<String> discoverProfiles(String confFolder) {
        try {
            return PropertiesProfileLoader.discoverProfiles(
                    confFolder, getModuleId(), getLoggingCategory(), RESERVED_LOGGING_NAMES);
        } catch (Exception e) {
            LOGGER.warn("Failed to scan logging profiles for module '{}': {}",
                    getModuleId(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Loads a named profile file from this module's logging directory into Properties.
     * Delegates to {@link PropertiesProfileLoader#loadProfile}.
     * <p>
     * Returns an empty Properties if the file does not exist.
     *
     * @param confFolder  Base configuration folder
     * @param profileName Profile name without extension
     * @return Loaded properties (never null)
     */
    public Properties loadProfileFile(String confFolder, String profileName) {
        try {
            Properties props = PropertiesProfileLoader.loadProfile(
                    confFolder, getModuleId(), getLoggingCategory(), profileName);
            if (!props.isEmpty()) {
                LOGGER.info("Loaded {} properties for preset '{}'", props.size(), profileName);
            }
            return props;
        } catch (Exception e) {
            LOGGER.error("Failed to load profile '{}' for module '{}'",
                    profileName, getModuleId(), e);
            return new Properties();
        }
    }

    // ── Registration helpers ──────────────────────────────────────────

    /**
     * Convenience method to register this provider with the global registry.
     * Call this from your module's {@code start()} lifecycle method.
     */
    public void register() {
        LoggingModuleRegistry.getInstance().register(this);
        LOGGER.info("Auto-registered logging provider for module '{}'", getModuleId());
    }

    /**
     * Convenience method to unregister this provider from the global registry.
     * Call this from your module's {@code stop()} lifecycle method.
     */
    public void unregister() {
        LoggingModuleRegistry.getInstance().unregister(getModuleId());
    }

    // ── Object contract ───────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModuleLoggingProvider that = (ModuleLoggingProvider) o;
        return getProfile().equals(that.getProfile());
    }

    @Override
    public int hashCode() {
        return getProfile().hashCode();
    }

    @Override
    public String toString() {
        return "ModuleLoggingProvider{module='" + getModuleId() + "', class=" + getClass().getSimpleName() + '}';
    }
}
