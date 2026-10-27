package application.utils.logging;

import application.utils.logging.event.LogLevel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LogLevel}.
 * <p>
 * Verifies enum values, severity ordering, SLF4J name mapping,
 * and the isAtLeast() comparison logic.
 * </p>
 */
@DisplayName("LogLevel Tests")
class LogLevelTest {

    // ------------------------ Enum Values & Severity ------------------------

    @Nested
    @DisplayName("Enum values and severity ordering")
    class EnumValuesTests {

        @Test
        @DisplayName("TRACE has lowest severity (5)")
        void traceHasLowestSeverity() {
            assertEquals(5, LogLevel.TRACE.getSeverity());
        }

        @Test
        @DisplayName("severity values increase: TRACE < DEBUG < INFO < WARN < ERROR < OFF")
        void severityValues_IncreaseMonotonically() {
            assertTrue(LogLevel.TRACE.getSeverity() < LogLevel.DEBUG.getSeverity());
            assertTrue(LogLevel.DEBUG.getSeverity() < LogLevel.INFO.getSeverity());
            assertTrue(LogLevel.INFO.getSeverity() < LogLevel.WARN.getSeverity());
            assertTrue(LogLevel.WARN.getSeverity() < LogLevel.ERROR.getSeverity());
            assertTrue(LogLevel.ERROR.getSeverity() < LogLevel.OFF.getSeverity());
        }

        @Test
        @DisplayName("display names match enum constants")
        void displayName_MatchesEnumConstant() {
            assertEquals("TRACE", LogLevel.TRACE.getDisplayName());
            assertEquals("DEBUG", LogLevel.DEBUG.getDisplayName());
            assertEquals("INFO", LogLevel.INFO.getDisplayName());
            assertEquals("WARN", LogLevel.WARN.getDisplayName());
            assertEquals("ERROR", LogLevel.ERROR.getDisplayName());
            assertEquals("OFF", LogLevel.OFF.getDisplayName());
        }

        @Test
        @DisplayName("all enum values are present (6 total)")
        void allValuesPresent() {
            assertArrayEquals(
                    new LogLevel[]{LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.OFF},
                    LogLevel.values()
            );
        }
    }

    // ------------------------ isAtLeast() ------------------------

    @Nested
    @DisplayName("isAtLeast() comparison")
    class IsAtLeastTests {

        @Test
        @DisplayName("level is at least itself")
        void isAtLeast_GivenSameLevel_ReturnsTrue() {
            assertTrue(LogLevel.INFO.isAtLeast(LogLevel.INFO));
        }

        @Test
        @DisplayName("ERROR is at least WARN")
        void isAtLeast_GivenHigherSeverity_ReturnsTrue() {
            assertTrue(LogLevel.ERROR.isAtLeast(LogLevel.WARN));
        }

        @Test
        @DisplayName("DEBUG is NOT at least WARN")
        void isAtLeast_GivenLowerSeverity_ReturnsFalse() {
            assertFalse(LogLevel.DEBUG.isAtLeast(LogLevel.WARN));
        }

        @Test
        @DisplayName("OFF is at least everything")
        void isAtLeast_GivenOFF_IsAtLeastEverything() {
            for (LogLevel level : LogLevel.values()) {
                assertTrue(LogLevel.OFF.isAtLeast(level), "OFF should be at least " + level);
            }
        }

        @Test
        @DisplayName("TRACE is NOT at least anything except itself")
        void isAtLeast_GivenTRACE_NotAtLeastAnythingElse() {
            assertFalse(LogLevel.TRACE.isAtLeast(LogLevel.DEBUG));
            assertFalse(LogLevel.TRACE.isAtLeast(LogLevel.INFO));
            assertTrue(LogLevel.TRACE.isAtLeast(LogLevel.TRACE));
        }
    }

    // ------------------------ fromSlf4jName() ------------------------

    @Nested
    @DisplayName("fromSlf4jName() - SLF4J name mapping")
    class FromSlf4jNameTests {

        @Test
        @DisplayName("null name defaults to INFO")
        void fromSlf4jName_GivenNull_ReturnsInfo() {
            assertSame(LogLevel.INFO, LogLevel.fromSlf4jName(null));
        }

        @Test
        @DisplayName("recognizes lowercase names")
        void fromSlf4jName_GivenLowercase_MapsCorrectly() {
            assertSame(LogLevel.TRACE, LogLevel.fromSlf4jName("trace"));
            assertSame(LogLevel.DEBUG, LogLevel.fromSlf4jName("debug"));
            assertSame(LogLevel.INFO, LogLevel.fromSlf4jName("info"));
            assertSame(LogLevel.WARN, LogLevel.fromSlf4jName("warn"));
            assertSame(LogLevel.ERROR, LogLevel.fromSlf4jName("error"));
            assertSame(LogLevel.OFF, LogLevel.fromSlf4jName("off"));
        }

        @Test
        @DisplayName("recognizes uppercase names")
        void fromSlf4jName_GivenUppercase_MapsCorrectly() {
            assertSame(LogLevel.TRACE, LogLevel.fromSlf4jName("TRACE"));
            assertSame(LogLevel.DEBUG, LogLevel.fromSlf4jName("DEBUG"));
            assertSame(LogLevel.INFO, LogLevel.fromSlf4jName("INFO"));
            assertSame(LogLevel.WARN, LogLevel.fromSlf4jName("WARN"));
            assertSame(LogLevel.ERROR, LogLevel.fromSlf4jName("ERROR"));
            assertSame(LogLevel.OFF, LogLevel.fromSlf4jName("OFF"));
        }

        @Test
        @DisplayName("recognizes mixed case names")
        void fromSlf4jName_GivenMixedCase_MapsCorrectly() {
            assertSame(LogLevel.TRACE, LogLevel.fromSlf4jName("Trace"));
            assertSame(LogLevel.DEBUG, LogLevel.fromSlf4jName("Debug"));
            assertSame(LogLevel.ERROR, LogLevel.fromSlf4jName("ErroR"));
        }

        @Test
        @DisplayName("unrecognized name defaults to INFO")
        void fromSlf4jName_GivenUnknown_ReturnsInfo() {
            assertSame(LogLevel.INFO, LogLevel.fromSlf4jName("unknown"));
            assertSame(LogLevel.INFO, LogLevel.fromSlf4jName("verbose"));
            assertSame(LogLevel.INFO, LogLevel.fromSlf4jName(""));
        }

        @Test
        @DisplayName("'logging' maps to INFO (not a valid level)")
        void fromSlf4jName_GivenLogging_ReturnsInfo() {
            assertSame(LogLevel.INFO, LogLevel.fromSlf4jName("logging"));
        }
    }

}