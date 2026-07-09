package application.utils.logging.event;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Performance optimizer that batches log events before dispatching to the Swing EDT.
 * <p>
 * Instead of triggering a UI update for every single log event (which can cause severe
 * performance degradation under high logging volume), this batcher accumulates events
 * in a lock-free ring buffer and flushes them periodically using configurable thresholds:
 * </p>
 * <ul>
 *   <li><b>Time threshold:</b> Flush after {@code maxDelayMs} of inactivity (default: 200ms)</li>
 *   <li><b>Count threshold:</b> Flush immediately when buffer reaches {@code maxBatchSize} events (default: 50)</li>
 * </ul>
 * <p>
 * <h3>Usage Pattern</h3>
 * <pre>{@code
 * LogEventBatcher batcher = new LogEventBatcher(events -> {
 *     // Update UI with all events in one EDT call
 *     for (LogEvent e : events) {
 *         appendToConsole(e);
 *     }
 * }, 200, 50);
 *
 * batcher.start();
 *
 * // Enqueue events from any thread
 * batcher.enqueue(event);
 *
 * // Cleanup on shutdown
 * batcher.stop(); // flushes remaining events
 * }</pre>
 * </p>
 * <p>
 * Thread-safe: Uses AtomicInteger for lock-free index operations. The consumer runs on EDT.
 * Memory-bounded: Ring buffer prevents unbounded growth under high throughput.
 * </p>
 */
public final class LogEventBatcher implements AutoCloseable {

    /** Default maximum delay before flushing (milliseconds) */
    public static final long DEFAULT_MAX_DELAY_MS = 200;

    /** Default maximum batch size before forced flush */
    public static final int DEFAULT_MAX_BATCH_SIZE = 50;

    /**
     * Consumer that receives batches of events on the EDT.
     */
    @FunctionalInterface
    public interface BatchConsumer {
        void acceptBatch(List<LogEvent> events);
    }

    private final BatchConsumer consumer;
    private final long maxDelayMs;
    private final int maxBatchSize;
    private final LogEvent[] ringBuffer;
    private final AtomicInteger writeIndex;
    private final AtomicInteger count;
    private final AtomicBoolean running;
    private final AtomicBoolean flushPending;

    private java.util.Timer flushTimer;
    private volatile boolean started;

    /**
     * Creates a batcher with default thresholds (200ms delay, 50 events max).
     *
     * @param consumer the EDT consumer that receives event batches
     */
    public LogEventBatcher(BatchConsumer consumer) {
        this(consumer, DEFAULT_MAX_DELAY_MS, DEFAULT_MAX_BATCH_SIZE);
    }

    /**
     * Creates a batcher with custom thresholds.
     *
     * @param consumer    the EDT consumer that receives event batches
     * @param maxDelayMs  maximum time to wait before flushing (milliseconds)
     * @param maxBatchSize maximum events to accumulate before forced flush
     */
    public LogEventBatcher(BatchConsumer consumer, long maxDelayMs, int maxBatchSize) {
        if (consumer == null) {
            throw new NullPointerException("Consumer must not be null");
        }
        if (maxDelayMs <= 0) {
            throw new IllegalArgumentException("maxDelayMs must be positive, got: " + maxDelayMs);
        }
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive, got: " + maxBatchSize);
        }
        this.consumer = consumer;
        this.maxDelayMs = maxDelayMs;
        this.maxBatchSize = maxBatchSize;
        this.ringBuffer = new LogEvent[maxBatchSize];
        this.writeIndex = new AtomicInteger(0);
        this.count = new AtomicInteger(0);
        this.running = new AtomicBoolean(true);
        this.flushPending = new AtomicBoolean(false);
    }

    /**
     * Starts the delay-based flush timer.
     * Must be called before enqueueing events if using automatic delay-based flushing.
     */
    public void start() {
        if (started) {
            return;
        }
        synchronized (this) {
            if (started) {
                return;
            }
            flushTimer = new java.util.Timer("LogEventBatcher-Flush-" + System.identityHashCode(this), true);
            flushTimer.scheduleAtFixedRate(new java.util.TimerTask() {
                @Override
                public void run() {
                    if (!running.get()) {
                        this.cancel();
                        return;
                    }
                    if (count.get() > 0) {
                        flushInternal(false);
                    }
                }
            }, maxDelayMs, maxDelayMs);
            started = true;
        }
    }

    /**
     * Enqueues a log event for batched delivery.
     * <p>
     * If the buffer reaches capacity, an immediate flush is triggered.
     * Thread-safe: uses atomic index operations.
     * </p>
     *
     * @param event the event to enqueue (never null)
     */
    public void enqueue(LogEvent event) {
        if (!running.get() || event == null) {
            return;
        }

        int currentCount = count.get();
        if (currentCount >= maxBatchSize) {
            // Buffer full - trigger immediate flush
            flushInternal(true);
        }

        int idx = writeIndex.getAndIncrement() % maxBatchSize;
        synchronized (ringBuffer) {
            ringBuffer[idx] = event;
        }
        count.incrementAndGet();

        // Schedule delayed flush if not already pending
        if (!started) {
            // Without timer, dispatch immediately but still batch in EDT
            scheduleEdtFlush();
        } else if (flushPending.compareAndSet(false, true)) {
            // Timer will pick this up on next tick
        }
    }

    /**
     * Forces an immediate flush of all buffered events.
     * Blocks until the EDT consumer has processed the batch.
     */
    public void flush() {
        if (!running.get()) {
            return;
        }
        flushInternal(true);
    }

    /**
     * Returns the number of events currently waiting in the buffer.
     */
    public int pendingCount() {
        return count.get();
    }

    /**
     * Stops this batcher, flushing any remaining events.
     * After calling stop(), no more events will be accepted.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return; // Already stopped
        }
        flushInternal(true);

        synchronized (this) {
            if (flushTimer != null) {
                flushTimer.cancel();
                flushTimer = null;
            }
            started = false;
        }
    }

    @Override
    public void close() {
        stop();
    }

    // ── Private ───────────────────────────────────────────────────────

    private void scheduleEdtFlush() {
        SwingUtilities.invokeLater(() -> {
            if (running.get() && count.get() > 0) {
                flushInternal(false);
            }
        });
    }

    /**
     * Internal flush logic. Collects all buffered events and delivers them to the consumer on EDT.
     *
     * @param reset true to clear the buffer after collecting (for capacity-based forced flush)
     */
    private void flushInternal(boolean reset) {
        int currentCount = count.get();
        if (currentCount == 0) {
            flushPending.set(false);
            return;
        }

        // Snapshot events from ring buffer in chronological order
        List<LogEvent> snapshot;
        synchronized (ringBuffer) {
            snapshot = new ArrayList<>(currentCount);
            int startIdx = (writeIndex.get() - currentCount + maxBatchSize) % maxBatchSize;
            for (int i = 0; i < currentCount; i++) {
                int idx = (startIdx + i) % maxBatchSize;
                snapshot.add(ringBuffer[idx]);
                if (reset) {
                    ringBuffer[idx] = null; // Help GC
                }
            }
        }

        if (reset) {
            count.set(0);
        }

        flushPending.set(false);

        // Dispatch to EDT
        if (!snapshot.isEmpty()) {
            final List<LogEvent> batch = Collections.unmodifiableList(snapshot);
            SwingUtilities.invokeLater(() -> {
                if (running.get()) {
                    try {
                        consumer.acceptBatch(batch);
                    } catch (Exception e) {
                        // Log error but don't crash the EDT
                        System.err.println("LogEventBatcher consumer error: " + e.getMessage());
                    } finally {
                        if (reset) {
                            // Already reset above
                        }
                    }
                }
            });
        }
    }
}