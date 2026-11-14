package application.module.node.gui.wizard;

import application.module.node.gui.wizard.steps.DatabaseConnectionStep;
import application.module.node.gui.wizard.steps.DatabaseInstallationStep;
import application.module.node.gui.wizard.steps.DatabaseSelectionStep;
import application.module.node.gui.wizard.steps.LoggingSelectionStep;
import application.module.node.gui.wizard.steps.NodeConfigurationStep;
import application.module.node.gui.wizard.steps.ProfileNameStep;
import application.module.node.gui.wizard.steps.SummaryStep;
import application.module.node.profile.NodeProfileRepository;
import application.module.node.profile.ProfileNameSuggester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
 * The wizard also owns the <b>live profile-name preview</b>: the name step stores
 * the user's custom name (or {@code null} for the suggested one), and the
 * controller re-resolves the suggested name from the final context (network /
 * engine / ports, SSOT: {@link ProfileNameSuggester}) on every context change —
 * the dialog shows the result in its title bar.
 * </p>
 * <p>
 * The step list and the "taken names" source are injectable so unit tests can
 * drive the state machine with fake steps or sandbox-bound real steps.
 * </p>
 */
public class NodeSetupWizardController {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeSetupWizardController.class);

    private final List<WizardStep> steps;
    private final WizardContext context = new WizardContext();
    private final List<Consumer<WizardContext>> changeListeners = new ArrayList<>();
    private final Supplier<Set<String>> takenNames;
    private int currentIndex = 0;

    /** The canonical wizard chain (name + 3 required + 3 skippable, plan §1.2). */
    public NodeSetupWizardController() {
        this(defaultSteps());
    }

    /** @param steps ordered step list (must not be empty). */
    public NodeSetupWizardController(List<WizardStep> steps) {
        this(steps, NodeSetupWizardController::repositoryTakenNames);
    }

    /**
     * @param steps      ordered step list (must not be empty)
     * @param takenNames source of the existing profile names for the suggested-name
     *                   resolution (null → the repository discovery, the production SSOT)
     */
    public NodeSetupWizardController(List<WizardStep> steps, Supplier<Set<String>> takenNames) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("Wizard steps must not be null/empty");
        }
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        this.takenNames = takenNames != null ? takenNames : NodeSetupWizardController::repositoryTakenNames;
        // Live context wiring: steps with a choice that alters the flow (the
        // profile name, the database-engine selection, the network/port options)
        // mirror it into the shared context as soon as it changes and notify this
        // controller, so context-dependent state (the "Step X of Y" count, the
        // skip logic, the suggested-name preview) adapts dynamically.
        for (WizardStep step : this.steps) {
            step.bindContext(context);
            step.addChangeListener(this::fireChangeListeners);
        }
        refreshNamePreview(context);
    }

    /** The default step chain (SSOT of the wizard flow). */
    public static List<WizardStep> defaultSteps() {
        return Arrays.asList(
                new ProfileNameStep(),
                new DatabaseSelectionStep(),
                new DatabaseInstallationStep(),
                new DatabaseConnectionStep(),
                new NodeConfigurationStep(),
                new LoggingSelectionStep(),
                new SummaryStep());
    }

    /** The production "taken names" source (SSOT: the repository discovery). */
    private static Set<String> repositoryTakenNames() {
        return new HashSet<>(NodeProfileRepository.discoverProfileNames());
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
        refreshNamePreview(changed);
        for (Consumer<WizardContext> listener : changeListeners) {
            listener.accept(changed);
        }
    }

    /**
     * Re-resolves the context's profile name: a custom name (step 1) is used
     * as-is; otherwise the suggested name is recomputed from the CURRENT context
     * (network / engine — the ports no longer rename the profile, so back /
     * forth navigation never changes it; SSOT: {@link ProfileNameSuggester}) so
     * the title-bar preview follows the selections of the later steps.
     */
    void refreshNamePreview(WizardContext ctx) {
        if (ctx.getCustomName() != null) {
            // "Custom name" is selected: the name is EXACTLY the user's text — an
            // empty field keeps an empty name (the title bar then shows no name),
            // never a suggested fallback.
            ctx.setName(ctx.getCustomName().trim());
            return;
        }
        try {
            String engineKey = ctx.getEngine() == null ? null : ctx.getEngine().getSettingsKey();
            String base = ProfileNameSuggester.baseName(ctx.getNetwork().getDisplayName(), engineKey, false, 0);
            // The profile still being created must not count as "taken", or the
            // suggested name would increment (_01 → _02 → …) on every re-resolve
            // while the wizard's own scratch profile exists on disk.
            Set<String> taken = new HashSet<>(takenNames.get());
            if (ctx.getName() != null) {
                taken.remove(ctx.getName());
            }
            ctx.setName(ProfileNameSuggester.nextAvailableName(base, taken));
        } catch (RuntimeException e) {
            // The live preview must never break navigation (transient states).
            LOGGER.warn("Could not refresh the suggested profile name", e);
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
        refreshNamePreview(context);
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
        refreshNamePreview(context);
        return null;
    }
}