package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;

/**
 * Centralized logger interface used by all modules and profiles.
 * <p>
 * Provides structured logging with subscriber-based event distribution.
 * Each module (node, database, system) creates its own logger instances:
 * </p>
 * <ul>
 *   <li><b>SystemLogger</b> — singleton that captures ALL application logs (like terminal output)</li>
 *   <li><b>ProfileLogger</b> — per-profile instance for module-specific logging</li>
 * </ul>
 * <p>
 * <h3>Usage Pattern</h3>
 * <pre>{@code
 * // In NodeProfile constructor:
 * this.logger = new ProfileLogger("node", profileName);
 *
 * // In Services (constructor injection):
 * public AccountService(ModuleLogger logger, ...) {
 *     this.logger = logger;
 * }
 *
 * // Logging:
 * logger.info("Account loaded: " + accountId);
 * }</pre>
 * </p>
 * <p>
 * <h3>Thread Safety</h3>
 * All logging methods are thread-safe. Subscribers may be invoked from any thread.
 * UI subscribers must dispatch work to the EDT internally.
 * </p>
 *
 * @see SystemLogger
 * @see ProfileLogger
 * @see LogSubscriber
 * @see LogEvent
 */
public interface ModuleLogger {

    // ── Identity ────────────────────────────────────────────────────────

    /**
     * @return the unique name of this logger instance
     */
    String getName();

    /**
     * @return true if this logger has been closed
     */
    boolean isClosed();

    // ── Level Control ───────────────────────────────────────────────────

    /**
     * @return the minimum log level for this logger
     */
    LogLevel getLogLevel();

    /**
     * Sets the minimum log level. Only events at or above this level are dispatched.
     *
     * @param level the new minimum level (never null)
     */
    void setLogLevel(LogLevel level);

    // ── Logging Methods ─────────────────────────────────────────────────

    /**
     * Logs a TRACE level message.
     */
    void trace(String message);

    /**
     * Logs a DEBUG level message.
     */
    void debug(String message);

    /**
     * Logs an INFO level message.
     */
    void info(String message);

    /**
     * Logs a WARN level message.
     */
    void warn(String message);

    /**
     * Logs a WARN level message with an optional cause.
     */
    void warn(String message, Throwable cause);

    /**
     * Logs an ERROR level message.
     */
    void error(String message);

    /**
     * Logs an ERROR level message with a cause.
     */
    void error(String message, Throwable cause);

    /**
     * Logs a message at the specified level.
     * Useful for dynamic logging where the level is determined at runtime.
     *
     * @param level   the log level (never null)
     * @param message the message to log
     */
    void log(LogLevel level, String message);

    /**
     * Logs a message at the specified level with an optional cause.
     *
     * @param level   the log level (never null)
     * @param message the message to log
     * @param cause   the throwable cause, or null
     */
    void log(LogLevel level, String message, Throwable cause);

    // ── Subscriber Management ───────────────────────────────────────────

    /**
     * Adds a subscriber to receive log events from this logger.
     *
     * @param subscriber the subscriber to add (never null)
     * @throws NullPointerException if subscriber is null
     */
    void addSubscriber(LogSubscriber subscriber);

    /**
     * Removes a subscriber. The subscriber's {@link LogSubscriber#dispose()} is called.
     *
     * @param subscriber the subscriber to remove
     * @return true if the subscriber was found and removed
     */
    boolean removeSubscriber(LogSubscriber subscriber);

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Closes this logger: removes all subscribers and marks as closed.
     * After closing, all logging methods become no-ops (silently ignored).
     */
    void close();
}