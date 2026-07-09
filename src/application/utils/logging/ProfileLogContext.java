package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogSubscriber;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-profile logging context providing isolated log event routing to subscribers.
 * <p>
 * Each running NodeProfile gets its own ProfileLogContext instance:
 * </p>
 * <ul>
 *   <li>Routes log events ONLY to this profile's GUI console subscribers</li>
 *   <li>Provides filtering (by level, module, text) via subscriber-level filters</li>
 *   <li>Manages subscriber lifecycle (add/remove/dispose)</li>
 * </ul>
 * <p>
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Create instance with profile name</li>
 *   <li>Call {@link #start()} to register with the global {@link ProfileLogRouter}</li>
 *   <li>Add subscribers via {@link #addSubscriber(LogSubscriber)}</li>
 *   <li>Call {@link #close()} to unregister and clean up (AutoCloseable)</li>
 * </ol>
 * </p>
 * <p>
 * Performance: O(n) subscriber iteration where n is typically small (1-3 per profile).
 * Thread-safe: Safe for concurrent dispatch from multiple threads via CopyOnWriteArrayList.
 * </p>
 *
 * @see ProfileLogRouter
 * @see LogSubscriber
 * @see LogEvent
 */
public final class ProfileLogContext implements AutoCloseable {

    private final String profileName;
    private final CopyOnWriteArrayList<LogSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private volatile boolean active = false;

    /**
     * Creates a new context for the given profile.
     * <p>
     * The context is inactive until {@link #start()} is called.
     * </p>
     *
     * @param profileName the unique profile name (e.g., "mainnet-prune")
     */
    public ProfileLogContext(String profileName) {
        if (profileName == null || profileName.isEmpty()) {
            throw new IllegalArgumentException("Profile name must not be null or empty");
        }
        this.profileName = profileName;
    }

    /** @return the unique profile name */
    public String getProfileName() {
        return profileName;
    }

    /** @return true if this context is registered with the global router */
    public boolean isActive() {
        return active;
    }

    /**
     * Activates this context by registering it with the global ProfileLogRouter.
     * <p>
     * Must be called before log events will be dispatched to subscribers.
     * Idempotent: calling multiple times is safe.
     * </p>
     */
    public void start() {
        if (active) {
            return;
        }
        ProfileLogRouter.getInstance().registerContext(this);
        active = true;
    }

    /**
     * Adds a subscriber to receive log events for this profile.
     *
     * @param subscriber the subscriber to add (never null)
     */
    public void addSubscriber(LogSubscriber subscriber) {
        if (subscriber == null) {
            throw new NullPointerException("Subscriber must not be null");
        }
        subscribers.add(subscriber);
    }

    /**
     * Removes a subscriber. The subscriber's {@link LogSubscriber#dispose()} is called.
     *
     * @param subscriber the subscriber to remove
     * @return true if the subscriber was found and removed
     */
    public boolean removeSubscriber(LogSubscriber subscriber) {
        boolean removed = subscribers.remove(subscriber);
        if (removed) {
            subscriber.dispose();
        }
        return removed;
    }

    /**
     * Returns an unmodifiable snapshot of all registered subscribers.
     */
    public List<LogSubscriber> getSubscribers() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(subscribers));
    }

    /**
     * Called by ProfileLogRouter when a log event occurs for this profile.
     * Not intended for direct external use.
     *
     * @param event the log event to dispatch (never null)
     */
    void dispatch(LogEvent event) {
        for (LogSubscriber subscriber : subscribers) {
            try {
                LogFilter filter = subscriber.getFilter();
                if (filter == null || filter.matches(event)) {
                    subscriber.onLogEvent(event);
                }
            } catch (Exception e) {
                // Protect one subscriber from breaking others
                System.err.println("[ProfileLogContext] Subscriber error in '" + profileName + "': " + e.getMessage());
            }
        }
    }

    /**
     * Dispatches a raw text line as a simple LogEvent to all subscribers.
     * Used by BootstrapLogBuffer flush and other synthetic event sources.
     *
     * @param text the raw log text (never null)
     */
    void dispatchText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        dispatch(LogEvent.fromText(text));
    }

    /**
     * Closes this context: unregisters from the global router,
     * disposes all subscribers, and clears internal state.
     */
    @Override
    public void close() {
        if (!active) {
            return;
        }
        ProfileLogRouter.getInstance().unregisterContext(profileName);
        active = false;

        // Dispose all subscribers
        for (LogSubscriber subscriber : subscribers) {
            try {
                subscriber.dispose();
            } catch (Exception e) {
                System.err.println("[ProfileLogContext] Dispose error in '" + profileName + "': " + e.getMessage());
            }
        }
        subscribers.clear();
    }

    @Override
    public String toString() {
        return "ProfileLogContext{" +
                "profile='" + profileName + '\'' +
                ", active=" + active +
                ", subscribers=" + subscribers.size() +
                '}';
    }
}