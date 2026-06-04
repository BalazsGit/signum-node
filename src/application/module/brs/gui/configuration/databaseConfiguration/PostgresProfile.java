package application.module.brs.gui.configuration.databaseConfiguration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import application.module.brs.gui.configuration.databaseConfiguration.DatabaseConfigurationUtils.ProgressListener;
import application.module.brs.util.PathUtils;
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
import java.util.*;
import java.util.stream.Collectors;

/**
 * PostgreSQL profile implementation for portable database management.
 */
public class PostgresProfile {
    private static final Logger logger = LoggerFactory.getLogger(PostgresProfile.class);
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
        public String databaseId;
        public String permissions;

        public UserGrant(String databaseId, String permissions) {
            this.databaseId = databaseId;
            this.permissions = permissions;
        }
    }

    public static class UserInfo {
        public String id;
        public String username;
        public String password;
        public String host;
        public List<UserGrant> grants = new ArrayList<>();

        public UserInfo(String id, String username, String password, String host) {
            this.id = id;
            this.username = username;
            this.password = password;
            this.host = host != null ? host : "localhost";
        }
    }

    // Directory and File Constants
    public static final String BIN_DIR = "bin";
    public static final String DATA_DIR = "data";
    public static final String CONFIG_FILE = "postgresql.conf";
    public static final String HBA_FILE = "pg_hba.conf";
    public static final String WIN_INIT_EXE = "initdb.exe";
    public static final String LIN_INIT_EXE = "initdb";
    public static final String WIN_DAEMON_EXE = "postgres.exe";
    public static final String LIN_DAEMON_EXE = "postgres";
    public static final String WIN_CTL_EXE = "pg_ctl.exe";
    public static final String LIN_CTL_EXE = "pg_ctl";

    public static final String CFG_PORT = "port";
    public static final String DEFAULT_PORT = "5432";

    private String profileName;
    private Path profileRoot;
    private final String currentOsName;
    private String installedVersion;
    private String downloadedVersion;
    private String downloadedOs;
    private String downloadedArch;
    private String binaryFolderName;
    private boolean step1Completed;
    private boolean step2Completed;
    private boolean step3Completed;
    private boolean isReady;
    private String adminUsername;
    private String adminPassword;
    private final List<DatabaseInfo> createdDatabases = new ArrayList<>();
    private final List<UserInfo> createdUsers = new ArrayList<>();
    private String configFilePath;

    private final Map<String, String> configuration = new LinkedHashMap<>();
    private final Set<String> visibleProperties = new LinkedHashSet<>();

    public PostgresProfile(String profileName) {
        this.profileName = profileName;
        this.profileRoot = (profileName != null && !profileName.trim().isEmpty())
                ? PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                        .resolve(DatabaseConfigurationPanel.DatabaseEngine.POSTGRESQL.toString()).resolve(profileName)
                : null;
        this.currentOsName = DatabaseConfigurationUtils.getOsName();
        this.adminUsername = "postgres";
        this.adminPassword = "";
        initDefaultConfiguration();
    }

    private void initDefaultConfiguration() {
        configuration.put(CFG_PORT, DEFAULT_PORT);
        visibleProperties.add(CFG_PORT);
    }

    public PostgresProfile(String profileName, JsonObject json) {
        this(profileName);
        this.installedVersion = getString(json, "installedVersion", null);
        this.downloadedVersion = getString(json, "downloadedVersion", null);
        this.downloadedOs = getString(json, "downloadedOs", null);
        this.downloadedArch = getString(json, "downloadedArch", null);
        this.binaryFolderName = getString(json, "binaryFolderName", null);
        this.step1Completed = getBoolean(json, "step1Completed");
        this.step2Completed = getBoolean(json, "step2Completed");
        this.step3Completed = getBoolean(json, "step3Completed");
        this.isReady = getBoolean(json, "isReady");
        this.adminUsername = getString(json, "adminUsername", "postgres");
        this.adminPassword = getString(json, "adminPassword", "");
        this.configFilePath = getString(json, "configFilePath", null);

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
                        getString(obj, "password", ""),
                        getString(obj, "host", "localhost"));
                if (obj.has("grants")) {
                    obj.getAsJsonArray("grants").forEach(g -> {
                        JsonObject gObj = g.getAsJsonObject();
                        user.grants.add(new UserGrant(getString(gObj, "databaseId", "global"),
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

    public JsonObject toJsonObject() {
        JsonObject json = new JsonObject();
        json.addProperty("step1Completed", step1Completed);
        json.addProperty("step2Completed", step2Completed);
        json.addProperty("step3Completed", step3Completed);
        json.addProperty("isReady", isReady);
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
        json.addProperty("adminUsername", adminUsername);
        json.addProperty("adminPassword", adminPassword);
        if (configFilePath != null)
            json.addProperty("configFilePath", configFilePath);

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
                uObj.addProperty("host", user.host);
                JsonArray grants = new JsonArray();
                for (UserGrant grant : user.grants) {
                    JsonObject gObj = new JsonObject();
                    gObj.addProperty("databaseId", grant.databaseId);
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

    public Path getBaseDir() {
        if (profileRoot == null)
            return null;
        return (binaryFolderName != null && !binaryFolderName.isEmpty()) ? profileRoot.resolve(binaryFolderName)
                : profileRoot;
    }

    public Path getBinPath() {
        Path base = getBaseDir();
        return base != null ? base.resolve(BIN_DIR) : null;
    }

    public Path getDataPath() {
        Path base = getBaseDir();
        return base != null ? base.resolve(DATA_DIR) : null;
    }

    public Path getConfigPath() {
        Path data = getDataPath();
        return data != null ? data.resolve(CONFIG_FILE) : null;
    }

    public Path getHbaPath() {
        Path data = getDataPath();
        return data != null ? data.resolve(HBA_FILE) : null;
    }

    public Path getInitExecutablePath() {
        Path bin = getBinPath();
        if (bin == null)
            return null;
        return bin.resolve(currentOsName.equalsIgnoreCase("windows") ? WIN_INIT_EXE : LIN_INIT_EXE);
    }

    public Path getCtlExecutablePath() {
        Path bin = getBinPath();
        if (bin == null)
            return null;
        return bin.resolve(currentOsName.equalsIgnoreCase("windows") ? WIN_CTL_EXE : LIN_CTL_EXE);
    }

    public String getProfileName() {
        return profileName;
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

    public void setProfileName(String profileName) {
        this.profileName = profileName;
        this.profileRoot = (profileName != null && !profileName.trim().isEmpty())
                ? PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                        .resolve(DatabaseConfigurationPanel.DatabaseEngine.POSTGRESQL.toString()).resolve(profileName)
                : null;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public String getAdminPassword() {
        return adminPassword;
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

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }

    public void addVisibleProperty(String key, String value) {
        visibleProperties.add(key);
        configuration.put(key, value);
    }

    public void removeProperty(String key) {
        visibleProperties.remove(key);
        configuration.remove(key);
    }

    public boolean isKeyInConfigFile(String key) {
        return visibleProperties.contains(key);
    }

    public void install(String downloadUrl, String version, String arch, ProgressListener listener) throws IOException {
        String filename = downloadUrl.substring(downloadUrl.lastIndexOf('/') + 1);
        Path archiveFile = this.profileRoot.resolve(filename);
        if (!Files.exists(archiveFile)) {
            DatabaseConfigurationUtils.downloadFile(downloadUrl, archiveFile, listener);
        }
        this.binaryFolderName = DatabaseConfigurationUtils.detectRootFolder(archiveFile);
        DatabaseConfigurationUtils.extractZip(archiveFile, this.profileRoot, listener);
        this.downloadedVersion = version;
        this.installedVersion = version;
        this.downloadedOs = currentOsName;
        this.downloadedArch = arch;
        this.step1Completed = true;
        this.updateReadiness();
        saveToProfileJson(Collections.singletonMap("step1Completed", true));
    }

    public void initializeInstance(ProgressListener listener) throws IOException, InterruptedException {
        Path dataPath = getDataPath();
        listener.onProgress("Preparing data directory...", 10);
        if (Files.exists(dataPath))
            DatabaseConfigurationUtils.deleteDirectoryRecursively(dataPath);
        Files.createDirectories(dataPath);

        listener.onProgress("Running initdb...", 40);
        Path initExe = getInitExecutablePath();
        List<String> command = Arrays.asList(initExe.toAbsolutePath().toString(), "-D",
                dataPath.toAbsolutePath().toString(), "-U", adminUsername, "--auth=md5", "--pwfile=pw.txt");

        // Temporary creation of password file
        Path pwFile = getBaseDir().resolve("pw.txt");
        Files.write(pwFile, adminPassword.getBytes(StandardCharsets.UTF_8));

        DatabaseConfigurationUtils.executeExternalProcess(command, getBaseDir().toFile(), "Postgres Init", listener);
        Files.deleteIfExists(pwFile);

        listener.onProgress("Writing configuration...", 70);
        writeConfigFile();
        updateHbaFile();

        this.step2Completed = true;
        this.updateReadiness();
        this.configFilePath = getConfigPath().toAbsolutePath().toString();
        saveToProfileJson(Collections.singletonMap("step2Completed", true));
    }

    public void writeConfigFile() throws IOException {
        Path configPath = getConfigPath();
        if (configPath == null)
            return;
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : configuration.entrySet()) {
            lines.add(entry.getKey() + " = '" + entry.getValue() + "'");
        }
        Files.write(configPath, lines, StandardCharsets.UTF_8);
    }

    private void updateHbaFile() throws IOException {
        Path hbaPath = getHbaPath();
        if (hbaPath == null)
            return;
        List<String> lines = Arrays.asList(
                "# TYPE  DATABASE        USER            ADDRESS                 METHOD",
                "host    all             all             127.0.0.1/32            md5",
                "host    all             all             ::1/128                 md5",
                "local   all             all                                     trust");
        Files.write(hbaPath, lines, StandardCharsets.UTF_8);
    }

    public void loadSettingsFromConfig() {
        Path configPath = getConfigPath();
        if (configPath == null || !Files.exists(configPath))
            return;
        try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;
                if (line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    String key = parts[0].trim();
                    String val = parts[1].trim().replace("'", "");
                    configuration.put(key, val);
                    visibleProperties.add(key);
                }
            }
        } catch (IOException e) {
            logger.error("Error reading Postgres config: {}", e.getMessage());
        }
    }

    public boolean isInstanceRunning() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", Integer.parseInt(getPort())), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void ensureInstanceRunning(ProgressListener listener) throws Exception {
        if (isInstanceRunning())
            return;
        Path ctlExe = getCtlExecutablePath();
        List<String> command = Arrays.asList(ctlExe.toAbsolutePath().toString(), "start", "-D",
                getDataPath().toAbsolutePath().toString(), "-w");
        DatabaseConfigurationUtils.executeExternalProcess(command, getBaseDir().toFile(), "Postgres Start", listener);
    }

    public void stopInstance(ProgressListener listener) throws Exception {
        if (!isInstanceRunning())
            return;
        Path ctlExe = getCtlExecutablePath();
        List<String> command = Arrays.asList(ctlExe.toAbsolutePath().toString(), "stop", "-D",
                getDataPath().toAbsolutePath().toString(), "-m", "fast");
        DatabaseConfigurationUtils.executeExternalProcess(command, getBaseDir().toFile(), "Postgres Stop", listener);
    }

    public void restartInstance(ProgressListener listener) throws Exception {
        stopInstance(listener);
        ensureInstanceRunning(listener);
    }

    public void runClientCommand(String cmd, ProgressListener listener) {
        Path bin = getBinPath();
        if (bin == null)
            return;
        Path psql = bin.resolve(currentOsName.equalsIgnoreCase("windows") ? "psql.exe" : "psql");
        List<String> command = Arrays.asList(psql.toAbsolutePath().toString(), "-h", "127.0.0.1", "-p", getPort(), "-U",
                adminUsername, "-c", cmd);
        try {
            DatabaseConfigurationUtils.executeExternalProcess(command, getBaseDir().toFile(), "SQL", listener);
        } catch (Exception e) {
            if (listener != null)
                listener.onLog("Command failed: " + e.getMessage());
        }
    }

    public void saveToProfileJson(Map<String, Object> updates) throws IOException {
        if (profileRoot == null)
            return;
        Files.createDirectories(profileRoot);
        Path profileJson = profileRoot.resolve("profile.json");
        JsonObject json = Files.exists(profileJson)
                ? GSON.fromJson(Files.newBufferedReader(profileJson), JsonObject.class)
                : new JsonObject();
        updates.forEach((k, v) -> {
            if (v == null)
                json.add(k, com.google.gson.JsonNull.INSTANCE);
            else if (v instanceof String)
                json.addProperty(k, (String) v);
            else if (v instanceof Boolean)
                json.addProperty(k, (Boolean) v);
            else
                json.add(k, GSON.toJsonTree(v));
        });
        try (BufferedWriter writer = Files.newBufferedWriter(profileJson)) {
            writer.write(GSON.toJson(json));
        }
    }

    public void uninstall() throws IOException {
        if (profileRoot == null)
            return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(profileRoot)) {
            for (Path entry : stream) {
                if (!entry.getFileName().toString().equals("profile.json")) {
                    if (Files.isDirectory(entry))
                        DatabaseConfigurationUtils.deleteDirectoryRecursively(entry);
                    else
                        Files.delete(entry);
                }
            }
        }
        step1Completed = step2Completed = step3Completed = isReady = false;
        saveToProfileJson(new HashMap<>());
    }

    public void setupDatabase(ProgressListener listener) throws Exception {
        ensureInstanceRunning(listener);
        String url = "jdbc:postgresql://127.0.0.1:" + getPort() + "/postgres";
        try (Connection conn = DriverManager.getConnection(url, adminUsername, adminPassword);
                Statement stmt = conn.createStatement()) {
            for (DatabaseInfo db : createdDatabases) {
                stmt.execute("CREATE DATABASE " + db.name);
            }
            for (UserInfo user : createdUsers) {
                stmt.execute(String.format("CREATE USER %s WITH PASSWORD '%s'", user.username, user.password));
                for (UserGrant grant : user.grants) {
                    stmt.execute(String.format("GRANT ALL PRIVILEGES ON DATABASE %s TO %s",
                            grant.databaseId.equals("global") ? "postgres" : grant.databaseId, user.username));
                }
            }
        }
        step3Completed = true;
        this.updateReadiness();
        saveToProfileJson(Collections.singletonMap("step3Completed", true));
    }

    public void updateReadiness() {
        this.isReady = step1Completed && step2Completed && step3Completed;
    }

    public void addCreatedDatabase(String name, String user, String permissions) throws IOException {
        createdDatabases.removeIf(db -> db.name.equalsIgnoreCase(name));
        createdDatabases.add(new DatabaseInfo(UUID.randomUUID().toString().substring(0, 8), name, user, permissions));
        saveToProfileJson(Collections.singletonMap("createdDatabases", createdDatabases));
    }

    public void addCreatedUser(String username, String password, String host, List<UserGrant> grants)
            throws IOException {
        createdUsers.removeIf(u -> u.username.equalsIgnoreCase(username));
        UserInfo newUser = new UserInfo(UUID.randomUUID().toString().substring(0, 8), username, password, host);
        if (grants != null)
            newUser.grants.addAll(grants);
        createdUsers.add(newUser);
        saveToProfileJson(Collections.singletonMap("createdUsers", createdUsers));
    }

    public void removeDatabase(String id) throws IOException {
        createdDatabases.removeIf(db -> db.id.equals(id));
        saveToProfileJson(Collections.singletonMap("createdDatabases", createdDatabases));
    }

    public void removeUser(String id) throws IOException {
        createdUsers.removeIf(u -> u.id.equals(id));
        saveToProfileJson(Collections.singletonMap("createdUsers", createdUsers));
    }

    public void updateUserPassword(String userId, String newPassword) throws IOException {
        createdUsers.stream().filter(u -> u.id.equals(userId)).findFirst().ifPresent(u -> u.password = newPassword);
        saveToProfileJson(Collections.singletonMap("createdUsers", createdUsers));
    }

    public void addUserGrant(String userId, String dbId, String permissions) throws IOException {
        createdUsers.stream().filter(u -> u.id.equals(userId)).findFirst().ifPresent(u -> {
            u.grants.removeIf(g -> g.databaseId.equals(dbId));
            u.grants.add(new UserGrant(dbId, permissions));
        });
        saveToProfileJson(Collections.singletonMap("createdUsers", createdUsers));
    }

    public void removeUserGrant(String userId, String dbId) throws IOException {
        createdUsers.stream().filter(u -> u.id.equals(userId)).findFirst()
                .ifPresent(u -> u.grants.removeIf(g -> g.databaseId.equals(dbId)));
        saveToProfileJson(Collections.singletonMap("createdUsers", createdUsers));
    }
}