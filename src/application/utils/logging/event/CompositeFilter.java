package application.utils.logging.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Composite filter that combines multiple {@link LogFilter} instances using
 * AND or OR logic.
 * <p>
 * This implements the Composite pattern from GoF, allowing arbitrary nesting
 * of filter combinations. Useful for building complex filter expressions
 * in the SystemConsole UI (e.g., "WARN level AND node module AND contains 'error'").
 * </p>
 * <p>
 * <h3>Combination Modes</h3>
 * <ul>
 *   <li><b>AND:</b> All child filters must match for the event to pass.</li>
 *   <li><b>OR:</b> At least one child filter must match for the event to pass.</li>
 *   <li><b>NAND (NOT AND):</b> Event passes if NOT all children match together.</li>
 *   <li><b>NOR (NOT OR):</b> Event passes only if NO children match.</li>
 * </ul>
 * </p>
 * <p>
 * Thread-safe: Immutable after construction. Child filters are stored in an
 * unmodifiable list copy.
 * </p>
 *
 * @example
 * <pre>{@code
 * // AND: WARN or ERROR level, AND from node module
 * LogFilter combined = CompositeFilter.and(
 *     LevelFilter.atLeast(LogLevel.WARN),
 *     ModuleFilter.including("node")
 * );
 *
 * // OR: From mainnet OR testnet profile
 * LogFilter eitherProfile = CompositeFilter.or(
 *     ProfileFilter.including("mainnet-prune"),
 *     ProfileFilter.including("testnet-prune")
 * );
 *
 * // Nested: (WARN+ AND node) OR (ERROR AND database)
 * LogFilter complex = CompositeFilter.or(
 *     CompositeFilter.and(LevelFilter.atLeast(LogLevel.WARN), ModuleFilter.including("node")),
 *     CompositeFilter.and(LevelFilter.including(LogLevel.ERROR), ModuleFilter.including("database"))
 * );
 * }</pre>
 *
 * @see LogFilter
 * @see LevelFilter
 * @see ProfileFilter
 * @see ModuleFilter
 * @see TextSearchFilter
 */
public final class CompositeFilter implements LogFilter {

    private final List<LogFilter> filters;
    private final CombinationMode mode;

    /**
     * Defines the logic used to combine child filter results.
     */
    public enum CombinationMode {
        /** All children must match (logical AND). */
        AND,

        /** At least one child must match (logical OR). */
        OR,

        /** Event passes if NOT all children match together (logical NAND). */
        NAND,

        /** Event passes only if NO children match (logical NOR). */
        NOR
    }

    private CompositeFilter(List<LogFilter> filters, CombinationMode mode) {
        this.filters = Collections.unmodifiableList(new ArrayList<>(filters));
        this.mode = mode;
    }

    /**
     * Creates an AND composite: all child filters must match.
     *
     * @param filters the filters to combine (at least two required)
     * @return a new CompositeFilter with AND logic
     * @throws IllegalArgumentException if fewer than 2 filters provided
     */
    public static CompositeFilter and(LogFilter... filters) {
        validateFilters(filters, 2);
        return new CompositeFilter(List.of(filters), CombinationMode.AND);
    }

    /**
     * Creates an AND composite from a list.
     *
     * @param filters the filters to combine (at least two required)
     * @return a new CompositeFilter with AND logic
     */
    public static CompositeFilter and(List<LogFilter> filters) {
        if (filters == null || filters.size() < 2) {
            throw new IllegalArgumentException("At least 2 filters are required for AND composition");
        }
        return new CompositeFilter(filters, CombinationMode.AND);
    }

    /**
     * Creates an OR composite: at least one child filter must match.
     *
     * @param filters the filters to combine (at least two required)
     * @return a new CompositeFilter with OR logic
     * @throws IllegalArgumentException if fewer than 2 filters provided
     */
    public static CompositeFilter or(LogFilter... filters) {
        validateFilters(filters, 2);
        return new CompositeFilter(List.of(filters), CombinationMode.OR);
    }

    /**
     * Creates an OR composite from a list.
     *
     * @param filters the filters to combine (at least two required)
     * @return a new CompositeFilter with OR logic
     */
    public static CompositeFilter or(List<LogFilter> filters) {
        if (filters == null || filters.size() < 2) {
            throw new IllegalArgumentException("At least 2 filters are required for OR composition");
        }
        return new CompositeFilter(filters, CombinationMode.OR);
    }

    /**
     * Creates a NAND composite: event passes if NOT all children match together.
     * Equivalent to {@code !and(...)} .
     */
    public static CompositeFilter nand(LogFilter... filters) {
        validateFilters(filters, 2);
        return new CompositeFilter(List.of(filters), CombinationMode.NAND);
    }

    /**
     * Creates a NOR composite: event passes only if NO children match.
     * Equivalent to {@code !or(...)} .
     */
    public static CompositeFilter nor(LogFilter... filters) {
        validateFilters(filters, 2);
        return new CompositeFilter(List.of(filters), CombinationMode.NOR);
    }

    /**
     * Negates the result of a single filter.
     * <p>
     * Useful for inverting any filter: {@code not(LevelFilter.atLeast(WARN))}
     * will only pass events below WARN level.
     * </p>
     *
     * @param filter the filter to negate
     * @return a LogFilter that returns the opposite of the input filter
     */
    public static LogFilter not(LogFilter filter) {
        Objects.requireNonNull(filter, "Filter must not be null");
        return event -> !filter.matches(event);
    }

    @Override
    public boolean matches(LogEvent event) {
        if (event == null || filters.isEmpty()) {
            return false;
        }

        switch (mode) {
            case AND:
                for (LogFilter filter : filters) {
                    if (!filter.matches(event)) {
                        return false; // Short-circuit on first failure
                    }
                }
                return true;

            case OR:
                for (LogFilter filter : filters) {
                    if (filter.matches(event)) {
                        return true; // Short-circuit on first success
                    }
                }
                return false;

            case NAND:
                // All must match, then invert
                boolean allMatch = true;
                for (LogFilter filter : filters) {
                    if (!filter.matches(event)) {
                        allMatch = false;
                        break;
                    }
                }
                return !allMatch;

            case NOR:
                // None must match
                for (LogFilter filter : filters) {
                    if (filter.matches(event)) {
                        return false; // Any match fails NOR
                    }
                }
                return true;

            default:
                return false;
        }
    }

    /** @return the child filters (unmodifiable view) */
    public List<LogFilter> getFilters() {
        return filters;
    }

    /** @return the combination mode (AND/OR/NAND/NOR) */
    public CombinationMode getMode() {
        return mode;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("CompositeFilter{").append(mode).append('(');
        for (int i = 0; i < filters.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(filters.get(i));
        }
        sb.append(')');
        sb.append('}');
        return sb.toString();
    }

    private static void validateFilters(LogFilter[] filters, int minSize) {
        if (filters == null || filters.length < minSize) {
            throw new IllegalArgumentException("At least " + minSize + " filters are required");
        }
        for (LogFilter filter : filters) {
            Objects.requireNonNull(filter, "Child filter must not be null");
        }
    }
}