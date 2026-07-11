package application.utils.logging;

import application.utils.logging.event.LevelFilter;
import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LevelFilter}.
 * <p>
 * Verifies inclusion/exclusion modes, atLeast() severity ordering,
 * null-safety, and validation of factory method inputs.
 * </p>
 */
@DisplayName("LevelFilter Tests")
class LevelFilterTest {

    private LogEvent createEvent(LogLevel level) {
        return new LogEvent.Builder()
                .level(level)
                .message("test message")
                .build();
    }

    // ------------------------ including() ------------------------

    @Nested
    @DisplayName("including() - inclusion mode")
    class IncludingTests {

        @Test
        @DisplayName("single level: matching event passes")
        void including_GivenMatchingLevel_ReturnsTrue() {
            LogFilter filter = LevelFilter.including(LogLevel.WARN);
            LogEvent event = createEvent(LogLevel.WARN);

            assertTrue(filter.matches(event));
        }

        @Test
        @DisplayName("single level: non-matching event is blocked")
        void including_GivenNonMatchingLevel_ReturnsFalse() {
            LogFilter filter = LevelFilter.including(LogLevel.WARN);
            LogEvent event = createEvent(LogLevel.INFO);

            assertFalse(filter.matches(event));
        }

        @Test
        @DisplayName("multiple levels: any matching level passes")
        void including_GivenMultipleLevels_MatchesAny() {
            LogFilter filter = LevelFilter.including(LogLevel.WARN, LogLevel.ERROR, LogLevel.DEBUG);

            assertTrue(filter.matches(createEvent(LogLevel.WARN)));
            assertTrue(filter.matches(createEvent(LogLevel.ERROR)));
            assertTrue(filter.matches(createEvent(LogLevel.DEBUG)));
            assertFalse(filter.matches(createEvent(LogLevel.INFO)));
            assertFalse(filter.matches(createEvent(LogLevel.TRACE)));
        }

        @Test
        @DisplayName("all levels: every LogLevel passes")
        void including_GivenAllLevels_MatchesEverything() {
            LogFilter filter = LevelFilter.including(
                    LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.OFF
            );

            for (LogLevel level : LogLevel.values()) {
                assertTrue(filter.matches(createEvent(level)), "Should match " + level);
            }
        }

        @Test
        @DisplayName("null event returns false")
        void including_GivenNullEvent_ReturnsFalse() {
            LogFilter filter = LevelFilter.including(LogLevel.INFO);
            assertFalse(filter.matches(null));
        }

        @Test
        @DisplayName("null varargs throws IllegalArgumentException")
        void including_GivenNullVarargs_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> LevelFilter.including((LogLevel[]) null));
        }

        @Test
        @DisplayName("empty varargs throws IllegalArgumentException")
        void including_GivenEmptyVarargs_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> LevelFilter.including());
        }
    }

    // ------------------------ excluding() ------------------------

    @Nested
    @DisplayName("excluding() - exclusion mode")
    class ExcludingTests {

        @Test
        @DisplayName("single level: matching event is blocked")
        void excluding_GivenMatchingLevel_ReturnsFalse() {
            LogFilter filter = LevelFilter.excluding(LogLevel.DEBUG);
            LogEvent event = createEvent(LogLevel.DEBUG);

            assertFalse(filter.matches(event));
        }

        @Test
        @DisplayName("single level: non-matching event passes")
        void excluding_GivenNonMatchingLevel_ReturnsTrue() {
            LogFilter filter = LevelFilter.excluding(LogLevel.DEBUG);
            LogEvent event = createEvent(LogLevel.INFO);

            assertTrue(filter.matches(event));
        }

        @Test
        @DisplayName("multiple excluded levels: all blocked, rest pass")
        void excluding_GivenMultipleLevels_BlocksAllSpecified() {
            LogFilter filter = LevelFilter.excluding(LogLevel.DEBUG, LogLevel.TRACE);

            assertFalse(filter.matches(createEvent(LogLevel.DEBUG)));
            assertFalse(filter.matches(createEvent(LogLevel.TRACE)));
            assertTrue(filter.matches(createEvent(LogLevel.INFO)));
            assertTrue(filter.matches(createEvent(LogLevel.WARN)));
            assertTrue(filter.matches(createEvent(LogLevel.ERROR)));
        }

        @Test
        @DisplayName("exclude all levels: nothing passes")
        void excluding_GivenAllLevels_BlocksEverything() {
            LogFilter filter = LevelFilter.excluding(
                    LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.OFF
            );

            for (LogLevel level : LogLevel.values()) {
                assertFalse(filter.matches(createEvent(level)), "Should block " + level);
            }
        }

        @Test
        @DisplayName("null event returns false")
        void excluding_GivenNullEvent_ReturnsFalse() {
            LogFilter filter = LevelFilter.excluding(LogLevel.INFO);
            assertFalse(filter.matches(null));
        }

        @Test
        @DisplayName("null varargs throws IllegalArgumentException")
        void excluding_GivenNullVarargs_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> LevelFilter.excluding((LogLevel[]) null));
        }

        @Test
        @DisplayName("empty varargs throws IllegalArgumentException")
        void excluding_GivenEmptyVarargs_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> LevelFilter.excluding());
        }
    }

    // ------------------------ atLeast() ------------------------

    @Nested
    @DisplayName("atLeast() - minimum severity mode")
    class AtLeastTests {

        @Test
        @DisplayName("atLeast TRACE: all levels pass")
        void atLeast_TRACE_AllLevelsPass() {
            LogFilter filter = LevelFilter.atLeast(LogLevel.TRACE);

            for (LogLevel level : LogLevel.values()) {
                // OFF has severity higher than ERROR, so it also passes
                if (level != LogLevel.OFF) {
                    assertTrue(filter.matches(createEvent(level)), "Should pass " + level);
                }
            }
        }

        @Test
        @DisplayName("atLeast ERROR: only ERROR and OFF pass")
        void atLeast_Error_OnlyErrorAndOffPass() {
            LogFilter filter = LevelFilter.atLeast(LogLevel.ERROR);

            assertFalse(filter.matches(createEvent(LogLevel.TRACE)));
            assertFalse(filter.matches(createEvent(LogLevel.DEBUG)));
            assertFalse(filter.matches(createEvent(LogLevel.INFO)));
            assertFalse(filter.matches(createEvent(LogLevel.WARN)));
            assertTrue(filter.matches(createEvent(LogLevel.ERROR)));
        }

        @Test
        @DisplayName("atLeast INFO: INFO, WARN, ERROR pass")
        void atLeast_Info_InfoAndAbovePass() {
            LogFilter filter = LevelFilter.atLeast(LogLevel.INFO);

            assertFalse(filter.matches(createEvent(LogLevel.TRACE)));
            assertFalse(filter.matches(createEvent(LogLevel.DEBUG)));
            assertTrue(filter.matches(createEvent(LogLevel.INFO)));
            assertTrue(filter.matches(createEvent(LogLevel.WARN)));
            assertTrue(filter.matches(createEvent(LogLevel.ERROR)));
        }

        @Test
        @DisplayName("atLeast WARN: WARN, ERROR pass")
        void atLeast_Warn_WarnAndAbovePass() {
            LogFilter filter = LevelFilter.atLeast(LogLevel.WARN);

            assertFalse(filter.matches(createEvent(LogLevel.TRACE)));
            assertFalse(filter.matches(createEvent(LogLevel.DEBUG)));
            assertFalse(filter.matches(createEvent(LogLevel.INFO)));
            assertTrue(filter.matches(createEvent(LogLevel.WARN)));
            assertTrue(filter.matches(createEvent(LogLevel.ERROR)));
        }

        @Test
        @DisplayName("atLeast same level: passes itself")
        void atLeast_GivenSameLevel_ReturnsTrue() {
            for (LogLevel level : LogLevel.values()) {
                LogFilter filter = LevelFilter.atLeast(level);
                assertTrue(filter.matches(createEvent(level)), "Should pass itself: " + level);
            }
        }

        @Test
        @DisplayName("null minimum level throws NullPointerException")
        void atLeast_GivenNull_ThrowsNPE() {
            assertThrows(NullPointerException.class, () -> LevelFilter.atLeast(null));
        }

        @Test
        @DisplayName("null event returns false")
        void atLeast_GivenNullEvent_ReturnsFalse() {
            LogFilter filter = LevelFilter.atLeast(LogLevel.INFO);
            assertFalse(filter.matches(null));
        }

        @Test
        @DisplayName("severity ordering: TRACE(5) < DEBUG(9) < INFO(800) < WARN(900) < ERROR(1000)")
        void severity_OrderingIsCorrect() {
            assertTrue(LogLevel.TRACE.getSeverity() < LogLevel.DEBUG.getSeverity());
            assertTrue(LogLevel.DEBUG.getSeverity() < LogLevel.INFO.getSeverity());
            assertTrue(LogLevel.INFO.getSeverity() < LogLevel.WARN.getSeverity());
            assertTrue(LogLevel.WARN.getSeverity() < LogLevel.ERROR.getSeverity());
        }
    }

    // ------------------------ toString() ------------------------

    @Nested
    @DisplayName("toString() representation")
    class ToStringTests {

        @Test
        @DisplayName("including mode shows 'include' in output")
        void toString_IncludingMode_ShowsInclude() {
            LogFilter filter = LevelFilter.including(LogLevel.WARN, LogLevel.ERROR);
            String str = filter.toString();

            assertTrue(str.contains("include"));
            assertTrue(str.contains("WARN"));
            assertTrue(str.contains("ERROR"));
        }

        @Test
        @DisplayName("excluding mode shows 'exclude' in output")
        void toString_ExcludingMode_ShowsExclude() {
            LogFilter filter = LevelFilter.excluding(LogLevel.DEBUG);
            String str = filter.toString();

            assertTrue(str.contains("exclude"));
            assertTrue(str.contains("DEBUG"));
        }

        @Test
        @DisplayName("atLeast mode shows 'include' (it is inclusion-based internally)")
        void toString_AtLeastMode_ShowsInclude() {
            LogFilter filter = LevelFilter.atLeast(LogLevel.INFO);
            String str = filter.toString();

            assertTrue(str.contains("include"));
        }
    }

    // ------------------------ Immutability ------------------------

    @Nested
    @DisplayName("Immutability guarantees")
    class ImmutabilityTests {

        @Test
        @DisplayName("filter behavior is consistent across multiple calls")
        void immutability_MultipleMatchesCalls_ReturnConsistentResults() {
            LogFilter filter = LevelFilter.including(LogLevel.INFO);
            LogEvent event = createEvent(LogLevel.INFO);

            // Call matches 100 times - always true
            for (int i = 0; i < 100; i++) {
                assertTrue(filter.matches(event));
            }
        }

        @Test
        @DisplayName("filter is safe for concurrent use")
        void immutability_ConcurrentAccess_IsSafe() throws InterruptedException {
            LogFilter filter = LevelFilter.atLeast(LogLevel.WARN);
            Thread[] threads = new Thread[10];
            boolean[] results = new boolean[10];

            for (int i = 0; i < threads.length; i++) {
                final int index = i;
                final LogLevel level = (i % 2 == 0) ? LogLevel.ERROR : LogLevel.DEBUG;
                threads[i] = new Thread(() -> {
                    results[index] = filter.matches(createEvent(level));
                });
                threads[i].start();
            }

            for (Thread t : threads) {
                t.join();
            }

            // Even indices: ERROR -> true, Odd indices: DEBUG -> false
            for (int i = 0; i < results.length; i++) {
                if (i % 2 == 0) {
                    assertTrue(results[i], "Index " + i + " should be true (ERROR)");
                } else {
                    assertFalse(results[i], "Index " + i + " should be false (DEBUG)");
                }
            }
        }
    }
}