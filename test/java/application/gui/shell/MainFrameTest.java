package application.gui.shell;

import application.kernel.ApplicationShutdown;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.swing.JPanel;
import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
