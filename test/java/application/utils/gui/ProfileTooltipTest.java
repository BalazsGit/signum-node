package application.utils.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.Test;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Dimension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the profile-action tooltip width behavior: the box sizes itself to
 * its text and is only capped (and wrapped onto several lines) at
 * {@link ConfigurationUtils#PROFILE_TOOLTIP_MAX_WIDTH} when the text would be
 * wider than that. The configured value is a <em>maximum</em>, not a fixed
 * size — short tooltips must not be padded out to it.
 */
class ProfileTooltipTest {

    private static final int MAX = ConfigurationUtils.PROFILE_TOOLTIP_MAX_WIDTH;

    @Test
    void shortTooltipFitsItsText_notPaddedToMax() throws Exception {
        runOnEdt(() -> {
            String html = ConfigurationUtils.profileTooltip("Save");
            // Short text must not be pinned to the max width.
            assertFalse(html.contains("<body width="),
                    "short tooltip should render at its natural width, not be pinned to the max: " + html);
            Dimension d = rendered(html);
            assertTrue(d.width < MAX, "short tooltip width should be under the max, was " + d.width);
        });
    }

    @Test
    void longTooltipIsCappedAtMax_andWraps() throws Exception {
        runOnEdt(() -> {
            String longText = "Creates and selects a new appearance profile initialized with the built-in "
                    + "Default appearance, including all of its settings, palettes, fonts and accent colors.";
            String html = ConfigurationUtils.profileTooltip(longText);
            assertTrue(html.contains("<body width='" + MAX + "'>"),
                    "long tooltip should be pinned to the max width: " + html);
            Dimension wrapped = rendered(html);
            Dimension natural = rendered("<html>" + longText + "</html>");
            assertTrue(wrapped.width <= MAX + 1,
                    "wrapped tooltip width should be capped at the max, was " + wrapped.width);
            assertTrue(wrapped.width < natural.width,
                    "wrapping should make the tooltip narrower than the single-line text");
            assertTrue(wrapped.height > natural.height,
                    "wrapping should make the tooltip taller than the single-line text");
        });
    }

    @Test
    void tooltipNeverExceedsMaxWidth() throws Exception {
        runOnEdt(() -> {
            String[] samples = {
                    "Save",
                    "Creates and selects a new appearance profile",
                    "Apply<br><br>Applies the saved changes, marks the profile as applied<br>"
                            + "and restarts the node so they take effect."
            };
            for (String text : samples) {
                Dimension d = rendered(ConfigurationUtils.profileTooltip(text));
                assertTrue(d.width <= MAX + 1,
                        "tooltip for '" + text + "' must not exceed the max width, was " + d.width);
            }
        });
    }

    @Test
    void nullTextReturnsNull() {
        assertNull(ConfigurationUtils.profileTooltip(null));
    }

    /** Runs a block on the event dispatch thread (profileTooltip measures via a JLabel). */
    private static void runOnEdt(Runnable block) throws Exception {
        UIManager.setLookAndFeel(new FlatDarkLaf());
        final Throwable[] error = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                block.run();
            } catch (Throwable t) {
                error[0] = t;
            }
        });
        if (error[0] != null) {
            throw new AssertionError(error[0]);
        }
    }

    private static Dimension rendered(String html) {
        JLabel probe = new JLabel(html);
        probe.setBorder(BorderFactory.createEmptyBorder());
        return probe.getPreferredSize();
    }
}