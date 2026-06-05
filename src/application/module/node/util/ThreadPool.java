package application.module.node.util;

import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ThreadPool {

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
        scheduledThreadPool = Executors.newScheduledThreadPool(totalThreads);
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
        running.lazySet(false); // Signal all loops to stop
        if (executor == null || executor.isTerminated()) {
            return;
        }
        logger.info("Stopping executor '{}'...", name);
        long shutdownStartTime = System.currentTimeMillis();
        executor.shutdown();

        // List what is currently running ONLY for the main pool to avoid misleading
        // logs
        if ("MainThreadPool".equals(name) && !activeThreadsJobName.isEmpty()) {
            logger.info("The following background jobs are still executing and being awaited: {}",
                    new ArrayList<>(activeThreadsJobName.values()));
        }

        int timeout = propertyService.getInt(Props.NODE_SHUTDOWN_TIMEOUT);
        logger.info("Waiting up to {} seconds for executor termination...", timeout);
        try {
            // Wait a while for existing tasks to terminate
            if (!executor.awaitTermination(timeout, TimeUnit.SECONDS)) {
                logger.warn("Executor service '{}' did not terminate gracefully within {}s. Forcing shutdown...", name,
                        timeout);

                // Report exactly which threads are stuck and for how long
                activeThreadsJobName.forEach((thread, jobName) -> {
                    Long startTime = activeThreadsStartTime.get(thread);
                    long duration = (startTime != null) ? (System.currentTimeMillis() - startTime) : -1;
                    logger.warn("Stuck Job: '{}' on thread '{}' (active for {} ms)", jobName,
                            thread.getName(), duration);
                });

                executor.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.error("Executor service '{}' did not terminate even after forcing shutdown (shutdownNow).",
                            name);
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
