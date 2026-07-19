package application.module.node.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import application.utils.config.ConfigPaths;
import application.utils.config.PropertiesProfileEntity;
import application.utils.config.PropertiesProfileLoader;
import application.utils.logging.ProfileLogger;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Represents a single node profile configuration loaded from disk.
 * <p>
 * Implements {@link PropertiesProfileEntity} to participate in the properties-profile loading
 * pipeline via {@link PropertiesProfileLoader}.
 * <p>
 * <b>Note:</b> This class is specific to Java {@code .properties}-based profiles.
 * Other profile formats (e.g., JSON-based database profiles) do not use this loader.
 *
 * <h3>Path Schema</h3>
 * Profiles are discovered from: {@code conf/node/profiles/*.properties}
 *
 * <h3>Reserved Profile Names</h3>
 * The following names are excluded from discovery:
 * <ul>
 *   <li>{@code profile-default} - default template (not a runnable profile)</li>
 *   <li>{@code logging-default} - logging default template</li>
 * </ul>
 *
 * @see PropertiesProfileLoader
 * @see PropertiesProfileEntity
 */
public class NodeProfile implements PropertiesProfileEntity {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeProfile.class);

    /** Runtime configuration root directory (parent of JAR location). */
    static final String CONF_ROOT = ConfigPaths.RUNTIME_CONF_ROOT;

    /** Module identifier for node profiles. */
    static final String MODULE_ID = "node";

    /** Profile category (subdirectory under module). */
    static final String CATEGORY = PropertiesProfileLoader.DEFAULT_CATEGORY_PROFILES;

    /** Default profile file name. */
    static final String DEFAULT_PROFILE_FILENAME =
            PropertiesProfileLoader.DEFAULT_MODULE_DEFAULT_FILENAME;

    /** Default logging file name. */
    static final String DEFAULT_LOGGING_FILENAME =
            PropertiesProfileLoader.DEFAULT_LOGGING_DEFAULT_FILENAME;

    /** Property key for per-profile logging preset selection. */
    public static final String PROPERTY_LOGGING_PRESET = "logging.preset";

    /** Default logging preset when not specified in profile. */
    public static final String DEFAULT_LOGGING_PRESET = "standard";

    /**
     * Set of reserved profile names that are excluded from profile discovery
     * and cannot be used when saving new profiles.
     */
    static final Set<String> RESERVED_PROFILE_NAMES = Set.of("profile-default", "logging-default");

    private final String profileName;
    private final Properties properties = new Properties();
    private volatile ProfileLogger logger;

    // ── Construction ───────────────────────────────────────────────────

    /**
     * Creates a new NodeProfile with the given name.
     *
     * @param profileName the profile name (identifier)
     */
    public NodeProfile(String profileName) {
        this.profileName = profileName;
    }

    // ── PropertiesProfileEntity Contract ───────────────────────────────

    /** {@inheritDoc} */
    @Override
    public String getName() {
        return profileName;
    }

    /** {@inheritDoc} */
    @Override
    public Properties getProperties() {
        return properties;
    }

    /** {@inheritDoc} */
    @Override
    public void setProperties(Properties newProps) {
        this.properties.clear();
        this.properties.putAll(newProps);
    }

    // ── Property Accessors ─────────────────────────────────────────────

    /**
     * Gets a property value by key.
     *
     * @param key the property key
     * @return the property value, or null if not set
     */
    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    /**
     * Gets a property value with a default fallback.
     *
     * @param key             the property key
     * @param defaultValue    default value if key is not present
     * @return the property value or default
     */
    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    /**
     * Sets a property. If value is null, the key is removed.
     *
     * @param key   the property key
     * @param value the property value (null to remove)
     */
    public void setProperty(String key, String value) {
        if (value == null) {
            properties.remove(key);
        } else {
            properties.setProperty(key, value);
        }
    }

    // ── Centralized Logger Integration ─────────────────────────────────

    /**
     * Returns the ProfileLogger for this profile, lazily creating it on first access.
     * The logger is auto-configured to forward events to SystemLogger.
     *
     * @return the ProfileLogger instance (never null)
     */
    public ProfileLogger getLogger() {
        if (logger == null) {
            synchronized (this) {
                if (logger == null) {
                    logger = new ProfileLogger("node", profileName);
                }
            }
        }
        return logger;
    }

    /**
     * Returns an SLF4J Logger adapter backed by this profile's ProfileLogger.
     * Use this instead of LoggerFactory.getLogger() in Services that belong to this profile.
     *
     * @return the SLF4J Logger adapter (never null)
     */
    public org.slf4j.Logger getSlf4jLogger() {
        return new NodeProfileAdapter(getLogger());
    }

    /**
     * Closes the ProfileLogger when this profile is shut down.
     * Should be called during profile cleanup.
     */
    public void closeLogger() {
        if (logger != null) {
            logger.close();
        }
    }

    // ── Logging Configuration ──────────────────────────────────────────

    /**
     * Returns the logging preset name configured for this profile.
     * Falls back to {@link #DEFAULT_LOGGING_PRESET} if not set.
     */
    public String getLoggingPreset() {
        String preset = properties.getProperty(PROPERTY_LOGGING_PRESET);
        if (preset == null || preset.isEmpty()) {
            return DEFAULT_LOGGING_PRESET;
        }
        return preset.trim();
    }

    /**
     * Sets the logging preset for this profile.
     */
    public void setLoggingPreset(String preset) {
        if (preset == null || preset.isEmpty()) {
            properties.remove(PROPERTY_LOGGING_PRESET);
        } else {
            properties.setProperty(PROPERTY_LOGGING_PRESET, preset.trim());
        }
    }

    /**
     * Returns true if this profile has an explicit logging preset configured.
     */
    public boolean hasLoggingPreset() {
        return properties.containsKey(PROPERTY_LOGGING_PRESET);
    }

    // ── Static Factory Methods (delegating to PropertiesProfileLoader) ─

    /**
     * Discovers and loads all node profiles from {@code conf/node/profiles/*.properties}.
     * <p>
     * This method delegates to {@link PropertiesProfileLoader#loadAll} using the
     * standardized path schema. Reserved profile names (default templates)
     * are excluded from discovery.
     *
     * @return array of loaded NodeProfiles, empty if none found
     */
    public static NodeProfile[] loadAll() {
        try {
            return PropertiesProfileLoader.loadAll(
                    CONF_ROOT, MODULE_ID, CATEGORY, RESERVED_PROFILE_NAMES,
                    NodeProfile::new, NodeProfile.class);
        } catch (Exception e) {
            LOGGER.error("Error loading node profiles", e);
            return new NodeProfile[0];
        }
    }

    /**
     * Loads a specific profile by name from {@code conf/node/profiles/{name}.properties}.
     *
     * @param profileName the profile name (without extension)
     * @return the loaded NodeProfile, or null if not found
     */
    public static NodeProfile loadByName(String profileName) {
        try {
            Properties props = PropertiesProfileLoader.loadProfile(
                    CONF_ROOT, MODULE_ID, CATEGORY, profileName);

            if (props.isEmpty()) {
                LOGGER.debug("Profile file not found or empty: {}", profileName);
                return null;
            }

            NodeProfile profile = new NodeProfile(profileName);
            profile.setProperties(props);
            return profile;
        } catch (Exception e) {
            LOGGER.error("Error loading profile {}", profileName, e);
            return null;
        }
    }

    /**
     * Checks if a profile name is reserved.
     *
     * @param profileName the name to check
     * @return true if the name is reserved and cannot be used as a profile
     */
    public static boolean isReservedProfileName(String profileName) {
        return profileName != null && RESERVED_PROFILE_NAMES.contains(profileName);
    }

    /**
     * Discovers all available (non-reserved) profile names.
     *
     * @return sorted list of discoverable profile names
     */
    public static List<String> discoverProfileNames() {
        return PropertiesProfileLoader.discoverProfiles(
                CONF_ROOT, MODULE_ID, CATEGORY, RESERVED_PROFILE_NAMES);
    }

    // ── Default File Management ────────────────────────────────────────

    /**
     * Synchronizes the default profile file from classpath resources to runtime conf/.
     * Uses SHA-256 hash comparison to detect updates.
     */
    public static void syncDefaultProfileFile() {
        InputStream is = NodeProfile.class.getResourceAsStream(
                "/conf/node/profiles/profile-default.properties");
        if (is != null) {
            PropertiesProfileLoader.syncDefaultFile(
                    CONF_ROOT, MODULE_ID, CATEGORY, DEFAULT_PROFILE_FILENAME, is);
        } else {
            LOGGER.warn("Default profile resource not found on classpath");
        }
    }

    /**
     * Synchronizes the default logging file from classpath resources to runtime conf/.
     */
    public static void syncDefaultLoggingFile() {
        InputStream is = NodeProfile.class.getResourceAsStream(
                "/conf/node/logging/logging-default.properties");
        if (is != null) {
            PropertiesProfileLoader.syncDefaultFile(
                    CONF_ROOT, MODULE_ID, PropertiesProfileLoader.DEFAULT_CATEGORY_LOGGING,
                    DEFAULT_LOGGING_FILENAME, is);
        } else {
            LOGGER.warn("Default logging resource not found on classpath");
        }
    }

    /**
     * Ensures empty placeholder files exist for both profiles and logging
     * categories when no user profiles are discovered.
     */
    public static void ensureEmptyPlaceholdersIfNeeded() {
        PropertiesProfileLoader.ensureEmptyPlaceholdersForModule(
                CONF_ROOT, MODULE_ID, RESERVED_PROFILE_NAMES,
                "node", "logging");
    }

    /**
     * Full initialization: sync defaults + create placeholders if needed.
     * Call this once during application startup.
     */
    public static void initialize() {
        PropertiesProfileLoader.ensureProfileDirExists(CONF_ROOT, MODULE_ID, CATEGORY);
        PropertiesProfileLoader.ensureProfileDirExists(CONF_ROOT, MODULE_ID,
                PropertiesProfileLoader.DEFAULT_CATEGORY_LOGGING);
        syncDefaultProfileFile();
        syncDefaultLoggingFile();
        ensureEmptyPlaceholdersIfNeeded();
    }

    // ── Object Contract ────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NodeProfile that = (NodeProfile) o;
        return profileName.equals(that.profileName) && properties.equals(that.properties);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(profileName, properties);
    }

    @Override
    public String toString() {
        return "NodeProfile{name='" + profileName + "', properties=" + properties.size() + " entries}";
    }
}