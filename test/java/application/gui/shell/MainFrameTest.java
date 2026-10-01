package application.gui.shell;

import application.kernel.ApplicationShutdown;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.swing.JPanel;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MainFrame}, focused on the shutdown dimming behavior
 * (the translucent veil shown over the frame while the shutdown sequence runs).
 */
@DisplayName("MainFrame Tests")
class MainFrameTest {

    // ====================================================================
    // Shutdown dimming
    // ====================================================================

    @Nested
    @DisplayName("Shutdown dimming")
    class DimmingTests {

        @Test
        @DisplayName("dim veil exists on the glass pane, is hidden by default and toggles with setDimmed")
        void dim_veil_toggles() {
            assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");

            MainFrame frame = new MainFrame();
            try {
                JPanel glass = (JPanel) frame.getGlassPane();
                assertEquals(1, glass.getComponentCount(),
                        "the dim veil must be hosted on the glass pane");

                assertFalse(glass.getComponent(0).isVisible(),
                        "the dim veil must be hidden by default");

                frame.setDimmed(true);
                assertTrue(glass.getComponent(0).isVisible(),
                        "setDimmed(true) must reveal the veil");

                frame.setDimmed(false);
                assertFalse(glass.getComponent(0).isVisible(),
                        "setDimmed(false) must hide the veil again");
            } finally {
                frame.dispose();
            }
        }
    }

    // ====================================================================
    // Trailing shutdown button
    // ====================================================================

    @Nested
    @DisplayName("Trailing shutdown button")
    class TrailingShutdownTests {

        @Test
        @DisplayName("the module tab row ends with the restart and shutdown buttons (trailingComponent client property)")
        void moduleTabRow_hasTrailingRestartAndShutdownButtons() {
            assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");

            MainFrame frame = new MainFrame();
            try {
                javax.swing.JTabbedPane pane = (javax.swing.JTabbedPane) frame.getTabManager().getComponent();
                java.awt.Container trailing =
                        (java.awt.Container) pane.getClientProperty("JTabbedPane.trailingComponent");
                assertNotNull(trailing, "the tab row must host the trailing buttons via trailingComponent");

                List<javax.swing.JButton> buttons = new ArrayList<>();
                collectButtons(trailing, buttons);
                assertEquals(2, buttons.size(),
                        "the trailing content must hold the restart and the shutdown button");
                // Restart sits to the left of (before) the shutdown button.
                assertEquals("Restart the entire application (all nodes restart)",
                        buttons.get(0).getToolTipText());
                assertEquals("Gracefully shut down all components and exit the application",
                        buttons.get(1).getToolTipText());
            } finally {
                frame.dispose();
            }
        }

        /** Depth-first collects every JButton under the given container (in order). */
        private static void collectButtons(java.awt.Container container, List<javax.swing.JButton> out) {
            for (java.awt.Component c : container.getComponents()) {
                if (c instanceof javax.swing.JButton b) {
                    out.add(b);
                }
                if (c instanceof java.awt.Container sub) {
                    collectButtons(sub, out);
                }
            }
        }
    }

    // ====================================================================
    // Duplicate shutdown guard
    // ====================================================================

    @Nested
    @DisplayName("Duplicate shutdown guard")
    class DuplicateShutdownTests {

        @Test
        @Timeout(10)
        @DisplayName("confirmAndShutdown is a no-op while the shutdown sequence already runs (no confirm dialog)")
        void confirmAndShutdown_ignoredWhenShutdownInitiated() {
            assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");

            try (org.mockito.MockedStatic<ApplicationShutdown> sh =
                         org.mockito.Mockito.mockStatic(ApplicationShutdown.class)) {
                ApplicationShutdown shutdown = mock(ApplicationShutdown.class);
                when(shutdown.isShutdownInitiated()).thenReturn(true);
                sh.when(ApplicationShutdown::getInstance).thenReturn(shutdown);

                MainFrame frame = new MainFrame();
                try {
                    // Would hang on the modal confirm dialog if the guard were missing.
                    assertDoesNotThrow(frame::confirmAndShutdown);
                } finally {
                    frame.dispose();
                }
            }
        }
    }
}
