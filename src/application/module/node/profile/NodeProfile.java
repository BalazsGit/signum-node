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

public class NodeProfile {
    private static final Logger LOGGER = LoggerFactory.getLogger(NodeProfile.class);
    private static final Path NODE_CONF_DIR = Paths.get("conf", "node");

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
