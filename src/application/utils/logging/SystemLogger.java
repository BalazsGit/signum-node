package application.utils.logging;

import application.utils.logging.event.LogLevel;

/**
 * Singleton system logger that captures ALL application logs regardless of module or profile.
 * <p>
 * This logger acts as the GUI equivalent of terminal output — every log message produced
 * by any module flows through the SystemLogger, making it the single source of truth for
 * the System Console display.
 * </p>
 * <p>
 * <h3>Design Rationale</h3>
 * The traditional terminal shows all logs in a flat stream. SystemLogger replicates this
 * behavior in the GUI layer while also providing structured subscriber-based distribution.
 * Module-specific consoles (NodeProfile, DatabaseProfile) use their own {@link ProfileLogger}
 * instances, which can optionally forward events to SystemLogger for unified viewing.
 * </p>
 * <p>
 * <h3>Thread Safety</h3>
 * Fully thread-safe via {@link LoggerImpl}'s CopyOnWriteArrayList for subscribers.
 * </p>
 *
 * @see ProfileLogger
 * @see ModuleLogger
 */
public final class SystemLogger extends LoggerImpl {

    private static volatile SystemLogger instance;
    private static final Object LOCK = new Object();

    /**
     * Returns the singleton SystemLogger instance (lazy initialization, thread-safe).
     *
     * @return the shared SystemLogger instance (never null)
     */
    public static SystemLogger getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new SystemLogger();
                }
            }
        }
        return instance;
    }

    /**
     * Resets the singleton instance. Intended for testing purposes only.
     */
    public static void resetInstance() {
        synchronized (LOCK) {
            if (instance != null) {
                instance.close();
                instance = null;
            }
        }
    }

    private SystemLogger() {
        super("system");
        // System logger captures EVERYTHING — minimum level is TRACE
        setLogLevel(LogLevel.TRACE);
    }

    /**
     * Returns true if the singleton has been initialized.
     */
    public static boolean isInitialized() {
        return instance != null;
    }

    @Override
    public void setLogLevel(LogLevel level) {
        // System logger always captures TRACE+ to mirror terminal behavior.
        // Subscribers can apply their own filters if they want to limit visibility.
        super.setLogLevel(LogLevel.TRACE);
    }
}