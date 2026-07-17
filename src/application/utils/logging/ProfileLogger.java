package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;

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
 *
 * @see NodeProfile
 * @see ModuleLogger
 * @see SystemLogger
 */
public final class ProfileLogger extends LoggerImpl {

    private final String moduleId;
    private final String profileName;
    private volatile boolean forwardToSystem = true;

    /**
     * Creates a new ProfileLogger for the given module and profile.
     * <p>
     * The logger name is auto-generated as {@code moduleId.profileName}.
     * </p>
     *
     * @param moduleId    the module identifier (e.g., "node", "database"), never null or empty
     * @param profileName the unique profile name within that module, never null or empty
     * @throws IllegalArgumentException if either parameter is null or empty
     */
    public ProfileLogger(String moduleId, String profileName) {
        super(buildName(moduleId, profileName));
        this.moduleId = Objects.requireNonNull(moduleId, "Module ID must not be null");
        if (moduleId.isEmpty()) {
            throw new IllegalArgumentException("Module ID must not be empty");
        }
        this.profileName = Objects.requireNonNull(profileName, "Profile name must not be null");
        if (profileName.isEmpty()) {
            throw new IllegalArgumentException("Profile name must not be empty");
        }
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
        // Dispatch to this profile's subscribers first
        super.dispatch(event);

        // Then forward to SystemLogger for unified viewing
        if (forwardToSystem) {
            try {
                SystemLogger.getInstance().dispatch(event);
            } catch (Exception e) {
                System.err.println("[ProfileLogger] Forward error in '" + name + "': " + e.getMessage());
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
                + ", forwardToSystem=" + forwardToSystem
                + ", closed=" + isClosed()
                + '}';
    }
}