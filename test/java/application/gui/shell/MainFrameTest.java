package application.gui.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
}
