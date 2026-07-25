package application.module.node.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GuiSettingsLoader}.
 * <p>
 * Follows AAA pattern (Arrange-Act-Assert) with JUnit 5.
 * Uses temporary files to simulate gui-settings.json without affecting real config.
 *
 * @since 4.0
 */
@DisplayName("GuiSettingsLoader Tests")
class GuiSettingsLoaderTest {

    @TempDir
    Path tempDir;

    // =====================================================================
    // loadForProfile(Path, String, String) - Happy Path
    // =====================================================================

    @Nested
    @DisplayName("loadForProfile(Path, String, String) - Happy Path")
    class HappyPathTests {

        @Test
        @DisplayName("loads full consoleFilters config for existing profile")
        void loadFullConfig_GivenCompleteJson_ReturnsGuiProfileSettings() throws IOException {
            // Arrange
            Path jsonFile = createJsonFile(
                "{\"modules\":{\"node\":{\"profiles\":{\"mainnet\":{" +
                "\"consoleFilters\":{\"logLevel\":\"info\",\"modules\":[\"node\",\"database\"]," +
                "\"textSearch\":\"error\"}}}}}}"
            );

            // Act
            GuiProfileSettings result = GuiSettingsLoader.loadForProfile(
                    jsonFile, "node", "mainnet");

            // Assert
            assertNotNull(result);
            assertTrue(result.hasConsoleFilters());
            assertEquals("info", result.getConsoleFilters().getLogLevel());
            assertEquals(List.of("node", "database"), result.getConsoleFilters().getModules());
            assertEquals("error", result.getConsoleFilters().getTextSearch());
        }

        @Test
        @DisplayName("loads config with only logLevel set")
        void loadLogLevelOnly_GivenPartialJson_ReturnsConfigWithLogLevel() throws IOException {
            // Arrange
            Path jsonFile = createJsonFile(
                "{\"modules\":{\"node\":{\"profiles\":{\"testnet\":{" +
                "\"consoleFilters\":{\"logLevel\":\"debug\"}}}}}}"
            );

            // Act
            GuiProfileSettings result = GuiSettingsLoader.loadForProfile(
                    jsonFile, "node", "testnet");

            // Assert
            assertNotNull(result);
            assertEquals("debug", result.getConsoleFilters().getLogLevel());
            assertFalse(result.getConsoleFilters().hasModulesFilter());
            assertFalse(result.getConsoleFilters().hasTextSearch());
        }

        @Test
        @DisplayName("loads config with only modules set")
        void loadModulesOnly_GivenPartialJson_ReturnsConfigWithModules() throws IOException {
            // Arrange
            Path jsonFile = createJsonFile(
                "{\"modules\":{\"node\":{\"profiles\":{\"custom\":{" +
                "\"consoleFilters\":{\"modules\":[\"peers\",\"mining\"]}}}}}}"
            );

            // Act
            GuiProfileSettings result = GuiSettingsLoader.loadForProfile(
                    jsonFile, "node", "custom");

            // Assert
            assertNotNull(result);
            assertNull(result.getConsoleFilters().getLogLevel());
            assertEquals(List.of("peers", "mining"), result.getConsoleFilters().getModules());
        }

        @Test
        @DisplayName("loads config with only textSearch set")
        void loadTextSearchOnly_GivenPartialJson_ReturnsConfigWithTextSearch() throws IOException {
            // Arrange
            Path jsonFile = createJsonFile(
                "{\"modules\":{\"node\":{\"profiles\":{\"mainnet\":{" +
                "\"consoleFilters\":{\"textSearch\":\"block\"}}}}}}"
            );

            // Act
            GuiProfileSettings result = GuiSettingsLoader.loadForProfile(
                    jsonFile, "node", "mainnet");

            // Assert
            assertNotNull(result);
            assertNull(result.getConsoleFilters().getLogLevel());
            assertFalse(result.getConsoleFilters().hasModulesFilter());
            assertEquals("block", result.getConsoleFilters().getTextSearch());
        }
    }

    // =====================================================================
    // loadForProfile(Path, String, String) - Null/Default Returns
    // =====================================================================

    @Nested
    @DisplayName("loadForProfile(Path, String, String) - Null Returns")
    class NullReturnTests {

        @Test
        @DisplayName("returns null when file does not exist")
        void loadForProfile_GivenNonExistentFile_ReturnsNull() {
            Path missing = tempDir.resolve("does-not-exist.json");

            GuiProfileSettings result = GuiSettingsLoader.loadForProfile(
                    missing, "node", "mainnet");

            assertNull(result);
        }

        @Test
        @DisplayName("returns null when profile section is missing")
        void loadForProfile_GivenMissingProfile_ReturnsNull() throws IOException {
            // Arrange - file exists but has no "mainnet" profile
            Path jsonFile = createJsonFile(
                "{\"modules\":{\"node\":{\"profiles\":{\"testnet\":{}}}}}"
            );

            GuiProfileSettings result = GuiSettingsLoader.loadForProfile(
                    jsonFile, "node", "mainnet");

            assertNull(result);
        }

        @Test
        @DisplayName("returns null when module section is missing")
        void loadForProfile_GivenMissingModule_ReturnsNull() throws IOException {
            Path jsonFile = createJsonFile("{\"modules\":{\"database\":{}}}");

            GuiProfileSettings result = GuiSettingsLoader.loadForProfile(
                    jsonFile, "node", "mainnet");

            assertNull(result);
        }

        @Test
        @DisplayName("returns null when modules root is missing")
        void loadForProfile_GivenMissingModulesRoot_ReturnsNull() throws IOException {
            Path jsonFile = createJsonFile("{\"other\":{}}");

            GuiProfileSettings result = GuiSettingsLoader.loadForProfile(
                    jsonFile, "node", "mainnet");

            assertNull(result);
        }

        @Test
        @DisplayName("returns null when profile has no consoleFilters")
        void loadForProfile_GivenNoConsoleFilters_ReturnsNull() throws IOException {
            Path jsonFile = createJsonFile(
                "{\"modules\":{\"node\":{\"profiles\":{\"mainnet\":{}}}}}"
            );

            GuiProfileSettings result = GuiSettingsLoader.loadForProfile(
                    jsonFile, "node", "mainnet");

            assertNull(result);
        }

        @Test
        @DisplayName("returns null when consoleFilters is null in JSON")
        void loadForProfile_GivenNullConsoleFilters_ReturnsNull() throws IOException {
            Path jsonFile = createJsonFile(
                "{\"modules\":{\"node\":{\"profiles\":{\"mainnet\":{" +
                "\"consoleFilters\":null}}}}}"
            );

            GuiProfileSettings result = GuiSettingsLoader.loadForProfile(
                    jsonFile, "node", "mainnet");

            assertNull(result);
        }

        @Test
        @DisplayName("returns null when all filter fields are empty")
        void loadForProfile_GivenAllEmptyFields_ReturnsNull() throws IOException {
            Path jsonFile = createJsonFile(
                "{\"modules\":{\"node\":{\"profiles\":{\"mainnet\":{" +
                "\"consoleFilters\":{\"logLevel\":\"\",\"textSearch\":\" \"}}}}}}"
            );

            GuiProfileSettings result = GuiSettingsLoader.loadForProfile(
                    jsonFile, "node", "mainnet");

            assertNull(result);
        }

        @Test
        @DisplayName("returns null for invalid JSON file")
        void loadForProfile_GivenInvalidJson_ReturnsNull() throws IOException {
            Path jsonFile = tempDir.resolve("invalid.json");
            try (FileWriter writer = new FileWriter(jsonFile.toFile())) {
                writer.write("{not valid json}");
            }

            GuiProfileSettings result = GuiSettingsLoader.loadForProfile(
                    jsonFile, "node", "mainnet");

            assertNull(result);
        }
    }

    // =====================================================================
    // loadForProfile(Path, String, String) - Input Validation
    // =====================================================================

    @Nested
    @DisplayName("loadForProfile(Path, String, String) - Input Validation")
    class InputValidationTests {

        @Test
        @DisplayName("throws NPE when settingsPath is null")
        void loadForProfile_GivenNullPath_ThrowsNPE() {
            assertThrows(NullPointerException.class, () ->
                GuiSettingsLoader.loadForProfile(null, "node", "mainnet")
            );
        }

        @Test
        @DisplayName("throws NPE when moduleName is null")
        void loadForProfile_GivenNullModuleName_ThrowsNPE() throws IOException {
            Path jsonFile = createJsonFile("{}");

            assertThrows(NullPointerException.class, () ->
                GuiSettingsLoader.loadForProfile(jsonFile, null, "mainnet")
            );
        }

        @Test
        @DisplayName("throws NPE when profileName is null")
        void loadForProfile_GivenNullProfileName_ThrowsNPE() throws IOException {
            Path jsonFile = createJsonFile("{}");

            assertThrows(NullPointerException.class, () ->
                GuiSettingsLoader.loadForProfile(jsonFile, "node", null)
            );
        }
    }

    // =====================================================================
    // Module Filtering (Defense-in-Depth Tests)
    // =====================================================================

    @Nested
    @DisplayName("Module Array Parsing")
    class ModuleArrayTests {

        @Test
        @DisplayName("handles empty modules array by returning null")
        void parseModules_GivenEmptyArray_ReturnsNull() throws IOException {
            Path jsonFile = createJsonFile(
                "{\"modules\":{\"node\":{\"profiles\":{\"mainnet\":{" +
                "\"consoleFilters\":{\"modules\":[]}}}}}}"
            );

            GuiProfileSettings result = GuiSettingsLoader.loadForProfile(
                    jsonFile, "node", "mainnet");

            assertNull(result); // empty modules + no other fields → null
        }

        @Test
        @DisplayName("handles single module in array")
        void parseModules_GivenSingleModule_ReturnsOneElementList() throws IOException {
            Path jsonFile = createJsonFile(
                "{\"modules\":{\"node\":{\"profiles\":{\"mainnet\":{" +
                "\"consoleFilters\":{\"modules\":[\"only-one\"]}}}}}}"
            );

            GuiProfileSettings result = GuiSettingsLoader.loadForProfile(
                    jsonFile, "node", "mainnet");

            assertNotNull(result);
            assertEquals(List.of("only-one"), result.getConsoleFilters().getModules());
        }

        @Test
        @DisplayName("handles multiple modules in array")
        void parseModules_GivenMultipleModules_ReturnsAll() throws IOException {
            Path jsonFile = createJsonFile(
                "{\"modules\":{\"node\":{\"profiles\":{\"mainnet\":{" +
                "\"consoleFilters\":{\"modules\":[\"a\",\"b\",\"c\",\"d\"]}}}}}}"
            );

            GuiProfileSettings result = GuiSettingsLoader.loadForProfile(
                    jsonFile, "node", "mainnet");

            assertNotNull(result);
            assertEquals(List.of("a", "b", "c", "d"), result.getConsoleFilters().getModules());
        }
    }

    // =====================================================================
    // DEFAULT_SETTINGS_PATH constant
    // =====================================================================

    @Nested
    @DisplayName("Constants")
    class ConstantsTests {

        @Test
        @DisplayName("DEFAULT_SETTINGS_PATH has expected value")
        void defaultSettingsPath_HasExpectedValue() {
            assertEquals("settings/gui-settings.json", GuiSettingsLoader.DEFAULT_SETTINGS_PATH);
        }

        @Test
        @DisplayName("MODULE_KEY_NODE has expected value")
        void moduleKeyNode_HasExpectedValue() {
            assertEquals("node", GuiSettingsLoader.MODULE_KEY_NODE);
        }
    }

    // ── Helper Methods ───────────────────────────────────────────────────

    /** Creates a JSON file with the given content and returns its path. */
    private Path createJsonFile(String json) throws IOException {
        Path file = tempDir.resolve("gui-settings-test.json");
        try (FileWriter writer = new FileWriter(file.toFile())) {
            writer.write(json);
        }
        return file;
    }
}