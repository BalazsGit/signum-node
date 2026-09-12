package application.module.node.gui.wizard;

import javax.swing.JComponent;

/**
 * A single step of the node setup wizard.
 * <p>
 * Contract (see plan §1.6): each step owns its panel and validates against the shared
 * {@link WizardContext}. {@code validate} returns {@code null} when the step is acceptable,
 * otherwise a user-facing error message. {@code onExit} collects the step's UI state into
 * the context (called when navigating away or finishing). {@code onEnter} is called when
 * the step becomes visible (e.g. the summary re-renders). {@code autoSkip} lets a step
 * opt out of the navigation flow for the current context (e.g. DB installation for SQLite,
 * which is file-based and needs no server setup).
 * </p>
 */
public interface WizardStep {

    /** Stable step identifier (e.g. {@code "database-selection"}). */
    String getId();

    /** Human-readable step title shown in the wizard indicator. */
    String getTitle();

    /** The step's UI panel (shown by the dialog's CardLayout). */
    JComponent getPanel();

    /**
     * Validates the step's current state.
     *
     * @return {@code null} if valid, otherwise the error message to show
     */
    String validate(WizardContext context);

    /** Collects this step's UI state into the context (on navigate-away / finish). */
    default void onExit(WizardContext context) {
    }

    /** Called when the step becomes visible. */
    default void onEnter(WizardContext context) {
    }

    /** @return true when the controller should skip this step for the given context. */
    default boolean autoSkip(WizardContext context) {
        return false;
    }
}