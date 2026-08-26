package application.module.node.profile;


import application.utils.config.ConfigPaths;
import application.utils.config.PropertiesProfileEntity;
import application.utils.config.PropertiesProfileLoader;

import java.nio.file.Path;
import java.util.Objects;
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
 *   <li>{@code node-default} - default template (not a runnable profile)</li>
 *   <li>{@code logging-default} - logging default template</li>
 * </ul>
 *
 * @see PropertiesProfileLoader
 * @see PropertiesProfileEntity
 */
public class NodeProfile implements PropertiesProfileEntity {

    // ── Constants ──────────────────────────────────────────────────────

    /** Runtime configuration root directory (parent of JAR location). */
    static final String CONF_ROOT = ConfigPaths.RUNTIME_CONF_ROOT;

    /** Module identifier for node profiles. */
    static final String MODULE_ID = "node";

    /** Profile category (subdirectory under module). */
    static final String CATEGORY = PropertiesProfileLoader.DEFAULT_CATEGORY_PROFILES;

    /** Default profile file name (follows {module}-default.properties convention). */
    static final String DEFAULT_PROFILE_FILENAME = MODULE_ID + "-default.properties";

    /** Default logging file name. */
    static final String DEFAULT_LOGGING_FILENAME =
            PropertiesProfileLoader.DEFAULT_LOGGING_DEFAULT_FILENAME;

    /** Property key for per-profile logging preset selection. */
    public static final String PROPERTY_LOGGING_PRESET = "logging.preset";

    /** Default logging preset when not specified in profile. */
    public static final String DEFAULT_LOGGING_PRESET = "standard";

    /** Property key for node autostart. When true, the node starts automatically on app launch. */
    public static final String PROPERTY_AUTOSTART = "node.autostart";

    /** Default autostart value when not specified in profile. */
    public static final boolean DEFAULT_AUTOSTART = false;

    /**
     * Set of reserved profile names that are excluded from profile discovery
     * and cannot be used when saving new profiles.
     */
    static final Set<String> RESERVED_PROFILE_NAMES = Set.of("node-default", "logging-default");

    // ── Immutable Identity Fields ──────────────────────────────────────

    /** Unique profile identifier (immutable). */
    private final String profileName;

    /** Path to the .properties file this profile was loaded from (immutable). */
    private final Path propertiesPath;

    // ── Config Data ────────────────────────────────────────────────────

    /** Properties backing store (Single Source of Truth for config values). */
    private final Properties properties = new Properties();
    // ── Construction ───────────────────────────────────────────────────

    /**
     * Legacy constructor for backward compatibility with {@link PropertiesProfileEntity}
     * contract. Creates a minimal profile with default runtime and no GUI support.
     *
     * @param profileName the profile name (identifier)
     * @deprecated Use {@link Builder} for new code
     */
    @Deprecated(forRemoval = false)
    public NodeProfile(String profileName) {
        this.profileName = Objects.requireNonNull(profileName, "profileName must not be null");
        this.propertiesPath = null;
    }

    /**
     * Package-private Builder constructor. All fields are set via the Builder
     * to enforce proper initialization before the profile is used.
     *
     * @param builder the configured Builder instance
     */
    NodeProfile(Builder builder) {
        this.profileName = Objects.requireNonNull(builder.name, "name must not be null");
        this.propertiesPath = builder.propertiesPath;
        

        if (builder.properties != null) {
            this.properties.putAll(builder.properties);
        }
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

    // ── Autostart Configuration ────────────────────────────────────────

    /**
     * Returns whether the node should start automatically when the application launches.
     * Falls back to {@link #DEFAULT_AUTOSTART} if not explicitly set in the profile.
     *
     * @return true if autostart is enabled, false otherwise
     */
    public boolean isAutostart() {
        String value = properties.getProperty(PROPERTY_AUTOSTART);
        if (value == null || value.isEmpty()) {
            return DEFAULT_AUTOSTART;
        }
        // Support Java Properties boolean-style values: on/off, true/false, yes/no, 1/0
        return java.lang.Boolean.parseBoolean(value)
                || "on".equalsIgnoreCase(value.trim())
                || "yes".equalsIgnoreCase(value.trim());
    }

    /**
     * Sets whether the node should start automatically when the application launches.
     *
     * @param value true to enable autostart, false to disable
     */
    public void setAutostart(boolean value) {
        properties.setProperty(PROPERTY_AUTOSTART, String.valueOf(value));
    }

    /**
     * Returns true if this profile has an explicit autostart setting configured.
     */
    public boolean hasAutostartSetting() {
        return properties.containsKey(PROPERTY_AUTOSTART);
    }


    // ── PropertiesPath Access ──────────────────────────────────────────

    /**
     * Returns the file path this profile was loaded from, or null if created programmatically.
     *
     * @return the properties file path, or null
     */
    public Path getPropertiesPath() {
        return propertiesPath;
    }

    // ── Builder Pattern ────────────────────────────────────────────────

    /**
     * Fluent API for constructing {@link NodeProfile} instances with full control.
     * <p>
     * <b>Required:</b> name, properties (can be empty).
      * <b>Optional:</b> properties, propertiesPath.
     * <p>
     * Usage:
     * <pre>{@code
     * NodeProfile profile = new NodeProfile.Builder("mainnet")
     *         .properties(loadedProperties)
     *         .propertiesPath(somePath)
     *         .build();
     * }</pre>
     */
    public static class Builder {
        private final String name;
        private Properties properties;
        private Path propertiesPath;

        /**
         * Starts building a profile with the given name.
         *
         * @param name the profile identifier (required)
         */
        public Builder(String name) {
            this.name = Objects.requireNonNull(name, "name must not be null");
        }

        /**
         * Sets the properties backing store.
         *
         * @param properties the properties to use
         * @return this builder for chaining
         */
        public Builder properties(Properties properties) {
            this.properties = properties;
            return this;
        }

        /**
         * Sets the path to the source .properties file.
         *
         * @param path the file path, or null if created programmatically
         * @return this builder for chaining
         */
        public Builder propertiesPath(Path path) {
            this.propertiesPath = path;
            return this;
        }

        /**
         * Builds the NodeProfile. Validates that required fields are set.
         *
         * @return a fully initialized {@link NodeProfile}
         * @throws IllegalStateException if name is null (should not happen)
         */
        public NodeProfile build() {
            return new NodeProfile(this);
        }
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
        return "NodeProfile{name='" + profileName + "', properties=" + properties.size() +
                " entries}";
    }
}
