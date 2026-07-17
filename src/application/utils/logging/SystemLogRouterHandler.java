package application.utils.logging;

import application.utils.logging.event.LogEvent;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;

/**
 * JUL (java.util.logging) Handler that intercepts all log records from the SLF4J→JUL bridge
 * and dispatches them directly to {@link SystemLogger}, bypassing the legacy MDC-based routing.
 *
 * <p>This is the critical bridge component that connects the SLF4J→JUL→GUI pipeline:
 * <pre>
 *   SLF4J Logger.info() → slf4j-jdk14 bridge → JUL LogManager
 *                                              ↓
 *                                       Root Logger handlers
 *                                              ↓
 *                                  SystemLogRouterHandler
 *                                        publish(LogRecord) {
 *                                          event = LogEvent.from(record);
 *                                          SystemLogger.getInstance().dispatch(event);
 *                                        }
 * </pre>
 *
 * <p><b>Why this exists:</b> The Signum Node uses SLF4J for logging throughout the codebase.
 * The {@code slf4j-jdk14} dependency bridges SLF4J calls to JUL (java.util.logging). This handler
 * captures those JUL events and routes them to our subscriber-based logging system so they
 * appear in the GUI consoles.</p>
 *
 * <p><b>Thread-safe:</b> Handler is thread-safe by design. SystemLogger uses CopyOnWriteArrayList.</p>
 *
 * @see SystemLogger
 * @see LogEvent
 */
public final class SystemLogRouterHandler extends Handler {

    private static volatile SystemLogRouterHandler instance;
    private static final Object LOCK = new Object();

    /**
     * Returns the singleton handler instance (lazy initialization, thread-safe).
     */
    public static SystemLogRouterHandler getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new SystemLogRouterHandler();
                }
            }
        }
        return instance;
    }

    /**
     * Resets the singleton. Intended for testing only.
     */
    public static void resetInstance() {
        synchronized (LOCK) {
            if (instance != null) {
                instance.uninstall();
                instance = null;
            }
        }
    }

    private SystemLogRouterHandler() {
        setLevel(Level.ALL); // Capture all levels
    }

    /**
     * Installs this handler on the root JUL logger. Idempotent.
     */
    public void install() {
        synchronized (LOCK) {
            LogManager manager = LogManager.getLogManager();
            java.util.logging.Logger rootLogger = manager.getLogger("");
            // Check if already installed
            for (Handler h : rootLogger.getHandlers()) {
                if (h == this) {
                    return; // Already installed
                }
            }
            rootLogger.addHandler(this);
        }
    }

    /**
     * Removes this handler from the root JUL logger. Idempotent.
     */
    public void uninstall() {
        synchronized (LOCK) {
            LogManager manager = LogManager.getLogManager();
            java.util.logging.Logger rootLogger = manager.getLogger("");
            rootLogger.removeHandler(this);
        }
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || record.getMessage() == null) {
            return;
        }

        try {
            // Convert JUL LogRecord to our LogEvent
            LogEvent event = LogEvent.from(record);

            // Dispatch directly to SystemLogger - no MDC, no profile routing
            // All GUI subscribers of SystemLogger will receive this event
            SystemLogger.getInstance().dispatch(event);
        } catch (Exception e) {
            // Never let logging failures crash the application
            System.err.println("[SystemLogRouterHandler] Error dispatching log: " + e.getMessage());
        }
    }

    @Override
    public void flush() {
        // No buffering - SystemLogger dispatches synchronously to subscribers
    }

    @Override
    public void close() {
        uninstall();
    }
}