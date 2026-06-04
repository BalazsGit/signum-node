package brs.gui.configuration.databaseConfiguration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import brs.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQLite profile implementation for database management.
 */
public class SQLiteProfile {
    private static final Logger logger = LoggerFactory.getLogger(SQLiteProfile.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final String CFG_URL = "DB.Url";
    public static final String CFG_JOURNAL_MODE = "DB.SqliteJournalMode";
    public static final String CFG_CACHE_SIZE = "DB.SqliteCacheSize";

    private String profileName;
    private Path profileRoot;
    private final Map<String, String> configuration = new LinkedHashMap<>();

    public SQLiteProfile(String profileName) {
        this.profileName = profileName;
        this.profileRoot = (profileName != null && !profileName.trim().isEmpty())
                ? PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                        .resolve(DatabaseConfigurationPanel.DatabaseEngine.SQLITE.toString()).resolve(profileName)
                : null;
        initDefaultConfiguration();
    }

    private void initDefaultConfiguration() {
        configuration.put(CFG_URL, "jdbc:sqlite:file:./db/signum.sqlite.db");
        configuration.put(CFG_JOURNAL_MODE, "WAL");
        configuration.put(CFG_CACHE_SIZE, "-131072");
    }

    public SQLiteProfile(String profileName, JsonObject json) {
        this(profileName);
        if (json != null) {
            for (Map.Entry<String, com.google.gson.JsonElement> entry : json.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    configuration.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
    }

    public JsonObject toJsonObject() {
        JsonObject json = new JsonObject();
        configuration.forEach(json::addProperty);
        return json;
    }

    public void saveToProfileJson() throws IOException {
        if (profileRoot == null) return;
        Files.createDirectories(profileRoot);
        Path profileJson = profileRoot.resolve("profile.json");
        try (BufferedWriter writer = Files.newBufferedWriter(profileJson, StandardCharsets.UTF_8)) {
            writer.write(GSON.toJson(toJsonObject()));
        }
    }

    public void ensureDatabaseDirectory() throws IOException {
        String url = configuration.get(CFG_URL);
        if (url != null && url.startsWith("jdbc:sqlite:")) {
            String pathStr = url.substring("jdbc:sqlite:".length());
            if (pathStr.startsWith("file:")) {
                pathStr = pathStr.substring(5);
            }
            // Paraméterek levágása (pl. ?cache=shared)
            int paramIdx = pathStr.indexOf('?');
            if (paramIdx != -1) {
                pathStr = pathStr.substring(0, paramIdx);
            }
            
            Path dbFile = PathUtils.resolvePath(pathStr);
            if (dbFile.getParent() != null) {
                Files.createDirectories(dbFile.getParent());
                logger.info("Ensured SQLite database directory exists: {}", dbFile.getParent());
            }
        }
    }

    public String getProfileName() { return profileName; }
    public Path getProfileRoot() { return profileRoot; }
    public Map<String, String> getConfiguration() { return configuration; }
    
    public String getDbUrl() { return configuration.get(CFG_URL); }

    public void loadFromDisk() {
        if (profileRoot == null) return;
        Path jsonFile = profileRoot.resolve("profile.json");
        if (Files.exists(jsonFile)) {
            try (BufferedReader reader = Files.newBufferedReader(jsonFile, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                json.entrySet().forEach(e -> configuration.put(e.getKey(), e.getValue().getAsString()));
            } catch (Exception e) {
                logger.error("Error loading SQLite profile", e);
            }
        }
    }
}