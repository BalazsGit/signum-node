package application.utils.config;

/**
 * Single Source of Truth (SSOT) for module identifiers and profile category names.
 * <p>
 * These constants define the canonical string literals used in:
 * <ul>
 *   <li>runtime configuration paths: {@code {ConfigPaths.RUNTIME_CONF_ROOT}/{module}/{category}/}</li>
 *   <li>logging scopes: {@code NodeLoggerRegistry}, {@code LogScope}</li>
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
     * System module identifier (conf path segment: {@code conf/system/}).
     * Owns the system-level (JVM-global) logging configuration layer:
     * {@code conf/system/logging/} (L1 {@code default.properties}, the
     * {@code system-default.properties} reference template and the
     * generated {@code runtime/global.log4j2.properties} — see
     * {@code application.utils.config.LoggingPaths}).
     */
    public static final String SYSTEM = "system";

    /**
     * Module profile category (path segment: {@code {conf}/{module}/profiles/}).
     * Kept in sync with {@link PropertiesProfileLoader#DEFAULT_CATEGORY_PROFILES}.
     */
    public static final String CATEGORY_PROFILES = "profiles";

    /**
     * Module logging profile category (path segment: {@code {conf}/{module}/logging/}).
     * Kept in sync with {@link PropertiesProfileLoader#DEFAULT_CATEGORY_LOGGING}.
     */
    public static final String CATEGORY_LOGGING = "logging";

    /**
     * Generated Log4j2 runtime-config category (path segment:
     * {@code {conf}/{module}/logging/runtime/}). Holds the GENERATED
     * {@code *.log4j2.properties} files (not the source of truth — see
     * {@code application.utils.config.LoggingPaths}).
     */
    public static final String CATEGORY_LOGGING_RUNTIME = "runtime";
}