package application.utils.logging;

/**
 * The per-row value state of a logging-profile row, derived relative to the
 * row's three reference baselines (headless — UI concerns such as the state
 * colors live in the GUI layer).
 * <p>
 * The four states are the EXACT model of the node profile configuration's
 * value states ({@code ProfileValueState.State}): every value is compared
 * against the saved AND the applied baseline, a 2×2 matrix over
 * (current = saved, current = applied). The logging profile editor therefore
 * behaves logically exactly like the node profile configuration editor —
 * the same four states, the same state derivation and the same state
 * coloring.
 * <p>
 * The three baselines (see {@link RowStateResolver}):
 * </p>
 * <ul>
 *   <li><b>saved</b> — the selected profile's on-disk content (a missing key
 *       falls back to the built-in default);</li>
 *   <li><b>applied</b> — the value the runtime is currently using (the
 *       applied snapshot; a missing key falls back to the built-in default);</li>
 *   <li><b>editor</b> — the value currently in the row's editor.</li>
 * </ul>
 *
 * @see RowStateResolver
 */
public enum RowState {

    /** The editor value differs from BOTH the saved content and the applied baseline: a dirty, not yet saved edit. */
    UNSAVED,

    /** The editor value differs from the saved content but matches the applied baseline. */
    UNSAVED_APPLIED,

    /** The editor value matches the saved content but not the applied baseline: saved, not yet activated. */
    SAVED,

    /** The value matches both the saved content and the applied baseline: what the runtime currently uses. */
    SAVED_APPLIED
}
