package application.module.node.gui.wizard;

import application.module.node.gui.wizard.steps.DatabaseConnectionStep;
import application.module.node.gui.wizard.steps.DatabaseInstallationStep;
import application.module.node.gui.wizard.steps.DatabaseSelectionStep;
import application.module.node.gui.wizard.steps.NodeConfigurationStep;
import application.module.node.gui.wizard.steps.SummaryStep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * State machine of the node setup wizard (plan §1.6).
 * <p>
 * Headless-safe: pure navigation + validation orchestration, no dialog logic.
 * {@link #next()} validates the current step, collects its state into the shared
 * {@link WizardContext}, then advances (auto-skipping steps whose {@code autoSkip}
 * matches the context, e.g. DB installation for SQLite). {@link #back()} goes back
 * over the same visible (non-auto-skipped) sequence without collecting (the step
 * re-collects when leaving again). {@link #finish()} validates + collects the last
 * step.
 * </p>
 * <p>
 * The step list is injectable so unit tests can drive the state machine with fake
 * steps or sandbox-bound real steps.
 * </p>
 */
public class NodeSetupWizardController {

    private final List<WizardStep> steps;
    private final WizardContext context = new WizardContext();
    private final List<Consumer<WizardContext>> changeListeners = new ArrayList<>();
    private int currentIndex = 0;

    /** The canonical wizard chain (3 required + 3 skippable, plan §1.2). */
    public NodeSetupWizardController() {
        this(defaultSteps());
    }

    /** @param steps ordered step list (must not be empty). */
    public NodeSetupWizardController(List<WizardStep> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("Wizard steps must not be null/empty");
        }
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        // Live context wiring: steps with a choice that alters the flow (the
        // database-engine selection) mirror it into the shared context as soon as
        // it changes and notify this controller, so context-dependent state (the
        // "Step X of Y" count, the skip logic) adapts dynamically.
        for (WizardStep step : this.steps) {
            step.bindContext(context);
            step.addChangeListener(this::fireChangeListeners);
        }
    }

    /** The default step chain (SSOT of the wizard flow). */
    public static List<WizardStep> defaultSteps() {
        return Arrays.asList(
                new DatabaseSelectionStep(),
                new DatabaseInstallationStep(),
                new DatabaseConnectionStep(),
                new NodeConfigurationStep(),
                new SummaryStep());
    }

    public WizardContext getContext() {
        return context;
    }

    public List<WizardStep> getSteps() {
        return steps;
    }

    public WizardStep currentStep() {
        return steps.get(currentIndex);
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public boolean isFirstStep() {
        return currentIndex == 0;
    }

    public boolean isLastStep() {
        List<WizardStep> visible = effectiveSteps();
        return visible.get(visible.size() - 1) == currentStep();
    }

    /**
     * The steps actually visible for the current context: steps whose
     * {@link WizardStep#autoSkip(WizardContext)} matches the context (e.g. the
     * server-only DB installation and connection steps for SQLite) are filtered
     * out. The first step is always kept (the navigation anchor).
     */
    public List<WizardStep> effectiveSteps() {
        List<WizardStep> visible = new ArrayList<>();
        for (WizardStep step : steps) {
            if (visible.isEmpty() || !step.autoSkip(context)) {
                visible.add(step);
            }
        }
        return visible;
    }

    /** @return how many steps the user actually walks through for the current context. */
    public int getVisibleStepCount() {
        return effectiveSteps().size();
    }

    /**
     * @return the 1-based position of the current step within the effective
     *         (visible) sequence — the "Step X" half of the wizard indicator.
     */
    public int getCurrentVisibleIndex() {
        List<WizardStep> visible = effectiveSteps();
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i) == currentStep()) {
                return i + 1;
            }
        }
        return getCurrentIndex() + 1; // defensive fallback; the current step is always visible
    }

    /**
     * Registers a callback fired when a live context change of a step (e.g. the
     * engine selection) may have altered the visible step sequence.
     *
     * @param listener notified with the (mutated) context (may be null)
     */
    public void addChangeListener(Consumer<WizardContext> listener) {
        changeListeners.add(listener);
    }

    private void fireChangeListeners(WizardContext changed) {
        for (Consumer<WizardContext> listener : changeListeners) {
            listener.accept(changed);
        }
    }

    /**
     * Validates the current step and advances (auto-skipping where applicable).
     *
     * @return the error message when the current step is invalid (no advance), otherwise null
     */
    public String next() {
        if (isLastStep()) {
            return null; // nothing to advance to — the dialog completes via finish()
        }
        String error = currentStep().validate(context);
        if (error != null) {
            return error;
        }
        currentStep().onExit(context);
        while (currentIndex < steps.size() - 1) {
            currentIndex++;
            if (currentStep().autoSkip(context)) {
                continue;
            }
            currentStep().onEnter(context);
            break;
        }
        return null;
    }

    /**
     * Goes back over the <b>visible</b> steps of the current context: a step
     * that is auto-skipped today (e.g. the DB connection step after the engine
     * was switched back to SQLite) is not shown when going back, so Back and
     * Next always iterate the same per-context sequence. No-op on the first
     * step.
     *
     * @return always null (back navigation is never blocked)
     */
    public String back() {
        while (currentIndex > 0) {
            currentIndex--;
            if (!currentStep().autoSkip(context)) {
                currentStep().onEnter(context);
                break;
            }
        }
        return null;
    }

    /**
     * Validates + collects the last step, completing the wizard state.
     *
     * @return the error message when invalid, otherwise null (context is complete)
     */
    public String finish() {
        String error = currentStep().validate(context);
        if (error != null) {
            return error;
        }
        currentStep().onExit(context);
        return null;
    }
}