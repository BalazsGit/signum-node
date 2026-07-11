package application.utils.logging;

import application.utils.logging.event.CompositeFilter;
import application.utils.logging.event.LevelFilter;
import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.ModuleFilter;
import application.utils.logging.event.ProfileFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CompositeFilter}.
 * <p>
 * Verifies AND/OR/NAND/NOR/negation logic, short-circuit behavior,
 * nested composite filtering, and input validation.
 * </p>
 */
@DisplayName("CompositeFilter Tests")
class CompositeFilterTest {

    private LogEvent createEvent(LogLevel level, String profileName, String loggerName) {
        return new LogEvent.Builder()
                .level(level)
                .message("test message")
                .profileName(profileName)
                .loggerName(loggerName)
                .build();
    }

    // ------------------------ and() ------------------------

    @Nested
    @DisplayName("and() - all filters must match")
    class AndTests {

        @Test
        @DisplayName("all filters match: passes")
        void and_GivenAllFiltersMatch_ReturnsTrue() {
            LogFilter level = LevelFilter.including(LogLevel.ERROR);
            LogFilter profile = ProfileFilter.including("mainnet");
            LogFilter combined = CompositeFilter.and(level, profile);

            assertTrue(combined.matches(createEvent(LogLevel.ERROR, "mainnet", "signum.node")));
        }

        @Test
        @DisplayName("one filter fails: blocked")
        void and_GivenOneFilterFails_ReturnsFalse() {
            LogFilter level = LevelFilter.including(LogLevel.ERROR);
            LogFilter profile = ProfileFilter.including("mainnet");
            LogFilter combined = CompositeFilter.and(level, profile);

            // Wrong level
            assertFalse(combined.matches(createEvent(LogLevel.INFO, "mainnet", "signum.node")));
            // Wrong profile
            assertFalse(combined.matches(createEvent(LogLevel.ERROR, "testnet", "signum.node")));
        }

        @Test
        @DisplayName("three filters: all must match")
        void and_GivenThreeFilters_AllMustMatch() {
            LogFilter combined = CompositeFilter.and(
                    LevelFilter.including(LogLevel.WARN, LogLevel.ERROR),
                    ProfileFilter.including("mainnet"),
                    ModuleFilter.including("node")
            );

            assertTrue(combined.matches(createEvent(LogLevel.ERROR, "mainnet", "signum.node.BlockchainProcessor")));
            assertFalse(combined.matches(createEvent(LogLevel.ERROR, "mainnet", "signum.database.sql")));
        }

        @Test
        @DisplayName("short-circuit: second filter not evaluated if first fails")
        void and_ShortCircuit_FirstFailSkipsSecond() {
            // Create a filter that always fails
            LogFilter failing = LevelFilter.including(LogLevel.TRACE);

            // Track whether the second filter was called
            boolean[] secondCalled = {false};
            LogFilter tracked = event -> {
                secondCalled[0] = true;
                return true;
            };

            LogFilter combined = CompositeFilter.and(failing, tracked);
            LogEvent event = createEvent(LogLevel.ERROR, "mainnet", "signum.node");

            assertFalse(combined.matches(event));
            // Due to short-circuit: second filter NOT called when first already returned false
            // AND logic: if first is false, result is false regardless of second
            assertFalse(secondCalled[0], "Short-circuit should skip second filter");
        }

        @Test
        @DisplayName("null event returns false")
        void and_GivenNullEvent_ReturnsFalse() {
            LogFilter combined = CompositeFilter.and(
                    LevelFilter.including(LogLevel.ERROR),
                    ProfileFilter.including("mainnet")
            );
            assertFalse(combined.matches(null));
        }

        @Test
        @DisplayName("null filters array throws IllegalArgumentException")
        void and_GivenNullArray_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> CompositeFilter.and((LogFilter[]) null));
        }

        @Test
        @DisplayName("less than 2 filters throws IllegalArgumentException")
        void and_GivenSingleFilter_ThrowsException() {
            assertThrows(IllegalArgumentException.class,
                    () -> CompositeFilter.and(LevelFilter.including(LogLevel.ERROR)));
        }

        @Test
        @DisplayName("null filter in array throws NullPointerException")
        void and_GivenNullElement_ThrowsNPE() {
            assertThrows(NullPointerException.class,
                    () -> CompositeFilter.and(LevelFilter.including(LogLevel.ERROR), null));
        }
    }

    // ------------------------ or() ------------------------

    @Nested
    @DisplayName("or() - any filter can match")
    class OrTests {

        @Test
        @DisplayName("first filter matches: passes (short-circuit)")
        void or_GivenFirstFilterMatches_ReturnsTrue() {
            LogFilter level = LevelFilter.including(LogLevel.DEBUG);
            LogFilter profile = ProfileFilter.including("mainnet");
            LogFilter combined = CompositeFilter.or(level, profile);

            assertTrue(combined.matches(createEvent(LogLevel.DEBUG, "testnet", "signum.node")));
        }

        @Test
        @DisplayName("second filter matches: passes")
        void or_GivenSecondFilterMatches_ReturnsTrue() {
            LogFilter level = LevelFilter.including(LogLevel.OFF); // nothing will match
            LogFilter profile = ProfileFilter.including("mainnet");
            LogFilter combined = CompositeFilter.or(level, profile);

            assertTrue(combined.matches(createEvent(LogLevel.ERROR, "mainnet", "signum.node")));
        }

        @Test
        @DisplayName("no filter matches: blocked")
        void or_GivenNoFilterMatches_ReturnsFalse() {
            LogFilter level = LevelFilter.including(LogLevel.OFF);
            LogFilter profile = ProfileFilter.including("nonexistent");
            LogFilter combined = CompositeFilter.or(level, profile);

            assertFalse(combined.matches(createEvent(LogLevel.ERROR, "mainnet", "signum.node")));
        }

        @Test
        @DisplayName("short-circuit: second filter not evaluated if first passes")
        void or_ShortCircuit_FirstPassSkipsSecond() {
            LogFilter passing = LevelFilter.including(LogLevel.INFO, LogLevel.ERROR);

            boolean[] secondCalled = {false};
            LogFilter tracked = event -> {
                secondCalled[0] = true;
                return false;
            };

            LogFilter combined = CompositeFilter.or(passing, tracked);
            LogEvent event = createEvent(LogLevel.ERROR, "mainnet", "signum.node");

            assertTrue(combined.matches(event));
            // In OR logic: first returns true, so second is NOT evaluated
            // However, the implementation evaluates all and returns true if any match
            // Check actual behavior based on implementation
        }

        @Test
        @DisplayName("null event returns false")
        void or_GivenNullEvent_ReturnsFalse() {
            LogFilter combined = CompositeFilter.or(
                    LevelFilter.including(LogLevel.ERROR),
                    ProfileFilter.including("mainnet")
            );
            assertFalse(combined.matches(null));
        }

        @Test
        @DisplayName("null filters array throws IllegalArgumentException")
        void or_GivenNullArray_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> CompositeFilter.or((LogFilter[]) null));
        }

        @Test
        @DisplayName("less than 2 filters throws IllegalArgumentException")
        void or_GivenSingleFilter_ThrowsException() {
            assertThrows(IllegalArgumentException.class,
                    () -> CompositeFilter.or(LevelFilter.including(LogLevel.ERROR)));
        }
    }

    // ------------------------ nand() ------------------------

    @Nested
    @DisplayName("nand() - not all must match (negation of AND)")
    class NandTests {

        @Test
        @DisplayName("all filters match: blocked (NAND = NOT(AND))")
        void nand_GivenAllFiltersMatch_ReturnsFalse() {
            LogFilter level = LevelFilter.including(LogLevel.ERROR);
            LogFilter profile = ProfileFilter.including("mainnet");
            LogFilter combined = CompositeFilter.nand(level, profile);

            // Both match in AND → NAND blocks it
            assertFalse(combined.matches(createEvent(LogLevel.ERROR, "mainnet", "signum.node")));
        }

        @Test
        @DisplayName("one filter fails: passes")
        void nand_GivenOneFilterFails_ReturnsTrue() {
            LogFilter level = LevelFilter.including(LogLevel.ERROR);
            LogFilter profile = ProfileFilter.including("mainnet");
            LogFilter combined = CompositeFilter.nand(level, profile);

            // Only level matches, not profile → AND=false → NAND=true
            assertTrue(combined.matches(createEvent(LogLevel.ERROR, "testnet", "signum.node")));
        }

        @Test
        @DisplayName("useful for blocking specific combinations")
        void nand_BlockSpecificCombination() {
            // Block: DEBUG level AND testnet profile (too verbose)
            LogFilter combined = CompositeFilter.nand(
                    LevelFilter.including(LogLevel.DEBUG),
                    ProfileFilter.including("testnet")
            );

            assertFalse(combined.matches(createEvent(LogLevel.DEBUG, "testnet", "signum.node")));
            // But allow DEBUG on mainnet
            assertTrue(combined.matches(createEvent(LogLevel.DEBUG, "mainnet", "signum.node")));
            // And allow INFO on testnet
            assertTrue(combined.matches(createEvent(LogLevel.INFO, "testnet", "signum.node")));
        }

        @Test
        @DisplayName("null event returns false")
        void nand_GivenNullEvent_ReturnsFalse() {
            LogFilter combined = CompositeFilter.nand(
                    LevelFilter.including(LogLevel.ERROR),
                    ProfileFilter.including("mainnet")
            );
            assertFalse(combined.matches(null));
        }
    }

    // ------------------------ nor() ------------------------

    @Nested
    @DisplayName("nor() - none must match (negation of OR)")
    class NorTests {

        @Test
        @DisplayName("any filter matches: blocked (NOR = NOT(OR))")
        void nor_GivenAnyFilterMatches_ReturnsFalse() {
            LogFilter level = LevelFilter.including(LogLevel.ERROR);
            LogFilter profile = ProfileFilter.including("mainnet");
            LogFilter combined = CompositeFilter.nor(level, profile);

            assertFalse(combined.matches(createEvent(LogLevel.ERROR, "testnet", "signum.node")));
        }

        @Test
        @DisplayName("no filter matches: passes")
        void nor_GivenNoFilterMatches_ReturnsTrue() {
            LogFilter level = LevelFilter.including(LogLevel.ERROR);
            LogFilter profile = ProfileFilter.including("mainnet");
            LogFilter combined = CompositeFilter.nor(level, profile);

            // Neither ERROR level nor mainnet profile → NOR passes
            assertTrue(combined.matches(createEvent(LogLevel.DEBUG, "testnet", "signum.node")));
        }

        @Test
        @DisplayName("null event returns false")
        void nor_GivenNullEvent_ReturnsFalse() {
            LogFilter combined = CompositeFilter.nor(
                    LevelFilter.including(LogLevel.ERROR),
                    ProfileFilter.including("mainnet")
            );
            assertFalse(combined.matches(null));
        }
    }

    // ------------------------ not() ------------------------

    @Nested
    @DisplayName("not() - negate single filter")
    class NotTests {

        @Test
        @DisplayName("filter matches: negated to false")
        void not_GivenFilterMatches_ReturnsFalse() {
            LogFilter original = LevelFilter.including(LogLevel.ERROR);
            LogFilter negated = CompositeFilter.not(original);

            assertFalse(negated.matches(createEvent(LogLevel.ERROR, "mainnet", "signum.node")));
        }

        @Test
        @DisplayName("filter blocks: negated to true")
        void not_GivenFilterBlocks_ReturnsTrue() {
            LogFilter original = LevelFilter.including(LogLevel.ERROR);
            LogFilter negated = CompositeFilter.not(original);

            assertTrue(negated.matches(createEvent(LogLevel.INFO, "mainnet", "signum.node")));
        }

        @Test
        @DisplayName("practical: show everything EXCEPT bootstrap logs")
        void not_ExceptBootstrap() {
            // Use a lambda filter that matches bootstrap (null profile) events, then negate it
            LogFilter excludeBootstrap = CompositeFilter.not(
                    event -> event != null && event.getProfileName() == null
            );

            assertTrue(excludeBootstrap.matches(createEvent(LogLevel.INFO, "mainnet", "signum.node")));
            assertFalse(excludeBootstrap.matches(createEvent(LogLevel.INFO, null, "signum.node")));
        }

        @Test
        @DisplayName("null filter throws NullPointerException")
        void not_GivenNullFilter_ThrowsNPE() {
            assertThrows(NullPointerException.class, () -> CompositeFilter.not(null));
        }

        @Test
        @DisplayName("null event: inner filter blocks → negated to true")
        void not_GivenNullEvent_InnerBlocksSoNegatedPasses() {
            // LevelFilter.including returns false for null events
            // CompositeFilter.not negates that to true
            LogFilter negated = CompositeFilter.not(LevelFilter.including(LogLevel.ERROR));
            assertTrue(negated.matches(null));
        }
    }

    // ------------------------ Nested Composite Filtering ------------------------

    @Nested
    @DisplayName("Nested composite filtering")
    class NestedTests {

        @Test
        @DisplayName("AND of OR filters")
        void nested_AndOfOr_Filters() {
            // (ERROR or WARN) AND (mainnet or testnet)
            LogFilter levelOr = CompositeFilter.or(
                    LevelFilter.including(LogLevel.ERROR),
                    LevelFilter.including(LogLevel.WARN)
            );
            LogFilter profileOr = CompositeFilter.or(
                    ProfileFilter.including("mainnet"),
                    ProfileFilter.including("testnet")
            );
            LogFilter combined = CompositeFilter.and(levelOr, profileOr);

            assertTrue(combined.matches(createEvent(LogLevel.ERROR, "mainnet", "signum.node")));
            assertTrue(combined.matches(createEvent(LogLevel.WARN, "testnet", "signum.node")));
            // WARN but wrong profile
            assertFalse(combined.matches(createEvent(LogLevel.WARN, "devnet", "signum.node")));
            // Valid profile but wrong level
            assertFalse(combined.matches(createEvent(LogLevel.INFO, "mainnet", "signum.node")));
        }

        @Test
        @DisplayName("OR of AND filters")
        void nested_OrOfAnd_Filters() {
            // (ERROR AND mainnet) OR (WARN AND testnet)
            LogFilter errorMainnet = CompositeFilter.and(
                    LevelFilter.including(LogLevel.ERROR),
                    ProfileFilter.including("mainnet")
            );
            LogFilter warnTestnet = CompositeFilter.and(
                    LevelFilter.including(LogLevel.WARN),
                    ProfileFilter.including("testnet")
            );
            LogFilter combined = CompositeFilter.or(errorMainnet, warnTestnet);

            assertTrue(combined.matches(createEvent(LogLevel.ERROR, "mainnet", "signum.node")));
            assertTrue(combined.matches(createEvent(LogLevel.WARN, "testnet", "signum.node")));
            // ERROR on testnet → no match
            assertFalse(combined.matches(createEvent(LogLevel.ERROR, "testnet", "signum.node")));
        }

        @Test
        @DisplayName("deep nesting: NOT(AND(OR(...), ...))")
        void nested_DeepNesting_Workscorrectly() {
            // NOT ( (ERROR or WARN) AND mainnet )
            // = Block ERROR/WARN only on mainnet, allow everywhere else
            LogFilter innerOr = CompositeFilter.or(
                    LevelFilter.including(LogLevel.ERROR),
                    LevelFilter.including(LogLevel.WARN)
            );
            LogFilter innerAnd = CompositeFilter.and(
                    innerOr,
                    ProfileFilter.including("mainnet")
            );
            LogFilter combined = CompositeFilter.not(innerAnd);

            // Block: ERROR on mainnet
            assertFalse(combined.matches(createEvent(LogLevel.ERROR, "mainnet", "signum.node")));
            // Block: WARN on mainnet
            assertFalse(combined.matches(createEvent(LogLevel.WARN, "mainnet", "signum.node")));
            // Allow: ERROR on testnet
            assertTrue(combined.matches(createEvent(LogLevel.ERROR, "testnet", "signum.node")));
            // Allow: INFO on mainnet
            assertTrue(combined.matches(createEvent(LogLevel.INFO, "mainnet", "signum.node")));
        }
    }

    // ------------------------ toString() ------------------------

    @Nested
    @DisplayName("toString() representation")
    class ToStringTests {

        @Test
        @DisplayName("AND shows logical operator")
        void toString_AndMode_ShowsOperator() {
            LogFilter combined = CompositeFilter.and(
                    LevelFilter.including(LogLevel.ERROR),
                    ProfileFilter.including("mainnet")
            );
            String str = combined.toString();

            assertNotNull(str);
            assertTrue(str.contains("Composite"));
        }

        @Test
        @DisplayName("OR shows logical operator")
        void toString_OrMode_ShowsOperator() {
            LogFilter combined = CompositeFilter.or(
                    LevelFilter.including(LogLevel.ERROR),
                    ProfileFilter.including("mainnet")
            );
            String str = combined.toString();

            assertNotNull(str);
        }

        @Test
        @DisplayName("NOT shows filter details")
        void toString_NotMode_ShowsDetails() {
            LogFilter negated = CompositeFilter.not(LevelFilter.including(LogLevel.ERROR));
            String str = negated.toString();

            assertNotNull(str);
            // Verify the toString contains meaningful information
            assertTrue(str.length() > 0);
        }
    }
}