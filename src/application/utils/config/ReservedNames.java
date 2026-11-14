package application.utils.config;

import java.util.Set;

/**
 * Single source of truth for the reserved profile names shared by the node
 * profile tree ({@code conf/{module}/profiles/}) and the logging profile tree
 * ({@code conf/{module}/logging/}).
 * <p>
 * <h3>Two-layer architecture (2026-12)</h3>
 * The intermediate L1 ({@code default.properties}) layer has been eliminated.
 * The resolution chain is now:
 * <pre>
 *   L0: hardcoded defaults (Props.java / ModuleLoggingProvider.getProfile().getDefaults())
 *   L2: conf/{module}/{category}/{profile}.properties (per-profile, GUI-editable)
 * </pre>
 * <p>
 * Template reference files ({@code node-default.properties},
 * {@code logging-default.properties}, {@code system-default.properties}) are
 * stored in {@code template/} subdirectories, which are outside the profile
 * discovery scope ({@code Files.list} + {@code Files::isRegularFile} excludes
 * subdirectories). No name-based reservation is needed.
 * <p>
 * The legacy names remain as constants for backward-compatibility with
 * existing {@code profile.json} applied-profile metadata
 * (see {@link #isDefaultMarker(String)}).
 *
 * @see PropertiesProfileLoader
 */
public final class ReservedNames {

    /**
     * Legacy constant retained for backward-compatibility with existing
     * applied-profile metadata. No longer a reserved runtime name.
     *
     * @deprecated The L1 layer has been eliminated; this name is no longer
     *             reserved. Kept only for {@link #isDefaultMarker(String)}.
     */
    @Deprecated(forRemoval = false)
    public static final String DEFAULT_PROFILE_NAME = "default";

    /**
     * Legacy constant: the node module's L0 documentation template file name.
     * Now located in {@code conf/node/profiles/template/}, outside discovery scope.
     *
     * @deprecated The template is in a subdirectory; no longer reserved by name.
     */
    @Deprecated(forRemoval = false)
    public static final String LEGACY_DEFAULT_NODE_NAME = "node-default";

    /**
     * Legacy constant: the logging L0 documentation template file name
     * + legacy applied marker. Now located in
     * {@code conf/node/logging/template/}, outside discovery scope.
     *
     * @deprecated The template is in a subdirectory; no longer reserved by name.
     */
    @Deprecated(forRemoval = false)
    public static final String LEGACY_DEFAULT_LOGGING_NAME = "logging-default";

    /**
     * Legacy constant: the system-level logging L0 documentation template
     * file name. Now located in {@code conf/system/logging/template/},
     * outside discovery scope.
     *
     * @deprecated The template is in a subdirectory; no longer reserved by name.
     */
    @Deprecated(forRemoval = false)
    public static final String SYSTEM_DEFAULT_LOGGING_NAME = "system-default";

    /**
     * Names excluded from NODE profile discovery ({@code conf/{module}/profiles/}).
     * <p>
     * <b>Empty</b> in the two-layer architecture: template files reside in
     * {@code template/} subdirectories (not scanned by discovery), and the
     * former L1 {@code default.properties} no longer exists.
     */
    public static final Set<String> NODE_RESERVED_NAMES = Set.of();

    /**
     * Names excluded from LOGGING profile discovery ({@code conf/{module}/logging/}).
     * <p>
     * <b>Empty</b> in the two-layer architecture (see {@link #NODE_RESERVED_NAMES}).
     */
    public static final Set<String> LOGGING_RESERVED_NAMES = Set.of();

    private ReservedNames() {
        // static utility
    }

    /** @return true when the name is reserved in a node profile directory (always false in 2-layer). */
    public static boolean isNodeReservedName(String name) {
        return name != null && NODE_RESERVED_NAMES.contains(name);
    }

    /** @return true when the name is reserved in a logging profile directory (always false in 2-layer). */
    public static boolean isLoggingReservedName(String name) {
        return name != null && LOGGING_RESERVED_NAMES.contains(name);
    }

    /**
     * Marker equivalence: whether the given applied-profile marker
     * denotes the built-in "Default" (i.e. NO explicit logging assignment is
     * recorded). A missing/blank marker, the legacy {@code default} name
     * and the legacy {@code logging-default} marker are all equivalent.
     * <p>
     * Retained for backward-compatibility with existing
     * {@code profiles.json} entries that reference the legacy names.
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