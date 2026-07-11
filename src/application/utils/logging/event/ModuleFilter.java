package application.utils.logging.event;

import java.util.Set;
import java.util.HashSet;
import java.util.Objects;

/**
 * Filter that includes or excludes log events based on their module ID.
 * <p>
 * The module ID is typically derived from the logger name (e.g., "signum.node",
 * "signum.database"). This filter allows SystemConsole aggregators to isolate
 * logs from specific modules.
 * </p>
 * <p>
 * Thread-safe: Immutable after construction.
 * </p>
 *
 * @example
 * <pre>{@code
 * // Only show node module logs
 * LogFilter filter = ModuleFilter.including("node");
 *
 * // Show database and network module logs
 * LogFilter filter = ModuleFilter.including("database", "network");
 *
 * // Exclude noisy test framework logs
 * LogFilter filter = ModuleFilter.excluding("org.junit", "org.mockito");
 * }</pre>
 *
 * @see LogEvent#getLoggerName()
 * @see LogFilter
 */
public final class ModuleFilter implements LogFilter {

    private final Set<String> modules;
    private final boolean includeMode;

    private ModuleFilter(Set<String> modules, boolean includeMode) {
        this.modules = Set.copyOf(modules);
        this.includeMode = includeMode;
    }

    /**
     * Creates a filter that only allows events whose logger name contains one of
     * the specified module IDs.
     *
     * @param modules the module identifiers to include (at least one required)
     * @return a new ModuleFilter in inclusion mode
     * @throws IllegalArgumentException if modules is null or empty
     */
    public static ModuleFilter including(String... modules) {
        if (modules == null || modules.length == 0) {
            throw new IllegalArgumentException("At least one module ID must be specified");
        }
        Set<String> set = new HashSet<>();
        for (String m : modules) {
            Objects.requireNonNull(m, "Module ID must not be null");
            set.add(m);
        }
        return new ModuleFilter(set, true);
    }

    /**
     * Creates a filter that blocks events whose logger name contains one of
     * the specified module IDs.
     *
     * @param modules the module identifiers to exclude (at least one required)
     * @return a new ModuleFilter in exclusion mode
     * @throws IllegalArgumentException if modules is null or empty
     */
    public static ModuleFilter excluding(String... modules) {
        if (modules == null || modules.length == 0) {
            throw new IllegalArgumentException("At least one module ID must be specified");
        }
        Set<String> set = new HashSet<>();
        for (String m : modules) {
            Objects.requireNonNull(m, "Module ID must not be null");
            set.add(m);
        }
        return new ModuleFilter(set, false);
    }

    @Override
    public boolean matches(LogEvent event) {
        if (event == null) {
            return false;
        }
        String loggerName = event.getLoggerName();
        if (loggerName == null || loggerName.isEmpty()) {
            return includeMode ? false : true;
        }

        boolean found = modules.stream().anyMatch(loggerName::contains);
        return includeMode ? found : !found;
    }

    @Override
    public String toString() {
        return "ModuleFilter{" +
                (includeMode ? "include" : "exclude") + "=" + modules +
                '}';
    }
}