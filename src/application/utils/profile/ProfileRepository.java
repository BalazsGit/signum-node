package application.utils.profile;

import application.utils.config.PropertiesProfileLoader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Module-independent, GUI-independent CRUD service for a module's on-disk profile
 * files (R1).
 * <p>
 * This is the generic counterpart of the earlier logging-specific
 * {@code LoggingProfileRepository}. It owns the on-disk layout
 * {@code {confRoot}/{moduleId}/{category}/{name}{extension}} plus an
 * applied-profile metadata file, and exposes a headless API that any module can
 * drive without any Swing or logging dependency.
 * </p>
 *
 * <p>Reserved names may be read but never created, renamed, saved-over or deleted
 * through this repository.</p>
 */
public final class ProfileRepository {

    /** Default metadata file name inside a module's profile directory. */
    public static final String DEFAULT_METADATA_FILE_NAME = "profile.json";

    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileRepository.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String confRoot;
    private final String moduleId;
    private final String category;
    private final String extension;
    private final Set<String> reservedNames;
    private final String appliedKey;
    private final String metadataFileName;

    /**
     * Creates a repository bound to an explicit layout.
     *
     * @param confRoot        base configuration root (must not be null/blank)
     * @param moduleId        the module id (must not be null/blank)
     * @param category        the on-disk category (must not be null/blank)
     * @param extension       the profile file extension (e.g. {@code ".properties"})
     * @param reservedNames   read-only reserved profile names (may be empty, not null)
     * @param appliedKey      JSON key for the applied profile in the metadata file
     * @param metadataFileName the metadata file name (e.g. {@code "profile.json"})
     */
    public ProfileRepository(String confRoot, String moduleId, String category,
                             String extension, Set<String> reservedNames,
                             String appliedKey, String metadataFileName) {
        if (confRoot == null || confRoot.isBlank()) {
            throw new IllegalArgumentException("confRoot must not be null or blank");
        }
        if (moduleId == null || moduleId.isBlank()) {
            throw new IllegalArgumentException("moduleId must not be null or blank");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category must not be null or blank");
        }
        this.confRoot = confRoot;
        this.moduleId = moduleId;
        this.category = category;
        this.extension = extension;
        this.reservedNames = (reservedNames == null) ? Set.of() : Set.copyOf(reservedNames);
        this.appliedKey = (appliedKey == null || appliedKey.isBlank()) ? "appliedProfile" : appliedKey;
        this.metadataFileName = (metadataFileName == null || metadataFileName.isBlank())
                ? DEFAULT_METADATA_FILE_NAME : metadataFileName;
    }

    // ── Path resolution ───────────────────────────────────────────────

    /**
     * @return the resolved profile directory for the module
     *         ({@code {confRoot}/{moduleId}/{category}})
     */
    public Path getDirectory() {
        return PropertiesProfileLoader.resolveProfileDir(confRoot, moduleId, category);
    }

    /**
     * @param profileName profile name (without extension)
     * @return the resolved file path for a named profile
     */
    public Path getProfileFile(String profileName) {
        return getDirectory().resolve(profileName + extension);
    }

    /**
     * @return the resolved applied-profile metadata file path
     */
    public Path getMetadataPath() {
        return getDirectory().resolve(metadataFileName);
    }

    // ── Discovery ─────────────────────────────────────────────────────

    /**
     * Lists discoverable (user) profile names, excluding reserved names.
     *
     * @return a sorted, unmodifiable list of profile names (never null)
     */
    public List<String> listProfiles() {
        Path dir = getDirectory();
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, p -> {
            String n = p.getFileName().toString();
            return n.endsWith(extension) && !isReserved(n.substring(0, n.length() - extension.length()));
        })) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                names.add(n.substring(0, n.length() - extension.length()));
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to list profiles in {}: {}", dir, e.getMessage());
        }
        Collections.sort(names);
        return Collections.unmodifiableList(names);
    }

    /**
     * @param profileName the name to check
     * @return true if the name is reserved (and therefore protected)
     */
    public boolean isReserved(String profileName) {
        return profileName != null && reservedNames.contains(profileName);
    }

    /**
     * Whether the given profile is resolvable on disk for the module.
     *
     * @param profileName profile name
     * @return true when an on-disk file exists
     */
    public boolean isResolvable(String profileName) {
        return profileName != null && Files.exists(getProfileFile(profileName));
    }

    // ── CRUD ──────────────────────────────────────────────────────────

    /**
     * Creates a new profile file.
     *
     * @param profileName profile name (must not be reserved)
     * @param props       initial contents (may be null for an empty profile)
     * @return the created file path
     * @throws IOException            on write error
     * @throws IllegalArgumentException if the name is reserved/blank, or the file already exists
     */
    public Path create(String profileName, Properties props) throws IOException {
        validateName(profileName);
        Path file = getProfileFile(profileName);
        if (Files.exists(file)) {
            throw new IllegalArgumentException("Profile '" + profileName + "' already exists");
        }
        Files.createDirectories(file.getParent());
        Properties toWrite = props != null ? props : new Properties();
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            toWrite.store(writer, "Created profile: " + profileName);
        }
        LOGGER.info("Created profile '{}' ({} entries)", profileName, toWrite.size());
        return file;
    }

    /**
     * Saves (creates or overwrites) a named profile.
     *
     * @param profileName profile name (must not be reserved/blank)
     * @param props       contents to persist (may be null for an empty profile)
     * @throws IOException            on write error
     * @throws IllegalArgumentException if the name is reserved/blank
     */
    public void save(String profileName, Properties props) throws IOException {
        validateName(profileName);
        Path file = getProfileFile(profileName);
        Files.createDirectories(file.getParent());
        Properties toWrite = props != null ? props : new Properties();
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            toWrite.store(writer, "Profile: " + profileName);
        }
        LOGGER.info("Saved profile '{}' ({} entries)", profileName, toWrite.size());
    }

    /**
     * Loads a named profile's contents.
     *
     * @param profileName profile name
     * @return the loaded properties (empty when the file is missing or unreadable; never null)
     */
    public Properties load(String profileName) {
        Path file = getProfileFile(profileName);
        Properties props = new Properties();
        if (!Files.exists(file)) {
            return props;
        }
        try (InputStream is = Files.newInputStream(file);
             Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            LOGGER.warn("Failed to load profile '{}' at {}: {}", profileName, file, e.getMessage());
        }
        return props;
    }

    /**
     * Renames a profile on disk. When the profile currently applied is being renamed,
     * the applied metadata is updated to the new name.
     *
     * @param oldName existing profile name
     * @param newName new profile name
     * @return the renamed file path
     * @throws IOException            on file move or metadata error
     * @throws IllegalArgumentException if either name is reserved, the source is missing, or the target exists
     */
    public Path rename(String oldName, String newName) throws IOException {
        validateName(newName);
        if (isReserved(oldName)) {
            throw new IllegalArgumentException("The reserved profile '" + oldName + "' cannot be renamed");
        }
        Path oldFile = getProfileFile(oldName);
        Path newFile = getProfileFile(newName);
        if (!Files.exists(oldFile)) {
            throw new IllegalArgumentException("Profile '" + oldName + "' does not exist");
        }
        if (Files.exists(newFile)) {
            throw new IllegalArgumentException("A profile named '" + newName + "' already exists");
        }
        Files.createDirectories(newFile.getParent());
        Files.move(oldFile, newFile, StandardCopyOption.REPLACE_EXISTING);
        if (oldName.equals(getApplied())) {
            setApplied(newName);
        }
        LOGGER.info("Renamed profile '{}' -> '{}'", oldName, newName);
        return newFile;
    }

    /**
     * Deletes a profile.
     *
     * @param profileName profile name to delete
     * @throws IOException            on file deletion error
     * @throws IllegalArgumentException if the name is reserved or does not exist
     */
    public void delete(String profileName) throws IOException {
        if (isReserved(profileName)) {
            throw new IllegalArgumentException("The reserved profile '" + profileName + "' cannot be deleted");
        }
        Path file = getProfileFile(profileName);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Profile '" + profileName + "' does not exist");
        }
        Files.delete(file);
        if (profileName.equals(getApplied())) {
            setApplied(null);
        }
        LOGGER.info("Deleted profile '{}'", profileName);
    }

    // ── Applied-profile metadata ──────────────────────────────────────

    /**
     * Reads the applied profile name from the metadata file.
     *
     * @return the applied profile name, or null when none is set or the file is missing/corrupt
     */
    public String getApplied() {
        Path metadata = getMetadataPath();
        if (!Files.exists(metadata)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(metadata, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json != null && json.has(appliedKey) && !json.get(appliedKey).isJsonNull()) {
                return json.get(appliedKey).getAsString();
            }
        } catch (Exception e) {
            LOGGER.warn("Corrupt metadata at {}: {}", metadata, e.getMessage());
        }
        return null;
    }

    /**
     * Sets (or clears, when null/blank) the applied profile name in the metadata.
     *
     * @param profileName the applied profile name, or null to clear
     * @throws IOException on write error
     */
    public void setApplied(String profileName) throws IOException {
        Path metadata = getMetadataPath();
        JsonObject json = new JsonObject();
        if (Files.exists(metadata)) {
            try (Reader reader = Files.newBufferedReader(metadata, StandardCharsets.UTF_8)) {
                JsonObject existing = JsonParser.parseReader(reader).getAsJsonObject();
                if (existing != null) {
                    json = existing;
                }
            } catch (Exception e) {
                LOGGER.warn("Corrupt metadata at {} - recreating", metadata, e.getMessage());
                json = new JsonObject();
            }
        }
        if (profileName == null || profileName.isBlank()) {
            json.remove(appliedKey);
        } else {
            json.addProperty(appliedKey, profileName);
        }
        Files.createDirectories(metadata.getParent());
        try (Writer writer = Files.newBufferedWriter(metadata, StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        }
        LOGGER.debug("Set applied profile to '{}'", profileName);
    }

    // ── Private helpers ───────────────────────────────────────────────

    private void validateName(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("profileName must not be null or blank");
        }
        if (isReserved(profileName)) {
            throw new IllegalArgumentException("The name '" + profileName + "' is reserved");
        }
    }

    @Override
    public String toString() {
        return "ProfileRepository{module='" + moduleId + "', category='" + category + "'}";
    }
}


