package application.utils.config;

/**
 * Centralized configuration path constants shared across the application.
 * <p>
 * All module-specific config paths should reference these constants
 * to ensure consistency when the base path changes.
 *
 * <h3>Path Schema</h3>
 * <pre>{@code
 *   ../conf/{module}/{category}/*.properties
 * }</pre>
 */
public final class ConfigPaths {

    private ConfigPaths() {
        // utility class — never instantiated
    }

    /**
     * Runtime configuration root directory.
     * <p>
     * Points to parent of JAR location: {@code ../conf}
     * This is where user-editable configuration files are stored.
     *
     * <h3>Directory Structure</h3>
     * <pre>{@code
     * ../conf/
     * ├── node/
     * │   ├── profiles/          ← Node profile configurations
     * │   │   └── *.properties
     * │   └── logging/           ← Node logging presets
     * │       └── *.properties
     * ├── database/
     * │   ├── profiles/
     * │   └── logging/
     * └── system/
     *     └── logging/
     * }</pre>
     */
    public static final String RUNTIME_CONF_ROOT = "../conf";

    /** Classpath prefix for embedded default configuration resources. */
    public static final String CLASSPATH_PREFIX = "/conf/";
}