package application.module.node.gui.wizard;

import jiconfont.icons.font_awesome.FontAwesome;

import javax.swing.JComponent;
import java.util.function.Consumer;

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

    /**
     * Proportional scale factor of the wizard's heading/keyword labels — the step
     * titles ("1. …", "2. …", "3. …"), the engine name in the engine-details panel
     * and the details section keywords (Description / Setup required / Best for) —
     * relative to the default UI font size (1.2f = 120%).
     */
    float KEYWORD_FONT_SCALE = 1.2f;

    /** Stable step identifier (e.g. {@code "database-selection"}). */
    String getId();

    /** Human-readable step title shown in the wizard indicator. */
    String getTitle();

    /**
     * The icon (Font Awesome) representing this step's topic, shown in the wizard's
     * top-left header (e.g. the database icon for the database steps). The dialog
     * renders it at its header icon size.
     *
     * @return the step's header icon (never null by default)
     */
    default FontAwesome getHeaderIcon() {
        return FontAwesome.INFO_CIRCLE;
    }

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

    /**
     * Called by the controller once the shared {@link WizardContext} exists.
     * <p>
     * A step that exposes a live choice altering the navigation flow (e.g. the
     * database-engine selection, which auto-skips the server-only installation
     * and connection steps) can mirror that choice into the context
     * <b>immediately</b> — not only at {@link #onExit} — so the wizard can
     * adapt its visible step sequence dynamically.
     * </p>
     */
    default void bindContext(WizardContext context) {
    }

    /**
     * Registers a callback fired when a live UI change of this step alters the
     * navigation context (e.g. the engine radio was switched). The controller
     * uses it to refresh context-dependent state such as the "Step X of Y"
     * indicator.
     *
     * @param listener notified with the (mutated) context (may be null)
     */
    default void addChangeListener(Consumer<WizardContext> listener) {
    }
}