package application.module.logging;

import application.module.node.profile.NodeProfile;
import application.module.node.profile.ProfileConfig;
import application.utils.config.ConfigPaths;
import application.utils.config.ModuleIds;
import application.utils.config.PropertiesProfileLoader;
import application.utils.io.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single source of truth for <b>node-profile → logging-profile</b> assignments.
 * <p>
 * Historically the association "which logging profile does a given node profile
 * use" was scattered across two mechanisms that could drift apart:
 * </p>
 * <ol>
 *   <li><b>Canonical (this store's writer):</b> {@code ProfileConfig.loggingPresets}
 *       in {@code conf/node/profiles.json} — a per-module map, e.g.
 *       {@code {"node":"myprofile","database":"quiet"}}.</li>
 * </ol>
 *
 * <p>
 * This store is the <b>only writer</b>: all writes go to mechanism 1
 * ({@link ProfileConfig#setLoggingPresets}). Legacy {@code profileLinks} entries are
 * migrated into the canonical map by {@link ProfileConfig} on first read.
 * </p>
 *
 * <p>
 * Headless-safe (no Swing). The module id dimension of the assignment map is
 * intentionally generic so future modules (pool, fluxcapacitor, …) can reuse the
 * same store without further changes.
 * </p>
 *
 * @see ProfileConfig
 * @see LoggingProfileRepository
 */
public final class LoggingAssignmentStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingAssignmentStore.class);

    /**
     * Process-wide push listeners for node-profile / assignment changes
     * (the BookmarkStore-style change notification). Fired synchronously on
     * the writing thread (EDT or CLI), so listeners must be non-blocking —
     * e.g. dispatch their refresh to the EDT via
     * {@link javax.swing.SwingUtilities#invokeLater}.
     */
    private static final List<Runnable> PROFILE_CHANGE_LISTENERS = new CopyOnWriteArrayList<>();

    private final String confRoot;
    private final ProfileConfig profileConfig;

    /**
     * Creates a store bound to the standard runtime conf root
     * ({@link ConfigPaths#RUNTIME_CONF_ROOT}).
     */
    public LoggingAssignmentStore() {
        this(PropertiesProfileLoader.DEFAULT_CONF_ROOT);
    }

    /**
     * Creates a store bound to a specific configuration root.
     *
     * @param confRoot Base configuration root (e.g. {@code ./conf}); must not be null
     */
    public LoggingAssignmentStore(String confRoot) {
        if (confRoot == null || confRoot.isBlank()) {
            throw new IllegalArgumentException("confRoot must not be null or blank");
        }
        this.confRoot = confRoot;
        // The canonical SSOT file is conf/node/profiles.json (matches ProfileConfig's default path).
        Path ssotFile = PathUtils.resolvePath(confRoot).resolve(ModuleIds.NODE).resolve("profiles.json");
        this.profileConfig = new ProfileConfig(ssotFile);
    }

    // ── Change notification (push) ─────────────────────────────────────

    /**
     * Registers a push listener notified after any node-profile or logging-assignment
     * change (create / rename / delete of a node profile, or an assignment write).
     * <p>
     * The listener runs <b>synchronously on the writing thread</b> — it must return
     * quickly (dispatch UI refreshes to the EDT). A throwing listener is caught,
     * logged, and removed: one broken listener must not silence the others.
     * </p>
     *
     * @param listener the listener to register (null is ignored)
     */
    public static void addProfileChangeListener(Runnable listener) {
        if (listener != null && !PROFILE_CHANGE_LISTENERS.contains(listener)) {
            PROFILE_CHANGE_LISTENERS.add(listener);
        }
    }

    /**
     * Unregisters a previously registered push listener (no-op if unknown).
     *
     * @param listener the listener to remove
     */
    public static void removeProfileChangeListener(Runnable listener) {
        PROFILE_CHANGE_LISTENERS.remove(listener);
    }

    /**
     * Notifies every registered listener that the node-profile / assignment state
     * changed. Called by the store's writers and by the node-profile lifecycle
     * operations (see {@code NodeProfileRepository}).
     */
    public static void fireProfileChanged() {
        for (Runnable listener : PROFILE_CHANGE_LISTENERS) {
            try {
                listener.run();
            } catch (Exception e) {
                LOGGER.warn("A logging profile-change listener failed — removing it: {}", e.getMessage());
                PROFILE_CHANGE_LISTENERS.remove(listener);
            }
        }
    }

    // ── Read ───────────────────────────────────────────────────────────

    /**
     * Returns the logging-profile assignment for a node profile.
     * <p>
     * Any legacy {@code profileLinks} entry is migrated to the canonical
     * {@code ProfileConfig.loggingPresets} map as part of the read.
     *
     * @param nodeProfileName Node profile name (e.g. {@code "mainnet"})
     * @return Unmodifiable map of module id → logging profile name; empty if unset (never null)
     */
    public Map<String, String> getAssignment(String nodeProfileName) {
        Map<String, String> canonical = profileConfig.getLoggingPresets(nodeProfileName);
        return canonical != null ? canonical : Map.of();
    }

    /**
     * Resolves the <b>effective</b> logging profile name that a node profile uses for a
     * given module, guaranteeing a valid fallback:
     * <ul>
     *   <li>if the assignment names a resolvable profile (on-disk file)
     *       → that name is returned;</li>
     *   <li>if the assignment is <b>invalid</b> (e.g. the linked profile was deleted) or
     *       <b>absent</b> → the reserved {@code logging-default} (hardcoded default) is
     *       returned.</li>
     * </ul>
     * <p>
     * This is the single read-side resolution used by the runtime applier
     * ({@code NodeLoggingApplier}), so an assignment can never point at a missing profile.
     * </p>
     *
     * @param nodeProfileName Node profile name (e.g. {@code "mainnet"})
     * @param moduleId        Module identifier (e.g. {@code "node"})
     * @return The effective profile name (never null)
     */
    public String resolveEffectiveForModule(String nodeProfileName, String moduleId) {
        String requested = getAssignment(nodeProfileName).get(moduleId);
        return new LoggingProfileRepository(confRoot).resolveEffective(moduleId, requested);
    }

    // ── Write (canonical only) ─────────────────────────────────────────

    /**
     * Sets the full assignment for a node profile. This is the single writer and
     * persists to the canonical {@code ProfileConfig} store.
     *
     * @param nodeProfileName Node profile name
     * @param assignments     Map of module id → logging profile name (null values ignored);
     *                        pass null or an empty map to clear
     */
    public void setAssignment(String nodeProfileName, Map<String, String> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            profileConfig.setLoggingPresets(nodeProfileName, null);
            fireProfileChanged();
            return;
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : assignments.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isBlank()) {
                cleaned.put(entry.getKey(), entry.getValue());
            }
        }
        profileConfig.setLoggingPresets(nodeProfileName, cleaned.isEmpty() ? null : cleaned);
        LOGGER.info("Set logging assignment for node profile '{}' -> {}", nodeProfileName, cleaned);
        fireProfileChanged();
    }

    /**
     * Sets (or updates) the logging profile for a single module within a node profile.
     *
     * @param nodeProfileName    Node profile name
     * @param moduleId           Module id (e.g. {@code "node"}, {@code "database"})
     * @param loggingProfileName Logging profile name, or null to clear that module
     */
    public void setAssignmentForModule(String nodeProfileName, String moduleId, String loggingProfileName) {
        Map<String, String> current = new LinkedHashMap<>(getAssignment(nodeProfileName));
        if (loggingProfileName == null || loggingProfileName.isBlank()) {
            current.remove(moduleId);
        } else {
            current.put(moduleId, loggingProfileName);
        }
        setAssignment(nodeProfileName, current);
    }

    /**
     * Clears any logging assignment for a node profile.
     *
     * @param nodeProfileName Node profile name
     */
    public void clearAssignment(String nodeProfileName) {
        setAssignment(nodeProfileName, null);
    }

    /**
     * Re-keys a renamed node profile's logging assignment: the assignment under
     * {@code oldName} (canonical store, or a legacy fallback migrated to the
     * canonical store) is moved to {@code newName}. No-op when the old profile has
     * no assignment.
     *
     * @param oldName the profile name before the rename
     * @param newName the profile name after the rename
     */
    public void renameAssignment(String oldName, String newName) {
        if (oldName == null || oldName.isBlank() || newName == null || newName.isBlank()) {
            return;
        }
        Map<String, String> assignment = new LinkedHashMap<>(getAssignment(oldName));
        if (assignment.isEmpty()) {
            return;
        }
        setAssignment(newName, assignment);
        clearAssignment(oldName);
        LOGGER.info("Re-keyed logging assignment for renamed profile '{}' -> '{}'", oldName, newName);
        fireProfileChanged(); // the row identity (node profile name) changed
    }

    /**
     * Checks whether the canonical store has an explicit assignment for the profile.
     *
     * @param nodeProfileName Node profile name
     * @return true if the canonical map is present and non-empty
     */
    public boolean isManaged(String nodeProfileName) {
        Map<String, String> canonical = profileConfig.getLoggingPresets(nodeProfileName);
        return canonical != null && !canonical.isEmpty();
    }

    // ── Discovery / reverse lookup ─────────────────────────────────────

    /**
     * Lists the discoverable node profile names, excluding exactly the reserved
     * names the Node module itself uses ({@link NodeProfile#RESERVED_PROFILE_NAMES})
     * — so this list agrees with the profiles shown as tabs in the Node panel
     * (a profile file literally named {@code node} IS a runnable profile and
     * must be listed here too).
     *
     * @return Sorted list of node profile names (never null)
     */
    public List<String> listNodeProfiles() {
        return PropertiesProfileLoader.discoverProfiles(
                confRoot, ModuleIds.NODE, ModuleIds.CATEGORY_PROFILES,
                NodeProfile.RESERVED_PROFILE_NAMES);
    }

    /**
     * Reverse lookup: returns the node profiles whose assignment references the given
     * logging profile. Used for delete-protection (see plan §2.5.3).
     *
     * @param loggingProfileName Logging profile name to search for
     * @return Sorted list of node profile names referencing it (never null)
     */
    public List<String> findReferencingProfiles(String loggingProfileName) {
        if (loggingProfileName == null || loggingProfileName.isBlank()) {
            return List.of();
        }
        java.util.List<String> matches = new java.util.ArrayList<>();
        for (String nodeProfile : listNodeProfiles()) {
            if (getAssignment(nodeProfile).values().contains(loggingProfileName)) {
                matches.add(nodeProfile);
            }
        }
        return matches;
    }

}