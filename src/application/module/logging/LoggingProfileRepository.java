package application.module.logging;

import application.utils.config.ConfigPaths;
import application.utils.config.ModuleIds;
import application.utils.config.PropertiesProfileLoader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * GUI-independent CRUD service for a module's logging profile files.
 * <p>
 * This is the <b>management layer</b> counterpart to the runtime logging core
 * ({@code application.utils.logging}). It owns the on-disk layout
 * {@code conf/{module}/logging/} and the applied-profile metadata
 * ({@code conf/{module}/logging/profile.json}), exposing a headless-friendly API
 * that both the future Logging module UI and CLI tooling can drive.
 * </p>
 *
 * <h3>On-disk contract (kept identical to the existing layout)</h3>
 * <ul>
 *   <li>{@code conf/{module}/logging/{profileName}.properties} — user profiles</li>
 *   <li>{@code conf/{module}/logging/logging-default.properties} — reserved default</li>
 *   <li>{@code conf/{module}/logging/profile.json} — applied-profile metadata
 *       ({@code {"appliedProfile": "..."}}, same schema as
 *       {@code ConfigurationUtils}, so existing files stay readable)</li>
 *   <li>{@code conf/{module}/logging/applied/{profileName}.json} — applied-state
 *       snapshot: the property values the runtime is currently using for that
 *       profile (the "applied" row-state baseline; see
 *       {@link #saveAppliedSnapshot(String, String, Properties)})</li>
 * </ul>
 *
 * <h3>Reserved name protection</h3>
 * The reserved default profile {@value #RESERVED_PROFILE_NAME} may be loaded and
 * read, but it can never be created, renamed, saved-over or deleted through this
 * repository (it is managed by the SHA-256 classpath sync in
 * {@link PropertiesProfileLoader}).
 *
 * <p>
 * This class intentionally has no Swing dependency and is safe to construct and
 * use in headless mode.
 * </p>
 *
 * @see PropertiesProfileLoader
 * @see EffectiveProfileResolver
 * @see LoggingAssignmentStore
 */
public final class LoggingProfileRepository {

    /** Reserved logging profile name that is protected from user CRUD. */
    public static final String RESERVED_PROFILE_NAME = "logging-default";

    /** JSON key holding the applied profile name in {@code profile.json}. */
    private static final String APPLIED_PROFILE_KEY = "appliedProfile";

    /** Metadata file name inside the logging directory. */
    private static final String METADATA_FILE_NAME = "profile.json";

    /** Applied-state snapshot subdirectory name inside the logging directory. */
    private static final String APPLIED_SNAPSHOT_DIR = "applied";

    /** JSON keys of the applied-state snapshot file. */
    private static final String SNAPSHOT_PROFILE_KEY = "profile";
    private static final String SNAPSHOT_APPLIED_AT_KEY = "appliedAt";
    private static final String SNAPSHOT_VALUES_KEY = "values";

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingProfileRepository.class);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Base configuration root (e.g. {@code ./conf}). Never null. */
    private final String confRoot;

    /**
     * Creates a repository bound to the standard runtime conf root
     * ({@link ConfigPaths#RUNTIME_CONF_ROOT}).
     */
    public LoggingProfileRepository() {
        this(PropertiesProfileLoader.DEFAULT_CONF_ROOT);
    }

    /**
     * Creates a repository bound to a specific configuration root.
     *
     * @param confRoot Base configuration root (e.g. {@code ./conf}); must not be null
     */
    public LoggingProfileRepository(String confRoot) {
        if (confRoot == null || confRoot.isBlank()) {
            throw new IllegalArgumentException("confRoot must not be null or blank");
        }
        this.confRoot = confRoot;
    }

    // ── Path resolution ────────────────────────────────────────────────

    /**
     * Returns the resolved logging directory for a module.
     *
     * @param moduleId Module identifier (e.g. {@code "node"}, {@code "database"})
     * @return Path to {@code conf/{module}/logging/}
     */
    public Path getLoggingDir(String moduleId) {
        return PropertiesProfileLoader.resolveProfileDir(confRoot, moduleId, ModuleIds.CATEGORY_LOGGING);
    }

    /**
     * Returns the resolved file path for a named logging profile.
     *
     * @param moduleId    Module identifier
     * @param profileName Profile name (without extension)
     * @return Path to {@code conf/{module}/logging/{profileName}.properties}
     */
    public Path getProfileFile(String moduleId, String profileName) {
        return PropertiesProfileLoader.resolveProfileFile(confRoot, moduleId, ModuleIds.CATEGORY_LOGGING, profileName);
    }

    /**
     * Returns the resolved path of the applied-profile metadata file.
     *
     * @param moduleId Module identifier
     * @return Path to {@code conf/{module}/logging/profile.json}
     */
    public Path getMetadataPath(String moduleId) {
        return getLoggingDir(moduleId).resolve(METADATA_FILE_NAME);
    }

    /**
     * Returns the resolved path of the applied-state snapshot for a profile.
     *
     * @param moduleId    Module identifier
     * @param profileName Profile name (without extension)
     * @return Path to {@code conf/{module}/logging/applied/{profileName}.json}
     */
    public Path getAppliedSnapshotPath(String moduleId, String profileName) {
        return getLoggingDir(moduleId).resolve(APPLIED_SNAPSHOT_DIR).resolve(profileName + ".json");
    }

    // ── Discovery ──────────────────────────────────────────────────────

    /**
     * Lists discoverable (user) logging profile names for a module.
     * The reserved default is always excluded.
     *
     * @param moduleId Module identifier
     * @return Sorted, unmodifiable list of profile names (never null)
     */
    public List<String> listProfiles(String moduleId) {
        return PropertiesProfileLoader.discoverProfiles(
                confRoot, moduleId, ModuleIds.CATEGORY_LOGGING, Collections.singleton(RESERVED_PROFILE_NAME));
    }

    /**
     * Checks whether a profile name is reserved (and therefore protected).
     *
     * @param profileName Profile name to check
     * @return true if the name is the reserved default
     */
    public boolean isReserved(String profileName) {
        return RESERVED_PROFILE_NAME.equals(profileName);
    }

    /**
     * Returns {@code true} when the given profile name is <b>resolvable</b> for the module:
     * an on-disk profile file exists ({@code conf/{module}/logging/{name}.properties}).
     * <p>
     * Used to validate an assignment before it is applied: a profile that has been deleted
     * (or never existed) is <i>not</i> resolvable and must fall back to the default
     * (see {@link #resolveEffective}).
     * </p>
     *
     * @param moduleId    Module identifier
     * @param profileName Profile name to check (without extension)
     * @return true if the profile name resolves to an on-disk file
     */
    public boolean hasProfile(String moduleId, String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return false;
        }
        return Files.exists(getProfileFile(moduleId, profileName));
    }

    /**
     * Resolves the effective logging profile to apply for a module, guaranteeing a valid
     * fallback: returns {@code requested} when it is resolvable ({@link #hasProfile}),
     * otherwise the reserved {@value #RESERVED_PROFILE_NAME} (the hardcoded default).
     * <p>
     * This implements the "invalid or deleted assignment falls back to the default"
     * guarantee required for per-node logging.
     * </p>
     *
     * @param moduleId    Module identifier
     * @param requested   The assigned profile name (may be null/blank)
     * @return The requested name if valid, otherwise {@value #RESERVED_PROFILE_NAME}
     */
    public String resolveEffective(String moduleId, String requested) {
        if (requested != null && !requested.isBlank() && hasProfile(moduleId, requested)) {
            return requested;
        }
        return RESERVED_PROFILE_NAME;
    }

    // ── Read ───────────────────────────────────────────────────────────

    /**
     * Loads a named profile into {@link Properties}.
     *
     * @param moduleId    Module identifier
     * @param profileName Profile name (without extension)
     * @return Loaded properties, or an empty instance if the file does not exist
     */
    public Properties loadProps(String moduleId, String profileName) {
        return PropertiesProfileLoader.loadProfile(confRoot, moduleId, ModuleIds.CATEGORY_LOGGING, profileName);
    }

    /**
     * Returns the currently applied profile name for a module.
     *
     * @param moduleId Module identifier
     * @return Applied profile name, or null if no metadata is present
     */
    public String getApplied(String moduleId) {
        Path metadata = getMetadataPath(moduleId);
        if (!Files.exists(metadata)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(metadata, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json != null && json.has(APPLIED_PROFILE_KEY)) {
                return json.get(APPLIED_PROFILE_KEY).getAsString();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to read applied-profile metadata at {}: {}", metadata, e.getMessage());
        }
        return null;
    }

    // ── Write ──────────────────────────────────────────────────────────

    /**
     * Creates a new logging profile from explicit initial content — the seed
     * properties are written as-is, which lets a caller create a profile from
     * an arbitrary state (e.g. the editor's currently set values).
     *
     * @param moduleId    Module identifier
     * @param profileName New profile name (must not be reserved or already present)
     * @param seed        Initial content (may be null — an empty profile is created)
     * @return Path of the created file
     * @throws IOException          if the file cannot be written
     * @throws IllegalArgumentException if the name is reserved, blank, or already exists
     */
    public Path create(String moduleId, String profileName, Properties seed) throws IOException {
        validateName(profileName);
        Path file = getProfileFile(moduleId, profileName);
        if (Files.exists(file)) {
            throw new IllegalArgumentException("A profile named '" + profileName + "' already exists for module '"
                    + moduleId + "'");
        }

        Properties props = seed != null ? (Properties) seed.clone() : new Properties();
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(writer, "Created logging profile: " + profileName);
        }
        LOGGER.info("Created logging profile '{}' for module '{}' ({} entries)", profileName, moduleId, props.size());
        return file;
    }

    /**
     * Renames a logging profile on disk.
     * <p>
     * If the profile currently applied is being renamed, the applied-profile
     * metadata is updated to the new name to stay consistent.
     * </p>
     *
     * @param moduleId Module identifier
     * @param oldName  Existing profile name
     * @param newName  New profile name
     * @return Path of the renamed file
     * @throws IOException            on file move or metadata error
     * @throws IllegalArgumentException if either name is reserved, or the destination exists
     */
    public Path rename(String moduleId, String oldName, String newName) throws IOException {
        validateName(newName);
        if (isReserved(oldName)) {
            throw new IllegalArgumentException("The reserved profile '" + oldName + "' cannot be renamed");
        }

        Path oldFile = getProfileFile(moduleId, oldName);
        Path newFile = getProfileFile(moduleId, newName);
        if (!Files.exists(oldFile)) {
            throw new IllegalArgumentException("Profile '" + oldName + "' does not exist for module '" + moduleId + "'");
        }
        if (Files.exists(newFile)) {
            throw new IllegalArgumentException("A profile named '" + newName + "' already exists for module '" + moduleId + "'");
        }

        Files.createDirectories(newFile.getParent());
        Files.move(oldFile, newFile, StandardCopyOption.REPLACE_EXISTING);
        renameAppliedSnapshotQuietly(moduleId, oldName, newName);

        if (oldName.equals(getApplied(moduleId))) {
            setApplied(moduleId, newName);
        }
        LOGGER.info("Renamed logging profile '{}' -> '{}' for module '{}'", oldName, newName, moduleId);
        return newFile;
    }

    /**
     * Deletes a logging profile.
     *
     * @param moduleId    Module identifier
     * @param profileName Profile name to delete
     * @throws IOException            on file deletion error
     * @throws IllegalArgumentException if the name is reserved or does not exist
     */
    public void delete(String moduleId, String profileName) throws IOException {
        if (isReserved(profileName)) {
            throw new IllegalArgumentException("The reserved profile '" + profileName + "' cannot be deleted");
        }
        Path file = getProfileFile(moduleId, profileName);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Profile '" + profileName + "' does not exist for module '" + moduleId + "'");
        }
        Files.delete(file);
        deleteAppliedSnapshotQuietly(moduleId, profileName);

        if (profileName.equals(getApplied(moduleId))) {
            setApplied(moduleId, null);
        }
        LOGGER.info("Deleted logging profile '{}' for module '{}'", profileName, moduleId);
    }

    /**
     * Saves {@link Properties} to a named profile file (creates or overwrites).
     *
     * @param moduleId    Module identifier
     * @param profileName Profile name (must not be reserved)
     * @param props       Properties to persist
     * @throws IOException            on write error
     * @throws IllegalArgumentException if the name is reserved or blank
     */
    public void saveProps(String moduleId, String profileName, Properties props) throws IOException {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("profileName must not be null or blank");
        }
        if (isReserved(profileName)) {
            throw new IllegalArgumentException("The reserved profile '" + profileName + "' cannot be overwritten");
        }
        Path file = getProfileFile(moduleId, profileName);
        Files.createDirectories(file.getParent());
        Properties toWrite = props != null ? props : new Properties();
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            toWrite.store(writer, "Logging profile: " + profileName);
        }
        LOGGER.info("Saved logging profile '{}' for module '{}' ({} entries)", profileName, moduleId, toWrite.size());
    }

    /**
     * Sets the applied profile name in the module's metadata.
     *
     * @param moduleId    Module identifier
     * @param profileName Profile name to mark as applied, or null to clear
     * @throws IOException on write error
     */
    public void setApplied(String moduleId, String profileName) throws IOException {
        Path metadata = getMetadataPath(moduleId);
        JsonObject json = new JsonObject();
        if (Files.exists(metadata)) {
            try (Reader reader = Files.newBufferedReader(metadata, StandardCharsets.UTF_8)) {
                JsonObject existing = JsonParser.parseReader(reader).getAsJsonObject();
                if (existing != null) {
                    json = existing;
                }
            } catch (Exception e) {
                LOGGER.warn("Corrupt metadata at {} — recreating", metadata, e);
                json = new JsonObject();
            }
        }

        if (profileName == null || profileName.isBlank()) {
            String previous = json.get(APPLIED_PROFILE_KEY) == null
                    ? null
                    : json.get(APPLIED_PROFILE_KEY).getAsString();
            json.remove(APPLIED_PROFILE_KEY);
            // Clearing the applied profile makes the built-in defaults the
            // applied baseline again — drop the stale snapshot.
            if (previous != null) {
                deleteAppliedSnapshotQuietly(moduleId, previous);
            }
        } else {
            json.addProperty(APPLIED_PROFILE_KEY, profileName);
        }

        Files.createDirectories(metadata.getParent());
        try (Writer writer = Files.newBufferedWriter(metadata, StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        }
        LOGGER.debug("Set applied profile for module '{}' to '{}'", moduleId, profileName);
    }

    // ── Applied-state snapshot ─────────────────────────────────────────

    /**
     * Persists the applied-state snapshot for a profile: the property values
     * the runtime is currently using (the "applied" baseline for the GUI's
     * row-state derivation).
     * <p>
     * The snapshot stores the actual VALUES (not just the profile name) so
     * the row states stay accurate after the profile file is edited and
     * re-saved: a saved-but-not-reapplied change reads as "saved", not
     * "applied". A missing key means "fall back to the built-in default"
     * (the same convention as profile files).
     * </p>
     *
     * @param moduleId    Module identifier
     * @param profileName Profile name (must not be reserved)
     * @param values      The values being applied (null = empty snapshot)
     * @throws IOException            on write error
     * @throws IllegalArgumentException if the name is reserved or blank
     */
    public void saveAppliedSnapshot(String moduleId, String profileName, Properties values) throws IOException {
        validateName(profileName);
        JsonObject json = new JsonObject();
        json.addProperty(SNAPSHOT_PROFILE_KEY, profileName);
        json.addProperty(SNAPSHOT_APPLIED_AT_KEY, java.time.Instant.now().toString());
        JsonObject valuesJson = new JsonObject();
        if (values != null) {
            for (String key : values.stringPropertyNames()) {
                valuesJson.addProperty(key, values.getProperty(key));
            }
        }
        json.add(SNAPSHOT_VALUES_KEY, valuesJson);
        Path file = getAppliedSnapshotPath(moduleId, profileName);
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        }
        LOGGER.info("Saved applied-state snapshot for profile '{}' of module '{}' ({} values)",
                profileName, moduleId, valuesJson.size());
    }

    /**
     * Loads the applied-state snapshot values for a profile.
     *
     * @param moduleId    Module identifier
     * @param profileName Profile name (null/blank = no snapshot)
     * @return The stored values (an empty {@link Properties} when no snapshot
     *         exists or it is unreadable — callers fall back to the built-in
     *         defaults for missing keys)
     */
    public Properties loadAppliedSnapshot(String moduleId, String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return new Properties();
        }
        Path file = getAppliedSnapshotPath(moduleId, profileName);
        if (!Files.exists(file)) {
            return new Properties();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            Properties props = new Properties();
            JsonObject values = json == null ? null : json.getAsJsonObject(SNAPSHOT_VALUES_KEY);
            if (values != null) {
                for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
                    props.setProperty(entry.getKey(), entry.getValue().getAsString());
                }
            }
            return props;
        } catch (Exception e) {
            LOGGER.warn("Corrupt applied-state snapshot at {} — ignoring: {}", file, e.getMessage());
            return new Properties();
        }
    }

    private void deleteAppliedSnapshotQuietly(String moduleId, String profileName) {
        try {
            Path file = getAppliedSnapshotPath(moduleId, profileName);
            if (Files.exists(file)) {
                Files.delete(file);
            }
            // Keep the conf tree clean: drop the applied/ directory when empty.
            Path dir = file.getParent();
            if (dir != null && Files.exists(dir) && isDirectoryEmpty(dir)) {
                Files.delete(dir);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to delete the applied-state snapshot for profile '{}' of module '{}': {}",
                    profileName, moduleId, e.getMessage());
        }
    }

    private static boolean isDirectoryEmpty(Path dir) {
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            return stream.findFirst().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void renameAppliedSnapshotQuietly(String moduleId, String oldName, String newName) {
        try {
            Path oldFile = getAppliedSnapshotPath(moduleId, oldName);
            if (Files.exists(oldFile)) {
                Files.createDirectories(oldFile.getParent());
                Files.move(oldFile, getAppliedSnapshotPath(moduleId, newName), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to rename the applied-state snapshot '{}' -> '{}' of module '{}': {}",
                    oldName, newName, moduleId, e.getMessage());
        }
    }

    // ── Private helpers ────────────────────────────────────────────────

    private static void validateName(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("profileName must not be null or blank");
        }
        if (RESERVED_PROFILE_NAME.equals(profileName)) {
            throw new IllegalArgumentException("The name '" + profileName + "' is reserved");
        }
    }
}