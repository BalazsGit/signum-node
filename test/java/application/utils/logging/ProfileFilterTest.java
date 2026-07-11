package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.ProfileFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProfileFilter}.
 * <p>
 * Verifies inclusion/exclusion modes, null-profile (bootstrap) handling,
 * includingWithBootstrap(), excludingNullProfile(), and input validation.
 * </p>
 */
@DisplayName("ProfileFilter Tests")
class ProfileFilterTest {

    private LogEvent createEvent(String profileName) {
        return new LogEvent.Builder()
                .level(LogLevel.INFO)
                .message("test message")
                .profileName(profileName)
                .build();
    }

    private LogEvent createEventWithLevel(String profileName, LogLevel level) {
        return new LogEvent.Builder()
                .level(level)
                .message("test message")
                .profileName(profileName)
                .build();
    }

    // ------------------------ including() ------------------------

    @Nested
    @DisplayName("including() - inclusion mode")
    class IncludingTests {

        @Test
        @DisplayName("single profile: matching event passes")
        void including_GivenMatchingProfile_ReturnsTrue() {
            LogFilter filter = ProfileFilter.including("mainnet-prune");
            assertTrue(filter.matches(createEvent("mainnet-prune")));
        }

        @Test
        @DisplayName("single profile: non-matching event is blocked")
        void including_GivenNonMatchingProfile_ReturnsFalse() {
            LogFilter filter = ProfileFilter.including("mainnet-prune");
            assertFalse(filter.matches(createEvent("testnet-prune")));
        }

        @Test
        @DisplayName("multiple profiles: any matching passes")
        void including_GivenMultipleProfiles_MatchesAny() {
            LogFilter filter = ProfileFilter.including("mainnet", "testnet", "devnet");

            assertTrue(filter.matches(createEvent("mainnet")));
            assertTrue(filter.matches(createEvent("testnet")));
            assertTrue(filter.matches(createEvent("devnet")));
            assertFalse(filter.matches(createEvent("staging")));
        }

        @Test
        @DisplayName("null profile (bootstrap): blocked in including mode")
        void including_GivenNullProfile_ReturnsFalse() {
            LogFilter filter = ProfileFilter.including("mainnet");
            assertFalse(filter.matches(createEvent(null)));
        }

        @Test
        @DisplayName("null event returns false")
        void including_GivenNullEvent_ReturnsFalse() {
            LogFilter filter = ProfileFilter.including("mainnet");
            assertFalse(filter.matches(null));
        }

        @Test
        @DisplayName("null varargs throws IllegalArgumentException")
        void including_GivenNullVarargs_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> ProfileFilter.including((String[]) null));
        }

        @Test
        @DisplayName("empty varargs throws IllegalArgumentException")
        void including_GivenEmptyVarargs_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> ProfileFilter.including());
        }

        @Test
        @DisplayName("null profile name in varargs throws NullPointerException")
        void including_GivenNullElement_ThrowsNPE() {
            assertThrows(NullPointerException.class, () -> ProfileFilter.including("mainnet", null));
        }
    }

    // ------------------------ excluding() ------------------------

    @Nested
    @DisplayName("excluding() - exclusion mode")
    class ExcludingTests {

        @Test
        @DisplayName("single profile: matching event is blocked")
        void excluding_GivenMatchingProfile_ReturnsFalse() {
            LogFilter filter = ProfileFilter.excluding("noisy-profile");
            assertFalse(filter.matches(createEvent("noisy-profile")));
        }

        @Test
        @DisplayName("single profile: non-matching event passes")
        void excluding_GivenNonMatchingProfile_ReturnsTrue() {
            LogFilter filter = ProfileFilter.excluding("noisy-profile");
            assertTrue(filter.matches(createEvent("mainnet")));
        }

        @Test
        @DisplayName("multiple excluded profiles: all blocked, rest pass")
        void excluding_GivenMultipleProfiles_BlocksAllSpecified() {
            LogFilter filter = ProfileFilter.excluding("noise1", "noise2");

            assertFalse(filter.matches(createEvent("noise1")));
            assertFalse(filter.matches(createEvent("noise2")));
            assertTrue(filter.matches(createEvent("mainnet")));
            assertTrue(filter.matches(createEvent("testnet")));
        }

        @Test
        @DisplayName("null profile (bootstrap): passes in excluding mode")
        void excluding_GivenNullProfile_ReturnsTrue() {
            LogFilter filter = ProfileFilter.excluding("mainnet");
            // Null profile is not in the exclude set, so it passes
            assertTrue(filter.matches(createEvent(null)));
        }

        @Test
        @DisplayName("null event returns false")
        void excluding_GivenNullEvent_ReturnsFalse() {
            LogFilter filter = ProfileFilter.excluding("mainnet");
            assertFalse(filter.matches(null));
        }

        @Test
        @DisplayName("null varargs throws IllegalArgumentException")
        void excluding_GivenNullVarargs_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> ProfileFilter.excluding((String[]) null));
        }

        @Test
        @DisplayName("empty varargs throws IllegalArgumentException")
        void excluding_GivenEmptyVarargs_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> ProfileFilter.excluding());
        }
    }

    // ------------------------ includingWithBootstrap() ------------------------

    @Nested
    @DisplayName("includingWithBootstrap() - includes null-profile events")
    class IncludingWithBootstrapTests {

        @Test
        @DisplayName("specified profiles pass")
        void includingWithBootstrap_GivenMatchingProfile_ReturnsTrue() {
            LogFilter filter = ProfileFilter.includingWithBootstrap("mainnet", "testnet");

            assertTrue(filter.matches(createEvent("mainnet")));
            assertTrue(filter.matches(createEvent("testnet")));
        }

        @Test
        @DisplayName("non-specified profiles are blocked")
        void includingWithBootstrap_GivenNonMatchingProfile_ReturnsFalse() {
            LogFilter filter = ProfileFilter.includingWithBootstrap("mainnet");
            assertFalse(filter.matches(createEvent("devnet")));
        }

        @Test
        @DisplayName("null profile (bootstrap) passes")
        void includingWithBootstrap_GivenNullProfile_ReturnsTrue() {
            LogFilter filter = ProfileFilter.includingWithBootstrap("mainnet");
            assertTrue(filter.matches(createEvent(null)));
        }

        @Test
        @DisplayName("null event returns false")
        void includingWithBootstrap_GivenNullEvent_ReturnsFalse() {
            LogFilter filter = ProfileFilter.includingWithBootstrap("mainnet");
            assertFalse(filter.matches(null));
        }

        @Test
        @DisplayName("null varargs throws IllegalArgumentException")
        void includingWithBootstrap_GivenNullVarargs_ThrowsException() {
            assertThrows(IllegalArgumentException.class,
                    () -> ProfileFilter.includingWithBootstrap((String[]) null));
        }

        @Test
        @DisplayName("empty varargs throws IllegalArgumentException")
        void includingWithBootstrap_GivenEmptyVarargs_ThrowsException() {
            assertThrows(IllegalArgumentException.class, () -> ProfileFilter.includingWithBootstrap());
        }
    }

    // ------------------------ excludingNullProfile() ------------------------

    @Nested
    @DisplayName("excludingNullProfile() - hide bootstrap logs")
    class ExcludingNullProfileTests {

        @Test
        @DisplayName("non-null profile passes")
        void excludingNullProfile_GivenValidProfile_ReturnsTrue() {
            LogFilter filter = ProfileFilter.excludingNullProfile();

            assertTrue(filter.matches(createEvent("mainnet")));
            assertTrue(filter.matches(createEvent("testnet")));
            assertTrue(filter.matches(createEvent("any-profile")));
        }

        @Test
        @DisplayName("null profile (bootstrap) is blocked")
        void excludingNullProfile_GivenNullProfile_ReturnsFalse() {
            LogFilter filter = ProfileFilter.excludingNullProfile();
            assertFalse(filter.matches(createEvent(null)));
        }

        @Test
        @DisplayName("null event returns false")
        void excludingNullProfile_GivenNullEvent_ReturnsFalse() {
            LogFilter filter = ProfileFilter.excludingNullProfile();
            assertFalse(filter.matches(null));
        }

        @Test
        @DisplayName("returns a LogFilter (lambda implementation)")
        void excludingNullProfile_ReturnsLogFilter() {
            LogFilter filter = ProfileFilter.excludingNullProfile();
            assertNotNull(filter);
            // It's a lambda, so it implements LogFilter but is NOT a ProfileFilter instance
            assertTrue(filter.matches(createEvent("mainnet")));
        }
    }

    // ------------------------ Case Sensitivity ------------------------

    @Nested
    @DisplayName("Case sensitivity")
    class CaseSensitivityTests {

        @Test
        @DisplayName("profile names are case-sensitive (exact match)")
        void including_CaseSensitive_MatchesExact() {
            LogFilter filter = ProfileFilter.including("Mainnet");

            assertTrue(filter.matches(createEvent("Mainnet")));
            assertFalse(filter.matches(createEvent("mainnet")));
            assertFalse(filter.matches(createEvent("MAINNET")));
        }

        @Test
        @DisplayName("excluding is also case-sensitive")
        void excluding_CaseSensitive_MatchesExact() {
            LogFilter filter = ProfileFilter.excluding("TestNet");

            assertFalse(filter.matches(createEvent("TestNet")));
            assertTrue(filter.matches(createEvent("testnet")));
        }
    }

    // ------------------------ toString() ------------------------

    @Nested
    @DisplayName("toString() representation")
    class ToStringTests {

        @Test
        @DisplayName("including mode shows 'include' and matchNull=false")
        void toString_IncludingMode_ShowsCorrectDetails() {
            LogFilter filter = ProfileFilter.including("mainnet", "testnet");
            String str = filter.toString();

            assertTrue(str.contains("include"));
            assertTrue(str.contains("matchNull="));
        }

        @Test
        @DisplayName("excluding mode shows 'exclude'")
        void toString_ExcludingMode_ShowsExclude() {
            LogFilter filter = ProfileFilter.excluding("noise");
            String str = filter.toString();

            assertTrue(str.contains("exclude"));
        }

        @Test
        @DisplayName("includingWithBootstrap shows matchNull=true")
        void toString_IncludingWithBootstrap_ShowsMatchNullTrue() {
            LogFilter filter = ProfileFilter.includingWithBootstrap("mainnet");
            String str = filter.toString();

            assertTrue(str.contains("matchNull=true"));
        }
    }

    // ------------------------ Immutability ------------------------

    @Nested
    @DisplayName("Immutability guarantees")
    class ImmutabilityTests {

        @Test
        @DisplayName("filter behavior is consistent across multiple calls")
        void immutability_MultipleMatchesCalls_ReturnConsistentResults() {
            LogFilter filter = ProfileFilter.including("mainnet");
            LogEvent event = createEvent("mainnet");

            for (int i = 0; i < 100; i++) {
                assertTrue(filter.matches(event));
            }
        }

        @Test
        @DisplayName("filter is safe for concurrent use")
        void immutability_ConcurrentAccess_IsSafe() throws InterruptedException {
            LogFilter filter = ProfileFilter.including("mainnet", "testnet");
            Thread[] threads = new Thread[10];
            boolean[] results = new boolean[10];

            for (int i = 0; i < threads.length; i++) {
                final int index = i;
                final String profile = (i % 2 == 0) ? "mainnet" : "devnet";
                threads[i] = new Thread(() -> {
                    results[index] = filter.matches(createEvent(profile));
                });
                threads[i].start();
            }

            for (Thread t : threads) {
                t.join();
            }

            for (int i = 0; i < results.length; i++) {
                if (i % 2 == 0) {
                    assertTrue(results[i], "Index " + i + " should be true (mainnet)");
                } else {
                    assertFalse(results[i], "Index " + i + " should be false (devnet)");
                }
            }
        }
    }
}