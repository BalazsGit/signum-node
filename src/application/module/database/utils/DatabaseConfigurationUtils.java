package application.module.database.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.plaf.basic.ComboPopup;
import javax.swing.*;
import javax.swing.plaf.ComboBoxUI;
import javax.swing.plaf.basic.*;
import java.awt.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import application.module.database.gui.DatabaseConfigurationPanel;
import application.module.database.profile.MariadbProfile;
import application.module.database.profile.MariadbProfile.DatabaseInfo;
import application.module.database.profile.MariadbProfile.UserGrant;
import application.module.database.profile.MariadbProfile.UserInfo;
import application.utils.io.PathUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Collections;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class DatabaseConfigurationUtils {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfigurationUtils.class);

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String message, int progress);

        default void onLog(String line) {
        }
    }

    /**
     * Base directory for database files and profiles.
     * <p>
     * Relative to the application root (JAR location), not the parent directory.
     */
    public static final String DATABASE_BASE_DIR = "./database";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static JsonObject cachedGlobalSettings;

    public static void downloadFile(String urlString, Path targetPath, ProgressListener listener) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "SignumNode-ConfigTool/1.0");

        long fileSize = conn.getContentLengthLong();
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(targetPath.toFile())) {
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
                if (fileSize > 0) {
                    int pct = (int) (total * 100 / fileSize);
                    listener.onProgress(String.format("Downloading... %.1f MB", total / 1048576.0), pct);
                }
            }
        }
    }

    public static void extractZip(Path zipFilePath, Path destDir, ProgressListener listener) throws IOException {
        long totalUncompressedSize = 0;
        try (ZipFile zipFile = new ZipFile(zipFilePath.toFile())) {
            Enumeration<? extends ZipEntry> enumeration = zipFile.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (!entry.isDirectory()) {
                    totalUncompressedSize += entry.getSize();
                }
            }
        }

        long currentExtractedBytes = 0;
        Files.createDirectories(destDir);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFilePath))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                Path newPath = destDir.resolve(entry.getName()).normalize();
                if (!newPath.startsWith(destDir.normalize())) {
                    throw new IOException("Zip Slip detected: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    Files.createDirectories(newPath.getParent());
                    try (OutputStream out = Files.newOutputStream(newPath)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            out.write(buffer, 0, len);
                            currentExtractedBytes += len;
                            if (totalUncompressedSize > 0 && listener != null) {
                                int progress = (int) ((currentExtractedBytes * 100L) / totalUncompressedSize);
                                listener.onProgress("Extracting: " + entry.getName(), progress);
                            }
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    public static String detectRootFolder(Path zipFilePath) {
        try (ZipFile zipFile = new ZipFile(zipFilePath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            String rootDir = null;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                int idx = name.indexOf('/');
                if (idx == -1)
                    return null;
                String currentPrefix = name.substring(0, idx);
                if (rootDir == null) {
                    rootDir = currentPrefix;
                } else if (!rootDir.equals(currentPrefix)) {
                    return null;
                }
            }
            return rootDir;
        } catch (IOException e) {
            return null;
        }
    }

    public static void executeExternalProcess(List<String> command, File workingDir, String logPrefix,
            ProgressListener listener)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(workingDir).redirectErrorStream(true).start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info("{}: {}", logPrefix, line);
                if (listener != null)
                    listener.onLog(line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException(logPrefix + " failed with exit code " + exitCode);
        }
    }

    /**
     * Base URL for MariaDB version API.
     */
    public static final String MARIA_DB_API_BASE_URL = "https://downloads.mariadb.org/rest-api/mariadb/";

    /**
     * JDBC Connection URL parsing pattern.
     */
    public static final Pattern JDBC_URL_PATTERN = Pattern
            .compile("jdbc:(mariadb|postgresql)://([^:/]+)(?::(\\d+))?/([^?]*)(.*)");

    /**
     * Default database credentials.
     */
    public static final String DEFAULT_DB_USER = "signumnode";
    public static final String DEFAULT_DB_PASSWORD = "s1gn00m_n0d3";

    /**
     * Pattern for validating database profile names.
     * Allows only alphanumeric characters, underscores, and hyphens.
     * Profile name must start with a letter or underscore and be 1-64 characters
     * long.
     */
    private static final Pattern VALID_PROFILE_NAME_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_-]{0,63}$");

    /**
     * Validates a database profile name.
     *
     * @param profileName The profile name to validate.
     * @return true if the profile name is valid (alphanumeric characters,
     *         underscores,
     *         and hyphens only; must start with a letter or underscore), false
     *         otherwise.
     */
    public static boolean isValidProfileName(String profileName) {
        if (profileName == null || profileName.isEmpty()) {
            return false;
        }
        return VALID_PROFILE_NAME_PATTERN.matcher(profileName).matches();
    }

    public static class DbUser {
        public String username;
        public String password;
        public String host;
        public String permissions;

        @Override
        public String toString() {
            if (username == null)
                return "";
            StringBuilder sb = new StringBuilder(username);
            if (host != null && !host.isEmpty())
                sb.append("@").append(host);
            if (permissions != null && !permissions.isEmpty())
                sb.append(" (").append(permissions).append(")");
            return sb.toString();
        }
    }

    public static class DbInstance {
        public String name;
        public String url;
        public List<DbUser> users = new ArrayList<>();

        @Override
        public String toString() {
            return name;
        }
    }

    public static class DbProfile {
        public String name;
        public List<DbInstance> databases = new ArrayList<>();

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Returns a simplified operating system name (e.g., "windows", "macos",
     * "linux")
     * based on the system property "os.name". This is used for database provider
     * compatibility.
     *
     * @return A string representing the simplified OS name.
     */
    public static String getOsName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win"))
            return "windows";
        if (os.contains("mac"))
            return "macos";
        if (os.contains("linux"))
            return "linux";
        return "unknown";
    }

    /**
     * Returns a simplified processor architecture name (e.g., "x64", "arm64")
     * based on the system property "os.arch".
     *
     * @return A string representing the simplified architecture name.
     */
    public static String getOsArch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("amd64") || arch.contains("x86_64"))
            return "x64";
        if (arch.contains("aarch64") || arch.contains("arm64"))
            return "arm64";
        return "unknown";
    }

    /**
     * Recursively deletes a directory and its contents.
     *
     * @param path The path to the directory or file to be deleted.
     * @throws IOException If an I/O error occurs during deletion.
     */
    public static void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                for (Path entry : entries) {
                    deleteDirectoryRecursively(entry);
                }
            }
        }
        Files.delete(path);
    }

    /**
     * Ensures that the base database directory (e.g., `../database`)
     * and engine-specific subdirectories (e.g., `../database/MariaDB`,
     * `../database/PostgreSQL`, `../database/SQLite`) exist.
     * If they do not exist, they are created.
     * exist.
     */
    public static void ensureDirectoryStructure() {
        try {
            Path baseDbPath = PathUtils.resolvePath(DATABASE_BASE_DIR);
            if (Files.notExists(baseDbPath)) {
                Files.createDirectories(baseDbPath);
                logger.info("Created base database directory: {}", baseDbPath);
            }

            for (DatabaseConfigurationPanel.DatabaseEngine engine : DatabaseConfigurationPanel.DatabaseEngine
                    .values()) {
                Path p = baseDbPath.resolve(engine.toString());
                if (Files.notExists(p)) {
                    Files.createDirectories(p);
                    logger.info("Created engine directory: {}", p);
                }
            }
        } catch (IOException e) {
            logger.error("Error ensuring database directory structure: {}", e.getMessage());
        }
    }

    /**
     * Loads the global database settings from the `settings.json` file located
     * in the `../database` directory.
     * If the file is missing or empty, it creates a default one.
     *
     * @return A {@link JsonObject} containing the global database settings.
     */
    public static synchronized JsonObject loadGlobalSettings() {
        if (cachedGlobalSettings != null) {
            return cachedGlobalSettings;
        }

        Path settingsFile = PathUtils.resolvePath(DATABASE_BASE_DIR).resolve("settings.json");
        if (Files.exists(settingsFile)) {
            try {
                if (Files.size(settingsFile) > 0) {
                    try (BufferedReader reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
                        logger.info("Loaded global settings from: {}", settingsFile);
                        cachedGlobalSettings = JsonParser.parseReader(reader).getAsJsonObject();
                        return cachedGlobalSettings;
                    }
                }
            } catch (Exception e) {
                logger.error("Error loading global settings from {}: {}", settingsFile, e.getMessage());
            }
        }
        cachedGlobalSettings = createDefaultSettingsJson(settingsFile);
        return cachedGlobalSettings;
    }

    /**
     * Creates a default `settings.json` file with predefined settings for MariaDB,
     * PostgreSQL, and SQLite.
     * This method is called if the `settings.json` file does not exist or is empty.
     * 
     * @param settingsFile The path where the default settings file should be
     *                     created.
     * @return A {@link JsonObject} containing the default settings.
     */
    private static JsonObject createDefaultSettingsJson(Path settingsFile) {
        JsonObject defaultSettings = new JsonObject();

        // MariaDB defaults
        JsonObject mariaDbSettings = new JsonObject();
        mariaDbSettings.addProperty("versionInfoUrl", MARIA_DB_API_BASE_URL);
        mariaDbSettings.addProperty("downloadBaseUrl", "https://downloads.mariadb.org/interstitial/");
        mariaDbSettings.addProperty("defaultDatabaseName", "signum");
        mariaDbSettings.addProperty("defaultAdminUsername", "root");
        mariaDbSettings.addProperty("defaultAdminPassword", "");
        mariaDbSettings.addProperty("defaultAppUsername", "signumnode");
        mariaDbSettings.addProperty("defaultAppUserPassword", "signum_pwd");
        defaultSettings.add("mariaDb", mariaDbSettings);

        // PostgreSQL defaults
        JsonObject postgresqlSettings = new JsonObject();
        postgresqlSettings.addProperty("versionListUrl", "https://example.com/api/postgresql/versions.json");
        postgresqlSettings.addProperty("downloadBaseUrl", "https://example.com/downloads/postgresql/");
        postgresqlSettings.addProperty("defaultDatabaseName", "signum");
        postgresqlSettings.addProperty("defaultAdminUsername", "postgres");
        postgresqlSettings.addProperty("defaultAdminPassword", "");
        postgresqlSettings.addProperty("defaultAppUsername", "signumnode");
        postgresqlSettings.addProperty("defaultAppUserPassword", "signum_pwd");
        defaultSettings.add("postgresql", postgresqlSettings);

        // SQLite defaults
        JsonObject sqliteSettings = new JsonObject();
        sqliteSettings.addProperty("versionListUrl", "");
        sqliteSettings.addProperty("downloadBaseUrl", "");
        sqliteSettings.addProperty("defaultDatabaseName", "signum.sqlite.db");
        defaultSettings.add("sqlite", sqliteSettings);

        try (BufferedWriter writer = Files.newBufferedWriter(settingsFile, StandardCharsets.UTF_8)) {
            writer.write(GSON.toJson(defaultSettings));
            logger.info("Created default global settings file: {}", settingsFile);
        } catch (IOException e) {
            logger.error("Error creating default global settings file {}: {}", settingsFile, e.getMessage());
        }
        return defaultSettings;
    }

    /**
     * Returns a list of profile names found in the configuration folder for a
     * specific engine.
     *
     * @param confFolder The base configuration folder.
     * @param engine     The database engine name.
     * @return A list of profile names.
     */
    public static List<String> getProfileNames(String confFolder, String engine) {
        // Prioritize Portable Database directory: ../database/<EngineName>
        Path base = PathUtils.resolvePath(DATABASE_BASE_DIR).resolve(engine);

        if (!Files.exists(base))
            return new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(base)) {
            List<String> names = new ArrayList<>();
            for (Path path : stream) {
                if (Files.isDirectory(path)) {
                    names.add(path.getFileName().toString());
                }
            }
            Collections.sort(names);
            return names;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Loads a database profile from a profile.json file.
     *
     * @param confFolder  The base configuration folder.
     * @param engine      The database engine name.
     * @param profileName The profile name.
     */
    public static DbProfile loadProfile(String confFolder, String engine, String profileName) {
        // Try Portable directory structure first
        Path profileDir = PathUtils.resolvePath(DATABASE_BASE_DIR).resolve(engine).resolve(profileName);

        Path jsonPath = profileDir.resolve("profile.json");
        if (!Files.exists(jsonPath))
            return null;

        try (BufferedReader reader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            DbProfile profile = new DbProfile();
            profile.name = profileName;

            // Handle new MariaDB portable profile format
            if (engine.equalsIgnoreCase("MariaDB") && (json.has("createdDatabases") || json.has("createdUsers"))) {
                try {
                    MariadbProfile mProfile = new MariadbProfile(profileName);
                    for (MariadbProfile.DatabaseInfo dbInfo : mProfile.getCreatedDatabases()) {
                        DbInstance instance = new DbInstance();
                        instance.name = dbInfo.name;
                        instance.url = "jdbc:mariadb://127.0.0.1:" + mProfile.getPort() + "/" + dbInfo.name;

                        for (MariadbProfile.UserInfo uInfo : mProfile.getCreatedUsers()) {
                            for (MariadbProfile.UserGrant grant : uInfo.grants) {
                                if (grant.databaseId.equals("global") || grant.databaseId.equals(dbInfo.id)) {
                                    DbUser user = new DbUser();
                                    user.username = uInfo.username;
                                    user.password = uInfo.password;
                                    user.host = uInfo.host;
                                    user.permissions = grant.permissions;
                                    instance.users.add(user);
                                }
                            }
                        }
                        profile.databases.add(instance);
                    }
                } catch (Exception e) {
                    logger.warn("Could not parse MariaDB profile '{}': {}", profileName, e.getMessage());
                }
            } else if (json.has("databases")) {
                // Legacy/Generic format
                JsonArray dbs = json.getAsJsonArray("databases");
                for (JsonElement dbEl : dbs) {
                    JsonObject dbObj = dbEl.getAsJsonObject();
                    DbInstance db = new DbInstance();
                    db.name = dbObj.get("name").getAsString();
                    db.url = dbObj.get("url").getAsString();
                    if (dbObj.has("users")) {
                        JsonArray users = dbObj.getAsJsonArray("users");
                        for (JsonElement uEl : users) {
                            JsonObject uObj = uEl.getAsJsonObject();
                            DbUser user = new DbUser();
                            user.username = uObj.get("username").getAsString();
                            user.password = uObj.get("password").getAsString();
                            db.users.add(user);
                        }
                    }
                    profile.databases.add(db);
                }
            }
            return profile;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A custom JComboBox that allows multiple checkbox selections.
     */
    public static class CheckSelectionComboBox extends JComboBox<String> {
        private final String initialValue;
        private final String[] options;
        private final List<JCheckBox> checkboxes = new ArrayList<>();
        private final String allKeyword;
        private final Consumer<String> onChange;

        public CheckSelectionComboBox(String initialValue, String[] options, String allKeyword,
                Consumer<String> onChange) {
            this.initialValue = initialValue;
            this.options = options;
            this.allKeyword = allKeyword;
            this.onChange = onChange;

            setFocusable(false); // Prevents the focus border from appearing on the header.

            // Manually trigger updateUI now that instance fields are ready.
            updateUI();
        }

        /**
         * Overrides updateUI to ensure that the custom popup is used, while
         * preserving the Look and Feel's default rendering for the combo box itself
         * and its arrow button.
         */
        @Override
        public void updateUI() {
            // Guard against early calls from JComboBox constructor before instance fields
            // are set.
            if (checkboxes == null || options == null || allKeyword == null) {
                super.updateUI();
                return;
            }

            checkboxes.clear();

            // First, let the default Look and Feel install its UI delegate.
            // UI replacement is avoided to prevent reflection and NPE issues.
            super.updateUI();

            setRenderer(new BasicComboBoxRenderer() {
                @Override
                public Component getListCellRendererComponent(JList list, Object value, int index, boolean isSelected,
                        boolean cellHasFocus) {
                    if (index == -1) {
                        // Do not show selection background for the closed header
                        isSelected = false;
                    }
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

                    // Text is overwritten with selected items only for the header (index == -1).
                    if (index == -1) {
                        String text = getSelectedItemsString();
                        setText(text.isEmpty() ? "None" : text);
                    } else {
                        // Dropdown list displays nothing (if visible)
                        // as a custom PopupMenu is used instead.
                        setText("");
                    }

                    return this;
                }
            });

            setModel(new DefaultComboBoxModel<>(new String[] { "" }));

            // Initialize checkboxes
            JCheckBox allCb = new JCheckBox(allKeyword);
            checkboxes.add(allCb);
            for (String opt : options) {
                checkboxes.add(new JCheckBox(opt));
            }

            updateState(initialValue);

            // Add listeners
            allCb.addActionListener(e -> {
                boolean sel = allCb.isSelected();
                for (int i = 1; i < checkboxes.size(); i++)
                    checkboxes.get(i).setSelected(sel);
                handleSelection();
            });

            for (int i = 1; i < checkboxes.size(); i++) {
                JCheckBox cb = checkboxes.get(i);
                cb.addActionListener(e -> {
                    if (!cb.isSelected())
                        checkboxes.get(0).setSelected(false);
                    else {
                        boolean allSelected = true;
                        for (int j = 1; j < checkboxes.size(); j++)
                            if (!checkboxes.get(j).isSelected())
                                allSelected = false;
                        checkboxes.get(0).setSelected(allSelected);
                    }
                    handleSelection();
                });
            }

            // Custom popup display is forced on every click,
            // as the Look & Feel might swallow the setPopupVisible call.
            MouseAdapter clickHandler = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (isEnabled())
                        showCustomPopup();
                }
            };
            this.addMouseListener(clickHandler);
            for (Component c : getComponents()) {
                c.addMouseListener(clickHandler);
            }
        }

        /**
         * Popup display is intercepted. A custom JPopupMenu is displayed instead
         * of using the internal, problematic JComboBox popup.
         */
        @Override
        public void setPopupVisible(boolean v) {
            if (v && isEnabled())
                showCustomPopup();
        }

        private void showCustomPopup() {
            if (checkboxes == null || checkboxes.isEmpty())
                return;

            JPopupMenu popup = new JPopupMenu();
            popup.setBorder(UIManager.getBorder("PopupMenu.border"));

            // Background is explicitly set from the theme.
            Color bg = UIManager.getColor("Panel.background");
            if (bg == null)
                bg = Color.WHITE;
            popup.setBackground(bg);

            // GridLayout(0, 1) ensures checkboxes are arranged vertically.
            JPanel panel = new JPanel(new java.awt.GridLayout(0, 1));
            panel.setBackground(bg);

            for (JCheckBox cb : checkboxes) {
                cb.setOpaque(true);
                cb.setBackground(bg);
                cb.setVisible(true);
                panel.add(cb);
            }

            JScrollPane sp = new JScrollPane(panel);
            sp.setBorder(null);
            sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            sp.getViewport().setBackground(bg);
            popup.add(sp);

            // Sizing: at least ComboBox width, height based on checkbox count.
            int width = Math.max(getWidth(), 250);
            int height = Math.min(checkboxes.size() * 25 + 10, 400);
            sp.setPreferredSize(new Dimension(width, height));

            popup.pack();
            popup.show(this, 0, getHeight());
        }

        private void handleSelection() {
            CheckSelectionComboBox.this.repaint();
            if (onChange != null)
                onChange.accept(getSelectedItemsString());
        }

        public void updateState(String value) {
            boolean isAll = allKeyword.equalsIgnoreCase(value);
            checkboxes.get(0).setSelected(isAll);
            java.util.Set<String> set = value == null ? java.util.Collections.emptySet()
                    : java.util.Arrays.stream(value.split(",")).map(String::trim).map(String::toUpperCase)
                            .collect(Collectors.toSet());

            for (int i = 1; i < checkboxes.size(); i++) {
                checkboxes.get(i).setSelected(isAll || set.contains(checkboxes.get(i).getText().toUpperCase()));
            }
            repaint();
        }

        public String getSelectedItemsString() {
            if (checkboxes.get(0).isSelected())
                return allKeyword;
            return checkboxes.stream().skip(1).filter(AbstractButton::isSelected)
                    .map(AbstractButton::getText).collect(Collectors.joining(","));
        }

    }
}
