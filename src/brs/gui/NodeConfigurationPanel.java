package brs.gui;

import brs.crypto.Crypto;
import brs.props.Prop;
import brs.props.Props;
import brs.util.Convert;
import brs.util.PathUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NodeConfigurationPanel extends JPanel {

    private final Properties currentProperties;
    private final Properties appliedProperties;
    private final Map<String, Supplier<String>> valueSuppliers = new HashMap<>();
    private final Map<String, JComponent> propertyComponents = new HashMap<>();
    private final Map<String, String> helpTexts = new HashMap<>();
    private final Map<String, String> defaultValues = new HashMap<>();
    private final Runnable restartAction;
    private final String confFolder;
    private final Path propertiesFile;
    private JComboBox<String> profileComboBox;

    public NodeConfigurationPanel(Runnable restartAction, String confFolder) {
        super(new BorderLayout());
        this.restartAction = restartAction;
        this.confFolder = confFolder;
        this.propertiesFile = PathUtils.resolvePath(confFolder).resolve("node.properties");

        // Ensure properties file exists and load it
        ensurePropertiesFileExists();

        this.currentProperties = new Properties();
        try (FileInputStream in = new FileInputStream(propertiesFile.toFile())) {
            this.currentProperties.load(in);
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.appliedProperties = new Properties();
        this.appliedProperties.putAll(this.currentProperties);

        initHelpTexts();
        initUI();
    }

    private void initUI() {
        // --- Profile Panel ---
        JPanel profilePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        profilePanel.setBorder(new EmptyBorder(5, 10, 5, 5));
        profilePanel.add(new JLabel("Configuration Profile:"));

        profileComboBox = new JComboBox<>();
        profileComboBox.setEditable(false);
        profileComboBox.setPreferredSize(new Dimension(200, 25));
        profilePanel.add(profileComboBox);

        JButton loadProfileBtn = new JButton("Load Profile");
        loadProfileBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.FOLDER_OPEN, 16, Color.BLACK));
        loadProfileBtn.setToolTipText("Load selected profile");
        loadProfileBtn.addActionListener(e -> loadProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(loadProfileBtn);

        JButton saveProfileBtn = new JButton("Save Profile");
        saveProfileBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.FLOPPY_O, 16, Color.BLACK));
        saveProfileBtn.setToolTipText("Save Configuration Profile");
        saveProfileBtn.addActionListener(e -> saveProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(saveProfileBtn);

        JButton helpBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.QUESTION_CIRCLE, 16, Color.LIGHT_GRAY));
        helpBtn.setBorder(BorderFactory.createEmptyBorder());
        helpBtn.setContentAreaFilled(false);
        helpBtn.setFocusPainted(false);
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info about Configuration Profiles");
        helpBtn.addActionListener(e -> showProfileHelp());
        profilePanel.add(helpBtn);

        loadProfiles(profileComboBox);
        add(profilePanel, BorderLayout.NORTH);

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setBorder(new JScrollPane().getBorder());

        // --- API Server Settings ---
        JPanel apiPanel = createCategoryPanel();
        addProperty(apiPanel, Props.API_SERVER, "Enable API Server");
        addProperty(apiPanel, Props.API_PORT, "API Port");
        addProperty(apiPanel, Props.API_LISTEN, "API Listen Interface");
        addListProperty(apiPanel, Props.API_ALLOWED, "Allowed IPs");
        addListProperty(apiPanel, Props.API_ADMIN_KEY_LIST, "Admin Password (API Key)");
        addProperty(apiPanel, Props.API_SSL, "Enable SSL");
        addProperty(apiPanel, Props.API_DOC_MODE, "API Documentation Mode", new String[] { "modern", "legacy", "off" });
        addProperty(apiPanel, Props.API_UI_DIR, "UI Directory");
        addProperty(apiPanel, Props.API_WEBSOCKET_ENABLE, "Enable WebSockets");
        addProperty(apiPanel, Props.API_WEBSOCKET_PORT, "WebSocket Port");
        addProperty(apiPanel, Props.API_WEBSOCKET_HEARTBEAT_INTERVAL, "WebSocket Heartbeat Interval");
        addListProperty(apiPanel, Props.API_ALLOWED_ORIGINS, "CORS Allowed Origins");
        addProperty(apiPanel, Props.API_ACCEPT_SURPLUS_PARAMS, "Accept Surplus Params");
        addProperty(apiPanel, Props.API_SERVER_ENFORCE_POST, "Enforce POST");
        addProperty(apiPanel, Props.API_SERVER_IDLE_TIMEOUT, "Idle Timeout");
        addProperty(apiPanel, Props.API_SSL_KEY_STORE_PATH, "SSL KeyStore Path");
        addPasswordProperty(apiPanel, Props.API_SSL_KEY_STORE_PASSWORD, "SSL KeyStore Password");
        addProperty(apiPanel, Props.API_SSL_LETSENCRYPT_PATH, "SSL LetsEncrypt Path");
        finalizeCategoryPanel(apiPanel);
        tabbedPane.addTab("API Settings", createScrollPane(apiPanel));

        // --- Database Settings ---
        JPanel dbPanel = createCategoryPanel();
        addProperty(dbPanel, Props.DB_URL, "JDBC Connection URL", getSuggestions(Props.DB_URL.getName()), true);
        addProperty(dbPanel, Props.DB_USERNAME, "Database Username");
        addPasswordProperty(dbPanel, Props.DB_PASSWORD, "Database Password");
        addProperty(dbPanel, Props.DB_CONNECTIONS, "Max Connections");
        addProperty(dbPanel, Props.DB_TRIM_DERIVED_TABLES, "Trim Derived Tables");
        addProperty(dbPanel, Props.DB_OPTIMIZE, "Optimize DB on Start/Stop");
        addProperty(dbPanel, Props.DB_SQLITE_JOURNAL_MODE, "SQLite Journal Mode",
                new String[] { "WAL", "DELETE", "TRUNCATE", "PERSIST", "MEMORY", "OFF" });
        addProperty(dbPanel, Props.DB_SQLITE_SYNCHRONOUS, "SQLite Synchronous",
                new String[] { "NORMAL", "FULL", "OFF" });
        addProperty(dbPanel, Props.DB_SQLITE_CACHE_SIZE, "SQLite Cache Size");
        addProperty(dbPanel, Props.DB_INSERT_BATCH_MAX_SIZE, "DB Insert Batch Size");
        addProperty(dbPanel, Props.DB_SKIP_CHECK, "Skip DB Check on Start");
        addProperty(dbPanel, Props.BRS_BLOCK_CACHE_MB, "Block Cache (MB)");
        finalizeCategoryPanel(dbPanel);
        tabbedPane.addTab("Database", createScrollPane(dbPanel));

        // --- P2P Networking ---
        JPanel p2pPanel = createCategoryPanel();
        addProperty(p2pPanel, Props.P2P_PORT, "P2P Port");
        addProperty(p2pPanel, Props.P2P_LISTEN, "P2P Listen Interface");
        addProperty(p2pPanel, Props.P2P_UPNP, "Enable UPnP");
        addProperty(p2pPanel, Props.P2P_MY_PLATFORM, "My Platform");
        addProperty(p2pPanel, Props.P2P_MY_ADDRESS, "My External Address");
        addProperty(p2pPanel, Props.P2P_SHARE_MY_ADDRESS, "Share My Address");
        addListProperty(p2pPanel, Props.P2P_BOOTSTRAP_PEERS, "Bootstrap Peers");
        addListProperty(p2pPanel, Props.P2P_REBROADCAST_TO, "Rebroadcast To");
        addProperty(p2pPanel, Props.P2P_NUM_BOOTSTRAP_CONNECTIONS, "Num Bootstrap Connections");
        addListProperty(p2pPanel, Props.P2P_BLACKLISTED_PEERS, "Blacklisted Peers");
        addProperty(p2pPanel, Props.P2P_MAX_CONNECTIONS, "Max Connections");
        addProperty(p2pPanel, Props.P2P_MAX_BLOCKS, "Max Blocks per Req");
        addProperty(p2pPanel, Props.P2P_TIMEOUT_CONNECT_MS, "Connect Timeout (ms)");
        addProperty(p2pPanel, Props.P2P_TIMEOUT_READ_MS, "Read Timeout (ms)");
        addProperty(p2pPanel, Props.P2P_TIMEOUT_IDLE_MS, "Idle Timeout (ms)");
        addProperty(p2pPanel, Props.P2P_BLACKLISTING_TIME_MS, "Blacklisting Time (ms)");
        addProperty(p2pPanel, Props.P2P_ENABLE_TX_REBROADCAST, "Enable Tx Rebroadcast");
        addProperty(p2pPanel, Props.P2P_USE_PEERS_DB, "Use Peers DB");
        addProperty(p2pPanel, Props.P2P_SAVE_PEERS, "Save Peers");
        addProperty(p2pPanel, Props.P2P_GET_MORE_PEERS, "Get More Peers");
        addProperty(p2pPanel, Props.P2P_GET_MORE_PEERS_THRESHOLD, "Get More Peers Threshold");
        addProperty(p2pPanel, Props.P2P_SEND_TO_LIMIT, "Send To Limit");
        addProperty(p2pPanel, Props.P2P_MAX_UNCONFIRMED_TRANSACTIONS, "Max Unconfirmed Txs");
        addProperty(p2pPanel, Props.P2P_MAX_PERCENTAGE_UNCONFIRMED_TRANSACTIONS_FULL_HASH_REFERENCE,
                "Max Unconfirmed Txs Full Hash Ref %");
        addProperty(p2pPanel, Props.P2P_MAX_UNCONFIRMED_TRANSACTIONS_RAW_SIZE_BYTES_TO_SEND,
                "Max Unconfirmed Txs Raw Size Bytes");
        finalizeCategoryPanel(p2pPanel);
        tabbedPane.addTab("P2P Networking", createScrollPane(p2pPanel));

        // --- Mining & GPU ---
        JPanel miningPanel = createCategoryPanel();
        addProperty(miningPanel, Props.GPU_ACCELERATION, "Enable GPU Acceleration");
        addProperty(miningPanel, Props.GPU_AUTODETECT, "Auto-Detect GPU");
        addProperty(miningPanel, Props.GPU_PLATFORM_IDX, "GPU Platform Index");
        addProperty(miningPanel, Props.GPU_DEVICE_IDX, "GPU Device Index");
        addProperty(miningPanel, Props.GPU_MEM_PERCENT, "GPU Memory Usage (%)");
        addProperty(miningPanel, Props.GPU_UNVERIFIED_QUEUE, "Unverified Queue Size");
        addProperty(miningPanel, Props.GPU_DYNAMIC_HASHES_PER_BATCH, "Dynamic Hashes Per Batch");
        addProperty(miningPanel, Props.GPU_HASHES_PER_BATCH, "Hashes Per Batch");
        addListProperty(miningPanel, Props.SOLO_MINING_PASSPHRASES, "Solo Mining Passphrases");
        addListProperty(miningPanel, Props.REWARD_RECIPIENT_PASSPHRASES, "Reward Recipient Passphrases");
        addProperty(miningPanel, Props.ALLOW_OTHER_SOLO_MINERS, "Allow Other Solo Miners");
        finalizeCategoryPanel(miningPanel);
        tabbedPane.addTab("Mining & GPU", createScrollPane(miningPanel));

        // --- System & Advanced ---
        JPanel systemPanel = createCategoryPanel();
        addProperty(systemPanel, Props.APPLICATION, "Application Name");
        addProperty(systemPanel, Props.VERSION, "Node Version");
        addProperty(systemPanel, Props.NETWORK_NAME, "Network Name");
        addProperty(systemPanel, Props.CPU_NUM_CORES, "CPU Cores Limit");
        addProperty(systemPanel, Props.BLOCK_PROCESS_THREAD_DELAY, "Thread Delay (ms)");
        addProperty(systemPanel, Props.MAX_INDIRECTS_PER_BLOCK, "Max Indirects Per Block");
        addProperty(systemPanel, Props.EXPERIMENTAL, "Enable Experimental Features");
        addProperty(systemPanel, Props.MEASUREMENT_ACTIVE, "Enable Metrics/Measurement");
        addProperty(systemPanel, Props.MEASUREMENT_DIR, "Measurement Dir");
        addProperty(systemPanel, Props.SETTINGS_DIR, "Settings Dir");
        addProperty(systemPanel, Props.ICON_LOCATION, "Icon Location");
        addProperty(systemPanel, Props.AUTO_POP_OFF_ENABLED, "Enable Auto Pop-Off");
        addProperty(systemPanel, Props.AUTO_CONSISTENCY_RESOLVE_ENABLED, "Enable Auto DB Resolve");
        addProperty(systemPanel, Props.INDIRECT_INCOMING_SERVICE_ENABLE, "Enable Indirect Incoming Service");
        addProperty(systemPanel, Props.BRS_AT_PROCESSOR_CACHE_BLOCK_COUNT, "AT Processor Cache (Blocks)");
        addProperty(systemPanel, Props.BRS_SHUTDOWN_TIMEOUT, "Shutdown Timeout (sec)");
        addProperty(systemPanel, Props.BRS_CHECKPOINT_HEIGHT, "Checkpoint Height");
        addProperty(systemPanel, Props.BRS_CHECKPOINT_HASH, "Checkpoint Hash");
        addListProperty(systemPanel, Props.BRS_PK_CHECKS, "PK Checks");
        addProperty(systemPanel, Props.ENABLE_AT_DEBUG_LOG, "Enable AT Debug Log");
        addProperty(systemPanel, Props.CASH_BACK_ID, "Cash Back ID");
        addProperty(systemPanel, Props.CASH_BACK_FACTOR, "Cash Back Factor");
        addProperty(systemPanel, Props.ALIAS_RENEWAL_FREQUENCY, "Alias Renewal Frequency");
        finalizeCategoryPanel(systemPanel);
        tabbedPane.addTab("System & Advanced", createScrollPane(systemPanel));

        // --- Dev & Debug ---
        JPanel devPanel = createCategoryPanel();
        addProperty(devPanel, Props.DEV_OFFLINE, "Offline Mode");
        addProperty(devPanel, Props.DEV_TIMEWARP, "Time Warp");
        addProperty(devPanel, Props.DEV_MOCK_MINING, "Mock Mining");
        addProperty(devPanel, Props.DEV_MOCK_MINING_DEADLINE, "Mock Mining Deadline");
        addProperty(devPanel, Props.BRS_TEST_UNCONFIRMED_TRANSACTIONS, "Test Unconfirmed Txs");
        addProperty(devPanel, Props.DEV_DUMP_PEERS_VERSION, "Dump Peers Version");
        addProperty(devPanel, Props.BRS_DEBUG_TRACE_ENABLED, "Debug Trace Enabled");
        addProperty(devPanel, Props.BRS_DEBUG_TRACE_QUOTE, "Debug Trace Quote");
        addProperty(devPanel, Props.BRS_DEBUG_TRACE_SEPARATOR, "Debug Trace Separator");
        addProperty(devPanel, Props.BRS_DEBUG_LOG_CONFIRMED, "Log Confirmed");
        addListProperty(devPanel, Props.BRS_DEBUG_TRACE_ACCOUNTS, "Debug Trace Accounts");
        addProperty(devPanel, Props.BRS_DEBUG_TRACE_LOG, "Debug Trace Log File");
        addProperty(devPanel, Props.BRS_COMMUNICATION_LOGGING_MASK, "Communication Logging Mask");
        finalizeCategoryPanel(devPanel);
        tabbedPane.addTab("Dev & Debug", createScrollPane(devPanel));

        // --- Jetty Server ---
        JPanel jettyPanel = createCategoryPanel();
        addProperty(jettyPanel, Props.JETTY_API_GZIP_FILTER, "API Gzip Filter");
        addProperty(jettyPanel, Props.JETTY_API_GZIP_FILTER_MIN_GZIP_SIZE, "API Gzip Min Size");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER, "API DoS Filter");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_MAX_REQUEST_PER_SEC, "API DoS Max Req/Sec");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_THROTTLED_REQUESTS, "API DoS Throttled Reqs");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_DELAY_MS, "API DoS Delay (ms)");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_MAX_WAIT_MS, "API DoS Max Wait (ms)");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_MAX_REQUEST_MS, "API DoS Max Req (ms)");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_THROTTLE_MS, "API DoS Throttle (ms)");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_MAX_IDLE_TRACKER_MS, "API DoS Max Idle Tracker (ms)");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_TRACK_SESSIONS, "API DoS Track Sessions");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_INSERT_HEADERS, "API DoS Insert Headers");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_REMOTE_PORT, "API DoS Remote Port");
        addListProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_IP_WHITELIST, "API DoS IP Whitelist");
        addProperty(jettyPanel, Props.JETTY_API_DOS_FILTER_MANAGED_ATTR, "API DoS Managed Attr");

        addProperty(jettyPanel, Props.JETTY_P2P_GZIP_FILTER, "P2P Gzip Filter");
        addProperty(jettyPanel, Props.JETTY_P2P_GZIP_FILTER_MIN_GZIP_SIZE, "P2P Gzip Min Size");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER, "P2P DoS Filter");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_MAX_REQUESTS_PER_SEC, "P2P DoS Max Req/Sec");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_THROTTLED_REQUESTS, "P2P DoS Throttled Reqs");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_DELAY_MS, "P2P DoS Delay (ms)");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_MAX_WAIT_MS, "P2P DoS Max Wait (ms)");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_MAX_REQUEST_MS, "P2P DoS Max Req (ms)");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_THROTTLE_MS, "P2P DoS Throttle (ms)");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_MAX_IDLE_TRACKER_MS, "P2P DoS Max Idle Tracker (ms)");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_TRACK_SESSIONS, "P2P DoS Track Sessions");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_INSERT_HEADERS, "P2P DoS Insert Headers");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_REMOTE_PORT, "P2P DoS Remote Port");
        addListProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_IP_WHITELIST, "P2P DoS IP Whitelist");
        addProperty(jettyPanel, Props.JETTY_P2P_DOS_FILTER_MANAGED_ATTR, "P2P DoS Managed Attr");
        finalizeCategoryPanel(jettyPanel);
        tabbedPane.addTab("Jetty Server", createScrollPane(jettyPanel));

        // --- Network Constants ---
        JPanel netPanel = createCategoryPanel();
        addProperty(netPanel, Props.BLOCK_TIME, "Block Time");
        addProperty(netPanel, Props.DECIMAL_PLACES, "Decimal Places");
        addProperty(netPanel, Props.ONE_COIN_NQT, "One Coin NQT");
        addProperty(netPanel, Props.GENESIS_BLOCK_ID, "Genesis Block ID");
        addProperty(netPanel, Props.GENESIS_TIMESTAMP, "Genesis Timestamp");
        addProperty(netPanel, Props.ADDRESS_PREFIX, "Address Prefix");
        addProperty(netPanel, Props.VALUE_SUFIX, "Value Suffix");
        addProperty(netPanel, Props.BLOCK_REWARD_START, "Block Reward Start");
        addProperty(netPanel, Props.BLOCK_REWARD_CYCLE, "Block Reward Cycle");
        addProperty(netPanel, Props.BLOCK_REWARD_CYCLE_PERCENTAGE, "Block Reward Cycle %");
        addProperty(netPanel, Props.BLOCK_REWARD_LIMIT_HEIGHT, "Block Reward Limit Height");
        addProperty(netPanel, Props.BLOCK_REWARD_LIMIT_AMOUNT, "Block Reward Limit Amount");
        addProperty(netPanel, Props.NETWORK_PARAMETERS, "Network Parameters Class");

        addProperty(netPanel, Props.REWARD_RECIPIENT_ENABLE_BLOCK_HEIGHT, "Reward Recipient Start");
        addProperty(netPanel, Props.DIGITAL_GOODS_STORE_BLOCK_HEIGHT, "DGS Start");
        addProperty(netPanel, Props.AUTOMATED_TRANSACTION_BLOCK_HEIGHT, "AT Start");
        addProperty(netPanel, Props.AT_FIX_BLOCK_2_BLOCK_HEIGHT, "AT Fix 2 Start");
        addProperty(netPanel, Props.AT_FIX_BLOCK_3_BLOCK_HEIGHT, "AT Fix 3 Start");
        addProperty(netPanel, Props.AT_FIX_BLOCK_4_BLOCK_HEIGHT, "AT Fix 4 Start");
        addProperty(netPanel, Props.AT_FIX_BLOCK_5_BLOCK_HEIGHT, "AT Fix 5 Start");
        addProperty(netPanel, Props.PRE_POC2_BLOCK_HEIGHT, "Pre-PoC2 Start");
        addProperty(netPanel, Props.POC2_BLOCK_HEIGHT, "PoC2 Start");
        addProperty(netPanel, Props.SODIUM_BLOCK_HEIGHT, "Sodium Start");
        addProperty(netPanel, Props.SIGNUM_HEIGHT, "Signum Start");
        addProperty(netPanel, Props.POC_PLUS_HEIGHT, "PoC+ Start");
        addProperty(netPanel, Props.SPEEDWAY_HEIGHT, "Speedway Start");
        addProperty(netPanel, Props.SMART_TOKEN_HEIGHT, "Smart Token Start");
        addProperty(netPanel, Props.SMART_FEES_HEIGHT, "Smart Fees Start");
        addProperty(netPanel, Props.SMART_ATS_HEIGHT, "Smart ATs Start");
        addProperty(netPanel, Props.DISTRIBUTION_FIX_BLOCK_HEIGHT, "Distribution Fix Start");
        addProperty(netPanel, Props.PK_BLOCK_HEIGHT, "PK Block Start");
        addProperty(netPanel, Props.PK2_BLOCK_HEIGHT, "PK2 Block Start");
        addProperty(netPanel, Props.PK_BLOCKS_PAST, "PK Blocks Past");
        addProperty(netPanel, Props.PK_API_BLOCK, "PK API Block");
        addProperty(netPanel, Props.SMART_ALIASES_HEIGHT, "Smart Aliases Start");
        addProperty(netPanel, Props.DEV_NEXT_FORK_BLOCK_HEIGHT, "Dev Next Fork Start");
        finalizeCategoryPanel(netPanel);
        tabbedPane.addTab("Network Constants", createScrollPane(netPanel));

        add(tabbedPane, BorderLayout.CENTER);

        // --- Bottom Panel with Buttons and File Path ---
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 0));
        bottomPanel.setBorder(new EmptyBorder(5, 10, 5, 5));

        // Legend
        bottomPanel.add(createLegendPanel(), BorderLayout.NORTH);

        // File path field
        JLabel pathLabel = new JLabel("Configuration File: " + propertiesFile.toAbsolutePath().toString());
        pathLabel.setForeground(Color.LIGHT_GRAY);
        bottomPanel.add(pathLabel, BorderLayout.CENTER);

        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));

        JButton resetBtn = new JButton("Reset to Saved Configuration");
        resetBtn.setFont(resetBtn.getFont().deriveFont(Font.BOLD));
        resetBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.UNDO, 16, Color.BLACK));
        resetBtn.addActionListener(e -> resetToCurrent());

        JButton resetAppliedBtn = new JButton("Reset to Applied Configuration");
        resetAppliedBtn.setFont(resetAppliedBtn.getFont().deriveFont(Font.BOLD));
        resetAppliedBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.HISTORY, 16, Color.BLACK));
        resetAppliedBtn.addActionListener(e -> resetToApplied());

        JButton resetToDefaultBtn = new JButton("Reset to Default Configuration");
        resetToDefaultBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.TRASH_O, 16, Color.BLACK));
        resetToDefaultBtn.addActionListener(e -> {
            int choice = JOptionPane.showConfirmDialog(this,
                    "This will delete the content of the configuration file (" + propertiesFile.getFileName()
                            + ").\nAre you sure you want to proceed?",
                    "Confirm Deletion",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                try {
                    Files.write(propertiesFile, new byte[0]); // Deletes content
                    resetToCurrent(); // Reloads from the now-empty file and updates UI

                    Object[] options = { "Restart to Apply Changes", "Cancel" };
                    int result = JOptionPane.showOptionDialog(this,
                            "The content of " + propertiesFile.getFileName() + " has been deleted.",
                            "Success",
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.INFORMATION_MESSAGE,
                            null, options, options[0]);

                    if (result == 0 && restartAction != null) { // "Restart to Apply Changes"
                        restartAction.run();
                    }
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, "Error deleting file content: " + ex.getMessage(), "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JButton saveBtn = new JButton("Save Configuration");
        saveBtn.setFont(saveBtn.getFont().deriveFont(Font.BOLD));
        saveBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.FLOPPY_O, 16, Color.BLACK));
        saveBtn.addActionListener(e -> {
            performSave();
            Object[] options = { "Restart and Apply Changes", "Cancel" };
            int result = JOptionPane.showOptionDialog(this,
                    "Configuration saved successfully!",
                    "Success",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.INFORMATION_MESSAGE,
                    null, options, options[0]);

            if (result == 0) { // "Restart and Apply Changes"
                if (restartAction != null) {
                    restartAction.run();
                }
            }
        });

        buttonPanel.add(resetAppliedBtn);
        buttonPanel.add(resetBtn);
        buttonPanel.add(resetToDefaultBtn);
        buttonPanel.add(saveBtn);
        bottomPanel.add(buttonPanel, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void loadProfiles(JComboBox<String> comboBox) {
        try {
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("nodeConfigurationProfiles")) {
                        JsonObject profiles = settings.getAsJsonObject("nodeConfigurationProfiles");
                        for (String profileName : profiles.keySet()) {
                            comboBox.addItem(profileName);
                        }
                    }
                    if (settings.has("lastSelectedProfile")) {
                        comboBox.setSelectedItem(settings.get("lastSelectedProfile").getAsString());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveProfile(String profileName) {
        String name = (String) JOptionPane.showInputDialog(this, "Enter profile name:", "Save Profile",
                JOptionPane.PLAIN_MESSAGE, null, null, profileName);
        if (name == null || name.trim().isEmpty())
            return;

        try {
            Path settingsPath = getGuiSettingsPath();
            JsonObject settings;
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                    settings = JsonParser.parseReader(reader).getAsJsonObject();
                }
            } else {
                settings = new JsonObject();
            }

            if (!settings.has("nodeConfigurationProfiles")) {
                settings.add("nodeConfigurationProfiles", new JsonObject());
            }
            JsonObject profiles = settings.getAsJsonObject("nodeConfigurationProfiles");

            if (profiles.has(name)) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "Profile '" + name + "' already exists. Do you want to overwrite it?",
                        "Confirm Overwrite",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            JsonObject profileData = new JsonObject();
            Properties props = getPropertiesFromUI();
            for (String key : props.stringPropertyNames()) {
                profileData.addProperty(key, props.getProperty(key));
            }

            profiles.add(name, profileData);
            settings.addProperty("lastSelectedProfile", name);

            if (Files.notExists(settingsPath.getParent())) {
                Files.createDirectories(settingsPath.getParent());
            }

            try (BufferedWriter writer = Files.newBufferedWriter(settingsPath)) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                writer.write(gson.toJson(settings));
            }

            // Update combobox
            boolean exists = false;
            for (int i = 0; i < profileComboBox.getItemCount(); i++) {
                if (profileComboBox.getItemAt(i).equals(name)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                profileComboBox.addItem(name);
            }
            profileComboBox.setSelectedItem(name);

            JOptionPane.showMessageDialog(this, "Profile '" + name + "' saved successfully.", "Success",
                    JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error saving profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    private void loadProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty())
            return;
        try {
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("nodeConfigurationProfiles")) {
                        JsonObject profiles = settings.getAsJsonObject("nodeConfigurationProfiles");
                        if (profiles.has(profileName)) {
                            JsonObject profileData = profiles.getAsJsonObject(profileName);
                            Properties props = new Properties();
                            for (String key : profileData.keySet()) {
                                props.setProperty(key, profileData.get(key).getAsString());
                            }
                            updateUIFromProperties(props);

                            // Update last selected
                            settings.addProperty("lastSelectedProfile", profileName);
                            try (BufferedWriter writer = Files.newBufferedWriter(settingsPath)) {
                                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                                writer.write(gson.toJson(settings));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error loading profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    private void showProfileHelp() {
        String message = "<html><body style='width: 350px'>" +
                "<h2>Configuration Profiles</h2>" +
                "<p>Configuration Profiles allow you to save and load different sets of node configurations.</p>" +
                "<ul>" +
                "<li><b>Save Profile</b>: Saves the current settings from all tabs into a named profile. If you use an existing profile name, it will be overwritten after confirmation.</li>"
                +
                "<li><b>Load Profile</b>: Loads the settings from the selected profile in the dropdown, updating the fields in all tabs.</li>"
                +
                "</ul>" +
                "<p>Profiles are stored in the <code>gui-settings.json</code> file in your settings directory.</p>" +
                "</body></html>";

        JOptionPane.showMessageDialog(this, message, "About Configuration Profiles", JOptionPane.INFORMATION_MESSAGE);
    }

    private Path getGuiSettingsPath() {
        String settingsDir = appliedProperties.getProperty(Props.SETTINGS_DIR.getName(),
                Props.SETTINGS_DIR.getDefaultValue());
        return PathUtils.resolvePath(settingsDir).resolve("gui-settings.json");
    }

    private Properties getPropertiesFromUI() {
        Properties props = new Properties();
        for (Map.Entry<String, Supplier<String>> entry : valueSuppliers.entrySet()) {
            String key = entry.getKey();
            String newValue = entry.getValue().get();
            String defaultValue = defaultValues.get(key);

            if (newValue != null && !newValue.equals(defaultValue)) {
                props.setProperty(key, newValue);
            }
        }
        return props;
    }

    private void updateUIFromProperties(Properties props) {
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String val = props.getProperty(key);
            if (val == null) {
                val = defaultValues.get(key);
            }
            if (val == null)
                val = "";

            if (comp instanceof JCheckBox) {
                boolean isSelected = "true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val) || "1".equals(val)
                        || "on".equalsIgnoreCase(val);
                ((JCheckBox) comp).setSelected(isSelected);
            } else if (comp instanceof JComboBox) {
                ((JComboBox<?>) comp).setSelectedItem(val);
            } else if (comp instanceof javax.swing.text.JTextComponent) {
                ((javax.swing.text.JTextComponent) comp).setText(val);
            } else if (comp instanceof JScrollPane) {
                JViewport viewport = ((JScrollPane) comp).getViewport();
                Component view = viewport.getView();
                if (view instanceof JTextArea) {
                    String[] items = val.split(";");
                    StringBuilder sb = new StringBuilder();
                    for (String item : items) {
                        if (!item.trim().isEmpty()) {
                            if (sb.length() > 0)
                                sb.append("\n");
                            sb.append(item.trim());
                        }
                    }
                    ((JTextArea) view).setText(sb.toString());
                }
            }
            updateColor(comp, key, defaultValues.get(key));
        }
    }

    private String[] getSuggestions(String propName) {
        Set<String> suggestions = new LinkedHashSet<>();
        String current = currentProperties.getProperty(propName);
        if (current != null && !current.isEmpty()) {
            suggestions.add(current);
        }

        Path[] paths = {
                PathUtils.resolvePath(confFolder).resolve("node-default.properties"),
                Paths.get("conf", "node-default.properties"),
                Paths.get("..", "conf", "node-default.properties"),
                Paths.get("node-default.properties")
        };

        for (Path path : paths) {
            if (Files.exists(path)) {
                try {
                    List<String> lines = Files.readAllLines(path);
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("#")) {
                            String content = trimmed.substring(1).trim();
                            if (content.startsWith(propName)) {
                                String remainder = content.substring(propName.length()).trim();
                                if (remainder.startsWith("=")) {
                                    String val = remainder.substring(1).trim();
                                    if (!val.isEmpty()) {
                                        suggestions.add(val);
                                    }
                                }
                            }
                        }
                    }
                    break;
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        return suggestions.toArray(new String[0]);
    }

    private JPanel createCategoryPanel() {
        JPanel panel = new JPanel(new MigLayout("fillx, insets 10, gap 5", "[][grow]", ""));
        return panel;
    }

    private void finalizeCategoryPanel(JPanel panel) {
        panel.add(new JLabel(), "pushy");
    }

    private JScrollPane createScrollPane(JPanel panel) {
        JScrollPane scrollPane = new JScrollPane(panel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        return scrollPane;
    }

    private void addProperty(JPanel panel, Prop<?> prop, String labelText) {
        addProperty(panel, prop, labelText, null, false);
    }

    private void addProperty(JPanel panel, Prop<?> prop, String labelText, String[] options) {
        addProperty(panel, prop, labelText, options, false);
    }

    private void addProperty(JPanel panel, Prop<?> prop, String labelText, String[] options, boolean editable) {
        // Label
        JLabel label = new JLabel(labelText + ":");
        panel.add(label, "align label");

        // Input Component
        String currentValue = currentProperties.getProperty(prop.getName());
        if (currentValue == null) {
            currentValue = getSafeDefault(prop);
        }
        defaultValues.put(prop.getName(), getSafeDefault(prop));

        JComponent inputComponent;

        if (prop.getDefaultValue() instanceof Boolean) {
            JCheckBox checkBox = new JCheckBox();
            boolean isSelected = "true".equalsIgnoreCase(currentValue) || "yes".equalsIgnoreCase(currentValue)
                    || "1".equals(currentValue) || "on".equalsIgnoreCase(currentValue);
            checkBox.setSelected(isSelected);
            inputComponent = checkBox;
            valueSuppliers.put(prop.getName(), () -> String.valueOf(checkBox.isSelected()));
            checkBox.addActionListener(
                    e -> updateColor(checkBox, prop.getName(), getSafeDefault(prop)));
        } else if (options != null) {
            JComboBox<String> comboBox = new JComboBox<>(options);
            comboBox.setPrototypeDisplayValue("Prototype");
            comboBox.setSelectedItem(currentValue);
            comboBox.setEditable(editable);
            // If current value is not in options (e.g. custom), add it or handle gracefully
            if (comboBox.getSelectedItem() == null && currentValue != null) {
                comboBox.setEditable(true);
                comboBox.setSelectedItem(currentValue);
            }
            fixComponentSize(comboBox);
            inputComponent = comboBox;
            valueSuppliers.put(prop.getName(), () -> (String) comboBox.getSelectedItem());

            comboBox.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                        boolean isSelected, boolean cellHasFocus) {
                    Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    String current = currentProperties.getProperty(prop.getName());
                    if (current == null)
                        current = getSafeDefault(prop);

                    String applied = appliedProperties.getProperty(prop.getName());
                    if (applied == null)
                        applied = getSafeDefault(prop);

                    if (value != null && value.toString().equals(applied)) {
                        c.setForeground(new Color(0, 128, 0));
                    } else if (value != null && value.toString().equals(current)) {
                        c.setForeground(Color.YELLOW);
                    } else {
                        c.setForeground(UIManager.getColor("text"));
                    }
                    return c;
                }
            });
            comboBox.addActionListener(
                    e -> updateColor(comboBox, prop.getName(), getSafeDefault(prop)));
        } else {
            JTextField textField = new JTextField(currentValue);
            styleTextField(textField);

            // Fix dimensions to match standard text fields
            fixComponentSize(textField);

            inputComponent = textField;
            valueSuppliers.put(prop.getName(), textField::getText);
            textField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    SwingUtilities.invokeLater(
                            () -> updateColor(textField, prop.getName(), getSafeDefault(prop)));
                }

                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    SwingUtilities.invokeLater(
                            () -> updateColor(textField, prop.getName(), getSafeDefault(prop)));
                }

                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    SwingUtilities.invokeLater(
                            () -> updateColor(textField, prop.getName(), getSafeDefault(prop)));
                }
            });
        }

        updateColor(inputComponent, prop.getName(), getSafeDefault(prop));
        if (inputComponent instanceof JCheckBox) {
            panel.add(inputComponent, "split 2, height pref!");
        } else {
            panel.add(inputComponent, "split 2, growx, height pref!");
        }
        propertyComponents.put(prop.getName(), inputComponent);

        // Help Button
        JButton helpBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.QUESTION_CIRCLE, 16, Color.LIGHT_GRAY));
        helpBtn.setBorder(BorderFactory.createEmptyBorder());
        helpBtn.setContentAreaFilled(false);
        helpBtn.setFocusPainted(false);
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info");
        helpBtn.addActionListener(e -> showHelp(prop, labelText));
        panel.add(helpBtn, "wrap");
        panel.add(new JSeparator(), "span, growx, wrap, gaptop 2, gapbottom 2");
    }

    private void addPasswordProperty(JPanel panel, Prop<String> prop, String labelText) {
        // Label
        JLabel label = new JLabel(labelText + ":");
        panel.add(label, "align label");

        // Input Component
        String currentValue = currentProperties.getProperty(prop.getName());
        if (currentValue == null) {
            currentValue = getSafeDefault(prop);
        }
        defaultValues.put(prop.getName(), getSafeDefault(prop));

        JPasswordField passwordField = new JPasswordField(currentValue);
        passwordField.setColumns(20);
        styleTextField(passwordField);

        // Fix dimensions to match standard text fields (consistent with addProperty)
        fixComponentSize(passwordField);

        char defaultEchoChar = passwordField.getEchoChar();

        passwordField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                SwingUtilities.invokeLater(() -> updateColor(passwordField, prop.getName(), getSafeDefault(prop)));
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                SwingUtilities.invokeLater(() -> updateColor(passwordField, prop.getName(), getSafeDefault(prop)));
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                SwingUtilities.invokeLater(() -> updateColor(passwordField, prop.getName(), getSafeDefault(prop)));
            }
        });
        updateColor(passwordField, prop.getName(), getSafeDefault(prop));
        panel.add(passwordField, "split 2, growx, height pref!");

        // Help Button
        JButton helpBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.QUESTION_CIRCLE, 16, Color.LIGHT_GRAY));
        helpBtn.setBorder(BorderFactory.createEmptyBorder());
        helpBtn.setContentAreaFilled(false);
        helpBtn.setFocusPainted(false);
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info");
        helpBtn.addActionListener(e -> showHelp(prop, labelText));
        panel.add(helpBtn, "wrap");

        // Show/Hide Checkbox
        JCheckBox showPass = new JCheckBox("Show Password");
        showPass.addActionListener(e -> {
            passwordField.setEchoChar(showPass.isSelected() ? (char) 0 : defaultEchoChar);
        });
        panel.add(showPass, "skip 1, wrap");

        panel.add(new JSeparator(), "span, growx, wrap, gaptop 2, gapbottom 2");

        valueSuppliers.put(prop.getName(), () -> new String(passwordField.getPassword()));
        propertyComponents.put(prop.getName(), passwordField);
    }

    private void addListProperty(JPanel panel, Prop<String> prop, String labelText) {
        JLabel label = new JLabel(labelText + ":");
        panel.add(label, "align label, aligny top");

        String currentValue = currentProperties.getProperty(prop.getName());
        if (currentValue == null) {
            currentValue = getSafeDefault(prop);
        }
        defaultValues.put(prop.getName(), normalizeListValue(getSafeDefault(prop), ";"));

        // Split by semicolon and join with newlines for display
        String[] items = currentValue.split(";");
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            String p = item.trim();
            if (!p.isEmpty()) {
                if (sb.length() > 0)
                    sb.append("\n");
                sb.append(p);
            }
        }

        JTextArea textArea = new JTextArea(sb.toString());
        textArea.setRows(4);
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        textArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                SwingUtilities.invokeLater(() -> updateColor(textArea, prop.getName(), getSafeDefault(prop)));
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                SwingUtilities.invokeLater(() -> updateColor(textArea, prop.getName(), getSafeDefault(prop)));
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                SwingUtilities.invokeLater(() -> updateColor(textArea, prop.getName(), getSafeDefault(prop)));
            }
        });
        updateColor(textArea, prop.getName(), getSafeDefault(prop));

        boolean isPkCheck = Props.BRS_PK_CHECKS.getName().equals(prop.getName());
        if (isPkCheck) {
            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.setOpaque(false);
            wrapper.add(scrollPane, BorderLayout.CENTER);

            JButton convertBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.MAGIC, 16, Color.LIGHT_GRAY));
            convertBtn.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
            convertBtn.setContentAreaFilled(false);
            convertBtn.setFocusPainted(false);
            convertBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            convertBtn.setToolTipText("Analyze/Convert Public Keys");
            convertBtn.addActionListener(e -> showPkConversionDialog(textArea));

            JPanel btnContainer = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            btnContainer.setOpaque(false);
            btnContainer.add(convertBtn);
            wrapper.add(btnContainer, BorderLayout.SOUTH);

            panel.add(wrapper, "split 2, growx, hmin 80");
        } else {
            panel.add(scrollPane, "split 2, growx, hmin 80");
        }
        propertyComponents.put(prop.getName(), scrollPane);

        // Help Button
        JButton helpBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.QUESTION_CIRCLE, 16, Color.LIGHT_GRAY));
        helpBtn.setBorder(BorderFactory.createEmptyBorder());
        helpBtn.setContentAreaFilled(false);
        helpBtn.setFocusPainted(false);
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info");
        helpBtn.addActionListener(e -> showHelp(prop, labelText));
        panel.add(helpBtn, "wrap, aligny top");
        panel.add(new JSeparator(), "span, growx, wrap, gaptop 2, gapbottom 2");

        valueSuppliers.put(prop.getName(), () -> normalizeListValue(textArea.getText(), "\n"));
    }

    private void showPkConversionDialog(JTextArea textArea) {
        JPanel panel = new JPanel(new MigLayout("fill, insets 5", "[grow]", "[][][grow]"));

        // --- Header with help text ---
        String helpText = "<html><body style='width: 650px;'>"
                + "<b>PK Checks Manager</b><br>"
                + "<p>This tool helps manage the <b><code>node.pkChecks</code></b> account freeze list. This is a security feature used to prevent specific accounts from sending transactions when the <code>PK_FREEZE</code> network feature is active.</p>"
                + "<b>How to Use:</b><ul>"
                + "<li><b>Analysis:</b> The text area below analyzes the current configuration, showing the decoded Account ID and RS Address for each 16-character hex entry.</li>"
                + "<li><b>Convert & Add:</b><ol>"
                + "<li>Enter an account identifier (Numeric ID, RS Address, or a 16-char Hex ID) into the input field.</li>"
                + "<li>Click 'Convert'. The tool will generate the correct 16-character Little Endian hexadecimal string required for the configuration.</li>"
                + "<li>A dialog will then prompt you to add the generated hex string to the main configuration list.</li>"
                + "</ol></li></ul>"
                + "</body></html>";
        JLabel helpLabel = new JLabel(helpText);
        helpLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
        panel.add(helpLabel, "wrap, growx, gaptop 5");

        // Input Panel
        JPanel inputPanel = new JPanel(new MigLayout("insets 0, fillx", "[][grow][]", "[]"));

        JTextField inputField = new JTextField();
        inputField.setToolTipText("Enter Account ID (Numeric), RS Address, or 16-char Hex");
        JButton convertButton = new JButton("Convert");

        inputPanel.add(new JLabel("Account:"), "gapright 5");
        inputPanel.add(inputField, "growx");
        inputPanel.add(convertButton, "gapleft 5");
        panel.add(inputPanel, "wrap, growx");

        // Result Area
        JTextArea resultArea = new JTextArea();
        resultArea.setEditable(false);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(resultArea);

        panel.add(scroll, "grow");

        // Analyze current config
        StringBuilder analysis = new StringBuilder("--- Current Configuration Analysis ---\n");
        String content = textArea.getText();
        String[] lines = content.split("\n");
        Pattern pkPattern = Pattern.compile("[0-9a-fA-F]{16}");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty())
                continue;

            Matcher m = pkPattern.matcher(line);
            if (m.find()) {
                String hexId = m.group();
                try {
                    long accountId = getAccountIdFromHex(hexId);
                    String rs = Crypto.rsEncode(accountId);

                    analysis.append(
                            String.format("Hex: %s -> ID: %s (%s)\n", hexId, Convert.toUnsignedLong(accountId), rs));
                } catch (Exception e) {
                    analysis.append("Error processing line: ").append(line).append("\n");
                }
            } else {
                analysis.append("Ignored: ").append(line).append("\n");
            }
        }
        resultArea.setText(analysis.toString());

        // Logic
        convertButton.addActionListener(e -> {
            String input = inputField.getText().trim();
            if (input.isEmpty())
                return;

            try {
                long accountId;
                String hex;
                String type;

                if (input.matches("[0-9a-fA-F]{16}")) {
                    type = "Hex";
                    hex = input;
                    accountId = getAccountIdFromHex(hex);
                } else {
                    try {
                        // Try RS
                        accountId = Crypto.rsDecode(input);
                        type = "RS Address";
                    } catch (Exception ex) {
                        // Try Numeric
                        try {
                            accountId = Convert.parseUnsignedLong(input);
                            type = "Numeric ID";
                        } catch (Exception ex2) {
                            throw new IllegalArgumentException("Invalid Account ID or RS Address");
                        }
                    }
                    hex = getHexFromAccountId(accountId);
                }

                String numeric = Convert.toUnsignedLong(accountId);
                String rs = Crypto.rsEncode(accountId);

                String resultMsg = String.format(
                        "Conversion Result (%s):\n" +
                                "--------------------------------------------------\n" +
                                "Hex (for config): %s\n" +
                                "Account ID:       %s\n" +
                                "RS Address:       %s\n" +
                                "--------------------------------------------------\n\n",
                        type, hex, numeric, rs);

                resultArea.insert(resultMsg, 0);
                resultArea.setCaretPosition(0);

                int choice = JOptionPane.showConfirmDialog(panel,
                        "Conversion successful!\n\nHex: " + hex + "\nAccount: " + rs
                                + "\n\nAdd this to the configuration list?",
                        "Add to Configuration", JOptionPane.YES_NO_OPTION);

                if (choice == JOptionPane.YES_OPTION) {
                    String currentText = textArea.getText();
                    if (!currentText.isEmpty() && !currentText.endsWith("\n")) {
                        textArea.append("\n");
                    }
                    textArea.append(hex);
                }

            } catch (Exception ex) {
                JOptionPane.showMessageDialog(panel, "Could not parse input: " + ex.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        JOptionPane.showMessageDialog(this, panel, "PK Checks Manager", JOptionPane.PLAIN_MESSAGE);
    }

    private long getAccountIdFromHex(String hexId) {
        byte[] bytes = Convert.parseHexString(hexId);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return buffer.getLong();
    }

    private String getHexFromAccountId(long accountId) {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(accountId);
        return Convert.toHexString(buffer.array());
    }

    private void styleTextField(JComponent field) {
        field.setFont(UIManager.getFont("TextField.font"));
        field.setBorder(BorderFactory.createCompoundBorder(
                UIManager.getBorder("TextField.border"),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
    }

    private void fixComponentSize(JComponent comp) {
        JTextField dummy = new JTextField("Prototype");
        styleTextField(dummy);
        Dimension pref = dummy.getPreferredSize();
        comp.setPreferredSize(new Dimension(comp.getPreferredSize().width, pref.height));
        comp.setMinimumSize(new Dimension(comp.getMinimumSize().width, pref.height));
    }

    private String getSafeDefault(Prop<?> prop) {
        Object def = prop.getDefaultValue();
        return def == null ? "" : String.valueOf(def);
    }

    private void updateColor(JComponent comp, String propName, String defaultValue) {
        if (comp instanceof JScrollPane) {
            JViewport viewport = ((JScrollPane) comp).getViewport();
            Component view = viewport.getView();
            if (view instanceof JTextArea) {
                comp = (JComponent) view;
            }
        }

        String current = currentProperties.getProperty(propName);
        if (current == null)
            current = defaultValue;

        String applied = appliedProperties.getProperty(propName);
        if (applied == null)
            applied = defaultValue;

        String value = "";
        JComponent target = comp;

        if (comp instanceof JCheckBox) {
            value = String.valueOf(((JCheckBox) comp).isSelected());
            boolean curBool = "true".equalsIgnoreCase(current) || "yes".equalsIgnoreCase(current) || "1".equals(current)
                    || "on".equalsIgnoreCase(current); // NOSONAR
            boolean appliedBool = "true".equalsIgnoreCase(applied) || "yes".equalsIgnoreCase(applied)
                    || "1".equals(applied) || "on".equalsIgnoreCase(applied); // NOSONAR
            boolean valBool = Boolean.parseBoolean(value);

            if (valBool == appliedBool)
                target.setForeground(new Color(0, 128, 0));
            else if (valBool == curBool)
                target.setForeground(Color.YELLOW);
            else {
                target.setForeground(UIManager.getColor("text"));
            }
            return;
        } else if (comp instanceof JComboBox) {
            Object item = ((JComboBox<?>) comp).getSelectedItem();
            value = item == null ? "" : item.toString();
            if (((JComboBox<?>) comp).isEditable()) {
                target = (JComponent) ((JComboBox<?>) comp).getEditor().getEditorComponent();
            }
        } else if (comp instanceof javax.swing.text.JTextComponent) {
            value = ((javax.swing.text.JTextComponent) comp).getText();
            if (comp instanceof JTextArea) {
                value = normalizeListValue(value, "\n");
                current = normalizeListValue(current, ";");
                applied = normalizeListValue(applied, ";");
            } else {
                value = value.trim();
                current = current.trim();
                applied = applied.trim();
            }
        }

        Color color;
        if (value.equals(applied)) {
            color = new Color(0, 128, 0);
        } else if (value.equals(current)) {
            color = Color.YELLOW;
        } else {
            color = UIManager.getColor("text");
        }

        if (target instanceof JTextPane) {
            JTextPane pane = (JTextPane) target;
            SimpleAttributeSet attrs = new SimpleAttributeSet();
            StyleConstants.setForeground(attrs, color);
            pane.getStyledDocument().setCharacterAttributes(0, pane.getText().length(), attrs, true);
        } else {
            target.setForeground(color);
        }
    }

    private JPanel createLegendPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        panel.setBorder(new EmptyBorder(0, 0, 5, 0));

        Color defaultColor = UIManager.getColor("text");
        if (defaultColor == null)
            defaultColor = Color.BLACK;

        panel.add(createLegendItem(defaultColor, "Unsaved values"));
        panel.add(createLegendItem(Color.YELLOW, "Saved values"));
        panel.add(createLegendItem(new Color(0, 128, 0), "Applied values"));

        return panel;
    }

    private JPanel createLegendItem(Color color, String text) {
        JPanel item = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JLabel colorBox = new JLabel("\u25A0");
        colorBox.setForeground(color);
        item.add(colorBox);
        item.add(new JLabel(text));
        return item;
    }

    private void resetToCurrent() {
        try (FileInputStream in = new FileInputStream(propertiesFile.toFile())) {
            currentProperties.clear();
            currentProperties.load(in);
        } catch (Exception e) {
            e.printStackTrace();
        }

        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String val = currentProperties.getProperty(key);
            if (val == null) {
                val = defaultValues.get(key);
            }
            if (val == null)
                continue;

            if (comp instanceof JCheckBox) {
                boolean isSelected = "true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val) || "1".equals(val)
                        || "on".equalsIgnoreCase(val);
                ((JCheckBox) comp).setSelected(isSelected);
            } else if (comp instanceof JComboBox) {
                ((JComboBox<?>) comp).setSelectedItem(val);
            } else if (comp instanceof javax.swing.text.JTextComponent) {
                ((javax.swing.text.JTextComponent) comp).setText(val);
            } else if (comp instanceof JScrollPane) {
                JViewport viewport = ((JScrollPane) comp).getViewport();
                Component view = viewport.getView();
                if (view instanceof JTextArea) {
                    String[] items = val.split(";");
                    StringBuilder sb = new StringBuilder();
                    for (String item : items) {
                        if (!item.trim().isEmpty()) {
                            if (sb.length() > 0)
                                sb.append("\n");
                            sb.append(item.trim());
                        }
                    }
                    ((JTextArea) view).setText(sb.toString());
                }
            }
        }
    }

    private void resetToApplied() {
        currentProperties.clear();
        currentProperties.putAll(appliedProperties);

        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String val = currentProperties.getProperty(key);
            if (val == null) {
                val = defaultValues.get(key);
            }
            if (val == null)
                continue;

            if (comp instanceof JCheckBox) {
                boolean isSelected = "true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val) || "1".equals(val)
                        || "on".equalsIgnoreCase(val);
                ((JCheckBox) comp).setSelected(isSelected);
            } else if (comp instanceof JComboBox) {
                ((JComboBox<?>) comp).setSelectedItem(val);
            } else if (comp instanceof javax.swing.text.JTextComponent) {
                ((javax.swing.text.JTextComponent) comp).setText(val);
            } else if (comp instanceof JScrollPane) {
                JViewport viewport = ((JScrollPane) comp).getViewport();
                Component view = viewport.getView();
                if (view instanceof JTextArea) {
                    String[] items = val.split(";");
                    StringBuilder sb = new StringBuilder();
                    for (String item : items) {
                        if (!item.trim().isEmpty()) {
                            if (sb.length() > 0)
                                sb.append("\n");
                            sb.append(item.trim());
                        }
                    }
                    ((JTextArea) view).setText(sb.toString());
                }
            }
        }
    }

    private void showHelp(Prop<?> prop, String labelText) {
        String description = helpTexts.getOrDefault(prop.getName(), "No detailed description available.");
        String message = "<html><body style='width: 300px'>" +
                "<h2>" + labelText + "</h2>" +
                "<p><b>Property Key:</b> <code>" + prop.getName() + "</code></p>" +
                "<p><b>Default Value:</b> " + prop.getDefaultValue() + "</p>" +
                "<hr>" +
                "<p>" + description.replace("\n", "<br>") + "</p>" +
                "</body></html>";

        JOptionPane.showMessageDialog(this, message, "Property Information", JOptionPane.INFORMATION_MESSAGE);
    }

    private void performSave() {
        for (Map.Entry<String, Supplier<String>> entry : valueSuppliers.entrySet()) {
            String key = entry.getKey();
            String newValue = entry.getValue().get();
            String defaultValue = defaultValues.get(key);

            if (newValue != null && !newValue.equals(defaultValue)) {
                currentProperties.setProperty(key, newValue);
            } else {
                currentProperties.remove(key);
            }
        }
        try {
            savePropertiesPreservingFormat(propertiesFile, currentProperties, valueSuppliers.keySet());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error saving properties: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }

        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            updateColor(entry.getValue(), entry.getKey(), defaultValues.get(entry.getKey()));
        }
    }

    private void savePropertiesPreservingFormat(Path file, Properties props, Set<String> managedKeys)
            throws IOException {
        List<String> lines = Files.exists(file) ? Files.readAllLines(file) : new ArrayList<>();
        List<String> newLines = new ArrayList<>();
        Set<String> processedKeys = new HashSet<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                newLines.add(line);
                continue;
            }

            int sepIdx = -1;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                    sepIdx = i;
                    break;
                }
            }

            if (sepIdx != -1) {
                String key = line.substring(0, sepIdx).trim();
                if (props.containsKey(key)) {
                    String val = props.getProperty(key);
                    newLines.add(key + "=" + escapePropertyValue(val));
                    processedKeys.add(key);
                } else {
                    if (!managedKeys.contains(key)) {
                        newLines.add(line);
                    }
                }
            } else {
                newLines.add(line);
            }
        }

        for (String key : props.stringPropertyNames()) {
            if (!processedKeys.contains(key)) {
                newLines.add(key + "=" + escapePropertyValue(props.getProperty(key)));
            }
        }

        Files.write(file, newLines);
    }

    private String normalizeListValue(String value, String delimiter) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Arrays.stream(value.split(delimiter))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.joining(";"));
    }

    private String escapePropertyValue(String value) {
        if (value == null)
            return "";
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\f", "\\f");
    }

    private void ensurePropertiesFileExists() {
        if (!Files.exists(propertiesFile)) {
            try {
                Files.createDirectories(propertiesFile.getParent());
                Files.createFile(propertiesFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void initHelpTexts() {
        // API
        helpTexts.put(Props.API_SERVER.getName(),
                "Enables the HTTP API server. Required for wallets and local tools to communicate with the node.");

        helpTexts.put(Props.API_PORT.getName(),
                "The TCP port number on which the API server listens for incoming HTTP/HTTPS requests."
                        + "<br>Ensure this port is not blocked by firewalls if you intend to access the node from other devices.");

        helpTexts.put(Props.API_LISTEN.getName(),
                "The interface IP or hostname to bind to."
                        + "<br><br><b>Examples:</b>"
                        + "<ul>"
                        + "<li><code>0.0.0.0</code>: Listen on all IPv4 interfaces.</li>"
                        + "<li><code>::</code>: Listen on all IPv6 interfaces.</li>"
                        + "<li><code>127.0.0.1</code>: Listen on local IPv4 loopback only.</li>"
                        + "<li><code>::1</code>: Listen on local IPv6 loopback only.</li>"
                        + "<li><code>localhost</code>: Listen on local loopback (resolves to IP).</li>"
                        + "</ul>");

        helpTexts.put(Props.API_ALLOWED.getName(),
                "List of allowed IP addresses, hostnames, or subnets to access the API."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (<code>;</code>)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the <code>node.properties</code> file."
                        + "<br><br><b>Examples:</b>"
                        + "<ul>"
                        + "<li><code>*</code>: Allows all IP addresses. <b>Warning:</b> Use with caution on public nodes.</li>"
                        + "<li><code>127.0.0.1</code>: A specific IPv4 address.</li>"
                        + "<li><code>localhost</code>: The local machine.</li>"
                        + "<li><code>[::1]</code>: The IPv6 loopback address.</li>"
                        + "<li><code>192.168.1.0/24</code>: An IPv4 subnet in CIDR notation.</li>"
                        + "</ul>");

        helpTexts.put(Props.API_ADMIN_KEY_LIST.getName(),
                "List of passwords (API Keys) required to authorize administrative API operations."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (<code>;</code>)."
                        + "<br>These keys are required for the following sensitive actions:"
                        + "<ul>"
                        + "<li><b>Full Reset</b>: Resets the node and forces a resync.</li>"
                        + "<li><b>Backup Database</b>: Dumps the database to a file.</li>"
                        + "<li><b>Clear Unconfirmed Transactions</b>: Prunes unconfirmed transactions.</li>"
                        + "<li><b>Pop Off</b>: Removes recent blocks from the database.</li>"
                        + "<li><b>Get State (Extended)</b>: Retrieves extended blockchain info (with counts).</li>"
                        + "</ul>"
                        + "<b>Security Note:</b> If this field is left empty, administrative API functions are completely disabled.");

        helpTexts.put(Props.API_SSL.getName(),
                "Enables SSL (HTTPS) for the API server."
                        + "<br><br><b>To enable SSL, you have two options:</b>"
                        + "<br><br><b>1. Manual Keystore (e.g., JKS or PKCS12):</b>"
                        + "<ul>"
                        + "<li>Set this property to <code>true</code>.</li>"
                        + "<li>Set <code>SSL KeyStore Path</code> to the path of your keystore file.</li>"
                        + "<li>Set <code>SSL KeyStore Password</code> to the password for your keystore.</li>"
                        + "</ul>"
                        + "<b>2. Automatic Conversion from Let's Encrypt (PEM):</b>"
                        + "<ul>"
                        + "<li>Set this property to <code>true</code>.</li>"
                        + "<li>Set <code>SSL LetsEncrypt Path</code> to the directory containing your <code>privkey.pem</code> and <code>fullchain.pem</code> files (e.g., <code>/etc/letsencrypt/live/your.domain.com</code>).</li>"
                        + "<li>Set <code>SSL KeyStore Path</code> to the desired location for the auto-generated PKCS12 keystore file (e.g., <code>conf/keystore.p12</code>).</li>"
                        + "<li>Set <code>SSL KeyStore Password</code> to a password for the new keystore. The node will create and manage this file.</li>"
                        + "</ul>"
                        + "After enabling, the API will be accessible via <code>https://</code> on the configured API port.");

        helpTexts.put(Props.API_DOC_MODE.getName(),
                "Controls the built-in API documentation."
                        + "<br><br><b>Available Modes:</b>"
                        + "<ul>"
                        + "<li><code>modern</code>: Enables the new, interactive Swagger UI documentation. Recommended for better experience.</li>"
                        + "<li><code>legacy</code>: Enables the old, servlet-based documentation. Use this if you have compatibility issues with the modern UI.</li>"
                        + "<li><code>off</code>: Disables the API documentation completely.</li>"
                        + "</ul>");

        helpTexts.put(Props.API_UI_DIR.getName(),
                "Specifies the local directory containing the static web files (HTML, JS, CSS) for the node's user interface (e.g., Phoenix Wallet)."
                        + "<br>These files are served at the root URL (e.g. <code>http://localhost:8125/</code>)."
                        + "<br>If left empty, no UI will be served from the root path.");

        helpTexts.put(Props.API_WEBSOCKET_ENABLE.getName(),
                "Enables the WebSocket server for real-time event notifications."
                        + "<br><br><b>Why enable it?</b>"
                        + "<br>WebSockets allow applications (wallets, explorers) to receive immediate updates without inefficient polling."
                        + "<br><br><b>If Enabled:</b>"
                        + "<br>The node pushes events (e.g., new blocks, transactions, balance changes) to connected clients instantly."
                        + "<br><br><b>If Disabled:</b>"
                        + "<br>Applications must repeatedly request data (poll) to detect changes, resulting in higher latency and load.");

        helpTexts.put(Props.API_WEBSOCKET_PORT.getName(),
                "The TCP port dedicated to the WebSocket server."
                        + "<br>This port is separate from the main HTTP API port and is used for real-time event streams."
                        + "<br><br><b>Details:</b>"
                        + "<ul>"
                        + "<li><b>Function:</b> Clients connect here to receive push notifications (blocks, transactions).</li>"
                        + "<li><b>Single Port:</b> Only one port can be configured.</li>"
                        + "<li><b>Firewall:</b> Must be open/forwarded for external access.</li>"
                        + "</ul>");

        helpTexts.put(Props.API_WEBSOCKET_HEARTBEAT_INTERVAL.getName(),
                "The interval in seconds for WebSocket heartbeat messages."
                        + "<br>This setting controls how often the server sends a 'ping' or heartbeat message to connected clients."
                        + "<br><br><b>Details:</b>"
                        + "<ul>"
                        + "<li><b>Unit:</b> Seconds.</li>"
                        + "<li><b>Purpose:</b> Keeps the connection alive through proxies, load balancers, and firewalls that might drop idle connections.</li>"
                        + "<li><b>Role:</b> Ensures both server and client know the connection is still active.</li>"
                        + "</ul>");

        helpTexts.put(Props.API_ALLOWED_ORIGINS.getName(),
                "Configures Cross-Origin Resource Sharing (CORS) allowed origins."
                        + "<br>This setting determines which websites are allowed to access the node's API from a browser."
                        + "<br><br><b>Details:</b>"
                        + "<ul>"
                        + "<li><b>Purpose:</b> Allows web applications (like wallets) hosted on other domains to interact with this node.</li>"
                        + "<li><b>Role:</b> Acts as a security filter for browser-based API requests.</li>"
                        + "</ul>"
                        + "<b>Configuration Options:</b>"
                        + "<ul>"
                        + "<li><code>*</code>: <b>Wildcard</b>. Allows access from <b>any</b> website. Recommended for public nodes.</li>"
                        + "<li><b>Specific Origin</b>: e.g., <code>https://wallet.signum.network</code>. Restricts access to a specific domain.</li>"
                        + "<li><b>List</b>: Multiple origins can be specified. In this field, you can list entries on new lines, or on a single line separated by semicolons (<code>;</code>).</li>"
                        + "</ul>"
                        + "The configuration is stored as a single semicolon-separated list in the <code>node.properties</code> file.");

        helpTexts.put(Props.API_ACCEPT_SURPLUS_PARAMS.getName(),
                "Controls how the API server handles requests containing unexpected or surplus parameters."
                        + "<br><br><b>If <code>false</code> (default and recommended):</b>"
                        + "<br>The server strictly validates all parameters. If a request includes a parameter not defined for that API call, the request is rejected with an 'incorrect parameter' error. This is the most secure setting."
                        + "<br><br><b>If <code>true</code>:</b>"
                        + "<br>The server will ignore any unknown parameters and process the request using only the recognized ones."
                        + "<br>This may be required for compatibility with older or poorly-written clients that send extra data."
                        + "<br><br><b>Security Note:</b> It is recommended to keep this disabled (<code>false</code>) unless explicitly needed.");

        helpTexts.put(Props.API_SERVER_ENFORCE_POST.getName(),
                "Controls whether the API server enforces the use of HTTP POST method for sensitive or state-changing requests."
                        + "<br><br><b>Why is this important?</b>"
                        + "<ul>"
                        + "<li><b>Security:</b> GET requests include parameters in the URL, which are often logged in server/proxy logs and browser history. This risks exposing sensitive data (like secret phrases). POST requests send data in the request body, which is not logged by default.</li>"
                        + "<li><b>Best Practice:</b> HTTP standards dictate that GET should be used for retrieving data, while POST should be used for actions that modify state (e.g., sending transactions).</li>"
                        + "</ul>"
                        + "<b>Configuration:</b>"
                        + "<ul>"
                        + "<li><code>true</code> (Recommended): Enforces POST for state-changing API calls. GET requests for these calls will be rejected.</li>"
                        + "<li><code>false</code>: Allows both GET and POST. <b>Warning:</b> Less secure. Use only for testing or legacy compatibility.</li>"
                        + "</ul>");

        helpTexts.put(Props.API_SERVER_IDLE_TIMEOUT.getName(),
                "The maximum time in milliseconds that an HTTP API connection can remain idle before the server closes it."
                        + "<br><br><b>Details:</b>"
                        + "<ul>"
                        + "<li><b>Unit:</b> Milliseconds.</li>"
                        + "<li><b>Purpose:</b> Frees up server resources by closing inactive connections.</li>"
                        + "<li><b>Impact:</b> Higher values allow for slower clients or long-polling, but consume more resources. Lower values save resources but might disconnect slow clients.</li>"
                        + "</ul>");

        helpTexts.put(Props.API_SSL_KEY_STORE_PATH.getName(),
                "The path to your SSL keystore file (e.g., a .jks or .p12 file)."
                        + "<br>Required if SSL is enabled.");

        helpTexts.put(Props.API_SSL_KEY_STORE_PASSWORD.getName(),
                "The password for your SSL keystore file."
                        + "<br>Required if SSL is enabled.");

        helpTexts.put(Props.API_SSL_LETSENCRYPT_PATH.getName(),
                "The path to your Let's Encrypt live directory (e.g., /etc/letsencrypt/live/your.domain.com)."
                        + "<br>If set, the node will automatically convert the PEM files to a PKCS12 keystore.");

        // Database
        helpTexts.put(Props.DB_URL.getName(),
                "The JDBC connection URL. Examples:"
                        + "<br>SQLite: <code>jdbc:sqlite:file:./db/signum.sqlite.db</code>"
                        + "<br>MariaDB: <code>jdbc:mariadb://localhost:3306/signum</code>"
                        + "<br>Postgres: <code>jdbc:postgresql://localhost:5432/signum</code>");

        helpTexts.put(Props.DB_USERNAME.getName(),
                "The username for the database connection. Required for MariaDB and PostgreSQL.");

        helpTexts.put(Props.DB_PASSWORD.getName(),
                "The password for the database connection. Required for MariaDB and PostgreSQL.");

        helpTexts.put(Props.DB_SKIP_CHECK.getName(),
                "If enabled, skips the database integrity check on startup."
                        + "<br><b>Warning:</b> This can speed up startup but is risky. Use only if you are sure the database is consistent.");

        helpTexts.put(Props.DB_INSERT_BATCH_MAX_SIZE.getName(),
                "The maximum number of rows to insert in a single database batch operation."
                        + "<br>A larger batch size can improve performance during sync but may use more memory.");

        helpTexts.put(Props.DB_CONNECTIONS.getName(),
                "The maximum number of simultaneous connections in the database connection pool.");

        helpTexts.put(Props.DB_TRIM_DERIVED_TABLES.getName(),
                "If enabled, the node will periodically prune old data from derived tables to save disk space. Recommended for non-archival nodes.");

        helpTexts.put(Props.DB_OPTIMIZE.getName(),
                "If enabled, the node performs database optimization (e.g., VACUUM for SQLite) during startup or shutdown."
                        + "<br>This helps reduce file size and improve performance but may increase startup/shutdown time.");

        helpTexts.put(Props.DB_SQLITE_JOURNAL_MODE.getName(),
                "SQLite journaling mode. WAL (Write-Ahead Logging) is recommended for performance and concurrency.");

        helpTexts.put(Props.DB_SQLITE_SYNCHRONOUS.getName(),
                "Controls the SQLite synchronization mode."
                        + "<ul>"
                        + "<li><b>NORMAL</b>: Good balance between safety and performance.</li>"
                        + "<li><b>FULL</b>: Safest but slower.</li>"
                        + "<li><b>OFF</b>: Fastest but risky (data corruption on power loss).</li>"
                        + "</ul>");

        helpTexts.put(Props.DB_SQLITE_CACHE_SIZE.getName(),
                "Memory allocated for SQLite cache."
                        + "<br><br><b>Positive Value (N):</b> Sets the number of pages."
                        + "<br>Total Cache Size = N * Page Size (default 4096 bytes)."
                        + "<br><i>Example:</i> <code>32768</code> pages * 4KB = ~128 MB."
                        + "<br><br><b>Negative Value (-N):</b> Sets the memory usage in KiB."
                        + "<br>Total Cache Size = abs(N) * 1024 bytes."
                        + "<br><i>Example:</i> <code>-131072</code> (KiB) = 128 MB."
                        + "<br><br><b>Recommendation:</b> Use negative values for a definitive RAM limit.");

        helpTexts.put(Props.BRS_BLOCK_CACHE_MB.getName(),
                "The size of the in-memory cache for blocks in Megabytes."
                        + "<br>Speeds up block retrieval during syncing and API requests.");

        // P2P
        helpTexts.put(Props.P2P_PORT.getName(),
                "The TCP port used for peer-to-peer communication."
                        + "<br>This port must be open and forwarded to allow other peers to connect to your node.");

        helpTexts.put(Props.P2P_LISTEN.getName(),
                "The interface IP or hostname for P2P communication."
                        + "<br><br><b>Examples:</b>"
                        + "<ul>"
                        + "<li><code>0.0.0.0</code>: Listen on all IPv4 interfaces.</li>"
                        + "<li><code>::</code>: Listen on all IPv6 interfaces.</li>"
                        + "<li><code>127.0.0.1</code>: Listen on local IPv4 loopback only.</li>"
                        + "<li><code>::1</code>: Listen on local IPv6 loopback only.</li>"
                        + "<li><code>localhost</code>: Listen on local loopback (resolves to IP).</li>"
                        + "</ul>");

        helpTexts.put(Props.P2P_UPNP.getName(),
                "Attempts to automatically configure port forwarding on your router using UPnP (Universal Plug and Play)."
                        + "<br>Recommended for home users behind a NAT router to make the node reachable from the internet."
                        + "<br>Should be disabled on servers or when port forwarding is configured manually.");

        helpTexts.put(Props.P2P_MY_PLATFORM.getName(),
                "A string identifying your node's platform to peers."
                        + "<br>Useful for network statistics."
                        + "<br>You can enter your Signum address here to be eligible for SNR (Signum Network Reward) awards.");

        helpTexts.put(Props.P2P_MY_ADDRESS.getName(),
                "The externally visible IP address or hostname of this node."
                        + "<br>This is the address that will be announced to other peers.");

        helpTexts.put(Props.P2P_SHARE_MY_ADDRESS.getName(),
                "Whether to announce this node's address to other peers."
                        + "<br>If disabled, your node will not be discoverable by others.");

        helpTexts.put(Props.P2P_BOOTSTRAP_PEERS.getName(),
                "A list of initial peers to connect to when the node starts."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (<code>;</code>)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the <code>node.properties</code> file."
                        + "<br>This helps the node to quickly find other peers and join the network.");

        helpTexts.put(Props.P2P_REBROADCAST_TO.getName(),
                "A list of peers to which this node will always rebroadcast transactions."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (<code>;</code>)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the <code>node.properties</code> file."
                        + "<br>Useful for ensuring transactions reach specific nodes (e.g., pools or exchanges).");

        helpTexts.put(Props.P2P_NUM_BOOTSTRAP_CONNECTIONS.getName(),
                "The number of bootstrap peers to connect to when the node starts."
                        + "<br>Increasing this may help with initial connectivity but increases startup load.");

        helpTexts.put(Props.P2P_BLACKLISTED_PEERS.getName(),
                "A list of peer addresses that are permanently banned from connecting to your node."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (<code>;</code>)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the <code>node.properties</code> file.");

        helpTexts.put(Props.P2P_MAX_CONNECTIONS.getName(),
                "The maximum number of active peer connections the node will maintain."
                        + "<br>Higher values allow more connectivity but consume more resources.");

        helpTexts.put(Props.P2P_MAX_BLOCKS.getName(),
                "The maximum number of blocks to send to a peer in a single request."
                        + "<br>Controls the bandwidth usage for block synchronization.");

        helpTexts.put(Props.P2P_TIMEOUT_CONNECT_MS.getName(),
                "The timeout in milliseconds for establishing a connection to a peer.");

        helpTexts.put(Props.P2P_TIMEOUT_READ_MS.getName(),
                "The timeout in milliseconds for reading data from a peer.");

        helpTexts.put(Props.P2P_TIMEOUT_IDLE_MS.getName(),
                "The timeout in milliseconds after which an idle peer connection is closed.");

        helpTexts.put(Props.P2P_BLACKLISTING_TIME_MS.getName(),
                "The duration in milliseconds for which a peer is blacklisted after misbehavior.");

        helpTexts.put(Props.P2P_ENABLE_TX_REBROADCAST.getName(),
                "Enables the rebroadcasting of new transactions to other peers."
                        + "<br>This helps propagate transactions across the network.");

        helpTexts.put(Props.P2P_USE_PEERS_DB.getName(),
                "Whether to use the database to store and retrieve known peers."
                        + "<br>If disabled, the node will only use bootstrap peers.");

        helpTexts.put(Props.P2P_SAVE_PEERS.getName(),
                "Whether to save discovered peers to the database for future use.");

        helpTexts.put(Props.P2P_GET_MORE_PEERS.getName(),
                "Whether to request lists of known peers from connected peers."
                        + "<br>This helps discover new nodes in the network.");

        helpTexts.put(Props.P2P_GET_MORE_PEERS_THRESHOLD.getName(),
                "The threshold of known peers below which the node will actively request more peers from others.");

        helpTexts.put(Props.P2P_SEND_TO_LIMIT.getName(),
                "The maximum number of peers to which a single transaction will be broadcasted.");

        helpTexts.put(Props.P2P_MAX_UNCONFIRMED_TRANSACTIONS.getName(),
                "The maximum number of unconfirmed transactions to keep in memory."
                        + "<br>Prevents memory exhaustion during high network activity.");

        helpTexts.put(Props.P2P_MAX_PERCENTAGE_UNCONFIRMED_TRANSACTIONS_FULL_HASH_REFERENCE.getName(),
                "The maximum percentage of unconfirmed transactions in memory that can reference another unconfirmed transaction by its full hash."
                        + "<br>This is a memory management setting to prevent complex chains of unconfirmed transactions from consuming too much memory.");

        helpTexts.put(Props.P2P_MAX_UNCONFIRMED_TRANSACTIONS_RAW_SIZE_BYTES_TO_SEND.getName(),
                "The maximum total size (in bytes) of raw unconfirmed transaction data to send to a peer in a single batch."
                        + "<br>This limit helps to prevent network flooding and manage bandwidth usage.");

        // Mining
        helpTexts.put(Props.GPU_ACCELERATION.getName(),
                "Enables GPU acceleration for mining verification."
                        + "<br>This can significantly improve performance when verifying nonces.");

        helpTexts.put(Props.GPU_AUTODETECT.getName(),
                "Automatically detects available GPU devices for acceleration."
                        + "<br>If disabled, you must manually specify the platform and device indices.");

        helpTexts.put(Props.GPU_PLATFORM_IDX.getName(),
                "The index of the OpenCL platform to use for GPU acceleration."
                        + "<br>Only used if auto-detection is disabled.");

        helpTexts.put(Props.GPU_DEVICE_IDX.getName(),
                "The index of the OpenCL device to use for GPU acceleration."
                        + "<br>Only used if auto-detection is disabled.");

        helpTexts.put(Props.GPU_MEM_PERCENT.getName(),
                "The percentage of GPU memory to allocate for mining verification.");

        helpTexts.put(Props.GPU_UNVERIFIED_QUEUE.getName(),
                "The size of the queue for unverified transactions/blocks waiting for GPU processing.");

        helpTexts.put(Props.GPU_DYNAMIC_HASHES_PER_BATCH.getName(),
                "Dynamically adjusts the number of hashes processed per GPU batch based on load.");

        helpTexts.put(Props.GPU_HASHES_PER_BATCH.getName(),
                "The fixed number of hashes to process in a single GPU batch."
                        + "<br>Only used if dynamic adjustment is disabled.");

        helpTexts.put(Props.SOLO_MINING_PASSPHRASES.getName(),
                "A list of secret phrases for accounts that are solo mining on this node."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (<code>;</code>)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the <code>node.properties</code> file."
                        + "<br>This allows miners to use the 'submitNonce' API without sending their secret phrase over the network."
                        + "<br><b>Security Warning:</b> Do not use on public-facing nodes or nodes accessible by others, as it stores secret phrases in the configuration file.");

        helpTexts.put(Props.REWARD_RECIPIENT_PASSPHRASES.getName(),
                "A list of passphrases for reward recipient accounts, used in pool mining."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (<code>;</code>)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the <code>node.properties</code> file."
                        + "<br>Format: <code>miner_account_id:reward_recipient_secret_phrase</code>"
                        + "<br>This allows the node to automatically claim mining rewards on behalf of the pool miners.");

        helpTexts.put(Props.ALLOW_OTHER_SOLO_MINERS.getName(),
                "Allows other accounts (not listed in Solo Mining Passphrases) to solo mine using this node."
                        + "<br>If enabled, anyone can submit nonces to this node.");

        // System
        helpTexts.put(Props.APPLICATION.getName(),
                "The name of the application (e.g. BRS). Used for peer identification.");
        helpTexts.put(Props.VERSION.getName(),
                "The version of the node software. Used for peer identification and protocol compatibility.");

        helpTexts.put(Props.NETWORK_NAME.getName(),
                "The name of the network this node is connected to (e.g., Signum, Testnet).");

        helpTexts.put(Props.CPU_NUM_CORES.getName(),
                "The number of CPU cores to use for processing."
                        + "<br>Set to 0 or negative to use all available cores.");

        helpTexts.put(Props.BLOCK_PROCESS_THREAD_DELAY.getName(),
                "The delay in milliseconds between block processing threads."
                        + "<br>Can be adjusted to manage CPU usage.");

        helpTexts.put(Props.MAX_INDIRECTS_PER_BLOCK.getName(),
                "The maximum number of indirect payments (e.g., from multi-out transactions) allowed per block.");

        helpTexts.put(Props.EXPERIMENTAL.getName(),
                "Enables experimental features that are not yet stable."
                        + "<br>Use with caution.");

        helpTexts.put(Props.MEASUREMENT_ACTIVE.getName(),
                "Enables performance measurement and logging to CSV files in the 'measurement' directory.");

        helpTexts.put(Props.MEASUREMENT_DIR.getName(),
                "The directory where measurement logs are stored.");

        helpTexts.put(Props.SETTINGS_DIR.getName(),
                "The directory where application settings are stored.");

        helpTexts.put(Props.ICON_LOCATION.getName(),
                "The path to the application icon file.");

        helpTexts.put(Props.AUTO_POP_OFF_ENABLED.getName(),
                "Enables automatic block pop-off when a fork is detected."
                        + "<br>Helps the node stay on the correct chain.");

        helpTexts.put(Props.AUTO_CONSISTENCY_RESOLVE_ENABLED.getName(),
                "Enables automatic resolution of database inconsistencies at startup.");

        helpTexts.put(Props.INDIRECT_INCOMING_SERVICE_ENABLE.getName(),
                "Enables the service to track indirect incoming payments (e.g., from multi-out transactions).");

        helpTexts.put(Props.BRS_AT_PROCESSOR_CACHE_BLOCK_COUNT.getName(),
                "The number of blocks to cache for the Automated Transaction (AT) processor."
                        + "<br>A larger cache can improve AT execution performance but uses more memory.");

        helpTexts.put(Props.BRS_SHUTDOWN_TIMEOUT.getName(),
                "The maximum time in seconds to wait for a graceful shutdown before forcing exit.");

        helpTexts.put(Props.BRS_CHECKPOINT_HEIGHT.getName(),
                "The block height of a known valid checkpoint. Used to verify chain integrity during sync."
                        + "<br>Set to -1 to disable checkpoint verification and check the entire chain from genesis.");

        helpTexts.put(Props.BRS_CHECKPOINT_HASH.getName(),
                "The hash of the checkpoint block.");

        helpTexts.put(Props.BRS_PK_CHECKS.getName(),
                "<b>Public Key Checks (Account Freeze List)</b>"
                        + "<br>This setting allows freezing specific accounts by preventing their public keys from being verified or used."
                        + "<br>This is a security measure used in conjunction with the <code>PK_FREEZE</code> network constant."
                        + "<br><br><b>Format:</b>"
                        + "<br>The list contains 16-character hexadecimal strings. Each string represents an Account ID encoded in Little Endian byte order."
                        + "<br><br><b>How to use:</b>"
                        + "<br>Use the <b>Magic Wand</b> icon next to this field to open the conversion tool. You can enter Account IDs or RS Addresses, and the tool will generate the correct hex code for this list."
                        + "<br><br><b>Effect:</b>"
                        + "<br>If an account ID is in this list and <code>PK_FREEZE</code> is active, the account cannot send transactions.");

        helpTexts.put(Props.ENABLE_AT_DEBUG_LOG.getName(),
                "Enables debug logging for Automated Transactions (ATs).");

        helpTexts.put(Props.CASH_BACK_ID.getName(),
                "The Account ID that receives a cashback for a percentage of the fees from transactions created by this node."
                        + "<br>The percentage is defined by 'Cash Back Factor'.");

        helpTexts.put(Props.CASH_BACK_FACTOR.getName(),
                "The percentage of transaction fees to return as cashback to the Cash Back ID.");

        helpTexts.put(Props.ALIAS_RENEWAL_FREQUENCY.getName(),
                "The frequency at which aliases should be renewed.");

        // Dev
        helpTexts.put(Props.DEV_OFFLINE.getName(),
                "Runs the node in offline mode, disabling P2P networking.");

        helpTexts.put(Props.DEV_TIMEWARP.getName(),
                "Enables time warping for testing purposes."
                        + "<br>Allows simulating future time.");

        helpTexts.put(Props.DEV_MOCK_MINING.getName(),
                "Enables mock mining for testing purposes."
                        + "<br>Simulates mining without actual PoC verification.");

        helpTexts.put(Props.DEV_MOCK_MINING_DEADLINE.getName(),
                "The deadline value to use for mock mining.");

        helpTexts.put(Props.BRS_TEST_UNCONFIRMED_TRANSACTIONS.getName(),
                "Developer setting to test unconfirmed transaction handling."
                        + "<br>Should not be enabled for normal operation.");

        helpTexts.put(Props.DEV_DUMP_PEERS_VERSION.getName(),
                "Dumps the versions of connected peers to the log on exit.");

        helpTexts.put(Props.BRS_DEBUG_TRACE_ENABLED.getName(),
                "Enables debug tracing for detailed logging.");

        helpTexts.put(Props.BRS_DEBUG_TRACE_QUOTE.getName(),
                "The quote character used in debug trace logs.");

        helpTexts.put(Props.BRS_DEBUG_TRACE_SEPARATOR.getName(),
                "The separator character used in debug trace logs.");

        helpTexts.put(Props.BRS_DEBUG_LOG_CONFIRMED.getName(),
                "Logs confirmed transactions for debugging.");

        helpTexts.put(Props.BRS_DEBUG_TRACE_ACCOUNTS.getName(),
                "A list of account IDs to trace in debug logs."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (<code>;</code>)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the <code>node.properties</code> file.");

        helpTexts.put(Props.BRS_DEBUG_TRACE_LOG.getName(),
                "The file path for the debug trace log.");

        helpTexts.put(Props.BRS_COMMUNICATION_LOGGING_MASK.getName(),
                "A bitmask controlling which P2P communication events are logged.");

        // Jetty
        helpTexts.put(Props.JETTY_API_GZIP_FILTER.getName(),
                "Enables GZIP compression for API server responses to reduce bandwidth usage.");

        helpTexts.put(Props.JETTY_API_GZIP_FILTER_MIN_GZIP_SIZE.getName(),
                "The minimum response size (in bytes) to be eligible for GZIP compression on the API server.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER.getName(),
                "Enables the Denial of Service (DoS) filter for the API server.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_MAX_REQUEST_PER_SEC.getName(),
                "DoS Filter: Maximum number of requests allowed from a single IP per second.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_THROTTLED_REQUESTS.getName(),
                "DoS Filter: Number of requests to throttle (queue) before rejecting.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_DELAY_MS.getName(),
                "DoS Filter: Delay in milliseconds applied to throttled requests.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_MAX_WAIT_MS.getName(),
                "DoS Filter: Maximum time in milliseconds a request will wait in the throttle queue.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_MAX_REQUEST_MS.getName(),
                "DoS Filter: Maximum time in milliseconds to process a request.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_THROTTLE_MS.getName(),
                "DoS Filter: Time in milliseconds to throttle a connection after it exceeds the rate limit.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_MAX_IDLE_TRACKER_MS.getName(),
                "DoS Filter: Maximum time in milliseconds to track an idle connection.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_TRACK_SESSIONS.getName(),
                "DoS Filter: Whether to track requests by session ID instead of IP address.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_INSERT_HEADERS.getName(),
                "DoS Filter: Whether to insert headers indicating the filter status.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_REMOTE_PORT.getName(),
                "DoS Filter: Whether to also track requests by remote port.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_IP_WHITELIST.getName(),
                "DoS Filter: A list of IPs that are exempt from rate limiting."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (<code>;</code>)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the <code>node.properties</code> file.");

        helpTexts.put(Props.JETTY_API_DOS_FILTER_MANAGED_ATTR.getName(),
                "DoS Filter: Whether the filter is managed by a container attribute.");

        helpTexts.put(Props.JETTY_P2P_GZIP_FILTER.getName(),
                "Enables GZIP compression for P2P server responses to reduce bandwidth usage.");

        helpTexts.put(Props.JETTY_P2P_GZIP_FILTER_MIN_GZIP_SIZE.getName(),
                "The minimum response size (in bytes) to be eligible for GZIP compression on the P2P server.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER.getName(),
                "Enables the Denial of Service (DoS) filter for the P2P server.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_MAX_REQUESTS_PER_SEC.getName(),
                "DoS Filter: Maximum number of requests allowed from a single IP per second.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_THROTTLED_REQUESTS.getName(),
                "DoS Filter: Number of requests to throttle (queue) before rejecting.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_DELAY_MS.getName(),
                "DoS Filter: Delay in milliseconds applied to throttled requests.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_MAX_WAIT_MS.getName(),
                "DoS Filter: Maximum time in milliseconds a request will wait in the throttle queue.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_MAX_REQUEST_MS.getName(),
                "DoS Filter: Maximum time in milliseconds to process a request.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_THROTTLE_MS.getName(),
                "DoS Filter: Time in milliseconds to throttle a connection after it exceeds the rate limit.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_MAX_IDLE_TRACKER_MS.getName(),
                "DoS Filter: Maximum time in milliseconds to track an idle connection.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_TRACK_SESSIONS.getName(),
                "DoS Filter: Whether to track requests by session ID instead of IP address.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_INSERT_HEADERS.getName(),
                "DoS Filter: Whether to insert headers indicating the filter status.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_REMOTE_PORT.getName(),
                "DoS Filter: Whether to also track requests by remote port.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_IP_WHITELIST.getName(),
                "DoS Filter: A list of IPs that are exempt from rate limiting."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (<code>;</code>)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the <code>node.properties</code> file.");

        helpTexts.put(Props.JETTY_P2P_DOS_FILTER_MANAGED_ATTR.getName(),
                "DoS Filter: Whether the filter is managed by a container attribute.");

        // Network Constants
        String networkWarning = "<br><br><b>Warning:</b> These are network-wide constants. Changing them will cause your node to be on a different network (fork) and is not recommended unless you are creating a custom network.";
        helpTexts.put(Props.BLOCK_TIME.getName(),
                "The target time in seconds between blocks." + networkWarning);

        helpTexts.put(Props.DECIMAL_PLACES.getName(),
                "The number of decimal places for the native currency." + networkWarning);

        helpTexts.put(Props.ONE_COIN_NQT.getName(),
                "The value of one coin in the smallest unit (NQT)." + networkWarning);

        helpTexts.put(Props.GENESIS_BLOCK_ID.getName(),
                "The unique ID of the first block (genesis block) of the blockchain." + networkWarning);

        helpTexts.put(Props.GENESIS_TIMESTAMP.getName(),
                "The epoch timestamp of the genesis block." + networkWarning);

        helpTexts.put(Props.ADDRESS_PREFIX.getName(),
                "The prefix used for addresses on this network (e.g., 'S' for Signum)." + networkWarning);

        helpTexts.put(Props.VALUE_SUFIX.getName(),
                "The suffix for the native currency (e.g., 'SIGNA')." + networkWarning);

        helpTexts.put(Props.BLOCK_REWARD_START.getName(),
                "The initial block reward amount in NQT." + networkWarning);

        helpTexts.put(Props.BLOCK_REWARD_CYCLE.getName(),
                "The number of blocks in a reward reduction cycle." + networkWarning);

        helpTexts.put(Props.BLOCK_REWARD_CYCLE_PERCENTAGE.getName(),
                "The percentage by which the block reward is reduced each cycle." + networkWarning);

        helpTexts.put(Props.BLOCK_REWARD_LIMIT_HEIGHT.getName(),
                "The block height at which the block reward reduction stops." + networkWarning);

        helpTexts.put(Props.BLOCK_REWARD_LIMIT_AMOUNT.getName(),
                "The minimum block reward amount after all reductions." + networkWarning);

        helpTexts.put(Props.NETWORK_PARAMETERS.getName(),
                "The Java class that defines all network parameters." + networkWarning);

        helpTexts.put(Props.REWARD_RECIPIENT_ENABLE_BLOCK_HEIGHT.getName(),
                "The block height at which reward recipient assignment becomes active." + networkWarning);

        helpTexts.put(Props.DIGITAL_GOODS_STORE_BLOCK_HEIGHT.getName(),
                "The block height at which the Digital Goods Store becomes active." + networkWarning);

        helpTexts.put(Props.AUTOMATED_TRANSACTION_BLOCK_HEIGHT.getName(),
                "The block height at which Automated Transactions (ATs) become active." + networkWarning);

        helpTexts.put(Props.AT_FIX_BLOCK_2_BLOCK_HEIGHT.getName(),
                "The block height for the 2nd AT fix." + networkWarning);

        helpTexts.put(Props.AT_FIX_BLOCK_3_BLOCK_HEIGHT.getName(),
                "The block height for the 3rd AT fix." + networkWarning);

        helpTexts.put(Props.AT_FIX_BLOCK_4_BLOCK_HEIGHT.getName(),
                "The block height for the 4th AT fix." + networkWarning);

        helpTexts.put(Props.AT_FIX_BLOCK_5_BLOCK_HEIGHT.getName(),
                "The block height for the 5th AT fix." + networkWarning);

        helpTexts.put(Props.PRE_POC2_BLOCK_HEIGHT.getName(),
                "The block height for the pre-PoC2 fork." + networkWarning);

        helpTexts.put(Props.POC2_BLOCK_HEIGHT.getName(),
                "The block height at which PoC2 becomes active." + networkWarning);

        helpTexts.put(Props.SODIUM_BLOCK_HEIGHT.getName(),
                "The block height for the Sodium hard fork." + networkWarning);

        helpTexts.put(Props.SIGNUM_HEIGHT.getName(),
                "The block height for the Signum hard fork." + networkWarning);

        helpTexts.put(Props.POC_PLUS_HEIGHT.getName(),
                "The block height at which PoC+ (Proof of Commitment) becomes active." + networkWarning);

        helpTexts.put(Props.SPEEDWAY_HEIGHT.getName(),
                "The block height for the Speedway hard fork." + networkWarning);

        helpTexts.put(Props.SMART_TOKEN_HEIGHT.getName(),
                "The block height at which Smart Tokens become active." + networkWarning);

        helpTexts.put(Props.SMART_FEES_HEIGHT.getName(),
                "The block height at which Smart Fees become active." + networkWarning);

        helpTexts.put(Props.SMART_ATS_HEIGHT.getName(),
                "The block height for the Smart ATs hard fork." + networkWarning);

        helpTexts.put(Props.DISTRIBUTION_FIX_BLOCK_HEIGHT.getName(),
                "The block height for the asset distribution fix." + networkWarning);

        helpTexts.put(Props.PK_BLOCK_HEIGHT.getName(),
                "The block height for the first Public Key announcement enforcement." + networkWarning);

        helpTexts.put(Props.PK2_BLOCK_HEIGHT.getName(),
                "The block height for the second Public Key announcement enforcement." + networkWarning);

        helpTexts.put(Props.PK_BLOCKS_PAST.getName(),
                "The number of blocks in the past to check for public key announcements." + networkWarning);

        helpTexts.put(Props.PK_API_BLOCK.getName(),
                "The block height at which the API enforces public key announcements." + networkWarning);

        helpTexts.put(Props.SMART_ALIASES_HEIGHT.getName(),
                "The block height at which Smart Aliases become active." + networkWarning);

        helpTexts.put(Props.DEV_NEXT_FORK_BLOCK_HEIGHT.getName(),
                "The block height for the next development fork." + networkWarning);
    }
}