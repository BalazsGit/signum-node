package application.utils.logging.event;

/**
 * Strategy interface for filtering log events before they reach subscribers.
 * <p>
 * A filter evaluates a single {@link LogEvent} and returns {@code true} if the event
 * should be delivered to the subscriber, or {@code false} to suppress it.
 * Filters can be combined using AND/OR logic via composition patterns.
 * </p>
 * <p>
 * <h3>Built-in Filter Types (Phase 3)</h3>
 * <ul>
 *   <li>{@code ProfileFilter} - Include/exclude events by profile name</li>
 *   <li>{@code ModuleFilter} - Include/exclude events by module ID</li>
 *   <li>{@code LevelFilter} - Include/exclude events by minimum log level</li>
 *   <li>{@code TextSearchFilter} - Include/exclude events matching a regex pattern</li>
 *   <li>{@code CompositeFilter} - AND/OR combination of multiple filters</li>
 * </ul>
 * </p>
 *
 * @see LogEvent
 * @see LogSubscriber
 */
public interface LogFilter {

    /**
     * Evaluates whether the given log event should be delivered to the subscriber.
     *
     * @param event the log event to evaluate (never null)
     * @return {@code true} if the event matches this filter, {@code false} to suppress it
     */
    boolean matches(LogEvent event);
}