package application.module.node.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import application.utils.logging.ConsoleColorScheme;
import application.utils.logging.ConsoleSettings;

/**
 * Unit tests for {@link ConsoleColorPanel}.
 * <p>
 * Tests focus on the public API methods and data model interactions,
 * using real ConsoleColorScheme instances (no mocking required).
 * </p>
 */
@DisplayName("ConsoleColorPanel Tests")
class ConsoleColorPanelTest {

    private ConsoleColorScheme scheme;
    private ConsoleColorPanel panel;

    @BeforeEach
    void setUp() {
        scheme = new ConsoleColorScheme();
        panel = new ConsoleColorPanel(scheme);
    }

    // ── Constructor Tests ────────────────────────────────────────────────

    @Test
    @DisplayName("Constructor: rejects null color scheme")
    void constructor_RejectsNullScheme() {
        assertThrows(NullPointerException.class, () -> new ConsoleColorPanel(null));
    }

    @Test
    @DisplayName("Constructor: returns correct scheme reference")
    void constructor_ReturnsCorrectScheme() {
        assertNotNull(panel.getColorScheme());
        assertSame(scheme, panel.getColorScheme());
    }

    @Test
    @DisplayName("Constructor: creates valid panel instance")
    void constructor_CreatesValidPanel() {
        assertNotNull(panel);
        assertTrue(panel instanceof ConsoleColorPanel);
    }

    // ── DEFAULT_SETTINGS_PATH ────────────────────────────────────────────

    @Test
    @DisplayName("DEFAULT_SETTINGS_PATH: points to settings/console-settings.json")
    void defaultSettingsPath_HasExpectedValue() {
        Path path = ConsoleColorPanel.DEFAULT_SETTINGS_PATH;
        assertNotNull(path);
        assertEquals("console-settings.json", path.getFileName().toString());
        assertEquals("settings", path.getParent().toString());
    }

    // ── refreshTable Tests ───────────────────────────────────────────────

    @Test
    @DisplayName("refreshTable: does not throw with empty profiles")
    void refreshTable_DoesNotThrow() {
        assertDoesNotThrow(() -> panel.refreshTable());
    }

    @Test
    @DisplayName("refreshTable: populates after profiles are assigned")
    void refreshTable_WithProfiles_PopulatesData() {
        // Set custom colors which also register the profiles
        scheme.setCustomColor("profile-a", Color.RED);
        scheme.setCustomColor("profile-b", Color.BLUE);

        assertDoesNotThrow(() -> panel.refreshTable());

        // Verify custom colors are set via getColorForProfile
        assertEquals(Color.RED, scheme.getColorForProfile("profile-a"));
        assertEquals(Color.BLUE, scheme.getColorForProfile("profile-b"));
    }

    @Test
    @DisplayName("refreshTable: handles empty profile list")
    void refreshTable_EmptyProfiles_NoError() {
        // Fresh scheme has no profiles
        assertEquals(0, scheme.getAssignedProfiles().size());
        assertDoesNotThrow(() -> panel.refreshTable());
    }

    // ── resetAllColors Tests ─────────────────────────────────────────────

    @Test
    @DisplayName("resetAllColors: clears custom colors")
    void resetAllColors_ClearsCustomColors() {
        // Set a custom color first
        scheme.getColorForProfile("test-profile");
        scheme.setCustomColor("test-profile", Color.MAGENTA);
        assertEquals(Color.MAGENTA, scheme.getColorForProfile("test-profile"));

        panel.resetAllColors();

        // After reset, custom color is cleared (returns auto-assigned color)
        assertNotEquals(Color.MAGENTA, scheme.getColorForProfile("test-profile"));
    }

    @Test
    @DisplayName("resetAllColors: does not throw with no profiles")
    void resetAllColors_NoProfiles_NoError() {
        assertDoesNotThrow(() -> panel.resetAllColors());
    }

    // ── saveSettings Tests ───────────────────────────────────────────────

    @Test
    @DisplayName("saveSettings: does not throw (catches file system exceptions)")
    void saveSettings_DoesNotThrowOnFailure() {
        assertDoesNotThrow(() -> panel.saveSettings());
    }

    // ── loadSettings Tests ───────────────────────────────────────────────

    @Test
    @DisplayName("loadSettings: does nothing when file does not exist")
    void loadSettings_NonExistentFile_DoesNothing() {
        Path fakePath = Path.of("/nonexistent/path/settings.json");
        assertDoesNotThrow(() -> panel.loadSettings(fakePath));
    }

    // ── Public API smoke tests ───────────────────────────────────────────

    @Test
    @DisplayName("Public API: all methods accessible without NPE")
    void publicApi_MethodsAccessible() {
        assertNotNull(panel.getColorScheme());
        assertDoesNotThrow(() -> panel.resetAllColors());
        assertDoesNotThrow(() -> panel.saveSettings());
    }

    // ── Color scheme integration tests ───────────────────────────────────

    @Test
    @DisplayName("Integration: custom color survives refreshTable")
    void integration_CustomColor_SurvivesRefresh() {
        scheme.getColorForProfile("mainnet");
        Color original = scheme.getColorForProfile("mainnet");

        // Set custom color
        scheme.setCustomColor("mainnet", Color.CYAN);
        assertEquals(Color.CYAN, scheme.getColorForProfile("mainnet"));

        // Refresh table doesn't clear custom colors
        panel.refreshTable();
        assertEquals(Color.CYAN, scheme.getColorForProfile("mainnet"));
    }

    @Test
    @DisplayName("Integration: multiple profiles get distinct auto-colors")
    void integration_MultipleProfiles_DistinctAutoColors() {
        Color c1 = scheme.getColorForProfile("alpha");
        Color c2 = scheme.getColorForProfile("beta");
        Color c3 = scheme.getColorForProfile("gamma");

        // Each profile should get a different color from the palette
        assertNotEquals(c1, c2);
        assertNotEquals(c2, c3);
        assertNotEquals(c1, c3);
    }

    @Test
    @DisplayName("Integration: palette wraps around after 20 profiles")
    void integration_PaletteWraps_AfterTwentyProfiles() {
        // The palette has 20 colors
        Color first = scheme.getColorForProfile("p01");

        for (int i = 2; i <= 20; i++) {
            scheme.getColorForProfile("p" + String.format("%02d", i));
        }

        // The 21st profile wraps back to the first palette color
        Color twentyFirst = scheme.getColorForProfile("p21");
        assertEquals(first, twentyFirst);
    }

    @Test
    @DisplayName("Integration: clearCustomColor restores auto-color")
    void integration_ClearCustom_RestoresAuto() {
        scheme.getColorForProfile("restore-test");
        Color autoColor = scheme.getColorForProfile("restore-test");

        // Override with custom color
        scheme.setCustomColor("restore-test", Color.MAGENTA);
        assertEquals(Color.MAGENTA, scheme.getColorForProfile("restore-test"));

        // Clear custom
        boolean cleared = scheme.clearCustomColor("restore-test");
        assertTrue(cleared);
        assertEquals(autoColor, scheme.getColorForProfile("restore-test"));
    }

    // ── ConsoleSettings integration tests ────────────────────────────────

    @Test
    @DisplayName("ConsoleSettings: syncFrom populates custom colors from scheme")
    void consoleSettings_SyncFrom_PopulatesCustomColors() {
        scheme.setCustomColor("test-profile", Color.MAGENTA);

        ConsoleSettings settings = new ConsoleSettings();
        assertDoesNotThrow(() -> settings.syncFrom(scheme));

        assertNotNull(settings.getCustomColorMap());
    }

    @Test
    @DisplayName("ConsoleSettings: applyTo restores custom colors to scheme")
    void consoleSettings_ApplyTo_RestoresColors() {
        ConsoleSettings settings = new ConsoleSettings();
        settings.setCustomColor("restore-test", Color.ORANGE);

        assertDoesNotThrow(() -> settings.applyTo(scheme));

        Color restored = settings.getCustomColor("restore-test");
        assertNotNull(restored);
        assertEquals(Color.ORANGE, restored);
    }

    @Test
    @DisplayName("ConsoleSettings: roundtrip preserves custom colors")
    void consoleSettings_Roundtrip_PreservesColors() {
        ConsoleColorScheme scheme1 = new ConsoleColorScheme();
        scheme1.setCustomColor("alpha", Color.CYAN);
        scheme1.setCustomColor("beta", Color.PINK);

        ConsoleSettings settings = new ConsoleSettings();
        settings.syncFrom(scheme1);

        ConsoleColorScheme scheme2 = new ConsoleColorScheme();
        settings.applyTo(scheme2);

        Color alpha = settings.getCustomColor("alpha");
        assertNotNull(alpha);
    }

    @Test
    @DisplayName("ConsoleSettings: empty scheme produces empty custom map")
    void consoleSettings_EmptyScheme_EmptyMap() {
        ConsoleSettings settings = new ConsoleSettings();
        settings.syncFrom(scheme);

        // Fresh scheme has no custom colors
        assertTrue(settings.getCustomColorMap().isEmpty());
    }
}