package application.utils.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link RowStateResolver} (the headless, pure derivation of a
 * logging-profile row's {@link RowState} from its three reference values).
 */
@DisplayName("RowStateResolver Tests")
class RowStateResolverTest {

    // ------------------------ Priority order ------------------------

    @Nested
    @DisplayName("State derivation (priority: UNSAVED > SAVED > APPLIED)")
    class DerivationTests {

        @Test
        @DisplayName("editor == saved == applied → APPLIED")
        void appliedWhenAllThreeMatch() {
            assertEquals(RowState.APPLIED, RowStateResolver.resolve("INFO", "INFO", "INFO"));
        }

        @Test
        @DisplayName("editor != saved → UNSAVED (even when editor == applied)")
        void unsavedWhenEditorDiffersFromSaved() {
            assertEquals(RowState.UNSAVED, RowStateResolver.resolve("FINE", "INFO", "FINE"));
            assertEquals(RowState.UNSAVED, RowStateResolver.resolve("FINE", "INFO", "INFO"));
        }

        @Test
        @DisplayName("editor == saved != applied → SAVED (saved, not yet activated)")
        void savedWhenEditorMatchesSavedButNotApplied() {
            assertEquals(RowState.SAVED, RowStateResolver.resolve("FINE", "FINE", "INFO"));
        }

        @Test
        @DisplayName("saved and applied both differ from the editor → UNSAVED")
        void unsavedWinsOverBothBaselines() {
            assertEquals(RowState.UNSAVED, RowStateResolver.resolve("FINE", "INFO", "CONFIG"));
        }

        @Test
        @DisplayName("the comparison is exact (no trimming): stray whitespace is a real edit")
        void comparisonIsExact() {
            assertEquals(RowState.UNSAVED, RowStateResolver.resolve("INFO ", "INFO", "INFO"));
            assertEquals(RowState.UNSAVED, RowStateResolver.resolve("INFO", "INFO ", "INFO"));
        }
    }

    // ------------------------ Null / empty normalization ------------------------

    @Nested
    @DisplayName("Null and empty value normalization")
    class NormalizationTests {

        @Test
        @DisplayName("normalize: null → empty string, other values pass through")
        void normalizeNullToEmpty() {
            assertEquals("", RowStateResolver.normalize(null));
            assertEquals("INFO", RowStateResolver.normalize("INFO"));
            assertEquals("", RowStateResolver.normalize(""));
        }

        @Test
        @DisplayName("all-null values read as equal → APPLIED")
        void allNullIsApplied() {
            assertEquals(RowState.APPLIED, RowStateResolver.resolve(null, null, null));
        }

        @Test
        @DisplayName("null and empty are the same state")
        void nullEqualsEmpty() {
            assertEquals(RowState.APPLIED, RowStateResolver.resolve(null, "", ""));
            assertEquals(RowState.APPLIED, RowStateResolver.resolve("", null, null));
        }

        @Test
        @DisplayName("a null editor value against a non-null saved baseline is UNSAVED")
        void nullEditorAgainstSavedIsUnsaved() {
            assertEquals(RowState.UNSAVED, RowStateResolver.resolve(null, "INFO", null));
        }

        @Test
        @DisplayName("an empty editor value against a non-empty saved baseline is UNSAVED")
        void emptyEditorAgainstSavedIsUnsaved() {
            assertEquals(RowState.UNSAVED, RowStateResolver.resolve("", "INFO", "INFO"));
        }
    }
}
