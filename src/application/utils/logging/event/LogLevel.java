package application.utils.logging.event;

/**
 * Unified log level enum providing mapping between SLF4J levels.
 * <p>
 * This enum normalizes the different severity representations used across
 * SLF4J/chains into a single comparable type for filtering and display purposes.
 * </p>
 */
public enum LogLevel {

    TRACE(5, "TRACE"),
    DEBUG(10, "DEBUG"),
    INFO(20, "INFO"),
    WARN(30, "WARN"),
    ERROR(40, "ERROR"),
    OFF(100, "OFF");

    private final int severity;
    private final String displayName;

    LogLevel(int severity, String displayName) {
        this.severity = severity;
        this.displayName = displayName;
    }

    /**
     * Returns the numeric severity value (lower = more verbose).
     */
    public int getSeverity() {
        return severity;
    }

    /**
     * Returns the human-readable display name.
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns true if this level is at least as severe as the given level.
     * Example: ERROR.isAtLeast(WARN) → true
     */
    public boolean isAtLeast(LogLevel other) {
        return this.severity >= other.severity;
    }

    /**
     * Converts a SLF4J level name string to LogLevel.
     * Handles common variations: "trace", "debug", "info", "warn", "error", "off".
     *
     * @param name the level name (case-insensitive)
     * @return the mapped LogLevel, or INFO if unrecognized
     */
    public static LogLevel fromSlf4jName(String name) {
        if (name == null) {
            return INFO;
        }
        switch (name.toLowerCase()) {
            case "trace": return TRACE;
            case "debug": return DEBUG;
            case "info":  return INFO;
            case "warn":  return WARN;
            case "error": return ERROR;
            case "off":   return OFF;
            default:      return INFO;
        }
    }

}