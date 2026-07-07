package application.module.node.instance;

import org.slf4j.event.Level;

/**
 * Thrown when a node instance fails to start.
 * <p>
 * This exception carries both the failure reason and an optional severity level,
 * enabling callers to decide whether to abort entirely or attempt recovery.
 *
 * @since 4.0
 */
public class NodeStartupException extends RuntimeException {

    /** Severity level associated with this startup failure. */
    private final Level severity;

    /** Optional profile name that failed to start. */
    private final String profileName;

    /**
     * Constructs a new startup exception with default ERROR severity.
     *
     * @param message the detail message
     */
    public NodeStartupException(String message) {
        this(message, Level.ERROR, null);
    }

    /**
     * Constructs a new startup exception with the specified severity.
     *
     * @param message  the detail message
     * @param severity the severity level of the failure
     */
    public NodeStartupException(String message, Level severity) {
        this(message, severity, null);
    }

    /**
     * Constructs a new startup exception with full context.
     *
     * @param message     the detail message
     * @param severity    the severity level of the failure
     * @param profileName the profile that failed (may be null)
     */
    public NodeStartupException(String message, Level severity, String profileName) {
        this(message, null, severity, profileName);
    }

    /**
     * Constructs a new startup exception caused by another throwable.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public NodeStartupException(String message, Throwable cause) {
        this(message, cause, Level.ERROR, null);
    }

    /**
     * Constructs a new startup exception with full context and cause.
     *
     * @param message     the detail message
     * @param cause       the underlying cause
     * @param severity    the severity level of the failure
     * @param profileName the profile that failed (may be null)
     */
    public NodeStartupException(String message, Throwable cause, Level severity, String profileName) {
        super(message, cause);
        this.severity = severity != null ? severity : Level.ERROR;
        this.profileName = profileName;
    }

    /**
     * Returns the severity level of this failure.
     */
    public Level getSeverity() {
        return severity;
    }

    /**
     * Returns the profile name that failed to start, or null if unknown.
     */
    public String getProfileName() {
        return profileName;
    }
}