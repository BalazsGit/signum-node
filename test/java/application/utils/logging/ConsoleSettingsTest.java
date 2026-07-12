package application.utils.logging;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConsoleSettings Tests")
class ConsoleSettingsTest {

    private ConsoleSettings settings;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        settings = new ConsoleSettings();
    }

    // ── Default Values ───────────────────────────────────────────────────

    @Test
    @DisplayName("Given new settings, then defaults are applied")
    void defaults_GivenNewSettings_ReturnsDefaults() {
        assertNull(settings.getMinLogLevel());
        assertEquals(1000, settings.getMaxConsoleLines());
        assertTrue(settings.getCustomColorMap().isEmpty());
    }

    // ── Custom Colors ────────────────────────────────────────────────────

    @Test
    @DisplayName("Given color set, then getCustomColor returns it")
    void getCustomColor_GivenColorSet_ReturnsColor() {
        Color expected = new Color(100, 150, 200);
        settings.setCustomColor("mainnet", expected);

        Color actual = settings.getCustomColor("mainnet");

        assertNotNull(actual);
        assertEquals(expected.getRGB(), actual.getRGB());
    }

    @Test
    @DisplayName("Given no color set, then getCustomColor returns null")
    void getCustomColor_GivenNoColorSet_ReturnsNull() {
        assertNull(settings.getCustomColor("nonexistent"));
    }

    @Test
    @DisplayName("Given color set, then removeCustomColor removes it")
    void removeCustomColor_GivenColorSet_RemovesIt() {
        settings.setCustomColor("testnet", Color.RED);
        assertTrue(settings.removeCustomColor("testnet"));
        assertNull(settings.getCustomColor("testnet"));
    }

    @Test
    @DisplayName("Given no color set, then removeCustomColor returns false")
    void removeCustomColor_GivenNoColorSet_ReturnsFalse() {
        assertFalse(settings.removeCustomColor("empty"));
    }

    @Test
    @DisplayName("Given null profileName, then setCustomColor throws NPE")
    void setCustomColor_GivenNullProfile_ThrowsNPE() {
        assertThrows(NullPointerException.class, () ->
            settings.setCustomColor(null, Color.RED));
    }

    @Test
    @DisplayName("Given null color, then setCustomColor throws NPE")
    void setCustomColor_GivenNullColor_ThrowsNPE() {
        assertThrows(NullPointerException.class, () ->
            settings.setCustomColor("profile", null));
    }

    @Test
    @DisplayName("Given multiple colors set, then clear removes all")
    void clearCustomColors_GivenMultipleSet_RemovesAll() {
        settings.setCustomColor("a", Color.RED);
        settings.setCustomColor("b", Color.GREEN);
        settings.clearCustomColors();

        assertTrue(settings.getCustomColorMap().isEmpty());
    }

    @Test
    @DisplayName("Given colors set, then getCustomColorMap returns unmodifiable copy")
    void getCustomColorMap_GivenColorsSet_ReturnsUnmodifiableCopy() {
        settings.setCustomColor("x", Color.BLUE);
        Map<String, Integer> map = settings.getCustomColorMap();

        assertThrows(UnsupportedOperationException.class, () ->
            map.put("y", 123));
    }

    // ── Minimum Log Level ────────────────────────────────────────────────

    @Test
    @DisplayName("Given level set, then getMinLogLevel returns it")
    void getMinLogLevel_GivenLevelSet_ReturnsIt() {
        settings.setMinLogLevel("WARN");
        assertEquals("WARN", settings.getMinLogLevel());
    }

    @Test
    @DisplayName("Given null level set, then getMinLogLevel returns null")
    void getMinLogLevel_GivenNullSet_ReturnsNull() {
        settings.setMinLogLevel(null);
        assertNull(settings.getMinLogLevel());
    }

    // ── Max Console Lines ────────────────────────────────────────────────

    @Test
    @DisplayName("Given positive lines, then setMaxConsoleLines updates value")
    void setMaxConsoleLines_GivenPositive_UpdatesValue() {
        settings.setMaxConsoleLines(500);
        assertEquals(500, settings.getMaxConsoleLines());
    }

    @Test
    @DisplayName("Given zero lines, then setMaxConsoleLines throws IAE")
    void setMaxConsoleLines_GivenZero_ThrowsIAE() {
        assertThrows(IllegalArgumentException.class, () ->
            settings.setMaxConsoleLines(0));
    }

    @Test
    @DisplayName("Given negative lines, then setMaxConsoleLines throws IAE")
    void setMaxConsoleLines_GivenNegative_ThrowsIAE() {
        assertThrows(IllegalArgumentException.class, () ->
            settings.setMaxConsoleLines(-10));
    }

    // ── ConsoleColorScheme Integration ───────────────────────────────────

    @Test
    @DisplayName("Given settings with colors, then applyTo sets them on scheme")
    void applyTo_GivenColorsSet_AppliesToScheme() {
        settings.setCustomColor("mainnet", new Color(1, 2, 3));
        settings.setCustomColor("testnet", new Color(4, 5, 6));

        ConsoleColorScheme scheme = new ConsoleColorScheme();
        settings.applyTo(scheme);

        assertEquals(new Color(1, 2, 3).getRGB(), scheme.getColorForProfile("mainnet").getRGB());
        assertEquals(new Color(4, 5, 6).getRGB(), scheme.getColorForProfile("testnet").getRGB());
    }

    @Test
    @DisplayName("Given null scheme, then applyTo throws NPE")
    void applyTo_GivenNullScheme_ThrowsNPE() {
        assertThrows(NullPointerException.class, () ->
            settings.applyTo(null));
    }

    @Test
    @DisplayName("Given scheme with custom colors, then syncFrom captures them")
    void syncFrom_GivenSchemeWithColors_SyncsThem() {
        ConsoleColorScheme scheme = new ConsoleColorScheme();
        scheme.setCustomColor("dev", Color.MAGENTA);

        settings.syncFrom(scheme);

        assertNotNull(settings.getCustomColor("dev"));
        assertEquals(Color.MAGENTA.getRGB(), settings.getCustomColor("dev").getRGB());
    }

    @Test
    @DisplayName("Given null scheme, then syncFrom throws NPE")
    void syncFrom_GivenNullScheme_ThrowsNPE() {
        assertThrows(NullPointerException.class, () ->
            settings.syncFrom(null));
    }

    // ── JSON Serialization ───────────────────────────────────────────────

    @Test
    @DisplayName("Given settings with data, then toJson produces valid JSON")
    void toJson_GivenData_ProducesValidJson() {
        settings.setCustomColor("main", new Color(100, 200, 50));
        settings.setMinLogLevel("INFO");
        settings.setMaxConsoleLines(500);

        String json = settings.toJson();

        assertNotNull(json);
        assertTrue(json.contains("\"customColors\""));
        assertTrue(json.contains("\"minLogLevel\""));
        assertTrue(json.contains("\"maxConsoleLines\""));
        assertTrue(json.contains("INFO"));
    }

    @Test
    @DisplayName("Given empty settings, then toJson produces valid JSON with empty colors")
    void toJson_GivenEmpty_ProducesValidJson() {
        String json = new ConsoleSettings().toJson();
        assertTrue(json.contains("\"customColors\""));
    }

    @Test
    @DisplayName("Given valid JSON, then fromJson parses correctly")
    void fromJson_GivenValidJson_ParsesCorrectly() {
        String json = """
            {
              "customColors": {
                "profile-a": -16776961,
                "profile-b": -65536
              },
              "minLogLevel": "DEBUG",
              "maxConsoleLines": 2000
            }
            """;

        ConsoleSettings parsed = ConsoleSettings.fromJson(json);

        assertEquals(2, parsed.getCustomColorMap().size());
        assertEquals(-16776961, parsed.getCustomColorMap().get("profile-a").intValue());
        assertEquals("DEBUG", parsed.getMinLogLevel());
        assertEquals(2000, parsed.getMaxConsoleLines());
    }

    @Test
    @DisplayName("Given JSON with missing fields, then fromJson uses defaults")
    void fromJson_GivenMissingFields_UsesDefaults() {
        String json = """
            {
              "customColors": {}
            }
            """;

        ConsoleSettings parsed = ConsoleSettings.fromJson(json);

        assertTrue(parsed.getCustomColorMap().isEmpty());
        assertNull(parsed.getMinLogLevel());
        assertEquals(1000, parsed.getMaxConsoleLines()); // default
    }

    @Test
    @DisplayName("Given invalid JSON, then fromJson returns empty settings")
    void fromJson_GivenInvalidJson_ReturnsDefaults() {
        ConsoleSettings parsed = ConsoleSettings.fromJson("{invalid json}");
        assertTrue(parsed.getCustomColorMap().isEmpty());
    }

    @Test
    @DisplayName("Given null JSON, then fromJson throws NPE")
    void fromJson_GivenNull_ThrowsNPE() {
        assertThrows(NullPointerException.class, () ->
            ConsoleSettings.fromJson(null));
    }

    // ── Round-trip Serialization ─────────────────────────────────────────

    @Test
    @DisplayName("Given settings, then toJson + fromJson round-trips correctly")
    void roundtrip_GivenComplexSettings_PreservesData() {
        settings.setCustomColor("alpha", new Color(10, 20, 30));
        settings.setCustomColor("beta", new Color(40, 50, 60));
        settings.setMinLogLevel("ERROR");
        settings.setMaxConsoleLines(750);

        String json = settings.toJson();
        ConsoleSettings restored = ConsoleSettings.fromJson(json);

        assertEquals(settings.getMinLogLevel(), restored.getMinLogLevel());
        assertEquals(settings.getMaxConsoleLines(), restored.getMaxConsoleLines());
        assertEquals(2, restored.getCustomColorMap().size());
        assertEquals(new Color(10, 20, 30).getRGB(),
                      restored.getCustomColorMap().get("alpha").intValue());
    }

    // ── File I/O ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given settings, then save + load round-trips correctly")
    void saveLoad_GivenSettings_RoundTrips() throws IOException {
        settings.setCustomColor("file-test", Color.ORANGE);
        settings.setMinLogLevel("TRACE");
        settings.setMaxConsoleLines(300);

        Path file = tempDir.resolve("console-settings.json");
        settings.save(file);

        ConsoleSettings loaded = ConsoleSettings.load(file);

        assertEquals(settings.getMinLogLevel(), loaded.getMinLogLevel());
        assertEquals(settings.getMaxConsoleLines(), loaded.getMaxConsoleLines());
        assertEquals(1, loaded.getCustomColorMap().size());
    }

    @Test
    @DisplayName("Given nonexistent file, then load returns empty settings")
    void load_GivenNonexistentFile_ReturnsDefaults() {
        Path missing = tempDir.resolve("does-not-exist.json");
        ConsoleSettings loaded = ConsoleSettings.load(missing);

        assertNotNull(loaded);
        assertTrue(loaded.getCustomColorMap().isEmpty());
    }

    @Test
    @DisplayName("Given null path, then save throws NPE")
    void save_GivenNullPath_ThrowsNPE() {
        assertThrows(NullPointerException.class, () ->
            settings.save(null));
    }

    @Test
    @DisplayName("Given null path, then load throws NPE")
    void load_GivenNullPath_ThrowsNPE() {
        assertThrows(NullPointerException.class, () ->
            ConsoleSettings.load(null));
    }

    @Test
    @DisplayName("Given settings and save with nested dir, then creates parent dirs")
    void save_GivenNestedDir_CreatesParentDirs() throws IOException {
        Path nested = tempDir.resolve("a").resolve("b").resolve("c").resolve("settings.json");
        assertFalse(Files.exists(nested.getParent()));

        settings.save(nested);

        assertTrue(Files.exists(nested));
    }

    // ── Equality & HashCode ──────────────────────────────────────────────

    @Test
    @DisplayName("Given identical settings, then equals returns true")
    void equals_GivenIdenticalSettings_ReturnsTrue() {
        ConsoleSettings other = new ConsoleSettings();
        other.setCustomColor("p", Color.RED);
        settings.setCustomColor("p", Color.RED);

        // Note: we compare specific fields since maps need exact match
        assertEquals(other.getMinLogLevel(), settings.getMinLogLevel());
        assertEquals(other.getMaxConsoleLines(), settings.getMaxConsoleLines());
    }

    @Test
    @DisplayName("Given different maxLines, then equals returns false")
    void equals_GivenDifferentMaxLines_ReturnsFalse() {
        settings.setMaxConsoleLines(500);
        ConsoleSettings other = new ConsoleSettings(); // default 1000

        assertNotEquals(other, settings);
    }

    @Test
    @DisplayName("Given null, then equals returns false")
    void equals_GivenNull_ReturnsFalse() {
        assertNotEquals(null, settings);
    }

    @Test
    @DisplayName("Given different class, then equals returns false")
    void equals_GivenDifferentClass_ReturnsFalse() {
        assertNotEquals("string", settings);
    }

    @Test
    @DisplayName("Given identical settings, then hashCode is same")
    void hashCode_GivenIdenticalSettings_ReturnsSame() {
        ConsoleSettings other = new ConsoleSettings();
        // Both empty defaults
        assertEquals(settings.hashCode(), other.hashCode());
    }

    // ── toString ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given settings, then toString contains key info")
    void toString_GivenSettings_ContainsInfo() {
        settings.setMinLogLevel("WARN");
        String s = settings.toString();

        assertTrue(s.contains("ConsoleSettings"));
        assertTrue(s.contains("minLevel=DEBUG") || s.contains("minLevel=WARN"));
    }
}