package application.gui.shell;

import application.kernel.ApplicationShutdown;
import application.module.node.gui.animations.RotatingSvgIcon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ShutdownProgressDialog}.
 * <p>
 * The content-panel tests are headless-safe (no window creation). The
 * dialog tests are skipped when the environment is headless.
 *
 * @since 5.0
 */
@DisplayName("ShutdownProgressDialog Tests")
class ShutdownProgressDialogTest {

    // ====================================================================
    // Content panel (headless-safe)
    // ====================================================================

    @Nested
    @DisplayName("Content panel layout")
    class ContentPanelTests {

        private static RotatingSvgIcon sizedIcon() {
            RotatingSvgIcon icon = new RotatingSvgIcon(0.5);
            icon.setPreferredSize(new java.awt.Dimension(64, 64));
            icon.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            return icon;
        }

        @Test
        @DisplayName("contains the exact shutdown status message")
        void panel_containsStatusMessage() {
            JPanel panel = ShutdownProgressDialog.buildContentPanel(sizedIcon());

            JLabel label = findLabel(panel);
            assertNotNull(label, "status label must be present");
            assertEquals(ShutdownProgressDialog.STATUS_MESSAGE, label.getText());
            assertEquals("Shutdown in progress, this could take several minutes", label.getText());
        }

        @Test
        @DisplayName("contains a 64x64 rotating Signum icon")
        void panel_containsRotatingIcon() {
            RotatingSvgIcon icon = sizedIcon();
            JPanel panel = ShutdownProgressDialog.buildContentPanel(icon);

            RotatingSvgIcon found = findRotatingIcon(panel);
            assertSame(icon, found, "the provided icon must be embedded in the panel");
            assertEquals(64, found.getPreferredSize().width);
            assertEquals(64, found.getPreferredSize().height);
        }

        @Test
        @DisplayName("message label and icon are centered (BoxLayout X alignment)")
        void panel_centeredComponents() {
            RotatingSvgIcon icon = sizedIcon();
            JPanel panel = ShutdownProgressDialog.buildContentPanel(icon);

            assertEquals(java.awt.Component.CENTER_ALIGNMENT,
                    findLabel(panel).getAlignmentX());
            assertEquals(java.awt.Component.CENTER_ALIGNMENT,
                    findRotatingIcon(panel).getAlignmentX());
        }

        @Test
        @DisplayName("status message and title are stable public constants")
        void constants_haveExpectedValues() {
            assertEquals("Shutting down", ShutdownProgressDialog.DIALOG_TITLE);
            assertTrue(ShutdownProgressDialog.STATUS_MESSAGE.toLowerCase().contains("shutdown in progress"));
            assertTrue(ShutdownProgressDialog.STATUS_MESSAGE.toLowerCase().contains("several minutes"));
        }

        private static JLabel findLabel(JPanel panel) {
            for (java.awt.Component c : panel.getComponents()) {
                if (c instanceof JLabel) {
                    return (JLabel) c;
                }
            }
            return null;
        }

        private static RotatingSvgIcon findRotatingIcon(JPanel panel) {
            for (java.awt.Component c : panel.getComponents()) {
                if (c instanceof RotatingSvgIcon) {
                    return (RotatingSvgIcon) c;
                }
            }
            return null;
        }
    }

    // ====================================================================
    // Header panel (headless-safe)
    // ====================================================================

    @Nested
    @DisplayName("Header panel layout")
    class HeaderPanelTests {

        @Test
        @DisplayName("header contains the bold dialog title label")
        void header_containsTitle() {
            javax.swing.JLabel titleLabel = null;
            for (java.awt.Component c : ShutdownProgressDialog.buildHeaderPanel().getComponents()) {
                if (c instanceof javax.swing.JLabel
                        && ShutdownProgressDialog.DIALOG_TITLE.equals(((javax.swing.JLabel) c).getText())) {
                    titleLabel = (javax.swing.JLabel) c;
                }
            }
            assertNotNull(titleLabel, "title label with the dialog title must be present");
            assertTrue(titleLabel.getFont().isBold(), "title label must be bold");
        }

        @Test
        @DisplayName("header contains the red POWER_OFF glyph")
        void header_containsPowerOffGlyph() {
            boolean glyphFound = false;
            for (java.awt.Component c : ShutdownProgressDialog.buildHeaderPanel().getComponents()) {
                if (c instanceof javax.swing.JLabel && ((javax.swing.JLabel) c).getIcon() != null) {
                    glyphFound = true;
                }
            }
            assertTrue(glyphFound, "a label with the power-off glyph icon must be present");
        }

        @Test
        @DisplayName("power-off glyph renders at a non-zero size")
        void glyph_renders() {
            // IconFontSwing derives exact pixel dimensions from font metrics,
            // so the size is only asserted to be a sensible, non-zero value.
            javax.swing.Icon glyph = ShutdownProgressDialog.buildPowerOffGlyph(18);
            assertNotNull(glyph);
            assertTrue(glyph.getIconWidth() > 0);
            assertTrue(glyph.getIconHeight() > 0);
        }
    }

    // ====================================================================
    // Dialog behavior (requires a display)
    // ====================================================================

    @Nested
    @DisplayName("Dialog window behavior")
    class DialogTests {

        @Test
        @DisplayName("dialog is undecorated (no title bar, no X button) and not closable")
        void dialog_titleAndCloseOperation() {
            assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");

            ShutdownProgressDialog dialog = new ShutdownProgressDialog(null);
            try {
                assertTrue(dialog.isUndecorated(), "dialog must be undecorated so no X button is shown");
                assertEquals(ShutdownProgressDialog.DIALOG_TITLE, dialog.getTitle());
                assertEquals(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE,
                        dialog.getDefaultCloseOperation());
            } finally {
                dialog.dispose();
            }
        }

        @Test
        @DisplayName("open/close cycle starts and stops the icon animation without error")
        void dialog_openCloseCycle() {
            assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");

            ShutdownProgressDialog dialog = new ShutdownProgressDialog(null);
            assertDoesNotThrow(() -> {
                dialog.setVisible(true);
                dialog.dispose();
            });
        }
    }

    // ====================================================================
    // Duplicate trigger guard
    // ====================================================================

    @Nested
    @DisplayName("Duplicate trigger guard")
    class DuplicateTriggerTests {

        @Test
        @DisplayName("a trigger while the shutdown sequence already started is a no-op (headless-safe)")
        void showAndExecuteShutdown_ignoredWhenAlreadyInitiated() throws Exception {
            try (org.mockito.MockedStatic<ApplicationShutdown> sh =
                         org.mockito.Mockito.mockStatic(ApplicationShutdown.class)) {
                ApplicationShutdown shutdown = mock(ApplicationShutdown.class);
                when(shutdown.isShutdownInitiated()).thenReturn(true);
                sh.when(ApplicationShutdown::getInstance).thenReturn(shutdown);

                assertDoesNotThrow(() -> ShutdownProgressDialog.showAndExecuteShutdown(null));

                // Give a (mistakenly) started sequence thread a moment, then prove
                // it never ran. If the guard were missing, this path would even
                // reach System.exit and kill the test JVM.
                Thread.sleep(300);
                verify(shutdown, never()).executeShutdownSequence();
            }
        }
    }
}
