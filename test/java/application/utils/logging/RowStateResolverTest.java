package application.utils.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link RowStateResolver} (the headless, pure derivation of a
 * logging-profile row's {@link RowState} from its three reference values —
 * the SAME four-state 2×2 matrix the node profile configuration's value
 * state resolver uses).
 */
@DisplayName("RowStateResolver Tests")
class RowStateResolverTest {

    // ------------------------ The four-state matrix ------------------------

    @Nested
    @DisplayName("State derivation (the shared 2x2 matrix over saved/applied)")
    class DerivationTests {

        @Test
        @DisplayName("editor == saved == applied → SAVED_APPLIED")
        void savedAppliedWhenAllThreeMatch() {
            assertEquals(RowState.SAVED_APPLIED, RowStateResolver.resolve("INFO", "INFO", "INFO"));
        }

        @Test
        @DisplayName("editor != saved ∧ editor != applied → UNSAVED")
        void unsavedWhenEditorDiffersFromBothBaselines() {
            assertEquals(RowState.UNSAVED, RowStateResolver.resolve("FINE", "INFO", "INFO"));
            assertEquals(RowState.UNSAVED, RowStateResolver.resolve("FINE", "INFO", "CONFIG"));
        }

        @Test
        @DisplayName("editor != saved ∧ editor == applied → UNSAVED_APPLIED (a dirty edit the runtime runs with)")
        void unsavedAppliedWhenEditorDiffersFromSavedButMatchesApplied() {
            assertEquals(RowState.UNSAVED_APPLIED, RowStateResolver.resolve("FINE", "INFO", "FINE"));
            assertEquals(RowState.UNSAVED_APPLIED, RowStateResolver.resolve("FINE", "INFO", "FINE", "FINE"),
                    "matching applied takes precedence over matching init");
        }

        @Test
        @DisplayName("editor == init != saved and applied → UNSAVED_INIT")
        void unsavedInitWhenEditorMatchesInitializationOnly() {
            assertEquals(RowState.UNSAVED_INIT, RowStateResolver.resolve("FINE", "CONFIG", "INFO", "FINE"));
            assertEquals(RowState.UNSAVED_INIT, RowStateResolver.resolve("FINE", "CONFIG", "INFO", "FINE "));
            assertEquals(RowState.UNSAVED, RowStateResolver.resolve("FINE", "CONFIG", "INFO", "OTHER"));
            assertEquals(RowState.UNSAVED_APPLIED, RowStateResolver.resolve("FINE", "CONFIG", "FINE", "FINE"));
            assertEquals(RowState.UNSAVED, RowStateResolver.resolve("FINE", "CONFIG", "INFO"));
        }

        @Test
        @DisplayName("editor == saved ∧ editor != applied → SAVED (saved, not yet activated)")
        void savedWhenEditorMatchesSavedButNotApplied() {
            assertEquals(RowState.SAVED, RowStateResolver.resolve("FINE", "FINE", "INFO"));
        }

        @Test
        @DisplayName("editor == saved == init != applied → SAVED_INIT")
        void savedInitWhenValuesStillMatchInitialization() {
            assertEquals(RowState.SAVED_INIT, RowStateResolver.resolve("FINE", "FINE", "INFO", "FINE"));
            assertEquals(RowState.SAVED, RowStateResolver.resolve("FINE", "FINE", "INFO", "CONFIG"));
            assertEquals(RowState.SAVED_APPLIED, RowStateResolver.resolve("FINE", "FINE", "FINE", "INFO"));
            assertEquals(RowState.SAVED, RowStateResolver.resolve("FINE", "FINE", "INFO"));
        }

        @Test
        @DisplayName("the comparison is normalized (trimmed): stray surrounding whitespace is not an edit")
        void comparisonIsTrimmed() {
            assertEquals(RowState.SAVED_APPLIED, RowStateResolver.resolve("INFO ", "INFO", "INFO"));
            assertEquals(RowState.SAVED_APPLIED, RowStateResolver.resolve("INFO", "INFO ", "INFO"));
            assertEquals(RowState.UNSAVED, RowStateResolver.resolve("FINE ", "INFO", "INFO"));
        }
    }

    // ------------------------ Null / empty normalization ------------------------

    @Nested
    @DisplayName("Null and empty value normalization")
    class NormalizationTests {

        @Test
        @DisplayName("normalize: null → empty string, other values are trimmed")
        void normalizeNullToEmpty() {
            assertEquals("", RowStateResolver.normalize(null));
            assertEquals("INFO", RowStateResolver.normalize("INFO"));
            assertEquals("", RowStateResolver.normalize(""));
            assertEquals("INFO", RowStateResolver.normalize("  INFO  "));
        }

        @Test
        @DisplayName("all-null values read as equal → SAVED_APPLIED")
        void allNullIsSavedApplied() {
            assertEquals(RowState.SAVED_APPLIED, RowStateResolver.resolve(null, null, null));
        }

        @Test
        @DisplayName("null and empty are the same state")
        void nullEqualsEmpty() {
            assertEquals(RowState.SAVED_APPLIED, RowStateResolver.resolve(null, "", ""));
            assertEquals(RowState.SAVED_APPLIED, RowStateResolver.resolve("", null, null));
        }

        @Test
        @DisplayName("a null editor value against a non-null saved baseline is dirty (UNSAVED_APPLIED: it matches the empty applied baseline)")
        void nullEditorAgainstSavedIsUnsavedApplied() {
            assertEquals(RowState.UNSAVED_APPLIED, RowStateResolver.resolve(null, "INFO", null));
        }

        @Test
        @DisplayName("an empty editor value against a non-empty saved baseline is UNSAVED")
        void emptyEditorAgainstSavedIsUnsaved() {
            assertEquals(RowState.UNSAVED, RowStateResolver.resolve("", "INFO", "INFO"));
        }
    }
}
