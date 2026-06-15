package application.module.database.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import application.module.database.databaseConfiguration.DatabaseConfigurationUtils;
import application.module.database.databaseConfiguration.MariadbProfile;
import application.module.database.databaseConfiguration.DatabaseConfigurationUtils.ProgressListener;
import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.database.gui.DatabaseConfigurationPanel.PropertyRow;
import application.module.node.Signum;
import application.module.node.gui.configuration.ConfigurationUtils;
import application.utils.gui.CustomDrawingComponent;
import application.utils.gui.CustomDrawings;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiUtils;
import application.utils.gui.HelpButton;
import application.utils.io.PathUtils;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.HierarchyEvent;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * MariaDB specific configuration and version management.
 */
public class MariaDBConfigurationPanel extends JPanel implements DatabaseEnginePanel {
    private static final Logger logger = LoggerFactory.getLogger(MariaDBConfigurationPanel.class);
    public static final String API_BASE_URL = DatabaseConfigurationUtils.MARIA_DB_API_BASE_URL;

    private Runnable restartAction;
    private String confFolder;
    private Runnable backAction;

    private JsonObject globalSettings = new JsonObject(); // New: For settings.json
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private MariadbProfile currentProfile; // Refactored: Use MariadbProfile object
    private JsonObject appliedProfileSettings = new JsonObject();
    private final Map<String, Supplier<String>> valueSuppliers = new HashMap<>();
    private final Map<String, JComponent> propertyComponents = new HashMap<>();
    private final Map<String, String> helpTexts = new HashMap<>();
    private final Map<String, String> defaultValues = new HashMap<>();
    private Path activeProfilePath;
    private final Icon checkIcon = IconFontSwing.buildIcon(FontAwesome.CHECK_CIRCLE,
            GuiConstants.getHelpIconSize(),
            GuiColors.getApplied());
    private final Icon errorIcon = IconFontSwing.buildIcon(FontAwesome.TIMES_CIRCLE, GuiConstants.getHelpIconSize(),
            GuiColors.getContrastRed());
    private JButton downloadDatabaseBtn; // New: For download button
    private JButton removeDatabaseBtn; // New: For uninstall button
    private JComboBox<String> profileComboBox;
    private final List<PropertyRow> allPropertyRows = new ArrayList<>();
    private JPanel searchResultsPanel;
    private CardLayout contentCardLayout;
    private JButton downloadBtn;
    private JButton saveProfileBtn;
    private JButton renameProfileBtn;
    private JButton deleteProfileBtn;
    private JButton newProfileBtn;
    private JButton reloadProfileBtn;
    private JButton refreshProfilesBtn;
    private JPanel contentContainer;
    private JButton startDbBtn;
    private JButton stopDbBtn;
    private JButton restartDbBtn;
    private JTextPane consoleTextPane;
    private JPanel consoleWrapper;
    private CustomDrawingComponent consoleChevron;
    private JLabel consoleTitle;
    private boolean isConsoleExpanded = false;
    private Timer consoleAnimator;
    private JComponent verticalFiller;
    private String runningProfileName;
    private String activeProfileName;
    private String loadedProfileName;

    private JLabel step3HeaderLabel;
    private JPanel step3ContentPanel;
    private JLabel step2HeaderLabel;
    private JPanel dbControlPanel;
    private JPanel step2ContentPanel;
    private static final String[] COMMON_MARIADB_PARAMS = {
            "port", "datadir", "log_error", "pid_file", "bind-address", "innodb_flush_log_at_trx_commit",
            "max_connections", "innodb_buffer_pool_size", "query_cache_size", "max_allowed_packet",
            "character-set-server", "collation-server", "default-storage-engine", "thread_cache_size",
            "wait_timeout", "interactive_timeout", "connect_timeout", "table_open_cache",
            "key_buffer_size", "myisam_sort_buffer_size", "read_buffer_size", "read_rnd_buffer_size",
            "sort_buffer_size", "join_buffer_size", "tmp_table_size", "max_heap_table_size",
            "skip-networking", "slow_query_log", "long_query_time", "innodb_log_file_size",
            "innodb_log_buffer_size", "innodb_file_per_table", "lower_case_table_names",
            "event_scheduler", "performance_schema", "innodb_io_capacity", "innodb_read_io_threads",
            "innodb_write_io_threads", "query_cache_limit", "query_cache_type", "innodb_thread_concurrency",
            "innodb_lock_wait_timeout", "innodb_flush_method", "innodb_force_recovery",
            "log_bin", "expire_logs_days", "max_binlog_size", "binlog_format", "relay_log",
            "server_id", "read_only", "log_slave_updates", "slave_skip_errors"
    };
    private DatabaseEngine currentEngine = DatabaseEngine.MARIADB;
    private boolean isInitialized = false;
    private JLabel step1StatusIcon; // Status icon for download/install step
    private int consoleHeight = 250;
    private JComboBox<String> majorVersionCombo;
    private JComboBox<String> minorVersionCombo;
    private JComboBox<String> patchVersionCombo;
    private Map<String, MariaDBConfigurationPanel.MainVersionInfo> allVersionsMap = new HashMap<>(); // Stores all

    // version data for the
    // current engine
    private String currentOsName;
    private JLabel step2StatusIcon; // New: Status icon for setup step
    private JLabel downloadStatusLabel; // New: For download status
    private JLabel installedVersionLabel;
    private JLabel pathLabel;
    private String currentOsArch; // Missing field added
    private JButton initializeDatabaseBtn; // New: For initializing the database
    private JButton updateConfigFileBtn; // New: For updating my.ini
    private JButton openConfigFileBtn; // New: For opening my.ini
    private JLabel step3StatusIcon; // New: Status icon for the new Step 3
    private JTabbedPane tabbedPane;
    private JPanel dbListPanel;
    private JPanel userListPanel;
    private final Map<String, List<JCheckBox>> permissionCheckboxMaps = new HashMap<>();

    // Constants for permissions
    private static final String[] TYPICAL_HOSTS = { "localhost", "%", "127.0.0.1", "::1" };
    private static final String ALL_PERMISSIONS_KEYWORD = "ALL";
    private static final String[] GRANULAR_PERMISSIONS = {
            "SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALTER", "GRANT OPTION"
    };
    private JPanel tempGrantsContainer;
    private final List<MariadbProfile.UserGrant> tempGrantsForNewUser = new ArrayList<>();

    // --- DatabaseEnginePanel Interface Implementation ---

    @Override
    public String getEngineName() {
        return DatabaseEngine.MARIADB.getDisplayName();
    }

    @Override
    public Path getProfilePath(String profileName) {
        return PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                .resolve(DatabaseEngine.MARIADB.toString()).resolve(profileName);
    }

    @Override
    public void loadProfile(String profileName, JsonObject globalSettings) {
        this.globalSettings = globalSettings;
        loadProfile(DatabaseEngine.MARIADB, profileName);
    }

    @Override
    public void saveProfile(String profileName, JsonObject globalSettings) {
        if (currentProfile != null) {
            try {
                currentProfile.saveToProfileJson(new HashMap<>()); // Saves current state
            } catch (IOException e) {
                logger.error("Failed to save profile: {}", e.getMessage());
            }
        }
    }

    @Override
    public void resetToDefaults(JsonObject globalSettings) {
        if (currentProfile != null) {
            this.currentProfile = new MariadbProfile(loadedProfileName);
            refreshStep2DynamicContent();
            updateUIFromData();
        }
    }

    @Override
    public String getUnsavedChangesReport() {
        return hasUnsavedChanges() ? "MariaDB profile has unsaved configuration changes." : null;
    }

    @Override
    public boolean hasUnsavedChanges() {
        if (currentProfile == null)
            return false;
        for (String key : currentProfile.getVisibleProperties()) {
            String currentVal = valueSuppliers.get(key) != null ? valueSuppliers.get(key).get() : "";
            String savedVal = currentProfile.getConfiguration().getOrDefault(key, "");
            if (!currentVal.equals(savedVal))
                return true;
        }
        return false;
    }

    @Override
    public void setLoadedProfileName(String name) {
        this.loadedProfileName = name;
    }

    @Override
    public String getLoadedProfileName() {
        return loadedProfileName;
    }

    @Override
    public void setRunningProfileName(String name) {
        this.runningProfileName = name;
    }

    @Override
    public String getRunningProfileName() {
        return runningProfileName;
    }

    @Override
    public void setActiveProfileName(String name) {
        this.activeProfileName = name;
    }

    @Override
    public String getActiveProfileName() {
        return activeProfileName;
    }

    @Override
    public JsonObject getCurrentProfileSettings() {
        return currentProfile != null ? currentProfile.toJsonObject() : new JsonObject();
    }

    @Override
    public void setAppliedProfileSettings(JsonObject settings) {
        this.appliedProfileSettings = settings;
        refreshUIColors();
    }

    @Override
    public void setOnDirtyStatusChanged(Runnable listener) {
        /* Could be connected to UI listeners */ }

    @Override
    public List<PropertyRow> getAllPropertyRows() {
        return allPropertyRows;
    }

    @Override
    public Map<String, JComponent> getPropertyComponents() {
        return propertyComponents;
    }

    @Override
    public Map<String, Supplier<String>> getValueSuppliers() {
        return valueSuppliers;
    }

    @Override
    public Map<String, String> getHelpTexts() {
        return helpTexts;
    }

    @Override
    public Map<String, String> getDefaultValues() {
        return defaultValues;
    }

    @Override
    public void setPathLabel(JLabel label) {
        this.pathLabel = label;
    }

    @Override
    public void setDownloadDatabaseBtn(JButton button) {
        this.downloadDatabaseBtn = button;
    }

    @Override
    public void setDownloadStatusLabel(JLabel label) {
        this.downloadStatusLabel = label;
    }

    @Override
    public void setStep1StatusIcon(JLabel label) {
        this.step1StatusIcon = label;
    }

    @Override
    public void setStep2StatusIcon(JLabel label) {
        this.step2StatusIcon = label;
    }

    @Override
    public void setGlobalSettings(JsonObject globalSettings) {
        this.globalSettings = globalSettings;
    }

    @Override
    public void refreshUIColors() {
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            updateColor(entry.getValue(), entry.getKey());
        }
    }

    private static class MaintenanceTask {
        final boolean shouldDownload;
        final boolean shouldExtract;

        MaintenanceTask(boolean shouldDownload, boolean shouldExtract) {
            this.shouldDownload = shouldDownload;
            this.shouldExtract = shouldExtract;
        }
    }

    public static class MainVersionInfo {
        public String name;
        public List<SubVersionInfo> subVersions = new ArrayList<>();
    }

    public static class SubVersionInfo {
        public String name;
        public List<DownloadEntry> downloads = new ArrayList<>();
    }

    public static class DownloadEntry {
        public String os;
        public String arch;
        public String file;
    }

    private static class ProgressInfo {
        final String message;
        final int progress;

        ProgressInfo(String message, int progress) {
            this.message = message;
            this.progress = progress;
        }
    }

    public static List<MainVersionInfo> fetchMajorReleases(String apiUrl) {
        List<MainVersionInfo> mainVersions = new ArrayList<>();
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    JsonObject response = JsonParser.parseReader(reader).getAsJsonObject();
                    if (response.has("major_releases")) {
                        response.getAsJsonArray("major_releases").forEach(el -> {
                            JsonObject obj = el.getAsJsonObject();
                            MainVersionInfo mv = new MainVersionInfo();
                            mv.name = obj.get("release_id").getAsString();
                            mainVersions.add(mv);
                        });
                        logger.debug("Successfully fetched {} MariaDB major releases.", mainVersions.size());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching MariaDB major releases: {}", e.getMessage());
        }
        return mainVersions;
    }

    private void initUI() {
        JPanel bodyPanel = new JPanel(new BorderLayout());

        // Initialize status icons to be placed in headers
        step1StatusIcon = new JLabel();
        step1StatusIcon.setVisible(false);
        step2StatusIcon = new JLabel();
        step2StatusIcon.setVisible(false);
        step3StatusIcon = new JLabel();
        step3StatusIcon.setVisible(false);

        // Initialize pathLabel early to avoid NPE when early profile refresh is
        // triggered
        pathLabel = new JLabel("Profile Directory: N/A");
        pathLabel.setForeground(GuiColors.getFaintText());

        // --- Profile Panel ---
        JPanel profilePanel = new JPanel(new MigLayout("insets 0, gap 5"));
        profilePanel.setBorder(new EmptyBorder(5, 10, 5, 5));
        profilePanel.add(new JLabel("Configuration Profile:"));

        profileComboBox = new JComboBox<>();
        profileComboBox.setEditable(false);
        profileComboBox.setPrototypeDisplayValue("XXXXXXXXXXXXXXXXXXXX");
        ConfigurationUtils.fixComponentSize(profileComboBox);
        profilePanel.add(profileComboBox);

        profileComboBox.setRenderer(
                ConfigurationUtils.createProfileComboBoxRenderer(() -> runningProfileName, () -> activeProfileName));

        newProfileBtn = new JButton("New Profile");
        newProfileBtn.setToolTipText("Create a new profile initialized with application defaults");
        newProfileBtn.addActionListener(e -> createNewProfile());
        profilePanel.add(newProfileBtn);

        saveProfileBtn = new JButton("Save Profile");
        saveProfileBtn.setToolTipText("Save and apply selected profile to the node");
        saveProfileBtn.addActionListener(e -> saveProfile());
        profilePanel.add(saveProfileBtn);

        renameProfileBtn = new JButton("Rename Profile");
        renameProfileBtn.setToolTipText("Rename selected profile");
        renameProfileBtn.addActionListener(e -> renameProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(renameProfileBtn);

        deleteProfileBtn = new JButton("Delete Profile");
        deleteProfileBtn.setToolTipText("Delete selected profile");
        deleteProfileBtn.addActionListener(e -> deleteProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(deleteProfileBtn);

        reloadProfileBtn = new JButton("Reload Profile");
        reloadProfileBtn.setToolTipText("Reload settings from the current profile file on disk");
        reloadProfileBtn.addActionListener(e -> reloadProfile());
        profilePanel.add(reloadProfileBtn);

        refreshProfilesBtn = new JButton("Refresh Profiles");
        refreshProfilesBtn.setToolTipText("Refresh the list of available profiles from the disk");
        refreshProfilesBtn.addActionListener(e -> refreshProfileList());
        profilePanel.add(refreshProfilesBtn);

        updateProfileButtonsUI();

        profileComboBox.addActionListener(e -> {
            if (!isInitialized) {
                return;
            }
            String selected = (String) profileComboBox.getSelectedItem();
            if (selected != null && !selected.equals(loadedProfileName)) { // Only load if different and not null
                loadProfile(currentEngine, selected); // This will update currentEngine if changed
            }
            updateProfileComboBoxColor();
            updateProfileButtonStates();
        });

        JButton helpBtn = new HelpButton();
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("View detailed information about database profile management");
        helpBtn.addActionListener(e -> showProfileHelp());
        profilePanel.add(helpBtn);

        JScrollPane profileScrollPane = new JScrollPane(profilePanel);
        profileScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        profileScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        profileScrollPane.setBorder(BorderFactory.createEmptyBorder());
        profileScrollPane.setOpaque(false);
        profileScrollPane.getViewport().setOpaque(false);

        GuiUtils.addHorizontalScrollPadding(profileScrollPane, profilePanel, new Insets(5, 10, 5, 5));

        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                refreshProfileList();
            }
        });

        // --- Search Panel ---
        JPanel searchPanel = new JPanel(new MigLayout("insets 5 10 5 5, fillx", "[][grow]", "[]"));
        searchPanel.add(new JLabel("Search Configuration:"));
        JTextField searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "Type to filter properties...");
        ConfigurationUtils.styleInputComponent(searchField);
        searchPanel.add(searchField, "growx");

        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                if (isInitialized) {
                    filterProperties(searchField.getText());
                }
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                if (isInitialized) {
                    filterProperties(searchField.getText());
                }
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                if (isInitialized) {
                    filterProperties(searchField.getText());
                }
            }
        });

        // --- Database Control Panel ---
        dbControlPanel = new JPanel(new MigLayout("insets 5 10 0 5, gap 5, fillx", "[grow]", "[]5[]0[]"));
        dbControlPanel.setOpaque(false);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        btnRow.setOpaque(false);

        startDbBtn = new JButton("Start Database",
                IconFontSwing.buildIcon(FontAwesome.PLAY, GuiConstants.getHelpIconSize(), GuiColors.getApplied()));
        stopDbBtn = new JButton("Stop Database",
                IconFontSwing.buildIcon(FontAwesome.STOP, GuiConstants.getHelpIconSize(), GuiColors.getContrastRed()));
        restartDbBtn = new JButton("Restart Database",
                IconFontSwing.buildIcon(FontAwesome.REFRESH, GuiConstants.getHelpIconSize(), GuiColors.getSaved()));

        ConfigurationUtils.fixComponentSize(startDbBtn);
        ConfigurationUtils.fixComponentSize(stopDbBtn);
        ConfigurationUtils.fixComponentSize(restartDbBtn);

        startDbBtn.addActionListener(e -> executeStartDatabaseWorker());
        stopDbBtn.addActionListener(e -> executeStopDatabaseWorker());
        restartDbBtn.addActionListener(e -> executeRestartDatabaseWorker());

        btnRow.add(startDbBtn);

        btnRow.add(stopDbBtn);
        btnRow.add(restartDbBtn);
        dbControlPanel.add(btnRow, "wrap");

        // --- Console Section ---
        JPanel consoleHeader = new JPanel(new MigLayout("insets 5 0 0 0, gap 5", "[][grow]", "[]"));
        consoleHeader.setOpaque(false);

        consoleChevron = new CustomDrawingComponent(CustomDrawings.Chevron.DOWN);
        consoleChevron.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        consoleChevron.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                toggleConsole();
            }
        });

        consoleTitle = new JLabel("Console Output");
        consoleTitle.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 13f));
        consoleTitle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        consoleTitle.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                toggleConsole();
            }
        });

        consoleHeader.add(consoleChevron);
        consoleHeader.add(consoleTitle, "growx");
        dbControlPanel.add(consoleHeader, "growx, wrap");
        dbControlPanel.add(new JSeparator(SwingConstants.HORIZONTAL), "growx, wrap, gaptop 2");

        consoleWrapper = new JPanel(new BorderLayout());
        consoleWrapper.setOpaque(false);
        consoleWrapper.setPreferredSize(new Dimension(10, 0)); // Start collapsed

        consoleTextPane = new JTextPane();
        consoleTextPane.setEditable(false);
        consoleTextPane.setBackground(UIManager.getColor("TextArea.background"));
        consoleTextPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JScrollPane consoleScroll = new JScrollPane(consoleTextPane);
        consoleScroll.setBorder(BorderFactory.createEmptyBorder());
        consoleWrapper.add(consoleScroll, BorderLayout.CENTER);

        JPanel resizer = new JPanel();
        resizer.setPreferredSize(new Dimension(10, 5));
        resizer.setCursor(Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR));
        resizer.setOpaque(false);
        MouseAdapter resizerAdapter = new MouseAdapter() {
            private int startY;
            private int startH;

            @Override
            public void mousePressed(MouseEvent e) {
                startY = e.getYOnScreen();
                startH = consoleWrapper.getHeight();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int newH = Math.max(100, startH + (e.getYOnScreen() - startY));
                consoleHeight = newH;
                consoleWrapper.setPreferredSize(new Dimension(consoleWrapper.getWidth(), newH));
                dbControlPanel.revalidate();
            }
        };
        resizer.addMouseListener(resizerAdapter);
        resizer.addMouseMotionListener(resizerAdapter);

        // --- Console Input Section ---
        JPanel consoleInputPanel = new JPanel(new BorderLayout(5, 0));
        consoleInputPanel.setOpaque(false);
        consoleInputPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));

        JPanel southContainer = new JPanel(new BorderLayout());
        southContainer.setOpaque(false);
        southContainer.add(consoleInputPanel, BorderLayout.CENTER);
        southContainer.add(resizer, BorderLayout.SOUTH);

        JComponent commandSymbol = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                CustomDrawings.COMMAND_SYMBOL.draw((Graphics2D) g, getWidth(), getHeight(), GuiColors.getButtonIcon());
            }

            @Override
            public Dimension getPreferredSize() {
                int size = Math.round(GuiConstants.getToolBarIconSize());
                return new Dimension(size, size);
            }

            @Override
            public Dimension getMaximumSize() {
                return getPreferredSize();
            }
        };
        commandSymbol.setToolTipText("Command Input");

        JTextField consoleInputField = new JTextField();
        consoleInputField.putClientProperty("JTextField.placeholderText", "Enter database command...");
        ConfigurationUtils.styleInputComponent(consoleInputField);

        JButton sendCommandBtn = new JButton("Send");
        ConfigurationUtils.fixComponentSize(sendCommandBtn);

        ActionListener sendAction = e -> {
            String cmd = consoleInputField.getText().trim();
            if (!cmd.isEmpty()) {
                appendLog("> " + cmd);
                // Run command in a separate thread to avoid blocking the UI
                new Thread(() -> {
                    currentProfile.runClientCommand(cmd, new DatabaseConfigurationUtils.ProgressListener() {
                        @Override
                        public void onProgress(String message, int progress) {
                        }

                        @Override
                        public void onLog(String line) {
                            MariaDBConfigurationPanel.this.appendLog(line);
                        }
                    });
                }).start();
                consoleInputField.setText("");
            }
        };
        consoleInputField.addActionListener(sendAction);
        sendCommandBtn.addActionListener(sendAction);

        JButton consoleHelpBtn = new HelpButton();
        consoleHelpBtn.setToolTipText("Command Help");
        consoleHelpBtn.addActionListener(e -> {
            String helpText = "<html><b>Available Commands:</b><br>" +
                    "Enter commands here to interact with the MariaDB instance via this console.</html>";
            JOptionPane.showMessageDialog(this, helpText, "Console Usage", JOptionPane.INFORMATION_MESSAGE);
        });

        JPanel consoleBtnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        consoleBtnRow.setOpaque(false);
        consoleBtnRow.add(sendCommandBtn);
        consoleBtnRow.add(consoleHelpBtn);

        consoleInputPanel.add(commandSymbol, BorderLayout.WEST);
        consoleInputPanel.add(consoleInputField, BorderLayout.CENTER);
        consoleInputPanel.add(consoleBtnRow, BorderLayout.EAST);

        consoleWrapper.add(southContainer, BorderLayout.SOUTH);

        dbControlPanel.add(consoleWrapper, "growx, h pref!, hidemode 3");

        JPanel northPanel = new JPanel(new BorderLayout());
        JPanel northTopPanel = new JPanel(new BorderLayout());
        northTopPanel.add(dbControlPanel, BorderLayout.NORTH);
        northTopPanel.add(profileScrollPane, BorderLayout.CENTER);
        northPanel.add(northTopPanel, BorderLayout.NORTH);
        northPanel.add(searchPanel, BorderLayout.SOUTH);
        bodyPanel.add(northPanel, BorderLayout.NORTH);

        // Clear list before rebuilding UI (in case of re-init)
        allPropertyRows.clear();

        JPanel dbSettingsPanel = new JPanel(new MigLayout("fillx, insets 10, gap 10", "[][grow]", ""));

        // Step 1: Download and Install Database
        addSectionHeader(dbSettingsPanel, "Step 1: Download and Install Database Engine", step1StatusIcon, true);
        JPanel step1ContentPanel = new JPanel(new MigLayout("fillx, insets 0 25 0 0, gap 5", "[][grow]", ""));
        step1ContentPanel.setOpaque(false);
        populateStep1Content(step1ContentPanel);
        dbSettingsPanel.add(step1ContentPanel, "span, growx, wrap");

        // Step 2: Run and Set up Database
        addSectionHeader(dbSettingsPanel, "Step 2: Initialize Database Engine", step2StatusIcon, false);
        this.step2ContentPanel = new JPanel(new MigLayout("fillx, insets 0 25 0 0, gap 5", "[][grow]", ""));
        this.step2ContentPanel.setOpaque(false);
        populateStep2Content(this.step2ContentPanel);
        dbSettingsPanel.add(this.step2ContentPanel, "span, growx, wrap, hidemode 3");

        // New Step 3: Configure Database and Users
        addSectionHeader(dbSettingsPanel, "Step 3: Configure Database and Users", step3StatusIcon, false);
        this.step3ContentPanel = new JPanel(new MigLayout("fillx, insets 0 25 0 0, gap 5", "[][grow]", ""));
        this.step3ContentPanel.setOpaque(false);
        populateStep3Content(this.step3ContentPanel); // New method for original Step 2 content
        dbSettingsPanel.add(this.step3ContentPanel, "span, growx, wrap, hidemode 3");

        refreshProfileList(); // This will also call updateMainVersionComboBox
        // updateVersionComboBox(); // Redundant call, refreshProfileList already calls
        // updateMainVersionComboBox

        verticalFiller = new JLabel();
        dbSettingsPanel.add(verticalFiller, "pushy");

        // --- Content Container (CardLayout for Settings vs Search Results) ---
        contentCardLayout = new CardLayout();
        contentContainer = new JPanel(contentCardLayout);

        JScrollPane scrollPane = new JScrollPane(dbSettingsPanel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        contentContainer.add(scrollPane, "SETTINGS");

        searchResultsPanel = new JPanel(new MigLayout("fillx, insets 10, gap 5", "[][grow]", ""));
        JScrollPane searchScrollPane = new JScrollPane(searchResultsPanel);
        searchScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        searchScrollPane.setBorder(BorderFactory.createEmptyBorder());
        contentContainer.add(searchScrollPane, "SEARCH");

        bodyPanel.add(contentContainer, BorderLayout.CENTER);
        // --- Bottom Panel with Buttons and File Path ---
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 0));
        bottomPanel.setBorder(new EmptyBorder(5, 10, 5, 5));

        // Legend
        bottomPanel.add(createLegendPanel(), BorderLayout.NORTH);

        // File path field
        if (activeProfilePath != null) {
            pathLabel.setText("Profile Directory: " + activeProfilePath.toAbsolutePath().toString());
        }
        bottomPanel.add(pathLabel, BorderLayout.CENTER);

        JPanel bottomContainer = new JPanel(new BorderLayout());
        bottomContainer.add(new JSeparator(SwingConstants.HORIZONTAL), BorderLayout.NORTH);
        bottomContainer.add(bottomPanel, BorderLayout.CENTER);
        bodyPanel.add(bottomContainer, BorderLayout.SOUTH);
        add(bodyPanel, BorderLayout.CENTER);
    }

    private void populateStep1Content(JPanel panel) {
        installedVersionLabel = new JLabel("No MariaDB version installed in this profile.");
        installedVersionLabel.setForeground(GuiColors.getFaintText());
        panel.add(installedVersionLabel, "span, wrap, gapbottom 5");

        panel.add(new JLabel("Version:"), "split 2, gaptop 5");

        JPanel versionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        versionPanel.setOpaque(false);

        majorVersionCombo = new JComboBox<>();
        minorVersionCombo = new JComboBox<>();
        patchVersionCombo = new JComboBox<>();

        ConfigurationUtils.fixComponentSize(majorVersionCombo);
        ConfigurationUtils.fixComponentSize(minorVersionCombo);
        ConfigurationUtils.fixComponentSize(patchVersionCombo);

        majorVersionCombo.addActionListener(e -> updateMinorCombo());
        minorVersionCombo.addActionListener(e -> updateSubVersionComboBox());
        patchVersionCombo.addActionListener(e -> checkDownloadAvailability());

        versionPanel.add(majorVersionCombo);
        versionPanel.add(new JLabel("."));
        versionPanel.add(minorVersionCombo);
        versionPanel.add(new JLabel("."));
        versionPanel.add(patchVersionCombo);

        panel.add(versionPanel, "growx, height pref!, wrap, gaptop 5");

        downloadDatabaseBtn = new JButton("Download & Install");
        downloadDatabaseBtn
                .setToolTipText("Download and install selected portable database version into the current profile.");
        downloadDatabaseBtn.addActionListener(e -> downloadAndInstallDatabase());
        ConfigurationUtils.fixComponentSize(downloadDatabaseBtn);

        removeDatabaseBtn = new JButton("Remove & Uninstall");
        removeDatabaseBtn.setToolTipText(
                "Remove all database binaries and data files from this profile, resetting it to a clean state.");
        removeDatabaseBtn.addActionListener(e -> removeAndUninstallDatabase());
        ConfigurationUtils.fixComponentSize(removeDatabaseBtn);

        panel.add(downloadDatabaseBtn, "split 2, gapleft 5");
        panel.add(removeDatabaseBtn, "wrap");

        downloadStatusLabel = new JLabel("");
        downloadStatusLabel.setForeground(GuiColors.getFaintText());
        panel.add(downloadStatusLabel, "span, wrap");

        updateMainVersionComboBox(); // Initial load of main versions
    }

    // Renamed and modified from original populateStep2Content
    private void populateStep2Content(JPanel panel) {
        refreshStep2DynamicContent();
    }

    private void refreshStep2DynamicContent() {
        if (step2ContentPanel == null || currentProfile == null)
            return;

        step2ContentPanel.removeAll();

        // Rebuild property list for this section
        allPropertyRows.removeIf(row -> row.originalParent == step2ContentPanel);
        configurationKeysTracked.forEach(key -> {
            propertyComponents.remove(key);
            valueSuppliers.remove(key);
        });
        configurationKeysTracked.clear();

        for (String key : currentProfile.getVisibleProperties()) {
            configurationKeysTracked.add(key);
            if (key.equals("innodb_flush_log_at_trx_commit")) {
                String[] innodbFlushOptions = { "0", "1", "2" };
                addProperty(step2ContentPanel, key, key, innodbFlushOptions, false);
            } else {
                addProperty(step2ContentPanel, key, key);
            }
        }

        addQuickAddSection(step2ContentPanel);
        addStep2Buttons(step2ContentPanel);

        step2ContentPanel.revalidate();
        step2ContentPanel.repaint();
    }

    private final List<String> configurationKeysTracked = new ArrayList<>();

    private void addQuickAddSection(JPanel panel) {
        JPanel quickAddPanel = new JPanel(new MigLayout("insets 0, gap 5", "[][][grow][][]", ""));
        quickAddPanel.setOpaque(false);

        quickAddPanel.add(new JLabel("Add Parameter:"), "align label");
        List<String> filteredParams = new ArrayList<>();
        Set<String> visible = currentProfile != null ? currentProfile.getVisibleProperties()
                : Collections.<String>emptySet();
        for (String p : COMMON_MARIADB_PARAMS) {
            if (!visible.contains(p))
                filteredParams.add(p);
        }
        JComboBox<String> paramCombo = new JComboBox<>(filteredParams.toArray(new String[0]));
        paramCombo.setEditable(true);
        paramCombo.setPrototypeDisplayValue("XXXXXXXXXXXXXXXXXXXX");
        ConfigurationUtils.styleInputComponent(paramCombo);
        ConfigurationUtils.fixComponentSize(paramCombo);
        quickAddPanel.add(paramCombo);

        JTextField paramValueField = createStyledTextField("");
        paramValueField.putClientProperty("JTextField.placeholderText", "Value");
        ConfigurationUtils.fixComponentSize(paramValueField);
        quickAddPanel.add(paramValueField, "growx");

        JButton addParamBtn = new JButton("Add");
        ConfigurationUtils.fixComponentSize(addParamBtn);
        addParamBtn.setToolTipText("Add selected parameter to the custom configuration area.");
        addParamBtn.addActionListener(e -> {
            String key = (String) paramCombo.getSelectedItem();
            String value = paramValueField.getText();
            if (key != null && !key.trim().isEmpty() && currentProfile != null) {
                currentProfile.addVisibleProperty(key.trim(), value.trim());
                paramValueField.setText(""); // Clear value field
                refreshStep2DynamicContent();
                updateUIFromData();
            }
        });
        quickAddPanel.add(addParamBtn);

        JButton addParamHelp = new HelpButton();
        addParamHelp.setToolTipText(
                "Select or type a MariaDB parameter name and provide a value to add it to the configuration.");
        quickAddPanel.add(addParamHelp, "wrap");

        panel.add(quickAddPanel, "span, growx, wrap, gaptop 10");
    }

    private void addStep2Buttons(JPanel panel) {
        JPanel configActionsPanel = new JPanel(new MigLayout("insets 0, gap 5", "", ""));
        configActionsPanel.setOpaque(false);

        initializeDatabaseBtn = new JButton("Initialize Database");
        ConfigurationUtils.fixComponentSize(initializeDatabaseBtn);
        initializeDatabaseBtn.setToolTipText("Initializes the MariaDB instance in the profile folder.");
        initializeDatabaseBtn.addActionListener(e -> initializeDatabase());
        configActionsPanel.add(initializeDatabaseBtn);

        updateConfigFileBtn = new JButton("Update my.ini");
        ConfigurationUtils.fixComponentSize(updateConfigFileBtn);
        updateConfigFileBtn.setToolTipText("Rewrite the my.ini/my.cnf file with current settings.");
        updateConfigFileBtn.addActionListener(e -> updateConfigFile());
        configActionsPanel.add(updateConfigFileBtn);

        openConfigFileBtn = new JButton("Open my.ini");
        ConfigurationUtils.fixComponentSize(openConfigFileBtn);
        openConfigFileBtn.setToolTipText("Open the my.ini/my.cnf file in your default text editor.");
        openConfigFileBtn.addActionListener(e -> openConfigFile());
        configActionsPanel.add(openConfigFileBtn, "wrap");

        panel.add(configActionsPanel, "span, growx, wrap, gaptop 5");
    }

    // New method for the content that was originally in Step 2
    private void populateStep3Content(JPanel panel) {
        // Section: Admin Credentials (Requirement 1)
        addSectionHeader(panel, "Admin Credentials (Root Access)", null, false);
        addProperty(panel, "adminUsername", "Admin Username"); // This is for the MariaDB root user
        addPasswordProperty(panel, "adminPassword", "Admin Password"); // This is for the MariaDB root password

        JButton changeAdminBtn = new JButton("Change Admin credentials");
        ConfigurationUtils.fixComponentSize(changeAdminBtn);
        changeAdminBtn.addActionListener(e -> showChangeAdminCredentialsDialog());
        panel.add(changeAdminBtn, "skip 1, wrap, gaptop 5");

        // Section: Databases (Requirement 3)
        addSectionHeader(panel, "Created Databases", null, false);
        dbListPanel = new JPanel(new MigLayout("fillx, insets 0", "[grow]", ""));
        dbListPanel.setOpaque(false);
        panel.add(dbListPanel, "span, growx, wrap, gaptop 10");

        JPanel addDbInnerPanel = new JPanel(new MigLayout("insets 0, gap 5", "[][grow][]", ""));
        addDbInnerPanel.setOpaque(false);
        JTextField newDbNameField = createStyledTextField("signum");
        newDbNameField.putClientProperty("JTextField.placeholderText", "Database name...");
        JButton addBtn = new JButton("ADD");
        addBtn.addActionListener(e -> executeCreateDatabaseWorker(newDbNameField.getText().trim()));

        addDbInnerPanel.add(new JLabel("Add Database:"));
        addDbInnerPanel.add(newDbNameField, "growx");
        addDbInnerPanel.add(addBtn);
        addDbInnerPanel.add(new HelpButton(), "wrap");
        panel.add(addDbInnerPanel, "span, growx, wrap, gapbottom 10");

        // Section: Database User Credentials (Requirement 1 & 2)
        addSectionHeader(panel, "Database Users", null, false); // Changed title as per request
        userListPanel = new JPanel(new MigLayout("fillx, insets 0", "[grow]", ""));
        userListPanel.setOpaque(false);
        panel.add(userListPanel, "span, growx, wrap, gaptop 5");

        // Add New Database User section (Requirement 1, 2, 5)
        addSectionHeader(panel, "Add New Database User", null, false);
        JPanel addUserInnerPanel = new JPanel(
                new MigLayout("insets 0, gap 5, fillx", "[pref!][grow][pref!][grow]", ""));
        addUserInnerPanel.setOpaque(false);

        // User input fields
        addUserInnerPanel.add(new JLabel("User:"), "align right");
        JTextField newUserNameField = createStyledTextField("");
        newUserNameField.putClientProperty("JTextField.placeholderText", "Username...");
        addUserInnerPanel.add(newUserNameField, "growx");

        addUserInnerPanel.add(new JLabel("Host:"), "align right");
        JComboBox<String> newUserHostCombo = new JComboBox<>(TYPICAL_HOSTS);
        newUserHostCombo.setEditable(true);
        newUserHostCombo.setSelectedItem("localhost");
        ConfigurationUtils.styleInputComponent(newUserHostCombo);
        ConfigurationUtils.fixComponentSize(newUserHostCombo);
        addUserInnerPanel.add(newUserHostCombo, "growx, wrap");

        addUserInnerPanel.add(new JLabel("Password:"), "align right"); // Password row starts on a new line
        JPasswordField newUserPassField = new JPasswordField();
        ConfigurationUtils.styleInputComponent(newUserPassField);
        addUserInnerPanel.add(newUserPassField, "growx, span, wrap");

        JCheckBox showNewUserPass = new JCheckBox("Show Password");
        char defaultEchoChar = newUserPassField.getEchoChar();
        showNewUserPass.addActionListener(
                e -> newUserPassField.setEchoChar(showNewUserPass.isSelected() ? (char) 0 : defaultEchoChar));
        addUserInnerPanel.add(showNewUserPass, "skip 1, wrap, gapbottom 10");

        // Temporary grants section for new user
        tempGrantsContainer = new JPanel( // (Requirement 1)
                new MigLayout("insets 0 0 0 0, fillx, gap 5", "[align right][grow][grow][pref]", ""));
        tempGrantsContainer.setOpaque(false);
        addUserInnerPanel.add(tempGrantsContainer, "span, growx, wrap");

        JButton addUserBtn = new JButton("ADD USER");
        addUserBtn.addActionListener(e -> {
            String name = newUserNameField.getText().trim();
            String pass = new String(newUserPassField.getPassword());
            String host = newUserHostCombo.getSelectedItem() != null ? newUserHostCombo.getSelectedItem().toString()
                    : "localhost";
            if (!name.isEmpty()) {
                try { // Pass host to addCreatedUser
                    currentProfile.addCreatedUser(name, pass, host, new ArrayList<>(tempGrantsForNewUser));
                    tempGrantsForNewUser.clear();
                    updateUIFromData();
                    newUserNameField.setText("");
                    newUserPassField.setText("");
                } catch (IOException ex) {
                    logger.error("Failed to add user", ex);
                }
            }
        });
        addUserInnerPanel.add(addUserBtn, "span, align left, wrap, gaptop 5");
        panel.add(addUserInnerPanel, "span, growx, wrap, gapbottom 10");
    }

    private void showChangeAdminCredentialsDialog() {
        if (currentProfile == null || activeProfilePath == null) {
            JOptionPane.showMessageDialog(this, "Please select or create a profile first.", "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        JTextField oldUserField = new JTextField(currentProfile.getAdminUsername());
        JPasswordField oldPassField = new JPasswordField(currentProfile.getAdminPassword());
        JTextField newUserField = new JTextField(currentProfile.getAdminUsername());
        JPasswordField newPassField = new JPasswordField("");

        char defaultEchoChar = oldPassField.getEchoChar();
        JCheckBox showOldPass = new JCheckBox("Show Password");
        showOldPass.addActionListener(
                e -> oldPassField.setEchoChar(showOldPass.isSelected() ? (char) 0 : defaultEchoChar));
        JCheckBox showNewPass = new JCheckBox("Show Password");
        showNewPass.addActionListener(
                e -> newPassField.setEchoChar(showNewPass.isSelected() ? (char) 0 : defaultEchoChar));

        JPanel panel = new JPanel(new MigLayout("wrap 2, insets 10", "[][grow]"));
        panel.add(new JLabel("Old Admin Username:"));
        panel.add(oldUserField, "growx");
        panel.add(new JLabel("Old Admin Password:"));
        panel.add(oldPassField, "growx");
        panel.add(showOldPass, "skip 1, wrap");
        panel.add(new JSeparator(), "span, growx, gaptop 5, gapbottom 5");
        panel.add(new JLabel("New Admin Username:"));
        panel.add(newUserField, "growx");
        panel.add(new JLabel("New Admin Password:"));
        panel.add(newPassField, "growx");
        panel.add(showNewPass, "skip 1, wrap");

        int result = JOptionPane.showConfirmDialog(this, panel, "Change Admin Credentials",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            executeChangeAdminCredentialsWorker(oldUserField.getText(), new String(oldPassField.getPassword()),
                    newUserField.getText(), new String(newPassField.getPassword()));
        }
    }

    private void showUpdateDatabaseDialog(MariadbProfile.DatabaseInfo dbToUpdate, String currentNameFromUI) {
        JTextField newNameField = new JTextField(currentNameFromUI);
        JTextField newUserField = new JTextField(dbToUpdate.user);
        JTextField newPermissionsField = new JTextField(dbToUpdate.permissions);

        String dialogPermsKey = dbToUpdate.id + "_permissions_dialog"; // Unique key for dialog checkboxes
        List<JCheckBox> dialogPermissionCheckboxes = new ArrayList<>();
        JComponent permissionSelectionPanel = createPermissionCheckboxesPanel(dialogPermsKey, dbToUpdate.permissions, // Already
                                                                                                                      // JComponent,
                                                                                                                      // no
                                                                                                                      // change
                                                                                                                      // needed
                                                                                                                      // here
                true);

        JPanel panel = new JPanel(new MigLayout("wrap 2, insets 10", "[][grow]"));
        panel.add(new JLabel("Database ID:"));
        panel.add(new JLabel(dbToUpdate.id), "growx");
        panel.add(new JSeparator(), "span, growx, gaptop 5, gapbottom 5");
        panel.add(new JLabel("New Database Name:"));
        panel.add(newNameField, "growx");
        panel.add(new JLabel("New User:"));
        panel.add(newUserField, "growx");
        panel.add(new JLabel("New Permissions:"), "align label");
        panel.add(permissionSelectionPanel, "growx"); // Add the checkbox panel here

        int result = JOptionPane.showConfirmDialog(this, panel, "Update Database Details",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String oldName = dbToUpdate.name; // Keep old name for logging/comparison
            String newName = newNameField.getText().trim();
            String newUser = newUserField.getText().trim();
            String newPerms = getPermissionsString(dialogPermsKey); // Get from checkboxes

            if (newName.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Database name cannot be empty.", "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (!newName.equals(oldName) || !newUser.equals(dbToUpdate.user)
                    || !newPerms.equals(dbToUpdate.permissions)) {
                executeUpdateDatabaseWorker(dbToUpdate.id, oldName, newName, newUser, newPerms);
            }
            permissionCheckboxMaps.remove(dialogPermsKey); // Clean up
        }
    }

    private void executeChangeAdminCredentialsWorker(String oldUser, String oldPass, String newUser, String newPass) {
        final JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        final JLabel statusLabel = new JLabel("Preparing...");
        statusLabel.setFont(UIManager.getFont("Label.font"));

        JPanel progressPanel = new JPanel(new MigLayout("fillx, insets 20", "[grow]", "[]10[]"));
        progressPanel.add(statusLabel, "wrap");
        progressPanel.add(progressBar, "growx");

        final JDialog progressDialog = new JDialog((Window) SwingUtilities.getWindowAncestor(this),
                "Changing Admin Credentials", Dialog.ModalityType.APPLICATION_MODAL);
        progressDialog.add(progressPanel);
        progressDialog.pack();
        progressDialog.setMinimumSize(new Dimension(450, progressDialog.getHeight()));
        progressDialog.setLocationRelativeTo(this);

        if (!isConsoleExpanded)
            toggleConsole();
        appendLog("\n--- Changing Admin Credentials ---");

        new SwingWorker<Void, ProgressInfo>() {
            @Override
            protected Void doInBackground() throws Exception {
                currentProfile.changeAdminCredentials(oldUser, oldPass, newUser, newPass,
                        new DatabaseConfigurationUtils.ProgressListener() {
                            @Override
                            public void onProgress(String message, int progress) {
                                publish(new ProgressInfo(message, progress));
                            }

                            @Override
                            public void onLog(String line) {
                                appendLog(line);
                            }
                        });
                return null;
            }

            @Override
            protected void process(List<ProgressInfo> chunks) {
                ProgressInfo info = chunks.get(chunks.size() - 1);
                statusLabel.setText(info.message);
                progressBar.setValue(info.progress);
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                try {
                    get();
                    updateUIFromData();
                    JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this,
                            "Admin credentials changed successfully.");
                } catch (Exception e) {
                    logger.error("Failed to change admin credentials", e);
                    JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this,
                            "Failed: " + e.getCause().getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
        progressDialog.setVisible(true);
    }

    private void executeCreateDatabaseWorker(String newDbName) {
        if (newDbName == null || newDbName.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Database name cannot be empty.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (!isConsoleExpanded)
            toggleConsole();
        appendLog("\n--- Creating Database: " + newDbName + " ---");

        String appUser = valueSuppliers.containsKey("appUsername") ? valueSuppliers.get("appUsername").get() : "";
        String permissions = valueSuppliers.containsKey("appUserPermissions")
                ? valueSuppliers.get("appUserPermissions").get()
                : "ALL";

        if (valueSuppliers.containsKey("adminUsername"))
            currentProfile.setAdminUsername(valueSuppliers.get("adminUsername").get());
        if (valueSuppliers.containsKey("adminPassword"))
            currentProfile.setAdminPassword(valueSuppliers.get("adminPassword").get());

        final JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        final JLabel statusLabel = new JLabel("Connecting...");
        statusLabel.setFont(UIManager.getFont("Label.font"));

        JPanel progressPanel = new JPanel(new MigLayout("fillx, insets 20", "[grow]", "[]10[]"));
        progressPanel.add(statusLabel, "wrap");
        progressPanel.add(progressBar, "growx");

        final JDialog progressDialog = new JDialog((Window) SwingUtilities.getWindowAncestor(this),
                "Creating Database", Dialog.ModalityType.APPLICATION_MODAL);
        progressDialog.add(progressPanel);
        progressDialog.pack();
        progressDialog.setMinimumSize(new Dimension(450, progressDialog.getHeight()));
        progressDialog.setLocationRelativeTo(this);

        new SwingWorker<Boolean, ProgressInfo>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                return currentProfile.createDatabase(newDbName, appUser, permissions,
                        new DatabaseConfigurationUtils.ProgressListener() {
                            @Override
                            public void onProgress(String message, int progress) {
                                publish(new ProgressInfo(message, progress));
                            }

                            @Override
                            public void onLog(String line) {
                                appendLog(line);
                            }
                        });
            }

            @Override
            protected void process(List<ProgressInfo> chunks) {
                ProgressInfo info = chunks.get(chunks.size() - 1);
                statusLabel.setText(info.message);
                progressBar.setValue(info.progress);
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                try {
                    boolean created = get();
                    updateUIFromData();
                    if (created) {
                        JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this,
                                "Database '" + newDbName + "' created successfully.");
                    } else {
                        JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this,
                                "Database '" + newDbName + "' already exists.", "Info",
                                JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception e) {
                    logger.error("Failed to create database", e);
                    JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this,
                            "Failed: " + e.getCause().getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
        progressDialog.setVisible(true);
    }

    private void executeUpdateDatabaseWorker(String dbId, String oldDbName, String newDbName, String newUser,
            String newPermissions) {
        if (newDbName == null || newDbName.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "New database name cannot be empty.", "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (newUser == null || newUser.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "New user name cannot be empty.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (newPermissions == null || newPermissions.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "New permissions cannot be empty.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        currentProfile.setAdminUsername(valueSuppliers.get("adminUsername").get());
        currentProfile.setAdminPassword(valueSuppliers.get("adminPassword").get());

        final JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        final JLabel statusLabel = new JLabel("Updating database...");
        statusLabel.setFont(UIManager.getFont("Label.font"));

        JPanel progressPanel = new JPanel(new MigLayout("fillx, insets 20", "[grow]", "[]10[]"));
        progressPanel.add(statusLabel, "wrap");
        progressPanel.add(progressBar, "growx");

        final JDialog progressDialog = new JDialog((Window) SwingUtilities.getWindowAncestor(this),
                "Updating Database", Dialog.ModalityType.APPLICATION_MODAL);
        progressDialog.add(progressPanel);
        progressDialog.pack();
        progressDialog.setMinimumSize(new Dimension(450, progressDialog.getHeight()));
        progressDialog.setLocationRelativeTo(this);

        new SwingWorker<Void, ProgressInfo>() {
            @Override
            protected Void doInBackground() throws Exception {
                currentProfile.updateDatabase(dbId, oldDbName, newDbName, newUser, newPermissions);
                publish(new ProgressInfo("Database updated successfully.", 100));
                return null;
            }

            @Override
            protected void process(List<ProgressInfo> chunks) {
                ProgressInfo info = chunks.get(chunks.size() - 1);
                statusLabel.setText(info.message);
                progressBar.setValue(info.progress);
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                try {
                    get();
                    updateUIFromData();
                    JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this,
                            "Database '" + oldDbName + "' updated to '" + newDbName + "' successfully.");
                } catch (Exception e) {
                    logger.error("Failed to update database", e);
                    JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this,
                            "Failed: " + e.getCause().getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
        progressDialog.setVisible(true);
    }

    private void executeRemoveDatabaseWorker(String dbId, String dbName) {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to remove database '" + dbName + "' (ID: " + dbId + ") from this profile?",
                "Confirm Removal", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        final JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        final JLabel statusLabel = new JLabel("Removing database...");
        statusLabel.setFont(UIManager.getFont("Label.font"));

        JPanel progressPanel = new JPanel(new MigLayout("fillx, insets 20", "[grow]", "[]10[]"));
        progressPanel.add(statusLabel, "wrap");
        progressPanel.add(progressBar, "growx");

        final JDialog progressDialog = new JDialog((Window) SwingUtilities.getWindowAncestor(this),
                "Removing Database", Dialog.ModalityType.APPLICATION_MODAL);
        progressDialog.add(progressPanel);
        progressDialog.pack();
        progressDialog.setMinimumSize(new Dimension(450, progressDialog.getHeight()));
        progressDialog.setLocationRelativeTo(this);

        new SwingWorker<Void, ProgressInfo>() {
            @Override
            protected Void doInBackground() throws Exception {
                currentProfile.removeDatabase(dbId);
                publish(new ProgressInfo("Database removed successfully.", 100));
                return null;
            }

            @Override
            protected void process(List<ProgressInfo> chunks) {
                ProgressInfo info = chunks.get(chunks.size() - 1);
                statusLabel.setText(info.message);
                progressBar.setValue(info.progress);
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                try {
                    get();
                    updateUIFromData();
                    JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this,
                            "Database '" + dbName + "' removed successfully.");
                } catch (Exception e) {
                    logger.error("Failed to remove database", e);
                    JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this,
                            "Failed: " + e.getCause().getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
        progressDialog.setVisible(true);
    }

    private void updateConfigFile() {
        if (currentProfile == null || !currentProfile.isStep2Completed()) {
            JOptionPane.showMessageDialog(this,
                    "Database instance must be initialized (Step 2 completed) to update the config file.", "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Collect current UI values for all visible config properties and update the
        // profile object
        for (String key : currentProfile.getVisibleProperties()) {
            Supplier<String> supplier = valueSuppliers.get(key);
            if (supplier != null) {
                currentProfile.getConfiguration().put(key, supplier.get());
            }
        }

        try {
            currentProfile.writeConfigFile(); // New method in MariadbProfile

            Map<String, Object> updates = new HashMap<>();
            updates.put("configFilePath", currentProfile.getConfigFilePath());
            currentProfile.saveToProfileJson(updates);

            updateUIFromData(); // Refresh UI colors
            JOptionPane.showMessageDialog(this, "Configuration file updated successfully.", "Success",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            logger.error("Failed to update config file: {}", e.getMessage(), e);
            JOptionPane.showMessageDialog(this, "Failed to update config file: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openConfigFile() {
        if (currentProfile == null || currentProfile.getConfigFilePath() == null
                || currentProfile.getConfigFilePath().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Configuration file path is not available. Please initialize the database first.", "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            File configFile = new File(currentProfile.getConfigFilePath());
            if (configFile.exists()) {
                Desktop.getDesktop().open(configFile);
            } else {
                JOptionPane.showMessageDialog(this,
                        "Configuration file not found at: " + currentProfile.getConfigFilePath(), "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        } catch (IOException e) {
            logger.error("Failed to open config file: {}", e.getMessage(), e);
            JOptionPane.showMessageDialog(this, "Failed to open config file: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void setStepStatus(JLabel label, Boolean success) {
        if (success == null) {
            label.setVisible(false);
            return;
        }
        label.setIcon(success ? checkIcon : errorIcon);
        label.setVisible(true);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Re-style and re-size input fields
        if (allPropertyRows != null) {
            for (PropertyRow row : allPropertyRows) {
                if (row.propertyKey == null && row.label != null) { // Section header
                    row.label.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 14f));
                }
                if (row.input != null) {
                    ConfigurationUtils.styleInputComponent(row.input);
                    ConfigurationUtils.fixComponentSize(row.input);
                }
            }
        }
        if (consoleTitle != null) {
            consoleTitle.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 13f));
        }
        updateProfileButtonsUI();
        if (startDbBtn != null) {
            startDbBtn.setIcon(
                    IconFontSwing.buildIcon(FontAwesome.PLAY, GuiConstants.getHelpIconSize(), GuiColors.getApplied()));
            stopDbBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.STOP, GuiConstants.getHelpIconSize(),
                    GuiColors.getContrastRed()));
            restartDbBtn.setIcon(
                    IconFontSwing.buildIcon(FontAwesome.REFRESH, GuiConstants.getHelpIconSize(), GuiColors.getSaved()));
        }
    }

    private void updateProfileButtonsUI() {
        if (profileComboBox != null)
            ConfigurationUtils.fixComponentSize(profileComboBox);
        ConfigurationUtils.configureProfileToolbar(newProfileBtn, null, saveProfileBtn, renameProfileBtn,
                deleteProfileBtn, reloadProfileBtn, refreshProfilesBtn, null);
    }

    public void updateUIFromData() {
        // Refresh Step 2 dynamic content to update visibility of sync/delete icons
        // based on state (Requirements 1 & 2)
        if (currentProfile != null) {
            refreshStep2DynamicContent();
        }

        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String val = getProfileValue(currentProfile, key);

            if (val == null)
                val = "";

            if (comp instanceof JComboBox) {
                ((JComboBox<?>) comp).setSelectedItem(val);
            } else if (comp instanceof javax.swing.text.JTextComponent) {
                ((javax.swing.text.JTextComponent) comp).setText(val);
            }
            updateColor(comp, key);
        }

        // Update the installed version label
        if (installedVersionLabel != null) {
            if (loadedProfileName == null && currentProfile.getProfileName() == null) {
                installedVersionLabel.setText("Select or create a profile to manage MariaDB installation.");
                installedVersionLabel.setForeground(GuiColors.getFaintText());
            } else {
                String installedVer = currentProfile.getInstalledVersion();
                String installedOs = currentProfile.getDownloadedOs();
                String installedArch = currentProfile.getDownloadedArch();

                if (installedVer != null && !installedVer.isEmpty()) {
                    installedVersionLabel.setText(
                            "Downloaded MariaDB version: " + installedVer + " " + installedOs + " " + installedArch);
                    installedVersionLabel.setForeground(GuiColors.getSaved());
                } else if (currentProfile.isStep1Completed()) { // Version string is missing despite step 1 completion
                    installedVersionLabel.setText("MariaDB binaries installed (version unknown).");
                } else {
                    installedVersionLabel.setText("No MariaDB version installed for this profile.");
                    installedVersionLabel.setForeground(GuiColors.getFaintText());
                }
                // Update version comboboxes based on loaded profile
                String ver = currentProfile.getInstalledVersion() != null
                        ? currentProfile.getInstalledVersion()
                        : currentProfile.getDownloadedVersion();
                setVersionComboBoxes(ver);
            }
            if (activeProfilePath != null) {
                pathLabel.setText("Profile Directory: " + activeProfilePath.toAbsolutePath().toString());
            }
        }

        // Update the appUserPermissions panel if it exists
        if (propertyComponents.containsKey("appUserPermissions")) {
            updatePermissionPanelState("appUserPermissions", currentProfile.getAppUserPermissions(), true);
        }

        boolean step1Finished = currentProfile != null && currentProfile.isStep1Completed();
        boolean step2Finished = step1Finished && currentProfile.isStep2Completed();
        boolean step3Finished = step2Finished && currentProfile.isStep3Completed();

        if (step1StatusIcon != null)
            setStepStatus(step1StatusIcon, step1Finished);
        if (step2StatusIcon != null)
            setStepStatus(step2StatusIcon, step2Finished);
        if (step3StatusIcon != null)
            setStepStatus(step3StatusIcon, step3Finished);

        if (step2HeaderLabel != null)
            step2HeaderLabel.setVisible(step1Finished);
        if (step2StatusIcon != null)
            step2StatusIcon.setVisible(step1Finished);
        if (step2ContentPanel != null)
            step2ContentPanel.setVisible(step1Finished);

        if (step3HeaderLabel != null)
            step3HeaderLabel.setVisible(step2Finished);
        if (step3StatusIcon != null)
            step3StatusIcon.setVisible(step2Finished);
        if (step3ContentPanel != null)
            step3ContentPanel.setVisible(step2Finished);

        if (step2HeaderLabel != null && currentProfile != null) {
            String configName = currentOsName.equalsIgnoreCase("windows") ? "my.ini" : "my.cnf";
            step2HeaderLabel.setText("Step 2: Initialize Database Instance (" + configName + ")");
        }

        updateDatabaseListUI();
        updateUserListUI();
        updateTempGrantsUI();
        updateProfileButtonStates();
    }

    // Helper to get value from MariadbProfile based on key
    private String getProfileValue(MariadbProfile profile, String key) {
        if (profile == null)
            return "";
        if (key != null && key.startsWith("db_name_")) {
            String id = key.substring(8);
            return profile.getCreatedDatabases().stream()
                    .filter(db -> db.id.equals(id))
                    .map(db -> db.name)
                    .findFirst()
                    .orElse("");
        }
        switch (key) {
            case "profileName":
                return profile.getProfileName();
            case "installedVersion":
                return profile.getInstalledVersion();
            case "downloadedVersion":
                return profile.getDownloadedVersion();
            case "downloadedOs":
                return profile.getDownloadedOs();
            case "downloadedArch":
                return profile.getDownloadedArch();
            case "step1Completed":
                return String.valueOf(profile.isStep1Completed());
            case "step2Completed":
                return String.valueOf(profile.isStep2Completed());
            case "step3Completed":
                return String.valueOf(profile.isStep3Completed());
            case "isReady":
                return String.valueOf(profile.isReady());
            case "adminUsername":
                return profile.getAdminUsername();
            case "adminPassword":
                return profile.getAdminPassword();
            case "appUsername":
                return profile.getAppUsername();
            case "appUserPassword":
                return profile.getAppUserPassword();
            case "appUserPermissions":
                return profile.getAppUserPermissions();
            default:
                return profile.getConfiguration().getOrDefault(key, "");
        }
    }

    // Helper to set value in MariadbProfile based on key
    private void setProfileValue(MariadbProfile profile, String key, String value) {
        if (profile == null)
            return;
        if (key != null && key.startsWith("db_name_")) {
            String id = key.substring(8);
            profile.getCreatedDatabases().stream()
                    .filter(db -> db.id.equals(id))
                    .findFirst()
                    .ifPresent(db -> db.name = value);
            return;
        }
        switch (key) {
            case "profileName":
                profile.setProfileName(value);
                break;
            case "installedVersion":
                profile.setInstalledVersion(value);
                break;
            case "downloadedVersion":
                profile.setDownloadedVersion(value);
                break;
            case "downloadedOs":
                profile.setDownloadedOs(value);
                break;
            case "downloadedArch":
                profile.setDownloadedArch(value);
                break;
            case "step1Completed":
                profile.setStep1Completed(Boolean.parseBoolean(value));
                break;
            case "step2Completed":
                profile.setStep2Completed(Boolean.parseBoolean(value));
                break;
            case "step3Completed":
                profile.setStep3Completed(Boolean.parseBoolean(value));
                break;
            case "isReady":
                profile.setReady(Boolean.parseBoolean(value));
                break;
            case "adminUsername":
                profile.setAdminUsername(value);
                break;
            case "adminPassword":
                profile.setAdminPassword(value);
                break; // Handle securely if needed
            case "appUsername":
                profile.setAppUsername(value);
                break;
            case "appUserPassword":
                profile.setAppUserPassword(value);
                break; // Handle securely if needed
            case "appUserPermissions":
                profile.setAppUserPermissions(value);
                break;
            default:
                profile.getConfiguration().put(key, value);
                break;
        }
    }

    private void filterProperties(String text) {
        boolean isSearch = text != null && !text.trim().isEmpty();

        if (isSearch) {
            searchResultsPanel.removeAll();
            String lowerText = text.toLowerCase();

            for (PropertyRow row : allPropertyRows) {
                if (row.propertyKey != null && (row.propertyKey.toLowerCase().contains(lowerText) ||
                        row.labelText.toLowerCase().contains(lowerText))) {

                    if (row.label != null) {
                        searchResultsPanel.add(row.label, "align label");
                    }
                    if (row.input != null) {
                        // Use specific constraints for large areas, default for others
                        String constraints = "customConfigEntries".equals(row.propertyKey) ? row.inputConstraints
                                : "split 2, growx, height pref!";
                        searchResultsPanel.add(row.input, constraints);
                    }
                    if (row.extra != null) {
                        searchResultsPanel.add(row.extra, row.extraConstraints);
                    }
                    if (row.help != null) {
                        searchResultsPanel.add(row.help, "wrap");
                    }
                    if (row.separator != null) {
                        searchResultsPanel.add(row.separator, "span, growx, wrap, gaptop 2, gapbottom 2");
                    }
                }
            }
            contentCardLayout.show(contentContainer, "SEARCH");
        } else {
            // Restore components to their original panels in order
            if (!allPropertyRows.isEmpty()) {
                allPropertyRows.get(0).originalParent.removeAll(); // Clear the main settings panel
            }
            for (PropertyRow row : allPropertyRows) {
                row.originalParent.add(row.label, row.labelConstraints);
                if (row.input != null) {
                    row.originalParent.add(row.input, row.inputConstraints);
                }
                if (row.extra != null) {
                    row.originalParent.add(row.extra, row.extraConstraints);
                }
                if (row.help != null) {
                    row.originalParent.add(row.help, row.helpConstraints);
                }
                if (row.separator != null) {
                    row.originalParent.add(row.separator, row.separatorConstraints);
                }
            }
            if (verticalFiller != null && !allPropertyRows.isEmpty()) {
                allPropertyRows.get(0).originalParent.add(verticalFiller, "pushy");
            }
            contentCardLayout.show(contentContainer, "SETTINGS");
        }
        revalidate();
        repaint();
    }

    // Helper to set the version comboboxes programmatically
    private void setVersionComboBoxes(String version) {
        if (version == null || version.isEmpty()) {
            return;
        }
        String[] parts = version.split("\\.");
        if (parts.length >= 2) {
            majorVersionCombo.setSelectedItem(parts[0]);
            minorVersionCombo.setSelectedItem(parts[1]);
            // Patch selection is deferred to fetchMariaDbPointReleases.done()
        }
    }

    private void updateMinorCombo() {
        minorVersionCombo.removeAllItems();
        String selectedMajor = (String) majorVersionCombo.getSelectedItem();
        if (selectedMajor != null) {
            allVersionsMap.keySet().stream()
                    .filter(v -> v.startsWith(selectedMajor + "."))
                    .map(v -> {
                        String[] parts = v.split("\\.");
                        return parts.length > 1 ? parts[1] : "";
                    })
                    .filter(v -> !v.isEmpty())
                    .distinct()
                    .sorted((a, b) -> {
                        try {
                            return Integer.compare(Integer.parseInt(b), Integer.parseInt(a));
                        } catch (NumberFormatException e) {
                            return b.compareTo(a);
                        }
                    })
                    .forEach(minorVersionCombo::addItem);
        }
        if (minorVersionCombo.getItemCount() > 0) {
            String ver = currentProfile.getInstalledVersion() != null
                    ? currentProfile.getInstalledVersion()
                    : currentProfile.getDownloadedVersion();

            // If no saved version is present, subversion 11 is selected by default
            // (targeting 10.11)
            if (ver == null && "10".equals(selectedMajor)) {
                boolean found11 = false;
                for (int i = 0; i < minorVersionCombo.getItemCount(); i++) {
                    if ("11".equals(minorVersionCombo.getItemAt(i))) {
                        minorVersionCombo.setSelectedIndex(i);
                        found11 = true;
                        break;
                    }
                }
                if (!found11)
                    minorVersionCombo.setSelectedIndex(0);
            } else {
                minorVersionCombo.setSelectedIndex(0);
            }
            updateSubVersionComboBox();
        }
    }

    private void updateSubVersionComboBox() {
        patchVersionCombo.removeAllItems();
        downloadDatabaseBtn.setEnabled(false);

        String major = (String) majorVersionCombo.getSelectedItem();
        String minor = (String) minorVersionCombo.getSelectedItem();
        if (major == null || minor == null)
            return;

        String selectedMainVersionName = major + "." + minor;

        MariaDBConfigurationPanel.MainVersionInfo selectedMainVersion = allVersionsMap.get(selectedMainVersionName);
        if (selectedMainVersion != null && selectedMainVersion.subVersions != null
                && !selectedMainVersion.subVersions.isEmpty()) {
            for (MariaDBConfigurationPanel.SubVersionInfo sv : selectedMainVersion.subVersions) {
                String[] parts = sv.name.split("\\.");
                if (parts.length >= 3) {
                    patchVersionCombo.addItem(parts[2]);
                } else {
                    patchVersionCombo.addItem(sv.name);
                }
            }
            if (patchVersionCombo.getItemCount() > 0) {
                patchVersionCombo.setSelectedIndex(0);
            }
            checkDownloadAvailability();
        } else {
            if (currentEngine == DatabaseEngine.MARIADB) {
                patchVersionCombo.addItem("Loading...");
                fetchMariaDbPointReleases(selectedMainVersionName);
            } else {
                patchVersionCombo.addItem("N/A");
            }
        }
    }

    private void checkDownloadAvailability() {
        downloadDatabaseBtn.setEnabled(false);
        initializeDatabaseBtn.setEnabled(false); // Disable initialize button by default
        downloadStatusLabel.setText(""); // Reset explanation text
        downloadStatusLabel.setForeground(GuiColors.getFaintText()); // Reset to default color

        String major = (String) majorVersionCombo.getSelectedItem();
        String minor = (String) minorVersionCombo.getSelectedItem();
        String patch = (String) patchVersionCombo.getSelectedItem();

        if (major == null || minor == null || patch == null || patch.isEmpty() || patch.equals("Loading...")
                || patch.equals("N/A")) {
            return;
        }

        // If step 1 is completed, enable step 2 button
        if (currentProfile.isStep1Completed()) { // Use MariadbProfile object
            initializeDatabaseBtn.setEnabled(!currentProfile.isStep2Completed());
            return; // No need to show "not available" message if already installed
        }

        String selectedMainVersionName = major + "." + minor;
        String selectedSubVersionName = selectedMainVersionName + "." + patch;

        MariaDBConfigurationPanel.MainVersionInfo mainVersion = allVersionsMap.get(selectedMainVersionName);
        if (mainVersion != null) {
            boolean versionFound = false;
            String downloadUrl = null;
            for (MariaDBConfigurationPanel.SubVersionInfo subVersion : mainVersion.subVersions) {
                if (subVersion.name.equals(selectedSubVersionName)) { // Check for exact match
                    versionFound = true;
                    downloadUrl = MariaDBConfigurationPanel.getDownloadUrlForCurrentOs(subVersion, currentOsName,
                            currentOsArch, globalSettings);
                    break;
                }
            }

            if (versionFound) {
                if (downloadUrl != null) {
                    downloadDatabaseBtn.setEnabled(true);
                } else {
                    downloadStatusLabel.setForeground(GuiColors.getContrastRed());
                    downloadStatusLabel.setText("Portable package not available for version " + selectedSubVersionName
                            + " on " + currentOsName + " " + currentOsArch + ". Please select another version.");
                }
            }
        }
    }

    private void updateMainVersionComboBox() {
        allVersionsMap.clear();
        majorVersionCombo.removeAllItems();
        minorVersionCombo.removeAllItems();
        patchVersionCombo.removeAllItems();
        downloadDatabaseBtn.setEnabled(false);

        JsonObject engineSettings = globalSettings.getAsJsonObject(currentEngine.getSettingsKey());
        String versionInfoUrl = (engineSettings != null && engineSettings.has("versionInfoUrl"))
                ? engineSettings.get("versionInfoUrl").getAsString()
                : "";

        if (versionInfoUrl.isEmpty()) {
            if (currentEngine == DatabaseEngine.MARIADB) {
                versionInfoUrl = API_BASE_URL;
            } else {
                return;
            }
        }

        final String finalUrl = versionInfoUrl; // Use finalUrl for lambda

        new SwingWorker<List<MariaDBConfigurationPanel.MainVersionInfo>, Void>() {
            // Changed Void to String for publish, but not used in this worker
            @Override
            protected List<MariaDBConfigurationPanel.MainVersionInfo> doInBackground() throws Exception {
                List<MariaDBConfigurationPanel.MainVersionInfo> versions = new ArrayList<>();
                // This panel is dedicated to MariaDB
                versions = MariaDBConfigurationPanel.fetchMajorReleases(finalUrl);
                // Fallback logic if the configured URL fails for MariaDB
                if (versions.isEmpty() && !finalUrl.equals(API_BASE_URL)) {
                    versions = MariaDBConfigurationPanel.fetchMajorReleases(API_BASE_URL);
                }
                return versions;
            }

            @Override
            protected void done() {
                try {
                    List<MariaDBConfigurationPanel.MainVersionInfo> mainVersions = get();
                    allVersionsMap.clear();
                    if (mainVersions != null && !mainVersions.isEmpty()) {
                        mainVersions.forEach(mv -> allVersionsMap.put(mv.name, mv));

                        allVersionsMap.keySet().stream()
                                .map(v -> v.split("\\.")[0])
                                .distinct()
                                .sorted((a, b) -> Integer.compare(Integer.parseInt(b), Integer.parseInt(a)))
                                .forEach(majorVersionCombo::addItem);

                        if (majorVersionCombo.getItemCount() > 0) {
                            String ver = currentProfile.getInstalledVersion() != null
                                    ? currentProfile.getInstalledVersion()
                                    : currentProfile.getDownloadedVersion();
                            if (ver != null) {
                                setVersionComboBoxes(ver);
                            } else {
                                // If no version is present, default to major version 10, prioritizing 10.11 if
                                // available
                                if (allVersionsMap.containsKey("10.11")) {
                                    majorVersionCombo.setSelectedItem("10");
                                } else {
                                    majorVersionCombo.setSelectedIndex(0);
                                }
                            }
                        }
                    } else {
                        logger.info("No MariaDB major versions found from API: {}", finalUrl);
                    }
                } catch (Exception e) {
                } finally {
                    SwingUtilities.invokeLater(() -> downloadStatusLabel.setText("")); // Clear status after attempt
                }
            }
        }.execute();
    }

    private void fetchMariaDbPointReleases(String majorVersion) {
        new SwingWorker<List<MariaDBConfigurationPanel.SubVersionInfo>, String>() { // Changed Void to String for
                                                                                    // publish
            @Override
            protected List<MariaDBConfigurationPanel.SubVersionInfo> doInBackground() throws Exception {
                List<MariaDBConfigurationPanel.SubVersionInfo> subVersions = new ArrayList<>();
                // Base URL for the MariaDB API
                String apiUrl = globalSettings.getAsJsonObject(currentEngine.getSettingsKey()).get("versionInfoUrl")
                        .getAsString();
                if (apiUrl.isEmpty()) {
                    apiUrl = API_BASE_URL;
                }

                subVersions = MariaDBConfigurationPanel.fetchPointReleases(majorVersion, apiUrl);
                return subVersions;
            }

            @Override
            protected void done() {
                try {
                    List<MariaDBConfigurationPanel.SubVersionInfo> results = get();
                    MariaDBConfigurationPanel.MainVersionInfo mv = allVersionsMap.get(majorVersion); // Get the major
                                                                                                     // version
                    // info
                    if (mv != null) {
                        mv.subVersions = results;
                        String currentSelectedMain = majorVersionCombo.getSelectedItem() + "."
                                + minorVersionCombo.getSelectedItem();
                        if (majorVersion.equals(currentSelectedMain)) {
                            patchVersionCombo.removeAllItems();
                            for (MariaDBConfigurationPanel.SubVersionInfo sv : results) {
                                String[] parts = sv.name.split("\\.");
                                if (parts.length >= 3) {
                                    patchVersionCombo.addItem(parts[2]);
                                } else {
                                    patchVersionCombo.addItem(sv.name);
                                }
                            }

                            String ver = currentProfile.getInstalledVersion() != null
                                    ? currentProfile.getInstalledVersion()
                                    : currentProfile.getDownloadedVersion();
                            if (ver != null && ver.startsWith(majorVersion + ".")) {
                                String[] vParts = ver.split("\\.");
                                if (vParts.length >= 3) {
                                    patchVersionCombo.setSelectedItem(vParts[2]);
                                }
                            } else if (patchVersionCombo.getItemCount() > 0) {
                                // Default choice: the latest version with available downloads (primarily for
                                // 10.11)
                                int bestIdx = 0;
                                if ("10.11".equals(majorVersion)) {
                                    for (int i = 0; i < results.size(); i++) {
                                        String url = MariaDBConfigurationPanel.getDownloadUrlForCurrentOs(
                                                results.get(i), currentOsName, currentOsArch, globalSettings);
                                        if (url != null) {
                                            bestIdx = i;
                                            break;
                                        }
                                    }
                                }
                                patchVersionCombo.setSelectedIndex(bestIdx);
                            }
                            checkDownloadAvailability();
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error fetching MariaDB point releases for {}: {}", majorVersion, e.getMessage());
                    downloadStatusLabel.setText("Error loading point releases.");
                    patchVersionCombo.removeAllItems();
                }
            }

            @Override
            protected void process(List<String> chunks) { // Update status label during fetching
                if (!chunks.isEmpty()) {
                    downloadStatusLabel.setText(chunks.get(chunks.size() - 1));
                }
            }
        }.execute();
    }

    private JTextField createStyledTextField(String text) {
        JTextField textField = new JTextField(text);
        ConfigurationUtils.styleInputComponent(textField);
        ConfigurationUtils.fixComponentSize(textField);
        return textField;
    }

    private void refreshProfileList() {
        try {
            String currentSelection = (String) profileComboBox.getSelectedItem();
            profileComboBox.removeAllItems();

            // Fetch profiles for the currently selected engine
            Path enginePath = PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                    .resolve(currentEngine.toString());

            Set<String> profileNames = new HashSet<>();
            if (Files.exists(enginePath)) {
                fetchFolderProfiles(enginePath).forEach(profileNames::add);
            }

            // Ensure the currently active or loaded profiles are included in the list
            // (Requirement Fix)
            if (activeProfileName != null && !activeProfileName.trim().isEmpty()) {
                profileNames.add(activeProfileName);
            }
            if (loadedProfileName != null && !loadedProfileName.trim().isEmpty()) {
                profileNames.add(loadedProfileName);
            }

            List<String> sortedProfiles = new ArrayList<>(profileNames);
            Collections.sort(sortedProfiles);
            sortedProfiles.forEach(profileComboBox::addItem);

            if (profileComboBox.getItemCount() == 0) {
                // If no profiles exist, ensure the combo box is truly empty
                profileComboBox.setSelectedItem(null);
                this.loadedProfileName = null;
                this.activeProfileName = null;
                this.runningProfileName = null;
                this.activeProfilePath = null;
            } else if (currentSelection != null && profileNames.contains(currentSelection)) {
                profileComboBox.setSelectedItem(currentSelection);
            } else if (activeProfileName != null && profileNames.contains(activeProfileName)) {
                profileComboBox.setSelectedItem(activeProfileName);
            } else if (loadedProfileName != null && profileNames.contains(loadedProfileName)) {
                profileComboBox.setSelectedItem(loadedProfileName);
            } else {
                profileComboBox.setSelectedIndex(0);
            }
        } catch (Exception e) {
            logger.error("Error refreshing profile list: {}", e.getMessage());
        }
        updateProfileButtonStates();
    }

    private List<String> fetchFolderProfiles(Path engineFolder) {
        List<String> profiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(engineFolder)) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) {
                    // Only consider it a profile if it contains profile.json
                    if (Files.exists(path.resolve("profile.json"))) { // Only consider it a profile if it contains
                                                                      // profile.json
                                                                      // conceptually
                        profiles.add(path.getFileName().toString());
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Error fetching folder profiles: {}", e.getMessage());
        }
        return profiles;
    }

    private void downloadAndInstallDatabase() {
        String major = (String) majorVersionCombo.getSelectedItem();
        String minor = (String) minorVersionCombo.getSelectedItem();
        String patch = (String) patchVersionCombo.getSelectedItem();

        if (major == null || minor == null || patch == null) {
            return;
        }

        String selectedSubVersionName = String.format("%s.%s.%s", major, minor, patch);
        String profileName = ensureProfileForDownload(major, minor, patch);
        if (profileName == null)
            return;

        SubVersionInfo subVersionInfo = findSubVersionInfo(major + "." + minor, selectedSubVersionName);
        if (subVersionInfo == null)
            return;

        String downloadUrl = MariaDBConfigurationPanel.getDownloadUrlForCurrentOs(subVersionInfo, currentOsName,
                currentOsArch, globalSettings);
        if (downloadUrl == null) {
            JOptionPane.showMessageDialog(this, "No download available for " + currentOsName + " " + currentOsArch
                    + " for the selected version.", "Download Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        Path targetProfileFolder = PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                .resolve(currentEngine.toString())
                .resolve(profileName);

        Path archiveFile = resolveArchiveFile(downloadUrl, targetProfileFolder);
        if (archiveFile == null)
            return;

        MaintenanceTask task = determineMaintenanceTask(selectedSubVersionName, archiveFile);
        if (task == null)
            return;

        if (!confirmMaintenance(selectedSubVersionName, downloadUrl, targetProfileFolder, task))
            return;

        executeDownloadAndInstallWorker(profileName, selectedSubVersionName, downloadUrl, targetProfileFolder,
                archiveFile, task);
    }

    private String ensureProfileForDownload(String major, String minor, String patch) {
        String currentProfileName = (String) profileComboBox.getSelectedItem();
        if (currentProfileName == null || currentProfileName.trim().isEmpty()) {
            String osLabel = currentOsName.equalsIgnoreCase("windows") ? "win" : currentOsName;
            String suggestedProfileName = String.format("Mariadb-%s.%s.%s-%s%s",
                    major, minor, patch, osLabel, currentOsArch);

            if (!createNewProfile(suggestedProfileName)) {
                return null;
            }
            currentProfileName = (String) profileComboBox.getSelectedItem();
        }
        return (currentProfileName != null && !currentProfileName.trim().isEmpty()) ? currentProfileName : null;
    }

    private SubVersionInfo findSubVersionInfo(String mainVersionName, String subVersionName) {
        MainVersionInfo mainVersionInfo = allVersionsMap.get(mainVersionName);
        if (mainVersionInfo == null)
            return null;

        SubVersionInfo selectedSubVersionInfo = mainVersionInfo.subVersions.stream()
                .filter(sv -> sv.name.equals(subVersionName))
                .findFirst().orElse(null);

        if (selectedSubVersionInfo == null) {
            JOptionPane.showMessageDialog(this, "Could not find details for the selected sub-version.",
                    "Download Error", JOptionPane.ERROR_MESSAGE);
        }
        return selectedSubVersionInfo;
    }

    private Path resolveArchiveFile(String downloadUrl, Path targetProfileFolder) {
        try {
            String filename = Paths.get(new URL(downloadUrl).getPath()).getFileName().toString();
            return targetProfileFolder.resolve(filename);
        } catch (java.net.MalformedURLException e) {
            logger.error("Invalid download URL: {}", downloadUrl, e);
            JOptionPane.showMessageDialog(this, "Invalid download URL: " + downloadUrl, "Download Error",
                    JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private MaintenanceTask determineMaintenanceTask(String selectedSubVersionName, Path archiveFile) {
        boolean shouldDownload = true;
        if (currentProfile.getDownloadedVersion() != null &&
                currentProfile.getDownloadedVersion().equals(selectedSubVersionName) &&
                Files.exists(archiveFile)) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Version " + selectedSubVersionName + " is already downloaded.\n" +
                            "Do you want to download it again and overwrite the existing file?",
                    "Confirm Redownload", JOptionPane.YES_NO_CANCEL_OPTION);
            if (choice == JOptionPane.NO_OPTION) {
                shouldDownload = false;
            } else if (choice != JOptionPane.YES_OPTION) {
                return null;
            }
        }

        boolean shouldExtract = true;
        if (currentProfile.getInstalledVersion() != null &&
                currentProfile.getInstalledVersion().equals(selectedSubVersionName)) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Version " + selectedSubVersionName + " is already installed.\n" +
                            "Do you want to reinstall (extract) it again?",
                    "Confirm Reinstall", JOptionPane.YES_NO_CANCEL_OPTION);
            if (choice == JOptionPane.NO_OPTION) {
                shouldExtract = false;
            } else if (choice != JOptionPane.YES_OPTION) {
                return null;
            }
        }

        if (!shouldDownload && shouldExtract && !Files.exists(archiveFile)) {
            JOptionPane.showMessageDialog(this,
                    "Installation archive missing on disk. Redownload is required to proceed.",
                    "Download Required", JOptionPane.WARNING_MESSAGE);
            shouldDownload = true;
        }

        if (!shouldDownload && !shouldExtract) {
            JOptionPane.showMessageDialog(this, "Selected version is already up to date.", "Info",
                    JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return new MaintenanceTask(shouldDownload, shouldExtract);
    }

    private boolean confirmMaintenance(String selectedSubVersionName, String downloadUrl, Path targetProfileFolder,
            MaintenanceTask task) {
        StringBuilder confirmMsg = new StringBuilder("Process for version " + selectedSubVersionName + "\n");
        if (task.shouldDownload)
            confirmMsg.append("- Download from: ").append(downloadUrl).append("\n");
        if (task.shouldExtract)
            confirmMsg.append("- Extract into: ").append(targetProfileFolder.toAbsolutePath()).append("\n");
        confirmMsg.append("\nDo you want to proceed?");

        int confirm = JOptionPane.showConfirmDialog(this, confirmMsg.toString(),
                "Confirm Download & Install", JOptionPane.YES_NO_OPTION);

        return confirm == JOptionPane.YES_OPTION;
    }

    private void executeDownloadAndInstallWorker(String profileName, String selectedSubVersionName, String downloadUrl,
            Path targetProfileFolder, Path archiveFile, MaintenanceTask task) {

        // Progress Dialog setup
        final JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        final JLabel statusLabel = new JLabel("Preparing installation...");
        statusLabel.setFont(UIManager.getFont("Label.font"));

        JPanel panel = new JPanel(new MigLayout("fillx, insets 20", "[grow]", "[]10[]"));
        panel.add(statusLabel, "wrap");
        panel.add(progressBar, "growx");

        final JDialog progressDialog = new JDialog((Window) SwingUtilities.getWindowAncestor(this),
                "Installation Progress", Dialog.ModalityType.APPLICATION_MODAL);
        progressDialog.add(panel);
        progressDialog.pack();
        progressDialog.setMinimumSize(new Dimension(450, progressDialog.getHeight()));
        progressDialog.setLocationRelativeTo(this);

        downloadDatabaseBtn.setEnabled(false);
        downloadStatusLabel.setText("Downloading...");

        if (!isConsoleExpanded)
            toggleConsole();
        appendLog("\n--- Downloading and Installing MariaDB (" + selectedSubVersionName + ") ---");

        SwingWorker<Void, ProgressInfo> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                currentProfile.install(downloadUrl, selectedSubVersionName, currentOsArch,
                        new DatabaseConfigurationUtils.ProgressListener() {
                            @Override
                            public void onProgress(String message, int progress) {
                                publish(new ProgressInfo(message, progress));
                            }

                            @Override
                            public void onLog(String line) {
                                appendLog(line);
                            }
                        });
                return null;
            }

            @Override
            protected void process(List<ProgressInfo> chunks) {
                if (!chunks.isEmpty()) {
                    ProgressInfo info = chunks.get(chunks.size() - 1);
                    downloadStatusLabel.setText(info.message);
                    statusLabel.setText(info.message);
                    progressBar.setValue(info.progress);
                }
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                downloadDatabaseBtn.setEnabled(true);
                try {
                    get();
                    updateUIFromData();
                    JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this,
                            "Database maintenance for version " + selectedSubVersionName
                                    + " completed successfully!",
                            "Operation Complete", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this,
                            "Operation failed: " + e.getCause().getMessage(),
                            "Operation Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };

        worker.execute();
        // Use invokeLater to ensure the worker starts and the dialog shows up correctly
        SwingUtilities.invokeLater(() -> {
            if (!worker.isDone())
                progressDialog.setVisible(true);
        });
    }

    private void initializeDatabase() {
        logger.info("DATABASE STEP 2: Starting MariaDB instance initialization for profile '{}'...", loadedProfileName);
        if (!validateInitializationPrerequisites())
            return;

        // Transfer GUI data to profile for Step 2
        for (String key : currentProfile.getVisibleProperties()) {
            Supplier<String> supplier = valueSuppliers.get(key);
            if (supplier != null) {
                currentProfile.getConfiguration().put(key, supplier.get());
            }
        }

        executeInitializationWorker(activeProfilePath, currentProfile.getPort());
    }

    private boolean validateInitializationPrerequisites() {
        if (!currentProfile.isStep1Completed()) {
            JOptionPane.showMessageDialog(this, "Please complete Step 1 (Download & Install) first.",
                    "Prerequisite Missing", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        String port = currentProfile.getPort();
        if (port == null || port.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Port cannot be empty.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (activeProfilePath == null || !Files.exists(activeProfilePath)) {
            JOptionPane.showMessageDialog(this, "Profile directory not found.", "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        return true;
    }

    private void executeInitializationWorker(Path profileRoot, String port) {

        // Progress Dialog setup
        final JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        final JLabel statusLabel = new JLabel("Preparing initialization...");
        statusLabel.setFont(UIManager.getFont("Label.font"));

        JPanel panel = new JPanel(new MigLayout("fillx, insets 20", "[grow]", "[]10[]"));
        panel.add(statusLabel, "wrap");
        panel.add(progressBar, "growx");

        final JDialog progressDialog = new JDialog((Window) SwingUtilities.getWindowAncestor(this),
                "Database Initialization Progress", Dialog.ModalityType.APPLICATION_MODAL);
        progressDialog.add(panel);
        progressDialog.pack();
        progressDialog.setMinimumSize(new Dimension(450, progressDialog.getHeight()));
        progressDialog.setLocationRelativeTo(this);

        initializeDatabaseBtn.setEnabled(false);
        downloadStatusLabel.setText("Initializing database..."); // Reuse downloadStatusLabel for general status

        if (!isConsoleExpanded)
            toggleConsole();
        appendLog("\n--- Initializing MariaDB Instance ---");

        SwingWorker<Void, ProgressInfo> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                currentProfile.initializeInstance(new DatabaseConfigurationUtils.ProgressListener() {
                    @Override
                    public void onProgress(String message, int progress) {
                        publish(new ProgressInfo(message, progress));
                    }

                    @Override
                    public void onLog(String line) {
                        appendLog(line);
                    }
                });
                return null;
            }

            @Override
            protected void process(List<ProgressInfo> chunks) {
                if (!chunks.isEmpty()) {
                    ProgressInfo info = chunks.get(chunks.size() - 1);
                    downloadStatusLabel.setText(info.message);
                    statusLabel.setText(info.message);
                    progressBar.setValue(info.progress);
                }
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                initializeDatabaseBtn.setEnabled(true); // Re-enable button
                try {
                    get(); // Check for exceptions from doInBackground
                    updateUIFromData();
                    JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this,
                            "MariaDB instance initialized successfully for profile '" + loadedProfileName + "'.",
                            "Operation Complete", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this,
                            "Database initialization failed: " + e.getCause().getMessage(),
                            "Operation Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };

        worker.execute();
        SwingUtilities.invokeLater(() -> {
            if (!worker.isDone())
                progressDialog.setVisible(true);
        });
    }

    private void updateProfileComboBoxColor() {
        ConfigurationUtils.updateProfileComboBoxColor(profileComboBox, runningProfileName, loadedProfileName);
    }

    private JsonObject getDataFromUI() {
        JsonObject data = new JsonObject();
        for (Map.Entry<String, Supplier<String>> entry : valueSuppliers.entrySet()) {
            String val = entry.getValue().get();
            if (val != null) {
                data.addProperty(entry.getKey(), val);
            }
        }
        return data;
    }

    private void loadProfileData() {
        Path jsonFile = activeProfilePath != null ? activeProfilePath.resolve("profile.json") : null;
        if (jsonFile != null && Files.exists(jsonFile)) {
            try (BufferedReader reader = Files.newBufferedReader(jsonFile, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                this.currentProfile = new MariadbProfile(loadedProfileName, json);
                // After loading, set the version comboboxes if a downloaded version exists
                setVersionComboBoxes(currentProfile.getDownloadedVersion());
            } catch (Exception e) {
                logger.error("Error loading profile.json: {}", e.getMessage());
                this.currentProfile = new MariadbProfile(loadedProfileName);
            }
        } else {
            this.currentProfile = new MariadbProfile(loadedProfileName);
        }
    }

    private void loadProfile(DatabaseEngine engine, String profileName) {
        Runnable loadAction = () -> {
            tempGrantsForNewUser.clear();
            this.loadedProfileName = profileName;
            this.currentEngine = engine;

            if (profileName == null || profileName.trim().isEmpty()) {
                this.activeProfilePath = null;
                this.currentProfile = new MariadbProfile(null);
            } else {
                Path targetFolder = PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                        .resolve(engine.toString())
                        .resolve(profileName);
                this.activeProfilePath = targetFolder;

                if (!Files.exists(targetFolder)) {
                    this.currentProfile = new MariadbProfile(profileName);
                } else {
                    try {
                        loadProfileData();
                    } catch (Exception e) {
                        logger.error("Error loading profile data: {}", e.getMessage());
                        this.currentProfile = new MariadbProfile(profileName);
                    }
                }
            }
            refreshStep2DynamicContent();
            updateUIFromData();
            updateProfileComboBoxColor();
            pathLabel.setText("Profile Directory: "
                    + (activeProfilePath != null ? activeProfilePath.toAbsolutePath().toString() : "N/A"));

            // Ensure the combo box selection matches the loaded profile name (Requirement
            // Fix)
            if (profileComboBox != null && !objectsEqual(profileComboBox.getSelectedItem(), profileName)) {
                profileComboBox.setSelectedItem(profileName);
            }
        };
        loadAction.run();
    }

    public void loadAppliedProperties() {
        // Load properties from the currently applied profile file
        String lastProfile = ConfigurationUtils
                .loadAppliedProfile(ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.DATABASE_SUBFOLDER));
        String appliedEngineName = DatabaseEngine.MARIADB.getDisplayName();
        String appliedProfileName = null;

        if (lastProfile != null && !lastProfile.trim().isEmpty() && lastProfile.contains(":")) {
            String[] parts = lastProfile.split(":");
            appliedEngineName = parts[0];
            appliedProfileName = parts[1];
        }

        DatabaseEngine appliedEngine = DatabaseEngine.fromDisplayName(appliedEngineName);
        if (appliedEngine == null)
            appliedEngine = DatabaseEngine.MARIADB;

        if (appliedProfileName != null) { // Only try to load if an applied profile name exists
            Path appliedProfileFolder = PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                    .resolve(appliedEngine.toString())
                    .resolve(appliedProfileName);
            Path jsonFile = appliedProfileFolder.resolve("profile.json");
            if (Files.exists(jsonFile)) {
                try (BufferedReader reader = Files.newBufferedReader(jsonFile, StandardCharsets.UTF_8)) {
                    this.appliedProfileSettings = JsonParser.parseReader(reader).getAsJsonObject();
                } catch (Exception e) {
                    logger.warn("Could not load applied database profile settings: {}", e.getMessage());
                    this.appliedProfileSettings = new JsonObject();
                }
            }
        } else {
            this.appliedProfileSettings = new JsonObject();
        }
        refreshUIColors();
    }

    private void updateDatabaseListUI() {
        if (dbListPanel == null)
            return;
        dbListPanel.removeAll();
        // Improved layout to remove gaps and allow the field to grow (Requirement 3)
        dbListPanel.setLayout(new MigLayout("fillx, insets 0, gap 5", "[][grow][][]", ""));

        allPropertyRows.removeIf(row -> row.originalParent == dbListPanel);
        // Clean up old property components and checkbox maps for dynamic lists
        propertyComponents.keySet().removeIf(k -> k.startsWith("db_name_"));
        permissionCheckboxMaps.keySet().removeIf(k -> k.startsWith("db_name_"));
        propertyComponents.keySet().removeIf(k -> k.endsWith("_permissions_dialog")); // For dialogs
        valueSuppliers.keySet().removeIf(key -> key.startsWith("db_name_"));

        if (currentProfile != null && !currentProfile.getCreatedDatabases().isEmpty()) {
            for (MariadbProfile.DatabaseInfo db : currentProfile.getCreatedDatabases()) {
                String propKey = "db_name_" + db.id;
                String labelText = "Database (ID: " + db.id + ")";
                PropertyRow row = new PropertyRow(propKey, labelText, dbListPanel);

                JLabel label = new JLabel(labelText);
                row.label = label;
                row.labelConstraints = "";
                dbListPanel.add(label, row.labelConstraints);

                JTextField nameField = createStyledTextField(db.name);
                propertyComponents.put(propKey, nameField);
                valueSuppliers.put(propKey, nameField::getText);

                nameField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                    private void update() {
                        SwingUtilities.invokeLater(() -> updateColor(nameField, propKey));
                    }

                    public void insertUpdate(javax.swing.event.DocumentEvent e) {
                        update();
                    }

                    public void removeUpdate(javax.swing.event.DocumentEvent e) {
                        update();
                    }

                    public void changedUpdate(javax.swing.event.DocumentEvent e) {
                        update();
                    }
                });

                row.input = nameField;
                row.inputConstraints = "growx, height pref!";
                dbListPanel.add(nameField, row.inputConstraints);

                // Requirements 1: Management icons only if Step 2 is completed
                if (currentProfile != null && currentProfile.isStep2Completed()) {
                    JPanel actionPanel = new JPanel(new MigLayout("insets 0, gap 2"));
                    actionPanel.setOpaque(false);

                    JButton updateBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.REFRESH,
                            GuiConstants.getHelpIconSize(), GuiColors.getApplied()));
                    updateBtn.setContentAreaFilled(false);
                    updateBtn.setBorderPainted(false);
                    updateBtn.setFocusPainted(false);
                    updateBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    updateBtn.setToolTipText("Update database name and details");
                    updateBtn.addActionListener(e -> showUpdateDatabaseDialog(db, nameField.getText().trim()));
                    actionPanel.add(updateBtn);

                    JButton removeBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.TRASH,
                            GuiConstants.getHelpIconSize(), GuiColors.getContrastRed()));
                    removeBtn.setContentAreaFilled(false);
                    removeBtn.setBorderPainted(false);
                    removeBtn.setFocusPainted(false);
                    removeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    removeBtn.setToolTipText("Remove this database");
                    removeBtn.addActionListener(e -> executeRemoveDatabaseWorker(db.id, db.name));
                    actionPanel.add(removeBtn);

                    row.extra = actionPanel;
                    row.extraConstraints = "gapleft 2";
                    dbListPanel.add(actionPanel, row.extraConstraints);
                } else {
                    dbListPanel.add(new JLabel(), "gapleft 2");
                }

                JButton infoBtn = new HelpButton();
                infoBtn.setToolTipText(String.format("User: %s, Permissions: %s", db.user, db.permissions));
                row.help = infoBtn;
                row.helpConstraints = "wrap";
                dbListPanel.add(infoBtn, row.helpConstraints);

                row.separator = new JSeparator();
                row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";
                dbListPanel.add(row.separator, row.separatorConstraints);

                allPropertyRows.add(row);
                updateColor(nameField, propKey);
            }
        }
        dbListPanel.revalidate();
        dbListPanel.repaint();
    }

    private void updateUserListUI() {
        if (userListPanel == null || currentProfile == null)
            return;
        userListPanel.removeAll();
        userListPanel.setLayout(new MigLayout("fillx, insets 0, gap 5", "[grow]", ""));

        boolean step2Completed = currentProfile.isStep2Completed();

        for (MariadbProfile.UserInfo user : currentProfile.getCreatedUsers()) {
            JPanel userContainer = new JPanel(new MigLayout("fillx, insets 10, gap 5",
                    "[align right][grow][align right][grow][pref!][pref!][pref!]", "")); // Adjusted layout for
                                                                                         // User/Host in one
            // row
            userContainer.setBorder(BorderFactory.createTitledBorder("User Management: " + user.username));
            userContainer.setOpaque(false);

            // 1. Username row (Requirement 1 & 3)
            userContainer.add(new JLabel("User:"), "align right");
            JTextField userField = createStyledTextField(user.username);
            userField.setEditable(false);
            userContainer.add(userField, "growx");

            userContainer.add(new JLabel("Host:"), "align right");
            JTextField hostField = createStyledTextField(user.host);
            hostField.setEditable(false);
            userContainer.add(hostField, "growx");

            JButton syncUserBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.REFRESH,
                    GuiConstants.getHelpIconSize(), GuiColors.getApplied()));
            syncUserBtn.setContentAreaFilled(false);
            syncUserBtn.setBorderPainted(false);
            syncUserBtn.setFocusPainted(false);
            syncUserBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            syncUserBtn.setToolTipText("Synchronize user and all grants to database");
            syncUserBtn.setEnabled(step2Completed);
            syncUserBtn.addActionListener(e -> runDatabaseSetup());
            userContainer.add(syncUserBtn, "gapleft 2");
            JButton delUserBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.TRASH, GuiConstants.getHelpIconSize(),
                    GuiColors.getContrastRed()));
            delUserBtn.setContentAreaFilled(false);
            delUserBtn.setBorderPainted(false);
            delUserBtn.setFocusPainted(false);
            delUserBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            delUserBtn.setToolTipText("Delete user from profile and database");
            delUserBtn.setEnabled(step2Completed);
            delUserBtn.addActionListener(e -> {
                int confirm = JOptionPane.showConfirmDialog(this,
                        "Are you sure you want to delete user '" + user.username + "'?", "Confirm Removal",
                        JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    try {
                        currentProfile.removeUser(user.id);
                        updateUIFromData();
                    } catch (IOException ex) {
                        logger.error("Fail", ex);
                    }
                }
            });
            userContainer.add(delUserBtn, "gapleft 2");

            JButton userHelpBtn = new HelpButton();
            userHelpBtn.setToolTipText("Manage this database user and its permissions.");
            userContainer.add(userHelpBtn, "gapleft 2, wrap");

            // 2. Password row (Requirement 1 & 3)
            userContainer.add(new JLabel("Password:"));
            JPasswordField passField = new JPasswordField(user.password);
            ConfigurationUtils.styleInputComponent(passField);
            userContainer.add(passField, "growx, span 4");

            JButton syncPassBtn = new JButton( // Sync button for password
                    IconFontSwing.buildIcon(FontAwesome.REFRESH, GuiConstants.getHelpIconSize(),
                            GuiColors.getApplied()));
            syncPassBtn.setContentAreaFilled(false);
            syncPassBtn.setBorderPainted(false);
            syncPassBtn.setFocusPainted(false);
            syncPassBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            syncPassBtn.setToolTipText("Update user password in database");
            syncPassBtn.setEnabled(step2Completed);
            syncPassBtn.addActionListener(e -> {
                try {
                    currentProfile.updateUserPassword(user.id, new String(passField.getPassword()));
                    updateUIFromData();
                    JOptionPane.showMessageDialog(this, "Password updated successfully.");
                } catch (IOException ex) {
                    logger.error("Failed to update password", ex);
                }
            });
            userContainer.add(syncPassBtn, "gapleft 2");

            JButton passHelpBtn = new HelpButton();
            passHelpBtn.setToolTipText("Update password for the selected user.");
            userContainer.add(passHelpBtn, "gapleft 2, wrap");

            // 3. Show password row
            JCheckBox showPass = new JCheckBox("Show Password");
            char defaultEchoChar = passField.getEchoChar();
            showPass.addActionListener(e -> passField.setEchoChar(showPass.isSelected() ? (char) 0 : defaultEchoChar));
            userContainer.add(showPass, "skip 1, span 5, wrap, gapbottom 10");

            // Add headers for grants section
            userContainer.add(new JSeparator(), "span, growx, wrap, gaptop 10, gapbottom 5"); // Separator before
                                                                                              // headers
            addPermissionHeaderRow(userContainer, false); // Adds "Table", "Permissions" headers
            for (MariadbProfile.UserGrant grant : user.grants) {
                String dbDisplay = grant.databaseId.equals("global") ? "GLOBAL (*.*)"
                        : currentProfile.getCreatedDatabases().stream().filter(d -> d.id.equals(grant.databaseId))
                                .map(d -> d.name + " (" + d.id + ")").findFirst().orElse("Unknown DB");

                // Replace JComboBox with checkboxes (Requirement 1)
                String grantPermsKey = user.id + "_" + grant.databaseId + "_permissions"; // Unique key for this grant's
                                                                                          // checkboxes
                JComponent permissionPanel = createPermissionCheckboxesPanel(grantPermsKey, grant.permissions,
                        step2Completed);
                userContainer.add(new JLabel("Account permissions:"), "align right");

                JTextField dbField = createStyledTextField(dbDisplay);
                dbField.setEditable(false);
                userContainer.add(dbField, "growx");

                userContainer.add(permissionPanel, "growx, height pref!, span 2");

                JButton syncGrantBtn = new JButton(
                        IconFontSwing.buildIcon(FontAwesome.REFRESH, GuiConstants.getHelpIconSize(),
                                GuiColors.getApplied()));
                syncGrantBtn.setContentAreaFilled(false);
                syncGrantBtn.setBorderPainted(false);
                syncGrantBtn.setFocusPainted(false);
                syncGrantBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                syncGrantBtn.setToolTipText("Update permissions for this database");
                syncGrantBtn.setEnabled(step2Completed);
                syncGrantBtn.addActionListener(e -> {
                    try { // (Requirement 3)
                        String newPermissions = getPermissionsString(grantPermsKey);
                        currentProfile.addUserGrant(user.id, grant.databaseId, newPermissions);
                        updateUIFromData();
                    } catch (IOException ex) { // (Requirement 3)
                        logger.error("Fail", ex);
                    }
                });
                userContainer.add(syncGrantBtn, "gapleft 2");

                JButton delGrantBtn = new JButton(
                        IconFontSwing.buildIcon(FontAwesome.TRASH, GuiConstants.getHelpIconSize(),
                                GuiColors.getContrastRed()));
                delGrantBtn.setContentAreaFilled(false);
                delGrantBtn.setBorderPainted(false);
                delGrantBtn.setFocusPainted(false);
                delGrantBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                delGrantBtn.setToolTipText("Revoke access to this database");
                delGrantBtn.setEnabled(step2Completed);
                delGrantBtn.addActionListener(e -> {
                    try {
                        currentProfile.removeUserGrant(user.id, grant.databaseId); // (Requirement 3)
                        updateUIFromData();
                    } catch (IOException ex) {
                        logger.error("Fail", ex);
                    }
                });
                userContainer.add(delGrantBtn, "gapleft 2");

                JButton grantHelpBtn = new HelpButton();
                grantHelpBtn.setToolTipText("Modify specific privileges for this database and host.");
                userContainer.add(grantHelpBtn, "gapleft 2, wrap");
            }

            userContainer.add(new JSeparator(), "span, growx, wrap, gaptop 10, gapbottom 5");

            String newGrantPermsKey = user.id + "_new_permissions"; // Unique key for new grant checkboxes
            JComponent newPermissionPanel = createPermissionCheckboxesPanel(newGrantPermsKey, "", step2Completed); // Empty

            addPermissionHeaderRow(userContainer, false); // Headers for adding new grant

            userContainer.add(new JLabel("Add Permissions:"), "align right");
            JComboBox<String> dbSelect = new JComboBox<>();
            Set<String> existingGrantDbIds = user.grants.stream().map(g -> g.databaseId).collect(Collectors.toSet());
            if (!existingGrantDbIds.contains("global")) {
                dbSelect.addItem("GLOBAL");
            }
            currentProfile.getCreatedDatabases().stream()
                    .filter(db -> !existingGrantDbIds.contains(db.id))
                    .forEach(db -> dbSelect.addItem(db.name + " (" + db.id + ")"));
            ConfigurationUtils.styleInputComponent(dbSelect);
            ConfigurationUtils.fixComponentSize(dbSelect);
            userContainer.add(dbSelect, "growx");

            userContainer.add(newPermissionPanel, "growx, height pref!, span 2"); // Add the checkbox panel here

            JButton addGrantBtn = new JButton("Add");
            addGrantBtn.setEnabled(step2Completed);
            ConfigurationUtils.styleInputComponent(addGrantBtn);
            ConfigurationUtils.fixComponentSize(addGrantBtn);
            addGrantBtn.addActionListener(e -> {
                String sel = (String) dbSelect.getSelectedItem();
                String dbId = sel.equals("GLOBAL") ? "global"
                        : sel.substring(sel.lastIndexOf('(') + 1, sel.length() - 1);
                String p = getPermissionsString(newGrantPermsKey);
                try {
                    currentProfile.addUserGrant(user.id, dbId, p);
                    updateUIFromData();
                } catch (IOException ex) {
                    logger.error("Fail", ex);
                }
            });
            userContainer.add(addGrantBtn, "right, span 2");
            JButton addGrantHelp = new HelpButton();
            addGrantHelp.setToolTipText("Add the selected database and host permissions to this user's profile.");
            userContainer.add(addGrantHelp, "gapleft 2, wrap");
            userListPanel.add(userContainer, "growx, wrap");
        }
        userListPanel.revalidate();
        userListPanel.repaint();
    }

    private void updateTempGrantsUI() {
        if (tempGrantsContainer == null || currentProfile == null)
            return;
        tempGrantsContainer.removeAll();
        // Apply consistent column setup for alignment with user management
        tempGrantsContainer.setLayout(new MigLayout("fillx, insets 0, gap 5",
                "[right][grow][grow][pref!][pref!]",
                ""));

        if (!tempGrantsForNewUser.isEmpty()) {
            tempGrantsContainer.add(new JLabel("Assigned Permissions:"),
                    "span, wrap, gaptop 5, gapbottom 5, gapleft 10");
            tempGrantsContainer.add(new JSeparator(), "span, growx, wrap, gapbottom 5");
            addPermissionHeaderRow(tempGrantsContainer, false);
            for (MariadbProfile.UserGrant grant : tempGrantsForNewUser) {
                String dbDisplay = grant.databaseId.equals("global") ? "GLOBAL (*.*)"
                        : currentProfile.getCreatedDatabases().stream().filter(d -> d.id.equals(grant.databaseId))
                                .map(d -> d.name + " (" + d.id + ")").findFirst().orElse("Unknown DB");

                // Replace JComboBox with checkboxes (Requirement 1)
                String tempGrantPermsKey = "temp_" + grant.databaseId + "_permissions"; // Unique key for temp grant
                                                                                        // checkboxes
                JComponent permissionPanel = createPermissionCheckboxesPanel(tempGrantPermsKey, grant.permissions,
                        true);

                tempGrantsContainer.add(new JLabel("Account permissions:"), "align right");

                JTextField dbField = createStyledTextField(dbDisplay);
                dbField.setEditable(false);
                tempGrantsContainer.add(dbField, "growx");

                tempGrantsContainer.add(permissionPanel, "growx, height pref!, span 2"); // Add the checkbox panel here

                JButton delBtn = new JButton(
                        IconFontSwing.buildIcon(FontAwesome.TRASH, GuiConstants.getHelpIconSize(),
                                GuiColors.getContrastRed()));
                delBtn.setContentAreaFilled(false);
                delBtn.setBorderPainted(false);
                delBtn.setFocusPainted(false);
                delBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                delBtn.setToolTipText("Remove this permission from list");
                delBtn.addActionListener(e -> {
                    tempGrantsForNewUser.remove(grant);
                    updateTempGrantsUI();
                });
                tempGrantsContainer.add(delBtn, "gapleft 2, wrap");
            }
        }

        tempGrantsContainer.add(new JSeparator(), "span, growx, wrap, gaptop 10, gapbottom 5");

        addPermissionHeaderRow(tempGrantsContainer, false);

        String addTempGrantPermsKey = "temp_new_permissions";
        JComponent addPermissionPanel = createPermissionCheckboxesPanel(addTempGrantPermsKey, "", true);
        tempGrantsContainer.add(new JLabel("Add Permissions:"), "align right");

        JComboBox<String> dbSelect = new JComboBox<>();
        Set<String> assignedDbIds = tempGrantsForNewUser.stream().map(g -> g.databaseId).collect(Collectors.toSet());
        if (!assignedDbIds.contains("global")) {
            dbSelect.addItem("GLOBAL");
        }
        currentProfile.getCreatedDatabases().stream().filter(db -> !assignedDbIds.contains(db.id))
                .forEach(db -> dbSelect.addItem(db.name + " (" + db.id + ")"));
        ConfigurationUtils.styleInputComponent(dbSelect);
        ConfigurationUtils.fixComponentSize(dbSelect);
        tempGrantsContainer.add(dbSelect, "growx");

        tempGrantsContainer.add(addPermissionPanel, "growx");

        // Add button for new temporary grant
        JButton addBtn = new JButton("Add");
        ConfigurationUtils.styleInputComponent(addBtn);
        ConfigurationUtils.fixComponentSize(addBtn);
        addBtn.addActionListener(e -> {
            String sel = (String) dbSelect.getSelectedItem();
            if (sel == null)
                return;
            String dbId = sel.equals("GLOBAL") ? "global" : sel.substring(sel.lastIndexOf('(') + 1, sel.length() - 1);
            tempGrantsForNewUser.add(new MariadbProfile.UserGrant(dbId, getPermissionsString(addTempGrantPermsKey)));
            updateTempGrantsUI();
        });
        tempGrantsContainer.add(addBtn);
        JButton addTempGrantHelp = new HelpButton();
        addTempGrantHelp.setToolTipText("Add the specified permissions to the list for the new user.");
        tempGrantsContainer.add(addTempGrantHelp, "wrap");

        tempGrantsContainer.revalidate();
        tempGrantsContainer.repaint();
    }

    /**
     * Adds header labels for "Table", "Permissions", and "Host" to a given panel,
     * aligning them with the corresponding input fields in the permission rows.
     *
     * @param panel The JPanel to which the headers should be added.
     */
    private void addPermissionHeaderRow(JPanel panel, boolean includeHost) {
        panel.add(new JLabel(""), "align right"); // Empty label for the first column
        JLabel tableHeader = new JLabel("Database");
        tableHeader.setFont(tableHeader.getFont().deriveFont(Font.BOLD));
        panel.add(tableHeader, "growx, align center");
        JLabel permissionHeader = new JLabel("Permissions");
        permissionHeader.setFont(permissionHeader.getFont().deriveFont(Font.BOLD));
        panel.add(permissionHeader, "growx, align center" + (includeHost ? "" : ", span 2, wrap"));
        if (!includeHost)
            return;
        JLabel hostHeader = new JLabel("Host");
        hostHeader.setFont(hostHeader.getFont().deriveFont(Font.BOLD));
        panel.add(hostHeader, "growx, align center, wrap");
    }

    private void runDatabaseSetup() {
        logger.info("DATABASE STEP 3: Starting Setup for profile '{}'...", loadedProfileName);

        if (!currentProfile.isStep2Completed()) { // Use MariadbProfile object
            JOptionPane.showMessageDialog(this, "Please complete Step 2 (Initialize Database Instance) first.",
                    "Prerequisite Missing", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (!isConsoleExpanded)
            toggleConsole();
        appendLog("\n--- Running Database User and Grant Setup ---");

        // 2. Start worker (UI trigger)
        final JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        final JLabel statusLabel = new JLabel("Running setup...");
        statusLabel.setFont(UIManager.getFont("Label.font"));

        JPanel panel = new JPanel(new MigLayout("fillx, insets 20", "[grow]", "[]10[]"));
        panel.add(statusLabel, "wrap");
        panel.add(progressBar, "growx");

        final JDialog progressDialog = new JDialog((Window) SwingUtilities.getWindowAncestor(this), "Setup Progress",
                Dialog.ModalityType.APPLICATION_MODAL);
        progressDialog.add(panel);
        progressDialog.pack();
        progressDialog.setMinimumSize(new Dimension(450, progressDialog.getHeight()));
        progressDialog.setLocationRelativeTo(this);

        new SwingWorker<Void, ProgressInfo>() {
            @Override
            protected Void doInBackground() throws Exception {
                currentProfile.setupDatabase(new DatabaseConfigurationUtils.ProgressListener() {
                    @Override
                    public void onProgress(String message, int progress) {
                        publish(new ProgressInfo(message, progress));
                    }

                    @Override
                    public void onLog(String line) {
                        appendLog(line);
                    }
                });
                return null;
            }

            @Override
            protected void process(List<ProgressInfo> chunks) {
                ProgressInfo info = chunks.get(chunks.size() - 1);
                progressBar.setValue(info.progress);
                statusLabel.setText(info.message);
                appendLog(info.message);
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                try {
                    get();
                    updateUIFromData();
                    JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this,
                            "Database setup completed successfully.");
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this,
                            "Setup failed: " + e.getCause().getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
        progressDialog.setVisible(true);
    }

    private void removeAndUninstallDatabase() {
        if (currentProfile == null || activeProfilePath == null)
            return;

        String msg = String.format("Are you sure you want to uninstall MariaDB from profile '%s'?\n" +
                "This will permanently delete all binaries and the entire data directory!", loadedProfileName);

        int confirm = JOptionPane.showConfirmDialog(this, msg, "Confirm Uninstall",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION)
            return;

        try {
            currentProfile.uninstall();
            updateUIFromData();

            JOptionPane.showMessageDialog(this,
                    "Database binaries and data have been removed. The profile is now in a clean state.",
                    "Uninstall Successful", JOptionPane.INFORMATION_MESSAGE);

            logger.info("Uninstall complete for profile '{}'.", loadedProfileName);

        } catch (IOException e) {
            logger.error("Error during uninstallation for profile '{}': {}", loadedProfileName, e.getMessage(), e);
            JOptionPane.showMessageDialog(this,
                    "An error occurred during uninstallation: " + e.getMessage(),
                    "Uninstall Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateProfileButtonStates() {
        String selected = (String) profileComboBox.getSelectedItem();
        boolean isNoProfileSelected = (selected == null || selected.trim().isEmpty() || currentProfile == null);
        boolean step1Completed = currentProfile != null && currentProfile.isStep1Completed();
        boolean step2Completed = currentProfile != null && currentProfile.isStep2Completed();

        // Update download and initialize buttons based on availability and step status
        checkDownloadAvailability();

        // Remove button logic: active if anything is downloaded or installed
        if (removeDatabaseBtn != null) {
            removeDatabaseBtn.setEnabled(currentProfile != null &&
                    (currentProfile.getDownloadedVersion() != null || currentProfile.isStep1Completed()));
        }

        if (startDbBtn != null) {
            startDbBtn.setEnabled(step1Completed && step2Completed);
        }
        if (stopDbBtn != null) {
            stopDbBtn.setEnabled(step1Completed && step2Completed);
        }
        if (restartDbBtn != null) {
            restartDbBtn.setEnabled(step1Completed && step2Completed);
        }

        renameProfileBtn.setEnabled(!isNoProfileSelected);
        deleteProfileBtn.setEnabled(!isNoProfileSelected);
        saveProfileBtn.setEnabled(!isNoProfileSelected);

        // Data Folder should not be modifiable after successful database initialization
        // (Step 2)
        for (String key : configurationKeysTracked) {
            JComponent comp = propertyComponents.get(key);
            if (comp != null) {
                boolean canEdit = !isNoProfileSelected;
                if (key.equals(MariadbProfile.CFG_DATADIR)) {
                    canEdit = canEdit && !step2Completed;
                }
                comp.setEnabled(canEdit);
            }
        }

        if (updateConfigFileBtn != null)
            updateConfigFileBtn.setEnabled(!isNoProfileSelected && step2Completed);
        if (openConfigFileBtn != null)
            openConfigFileBtn.setEnabled(
                    !isNoProfileSelected && step2Completed && currentProfile.getConfigFilePath() != null);
    }

    private void addPermissionProperty(JPanel panel, String propertyKey, String labelText, String initialPermissions,
            boolean isEditable) {
        PropertyRow row = new PropertyRow(propertyKey, labelText, panel);
        JLabel label = new JLabel(labelText);
        row.label = label;
        row.labelConstraints = "align label";
        panel.add(label, row.labelConstraints);

        DatabaseConfigurationUtils.CheckSelectionComboBox permissionPanel = (DatabaseConfigurationUtils.CheckSelectionComboBox) createPermissionCheckboxesPanel(
                propertyKey, initialPermissions,
                isEditable);
        row.input = permissionPanel;
        row.inputConstraints = "growx, height pref!";
        panel.add(permissionPanel, row.inputConstraints);
        // propertyComponents and valueSuppliers are now set within PermissionsComboBox
        // constructor
        // propertyComponents.put(propertyKey, permissionPanel);
        // valueSuppliers.put(propertyKey, () -> getPermissionsString(propertyKey));

        // Add help button
        JButton helpBtn = new HelpButton();
        row.help = helpBtn;
        row.helpConstraints = "wrap";
        panel.add(helpBtn, row.helpConstraints);

        row.separator = new JSeparator();
        row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";
        panel.add(row.separator, row.separatorConstraints);
        allPropertyRows.add(row);
    }

    private void addProperty(JPanel panel, String propertyKey, String labelText, JTextField existingTextField) {
        addPropertyInternal(panel, propertyKey, labelText, null, false, existingTextField, null);
    }

    private void addProperty(JPanel panel, String propertyKey, String labelText) {
        addPropertyInternal(panel, propertyKey, labelText, null, false, null, null);
    }

    private void addProperty(JPanel panel, String propertyKey, String labelText, boolean editable) {
        addPropertyInternal(panel, propertyKey, labelText, null, editable, null, null); // This was the problematic
                                                                                        // line, now fixed by adding the
                                                                                        // correct overload
    }

    private void addProperty(JPanel panel, String propertyKey, String labelText, String[] options, boolean editable) {
        addPropertyInternal(panel, propertyKey, labelText, options, editable, null, null);
    }

    private void addProperty(JPanel panel, String propertyKey, String labelText, JComboBox<String> existingComboBox,
            boolean editable) {
        addPropertyInternal(panel, propertyKey, labelText, null, editable, null, existingComboBox);
    }

    private void addPropertyInternal(JPanel panel, String propertyKey, String labelText, String[] options,
            boolean editable, JTextField existingTextField, JComboBox<String> existingComboBox) {
        PropertyRow row = new PropertyRow(propertyKey, labelText, panel);
        JLabel label = new JLabel(labelText);
        row.label = label;
        row.labelConstraints = "align label";
        panel.add(label, row.labelConstraints);

        JComponent inputComponent;
        if (options != null || existingComboBox != null) {
            final JComboBox<String> combo = (existingComboBox != null) ? existingComboBox : new JComboBox<>(options);
            combo.setEditable(editable);
            ConfigurationUtils.styleInputComponent(combo);
            ConfigurationUtils.fixComponentSize(combo);
            inputComponent = combo;
            valueSuppliers.put(propertyKey, () -> {
                Object selectedItem = combo.getSelectedItem();
                return selectedItem != null ? selectedItem.toString() : "";
            });
            combo.addActionListener(e -> {
                updateColor(combo, propertyKey);
            });
        } else {
            final JTextField textField = (existingTextField != null) ? existingTextField : createStyledTextField("");
            inputComponent = textField;
            valueSuppliers.put(propertyKey, () -> {
                String text = textField.getText();
                return text != null ? text : "";
            });
            textField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                private void update() {
                    SwingUtilities.invokeLater(() -> {
                        updateColor(textField, propertyKey);
                    });
                }

                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    update();
                }

                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    update();
                }

                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    update();
                }
            });
        }

        row.input = inputComponent;

        // Check if this is a my.ini configuration property (typically Step 2)
        boolean isMyIni = currentProfile != null && propertyKey != null && currentProfile.isMyIniProperty(propertyKey);
        boolean step2Completed = currentProfile != null && currentProfile.isStep2Completed();

        // Removal/Refresh icons only visible after Step 2 completion (Requirement 1)
        // and only for appropriate properties. Datadir becomes non-editable after
        // initialization (Requirement 2).
        boolean isRemovable = step2Completed && isMyIni
                && !MariadbProfile.CFG_PORT.equals(propertyKey) && !MariadbProfile.CFG_DATADIR.equals(propertyKey);

        boolean isFieldEditable = !MariadbProfile.CFG_DATADIR.equals(propertyKey) || !step2Completed;
        boolean isRefreshable = step2Completed && isMyIni && isFieldEditable;

        int splitCount = 2 + (isRemovable ? 1 : 0) + (isRefreshable ? 1 : 0);
        row.inputConstraints = "split " + splitCount + ", growx, height pref!";
        panel.add(inputComponent, row.inputConstraints);
        propertyComponents.put(propertyKey, inputComponent);

        if (isRefreshable || isRemovable) {
            JPanel actionPanel = new JPanel(new MigLayout("insets 0, gap 2"));
            actionPanel.setOpaque(false);

            if (isRefreshable) {
                JButton refreshBtn = new JButton(
                        IconFontSwing.buildIcon(FontAwesome.REFRESH, GuiConstants.getHelpIconSize(),
                                GuiColors.getApplied()));
                refreshBtn.setContentAreaFilled(false);
                refreshBtn.setBorderPainted(false);
                refreshBtn.setFocusPainted(false);
                refreshBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                refreshBtn.setToolTipText("Sync this property to my.ini immediately");
                refreshBtn.addActionListener(e -> {
                    try {
                        currentProfile.getConfiguration().put(propertyKey, valueSuppliers.get(propertyKey).get());
                        currentProfile.writeConfigFile();
                        updateUIFromData();
                    } catch (IOException ex) {
                        logger.error("Failed to sync property '{}' to my.ini: {}", propertyKey, ex.getMessage());
                        JOptionPane.showMessageDialog(this, "Failed to update config file: " + ex.getMessage(), "Error",
                                JOptionPane.ERROR_MESSAGE);
                    }
                });
                actionPanel.add(refreshBtn);
            }

            if (isRemovable) {
                JButton deleteBtn = new JButton(
                        IconFontSwing.buildIcon(FontAwesome.TRASH, GuiConstants.getHelpIconSize(),
                                GuiColors.getContrastRed()));
                deleteBtn.setContentAreaFilled(false);
                deleteBtn.setBorderPainted(false);
                deleteBtn.setFocusPainted(false);
                deleteBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                deleteBtn.setToolTipText("Remove this property from configuration and my.ini immediately");
                deleteBtn.addActionListener(e -> {
                    try {
                        currentProfile.removeProperty(propertyKey);
                        currentProfile.writeConfigFile();
                        refreshStep2DynamicContent();
                        updateUIFromData();
                    } catch (IOException ex) {
                        logger.error("Failed to delete property '{}': {}", propertyKey, ex.getMessage());
                    }
                });
                actionPanel.add(deleteBtn);
            }

            row.extra = actionPanel;
            row.extraConstraints = "gapleft 2";
            panel.add(actionPanel, row.extraConstraints);
        }

        JButton helpBtn = new HelpButton();
        row.help = helpBtn;
        row.helpConstraints = "wrap";
        panel.add(helpBtn, row.helpConstraints);

        row.separator = new JSeparator();
        row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";
        panel.add(row.separator, row.separatorConstraints);
        allPropertyRows.add(row);
    }

    private void addPasswordProperty(JPanel panel, String propertyKey, String labelText) {
        PropertyRow row = new PropertyRow(propertyKey, labelText, panel);
        JLabel label = new JLabel(labelText);
        row.label = label;
        row.labelConstraints = "align label";
        panel.add(label, row.labelConstraints);

        JPasswordField passwordField = new JPasswordField();
        char defaultEchoChar = passwordField.getEchoChar();
        ConfigurationUtils.styleInputComponent(passwordField);
        ConfigurationUtils.fixComponentSize(passwordField);
        row.input = passwordField;
        row.inputConstraints = "split 2, growx, height pref!";
        panel.add(passwordField, row.inputConstraints);

        propertyComponents.put(propertyKey, passwordField);
        valueSuppliers.put(propertyKey, () -> {
            char[] password = passwordField.getPassword();
            return password != null ? new String(password) : "";
        });

        passwordField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void update() {
                SwingUtilities.invokeLater(() -> {
                    updateColor(passwordField, propertyKey);
                });
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                update();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                update();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                update();
            }
        });

        updateColor(passwordField, propertyKey);

        JButton helpBtn = new HelpButton();
        row.help = helpBtn;
        row.helpConstraints = "wrap";
        panel.add(helpBtn, row.helpConstraints);

        // Show/Hide Checkbox
        JCheckBox showPass = new JCheckBox("Show Password");
        showPass.addActionListener(e -> passwordField.setEchoChar(showPass.isSelected() ? (char) 0 : defaultEchoChar));
        row.extra = showPass;
        row.extraConstraints = "skip 1, wrap";
        panel.add(showPass, row.extraConstraints);

        row.separator = new JSeparator();
        row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";
        panel.add(row.separator, row.separatorConstraints);
        allPropertyRows.add(row);
    }

    private void addSectionHeader(JPanel panel, String title, JLabel statusIcon, boolean isFirst) {
        PropertyRow row = new PropertyRow(null, title, panel);
        JLabel label = new JLabel(title);
        label.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 14f));
        label.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, GuiColors.getSeparator()));
        row.label = label;

        if (title.contains("Step 2")) {
            step2HeaderLabel = label;
        }
        if (title.contains("Step 3")) {
            step3HeaderLabel = label;
        }

        String commonGap = (isFirst ? "" : "gaptop 15, ") + "gapbottom 5, hidemode 3";
        if (statusIcon != null) {
            row.labelConstraints = commonGap + ", split 2, growx";
            row.extra = statusIcon; // Store icon in extra to prevent loss during filtering
            row.extraConstraints = commonGap + ", wrap";
            panel.add(label, row.labelConstraints);
            panel.add(statusIcon, row.extraConstraints);
        } else {
            row.labelConstraints = commonGap + ", span, growx, wrap";
            panel.add(label, row.labelConstraints);
        }
        allPropertyRows.add(row);
    }

    private void updateColor(JComponent comp, String propKey) {
        String savedValue = getProfileValue(currentProfile, propKey); // Use MariadbProfile
        String appliedValue = "";
        if (propKey != null && propKey.startsWith("db_name_")) {
            String id = propKey.substring(8);
            if (appliedProfileSettings.has("createdDatabases")
                    && !appliedProfileSettings.get("createdDatabases").isJsonNull()) {
                JsonArray dbs = appliedProfileSettings.getAsJsonArray("createdDatabases");
                for (JsonElement el : dbs) {
                    JsonObject obj = el.getAsJsonObject();
                    if (obj.has("id") && obj.get("id").getAsString().equals(id)) {
                        appliedValue = obj.has("name") ? obj.get("name").getAsString() : "";
                        break;
                    }
                }
            }
        } else {
            appliedValue = appliedProfileSettings.has(propKey) ? appliedProfileSettings.get(propKey).getAsString()
                    : "";
        }

        String currentValue = valueSuppliers.get(propKey) != null ? valueSuppliers.get(propKey).get() : "";
        if (currentValue == null) {
            currentValue = "";
        }

        boolean isMyIniProp = currentProfile != null && currentProfile.isMyIniProperty(propKey);
        boolean isSavedInFile = currentProfile != null && currentProfile.isKeyInConfigFile(propKey);

        Color color;
        if ((isMyIniProp && !isSavedInFile) || !currentValue.equals(savedValue)) {
            color = GuiColors.getUnsaved();
        } else if (currentValue.equals(appliedValue)) {
            color = GuiColors.getApplied();
        } else {
            color = GuiColors.getSaved();
        }
        comp.setForeground(color);

        PropertyRow row = allPropertyRows.stream().filter(r -> propKey.equals(r.propertyKey)).findFirst().orElse(null);
        if (row != null && row.label != null) {
            boolean isModified = !currentValue.equals(savedValue) || (isMyIniProp && !isSavedInFile);
            row.label.setText(isModified ? row.labelText + " *" : row.labelText);
        }
    }

    private JComponent createPermissionCheckboxesPanel(String propertyKey, String initialPermissions,
            boolean isEditable) {
        DatabaseConfigurationUtils.CheckSelectionComboBox comboBox = new DatabaseConfigurationUtils.CheckSelectionComboBox(
                initialPermissions, GRANULAR_PERMISSIONS, ALL_PERMISSIONS_KEYWORD,
                newVal -> updateColor(propertyComponents.get(propertyKey), propertyKey));
        comboBox.setEnabled(isEditable);
        ConfigurationUtils.styleInputComponent(comboBox);
        ConfigurationUtils.fixComponentSize(comboBox);

        propertyComponents.put(propertyKey, comboBox);
        valueSuppliers.put(propertyKey, comboBox::getSelectedItemsString);

        return comboBox;
    }

    private String getPermissionsString(String propertyKey) {
        JComponent comp = propertyComponents.get(propertyKey);
        if (comp instanceof DatabaseConfigurationUtils.CheckSelectionComboBox) {
            return ((DatabaseConfigurationUtils.CheckSelectionComboBox) comp).getSelectedItemsString();
        }
        return "";
    }

    private void updatePermissionPanelState(String propertyKey, String newPermissions, boolean isEditable) {
        JComponent comp = propertyComponents.get(propertyKey);
        if (comp instanceof DatabaseConfigurationUtils.CheckSelectionComboBox) {
            ((DatabaseConfigurationUtils.CheckSelectionComboBox) comp).updateState(newPermissions);
            comp.setEnabled(isEditable);
        }
    }

    private JPanel createLegendPanel() {
        return ConfigurationUtils.createLegendPanel(this);
    }

    private void showProfileHelp() {
        JOptionPane.showMessageDialog(this, "Manage DB profiles. Each profile is a folder with profile.json.", "Help",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void reloadProfile() {
        loadProfile(currentEngine, loadedProfileName);
    }

    private void saveProfile() {
        String selected = (String) profileComboBox.getSelectedItem();
        if (selected == null)
            return;
        ConfigurationUtils.updateAppliedProfile(
                ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.DATABASE_SUBFOLDER),
                currentEngine.toString() + ":" + selected);
        this.runningProfileName = selected;
        this.activeProfileName = selected;
        updateProfileComboBoxColor();
    }

    private boolean createNewProfile() {
        return createNewProfile("New-DB-Profile");
    }

    private boolean createNewProfile(String suggestedName) {
        String name = promptForNewProfileName(suggestedName);
        if (name != null && !name.trim().isEmpty()) {
            try {
                Path targetFolder = PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                        .resolve(currentEngine.toString())
                        .resolve(name);

                if (Files.notExists(targetFolder)) {
                    Files.createDirectories(targetFolder);
                }

                MariadbProfile newProfile = new MariadbProfile(name);
                // Called with an empty map so only profile.json is created as default.
                newProfile.saveToProfileJson(new HashMap<>());

                refreshProfileList();
                profileComboBox.setSelectedItem(name);
                return true;
            } catch (Exception e) {
                logger.error("Error creating new profile '{}': {}", name, e.getMessage());
                JOptionPane.showMessageDialog(this, "Error creating new profile: " + e.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
        return false;
    }

    private String promptForNewProfileName(String suggestedName) {
        JTextField nameField = new JTextField(suggestedName != null ? suggestedName : "");
        JPanel panel = new JPanel(new MigLayout("wrap 1, fillx, insets 0", "[grow]", "[]5[]"));
        panel.add(new JLabel("Enter a name for the new profile:"));
        panel.add(nameField, "growx");

        int result = JOptionPane.showConfirmDialog(this, panel, "Create New Profile",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String enteredName = nameField.getText().trim();
            if (enteredName.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Profile name cannot be empty.", "Error",
                        JOptionPane.ERROR_MESSAGE);
                return promptForNewProfileName(suggestedName); // Re-prompt
            }
            Path enginePath = PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                    .resolve(currentEngine.toString());
            if (Files.exists(enginePath.resolve(enteredName))) {
                int overwriteChoice = JOptionPane.showConfirmDialog(this,
                        "Profile '" + enteredName + "' already exists. Use this profile?",
                        "Profile Exists", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (overwriteChoice == JOptionPane.NO_OPTION) {
                    return promptForNewProfileName(suggestedName); // Re-prompt
                }
            }
            return enteredName;
        }
        return null;
    }

    private void renameProfile(String oldName) {
        if (oldName == null || oldName.equalsIgnoreCase("default")) {
            return;
        }

        String newName = (String) JOptionPane.showInputDialog(this, "Enter new name for profile '" + oldName + "':",
                "Rename Profile", JOptionPane.PLAIN_MESSAGE, null, null, oldName);
        if (newName == null || newName.trim().isEmpty() || newName.equals(oldName))
            return;

        try {
            Path enginePath = PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                    .resolve(currentEngine.toString());
            Path oldPath = enginePath.resolve(oldName);
            Path newPath = enginePath.resolve(newName);

            if (Files.exists(newPath)) {
                JOptionPane.showMessageDialog(this, "Profile '" + newName + "' already exists.", "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            // Check if Node is running this profile and if DB instance is active
            boolean isRunning = oldName.equals(runningProfileName) && currentProfile != null
                    && currentProfile.isInstanceRunning();

            if (isRunning) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "The profile '" + oldName + "' is currently in use.\n" +
                                "Renaming it will cause the Node and Database to shut down and restart automatically.\n\n"
                                +
                                "Do you want to proceed?",
                        "Confirm Node Restart",
                        JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION)
                    return;
            }

            executeRenameProfileWorker(oldName, newName, oldPath, newPath, isRunning);
        } catch (Exception e) {
            logger.error("Error determining paths for rename: {}", e.getMessage());
        }
    }

    private void executeRenameProfileWorker(String oldName, String newName, Path oldPath, Path newPath,
            boolean isRunning) {
        final JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        final JLabel statusLabel = new JLabel("Preparing...");

        JPanel progressPanel = new JPanel(new MigLayout("fillx, insets 20", "[grow]", "[]10[]"));
        progressPanel.add(statusLabel, "wrap");
        progressPanel.add(progressBar, "growx");

        final JDialog progressDialog = new JDialog((Window) SwingUtilities.getWindowAncestor(this),
                "Renaming Profile", Dialog.ModalityType.APPLICATION_MODAL);
        progressDialog.add(progressPanel);
        progressDialog.pack();
        progressDialog.setLocationRelativeTo(this);

        if (!isConsoleExpanded)
            toggleConsole();
        appendLog("\n--- Renaming MariaDB profile: " + oldName + " -> " + newName + " ---");

        new SwingWorker<Void, ProgressInfo>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (isRunning) {
                    publish(new ProgressInfo("Stopping Signum Node core...", 10));
                    Signum.shutdownNode();
                    Thread.sleep(2000);

                    if (currentProfile != null && currentProfile.isInstanceRunning()) {
                        publish(new ProgressInfo("Stopping MariaDB instance...", 30));
                        currentProfile.stopInstance((msg, p) -> publish(new ProgressInfo(msg, 30 + (p / 5))));
                    }
                }

                publish(new ProgressInfo("Moving profile folder...", 60));
                Files.move(oldPath, newPath);

                publish(new ProgressInfo("Updating metadata...", 70));
                ConfigurationUtils.updateAppliedProfile(
                        ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.DATABASE_SUBFOLDER),
                        DatabaseEngine.MARIADB.getDisplayName() + ":" + newName);

                if (oldName.equals(loadedProfileName)) {
                    loadedProfileName = newName;
                    activeProfilePath = newPath;
                    if (currentProfile != null)
                        currentProfile.setProfileName(newName);
                }
                if (oldName.equals(runningProfileName)) {
                    runningProfileName = newName;
                    activeProfileName = newName;
                }

                if (isRunning) {
                    publish(new ProgressInfo("Restarting MariaDB instance...", 80));
                    if (currentProfile != null) {
                        currentProfile.ensureInstanceRunning((msg, p) -> publish(new ProgressInfo(msg, 80 + (p / 10))));
                    }

                    publish(new ProgressInfo("Restarting Signum Node core...", 95));
                    Signum.startNode();
                }

                return null;
            }

            @Override
            protected void process(List<ProgressInfo> chunks) {
                ProgressInfo info = chunks.get(chunks.size() - 1);
                statusLabel.setText(info.message);
                progressBar.setValue(info.progress);
                appendLog(info.message);
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                try {
                    get();
                    refreshProfileList();
                    profileComboBox.setSelectedItem(newName);
                    updateUIFromData();
                    JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this, "Profile renamed successfully.");
                } catch (Exception e) {
                    logger.error("Rename operation failed", e);
                    JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this,
                            "Error: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
        progressDialog.setVisible(true);
    }

    private void deleteProfile(String name) {
        if (name == null || name.trim().isEmpty())
            return;

        int confirm = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to delete profile '" + name
                        + "'?\nThis will delete all database files in that folder!",
                "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION)
            return;

        try {
            Path profilePath = PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                    .resolve(currentEngine.toString()).resolve(name);
            DatabaseConfigurationUtils.deleteDirectoryRecursively(profilePath);

            this.loadedProfileName = null; // No profile loaded after deletion
            refreshProfileList();
            profileComboBox.setSelectedItem(null); // Select nothing
            loadProfile(currentEngine, null); // Load empty state

            JOptionPane.showMessageDialog(this, "Profile deleted successfully.", "Success",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            logger.error("Error deleting profile: {}", e.getMessage());
            JOptionPane.showMessageDialog(this, "Error deleting folder: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    public static List<SubVersionInfo> fetchPointReleases(String majorVersion, String apiUrl) {
        List<SubVersionInfo> subVersions = new ArrayList<>();
        String fullUrl = apiUrl + majorVersion + "/";
        try {
            URL url = new URL(fullUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    logger.debug("MariaDB API point releases response received for version {}.", majorVersion);
                    JsonObject response = JsonParser.parseReader(reader).getAsJsonObject();
                    if (response.has("releases")) {
                        JsonObject releasesObj = response.getAsJsonObject("releases");
                        for (Map.Entry<String, com.google.gson.JsonElement> entry : releasesObj.entrySet()) {
                            if (entry.getValue() == null || entry.getValue().isJsonNull())
                                continue;
                            JsonObject rel = entry.getValue().getAsJsonObject();
                            SubVersionInfo sv = new SubVersionInfo();
                            sv.name = entry.getKey(); // The key is the version name, e.g., "10.5.12"

                            if (rel.has("files") && !rel.get("files").isJsonNull()) {
                                rel.getAsJsonArray("files").forEach(f -> {
                                    JsonObject fileObj = f.getAsJsonObject();
                                    if (fileObj.get("package_type") == null || fileObj.get("package_type").isJsonNull())
                                        return;
                                    String pkgType = fileObj.get("package_type").getAsString().toLowerCase();
                                    String fileName = fileObj.has("file_name") && !fileObj.get("file_name").isJsonNull()
                                            ? fileObj.get("file_name").getAsString().toLowerCase()
                                            : "";

                                    if (pkgType.contains("debug") || pkgType.contains("source")
                                            || fileName.contains("debug") || fileName.contains("src")) {
                                        return;
                                    }

                                    String fileOs = fileObj.get("os").isJsonNull() ? ""
                                            : fileObj.get("os").getAsString();
                                    String fileCpu = fileObj.get("cpu").isJsonNull() ? ""
                                            : fileObj.get("cpu").getAsString();
                                    String fileUrl = fileObj.get("file_download_url").isJsonNull() ? ""
                                            : fileObj.get("file_download_url").getAsString();

                                    // Binary packages (e.g., "Binary zip") are also accepted.
                                    if (pkgType.contains("zip") || pkgType.contains("tar.gz")) {
                                        // Safely retrieve fields, handling potential JsonNull values
                                        DownloadEntry de = new DownloadEntry();
                                        de.os = fileOs;
                                        de.arch = fileCpu;
                                        de.file = fileUrl;
                                        sv.downloads.add(de);
                                    }
                                });
                            }
                            subVersions.add(sv);
                        }
                    }
                }
            }

            // Semantic version sorting (descending)
            subVersions.sort((a, b) -> {
                String[] partsA = a.name.split("\\.");
                String[] partsB = b.name.split("\\.");
                int length = Math.max(partsA.length, partsB.length);
                for (int i = 0; i < length; i++) {
                    int vA = i < partsA.length ? Integer.parseInt(partsA[i].replaceAll("\\D", "")) : 0;
                    int vB = i < partsB.length ? Integer.parseInt(partsB[i].replaceAll("\\D", "")) : 0;
                    if (vA != vB)
                        return Integer.compare(vB, vA);
                }
                return b.name.compareTo(a.name);
            });

            String fetchedVersions = subVersions.stream().map(sv -> sv.name)
                    .collect(java.util.stream.Collectors.joining(", "));
            logger.debug("Successfully fetched {} MariaDB point releases for {}: [{}]", subVersions.size(),
                    majorVersion, fetchedVersions);
        } catch (Exception e) {
            logger.error("Error fetching MariaDB point releases for {}: {}", majorVersion, e.getMessage());
        }
        return subVersions;
    }

    public static String getDownloadUrlForCurrentOs(SubVersionInfo subVersion, String os, String arch,
            JsonObject globalSettings) {
        logger.debug("Evaluating downloads for version {}. Target OS: '{}', Target Arch: '{}'", subVersion.name, os,
                arch);
        for (DownloadEntry entry : subVersion.downloads) {
            logger.debug("  Checking download entry: entryOS='{}', entryArch='{}'", entry.os, entry.arch);
            if (entry.os.equalsIgnoreCase(os)) {
                boolean archMatch = entry.arch.equalsIgnoreCase(arch);
                if (!archMatch) {
                    // Architecture aliases mapping for MariaDB API compatibility (e.g., node "x64"
                    // vs API "x86_64")
                    if (arch.equalsIgnoreCase("x64") &&
                            (entry.arch.equalsIgnoreCase("x86_64") || entry.arch.equalsIgnoreCase("amd64"))) {
                        archMatch = true;
                    } else if (arch.equalsIgnoreCase("arm64") && entry.arch.equalsIgnoreCase("aarch64")) {
                        archMatch = true;
                    }
                }

                if (archMatch) {
                    String resolvedUrl = entry.file;

                    if (resolvedUrl.contains("downloads.mariadb.org")) {
                        String filename = resolvedUrl.substring(resolvedUrl.lastIndexOf('/') + 1);
                        String version = subVersion.name;

                        String platformFolder = "";
                        if (os.equalsIgnoreCase("windows")) {
                            platformFolder = "winx64-packages/";
                        } else if (os.equalsIgnoreCase("linux")) {
                            platformFolder = (arch.equalsIgnoreCase("x64")) ? "bintar-linux-x86_64/"
                                    : "bintar-linux-aarch64/";
                        }

                        resolvedUrl = "https://archive.mariadb.org/mariadb-" + version + "/" + platformFolder
                                + filename;
                    } else if (!resolvedUrl.toLowerCase().startsWith("http")) {
                        JsonObject engineSettings = globalSettings.getAsJsonObject("mariaDb");
                        if (engineSettings != null && engineSettings.has("downloadBaseUrl")) {
                            resolvedUrl = engineSettings.get("downloadBaseUrl").getAsString() + resolvedUrl;
                        }
                    }

                    return resolvedUrl;
                }
            }
        }
        return null;
    }

    public MariaDBConfigurationPanel() {
        super(new BorderLayout());
        // add(new JLabel("MariaDB Configuration")); // This line is not needed, initUI
        // will build the panel

        this.confFolder = Signum.CONF_FOLDER;

        this.currentOsName = DatabaseConfigurationUtils.getOsName();
        this.currentOsArch = DatabaseConfigurationUtils.getOsArch();

        // Initialize currentProfile with a default empty profile name, will be
        // overwritten by loadProfile
        this.currentProfile = new MariadbProfile(null);

        this.globalSettings = DatabaseConfigurationUtils.loadGlobalSettings();
        DatabaseConfigurationUtils.ensureDirectoryStructure();

        // Determine the currently applied profile name from metadata once at startup
        String lastProfile = ConfigurationUtils
                .loadAppliedProfile(ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.DATABASE_SUBFOLDER));

        // Format in metadata for DB is "Engine:ProfileName"
        String lastEngine = DatabaseEngine.MARIADB.getDisplayName(); // This panel is specifically for MariaDB
        String lastProfileName = null; // Start with no profile selected by default

        if (lastProfile != null && !lastProfile.trim().isEmpty() && lastProfile.contains(":")) {
            String[] parts = lastProfile.split(":");
            // Only use the profile name if the engine matches MariaDB
            if (parts[0].equals(DatabaseEngine.MARIADB.getDisplayName())) {
                lastProfileName = parts[1];
            }
        }

        this.currentEngine = DatabaseEngine.MARIADB; // This panel is always for MariaDB
        this.runningProfileName = lastProfileName;
        this.activeProfileName = lastProfileName; // Initial selection in the UI
        this.loadedProfileName = null; // Nothing loaded yet
        this.activeProfilePath = null;

        // Set to false initially to prevent listeners from firing during UI build
        this.isInitialized = false;
        initUI();
        this.isInitialized = true; // Mark as initialized before the first data load

        loadProfile(this.currentEngine, lastProfileName);
    }

    private boolean objectsEqual(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }

    private void toggleConsole() {
        if (consoleAnimator != null && consoleAnimator.isRunning())
            return;

        isConsoleExpanded = !isConsoleExpanded;
        consoleChevron.setDrawing(isConsoleExpanded ? CustomDrawings.Chevron.UP : CustomDrawings.Chevron.DOWN);

        final int targetHeight = isConsoleExpanded ? consoleHeight : 0;
        final int startHeight = consoleWrapper.getHeight();

        consoleAnimator = new Timer(10, new ActionListener() {
            final long startTime = System.currentTimeMillis();
            final int duration = 250;

            @Override
            public void actionPerformed(ActionEvent e) {
                long elapsed = System.currentTimeMillis() - startTime;
                float progress = Math.min(1.0f, (float) elapsed / duration);
                progress = 1.0f - (float) Math.pow(1.0f - progress, 3); // Cubic Ease Out

                int h = (int) (startHeight + (targetHeight - startHeight) * progress);
                consoleWrapper.setPreferredSize(
                        new Dimension(consoleWrapper.getWidth() > 0 ? consoleWrapper.getWidth() : 100, h));
                dbControlPanel.revalidate();

                if (progress >= 1.0f) {
                    ((Timer) e.getSource()).stop();
                    consoleWrapper.setPreferredSize(new Dimension(consoleWrapper.getWidth(), targetHeight));
                    dbControlPanel.revalidate();
                }
            }
        });
        consoleAnimator.start();
    }

    private void appendLog(String line) {
        SwingUtilities.invokeLater(() -> {
            try {
                javax.swing.text.Document doc = consoleTextPane.getDocument();
                doc.insertString(doc.getLength(), line + "\n", null);
                consoleTextPane.setCaretPosition(doc.getLength());
            } catch (Exception e) {
                // ignore
            }
        });
    }

    private void executeStartDatabaseWorker() {
        executeDbControlWorker("Starting", currentProfile::ensureInstanceRunning, "Database started successfully.");
    }

    private void executeStopDatabaseWorker() {
        executeDbControlWorker("Stopping", currentProfile::stopInstance, "Database stopped successfully.");
    }

    private void executeRestartDatabaseWorker() {
        executeDbControlWorker("Restarting", currentProfile::restartInstance, "Database restarted successfully.");
    }

    private interface DbControlAction {
        void execute(DatabaseConfigurationUtils.ProgressListener listener) throws Exception;
    }

    private void executeDbControlWorker(String actionName, DbControlAction action, String successMsg) {
        if (currentProfile == null)
            return;

        if (!isConsoleExpanded)
            toggleConsole();
        appendLog("\n--- " + actionName + " MariaDB Operation ---");

        final JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        final JLabel statusLabel = new JLabel("Preparing...");
        statusLabel.setFont(UIManager.getFont("Label.font"));

        JPanel progressPanel = new JPanel(new MigLayout("fillx, insets 20", "[grow]", "[]10[]"));
        progressPanel.add(statusLabel, "wrap");
        progressPanel.add(progressBar, "growx");

        final JDialog progressDialog = new JDialog((Window) SwingUtilities.getWindowAncestor(this),
                actionName + " MariaDB", Dialog.ModalityType.APPLICATION_MODAL);
        progressDialog.add(progressPanel);
        progressDialog.pack();
        progressDialog.setMinimumSize(new Dimension(450, progressDialog.getHeight()));
        progressDialog.setLocationRelativeTo(this);

        new SwingWorker<Void, ProgressInfo>() {
            @Override
            protected Void doInBackground() throws Exception {
                action.execute(new DatabaseConfigurationUtils.ProgressListener() {
                    @Override
                    public void onProgress(String msg, int p) {
                        publish(new ProgressInfo(msg, p));
                    }

                    @Override
                    public void onLog(String line) {
                        MariaDBConfigurationPanel.this.appendLog(line);
                    }
                });
                return null;
            }

            @Override
            protected void process(List<ProgressInfo> chunks) {
                ProgressInfo info = chunks.get(chunks.size() - 1);
                progressBar.setValue(info.progress);
                statusLabel.setText(info.message);
                appendLog(info.message);
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                try {
                    get();
                    updateUIFromData();
                    JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this, successMsg);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(MariaDBConfigurationPanel.this, "Error: " + e.getCause().getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
        progressDialog.setVisible(true);
    }

    // The fetchReleasesButton was part of the original constructor, but it's now
    // handled by initUI and updateMainVersionComboBox
    // JButton fetchReleasesButton = new JButton("Fetch Major Releases");
    // fetchReleasesButton.addActionListener(e -> {
    // List<MainVersionInfo> releases = fetchMajorReleases(API_BASE_URL);
    // if (releases != null) {
    // JOptionPane.showMessageDialog(this, "Fetched " + releases.size() + " major
    // releases.");
    // } else {
    // JOptionPane.showMessageDialog(this, "Failed to fetch releases.", "Error",
    // JOptionPane.ERROR_MESSAGE);
    // }
    // });
    // add(fetchReleasesButton); // This button is now part of the initUI() in
    // populateStep1Content

}