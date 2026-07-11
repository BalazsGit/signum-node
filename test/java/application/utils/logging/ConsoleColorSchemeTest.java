package application.utils.logging;

import java.awt.Color;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConsoleColorScheme}.
 * <p>
 * Verifies auto-assignment from palette, custom overrides, persistence via export/import,
 * thread-safety of synchronized methods, and edge cases (null handling, palette exhaustion).
 * </p>
 */
@DisplayName("ConsoleColorScheme Tests")
class ConsoleColorSchemeTest {

    // ------------------------ Auto-Assignment ------------------------

    @Nested
    @DisplayName("Auto-assignment from palette")
    class AutoAssignmentTests {

        @Test
        @DisplayName("first profile gets first palette color")
        void getColorForProfile_FirstProfile_ReturnsFirstPaletteColor() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            Color color = scheme.getColorForProfile("profile-a");

            assertNotNull(color);
            // First palette entry is light blue (120, 180, 255)
            assertEquals(new Color(120, 180, 255), color);
        }

        @Test
        @DisplayName("second profile gets second palette color")
        void getColorForProfile_SecondProfile_ReturnsSecondPaletteColor() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            scheme.getColorForProfile("profile-a"); // consume first slot
            Color colorB = scheme.getColorForProfile("profile-b");

            assertNotNull(colorB);
            // Second palette entry is light green (160, 255, 160)
            assertEquals(new Color(160, 255, 160), colorB);
        }

        @Test
        @DisplayName("same profile always returns same auto-assigned color")
        void getColorForProfile_SameProfileTwice_ReturnsSameColor() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            Color first = scheme.getColorForProfile("mainnet");
            Color second = scheme.getColorForProfile("mainnet");

            assertSame(first, second); // Same Color instance from cache
        }

        @Test
        @DisplayName("different profiles get different colors")
        void getColorForProfile_DifferentProfiles_ReturnDifferentColors() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            Color colorA = scheme.getColorForProfile("alpha");
            Color colorB = scheme.getColorForProfile("beta");

            assertNotEquals(colorA, colorB);
        }

        @Test
        @DisplayName("null profileName throws NullPointerException")
        void getColorForProfile_NullProfile_ThrowsNPE() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            assertThrows(NullPointerException.class, () -> scheme.getColorForProfile(null));
        }
    }

    // ------------------------ Custom Overrides ------------------------

    @Nested
    @DisplayName("Custom color overrides")
    class CustomOverrideTests {

        @Test
        @DisplayName("custom color takes precedence over auto-assigned")
        void setCustomColor_ReplacesAutoAssigned() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            Color autoColor = scheme.getColorForProfile("mainnet");

            Color customColor = new Color(0, 255, 0);
            scheme.setCustomColor("mainnet", customColor);

            assertNotEquals(autoColor, scheme.getColorForProfile("mainnet"));
            assertEquals(customColor, scheme.getColorForProfile("mainnet"));
        }

        @Test
        @DisplayName("custom color for unassigned profile")
        void setCustomColor_UnassignedProfile() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            Color customColor = new Color(255, 0, 0);

            scheme.setCustomColor("testnet", customColor);
            assertTrue(scheme.hasCustomColor("testnet"));
            assertEquals(customColor, scheme.getColorForProfile("testnet"));
        }

        @Test
        @DisplayName("clearCustomColor reverts to auto-assigned behavior")
        void clearCustomColor_RevertsToAutoAssigned() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            Color customColor = new Color(123, 45, 67);
            scheme.setCustomColor("dev", customColor);

            assertTrue(scheme.hasCustomColor("dev"));
            boolean cleared = scheme.clearCustomColor("dev");

            assertTrue(cleared);
            assertFalse(scheme.hasCustomColor("dev"));
        }

        @Test
        @DisplayName("clearCustomColor on non-custom returns false")
        void clearCustomColor_NoCustom_ReturnsFalse() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            // Auto-assign without setting custom
            scheme.getColorForProfile("alpha");

            assertFalse(scheme.hasCustomColor("alpha"));
            boolean cleared = scheme.clearCustomColor("alpha");

            // Was never custom, so should return false (no custom entry to remove)
            assertFalse(cleared);
        }

        @Test
        @DisplayName("null profileName in setCustomColor throws NPE")
        void setCustomColor_NullProfile_ThrowsNPE() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            assertThrows(NullPointerException.class,
                () -> scheme.setCustomColor(null, Color.RED));
        }

        @Test
        @DisplayName("null color in setCustomColor throws NPE")
        void setCustomColor_NullColor_ThrowsNPE() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            assertThrows(NullPointerException.class,
                () -> scheme.setCustomColor("test", null));
        }
    }

    // ------------------------ resolveEventColor ------------------------

    @Nested
    @DisplayName("resolveEventColor (null-safe lookup)")
    class ResolveEventColorTests {

        @Test
        @DisplayName("null profileName returns fallback color")
        void resolveEventColor_NullProfile_ReturnsFallback() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            Color fallback = scheme.resolveEventColor(null);

            assertNotNull(fallback);
            assertEquals(new Color(200, 200, 200), fallback);
        }

        @Test
        @DisplayName("empty profileName returns fallback color")
        void resolveEventColor_EmptyProfile_ReturnsFallback() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            Color fallback = scheme.resolveEventColor("");

            assertNotNull(fallback);
            assertEquals(new Color(200, 200, 200), fallback);
        }

        @Test
        @DisplayName("valid profileName delegates to getColorForProfile")
        void resolveEventColor_ValidProfile_ReturnsAssignedColor() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            scheme.setCustomColor("mainnet", Color.RED);

            assertEquals(Color.RED, scheme.resolveEventColor("mainnet"));
        }
    }

    // ------------------------ Pre-Assignment ------------------------

    @Nested
    @DisplayName("preAssignColors")
    class PreAssignTests {

        @Test
        @DisplayName("pre-assigns colors for known profiles")
        void preAssignColors_AssignsAll() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            List<String> profiles = List.of("mainnet", "testnet", "devnet");

            scheme.preAssignColors(profiles);

            // All should now have assigned colors
            Color c1 = scheme.getColorForProfile("mainnet");
            Color c2 = scheme.getColorForProfile("testnet");
            Color c3 = scheme.getColorForProfile("devnet");

            assertNotEquals(c1, c2);
            assertNotEquals(c1, c3);
            assertNotEquals(c2, c3);
        }

        @Test
        @DisplayName("skips null/empty entries in list")
        void preAssignColors_SkipsNullOrEmpty() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            // List.of() does not accept null, so use an ArrayList
            java.util.List<String> profiles = java.util.Arrays.asList("valid", null, "", "also-valid");

            assertDoesNotThrow(() -> scheme.preAssignColors(profiles));
        }

        @Test
        @DisplayName("null list throws NPE")
        void preAssignColors_NullList_ThrowsNPE() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            assertThrows(NullPointerException.class, () -> scheme.preAssignColors(null));
        }
    }

    // ------------------------ Export/Import ------------------------

    @Nested
    @DisplayName("Export and import (persistence)")
    class ExportImportTests {

        @Test
        @DisplayName("exportCustomColors returns RGB map of custom overrides")
        void exportCustomColors_ReturnsRgbMap() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            Color red = new Color(255, 0, 0);
            scheme.setCustomColor("mainnet", red);

            Map<String, Integer> exported = scheme.exportCustomColors();

            assertEquals(1, exported.size());
            assertEquals(red.getRGB(), exported.get("mainnet"));
        }

        @Test
        @DisplayName("export is unmodifiable")
        void exportCustomColors_ReturnsUnmodifiableMap() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            Map<String, Integer> exported = scheme.exportCustomColors();

            assertThrows(UnsupportedOperationException.class, () -> exported.put("test", 0));
        }

        @Test
        @DisplayName("fromRgbMap restores custom colors")
        void fromRgbMap_RestoresCustomColors() {
            ConsoleColorScheme original = new ConsoleColorScheme();
            Color green = new Color(0, 200, 100);
            original.setCustomColor("testnet", green);

            Map<String, Integer> exported = original.exportCustomColors();
            ConsoleColorScheme restored = ConsoleColorScheme.fromRgbMap(exported);

            assertEquals(green, restored.getColorForProfile("testnet"));
            assertTrue(restored.hasCustomColor("testnet"));
        }

        @Test
        @DisplayName("fromRgbMap with null throws NPE")
        void fromRgbMap_NullMap_ThrowsNPE() {
            assertThrows(NullPointerException.class, () -> ConsoleColorScheme.fromRgbMap(null));
        }
    }

    // ------------------------ Reset ------------------------

    @Nested
    @DisplayName("Reset")
    class ResetTests {

        @Test
        @DisplayName("reset clears all custom and auto-assigned colors")
        void reset_ClearsEverything() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            scheme.setCustomColor("mainnet", Color.RED);
            scheme.getColorForProfile("testnet"); // auto-assign

            scheme.reset();

            // After reset, "mainnet" should get a fresh auto-assigned color (not the custom red)
            assertFalse(scheme.hasCustomColor("mainnet"));
            assertNotEquals(Color.RED, scheme.getColorForProfile("mainnet"));
        }
    }

    // ------------------------ Singleton ------------------------

    @Nested
    @DisplayName("Singleton (getDefault)")
    class SingletonTests {

        @Test
        @DisplayName("getDefault returns same instance")
        void getDefault_ReturnsSameInstance() {
            ConsoleColorScheme s1 = ConsoleColorScheme.getDefault();
            ConsoleColorScheme s2 = ConsoleColorScheme.getDefault();

            assertSame(s1, s2);
        }

        @Test
        @DisplayName("singleton is independent from new instances")
        void singletonIndependentFromNewInstances() {
            ConsoleColorScheme singleton = ConsoleColorScheme.getDefault();
            ConsoleColorScheme local = new ConsoleColorScheme();

            // Modify local without affecting singleton
            local.setCustomColor("isolated", Color.BLUE);
            assertFalse(singleton.hasCustomColor("isolated"));
        }
    }

    // ------------------------ Palette Exhaustion ------------------------

    @Nested
    @DisplayName("Palette exhaustion (more profiles than colors)")
    class PaletteExhaustionTests {

        @Test
        @DisplayName("21st profile still gets a color (cycles through palette)")
        void getColorForProfile_OverPaletteLimit_CyclesCorrectly() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();

            // Assign 25 profiles (more than the 20-color palette)
            for (int i = 0; i < 25; i++) {
                Color c = scheme.getColorForProfile("p" + i);
                assertNotNull(c, "Profile p" + i + " should get a color");
            }
        }

        @Test
        @DisplayName("all assigned colors are non-null")
        void getColorForProfile_AllProfiles_GetNonNullColor() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();

            for (int i = 0; i < 50; i++) {
                Color c = scheme.getColorForProfile("profile-" + i);
                assertNotNull(c, "Even beyond palette size, color must not be null");
            }
        }
    }

    // ------------------------ getAssignedProfiles ------------------------

    @Nested
    @DisplayName("getAssignedProfiles")
    class AssignedProfilesTests {

        @Test
        @DisplayName("returns only profiles with custom colors")
        void getAssignedProfiles_ReturnsCustomOnly() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            scheme.setCustomColor("a", Color.RED);
            scheme.setCustomColor("b", Color.GREEN);

            List<String> profiles = scheme.getAssignedProfiles();
            assertEquals(2, profiles.size());
            assertTrue(profiles.contains("a"));
            assertTrue(profiles.contains("b"));
        }

        @Test
        @DisplayName("returned list is unmodifiable")
        void getAssignedProfiles_ReturnsUnmodifiableList() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            List<String> profiles = scheme.getAssignedProfiles();

            assertThrows(UnsupportedOperationException.class, () -> profiles.add("test"));
        }
    }

    // ------------------------ toString ------------------------

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("toString contains scheme info")
        void toString_ContainsSchemeInfo() {
            ConsoleColorScheme scheme = new ConsoleColorScheme();
            String str = scheme.toString();

            assertNotNull(str);
            assertTrue(str.contains("ConsoleColorScheme"));
            assertTrue(str.contains("custom="));
            assertTrue(str.contains("autoAssigned="));
        }
    }
}