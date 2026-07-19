package application.utils.gui.console;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link UnifiedConsolePanel} command position change behavior.
 * <p>
 * Tests cover configuration defaults and position tracking logic.
 * NOTE: UI interaction tests (hide/show/toggle animations) require EDT and are
 * excluded from headless execution — they are validated through manual testing
 * and integration scenarios where a real display is available.
 * </p>
 */
@DisplayName("UnifiedConsolePanel - Position Change Tests")
class UnifiedConsolePanelPositionChangeTest {

    // ------------------------ Configuration Validation ------------------------

    @Nested
    @DisplayName("Configuration defaults")
    class ConfigDefaults {

        @Test
        @DisplayName("animation is enabled by default")
        void animateCommandInput_GivenDefaultConfig_IsTrue() {
            ConsolePanelConfiguration config = ConsolePanelConfiguration.systemConsole();
            assertTrue(config.isAnimateCommandInput());
        }

        @Test
        @DisplayName("command position defaults to BOTTOM")
        void commandPosition_GivenDefaultConfig_IsBottom() {
            ConsolePanelConfiguration config = ConsolePanelConfiguration.systemConsole();
            assertEquals(ConsoleInputPosition.BOTTOM, config.getCommandPosition());
        }

        @Test
        @DisplayName("animation can be disabled")
        void animateCommandInput_GivenWithAnimateFalse_IsFalse() {
            ConsolePanelConfiguration config = ConsolePanelConfiguration.systemConsole()
                    .withAnimateCommandInput(false);
            assertFalse(config.isAnimateCommandInput());
        }

        @Test
        @DisplayName("command position can be set to TOP")
        void commandPosition_GivenWithTop_IsTop() {
            ConsolePanelConfiguration config = ConsolePanelConfiguration.systemConsole()
                    .withCommandPosition(ConsoleInputPosition.TOP);
            assertEquals(ConsoleInputPosition.TOP, config.getCommandPosition());
        }

        @Test
        @DisplayName("command position rejects null")
        void commandPosition_GivenNull_ThrowsNPE() {
            assertThrows(NullPointerException.class,
                    () -> ConsolePanelConfiguration.systemConsole().withCommandPosition(null));
        }

        @Test
        @DisplayName("configuration supports method chaining")
        void config_GivenFluentCalls_ReturnsSameInstance() {
            ConsolePanelConfiguration config = ConsolePanelConfiguration.systemConsole();
            assertSame(config, config.withAnimateCommandInput(true));
            assertSame(config, config.withCommandPosition(ConsoleInputPosition.TOP));
            assertSame(config, config.withShowCommandInput(true));
            assertSame(config, config.withMaxLines(500));
        }

        @Test
        @DisplayName("animate command input default is true")
        void animateCommandInput_DefaultIsTrue() {
            ConsolePanelConfiguration config = ConsolePanelConfiguration.systemConsole();
            assertTrue(config.isAnimateCommandInput());
        }

        @Test
        @DisplayName("command input visible default is false")
        void commandInputVisible_DefaultIsFalse() {
            ConsolePanelConfiguration config = ConsolePanelConfiguration.systemConsole();
            assertFalse(config.isCommandInputVisible());
        }

        @Test
        @DisplayName("enable command toggle default is true")
        void enableCommandToggle_DefaultIsTrue() {
            ConsolePanelConfiguration config = ConsolePanelConfiguration.systemConsole();
            assertTrue(config.isEnableCommandToggle());
        }
    }

    // ------------------------ ConsoleInputPosition Enum ------------------------

    @Nested
    @DisplayName("ConsoleInputPosition enum")
    class PositionEnumTests {

        @Test
        @DisplayName("TOP position exists")
        void topPosition_Exists() {
            assertNotNull(ConsoleInputPosition.TOP);
        }

        @Test
        @DisplayName("BOTTOM position exists")
        void bottomPosition_Exists() {
            assertNotNull(ConsoleInputPosition.BOTTOM);
        }

        @Test
        @DisplayName("enum has exactly two values")
        void enumValues_CountIsTwo() {
            assertEquals(2, ConsoleInputPosition.values().length);
        }

        @Test
        @DisplayName("TOP and BOTTOM are different")
        void positions_AreDifferent() {
            assertNotSame(ConsoleInputPosition.TOP, ConsoleInputPosition.BOTTOM);
        }
    }

    // ------------------------ Configuration toString ------------------------

    @Nested
    @DisplayName("Configuration diagnostics")
    class ConfigDiagnostics {

        @Test
        @DisplayName("toString includes animateCommandInput")
        void toString_ContainsAnimateSetting() {
            ConsolePanelConfiguration config = ConsolePanelConfiguration.systemConsole()
                    .withAnimateCommandInput(false)
                    .withShowCommandInput(true)
                    .withCommandPosition(ConsoleInputPosition.TOP);

            String str = config.toString();
            assertTrue(str.contains("animateCommandInput="));
            assertTrue(str.contains("commandPosition=TOP"));
        }

        @Test
        @DisplayName("toString reflects command position")
        void toString_ContainsPosition() {
            ConsolePanelConfiguration bottomConfig = ConsolePanelConfiguration.systemConsole()
                    .withCommandPosition(ConsoleInputPosition.BOTTOM);
            assertTrue(bottomConfig.toString().contains("commandPosition=BOTTOM"));

            ConsolePanelConfiguration topConfig = ConsolePanelConfiguration.systemConsole()
                    .withCommandPosition(ConsoleInputPosition.TOP);
            assertTrue(topConfig.toString().contains("commandPosition=TOP"));
        }
    }

    // ------------------------ Profile Console Config ------------------------

    @Nested
    @DisplayName("Profile console configuration")
    class ProfileConsoleConfig {

        @Test
        @DisplayName("profile console supports animation setting")
        void profileConsole_SupportsAnimateSetting() {
            ConsolePanelConfiguration config = ConsolePanelConfiguration.profileConsole("test")
                    .withAnimateCommandInput(false);
            assertFalse(config.isAnimateCommandInput());
        }

        @Test
        @DisplayName("profile console supports position setting")
        void profileConsole_SupportsPositionSetting() {
            ConsolePanelConfiguration config = ConsolePanelConfiguration.profileConsole("test")
                    .withCommandPosition(ConsoleInputPosition.TOP);
            assertEquals(ConsoleInputPosition.TOP, config.getCommandPosition());
        }

        @Test
        @DisplayName("profile console rejects null name")
        void profileConsole_GivenNullName_ThrowsIAE() {
            assertThrows(IllegalArgumentException.class,
                    () -> ConsolePanelConfiguration.profileConsole(null));
        }

        @Test
        @DisplayName("profile console rejects empty name")
        void profileConsole_GivenEmptyName_ThrowsIAE() {
            assertThrows(IllegalArgumentException.class,
                    () -> ConsolePanelConfiguration.profileConsole(""));
        }
    }
}