package application.utils.logging.event;

import java.util.Set;
import java.util.HashSet;
import java.util.Objects;

/**
 * Filter that includes or excludes log events based on their profile name.
 * <p>
 * The profile name is extracted from the SLF4J MDC context via {@link ProfileThreadContext}
 * and stored in the {@link LogEvent}. This filter allows SystemConsole aggregators
 * to show/hide logs from specific node profiles (e.g., "mainnet-prune", "testnet").
 * </p>
 * <p>
 * Thread-safe: Immutable after construction.
 * </p>
 *
 * @example
 * <pre>{@code
 * // Only show logs from mainnet-prune profile
 * LogFilter filter = ProfileFilter.including("mainnet-prune");
 *
 * // Show multiple profiles
 * LogFilter filter = ProfileFilter.including("mainnet-prune", "testnet-prune");
 *
 * // Hide bootstrap/system logs (null profile name)
 * LogFilter filter = ProfileFilter.excludingNullProfile();
 * }</pre>
 *
 * @see LogEvent#getProfileName()
 * @see LogFilter
 */
public final class ProfileFilter implements LogFilter {

    private final Set<String> profiles;
    private final boolean includeMode;
    private final boolean matchNull;

    private ProfileFilter(Set<String> profiles, boolean includeMode, boolean matchNull) {
        this.profiles = Set.copyOf(profiles);
        this.includeMode = includeMode;
        this.matchNull = matchNull;
    }

    /**
     * Creates a filter that only allows events from the specified profile names.
     *
     * @param profiles the profile names to include (at least one required)
     * @return a new ProfileFilter in inclusion mode
     * @throws NullPointerException if profiles is null or empty
     */
    public static ProfileFilter including(String... profiles) {
        if (profiles == null || profiles.length == 0) {
            throw new IllegalArgumentException("At least one profile name must be specified");
        }
        Set<String> set = new HashSet<>();
        for (String p : profiles) {
            Objects.requireNonNull(p, "Profile name must not be null");
            set.add(p);
        }
        return new ProfileFilter(set, true, false);
    }

    /**
     * Creates a filter that blocks events from the specified profile names,
     * allowing all other profiles through.
     *
     * @param profiles the profile names to exclude (at least one required)
     * @return a new ProfileFilter in exclusion mode
     * @throws NullPointerException if profiles is null or empty
     */
    public static ProfileFilter excluding(String... profiles) {
        if (profiles == null || profiles.length == 0) {
            throw new IllegalArgumentException("At least one profile name must be specified");
        }
        Set<String> set = new HashSet<>();
        for (String p : profiles) {
            Objects.requireNonNull(p, "Profile name must not be null");
            set.add(p);
        }
        return new ProfileFilter(set, false, false);
    }

    /**
     * Creates a filter that only allows events with a non-null profile name.
     * Useful for hiding bootstrap/system-level logs in the SystemConsole.
     *
     * @return a LogFilter that passes only events with an assigned profile
     */
    public static LogFilter excludingNullProfile() {
        return event -> event != null && event.getProfileName() != null;
    }

    /**
     * Creates a filter that includes events from the specified profiles AND
     * events with no profile assigned (bootstrap/system logs).
     *
     * @param profiles the profile names to include
     * @return a new ProfileFilter that also passes null-profile events
     */
    public static ProfileFilter includingWithBootstrap(String... profiles) {
        if (profiles == null || profiles.length == 0) {
            throw new IllegalArgumentException("At least one profile name must be specified");
        }
        Set<String> set = new HashSet<>();
        for (String p : profiles) {
            Objects.requireNonNull(p, "Profile name must not be null");
            set.add(p);
        }
        return new ProfileFilter(set, true, true);
    }

    @Override
    public boolean matches(LogEvent event) {
        if (event == null) {
            return false;
        }
        String eventProfile = event.getProfileName();

        // Handle null profile special case
        if (eventProfile == null) {
            return includeMode ? matchNull : !matchNull;
        }

        // Match on the bare profile name OR the qualified (module.profile) name,
        // so both "mainnet" and "node.mainnet" address the same profile.
        boolean contained = profiles.contains(eventProfile);
        String eventModule = event.getModule();
        if (!contained && eventModule != null && !eventModule.isEmpty()) {
            contained = profiles.contains(eventModule + "." + eventProfile);
        }
        return includeMode ? contained : !contained;
    }

    @Override
    public String toString() {
        return "ProfileFilter{" +
                (includeMode ? "include" : "exclude") + "=" + profiles +
                ", matchNull=" + matchNull +
                '}';
    }
}