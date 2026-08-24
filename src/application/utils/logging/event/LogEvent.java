package application.utils.logging.event;

import application.utils.logging.LogScope;

import java.util.Arrays;
import java.util.logging.LogRecord;

/**
 * Immutable data class representing a single log event in the routing system.
 * <p>
 * Acts as the common currency between the logging framework (JUL/SLF4J) and
 * the GUI display layer. Created from JUL {@link LogRecord} instances via
 * {@link #from(LogRecord)}, or directly for synthetic events (e.g., bootstrap flush).
 * </p>
 * <p>
 * <h3>Lazy Formatting</h3>
 * The rendered text is computed on-demand via {@link #getRenderedText(LogFormatter)}
 * and cached using double-checked locking for thread safety with minimal synchronization overhead.
 * </p>
 *
 * @see LogRecord
 * @see LogFormatter
 */
public final class LogEvent {

    private final long timestamp;
    private final LogLevel level;
    private final String loggerName;
    private final String message;
    private final String sourceClassName;
    private final String sourceMethodName;
    private final int sourceLineNumber;
    private final String threadName;
    private final String profileName;
    private final String module;
    private final Throwable throwable;
    private final Object[] parameters;

    // Cached rendered text (double-checked locking)
    private volatile String cachedRenderedText;
    private volatile LogFormatter cachedFormatter;

    private LogEvent(Builder builder) {
        this.timestamp = builder.timestamp;
        this.level = builder.level;
        this.loggerName = builder.loggerName;
        this.message = builder.message;
        this.sourceClassName = builder.sourceClassName;
        this.sourceMethodName = builder.sourceMethodName;
        this.sourceLineNumber = builder.sourceLineNumber;
        this.threadName = builder.threadName;
        this.profileName = builder.profileName;
        this.module = builder.module;
        this.throwable = builder.throwable;
        this.parameters = builder.parameters != null ? builder.parameters.clone() : null;
    }

    /**
     * Creates a LogEvent from a JUL LogRecord.
     * <p>
     * This is the primary factory method used by ProfileLogRouter to convert
     * framework log records into our routing-friendly format.
     * </p>
     *
     * @param record the JUL LogRecord to convert (never null)
     * @return a new immutable LogEvent
     * @throws NullPointerException if record is null
     */
    public static LogEvent from(LogRecord record) {
        if (record == null) {
            throw new NullPointerException("LogRecord must not be null");
        }
        Builder builder = new Builder()
                .timestamp(record.getMillis())
                .level(LogLevel.fromJul(record.getLevel()))
                .loggerName(record.getLoggerName())
                .message(record.getMessage())
                .threadName(Thread.currentThread().getName());

        // Extract source info if available
        if (record.getSourceClassName() != null) {
            builder.sourceClassName(record.getSourceClassName());
        }
        if (record.getSourceMethodName() != null) {
            builder.sourceMethodName(record.getSourceMethodName());
        }
        // Note: LogRecord does not expose line number directly; it remains at default -1

        // Extract parameters from LogRecord
        Object[] params = record.getParameters();
        if (params != null && params.length > 0) {
            builder.parameters(params.clone());
        }

        // Extract throwable
        if (record.getThrown() != null) {
            builder.throwable(record.getThrown());
        }

        return builder.build();
    }

    /**
     * Creates a simple text-based LogEvent for raw log lines (e.g., bootstrap flush).
     *
     * @param text the raw log text
     * @return a new LogEvent with INFO level and no metadata
     */
    public static LogEvent fromText(String text) {
        return new Builder()
                .timestamp(System.currentTimeMillis())
                .level(LogLevel.INFO)
                .message(text)
                .threadName(Thread.currentThread().getName())
                .build();
    }

    /** @return the event timestamp in milliseconds since epoch */
    public long getTimestamp() {
        return timestamp;
    }

    /** @return the unified log level */
    public LogLevel getLevel() {
        return level;
    }

    /** @return the logger name (e.g., "signum.node") or null */
    public String getLoggerName() {
        return loggerName;
    }

    /** @return the raw message template (may contain {} placeholders) */
    public String getMessage() {
        return message;
    }

    /** @return the source class name, or null if unknown */
    public String getSourceClassName() {
        return sourceClassName;
    }

    /** @return the source method name, or null if unknown */
    public String getSourceMethodName() {
        return sourceMethodName;
    }

    /** @return the source line number, or -1 if unknown */
    public int getSourceLineNumber() {
        return sourceLineNumber;
    }

    /** @return the thread name that produced this event */
    public String getThreadName() {
        return threadName;
    }

    /** @return the profile name associated with this event, or null if unassigned */
    public String getProfileName() {
        return profileName;
    }

    /**
     * @return the module this event belongs to (e.g. "node", "database"), or null if unscoped
     */
    public String getModule() {
        return module;
    }

    /**
     * The composite {@code (module, profile)} scope of this event, or null if either part is
     * absent. This is the collision-safe identifier used for routing, registry lookup, and display.
     *
     * @return the scope, or null
     */
    public LogScope getScope() {
        return (module != null && profileName != null) ? LogScope.of(module, profileName) : null;
    }

    /**
     * The qualified {@code module.profile} name, or the bare profile name if no module is set,
     * or null if neither is set. Suitable for display (e.g. {@code <node.mainnet>}).
     *
     * @return the qualified name, or the bare profile name, or null
     */
    public String getQualifiedName() {
        if (module != null && profileName != null) {
            return module + "." + profileName;
        }
        return profileName;
    }

    /**
     * Returns a new builder pre-populated with this event's values, for creating a modified copy
     * (e.g. stamping the module/profile scope onto an event).
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        Builder b = new Builder()
                .timestamp(timestamp)
                .level(level)
                .loggerName(loggerName)
                .message(message)
                .sourceClassName(sourceClassName)
                .sourceMethodName(sourceMethodName)
                .sourceLineNumber(sourceLineNumber)
                .threadName(threadName)
                .profileName(profileName)
                .module(module);
        if (throwable != null) {
            b.throwable(throwable);
        }
        if (parameters != null) {
            b.parameters(parameters);
        }
        return b;
    }

    /** @return the associated throwable, or null if none */
    public Throwable getThrowable() {
        return throwable;
    }

    /** @return the parameter array for message formatting, or null if none */
    public Object[] getParameters() {
        return parameters != null ? parameters.clone() : null;
    }

    /**
     * Returns the fully formatted, display-ready text for this event.
     * <p>
     * Uses lazy evaluation with double-checked locking: formatting only occurs
     * once per formatter instance, then the result is cached in a volatile field.
     * </p>
     *
     * @param formatter the formatter to use (never null)
     * @return the rendered text string (never null)
     */
    public String getRenderedText(LogFormatter formatter) {
        if (cachedRenderedText != null && cachedFormatter == formatter) {
            return cachedRenderedText;
        }
        synchronized (this) {
            if (cachedRenderedText != null && cachedFormatter == formatter) {
                return cachedRenderedText;
            }
            cachedRenderedText = formatter.format(this);
            cachedFormatter = formatter;
            return cachedRenderedText;
        }
    }

    @Override
    public String toString() {
        return level + " [" + threadName + "] " + loggerName + ": " + message;
    }

    /**
     * Fluent builder for constructing LogEvent instances.
     */
    public static class Builder {
        private long timestamp;
        private LogLevel level = LogLevel.INFO;
        private String loggerName;
        private String message;
        private String sourceClassName;
        private String sourceMethodName;
        private int sourceLineNumber = -1;
        private String threadName;
        private String profileName;
        private String module;
        private Throwable throwable;
        private Object[] parameters;

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder level(LogLevel level) {
            this.level = level;
            return this;
        }

        public Builder loggerName(String loggerName) {
            this.loggerName = loggerName;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder sourceClassName(String sourceClassName) {
            this.sourceClassName = sourceClassName;
            return this;
        }

        public Builder sourceMethodName(String sourceMethodName) {
            this.sourceMethodName = sourceMethodName;
            return this;
        }

        public Builder sourceLineNumber(int sourceLineNumber) {
            this.sourceLineNumber = sourceLineNumber;
            return this;
        }

        public Builder threadName(String threadName) {
            this.threadName = threadName;
            return this;
        }

        public Builder profileName(String profileName) {
            this.profileName = profileName;
            return this;
        }

        public Builder module(String module) {
            this.module = module;
            return this;
        }

        public Builder throwable(Throwable throwable) {
            this.throwable = throwable;
            return this;
        }

        public Builder parameters(Object[] parameters) {
            this.parameters = parameters != null ? parameters.clone() : null;
            return this;
        }

        public LogEvent build() {
            return new LogEvent(this);
        }
    }
}