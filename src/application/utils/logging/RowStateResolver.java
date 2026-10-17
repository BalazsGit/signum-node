package application.utils.logging;

/**
 * Pure, headless derivation of a logging-profile row's {@link RowState}.
 * <p>
 * The three-argument derivation is the same 2×2 matrix the node profile
 * configuration editor uses ({@code ProfileValueState}). The logging profile
 * editor can additionally compare against its immutable initialization
 * snapshot:
 * </p>
 * <pre>
 *   editor != saved ∧ editor != applied → UNSAVED          (dirty, not yet saved)
 *   editor != saved ∧ editor = applied  → UNSAVED_APPLIED  (dirty, matches applied)
 *   editor = saved ∧ editor != applied ∧ editor != init → SAVED
 *   editor = saved ∧ editor = applied   → SAVED_APPLIED    (saved and matches applied)
 *   editor = saved = init ∧ editor != applied → SAVED_INIT (unchanged since initialization)
 * </pre>
 * <p>
 * The normalization is the one the whole state model compares with
 * (the same rules as the configuration editor's resolver): a null value
 * reads as the empty string, so "no value" and "empty value" are the same
 * state, and values are compared after trimming — stray surrounding
 * whitespace is a formatting artifact, not an edit.
 * </p>
 * <p>
 * The resolver is deliberately dumb about WHERE the baselines come from:
 * the caller resolves saved, applied, and (for logging profiles) init
 * values, then passes them with the editor value.
 * </p>
 *
 * @see RowState
 */
public final class RowStateResolver {

    private RowStateResolver() {
        // utility class
    }

    /**
     * Derives the shared row state from the current, saved, and applied
     * values. Kept for callers such as the node configuration panel that use
     * the original four-state model.
     *
     * @param editorValue  the value currently in the row's editor (may be null)
     * @param savedValue   the selected profile's saved (on-disk) value for the row (may be null)
     * @param appliedValue the applied reference value for the row (may be null)
     * @return the row's derived {@link RowState}
     */
    public static RowState resolve(String editorValue, String savedValue, String appliedValue) {
        return resolve(editorValue, savedValue, appliedValue, null, false);
    }

    /**
     * Derives a logging profile row state with the profile editor's
     * initialization snapshot. The additional state applies only when the editor is saved,
     * differs from the applied comparison baseline, and still matches init.
     *
     * @param editorValue  value currently shown in the editor
     * @param savedValue   value currently stored in the selected profile
     * @param appliedValue applied reference (comparison-only or host baseline)
     * @param initValue    value captured when the profile editor initialized
     * @return derived row state
     */
    public static RowState resolve(String editorValue, String savedValue, String appliedValue, String initValue) {
        return resolve(editorValue, savedValue, appliedValue, initValue, true);
    }

    private static RowState resolve(String editorValue, String savedValue, String appliedValue,
            String initValue, boolean compareInit) {
        String editor = normalize(editorValue);
        String saved = normalize(savedValue);
        String applied = normalize(appliedValue);
        boolean eqSaved = editor.equals(saved);
        boolean eqApplied = editor.equals(applied);
        if (eqSaved && eqApplied) {
            return RowState.SAVED_APPLIED;
        }
        if (eqSaved) {
            if (compareInit && !eqApplied && editor.equals(normalize(initValue))) {
                return RowState.SAVED_INIT;
            }
            return RowState.SAVED;
        }
        if (eqApplied) {
            return RowState.UNSAVED_APPLIED;
        }
        return RowState.UNSAVED;
    }

    /**
     * Normalizes a row value for comparison (the same normalization the
     * configuration editor's state resolver uses): a null value reads as the
     * empty string (the two are the same state — "no value") and surrounding
     * whitespace is trimmed.
     *
     * @param value the value to normalize (may be null)
     * @return the normalized value (never null)
     */
    public static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
