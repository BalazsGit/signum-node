package application.utils.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Module-independent store of <b>owner profile → (module → profile)</b> assignments (R1).
 * <p>
 * This is the generic counterpart of the earlier logging-specific
 * {@code LoggingAssignmentStore}. It is the single source of truth for the
 * association "which profile does a given owner (e.g. a node profile) use per module".
 * </p>
 *
 * <p>
 * The store is deliberately <b>agnostic about where the assignment is persisted</b>:
 * it delegates all reads/writes to a pluggable {@link Codec}. The logging module
 * supplies a codec backed by the node profiles SSOT ({@code conf/node/profiles.json});
 * another module can supply a codec backed by any other store without touching this
 * class.
 * </p>
 *
 * <p>Headless-safe (no Swing).</p>
 */
public final class ProfileAssignmentStore {

    /**
     * Pluggable single-source-of-truth codec for the owner → (module → profile) map.
     * A module provides a codec backed by its own persistence.
     */
    public interface Codec {
        /**
         * @param owner the owner profile name
         * @return the module→profile map for the owner, or null/empty when none is set
         */
        Map<String, String> get(String owner);

        /**
         * Sets (or clears, when {@code assignment} is null) the assignment for the owner.
         *
         * @param owner      the owner profile name
         * @param assignment the module→profile map, or null to clear
         */
        void set(String owner, Map<String, String> assignment);

        /**
         * @param owner the owner profile name
         * @return the known owner profile names (never null)
         */
        List<String> listOwners();
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileAssignmentStore.class);

    private final Codec codec;

    /**
     * Process-wide push listeners for assignment changes (BookmarkStore-style
     * change notification). Fired synchronously on the writing thread, so listeners
     * must be non-blocking (e.g. dispatch their refresh to the EDT).
     */
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    /**
     * Creates a store backed by the given codec.
     *
     * @param codec the assignment persistence codec (must not be null)
     */
    public ProfileAssignmentStore(Codec codec) {
        if (codec == null) {
            throw new IllegalArgumentException("codec must not be null");
        }
        this.codec = codec;
    }

    // ── Change notification (push) ────────────────────────────────────

    /**
     * Registers a listener notified after any assignment change.
     *
     * @param listener the listener to add
     */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    /**
     * Removes a previously registered listener.
     *
     * @param listener the listener to remove
     */
    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    private void fireChange() {
        changeListeners.forEach(Runnable::run);
    }

    // ── Assignment API ────────────────────────────────────────────────

    /**
     * Returns a copy of the module→profile assignment for the owner.
     *
     * @param owner the owner profile name
     * @return the assignment map (never null; empty when none is set)
     */
    public Map<String, String> getAssignment(String owner) {
        Map<String, String> map = codec.get(owner);
        return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    }

    /**
     * Sets (or clears, when {@code assignment} is null) the full assignment for the owner.
     *
     * @param owner      the owner profile name
     * @param assignment the module→profile map, or null to clear
     */
    public void setAssignment(String owner, Map<String, String> assignment) {
        if (owner == null || owner.isBlank()) {
            return;
        }
        codec.set(owner, assignment == null ? null : new LinkedHashMap<>(assignment));
        LOGGER.debug("Set assignment for owner '{}' to {}", owner, assignment);
        fireChange();
    }

    /**
     * Sets (or removes, when blank) a single module's assignment for the owner.
     *
     * @param owner       the owner profile name
     * @param moduleId    the module id
     * @param profileName the profile name for the module, or null/blank to remove
     */
    public void setModuleAssignment(String owner, String moduleId, String profileName) {
        if (owner == null || owner.isBlank() || moduleId == null || moduleId.isBlank()) {
            return;
        }
        Map<String, String> current = getAssignment(owner);
        if (profileName == null || profileName.isBlank()) {
            current.remove(moduleId);
        } else {
            current.put(moduleId, profileName);
        }
        setAssignment(owner, current);
    }

    /**
     * Clears any assignment for the owner.
     *
     * @param owner the owner profile name
     */
    public void clearAssignment(String owner) {
        setAssignment(owner, null);
    }

    /**
     * Re-keys a renamed owner's assignment (moves the map from {@code oldName} to
     * {@code newName}). No-op when the old owner has no assignment.
     *
     * @param oldName the owner name before the rename
     * @param newName the owner name after the rename
     */
    public void renameAssignment(String oldName, String newName) {
        if (oldName == null || oldName.isBlank() || newName == null || newName.isBlank()) {
            return;
        }
        Map<String, String> assignment = getAssignment(oldName);
        if (assignment.isEmpty()) {
            return;
        }
        setAssignment(newName, assignment);
        clearAssignment(oldName);
        LOGGER.info("Re-keyed assignment for renamed owner '{}' -> '{}'", oldName, newName);
        fireChange(); // the row identity (owner name) changed
    }

    /**
     * @param owner the owner profile name
     * @return true if the owner has a non-empty assignment
     */
    public boolean isManaged(String owner) {
        return !getAssignment(owner).isEmpty();
    }

    /**
     * @return the known owner profile names (never null)
     */
    public List<String> listOwners() {
        List<String> owners = codec.listOwners();
        return owners == null ? Collections.emptyList() : owners;
    }

    /**
     * Reverse lookup: returns the owners whose assignment references the given profile.
     * Used for delete-protection.
     *
     * @param profileName the profile name to search for
     * @return a list of owner names referencing it (never null)
     */
    public List<String> findReferencingProfiles(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String owner : listOwners()) {
            if (getAssignment(owner).values().contains(profileName)) {
                matches.add(owner);
            }
        }
        return matches;
    }

    @Override
    public String toString() {
        return "ProfileAssignmentStore{codec=" + codec.getClass().getSimpleName() + '}';
    }
}
