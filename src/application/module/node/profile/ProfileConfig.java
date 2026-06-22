package application.module.node.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Loads and manages profile configuration from profiles.json.
 * Handles autostart, enabled status, and description per profile.
 */
public class ProfileConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileConfig.class);
    private static final String DEFAULT_CONFIG_PATH = "conf/node/profiles.json";
    private final Path configPath;
    private final Gson gson;
    private ProfileData profileData;

    /**
     * Internal data structure for profiles.json.
     */
    public static class ProfileData {
        private String version = "1.0";
        private int maxConcurrentNodes = 1;
        private Map<String, ProfileEntry> profiles = new LinkedHashMap<>();

        public String getVersion() {
            return version;
        }

        public int getMaxConcurrentNodes() {
            return maxConcurrentNodes;
        }

        public Map<String, ProfileEntry> getProfiles() {
            return profiles;
        }
    }

    /**
     * Entry for a single profile in profiles.json.
     */
    public static class ProfileEntry {
        private String propertiesFile;
        private boolean autoStart = false;
        private boolean enabled = true;
        private String description = "";

        public ProfileEntry() {
        }

        public ProfileEntry(String propertiesFile, boolean autoStart, boolean enabled, String description) {
            this.propertiesFile = propertiesFile;
            this.autoStart = autoStart;
            this.enabled = enabled;
            this.description = description;
        }

        public String getPropertiesFile() {
            return propertiesFile;
        }

        public void setPropertiesFile(String propertiesFile) {
            this.propertiesFile = propertiesFile;
        }

        public boolean isAutoStart() {
            return autoStart;
        }

        public void setAutoStart(boolean autoStart) {
            this.autoStart = autoStart;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    public ProfileConfig() {
        this.configPath = Paths.get(DEFAULT_CONFIG_PATH);
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.profileData = null;
    }

    public ProfileConfig(Path configPath) {
        this.configPath = configPath;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.profileData = null;
    }

    /**
     * Loads profiles from the JSON file. Creates default if file doesn't exist.
     */
    public synchronized ProfileData load() {
        if (profileData != null) {
            return profileData;
        }

        try (FileReader reader = new FileReader(configPath.toFile())) {
            profileData = gson.fromJson(reader, ProfileData.class);
            if (profileData == null) {
                profileData = createDefault();
                save();
            }
            LOGGER.info("Loaded profiles config from {}: {} profiles found",
                    configPath, profileData.getProfiles().size());
        } catch (IOException e) {
            LOGGER.warn("Could not load profiles config from {}, creating default", configPath);
            profileData = createDefault();
            try {
                save();
            } catch (Exception ex) {
                LOGGER.error("Failed to create default profiles config", ex);
            }
        }

        return profileData;
    }

    /**
     * Saves the current profile data to disk.
     */
    public synchronized void save() throws IOException {
        if (profileData == null) {
            return;
        }
        try (FileWriter writer = new FileWriter(configPath.toFile())) {
            gson.toJson(profileData, writer);
            LOGGER.debug("Saved profiles config to {}", configPath);
        }
    }

    /**
     * Gets the autoStart setting for a profile.
     */
    public boolean getAutoStart(String profileName) {
        ProfileData data = load();
        ProfileEntry entry = data.getProfiles().get(profileName);
        return entry != null && entry.isAutoStart();
    }

    /**
     * Sets the autoStart setting for a profile and persists it.
     */
    public void setAutoStart(String profileName, boolean autoStart) {
        ProfileData data = load();
        ProfileEntry entry = data.getProfiles().computeIfAbsent(profileName, k -> new ProfileEntry());
        entry.setAutoStart(autoStart);
        try {
            save();
        } catch (IOException e) {
            LOGGER.error("Failed to save autoStart setting for {}", profileName, e);
        }
    }

    /**
     * Gets the enabled setting for a profile.
     */
    public boolean isEnabled(String profileName) {
        ProfileData data = load();
        ProfileEntry entry = data.getProfiles().get(profileName);
        return entry == null || entry.isEnabled(); // default: enabled
    }

    /**
     * Sets the enabled setting for a profile and persists it.
     */
    public void setEnabled(String profileName, boolean enabled) {
        ProfileData data = load();
        ProfileEntry entry = data.getProfiles().computeIfAbsent(profileName, k -> new ProfileEntry());
        entry.setEnabled(enabled);
        try {
            save();
        } catch (IOException e) {
            LOGGER.error("Failed to save enabled setting for {}", profileName, e);
        }
    }

    /**
     * Gets the ProfileEntry for a profile.
     */
    public ProfileEntry getProfileEntry(String profileName) {
        ProfileData data = load();
        return data.getProfiles().get(profileName);
    }

    /**
     * Gets all enabled profile names.
     */
    public List<String> getEnabledProfileNames() {
        ProfileData data = load();
        return data.getProfiles().entrySet().stream()
                .filter(e -> e.getValue().isEnabled())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Gets all autostart-enabled profile names.
     */
    public List<String> getAutoStartProfileNames() {
        ProfileData data = load();
        return data.getProfiles().entrySet().stream()
                .filter(e -> e.getValue().isAutoStart() && e.getValue().isEnabled())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Gets max concurrent nodes allowed.
     */
    public int getMaxConcurrentNodes() {
        ProfileData data = load();
        return data.getMaxConcurrentNodes();
    }

    /**
     * Creates a default ProfileData structure.
     */
    private ProfileData createDefault() {
        ProfileData data = new ProfileData();
        data.version = "1.0";
        data.maxConcurrentNodes = 1;
        return data;
    }
}