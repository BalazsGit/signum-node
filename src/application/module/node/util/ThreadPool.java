package application.module.node.util;

import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.utils.logging.MdcPropagatingThreadFactory;
import application.utils.logging.ProfileThreadContext;
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

    public ThreadPool(PropertyService propertyService) {
        this.propertyService = propertyService;
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
        // Use MdcPropagatingThreadFactory so child threads inherit the parent's MDC context.
        // Since NodeLifecycleManager wraps startup in ProfileThreadContext.wrap(module, profile),
        // all scheduled workers will carry module="node" + profile=<profileName> for proper log routing.
        scheduledThreadPool = Executors.newScheduledThreadPool(
                totalThreads, new MdcPropagatingThreadFactory("Node-Worker-", true));
        for (Map.Entry<Runnable, Long> entry : backgroundJobs.entrySet()) {
            final Runnable inner = entry.getKey();
            final String name = backgroundJobNames.get(inner);
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
            final Runnable inner = entry.getKey();
            final String name = "CoreTask-" + inner.getClass().getSimpleName();
            Runnable toRun = () -> {
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
            shutdownExecutor("MainThreadPool", scheduledThreadPool);
            scheduledThreadPool = null;
        }
    }

    public void shutdownExecutor(ExecutorService executor) {
        shutdownExecutor("UnnamedExecutor", executor);
    }

    public void shutdownExecutor(String name, ExecutorService executor) {
        running.set(false); // Signal all loops to stop
        if (executor == null || executor.isTerminated()) {
            return;
        }
        logger.info("Stopping executor '{}'...", name);
        long shutdownStartTime = System.currentTimeMillis();

        // Phase 1: Request graceful shutdown.
        executor.shutdown();

        if ("MainThreadPool".equals(name) && !activeThreadsJobName.isEmpty()) {
            logger.info("Waiting for essential background jobs to finish: {}",
                    new ArrayList<>(activeThreadsJobName.values()));
        }

        int timeout = propertyService.getInt(Props.NODE_SHUTDOWN_TIMEOUT);
        logger.info("Waiting up to {}s for termination (grace period: {}s)...", timeout, SHUTDOWN_GRACE_PERIOD_SECONDS);

        try {
            // 1. Wait a short grace period for essential tasks to finish voluntarily
            if (!executor.awaitTermination(SHUTDOWN_GRACE_PERIOD_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("Executor '{}' still busy after {}s. Interrupting non-essential idlers...",
                        name, SHUTDOWN_GRACE_PERIOD_SECONDS);

                // Targeted Nudge: Interrupt ONLY non-essential jobs now that grace period
                // expired
                activeThreadsJobName.forEach((thread, jobName) -> {
                    if (INTERRUPTIBLE_JOBS.contains(jobName)) {
                        logger.info("  - Interrupting non-essential job: '{}'", jobName);
                        thread.interrupt();
                    } else {
                        Long startTime = activeThreadsStartTime.get(thread);
                        long duration = (startTime != null) ? (System.currentTimeMillis() - startTime) : -1;
                        logger.info("  - Vital job still running: '{}' ({} ms)", jobName, duration);
                    }
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
        // Capture current MDC so spawned startup threads also carry the routing context.
        String capturedModule = ProfileThreadContext.getModuleId();
        String capturedProfile = ProfileThreadContext.getProfile();
        for (final Runnable runnable : jobs) {
            Thread thread = new Thread(ProfileThreadContext.wrap(() -> {
                try {
                    runnable.run();
                } catch (Exception t) {
                    errors.append(t.getMessage()).append('\n');
                    throw t;
                }
            }, capturedModule, capturedProfile));
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
