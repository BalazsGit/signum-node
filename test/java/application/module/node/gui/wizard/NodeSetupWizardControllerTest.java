package application.module.node.gui.wizard;

import application.module.node.gui.wizard.steps.DatabaseInstallationStep;
import application.module.node.gui.wizard.steps.DatabaseSelectionStep;
import application.module.node.gui.wizard.steps.LoggingProfileStep;
import application.module.node.gui.wizard.steps.NodeConfigurationStep;
import application.module.node.gui.wizard.steps.SummaryStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NodeSetupWizardController")
class NodeSetupWizardControllerTest {

    /** Minimal fake step for state-machine tests. */
    private static class FakeStep implements WizardStep {
        final String id;
        final String error;
        final boolean skip;
        final WizardContext touched;
        boolean exited;
        boolean entered;

        FakeStep(String id, String error, boolean skip, WizardContext touched) {
            this.id = id;
            this.error = error;
            this.skip = skip;
            this.touched = touched;
        }

        @Override public String getId() { return id; }
        @Override public String getTitle() { return id; }
        @Override public JComponent getPanel() { return null; }
        @Override public String validate(WizardContext context) { return error; }
        @Override public void onExit(WizardContext context) { exited = true; touched.setName("by-" + id); }
        @Override public void onEnter(WizardContext context) { entered = true; }
        @Override public boolean autoSkip(WizardContext context) { return skip; }
    }

    @Test
    @DisplayName("default wizard chain has the canonical steps (incl. install + connection)")
    void defaultStepChain() {
        List<WizardStep> steps = NodeSetupWizardController.defaultSteps();
        assertEquals(6, steps.size());
        assertEquals("database-selection", steps.get(0).getId());
        assertEquals("database-installation", steps.get(1).getId());
        assertEquals("database-connection", steps.get(2).getId());
        assertEquals("node-configuration", steps.get(3).getId());
        assertEquals("logging-profile", steps.get(4).getId());
        assertEquals("summary", steps.get(5).getId());
    }

    @Test
    @DisplayName("constructor rejects an empty step list")
    void emptyStepsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NodeSetupWizardController(List.of()));
    }

    @Test
    @DisplayName("next() blocks when the current step is invalid")
    void nextBlockedByValidation() {
        WizardContext ctx = new WizardContext();
        FakeStep bad = new FakeStep("bad", "invalid!", false, ctx);
        FakeStep ok = new FakeStep("ok", null, false, ctx);
        NodeSetupWizardController c = new NodeSetupWizardController(Arrays.asList(bad, ok));
        assertEquals("invalid!", c.next());
        assertTrue(c.isFirstStep());
        assertFalse(bad.exited);
    }

    @Test
    @DisplayName("next() collects state and advances; auto-skip steps are skipped")
    void nextCollectsAndAutoSkips() {
        WizardContext ctx = new WizardContext();
        FakeStep a = new FakeStep("a", null, false, ctx);
        FakeStep skipped = new FakeStep("skipped", null, true, ctx);
        FakeStep b = new FakeStep("b", null, false, ctx);
        NodeSetupWizardController c = new NodeSetupWizardController(Arrays.asList(a, skipped, b));
        assertNull(c.next());
        assertEquals("by-a", ctx.getName());
        assertTrue(a.exited);
        assertFalse(skipped.exited);
        assertFalse(skipped.entered);
        assertTrue(b.entered);
        assertEquals("b", c.currentStep().getId());
    }

    @Test
    @DisplayName("back() returns without collecting; next re-collects")
    void backAndForward() {
        WizardContext ctx = new WizardContext();
        FakeStep a = new FakeStep("a", null, false, ctx);
        FakeStep b = new FakeStep("b", null, false, ctx);
        NodeSetupWizardController c = new NodeSetupWizardController(Arrays.asList(a, b));
        assertNull(c.next());
        assertNull(c.back());
        assertEquals("a", c.currentStep().getId());
        assertNull(c.next());
        assertEquals("b", c.currentStep().getId());
    }

    @Test
    @DisplayName("finish() validates + collects the last step")
    void finish() {
        WizardContext ctx = new WizardContext();
        FakeStep a = new FakeStep("a", null, false, ctx);
        FakeStep b = new FakeStep("b", null, false, ctx);
        NodeSetupWizardController c = new NodeSetupWizardController(Arrays.asList(a, b));
        c.next();
        assertNull(c.finish());
        assertEquals("by-b", ctx.getName());
        assertTrue(b.exited);
    }

    @Test
    @DisplayName("full wizard flow (SQLite): DB install auto-skipped, suggested name resolved")
    void fullFlowSqlite() {
        NodeConfigurationStep nodeStep = new NodeConfigurationStep(() -> new HashSet<>(List.of("mainnet", "mainnet_01")));
        NodeSetupWizardController c = new NodeSetupWizardController(Arrays.asList(
                new DatabaseSelectionStep(),
                new DatabaseInstallationStep(),
                new application.module.node.gui.wizard.steps.DatabaseConnectionStep(),
                nodeStep,
                new LoggingProfileStep(),
                new SummaryStep()));
        WizardContext ctx = c.getContext();

        // step 0: engine selection (SQLite default) — DB install auto-skipped (step 1)
        assertNull(c.next());
        assertEquals("node-configuration", c.currentStep().getId());
        assertEquals(application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine.SQLITE,
                ctx.getEngine());

        // step 2: node config — suggested name avoids the taken names
        assertNull(c.next());
        assertEquals("logging-profile", c.currentStep().getId());
        assertEquals("mainnet_02", ctx.getName());

        // step 3: logging — default preset
        assertNull(c.next());
        assertEquals("summary", c.currentStep().getId());
        assertEquals("standard", ctx.getLoggingPreset());

        // step 4: finish
        assertNull(c.finish());
        assertTrue(ctx.isStartImmediately());
        assertNotNull(ctx.getName());
    }
}