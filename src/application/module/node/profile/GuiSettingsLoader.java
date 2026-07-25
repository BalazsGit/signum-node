package application.module.node.profile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Factory utility that loads GUI-specific settings for a node profile
 * from {@code settings/gui-settings.json}.
 * <p>
 * <h3>JSON Path Schema</h3>
 * <pre>{@code
 * {
 *   "modules": {
 *     "node": {
 *       "profiles": {
 *         "mainnet": {
 *           "consoleFilters": {
 *             "logLevel": "info",
 *             "modules": ["node", "database"],
 *             "textSearch": ""
 *           }
 *         }
 *       }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <h3>Design Decisions</h3>
 * <ul>
 *   <li><b>Factory Pattern:</b> This class creates {@link GuiProfileSettings}
 *       instances from persistent JSON storage. It encapsulates all parsing
 *       logic so callers receive a ready-to-use immutable value object.</li>
 *   <li><b>Stateless Utility:</b> No mutable state is stored between calls.
 *       Each invocation reads from disk (or returns cached defaults). Safe
 *       for concurrent access.</li>
 *   <li><b>Graceful Degradation:</b> Returns {@code null} when the file
 *       does not exist, the profile section is missing, or parsing fails.
 *       The GUI falls back to its built-in defaults in all failure cases.</li>
 *   <li><b>Defensive Copying:</b> Module lists are copied during construction
 *       of {@link ConsoleFilterConfig} to prevent external mutation.</li>
 * </ul>
 *
 * @see GuiProfileSettings
 * @see ConsoleFilterConfig
 * @since 4.0
 */
public final class GuiSettingsLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuiSettingsLoader.class);

    /** Default settings file path (relative to working directory). */
    public static final String DEFAULT_SETTINGS_PATH = "settings/gui-settings.json";

    /** Module key for node profiles in gui-settings.json. */
    public static final String MODULE_KEY_NODE = "node";

    // ── Construction ─────────────────────────────────────────────────────

    private GuiSettingsLoader() {
        // Utility class — never instantiated
    }

    // ── Public Factory Methods ───────────────────────────────────────────

    /**
     * Loads GUI settings for the specified node profile from the default
     * {@code settings/gui-settings.json} file.
     * <p>
     * Navigation path: {@code modules → node → profiles → {profileName}}
     *
     * @param moduleName  the module identifier (typically "node")
     * @param profileName the node profile name to load settings for
     * @return a fully populated {@link GuiProfileSettings}, or {@code null}
     *         if the file does not exist, the profile section is missing,
     *         or parsing fails
     */
    public static GuiProfileSettings loadForProfile(String moduleName, String profileName) {
        Objects.requireNonNull(moduleName, "moduleName must not be null");
        Objects.requireNonNull(profileName, "profileName must not be null");

        Path settingsPath = Paths.get(DEFAULT_SETTINGS_PATH);
        if (!Files.exists(settingsPath)) {
            LOGGER.debug("GUI settings file not found at {}", settingsPath);
            return null;
        }

        try {
            JsonObject root = readJsonFile(settingsPath);
            if (root == null) {
                return null;
            }

            JsonObject profileSection = navigateToProfile(root, moduleName, profileName);
            if (profileSection == null) {
                LOGGER.debug("No GUI settings for module '{}' profile '{}'", moduleName, profileName);
                return null;
            }

            return parseGuiProfileSettings(profileSection);
        } catch (Exception e) {
            LOGGER.warn("Failed to load GUI settings for profile '{}': {}", profileName, e.getMessage());
            return null;
        }
    }

    /**
     * Loads GUI settings using a custom file path.
     * Useful for testing with mock files or alternative config locations.
     *
     * @param settingsPath the path to the gui-settings.json file
     * @param moduleName   the module identifier (typically "node")
     * @param profileName  the node profile name
     * @return a fully populated {@link GuiProfileSettings}, or {@code null}
     */
    static GuiProfileSettings loadForProfile(Path settingsPath, String moduleName, String profileName) {
        Objects.requireNonNull(settingsPath, "settingsPath must not be null");
        Objects.requireNonNull(moduleName, "moduleName must not be null");
        Objects.requireNonNull(profileName, "profileName must not be null");

        if (!Files.exists(settingsPath)) {
            return null;
        }

        try {
            JsonObject root = readJsonFile(settingsPath);
            if (root == null) {
                return null;
            }

            JsonObject profileSection = navigateToProfile(root, moduleName, profileName);
            if (profileSection == null) {
                return null;
            }

            return parseGuiProfileSettings(profileSection);
        } catch (Exception e) {
            LOGGER.warn("Failed to load GUI settings from {}: {}", settingsPath, e.getMessage());
            return null;
        }
    }

    // ── JSON Navigation ──────────────────────────────────────────────────

    /**
     * Navigates the JSON structure: modules → {moduleName} → profiles → {profileName}.
     * Returns the profile JsonObject or null if any level is missing.
     */
    private static JsonObject navigateToProfile(JsonObject root, String moduleName, String profileName) {
        JsonObject modules = getJsonObject(root, "modules");
        if (modules == null) {
            return null;
        }

        JsonObject moduleSection = getJsonObject(modules, moduleName);
        if (moduleSection == null) {
            return null;
        }

        JsonObject profiles = getJsonObject(moduleSection, "profiles");
        if (profiles == null) {
            return null;
        }

        return getJsonObject(profiles, profileName);
    }

    // ── Parsing Logic ────────────────────────────────────────────────────

    /**
     * Parses a profile section JsonObject into GuiProfileSettings.
     */
    private static GuiProfileSettings parseGuiProfileSettings(JsonObject profileSection) {
        ConsoleFilterConfig consoleFilters = null;

        if (profileSection.has("consoleFilters") && !profileSection.get("consoleFilters").isJsonNull()) {
            JsonObject filtersObj = profileSection.getAsJsonObject("consoleFilters");
            consoleFilters = parseConsoleFilterConfig(filtersObj);
        }

        // Only return non-null if at least one setting was parsed
        if (consoleFilters == null) {
            return null;
        }

        return new GuiProfileSettings(consoleFilters);
    }

    /**
     * Parses consoleFilters JsonObject into ConsoleFilterConfig.
     */
    private static ConsoleFilterConfig parseConsoleFilterConfig(JsonObject filtersObj) {
        String logLevel = null;
        List<String> modules = null;
        String textSearch = null;

        // Parse logLevel (string, optional)
        if (filtersObj.has("logLevel") && !filtersObj.get("logLevel").isJsonNull()) {
            logLevel = filtersObj.get("logLevel").getAsString();
            if (logLevel == null || logLevel.isBlank()) {
                logLevel = null;
            }
        }

        // Parse modules (array of strings, optional)
        if (filtersObj.has("modules") && !filtersObj.get("modules").isJsonNull()) {
            List<String> parsedModules = new ArrayList<>();
            filtersObj.getAsJsonArray("modules").forEach(element -> {
                if (element.isJsonPrimitive()) {
                    parsedModules.add(element.getAsString());
                }
            });
            modules = parsedModules.isEmpty() ? null : parsedModules;
        }

        // Parse textSearch (string, optional)
        if (filtersObj.has("textSearch") && !filtersObj.get("textSearch").isJsonNull()) {
            textSearch = filtersObj.get("textSearch").getAsString();
            if (textSearch == null || textSearch.isBlank()) {
                textSearch = null;
            }
        }

        // Return null only if all fields are empty (no meaningful config)
        if (logLevel == null && modules == null && textSearch == null) {
            return null;
        }

        return new ConsoleFilterConfig(logLevel, modules, textSearch);
    }

    // ── File I/O Utilities ───────────────────────────────────────────────

    /**
     * Reads and parses a JSON file using a streaming parser.
     * <p>
     * Uses {@link JsonParser#parseReader(java.io.Reader)} for memory-efficient
     * parsing — avoids loading the entire file into a String first, which is
     * important for large configuration files.
     *
     * @param path the JSON file to parse
     * @return the root JsonObject, or null if reading/parsing fails
     */
    private static JsonObject readJsonFile(Path path) {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            LOGGER.error("Failed to read JSON file: {}", path, e);
            return null;
        } catch (Exception e) {
            LOGGER.error("Invalid JSON in file: {}", path, e);
            return null;
        }
    }

    // ── Helper Methods ───────────────────────────────────────────────────

    /**
     * Safely extracts a nested JsonObject by key. Returns null if the key
     * does not exist or the value is not an object.
     */
    private static JsonObject getJsonObject(JsonObject parent, String key) {
        if (!parent.has(key)) {
            return null;
        }
        try {
            return parent.getAsJsonObject(key);
        } catch (ClassCastException e) {
            LOGGER.debug("Key '{}' is not a JSON object", key);
            return null;
        }
    }
}