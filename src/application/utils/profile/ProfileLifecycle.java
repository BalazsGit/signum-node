package application.utils.profile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Module-independent lifecycle orchestration for a module's profiles (R1).
 * <p>
 * This is the generic counterpart of the earlier scattered profile lifecycle logic
 * (delete-protection, rename re-keying, applied-marking). It composes a
 * {@link ProfileRepository} (on-disk state) with a {@link ProfileAssignmentStore}
 * (owner → module → profile assignments) to provide safe, reference-checked
 * create / rename / delete operations, so no module has to re-implement the
 * "is this profile still in use?" rules.
 * </p>
 *
 * <p>Headless-safe (no Swing).</p>
 */
public final class ProfileLifecycle {

    private final String moduleId;
    private final ProfileRepository repository;
    private final ProfileAssignmentStore assignments;

    /**
     * Creates a lifecycle bound to one module's repository and assignment store.
     *
     * @param moduleId    the module id (must not be null/blank)
     * @param repository  the module's on-disk profile repository
     * @param assignments the assignment store (may be null if the module has no owners)
     */
    public ProfileLifecycle(String moduleId, ProfileRepository repository, ProfileAssignmentStore assignments) {
        if (moduleId == null || moduleId.isBlank()) {
            throw new IllegalArgumentException("moduleId must not be null or blank");
        }
        if (repository == null) {
            throw new IllegalArgumentException("repository must not be null");
        }
        this.moduleId = moduleId;
        this.repository = repository;
        this.assignments = assignments;
    }

    /**
     * @return the module id this lifecycle manages
     */
    public String getModuleId() {
        return moduleId;
    }

    /**
     * @return the underlying repository
     */
    public ProfileRepository getRepository() {
        return repository;
    }

    /**
     * Creates a new profile and (when {@code owner} is given) assigns it to that owner.
     *
     * @param owner       the owner profile name, or null to create without assigning
     * @param profileName the new profile name (must not be reserved)
     * @param data        initial contents (may be null for an empty profile)
     * @return the created file path
     * @throws IOException on write error
     */
    public Path create(String owner, String profileName, Properties data) throws IOException {
        Path file = repository.create(profileName, data);
        if (owner != null && !owner.isBlank() && assignments != null) {
            assignments.setModuleAssignment(owner, moduleId, profileName);
        }
        return file;
    }

    /**
     * Renames a profile, re-keying any owner assignment that referenced the old name.
     *
     * @param profileName the existing profile name
     * @param newName     the new profile name (must not be reserved)
     * @return the renamed file path
     * @throws IOException on file move error
     */
    public Path rename(String profileName, String newName) throws IOException {
        Path file = repository.rename(profileName, newName);
        if (assignments != null) {
            for (String owner : assignments.listOwners()) {
                Map<String, String> map = assignments.getAssignment(owner);
                if (profileName.equals(map.get(moduleId))) {
                    assignments.setModuleAssignment(owner, moduleId, newName);
                }
            }
        }
        return file;
    }

    /**
     * Deletes a profile, refusing when one or more owners still reference it.
     *
     * @param profileName the profile name to delete (must not be reserved)
     * @throws IOException           on file deletion error
     * @throws ProfileInUseException when the profile is still referenced by owners
     */
    public void delete(String profileName) throws IOException {
        if (assignments != null) {
            List<String> referencing = assignments.findReferencingProfiles(profileName);
            if (!referencing.isEmpty()) {
                throw new ProfileInUseException(profileName, referencing);
            }
        }
        repository.delete(profileName);
    }

    /**
     * Reverse lookup: the owners whose assignment references the given profile.
     *
     * @param profileName the profile name
     * @return the referencing owner names (never null; empty when no assignments)
     */
    public List<String> findReferencingProfiles(String profileName) {
        return assignments == null ? List.of() : assignments.findReferencingProfiles(profileName);
    }

    /**
     * Marks a profile as the applied one.
     *
     * @param profileName the applied profile name, or null to clear
     * @throws IOException on write error
     */
    public void setApplied(String profileName) throws IOException {
        repository.setApplied(profileName);
    }

    /**
     * @return the currently applied profile name, or null
     */
    public String getApplied() {
        return repository.getApplied();
    }

    @Override
    public String toString() {
        return "ProfileLifecycle{module='" + moduleId + "'}";
    }

    /**
     * Thrown when a profile about to be deleted is still referenced by one or more owners.
     */
    public static final class ProfileInUseException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final List<String> referencing;

        ProfileInUseException(String profileName, List<String> referencing) {
            super("Profile '" + profileName + "' is referenced by: " + referencing);
            this.referencing = referencing;
        }

        /**
         * @return the owner names still referencing the profile
         */
        public List<String> getReferencingProfiles() {
            return referencing;
        }
    }
}
