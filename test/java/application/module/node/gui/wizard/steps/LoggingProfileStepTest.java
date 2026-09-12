package application.module.node.gui.wizard.steps;

import application.module.node.gui.wizard.WizardContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JRadioButton;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("LoggingProfileStep")
class LoggingProfileStepTest {

    @Test
    @DisplayName("defaults to the standard preset and collects it on exit")
    void defaultPreset() {
        LoggingProfileStep step = new LoggingProfileStep();
        assertEquals("standard", step.getPreset());
        WizardContext ctx = new WizardContext();
        step.onExit(ctx);
        assertEquals("standard", ctx.getLoggingPreset());
    }

    @Test
    @DisplayName("custom preset selection is collected on exit")
    void customPreset() {
        LoggingProfileStep step = new LoggingProfileStep();
        // click the "Custom preset" radio → combo becomes enabled
        for (java.awt.Component c : step.getPanel().getComponents()) {
            if (c instanceof JRadioButton rb && "Custom preset".equals(rb.getText())) {
                rb.doClick();
                break;
            }
        }
        WizardContext ctx = new WizardContext();
        step.onExit(ctx);
        // the combo was preselected to "verbose"
        assertEquals("verbose", ctx.getLoggingPreset());
    }
}