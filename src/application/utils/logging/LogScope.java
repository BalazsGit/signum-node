package application.utils.logging;

import java.util.Objects;

/**
 * An immutable {@code (module, profile)} pair identifying a specific profile
 * within a specific module — the collision-safe identity used for log routing,
 * registry keys, color assignment, and the console tag.
 * <p>
 * Profiles are only unique <i>within</i> a module: the {@code node} module and a
 * {@code database} module can both have a profile named {@code mainnet}. A bare
 * profile name therefore does not uniquely identify a log target; this value
 * object does. It compares on both parts, so {@code node.mainnet} and
 * {@code database.mainnet} are distinct even though their profile names match.
 * </p>
 * <p>
 * The qualified string form ({@link #qualifiedName()}) is intended for
 * <b>display and persistence only</b> (e.g. the {@code <node.mainnet>} tag).
 * It must never be parsed back into a {@code (module, profile)} pair, since a
 * profile name may itself contain a dot. Always keep the two parts separately.
 * </p>
 *
 * @see NodeLogContext
 * @see NodeLoggerRegistry
 */
public final class LogScope {

    private final String module;
    private final String profile;

    private LogScope(String module, String profile) {
        this.module = Objects.requireNonNull(module, "module must not be null");
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        if (module.isEmpty()) {
            throw new IllegalArgumentException("module must not be empty");
        }
        if (profile.isEmpty()) {
            throw new IllegalArgumentException("profile must not be empty");
        }
    }

    /**
     * Creates a new scope.
     *
     * @param module  the module id (e.g. "node", "database") — non-null, non-empty
     * @param profile the profile name within the module (e.g. "mainnet") — non-null, non-empty
     * @return the new scope
     */
    public static LogScope of(String module, String profile) {
        return new LogScope(module, profile);
    }

    /** @return the module id (non-null, non-empty) */
    public String module() {
        return module;
    }

    /** @return the profile name within the module (non-null, non-empty) */
    public String profile() {
        return profile;
    }

    /**
     * Returns the qualified display form {@code module.profile}.
     * <p>
     * Intended for display/persistence only — do not parse it back.
     * </p>
     *
     * @return e.g. {@code "node.mainnet"}
     */
    public String qualifiedName() {
        return module + "." + profile;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LogScope)) {
            return false;
        }
        LogScope other = (LogScope) o;
        return module.equals(other.module) && profile.equals(other.profile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(module, profile);
    }

    @Override
    public String toString() {
        return qualifiedName();
    }
}
