package application.utils.logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded circular buffer for capturing log events before the ProfileLogRouter is installed.
 * <p>
 * This bridge replaces the legacy {@code Signum.BOOTSTRAP_LOGS} static list with a
 * capacity-limited, thread-safe buffer. It stores log lines produced during application
 * startup (bootstrap phase) and flushes them to the appropriate profile context once
 * the router is ready.
 * </p>
 * <p>
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Create instance before any logging occurs</li>
 *   <li>Call {@link #add(String)} for each bootstrap log line (or use {@link #addRaw(java.util.logging.LogRecord)})</li>
 *   <li>After ProfileLogRouter is installed, call {@link #flushToContext(ProfileLogContext)} or
 *       {@link #flushAllToContexts(ProfileLogRouter)}</li>
 *   <li>Call {@link #clear()} to release memory</li>
 * </ol>
 * </p>
 * <p>
 * Thread-safe: Uses synchronized methods + AtomicInteger for the write index.
 * Memory-safe: Bounded capacity prevents unbounded growth during long bootstrap phases.
 * </p>
 */
public final class BootstrapLogBuffer {

    /** Default maximum number of entries stored in the buffer */
    public static final int DEFAULT_CAPACITY = 500;

    private final int capacity;
    private final List<String> entries;
    private final AtomicInteger writeIndex;
    private final AtomicInteger count;

    /**
     * Creates a new buffer with the default capacity (500 entries).
     */
    public BootstrapLogBuffer() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a new buffer with the specified capacity.
     *
     * @param capacity maximum number of entries to store (must be > 0)
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BootstrapLogBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive, got: " + capacity);
        }
        this.capacity = capacity;
        this.entries = Collections.synchronizedList(new ArrayList<>(capacity));
        this.writeIndex = new AtomicInteger(0);
        this.count = new AtomicInteger(0);
    }

    /**
     * Adds a raw log line to the buffer.
     * <p>
     * When the buffer reaches capacity, the oldest entry is overwritten (circular behavior).
     * </p>
     *
     * @param line the log line to store
     */
    public void add(String line) {
        if (line == null) {
            return;
        }
        synchronized (entries) {
            int currentCount = count.get();
            if (currentCount < capacity) {
                // Buffer not full yet, append normally
                entries.add(line);
                count.incrementAndGet();
            } else {
                // Buffer full - overwrite oldest entry in circular fashion
                int index = writeIndex.getAndIncrement() % capacity;
                entries.set(index, line);
            }
        }
    }

    /**
     * Returns all buffered entries as an unmodifiable list.
     * <p>
     * When the buffer is full (circular mode), entries are returned in their original
     * chronological order (oldest first).
     * </p>
     *
     * @return immutable snapshot of buffered entries (never null)
     */
    public List<String> getEntries() {
        synchronized (entries) {
            int currentCount = count.get();
            if (currentCount < capacity) {
                // Not full yet, return as-is
                return Collections.unmodifiableList(new ArrayList<>(entries));
            } else {
                // Full circular buffer - return in chronological order
                int startIdx = writeIndex.get() % capacity;
                List<String> ordered = new ArrayList<>(currentCount);
                for (int i = 0; i < capacity; i++) {
                    int idx = (startIdx + i) % capacity;
                    ordered.add(entries.get(idx));
                }
                return Collections.unmodifiableList(ordered);
            }
        }
    }

    /**
     * Returns the number of entries currently in the buffer.
     */
    public int size() {
        return count.get();
    }

    /**
     * Returns the maximum capacity of this buffer.
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Returns true if the buffer has reached capacity.
     */
    public boolean isFull() {
        return count.get() >= capacity;
    }

    /**
     * Flushes all buffered entries to the given ProfileLogContext as raw text events.
     * <p>
     * Each buffered line is dispatched as a simple text log event. After flushing,
     * consider calling {@link #clear()} if the entries should be discarded.
     * </p>
     *
     * @param context the profile-specific context to receive flushed entries
     */
    public void flushToContext(ProfileLogContext context) {
        if (context == null) {
            throw new IllegalArgumentException("ProfileLogContext must not be null");
        }
        List<String> snapshot = getEntries();
        for (String line : snapshot) {
            // Dispatch raw text as a simple log event to the subscriber
            context.dispatchText(line);
        }
    }

    /**
     * Flushes all buffered entries to every registered context in the given router.
     * Used during startup to distribute bootstrap logs to all active profiles.
     *
     * @param router the global router containing all profile contexts
     */
    public void flushAllToContexts(ProfileLogRouter router) {
        if (router == null) {
            throw new IllegalArgumentException("ProfileLogRouter must not be null");
        }
        List<String> snapshot = getEntries();
        for (java.util.Map.Entry<String, ProfileLogContext> entry : router.getAllContexts().entrySet()) {
            ProfileLogContext context = entry.getValue();
            for (String line : snapshot) {
                context.dispatchText(line);
            }
        }
    }

    /**
     * Clears all buffered entries and resets the buffer state.
     */
    public void clear() {
        synchronized (entries) {
            entries.clear();
            writeIndex.set(0);
            count.set(0);
        }
    }
}