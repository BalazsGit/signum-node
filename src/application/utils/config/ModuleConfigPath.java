package application.utils.config;

import application.utils.io.PathUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Handles module configuration file paths using a hybrid resolution strategy:
 * <pre>
 *   1. Runtime user override:  conf/{module}/{file}.properties        (if exists)
 *   2. Classpath default:      classpath:/conf/{module}/{file}-default.properties
 * </pre>
 *
 * On init, empty override files are created in conf/ so the user can
 * edit them without touching the JAR-embedded defaults.
 *
 * Thread-safe and immutable after construction.
 */
public final class ModuleConfigPath {

    private static final Logger LOGGER = Logger.getLogger(ModuleConfigPath.class.getName());

    /** Base directory for runtime (user-editable) configuration. */
    private static final String RUNTIME_CONF_ROOT = "conf";

    /** Classpath prefix for embedded default configuration. */
    private static final String CLASSPATH_PREFIX = "/conf/";

    private ModuleConfigPath() {
        // utility class
    }

    // -----------------------------------------------------------------
    // Public API — Path resolution
    // -----------------------------------------------------------------

    /**
     * Returns the runtime (user-editable) path for a given module config file.
     * <p>Example: module="node", file="logging" → conf/node/logging.properties</p>
     *
     * @param moduleId module identifier (e.g., "node", "database")
     * @param fileName logical file name without extension (e.g., "node", "logging")
     * @return Path to the user override file (resolved via PathUtils)
     */
    public static Path getRuntimePath(String moduleId, String fileName) {
        return PathUtils.resolvePath(RUNTIME_CONF_ROOT + "/" + moduleId + "/" + fileName + ".properties");
    }

    /**
     * Returns the classpath resource name for a module default config file.
     * <p>Example: module="node", file="logging" → /conf/node/logging-default.properties</p>
     *
     * @param moduleId module identifier
     * @param fileName logical file name without extension
     * @return classpath resource name
     */
    public static String getClassPathResource(String moduleId, String fileName) {
        return CLASSPATH_PREFIX + moduleId + "/" + fileName + "-default.properties";
    }

    /**
     * Returns the classpath resource for a module profile default.
     * <p>Example: module="node" → /conf/node/profiles/profile-default.properties</p>
     */
    public static String getProfileClassPathResource(String moduleId) {
        return CLASSPATH_PREFIX + moduleId + "/profiles/profile-default.properties";
    }

    // -----------------------------------------------------------------
    // Init — create empty user override files if they don't exist
    // -----------------------------------------------------------------

    /**
     * Ensures an empty override properties file exists at the given runtime path.
     * If the directory does not exist it is created recursively.
     * If the file already exists it is left untouched.
     *
     * @param moduleId module identifier
     * @param fileName logical file name without extension
     */
    public static void initEmptyOverride(String moduleId, String fileName) {
        Path path = getRuntimePath(moduleId, fileName);
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                Files.createFile(path);
                LOGGER.info("Created empty override config: " + path);
            }
        } catch (IOException e) {
            LOGGER.warning("Could not create override config at " + path + ": " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    // Load — stream helpers
    // -----------------------------------------------------------------

    /**
     * Opens an input stream for the classpath (JAR-embedded) default config.
     *
     * @param moduleId module identifier
     * @param fileName logical file name without extension
     * @return InputStream or null if resource not found
     */
    public static InputStream loadFromClassPath(String moduleId, String fileName) {
        String resource = getClassPathResource(moduleId, fileName);
        return ModuleConfigPath.class.getResourceAsStream(resource);
    }

    /**
     * Opens an input stream for the classpath (JAR-embedded) profile default config.
     *
     * @param moduleId module identifier
     * @return InputStream or null if resource not found
     */
    public static InputStream loadProfileFromClassPath(String moduleId) {
        return ModuleConfigPath.class.getResourceAsStream(getProfileClassPathResource(moduleId));
    }

    /**
     * Opens an input stream for the runtime user override file.
     *
     * @param moduleId module identifier
     * @param fileName logical file name without extension
     * @return InputStream or null if file does not exist
     * @throws IOException if the file cannot be opened
     */
    public static InputStream loadFromRuntime(String moduleId, String fileName) throws IOException {
        Path path = getRuntimePath(moduleId, fileName);
        if (Files.exists(path)) {
            return Files.newInputStream(path);
        }
        return null;
    }
}