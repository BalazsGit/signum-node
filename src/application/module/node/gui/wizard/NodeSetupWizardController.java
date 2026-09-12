package application.module.node.gui.wizard;

import application.module.node.gui.wizard.steps.DatabaseConnectionStep;
import application.module.node.gui.wizard.steps.DatabaseInstallationStep;
import application.module.node.gui.wizard.steps.DatabaseSelectionStep;
import application.module.node.gui.wizard.steps.LoggingProfileStep;
import application.module.node.gui.wizard.steps.NodeConfigurationStep;
import application.module.node.gui.wizard.steps.SummaryStep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * State machine of the node setup wizard (plan §1.6).
 * <p>
 * Headless-safe: pure navigation + validation orchestration, no dialog logic.
 * {@link #next()} validates the current step, collects its state into the shared
 * {@link WizardContext}, then advances (auto-skipping steps whose {@code autoSkip}
 * matches the context, e.g. DB installation for SQLite). {@link #back()} goes back
 * without collecting (the step re-collects when leaving again). {@link #finish()}
 * validates + collects the last step.
 * </p>
 * <p>
 * The step list is injectable so unit tests can drive the state machine with fake
 * steps or sandbox-bound real steps.
 * </p>
 */
public class NodeSetupWizardController {

    private final List<WizardStep> steps;
    private final WizardContext context = new WizardContext();
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
    }

    /** The default step chain (SSOT of the wizard flow). */
    public static List<WizardStep> defaultSteps() {
        return Arrays.asList(
                new DatabaseSelectionStep(),
                new DatabaseInstallationStep(),
                new DatabaseConnectionStep(),
                new NodeConfigurationStep(),
                new LoggingProfileStep(),
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
        return currentIndex == steps.size() - 1;
    }

    /**
     * Validates the current step and advances (auto-skipping where applicable).
     *
     * @return the error message when the current step is invalid (no advance), otherwise null
     */
    public String next() {
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
     * Goes back one step (no-op on the first step).
     *
     * @return always null (back navigation is never blocked)
     */
    public String back() {
        if (currentIndex == 0) {
            return null;
        }
        currentIndex--;
        steps.get(currentIndex).onEnter(context);
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