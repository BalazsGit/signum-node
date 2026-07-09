package application.module.node.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

public class NodeProfile {
    private static final Logger LOGGER = LoggerFactory.getLogger(NodeProfile.class);
    private static final Path NODE_CONF_DIR = Paths.get("conf", "node");

    /**
     * Set of reserved profile names that are excluded from profile discovery and cannot be
     * used when saving new profiles.
     * <p>
     * These names correspond to informational template files containing only commented-out
     * default settings. Users can open these files manually to explore available configuration
     * options, but they are not intended to be loaded or saved as active profiles since they
     * carry no uncommented (active) configuration values.
     * </p>
     * <ul>
     *   <li>{@code node-default} — default node configuration reference</li>
     *   <li>{@code logging-default} — default logging configuration reference</li>
     * </ul>
     * <p>
     * We use an explicit allow-list of reserved names rather than a generic suffix pattern
     * (like {@code endsWith("-default")}) to avoid incorrectly blocking user profiles that
     * happen to end with "-default" (e.g., "my-custom-default"). Only the specific reserved
     * names defined here are excluded.
     * </p>
     */
    private static final Set<String> RESERVED_PROFILE_NAMES = Set.of("node-default", "logging-default");

    private final Properties properties = new Properties();
    private final String profileName;

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

    /**
     * Discovers all node profiles from conf/node/*.properties files.
     *
     * @return Array of loaded NodeProfile objects
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

                    // Skip reserved profile names: these are informational template files
                    // containing only commented-out default settings for user reference.
                    // They are not intended to be loaded as active profiles since they have
                    // no uncommented configuration values. See RESERVED_PROFILE_NAMES for details.
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
     * Checks if a profile name is reserved and should be excluded from discovery and saving.
     * <p>
     * Reserved names like {@code node-default} and {@code logging-default} are informational
     * template files containing only commented-out default settings for user reference.
     * These files are NOT loadable as active profiles since they contain no uncommented
     * configuration values. Users can manually open these files to explore available options.
     * </p>
     * <p>
     * We use an explicit set of reserved names (not a suffix pattern) so that user profiles
     * ending with "-default" (e.g., "my-custom-default") are not incorrectly blocked.
     * Only the specific names in {@link #RESERVED_PROFILE_NAMES} are excluded.
     * </p>
     *
     * @param profileName the profile name (without .properties extension)
     * @return true if this name is reserved and should be excluded from discovery/saving
     */
    public static boolean isReservedProfileName(String profileName) {
        return profileName != null && RESERVED_PROFILE_NAMES.contains(profileName);
    }

    /**
     * Loads a specific profile by name from conf/node/{name}.properties.
     *
     * @param profileName the name of the profile (without .properties extension)
     * @return The loaded NodeProfile, or null if not found
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
