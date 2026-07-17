package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Abstract base implementation of {@link ModuleLogger} providing common subscriber
 * management, level filtering, and event dispatch logic.
 * <p>
 * <h3>Thread Safety</h3>
 * Uses {@link CopyOnWriteArrayList} for subscribers, making it safe for concurrent
 * logging from multiple threads. Subscriber iteration is lock-free.
 * </p>
 * <p>
 * <h3>Extensibility</h3>
 * Subclasses must implement {@link #getName()} and may override dispatch behavior.
 * See {@link SystemLogger} and {@link ProfileLogger} for concrete implementations.
 * </p>
 *
 * @see ModuleLogger
 * @see SystemLogger
 * @see ProfileLogger
 */
public abstract class LoggerImpl implements ModuleLogger {

    protected final String name;
    protected volatile LogLevel minLevel = LogLevel.INFO;
    protected final CopyOnWriteArrayList<LogSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private volatile boolean closed = false;

    /**
     * Creates a new logger with the given name.
     *
     * @param name the unique logger name (never null or empty)
     */
    protected LoggerImpl(String name) {
        this.name = Objects.requireNonNull(name, "Logger name must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Logger name must not be empty");
        }
    }

    // ── Identity ────────────────────────────────────────────────────────

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    // ── Level Control ───────────────────────────────────────────────────

    @Override
    public LogLevel getLogLevel() {
        return minLevel;
    }

    @Override
    public void setLogLevel(LogLevel level) {
        this.minLevel = Objects.requireNonNull(level, "Log level must not be null");
    }

    // ── Logging Methods (convenience) ───────────────────────────────────

    @Override
    public void trace(String message) {
        log(LogLevel.TRACE, message);
    }

    @Override
    public void debug(String message) {
        log(LogLevel.DEBUG, message);
    }

    @Override
    public void info(String message) {
        log(LogLevel.INFO, message);
    }

    @Override
    public void warn(String message) {
        log(LogLevel.WARN, message);
    }

    @Override
    public void warn(String message, Throwable cause) {
        log(LogLevel.WARN, message, cause);
    }

    @Override
    public void error(String message) {
        log(LogLevel.ERROR, message);
    }

    @Override
    public void error(String message, Throwable cause) {
        log(LogLevel.ERROR, message, cause);
    }

    // ── Core Logging ────────────────────────────────────────────────────

    @Override
    public void log(LogLevel level, String message) {
        log(level, message, null);
    }

    @Override
    public void log(LogLevel level, String message, Throwable cause) {
        if (closed || level == null || message == null) {
            return;
        }
        if (level.ordinal() < minLevel.ordinal()) {
            return;
        }
        LogEvent event = new LogEvent.Builder()
                .loggerName(name)
                .level(level)
                .message(message)
                .throwable(cause)
                .build();
        dispatch(event);
    }

    // ── Subscriber Management ───────────────────────────────────────────

    @Override
    public void addSubscriber(LogSubscriber subscriber) {
        if (subscriber == null) {
            throw new NullPointerException("Subscriber must not be null");
        }
        if (closed) {
            return;
        }
        subscribers.add(subscriber);
    }

    @Override
    public boolean removeSubscriber(LogSubscriber subscriber) {
        boolean removed = subscribers.remove(subscriber);
        if (removed) {
            subscriber.dispose();
        }
        return removed;
    }

    /**
     * Returns an unmodifiable snapshot of all registered subscribers.
     */
    public List<LogSubscriber> getSubscribers() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(subscribers));
    }

    // ── Event Dispatch ──────────────────────────────────────────────────

    /**
     * Dispatches a log event to all registered subscribers.
     * <p>
     * Each subscriber's optional {@link application.utils.logging.event.LogFilter} is evaluated
     * before calling {@link LogSubscriber#onLogEvent(LogEvent)}. Exceptions from one
     * subscriber are caught and logged, protecting other subscribers.
     * </p>
     *
     * @param event the log event to dispatch (never null)
     */
    protected void dispatch(LogEvent event) {
        for (LogSubscriber subscriber : subscribers) {
            try {
                var filter = subscriber.getFilter();
                if (filter == null || filter.matches(event)) {
                    subscriber.onLogEvent(event);
                }
            } catch (Exception e) {
                // Protect one subscriber from breaking others
                System.err.println("[" + getClass().getSimpleName() + "] "
                        + "Subscriber error in '" + name + "': " + e.getMessage());
            }
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Override
    public void close() {
        closed = true;
        // Dispose all subscribers
        for (LogSubscriber subscriber : subscribers) {
            try {
                subscriber.dispose();
            } catch (Exception e) {
                System.err.println("[" + getClass().getSimpleName() + "] "
                        + "Dispose error in '" + name + "': " + e.getMessage());
            }
        }
        subscribers.clear();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{"
                + "name='" + name + '\''
                + ", level=" + minLevel
                + ", subscribers=" + subscribers.size()
                + ", closed=" + closed
                + '}';
    }
}