package application.utils.logging;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for module-specific logging profiles.
 * <p>
 * Each functional module (node, database, pool, etc.) extends this class to define
 * its own set of logger name → level mappings that can be composed into a
 * single effective logging configuration at runtime.
 * </p>
 *
 * <h3>Design Pattern: Strategy</h3>
 * Subclasses encapsulate different logging strategies (verbose, production, minimal)
 * that can be swapped without modifying the composition logic.
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * public class DatabaseLoggingProfile extends ModuleLoggingProfile {
 *     @Override
 *     public String getModuleId() { return "database"; }
 *
 *     @Override
 *     public Map<String, String> getDefaults() {
 *         Map<String, String> defaults = new LinkedHashMap<>();
 *         defaults.put("com.zaxxer.hikari.level", "WARNING");
 *         defaults.put("org.jooq.Constants.level", "OFF");
 *         return defaults;
 *     }
 * }
 * }</pre>
 *
 * @see LoggingModuleRegistry
 * @see LoggingProfileManager
 */
public abstract class ModuleLoggingProfile {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModuleLoggingProfile.class);

    /**
     * Returns the unique identifier for this module.
     * Must match the directory name under {@code conf/} where logging profiles reside.
     *
     * @return Module ID (e.g., "node", "database", "pool")
     */
    public abstract String getModuleId();

    /**
     * Returns a human-readable display name for this module.
     *
     * @return Display name shown in the GUI (e.g., "Signum Node", "Database Engine")
     */
    public abstract String getDisplayName();

    /**
     * Returns a short description of what this module controls via logging.
     * Used in help tooltips and documentation.
     *
     * @return Description text
     */
    public abstract String getDescription();

    /**
     * Provides the default logger level mappings for this module.
     * These defaults are applied when no explicit profile file is found on disk.
     *
     * @return Immutable map of logger names to their default levels
     */
    public abstract Map<String, String> getDefaults();

    /**
     * Returns the supported logging level presets for this module.
     * A preset groups related loggers into a single named configuration
     * (e.g., "minimal", "standard", "verbose", "debug").
     *
     * <p>The returned map uses preset name as key and a nested map of
     * logger→level overrides as value. These overrides are merged on top
     * of the base defaults from {@link #getDefaults()}.</p>
     *
     * @return Immutable map of preset names to their logger level overrides
     */
    public Map<String, Map<String, String>> getPresetOverrides() {
        return Collections.emptyMap();
    }

    /**
     * Returns the relative path segment for this module's logging configuration directory.
     * Default implementation resolves to {@code conf/{moduleId}/logging/}.
     *
     * <p>Override this method if your module uses a non-standard subdirectory structure.</p>
     *
     * @return Path segment (default: "logging")
     */
    public String getLoggingSubDir() {
        return "logging";
    }

    /**
     * Validates a Properties object before it is merged into the composite profile.
     * Subclasses can enforce constraints such as required keys or valid value ranges.
     *
     * <p>Default implementation performs no validation (always passes).</p>
     *
     * @param properties The properties to validate
     * @throws IllegalArgumentException if validation fails
     */
    public void validate(Properties properties) {
        // Default: accept all inputs
    }

    /**
     * Merges the default logger mappings from this profile into the given Properties.
     * Existing entries in {@code target} are NOT overwritten unless {@code force} is true.
     *
     * @param target The properties map to merge into
     * @param force  If true, defaults overwrite existing values
     */
    public void mergeDefaults(Properties target, boolean force) {
        Map<String, String> defaults = getDefaults();
        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            if (force || !target.containsKey(entry.getKey())) {
                target.setProperty(entry.getKey(), entry.getValue());
                LOGGER.debug("Merged default logger: {} -> {}", entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Applies the specified preset overrides on top of the given Properties.
     *
     * @param target  The properties to apply overrides onto
     * @param presetName The name of the preset to apply
     * @return true if the preset was found and applied, false otherwise
     */
    public boolean applyPreset(Properties target, String presetName) {
        Map<String, Map<String, String>> presets = getPresetOverrides();
        Map<String, String> overrides = presets.get(presetName);
        if (overrides == null || overrides.isEmpty()) {
            LOGGER.warn("Preset '{}' not found for module '{}'", presetName, getModuleId());
            return false;
        }
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            target.setProperty(entry.getKey(), entry.getValue());
            LOGGER.debug("Applied preset override [{}]: {} -> {}", presetName, entry.getKey(), entry.getValue());
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModuleLoggingProfile that = (ModuleLoggingProfile) o;
        return Objects.equals(getModuleId(), that.getModuleId())
                && Objects.equals(getDefaults(), that.getDefaults());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getModuleId(), getDefaults());
    }

    @Override
    public String toString() {
        return "ModuleLoggingProfile{" +
                "moduleId='" + getModuleId() + '\'' +
                ", displayName='" + getDisplayName() + '\'' +
                '}';
    }
}