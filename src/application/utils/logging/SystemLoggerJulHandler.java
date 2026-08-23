package application.utils.logging;

import application.utils.logging.event.LogEvent;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * JUL {@link Handler} that bridges all SLF4J/JUL log events into the GUI
 * subscriber system.
 * <p>
 * <b>Routing:</b>
 * <ol>
 *   <li><b>Always</b> dispatches to {@link SystemLogger} (System Console tab)</li>
 *   <li><b>Also</b> dispatches to the per-profile {@link ProfileLogger} if
 *       {@link NodeLogContext#current()} returns a profile name that has a
 *       registered logger in {@link NodeLoggerRegistry}</li>
 * </ol>
 * <p>
 * <b>Install:</b> Call {@link #install()} once at application startup (e.g. in
 * the Launcher). The handler is added to the JUL root logger and captures all
 * SLF4J→JUL log events (via slf4j-jdk14 binding).
 * </p>
 * <p>
 * <b>No console duplication:</b> This handler does NOT write to System.out.
 * The default JUL ConsoleHandler remains in place for the OS terminal.
 * </p>
 *
 * @see SystemLogger
 * @see NodeLogContext
 * @see NodeLoggerRegistry
 */
public final class SystemLoggerJulHandler extends Handler {

    private static volatile SystemLoggerJulHandler instance;
    private static final Object LOCK = new Object();

    private SystemLoggerJulHandler() {
        setLevel(Level.ALL);
    }

    /**
     * Returns the singleton handler instance.
     */
    public static SystemLoggerJulHandler getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new SystemLoggerJulHandler();
                }
            }
        }
        return instance;
    }

    /**
     * Installs this handler on the JUL root logger (idempotent).
     * Call this once at application startup before any logging occurs.
     */
    public static void install() {
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        // Idempotent: only add if not already present
        synchronized (LOCK) {
            for (Handler h : root.getHandlers()) {
                if (h instanceof SystemLoggerJulHandler) {
                    return; // already installed
                }
            }
        }
        root.addHandler(getInstance());
    }

    /**
     * Removes this handler from the JUL root logger (for testing).
     */
    public static void uninstall() {
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        root.removeHandler(getInstance());
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null) {
            return;
        }
        try {
            LogEvent event = LogEvent.from(record);
            // Set the profile name from ThreadLocal context (if active)
            String profile = NodeLogContext.current();
            if (profile != null) {
                event = withProfile(event, profile);
            }

            // 1. Always dispatch to SystemLogger (System Console)
            SystemLogger.getInstance().dispatch(event);

            // 2. Also dispatch to per-node ProfileLogger if context is active
            if (profile != null) {
                ProfileLogger nodeLogger = NodeLoggerRegistry.get(profile);
                if (nodeLogger != null && !nodeLogger.isClosed()) {
                    nodeLogger.dispatch(event);
                }
            }
        } catch (Exception e) {
            // Never let logging errors crash the application
            System.err.println("[SystemLoggerJulHandler] Error: " + e.getMessage());
        }
    }

    /**
     * Creates a copy of the event with the profile name set.
     * LogEvent is immutable, so we rebuild it with the profile added.
     */
    private static LogEvent withProfile(LogEvent source, String profile) {
        LogEvent.Builder builder = new LogEvent.Builder()
                .timestamp(source.getTimestamp())
                .level(source.getLevel())
                .loggerName(source.getLoggerName())
                .message(source.getMessage())
                .threadName(source.getThreadName())
                .profileName(profile);

        if (source.getSourceClassName() != null) {
            builder.sourceClassName(source.getSourceClassName());
        }
        if (source.getSourceMethodName() != null) {
            builder.sourceMethodName(source.getSourceMethodName());
        }
        if (source.getThrowable() != null) {
            builder.throwable(source.getThrowable());
        }
        if (source.getParameters() != null) {
            builder.parameters(source.getParameters());
        }
        return builder.build();
    }

    @Override
    public void flush() {
        // No buffering — no-op
    }

    @Override
    public void close() {
        // No resources to release — no-op
    }
}