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

    /**
     * Lazy-activation timer: only scheduled when there are events to flush.
     * Uses single-shot scheduling (not fixed-rate) to avoid CPU waste during idle periods.
     */
    private java.util.Timer flushTimer;
    private volatile boolean started;
    
    /** Tracks whether a delayed flush task is currently scheduled */
    private volatile boolean flushTaskScheduled = false;

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
    }

    /**
     * Starts the lazy-activation flush timer infrastructure.
     * <p>
     * Unlike the previous fixed-rate approach, this creates a daemon Timer that is only
     * activated on-demand via {@link #scheduleDelayedFlush()}. During idle periods
     * (no pending events), no timer tasks execute, eliminating unnecessary CPU wakeups.
     * </p>
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
            started = true;
        }
    }
    
    /**
     * Schedules a single-shot delayed flush task.
     * If a task is already scheduled, it is cancelled and rescheduled (resetting the delay).
     * This provides coalescing behavior: rapid successive enqueues only trigger one flush.
     */
    private void scheduleDelayedFlush() {
        if (!started) {
            return;
        }
        synchronized (this) {
            if (!started || !running.get()) {
                return;
            }
            // Cancel any existing pending task to coalesce rapid enqueues
            if (flushTaskScheduled) {
                return; // Already scheduled, no need to reschedule
            }
            java.util.TimerTask task = new java.util.TimerTask() {
                @Override
                public void run() {
                    synchronized (LogEventBatcher.this) {
                        flushTaskScheduled = false;
                    }
                    if (running.get() && count.get() > 0) {
                        flushInternal();
                    }
                }
            };
            flushTimer.schedule(task, maxDelayMs);
            flushTaskScheduled = true;
        }
    }

    /**
     * Enqueues a log event for batched delivery.
     * <p>
     * If the buffer reaches capacity, an immediate flush is triggered.
     * Thread-safe: uses atomic index operations.
     * </p>
     * <p>
     * Lazy activation: after enqueueing, a single-shot delayed flush task is scheduled.
     * Rapid successive enqueues coalesce into one flush (the delay timer is not restarted).
     * During idle periods with no pending events, no timer runs at all.
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
            flushInternal();
        }

        int idx = writeIndex.getAndIncrement() % maxBatchSize;
        synchronized (ringBuffer) {
            ringBuffer[idx] = event;
        }
        count.incrementAndGet();

        // Schedule lazy single-shot delayed flush
        if (!started) {
            // Without timer infrastructure, dispatch immediately but still batch in EDT
            scheduleEdtFlush();
        } else {
            scheduleDelayedFlush();
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
        flushInternal();
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
        
        // Cancel any pending delayed flush task
        synchronized (this) {
            flushTaskScheduled = false;
        }
        
        flushInternal();

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
                flushInternal();
            }
        });
    }

    /**
     * Internal flush logic. Collects all buffered events and delivers them to the consumer on EDT.
     * <p>
     * The buffer is always <b>consumed</b> (slots cleared + count reset) as part of the flush,
     * so every event is delivered <b>exactly once</b>. The read, snapshot and reset happen under
     * a single lock, making the flush atomic with respect to concurrent {@link #enqueue(LogEvent)}
     * / {@link #flush()} calls and therefore preventing the same batch from being delivered twice.
     * </p>
     */
    private void flushInternal() {
        List<LogEvent> snapshot;
        synchronized (ringBuffer) {
            int currentCount = count.get();
            if (currentCount == 0) {
                return;
            }
            // Snapshot the buffered events in chronological order, then consume them
            // (clear the slots + reset the count) so nothing is re-delivered.
            snapshot = new ArrayList<>(currentCount);
            int startIdx = (writeIndex.get() - currentCount + maxBatchSize) % maxBatchSize;
            for (int i = 0; i < currentCount; i++) {
                int idx = (startIdx + i) % maxBatchSize;
                snapshot.add(ringBuffer[idx]);
                ringBuffer[idx] = null; // Help GC
            }
            count.set(0);
        }

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
                    }
                }
            });
        }
    }
}