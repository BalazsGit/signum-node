package application.utils.logging.event;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Filter that includes or excludes log events based on a regex pattern matched
 * against the event's rendered message text.
 * <p>
 * Useful for searching the SystemConsole output for specific keywords, error
 * messages, or any text pattern. The regex is compiled once at construction time
 * for efficient repeated matching.
 * </p>
 * <p>
 * <h3>Search Scope</h3>
 * By default, the pattern is matched against {@link LogEvent#getMessage()}.
 * Use {@link #matchingAllFields(Pattern)} to search across all text fields
 * (message, logger name, thread name, profile name).
 * </p>
 * <p>
 * Thread-safe: Immutable after construction.
 * </p>
 *
 * @example
 * <pre>{@code
 * // Case-insensitive search for "connection refused"
 * LogFilter filter = TextSearchFilter.including("(?i)connection refused");
 *
 * // Exclude any log containing the word "deprecated"
 * LogFilter filter = TextSearchFilter.excluding("deprecated");
 *
 * // Find all stack traces (events with line numbers)
 * LogFilter filter = TextSearchFilter.including(Pattern.compile("at\\s+\\w+\\.\\w+"));
 * }</pre>
 *
 * @see Pattern
 * @see LogEvent#getMessage()
 * @see LogFilter
 */
public final class TextSearchFilter implements LogFilter {

    private final Pattern pattern;
    private final boolean includeMode;
    private final SearchScope scope;

    /**
     * Defines which fields of the log event to search.
     */
    public enum SearchScope {
        /** Only search the message field (default, fastest). */
        MESSAGE_ONLY,

        /** Search message + logger name. */
        MESSAGE_AND_LOGGER,

        /** Search all text fields: message, logger, thread, profile. */
        ALL_FIELDS
    }

    private TextSearchFilter(Pattern pattern, boolean includeMode, SearchScope scope) {
        this.pattern = pattern;
        this.includeMode = includeMode;
        this.scope = scope;
    }

    /**
     * Creates a filter that matches events whose message contains the given literal text.
     * <p>
     * The search is case-sensitive and treats the input as a literal string (not regex).
     * </p>
     *
     * @param text the literal text to search for
     * @return a new TextSearchFilter in inclusion mode
     */
    public static TextSearchFilter includingLiteral(String text) {
        Objects.requireNonNull(text, "Search text must not be null");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Search text must not be empty");
        }
        return new TextSearchFilter(Pattern.compile(Pattern.quote(text)), true, SearchScope.MESSAGE_ONLY);
    }

    /**
     * Creates a filter that matches events whose message matches the given regex pattern.
     *
     * @param regex the regular expression pattern
     * @return a new TextSearchFilter in inclusion mode
     * @throws IllegalArgumentException if the regex is invalid
     */
    public static TextSearchFilter including(String regex) {
        Objects.requireNonNull(regex, "Regex pattern must not be null");
        if (regex.isEmpty()) {
            throw new IllegalArgumentException("Regex pattern must not be empty");
        }
        return new TextSearchFilter(Pattern.compile(regex), true, SearchScope.MESSAGE_ONLY);
    }

    /**
     * Creates a filter using a pre-compiled Pattern.
     *
     * @param pattern the compiled pattern to match against
     * @return a new TextSearchFilter in inclusion mode
     */
    public static TextSearchFilter including(Pattern pattern) {
        Objects.requireNonNull(pattern, "Pattern must not be null");
        return new TextSearchFilter(pattern, true, SearchScope.MESSAGE_ONLY);
    }

    /**
     * Creates a filter that blocks events whose message matches the given regex pattern.
     *
     * @param regex the regular expression pattern
     * @return a new TextSearchFilter in exclusion mode
     * @throws IllegalArgumentException if the regex is invalid
     */
    public static TextSearchFilter excluding(String regex) {
        Objects.requireNonNull(regex, "Regex pattern must not be null");
        if (regex.isEmpty()) {
            throw new IllegalArgumentException("Regex pattern must not be empty");
        }
        return new TextSearchFilter(Pattern.compile(regex), false, SearchScope.MESSAGE_ONLY);
    }

    /**
     * Creates a filter that searches all text fields of the log event.
     * <p>
     * This includes: message, logger name, thread name, and profile name.
     * </p>
     *
     * @param regex the regular expression pattern
     * @return a new TextSearchFilter in inclusion mode searching all fields
     */
    public static TextSearchFilter matchingAllFields(String regex) {
        Objects.requireNonNull(regex, "Regex pattern must not be null");
        if (regex.isEmpty()) {
            throw new IllegalArgumentException("Regex pattern must not be empty");
        }
        return new TextSearchFilter(Pattern.compile(regex), true, SearchScope.ALL_FIELDS);
    }

    /**
     * Creates a filter with the specified scope.
     *
     * @param regex  the regular expression pattern
     * @param scope  which fields to search
     * @return a new TextSearchFilter in inclusion mode
     */
    public static TextSearchFilter custom(String regex, SearchScope scope) {
        Objects.requireNonNull(regex, "Regex pattern must not be null");
        Objects.requireNonNull(scope, "Scope must not be null");
        if (regex.isEmpty()) {
            throw new IllegalArgumentException("Regex pattern must not be empty");
        }
        return new TextSearchFilter(Pattern.compile(regex), true, scope);
    }

    @Override
    public boolean matches(LogEvent event) {
        if (event == null) {
            return false;
        }

        boolean matched;
        switch (scope) {
            case MESSAGE_ONLY:
                matched = matchString(event.getMessage());
                break;
            case MESSAGE_AND_LOGGER:
                matched = matchString(event.getMessage()) || matchString(event.getLoggerName());
                break;
            case ALL_FIELDS:
                matched = matchString(event.getMessage())
                        || matchString(event.getLoggerName())
                        || matchString(event.getThreadName())
                        || matchString(event.getProfileName());
                break;
            default:
                matched = false;
        }

        return includeMode ? matched : !matched;
    }

    private boolean matchString(String text) {
        return text != null && pattern.matcher(text).find();
    }

    /** @return the compiled regex pattern */
    public Pattern getPattern() {
        return pattern;
    }

    /** @return true if this filter includes matching events (vs excludes them) */
    public boolean isIncludeMode() {
        return includeMode;
    }

    /** @return which fields are searched */
    public SearchScope getScope() {
        return scope;
    }

    @Override
    public String toString() {
        return "TextSearchFilter{" +
                (includeMode ? "include" : "exclude") + ", pattern=" + pattern.pattern() +
                ", scope=" + scope +
                '}';
    }
}