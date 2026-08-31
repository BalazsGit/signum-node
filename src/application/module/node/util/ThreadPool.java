package application.module.node.util;

import application.module.node.TransactionApplyContext;
import application.module.node.TransactionType;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ThreadPool {

    private static final int SHUTDOWN_GRACE_PERIOD_SECONDS = 10;

    /**
     * Jobs that are safe to interrupt immediately because they are usually
     * blocked on network I/O and don't perform critical atomic operations.
     */
    private static final Set<String> INTERRUPTIBLE_JOBS = Set.of(
            "GetMoreBlocks", "PeerConnecting", "GetCumulativeDifficulty");

    public static final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * Number of currently active (started, not yet shut down) main thread pools.
     * <p>
     * The global {@link #running} flag is only cleared when the LAST active pool
     * goes down, and re-armed whenever any pool (re)starts. Without the count a
     * single stop would (a) permanently zombie every pool started afterwards —
     * e.g. the restart sequence — because worker loops gate on
     * {@code running.get()}, and (b) kill the worker loops of every other
     * running node profile in multi-node mode.
     * </p>
     */
    private static final java.util.concurrent.atomic.AtomicInteger activePoolCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private static final Logger logger = LoggerFactory.getLogger(ThreadPool.class);
    private final Map<Thread, Long> activeThreadsStartTime = new ConcurrentHashMap<>();
    private final Map<Thread, String> activeThreadsJobName = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduledThreadPool;
    private final Map<Runnable, Long> backgroundJobs = new HashMap<>();
    private final Map<Runnable, String> backgroundJobNames = new HashMap<>();
    private final Map<Runnable, Long> backgroundJobsCores = new HashMap<>();
    private final List<Runnable> beforeStartJobs = new ArrayList<>();
    private final List<Runnable> lastBeforeStartJobs = new ArrayList<>();
    private final List<Runnable> afterStartJobs = new ArrayList<>();

    private final PropertyService propertyService;

    /**
     * The node profile name that owns this thread pool.
     * <p>
     * Set by the owning {@code Signum} instance at startup. When present, every
     * task executed by this pool runs inside a {@link application.utils.logging.NodeLogContext}
     * scoped to this profile, so the {@code SystemLoggerJulHandler} can route its
     * log events to the correct per-node {@code ProfileLogger} (Node Console).
     * </p>
     */
    private volatile String profileName;

    /**
     * Per-node transaction context bound to each thread in this pool.
     * Ensures multi-node isolation: each pool thread sees only its own node's context.
     */
    private volatile TransactionApplyContext transactionApplyContext;

    public ThreadPool(PropertyService propertyService) {
        this.propertyService = propertyService;
    }

    /**
     * Binds the per-node TransactionApplyContext to all threads in this pool.
     * Each task executed by this pool will have the context available via
     * {@link TransactionType#bindContext(TransactionApplyContext)}.
     */
    public void setTransactionApplyContext(TransactionApplyContext ctx) {
        this.transactionApplyContext = ctx;
    }

    /**
     * Sets the node profile name that owns this pool.
     * When set, all tasks executed by this pool run inside a
     * {@link application.utils.logging.NodeLogContext} scoped to this profile,
     * so log events are routed to the correct per-node {@code ProfileLogger}.
     *
     * @param profileName the node profile name (e.g. "mainnet")
     */
    public void setProfileName(String profileName) {
        this.profileName = profileName;
    }

    /**
     * Wraps a Runnable to bind/clear the node's TransactionApplyContext
     * and (if a profile name is set) to set/clear the {@code NodeLogContext}.
     * Reads both lazily at execution time to support contexts set after pool start.
     */
    private Runnable withContext(Runnable task) {
        ThreadPool self = this;
        return () -> {
            TransactionApplyContext ctx = self.transactionApplyContext;
            String profile = self.profileName;
            boolean hasTxCtx = (ctx != null);
            boolean hasLogCtx = (profile != null);
            if (!hasTxCtx && !hasLogCtx) {
                task.run();
            } else {
                if (hasTxCtx) {
                    TransactionType.bindContext(ctx);
                }
                if (hasLogCtx) {
                    application.utils.logging.NodeLogContext.set("node", profile);
                }
                try {
                    task.run();
                } finally {
                    if (hasLogCtx) {
                        application.utils.logging.NodeLogContext.clear();
                    }
                    if (hasTxCtx) {
                        TransactionType.clearContext();
                    }
                }
            }
        };
    }

    public synchronized void runBeforeStart(Runnable runnable, boolean runLast) {
        if (scheduledThreadPool != null) {
            throw new IllegalStateException("Executor service already started");
        }
        if (runLast) {
            lastBeforeStartJobs.add(runnable);
        } else {
            beforeStartJobs.add(runnable);
        }
    }

    public synchronized void runAfterStart(Runnable runnable) {
        afterStartJobs.add(runnable);
    }

    public void scheduleThread(String name, Runnable runnable, int delay) {
        scheduleThread(name, runnable, delay, TimeUnit.SECONDS);
    }

    public synchronized void scheduleThread(String name, Runnable runnable, int delay, TimeUnit timeUnit) {
        if (scheduledThreadPool != null) {
            throw new IllegalStateException("Executor service already started, no new jobs accepted");
        }
        if (!propertyService.getBoolean("node.disable" + name + "Thread", false)) {
            backgroundJobs.put(runnable, timeUnit.toMillis(delay));
            backgroundJobNames.put(runnable, name);
        } else {
            logger.info("Will not run {} thread", name);
        }
    }

    public void scheduleThreadCores(Runnable runnable, int delay) {
        scheduleThreadCores(runnable, delay, TimeUnit.SECONDS);
    }

    public synchronized void scheduleThreadCores(Runnable runnable, int delay, TimeUnit timeUnit) {
        if (scheduledThreadPool != null) {
            throw new IllegalStateException("Executor service already started, no new jobs accepted");
        }
        backgroundJobsCores.put(runnable, timeUnit.toMillis(delay));
    }

    public synchronized void start(int timeMultiplier) {
        if (scheduledThreadPool != null) {
            throw new IllegalStateException("Executor service already started");
        }
        // A live pool means the node subsystem is active again: re-arm the global
        // flag so worker loops (which gate on ThreadPool.running) keep running,
        // and the "… stopped." shutdown log lines stop spewing on every periodic
        // task execution (observed as a multi-thousand-line log flood after a
        // restart, because a previous stop had cleared the flag and nothing
        // re-armed it).
        activePoolCount.incrementAndGet();
        running.set(true);

        logger.debug("Running {} tasks...", beforeStartJobs.size());
        runAll(beforeStartJobs);
        beforeStartJobs.clear();

        logger.debug("Running {} final tasks...", lastBeforeStartJobs.size());
        runAll(lastBeforeStartJobs);
        lastBeforeStartJobs.clear();

        int cores = propertyService.getInt(Props.CPU_NUM_CORES);
        if (cores <= 0) {
            cores = Runtime.getRuntime().availableProcessors() / 2;
            cores = Math.max(1, cores);
        }
        logger.info("Using {} cores", cores);
        logger.info("Using {} msec Thread delay", propertyService.getInt(Props.BLOCK_PROCESS_THREAD_DELAY));
        int totalThreads = backgroundJobs.size() + backgroundJobsCores.size() * cores;
        logger.debug("Starting {} background jobs", totalThreads);
        // MDC propagation removed — ProfileLogger subscriber model replaces it
        scheduledThreadPool = Executors.newScheduledThreadPool(
                totalThreads, r -> { Thread t = new Thread(r, "Node-Worker-"); t.setDaemon(true); return t; });
        for (Map.Entry<Runnable, Long> entry : backgroundJobs.entrySet()) {
            final Runnable inner = withContext(entry.getKey());
            final String name = backgroundJobNames.get(entry.getKey());
            Runnable toRun = () -> {
                Thread currentThread = Thread.currentThread();
                String oldName = currentThread.getName();
                currentThread.setName(name + "Thread");
                activeThreadsStartTime.put(currentThread, System.currentTimeMillis());
                activeThreadsJobName.put(currentThread, name);
                try {
                    inner.run();
                } catch (Exception e) {
                    logger.warn("Uncaught exception while running background thread " + name, e);
                } finally {
                    activeThreadsStartTime.remove(currentThread);
                    activeThreadsJobName.remove(currentThread);
                    if (!running.get()) {
                        logger.info("Background thread '{}' stopped.", name);
                    }
                    Thread.currentThread().setName(oldName);
                }
            };
            scheduledThreadPool.scheduleWithFixedDelay(toRun, 0, Math.max(entry.getValue() / timeMultiplier, 1),
                    TimeUnit.MILLISECONDS);
        }
        // backgroundJobs.clear(); // Keep for debugging if needed, or clear later

        // Starting multicore-Threads:
        for (Map.Entry<Runnable, Long> entry : backgroundJobsCores.entrySet()) {
            final Runnable inner = withContext(entry.getKey());
            final String name = "CoreTask-" + entry.getKey().getClass().getSimpleName();
            Runnable toRun = () -> {
                // Context is already bound by withContext wrapper
                Thread currentThread = Thread.currentThread();
                activeThreadsStartTime.put(currentThread, System.currentTimeMillis());
                activeThreadsJobName.put(currentThread, name);
                try {
                    inner.run();
                } catch (Exception e) {
                    logger.warn("Uncaught exception while running background thread " + name, e);
                } finally {
                    activeThreadsStartTime.remove(currentThread);
                    activeThreadsJobName.remove(currentThread);
                    if (!running.get()) {
                        logger.info("Core background thread '{}' stopped.", name);
                    }
                }
            };
            for (int i = 0; i < cores; i++)
                scheduledThreadPool.scheduleWithFixedDelay(toRun, 0,
                        Math.max(entry.getValue() / timeMultiplier, 1), TimeUnit.MILLISECONDS);
        }
        backgroundJobsCores.clear();

        if (logger.isDebugEnabled()) {
            logger.debug("Starting {} delayed tasks", afterStartJobs.size());
        }
        Thread thread = new Thread(() -> {
            runAll(afterStartJobs);
            afterStartJobs.clear();
        });
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void shutdown() {
        if (scheduledThreadPool != null) {
            // Only the LAST pool clearing the global flag may stop the worker
            // loops of other running node profiles; a stale global false is also
            // what zombied pools started after the first stop (restart case).
            if (activePoolCount.decrementAndGet() <= 0) {
                running.set(false);
            }
            shutdownExecutor("MainThreadPool", scheduledThreadPool);
            scheduledThreadPool = null;
        }
    }

    public void shutdownExecutor(ExecutorService executor) {
        shutdownExecutor("UnnamedExecutor", executor);
    }

    public void shutdownExecutor(String name, ExecutorService executor) {
        // NOTE: the global "running" flag is managed by start()/shutdown() with
        // pool reference counting — auxiliary executor shutdowns (e.g. the peer
        // acceptor "UnnamedExecutor") must not clear it, otherwise they would
        // stop the worker loops of other running node profiles.
        if (executor == null || executor.isTerminated()) {
            return;
        }
        logger.info("Stopping executor '{}'...", name);
        long shutdownStartTime = System.currentTimeMillis();

        // Phase 1: Request graceful shutdown (no new tasks will start).
        executor.shutdown();

        // Phase 2: Abort the non-essential idler loops IMMEDIATELY — the moment a
        // shutdown is requested, network idler loops (PeerConnecting, GetMoreBlocks,
        // GetCumulativeDifficulty) must stop instead of completing their current
        // cycle. They gate on the running flag / interrupt status and exit at the
        // next sleep or loop check, usually within milliseconds. Only the essential
        // (vital) jobs get the grace period below.
        activeThreadsJobName.forEach((thread, jobName) -> {
            if (INTERRUPTIBLE_JOBS.contains(jobName)) {
                logger.info("Aborting non-essential job on shutdown request: '{}'", jobName);
                thread.interrupt();
            }
        });

        // Phase 3: report which ESSENTIAL (vital) jobs still need the grace period;
        // interruptible idlers were already aborted in Phase 2.
        if ("MainThreadPool".equals(name) && !activeThreadsJobName.isEmpty()) {
            List<String> essential = new ArrayList<>();
            for (String jobName : activeThreadsJobName.values()) {
                if (!INTERRUPTIBLE_JOBS.contains(jobName) && !essential.contains(jobName)) {
                    essential.add(jobName);
                }
            }
            if (!essential.isEmpty()) {
                logger.info("Waiting for essential background jobs to finish: {}", essential);
            }
        }

        int timeout = propertyService.getInt(Props.NODE_SHUTDOWN_TIMEOUT);
        logger.info("Waiting up to {}s for termination (grace period: {}s)...", timeout, SHUTDOWN_GRACE_PERIOD_SECONDS);

        try {
            // 1. Wait a short grace period for essential tasks to finish voluntarily
            if (!executor.awaitTermination(SHUTDOWN_GRACE_PERIOD_SECONDS, TimeUnit.SECONDS)) {
                // Non-essential idlers were already interrupted in Phase 2 — only
                // vital jobs can still be running. Report their runtime.
                activeThreadsJobName.forEach((thread, jobName) -> {
                    Long startTime = activeThreadsStartTime.get(thread);
                    long duration = (startTime != null) ? (System.currentTimeMillis() - startTime) : -1;
                    logger.info("  - Vital job still running: '{}' ({} ms)", jobName, duration);
                });

                // 2. Wait for the remaining timeout for vital tasks to finish naturally.
                // We do NOT call shutdownNow() here yet to protect vital tasks.
                if (!executor.awaitTermination(Math.max(1, timeout - SHUTDOWN_GRACE_PERIOD_SECONDS),
                        TimeUnit.SECONDS)) {
                    logger.error("Executor '{}' did not terminate after full {}s timeout. Forcing shutdownNow...",
                            name, timeout);
                    executor.shutdownNow(); // Final safety kill for everything
                    throw new RuntimeException("Executor service '" + name + "' failed to terminate.");
                }
            } else {
                long totalShutdownTime = System.currentTimeMillis() - shutdownStartTime;
                logger.info("Executor service '{}' terminated cleanly in {} ms.", name, totalShutdownTime);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow(); // (Re-)Cancel if current thread also interrupted
            Thread.currentThread().interrupt(); // Preserve interrupt status
        }
    }

    private void runAll(List<Runnable> jobs) {
        List<Thread> threads = new ArrayList<>();
        final StringBuffer errors = new StringBuffer();
        for (final Runnable runnable : jobs) {
            Thread thread = new Thread(() -> {
                try {
                    runnable.run();
                } catch (Exception t) {
                    errors.append(t.getMessage()).append('\n');
                    throw t;
                }
            });
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (errors.length() > 0) {
            throw new RuntimeException("Errors running startup tasks:\n" + errors.toString());
        }
    }

}
