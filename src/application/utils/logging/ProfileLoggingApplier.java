package application.utils.logging;

import application.module.node.profile.NodeProfile;
import application.module.node.profile.ProfileConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.LogManager;

/**
 * Applies per-node-profile logging configuration using the priority chain:
 * <ol>
 *   <li><b>profiles.json loggingPresets</b> – per-module level overrides defined in
 *       {@code ProfileConfig.ProfileEntry.loggingPresets} (highest priority)</li>
 *   <li><b>profiles.json loggingProfile</b> – named preset reference from
 *       {@code ProfileConfig.ProfileEntry.loggingProfile}</li>
 *   <li><b>NodeProfile.properties logging.preset</b> – value of
 *       {@code NodeProfile.PROPERTY_LOGGING_PRESET} key in the node's .properties file</li>
 *   <li><b>Hardcoded default</b> – {@link NodeProfile#DEFAULT_LOGGING_PRESET} (lowest priority)</li>
 * </ol>
 * <p>
 * This class is a pure utility — it does not hold state and can be called at any point
 * during node startup to apply the correct logging configuration for a given profile.
 * </p>
 *
 * @since 4.0
 */
public final class ProfileLoggingApplier {

    /** Shorthand prefix used in legacy logging properties files. */
    private static final String SHORTHAND_PREFIX = "node.";
    /** Actual Java package prefix that the shorthand maps to. */
    private static final String ACTUAL_PACKAGE_PREFIX = "application.module.node.";

    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileLoggingApplier.class);

    private ProfileLoggingApplier() {
        // utility class
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Applies the effective logging configuration for the given node profile.
     * <p>
     * The resolution order is: profiles.json presets → profiles.json named profile
     * → NodeProfile.properties preset → hardcoded default.
     * </p>
     *
     * @param confFolder   base configuration folder (e.g., "conf/mainnet")
     * @param profileName  the node profile name (e.g., "mainnet", "testnet")
     */
    public static void apply(String confFolder, String profileName) {
        if (confFolder == null || confFolder.isEmpty()) {
            throw new IllegalArgumentException("confFolder must not be null or empty");
        }
        if (profileName == null || profileName.isEmpty()) {
            throw new IllegalArgumentException("profileName must not be null or empty");
        }

        // Step 1: Resolve the effective preset name and per-module overrides
        ResolvedPreset resolved = resolvePreset(confFolder, profileName);

        LOGGER.info("Applying logging for profile '{}': preset='{}', moduleOverrides={}",
                profileName, resolved.presetName, resolved.moduleOverrides);

        // Step 2: Build composite Properties from LoggingProfileManager
        LoggingProfileManager manager = new LoggingProfileManager();

        // Convert module overrides to the format LoggingProfileManager expects (module -> preset)
        Map<String, String> presetMap = buildPresetMap(resolved);

        Properties composite;
        if (!resolved.moduleOverrides.isEmpty()) {
            // We have per-module level overrides – apply them as runtime overrides
            composite = manager.createCompositeProfile(confFolder, resolved.presetName,
                    null, resolved.moduleOverrides);
        } else {
            composite = manager.createCompositeProfile(confFolder, resolved.presetName, presetMap);
        }

        // Step 3: Remap 'node.*' shorthand to actual package prefix
        Properties mappedProperties = remapShorthand(composite);

        // Step 4: Apply to JUL LogManager
        try (ByteArrayOutputStream outStream = new ByteArrayOutputStream();
             ByteArrayInputStream inStream = createInputStream(mappedProperties)) {
            mappedProperties.store(outStream, "Per-profile logging configuration for '" + profileName + "'");
            inStream.reset();
            LogManager.getLogManager().readConfiguration(inStream);
            LOGGER.info("Logging configuration applied for profile '{}'", profileName);
        } catch (Exception e) {
            LOGGER.error("Failed to apply logging configuration for profile '{}': {}",
                    profileName, e.getMessage(), e);
        }
    }

    // =========================================================================
    // Resolution Logic
    // =========================================================================

    /**
     * Resolves the effective preset name and per-module overrides for a profile.
     */
    static ResolvedPreset resolvePreset(String confFolder, String profileName) {
        // Load NodeProfile from .properties file
        NodeProfile nodeProfile = NodeProfile.loadByName(profileName);

        // Default preset (hardcoded fallback)
        String presetName = NodeProfile.DEFAULT_LOGGING_PRESET;
        Map<String, String> moduleOverrides = new HashMap<>();

        // Priority 1: profiles.json loggingPresets (per-module overrides) – highest priority
        ProfileConfig profileConfig = new ProfileConfig();
        Map<String, String> presetsFromJson = profileConfig.getLoggingPresets(profileName);
        if (presetsFromJson != null && !presetsFromJson.isEmpty()) {
            moduleOverrides.putAll(presetsFromJson);
            LOGGER.debug("Using per-module overrides from profiles.json for '{}': {}",
                    profileName, presetsFromJson);
        }

        // Priority 2: profiles.json loggingProfile (named preset)
        String namedProfile = profileConfig.getLoggingProfile(profileName);
        if (namedProfile != null && !namedProfile.isEmpty()) {
            presetName = namedProfile.trim();
            LOGGER.debug("Using named logging profile from profiles.json for '{}': {}",
                    profileName, presetName);
        }

        // Priority 3: NodeProfile.properties logging.preset – only override if not set by profiles.json
        if (nodeProfile != null && nodeProfile.hasLoggingPreset()) {
            String propsPreset = nodeProfile.getLoggingPreset();
            if (!NodeProfile.DEFAULT_LOGGING_PRESET.equals(propsPreset)) {
                // Only use .properties preset if it's non-default AND profiles.json didn't set one
                if (namedProfile == null || namedProfile.isEmpty()) {
                    presetName = propsPreset;
                    LOGGER.debug("Using logging preset from NodeProfile.properties for '{}': {}",
                            profileName, presetName);
                }
            }
        }

        // Priority 4: hardcoded default is already set as initial value

        return new ResolvedPreset(presetName, moduleOverrides);
    }

    /**
     * Converts resolved data to a presetMap for LoggingProfileManager.
     */
    private static Map<String, String> buildPresetMap(ResolvedPreset resolved) {
        Map<String, String> presetMap = new HashMap<>();
        // Use the preset name for the 'node' module
        if (resolved.presetName != null && !resolved.presetName.isEmpty()) {
            presetMap.put("node", resolved.presetName);
        }
        return presetMap;
    }

    /**
     * Remaps {@code node.*} keys to {@code application.module.node.*} so both
     * shorthand and fully-qualified logger names work.
     */
    static Properties remapShorthand(Properties source) {
        Properties result = new Properties();
        for (String key : source.stringPropertyNames()) {
            String value = source.getProperty(key);
            result.setProperty(key, value);
            if (key.startsWith(SHORTHAND_PREFIX)) {
                result.setProperty(ACTUAL_PACKAGE_PREFIX + key.substring(SHORTHAND_PREFIX.length()), value);
            }
        }
        return result;
    }

    /**
     * Creates a ByteArrayInputStream from Properties.
     */
    private static ByteArrayInputStream createInputStream(Properties props) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        props.store(baos, null);
        return new ByteArrayInputStream(baos.toByteArray());
    }

    // =========================================================================
    // Internal Data Types
    // =========================================================================

    /**
     * Result of preset resolution.
     */
    static class ResolvedPreset {
        final String presetName;
        final Map<String, String> moduleOverrides;

        ResolvedPreset(String presetName, Map<String, String> moduleOverrides) {
            this.presetName = presetName;
            this.moduleOverrides = moduleOverrides;
        }
    }
}