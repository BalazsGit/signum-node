package application.module.node.gui.wizard;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.node.gui.wizard.steps.DatabaseConnectionStep;
import application.module.node.gui.wizard.steps.DatabaseInstallationStep;
import application.module.node.gui.wizard.steps.DatabaseSelectionStep;
import application.module.node.gui.wizard.steps.NodeConfigurationStep;
import application.module.node.gui.wizard.steps.SummaryStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
        assertEquals(5, steps.size());
        assertEquals("database-selection", steps.get(0).getId());
        assertEquals("database-installation", steps.get(1).getId());
        assertEquals("database-connection", steps.get(2).getId());
        assertEquals("node-configuration", steps.get(3).getId());
        assertEquals("summary", steps.get(4).getId());
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
    @DisplayName("full wizard flow (SQLite): 3 steps — selection, DB configuration, node configuration")
    void fullFlowSqlite() {
        NodeConfigurationStep nodeStep = new NodeConfigurationStep(() -> new HashSet<>(List.of("mainnet", "mainnet_01")));
        NodeSetupWizardController c = new NodeSetupWizardController(Arrays.asList(
                new DatabaseSelectionStep(),
                new DatabaseInstallationStep(),
                new application.module.node.gui.wizard.steps.DatabaseConnectionStep(),
                nodeStep,
                new SummaryStep()));
        WizardContext ctx = c.getContext();

        // step 1: engine selection (SQLite default) — the DB installation step is auto-skipped
        assertNull(c.next());
        assertEquals("database-connection", c.currentStep().getId(),
                "SQLite walks the DB-configuration step (server engines only add the installation step)");
        assertEquals(application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine.SQLITE,
                ctx.getEngine());

        // step 2: DB configuration — same step as the server engines (file-based → lenient)
        assertNull(c.next());
        assertEquals("node-configuration", c.currentStep().getId());
        assertTrue(c.isLastStep(), "SQLite: the node-configuration step is the LAST step (no summary)");

        // step 3: finish — the node configuration collects the suggested name (avoids the taken names)
        assertNull(c.finish());
        assertEquals("mainnet_02", ctx.getName());
        assertTrue(ctx.isStartImmediately());
    }

    /** The canonical chain with sandbox-bound DB steps (no real panels / disk checks). */
    private static List<WizardStep> defaultStepsForTest() {
        return Arrays.asList(
                new DatabaseSelectionStep(),
                new DatabaseInstallationStep(e -> new JPanel(), e -> false),
                new DatabaseConnectionStep(),
                new NodeConfigurationStep(() -> new HashSet<>()),
                new SummaryStep());
    }

    @Test
    @DisplayName("visible step count adapts to the engine (SQLite skips installation + summary)")
    void visibleStepCountAdaptsToEngine() {
        NodeSetupWizardController c = new NodeSetupWizardController(defaultStepsForTest());
        // The context's engine is live-bound from the preselected radio (SQLite)
        // → only 3 steps are actually walked.
        assertEquals(3, c.getVisibleStepCount());
        assertEquals(1, c.getCurrentVisibleIndex());
        assertEquals(Arrays.asList("database-selection", "database-connection", "node-configuration"),
                c.effectiveSteps().stream().map(WizardStep::getId).toList());

        // A server engine walks all five steps.
        c.getContext().setEngine(DatabaseEngine.MARIADB);
        assertEquals(5, c.getVisibleStepCount());
        assertEquals(1, c.getCurrentVisibleIndex());
    }

    @Test
    @DisplayName("the visible index tracks the walked (non-skipped) position")
    void visibleIndexTracksNavigation() {
        NodeSetupWizardController c = new NodeSetupWizardController(defaultStepsForTest());
        assertNull(c.next()); // SQLite: installation + summary auto-skipped
        assertEquals("database-connection", c.currentStep().getId());
        assertEquals(2, c.getCurrentVisibleIndex());
        assertEquals(3, c.getVisibleStepCount());
        assertNull(c.next()); // node configuration — the LAST visible step for SQLite
        assertEquals("node-configuration", c.currentStep().getId());
        assertEquals(3, c.getCurrentVisibleIndex());
        assertTrue(c.isLastStep());
    }

    @Test
    @DisplayName("back() walks the visible steps of the current context (auto-skipped steps are never shown)")
    void backSkipsAutoSkippedSteps() {
        WizardContext ctx = new WizardContext();
        FakeStep a = new FakeStep("a", null, false, ctx);
        FakeStep skipped = new FakeStep("skipped", null, true, ctx);
        FakeStep b = new FakeStep("b", null, false, ctx);
        NodeSetupWizardController c = new NodeSetupWizardController(Arrays.asList(a, skipped, b));
        assertNull(c.next());
        assertEquals("b", c.currentStep().getId());
        assertNull(c.back());
        assertEquals("a", c.currentStep().getId(), "back must skip the auto-skipped step");
        assertFalse(skipped.entered, "the auto-skipped step must never be shown");
        assertNull(c.back(), "back on the first step is a no-op");
        assertEquals("a", c.currentStep().getId());
    }

    @Test
    @DisplayName("switching the engine live updates the context, the visible count and fires listeners")
    void liveEngineChangeRefreshesVisibleCount() {
        NodeSetupWizardController c = new NodeSetupWizardController(defaultStepsForTest());
        AtomicInteger fired = new AtomicInteger();
        c.addChangeListener(ctx -> fired.incrementAndGet());
        assertEquals(3, c.getVisibleStepCount(), "SQLite (preselected) → 3 visible steps");

        DatabaseSelectionStep selection = (DatabaseSelectionStep) c.getSteps().get(0);
        findRadio(selection.getPanel(), "MariaDB").doClick();
        assertEquals(DatabaseEngine.MARIADB, c.getContext().getEngine());
        assertEquals(5, c.getVisibleStepCount(), "server engine → all five steps are visible");
        assertEquals(1, fired.get());

        findRadio(selection.getPanel(), "SQLite").doClick();
        assertEquals(DatabaseEngine.SQLITE, c.getContext().getEngine());
        assertEquals(3, c.getVisibleStepCount(), "back to SQLite → installation + summary drop out again");
        assertEquals(2, fired.get());

        findRadio(selection.getPanel(), "SQLite").doClick(); // same selection → no event
        assertEquals(2, fired.get());
    }

    private static JRadioButton findRadio(JComponent root, String text) {
        List<Component> out = new ArrayList<>();
        collectChildren(root, out);
        for (Component c : out) {
            if (c instanceof JRadioButton rb && text.equals(rb.getText())) {
                return rb;
            }
        }
        throw new IllegalStateException("radio not found: " + text);
    }

    private static void collectChildren(JComponent root, List<Component> out) {
        for (Component c : root.getComponents()) {
            if (c instanceof JComponent j) {
                out.add(j);
                collectChildren(j, out);
            }
        }
    }
}