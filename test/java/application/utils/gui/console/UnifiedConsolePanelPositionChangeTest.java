package application.utils.gui.console;

import application.module.node.gui.SystemConsoleSubscriber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.function.Supplier;

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

    // ------------------------ Command position change (visibility-preserving) ------------------------

    /**
     * Regression tests for the show-command-input bug: the command input's
     * position toggle and visibility state must stay decoupled. Moving the
     * input while it is HIDDEN must not force it open (no stale collapse
     * callback may reposition + expand it later), and moving it while it is
     * VISIBLE must preserve its visibility.
     */
    @Nested
    @DisplayName("Command position change vs. visibility state")
    class PositionChangeVisibility {

        private UnifiedConsolePanel panel;

        @BeforeEach
        void setUp() {
            // systemConsole(): showCommandInput defaults to false -> inputPanel is
            // created lazily, mirroring the real node console startup state.
            panel = new UnifiedConsolePanel(
                    ConsolePanelConfiguration.systemConsole(),
                    SystemConsoleSubscriber.class);
        }

        @AfterEach
        void tearDown() {
            panel.dispose();
        }

        @Test
        @DisplayName("bug sequence: show -> hide -> move -> show -> hide stays hidden")
        void showHideMoveShowHide_doesNotForcePanelVisible() throws Exception {
            // 1. show (lazy init in BOTTOM slot)
            panel.setShowCommandInput(true);
            pumpUntil(panel::isCommandInputVisible, "input never became visible after show");

            // 2. hide
            panel.setShowCommandInput(false);
            pumpUntil(() -> !panel.isCommandInputVisible(), "input never became hidden after hide");

            // 3. move to TOP while hidden — must stay hidden
            panel.setCommandPosition(ConsoleInputPosition.TOP);
            pumpEdt();
            assertFalse(panel.isCommandInputVisible(),
                    "moving a HIDDEN input to TOP must not show it (stale collapse listener)");
            assertEquals(ConsoleInputPosition.TOP, panel.getEffectiveCommandPosition());

            // 4. show again — must appear at TOP
            panel.setShowCommandInput(true);
            pumpUntil(panel::isCommandInputVisible, "input never became visible at TOP");
            assertTrue(isAtTop(panel),
                    "input at TOP position must live in the header region");

            // 5. hide again
            panel.setShowCommandInput(false);
            pumpUntil(() -> !panel.isCommandInputVisible(), "input never became hidden at TOP");

            // 6. move back to BOTTOM while hidden — must stay hidden
            panel.setCommandPosition(ConsoleInputPosition.BOTTOM);
            pumpEdt();
            assertFalse(panel.isCommandInputVisible(),
                    "moving a HIDDEN input back to BOTTOM must not show it");

            // 7. show once more — must appear at BOTTOM
            panel.setShowCommandInput(true);
            pumpUntil(panel::isCommandInputVisible, "input never became visible at BOTTOM");
            assertTrue(isAtBottom(panel),
                    "input at BOTTOM position must live directly in the console panel");

            // Final hide — nothing may resurface it
            panel.setShowCommandInput(false);
            pumpUntil(() -> !panel.isCommandInputVisible(), "input never became hidden at the end");
            pumpEdt();
            pumpEdt();
            assertFalse(panel.isCommandInputVisible(),
                    "a hidden input must not reappear after unrelated EDT activity");
        }

        @Test
        @DisplayName("moving a hidden input is silent (no animation, no forced show)")
        void hiddenInput_positionChange_IsSilent() throws Exception {
            // inputPanel does not exist yet (lazy init)
            panel.setCommandPosition(ConsoleInputPosition.BOTTOM);
            assertEquals(ConsoleInputPosition.BOTTOM, panel.getEffectiveCommandPosition());
            assertFalse(panel.isCommandInputVisible(), "no input exists yet, so nothing can be visible");

            // create it, then hide it and move — the panel must stay collapsed
            panel.setShowCommandInput(true);
            pumpUntil(panel::isCommandInputVisible, "input never became visible");
            panel.setShowCommandInput(false);
            pumpUntil(() -> !panel.isCommandInputVisible(), "input never became hidden");

            panel.setCommandPosition(ConsoleInputPosition.BOTTOM);
            assertEquals(ConsoleInputPosition.BOTTOM, panel.getEffectiveCommandPosition());
            assertFalse(panel.isCommandInputVisible(),
                    "an in-flight/finished collapse must not be interrupted into an expand");

            // later show must appear at the (new) bottom slot
            panel.setShowCommandInput(true);
            pumpUntil(panel::isCommandInputVisible, "input never became visible after move");
            assertTrue(isAtBottom(panel),
                    "after a hidden move to BOTTOM the input must appear in the console panel");
        }

        @Test
        @DisplayName("moving a visible input preserves its visibility (animated reposition)")
        void visibleInput_positionChange_KeepsItVisible() throws Exception {
            panel.setShowCommandInput(true);
            pumpUntil(panel::isCommandInputVisible, "input never became visible");
            assertTrue(isAtBottom(panel),
                    "default BOTTOM position must live directly in the console panel");

            panel.setCommandPosition(ConsoleInputPosition.TOP);
            pumpEdt();
            // The position is already recorded (even mid-animation)…
            assertEquals(ConsoleInputPosition.TOP, panel.getEffectiveCommandPosition());
            // …and the panel must end up visible at the new slot.
            pumpUntil(() -> panel.isCommandInputVisible() && isAtTop(panel),
                    "visible input must end up expanded in the header region after a move");
        }

        @Test
        @DisplayName("instant position change while hidden (animation disabled) stays hidden")
        void hiddenInput_instantPositionChange_StaysHidden() throws Exception {
            UnifiedConsolePanel instant = new UnifiedConsolePanel(
                    ConsolePanelConfiguration.systemConsole().withAnimateCommandInput(false),
                    SystemConsoleSubscriber.class);
            try {
                instant.setShowCommandInput(true);
                pumpUntil(instant::isCommandInputVisible, "input never became visible");
                instant.setShowCommandInput(false);
                pumpUntil(() -> !instant.isCommandInputVisible(), "input never became hidden");

                instant.setCommandPosition(ConsoleInputPosition.TOP);
                pumpEdt();
                assertFalse(instant.isCommandInputVisible(),
                        "an instant move of a hidden input must not show it");
                assertEquals(ConsoleInputPosition.TOP, instant.getEffectiveCommandPosition());

                instant.setShowCommandInput(true);
                pumpUntil(instant::isCommandInputVisible, "input never became visible at TOP");
                assertTrue(isAtTop(instant),
                        "after an instant move to TOP the input must appear in the header region");
            } finally {
                instant.dispose();
            }
        }

        @Test
        @DisplayName("rapid show/hide toggling (double toggle) leaves no stale listener")
        void rapidDoubleToggle_thenMove_thenShow_appearsAtNewPosition() throws Exception {
            UnifiedConsolePanel top = new UnifiedConsolePanel(
                    ConsolePanelConfiguration.systemConsole().withCommandPosition(ConsoleInputPosition.TOP),
                    SystemConsoleSubscriber.class);
            try {
                top.setShowCommandInput(true);
                pumpUntil(top::isCommandInputVisible, "input never became visible");

                // Double toggle: hide then immediately show again. The hide starts a
                // collapse that must be superseded by the show (listener cleared).
                top.setShowCommandInput(false);
                top.setShowCommandInput(true);
                pumpUntil(top::isCommandInputVisible, "input must be visible after a double toggle");

                // Move while visible: collapse -> reposition -> expand at BOTTOM
                top.setCommandPosition(ConsoleInputPosition.BOTTOM);
                pumpUntil(() -> top.isCommandInputVisible() && isAtBottom(top),
                        "after the move the input must be visible in the console panel");

                // Hide and settle
                top.setShowCommandInput(false);
                pumpUntil(() -> !top.isCommandInputVisible(), "input never became hidden after move");
                pumpEdt();
                pumpEdt();
                assertFalse(top.isCommandInputVisible(),
                        "the superseded collapse from the double toggle must not resurface the input");
            } finally {
                top.dispose();
            }
        }

        // ── helpers ────────────────────────────────────────────────────────

        /** Pumps the EDT once so a single batch of pending events runs. */
        private static void pumpEdt() {
            try {
                SwingUtilities.invokeAndWait(() -> { });
            } catch (Exception e) {
                throw new AssertionError("EDT pump failed", e);
            }
        }

        /** Pumps the EDT until {@code condition} holds (or fails after the timeout). */
        private static void pumpUntil(Supplier<Boolean> condition, String timeoutMessage) {
            long deadline = System.currentTimeMillis() + 5_000;
            while (!readOnEdt(condition) && System.currentTimeMillis() < deadline) {
                pumpEdt();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while waiting: " + timeoutMessage, e);
                }
            }
            assertTrue(readOnEdt(condition), timeoutMessage);
        }

        private static boolean readOnEdt(Supplier<Boolean> supplier) {
            try {
                boolean[] result = new boolean[1];
                SwingUtilities.invokeAndWait(() -> result[0] = supplier.get());
                return result[0];
            } catch (Exception e) {
                throw new AssertionError("EDT read failed", e);
            }
        }

        /**
         * Whether the input panel sits directly in the console panel
         * (the BOTTOM position slot).
         */
        private static boolean isAtBottom(UnifiedConsolePanel console) {
            java.awt.Component input = console.getCommandInputPanel();
            return input.getParent() == console;
        }

        /**
         * Whether the input panel sits in the header region
         * (the TOP position slot).
         */
        private static boolean isAtTop(UnifiedConsolePanel console) {
            java.awt.Component input = console.getCommandInputPanel();
            return input.getParent() != null && input.getParent() != console;
        }
    }
}