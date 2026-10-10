package application.utils.logging;

/**
 * Pure, headless derivation of a logging-profile row's {@link RowState}.
 * <p>
 * The derivation compares the row's three values with the priority
 * {@code UNSAVED > SAVED > APPLIED}:
 * </p>
 * <pre>
 *   1) editor != saved    → UNSAVED (a dirty, not yet saved edit)
 *   2) editor != applied  → SAVED   (saved, but not what the runtime uses)
 *   3) otherwise          → APPLIED (the runtime's current value)
 * </pre>
 * <p>
 * The resolver is deliberately dumb about WHERE the baselines come from:
 * the caller resolves each baseline (disk content, applied snapshot, editor
 * value) and passes the three values in. Values are compared after
 * {@link #normalize(String)} — a null value reads as the empty string, so
 * "no value" and "empty value" are the same state. The comparison is
 * otherwise exact (no trimming): a value with stray whitespace is a real
 * edit, not a formatting artifact.
 * </p>
 *
 * @see RowState
 */
public final class RowStateResolver {

    private RowStateResolver() {
        // utility class
    }

    /**
     * Derives the row state from the row's three values.
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
        if (!editor.equals(saved)) {
            return RowState.UNSAVED;
        }
        if (!editor.equals(applied)) {
            return RowState.SAVED;
        }
        return RowState.APPLIED;
    }

    /**
     * Normalizes a row value for comparison: a null value reads as the empty
     * string (the two are the same state — "no value").
     *
     * @param value the value to normalize (may be null)
     * @return the normalized value (never null)
     */
    public static String normalize(String value) {
        return value == null ? "" : value;
    }
}
