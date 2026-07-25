package application.module.node.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Loads and manages profile configuration from {@code profiles.json}.
 * <p>
 * <b>Single Source of Truth Migration:</b> Per-profile settings such as
 * {@code autoStart}, {@code enabled}, {@code description} and {@code propertiesFile}
 * have been migrated to the NodeProfile's {@code .properties} file. This class
 * now only manages cross-profile configuration that does not belong in individual
 * profile files:
 * <ul>
 *   <li>{@code loggingAssociations} — node profile name → logging preset mapping</li>
 *   <li>{@code tabOrder} — user-defined GUI tab display order</li>
 *   <li>{@code maxConcurrentNodes} — global limit on parallel running nodes</li>
 * </ul>
 * <p>
 * The legacy {@link ProfileEntry} fields are marked deprecated but retained for
 * backward-compatible JSON parsing (old profiles.json files may still contain them).
 */
public class ProfileConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileConfig.class);
    private static final String DEFAULT_CONFIG_PATH = "conf/node/profiles.json";
    private final Path configPath;
    private final Gson gson;
    private ProfileData profileData;

    /**
     * Internal data structure for profiles.json.
     * <p>
     * Uses LinkedHashMap to preserve insertion order for profile entries.
     * The tabOrder field stores the user-defined tab display order in the GUI.
     */
    public static class ProfileData {
        private String version = "1.0";
        private int maxConcurrentNodes = 1;
        /** User-defined tab order for profile tabs in the NodePanel GUI. Null means default (filesystem) order. */
        private List<String> tabOrder;
        private Map<String, ProfileEntry> profiles = new LinkedHashMap<>();

        public String getVersion() {
            return version;
        }

        public int getMaxConcurrentNodes() {
            return maxConcurrentNodes;
        }

        public List<String> getTabOrder() {
            return tabOrder;
        }

        public void setTabOrder(List<String> tabOrder) {
            this.tabOrder = tabOrder;
        }

        public Map<String, ProfileEntry> getProfiles() {
            return profiles;
        }
    }

    /**
     * Entry for a single profile in profiles.json.
     * <p>
     * <b>Migration Note:</b> The {@code autoStart}, {@code enabled}, {@code description}
     * and {@code propertiesFile} fields are deprecated — this data is now stored in the
     * NodeProfile's {@code .properties} file (Single Source of Truth). These fields are
     * retained only for backward-compatible JSON parsing when loading older profiles.json
     * files that still contain them. Gson will silently ignore unknown keys, so removing
     * these fields here would not break deserialization.
     */
    public static class ProfileEntry {
        // ── Deprecated fields (retained for backward-compatible JSON parsing) ──

        /** @deprecated Autostart is now in NodeProfile.properties (node.autostart) */
        @Deprecated
        private String propertiesFile;

        /** @deprecated Autostart is now in NodeProfile.properties (node.autostart) */
        @Deprecated(forRemoval = true)
        private boolean autoStart = false;

        /** @description Enabled state is managed by profile presence in .properties discovery */
        @Deprecated(forRemoval = true)
        private boolean enabled = true;

        /** @deprecated Description is now in NodeProfile.properties (node.description) */
        @Deprecated(forRemoval = true)
        private String description = "";

        // ── Active fields ──

        /** Reference to a named logging profile (e.g., "debug", "quiet", "verbose"). Optional. */
        private String loggingProfile;

        /** Per-module logging level presets (e.g., {"node": "debug", "database": "quiet"}). Optional. */
        private Map<String, String> loggingPresets;

        public ProfileEntry() {
        }

        /**
         * @deprecated Use the default constructor. AutoStart/Enabled/Description
         * are now managed in NodeProfile.properties.
         */
        @Deprecated(forRemoval = true)
        public ProfileEntry(String propertiesFile, boolean autoStart, boolean enabled, String description) {
            this.propertiesFile = propertiesFile;
            this.autoStart = autoStart;
            this.enabled = enabled;
            this.description = description;
        }

        /** @deprecated Autostart is now in NodeProfile.properties */
        @Deprecated(forRemoval = true)
        public String getPropertiesFile() { return propertiesFile; }

        /** @deprecated Autostart is now in NodeProfile.properties */
        @Deprecated(forRemoval = true)
        public void setPropertiesFile(String propertiesFile) { this.propertiesFile = propertiesFile; }

        /** @deprecated Autostart is now in NodeProfile.properties (node.autostart) */
        @Deprecated(forRemoval = true)
        public boolean isAutoStart() { return autoStart; }

        /** @deprecated Autostart is now in NodeProfile.properties (node.autostart) */
        @Deprecated(forRemoval = true)
        public void setAutoStart(boolean autoStart) { this.autoStart = autoStart; }

        /** @deprecated Enabled state is managed by profile presence in .properties discovery */
        @Deprecated(forRemoval = true)
        public boolean isEnabled() { return enabled; }

        /** @deprecated Enabled state is managed by profile presence in .properties discovery */
        @Deprecated(forRemoval = true)
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        /** @deprecated Description is now in NodeProfile.properties */
        @Deprecated(forRemoval = true)
        public String getDescription() { return description; }

        /** @deprecated Description is now in NodeProfile.properties */
        @Deprecated(forRemoval = true)
        public void setDescription(String description) { this.description = description; }

        // ── Active API (logging association) ──

        /**
         * Gets the logging profile reference for this node profile.
         * @return the logging profile name (e.g., "debug", "quiet"), or null if not set
         */
        public String getLoggingProfile() { return loggingProfile; }

        /**
         * Sets the logging profile reference for this node profile.
         * @param loggingProfile the logging profile name, or null to clear
         */
        public void setLoggingProfile(String loggingProfile) { this.loggingProfile = loggingProfile; }

        /**
         * Gets per-module logging level presets.
         * @return unmodifiable map of module ID to log level strings, or null if not set
         */
        public Map<String, String> getLoggingPresets() {
            return loggingPresets != null ? Collections.unmodifiableMap(loggingPresets) : null;
        }

        /**
         * Sets per-module logging level presets.
         * @param loggingPresets map of module ID to log level strings, or null to clear
         */
        public void setLoggingPresets(Map<String, String> loggingPresets) {
            this.loggingPresets = loggingPresets;
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

    // ── Deprecated API (per-profile settings now in .properties) ──

    /**
     * @deprecated Autostart is now read from NodeProfile.properties (node.autostart).
     * Use {@link NodeProfile#isAutostart()} directly.
     */
    @Deprecated(forRemoval = true)
    public boolean getAutoStart(String profileName) {
        ProfileData data = load();
        ProfileEntry entry = data.getProfiles().get(profileName);
        return entry != null && entry.isAutoStart();
    }

    /**
     * @deprecated Autostart is now set in NodeProfile.properties (node.autostart).
     */
    @Deprecated(forRemoval = true)
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
     * @deprecated Enabled state is managed by profile presence in .properties discovery.
     */
    @Deprecated(forRemoval = true)
    public boolean isEnabled(String profileName) {
        ProfileData data = load();
        ProfileEntry entry = data.getProfiles().get(profileName);
        return entry == null || entry.isEnabled();
    }

    /**
     * @deprecated Enabled state is managed by profile presence in .properties discovery.
     */
    @Deprecated(forRemoval = true)
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

    /** @deprecated Use {@link NodeLifecycleManager#getProfile(String)} instead. */
    @Deprecated(forRemoval = true)
    public ProfileEntry getProfileEntry(String profileName) {
        ProfileData data = load();
        return data.getProfiles().get(profileName);
    }

    /**
     * @deprecated Enabled profiles list is no longer used. Use
     * {@link NodeLifecycleManager#getAllProfiles()} for the registered profiles.
     */
    @Deprecated(forRemoval = true)
    public List<String> getEnabledProfileNames() {
        ProfileData data = load();
        return data.getProfiles().keySet().stream().collect(Collectors.toList());
    }

    /**
     * @deprecated Autostart is now read from .properties. This method is a no-op
     * and retained for backward compatibility only.
     */
    @Deprecated(forRemoval = true)
    public List<String> getAutoStartProfileNames() {
        ProfileData data = load();
        return data.getProfiles().entrySet().stream()
                .filter(e -> e.getValue().isAutoStart())
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
     * Gets the user-defined tab order for profile tabs.
     * Returns null if no custom order has been set (use default filesystem order).
     */
    public List<String> getTabOrder() {
        ProfileData data = load();
        return data.getTabOrder();
    }

    /**
     * Sets and persists the user-defined tab order for profile tabs.
     * Pass null to reset to default (filesystem) order.
     *
     * @param tabOrder Ordered list of profile names representing the desired tab display order
     */
    public void setTabOrder(List<String> tabOrder) {
        ProfileData data = load();
        data.setTabOrder(tabOrder);
        try {
            save();
        } catch (IOException e) {
            LOGGER.error("Failed to save tab order", e);
        }
    }

    /**
     * Gets the logging profile reference for a profile.
     *
     * @param profileName the profile name
     * @return the logging profile name (e.g., "debug", "quiet"), or null if not set
     */
    public String getLoggingProfile(String profileName) {
        ProfileData data = load();
        ProfileEntry entry = data.getProfiles().get(profileName);
        return entry != null ? entry.getLoggingProfile() : null;
    }

    /**
     * Sets and persists the logging profile reference for a profile.
     *
     * @param profileName the profile name
     * @param loggingProfile the logging profile name, or null to clear
     */
    public void setLoggingProfile(String profileName, String loggingProfile) {
        ProfileData data = load();
        ProfileEntry entry = data.getProfiles().computeIfAbsent(profileName, k -> new ProfileEntry());
        entry.setLoggingProfile(loggingProfile);
        try {
            save();
        } catch (IOException e) {
            LOGGER.error("Failed to save logging profile for {}", profileName, e);
        }
    }

    /**
     * Gets the per-module logging presets for a profile.
     *
     * @param profileName the profile name
     * @return unmodifiable map of module ID to log level strings, or null if not set
     */
    public Map<String, String> getLoggingPresets(String profileName) {
        ProfileData data = load();
        ProfileEntry entry = data.getProfiles().get(profileName);
        return entry != null ? entry.getLoggingPresets() : null;
    }

    /**
     * Sets and persists the per-module logging presets for a profile.
     *
     * @param profileName the profile name
     * @param loggingPresets map of module ID to log level strings, or null to clear
     */
    public void setLoggingPresets(String profileName, Map<String, String> loggingPresets) {
        ProfileData data = load();
        ProfileEntry entry = data.getProfiles().computeIfAbsent(profileName, k -> new ProfileEntry());
        entry.setLoggingPresets(loggingPresets);
        try {
            save();
        } catch (IOException e) {
            LOGGER.error("Failed to save logging presets for {}", profileName, e);
        }
    }

    /**
     * Creates a default ProfileData structure.
     */
    private ProfileData createDefault() {
        ProfileData data = new ProfileData();
        data.version = "1.0";
        data.maxConcurrentNodes = 1;
        data.tabOrder = null; // No custom order by default
        return data;
    }
}
