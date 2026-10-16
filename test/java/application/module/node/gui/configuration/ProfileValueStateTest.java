package application.module.node.gui.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProfileValueState} (the headless, pure derivation of
 * a profile configuration value's four-value state from its three reference
 * values: current, saved and applied).
 */
@DisplayName("ProfileValueState Tests")
class ProfileValueStateTest {

    // ------------------------ The 2×2 state matrix ------------------------

    @Nested
    @DisplayName("State derivation (2×2 matrix over saved / applied)")
    class DerivationTests {

        @Test
        @DisplayName("current == saved == applied → SAVED_APPLIED")
        void savedAppliedWhenAllThreeMatch() {
            assertEquals(ProfileValueState.State.SAVED_APPLIED,
                    ProfileValueState.resolve(false, "44", "44", "44"));
        }

        @Test
        @DisplayName("current != saved ∧ current == applied → UNSAVED_APPLIED (never 'saved')")
        void unsavedAppliedWhenMatchingOnlyTheAppliedBaseline() {
            assertEquals(ProfileValueState.State.UNSAVED_APPLIED,
                    ProfileValueState.resolve(false, "99", "44", "99"));
        }

        @Test
        @DisplayName("current == saved ∧ current != applied → SAVED (saved, not yet applied)")
        void savedWhenMatchingOnlyTheSavedBaseline() {
            assertEquals(ProfileValueState.State.SAVED,
                    ProfileValueState.resolve(false, "77", "77", "44"));
        }

        @Test
        @DisplayName("current != saved ∧ current != applied → UNSAVED")
        void unsavedWhenMatchingNeitherBaseline() {
            assertEquals(ProfileValueState.State.UNSAVED,
                    ProfileValueState.resolve(false, "55", "44", "77"));
        }

        @Test
        @DisplayName("a dirty edit is never 'saved', even when it matches the applied baseline")
        void dirtyEditIsNeverSaved() {
            assertEquals(ProfileValueState.State.UNSAVED_APPLIED,
                    ProfileValueState.resolve(false, "99", "99-different-file", "99"));
            assertEquals(ProfileValueState.State.UNSAVED,
                    ProfileValueState.resolve(false, "99", "44", "77"));
        }

        @Test
        @DisplayName("non-boolean values compare as trimmed strings (stray whitespace is not an edit)")
        void textComparisonTrims() {
            assertEquals(ProfileValueState.State.SAVED_APPLIED,
                    ProfileValueState.resolve(false, " 44 ", "44", "44 "));
            assertEquals(ProfileValueState.State.UNSAVED,
                    ProfileValueState.resolve(false, "44x", "44", "44"));
        }

        @Test
        @DisplayName("checkbox values compare parsed booleans (any 'on' spelling)")
        void checkboxBooleanComparison() {
            assertEquals(ProfileValueState.State.SAVED_APPLIED,
                    ProfileValueState.resolve(true, "true", "yes", "1"));
            assertEquals(ProfileValueState.State.UNSAVED_APPLIED,
                    ProfileValueState.resolve(true, "off", "on", "false"));
            assertEquals(ProfileValueState.State.UNSAVED,
                    ProfileValueState.resolve(true, "false", "true", "true"));
        }
    }

    // ------------------------ Null / empty normalization ------------------------

    @Nested
    @DisplayName("Null and empty value normalization")
    class NormalizationTests {

        @Test
        @DisplayName("all-null values read as equal → SAVED_APPLIED")
        void allNullIsSavedApplied() {
            assertEquals(ProfileValueState.State.SAVED_APPLIED,
                    ProfileValueState.resolve(false, null, null, null));
        }

        @Test
        @DisplayName("null and empty are the same value")
        void nullEqualsEmpty() {
            assertEquals(ProfileValueState.State.SAVED_APPLIED,
                    ProfileValueState.resolve(false, null, "", ""));
            assertEquals(ProfileValueState.State.UNSAVED_APPLIED,
                    ProfileValueState.resolve(false, null, "44", ""));
        }

        @Test
        @DisplayName("an empty editor value against a non-empty baseline is unsaved")
        void emptyAgainstBaselineIsUnsaved() {
            assertEquals(ProfileValueState.State.UNSAVED,
                    ProfileValueState.resolve(false, "", "44", "77"));
            assertEquals(ProfileValueState.State.UNSAVED_APPLIED,
                    ProfileValueState.resolve(false, "", "44", ""));
        }
    }

    // ------------------------ equalsValue ------------------------

    @Nested
    @DisplayName("Value equality (equalsValue)")
    class EqualsValueTests {

        @Test
        @DisplayName("text values compare trimmed, null reads as empty")
        void textEquals() {
            assertTrue(ProfileValueState.equalsValue(false, " a ", "a"));
            assertTrue(ProfileValueState.equalsValue(false, null, ""));
            assertFalse(ProfileValueState.equalsValue(false, "a", "b"));
        }

        @Test
        @DisplayName("boolean values compare their parsed state")
        void booleanEquals() {
            assertTrue(ProfileValueState.equalsValue(true, "on", "true"));
            assertTrue(ProfileValueState.equalsValue(true, "false", "off"));
            assertFalse(ProfileValueState.equalsValue(true, "on", "off"));
        }
    }

    // ------------------------ The star rule ------------------------

    @Nested
    @DisplayName("Trailing asterisk rule (showsStar)")
    class ShowsStarTests {

        @Test
        @DisplayName("the star is shown in BOTH unsaved states (differs from the saved file)")
        void starInBothUnsavedStates() {
            assertTrue(ProfileValueState.showsStar(ProfileValueState.State.UNSAVED));
            assertTrue(ProfileValueState.showsStar(ProfileValueState.State.UNSAVED_APPLIED));
            assertFalse(ProfileValueState.showsStar(ProfileValueState.State.SAVED));
            assertFalse(ProfileValueState.showsStar(ProfileValueState.State.SAVED_APPLIED));
        }
    }

    // ------------------------ The dropdown baselines ------------------------

    @Nested
    @DisplayName("Editable-select dropdown baselines (dropdownBaselines)")
    class DropdownBaselinesTests {

        @Test
        @DisplayName("SAVED: the saved and the applied value are selectable")
        void savedStateOffersBothBaselines() {
            assertEquals(List.of("77", "44"),
                    ProfileValueState.dropdownBaselines(false, "77", "77", "44"));
        }

        @Test
        @DisplayName("UNSAVED_APPLIED: the saved value is selectable")
        void unsavedAppliedStateOffersTheSavedValue() {
            assertEquals(List.of("44"),
                    ProfileValueState.dropdownBaselines(false, "99", "44", "99"));
        }

        @Test
        @DisplayName("UNSAVED: the saved and the applied value are selectable")
        void unsavedStateOffersBothBaselines() {
            assertEquals(List.of("44", "77"),
                    ProfileValueState.dropdownBaselines(false, "55", "44", "77"));
        }

        @Test
        @DisplayName("UNSAVED: when the baselines agree it is ONE entry")
        void unsavedStateDeduplicatesAgreeingBaselines() {
            assertEquals(List.of("44"),
                    ProfileValueState.dropdownBaselines(false, "55", "44", "44"));
        }

        @Test
        @DisplayName("SAVED_APPLIED: no extra baselines (the dropdown is the value itself)")
        void savedAppliedStateOffersNone() {
            assertTrue(ProfileValueState.dropdownBaselines(false, "44", "44", "44").isEmpty());
        }
    }

    // ------------------------ The per-item dropdown coloring ------------------------

    @Nested
    @DisplayName("Dropdown item display state (itemState)")
    class ItemStateTests {

        @Test
        @DisplayName("the item equal to the current value displays the row's own state (gray visible)")
        void currentItemDisplaysTheRowState() {
            assertEquals(ProfileValueState.State.UNSAVED_APPLIED,
                    ProfileValueState.itemState(false, "99",
                            ProfileValueState.State.UNSAVED_APPLIED, "99", "44", "99"));
            assertEquals(ProfileValueState.State.SAVED_APPLIED,
                    ProfileValueState.itemState(false, "44",
                            ProfileValueState.State.SAVED_APPLIED, "44", "44", "44"));
        }

        @Test
        @DisplayName("an applied value that is NOT saved displays unsaved-applied (gray), not green")
        void appliedOnlyItemDisplaysUnsavedApplied() {
            // the row's current value is the saved one (a SAVED row) and the
            // applied baseline differs: the applied entry of the dropdown is
            // an applied-but-not-saved value — its OWN state is
            // UNSAVED_APPLIED, so it must be gray, not applied-green.
            assertEquals(ProfileValueState.State.UNSAVED_APPLIED,
                    ProfileValueState.itemState(false, "99",
                            ProfileValueState.State.SAVED, "77", "77", "99"));
            // the same for an unsaved current value: the applied entry is
            // still an applied-but-not-saved value (gray)
            assertEquals(ProfileValueState.State.UNSAVED_APPLIED,
                    ProfileValueState.itemState(false, "99",
                            ProfileValueState.State.UNSAVED, "55", "77", "99"));
        }

        @Test
        @DisplayName("a value present in BOTH baselines displays applied (green)")
        void savedAndAppliedItemDisplaysApplied() {
            assertEquals(ProfileValueState.State.SAVED_APPLIED,
                    ProfileValueState.itemState(false, "99",
                            ProfileValueState.State.UNSAVED, "55", "99", "99"));
        }

        @Test
        @DisplayName("the saved-only value displays saved (yellow)")
        void savedOnlyItemDisplaysSaved() {
            assertEquals(ProfileValueState.State.SAVED,
                    ProfileValueState.itemState(false, "44",
                            ProfileValueState.State.UNSAVED, "55", "44", "77"));
        }

        @Test
        @DisplayName("everything else displays unsaved (normal)")
        void otherItemsDisplayUnsaved() {
            assertEquals(ProfileValueState.State.UNSAVED,
                    ProfileValueState.itemState(false, "1234",
                            ProfileValueState.State.UNSAVED, "55", "44", "77"));
        }
    }
}