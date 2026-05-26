package brs.gui.configuration.databaseConfiguration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import brs.gui.configuration.databaseConfiguration.DatabaseConfigurationUtils.ProgressListener;
import brs.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

public class MariadbProfile {
    private static final Logger logger = LoggerFactory.getLogger(MariadbProfile.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static class DatabaseInfo {
        public String id;
        public String name;
        public String user;
        public String permissions;

        public DatabaseInfo(String id, String name, String user, String permissions) {
            this.id = id;
            this.name = name;
            this.user = user;
            this.permissions = permissions;
        }
    }

    public static class UserGrant {
        public String databaseId; // "global" or specific DB ID
        public String host;
        public String permissions;

        public UserGrant(String databaseId, String host, String permissions) {
            this.databaseId = databaseId;
            this.host = host != null ? host : "localhost";
            this.permissions = permissions;
        }
    }

    public static class UserInfo {
        public String id;
        public String username;
        public String password;
        public List<UserGrant> grants = new ArrayList<>();

        public UserInfo(String id, String username, String password) {
            this.id = id;
            this.username = username;
            this.password = password;
        }
    }

    // Centralized Directory and File Constants
    public static final String BIN_DIR = "bin";
    public static final String DATA_DIR = "data";
    public static final String WIN_CONFIG = "my.ini";
    public static final String LIN_CONFIG = "my.cnf";
    public static final String WIN_INSTALL_EXE = "mysql_install_db.exe";
    public static final String LIN_INSTALL_EXE = "mysql_install_db";
    public static final String WIN_DAEMON_EXE = "mariadbd.exe";
    public static final String LIN_DAEMON_EXE = "mariadbd";

    // Configuration Keys
    public static final String MYSQLD_SECTION = "[mysqld]";
    public static final String CFG_PORT = "port";
    public static final String CFG_DATADIR = "datadir";
    public static final String CFG_LOG_ERROR = "log_error";
    public static final String CFG_PID_FILE = "pid_file";
    public static final String CFG_BIND_ADDRESS = "bind-address";
    public static final String CFG_INNODB_FLUSH = "innodb_flush_log_at_trx_commit";

    // Default Configuration Values
    public static final String DEFAULT_PORT = "3306";
    public static final String DEFAULT_DATA_DIR_RELATIVE = "./data";
    public static final String DEFAULT_ERROR_LOG = "./error.log";
    public static final String DEFAULT_PID_FILE = "./mariadb.pid";
    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    public static final String DEFAULT_INNODB_FLUSH_VAL = "0";

    private String profileName;
    private Path profileRoot;
    private final String currentOsName;
    private String installedVersion;
    private String downloadedVersion;
    private String downloadedOs;
    private String downloadedArch;
    private String binaryFolderName; // Name of the root directory extracted from the ZIP archive
    private boolean step1Completed; // Download & Install
    private boolean step2Completed; // Initialize Database Instance
    private boolean step3Completed; // Configure Database and Users
    private boolean isReady; // Overall readiness
    private String adminUsername;
    private String adminPassword;
    private String appUsername;
    private String appUserPassword;
    private String appUserPermissions;
    private final List<DatabaseInfo> createdDatabases = new ArrayList<>();
    private final List<UserInfo> createdUsers = new ArrayList<>();
    private String configFilePath;

    private final Map<String, String> configuration = new LinkedHashMap<>();
    private final Set<String> keysInFile = new HashSet<>();
    private final Set<String> visibleProperties = new LinkedHashSet<>();

    // Constructor for new profiles
    public MariadbProfile(String profileName) {
        this.profileName = profileName;
        this.profileRoot = (profileName != null && !profileName.trim().isEmpty())
                ? PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                        .resolve(DatabaseConfigurationPanel.DatabaseEngine.MARIADB.toString()).resolve(profileName)
                : null;
        this.currentOsName = DatabaseConfigurationUtils.getOsName();
        this.installedVersion = null;
        this.downloadedVersion = null;
        this.downloadedOs = null;
        this.downloadedArch = null;
        this.binaryFolderName = null;
        this.step1Completed = false;
        this.step2Completed = false;
        this.step3Completed = false;
        this.isReady = false; // Derived state
        this.adminUsername = "root"; // Default admin username
        this.adminPassword = ""; // Default empty password
        this.appUsername = null;
        this.appUserPassword = null;
        this.appUserPermissions = null;
        initDefaultConfiguration();
    }

    private void initDefaultConfiguration() {
        configuration.put(CFG_PORT, DEFAULT_PORT);
        configuration.put(CFG_DATADIR, DEFAULT_DATA_DIR_RELATIVE);
        visibleProperties.add(CFG_PORT);
        visibleProperties.add(CFG_DATADIR);
    }

    // Constructor from JsonObject
    public MariadbProfile(String profileName, JsonObject json) {
        this.profileName = profileName;
        this.profileRoot = (profileName != null && !profileName.trim().isEmpty())
                ? PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                        .resolve(DatabaseConfigurationPanel.DatabaseEngine.MARIADB.toString()).resolve(profileName)
                : null;
        this.currentOsName = DatabaseConfigurationUtils.getOsName();
        this.installedVersion = getString(json, "installedVersion", null);
        this.downloadedVersion = getString(json, "downloadedVersion", null);
        this.downloadedOs = getString(json, "downloadedOs", null);
        this.downloadedArch = getString(json, "downloadedArch", null);
        this.binaryFolderName = getString(json, "binaryFolderName", null);
        this.step1Completed = getBoolean(json, "step1Completed");
        this.step2Completed = getBoolean(json, "step2Completed");
        this.step3Completed = getBoolean(json, "step3Completed");
        this.isReady = getBoolean(json, "isReady"); // Derived state
        this.adminUsername = getString(json, "adminUsername", "root");
        this.adminPassword = getString(json, "adminPassword", "");
        this.appUsername = getString(json, "appUsername", null);
        this.appUserPassword = getString(json, "appUserPassword", null);
        this.appUserPermissions = getString(json, "appUserPermissions", null);
        this.configFilePath = getString(json, "configFilePath", null);

        initDefaultConfiguration();
        // Note: Configuration values and visible properties are no longer read from
        // profile.json.
        // The only source of truth for MariaDB settings is the actual config file
        // (my.ini/my.cnf).
        // If the file doesn't exist, default fields (port, datadir) are used.

        // Try to load/sync values from actual config file if it exists
        loadSettingsFromConfig();

        if (json.has("createdDatabases") && !json.get("createdDatabases").isJsonNull()) {
            JsonArray dbs = json.getAsJsonArray("createdDatabases");
            for (JsonElement el : dbs) {
                JsonObject obj = el.getAsJsonObject();
                createdDatabases.add(new DatabaseInfo(
                        getString(obj, "id", UUID.randomUUID().toString().substring(0, 8)),
                        getString(obj, "name", ""),
                        getString(obj, "user", ""),
                        getString(obj, "permissions", "")));
            }
        }

        if (json.has("createdUsers") && !json.get("createdUsers").isJsonNull()) {
            JsonArray users = json.getAsJsonArray("createdUsers");
            for (JsonElement el : users) {
                JsonObject obj = el.getAsJsonObject();
                UserInfo user = new UserInfo(
                        getString(obj, "id", UUID.randomUUID().toString().substring(0, 8)),
                        getString(obj, "username", ""),
                        getString(obj, "password", ""));
                if (obj.has("grants")) {
                    obj.getAsJsonArray("grants").forEach(g -> {
                        JsonObject gObj = g.getAsJsonObject();
                        user.grants.add(new UserGrant(getString(gObj, "databaseId", "global"),
                                getString(gObj, "host", "localhost"),
                                getString(gObj, "permissions", "ALL")));
                    });
                }
                createdUsers.add(user);
            }
        }
    }

    private static String getString(JsonObject json, String memberName, String defaultValue) {
        return json.has(memberName) && !json.get(memberName).isJsonNull() ? json.get(memberName).getAsString()
                : defaultValue;
    }

    private static boolean getBoolean(JsonObject json, String memberName) {
        return json.has(memberName) && !json.get(memberName).isJsonNull() && json.get(memberName).getAsBoolean();
    }

    // Convert to JsonObject for saving
    public JsonObject toJsonObject() {
        JsonObject json = new JsonObject();
        json.addProperty("step1Completed", step1Completed);
        json.addProperty("step2Completed", step2Completed);
        json.addProperty("step3Completed", step3Completed);
        json.addProperty("isReady", isReady);

        if (step1Completed) {
            if (installedVersion != null)
                json.addProperty("installedVersion", installedVersion);
            if (downloadedVersion != null)
                json.addProperty("downloadedVersion", downloadedVersion);
            if (downloadedOs != null)
                json.addProperty("downloadedOs", downloadedOs);
            if (downloadedArch != null)
                json.addProperty("downloadedArch", downloadedArch);
            if (binaryFolderName != null)
                json.addProperty("binaryFolderName", binaryFolderName);
        }

        if (step2Completed) {
            json.addProperty("adminUsername", getAdminUsername());
            json.addProperty("adminPassword", getAdminPassword());
            if (configFilePath != null) // configFilePath is set after initializeInstance, which is step2
                json.addProperty("configFilePath", configFilePath);
        }

        if (!createdDatabases.isEmpty()) {
            JsonArray dbs = new JsonArray();
            for (DatabaseInfo db : createdDatabases) {
                JsonObject dbObj = new JsonObject();
                dbObj.addProperty("id", db.id);
                dbObj.addProperty("name", db.name);
                dbObj.addProperty("user", db.user);
                dbObj.addProperty("permissions", db.permissions);
                dbs.add(dbObj);
            }
            json.add("createdDatabases", dbs);
        }

        if (!createdUsers.isEmpty()) {
            JsonArray users = new JsonArray();
            for (UserInfo user : createdUsers) {
                JsonObject uObj = new JsonObject();
                uObj.addProperty("id", user.id);
                uObj.addProperty("username", user.username);
                uObj.addProperty("password", user.password);
                JsonArray grants = new JsonArray();
                for (UserGrant grant : user.grants) {
                    JsonObject gObj = new JsonObject();
                    gObj.addProperty("databaseId", grant.databaseId);
                    gObj.addProperty("host", grant.host);
                    gObj.addProperty("permissions", grant.permissions);
                    grants.add(gObj);
                }
                uObj.add("grants", grants);
                users.add(uObj);
            }
            json.add("createdUsers", users);
        }

        return json;
    }

    // --- Path Resolution Logic (Professional separation of concerns) ---

    public Path getBaseDir() {
        if (profileRoot == null)
            return null;
        if (binaryFolderName != null && !binaryFolderName.isEmpty()) {
            return profileRoot.resolve(binaryFolderName);
        }
        return profileRoot;
    }

    public Path getBinPath() {
        Path base = getBaseDir();
        return base != null ? base.resolve(BIN_DIR) : null;
    }

    public Path getDataPath() {
        Path base = getBaseDir();
        return base != null ? base.resolve(getDataDirectory()) : null;
    }

    public Path getConfigPath() {
        Path base = getBaseDir();
        if (base == null)
            return null;
        boolean isWin = currentOsName.equalsIgnoreCase("windows");
        return base.resolve(isWin ? WIN_CONFIG : LIN_CONFIG);
    }

    public Path getInstallExecutablePath() {
        Path bin = getBinPath();
        if (bin == null)
            return null;
        boolean isWin = currentOsName.equalsIgnoreCase("windows");
        return bin.resolve(isWin ? WIN_INSTALL_EXE : LIN_INSTALL_EXE);
    }

    public Path getDaemonExecutablePath() {
        Path bin = getBinPath();
        if (bin == null)
            return null;
        boolean isWin = currentOsName.equalsIgnoreCase("windows");
        return bin.resolve(isWin ? WIN_DAEMON_EXE : LIN_DAEMON_EXE);
    }

    // --- Getters ---
    public String getProfileName() {
        return profileName;
    }

    public Path getProfileRoot() {
        return profileRoot;
    }

    public String getInstalledVersion() {
        return installedVersion;
    }

    public String getDownloadedVersion() {
        return downloadedVersion;
    }

    public String getDownloadedOs() {
        return downloadedOs;
    }

    public String getDownloadedArch() {
        return downloadedArch;
    }

    public String getBinaryFolderName() {
        return binaryFolderName;
    }

    public boolean isStep1Completed() {
        return step1Completed;
    }

    public boolean isStep2Completed() {
        return step2Completed;
    }

    public boolean isStep3Completed() {
        return step3Completed;
    }

    public boolean isReady() {
        return isReady;
    }

    public String getPort() {
        return configuration.getOrDefault(CFG_PORT, DEFAULT_PORT);
    }

    public String getDataDirectory() {
        return configuration.getOrDefault(CFG_DATADIR, DEFAULT_DATA_DIR_RELATIVE);
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public String getAppUsername() {
        return appUsername; // This is now deprecated, users are managed via createdUsers list
    }

    public String getAppUserPassword() {
        return appUserPassword; // This is now deprecated
    }

    public String getAppUserPermissions() {
        return appUserPermissions != null ? appUserPermissions : "ALL"; // Hardcoded default for now, could be constant
    }

    public String getLogError() {
        return configuration.getOrDefault(CFG_LOG_ERROR, DEFAULT_ERROR_LOG);
    }

    public String getPidFile() {
        return configuration.getOrDefault(CFG_PID_FILE, DEFAULT_PID_FILE);
    }

    public String getBindAddress() {
        return configuration.getOrDefault(CFG_BIND_ADDRESS, DEFAULT_BIND_ADDRESS);
    }

    public String getInnodbFlushLogAtTrxCommit() {
        return configuration.getOrDefault(CFG_INNODB_FLUSH, DEFAULT_INNODB_FLUSH_VAL);
    }

    public String getConfigFilePath() {
        return configFilePath;
    }

    public List<DatabaseInfo> getCreatedDatabases() {
        return createdDatabases;
    }

    public List<UserInfo> getCreatedUsers() {
        return createdUsers;
    }

    public Map<String, String> getConfiguration() {
        return configuration;
    }

    public Set<String> getVisibleProperties() {
        return visibleProperties;
    }

    public void addVisibleProperty(String key, String value) {
        visibleProperties.add(key);
        configuration.put(key, value);
    }

    public void removeProperty(String key) {
        visibleProperties.remove(key);
        configuration.remove(key);
    }

    public boolean isMyIniProperty(String key) {
        return configuration.containsKey(key);
    }

    public boolean isKeyInConfigFile(String key) {
        return keysInFile.contains(key);
    }

    // --- Setters ---
    public void setProfileName(String profileName) {
        this.profileName = profileName;
        this.profileRoot = (profileName != null && !profileName.trim().isEmpty())
                ? PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                        .resolve(DatabaseConfigurationPanel.DatabaseEngine.MARIADB.toString()).resolve(profileName)
                : null;
    }

    public void setInstalledVersion(String installedVersion) {
        this.installedVersion = installedVersion;
    }

    public void setDownloadedVersion(String downloadedVersion) {
        this.downloadedVersion = downloadedVersion;
    }

    public void setDownloadedOs(String downloadedOs) {
        this.downloadedOs = downloadedOs;
    }

    public void setDownloadedArch(String downloadedArch) {
        this.downloadedArch = downloadedArch;
    }

    public void setBinaryFolderName(String binaryFolderName) {
        this.binaryFolderName = binaryFolderName;
    }

    public void setStep1Completed(boolean step1Completed) {
        this.step1Completed = step1Completed;
    }

    public void setStep2Completed(boolean step2Completed) {
        this.step2Completed = step2Completed;
    }

    public void setStep3Completed(boolean step3Completed) {
        this.step3Completed = step3Completed;
    }

    public void setReady(boolean ready) {
        isReady = ready;
    }

    public void setPort(String port) {
        configuration.put(CFG_PORT, port);
    }

    public void setDataDirectory(String dataDirectory) {
        configuration.put(CFG_DATADIR, dataDirectory);
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }

    public void setAppUsername(String appUsername) {
        this.appUsername = appUsername;
    }

    public void setAppUserPassword(String appUserPassword) {
        this.appUserPassword = appUserPassword;
    }

    public void setAppUserPermissions(String appUserPermissions) {
        this.appUserPermissions = appUserPermissions;
    }

    public void setLogError(String logError) {
        configuration.put(CFG_LOG_ERROR, logError);
    }

    public void setPidFile(String pidFile) {
        configuration.put(CFG_PID_FILE, pidFile);
    }

    public void setBindAddress(String bindAddress) {
        configuration.put(CFG_BIND_ADDRESS, bindAddress);
    }

    public void setInnodbFlushLogAtTrxCommit(String innodbFlushLogAtTrxCommit) {
        configuration.put(CFG_INNODB_FLUSH, innodbFlushLogAtTrxCommit);
    }

    public String getCustomConfigEntries() {
        return ""; // No longer used directly
    }

    public void setConfigFilePath(String configFilePath) {
        this.configFilePath = configFilePath;
    }

    public void addCreatedDatabase(String name, String user, String permissions) {
        // Check if it already exists by name.
        createdDatabases.removeIf(db -> db.name.equalsIgnoreCase(name));

        String id = UUID.randomUUID().toString().substring(0, 8);
        createdDatabases.add(new DatabaseInfo(id, name, user, permissions));
    }

    public void updateDatabase(String id, String oldName, String newName, String newUser, String newPermissions)
            throws IOException {
        Optional<DatabaseInfo> existingDb = createdDatabases.stream()
                .filter(db -> db.id.equals(id))
                .findFirst();

        if (existingDb.isPresent()) {
            DatabaseInfo dbInfo = existingDb.get();
            dbInfo.name = newName; // Update name
            dbInfo.user = newUser; // Update user
            dbInfo.permissions = newPermissions; // Update permissions
            Map<String, Object> updates = new HashMap<>();
            updates.put("createdDatabases", this.createdDatabases); // Save the whole list
            saveToProfileJson(updates);
            logger.info("Database ID '{}' (Name: '{}') updated to Name: '{}' in profile '{}'.", id, oldName, newName,
                    profileName);
        } else {
            logger.warn("Attempted to update non-existent database ID '{}' in profile '{}'.", id, profileName);
            throw new IllegalArgumentException("Database ID '" + id + "' not found.");
        }
    }

    public void removeDatabase(String id) throws IOException {
        Optional<DatabaseInfo> dbToRemove = createdDatabases.stream()
                .filter(db -> db.id.equals(id))
                .findFirst();

        if (dbToRemove.isPresent()) {
            String dbName = dbToRemove.get().name;
            createdDatabases.removeIf(db -> db.id.equals(id));
            Map<String, Object> updates = new HashMap<>();
            updates.put("createdDatabases", this.createdDatabases); // Save the whole list
            saveToProfileJson(updates);
            logger.info("Database ID '{}' (Name: '{}') removed from profile '{}'.", id, dbName, profileName);
        }
    }

    public void addCreatedUser(String username, String password, List<UserGrant> grants) throws IOException {
        createdUsers.removeIf(u -> u.username.equalsIgnoreCase(username)); // Ensure uniqueness by username
        UserInfo newUser = new UserInfo(UUID.randomUUID().toString().substring(0, 8), username, password);
        if (grants != null && !grants.isEmpty()) {
            newUser.grants.addAll(grants);
        }
        // Ensure uniqueness of grants within the new user's list
        newUser.grants = newUser.grants.stream()
                .collect(Collectors.toMap(g -> g.databaseId, g -> g, (existing, replacement) -> existing))
                .values().stream().collect(Collectors.toList());
        createdUsers.add(newUser);
        Map<String, Object> updates = new HashMap<>(); // Save immediately to profile.json
        updates.put("createdUsers", this.createdUsers);
        saveToProfileJson(updates);
    }

    public void stopInstance(ProgressListener listener) throws Exception {
        if (!isInstanceRunning()) {
            if (listener != null)
                listener.onProgress("MariaDB is already stopped.", 100);
            return;
        }

        if (listener != null)
            listener.onProgress("Stopping MariaDB instance...", 20);
        Path binPath = getBinPath();
        if (binPath == null)
            throw new IOException("MariaDB binary path could not be resolved.");

        Path mysqlAdmin = binPath.resolve(currentOsName.equalsIgnoreCase("windows") ? "mysqladmin.exe" : "mysqladmin");
        if (!Files.exists(mysqlAdmin)) {
            throw new FileNotFoundException("mysqladmin not found at: " + mysqlAdmin.toAbsolutePath());
        }

        List<String> command = new ArrayList<>();
        command.add(mysqlAdmin.toAbsolutePath().toString());
        command.add("--defaults-file=" + getConfigPath().toAbsolutePath().toString());
        command.add("--host=127.0.0.1");
        command.add("--port=" + getPort());
        command.add("-u" + getAdminUsername());
        String pwd = getAdminPassword();
        if (pwd != null && !pwd.isEmpty()) {
            command.add("-p" + pwd);
        }
        command.add("shutdown");

        DatabaseConfigurationUtils.executeExternalProcess(command, getBaseDir().toFile(), "MariaDB Stop", listener);

        // Wait loop for shutdown confirmation
        for (int i = 0; i < 20; i++) {
            Thread.sleep(1000);
            if (!isInstanceRunning()) {
                if (listener != null)
                    listener.onProgress("MariaDB instance stopped.", 100);
                return;
            }
        }
        throw new IOException("MariaDB failed to stop within timeout.");
    }

    public void restartInstance(ProgressListener listener) throws Exception {
        stopInstance((msg, p) -> {
            if (listener != null)
                listener.onProgress(msg, p / 2);
        });
        ensureInstanceRunning((msg, p) -> {
            if (listener != null)
                listener.onProgress(msg, 50 + (p / 2));
        });
    }

    public void removeUser(String id) throws IOException {
        Optional<UserInfo> userToRemove = createdUsers.stream().filter(u -> u.id.equals(id)).findFirst();
        if (userToRemove.isPresent()) {
            // TODO: Also execute DROP USER in MariaDB
            createdUsers.removeIf(u -> u.id.equals(id));
            Map<String, Object> updates = new HashMap<>();
            updates.put("createdUsers", this.createdUsers);
            saveToProfileJson(updates);
            logger.info("User ID '{}' removed from profile '{}'.", id, profileName);
        }
    }

    public void updateUserPassword(String userId, String newPassword) throws IOException {
        createdUsers.stream().filter(u -> u.id.equals(userId)).findFirst().ifPresent(u -> {
            u.password = newPassword;
            // TODO: Also execute ALTER USER ... IDENTIFIED BY in MariaDB
        });
        Map<String, Object> updates = new HashMap<>();
        updates.put("createdUsers", this.createdUsers);
        saveToProfileJson(updates);
        logger.info("Password for user ID '{}' updated in profile '{}'.", userId, profileName);
    }

    public void addUserGrant(String userId, String dbId, String host, String permissions) throws IOException {
        createdUsers.stream().filter(u -> u.id.equals(userId)).findFirst().ifPresent(u -> {
            // MariaDB identify grants by DB and Host
            u.grants.removeIf(g -> g.databaseId.equals(dbId) && g.host.equals(host));
            u.grants.add(new UserGrant(dbId, host, permissions));
        });
        Map<String, Object> updates = new HashMap<>();
        updates.put("createdUsers", this.createdUsers);
        saveToProfileJson(updates);
        logger.info("Grant for user ID '{}' on DB '{}' with permissions '{}' added/updated in profile '{}'.", userId,
                dbId, permissions, profileName);
    }

    public void removeUserGrant(String userId, String dbId, String host) throws IOException {
        createdUsers.stream().filter(u -> u.id.equals(userId)).findFirst().ifPresent(u -> {
            Optional<UserGrant> grantToRemove = u.grants.stream()
                    .filter(g -> g.databaseId.equals(dbId) && g.host.equals(host)).findFirst();
            if (grantToRemove.isPresent()) {
                // TODO: Also execute REVOKE ... ON ... FROM in MariaDB
                u.grants.removeIf(g -> g.databaseId.equals(dbId) && g.host.equals(host));
                logger.info("Grant for user ID '{}' on DB '{}' removed from profile '{}'.", userId, dbId, profileName);
            } else {
                logger.warn("Attempted to remove non-existent grant for user ID '{}' on DB '{}' in profile '{}'.",
                        userId, dbId, profileName);
            }
        });
        Map<String, Object> updates = new HashMap<>();
        updates.put("createdUsers", this.createdUsers);
        saveToProfileJson(updates);
    }

    // Helper to map permission string to SQL
    private String mapPermissionsToSql(String permissions) {
        if (permissions == null || permissions.trim().isEmpty()) {
            return "USAGE"; // Default to no privileges
        }
        if (permissions.equalsIgnoreCase("ALL")) {
            return "ALL PRIVILEGES";
        }

        // Handle comma-separated list of privileges
        // Split, trim, convert to uppercase, and join with ", "
        // This allows for flexible combinations like "SELECT, INSERT, UPDATE"
        return Arrays.stream(permissions.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .collect(Collectors.joining(", "));
    }

    // --- Utility methods ---
    public boolean isDownloadAndInstallNeeded() {
        return !step1Completed;
    }

    public boolean isInitializationNeeded() {
        return step1Completed && !step2Completed;
    }

    public boolean isSetupNeeded() {
        return step2Completed && !step3Completed;
    }

    public void uninstall() throws IOException {
        if (this.profileRoot == null)
            return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.profileRoot)) {
            for (Path entry : stream) {
                if (!entry.getFileName().toString().equals("profile.json")) {
                    if (Files.isDirectory(entry)) {
                        DatabaseConfigurationUtils.deleteDirectoryRecursively(entry);
                    } else {
                        Files.delete(entry);
                    }
                }
            }
        }
        this.installedVersion = null;
        this.downloadedVersion = null;
        this.downloadedOs = null;
        this.downloadedArch = null;
        this.step1Completed = false;
        this.step2Completed = false;
        this.step3Completed = false;
        this.isReady = false;
        this.adminUsername = "root"; // Reset admin credentials to default
        this.adminPassword = "";
        this.appUsername = null; // Clear deprecated app user

        Map<String, Object> updates = new HashMap<>();
        updates.put("installedVersion", null);
        updates.put("downloadedVersion", null);
        updates.put("downloadedOs", null);
        updates.put("downloadedArch", null);
        updates.put("binaryFolderName", null);
        updates.put("step1Completed", false);
        updates.put("step2Completed", false);
        updates.put("step3Completed", false);
        updates.put("configFilePath", null);
        updates.put("adminUsername", "root");
        updates.put("adminPassword", "");
        updates.put("appUsername", null);
        updates.put("isReady", false);
        updates.put("createdDatabases", new ArrayList<>());
        updates.put("createdUsers", new ArrayList<>());
        saveToProfileJson(updates);
        keysInFile.clear();
    }

    /**
     * Updates specified key-value pairs in the profile.json file, or creates them
     * if they do not exist.
     * All other settings remain unchanged. This method replaces the previous
     * saveAdmin(Path, String, String) implementation.
     *
     * @param profileRoot The root directory of the profile.
     * @param updates     A Map containing the key-value pairs to be updated.
     * @throws IOException If an I/O error occurs during file reading or writing.
     */
    public void saveToProfileJson(Map<String, Object> updates) throws IOException {
        if (profileRoot == null || updates == null) {
            return;
        }
        Files.createDirectories(profileRoot); // Ensure directory exists
        Path profileJson = profileRoot.resolve("profile.json");
        JsonObject json;

        if (Files.exists(profileJson)) {
            try (BufferedReader reader = Files.newBufferedReader(profileJson, StandardCharsets.UTF_8)) {
                json = GSON.fromJson(reader, JsonObject.class);
                if (json == null)
                    json = new JsonObject();
            }
        } else {
            json = new JsonObject(); // Create new if file doesn't exist
        }

        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (val == null) {
                json.add(key, com.google.gson.JsonNull.INSTANCE);
            } else if (val instanceof String) {
                json.addProperty(key, (String) val);
            } else if (val instanceof Boolean) {
                json.addProperty(key, (Boolean) val);
            } else if (val instanceof Number) {
                json.addProperty(key, (Number) val);
            } else {
                json.add(key, GSON.toJsonTree(val));
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(profileJson, StandardCharsets.UTF_8)) {
            writer.write(GSON.toJson(json));
        }
        logger.info("Partial profile update for profile '{}'. Keys updated: {}", profileName,
                String.join(", ", updates.keySet()));
    }

    /**
     * Step 1: Downloads and installs (extracts) MariaDB binaries.
     */
    public void install(String downloadUrl, String version, String arch,
            ProgressListener listener) throws IOException {
        String filename = downloadUrl.substring(downloadUrl.lastIndexOf('/') + 1);
        Path archiveFile = this.profileRoot.resolve(filename);

        // 1. Download (if necessary)
        if (!Files.exists(archiveFile)) {
            DatabaseConfigurationUtils.downloadFile(downloadUrl, archiveFile, listener);
        }

        // Detect root folder inside the zip
        this.binaryFolderName = DatabaseConfigurationUtils.detectRootFolder(archiveFile);
        logger.info("Detected binary root folder in ZIP: {}", binaryFolderName != null ? binaryFolderName : "(none)");

        // 2. Extraction
        DatabaseConfigurationUtils.extractZip(archiveFile, this.profileRoot, listener);

        // Update state
        this.downloadedVersion = version;
        this.installedVersion = version;
        this.downloadedOs = currentOsName;
        this.downloadedArch = arch;
        this.step1Completed = true;
        this.updateReadiness();

        Map<String, Object> updates = new HashMap<>();
        updates.put("downloadedVersion", this.downloadedVersion);
        updates.put("installedVersion", this.installedVersion);
        updates.put("downloadedOs", this.downloadedOs);
        updates.put("downloadedArch", this.downloadedArch);
        updates.put("binaryFolderName", this.binaryFolderName);
        updates.put("step1Completed", this.step1Completed);
        updates.put("isReady", this.isReady);
        saveToProfileJson(updates);
    }

    /**
     * Rewrites the MariaDB configuration file based on the current profile
     * settings.
     * This method is triggered by the "Update my.ini" action in the GUI.
     *
     * @throws IOException If an I/O error occurs during file writing.
     */
    public void writeConfigFile() throws IOException {
        Path configPath = getConfigPath();
        if (configPath == null)
            return;

        logger.info("Writing MariaDB configuration to {}", configPath.toAbsolutePath());
        Files.createDirectories(configPath.getParent());

        try (BufferedWriter writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            writer.write(MYSQLD_SECTION + "\n");
            for (Map.Entry<String, String> entry : configuration.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();

                // Apply quoting and relative path logic for specific keys
                if (key.equals(CFG_DATADIR)) {
                    if (!value.startsWith("./") && !new File(value).isAbsolute()) {
                        value = "./" + value;
                    }
                    if (!value.startsWith("\"") && !value.endsWith("\"")) {
                        value = "\"" + value + "\"";
                    }
                } else if (key.equals(CFG_LOG_ERROR) || key.equals(CFG_PID_FILE)) {
                    if (!value.startsWith("\"") && !value.endsWith("\"")) {
                        value = "\"" + value + "\"";
                    }
                }
                writer.write(String.format("%s=%s%n", key, value));
            }
        }
        this.configFilePath = configPath.toAbsolutePath().toString();
        keysInFile.clear();
        keysInFile.addAll(configuration.keySet());
    }

    /**
     * Reads the MariaDB configuration file and updates the profile fields.
     */
    public void loadSettingsFromConfig() {
        Path configPath = getConfigPath();

        // Reset to clean state (defaults only) before loading from file.
        // This ensures that properties manually removed from the file are also removed
        // from the UI.
        configuration.clear();
        visibleProperties.clear();
        keysInFile.clear();
        initDefaultConfiguration();

        if (configPath == null || !Files.exists(configPath))
            return;

        this.configFilePath = configPath.toAbsolutePath().toString();

        try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            String line;
            boolean inMysqld = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";"))
                    continue;
                if (line.startsWith("[")) {
                    inMysqld = line.equalsIgnoreCase(MYSQLD_SECTION);
                    continue;
                }
                if (inMysqld && line.contains("=")) {
                    int eqIdx = line.indexOf('=');
                    String key = line.substring(0, eqIdx).trim();
                    String val = line.substring(eqIdx + 1).trim();

                    if (val.startsWith("\"") && val.endsWith("\""))
                        val = val.substring(1, val.length() - 1);

                    configuration.put(key, val);
                    visibleProperties.add(key);
                    keysInFile.add(key);
                }
            }
        } catch (IOException e) {
            logger.error("Error reading MariaDB config file: {}", e.getMessage());
        }
    }

    /**
     * Step 2: Creates the configuration and initializes the database directory.
     */
    public void initializeInstance(ProgressListener listener)
            throws IOException, InterruptedException {
        Path baseDir = getBaseDir();
        Path dataPath = getDataPath();
        Path configPath = getConfigPath();

        logger.info("Starting MariaDB initialization. BaseDir: {}, DataPath: {}, ConfigPath: {}",
                baseDir.toAbsolutePath(), dataPath.toAbsolutePath(), configPath.toAbsolutePath());

        listener.onProgress("Preparing data directory...", 10);
        if (Files.exists(dataPath) && Files.isDirectory(dataPath) && Files.list(dataPath).findFirst().isPresent()) {
            DatabaseConfigurationUtils.deleteDirectoryRecursively(dataPath);
        }
        Files.createDirectories(dataPath);

        listener.onProgress("Writing configuration file...", 30);
        writeConfigFile();

        listener.onProgress("Running database initialization command...", 60);
        Path exePath = getInstallExecutablePath();
        if (!Files.exists(exePath)) {
            String errorMsg = "Database initialization executable not found at: " + exePath.toAbsolutePath();
            logger.error(errorMsg);
            throw new FileNotFoundException(errorMsg);
        }
        List<String> command = Arrays.asList(exePath.toAbsolutePath().toString(), "--datadir=" + getDataDirectory());

        DatabaseConfigurationUtils.executeExternalProcess(command, baseDir.toFile(), "MariaDB Init", listener);

        // Set defaults for admin credentials if they are missing
        if (this.adminUsername == null || this.adminUsername.trim().isEmpty()) {
            this.adminUsername = "root";
        }
        if (this.adminPassword == null) {
            this.adminPassword = "";
        }

        this.step2Completed = true;
        this.updateReadiness();

        Map<String, Object> updates = new HashMap<>();
        updates.put("adminUsername", this.adminUsername);
        updates.put("adminPassword", this.adminPassword);
        updates.put("configFilePath", this.configFilePath);
        updates.put("step2Completed", this.step2Completed);
        updates.put("isReady", this.isReady);
        saveToProfileJson(updates);
    }

    /**
     * Checks if MariaDB is responding on the configured port.
     */
    public boolean isInstanceRunning() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", Integer.parseInt(getPort())), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Ensures the database is running, starting it if necessary.
     */
    public void ensureInstanceRunning(ProgressListener listener) throws Exception {
        if (isInstanceRunning()) {
            if (listener != null)
                listener.onProgress("MariaDB is already running.", 20);
            return;
        }

        if (listener != null)
            listener.onProgress("Starting MariaDB instance...", 10);
        Path daemonExe = getDaemonExecutablePath();
        Path baseDir = getBaseDir();
        Path configPath = getConfigPath();

        if (!Files.exists(daemonExe)) {
            throw new FileNotFoundException("MariaDB daemon not found at: " + daemonExe.toAbsolutePath());
        }

        List<String> command = new ArrayList<>();
        command.add(daemonExe.toAbsolutePath().toString());
        command.add("--defaults-file=" + configPath.toAbsolutePath().toString());
        command.add("--console"); // Windowson szükséges a logok elkapásához

        ProcessBuilder pb = new ProcessBuilder(command).directory(baseDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Kimenet olvasása háttérszálon, hogy ne akadjon meg a folyamat és lássuk a
        // logokat a GUI-ban
        Path logFile = getDataPath().resolve("mariadb_daemon.log");
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                    BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (listener != null)
                        listener.onLog(line);
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException e) {
                // A folyamat leállt vagy a stream lezárult
            }
        }, "MariaDB-Log-Forwarder-" + profileName).start();

        // Wait loop for startup confirmation
        for (int i = 0; i < 15; i++) {
            Thread.sleep(1000);
            if (isInstanceRunning()) {
                if (listener != null)
                    listener.onProgress("MariaDB instance started.", 40);
                return;
            }
        }
        throw new IOException("MariaDB failed to start within timeout. Check mariadb_daemon.log");
    }

    public void runClientCommand(String cmd, ProgressListener listener) {
        Path binPath = getBinPath();
        if (binPath == null)
            return;

        Path mysqlExe = binPath.resolve(currentOsName.equalsIgnoreCase("windows") ? "mysql.exe" : "mysql");
        if (!Files.exists(mysqlExe)) {
            if (listener != null)
                listener.onLog("Error: mysql client not found.");
            return;
        }

        List<String> command = new ArrayList<>();
        command.add(mysqlExe.toAbsolutePath().toString());
        command.add("--host=127.0.0.1");
        command.add("--port=" + getPort());
        command.add("-u" + getAdminUsername());
        if (getAdminPassword() != null && !getAdminPassword().isEmpty()) {
            command.add("-p" + getAdminPassword());
        }
        command.add("-e");
        command.add(cmd);

        try {
            DatabaseConfigurationUtils.executeExternalProcess(command, getBaseDir().toFile(), "SQL", listener);
        } catch (Exception e) {
            if (listener != null)
                listener.onLog("Command failed: " + e.getMessage());
        }
    }

    public void changeAdminCredentials(String oldUser, String oldPass, String newUser,
            String newPass, ProgressListener listener) throws Exception {
        ensureInstanceRunning(listener);

        listener.onProgress("Connecting to database...", 50);
        String url = "jdbc:mariadb://127.0.0.1:" + getPort() + "/mysql";

        try (Connection conn = DriverManager.getConnection(url, oldUser, oldPass);
                Statement stmt = conn.createStatement()) {

            listener.onProgress("Updating credentials...", 70);
            if (!oldUser.equals(newUser)) {
                stmt.execute(String.format("RENAME USER '%s'@'localhost' TO '%s'@'localhost'", oldUser, newUser));
            }
            stmt.execute(String.format("ALTER USER '%s'@'localhost' IDENTIFIED BY '%s'", newUser, newPass));
            stmt.execute("FLUSH PRIVILEGES");

            listener.onProgress("Credentials updated successfully.", 100);
            this.adminUsername = newUser;
            this.adminPassword = newPass;

            Map<String, Object> updates = new HashMap<>();
            updates.put("adminUsername", this.adminUsername);
            updates.put("adminPassword", this.adminPassword);
            saveToProfileJson(updates);
        }
    }

    // Step 3: Perform database creation and user setup based on the profile
    public boolean createDatabase(String dbName, String appUser, String permissions,
            ProgressListener listener) throws Exception {
        ensureInstanceRunning(listener);

        String url = "jdbc:mariadb://127.0.0.1:" + getPort() + "/mysql";
        try (Connection conn = DriverManager.getConnection(url, getAdminUsername(), getAdminPassword());
                Statement stmt = conn.createStatement()) {

            listener.onProgress("Checking database existence...", 30);
            boolean exists = false;
            try (java.sql.ResultSet rs = conn.getMetaData().getCatalogs()) {
                while (rs.next()) {
                    if (rs.getString(1).equalsIgnoreCase(dbName)) {
                        exists = true;
                        break;
                    }
                }
            }

            if (exists) {
                listener.onLog("Database '" + dbName + "' already exists.");
                addCreatedDatabase(dbName, appUser, permissions);
                this.step3Completed = true;
                this.updateReadiness();
                Map<String, Object> updates = new HashMap<>();
                updates.put("createdDatabases", this.createdDatabases);
                updates.put("step3Completed", this.step3Completed);
                updates.put("isReady", this.isReady);
                saveToProfileJson(updates);
                return false;
            }

            listener.onProgress("Creating database: " + dbName, 60);
            listener.onLog("SQL: CREATE DATABASE `" + dbName + "`");
            stmt.execute("CREATE DATABASE `" + dbName + "`");

            addCreatedDatabase(dbName, appUser, permissions);
            this.step3Completed = true;
            this.updateReadiness();

            Map<String, Object> updates = new HashMap<>();
            updates.put("createdDatabases", this.createdDatabases);
            updates.put("step3Completed", this.step3Completed);
            updates.put("isReady", this.isReady);
            saveToProfileJson(updates);
            return true;
        }
    }

    /**
     * Step 3: SQL configuration (database and user creation).
     */
    public void setupDatabase(ProgressListener listener) throws Exception {
        ensureInstanceRunning(listener);
        listener.onProgress("Database is running. Starting user and grant setup...", 10);

        String url = "jdbc:mariadb://127.0.0.1:" + getPort() + "/mysql";
        try (Connection conn = DriverManager.getConnection(url, getAdminUsername(), getAdminPassword());
                Statement stmt = conn.createStatement()) {

            // 1. Process created databases (ensure they exist)
            for (DatabaseInfo db : createdDatabases) {
                listener.onProgress("Ensuring database '" + db.name + "' exists...", 20);
                listener.onLog("SQL: CREATE DATABASE IF NOT EXISTS `" + db.name + "`");
                stmt.execute("CREATE DATABASE IF NOT EXISTS `" + db.name + "`");
            }

            // 2. Process created users and their grants
            for (UserInfo user : createdUsers) {
                listener.onProgress("Processing user '" + user.username + "'...", 40);

                // Group grants by host because MariaDB users are defined as user@host
                Set<String> uniqueHosts = user.grants.stream().map(g -> g.host).collect(Collectors.toSet());
                if (uniqueHosts.isEmpty())
                    uniqueHosts.add("localhost");

                for (String host : uniqueHosts) {
                    try {
                        listener.onLog("Ensuring user exists: " + user.username + "@" + host);
                        stmt.execute(String.format("CREATE USER IF NOT EXISTS '%s'@'%s' IDENTIFIED BY '%s'",
                                user.username, host, user.password));
                        // Since CREATE USER IF NOT EXISTS doesn't update password if user exists:
                        stmt.execute(String.format("ALTER USER '%s'@'%s' IDENTIFIED BY '%s'",
                                user.username, host, user.password));
                        logger.info("Ensured user exists: {}@{}", user.username, host);
                    } catch (java.sql.SQLException e) {
                        logger.error("Failed to create/alter user {}@{}", user.username, host, e);
                        throw e;
                    }
                }

                // Apply grants
                for (UserGrant grant : user.grants) {
                    String sqlPermissions = mapPermissionsToSql(grant.permissions);
                    String target = grant.databaseId.equals("global") ? "*.*"
                            : "`" + getDatabaseNameById(grant.databaseId) + "`.*";

                    listener.onProgress(
                            String.format("Granting %s on %s to %s...", sqlPermissions, target, user.username), 60);
                    listener.onLog("SQL: GRANT " + sqlPermissions + " ON " + target + " TO '" + user.username + "'@'"
                            + grant.host + "'");
                    stmt.execute(String.format("GRANT %s ON %s TO '%s'@'%s'",
                            sqlPermissions, target, user.username, grant.host));
                }
            }

            listener.onProgress("Flushing privileges...", 90);
            stmt.execute("FLUSH PRIVILEGES");

            listener.onProgress("User and grant setup complete.", 100);
        }

        this.step3Completed = true;
        this.updateReadiness();

        // After successful setup, update the profile.json
        // The createdDatabases and createdUsers lists are already updated by their
        // respective add/remove methods
        // This ensures the step3Completed flag is persisted.
        Map<String, Object> updates = new HashMap<>();
        updates.put("step3Completed", this.step3Completed);
        updates.put("isReady", this.isReady);
        saveToProfileJson(updates);
    }

    private String getDatabaseNameById(String dbId) {
        return createdDatabases.stream()
                .filter(db -> db.id.equals(dbId))
                .map(db -> db.name)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Database with ID " + dbId + " not found."));
    }

    /**
     * Updates the overall readiness state of the profile based on completed steps.
     * This method is called internally or by the GUI controller after a successful
     * operation.
     */
    public void updateReadiness() {
        // Profile is considered ready when all mandatory steps have been successfully
        // completed
        this.isReady = step1Completed && step2Completed && step3Completed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        MariadbProfile that = (MariadbProfile) o;
        return step1Completed == that.step1Completed &&
                step2Completed == that.step2Completed &&
                step3Completed == that.step3Completed &&
                isReady == that.isReady &&
                Objects.equals(profileName, that.profileName) &&
                Objects.equals(installedVersion, that.installedVersion) &&
                Objects.equals(downloadedOs, that.downloadedOs) &&
                Objects.equals(downloadedArch, that.downloadedArch) &&
                Objects.equals(binaryFolderName, that.binaryFolderName) &&
                Objects.equals(adminUsername, that.adminUsername) &&
                Objects.equals(adminPassword, that.adminPassword) &&
                Objects.equals(appUsername, that.appUsername) &&
                Objects.equals(appUserPassword, that.appUserPassword) &&
                Objects.equals(appUserPermissions, that.appUserPermissions) &&
                Objects.equals(configuration, that.configuration) &&
                Objects.equals(visibleProperties, that.visibleProperties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profileName, installedVersion, downloadedOs, downloadedArch, step1Completed, step2Completed,
                step3Completed, isReady, binaryFolderName, adminUsername, adminPassword, appUsername,
                appUserPassword, appUserPermissions, configuration, visibleProperties);
    }

    @Override
    public String toString() {
        return "MariadbProfile{" +
                "profileName='" + profileName + '\'' +
                ", installedVersion='" + installedVersion + '\'' +
                ", step1Completed=" + step1Completed +
                ", step2Completed=" + step2Completed +
                ", step3Completed=" + step3Completed +
                ", isReady=" + isReady +
                '}';
    }
}