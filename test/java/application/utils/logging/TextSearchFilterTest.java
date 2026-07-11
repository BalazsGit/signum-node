package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.TextSearchFilter;
import application.utils.logging.event.TextSearchFilter.SearchScope;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TextSearchFilter}.
 * <p>
 * Verifies literal/regex matching, SearchScope variants (MESSAGE_ONLY, MESSAGE_AND_LOGGER, ALL_FIELDS),
 * inclusion/exclusion modes, and input validation.
 * </p>
 */
@DisplayName("TextSearchFilter Tests")
class TextSearchFilterTest {

    private LogEvent createEvent(String message) {
        return new LogEvent.Builder()
                .level(LogLevel.INFO)
                .message(message)
                .build();
    }

    private LogEvent createFullEvent(String message, String loggerName, String threadName, String profileName) {
        return new LogEvent.Builder()
                .level(LogLevel.INFO)
                .message(message)
                .loggerName(loggerName)
                .threadName(threadName)
                .profileName(profileName)
                .build();
    }

    // ------------------------ includingLiteral() ------------------------

    @Nested
    @DisplayName("includingLiteral() - literal text search")
    class IncludingLiteralTests {

        @Test
        @DisplayName("exact substring match passes")
        void includingLiteral_GivenExactMatch_ReturnsTrue() {
            LogFilter filter = TextSearchFilter.includingLiteral("connection refused");
            assertTrue(filter.matches(createEvent("Failed: connection refused to host")));
        }

        @Test
        @DisplayName("case-sensitive: different case does not match")
        void includingLiteral_CaseSensitive_NoMatch() {
            LogFilter filter = TextSearchFilter.includingLiteral("ERROR");
            assertFalse(filter.matches(createEvent("error occurred")));
            assertTrue(filter.matches(createEvent("an ERROR occurred")));
        }

        @Test
        @DisplayName("special regex characters are escaped (literal search)")
        void includingLiteral_SpecialChars_TreatedAsLiteral() {
            // The dot is a regex wildcard, but includingLiteral escapes it
            LogFilter filter = TextSearchFilter.includingLiteral("price: $10.99");
            assertTrue(filter.matches(createEvent("The price: $10.99 was correct")));
            // Should NOT match "price: $10X99" since dot is escaped
            assertFalse(filter.matches(createEvent("The price: $10X99 was wrong")));
        }

        @Test
        @DisplayName("null text throws NullPointerException")
        void includingLiteral_GivenNull_ThrowsNPE() {
            assertThrows(NullPointerException.class, () -> TextSearchFilter.includingLiteral(null));
        }

        @Test
        @DisplayName("empty text throws IllegalArgumentException")
        void includingLiteral_GivenEmptyString_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> TextSearchFilter.includingLiteral(""));
        }
    }

    // ------------------------ including(String regex) ------------------------

    @Nested
    @DisplayName("including(String regex) - regex search")
    class IncludingRegexTests {

        @Test
        @DisplayName("case-insensitive regex matches")
        void including_CaseInsensitivePattern_Matches() {
            LogFilter filter = TextSearchFilter.including("(?i)connection refused");

            assertTrue(filter.matches(createEvent("Connection refused")));
            assertTrue(filter.matches(createEvent("CONNECTION REFUSED")));
            assertTrue(filter.matches(createEvent("connection Refused to host")));
        }

        @Test
        @DisplayName("regex with wildcards matches patterns")
        void including_WildcardPattern_Matches() {
            LogFilter filter = TextSearchFilter.including("Error.*timeout");

            assertTrue(filter.matches(createEvent("Error: connection timeout after 30s")));
            assertFalse(filter.matches(createEvent("Error: connection refused")));
        }

        @Test
        @DisplayName("stack trace pattern matches method calls")
        void including_StackTracePattern_Matches() {
            LogFilter filter = TextSearchFilter.including("at\\s+\\w+\\.\\w+");

            assertTrue(filter.matches(createEvent("at com.example.MyClass.myMethod(MyClass.java:42)")));
            assertFalse(filter.matches(createEvent("Simple log message")));
        }

        @Test
        @DisplayName("invalid regex throws IllegalArgumentException")
        void including_InvalidRegex_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> TextSearchFilter.including("[invalid"));
        }

        @Test
        @DisplayName("null regex throws NullPointerException")
        void including_GivenNull_ThrowsNPE() {
            assertThrows(NullPointerException.class, () -> TextSearchFilter.including((String) null));
        }

        @Test
        @DisplayName("empty regex throws IllegalArgumentException")
        void including_GivenEmptyString_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> TextSearchFilter.including(""));
        }
    }

    // ------------------------ including(Pattern) ------------------------

    @Nested
    @DisplayName("including(Pattern) - pre-compiled Pattern")
    class IncludingPatternTests {

        @Test
        @DisplayName("pre-compiled pattern works correctly")
        void including_GivenPreCompiledPattern_Matches() {
            Pattern pattern = Pattern.compile("(?i)deprecated");
            LogFilter filter = TextSearchFilter.including(pattern);

            assertTrue(filter.matches(createEvent("This method is Deprecated since v2")));
            assertFalse(filter.matches(createEvent("This method is active")));
        }

        @Test
        @DisplayName("null Pattern throws NullPointerException")
        void including_GivenNullPattern_ThrowsNPE() {
            assertThrows(NullPointerException.class, () -> TextSearchFilter.including((Pattern) null));
        }
    }

    // ------------------------ excluding() ------------------------

    @Nested
    @DisplayName("excluding(String regex) - exclusion mode")
    class ExcludingTests {

        @Test
        @DisplayName("matching pattern is blocked")
        void excluding_GivenMatchingPattern_ReturnsFalse() {
            LogFilter filter = TextSearchFilter.excluding("deprecated");
            assertFalse(filter.matches(createEvent("This API is deprecated since v1")));
        }

        @Test
        @DisplayName("non-matching pattern passes")
        void excluding_GivenNonMatchingPattern_ReturnsTrue() {
            LogFilter filter = TextSearchFilter.excluding("deprecated");
            assertTrue(filter.matches(createEvent("This API is stable")));
        }

        @Test
        @DisplayName("null event returns false")
        void excluding_GivenNullEvent_ReturnsFalse() {
            LogFilter filter = TextSearchFilter.excluding("test");
            assertFalse(filter.matches(null));
        }

        @Test
        @DisplayName("null regex throws NullPointerException")
        void excluding_GivenNull_ThrowsNPE() {
            assertThrows(NullPointerException.class, () -> TextSearchFilter.excluding(null));
        }

        @Test
        @DisplayName("empty regex throws IllegalArgumentException")
        void excluding_GivenEmptyString_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> TextSearchFilter.excluding(""));
        }
    }

    // ------------------------ SearchScope variants ------------------------

    @Nested
    @DisplayName("SearchScope variants")
    class SearchScopeTests {

        @Test
        @DisplayName("MESSAGE_ONLY: only searches message field")
        void matching_MessageOnly_OnlySearchesMessage() {
            LogFilter filter = TextSearchFilter.custom("database", SearchScope.MESSAGE_ONLY);

            // Pattern in message → match
            assertTrue(filter.matches(createFullEvent(
                    "Connected to database", "signum.node", "main", "testnet")));

            // Pattern only in logger name → no match
            assertFalse(filter.matches(createFullEvent(
                    "Operation complete", "signum.database.pool", "main", "testnet")));
        }

        @Test
        @DisplayName("MESSAGE_AND_LOGGER: searches message + logger name")
        void matching_MessageAndLogger_SearchesBothFields() {
            LogFilter filter = TextSearchFilter.custom("database", SearchScope.MESSAGE_AND_LOGGER);

            // Pattern in message → match
            assertTrue(filter.matches(createFullEvent(
                    "Connected to database", "signum.node", "main", "testnet")));

            // Pattern in logger name → match
            assertTrue(filter.matches(createFullEvent(
                    "Operation complete", "signum.database.pool", "main", "testnet")));

            // Pattern not in either → no match
            assertFalse(filter.matches(createFullEvent(
                    "Operation complete", "signum.node", "main", "testnet")));
        }

        @Test
        @DisplayName("ALL_FIELDS: searches message, logger, thread, profile")
        void matching_AllFields_SearchesAllTextFields() {
            LogFilter filter = TextSearchFilter.custom("testnet", SearchScope.ALL_FIELDS);

            // Pattern in message → match
            assertTrue(filter.matches(createFullEvent(
                    "Running on testnet", "signum.node", "main", "mainnet")));

            // Pattern in logger name → match
            assertTrue(filter.matches(createFullEvent(
                    "Status OK", "signum.testnet.handler", "main", "mainnet")));

            // Pattern in thread name → match
            assertTrue(filter.matches(createFullEvent(
                    "Status OK", "signum.node", "testnet-worker", "mainnet")));

            // Pattern in profile name → match
            assertTrue(filter.matches(createFullEvent(
                    "Status OK", "signum.node", "main", "testnet")));

            // Not in any field → no match
            assertFalse(filter.matches(createFullEvent(
                    "Status OK", "signum.node", "main", "mainnet")));
        }

        @Test
        @DisplayName("matchingAllFields() convenience method works")
        void matchingAllFields_ConvenienceMethod_Works() {
            LogFilter filter = TextSearchFilter.matchingAllFields("connection");

            assertTrue(filter.matches(createFullEvent(
                    "Data sync", "signum.connection.pool", "worker-1", "mainnet")));
        }

        @Test
        @DisplayName("custom() with null scope throws NullPointerException")
        void custom_GivenNullScope_ThrowsNPE() {
            assertThrows(NullPointerException.class,
                    () -> TextSearchFilter.custom("test", (SearchScope) null));
        }
    }

    // ------------------------ Accessors ------------------------

    @Nested
    @DisplayName("Accessor methods")
    class AccessorTests {

        @Test
        @DisplayName("getPattern() returns the compiled pattern")
        void getPattern_ReturnsPattern() {
            TextSearchFilter filter = TextSearchFilter.including("(?i)test");
            Pattern pattern = filter.getPattern();

            assertNotNull(pattern);
            assertTrue(pattern.matcher("Test").find());
        }

        @Test
        @DisplayName("isIncludeMode() returns correct mode")
        void isIncludeMode_ReturnsCorrectValue() {
            TextSearchFilter includeFilter = TextSearchFilter.including("test");
            assertTrue(includeFilter.isIncludeMode());

            TextSearchFilter excludeFilter = TextSearchFilter.excluding("test");
            assertFalse(excludeFilter.isIncludeMode());
        }

        @Test
        @DisplayName("getScope() returns correct scope")
        void getScope_ReturnsCorrectValue() {
            TextSearchFilter msgOnly = TextSearchFilter.including("test");
            assertEquals(SearchScope.MESSAGE_ONLY, msgOnly.getScope());

            TextSearchFilter allFields = TextSearchFilter.matchingAllFields("test");
            assertEquals(SearchScope.ALL_FIELDS, allFields.getScope());
        }
    }

    // ------------------------ null event handling ------------------------

    @Nested
    @DisplayName("null event handling")
    class NullEventTests {

        @Test
        @DisplayName("includingLiteral: null event returns false")
        void includingLiteral_GivenNullEvent_ReturnsFalse() {
            LogFilter filter = TextSearchFilter.includingLiteral("test");
            assertFalse(filter.matches(null));
        }

        @Test
        @DisplayName("including regex: null event returns false")
        void including_GivenNullEvent_ReturnsFalse() {
            LogFilter filter = TextSearchFilter.including("test");
            assertFalse(filter.matches(null));
        }

        @Test
        @DisplayName("matchingAllFields: null event returns false")
        void matchingAllFields_GivenNullEvent_ReturnsFalse() {
            LogFilter filter = TextSearchFilter.matchingAllFields("test");
            assertFalse(filter.matches(null));
        }
    }

    // ------------------------ toString() ------------------------

    @Nested
    @DisplayName("toString() representation")
    class ToStringTests {

        @Test
        @DisplayName("shows include/exclude mode, pattern, and scope")
        void toString_ShowsAllDetails() {
            TextSearchFilter filter = TextSearchFilter.matchingAllFields("(?i)error");
            String str = filter.toString();

            assertTrue(str.contains("include"));
            assertTrue(str.contains("(?i)error"));
            assertTrue(str.contains("ALL_FIELDS"));
        }

        @Test
        @DisplayName("excluding mode shows exclude")
        void toString_ExcludingMode_ShowsExclude() {
            TextSearchFilter filter = TextSearchFilter.excluding("debug");
            String str = filter.toString();

            assertTrue(str.contains("exclude"));
        }
    }
}