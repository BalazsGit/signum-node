package application.utils.logging.event;

/**
 * Observer interface for receiving log events from a {@link application.utils.logging.ProfileLogContext}.
 * <p>
 * Implementations receive filtered log events dispatched by the routing system.
 * Each subscriber may optionally provide a {@link LogFilter} to control which
 * events are delivered. The filter is evaluated by the dispatcher before calling
 * {@link #onLogEvent(LogEvent)}.
 * </p>
 * <p>
 * <h3>Thread Safety</h3>
 * Subscribers may be invoked from any thread (typically the thread that produced
 * the original log). UI subscribers must dispatch work to the EDT internally
 * (e.g., via {@link javax.swing.SwingUtilities#invokeLater(Runnable)} or a batcher).
 * </p>
 * <p>
 * <h3>Lifecycle</h3>
 * When a subscriber is no longer needed, call {@link #dispose()} to release resources.
 * The owning {@code ProfileLogContext} is responsible for removing disposed subscribers.
 * </p>
 *
 * @see LogEvent
 * @see LogFilter
 */
public interface LogSubscriber {

    /**
     * Called when a log event matches this subscriber's filter (or when no filter is set).
     *
     * @param event the log event to process (never null)
     */
    void onLogEvent(LogEvent event);

    /**
     * Returns the optional filter for this subscriber.
     * <p>
     * If {@code null}, all events are delivered without filtering.
     * If non-null, only events for which {@link LogFilter#matches(LogEvent)} returns true
     * are delivered to {@link #onLogEvent(LogEvent)}.
     * </p>
     *
     * @return the filter, or {@code null} to accept all events
     */
    LogFilter getFilter();

    /**
     * Releases any resources held by this subscriber.
     * <p>
     * Called automatically when the subscriber is removed from its context,
     * or explicitly by the owner during cleanup. After calling dispose(),
     * no further {@code onLogEvent()} calls will be made.
     * </p>
     */
    default void dispose() {
        // No-op default implementation
    }
}