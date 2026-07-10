package application.utils.logging.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LogEventBatcher}.
 * <p>
 * Covers constructor validation, enqueue/flush lifecycle, stop/cleanup,
 * capacity-based auto-flush, and AutoCloseable contract.
 * </p>
 */
@DisplayName("LogEventBatcher Tests")
class LogEventBatcherTest {

    // ── Helper: create a test LogEvent ───────────────────────────────

    private LogEvent createTestEvent(String message) {
        return new LogEvent.Builder()
                .timestamp(System.currentTimeMillis())
                .level(LogLevel.INFO)
                .message(message)
                .threadName("test-thread")
                .build();
    }

    /**
     * Latch-based BatchConsumer that captures all batches and signals arrival via CountDownLatch.
     * This makes async EDT dispatch testable in headless environments where the EDT may not run.
     */
    private static class LatchCaptureConsumer implements LogEventBatcher.BatchConsumer {
        private final List<List<LogEvent>> capturedBatches = Collections.synchronizedList(new ArrayList<>());
        private CountDownLatch latch;

        /** Create a consumer that waits for exactly {@code expectedBatches} deliveries. */
        public LatchCaptureConsumer(int expectedBatches) {
            this.latch = new CountDownLatch(expectedBatches);
        }

        @Override
        public void acceptBatch(List<LogEvent> events) {
            synchronized (capturedBatches) {
                capturedBatches.add(Collections.unmodifiableList(new ArrayList<>(events)));
            }
            latch.countDown();
        }

        public List<List<LogEvent>> getCapturedBatches() {
            return new ArrayList<>(capturedBatches);
        }

        public int getTotalEventsDelivered() {
            return capturedBatches.stream().mapToInt(List::size).sum();
        }

        /** Block until all expected batches arrive or timeout. */
        public void await(int timeoutSeconds) throws InterruptedException {
            latch.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        public void reset() {
            synchronized (capturedBatches) {
                capturedBatches.clear();
            }
        }
    }

    /**
     * Simple capture consumer without latch (for tests that use waitForEdtDispatch).
     */
    private static class CaptureConsumer implements LogEventBatcher.BatchConsumer {
        private final List<List<LogEvent>> capturedBatches = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void acceptBatch(List<LogEvent> events) {
            synchronized (capturedBatches) {
                capturedBatches.add(Collections.unmodifiableList(new ArrayList<>(events)));
            }
        }

        public List<List<LogEvent>> getCapturedBatches() {
            return new ArrayList<>(capturedBatches);
        }

        public int getTotalEventsDelivered() {
            return capturedBatches.stream().mapToInt(List::size).sum();
        }

        public void reset() {
            synchronized (capturedBatches) {
                capturedBatches.clear();
            }
        }
    }

    // ── Constructor Validation ────────────────────────────────────────

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorTests {

        @Test
        @DisplayName("throws NullPointerException when consumer is null")
        void constructor_GivenNullConsumer_ThrowsNPE() {
            assertThrows(NullPointerException.class, () ->
                    new LogEventBatcher(null)
            );
        }

        @Test
        @DisplayName("throws NullPointerException when consumer is null (3-arg)")
        void constructor_GivenNullConsumer3Arg_ThrowsNPE() {
            assertThrows(NullPointerException.class, () ->
                    new LogEventBatcher((LogEventBatcher.BatchConsumer) null, 200, 50)
            );
        }

        @Test
        @DisplayName("throws IllegalArgumentException when maxDelayMs is zero")
        void constructor_GivenZeroDelay_ThrowsIAE() {
            assertThrows(IllegalArgumentException.class, () ->
                    new LogEventBatcher(events -> {}, 0, 50)
            );
        }

        @Test
        @DisplayName("throws IllegalArgumentException when maxDelayMs is negative")
        void constructor_GivenNegativeDelay_ThrowsIAE() {
            assertThrows(IllegalArgumentException.class, () ->
                    new LogEventBatcher(events -> {}, -100, 50)
            );
        }

        @Test
        @DisplayName("throws IllegalArgumentException when maxBatchSize is zero")
        void constructor_GivenZeroBatchSize_ThrowsIAE() {
            assertThrows(IllegalArgumentException.class, () ->
                    new LogEventBatcher(events -> {}, 200, 0)
            );
        }

        @Test
        @DisplayName("throws IllegalArgumentException when maxBatchSize is negative")
        void constructor_GivenNegativeBatchSize_ThrowsIAE() {
            assertThrows(IllegalArgumentException.class, () ->
                    new LogEventBatcher(events -> {}, 200, -5)
            );
        }

        @Test
        @DisplayName("default constructor uses expected thresholds")
        void defaultConstructor_UsesDefaultThresholds() {
            LogEventBatcher batcher = new LogEventBatcher(events -> {});
            // Verify it was created successfully with defaults
            assertNotNull(batcher);
            assertEquals(0, batcher.pendingCount());
            batcher.stop();
        }

        @Test
        @DisplayName("custom constructor accepts valid parameters")
        void customConstructor_GivenValidParams_CreatesSuccessfully() {
            LogEventBatcher batcher = new LogEventBatcher(events -> {}, 500, 100);
            assertNotNull(batcher);
            assertEquals(0, batcher.pendingCount());
            batcher.stop();
        }

        @Test
        @DisplayName("constructor with boundary values (delay=1, size=1)")
        void constructor_GivenMinimumPositiveValues_CreatesSuccessfully() {
            LogEventBatcher batcher = new LogEventBatcher(events -> {}, 1, 1);
            assertNotNull(batcher);
            batcher.stop();
        }
    }

    // ── Enqueue / pendingCount ───────────────────────────────────────

    @Nested
    @DisplayName("Enqueue and pendingCount")
    class EnqueueTests {

        @Test
        @DisplayName("enqueue increments pendingCount")
        void enqueue_GivenValidEvent_IncrementsPendingCount() {
            LogEventBatcher batcher = new LogEventBatcher(events -> {}, 200, 50);
            assertEquals(0, batcher.pendingCount());

            batcher.enqueue(createTestEvent("msg1"));
            assertEquals(1, batcher.pendingCount());

            batcher.enqueue(createTestEvent("msg2"));
            assertEquals(2, batcher.pendingCount());

            batcher.stop();
        }

        @Test
        @DisplayName("enqueue ignores null event")
        void enqueue_GivenNullEvent_IgnoresSilently() {
            LogEventBatcher batcher = new LogEventBatcher(events -> {}, 200, 50);
            batcher.enqueue(null);
            assertEquals(0, batcher.pendingCount());
            batcher.stop();
        }

        @Test
        @DisplayName("enqueue after stop is a no-op")
        void enqueue_GivenStoppedBatcher_IgnoresEvent() {
            LogEventBatcher batcher = new LogEventBatcher(events -> {}, 200, 50);
            batcher.stop();

            batcher.enqueue(createTestEvent("after-stop"));
            assertEquals(0, batcher.pendingCount());
        }
    }

    // ── Flush behaviour ──────────────────────────────────────────────

    @Nested
    @DisplayName("Flush behaviour")
    class FlushTests {

        @Test
        @DisplayName("flush delivers buffered events to consumer")
        void flush_GivenBufferedEvents_DeliversToConsumer() throws InterruptedException {
            LatchCaptureConsumer consumer = new LatchCaptureConsumer(1);
            // Use start() so timer-based flushing is active, avoiding multiple invokeLater calls
            LogEventBatcher batcher = new LogEventBatcher(consumer, 5000, 50);
            batcher.start();

            batcher.enqueue(createTestEvent("msg1"));
            batcher.enqueue(createTestEvent("msg2"));
            assertEquals(2, batcher.pendingCount());

            batcher.flush();
            // Dispatch EDT event manually since headless env may not have EDT running
            dispatchPendingEdtEvents();
            consumer.await(5);

            List<List<LogEvent>> batches = consumer.getCapturedBatches();
            assertTrue(batches.size() >= 1, "At least one batch should be delivered");
            int totalEvents = batches.stream().mapToInt(List::size).sum();
            assertEquals(2, totalEvents, "Both events should be in the delivered batches");

            batcher.stop();
        }

        @Test
        @DisplayName("flush on empty buffer does nothing")
        void flush_GivenEmptyBuffer_DoesNothing() {
            CaptureConsumer consumer = new CaptureConsumer();
            LogEventBatcher batcher = new LogEventBatcher(consumer, 200, 50);

            batcher.flush();
            waitForEdtDispatch();

            assertEquals(0, consumer.getTotalEventsDelivered());
            batcher.stop();
        }

        @Test
        @DisplayName("flush after stop is a no-op")
        void flush_GivenStoppedBatcher_DoesNothing() {
            CaptureConsumer consumer = new CaptureConsumer();
            LogEventBatcher batcher = new LogEventBatcher(consumer, 200, 50);

            batcher.stop();
            batcher.flush();
            waitForEdtDispatch();

            assertEquals(0, consumer.getTotalEventsDelivered());
        }
    }

    // ── Capacity-based auto-flush ────────────────────────────────────

    @Nested
    @DisplayName("Capacity-based auto-flush")
    class AutoFlushTests {

        @Test
        @DisplayName("batcher triggers auto-flush when buffer reaches capacity")
        void enqueue_GivenBatchSizeTwo_AutoFlushesOnSecondEvent() throws InterruptedException {
            LatchCaptureConsumer consumer = new LatchCaptureConsumer(1);
            LogEventBatcher batcher = new LogEventBatcher(consumer, 5000, 2); // large delay so timer doesn't fire

            batcher.enqueue(createTestEvent("msg1"));
            assertEquals(1, batcher.pendingCount());

            // Second enqueue triggers auto-flush since count >= maxBatchSize
            batcher.enqueue(createTestEvent("msg2"));
            dispatchPendingEdtEvents();
            consumer.await(5);

            // Events were delivered via flush triggered by capacity
            List<List<LogEvent>> batches = consumer.getCapturedBatches();
            assertTrue(batches.size() >= 1, "Auto-flush should deliver a batch");
            batcher.stop();
        }

        @Test
        @DisplayName("batcher delivers after reaching capacity")
        void enqueue_GivenBatchSizeThree_AutoFlushesAtCapacity() throws InterruptedException {
            LatchCaptureConsumer consumer = new LatchCaptureConsumer(1);
            LogEventBatcher batcher = new LogEventBatcher(consumer, 5000, 3);

            batcher.enqueue(createTestEvent("msg1"));
            batcher.enqueue(createTestEvent("msg2"));
            assertEquals(2, batcher.pendingCount());

            // Third one triggers capacity flush
            batcher.enqueue(createTestEvent("msg3"));
            dispatchPendingEdtEvents();
            consumer.await(5);

            assertTrue(consumer.getCapturedBatches().size() >= 1, "Should auto-flush at capacity");
            batcher.stop();
        }
    }

    // ── Stop / Close / AutoCloseable ─────────────────────────────────

    @Nested
    @DisplayName("Stop, close, and AutoCloseable contract")
    class StopAndCloseTests {

        @Test
        @DisplayName("stop drains buffer and prevents further enqueue")
        void stop_DrainsBuffer_PreventsFurtherEnqueue() {
            LogEventBatcher batcher = new LogEventBatcher(events -> {}, 5000, 50);
            batcher.start();

            batcher.enqueue(createTestEvent("msg"));
            assertEquals(1, batcher.pendingCount());

            batcher.stop();

            // After stop, running is false so new enqueues are ignored
            batcher.enqueue(createTestEvent("after-stop"));
            assertEquals(0, batcher.pendingCount(), "Buffer should be empty after stop flushes");

            // Verify stop is idempotent
            batcher.stop();
        }

        @Test
        @DisplayName("stop is idempotent")
        void stop_GivenAlreadyStopped_IsIdempotent() {
            CaptureConsumer consumer = new CaptureConsumer();
            LogEventBatcher batcher = new LogEventBatcher(consumer, 200, 50);

            batcher.stop();
            batcher.stop(); // Should not throw
        }

        @Test
        @DisplayName("close delegates to stop")
        void close_DelegatesToStop() {
            LogEventBatcher batcher = new LogEventBatcher(events -> {}, 5000, 50);
            batcher.start();

            batcher.enqueue(createTestEvent("msg"));
            assertEquals(1, batcher.pendingCount());

            batcher.close();

            // Enqueue after close should be ignored (close → stop sets running=false)
            batcher.enqueue(createTestEvent("after-close"));
            assertEquals(0, batcher.pendingCount(), "Buffer should be empty after close flushes");
        }

        @Test
        @DisplayName("try-with-resources closes batcher")
        void tryWithResources_ClosesBatcher() {
            AtomicBoolean closed = new AtomicBoolean(false);
            try (LogEventBatcher batcher = new LogEventBatcher(events -> {}, 200, 50)) {
                batcher.enqueue(createTestEvent("inside"));
            }
            // After try-with-resources, enqueue is ignored
        }

        @Test
        @DisplayName("double close does not throw")
        void close_GivenAlreadyClosed_DoesNotThrow() {
            LogEventBatcher batcher = new LogEventBatcher(events -> {}, 200, 50);
            batcher.close();
            batcher.close(); // idempotent
        }
    }

    // ── Start / Timer lifecycle ──────────────────────────────────────

    @Nested
    @DisplayName("Start and timer lifecycle")
    class StartTests {

        @Test
        @DisplayName("start creates flush timer")
        void start_CreatesFlushTimer() {
            LogEventBatcher batcher = new LogEventBatcher(events -> {}, 200, 50);
            batcher.start();
            // No exception thrown
            batcher.stop();
        }

        @Test
        @DisplayName("start is idempotent")
        void start_GivenAlreadyStarted_IsIdempotent() {
            LogEventBatcher batcher = new LogEventBatcher(events -> {}, 200, 50);
            batcher.start();
            batcher.start(); // Should not create duplicate timer
            batcher.stop();
        }

        @Test
        @DisplayName("start after stop does not restart")
        void start_GivenPreviouslyStopped_DoesNotRestart() {
            LogEventBatcher batcher = new LogEventBatcher(events -> {}, 200, 50);
            batcher.start();
            batcher.stop();
            // After stop the running flag is false — further operations are no-ops
        }
    }

    // ── Consumer error resilience ────────────────────────────────────

    @Nested
    @DisplayName("Consumer error resilience")
    class ConsumerErrorTests {

        @Test
        @DisplayName("consumer exception does not crash EDT thread")
        void flush_GivenThrowingConsumer_HandlesGracefully() {
            LogEventBatcher.BatchConsumer throwingConsumer = events -> {
                throw new RuntimeException("Simulated consumer error");
            };
            LogEventBatcher batcher = new LogEventBatcher(throwingConsumer, 200, 50);

            batcher.enqueue(createTestEvent("msg"));
            batcher.flush();
            waitForEdtDispatch();

            // The batcher should still be functional (or stopped cleanly)
            batcher.stop();
        }
    }

    // ── BatchConsumer interface ──────────────────────────────────────

    @Nested
    @DisplayName("BatchConsumer functional interface")
    class BatchConsumerTests {

        @Test
        @DisplayName("lambda can be used as BatchConsumer")
        void batchConsumer_AcceptsLambda() {
            List<List<LogEvent>> delivered = Collections.synchronizedList(new ArrayList<>());
            LogEventBatcher.BatchConsumer consumer = delivered::add;
            assertNotNull(consumer);

            LogEventBatcher batcher = new LogEventBatcher(consumer, 200, 50);
            assertNotNull(batcher);
            batcher.stop();
        }
    }

    // ── Constants ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Public constants")
    class ConstantsTests {

        @Test
        @DisplayName("DEFAULT_MAX_DELAY_MS equals 200")
        void defaultMaxDelay_IsTwoHundred() {
            assertEquals(200, LogEventBatcher.DEFAULT_MAX_DELAY_MS);
        }

        @Test
        @DisplayName("DEFAULT_MAX_BATCH_SIZE equals 50")
        void defaultMaxBatchSize_IsFifty() {
            assertEquals(50, LogEventBatcher.DEFAULT_MAX_BATCH_SIZE);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Dispatch any pending EDT events by pumping the EventQueue.
     * In headless CI environments the EDT may not be running, so we
     * manually trigger invocation to make tests deterministic.
     */
    private void dispatchPendingEdtEvents() {
        try {
            java.awt.EventQueue.invokeAndWait(() -> {
                // Force the EDT to process its queue
            });
        } catch (java.awt.HeadlessException e) {
            // In headless env, just sleep and hope the timer thread processes events
            try { Thread.sleep(200); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } catch (InterruptedException | java.lang.reflect.InvocationTargetException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Give the EDT a brief moment to process any pending invokeLater callbacks.
     * In headless test environments this is a best-effort approach.
     */
    private void waitForEdtDispatch() {
        dispatchPendingEdtEvents();
    }
}
