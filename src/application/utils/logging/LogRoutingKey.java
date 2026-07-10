package application.utils.logging;

import java.util.Objects;

/**
 * Immutable composite key for log routing: moduleId + profileName.
 *
 * <p>Ensures logs from different modules with identical profile names
 * are routed to the correct destination console panel.</p>
 *
 * <h3>Example Scenarios</h3>
 * <pre>
 *   LogRoutingKey.of("node", "profil-bela")    {@literal →} node modul profil-bela konzolja
 *   LogRoutingKey.of("database", "profil-bela") {@literal →} database modul profil-bela konzolja
 *   LogRoutingKey.of("mining", "mainnet")       {@literal →} mining modul mainnet konzolja
 * </pre>
 *
 * <p><b>Thread-safe:</b> Immutable after construction.</p>
 * <p><b>Performance:</b> O(1) hashCode via cached computation.</p>
 *
 * @see ProfileThreadContext
 * @see ProfileLogRouter
 * @see ProfileLogContext
 */
public final class LogRoutingKey {

    /** Separator used in toString() representation: "moduleId:profileName" */
    public static final String SEPARATOR = ":";

    private final String moduleId;
    private final String profileName;
    private final String compositeString;
    private final int hashCode;

    /**
     * Private constructor – use {@link #of(String, String)} factory method.
     */
    private LogRoutingKey(String moduleId, String profileName) {
        this.moduleId = moduleId;
        this.profileName = profileName;
        this.compositeString = (moduleId != null ? moduleId : "")
                + SEPARATOR
                + (profileName != null ? profileName : "");
        this.hashCode = Objects.hash(moduleId, profileName);
    }

    /**
     * Creates a composite routing key from module ID and profile name.
     *
     * @param moduleId    the module identifier (e.g., "node", "database", "mining"), or null
     * @param profileName the profile name within that module, or null
     * @return a new {@link LogRoutingKey}, or {@code null} if both parameters are null/empty
     */
    public static LogRoutingKey of(String moduleId, String profileName) {
        if ((profileName == null || profileName.isEmpty())
                && (moduleId == null || moduleId.isEmpty())) {
            return null; // Empty key indicates bootstrap/broadcast logging
        }
        return new LogRoutingKey(moduleId, profileName);
    }

    /** @return the module identifier, or {@code null} if not set */
    public String getModuleId() {
        return moduleId;
    }

    /** @return the profile name, or {@code null} if not set */
    public String getProfileName() {
        return profileName;
    }

    /** @return {@code true} if a module ID is configured */
    public boolean hasModule() {
        return moduleId != null && !moduleId.isEmpty();
    }

    /** @return {@code true} if a profile name is configured */
    public boolean hasProfile() {
        return profileName != null && !profileName.isEmpty();
    }

    /** @return {@code true} if both module and profile are absent (bootstrap context) */
    public boolean isEmpty() {
        return !hasModule() && !hasProfile();
    }

    /**
     * Returns a human-readable composite string representation.
     * Format: "moduleId:profileName" (e.g., "node:profil-bela")
     */
    @Override
    public String toString() {
        return compositeString;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LogRoutingKey that = (LogRoutingKey) o;
        return Objects.equals(moduleId, that.moduleId)
                && Objects.equals(profileName, that.profileName);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}