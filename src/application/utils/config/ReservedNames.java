package application.utils.config;

import java.util.Set;

/**
 * Single source of truth for the reserved profile names shared by the node
 * profile tree ({@code conf/{module}/profiles/}) and the logging profile tree
 * ({@code conf/{module}/logging/}).
 * <p>
 * <h3>The user-editable L1 layer</h3>
 * The canonical reserved name is {@link #DEFAULT_PROFILE_NAME} ({@code default}):
 * the user-owned {@code default.properties} file that is the runtime L1 layer
 * (its active lines override the hardcoded L0 defaults for every profile). It
 * is auto-created at first start and is never overwritten by the classpath
 * sync.
 * <p>
 * The legacy names ({@code node-default}, {@code logging-default}) remain
 * reserved as the file names of the reference-only L0 documentation templates
 * (SHA-256 synced from classpath resources, never read at runtime) and as
 * legacy applied-profile markers (see {@link #isDefaultMarker(String)}).
 *
 * @see PropertiesProfileLoader
 */
public final class ReservedNames {

    /** The canonical reserved L1 profile name (the user-editable {@code default.properties}). */
    public static final String DEFAULT_PROFILE_NAME = "default";

    /** Legacy reserved name: the node module's L0 documentation template file. */
    public static final String LEGACY_DEFAULT_NODE_NAME = "node-default";

    /** Legacy reserved name: the logging L0 documentation template file + legacy applied marker. */
    public static final String LEGACY_DEFAULT_LOGGING_NAME = "logging-default";

    /**
     * Names excluded from NODE profile discovery ({@code conf/{module}/profiles/}):
     * the canonical L1 name + the legacy template names (a
     * {@code logging-default.properties} may exist in the profiles directory of
     * legacy installs).
     */
    public static final Set<String> NODE_RESERVED_NAMES =
            Set.of(DEFAULT_PROFILE_NAME, LEGACY_DEFAULT_NODE_NAME, LEGACY_DEFAULT_LOGGING_NAME);

    /** Names excluded from LOGGING profile discovery ({@code conf/{module}/logging/}). */
    public static final Set<String> LOGGING_RESERVED_NAMES =
            Set.of(DEFAULT_PROFILE_NAME, LEGACY_DEFAULT_LOGGING_NAME);

    private ReservedNames() {
        // static utility
    }

    /** @return true when the name is reserved in a node profile directory. */
    public static boolean isNodeReservedName(String name) {
        return name != null && NODE_RESERVED_NAMES.contains(name);
    }

    /** @return true when the name is reserved in a logging profile directory. */
    public static boolean isLoggingReservedName(String name) {
        return name != null && LOGGING_RESERVED_NAMES.contains(name);
    }

    /**
     * Marker equivalence: whether the given applied-profile marker
     * denotes the built-in "Default" (i.e. NO explicit logging assignment is
     * recorded). A missing/blank marker, the canonical {@code default} name
     * and the legacy {@code logging-default} marker are all equivalent.
     *
     * @param marker the applied-profile marker (may be null)
     * @return true when the marker denotes the built-in default
     */
    public static boolean isDefaultMarker(String marker) {
        return marker == null || marker.isBlank()
                || DEFAULT_PROFILE_NAME.equals(marker)
                || LEGACY_DEFAULT_LOGGING_NAME.equals(marker);
    }
}