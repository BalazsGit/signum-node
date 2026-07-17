package application.module.node.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import application.utils.io.PathUtils;
import application.utils.logging.ProfileLogger;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

public class NodeProfile {
    private static final Logger LOGGER = LoggerFactory.getLogger(NodeProfile.class);
    private static final Path NODE_CONF_DIR = PathUtils.resolvePath("conf/node");

    /**
     * Property key for per-profile logging preset selection.
     */
    public static final String PROPERTY_LOGGING_PRESET = "logging.preset";

    /** Default logging preset when not specified in profile */
    public static final String DEFAULT_LOGGING_PRESET = "standard";

    /**
     * Set of reserved profile names that are excluded from profile discovery and cannot be
     * used when saving new profiles.
     */
    private static final Set<String> RESERVED_PROFILE_NAMES = Set.of("node-default", "logging-default");

    private final Properties properties = new Properties();
    private final String profileName;
    private volatile ProfileLogger logger;

    public NodeProfile(String profileName) {
        this.profileName = profileName;
    }

    public String getName() {
        return profileName;
    }

    public Properties getProperties() {
        return properties;
    }

    public void setProperties(Properties newProps) {
        this.properties.clear();
        this.properties.putAll(newProps);
    }

    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

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

    // ── Static Factory Methods ─────────────────────────────────────────

    /**
     * Discovers all node profiles from conf/node/*.properties files.
     */
    public static NodeProfile[] loadAll() {
        List<NodeProfile> profiles = new ArrayList<>();

        if (!Files.exists(NODE_CONF_DIR)) {
            LOGGER.debug("Node profiles directory does not exist: {}", NODE_CONF_DIR);
            return profiles.toArray(new NodeProfile[0]);
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(NODE_CONF_DIR, "*.properties")) {
            for (Path file : stream) {
                try {
                    String profileName = file.getFileName().toString().replace(".properties", "");

                    if (isReservedProfileName(profileName)) {
                        LOGGER.debug("Skipping reserved profile name: {}", profileName);
                        continue;
                    }

                    NodeProfile profile = new NodeProfile(profileName);

                    try (InputStream is = Files.newInputStream(file)) {
                        profile.getProperties().load(is);
                    }

                    profiles.add(profile);
                } catch (Exception e) {
                    LOGGER.error("Error loading profile from {}", file.getFileName(), e);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error scanning node profiles directory", e);
        }

        return profiles.toArray(new NodeProfile[0]);
    }

    /**
     * Checks if a profile name is reserved.
     */
    public static boolean isReservedProfileName(String profileName) {
        return profileName != null && RESERVED_PROFILE_NAMES.contains(profileName);
    }

    /**
     * Loads a specific profile by name from conf/node/{name}.properties.
     */
    public static NodeProfile loadByName(String profileName) {
        Path profileFile = NODE_CONF_DIR.resolve(profileName + ".properties");
        if (!Files.exists(profileFile)) {
            LOGGER.debug("Profile file not found: {}", profileFile);
            return null;
        }

        try {
            NodeProfile profile = new NodeProfile(profileName);
            try (InputStream is = Files.newInputStream(profileFile)) {
                profile.getProperties().load(is);
            }
            return profile;
        } catch (Exception e) {
            LOGGER.error("Error loading profile {}", profileName, e);
            return null;
        }
    }

    // ── Object Contract ────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        NodeProfile that = (NodeProfile) o;
        return properties.equals(that.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(properties);
    }
}