package application.utils.logging;

import java.util.concurrent.ThreadFactory;

/**
 * A {@link ThreadFactory} implementation that captures the SLF4J MDC context
 * (module ID + profile name) at thread-creation time and restores it in every
 * newly created thread.
 * <p>
 * This is essential for proper log routing: when a parent thread submits work to
 * an {@link java.util.concurrent.ExecutorService}, the worker threads must carry
 * the same MDC context so that {@link ProfileLogRouter} can route events to the
 * correct {@link ProfileLogContext}.
 * </p>
 * <p>
 * <h3>Usage</h3>
 * <pre>{@code
 * ExecutorService exec = Executors.newScheduledThreadPool(
 *         4, new MdcPropagatingThreadFactory("MyPool-", true));
 * }</pre>
 * <p>
 * <b>Thread-safe:</b> Yes. MDC.getCopyOfMdcContextMap() returns a defensive copy.
 * </p>
 *
 * @see ProfileThreadContext
 * @see org.slf4j.MDC
 */
public final class MdcPropagatingThreadFactory implements ThreadFactory {

    private final ThreadFactory delegate;
    private final boolean captureAtCreation;

    /**
     * Creates a factory that wraps the default ThreadFactory.
     *
     * @param prefix           thread name prefix
     * @param daemon           whether threads are daemon threads
     * @param captureAtCreation if true, capture MDC snapshot when the thread is
     *                         created; if false, capture MDC when the task runs
     *                         (useful for dynamic routing)
     */
    public MdcPropagatingThreadFactory(String prefix, boolean daemon, boolean captureAtCreation) {
        this.delegate = r -> {
            Thread t = new Thread(r);
            t.setName(prefix + t.getId());
            t.setDaemon(daemon);
            return t;
        };
        this.captureAtCreation = captureAtCreation;
    }

    /**
     * Creates a factory with default settings (capture MDC at thread creation).
     *
     * @param prefix thread name prefix
     * @param daemon whether threads are daemon threads
     */
    public MdcPropagatingThreadFactory(String prefix, boolean daemon) {
        this(prefix, daemon, true);
    }

    /**
     * Creates a factory with daemon=true and captureAtCreation=true.
     *
     * @param prefix thread name prefix
     */
    public MdcPropagatingThreadFactory(String prefix) {
        this(prefix, true, true);
    }

    @Override
    public Thread newThread(Runnable r) {
        // Capture the current routing context from MDC (using our known keys).
        // We only use SLF4J's MDC.get()/put()/remove() API, not logback-specific ones.
        final String moduleSnapshot;
        final String profileSnapshot;
        if (captureAtCreation) {
            moduleSnapshot = ProfileThreadContext.getModuleId();
            profileSnapshot = ProfileThreadContext.getProfile();
        } else {
            moduleSnapshot = null;
            profileSnapshot = null;
        }

        Runnable wrapped = () -> {
            try {
                // Restore the captured MDC context at task execution time
                if (moduleSnapshot != null || profileSnapshot != null) {
                    ProfileThreadContext.setContext(moduleSnapshot, profileSnapshot);
                }
                r.run();
            } finally {
                // Always clean up to prevent MDC leaks across tasks
                ProfileThreadContext.clear();
            }
        };

        return delegate.newThread(wrapped);
    }
}