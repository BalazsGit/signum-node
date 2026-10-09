package application.module.node.gui.wizard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JRadioButton;
import application.utils.gui.GuiFontManager;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GUI-level regression tests for the wizard's "Step X of Y" indicator.
 * <p>
 * Drives the REAL {@link NodeSetupWizardDialog} (all five step panels built,
 * the controller live-bound to the shared context) and asserts that the
 * indicator follows the <b>visible</b> (auto-skip filtered) step sequence:
 * </p>
 * <ul>
 *   <li>SQLite (preselected) → 3 visible steps from the very first paint;</li>
 *   <li>clicking an engine radio updates the indicator <b>live</b> (5 ↔ 3)
 *       without any navigation;</li>
 *   <li>with SQLite, Back/Next walks exactly those 3 steps, numbered 1..3,
 *       in both directions.</li>
 * </ul>
 */
@DisplayName("NodeSetupWizardDialog step indicator (live, per-context)")
class NodeSetupWizardDialogIndicatorTest {

    private NodeSetupWizardDialog dialog;

    @BeforeAll
    static void registerIconFont() {
        // The same icon-font registration the application performs at startup
        // (AppearanceModule#init) so IconFontSwing resolves the header icons.
        jiconfont.swing.IconFontSwing.register(
                jiconfont.icons.font_awesome.FontAwesome.getIconFont());
    }

    @AfterEach
    void cleanup() {
        if (dialog != null) {
            dialog.dispose();
            dialog = null;
        }
    }

    @Test
    @DisplayName("the indicator is live: switching the engine radio updates 'Step 1 of N' immediately")
    void indicatorUpdatesLiveWithEngineSelection() {
        dialog = new NodeSetupWizardDialog(null, null);
        // SQLite is preselected → the server-only installation step (and the summary)
        // are invisible from the start
        assertEquals("Step 1 of 3 — Database", indicatorText());

        findRadio("MariaDB").doClick();
        assertEquals("Step 1 of 5 — Database", indicatorText());

        findRadio("SQLite").doClick();
        assertEquals("Step 1 of 3 — Database", indicatorText());
    }

    @Test
    @DisplayName("with SQLite the wizard walks exactly 3 steps, back and forth")
    void sqliteWalksExactlyThreeStepsBothWays() {
        dialog = new NodeSetupWizardDialog(null, null);
        assertEquals("Step 1 of 3 — Database", indicatorText());
        assertEquals("Next", findButton("Next").getText());

        clickButton("Next");
        assertEquals("Step 2 of 3 — Database configuration", indicatorText());

        clickButton("Back");
        assertEquals("Step 1 of 3 — Database", indicatorText());

        clickButton("Next");
        assertEquals("Step 2 of 3 — Database configuration", indicatorText());

        clickButton("Next");
        assertEquals("Step 3 of 3 — Node configuration", indicatorText());
        assertEquals("Finish", findButton("Finish").getText());

        clickButton("Back");
        assertEquals("Step 2 of 3 — Database configuration", indicatorText());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** The wizard's "Step X of Y — title" indicator label text. */
    @Test
    @DisplayName("the 'Step x/y — title' indicator uses the bold 1.2× keyword heading font")
    void indicatorUsesBoldScaledKeywordFont() {
        dialog = new NodeSetupWizardDialog(null, null);

        // Assert — proportional 1.2× bold heading style (same as the in-step titles)
        assertEquals(GuiFontManager.getBoldScaledDefaultFont(WizardStep.KEYWORD_FONT_SCALE),
                findIndicator().getFont(),
                "the step indicator should use the 1.2× bold keyword heading font");
    }

    /** The wizard's "Step X of Y — title" indicator label. */
    private JLabel findIndicator() {
        List<Component> out = new ArrayList<>();
        collectChildren(dialog, out);
        for (Component c : out) {
            if (c instanceof JLabel l && l.getText() != null && l.getText().startsWith("Step ")) {
                return l;
            }
        }
        throw new IllegalStateException("step indicator label not found");
    }

    private String indicatorText() {
        List<Component> out = new ArrayList<>();
        collectChildren(dialog, out);
        for (Component c : out) {
            if (c instanceof JLabel l && l.getText() != null && l.getText().startsWith("Step ")) {
                return l.getText();
            }
        }
        throw new IllegalStateException("step indicator label not found");
    }

    private JRadioButton findRadio(String text) {
        return findByClass(dialog, JRadioButton.class, text);
    }

    private JButton findButton(String text) {
        return findByClass(dialog, JButton.class, text);
    }

    private void clickButton(String text) {
        findByClass(dialog, JButton.class, text).doClick();
    }

    /**
     * Finds the button of the given type whose text matches.
     * <p>
     * The text check goes through {@link AbstractButton}, the common superclass
     * of Swing button types.
     * </p>
     */
    private static <T extends JComponent> T findByClass(java.awt.Container root, Class<T> type, String text) {
        List<Component> out = new ArrayList<>();
        collectChildren(root, out);
        for (Component c : out) {
            if (type.isInstance(c) && c instanceof AbstractButton b && text.equals(b.getText())) {
                return type.cast(c);
            }
        }
        throw new IllegalStateException(type.getSimpleName() + " not found: " + text);
    }

    private static void collectChildren(java.awt.Container root, List<Component> out) {
        for (Component c : root.getComponents()) {
            if (c instanceof JComponent j) {
                out.add(j);
                collectChildren(j, out);
            }
        }
    }
}