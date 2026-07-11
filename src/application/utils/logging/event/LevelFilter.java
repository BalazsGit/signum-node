package application.utils.logging.event;

import java.util.Set;
import java.util.HashSet;

/**
 * Filter that includes or excludes log events based on their {@link LogLevel}.
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>Inclusion mode (default):</b> Only events matching the specified levels pass through.</li>
 *   <li><b>Minimum level mode:</b> Only events at or above a minimum severity pass through.</li>
 * </ul>
 * </p>
 * <p>
 * Thread-safe: Immutable after construction.
 * </p>
 *
 * @example
 * <pre>{@code
 * // Only show WARN and ERROR
 * LogFilter filter = LevelFilter.including(LogLevel.WARN, LogLevel.ERROR);
 *
 * // Show everything at INFO or higher (INFO, WARN, ERROR)
 * LogFilter filter = LevelFilter.atLeast(LogLevel.INFO);
 *
 * // Exclude DEBUG-level noise
 * LogFilter filter = LevelFilter.excluding(LogLevel.DEBUG, LogLevel.TRACE);
 * }</pre>
 *
 * @see LogLevel
 * @see LogFilter
 */
public final class LevelFilter implements LogFilter {

    private final Set<LogLevel> levels;
    private final boolean includeMode;

    private LevelFilter(Set<LogLevel> levels, boolean includeMode) {
        this.levels = Set.copyOf(levels);
        this.includeMode = includeMode;
    }

    /**
     * Creates a filter that only allows events with the specified log levels.
     *
     * @param levels the levels to include (at least one required)
     * @return a new LevelFilter in inclusion mode
     * @throws NullPointerException if levels is null or empty
     */
    public static LevelFilter including(LogLevel... levels) {
        if (levels == null || levels.length == 0) {
            throw new IllegalArgumentException("At least one log level must be specified");
        }
        return new LevelFilter(new HashSet<>(Set.of(levels)), true);
    }

    /**
     * Creates a filter that blocks events with the specified log levels,
     * allowing all other levels through.
     *
     * @param levels the levels to exclude (at least one required)
     * @return a new LevelFilter in exclusion mode
     * @throws NullPointerException if levels is null or empty
     */
    public static LevelFilter excluding(LogLevel... levels) {
        if (levels == null || levels.length == 0) {
            throw new IllegalArgumentException("At least one log level must be specified");
        }
        return new LevelFilter(new HashSet<>(Set.of(levels)), false);
    }

    /**
     * Creates a filter that only allows events at or above the specified minimum severity.
     * <p>
     * Severity order: TRACE < DEBUG < INFO < WARN < ERROR
     * </p>
     *
     * @param minimumLevel the minimum severity to allow
     * @return a new LevelFilter that passes events >= minimumLevel
     * @throws NullPointerException if minimumLevel is null
     */
    public static LevelFilter atLeast(LogLevel minimumLevel) {
        if (minimumLevel == null) {
            throw new NullPointerException("Minimum level must not be null");
        }
        Set<LogLevel> allowed = new HashSet<>();
        for (LogLevel level : LogLevel.values()) {
            if (level.getSeverity() >= minimumLevel.getSeverity()) {
                allowed.add(level);
            }
        }
        return new LevelFilter(allowed, true);
    }

    @Override
    public boolean matches(LogEvent event) {
        if (event == null) {
            return false;
        }
        boolean contained = levels.contains(event.getLevel());
        return includeMode ? contained : !contained;
    }

    @Override
    public String toString() {
        return "LevelFilter{" +
                (includeMode ? "include" : "exclude") + "=" + levels +
                '}';
    }
}