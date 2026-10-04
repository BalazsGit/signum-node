package application.utils.logging;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central orchestrator that composes module-specific logging profiles into a
 * single effective {@link Properties} object ready to be applied to Java's
 * {@code LogManager}.
 * <p>
 * <h3>Design Pattern: Composite + Factory</h3>
 * <ul>
 *   <li><b>Composite:</b> Merges multiple module profiles (each with its own defaults
 *       and on-disk overrides) into one unified configuration.</li>
 *   <li><b>Factory:</b> Produces ready-to-use {@link Properties} objects via
 *       {@link #createCompositeProfile(String, String, Map)}.</li>
 * </ul>
 * </p>
 *
 * <h3>Composition Order (highest priority last)</h3>
 * <ol>
 *   <li>Global base defaults (from existing {@code conf/logging-default.properties})</li>
 *   <li>Each enabled module's built-in defaults ({@link ModuleLoggingProfile#getDefaults()})</li>
 *   <li>On-disk profile file from {@code conf/{module}/logging/{profileName}.properties}</li>
 *   <li>User-provided runtime overrides (final argument map)</li>
 * </ol>
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * LoggingProfileManager manager = new LoggingProfileManager();
 *
 * Properties composite = manager.createCompositeProfile(
 *         "conf/mainnet",          // conf folder
 *         "production"             // base profile name per module
 * );
 *
 * // Apply to Java LogManager:
 * try (ByteArrayInputStream is = ...) {
 *     LogManager.getLogManager().readConfiguration(is);
 * }
 * }</pre>
 *
 * @see LoggingModuleRegistry
 * @see ModuleLoggingProfile
 * @see ModuleLoggingProvider
 */
public final class LoggingProfileManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingProfileManager.class);

    /** Global base defaults key (fallback when no module-specific profile exists). */
    public static final String GLOBAL_BASE_PROFILE = "logging-default";

    // ── Construction ──────────────────────────────────────────────────

    /**
     * Creates a new manager instance. Not a singleton — safe to instantiate per-request
     * or reuse as a long-lived bean.
     */
    public LoggingProfileManager() {
        // No state — pure orchestrator
    }

    // ── Core composition API ──────────────────────────────────────────

    /**
     * Creates a composite logging profile by merging enabled modules in registration order.
     *
     * @param confFolder   Base configuration folder (e.g. "conf/mainnet")
     * @param profileName  The profile name to load from each module's directory
     *                     (without .properties extension)
     * @return Merged Properties object (never null)
     */
    public Properties createCompositeProfile(
            String confFolder,
            String profileName) {

        Properties result = new Properties();

        // Step 1: Load global base defaults
        loadGlobalBaseDefaults(confFolder, result);

        // Step 2-4: Iterate registered modules in order
        Collection<ModuleLoggingProvider> providers = LoggingModuleRegistry.getInstance().getAllProviders();
        LOGGER.info("Composing logging profile '{}' from {} registered module(s)", profileName, providers.size());

        for (ModuleLoggingProvider provider : providers) {
            ModuleLoggingProfile profile = provider.getProfile();
            String moduleId = profile.getModuleId();

            // Step 2: Merge module defaults
            profile.mergeDefaults(result, false);
            LOGGER.debug("Merged defaults for module '{}'", moduleId);

            // Step 3: Load on-disk profile file overrides
            Properties diskProps = provider.loadProfileFile(confFolder, profileName);
            result.putAll(diskProps);
            LOGGER.debug("Applied disk overrides for module '{}' ({} keys)", moduleId, diskProps.size());
        }

        LOGGER.info("Composite profile '{}' assembled with {} total property entries", profileName, result.size());
        return result;
    }

    /**
     * Overload that accepts runtime overrides applied AFTER all composition steps.
     *
     * @param confFolder   Base configuration folder
     * @param profileName  Profile name
     * @param overrides    Final key→value overrides (highest priority)
     * @return Merged Properties (never null)
     */
    public Properties createCompositeProfile(
            String confFolder,
            String profileName,
            Map<String, String> overrides) {

        Properties result = createCompositeProfile(confFolder, profileName);
        if (overrides != null && !overrides.isEmpty()) {
            for (Map.Entry<String, String> entry : overrides.entrySet()) {
                result.setProperty(entry.getKey(), entry.getValue());
            }
            LOGGER.debug("Applied {} runtime overrides", overrides.size());
        }
        return result;
    }

    // ── Metadata queries ──────────────────────────────────────────────

    /**
     * Returns all supported logger keys across registered modules (union of defaults).
     *
     * @return Immutable set of logger key names (never null)
     */
    public List<String> getAllSupportedLoggerKeys() {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (ModuleLoggingProvider provider : LoggingModuleRegistry.getInstance().getAllProviders()) {
            keys.addAll(provider.getProfile().getDefaults().keySet());
        }
        return Collections.unmodifiableList(new ArrayList<>(keys));
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Loads global base defaults from {@code conf/logging-default.properties} as a
     * fallback layer. This maintains backward compatibility with the existing codebase.
     */
    private void loadGlobalBaseDefaults(String confFolder, Properties target) {
        Path basePath = application.utils.io.PathUtils.resolvePath(confFolder);
        Path defaultFile = basePath.resolve(GLOBAL_BASE_PROFILE + ".properties");

        if (!java.nio.file.Files.exists(defaultFile)) {
            LOGGER.debug("Global base defaults file not found: {}", defaultFile);
            return;
        }

        try (FileInputStream is = new FileInputStream(defaultFile.toFile())) {
            Properties base = new Properties();
            base.load(is);
            target.putAll(base);
            LOGGER.info("Loaded {} global base default entries from {}", base.size(), defaultFile.getFileName());
        } catch (IOException e) {
            LOGGER.warn("Failed to load global base defaults from {}: {}", defaultFile, e.getMessage());
        }
    }

    @Override
    public String toString() {
        int modules = LoggingModuleRegistry.getInstance().size();
        return "LoggingProfileManager{registeredModules=" + modules + '}';
    }
}