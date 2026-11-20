package application.module.database.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import application.module.database.gui.DatabaseConfigurationPanel;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.SqliteDatabasePaths;
import application.module.node.props.Props;
import application.utils.io.PathUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite profile implementation for database management.
 * <p>
 * A database profile stores its <b>engine settings only</b>
 * ({@code DB.SqliteJournalMode}, {@code DB.SqliteCacheSize}) in its
 * {@code profile.json}. The database URL is a <b>derived</b> value
 * ({@link #derivedDbUrl()}): the profile is file-based by definition — its
 * directory ({@code ./database/SQLite/<name>}) holds the {@code *.db} files,
 * the filesystem is the source of truth for that list, and the first file (the
 * conventional {@code signum.sqlite.db} when the directory holds none yet) is
 * the active one. The <b>node profile's {@code DB.Url} property is the SSOT of
 * the database assignment</b> (see
 * {@link SqliteDatabasePaths#assignmentProfileName(String)}). A legacy
 * {@code DB.Url} key in an older {@code profile.json} is ignored — no
 * migration path exists, the URL is derived — and is dropped on the next save.
 * </p>
 */
public class SQLiteProfile {
    private static final Logger logger = LoggerFactory.getLogger(SQLiteProfile.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Database-URL key (the GUI property key of the panel's "Database File"
     * row). No longer stored in {@code profile.json}: the URL is derived (see
     * the class documentation). A legacy key in an older profile JSON is
     * ignored on load and dropped on the next save.
     */
    public static final String CFG_URL = "DB.Url";
    public static final String CFG_JOURNAL_MODE = "DB.SqliteJournalMode";
    public static final String CFG_CACHE_SIZE = "DB.SqliteCacheSize";

    /** The hardcoded default values for each configurable SQLite property (L0 baseline). */
    public static final Map<String, String> DEFAULTS = Map.of(
            CFG_JOURNAL_MODE, "WAL",
            CFG_CACHE_SIZE, "-131072");

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
        // Engine settings only — the database URL is derived (see the class
        // documentation) and is NOT part of the stored configuration.
        configuration.put(CFG_JOURNAL_MODE, "WAL");
        configuration.put(CFG_CACHE_SIZE, "-131072");
    }

    public SQLiteProfile(String profileName, JsonObject json) {
        this(profileName);
        if (json != null) {
            for (Map.Entry<String, com.google.gson.JsonElement> entry : json.entrySet()) {
                // A legacy stored URL is ignored (the URL is derived — see the
                // class documentation); it is dropped again on the next save.
                if (!CFG_URL.equals(entry.getKey()) && entry.getValue().isJsonPrimitive()) {
                    configuration.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
    }

    /**
     * The profile's JSON form: its engine settings only. A legacy stored
     * {@code DB.Url} key in an older file is intentionally dropped (the URL is
     * derived — see the class documentation).
     */
    public JsonObject toJsonObject() {
        JsonObject json = new JsonObject();
        configuration.forEach((key, value) -> {
            if (!CFG_URL.equals(key)) {
                json.addProperty(key, value);
            }
        });
        return json;
    }

    public void saveToProfileJson() throws IOException {
        if (profileRoot == null)
            return;
        Files.createDirectories(profileRoot);
        Path profileJson = profileRoot.resolve("profile.json");
        try (BufferedWriter writer = Files.newBufferedWriter(profileJson, StandardCharsets.UTF_8)) {
            writer.write(GSON.toJson(toJsonObject()));
        }
    }

    /**
     * Ensures the directory the profile's active database file lives in exists:
     * the profile's own {@code ./database/SQLite/<name>} directory (the URL is
     * derived — see the class documentation).
     *
     * @throws IOException when the directory cannot be created
     */
    public void ensureDatabaseDirectory() throws IOException {
        SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(getDbUrl());
        if (parts == null) {
            return;
        }
        Path dir = PathUtils.resolvePath(parts.dir().toString());
        Files.createDirectories(dir);
        logger.info("Ensured SQLite database directory exists: {}", dir);
    }

    public String getProfileName() {
        return profileName;
    }

    public Path getProfileRoot() {
        return profileRoot;
    }

    public Map<String, String> getConfiguration() {
        return configuration;
    }

    /**
     * The database URL of this profile — the {@link #derivedDbUrl()} (the
     * convention directory + the active file). A legacy stored {@code DB.Url}
     * in an older profile JSON is ignored.
     */
    public String getDbUrl() {
        return derivedDbUrl();
    }

    /**
     * The derived database URL of this profile: the canonical file-based URL of
     * the profile's own directory pointing at its active database file
     * ({@code jdbc:sqlite:file:./database/SQLite/<name>/<activeFile>}). Always
     * in the conventional relative form — the string comparisons elsewhere
     * (e.g. {@code ProfileCreateDefaults.isPerProfileSqliteUrl}) rely on it.
     *
     * @return the derived URL, or {@code null} for an unnamed profile
     */
    public String derivedDbUrl() {
        if (profileRoot == null) {
            return null;
        }
        return SqliteDatabasePaths.composeUrl(
                SqliteDatabasePaths.profileDir(getProfileName()), activeDatabaseFile());
    }

    /**
     * The active database file name (with {@code .db}) of this profile: the
     * first {@code *.db} file discovered in the profile's own directory (the
     * case-insensitive discovery shared with the file list editor), or the
     * conventional {@link SqliteDatabasePaths#DEFAULT_DATABASE_FILE_NAME} when
     * the directory holds no database file yet (the database is created
     * lazily, on save / first run).
     */
    public String activeDatabaseFile() {
        if (profileRoot == null) {
            return SqliteDatabasePaths.DEFAULT_DATABASE_FILE_NAME;
        }
        List<String> files = SqliteDatabasePaths.discoverDbFiles(profileRoot);
        return files.isEmpty() ? SqliteDatabasePaths.DEFAULT_DATABASE_FILE_NAME : files.get(0);
    }

    /**
     * The database-profile name a node profile is assigned to, derived LIVE from
     * the node profile's own {@code DB.Url} property (the SSOT of the database
     * assignment — there is no separate assignment store, and nothing is read
     * from or written to a database profile's JSON).
     *
     * @param nodeProfile the loaded node profile (may be null)
     * @return the database-profile name when the node profile's {@code DB.Url}
     *         points at a {@code *.db} file of a convention
     *         {@code ./database/SQLite/<name>} directory, otherwise
     *         {@code null} (no URL — the node runs its default database — or a
     *         non-convention / custom URL)
     */
    public static String assignedDatabaseProfile(NodeProfile nodeProfile) {
        if (nodeProfile == null || nodeProfile.getProperties() == null) {
            return null;
        }
        return SqliteDatabasePaths.assignmentProfileName(
                nodeProfile.getProperties().getProperty(Props.DB_URL.getName()));
    }

    public void loadFromDisk() {
        if (profileRoot == null)
            return;
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