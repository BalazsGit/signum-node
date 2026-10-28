package application.module.logging;

import application.utils.config.ModuleIds;
import application.utils.config.PropertiesProfileLoader;
import application.utils.config.ReservedNames;
import application.utils.profile.ProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
 *   <li>{@code conf/{module}/logging/default.properties} — the user-editable L1
 *       layer (auto-created at first start, never overwritten by the sync)</li>
 *   <li>{@code conf/{module}/logging/logging-default.properties} — the reference-only
 *       L0 documentation template (SHA-256 synced from classpath resources)</li>
 *   <li>{@code conf/{module}/logging/profile.json} — applied-profile metadata
 *       ({@code {"appliedProfile": "..."}}, same schema as
 *       {@code ConfigurationUtils}, so existing files stay readable)</li>
 * </ul>
 *
 * <h3>Reserved name protection</h3>
 * The reserved names (the canonical {@code default} — the user-editable L1
 * layer — and the legacy {@code logging-default} reference template) may be
 * loaded and read, but can never be created, renamed, saved-over or deleted
 * through this repository (SSOT: {@link ReservedNames}).
 *
 * <p>
 * This class intentionally has no Swing dependency and is safe to construct and
 * use in headless mode.
 * </p>
 *
 * <p>
 * The generic on-disk CRUD, path resolution and applied-profile metadata are delegated to the
 * module-independent R1 {@link ProfileRepository} (P6.1); this class keeps the logging-specific
 * applied-baseline cache and effective-profile resolution.
 * </p>
 *
 * @see PropertiesProfileLoader
 * @see EffectiveProfileResolver
 * @see LoggingAssignmentStore
 * @see ProfileRepository
 */
public final class LoggingProfileRepository {

    /**
     * The canonical reserved L1 profile name: the user-editable
     * {@code default.properties} file (auto-created at first start, protected
     * from user CRUD — see {@link #isReserved}). The legacy
     * {@code logging-default} reference template stays reserved as well
     * (SSOT: {@link ReservedNames}).
     */
    public static final String RESERVED_PROFILE_NAME = ReservedNames.DEFAULT_PROFILE_NAME;

    /** JSON key holding the applied profile name in {@code profile.json}. */
    private static final String APPLIED_PROFILE_KEY = "appliedProfile";

    /** Metadata file name inside the logging directory. */
    private static final String METADATA_FILE_NAME = "profile.json";

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingProfileRepository.class);

    private static final ConcurrentMap<AppliedProfileKey, Properties> APPLIED_BASELINES =
            new ConcurrentHashMap<>();

    private record AppliedProfileKey(String confRoot, String moduleId, String profileName) {
    }

    /**
     * Per-module R1 {@link ProfileRepository} instances (one per module id), so the generic
     * on-disk CRUD, path resolution and applied-profile metadata live in the module-independent
     * R1 layer (P6.1) while this class keeps the logging-specific applied-baseline cache and
     * effective-profile resolution.
     */
    private final ConcurrentMap<String, ProfileRepository> repositories = new ConcurrentHashMap<>();

    /** Base configuration root (e.g. {@code ./conf}). Never null. */
    private final String confRoot;

    /**
     * Creates a repository bound to the standard runtime conf root
     * ({@link PropertiesProfileLoader#DEFAULT_CONF_ROOT}).
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

    // ── R1 delegation ──────────────────────────────────────────────────

    /**
     * The module-independent R1 repository for a module (created on demand, cached per module
     * id). Bound to {@code {confRoot}/{moduleId}/logging/} with the logging reserved names and
     * the {@code profile.json} applied-metadata layout — identical to the on-disk contract this
     * class has always exposed.
     */
    private ProfileRepository repo(String moduleId) {
        return repositories.computeIfAbsent(moduleId, id -> new ProfileRepository(
                confRoot, id, ModuleIds.CATEGORY_LOGGING, ".properties",
                ReservedNames.LOGGING_RESERVED_NAMES, APPLIED_PROFILE_KEY, METADATA_FILE_NAME));
    }

    // ── Path resolution ────────────────────────────────────────────────

    /**
     * Returns the resolved logging directory for a module.
     *
     * @param moduleId Module identifier (e.g. {@code "node"}, {@code "database"})
     * @return Path to {@code conf/{module}/logging/}
     */
    public Path getLoggingDir(String moduleId) {
        return repo(moduleId).getDirectory();
    }

    /**
     * Returns the resolved file path for a named logging profile.
     *
     * @param moduleId    Module identifier
     * @param profileName Profile name (without extension)
     * @return Path to {@code conf/{module}/logging/{profileName}.properties}
     */
    public Path getProfileFile(String moduleId, String profileName) {
        return repo(moduleId).getProfileFile(profileName);
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
        return repo(moduleId).listProfiles();
    }

    /**
     * Checks whether a profile name is reserved (and therefore protected).
     *
     * @param profileName Profile name to check
     * @return true if the name is the reserved default
     */
    public boolean isReserved(String profileName) {
        return ReservedNames.isLoggingReservedName(profileName);
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
        return repo(moduleId).isResolvable(profileName);
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
        return repo(moduleId).load(profileName);
    }

    /**
     * Returns the applied baseline used for row-state display. The module-level
     * marker is a default for newly created node profiles; it does not represent
     * the current logging state of existing node profiles. Values are held in
     * memory for the current process and reloaded from the selected profile after
     * a process restart.
     */
    public Properties loadAppliedBaseline(String moduleId, String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return new Properties();
        }
        Properties baseline = APPLIED_BASELINES.get(appliedProfileKey(moduleId, profileName));
        return baseline == null ? loadProps(moduleId, profileName) : copyOf(baseline);
    }

    /** Captures the values that were applied in this process for UI row-state comparisons. */
    public void rememberAppliedBaseline(String moduleId, String profileName, Properties values) {
        if (profileName == null || profileName.isBlank()) {
            return;
        }
        APPLIED_BASELINES.put(appliedProfileKey(moduleId, profileName), copyOf(values));
    }

    /**
     * Removes legacy JSON snapshots from this module's dedicated {@code applied/}
     * directory. The current implementation never writes them.
     */
    public void cleanupLegacyAppliedSnapshots(String moduleId) {
        Path dir = getLoggingDir(moduleId).resolve("applied");
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to remove legacy applied logging snapshots from {}: {}", dir, e.getMessage());
            return;
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            if (files.findAny().isEmpty()) {
                Files.deleteIfExists(dir);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to remove empty legacy applied logging directory {}: {}", dir, e.getMessage());
        }
    }

    /**
     * Returns the currently applied profile name for a module.
     *
     * @param moduleId Module identifier
     * @return Applied profile name, or null if no metadata is present
     */
    public String getApplied(String moduleId) {
        return repo(moduleId).getApplied();
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
        return repo(moduleId).create(profileName, seed != null ? copyOf(seed) : null);
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
        Path newFile = repo(moduleId).rename(oldName, newName);
        Properties appliedBaseline = APPLIED_BASELINES.remove(appliedProfileKey(moduleId, oldName));
        if (appliedBaseline != null) {
            APPLIED_BASELINES.put(appliedProfileKey(moduleId, newName), appliedBaseline);
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
        repo(moduleId).delete(profileName);
        APPLIED_BASELINES.remove(appliedProfileKey(moduleId, profileName));
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
        repo(moduleId).save(profileName, props);
        LOGGER.info("Saved logging profile '{}' for module '{}'", profileName, moduleId);
    }

    /**
     * Sets the applied profile name in the module's metadata.
     *
     * @param moduleId    Module identifier
     * @param profileName Profile name to mark as applied, or null to clear
     * @throws IOException on write error
     */
    public void setApplied(String moduleId, String profileName) throws IOException {
        String previous = getApplied(moduleId);
        repo(moduleId).setApplied(profileName);
        // Drop the in-memory baseline of the previously-applied profile (logging-only state).
        if (previous != null
                && (profileName == null || profileName.isBlank() || !previous.equals(profileName))) {
            APPLIED_BASELINES.remove(appliedProfileKey(moduleId, previous));
        }
        LOGGER.debug("Set applied profile for module '{}' to '{}'", moduleId, profileName);
    }

    private static Properties copyOf(Properties source) {
        Properties copy = new Properties();
        if (source != null) {
            copy.putAll(source);
        }
        return copy;
    }

    private AppliedProfileKey appliedProfileKey(String moduleId, String profileName) {
        return new AppliedProfileKey(confRoot, moduleId, profileName);
    }

    // ── Private helpers ────────────────────────────────────────────────
}