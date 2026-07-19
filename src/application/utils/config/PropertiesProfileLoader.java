package application.utils.config;

import application.utils.io.PathUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Centralized utility for loading <b>Java {@code .properties}</b>-based module profiles.
 * <p>
 * <h3>Scope</h3>
 * This loader is specifically designed for {@link Properties} files following the
 * standard Java key=value format. Other profile formats (e.g., JSON-based database
 * profiles, XML configurations) use their own dedicated loaders.
 * <p>
 * For non-properties profiles, see:
 * <ul>
 *   <li>{@code MariaDbProfile} - JSON-based database profiles</li>
 * </ul>
 * <p>
 * <h3>Path Schema</h3>
 * <pre>
 *   ../conf/{module}/{category}/*.properties
 * </pre>
 * <p>
 * <h3>Design Patterns</h3>
 * <ul>
 *   <li><b>Factory:</b> {@link PropertiesProfileFactory} enables creation of strongly-typed
 *       profile entities without the loader needing to know concrete classes.</li>
 *   <li><b>Strategy:</b> Category names (e.g., "profiles", "logging") are swappable strategies
 *       that control which subdirectory is scanned.</li>
 *   <li><b>Template Method:</b> {@link #loadAll(String, String, String, Set, PropertiesProfileFactory, Class)}
 *       provides a common loading skeleton (discover → create → load properties) that
 *       varies only in the profile entity type.</li>
 * </ul>
 *
 * <h3>Default File Synchronization</h3>
 * Default files (e.g., {@code profile-default.properties}) are automatically synchronized
 * from classpath resources to the runtime {@code conf/} directory using SHA-256 hash comparison.
 * This ensures users always have the latest default values when the application is updated,
 * while preserving user-customized profiles.
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * // Load all node profiles (properties-based)
 * Set<String> reserved = Set.of("profile-default", "logging-default");
 * NodeProfile[] profiles = PropertiesProfileLoader.loadAll(
 *         "conf", "node", "profiles", reserved,
 *         NodeProfile::new, NodeProfile.class);
 *
 * // Sync default file from resources
 * try (InputStream is = getClass().getResourceAsStream("/conf/node/profiles/profile-default.properties")) {
 *     PropertiesProfileLoader.syncDefaultFile("conf", "node", "profiles",
 *             "profile-default.properties", is);
 * }
 * }</pre>
 *
 * @see PropertiesProfileEntity
 * @see PropertiesProfileFactory
 * @see PathUtils
 */
public final class PropertiesProfileLoader {

    // ── Default Constants (parameterizable) ─────────────────────────────

    /** Default category for module profiles. */
    public static final String DEFAULT_CATEGORY_PROFILES = "profiles";

    /** Default category for logging presets. */
    public static final String DEFAULT_CATEGORY_LOGGING = "logging";

    /**
     * Standard conf root directory name.
     * Points to parent of JAR location: {@code ../conf}
     */
    public static final String DEFAULT_CONF_ROOT = "../conf";

    /** Default filename suffix for module profile defaults. */
    public static final String DEFAULT_MODULE_DEFAULT_FILENAME = "profile-default.properties";

    /** Default filename suffix for logging profile defaults. */
    public static final String DEFAULT_LOGGING_DEFAULT_FILENAME = "logging-default.properties";

    private PropertiesProfileLoader() {
        // utility class — never instantiated
    }

    // ── Path Resolution ────────────────────────────────────────────────

    /**
     * Resolves the profile directory for a given module and category.
     * <p>Schema: {@code confRoot/moduleId/category}</p>
     *
     * @param confRoot  base configuration root (e.g., "conf")
     * @param moduleId  module identifier (e.g., "node", "database")
     * @param category  profile category subdirectory (e.g., "profiles", "logging")
     * @return resolved Path to the profile directory
     */
    public static Path resolveProfileDir(String confRoot, String moduleId, String category) {
        return PathUtils.resolvePath(confRoot + "/" + moduleId + "/" + category);
    }

    /**
     * Resolves the full path to a specific properties-profile file.
     * <p>Schema: {@code confRoot/moduleId/category/profileName.properties}</p>
     *
     * @param confRoot    base configuration root
     * @param moduleId    module identifier
     * @param category    profile category
     * @param profileName profile name without extension
     * @return resolved Path to the profile file
     */
    public static Path resolveProfileFile(String confRoot, String moduleId, String category,
            String profileName) {
        String fileName = profileName.endsWith(".properties") ? profileName : profileName + ".properties";
        return resolveProfileDir(confRoot, moduleId, category).resolve(fileName);
    }

    // ── Discovery ──────────────────────────────────────────────────────

    /**
     * Discovers all properties-profile names in the given module/category directory.
     * <p>
     * Scans for {@code *.properties} files and excludes reserved names
     * (default templates that are not runnable profiles).
     *
     * @param confRoot      base configuration root
     * @param moduleId      module identifier
     * @param category      profile category
     * @param reservedNames set of profile names to exclude from discovery
     * @return sorted list of discoverable profile names (without extension), never null
     */
    public static List<String> discoverProfiles(String confRoot, String moduleId,
            String category, Set<String> reservedNames) {

        Path dir = resolveProfileDir(confRoot, moduleId, category);

        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return Collections.emptyList();
        }

        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".properties"))
                    .map(name -> name.substring(0, name.length() - 11)) // strip ".properties"
                    .filter(name -> reservedNames == null || !reservedNames.contains(name))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan properties-profile directory: " + dir, e);
        }
    }

    /**
     * Returns the count of discoverable properties-profiles (excluding reserved names).
     *
     * @param confRoot      base configuration root
     * @param moduleId      module identifier
     * @param category      profile category
     * @param reservedNames set of reserved profile names
     * @return number of user profiles available
     */
    public static int countProfiles(String confRoot, String moduleId,
            String category, Set<String> reservedNames) {
        return discoverProfiles(confRoot, moduleId, category, reservedNames).size();
    }

    // ── Loading ────────────────────────────────────────────────────────

    /**
     * Loads a single properties-profile file into a {@link Properties} object.
     *
     * @param confRoot    base configuration root
     * @param moduleId    module identifier
     * @param category    profile category
     * @param profileName profile name (without extension)
     * @return loaded Properties, or empty Properties if file does not exist
     * @throws RuntimeException if the file exists but cannot be read
     */
    public static Properties loadProfile(String confRoot, String moduleId,
            String category, String profileName) {

        Path file = resolveProfileFile(confRoot, moduleId, category, profileName);

        if (!Files.exists(file)) {
            return new Properties();
        }

        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(file)) {
            props.load(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load properties-profile from: " + file, e);
        }
        return props;
    }

    /**
     * Batch loads all discoverable properties-profiles using the provided factory.
     * <p>
     * For each discovered profile name:
     * <ol>
     *   <li>Create entity via {@link PropertiesProfileFactory#create(String)}</li>
     *   <li>Load properties from disk</li>
     *   <li>Set properties on the entity</li>
     * </ol>
     *
     * @param confRoot      base configuration root
     * @param moduleId      module identifier
     * @param category      profile category
     * @param reservedNames set of reserved names to exclude
     * @param factory       factory for creating profile entities
     * @param type          runtime class of the profile entity (for array creation)
     * @param <T>           must implement {@link PropertiesProfileEntity}
     * @return array of loaded profile entities, never null
     */
    public static <T extends PropertiesProfileEntity> T[] loadAll(String confRoot, String moduleId,
            String category, Set<String> reservedNames,
            PropertiesProfileFactory<T> factory, Class<T> type) {

        List<String> names = discoverProfiles(confRoot, moduleId, category, reservedNames);
        @SuppressWarnings("unchecked")
        T[] result = (T[]) java.lang.reflect.Array.newInstance(type, names.size());

        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            T entity = factory.create(name);
            Properties props = loadProfile(confRoot, moduleId, category, name);
            entity.setProperties(props);
            result[i] = entity;
        }

        return result;
    }

    // ── Default File Sync (Hash-Based) ─────────────────────────────────

    /**
     * Synchronizes a default properties-file from a classpath resource to the
     * runtime {@code conf/} directory using SHA-256 hash comparison.
     * <p>
     * Behavior:
     * <ul>
     *   <li>If target file is <b>missing</b>: copy from resource.</li>
     *   <li>If target file <b>exists but hash differs</b>: overwrite (update detected).</li>
     *   <li>If target file <b>exists and hash matches</b>: do nothing.</li>
     * </ul>
     * <p>
     * This ensures default template files are always up-to-date after application updates,
     * while user-modified custom profiles are preserved.
     *
     * @param confRoot          base configuration root (e.g., "conf")
     * @param moduleId          module identifier
     * @param category          profile category
     * @param defaultFileName   filename of the default file (e.g., "profile-default.properties")
     * @param classpathResource InputStream from the embedded classpath resource
     * @throws RuntimeException if synchronization fails
     */
    public static void syncDefaultFile(String confRoot, String moduleId, String category,
            String defaultFileName, InputStream classpathResource) {

        Path target = resolveProfileDir(confRoot, moduleId, category).resolve(defaultFileName);

        if (classpathResource == null) {
            throw new IllegalArgumentException("Classpath resource must not be null for: " + defaultFileName);
        }

        try {
            // Step 1: Compute hash of the classpath resource
            String resourceHash = computeSha256(classpathResource);

            if (!Files.exists(target)) {
                // Create directory structure if needed
                Files.createDirectories(target.getParent());
                copyStream(classpathResource, target);
                return;
            }

            // Step 2: Compare hashes — only overwrite if different
            String fileHash = computeSha256(Files.newInputStream(target));
            if (!fileHash.equals(resourceHash)) {
                copyStream(classpathResource, target);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to sync default properties-file: " + target, e);
        }
    }

    /**
     * Overloaded convenience that loads the classpath resource automatically.
     *
     * @param confRoot           base configuration root
     * @param moduleId           module identifier
     * @param category           profile category
     * @param defaultFileName    filename of the default file
     * @param classpathResourceName full classpath resource name (e.g., "/conf/node/profiles/profile-default.properties")
     */
    public static void syncDefaultFileFromClasspath(String confRoot, String moduleId, String category,
            String defaultFileName, String classpathResourceName) {

        InputStream is = PropertiesProfileLoader.class.getResourceAsStream(classpathResourceName);
        if (is == null) {
            throw new IllegalArgumentException("Classpath resource not found: " + classpathResourceName);
        }
        syncDefaultFile(confRoot, moduleId, category, defaultFileName, is);
    }

    // ── Empty Placeholder Creation ─────────────────────────────────────

    /**
     * Ensures empty placeholder properties-files exist ONLY when no user profiles
     * are discovered (excluding reserved/default names).
     * <p>
     * If at least one custom profile exists in the directory, this method does nothing.
     * This prevents polluting the conf/ directory with unnecessary empty files when
     * the user already has active profiles configured.
     *
     * @param confRoot      base configuration root
     * @param moduleId      module identifier
     * @param category      profile category
     * @param reservedNames set of reserved names to exclude from discovery
     * @param placeholderName name for the empty placeholder (without .properties extension)
     */
    public static void ensureEmptyPlaceholderIfNoProfiles(String confRoot, String moduleId,
            String category, Set<String> reservedNames, String placeholderName) {

        int count = countProfiles(confRoot, moduleId, category, reservedNames);

        if (count > 0) {
            // User profiles already exist — no need for placeholders
            return;
        }

        Path file = resolveProfileFile(confRoot, moduleId, category, placeholderName);

        if (!Files.exists(file)) {
            try {
                Files.createDirectories(file.getParent());
                Files.createFile(file);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create empty placeholder: " + file, e);
            }
        }
    }

    /**
     * Convenience: ensures both module and logging placeholders when no profiles exist.
     *
     * @param confRoot      base configuration root
     * @param moduleId      module identifier
     * @param reservedNames set of reserved names
     * @param profilePlaceholder  placeholder name for profiles category
     * @param loggingPlaceholder  placeholder name for logging category
     */
    public static void ensureEmptyPlaceholdersForModule(String confRoot, String moduleId,
            Set<String> reservedNames,
            String profilePlaceholder, String loggingPlaceholder) {

        ensureEmptyPlaceholderIfNoProfiles(confRoot, moduleId, DEFAULT_CATEGORY_PROFILES,
                reservedNames, profilePlaceholder);
        ensureEmptyPlaceholderIfNoProfiles(confRoot, moduleId, DEFAULT_CATEGORY_LOGGING,
                reservedNames, loggingPlaceholder);
    }

    // ── Directory Management ───────────────────────────────────────────

    /**
     * Ensures the profile directory exists, creating it (and parents) if necessary.
     *
     * @param confRoot   base configuration root
     * @param moduleId   module identifier
     * @param category   profile category
     * @return the (possibly newly created) directory Path
     */
    public static Path ensureProfileDirExists(String confRoot, String moduleId, String category) {
        Path dir = resolveProfileDir(confRoot, moduleId, category);
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create profile directory: " + dir, e);
            }
        }
        return dir;
    }

    // ── Private Helpers ────────────────────────────────────────────────

    /**
     * Computes the SHA-256 hex digest of an InputStream.
     * The stream is fully consumed.
     */
    static String computeSha256(InputStream is) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(is.readAllBytes());
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute SHA-256 hash", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void copyStream(InputStream is, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(is, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}