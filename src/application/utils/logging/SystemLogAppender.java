package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogLevel;

/**
 * Direct SLF4J-to-SystemLogger bridge that dispatches log events to {@link SystemLogger},
 * completely bypassing the JUL bridge (slf4j-jdk14) and MDC-based routing.
 * 
 * <p>This replaces the legacy flow:</p>
 * <pre>
 *   SLF4J → slf4j-jdk14 → JUL LogManager → ProfileLogRouter → MDC routing → GUI
 * </pre>
 * <p>With the new direct flow:</p>
 * <pre>
 *   SLF4J (via this appender) → SystemLogger → GUI subscribers
 * </pre>
 * 
 * <p><b>Thread-safe:</b> Yes. Uses SystemLogger's CopyOnWriteArrayList internally.</p>
 * <p><b>No MDC dependency:</b> All log events are dispatched directly without thread context.</p>
 * 
 * <h3>Usage</h3>
 * <p>Call the static methods directly to log, bypassing SLF4J entirely:</p>
 * <pre>{@code
 * SystemLogAppender.info("MyLogger", "Application started");
 * SystemLogAppender.error("MyLogger", "Failed to connect", exception);
 * }</pre>
 * 
 * @see SystemLogger
 * @see ModuleLogger
 */
public final class SystemLogAppender {

    private SystemLogAppender() {
        // Utility class - no instantiation
    }

    // -------------------------- Convenience Logging Methods --------------------------

    /** Logs a TRACE level message directly to SystemLogger. */
    public static void trace(String loggerName, String message) {
        dispatch(LogLevel.TRACE, loggerName, message, null);
    }

    /** Logs a TRACE level message with parameters directly to SystemLogger. */
    public static void trace(String loggerName, String format, Object... arguments) {
        dispatch(LogLevel.TRACE, loggerName, format(format, arguments), null);
    }

    /** Logs a TRACE level message with throwable directly to SystemLogger. */
    public static void trace(String loggerName, String message, Throwable t) {
        dispatch(LogLevel.TRACE, loggerName, message, t);
    }

    /** Logs a DEBUG level message directly to SystemLogger. */
    public static void debug(String loggerName, String message) {
        dispatch(LogLevel.DEBUG, loggerName, message, null);
    }

    /** Logs a DEBUG level message with parameters directly to SystemLogger. */
    public static void debug(String loggerName, String format, Object... arguments) {
        dispatch(LogLevel.DEBUG, loggerName, format(format, arguments), null);
    }

    /** Logs a DEBUG level message with throwable directly to SystemLogger. */
    public static void debug(String loggerName, String message, Throwable t) {
        dispatch(LogLevel.DEBUG, loggerName, message, t);
    }

    /** Logs an INFO level message directly to SystemLogger. */
    public static void info(String loggerName, String message) {
        dispatch(LogLevel.INFO, loggerName, message, null);
    }

    /** Logs an INFO level message with parameters directly to SystemLogger. */
    public static void info(String loggerName, String format, Object... arguments) {
        dispatch(LogLevel.INFO, loggerName, format(format, arguments), null);
    }

    /** Logs an INFO level message with throwable directly to SystemLogger. */
    public static void info(String loggerName, String message, Throwable t) {
        dispatch(LogLevel.INFO, loggerName, message, t);
    }

    /** Logs a WARN level message directly to SystemLogger. */
    public static void warn(String loggerName, String message) {
        dispatch(LogLevel.WARN, loggerName, message, null);
    }

    /** Logs a WARN level message with parameters directly to SystemLogger. */
    public static void warn(String loggerName, String format, Object... arguments) {
        dispatch(LogLevel.WARN, loggerName, format(format, arguments), null);
    }

    /** Logs a WARN level message with throwable directly to SystemLogger. */
    public static void warn(String loggerName, String message, Throwable t) {
        dispatch(LogLevel.WARN, loggerName, message, t);
    }

    /** Logs an ERROR level message directly to SystemLogger. */
    public static void error(String loggerName, String message) {
        dispatch(LogLevel.ERROR, loggerName, message, null);
    }

    /** Logs an ERROR level message with parameters directly to SystemLogger. */
    public static void error(String loggerName, String format, Object... arguments) {
        dispatch(LogLevel.ERROR, loggerName, format(format, arguments), null);
    }

    /** Logs an ERROR level message with throwable directly to SystemLogger. */
    public static void error(String loggerName, String message, Throwable t) {
        dispatch(LogLevel.ERROR, loggerName, message, t);
    }

    // -------------------------- Core Dispatch --------------------------

    /**
     * Core dispatch method that creates and sends a LogEvent to SystemLogger.
     */
    private static void dispatch(LogLevel level, String loggerName, String message, Throwable throwable) {
        if (message == null) {
            return;
        }

        SystemLogger systemLogger = SystemLogger.getInstance();

        // Build LogEvent using the Builder pattern
        LogEvent.Builder builder = new LogEvent.Builder()
                .timestamp(System.currentTimeMillis())
                .level(level)
                .loggerName(loggerName)
                .message(message)
                .threadName(Thread.currentThread().getName());

        if (throwable != null) {
            builder.throwable(throwable);
        }

        LogEvent logEvent = builder.build();
        systemLogger.dispatch(logEvent);
    }

    /**
     * Formats a parameterized message using {} placeholder substitution.
     * Compatible with SLF4J's parameterized message format.
     */
    private static String format(String format, Object... arguments) {
        if (format == null) {
            return null;
        }
        if (arguments == null || arguments.length == 0) {
            return format;
        }

        StringBuilder sb = new StringBuilder(format.length() + 32);
        int argIndex = 0;
        String remaining = format;

        while (argIndex < arguments.length) {
            int openIdx = remaining.indexOf("{}");
            if (openIdx == -1) {
                break;
            }

            // Append text before placeholder
            sb.append(remaining, 0, openIdx);

            // Substitute argument
            Object arg = arguments[argIndex++];
            if (arg == null) {
                sb.append("null");
            } else {
                sb.append(arg.toString());
            }

            remaining = remaining.substring(openIdx + 2);
        }

        // Append remaining text
        sb.append(remaining);
        return sb.toString();
    }
}