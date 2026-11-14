package application.module.appearance;

import application.utils.config.PropertiesProfileLoader;
import application.utils.io.PathUtils;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Repository for appearance profiles stored as {@code .properties} files under
 * {@code conf/appearance/profiles/}.
 *
 * <p>Uses the same {@link PropertiesProfileLoader} infrastructure for discovery
 * and loading. Saving uses direct file I/O (same pattern as
 * {@code LoggingProfileRepository.saveProps}). The "Default" profile is a
 * virtual entry backed by {@link AppearanceDefaults} (L0) — it is NOT a file
 * on disk.</p>
 *
 * <p>Resolution: L0 ({@link AppearanceDefaults}) ← L2 ({@code {name}.properties}).</p>
 */
public class AppearanceProfileRepository {

    private static final String MODULE = "appearance";
    private static final String CATEGORY = "profiles";
    private static final Set<String> RESERVED_NAMES = Set.of(); // empty: template/ subdir is invisible

    private final String confRoot;

    public AppearanceProfileRepository(String confRoot) {
        this.confRoot = confRoot;
    }

    /**
     * Returns the directory where appearance profile files are stored.
     */
    public Path getProfileDir() {
        return PathUtils.resolvePath(confRoot + "/" + MODULE + "/" + CATEGORY);
    }

    /**
     * Discovers all on-disk appearance profile names (without the {@code .properties}
     * extension). The virtual "Default" entry is NOT included.
     */
    public List<String> discoverProfiles() {
        return PropertiesProfileLoader.discoverProfiles(confRoot, MODULE, CATEGORY, RESERVED_NAMES);
    }

    /**
     * Loads an appearance profile from disk, merging with L0 defaults.
     *
     * @param name the profile name (without extension)
     * @return the effective profile (L0 ← L2), or an empty L0-only profile if the file doesn't exist
     */
    public AppearanceProfile loadProfile(String name) {
        Properties props = PropertiesProfileLoader.loadProfile(confRoot, MODULE, CATEGORY, name);
        AppearanceProfile profile = AppearanceProfile.fromProperties(name, props);
        // Merge with L0: if theme is null, use the default
        if (profile.getThemeClassName() == null) {
            profile.setThemeClassName(AppearanceDefaults.DEFAULT_THEME_CLASS);
        }
        return profile;
    }

    /**
     * Saves an appearance profile to disk.
     *
     * @param name    the profile name (without extension)
     * @param profile the profile to save
     */
    public void saveProfile(String name, AppearanceProfile profile) {
        Properties props = profile.toProperties();
        Path file = getProfileDir().resolve(name + ".properties");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
                props.store(writer, "Signum Node appearance profile: " + name);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save appearance profile '" + name + "': " + e.getMessage(), e);
        }
    }

    /**
     * Renames an on-disk appearance profile file.
     *
     * @param oldName current name
     * @param newName new name
     */
    public void renameProfile(String oldName, String newName) {
        Path dir = getProfileDir();
        Path oldFile = dir.resolve(oldName + ".properties");
        Path newFile = dir.resolve(newName + ".properties");
        try {
            Files.createDirectories(dir);
            Files.move(oldFile, newFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to rename appearance profile: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes an on-disk appearance profile file.
     *
     * @param name the profile name to delete
     */
    public void deleteProfile(String name) {
        Path file = getProfileDir().resolve(name + ".properties");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete appearance profile: " + e.getMessage(), e);
        }
    }

    /**
     * Ensures the profile directory exists.
     */
    public void ensureDirExists() {
        PropertiesProfileLoader.ensureProfileDirExists(confRoot, MODULE, CATEGORY);
    }
}