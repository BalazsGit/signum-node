package application.utils.logging;

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
 * @see LoggingModuleRegistry
 * @see LoggingProfileManager
 */
public abstract class ModuleLoggingProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModuleLoggingProvider.class);

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
     * Resolves to: {@code conf/{moduleId}/logging/}
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
     * Scans the module's logging directory and returns discovered profile names.
     * Profile name is the file stem (without {@code .properties} extension).
     *
     * @return Unmodifiable list of profile names found on disk (never null)
     */
    public List<String> discoverProfiles(String confFolder) {
        Path loggingDir = getLoggingConfigPath(confFolder);
        if (!java.nio.file.Files.exists(loggingDir)) {
            LOGGER.debug("Module '{}' has no logging directory at {}", getModuleId(), loggingDir);
            return Collections.emptyList();
        }
        try (var stream = java.nio.file.Files.list(loggingDir)) {
            return stream.filter(p -> !java.nio.file.Files.isDirectory(p))
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".properties"))
                    .map(name -> name.substring(0, name.length() - 11))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            LOGGER.warn("Failed to scan logging profiles for module '{}': {}", getModuleId(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Loads a named profile file from this module's logging directory into Properties.
     * Returns an empty Properties if the file does not exist.
     *
     * @param confFolder  Base configuration folder
     * @param profileName Profile name without extension
     * @return Loaded properties (never null)
     */
    public Properties loadProfileFile(String confFolder, String profileName) {
        Path file = getLoggingConfigPath(confFolder).resolve(profileName + ".properties");
        Properties props = new Properties();
        if (!java.nio.file.Files.exists(file)) {
            LOGGER.debug("Profile file not found: {}", file);
            return props;
        }
        try (var is = java.nio.file.Files.newInputStream(file)) {
            props.load(is);
            LOGGER.info("Loaded {} properties from {}", props.size(), file.getFileName());
        } catch (Exception e) {
            LOGGER.error("Failed to load profile '{}' for module '{}'", profileName, getModuleId(), e);
        }
        return props;
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