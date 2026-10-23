package application.module.logging.context;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Registry that OWNS the node LoggerContexts by direct object reference
 * (per-node isolated LoggerContext plan, §6.2/§6.3 — D7): the node contexts
 * are NEVER looked up through the factory/selector; this manager's map is
 * the single place that knows them.
 * <p>
 * Creation happens under the manager lock, so parallel node starts are safe
 * and a profile can never end up with two contexts. Disposal
 * ({@link #remove}) stops the context and calls
 * {@link Log4jContextFactory#removeContext(org.apache.logging.log4j.spi.LoggerContext)}
 * so the factory's {@code getLoggerContexts()} stays consistent (not a
 * routing concern, §6.2).
 * </p>
 *
 * <p>
 * Lifecycle split: {@code stop()} (node stop) leaves the context registered —
 * a restarted node reuses the same object; {@code remove()} (node disposal)
 * drops it entirely.
 * </p>
 *
 * <p>Headless-safe (no Swing).</p>
 */
public final class NodeLoggingContextManager {

    /**
     * The factory used for {@code removeContext} on disposal. Null means "the
     * current {@link LogManager} factory at removal time" (production path);
     * tests may inject a specific one.
     */
    private final Log4jContextFactory factory;
    private final ReentrantLock managerLock = new ReentrantLock();
    private final Map<String, NodeLoggingContext> contexts = new ConcurrentHashMap<>();

    /**
     * @param factory the Log4j2 factory for dispose bookkeeping, or null to
     *                resolve the current {@code LogManager} factory lazily
     */
    public NodeLoggingContextManager(final Log4jContextFactory factory) {
        this.factory = factory;
    }

    /** Production manager: removes disposed contexts from the live LogManager factory. */
    public static NodeLoggingContextManager ofCurrentFactory() {
        return new NodeLoggingContextManager(null);
    }

    /**
     * Returns the context of the profile, creating and starting it from the
     * given generated config file when it does not exist yet. Idempotent under
     * the manager lock (parallel starts of the same profile share one context).
     *
     * @param profileName the node profile name
     * @param configUri   the generated .properties file (or null for the
     *                    two-phase start's minimal config, plan §6.3)
     */
    public NodeLoggingContext getOrCreate(final String profileName, final URI configUri) {
        managerLock.lock();
        try {
            NodeLoggingContext existing = contexts.get(profileName);
            if (existing != null) {
                return existing;
            }
            NodeLoggingContext created = new NodeLoggingContext(profileName, configUri).start();
            NodeLoggingContext raced = contexts.putIfAbsent(profileName, created);
            if (raced != null) {
                created.remove(factoryForDispose()); // lost the race: dispose our duplicate
                return raced;
            }
            return created;
        } finally {
            managerLock.unlock();
        }
    }

    /** @return the context of the profile, or null when none was created */
    public NodeLoggingContext get(final String profileName) {
        return contexts.get(profileName);
    }

    /**
     * Disposes the profile's context: stop + factory removal + map removal.
     * A later {@code getOrCreate} creates a FRESH context.
     *
     * @return true when a context existed and was disposed
     */
    public boolean remove(final String profileName) {
        managerLock.lock();
        try {
            NodeLoggingContext ctx = contexts.remove(profileName);
            if (ctx == null) {
                return false;
            }
            ctx.remove(factoryForDispose());
            return true;
        } finally {
            managerLock.unlock();
        }
    }

    private Log4jContextFactory factoryForDispose() {
        if (factory != null) {
            return factory;
        }
        return (Log4jContextFactory) LogManager.getFactory();
    }
}