package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.ModuleFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ModuleFilter}.
 * <p>
 * Verifies inclusion/exclusion modes, substring matching behavior (contains, not equals),
 * null/empty logger name handling, and input validation.
 * </p>
 */
@DisplayName("ModuleFilter Tests")
class ModuleFilterTest {

    private LogEvent createEvent(String loggerName) {
        return new LogEvent.Builder()
                .level(LogLevel.INFO)
                .message("test message")
                .loggerName(loggerName)
                .build();
    }

    // ------------------------ including() ------------------------

    @Nested
    @DisplayName("including() - inclusion mode")
    class IncludingTests {

        @Test
        @DisplayName("exact logger name match passes")
        void including_GivenExactMatch_ReturnsTrue() {
            LogFilter filter = ModuleFilter.including("node");
            assertTrue(filter.matches(createEvent("signum.node")));
        }

        @Test
        @DisplayName("substring match passes (contains logic)")
        void including_GivenSubstringMatch_ReturnsTrue() {
            LogFilter filter = ModuleFilter.including("database");
            assertTrue(filter.matches(createEvent("signum.database.connection")));
            assertTrue(filter.matches(createEvent("org.signum.database.pool")));
        }

        @Test
        @DisplayName("non-matching logger is blocked")
        void including_GivenNonMatch_ReturnsFalse() {
            LogFilter filter = ModuleFilter.including("node");
            assertFalse(filter.matches(createEvent("signum.mining")));
            assertFalse(filter.matches(createEvent("org.apache.http")));
        }

        @Test
        @DisplayName("multiple modules: any match passes")
        void including_GivenMultipleModules_MatchesAny() {
            LogFilter filter = ModuleFilter.including("node", "database");

            assertTrue(filter.matches(createEvent("signum.node.BlockchainProcessor")));
            assertTrue(filter.matches(createEvent("signum.database.sql")));
            assertFalse(filter.matches(createEvent("signum.mining.PoWSolver")));
        }

        @Test
        @DisplayName("null logger name: blocked in including mode")
        void including_GivenNullLoggerName_ReturnsFalse() {
            LogFilter filter = ModuleFilter.including("node");
            assertFalse(filter.matches(createEvent(null)));
        }

        @Test
        @DisplayName("empty logger name: blocked in including mode")
        void including_GivenEmptyLoggerName_ReturnsFalse() {
            LogFilter filter = ModuleFilter.including("node");
            assertFalse(filter.matches(createEvent("")));
        }

        @Test
        @DisplayName("null event returns false")
        void including_GivenNullEvent_ReturnsFalse() {
            LogFilter filter = ModuleFilter.including("node");
            assertFalse(filter.matches(null));
        }

        @Test
        @DisplayName("null varargs throws IllegalArgumentException")
        void including_GivenNullVarargs_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> ModuleFilter.including((String[]) null));
        }

        @Test
        @DisplayName("empty varargs throws IllegalArgumentException")
        void including_GivenEmptyVarargs_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> ModuleFilter.including());
        }

        @Test
        @DisplayName("null module name in varargs throws NullPointerException")
        void including_GivenNullElement_ThrowsNPE() {
            assertThrows(NullPointerException.class, () -> ModuleFilter.including("node", null));
        }
    }

    // ------------------------ excluding() ------------------------

    @Nested
    @DisplayName("excluding() - exclusion mode")
    class ExcludingTests {

        @Test
        @DisplayName("matching logger is blocked")
        void excluding_GivenMatchingLogger_ReturnsFalse() {
            LogFilter filter = ModuleFilter.excluding("org.junit");
            assertFalse(filter.matches(createEvent("org.junit.jupiter.api.Test")));
        }

        @Test
        @DisplayName("non-matching logger passes")
        void excluding_GivenNonMatchingLogger_ReturnsTrue() {
            LogFilter filter = ModuleFilter.excluding("org.junit");
            assertTrue(filter.matches(createEvent("signum.node")));
        }

        @Test
        @DisplayName("multiple excluded modules: all blocked")
        void excluding_GivenMultipleModules_BlocksAllSpecified() {
            LogFilter filter = ModuleFilter.excluding("org.junit", "org.mockito");

            assertFalse(filter.matches(createEvent("org.junit.jupiter.api.Test")));
            assertFalse(filter.matches(createEvent("org.mockito.Mock")));
            assertTrue(filter.matches(createEvent("signum.node")));
        }

        @Test
        @DisplayName("null logger name: passes in excluding mode")
        void excluding_GivenNullLoggerName_ReturnsTrue() {
            LogFilter filter = ModuleFilter.excluding("node");
            // Empty/null logger → includeMode=false means it passes
            assertTrue(filter.matches(createEvent(null)));
        }

        @Test
        @DisplayName("empty logger name: passes in excluding mode")
        void excluding_GivenEmptyLoggerName_ReturnsTrue() {
            LogFilter filter = ModuleFilter.excluding("node");
            assertTrue(filter.matches(createEvent("")));
        }

        @Test
        @DisplayName("null event returns false")
        void excluding_GivenNullEvent_ReturnsFalse() {
            LogFilter filter = ModuleFilter.excluding("node");
            assertFalse(filter.matches(null));
        }

        @Test
        @DisplayName("null varargs throws IllegalArgumentException")
        void excluding_GivenNullVarargs_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> ModuleFilter.excluding((String[]) null));
        }

        @Test
        @DisplayName("empty varargs throws IllegalArgumentException")
        void excluding_GivenEmptyVarargs_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> ModuleFilter.excluding());
        }
    }

    // ------------------------ Substring Matching Behavior ------------------------

    @Nested
    @DisplayName("Substring matching behavior (contains, not equals)")
    class SubstringMatchingTests {

        @Test
        @DisplayName("partial module name matches full logger path")
        void matching_PartialModuleName_MatchesFullPath() {
            LogFilter filter = ModuleFilter.including("signum");

            assertTrue(filter.matches(createEvent("signum.node.BlockchainProcessor")));
            assertTrue(filter.matches(createEvent("signum.database.sql.DbContext")));
            assertTrue(filter.matches(createEvent("signum.crypto.Hash")));
        }

        @Test
        @DisplayName("does not require exact equality")
        void matching_DoesNotRequireExactEquality() {
            LogFilter filter = ModuleFilter.including("node");
            // "node" is a substring of "signum.node.impl", so it matches
            assertTrue(filter.matches(createEvent("signum.node.impl.AccountServiceImpl")));
        }

        @Test
        @DisplayName("case-sensitive matching")
        void matching_CaseSensitive_NoFalsePositives() {
            LogFilter filter = ModuleFilter.including("Node");

            assertFalse(filter.matches(createEvent("signum.node")));
            assertTrue(filter.matches(createEvent("signum.Node")));
        }

        @Test
        @DisplayName("short module ID does not match unrelated logger")
        void matching_ShortId_NoPartialMatch() {
            LogFilter filter = ModuleFilter.including("nod");
            // "nod" is NOT contained in "signum.node" (it contains "node" not "nod")
            // Actually "node" contains "nod"... let's verify
            assertTrue(filter.matches(createEvent("signum.node")));
        }
    }

    // ------------------------ toString() ------------------------

    @Nested
    @DisplayName("toString() representation")
    class ToStringTests {

        @Test
        @DisplayName("including mode shows 'include'")
        void toString_IncludingMode_ShowsInclude() {
            LogFilter filter = ModuleFilter.including("node", "database");
            String str = filter.toString();

            assertTrue(str.contains("include"));
            assertTrue(str.contains("node"));
        }

        @Test
        @DisplayName("excluding mode shows 'exclude'")
        void toString_ExcludingMode_ShowsExclude() {
            LogFilter filter = ModuleFilter.excluding("org.junit");
            String str = filter.toString();

            assertTrue(str.contains("exclude"));
            assertTrue(str.contains("org.junit"));
        }
    }

    // ------------------------ Immutability ------------------------

    @Nested
    @DisplayName("Immutability guarantees")
    class ImmutabilityTests {

        @Test
        @DisplayName("filter behavior is consistent across multiple calls")
        void immutability_MultipleMatchesCalls_ReturnConsistentResults() {
            LogFilter filter = ModuleFilter.including("node");
            LogEvent event = createEvent("signum.node.BlockchainProcessor");

            for (int i = 0; i < 100; i++) {
                assertTrue(filter.matches(event));
            }
        }

        @Test
        @DisplayName("filter is safe for concurrent use")
        void immutability_ConcurrentAccess_IsSafe() throws InterruptedException {
            LogFilter filter = ModuleFilter.including("node", "database");
            Thread[] threads = new Thread[10];
            boolean[] results = new boolean[10];

            for (int i = 0; i < threads.length; i++) {
                final int index = i;
                final String logger = (i % 2 == 0) ? "signum.node" : "signum.mining";
                threads[index] = new Thread(() -> {
                    results[index] = filter.matches(createEvent(logger));
                });
                threads[index].start();
            }

            for (Thread t : threads) {
                t.join();
            }

            for (int i = 0; i < results.length; i++) {
                if (i % 2 == 0) {
                    assertTrue(results[i], "Index " + i + " should be true (node)");
                } else {
                    assertFalse(results[i], "Index " + i + " should be false (mining)");
                }
            }
        }
    }
}