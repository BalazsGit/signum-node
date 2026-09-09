package application.module.logging;

import application.module.node.profile.ProfileConfig;
import application.utils.config.ConfigPaths;
import application.utils.config.ModuleIds;
import application.utils.config.PropertiesProfileLoader;
import application.utils.io.PathUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Single source of truth for <b>node-profile → logging-profile</b> assignments.
 * <p>
 * Historically the association "which logging profile does a given node profile
 * use" was scattered across three mechanisms that could drift apart:
 * </p>
 * <ol>
 *   <li><b>Canonical (this store's writer):</b> {@code ProfileConfig.loggingPresets}
 *       in {@code conf/node/profiles.json} — a per-module map, e.g.
 *       {@code {"node":"standard","database":"verbose"}}.</li>
 *   <li><b>Legacy property:</b> {@code logging.preset} inside the node profile's own
 *       {@code .properties} file.</li>
 *   <li><b>Legacy link:</b> {@code profileLinks.<profile>.logging} in
 *       {@code conf/node/profile.json}.</li>
 * </ol>
 *
 * <p>
 * This store is the <b>only writer</b>: all writes go to mechanism 1
 * ({@link ProfileConfig#setLoggingPresets}). Reads prefer the canonical map and only
 * fall back to the legacy mechanisms (2 and 3) for backward compatibility with
 * existing {@code conf/} trees that have not yet been migrated.
 * </p>
 *
 * <p>
 * Headless-safe (no Swing). The module id dimension of the assignment map is
 * intentionally generic so future modules (pool, fluxcapacitor, …) can reuse the
 * same store without further changes.
 * </p>
 *
 * @see ProfileConfig
 * @see LoggingProfileRepository
 */
public final class LoggingAssignmentStore {

    /** JSON key for the profile-links object in {@code profile.json}. */
    static final String PROFILE_LINKS_KEY = "profileLinks";

    /** JSON key for the linked logging profile within a profile-link entry. */
    static final String LINKED_LOGGING_KEY = "logging";

    /** Legacy property name holding a single logging preset (mechanism 2). */
    static final String LEGACY_PRESET_PROPERTY = "logging.preset";

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingAssignmentStore.class);

    private final String confRoot;
    private final ProfileConfig profileConfig;

    /**
     * Creates a store bound to the standard runtime conf root
     * ({@link ConfigPaths#RUNTIME_CONF_ROOT}).
     */
    public LoggingAssignmentStore() {
        this(PropertiesProfileLoader.DEFAULT_CONF_ROOT);
    }

    /**
     * Creates a store bound to a specific configuration root.
     *
     * @param confRoot Base configuration root (e.g. {@code ./conf}); must not be null
     */
    public LoggingAssignmentStore(String confRoot) {
        if (confRoot == null || confRoot.isBlank()) {
            throw new IllegalArgumentException("confRoot must not be null or blank");
        }
        this.confRoot = confRoot;
        // The canonical SSOT file is conf/node/profiles.json (matches ProfileConfig's default path).
        Path ssotFile = PathUtils.resolvePath(confRoot).resolve(ModuleIds.NODE).resolve("profiles.json");
        this.profileConfig = new ProfileConfig(ssotFile);
    }

    // ── Read (canonical first, legacy fallback) ────────────────────────

    /**
     * Returns the logging-profile assignment for a node profile.
     * <p>
     * Resolution order:
     * <ol>
     *   <li>Canonical {@code ProfileConfig.loggingPresets} map (preferred).</li>
     *   <li>Legacy {@code profileLinks.<profile>.logging} in {@code conf/node/profile.json}
     *       (mapped to the {@code node} module id).</li>
     *   <li>Legacy {@code logging.preset} property in the node profile's
     *       {@code .properties} file (mapped to the {@code node} module id).</li>
     * </ol>
     * </p>
     *
     * @param nodeProfileName Node profile name (e.g. {@code "mainnet"})
     * @return Unmodifiable map of module id → logging profile name; empty if unset (never null)
     */
    public Map<String, String> getAssignment(String nodeProfileName) {
        Map<String, String> canonical = profileConfig.getLoggingPresets(nodeProfileName);
        if (canonical != null && !canonical.isEmpty()) {
            return canonical;
        }

        // Legacy fallback 1: profileLinks.<profile>.logging (conf/node/profile.json)
        String linked = readLegacyLinkedLogging(nodeProfileName);
        if (linked != null && !linked.isBlank()) {
            Map<String, String> migrated = new LinkedHashMap<>();
            migrated.put(ModuleIds.NODE, linked);
            return migrated;
        }

        // Legacy fallback 2: logging.preset property in the node profile's .properties
        String preset = readLegacyPresetProperty(nodeProfileName);
        if (preset != null && !preset.isBlank()) {
            Map<String, String> migrated = new LinkedHashMap<>();
            migrated.put(ModuleIds.NODE, preset);
            return migrated;
        }

        return Map.of();
    }

    /**
     * Resolves the <b>effective</b> logging profile name that a node profile uses for a
     * given module, guaranteeing a valid fallback:
     * <ul>
     *   <li>if the assignment names a resolvable profile (on-disk file or provider preset)
     *       → that name is returned;</li>
     *   <li>if the assignment is <b>invalid</b> (e.g. the linked profile was deleted) or
     *       <b>absent</b> → the reserved {@code logging-default} (hardcoded default) is
     *       returned.</li>
     * </ul>
     * <p>
     * This is the single read-side resolution used by the runtime applier
     * ({@code NodeLoggingApplier}), so an assignment can never point at a missing profile.
     * </p>
     *
     * @param nodeProfileName Node profile name (e.g. {@code "mainnet"})
     * @param moduleId        Module identifier (e.g. {@code "node"})
     * @return The effective profile name (never null)
     */
    public String resolveEffectiveForModule(String nodeProfileName, String moduleId) {
        String requested = getAssignment(nodeProfileName).get(moduleId);
        return new LoggingProfileRepository(confRoot).resolveEffective(moduleId, requested);
    }

    // ── Write (canonical only) ─────────────────────────────────────────

    /**
     * Sets the full assignment for a node profile. This is the single writer and
     * persists to the canonical {@code ProfileConfig} store.
     *
     * @param nodeProfileName Node profile name
     * @param assignments     Map of module id → logging profile name (null values ignored);
     *                        pass null or an empty map to clear
     */
    public void setAssignment(String nodeProfileName, Map<String, String> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            profileConfig.setLoggingPresets(nodeProfileName, null);
            return;
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : assignments.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isBlank()) {
                cleaned.put(entry.getKey(), entry.getValue());
            }
        }
        profileConfig.setLoggingPresets(nodeProfileName, cleaned.isEmpty() ? null : cleaned);
        LOGGER.info("Set logging assignment for node profile '{}' -> {}", nodeProfileName, cleaned);
    }

    /**
     * Sets (or updates) the logging profile for a single module within a node profile.
     *
     * @param nodeProfileName    Node profile name
     * @param moduleId           Module id (e.g. {@code "node"}, {@code "database"})
     * @param loggingProfileName Logging profile name, or null to clear that module
     */
    public void setAssignmentForModule(String nodeProfileName, String moduleId, String loggingProfileName) {
        Map<String, String> current = new LinkedHashMap<>(getAssignment(nodeProfileName));
        if (loggingProfileName == null || loggingProfileName.isBlank()) {
            current.remove(moduleId);
        } else {
            current.put(moduleId, loggingProfileName);
        }
        setAssignment(nodeProfileName, current);
    }

    /**
     * Clears any logging assignment for a node profile.
     *
     * @param nodeProfileName Node profile name
     */
    public void clearAssignment(String nodeProfileName) {
        setAssignment(nodeProfileName, null);
    }

    /**
     * Checks whether the canonical store has an explicit assignment for the profile.
     *
     * @param nodeProfileName Node profile name
     * @return true if the canonical map is present and non-empty
     */
    public boolean isManaged(String nodeProfileName) {
        Map<String, String> canonical = profileConfig.getLoggingPresets(nodeProfileName);
        return canonical != null && !canonical.isEmpty();
    }

    // ── Discovery / reverse lookup ─────────────────────────────────────

    /**
     * Lists the discoverable node profile names (excluding reserved defaults).
     *
     * @return Sorted list of node profile names (never null)
     */
    public List<String> listNodeProfiles() {
        java.util.Set<String> reserved = java.util.Set.of("node-default", "node");
        return PropertiesProfileLoader.discoverProfiles(
                confRoot, ModuleIds.NODE, ModuleIds.CATEGORY_PROFILES, reserved);
    }

    /**
     * Reverse lookup: returns the node profiles whose assignment references the given
     * logging profile. Used for delete-protection (see plan §2.5.3).
     *
     * @param loggingProfileName Logging profile name to search for
     * @return Sorted list of node profile names referencing it (never null)
     */
    public List<String> findReferencingProfiles(String loggingProfileName) {
        if (loggingProfileName == null || loggingProfileName.isBlank()) {
            return List.of();
        }
        java.util.List<String> matches = new java.util.ArrayList<>();
        for (String nodeProfile : listNodeProfiles()) {
            if (getAssignment(nodeProfile).values().contains(loggingProfileName)) {
                matches.add(nodeProfile);
            }
        }
        return matches;
    }

    // ── Legacy readers ─────────────────────────────────────────────────

    private String readLegacyLinkedLogging(String nodeProfileName) {
        Path profileJson = PathUtils.resolvePath(confRoot).resolve(ModuleIds.NODE).resolve("profile.json");
        if (!Files.exists(profileJson)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(profileJson, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root == null || !root.has(PROFILE_LINKS_KEY)) {
                return null;
            }
            JsonObject links = root.getAsJsonObject(PROFILE_LINKS_KEY);
            if (links == null || !links.has(nodeProfileName)) {
                return null;
            }
            JsonObject entry = links.getAsJsonObject(nodeProfileName);
            if (entry != null && entry.has(LINKED_LOGGING_KEY)) {
                return entry.get(LINKED_LOGGING_KEY).getAsString();
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to read legacy profileLinks for '{}': {}", nodeProfileName, e.getMessage());
        }
        return null;
    }

    private String readLegacyPresetProperty(String nodeProfileName) {
        Path propsFile = PropertiesProfileLoader.resolveProfileFile(
                confRoot, ModuleIds.NODE, ModuleIds.CATEGORY_PROFILES, nodeProfileName);
        if (!Files.exists(propsFile)) {
            return null;
        }
        try (java.io.InputStream is = Files.newInputStream(propsFile);
                java.io.InputStreamReader reader = new java.io.InputStreamReader(is, StandardCharsets.UTF_8)) {
            Properties props = new Properties();
            props.load(reader);
            return props.getProperty(LEGACY_PRESET_PROPERTY);
        } catch (Exception e) {
            LOGGER.debug("Failed to read legacy preset property for '{}': {}", nodeProfileName, e.getMessage());
        }
        return null;
    }
}