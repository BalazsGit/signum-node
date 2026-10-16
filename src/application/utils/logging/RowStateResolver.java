package application.utils.logging;

/**
 * Pure, headless derivation of a logging-profile row's {@link RowState}.
 * <p>
 * The derivation is the SAME 2×2 matrix the node profile configuration
 * editor uses ({@code ProfileValueState}): the row's editor value is
 * compared against the saved (on-disk) baseline AND the applied (runtime)
 * baseline:
 * </p>
 * <pre>
 *   editor != saved ∧ editor != applied → UNSAVED          (dirty, not yet saved)
 *   editor != saved ∧ editor = applied  → UNSAVED_APPLIED  (dirty, but matches the runtime)
 *   editor = saved ∧ editor != applied  → SAVED            (saved, not yet activated)
 *   editor = saved ∧ editor = applied   → SAVED_APPLIED    (what the runtime currently uses)
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
 * the caller resolves each baseline (disk content, applied snapshot, editor
 * value) and passes the three values in.
 * </p>
 *
 * @see RowState
 */
public final class RowStateResolver {

    private RowStateResolver() {
        // utility class
    }

    /**
     * Derives the row state from the row's three values — the 2×2 matrix of
     * the class javadoc (the same derivation the node profile
     * configuration's value-state resolver performs).
     *
     * @param editorValue  the value currently in the row's editor (may be null)
     * @param savedValue   the selected profile's saved (on-disk) value for the row (may be null)
     * @param appliedValue the value the runtime is currently using for the row (may be null)
     * @return the row's derived {@link RowState}
     */
    public static RowState resolve(String editorValue, String savedValue, String appliedValue) {
        String editor = normalize(editorValue);
        String saved = normalize(savedValue);
        String applied = normalize(appliedValue);
        boolean eqSaved = editor.equals(saved);
        boolean eqApplied = editor.equals(applied);
        if (eqSaved && eqApplied) {
            return RowState.SAVED_APPLIED;
        }
        if (eqSaved) {
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
