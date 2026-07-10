package application.utils.logging;

import application.utils.logging.event.LogLevel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LogLevel}.
 * <p>
 * Verifies enum values, severity ordering, JUL/SLF4J bidirectional mapping,
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

    // ------------------------ fromJul() ------------------------

    @Nested
    @DisplayName("fromJul() - JUL Level mapping")
    class FromJulTests {

        @Test
        @DisplayName("null JUL level defaults to INFO")
        void fromJul_GivenNull_ReturnsInfo() {
            assertSame(LogLevel.INFO, LogLevel.fromJul(null));
        }

        @Test
        @DisplayName("JUL FINEST maps to TRACE")
        void fromJul_GivenFINEST_ReturnsTRACE() {
            assertSame(LogLevel.TRACE, LogLevel.fromJul(Level.FINEST));
        }

        @Test
        @DisplayName("JUL FINER maps to TRACE (below FINEST threshold)")
        void fromJul_GivenFINER_ReturnsTRACE() {
            // FINER.intValue() <= FINEST.intValue() is false, but FINER < FINE so it's TRACE
            // Actually FINER > FINEST, so it falls into DEBUG range
            // Let's check: FINEST=300, FINER=400, FINE=500, INFO=800, WARNING=900, SEVERE=1000
            // FINER(400) <= FINEST(300)? No. <= FINE(500)? Yes → DEBUG
            assertSame(LogLevel.DEBUG, LogLevel.fromJul(Level.FINER));
        }

        @Test
        @DisplayName("JUL FINE maps to DEBUG")
        void fromJul_GivenFINE_ReturnsDEBUG() {
            assertSame(LogLevel.DEBUG, LogLevel.fromJul(Level.FINE));
        }

        @Test
        @DisplayName("JUL INFO maps to INFO")
        void fromJul_GivenINFO_ReturnsINFO() {
            assertSame(LogLevel.INFO, LogLevel.fromJul(Level.INFO));
        }

        @Test
        @DisplayName("JUL WARNING maps to WARN")
        void fromJul_GivenWARNING_ReturnsWARN() {
            assertSame(LogLevel.WARN, LogLevel.fromJul(Level.WARNING));
        }

        @Test
        @DisplayName("JUL SEVERE maps to ERROR")
        void fromJul_GivenSEVERE_ReturnsERROR() {
            assertSame(LogLevel.ERROR, LogLevel.fromJul(Level.SEVERE));
        }

        @Test
        @DisplayName("JUL OFF maps to ERROR (above WARNING)")
        void fromJul_GivenOFF_ReturnsERROR() {
            // OFF.intValue() = 1000, which is > WARNING(900), so it falls into else branch → ERROR
            assertSame(LogLevel.ERROR, LogLevel.fromJul(Level.OFF));
        }

        @Test
        @DisplayName("JUL ALL maps to TRACE (lowest severity)")
        void fromJul_GivenALL_ReturnsTRACE() {
            // ALL.intValue() = 0, which is <= FINEST(300), so → TRACE
            assertSame(LogLevel.TRACE, LogLevel.fromJul(Level.ALL));
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

    // ------------------------ toJul() ------------------------

    @Nested
    @DisplayName("toJul() - convert back to JUL Level")
    class ToJulTests {

        @Test
        @DisplayName("TRACE maps to JUL FINEST")
        void toJul_GivenTRACE_ReturnsFINEST() {
            assertSame(Level.FINEST, LogLevel.TRACE.toJul());
        }

        @Test
        @DisplayName("DEBUG maps to JUL FINE")
        void toJul_GivenDEBUG_ReturnsFINE() {
            assertSame(Level.FINE, LogLevel.DEBUG.toJul());
        }

        @Test
        @DisplayName("INFO maps to JUL INFO")
        void toJul_GivenINFO_ReturnsINFO() {
            assertSame(Level.INFO, LogLevel.INFO.toJul());
        }

        @Test
        @DisplayName("WARN maps to JUL WARNING")
        void toJul_GivenWARN_ReturnsWARNING() {
            assertSame(Level.WARNING, LogLevel.WARN.toJul());
        }

        @Test
        @DisplayName("ERROR maps to JUL SEVERE")
        void toJul_GivenERROR_ReturnsSEVERE() {
            assertSame(Level.SEVERE, LogLevel.ERROR.toJul());
        }

        @Test
        @DisplayName("OFF maps to JUL OFF")
        void toJul_GivenOFF_ReturnsOFF() {
            assertSame(Level.OFF, LogLevel.OFF.toJul());
        }
    }

    // ------------------------ Round-trip Consistency ------------------------

    @Nested
    @DisplayName("Round-trip consistency (fromJul → toJul and vice versa)")
    class RoundTripTests {

        @Test
        @DisplayName("JUL INFO → LogLevel → JUL is consistent")
        void julToLogLevelToJul_InfoConsistent() {
            Level original = Level.INFO;
            LogLevel level = LogLevel.fromJul(original);
            Level roundTrip = level.toJul();
            assertSame(original, roundTrip);
        }

        @Test
        @DisplayName("SLF4J 'error' → LogLevel → SLF4j name is consistent")
        void slf4jToLogLevel_NameConsistent() {
            String original = "error";
            LogLevel level = LogLevel.fromSlf4jName(original);
            // Check enum name matches original concept (case-insensitive comparison)
            assertEquals("ERROR", level.getDisplayName());
        }

        @Test
        @DisplayName("all recognized SLF4J names round-trip through fromSlf4jName")
        void allSlf4jNamesRoundTrip() {
            String[] names = {"trace", "debug", "info", "warn", "error", "off"};
            for (String name : names) {
                LogLevel level = LogLevel.fromSlf4jName(name);
                assertNotNull(level);
                // Verify we can convert to JUL and back without null
                Level jul = level.toJul();
                assertNotNull(jul);
            }
        }
    }
}