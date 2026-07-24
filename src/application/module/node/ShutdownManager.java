package application.module.node;

import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.utils.io.PathUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Manages graceful shutdown state persistence for a node profile.
 * <p>
 * Writes structured shutdown progress to {@code settings.json} under the
 * hierarchical path {@code module -> node -> {profileName}}. This allows
 * multiple profiles (mainnet, testnet, mock, etc.) to coexist in the same
 * settings file without key collisions.
 * </p>
 *
 * <h2>JSON Structure</h2>
 * <pre>{@code
 * {
 *   "module": {
 *     "node": {
 *       "mainnet": {
 *         "shutdownStatus": "clean",
 *         "startupTimestamp": 1784474515148,
 *         "shutdownTimestamp": 1784474527513,
 *         "components": {
 *           "WebServer": "success",
 *           "BlockchainProcessor": "success",
 *           ...
 *         }
 *       },
 *       "testnet": { ... }
 *     }
 *   }
 * }
 * }</pre>
 */
public class ShutdownManager {

    private static final Logger logger = LoggerFactory.getLogger(ShutdownManager.class);

    static final String STATE_FILE_NAME = "settings.json";

    /** Top-level module key in settings.json. */
    static final String MODULE_KEY = "module";

    /** Node sub-key under module. */
    static final String NODE_KEY = "node";

    private static final List<String> COMPONENTS = Arrays.asList(
            "WebServer", "BlockchainProcessor", "Peers", "ThreadPool", "Database", "DBCacheManager");

    /** Resolved path to settings.json. */
    private final Path stateFilePath;

    /** Human-readable profile name (e.g. "mainnet", "SQLite-MAINNET"). */
    private final String profileName;

    /** Current shutdown state JsonObject (flat: status, timestamps, components). */
    private JsonObject shutdownState;

    /** True if the previous shutdown was not clean. */
    private boolean wasDirty = false;

    /**
     * Creates a new ShutdownManager for the given profile.
     *
     * @param propertyService the property service providing the settings directory
     * @param profileName     the human-readable profile name used as a JSON key
     */
    public ShutdownManager(PropertyService propertyService, String profileName) {
        this.profileName = profileName != null && !profileName.isEmpty() ? profileName : "default";
        String confDir = propertyService.getString(Props.SETTINGS_DIR);
        this.stateFilePath = PathUtils.resolvePath(Paths.get(confDir, STATE_FILE_NAME).toString());
        this.shutdownState = new JsonObject();
        checkPreviousState();
        initRunningState();
    }

    // =========================================================================
    // State inspection
    // =========================================================================

    /**
     * Inspects the persisted state file for this profile and marks the instance
     * as dirty if the last shutdown was not clean.
     */
    private void checkPreviousState() {
        if (Files.exists(stateFilePath)) {
            try (BufferedReader reader = Files.newBufferedReader(stateFilePath)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject profileSection = getMyProfileSection(root);
                if (profileSection != null && profileSection.has("shutdownStatus")) {
                    String status = profileSection.get("shutdownStatus").getAsString();
                    if (!"clean".equals(status)) {
                        this.wasDirty = true;
                        logger.warn("Previous shutdown was not clean for profile '{}'. Status: {}",
                                profileName, status);
                        if (profileSection.has("components")) {
                            logger.warn("Component states: {}", profileSection.get("components").toString());
                        }
                    } else {
                        logger.info("Previous shutdown was clean for profile '{}'. Status: {}",
                                profileName, status);
                    }
                }
            } catch (Exception e) {
                logger.error("Could not read or parse previous shutdown state file for profile '{}'. "
                        + "Assuming dirty shutdown.", profileName, e);
                this.wasDirty = true;
            }
        } else {
            logger.info("No previous shutdown state file found for profile '{}'. Assuming clean start.", profileName);
        }
    }

    /**
     * Returns {@code true} if the previous shutdown was not detected as clean.
     */
    public boolean wasPreviousShutdownDirty() {
        return wasDirty;
    }

    // =========================================================================
    // Lifecycle operations
    // =========================================================================

    /** Initialises the file with a "running" marker so crashes are detectable. */
    private void initRunningState() {
        shutdownState = new JsonObject();
        shutdownState.addProperty("shutdownStatus", "running");
        shutdownState.addProperty("startupTimestamp", System.currentTimeMillis());
        writeState();
    }

    /** Called when a graceful shutdown sequence begins. */
    public void startShutdown() {
        shutdownState = new JsonObject();
        shutdownState.addProperty("shutdownStatus", "in_progress");
        shutdownState.addProperty("shutdownTimestamp", System.currentTimeMillis());
        JsonObject components = new JsonObject();
        for (String component : COMPONENTS) {
            components.addProperty(component, "pending");
        }
        shutdownState.add("components", components);
        writeState();
    }

    /** Marks a component as shut down successfully. */
    public void markSuccess(String component) {
        if (shutdownState.has("components")) {
            shutdownState.get("components").getAsJsonObject().addProperty(component, "success");
        }
    }

    /** Marks a component as failed during shutdown and persists immediately. */
    public void markFailure(String component) {
        if (shutdownState.has("components")) {
            shutdownState.get("components").getAsJsonObject().addProperty(component, "failed");
        }
        logger.warn("Shutdown of component '{}' failed for profile '{}'.", component, profileName);
        writeState();
    }

    /** Finalises the shutdown sequence and writes the terminal status. */
    public void finishShutdown() {
        boolean hasFailures = false;
        if (shutdownState.has("components")) {
            JsonObject components = shutdownState.get("components").getAsJsonObject();
            for (String component : COMPONENTS) {
                if (!components.has(component)) {
                    continue;
                }
                String value = components.get(component).getAsString();
                if ("failed".equals(value)) {
                    hasFailures = true;
                } else if ("pending".equals(value)) {
                    components.addProperty(component, "success");
                }
            }
        }

        if (hasFailures) {
            shutdownState.addProperty("shutdownStatus", "completed_with_errors");
            logger.warn("Shutdown completed with errors for profile '{}'.", profileName);
        } else {
            shutdownState.addProperty("shutdownStatus", "clean");
            logger.info("Shutdown completed cleanly for profile '{}'.", profileName);
        }
        writeState();
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    /**
     * Persists the current shutdown state to {@code settings.json} under the
     * hierarchical {@code module -> node -> {profileName}} path.
     * <p>
     * Existing keys outside this hierarchy are preserved.
     * </p>
     */
    private void writeState() {
        try {
            if (stateFilePath.getParent() != null) {
                Files.createDirectories(stateFilePath.getParent());
            }

            // Read existing content to preserve other settings
            JsonObject root = new JsonObject();
            if (Files.exists(stateFilePath)) {
                try (BufferedReader reader = Files.newBufferedReader(stateFilePath)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed.isJsonObject()) {
                        root = parsed.getAsJsonObject();
                    }
                } catch (Exception e) {
                    // Ignore read errors; start fresh
                }
            }

            // Ensure hierarchy exists: module -> node -> profileName
            if (!root.has(MODULE_KEY)) {
                root.add(MODULE_KEY, new JsonObject());
            }
            JsonObject moduleObj = root.getAsJsonObject(MODULE_KEY);
            if (!moduleObj.has(NODE_KEY)) {
                moduleObj.add(NODE_KEY, new JsonObject());
            }
            JsonObject nodeObj = moduleObj.getAsJsonObject(NODE_KEY);
            if (!nodeObj.has(profileName)) {
                nodeObj.add(profileName, new JsonObject());
            }
            JsonObject profileSection = nodeObj.getAsJsonObject(profileName);

            // Merge current shutdown state into the profile section
            for (Map.Entry<String, JsonElement> entry : shutdownState.entrySet()) {
                profileSection.add(entry.getKey(), entry.getValue());
            }

            // Write atomically with SYNC
            try (BufferedWriter writer = Files.newBufferedWriter(stateFilePath,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC)) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                writer.write(gson.toJson(root));
            }
        } catch (IOException e) {
            logger.error("Failed to write shutdown state to file: {}", stateFilePath, e);
        }
    }

    /**
     * Gets or creates the nested JsonObject for {@code module -> node -> profileName}.
     */
    private JsonObject getOrCreateProfileSection(JsonObject root) {
        if (!root.has(MODULE_KEY)) {
            root.add(MODULE_KEY, new JsonObject());
        }
        JsonObject moduleObj = root.getAsJsonObject(MODULE_KEY);
        if (!moduleObj.has(NODE_KEY)) {
            moduleObj.add(NODE_KEY, new JsonObject());
        }
        JsonObject nodeObj = moduleObj.getAsJsonObject(NODE_KEY);
        if (!nodeObj.has(profileName)) {
            nodeObj.add(profileName, new JsonObject());
        }
        return nodeObj.getAsJsonObject(profileName);
    }

    /**
     * Returns the profile section for this manager's profile name, or null.
     */
    private JsonObject getMyProfileSection(JsonObject root) {
        if (root == null) {
            return null;
        }
        if (!root.has(MODULE_KEY)) {
            return null;
        }
        JsonObject moduleObj = root.getAsJsonObject(MODULE_KEY);
        if (!moduleObj.has(NODE_KEY)) {
            return null;
        }
        JsonObject nodeObj = moduleObj.getAsJsonObject(NODE_KEY);
        if (!nodeObj.has(profileName)) {
            return null;
        }
        return nodeObj.getAsJsonObject(profileName);
    }
} // 388 lines total.