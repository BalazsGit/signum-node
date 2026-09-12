package application.module.node.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import application.utils.config.ModuleIds;
import application.utils.config.PropertiesProfileLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Static factory / repository for node profiles.
 * <p>
 * v4 (P1.1): the profile-discovery and file-management statics were extracted
 * from {@link NodeProfile} so that the profile class itself remains a plain,
 * stateless POJO (identity + property bag + builder). All path-schema
 * knowledge lives here.
 *
 * @see NodeProfile
 * @see PropertiesProfileLoader
 */
public final class NodeProfileRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeProfileRepository.class);

    private NodeProfileRepository() {
        // static utility
    }

    // ── Discovery ──────────────────────────────────────────────────────

    /**
     * Discovers and loads all node profiles from {@code conf/node/profiles/*.properties}.
     * <p>
     * Reserved profile names (default templates) are excluded from discovery.
     *
     * @return array of loaded NodeProfiles, empty if none found
     */
    public static NodeProfile[] loadAll() {
        try {
            return PropertiesProfileLoader.loadAll(
                    NodeProfile.CONF_ROOT, NodeProfile.MODULE_ID, NodeProfile.CATEGORY,
                    NodeProfile.RESERVED_PROFILE_NAMES,
                    name -> new NodeProfile(name), NodeProfile.class);
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
                    NodeProfile.CONF_ROOT, NodeProfile.MODULE_ID, NodeProfile.CATEGORY, profileName);

            if (props.isEmpty()) {
                LOGGER.debug("Profile file not found or empty: {}", profileName);
                return null;
            }

            Path propsPath = Paths.get(NodeProfile.CONF_ROOT, NodeProfile.MODULE_ID,
                    NodeProfile.CATEGORY, profileName + ".properties");

            return new NodeProfile.Builder(profileName)
                    .properties(props)
                    .propertiesPath(propsPath)
                    .build();
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
        return profileName != null && NodeProfile.RESERVED_PROFILE_NAMES.contains(profileName);
    }

    /**
     * Discovers all available (non-reserved) profile names.
     *
     * @return sorted list of discoverable profile names
     */
    public static List<String> discoverProfileNames() {
        return PropertiesProfileLoader.discoverProfiles(
                NodeProfile.CONF_ROOT, NodeProfile.MODULE_ID, NodeProfile.CATEGORY,
                NodeProfile.RESERVED_PROFILE_NAMES);
    }

    /**
     * Reorders the given profiles into the canonical startup (arbitration) order.
     * <p>
     * Names present in {@code preferredOrder} (the user-defined tab/start order)
     * come first — restricted to profiles that actually exist, in that order — and
     * any remaining profiles are appended in their original discovery order. Unknown
     * names in {@code preferredOrder} are ignored; a null/empty {@code preferredOrder}
     * yields the pure discovery order.
     * </p>
     * <p>
     * This is the <b>single source of truth</b> for the autostart order: the "first
     * profile wins the resource" arbitration depends on a deterministic order that
     * matches the GUI tab display (both read {@link ProfileConfig#getTabOrder()}).
     * </p>
     *
     * @param profiles       the discovered profiles (any order), may be null/empty
     * @param preferredOrder the desired order of profile names, may be null
     * @return the same profiles in startup order, never null
     */
    public static NodeProfile[] inStartupOrder(NodeProfile[] profiles, List<String> preferredOrder) {
        Map<String, NodeProfile> byName = new LinkedHashMap<>();
        if (profiles != null) {
            for (NodeProfile profile : profiles) {
                if (profile != null && profile.getName() != null) {
                    byName.putIfAbsent(profile.getName(), profile);
                }
            }
        }
        List<String> desired = new ArrayList<>(byName.size());
        if (preferredOrder != null) {
            for (String name : preferredOrder) {
                if (name != null && byName.containsKey(name) && !desired.contains(name)) {
                    desired.add(name);
                }
            }
        }
        for (String name : byName.keySet()) {
            if (!desired.contains(name)) {
                desired.add(name);
            }
        }
        NodeProfile[] ordered = new NodeProfile[desired.size()];
        for (int i = 0; i < desired.size(); i++) {
            ordered[i] = byName.get(desired.get(i));
        }
        return ordered;
    }

    /**
     * Reorders the given profiles using the user-defined tab/start order persisted in
     * {@link ProfileConfig} (falls back to discovery order when none is set).
     *
     * @param profiles the discovered profiles (any order)
     * @return the profiles in startup order, never null
     */
    public static NodeProfile[] inStartupOrder(NodeProfile[] profiles) {
        List<String> preferred;
        try {
            preferred = new ProfileConfig().getTabOrder();
        } catch (Exception e) {
            preferred = null;
        }
        return inStartupOrder(profiles, preferred);
    }

    // ── CRUD (create / rename / delete / list) ─────────────────────────
    //
    // The public no-conf-root methods below use the default runtime conf root
    // (SSOT: {@link NodeProfile#CONF_ROOT}). The package-private {@code (confRoot, ...)}
    // overloads are the core implementations and accept an explicit conf root so that
    // CLI/GUI callers and unit tests can operate on a sandbox directory.

    /**
     * Creates a new node profile (default conf root). See {@link #createProfile(String, String, Properties)}.
     */
    public static NodeProfile createProfile(String profileName, Properties properties) throws IOException {
        return createProfile(NodeProfile.CONF_ROOT, profileName, properties);
    }

    /**
     * Creates a new node profile by writing {@code {confRoot}/node/profiles/{name}.properties}
     * and returning the loaded entity.
     * <p>
     * Persistence mirrors {@code LoggingProfileRepository.saveProps()} — direct
     * {@code java.nio.file} I/O + {@link Properties#store}, with reserved-name protection.
     * This class stays GUI/Swing-free and holds no {@code NodeModule} dependency: the
     * "is the node running?" guard is the caller's responsibility.
     * </p>
     *
     * @param confRoot    the runtime conf root (e.g. {@code conf} or a test sandbox dir)
     * @param profileName profile name (validated)
     * @param properties  the properties to persist (null → empty profile)
     * @return the newly loaded profile
     * @throws IllegalArgumentException if the name is blank, invalid, reserved, or already exists
     * @throws IOException              on write error
     */
    static NodeProfile createProfile(String confRoot, String profileName, Properties properties) throws IOException {
        validateName(profileName);
        Path dir = profileDir(confRoot);
        Path file = dir.resolve(profileName + ".properties");
        if (Files.exists(file)) {
            throw new IllegalArgumentException("Profile '" + profileName + "' already exists");
        }
        Files.createDirectories(dir);
        Properties toWrite = properties != null ? properties : new Properties();
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            toWrite.store(writer, "Node profile: " + profileName);
        }
        LOGGER.info("Created node profile '{}' ({} entries)", profileName, toWrite.size());
        Properties copy = new Properties();
        copy.putAll(toWrite);
        return new NodeProfile.Builder(profileName)
                .properties(copy)
                .propertiesPath(file)
                .build();
    }

    /**
     * Creates a new node profile from the {@code node-default.properties} template (default conf root).
     */
    public static NodeProfile createDefaultProfile(String profileName, Properties overrides) throws IOException {
        return createDefaultProfile(NodeProfile.CONF_ROOT, profileName, overrides);
    }

    /**
     * Creates a new node profile from the {@code {confRoot}/node/profiles/node-default.properties}
     * template, applying the given overrides on top.
     *
     * @throws IllegalArgumentException if the name is blank, invalid, reserved, or already exists
     * @throws IOException              on read/write error
     */
    static NodeProfile createDefaultProfile(String confRoot, String profileName, Properties overrides) throws IOException {
        Path dir = profileDir(confRoot);
        Path template = dir.resolve(NodeProfile.DEFAULT_PROFILE_FILENAME);
        Properties base = new Properties();
        if (Files.exists(template)) {
            try (Reader reader = Files.newBufferedReader(template, StandardCharsets.UTF_8)) {
                base.load(reader);
            }
        } else {
            LOGGER.warn("Default profile template not found at {}; creating empty base", template);
        }
        if (overrides != null) {
            for (String key : overrides.stringPropertyNames()) {
                base.setProperty(key, overrides.getProperty(key));
            }
        }
        return createProfile(confRoot, profileName, base);
    }

    /**
     * Renames a profile (default conf root). See {@link #renameProfile(String, String, String)}.
     */
    public static void renameProfile(String oldName, String newName) throws IOException {
        renameProfile(NodeProfile.CONF_ROOT, oldName, newName);
    }

    /**
     * Renames a profile: moves the {@code .properties} file and updates the cross-profile
     * metadata in {@link ProfileConfig} (tab order + logging association).
     *
     * @throws IllegalArgumentException if the old name is unknown, or the new name is invalid/taken/reserved
     * @throws IOException              on file error
     */
    static void renameProfile(String confRoot, String oldName, String newName) throws IOException {
        validateName(newName);
        Path dir = profileDir(confRoot);
        Path from = dir.resolve(oldName + ".properties");
        if (!Files.exists(from)) {
            throw new IllegalArgumentException("Profile '" + oldName + "' not found");
        }
        Path to = dir.resolve(newName + ".properties");
        if (Files.exists(to)) {
            throw new IllegalArgumentException("Profile '" + newName + "' already exists");
        }
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        updateProfileConfig(confRoot, oldName, newName);
        LOGGER.info("Renamed node profile '{}' -> '{}'", oldName, newName);
    }

    /**
     * Deletes a profile (default conf root). See {@link #deleteProfile(String, String)}.
     */
    public static void deleteProfile(String profileName) throws IOException {
        deleteProfile(NodeProfile.CONF_ROOT, profileName);
    }

    /**
     * Deletes a profile: removes the {@code .properties} file and clears its
     * cross-profile metadata in {@link ProfileConfig}.
     *
     * @throws IllegalArgumentException if the profile is unknown or reserved
     * @throws IOException              on file error
     */
    static void deleteProfile(String confRoot, String profileName) throws IOException {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("Profile name must not be null or blank");
        }
        if (isReservedProfileName(profileName)) {
            throw new IllegalArgumentException("The reserved profile '" + profileName + "' cannot be deleted");
        }
        Path dir = profileDir(confRoot);
        Path file = dir.resolve(profileName + ".properties");
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Profile '" + profileName + "' not found");
        }
        Files.delete(file);
        clearProfileConfig(confRoot, profileName);
        LOGGER.info("Deleted node profile '{}'", profileName);
    }

    /**
     * Lists all available (non-reserved) profile names (default conf root).
     */
    public static List<String> listProfiles() {
        return discoverProfileNames();
    }

    /**
     * Discovers all available (non-reserved) profile names under an explicit conf root.
     *
     * @param confRoot the runtime conf root (e.g. {@code conf} or a test sandbox dir)
     * @return sorted list of discoverable profile names
     */
    public static List<String> discoverProfileNames(String confRoot) {
        return PropertiesProfileLoader.discoverProfiles(
                confRoot, NodeProfile.MODULE_ID, NodeProfile.CATEGORY, NodeProfile.RESERVED_PROFILE_NAMES);
    }

    /**
     * Loads a profile by name from an explicit conf root.
     *
     * @param confRoot    the runtime conf root (e.g. {@code conf} or a test sandbox dir)
     * @param profileName the profile name (without extension)
     * @return the loaded profile, or null if not found
     */
    public static NodeProfile loadProfile(String confRoot, String profileName) {
        return loadFrom(confRoot, profileName);
    }

    // ── CRUD helpers ────────────────────────────────────────────────────

    private static NodeProfile loadFrom(String confRoot, String name) {
        try {
            Path file = PropertiesProfileLoader.resolveProfileFile(
                    confRoot, NodeProfile.MODULE_ID, NodeProfile.CATEGORY, name);
            if (!Files.exists(file)) {
                return null; // file genuinely absent
            }
            // Note: a profile file may be valid yet hold zero user properties (comments only),
            // so existence — not emptiness — is the "found" signal.
            Properties props = PropertiesProfileLoader.loadProfile(
                    confRoot, NodeProfile.MODULE_ID, NodeProfile.CATEGORY, name);
            return new NodeProfile.Builder(name)
                    .properties(props)
                    .propertiesPath(file)
                    .build();
        } catch (Exception e) {
            LOGGER.error("Error loading profile {} from {}", name, confRoot, e);
            return null;
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Profile name must not be null or blank");
        }
        if (!name.matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "Invalid profile name '" + name + "': allowed characters are a-z, A-Z, 0-9, '_', '-'");
        }
        if (isReservedProfileName(name)) {
            throw new IllegalArgumentException("The profile name '" + name + "' is reserved");
        }
    }

    private static Path profileDir(String confRoot) {
        return PropertiesProfileLoader.ensureProfileDirExists(
                confRoot, NodeProfile.MODULE_ID, NodeProfile.CATEGORY);
    }

    private static Path profileConfigPath(String confRoot) {
        return Paths.get(confRoot, NodeProfile.MODULE_ID, "profiles.json");
    }

    private static void updateProfileConfig(String confRoot, String oldName, String newName) {
        try {
            ProfileConfig config = new ProfileConfig(profileConfigPath(confRoot));
            List<String> order = config.getTabOrder();
            if (order != null && order.contains(oldName)) {
                List<String> newOrder = new ArrayList<>(order);
                newOrder.set(newOrder.indexOf(oldName), newName);
                config.setTabOrder(newOrder);
            }
            String logging = config.getLoggingProfile(oldName);
            if (logging != null) {
                config.setLoggingProfile(newName, logging);
            }
            config.setLoggingProfile(oldName, null);
        } catch (Exception e) {
            LOGGER.warn("Failed to update profiles.json after rename '{}' -> '{}'", oldName, newName, e);
        }
    }

    private static void clearProfileConfig(String confRoot, String name) {
        try {
            ProfileConfig config = new ProfileConfig(profileConfigPath(confRoot));
            List<String> order = config.getTabOrder();
            if (order != null && order.contains(name)) {
                List<String> newOrder = new ArrayList<>(order);
                newOrder.remove(name);
                config.setTabOrder(newOrder);
            }
            config.setLoggingProfile(name, null);
        } catch (Exception e) {
            LOGGER.warn("Failed to update profiles.json after delete '{}'", name, e);
        }
    }

    // ── Default File Management ────────────────────────────────────────

    /**
     * Synchronizes the default profile file from classpath resources to runtime conf/.
     * Uses SHA-256 hash comparison to detect updates.
     */
    public static void syncDefaultProfileFile() {
        InputStream is = NodeProfile.class.getResourceAsStream(
                "/conf/node/profiles/node-default.properties");
        if (is != null) {
            PropertiesProfileLoader.syncDefaultFile(
                    NodeProfile.CONF_ROOT, NodeProfile.MODULE_ID, NodeProfile.CATEGORY,
                    NodeProfile.DEFAULT_PROFILE_FILENAME, is);
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
                    NodeProfile.CONF_ROOT, NodeProfile.MODULE_ID,
                    PropertiesProfileLoader.DEFAULT_CATEGORY_LOGGING,
                    NodeProfile.DEFAULT_LOGGING_FILENAME, is);
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
                NodeProfile.CONF_ROOT, NodeProfile.MODULE_ID, NodeProfile.RESERVED_PROFILE_NAMES,
                ModuleIds.NODE, ModuleIds.CATEGORY_LOGGING);
    }

    /**
     * Full initialization: sync defaults + create placeholders if needed.
     * <p>
     * Call this once during application startup, before any profile loading occurs.
     */
    public static void initialize() {
        PropertiesProfileLoader.initializeModule(
                NodeProfile.CONF_ROOT, NodeProfile.MODULE_ID, NodeProfile.RESERVED_PROFILE_NAMES,
                ModuleIds.NODE, ModuleIds.CATEGORY_LOGGING);
    }
}