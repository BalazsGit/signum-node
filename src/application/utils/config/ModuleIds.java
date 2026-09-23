package application.utils.config;

/**
 * Single Source of Truth (SSOT) for module identifiers and profile category names.
 * <p>
 * These constants define the canonical string literals used in:
 * <ul>
 *   <li>runtime configuration paths: {@code {ConfigPaths.RUNTIME_CONF_ROOT}/{module}/{category}/}</li>
 *   <li>logging scopes: {@code NodeLoggerRegistry}, {@code NodeLogContext}, {@code LogScope}</li>
 *   <li>module identity: {@link application.api.Module#getId()}</li>
 * </ul>
 * <p>
 * <b>Rule:</b> production and test code must reference these constants instead of
 * inlining the literals. {@code ConfigPathsConsistencyTest} enforces the consistency.
 * <p>
 * <h3>Path Schema</h3>
 * <pre>
 *   {ConfigPaths.RUNTIME_CONF_ROOT}/{module}/{category}/*.properties
 *   e.g. ./conf/node/profiles/profile-default.properties
 * </pre>
 *
 * @see ConfigPaths
 * @see PropertiesProfileLoader
 */
public final class ModuleIds {

    private ModuleIds() {
        // utility class — never instantiated
    }

    /** Node module identifier (conf path segment + logging scope + module registry ID). */
    public static final String NODE = "node";

    /** Database module identifier (conf path segment + logging scope + module registry ID). */
    public static final String DATABASE = "database";

    /** Browser module identifier (conf path segment + logging scope + module registry ID). */
    public static final String BROWSER = "browser";

    /**
     * Module profile category (path segment: {@code {conf}/{module}/profiles/}).
     * Kept in sync with {@link PropertiesProfileLoader#DEFAULT_CATEGORY_PROFILES}.
     */
    public static final String CATEGORY_PROFILES = "profiles";

    /**
     * Module logging preset category (path segment: {@code {conf}/{module}/logging/}).
     * Kept in sync with {@link PropertiesProfileLoader#DEFAULT_CATEGORY_LOGGING}.
     */
    public static final String CATEGORY_LOGGING = "logging";
}