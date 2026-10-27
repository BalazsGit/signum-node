package application.utils.profile;

import java.util.Map;

/**
 * Module-independent contract a functional module implements to participate in
 * the generic profile infrastructure (the R1 layer, plan {@code P6.1}).
 * <p>
 * The R1 layer is deliberately <b>module-independent</b>: it knows nothing about
 * logging, database, or any other concrete concern. A module only describes
 * <i>what</i> a profile is (its metadata, its built-in L0 defaults and its on-disk
 * category); the persistence, assignment, registration and lifecycle mechanics are
 * shared by every module through the rest of this package.
 * </p>
 *
 * <h3>Layering</h3>
 * <pre>{@code
 *   ProfileModule        -- this contract (per-module metadata + L0 defaults)
 *   ProfileRegistry      -- the process-wide module -> ProfileModule registry
 *   ProfileRepository    -- on-disk CRUD of a module's profile files
 *   ProfileAssignmentStore -- owner -> (module -> profile) assignment map
 *   ProfileLifecycle     -- create / rename / delete orchestration with reference checks
 * }</pre>
 *
 * <p>
 * The logging module is the first implementation of this layer: its
 * {@code ModuleLoggingProfile} exposes its L0 defaults and the {@code logging}
 * on-disk category. Later modules (e.g. database) reuse the same infrastructure
 * without any further changes.
 * </p>
 */
public interface ProfileModule {

    /**
     * @return the unique, stable module identifier (e.g. {@code "node"}, {@code "database"}).
     */
    String getModuleId();

    /**
     * @return a human-readable display name for the module (never null).
     */
    String getDisplayName();

    /**
     * @return a short human-readable description of what the module's profiles control.
     */
    String getDescription();

    /**
     * Returns the module's built-in L0 default profile: the hardcoded key-value
     * mapping that every on-disk profile starts from (the L0 layer).
     *
     * @return the L0 defaults (never null; may be empty)
     */
    Map<String, String> getDefaultProfile();

    /**
     * @return the on-disk category subdirectory for this module's profile files
     *         (e.g. {@code "logging"}, {@code "prefs"}). Used to resolve
     *         {@code {confRoot}/{moduleId}/{category}/}.
     */
    String getCategory();
}
