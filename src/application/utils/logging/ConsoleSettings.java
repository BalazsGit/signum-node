package application.utils.logging;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Persistent settings for the SystemConsole: color schemes, filter state,
 * max lines, and other user-customizable console preferences.
 * <p>
 * <h3>Data Model</h3>
 * Stores custom profile-to-color overrides (RGB integers) that integrate
 * directly with {@link ConsoleColorScheme#fromRgbMap(Map)} for restoration,
 * plus a default minimum log level filter.
 * </p>
 * <p>
 * <h3>JSON Format</h3>
 * <pre>{@code
 * {
 *   "customColors": {
 *     "mainnet-prune": -12345678,
 *     "testnet-prune": -65536
 *   },
 *   "minLogLevel": "INFO",
 *   "maxConsoleLines": 1000
 * }
 * }</pre>
 * </p>
 * <p>
 * Thread-safe: All public methods are synchronized. Uses Gson for JSON I/O
 * following the same pattern as {@code AppearanceProfile} / gui-settings.json.
 * </p>
 *
 * @see ConsoleColorScheme
 * @see SystemConsoleSubscriber
 */
public final class ConsoleSettings {

    // ── Custom color overrides: profileName -> RGB int ───────────────────
    private final Map<String, Integer> customColors = new HashMap<>();

    // ── Default minimum log level filter (null = accept all) ─────────────
    private String minLogLevel;

    /** Default maximum console line count to prevent unbounded memory growth */
    public static final int DEFAULT_MAX_LINES = 1000;

    // ── Maximum console line count before trimming ────────────────────────
    private int maxConsoleLines = DEFAULT_MAX_LINES;

    // ── Constructors ─────────────────────────────────────────────────────

    /** Creates an empty ConsoleSettings with defaults. */
    public ConsoleSettings() {
    }

    // ── Custom Colors ────────────────────────────────────────────────────

    /**
     * Sets a custom color override for the given profile.
     *
     * @param profileName the profile identifier (never null)
     * @param color       the color to assign (never null)
     */
    public synchronized void setCustomColor(String profileName, Color color) {
        Objects.requireNonNull(profileName, "Profile name must not be null");
        Objects.requireNonNull(color, "Color must not be null");
        customColors.put(profileName, color.getRGB());
    }

    /**
     * Gets the custom color override for the given profile.
     *
     * @param profileName the profile identifier (never null)
     * @return the Color if a custom override exists, null otherwise
     */
    public synchronized Color getCustomColor(String profileName) {
        Objects.requireNonNull(profileName, "Profile name must not be null");
        Integer rgb = customColors.get(profileName);
        return rgb != null ? new Color(rgb) : null;
    }

    /**
     * Removes the custom color override for the given profile.
     *
     * @param profileName the profile identifier (never null)
     * @return true if an override was removed
     */
    public synchronized boolean removeCustomColor(String profileName) {
        Objects.requireNonNull(profileName, "Profile name must not be null");
        return customColors.remove(profileName) != null;
    }

    /**
     * Returns all custom color overrides as an unmodifiable map.
     *
     * @return profileName -> RGB int mapping (never null)
     */
    public synchronized Map<String, Integer> getCustomColorMap() {
        return Map.copyOf(customColors);
    }

    /**
     * Clears all custom color overrides.
     */
    public synchronized void clearCustomColors() {
        customColors.clear();
    }

    // ── Minimum Log Level ────────────────────────────────────────────────

    /**
     * Sets the default minimum log level for new console subscribers.
     *
     * @param level the level name (e.g., "TRACE", "DEBUG", "INFO", "WARN", "ERROR")
     *              or null to accept all levels
     */
    public synchronized void setMinLogLevel(String level) {
        this.minLogLevel = level; // Validated on export/apply
    }

    /** @return the minimum log level name, or null if all levels accepted */
    public synchronized String getMinLogLevel() {
        return minLogLevel;
    }

    // ── Max Console Lines ────────────────────────────────────────────────

    /**
     * Sets the maximum console line count before trimming oldest lines.
     *
     * @param lines a positive integer
     * @throws IllegalArgumentException if lines <= 0
     */
    public synchronized void setMaxConsoleLines(int lines) {
        if (lines <= 0) {
            throw new IllegalArgumentException("maxConsoleLines must be positive, got: " + lines);
        }
        this.maxConsoleLines = lines;
    }

    /** @return the maximum console line count */
    public synchronized int getMaxConsoleLines() {
        return maxConsoleLines;
    }

    // ── ConsoleColorScheme Integration ──────────────────────────────────

    /**
     * Applies all stored custom color overrides to the given ConsoleColorScheme.
     * Convenience method for restoring persisted settings at startup.
     *
     * @param scheme the target color scheme (never null)
     */
    public void applyTo(ConsoleColorScheme scheme) {
        Objects.requireNonNull(scheme, "ConsoleColorScheme must not be null");
        Map<String, Integer> snapshot;
        synchronized (this) {
            snapshot = new HashMap<>(customColors);
        }
        for (Map.Entry<String, Integer> entry : snapshot.entrySet()) {
            scheme.setCustomColor(entry.getKey(), new Color(entry.getValue()));
        }
    }

    /**
     * Syncs this ConsoleSettings from an existing ConsoleColorScheme,
     * capturing its current custom color overrides.
     *
     * @param scheme the source color scheme (never null)
     */
    public void syncFrom(ConsoleColorScheme scheme) {
        Objects.requireNonNull(scheme, "ConsoleColorScheme must not be null");
        synchronized (this) {
            this.customColors.clear();
            this.customColors.putAll(scheme.exportCustomColors());
        }
    }

    // ── JSON Persistence ─────────────────────────────────────────────────

    /**
     * Serializes this ConsoleSettings to a formatted JSON string.
     *
     * @return the JSON representation (never null)
     */
    public String toJson() {
        JsonObject root = new JsonObject();

        synchronized (this) {
            JsonObject colorsObj = new JsonObject();
            for (Map.Entry<String, Integer> entry : customColors.entrySet()) {
                colorsObj.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("customColors", colorsObj);

            if (minLogLevel != null) {
                root.addProperty("minLogLevel", minLogLevel);
            }
            root.addProperty("maxConsoleLines", maxConsoleLines);
        }

        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    /**
     * Parses a ConsoleSettings from a JSON string.
     *
     * @param json the JSON string (never null)
     * @return a new ConsoleSettings instance
     */
    public static ConsoleSettings fromJson(String json) {
        Objects.requireNonNull(json, "JSON must not be null");
        ConsoleSettings settings = new ConsoleSettings();

        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            // customColors
            if (root.has("customColors") && root.get("customColors").isJsonObject()) {
                JsonObject colorsObj = root.getAsJsonObject("customColors");
                for (var entry : colorsObj.entrySet()) {
                    settings.customColors.put(entry.getKey(), entry.getValue().getAsInt());
                }
            }

            // minLogLevel
            if (root.has("minLogLevel") && !root.get("minLogLevel").isJsonNull()) {
                settings.minLogLevel = root.get("minLogLevel").getAsString();
            }

            // maxConsoleLines
            if (root.has("maxConsoleLines") && root.get("maxConsoleLines").isJsonPrimitive()) {
                int lines = root.get("maxConsoleLines").getAsInt();
                if (lines > 0) {
                    settings.maxConsoleLines = lines;
                }
            }
        } catch (Exception e) {
            // Return defaults on parse failure
            System.err.println("[ConsoleSettings] Failed to parse JSON: " + e.getMessage());
        }

        return settings;
    }

    // ── File I/O ─────────────────────────────────────────────────────────

    /**
     * Saves this ConsoleSettings to the specified file path.
     * Creates parent directories if they do not exist.
     *
     * @param path the file path to write to (never null)
     * @throws IOException if an I/O error occurs
     */
    public void save(Path path) throws IOException {
        Objects.requireNonNull(path, "Path must not be null");
        if (!Files.exists(path.getParent())) {
            Files.createDirectories(path.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(toJson());
        }
    }

    /**
     * Loads ConsoleSettings from the specified file path.
     * Returns a default (empty) settings instance if the file does not exist.
     *
     * @param path the file path to read from (never null)
     * @return the loaded ConsoleSettings (never null)
     */
    public static ConsoleSettings load(Path path) {
        Objects.requireNonNull(path, "Path must not be null");
        if (!Files.exists(path)) {
            return new ConsoleSettings();
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            reader.lines().forEach(line -> sb.append(line).append('\n'));
            return fromJson(sb.toString());
        } catch (IOException e) {
            System.err.println("[ConsoleSettings] Failed to load from " + path + ": " + e.getMessage());
            return new ConsoleSettings();
        }
    }

    // ── Equality & toString ──────────────────────────────────────────────

    @Override
    public synchronized boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConsoleSettings that = (ConsoleSettings) o;
        return maxConsoleLines == that.maxConsoleLines
                && Objects.equals(customColors, that.customColors)
                && Objects.equals(minLogLevel, that.minLogLevel);
    }

    @Override
    public synchronized int hashCode() {
        return Objects.hash(customColors, minLogLevel, maxConsoleLines);
    }

    @Override
    public synchronized String toString() {
        return "ConsoleSettings{" +
                "colors=" + customColors.size() +
                ", minLevel=" + minLogLevel +
                ", maxLines=" + maxConsoleLines +
                '}';
    }
}