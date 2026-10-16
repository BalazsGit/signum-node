package application.module.node.gui.configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Headless, pure single source of truth for the four value states of a node
 * profile configuration input.
 * <p>
 * The profile editor tracks <b>three values for every input</b>, all identical
 * at initialization:
 * <ul>
 *   <li><b>current</b> — the value the configuration input currently displays
 *       (the editor component; the panel's {@code valueSuppliers}),</li>
 *   <li><b>saved</b> — the value persisted in the profile's .properties file
 *       (the panel's {@code savedProfile} DTO),</li>
 *   <li><b>applied</b> — the value loaded from the profile at init, the baseline
 *       the node runs (or will run) with (the panel's {@code appliedProfile}
 *       DTO snapshot).</li>
 * </ul>
 * The state of a value is the relation between these three, a 2×2 matrix:
 * <pre>
 *   current ≠ saved ∧ current ≠ applied → UNSAVED          (normal color + "*")
 *   current ≠ saved ∧ current = applied → UNSAVED_APPLIED  (gray + "*")
 *   current = saved ∧ current ≠ applied → SAVED            (yellow)
 *   current = saved ∧ current = applied → SAVED_APPLIED    (green)
 * </pre>
 * Every GUI consumer of the state (the row color coding, the trailing asterisk,
 * the editable select's dropdown model and the "Show values" visibility filter)
 * must derive from this resolver so they can never disagree. The class is
 * deliberately Swing-free (pure string logic) so the derivation is unit-testable
 * in isolation — the same pattern as the logging module's {@code RowStateResolver}.
 */
public final class ProfileValueState {

    /**
     * The four value states (see the class javadoc for the matrix).
     */
    public enum State {
        /** current differs from both the saved file and the applied baseline. */
        UNSAVED,
        /** current differs from the saved file but matches the applied baseline. */
        UNSAVED_APPLIED,
        /** current equals the saved file but differs from the applied baseline. */
        SAVED,
        /** current equals both the saved file and the applied baseline. */
        SAVED_APPLIED
    }

    private ProfileValueState() {
    } // prevent instantiation

    /**
     * The normalized equality the whole state model compares with: boolean
     * values (check boxes) compare their parsed booleans, all other values
     * compare as trimmed strings (null reads as the empty string).
     */
    public static boolean equalsValue(boolean isCheckbox, String a, String b) {
        if (isCheckbox) {
            return parseBool(a) == parseBool(b);
        }
        return norm(a).equals(norm(b));
    }

    /**
     * Resolves the value state of a configuration value against the saved
     * (profile-on-disk) and applied (the init-loaded baseline the node runs
     * with) values — the 2×2 matrix of the class javadoc.
     *
     * @param isCheckbox true when the value is a boolean check-box value
     * @param current    the value the input currently displays
     * @param saved      the value stored in the profile's properties file
     * @param applied    the value loaded from the profile at init
     * @return the value state
     */
    public static State resolve(boolean isCheckbox, String current, String saved, String applied) {
        boolean eqSaved = equalsValue(isCheckbox, current, saved);
        boolean eqApplied = equalsValue(isCheckbox, current, applied);
        if (eqSaved && eqApplied) {
            return State.SAVED_APPLIED;
        }
        if (eqSaved) {
            return State.SAVED;
        }
        if (eqApplied) {
            return State.UNSAVED_APPLIED;
        }
        return State.UNSAVED;
    }

    /**
     * The trailing asterisk rule: the {@code *} on the row label is shown in
     * BOTH unsaved states (the value differs from the saved file).
     */
    public static boolean showsStar(State state) {
        return state == State.UNSAVED || state == State.UNSAVED_APPLIED;
    }

    /**
     * The baseline values that must be SELECTABLE in an editable select's
     * dropdown on top of the current value (the current value itself is always
     * part of the model — the dropdown is never empty):
     * <ul>
     *   <li>SAVED — the saved value and the applied value (in SAVED the saved
     *       value is the current value itself, the applied one is the other
     *       baseline the user may want to select back),</li>
     *   <li>UNSAVED_APPLIED — the saved value,</li>
     *   <li>UNSAVED — the saved and the applied value (ONE entry when they
     *       agree — it then carries the applied coloring),</li>
     *   <li>SAVED_APPLIED — none: the dropdown is the value itself.</li>
     * </ul>
     *
     * @return the (already normalized, de-duplicated) baseline entries
     */
    public static List<String> dropdownBaselines(boolean isCheckbox, String current, String saved, String applied) {
        List<String> baselines = new ArrayList<>();
        String s = norm(saved);
        String a = norm(applied);
        switch (resolve(isCheckbox, current, saved, applied)) {
            case SAVED:
                baselines.add(s);
                if (!a.equals(s)) {
                    baselines.add(a);
                }
                break;
            case UNSAVED_APPLIED:
                baselines.add(s);
                break;
            case UNSAVED:
                baselines.add(s);
                if (!a.equals(s)) {
                    baselines.add(a);
                }
                break;
            case SAVED_APPLIED:
            default:
                // the dropdown is the value itself — no extra baselines
                break;
        }
        return baselines;
    }

    /**
     * The display state of an arbitrary dropdown item against the baselines
     * (the select's value coloring). EVERY entry is painted by the state of
     * its OWN value — the state that value would have if it were the row's
     * current value:
     * <ul>
     *   <li>the item equal to the row's CURRENT value displays in the row's own
     *       state color (this is what makes the gray UNSAVED_APPLIED state
     *       visible inside the dropdown — and is exactly what resolving the
     *       item against the baselines yields),</li>
     *   <li>an item equal to the applied value displays as SAVED_APPLIED
     *       (green) ONLY when that value is also saved — an applied-but-not-
     *       saved value is itself in the UNSAVED_APPLIED state (gray),</li>
     *   <li>an item equal to the saved value only displays as saved (yellow),</li>
     *   <li>everything else displays unsaved (normal text color).</li>
     * </ul>
     *
     * @param rowState the state of the row's current value ({@link #resolve})
     *                 (unused beyond documentation — for the entry equal to
     *                 the current value, resolving the item against the
     *                 baselines yields exactly this state)
     */
    public static State itemState(boolean isCheckbox, String item, State rowState,
            String current, String saved, String applied) {
        return resolve(isCheckbox, item, saved, applied);
    }

    /** @return true when the value parses as a boolean "on" state */
    public static boolean parseBool(String s) {
        return "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s)
                || "1".equals(s) || "on".equalsIgnoreCase(s);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }
}

