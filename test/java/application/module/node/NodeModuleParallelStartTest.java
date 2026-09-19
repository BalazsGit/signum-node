package application.module.node;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v5 (multi-node) tests for the PARALLEL lifecycle executor + per-profile
 * serialization + start-pending UX contract in {@link NodeModule}.
 * <p>
 * These tests verify, without starting a real node:
 * <ul>
 *   <li><b>Pending:</b> {@code startNode(name)} marks the profile start-pending
 *       IMMEDIATELY (synchronously), and the mark is cleared when the queued task
 *       finishes — on success AND on failure.</li>
 *   <li><b>Per-profile serialization:</b> two starts for the SAME profile run
 *       strictly sequentially in submission order (no overlap).</li>
 *   <li><b>Cross-profile parallelism:</b> starts for DIFFERENT profiles can run
 *       concurrently (the head-of-line-blocking regression is gone).</li>
 *   <li><b>Pending listener:</b> subscribers are notified when the pending set
 *       changes.</li>
 * </ul>
 * </p>
 * <p>
 * {@code Signum} is final (cannot be subclassed); Mockito 5's inline mock maker
 * mocks it. Only {@code getProfileName()} is stubbed so registry keying works;
 * {@code getProfile()} stays null, which skips the resource-reservation logic
 * (orthogonal to the concurrency contract under test here).
 * </p>
 */
class NodeModuleParallelStartTest {

    private NodeModule module() {
        return NodeModule.getInstance();
    }

    private Signum mockProfile(String name) {
        Signum fake = Mockito.mock(Signum.class);
        Mockito.when(fake.getProfileName()).thenReturn(name);
        return fake;
    }

    /** Bounded wait for a profile's start-pending mark to clear (task finished). */
    private void awaitNotPending(String name, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (module().isStartPending(name)) {
            if (System.currentTimeMillis() > deadline) {
                break;
            }
            Thread.sleep(25);
        }
    }

    @Test
    public void startNode_marksPendingImmediately_andClearsWhenTaskFinishes() throws Exception {
        String name = "pending-happy-" + System.nanoTime();
        Signum fake = mockProfile(name);
        // A real (bounded) delay so the pending window is observable.
        Mockito.doAnswer(inv -> {
            Thread.sleep(1200);
            return null;
        }).when(fake).start();

        module().addNode(fake);
        try {
            module().startNode(name);
            // Contract: pending is set SYNCHRONOUSLY by startNode, before the task runs.
            assertTrue(module().isStartPending(name),
                    "start must be pending immediately after startNode() returns");

            awaitNotPending(name, 10000);
            assertFalse(module().isStartPending(name),
                    "pending mark must be cleared once the start task completed");
        } finally {
            module().removeNode(name);
        }
    }

    @Test
    public void startNode_clearsPendingEvenWhenStartFails() throws Exception {
        String name = "pending-fail-" + System.nanoTime();
        Signum fake = mockProfile(name);
        Mockito.doThrow(new RuntimeException("simulated startup failure")).when(fake).start();

        module().addNode(fake);
        try {
            module().startNode(name);
            assertTrue(module().isStartPending(name),
                    "start must be pending right after startNode() returns");

            awaitNotPending(name, 10000);
            assertFalse(module().isStartPending(name),
                    "pending mark must be cleared even when start() throws");
        } finally {
            module().removeNode(name);
        }
    }

    @Test
    public void sameProfile_startsAreSerializedInOrder() throws Exception {
        String name = "serialize-" + System.nanoTime();
        Signum fake = mockProfile(name);

        List<String> events = Collections.synchronizedList(new ArrayList<>());
        Mockito.doAnswer(inv -> {
            events.add("enter");
            Thread.sleep(300);
            events.add("exit");
            return null;
        }).when(fake).start();

        module().addNode(fake);
        try {
            // Two starts for the SAME profile queued back-to-back. The per-profile gate
            // must run them strictly sequentially in submission order (no overlap).
            module().startNode(name);
            module().startNode(name);

            // Wait (bounded) until BOTH serialized start tasks have completed. (Waiting on
            // the pending mark alone is not enough: it clears as soon as the FIRST task's
            // finally runs, before the second serialized task has necessarily finished.)
            long deadline = System.currentTimeMillis() + 10000;
            while (events.size() < 4 && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }

            assertEquals(4, events.size(), "both start tasks must run");
            assertEquals(List.of("enter", "exit", "enter", "exit"), events,
                    "starts for the same profile must be strictly serialized in order");
        } finally {
            module().removeNode(name);
        }
    }

    @Test
    public void differentProfiles_canStartInParallel() throws Exception {
        String a = "par-a-" + System.nanoTime();
        String b = "par-b-" + System.nanoTime();
        Signum fa = mockProfile(a);
        Signum fb = mockProfile(b);

        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger maxActive = new AtomicInteger(0);
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch releaseA = new CountDownLatch(1);

        // Profile A's start holds until released; profile B's start waits on A entering
        // so the two are guaranteed to be in-flight together, then both proceed.
        Mockito.doAnswer(inv -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            bothEntered.countDown();
            releaseA.await();
            active.decrementAndGet();
            return null;
        }).when(fa).start();
        Mockito.doAnswer(inv -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            bothEntered.countDown();
            Thread.sleep(200);
            active.decrementAndGet();
            return null;
        }).when(fb).start();

        module().addNode(fa);
        module().addNode(fb);
        try {
            module().startNode(a);
            module().startNode(b);

            // Wait until both start tasks are in flight (or a generous timeout).
            boolean overlapped = bothEntered.await(10, TimeUnit.SECONDS);
            releaseA.countDown(); // let A finish so the pool threads are released

            awaitNotPending(a, 10000);
            awaitNotPending(b, 10000);

            assertTrue(overlapped, "both profiles' start tasks must have entered (overlap window)");
            assertTrue(maxActive.get() >= 2,
                    "different profiles must start in PARALLEL (max concurrent starts="
                            + maxActive.get() + ", expected >= 2)");
        } finally {
            module().removeNode(a);
            module().removeNode(b);
        }
    }

    @Test
    public void startPendingListener_isNotified() throws Exception {
        String name = "pending-listener-" + System.nanoTime();
        Signum fake = mockProfile(name);
        Mockito.doAnswer(inv -> {
            Thread.sleep(800);
            return null;
        }).when(fake).start();

        AtomicInteger notifications = new AtomicInteger(0);
        Runnable listener = () -> notifications.incrementAndGet();
        module().addPendingListener(listener);

        module().addNode(fake);
        try {
            module().startNode(name);
            // mark-pending fires at least one notification; unmark (after the task)
            // should fire another. Allow the task to complete.
            awaitNotPending(name, 10000);
            Thread.sleep(100);
            assertTrue(notifications.get() >= 1,
                    "pending listener must be notified when the pending set changes "
                            + "(got " + notifications.get() + ")");
        } finally {
            module().removePendingListener(listener);
            module().removeNode(name);
        }
    }
}