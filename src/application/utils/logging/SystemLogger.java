package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;

import java.util.concurrent.atomic.AtomicInteger;

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
 * <h3>Early-startup ring buffer</h3>
 * The System Console tab is created only when the GUI starts — minutes into a normal
 * boot. To make the startup lines visible there (the "incomplete console" symptom), the
 * last {@value #BUFFER_SIZE} events are kept in a ring buffer and replayed to every
 * subscriber when it attaches. The buffer is bounded, so memory stays flat.
 * </p>
 * <p>
 * <h3>Thread Safety</h3>
 * Fully thread-safe via {@link LoggerImpl}'s CopyOnWriteArrayList for subscribers and
 * atomic buffer indices.
 * </p>
 *
 * @see ProfileLogger
 * @see ModuleLogger
 */
public final class SystemLogger extends LoggerImpl {

    /** Number of most-recent events retained for late-attaching GUI consoles. */
    static final int BUFFER_SIZE = 500;

    private static volatile SystemLogger instance;
    private static final Object LOCK = new Object();

    private final LogEvent[] buffer = new LogEvent[BUFFER_SIZE];
    private final AtomicInteger bufferHead = new AtomicInteger();
    private final AtomicInteger bufferCount = new AtomicInteger();

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

    // ------------------------------------------------------------------
    // Early-startup ring buffer (bounded) + replay on attach
    // ------------------------------------------------------------------

    @Override
    protected void dispatch(LogEvent event) {
        // Retain the event first, so a console attaching later still sees it.
        buffer[bufferHead.getAndIncrement() % BUFFER_SIZE] = event;
        bufferCount.incrementAndGet();
        super.dispatch(event);
    }

    @Override
    public void addSubscriber(LogSubscriber subscriber) {
        super.addSubscriber(subscriber);
        if (isClosed()) {
            return;
        }
        // Replay the retained startup events to the NEW subscriber only (existing
        // subscribers already received those events live — no duplication).
        int total = Math.min(bufferCount.get(), BUFFER_SIZE);
        int head = bufferHead.get();
        LogFilter filter = subscriber.getFilter();
        for (int i = 0; i < total; i++) {
            int idx = Math.floorMod(head - total + i, BUFFER_SIZE);
            LogEvent event = buffer[idx];
            if (event == null) {
                continue;
            }
            if (filter == null || filter.matches(event)) {
                try {
                    subscriber.onLogEvent(event);
                } catch (Exception e) {
                    // a replay failure must not break the live subscription
                }
            }
        }
    }
}