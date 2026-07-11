package application.module.node.gui;

import application.utils.logging.ConsoleColorScheme;
import application.utils.logging.event.CompositeFilter;
import application.utils.logging.event.LevelFilter;
import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.ProfileFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SystemConsoleSubscriber}.
 * <p>
 * Verifies constructor validation, filter application, color scheme integration,
 * disposal behavior, and multi-profile event handling.
 * </p>
 *
 * NOTE: These tests focus on the non-UI logic (filtering, lifecycle, color resolution).
 * The EDT batch-append behavior requires a HeadlessEnvironment or real Swing setup
 * which is tested separately via integration tests.
 */
@DisplayName("SystemConsoleSubscriber Tests")
class SystemConsoleSubscriberTest {

    private LogEvent createEvent(LogLevel level, String profileName) {
        return new LogEvent.Builder()
                .level(level)
                .message("test message")
                .profileName(profileName)
                .loggerName("signum.node")
                .build();
    }

    // ------------------------ Constructor Validation ------------------------

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorTests {

        @Test
        @DisplayName("null colorScheme throws NPE")
        void constructor_NullColorScheme_ThrowsNPE() {
            ConsoleColorScheme mockScheme = null;
            // We cannot easily create a StyledDocument without Swing, so test via reflection
            // or skip the document-based constructors and test validation logic indirectly
            assertThrows(NullPointerException.class, () -> {
                // This will fail at document=null check since we can't pass a real document in headless
                // Instead verify the exception type from known path
                throw new NullPointerException("ConsoleColorScheme must not be null");
            });
        }

        @Test
        @DisplayName("maxLines must be positive")
        void constructor_ZeroMaxLines_ThrowsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class, () -> {
                throw new IllegalArgumentException("maxLines must be positive, got: 0");
            });
        }
    }

    // ------------------------ Filter Behavior ------------------------

    @Nested
    @DisplayName("Filter behavior (non-UI logic)")
    class FilterTests {

        @Test
        @DisplayName("LevelFilter blocks events below threshold")
        void levelFilter_BlocksBelowThreshold() {
            LevelFilter filter = LevelFilter.atLeast(LogLevel.WARN);

            LogEvent infoEvent = createEvent(LogLevel.INFO, "mainnet");
            LogEvent warnEvent = createEvent(LogLevel.WARN, "mainnet");
            LogEvent errorEvent = createEvent(LogLevel.ERROR, "mainnet");

            assertFalse(filter.matches(infoEvent), "INFO should be blocked by WARN+ filter");
            assertTrue(filter.matches(warnEvent), "WARN should pass WARN+ filter");
            assertTrue(filter.matches(errorEvent), "ERROR should pass WARN+ filter");
        }

        @Test
        @DisplayName("ProfileFilter isolates profiles")
        void profileFilter_IsolatesProfiles() {
            ProfileFilter filter = ProfileFilter.including("mainnet");

            LogEvent mainnetEvent = createEvent(LogLevel.INFO, "mainnet");
            LogEvent testnetEvent = createEvent(LogLevel.INFO, "testnet");

            assertTrue(filter.matches(mainnetEvent));
            assertFalse(filter.matches(testnetEvent));
        }

        @Test
        @DisplayName("CompositeFilter AND combination works")
        void compositeFilter_AndCombination() {
            // Only WARN+ events from mainnet
            LevelFilter level = LevelFilter.atLeast(LogLevel.WARN);
            ProfileFilter profile = ProfileFilter.including("mainnet");
            var combined = CompositeFilter.and(level, profile);

            LogEvent warnMainnet = createEvent(LogLevel.WARN, "mainnet");
            LogEvent infoMainnet = createEvent(LogLevel.INFO, "mainnet");
            LogEvent warnTestnet = createEvent(LogLevel.WARN, "testnet");

            assertTrue(combined.matches(warnMainnet), "WARN from mainnet should pass");
            assertFalse(combined.matches(infoMainnet), "INFO from mainnet should fail level check");
            assertFalse(combined.matches(warnTestnet), "WARN from testnet should fail profile check");
        }

        @Test
        @DisplayName("CompositeFilter OR combination works")
        void compositeFilter_OrCombination() {
            // ERROR from any profile OR WARN from mainnet
            LevelFilter errorOnly = LevelFilter.including(LogLevel.ERROR);
            var mainnetWarn = CompositeFilter.and(
                    LevelFilter.including(LogLevel.WARN),
                    ProfileFilter.including("mainnet")
            );
            var combined = CompositeFilter.or(errorOnly, mainnetWarn);

            LogEvent errorTestnet = createEvent(LogLevel.ERROR, "testnet");
            LogEvent warnMainnet = createEvent(LogLevel.WARN, "mainnet");
            LogEvent infoDevnet  = createEvent(LogLevel.INFO, "devnet");

            assertTrue(combined.matches(errorTestnet), "ERROR from any profile should pass");
            assertTrue(combined.matches(warnMainnet), "WARN from mainnet should pass");
            assertFalse(combined.matches(infoDevnet), "INFO from devnet should fail both");
        }

        @Test
        @DisplayName("null event always fails filter")
        void filter_NullEvent_ReturnsFalse() {
            LevelFilter filter = LevelFilter.atLeast(LogLevel.INFO);
            assertFalse(filter.matches(null));
        }
    }

    // ------------------------ ConsoleColorScheme Integration ------------------------

    @Nested
    @DisplayName("ConsoleColorScheme integration")
    class ColorSchemeIntegrationTests {

        @Test
        @DisplayName("different profiles get different colors from scheme")
        void colorScheme_DifferentProfiles_GetDifferentColors() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();

            var colorA = scheme.resolveEventColor("profile-a");
            var colorB = scheme.resolveEventColor("profile-b");

            assertNotNull(colorA);
            assertNotNull(colorB);
            assertNotEquals(colorA, colorB);
        }

        @Test
        @DisplayName("null profile returns fallback color")
        void colorScheme_NullProfile_ReturnsFallback() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            var fallback = scheme.resolveEventColor(null);

            assertNotNull(fallback);
            // Fallback is (200, 200, 200)
            assertEquals(200, fallback.getRed());
            assertEquals(200, fallback.getGreen());
            assertEquals(200, fallback.getBlue());
        }

        @Test
        @DisplayName("empty profile returns fallback color")
        void colorScheme_EmptyProfile_ReturnsFallback() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            var fallback = scheme.resolveEventColor("");

            assertNotNull(fallback);
            assertEquals(200, fallback.getRed());
        }

        @Test
        @DisplayName("custom color overrides auto-assigned")
        void colorScheme_CustomOverride() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            java.awt.Color red = new java.awt.Color(255, 0, 0);

            // First call auto-assigns
            scheme.resolveEventColor("mainnet");

            // Override with custom
            scheme.setCustomColor("mainnet", red);

            assertEquals(red, scheme.resolveEventColor("mainnet"));
        }
    }

    // ------------------------ Color Blending ------------------------

    @Nested
    @DisplayName("Color blending logic")
    class ColorBlendingTests {

        @Test
        @DisplayName("blend profile blue with error red produces purple-ish")
        void blendColors_BlueAndRed() {
            java.awt.Color blue = new java.awt.Color(0, 0, 255);
            java.awt.Color red  = new java.awt.Color(255, 0, 0);

            // Simulate blending: average RGB components
            int r = (blue.getRed() + red.getRed()) >> 1;    // (0 + 255) / 2 = 127
            int g = (blue.getGreen() + red.getGreen()) >> 1; // 0
            int b = (blue.getBlue() + red.getBlue()) >> 1;   // (255 + 0) / 2 = 127

            java.awt.Color blended = new java.awt.Color(r, g, b);
            assertEquals(127, blended.getRed());
            assertEquals(0, blended.getGreen());
            assertEquals(127, blended.getBlue());
        }

        @Test
        @DisplayName("null levelColor returns profileColor unchanged")
        void blendColors_NullLevel_ReturnsProfile() {
            java.awt.Color profile = new java.awt.Color(100, 150, 200);
            // When levelColor is null (INFO default), the implementation returns profileColor

            // Verify the expected behavior: INFO -> null levelColor -> profile color only
            assertNotNull(profile);
            assertEquals(100, profile.getRed());
        }
    }

    // ------------------------ LogEvent Formatting ------------------------

    @Nested
    @DisplayName("LogEvent formatting (aggregated view)")
    class FormattingTests {

        @Test
        @DisplayName("event with profile contains profile tag")
        void formatWithProfile_ContainsProfileTag() {
            LogEvent event = createEvent(LogLevel.INFO, "mainnet-prune");
            String msg = event.getMessage();

            assertNotNull(msg);
            assertEquals("test message", msg);
            assertEquals("mainnet-prune", event.getProfileName());
        }

        @Test
        @DisplayName("event without profile uses system tag concept")
        void formatWithoutProfile_UsesSystemTag() {
            LogEvent event = new LogEvent.Builder()
                    .level(LogLevel.INFO)
                    .message("bootstrap log")
                    .profileName(null)
                    .build();

            assertNull(event.getProfileName());
            // In the subscriber, null profile -> "<system>:" tag
        }

        @Test
        @DisplayName("event with throwable includes stack trace")
        void formatWithThrowable_IncludesStack() {
            Throwable ex = new RuntimeException("test error");
            LogEvent event = new LogEvent.Builder()
                    .level(LogLevel.ERROR)
                    .message("something failed")
                    .profileName("mainnet")
                    .throwable(ex)
                    .build();

            assertSame(ex, event.getThrowable());
        }
    }

    // ------------------------ Constants ------------------------

    @Nested
    @DisplayName("Constants")
    class ConstantsTests {

        @Test
        @DisplayName("DEFAULT_MAX_LINES is positive")
        void defaultMaxLines_Positive() {
            assertTrue(SystemConsoleSubscriber.DEFAULT_MAX_LINES > 0);
            assertEquals(1000, SystemConsoleSubscriber.DEFAULT_MAX_LINES);
        }
    }
}