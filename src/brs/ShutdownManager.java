package brs;

import brs.props.PropertyService;
import brs.props.Props;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
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
import brs.util.PathUtils;
import java.util.Map;

public class ShutdownManager {

    private static final Logger logger = LoggerFactory.getLogger(ShutdownManager.class);
    private static final String STATE_FILE_NAME = "settings.json";
    private static final List<String> COMPONENTS = Arrays.asList(
            "WebServer", "BlockchainProcessor", "Peers", "ThreadPool", "Database", "DBCacheManager");

    private final Path stateFilePath;
    private JsonObject shutdownState;
    private boolean wasDirty = false;

    public ShutdownManager(PropertyService propertyService) {
        String confDir = propertyService.getString(Props.SETTINGS_DIR);
        this.stateFilePath = PathUtils.resolvePath(Paths.get(confDir, STATE_FILE_NAME).toString());
        this.shutdownState = new JsonObject();
        checkPreviousState();
        initRunningState();
    }

    private void checkPreviousState() {
        if (Files.exists(stateFilePath)) {
            try (BufferedReader reader = Files.newBufferedReader(stateFilePath)) {
                JsonObject previousState = JsonParser.parseReader(reader).getAsJsonObject();
                if (previousState.has("shutdownStatus")
                        && !"clean".equals(previousState.get("shutdownStatus").getAsString())) {
                    this.wasDirty = true;
                    logger.warn("Previous shutdown was not clean. Status: {}",
                            previousState.get("shutdownStatus").getAsString());
                    if (previousState.has("components")) {
                        logger.warn("Component states: {}", previousState.get("components").toString());
                    }
                }
            } catch (Exception e) {
                logger.error("Could not read or parse previous shutdown state file. Assuming dirty shutdown.", e);
                this.wasDirty = true;
            }
        }
        // If file doesn't exist, we assume it was a clean shutdown or first run.
    }

    public boolean wasPreviousShutdownDirty() {
        return wasDirty;
    }

    private void initRunningState() {
        shutdownState = new JsonObject();
        shutdownState.addProperty("shutdownStatus", "running");
        shutdownState.addProperty("startupTimestamp", System.currentTimeMillis());
        // We initialize the file immediately to "running" state.
        // If the node crashes anytime after this point, it will be detected as a dirty
        // shutdown.
        writeState();
    }

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

    public void markSuccess(String component) {
        if (shutdownState.has("components")) {
            shutdownState.get("components").getAsJsonObject().addProperty(component, "success");
        }
        // No need to write on every success, only on failure or completion.
    }

    public void markFailure(String component) {
        if (shutdownState.has("components")) {
            shutdownState.get("components").getAsJsonObject().addProperty(component, "failed");
        }
        logger.warn("Shutdown of component '{}' failed.", component);
        writeState(); // Write immediately on failure to capture the state
    }

    public void finishShutdown() {
        boolean hasFailures = false;
        // Mark any remaining pending components as implicitly successful if we reached
        // the end
        if (shutdownState.has("components")) {
            JsonObject components = shutdownState.get("components").getAsJsonObject();
            for (String component : COMPONENTS) {
                if ("failed".equals(components.get(component).getAsString())) {
                    hasFailures = true;
                } else if ("pending".equals(components.get(component).getAsString())) {
                    components.addProperty(component, "success");
                }
            }
        }

        if (hasFailures) {
            shutdownState.addProperty("shutdownStatus", "completed_with_errors");
            logger.warn("Shutdown completed with errors.");
        } else {
            shutdownState.addProperty("shutdownStatus", "clean");
            logger.info("Shutdown completed cleanly.");
        }
        writeState();
    }

    private void writeState() {
        try {
            if (stateFilePath.getParent() != null) {
                Files.createDirectories(stateFilePath.getParent());
            }

            // Read existing content first to preserve other settings (like trimHeight)
            JsonObject jsonToWrite = new JsonObject();
            if (Files.exists(stateFilePath)) {
                try (BufferedReader reader = Files.newBufferedReader(stateFilePath)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed.isJsonObject()) {
                        jsonToWrite = parsed.getAsJsonObject();
                    }
                } catch (Exception e) {
                    // Ignore read errors, we will overwrite/merge what we can
                }
            }

            // Merge current shutdown state into the JSON
            for (Map.Entry<String, JsonElement> entry : shutdownState.entrySet()) {
                jsonToWrite.add(entry.getKey(), entry.getValue());
            }

            // Use SYNC to ensure the data is written to the storage device immediately
            try (BufferedWriter writer = Files.newBufferedWriter(stateFilePath,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC)) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                writer.write(gson.toJson(jsonToWrite));
            }
        } catch (IOException e) {
            logger.error("Failed to write shutdown state to file: {}", stateFilePath, e);
        }
    }
}