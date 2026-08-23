package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Per-profile logger instance. Each NodeProfile or DatabaseProfile gets its own
 * ProfileLogger, enabling module-specific and profile-specific log routing.
 * <p>
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Create instance with moduleId + profileName</li>
 *   <li>Add subscribers (GUI consoles, file appenders, etc.)</li>
 *   <li>Log events via convenience methods or {@link #log(LogLevel, String)}</li>
 *   <li>Call {@link #close()} when the profile is shut down</li>
 * </ol>
 * </p>
 * <p>
 * <h3>Runtime Swapping</h3>
 * A ProfileLogger can be replaced at runtime by updating the profile's logger field.
 * This enables dynamic log level changes without restarting the node.
 * </p>
 * <p>
 * <h3>Forwarding to SystemLogger</h3>
 * By default, every ProfileLogger automatically forwards events to {@link SystemLogger}
 * so the System Console sees all logs. Use {@link #setForwardToSystem(boolean)} to control this.
 * </p>
 * <p>
 * <h3>Replay for Late-Attaching Subscribers</h3>
 * The most recent {@link #DEFAULT_REPLAY_CAPACITY} events (configurable via the
 * 3-argument constructor) are retained in a bounded buffer. When a subscriber attaches
 * via {@link #addSubscriber(LogSubscriber)} — e.g. a GUI console tab opened after the
 * node already started — it immediately receives the buffered history (oldest first)
 * before live events continue. Buffer mutation and the subscribe/replay sequence are
 * ordered under a single lock, so a late subscriber observes <b>no gaps, no duplicates</b>,
 * and events arrive in chronological order.
 * </p>
 *
 * @see NodeProfile
 * @see ModuleLogger
 * @see SystemLogger
 */
public final class ProfileLogger extends LoggerImpl {

    /**
     * Default number of recent events retained for replay to late-attaching
     * subscribers. Aligned with the GUI console default line cap
     * ({@code ProfileConsoleSubscriber.DEFAULT_MAX_LINES}).
     */
    public static final int DEFAULT_REPLAY_CAPACITY = 500;

    private final String moduleId;
    private final String profileName;
    private volatile boolean forwardToSystem = true;

    // ── Replay buffer (late-subscriber support) ───────────────────────────

    /** Configured maximum number of events retained for replay (always > 0). */
    private final int replayCapacity;

    /**
     * Guards the replay buffer and orders {@link #dispatch(LogEvent)} against
     * {@link #addSubscriber(LogSubscriber)} so replay is gap- and duplicate-free.
     */
    private final Object replayLock = new Object();

    /**
     * Bounded ring of recent events (oldest → newest).
     * Accessed only while holding {@link #replayLock}.
     */
    private final ArrayDeque<LogEvent> replayBuffer = new ArrayDeque<>();

    /**
     * Creates a new ProfileLogger for the given module and profile with the
     * default replay capacity ({@link #DEFAULT_REPLAY_CAPACITY}).
     *
     * @param moduleId    the module identifier (e.g., "node", "database"), never null or empty
     * @param profileName the unique profile name within that module, never null or empty
     * @throws IllegalArgumentException if either parameter is null or empty
     */
    public ProfileLogger(String moduleId, String profileName) {
        this(moduleId, profileName, DEFAULT_REPLAY_CAPACITY);
    }

    /**
     * Creates a new ProfileLogger for the given module and profile.
     * <p>
     * The logger name is auto-generated as {@code moduleId.profileName}.
     * </p>
     *
     * @param moduleId     the module identifier (e.g., "node", "database"), never null or empty
     * @param profileName  the unique profile name within that module, never null or empty
     * @param replayCapacity maximum number of recent events retained for replay
     *                       to late-attaching subscribers (must be &gt; 0)
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public ProfileLogger(String moduleId, String profileName, int replayCapacity) {
        super(buildName(moduleId, profileName));
        this.moduleId = Objects.requireNonNull(moduleId, "Module ID must not be null");
        if (moduleId.isEmpty()) {
            throw new IllegalArgumentException("Module ID must not be empty");
        }
        this.profileName = Objects.requireNonNull(profileName, "Profile name must not be null");
        if (profileName.isEmpty()) {
            throw new IllegalArgumentException("Profile name must not be empty");
        }
        if (replayCapacity <= 0) {
            throw new IllegalArgumentException("Replay capacity must be positive, got: " + replayCapacity);
        }
        this.replayCapacity = replayCapacity;
    }

    /**
     * Builds the logger name as "moduleId.profileName".
     */
    private static String buildName(String moduleId, String profileName) {
        return moduleId + "." + profileName;
    }

    /** @return the module identifier (e.g., "node", "database") */
    public String getModuleId() {
        return moduleId;
    }

    /** @return the profile name within the module */
    public String getProfileName() {
        return profileName;
    }

    /** @return the maximum number of events retained for replay (always &gt; 0) */
    public int getReplayCapacity() {
        return replayCapacity;
    }

    /** @return the number of events currently retained in the replay buffer */
    public int getReplayBufferSize() {
        synchronized (replayLock) {
            return replayBuffer.size();
        }
    }

    /**
     * Returns true if this logger forwards events to SystemLogger.
     */
    public boolean isForwardToSystem() {
        return forwardToSystem;
    }

    /**
     * Controls whether log events from this profile are also forwarded to SystemLogger.
     * Default is true so System Console sees all logs.
     *
     * @param forward true to enable forwarding (default), false to disable
     */
    public void setForwardToSystem(boolean forward) {
        this.forwardToSystem = forward;
    }

    @Override
    protected void dispatch(LogEvent event) {
        // Buffer the event and dispatch to this profile's subscribers atomically
        // w.r.t. addSubscriber(): this is what guarantees a late subscriber either
        // sees the event in the replay snapshot (if it was appended before the
        // subscribe) or receives it live (if it was appended after). No gaps,
        // no duplicates, chronological order.
        synchronized (replayLock) {
            replayBuffer.addLast(event);
            while (replayBuffer.size() > replayCapacity) {
                replayBuffer.removeFirst();
            }
            super.dispatch(event);
        }

        // Then forward to SystemLogger for unified viewing
        if (forwardToSystem) {
            try {
                SystemLogger.getInstance().dispatch(event);
            } catch (Exception e) {
                System.err.println("[ProfileLogger] Forward error in '" + name + "': " + e.getMessage());
            }
        }
    }

    /**
     * Attaches a subscriber and, if events were logged before the attachment,
     * immediately replays the buffered history (oldest first) to the new
     * subscriber before live events continue.
     * <p>
     * Idempotent: attaching the same subscriber instance twice is a no-op.
     * </p>
     *
     * @param subscriber the subscriber to attach (never null)
     */
    @Override
    public void addSubscriber(LogSubscriber subscriber) {
        if (subscriber == null) {
            throw new NullPointerException("Subscriber must not be null");
        }
        if (isClosed()) {
            return;
        }
        List<LogEvent> snapshot;
        synchronized (replayLock) {
            if (subscribers.contains(subscriber)) {
                return; // already attached — idempotent no-op
            }
            snapshot = replayBuffer.isEmpty() ? null : new ArrayList<>(replayBuffer);
            super.addSubscriber(subscriber);
            // Replay inside the lock: concurrent dispatches from other threads
            // are held until the replay burst completes, so the new subscriber
            // sees history strictly before live events (chronological order).
            if (snapshot != null) {
                for (LogEvent buffered : snapshot) {
                    try {
                        subscriber.onLogEvent(buffered);
                    } catch (Exception e) {
                        System.err.println("[ProfileLogger] Replay error in '" + name + "': " + e.getMessage());
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return "ProfileLogger{"
                + "module='" + moduleId + '\''
                + ", profile='" + profileName + '\''
                + ", level=" + minLevel
                + ", subscribers=" + subscribers.size()
                + ", replayBuffer=" + getReplayBufferSize() + "/" + replayCapacity
                + ", forwardToSystem=" + forwardToSystem
                + ", closed=" + isClosed()
                + '}';
    }
}