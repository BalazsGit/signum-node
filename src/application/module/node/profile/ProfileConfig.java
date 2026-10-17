package application.module.node.profile;

import application.module.node.props.Props;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Iterator;
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
 *   <li>{@code appliedProfile} — the last applied node-profile marker</li>
 *   <li>{@code loggingPresets} — node profile name → per-module logging profile
 *       assignment map (the JSON key is retained for compatibility with existing files)</li>
 *   <li>{@code tabOrder} — user-defined GUI tab display order</li>
 *   <li>{@code maxConcurrentNodes} — global limit on parallel running nodes</li>
 *   <li>{@code databaseProfileLink} — the server-engine (MariaDB/PostgreSQL) linked
 *       database profile assignment ({@code "Engine:<profile>"}); the SQLite assignment
 *       is derived from the node profile's {@code DB.Url} and is not stored here</li>
 *   <li>{@code databaseAutoStart} / {@code databaseAutoStop} — whether the linked
 *       database profile is started/stopped with the node (server engines only;
 *       {@code null} = not set)</li>
 * </ul>
 * <p>
 * The legacy {@link ProfileEntry} fields are marked deprecated but retained for
 * backward-compatible JSON parsing (old profiles.json files may still contain them).
 * </p>
 * <p>
 * <b>Reload-on-write:</b> {@code load()} caches the parsed document per instance,
 * and several long-lived instances may exist at once (the GUI's app-lifetime
 * tab-order instance, per-operation {@code LoggingAssignmentStore} instances,
 * CLI). Every mutating method therefore starts from a fresh disk read
 * ({@link #reload()}) so a save can never serialize a stale snapshot and
 * silently discard writes made by another instance (e.g. a creation-time
 * logging seed written moments before a tab-order save).
 * </p>
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
        private String appliedProfile;
        /** User-defined tab order for profile tabs in the NodePanel GUI. Null means default (filesystem) order. */
        private List<String> tabOrder;
        private Map<String, ProfileEntry> profiles = new LinkedHashMap<>();

        public String getVersion() {
            return version;
        }

        public int getMaxConcurrentNodes() {
            return maxConcurrentNodes;
        }

        public String getAppliedProfile() {
            return appliedProfile;
        }

        public void setAppliedProfile(String appliedProfile) {
            this.appliedProfile = appliedProfile;
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

        /** Per-module logging profile assignments (e.g., {"node": "myprofile", "database": "quiet"}). Optional. */
        private Map<String, String> loggingPresets;

        /**
         * Linked database profile assignment for a server engine
         * ({@code "MariaDB:<profile>"} / {@code "PostgreSQL:<profile>"}). Optional.
         * The SQLite (file-based) assignment is derived from the node profile's
         * {@code DB.Url} convention path and is deliberately not stored here.
         */
        private String databaseProfileLink;

        /**
         * Whether the linked database profile is started together with the node.
         * Server engines only (SQLite is an embedded engine — N/A).
         * {@code null} = not set.
         */
        private Boolean databaseAutoStart;

        /**
         * Whether the linked database profile is stopped together with the node.
         * Server engines only (SQLite is an embedded engine — N/A).
         * {@code null} = not set.
         */
        private Boolean databaseAutoStop;

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
         * Gets per-module logging profile assignments.
         * @return unmodifiable map of module ID to logging profile name, or null if not set
         */
        public Map<String, String> getLoggingPresets() {
            return loggingPresets != null ? Collections.unmodifiableMap(loggingPresets) : null;
        }

        /**
         * Sets per-module logging profile assignments.
         * @param loggingPresets map of module ID to logging profile name, or null to clear
         */
        public void setLoggingPresets(Map<String, String> loggingPresets) {
            this.loggingPresets = loggingPresets;
        }

        /**
         * Gets the linked database profile assignment (server engines).
         * @return the link value ({@code "Engine:<profile>"}), or null if not set
         */
        public String getDatabaseProfileLink() { return databaseProfileLink; }

        /**
         * Sets the linked database profile assignment (server engines).
         * @param databaseProfileLink the link value, or null to clear
         */
        public void setDatabaseProfileLink(String databaseProfileLink) { this.databaseProfileLink = databaseProfileLink; }

        /**
         * Gets the database auto-start setting.
         * @return true/false when set, null when not set
         */
        public Boolean getDatabaseAutoStart() { return databaseAutoStart; }

        /**
         * Sets the database auto-start setting.
         * @param databaseAutoStart true/false, or null to clear
         */
        public void setDatabaseAutoStart(Boolean databaseAutoStart) { this.databaseAutoStart = databaseAutoStart; }

        /**
         * Gets the database auto-stop setting.
         * @return true/false when set, null when not set
         */
        public Boolean getDatabaseAutoStop() { return databaseAutoStop; }

        /**
         * Sets the database auto-stop setting.
         * @param databaseAutoStop true/false, or null to clear
         */
        public void setDatabaseAutoStop(Boolean databaseAutoStop) { this.databaseAutoStop = databaseAutoStop; }
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

        migrateLegacyMetadata();
        return profileData;
    }

    /**
     * Migrates the former node/profile.json metadata into this canonical document.
     * The legacy file is deleted only after profiles.json has been saved and every
     * recognized legacy field has been migrated; unknown fields are retained.
     */
    private void migrateLegacyMetadata() {
        Path legacyPath = configPath.resolveSibling("profile.json");
        if (!Files.isRegularFile(legacyPath)) {
            return;
        }

        JsonObject legacy;
        try (Reader reader = Files.newBufferedReader(legacyPath, StandardCharsets.UTF_8)) {
            legacy = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.error("Could not read legacy node profile metadata from {}", legacyPath, e);
            return;
        }

        boolean changed = false;
        JsonElement appliedProfile = legacy.get("appliedProfile");
        if (appliedProfile != null && appliedProfile.isJsonPrimitive()
                && appliedProfile.getAsJsonPrimitive().isString()) {
            if (profileData.getAppliedProfile() == null || profileData.getAppliedProfile().isBlank()) {
                profileData.setAppliedProfile(appliedProfile.getAsString());
            }
            legacy.remove("appliedProfile");
            changed = true;
        }

        JsonElement profileLinks = legacy.get("profileLinks");
        if (profileLinks != null && profileLinks.isJsonObject()) {
            JsonObject linksByProfile = profileLinks.getAsJsonObject();
            Iterator<Map.Entry<String, JsonElement>> linkIterator = linksByProfile.entrySet().iterator();
            while (linkIterator.hasNext()) {
                Map.Entry<String, JsonElement> profileLink = linkIterator.next();
                if (!profileLink.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject links = profileLink.getValue().getAsJsonObject();
                ProfileEntry entry = profileData.getProfiles().computeIfAbsent(
                        profileLink.getKey(), key -> new ProfileEntry());
                changed |= migrateLegacyLoggingLink(entry, links);
                changed |= migrateLegacyDatabaseLinks(entry, links, isSqliteProfile(profileLink.getKey()));
                if (links.size() == 0) {
                    linkIterator.remove();
                    changed = true;
                }
            }
            if (linksByProfile.size() == 0) {
                legacy.remove("profileLinks");
                changed = true;
            }
        }

        if (!changed) {
            return;
        }

        try {
            save();
            if (legacy.size() == 0) {
                Files.deleteIfExists(legacyPath);
                LOGGER.info("Migrated legacy node profile metadata to {}", configPath);
            } else {
                try (Writer writer = Files.newBufferedWriter(legacyPath, StandardCharsets.UTF_8)) {
                    new GsonBuilder().setPrettyPrinting().create().toJson(legacy, writer);
                }
                LOGGER.warn("Migrated recognized node metadata to {}; retained unknown fields in {}",
                        configPath, legacyPath);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to persist migrated node profile metadata from {}", legacyPath, e);
        }
    }

    private static boolean migrateLegacyLoggingLink(ProfileEntry entry, JsonObject links) {
        JsonElement legacyLogging = links.get("logging");
        if (legacyLogging == null || !legacyLogging.isJsonPrimitive()
                || !legacyLogging.getAsJsonPrimitive().isString()) {
            return false;
        }

        Map<String, String> assignments = new LinkedHashMap<>();
        if (entry.getLoggingPresets() != null) {
            assignments.putAll(entry.getLoggingPresets());
        }
        assignments.putIfAbsent("node", legacyLogging.getAsString());
        entry.setLoggingPresets(assignments);
        links.remove("logging");
        return true;
    }

    private boolean migrateLegacyDatabaseLinks(ProfileEntry entry, JsonObject links, boolean sqliteProfile) {
        boolean changed = false;
        JsonElement database = links.get("database");
        boolean sqliteLink = database != null && database.isJsonPrimitive()
                && database.getAsJsonPrimitive().isString()
                && database.getAsString().regionMatches(true, 0, "SQLite:", 0, "SQLite:".length());
        if (database != null && database.isJsonPrimitive() && database.getAsJsonPrimitive().isString()) {
            if (!sqliteLink && !sqliteProfile && (entry.getDatabaseProfileLink() == null
                    || entry.getDatabaseProfileLink().isBlank())) {
                entry.setDatabaseProfileLink(database.getAsString());
            }
            links.remove("database");
            changed = true;
        }

        if (sqliteLink || sqliteProfile) {
            changed |= links.remove("dbAutoStart") != null;
            changed |= links.remove("dbAutoStop") != null;
            return changed;
        }

        JsonElement autoStart = links.get("dbAutoStart");
        if (autoStart != null && autoStart.isJsonPrimitive() && autoStart.getAsJsonPrimitive().isBoolean()) {
            if (entry.getDatabaseAutoStart() == null) {
                entry.setDatabaseAutoStart(autoStart.getAsBoolean());
            }
            links.remove("dbAutoStart");
            changed = true;
        }
        JsonElement autoStop = links.get("dbAutoStop");
        if (autoStop != null && autoStop.isJsonPrimitive() && autoStop.getAsJsonPrimitive().isBoolean()) {
            if (entry.getDatabaseAutoStop() == null) {
                entry.setDatabaseAutoStop(autoStop.getAsBoolean());
            }
            links.remove("dbAutoStop");
            changed = true;
        }
        return changed;
    }

    private boolean isSqliteProfile(String profileName) {
        Path nodeDirectory = configPath.getParent();
        Path confRoot = nodeDirectory != null ? nodeDirectory.getParent() : null;
        if (confRoot == null) {
            return false;
        }
        NodeProfile profile = NodeProfileRepository.loadProfile(confRoot.toString(), profileName);
        if (profile == null) {
            return false;
        }
        String databaseUrl = profile.getProperty(Props.DB_URL.getName());
        return databaseUrl != null && databaseUrl.regionMatches(true, 0,
                "jdbc:sqlite:", 0, "jdbc:sqlite:".length());
    }

    /**
     * Discards the in-memory cache and re-reads the configuration from disk
     * (see the class javadoc "Reload-on-write"). Every mutating method starts
     * with this so concurrent writers (other {@link ProfileConfig} instances
     * with their own caches) are never clobbered by a stale snapshot.
     *
     * @return the freshly loaded data
     */
    public synchronized ProfileData reload() {
        profileData = null;
        return load();
    }

    /**
     * Saves the current profile data to disk, creating the parent directory if it does not
     * yet exist (e.g. a fresh {@code conf/node/} tree). Without this, the first write silently
     * failed when the SSOT file's directory was not present.
     */
    public synchronized void save() throws IOException {
        if (profileData == null) {
            return;
        }
        Path parent = configPath.getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        try (FileWriter writer = new FileWriter(configPath.toFile())) {
            gson.toJson(profileData, writer);
            LOGGER.debug("Saved profiles config to {}", configPath);
        }
    }

    /**
     * Renames metadata associated with a node profile while preserving all
     * canonical logging and database assignments.
     */
    public void renameProfileMetadata(String oldName, String newName) {
        ProfileData data = reload();
        ProfileEntry oldEntry = data.getProfiles().remove(oldName);
        if (oldEntry != null) {
            ProfileEntry newEntry = data.getProfiles().computeIfAbsent(newName, key -> new ProfileEntry());
            mergeProfileEntry(newEntry, oldEntry);
        }
        if (oldName.equals(data.getAppliedProfile())) {
            data.setAppliedProfile(newName);
        }
        try {
            save();
        } catch (IOException e) {
            LOGGER.error("Failed to rename profile metadata '{}' to '{}'", oldName, newName, e);
        }
    }

    /**
     * Removes metadata associated with a deleted node profile.
     */
    public void removeProfileMetadata(String profileName) {
        ProfileData data = reload();
        data.getProfiles().remove(profileName);
        if (profileName.equals(data.getAppliedProfile())) {
            data.setAppliedProfile(null);
        }
        try {
            save();
        } catch (IOException e) {
            LOGGER.error("Failed to remove profile metadata for '{}'", profileName, e);
        }
    }

    private static void mergeProfileEntry(ProfileEntry target, ProfileEntry source) {
        if (target.getLoggingProfile() == null) {
            target.setLoggingProfile(source.getLoggingProfile());
        }
        if (target.getLoggingPresets() == null || target.getLoggingPresets().isEmpty()) {
            target.setLoggingPresets(source.getLoggingPresets());
        } else if (source.getLoggingPresets() != null) {
            Map<String, String> merged = new LinkedHashMap<>(source.getLoggingPresets());
            merged.putAll(target.getLoggingPresets());
            target.setLoggingPresets(merged);
        }
        if (target.getDatabaseProfileLink() == null || target.getDatabaseProfileLink().isBlank()) {
            target.setDatabaseProfileLink(source.getDatabaseProfileLink());
        }
        if (target.getDatabaseAutoStart() == null) {
            target.setDatabaseAutoStart(source.getDatabaseAutoStart());
        }
        if (target.getDatabaseAutoStop() == null) {
            target.setDatabaseAutoStop(source.getDatabaseAutoStop());
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
        ProfileData data = reload();
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
        ProfileData data = reload();
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
        // Reload-on-write: a stale in-memory snapshot must not be persisted —
        // it would discard concurrent writes (e.g. a creation-time logging
        // seed) from the other ProfileConfig instances sharing this file.
        ProfileData data = reload();
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
        ProfileData data = reload();
        if (loggingProfile == null) {
            // null clears the association: drop the field, and remove the entry
            // when nothing else is left on it — a stale EMPTY entry must not
            // survive a clear, but an entry still carrying the canonical
            // loggingPresets map (see rename) must be kept.
            ProfileEntry entry = data.getProfiles().get(profileName);
            if (entry != null) {
                entry.setLoggingProfile(null);
                if (isEffectivelyEmpty(entry)) {
                    data.getProfiles().remove(profileName);
                }
            }
        } else {
            data.getProfiles().computeIfAbsent(profileName, k -> new ProfileEntry())
                    .setLoggingProfile(loggingProfile);
        }
        try {
            save();
        } catch (IOException e) {
            LOGGER.error("Failed to save logging profile for {}", profileName, e);
        }
    }

    /**
     * Gets the per-module logging profile assignments for a profile.
     *
     * @param profileName the profile name
     * @return unmodifiable map of module ID to logging profile name, or null if not set
     */
    public Map<String, String> getLoggingPresets(String profileName) {
        ProfileData data = load();
        ProfileEntry entry = data.getProfiles().get(profileName);
        return entry != null ? entry.getLoggingPresets() : null;
    }

    /**
     * Sets and persists the per-module logging profile assignments for a profile.
     *
     * @param profileName the profile name
     * @param loggingPresets map of module ID to logging profile name, or null to clear
     */
    public void setLoggingPresets(String profileName, Map<String, String> loggingPresets) {
        ProfileData data = reload();
        if (loggingPresets == null) {
            // null clears the association: drop the field, and remove the entry
            // when nothing else is left on it (see setLoggingProfile).
            ProfileEntry entry = data.getProfiles().get(profileName);
            if (entry != null) {
                entry.setLoggingPresets(null);
                if (isEffectivelyEmpty(entry)) {
                    data.getProfiles().remove(profileName);
                }
            }
        } else {
            data.getProfiles().computeIfAbsent(profileName, k -> new ProfileEntry())
                    .setLoggingPresets(loggingPresets);
        }
        try {
            save();
        } catch (IOException e) {
            LOGGER.error("Failed to save logging assignments for {}", profileName, e);
        }
    }

    /**
     * Gets the linked database profile assignment (server engines) for a profile.
     *
     * @param profileName the node profile name
     * @return the link value ({@code "Engine:<profile>"}), or null if not set
     */
    public String getDatabaseProfileLink(String profileName) {
        ProfileData data = load();
        ProfileEntry entry = data.getProfiles().get(profileName);
        return entry != null ? entry.getDatabaseProfileLink() : null;
    }

    /**
     * Sets and persists the linked database profile assignment (server engines)
     * for a profile. The SQLite (file-based) assignment is never stored — it is
     * derived from the node profile's {@code DB.Url}.
     *
     * @param profileName the node profile name
     * @param link        the link value ({@code "Engine:<profile>"}), or null to clear
     */
    public void setDatabaseProfileLink(String profileName, String link) {
        String value = (link == null || link.trim().isEmpty()) ? null : link.trim();
        ProfileData data = reload();
        ProfileEntry entry = data.getProfiles().get(profileName);
        if (entry != null) {
            entry.setDatabaseProfileLink(value);
            if (isEffectivelyEmpty(entry)) {
                data.getProfiles().remove(profileName);
            }
        } else if (value != null) {
            data.getProfiles().computeIfAbsent(profileName, k -> new ProfileEntry())
                    .setDatabaseProfileLink(value);
        } else {
            return; // nothing to set and nothing to clear
        }
        try {
            save();
        } catch (IOException e) {
            LOGGER.error("Failed to save database profile link for {}", profileName, e);
        }
    }

    /**
     * Gets the database auto-start setting for a profile.
     *
     * @param profileName the node profile name
     * @return true/false when set, null when not set
     */
    public Boolean getDatabaseAutoStart(String profileName) {
        ProfileData data = load();
        ProfileEntry entry = data.getProfiles().get(profileName);
        return entry != null ? entry.getDatabaseAutoStart() : null;
    }

    /**
     * Sets and persists the database auto-start setting for a profile.
     *
     * @param profileName the node profile name
     * @param value       true/false, or null to clear
     */
    public void setDatabaseAutoStart(String profileName, Boolean value) {
        setDatabaseBooleanFlag(profileName, value, true);
    }

    /**
     * Gets the database auto-stop setting for a profile.
     *
     * @param profileName the node profile name
     * @return true/false when set, null when not set
     */
    public Boolean getDatabaseAutoStop(String profileName) {
        ProfileData data = load();
        ProfileEntry entry = data.getProfiles().get(profileName);
        return entry != null ? entry.getDatabaseAutoStop() : null;
    }

    /**
     * Sets and persists the database auto-stop setting for a profile.
     *
     * @param profileName the node profile name
     * @param value       true/false, or null to clear
     */
    public void setDatabaseAutoStop(String profileName, Boolean value) {
        setDatabaseBooleanFlag(profileName, value, false);
    }

    /** Shared read-modify-write for the nullable auto-start/auto-stop flags. */
    private void setDatabaseBooleanFlag(String profileName, Boolean value, boolean startFlag) {
        if (value == null
                && (startFlag ? getDatabaseAutoStart(profileName) : getDatabaseAutoStop(profileName)) == null) {
            return; // nothing to set and nothing to clear
        }
        ProfileData data = reload();
        ProfileEntry entry = data.getProfiles().computeIfAbsent(profileName, k -> new ProfileEntry());
        if (startFlag) {
            entry.setDatabaseAutoStart(value);
        } else {
            entry.setDatabaseAutoStop(value);
        }
        if (isEffectivelyEmpty(entry)) {
            data.getProfiles().remove(profileName);
        }
        try {
            save();
        } catch (IOException e) {
            LOGGER.error("Failed to save database auto-{} setting for {}", startFlag ? "start" : "stop", profileName, e);
        }
    }

    /**
     * Whether the legacy entry carries no association at all (logging, database
     * link, database auto-start/auto-stop) — such an empty entry may be removed
     * from profiles.json on a clear.
     */
    private static boolean isEffectivelyEmpty(ProfileEntry entry) {
        return entry.getLoggingProfile() == null
                && (entry.getLoggingPresets() == null || entry.getLoggingPresets().isEmpty())
                && (entry.getDatabaseProfileLink() == null || entry.getDatabaseProfileLink().isEmpty())
                && entry.getDatabaseAutoStart() == null
                && entry.getDatabaseAutoStop() == null;
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
