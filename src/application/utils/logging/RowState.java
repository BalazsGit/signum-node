package application.utils.logging;

/**
 * The per-row value state of a logging-profile row, derived relative to the
 * row's reference baselines (headless — UI concerns such as the state
 * colors live in the GUI layer).
 * <p>
 * The four shared states are the EXACT model of the node profile
 * configuration's value states ({@code ProfileValueState.State}). Logging
 * profiles additionally use {@link #SAVED_INIT} and {@link #UNSAVED_INIT} to
 * distinguish values that match the initialization snapshot.
 * <p>
 * The references (see {@link RowStateResolver}):
 * </p>
 * <ul>
 *   <li><b>saved</b> — the selected profile's on-disk content (a missing key
 *       falls back to the built-in default);</li>
 *   <li><b>applied</b> — the comparison baseline; in the module logging editor
 *       this is a dummy state and does not describe running nodes;</li>
 *   <li><b>init</b> — the value captured from the profile when its logging
 *       editor initializes;</li>
 *   <li><b>editor</b> — the value currently in the row's editor.</li>
 * </ul>
 *
 * @see RowStateResolver
 */
public enum RowState {

    /** The editor value differs from BOTH the saved content and applied comparison baseline: a dirty edit. */
    UNSAVED,

    /** The editor value differs from saved but matches the applied comparison baseline. */
    UNSAVED_APPLIED,

    /** The editor value differs from saved and applied but matches the init snapshot. */
    UNSAVED_INIT,

    /** The editor value matches saved, but differs from init and the applied comparison baseline. */
    SAVED,

    /** The editor value matches its saved and module-initialized values, but not the applied comparison baseline. */
    SAVED_INIT,

    /** The value matches both saved content and the applied comparison baseline. */
    SAVED_APPLIED
}
