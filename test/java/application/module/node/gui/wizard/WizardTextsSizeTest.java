package application.module.node.gui.wizard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The selectable display-text panes must report their preferred size
 * <b>reflowed at the fixed wrap width</b> (the natural width of an HTML
 * document is the full unwrapped line width, which would overflow the
 * fixed-size wizard dialog).
 */
@DisplayName("WizardTexts (selectable display text)")
class WizardTextsSizeTest {

    private static final String SENTENCE =
            "This is a deliberately very long single line of text that must wrap inside the fixed width of the wizard dialog for sure.";

    /** ~1300 px at the default font — wider than both wrap widths. */
    private static final String LONG_LINE = SENTENCE + " " + SENTENCE;

    @Test
    @DisplayName("a long single line wraps at the step width (fixed width, multi-line height)")
    void longLineWrapsAtStepWidth() {
        Dimension d = WizardTexts.info(LONG_LINE).getPreferredSize();
        assertEquals(WizardTexts.STEP_WRAP_WIDTH, d.width, "the width is clamped to the wrap width");
        assertTrue(d.height > 30, "the long line must render on more than one line: " + d);
    }

    @Test
    @DisplayName("the heading panes wrap the same way (bold 1.2x font)")
    void headingWrapsAtStepWidth() {
        Dimension dh = WizardTexts.heading(LONG_LINE).getPreferredSize();
        assertEquals(WizardTexts.STEP_WRAP_WIDTH, dh.width);
        assertTrue(dh.height > 30, "the heading must wrap too: " + dh);
    }

    @Test
    @DisplayName("the wrap width is honored per pane (the dialog messages wrap narrower)")
    void customWrapWidth() {
        Dimension d = WizardTexts.info(LONG_LINE, WizardTexts.DIALOG_WRAP_WIDTH).getPreferredSize();
        assertEquals(WizardTexts.DIALOG_WRAP_WIDTH, d.width);
        Dimension step = WizardTexts.info(LONG_LINE).getPreferredSize();
        assertTrue(d.height >= step.height, "the narrower wrap must need at least as many lines");
    }

    @Test
    @DisplayName("a short text stays one line high (but the full wrap width)")
    void shortTextSingleLine() {
        Dimension ds = WizardTexts.info("short").getPreferredSize();
        assertEquals(WizardTexts.STEP_WRAP_WIDTH, ds.width);
        assertTrue(ds.height > 0 && ds.height < 30, "a short text is exactly one line high: " + ds);
    }
}