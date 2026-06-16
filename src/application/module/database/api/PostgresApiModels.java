package application.module.database.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON model builders for PostgreSQL API responses.
 * Mirrors the structure of MariaDbApiModels for consistency across database
 * engines.
 */
public class PostgresApiModels {

    private PostgresApiModels() {
        // Utility class
    }

    // -------------------- Version Models (mirrors MariaDbApiModels)
    // --------------------

    /**
     * Represents a major PostgreSQL version from GitHub releases API.
     */
    public static class MainVersionInfo {
        public String name;
        public List<SubVersionInfo> subVersions = new ArrayList<>();
    }

    /**
     * Represents a minor/sub version under a major version.
     */
    public static class SubVersionInfo {
        public String name;
        public List<DownloadEntry> downloads = new ArrayList<>();
    }

    /**
     * Represents a downloadable artifact for a specific OS/arch combination.
     */
    public static class DownloadEntry {
        public String os;
        public String arch;
        public String file;
    }

    /**
     * Parses GitHub releases JSON array into structured version info.
     * Expected tag format: major.minor.patch (e.g., "17.6.0")
     */
    public static List<MainVersionInfo> parseReleases(JsonArray releasesJson, String currentOsName,
            String currentArch) {
        List<MainVersionInfo> result = new ArrayList<>();
        Map<String, MainVersionInfo> majorMap = new HashMap<>();

        for (com.google.gson.JsonElement elem : releasesJson) {
            if (!elem.isJsonObject())
                continue;
            JsonObject obj = elem.getAsJsonObject();
            String tag = obj.has("tag_name") ? obj.get("tag_name").getAsString() : null;
            if (tag == null)
                continue;

            String[] parts = tag.split("\\.");
            if (parts.length < 3)
                continue;

            String major = parts[0];
            String minor = parts[1];
            // String patch = parts[2]; // not needed for UI combo boxes

            MainVersionInfo majorInfo = majorMap.computeIfAbsent(major, k -> {
                MainVersionInfo info = new MainVersionInfo();
                info.name = k;
                result.add(info);
                return info;
            });

            SubVersionInfo minorInfo = majorInfo.subVersions.stream()
                    .filter(s -> s.name.equals(minor))
                    .findFirst().orElse(null);
            if (minorInfo == null) {
                minorInfo = new SubVersionInfo();
                minorInfo.name = minor;
                majorInfo.subVersions.add(minorInfo);
            }

            // Parse assets for download entries
            if (obj.has("assets") && obj.get("assets").isJsonArray()) {
                for (com.google.gson.JsonElement asset : obj.getAsJsonArray("assets")) {
                    if (!asset.isJsonObject())
                        continue;
                    JsonObject a = asset.getAsJsonObject();
                    String name = a.has("name") ? a.get("name").getAsString() : null;
                    if (name == null)
                        continue;

                    DownloadEntry entry = new DownloadEntry();
                    entry.os = currentOsName;
                    entry.arch = currentArch;
                    entry.file = name;
                    minorInfo.downloads.add(entry);
                }
            }
        }
        return result;
    }

    /**
     * Creates an error response for PostgreSQL API errors.
     */

    // -------------------- Status --------------------

    public static JsonObject statusResponse(boolean running, boolean initialized, boolean configured,
            String version, String dataDirectory, String error) {
        JsonObject json = new JsonObject();
        json.addProperty("running", running);
        json.addProperty("initialized", initialized);
        json.addProperty("configured", configured);
        json.addProperty("version", version != null ? version : "");
        json.addProperty("dataDirectory", dataDirectory != null ? dataDirectory : "");
        if (error != null) {
            json.addProperty("error", error);
        }
        return json;
    }

    // -------------------- Process --------------------

    public static JsonObject processResponse(int pid, String state, long cpuPercent, long memoryPercent) {
        JsonObject json = new JsonObject();
        json.addProperty("pid", pid);
        json.addProperty("state", state != null ? state : "");
        json.addProperty("cpuPercent", cpuPercent);
        json.addProperty("memoryPercent", memoryPercent);
        return json;
    }

    // -------------------- Database List --------------------

    public static JsonArray databaseListResponse(java.util.List<DatabaseInfo> databases) {
        JsonArray array = new JsonArray();
        for (DatabaseInfo db : databases) {
            JsonObject json = new JsonObject();
            json.addProperty("name", db.name());
            json.addProperty("owner", db.owner());
            json.addProperty("size", db.size());
            json.addProperty("encoding", db.encoding());
            array.add(json);
        }
        return array;
    }

    // -------------------- Table List --------------------

    public static JsonArray tableListResponse(java.util.List<TableInfo> tables) {
        JsonArray array = new JsonArray();
        for (TableInfo table : tables) {
            JsonObject json = new JsonObject();
            json.addProperty("schema", table.schema());
            json.addProperty("name", table.name());
            json.addProperty("rowCount", table.rowCount());
            json.addProperty("size", table.size());
            array.add(json);
        }
        return array;
    }

    // -------------------- User List --------------------

    public static JsonArray userListResponse(java.util.List<UserInfo> users) {
        JsonArray array = new JsonArray();
        for (UserInfo user : users) {
            JsonObject json = new JsonObject();
            json.addProperty("name", user.name());
            json.addProperty("superuser", user.superuser());
            json.addProperty("canCreateDB", user.canCreateDb());
            json.addProperty("canCreateRole", user.canCreateRole());
            array.add(json);
        }
        return array;
    }

    // -------------------- Extension List --------------------

    public static JsonArray extensionListResponse(java.util.List<ExtensionInfo> extensions) {
        JsonArray array = new JsonArray();
        for (ExtensionInfo ext : extensions) {
            JsonObject json = new JsonObject();
            json.addProperty("name", ext.name());
            json.addProperty("version", ext.version());
            json.addProperty("schema", ext.schema());
            array.add(json);
        }
        return array;
    }

    // -------------------- Error --------------------

    public static JsonObject errorResponse(String message, int code) {
        JsonObject json = new JsonObject();
        json.addProperty("error", message != null ? message : "Unknown error");
        json.addProperty("code", code);
        return json;
    }

    public static JsonObject errorResponse(String message, Throwable cause) {
        return errorResponse(cause != null ? cause.getMessage() : message, 500);
    }

    // -------------------- Info Records --------------------

    public record DatabaseInfo(String name, String owner, long size, String encoding) {
    }

    public record TableInfo(String schema, String name, long rowCount, long size) {
    }

    public record UserInfo(String name, boolean superuser, boolean canCreateDb, boolean canCreateRole) {
    }

    public record ExtensionInfo(String name, String version, String schema) {
    }
}