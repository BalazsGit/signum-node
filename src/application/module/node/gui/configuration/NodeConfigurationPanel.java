package application.module.node.gui.configuration;

import application.module.node.Signum;
import application.module.node.crypto.Crypto;
import application.module.node.Constants;
import application.module.node.props.Prop;
import application.module.node.props.Props;
import application.module.node.gui.configuration.databaseConfiguration.DatabaseConfigurationPanel;
import application.module.node.gui.configuration.databaseConfiguration.DatabaseConfigurationUtils;
import application.module.node.util.Convert;
import application.module.node.util.PathUtils;
import application.module.node.gui.GuiColors;
import application.module.node.gui.GuiConstants;
import application.module.node.gui.util.GuiUtils;
import application.module.node.gui.util.HelpButton;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import com.google.gson.*;

import java.awt.event.ActionListener;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NodeConfigurationPanel extends JPanel {

    private NodeProfile savedProfile;
    private NodeProfile appliedProfile;
    private static final Logger LOGGER = LoggerFactory.getLogger(NodeConfigurationPanel.class);
    private final Map<String, Supplier<String>> valueSuppliers = new HashMap<>();
    private final Map<String, JComponent> propertyComponents = new HashMap<>();

    private static final String KEY_PROFILE_LINKS = "profileLinks";
    private static final String KEY_DATABASE = "database";
    private static final String KEY_LOGGING = "logging";
    private static final String KEY_LAF = "laf";
    private static final String KEY_DB_AUTO_START = "dbAutoStart";
    private static final String KEY_DB_AUTO_STOP = "dbAutoStop";

    private final Map<String, String> helpTexts = new HashMap<>();
    private final Map<String, String> defaultValues = new HashMap<>();
    private final Runnable restartAction;

    // Linked Profiles UI
    private JdbcProfileConfigurationPanel linkedDbPanel;
    private JComboBox<String> linkedLogCombo;
    private JComboBox<String> linkedLafCombo;
    private JCheckBox autoStartDbCheck;
    private JCheckBox autoStopDbCheck;

    private String savedLinkedLog = "";
    private String savedLinkedLaf = "";
    private String savedLinkedDb = "";
    private boolean savedDbAutoStart = false;
    private boolean savedDbAutoStop = false;

    private final Runnable backAction;
    private final Runnable switchAction;
    private final String confFolder;
    private Path propertiesFile;
    private JComboBox<String> profileComboBox;
    private final java.util.List<PropertyRow> allPropertyRows = new ArrayList<>();
    private JPanel searchResultsPanel;
    private CardLayout contentCardLayout;
    private JTabbedPane categoryTabbedPane;
    private JButton saveProfileBtn;
    private JButton applyProfileBtn;
    private JButton renameProfileBtn;
    private JButton deleteProfileBtn;
    private JButton newProfileBtn;
    private JButton reloadProfileBtn;
    private JButton resetToDefaultsBtn;
    private JButton refreshProfilesBtn;
    private JPanel contentContainer;
    private String runningProfileName;
    private String activeProfileName;
    private String loadedProfileName;
    private int currentAddingTabIndex = -1;
    private int linkedProfilesTabIndex = -1;
    private boolean isProgrammaticChange = false;

    public NodeConfigurationPanel(Runnable restartAction, String confFolder, Runnable backAction,
            Runnable switchAction) {
        super(new BorderLayout());
        this.restartAction = restartAction;
        this.confFolder = confFolder;
        this.backAction = backAction;
        this.switchAction = switchAction;

        // Determine the currently applied profile name from metadata once at startup
        this.runningProfileName = Signum.getActiveNodeProfile();
        this.activeProfileName = this.runningProfileName;
        this.loadedProfileName = this.runningProfileName;

        // Use the detected profile to resolve the properties file path
        this.propertiesFile = ConfigurationUtils.resolveProfilePath(confFolder, Signum.NODE_SUBFOLDER,
                this.loadedProfileName + ".properties");
        ConfigurationUtils.ensureConfigFileExists(this.propertiesFile);

        this.savedProfile = new NodeProfile(this.loadedProfileName);
        try (FileInputStream in = new FileInputStream(propertiesFile.toFile())) {
            this.savedProfile.getProperties().load(in);
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.appliedProfile = new NodeProfile(this.runningProfileName);
        // Request the running values from the Signum service for accurate comparison
        // Populated on demand in addProperty methods to avoid compilation errors with
        // Props class

        this.renameProfileBtn = new JButton("Rename Profile");
        this.deleteProfileBtn = new JButton("Delete Profile");
        this.categoryTabbedPane = new JTabbedPane();
        // Initialize buttons early to avoid NullPointerException in listeners during UI
        // construction
        this.saveProfileBtn = new JButton("Save Profile As");
        this.applyProfileBtn = new JButton("Apply Profile");
        loadAppliedProperties();
        initHelpTexts();
        initUI();
        loadProfileLinks(this.loadedProfileName);
    }

    private void initUI() {
        JPanel bodyPanel = new JPanel(new BorderLayout());

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

        newProfileBtn = new JButton("New Default Profile");
        newProfileBtn.setToolTipText("Create a new profile initialized with application defaults");
        newProfileBtn.addActionListener(e -> createNewProfile());
        profilePanel.add(newProfileBtn);

        saveProfileBtn.setToolTipText("Save Configuration Profile");
        saveProfileBtn.addActionListener(e -> saveProfile());
        profilePanel.add(saveProfileBtn);

        applyProfileBtn.setToolTipText("Apply selected profile to the node");
        applyProfileBtn.addActionListener(e -> applyProfile());
        profilePanel.add(applyProfileBtn);

        renameProfileBtn.setToolTipText("Rename selected profile");
        renameProfileBtn.addActionListener(e -> renameProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(renameProfileBtn);

        deleteProfileBtn.setToolTipText("Delete selected profile");
        deleteProfileBtn.addActionListener(e -> deleteProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(deleteProfileBtn);

        resetToDefaultsBtn = new JButton("Reset to Defaults");
        resetToDefaultsBtn.setToolTipText("Reset current profile settings to application defaults (without saving)");
        resetToDefaultsBtn.addActionListener(e -> resetToDefaults());
        profilePanel.add(resetToDefaultsBtn);

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
            if (isProgrammaticChange)
                return;
            String selected = (String) profileComboBox.getSelectedItem();
            if (selected != null) {
                loadProfile(selected);
            }
            updateProfileComboBoxColor();
            updateProfileButtonStates();
        });

        JButton helpBtn = new HelpButton();
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("View detailed information about configuration profile management");
        helpBtn.addActionListener(e -> showProfileHelp());
        profilePanel.add(helpBtn); // Add help button

        JScrollPane profileScrollPane = new JScrollPane(profilePanel);
        profileScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        profileScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        profileScrollPane.setBorder(BorderFactory.createEmptyBorder());
        profileScrollPane.setOpaque(false);
        profileScrollPane.getViewport().setOpaque(false);

        GuiUtils.addHorizontalScrollPadding(profileScrollPane, profilePanel, new Insets(5, 10, 5, 5));

        refreshProfileList();

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
                filterProperties(searchField.getText());
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                filterProperties(searchField.getText());
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                filterProperties(searchField.getText());
            }
        });

        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(profileScrollPane, BorderLayout.NORTH);
        northPanel.add(searchPanel, BorderLayout.SOUTH);
        bodyPanel.add(northPanel, BorderLayout.NORTH);

        // No border around the tabbed pane itself

        // Clear list before rebuilding UI (in case of re-init)
        allPropertyRows.clear();

        // --- API Server Settings ---
        currentAddingTabIndex = 0;
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
        categoryTabbedPane.addTab("API Settings", createScrollPane(apiPanel));

        // --- Database Settings ---
        currentAddingTabIndex = 1;
        JPanel dbPanel = createCategoryPanel();
        addJdbcUrlProperty(dbPanel, (Prop<String>) Props.DB_URL, "JDBC Connection URL");
        addProperty(dbPanel, Props.DB_CONNECTIONS, "Max Connections");
        addProperty(dbPanel, Props.DB_ARCHIVAL_MODE, "Archival Mode",
                new String[] { "ARCHIVE", "TRIM", "PRUNE" }, false);
        addProperty(dbPanel, Props.DB_OPTIMIZE, "Optimize DB on Start/Stop");
        addProperty(dbPanel, Props.DB_SQLITE_JOURNAL_MODE, "SQLite Journal Mode",
                new String[] { "WAL", "DELETE", "TRUNCATE", "PERSIST", "MEMORY", "OFF" });
        addProperty(dbPanel, Props.DB_SQLITE_SYNCHRONOUS, "SQLite Synchronous",
                new String[] { "NORMAL", "FULL", "OFF" });
        addProperty(dbPanel, Props.DB_SQLITE_CACHE_SIZE, "SQLite Cache Size");
        addProperty(dbPanel, Props.DB_INSERT_BATCH_MAX_SIZE, "DB Insert Batch Size");
        addProperty(dbPanel, Props.DB_SKIP_CHECK, "Skip DB Check on Start");
        addProperty(dbPanel, Props.NODE_BLOCK_CACHE_MB, "Block Cache (MB)");
        finalizeCategoryPanel(dbPanel);
        categoryTabbedPane.addTab("Database", createScrollPane(dbPanel));

        // --- P2P Networking ---
        currentAddingTabIndex = 2;
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
        categoryTabbedPane.addTab("P2P Networking", createScrollPane(p2pPanel));

        // --- Mining & GPU ---
        currentAddingTabIndex = 3;
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
        categoryTabbedPane.addTab("Mining & GPU", createScrollPane(miningPanel));

        // --- System & Advanced ---
        currentAddingTabIndex = 4;
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
        addProperty(systemPanel, Props.POP_OFF_SKIP_DB_CHECK, "Skip DB Check on Manual Pop-Off");
        addProperty(systemPanel, Props.AUTO_CONSISTENCY_RESOLVE_ENABLED, "Enable Auto DB Resolve");
        addProperty(systemPanel, Props.INDIRECT_INCOMING_SERVICE_ENABLE, "Enable Indirect Incoming Service");
        addProperty(systemPanel, Props.NODE_AT_PROCESSOR_CACHE_BLOCK_COUNT, "AT Processor Cache (Blocks)");
        addProperty(systemPanel, Props.NODE_SHUTDOWN_TIMEOUT, "Shutdown Timeout (sec)");
        addProperty(systemPanel, Props.NODE_CHECKPOINT_HEIGHT, "Checkpoint Height");
        addProperty(systemPanel, Props.NODE_CHECKPOINT_HASH, "Checkpoint Hash");
        addListProperty(systemPanel, Props.NODE_PK_CHECKS, "PK Checks");
        addProperty(systemPanel, Props.ENABLE_AT_DEBUG_LOG, "Enable AT Debug Log");
        addProperty(systemPanel, Props.CASH_BACK_ID, "Cash Back ID");
        addProperty(systemPanel, Props.CASH_BACK_FACTOR, "Cash Back Factor");
        addProperty(systemPanel, Props.ALIAS_RENEWAL_FREQUENCY, "Alias Renewal Frequency");
        finalizeCategoryPanel(systemPanel);
        categoryTabbedPane.addTab("System & Advanced", createScrollPane(systemPanel));

        // --- Dev & Debug ---
        currentAddingTabIndex = 5;
        JPanel devPanel = createCategoryPanel();
        addProperty(devPanel, Props.DEV_OFFLINE, "Offline Mode");
        addProperty(devPanel, Props.DEV_TIMEWARP, "Time Warp");
        addProperty(devPanel, Props.DEV_MOCK_MINING, "Mock Mining");
        addProperty(devPanel, Props.DEV_MOCK_MINING_DEADLINE, "Mock Mining Deadline");
        addProperty(devPanel, Props.NODE_TEST_UNCONFIRMED_TRANSACTIONS, "Test Unconfirmed Txs");
        addProperty(devPanel, Props.DEV_DUMP_PEERS_VERSION, "Dump Peers Version");
        addProperty(devPanel, Props.NODE_DEBUG_TRACE_ENABLED, "Debug Trace Enabled");
        addProperty(devPanel, Props.NODE_DEBUG_TRACE_QUOTE, "Debug Trace Quote");
        addProperty(devPanel, Props.NODE_DEBUG_TRACE_SEPARATOR, "Debug Trace Separator");
        addProperty(devPanel, Props.NODE_DEBUG_LOG_CONFIRMED, "Log Confirmed");
        addListProperty(devPanel, Props.NODE_DEBUG_TRACE_ACCOUNTS, "Debug Trace Accounts");
        addProperty(devPanel, Props.NODE_DEBUG_TRACE_LOG, "Debug Trace Log File");
        addProperty(devPanel, Props.NODE_COMMUNICATION_LOGGING_MASK, "Communication Logging Mask");
        finalizeCategoryPanel(devPanel);
        categoryTabbedPane.addTab("Dev & Debug", createScrollPane(devPanel));

        // --- Jetty Server ---
        currentAddingTabIndex = 6;
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
        categoryTabbedPane.addTab("Jetty Server", createScrollPane(jettyPanel));

        // --- Network Constants ---
        currentAddingTabIndex = 7;
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
        categoryTabbedPane.addTab("Network Constants", createScrollPane(netPanel));

        // --- Linked Profiles ---
        linkedProfilesTabIndex = categoryTabbedPane.getTabCount();
        currentAddingTabIndex = linkedProfilesTabIndex;

        JPanel linkedPanel = createCategoryPanel();
        addSectionHeader(linkedPanel, "Linked Profiles", true);
        linkedLogCombo = new JComboBox<>();
        linkedLafCombo = new JComboBox<>();
        addLinkedProfileRowWithButtons(linkedPanel, "Logger Profile:", linkedLogCombo, KEY_LOGGING);
        addLinkedProfileRowWithButtons(linkedPanel, "Look and Feel Profile:", linkedLafCombo, KEY_LAF);

        addSectionHeader(linkedPanel, "Linked Database Profile Detail:", false);

        autoStartDbCheck = new JCheckBox("Auto Start Database");
        autoStopDbCheck = new JCheckBox("Auto Stop Database");
        autoStartDbCheck.setOpaque(false);
        autoStopDbCheck.setOpaque(false);

        linkedPanel.add(autoStartDbCheck, "split 2, gapleft 10");
        linkedPanel.add(autoStopDbCheck, "wrap, gapbottom 10");

        linkedDbPanel = new JdbcProfileConfigurationPanel(confFolder, () -> {
            if (!isProgrammaticChange) {
                updateDirtyStatus();
            }
        });

        // Add listener to linkedDbPanel's engineCombo to update checkbox state
        if (linkedDbPanel != null && linkedDbPanel.getEngineCombo() instanceof JComboBox) {
            @SuppressWarnings("unchecked")
            JComboBox<DatabaseConfigurationPanel.DatabaseEngine> engineCombo = (JComboBox<DatabaseConfigurationPanel.DatabaseEngine>) linkedDbPanel
                    .getEngineCombo();
            engineCombo.addActionListener(e -> {
                if (!isProgrammaticChange) {
                    updateAutoDbCheckboxesState();
                    updateDirtyStatus(); // State change might make it dirty
                }
            });
        }

        // Listener for autoStart/Stop checkboxes
        ActionListener linkedCheckListener = e -> {
            if (!isProgrammaticChange) {
                updateDirtyStatus();
            }
        };
        autoStartDbCheck.addActionListener(linkedCheckListener);
        autoStopDbCheck.addActionListener(linkedCheckListener);

        // Initial state update for autoStart/Stop checkboxes
        updateAutoDbCheckboxesState();
        if (linkedDbPanel != null) {
            linkedPanel.add(linkedDbPanel, "span, growx, wrap");
        }

        finalizeCategoryPanel(linkedPanel);
        categoryTabbedPane.addTab("Linked Profiles", createScrollPane(linkedPanel));

        // --- Content Container (CardLayout for Tabs vs Search Results) ---
        contentCardLayout = new CardLayout();
        contentContainer = new JPanel(contentCardLayout);

        contentContainer.add(categoryTabbedPane, "TABS");

        searchResultsPanel = new JPanel(new MigLayout("fillx, insets 10, gap 5", "[][grow]", ""));
        JScrollPane searchScrollPane = createScrollPane(searchResultsPanel);
        contentContainer.add(searchScrollPane, "SEARCH");

        bodyPanel.add(contentContainer, BorderLayout.CENTER);
        add(bodyPanel, BorderLayout.CENTER);

        // --- Bottom Panel with Buttons and File Path ---
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 0));
        bottomPanel.setBorder(new EmptyBorder(5, 10, 5, 5));

        // Legend
        bottomPanel.add(createLegendPanel(), BorderLayout.NORTH);

        // File path field
        JLabel pathLabel = new JLabel("Configuration File: " + propertiesFile.toAbsolutePath().toString());
        pathLabel.setForeground(GuiColors.getFaintText());
        bottomPanel.add(pathLabel, BorderLayout.CENTER);

        JPanel bottomContainer = new JPanel(new BorderLayout());
        bottomContainer.add(new JSeparator(SwingConstants.HORIZONTAL), BorderLayout.NORTH);
        bottomContainer.add(bottomPanel, BorderLayout.CENTER);
        add(bottomContainer, BorderLayout.SOUTH);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Re-style and re-size input fields (borders and fonts)
        if (allPropertyRows != null) {
            allPropertyRows.forEach(row -> {
                if (row.input != null) {
                    ConfigurationUtils.styleInputComponent(row.input);
                    ConfigurationUtils.fixComponentSize(row.input);
                }
            });
        }
        updateProfileButtonsUI();
    }

    private void updateProfileButtonsUI() {
        if (profileComboBox != null)
            ConfigurationUtils.fixComponentSize(profileComboBox);
        ConfigurationUtils.configureProfileToolbar(newProfileBtn, saveProfileBtn, applyProfileBtn, renameProfileBtn,
                deleteProfileBtn, reloadProfileBtn, refreshProfilesBtn, resetToDefaultsBtn);
    }

    private void refreshUIColors() {
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            updateColor(entry.getValue(), entry.getKey(), defaultValues.get(entry.getKey()));
        }
    }

    private void filterProperties(String text) {
        boolean isSearch = text != null && !text.trim().isEmpty();

        if (isSearch) {
            searchResultsPanel.removeAll();
            String lowerText = text.toLowerCase();

            for (PropertyRow row : allPropertyRows) {
                if (row.prop.getName().toLowerCase().contains(lowerText) ||
                        row.labelText.toLowerCase().contains(lowerText)) {

                    searchResultsPanel.add(row.label, "align label");
                    searchResultsPanel.add(row.input, "split 2, growx, height pref!");
                    if (row.extra != null) {
                        searchResultsPanel.add(row.extra, row.extraConstraints);
                    }
                    searchResultsPanel.add(row.help, "wrap");
                    searchResultsPanel.add(row.separator, "span, growx, wrap, gaptop 2, gapbottom 2");
                }
            }
            contentCardLayout.show(contentContainer, "SEARCH");
        } else {
            // Clear original parents first to ensure correct ordering and no leftovers like
            // pushy labels at the top
            Set<JPanel> parents = allPropertyRows.stream()
                    .map(row -> row.originalParent)
                    .collect(Collectors.toSet());
            parents.forEach(JPanel::removeAll);

            // Restore components to their original panels in order
            for (PropertyRow row : allPropertyRows) {
                row.originalParent.add(row.label, row.labelConstraints);
                row.originalParent.add(row.input, row.inputConstraints);
                if (row.extra != null) {
                    row.originalParent.add(row.extra, row.extraConstraints);
                }
                row.originalParent.add(row.help, row.helpConstraints);
                row.originalParent.add(row.separator, row.separatorConstraints);
            }

            // Re-add vertical fillers
            parents.forEach(p -> p.add(new JLabel(), "pushy"));

            contentCardLayout.show(contentContainer, "TABS");
        }
        revalidate();
        repaint();
    }

    private void refreshProfileList() {
        boolean wasProgrammatic = isProgrammaticChange;
        try {
            isProgrammaticChange = true;
            String currentSelection = (String) profileComboBox.getSelectedItem();
            profileComboBox.removeAllItems();

            String lastProfile = ConfigurationUtils
                    .loadAppliedProfile(ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.NODE_SUBFOLDER));
            this.activeProfileName = lastProfile != null ? lastProfile.trim() : Signum.PROPERTIES_NAME;

            Path nodeConfPath = PathUtils.resolvePath(confFolder).resolve(Signum.NODE_SUBFOLDER);
            String baseFileName = Signum.PROPERTIES_NAME + ".properties";
            ConfigurationUtils.fetchProfileNames(nodeConfPath, Signum.DEFAULT_PROPERTIES_NAME + ".properties")
                    .stream()
                    .filter(name -> !(name + ".properties").equals(baseFileName))
                    .forEach(profileComboBox::addItem);

            // Ensure the base profile is always available in the list
            boolean hasBase = false;
            for (int i = 0; i < profileComboBox.getItemCount(); i++) {
                if (Signum.PROPERTIES_NAME.equals(profileComboBox.getItemAt(i))) {
                    hasBase = true;
                    break;
                }
            }
            if (!hasBase) {
                profileComboBox.insertItemAt(Signum.PROPERTIES_NAME, 0);
            }

            if (currentSelection != null) {
                profileComboBox.setSelectedItem(currentSelection);
            } else if (this.activeProfileName != null) {
                profileComboBox.setSelectedItem(this.activeProfileName);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            isProgrammaticChange = wasProgrammatic;
        }
        updateProfileComboBoxColor();
        updateProfileButtonStates();
    }

    public String getLoadedProfileName() {
        return loadedProfileName;
    }

    private void refreshLinkedProfileLists() {
        isProgrammaticChange = true;

        // Logging Profiles
        linkedLogCombo.removeAllItems();
        linkedLogCombo.addItem("");
        Path loggingPath = PathUtils.resolvePath(confFolder).resolve(Signum.NODE_LOGGING_SUBFOLDER);
        ConfigurationUtils.fetchProfileNames(loggingPath, Signum.DEFAULT_LOGGING_PROPERTIES_NAME + ".properties")
                .forEach(linkedLogCombo::addItem);
        if (!Signum.LOGGING_PROPERTIES_NAME.equals(Signum.DEFAULT_LOGGING_PROPERTIES_NAME)) {
            linkedLogCombo.addItem(Signum.LOGGING_PROPERTIES_NAME);
        }

        // LAF Profiles (from gui-settings.json)
        linkedLafCombo.removeAllItems();
        linkedLafCombo.addItem("");
        try {
            Path settingsPath = PathUtils.resolvePath(Props.SETTINGS_DIR.getDefaultValue())
                    .resolve("gui-settings.json");
            if (Files.exists(settingsPath)) {
                JsonObject settings = JsonParser.parseReader(Files.newBufferedReader(settingsPath)).getAsJsonObject();
                if (settings.has("lookAndFeelProfiles")) {
                    settings.getAsJsonObject("lookAndFeelProfiles").keySet().forEach(linkedLafCombo::addItem);
                }
            }
        } catch (Exception e) {
            /* ignore */ }

        // Apply priority selection: 1. linked, 2. active
        String currentLinkedLog = getLinkedLoggingProfile();
        linkedLogCombo.setSelectedItem(currentLinkedLog != null && !currentLinkedLog.isEmpty() ? currentLinkedLog
                : Signum.getActiveLoggingProfile());
        String currentLinkedLaf = getLinkedLafProfile();
        linkedLafCombo
                .setSelectedItem(currentLinkedLaf != null && !currentLinkedLaf.isEmpty() ? currentLinkedLaf : "gui");

        isProgrammaticChange = false;
    }

    private void loadProfileLinks(String profileName) {
        isProgrammaticChange = true;
        refreshLinkedProfileLists();

        if (linkedDbPanel != null) {
            ((JComboBox<?>) linkedDbPanel.getProfileCombo()).setSelectedItem("");
        }
        linkedLogCombo.setSelectedItem("");
        linkedLafCombo.setSelectedItem("");
        autoStartDbCheck.setSelected(true);
        autoStopDbCheck.setSelected(true);

        Path metadataPath = ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.NODE_SUBFOLDER);
        if (Files.exists(metadataPath)) {
            try (Reader reader = Files.newBufferedReader(metadataPath)) {
                JsonObject metadata = JsonParser.parseReader(reader).getAsJsonObject();
                if (metadata.has(KEY_PROFILE_LINKS) && metadata.getAsJsonObject(KEY_PROFILE_LINKS).has(profileName)) {
                    JsonObject links = metadata.getAsJsonObject(KEY_PROFILE_LINKS).getAsJsonObject(profileName);
                    if (links.has(KEY_DATABASE)) {
                        String dbLink = links.get(KEY_DATABASE).getAsString();
                        if (dbLink.contains(":")) {
                            String[] parts = dbLink.split(":");
                            ((JComboBox<DatabaseConfigurationPanel.DatabaseEngine>) linkedDbPanel.getEngineCombo())
                                    .setSelectedItem(
                                            DatabaseConfigurationPanel.DatabaseEngine.fromDisplayName(parts[0]));
                            ((JComboBox<String>) linkedDbPanel.getProfileCombo()).setSelectedItem(parts[1]);
                        }
                    }
                    if (links.has(KEY_LOGGING))
                        linkedLogCombo.setSelectedItem(links.get(KEY_LOGGING).getAsString());
                    if (links.has(KEY_LAF))
                        linkedLafCombo.setSelectedItem(links.get(KEY_LAF).getAsString());
                    if (links.has(KEY_DB_AUTO_START))
                        autoStartDbCheck.setSelected(links.get(KEY_DB_AUTO_START).getAsBoolean());
                    if (links.has(KEY_DB_AUTO_STOP))
                        autoStopDbCheck.setSelected(links.get(KEY_DB_AUTO_STOP).getAsBoolean());
                }
            } catch (Exception e) {
                LOGGER.error("Error loading profile links from JSON", e);
            }
        }

        updateAutoDbCheckboxesState();

        savedLinkedLog = getLinkedLoggingProfile();
        savedLinkedLaf = getLinkedLafProfile();
        savedLinkedDb = getLinkedDbProfile();
        savedDbAutoStart = autoStartDbCheck.isSelected();
        savedDbAutoStop = autoStopDbCheck.isSelected();

        isProgrammaticChange = false;
    }

    private void saveProfileLinks(String profileName) {
        Path metadataPath = ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.NODE_SUBFOLDER);
        JsonObject metadata = new JsonObject();
        if (Files.exists(metadataPath)) {
            try (Reader reader = Files.newBufferedReader(metadataPath)) {
                metadata = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (Exception e) {
                /* start new if corrupt */ }
        }

        if (!metadata.has(KEY_PROFILE_LINKS)) {
            metadata.add(KEY_PROFILE_LINKS, new JsonObject());
        }
        JsonObject allLinks = metadata.getAsJsonObject(KEY_PROFILE_LINKS);

        JsonObject currentLinks = new JsonObject();
        String db = getLinkedDbProfile();
        String log = (String) linkedLogCombo.getSelectedItem();
        String laf = (String) linkedLafCombo.getSelectedItem();
        boolean autoStart = autoStartDbCheck.isSelected();
        boolean autoStop = autoStopDbCheck.isSelected();

        if ((db != null && !db.isEmpty()) || (log != null && !log.isEmpty()) || (laf != null && !laf.isEmpty())
                || autoStart || autoStop) {
            if (db != null && !db.isEmpty())
                currentLinks.addProperty(KEY_DATABASE, db);
            if (log != null && !log.isEmpty())
                currentLinks.addProperty(KEY_LOGGING, log);
            if (laf != null && !laf.isEmpty())
                currentLinks.addProperty(KEY_LAF, laf);
            currentLinks.addProperty(KEY_DB_AUTO_START, autoStart);
            currentLinks.addProperty(KEY_DB_AUTO_STOP, autoStop);

            allLinks.add(profileName, currentLinks);

            savedLinkedLog = log != null ? log : "";
            savedLinkedLaf = laf != null ? laf : "";
            savedLinkedDb = db != null ? db : "";
            savedDbAutoStart = autoStart;
            savedDbAutoStop = autoStop;
        } else {
            allLinks.remove(profileName);
            savedLinkedLog = "";
            savedLinkedLaf = "";
            savedLinkedDb = "";
            savedDbAutoStart = false;
            savedDbAutoStop = false;
        }

        try (Writer writer = Files.newBufferedWriter(metadataPath)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(metadata, writer);
        } catch (Exception e) {
            LOGGER.error("Error saving profile links to JSON", e);
        }
    }

    private void updateProfileComboBoxColor() {
        ConfigurationUtils.updateProfileComboBoxColor(profileComboBox, runningProfileName, activeProfileName);
    }

    private boolean saveProfile() {
        String currentProfile = (String) profileComboBox.getSelectedItem();
        String suggestedName = currentProfile != null ? currentProfile : "";

        JTextField nameField = new JTextField(suggestedName);
        JLabel errorLabel = new JLabel("Saving as system profile is not allowed.");
        errorLabel.setForeground(GuiColors.getContrastRed());
        errorLabel.setVisible(false);

        JPanel panel = new JPanel(new MigLayout("wrap 1, fillx, insets 0", "[grow]", "[]5[]5[]"));
        panel.add(new JLabel("Enter profile name:"));
        panel.add(nameField, "growx");
        panel.add(errorLabel, "hidemode 3");

        String report = getUnsavedChangesReport();
        if (report != null) {
            JLabel reportLabel = new JLabel(report);
            JScrollPane scroll = new JScrollPane(reportLabel);
            scroll.setPreferredSize(new Dimension(500, 200));
            scroll.setBorder(BorderFactory.createTitledBorder("Changes to be saved"));
            panel.add(scroll, "growx, gaptop 10");
        }

        JButton saveBtn = new JButton("Save");
        JButton discardBtn = new JButton("Discard");
        JButton cancelBtn = new JButton("Cancel");
        Object[] options = { saveBtn, discardBtn, cancelBtn };

        JOptionPane pane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.YES_NO_CANCEL_OPTION, null,
                options, saveBtn);
        JDialog dialog = pane.createDialog(this, "Save Profile As");

        saveBtn.addActionListener(e -> {
            pane.setValue(saveBtn);
            dialog.setVisible(false);
        });
        discardBtn.addActionListener(e -> {
            pane.setValue(discardBtn);
            dialog.setVisible(false);
        });
        cancelBtn.addActionListener(e -> {
            pane.setValue(cancelBtn);
            dialog.setVisible(false);
        });

        nameField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void validate() {
                String text = nameField.getText().trim();
                boolean isReserved = "node-default".equalsIgnoreCase(text);
                errorLabel.setVisible(isReserved);
                saveBtn.setEnabled(!isReserved && !text.isEmpty());
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                validate();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                validate();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                validate();
            }
        });

        try {
            while (true) {
                pane.setValue(JOptionPane.UNINITIALIZED_VALUE);
                dialog.setVisible(true);
                Object value = pane.getValue();

                if (value == saveBtn) {
                    String name = nameField.getText().trim();
                    try {
                        Path targetFile = ConfigurationUtils.resolveProfilePath(confFolder, Signum.NODE_SUBFOLDER,
                                name + ".properties");
                        if (Files.exists(targetFile)) {
                            int choice = JOptionPane.showConfirmDialog(this,
                                    "Profile '" + name + "' already exists. Do you want to overwrite it?",
                                    "Override profile settings",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.WARNING_MESSAGE);
                            if (choice != JOptionPane.YES_OPTION) {
                                continue;
                            }
                        }

                        Properties propsToSave = getPropertiesFromUI();
                        ConfigurationUtils.savePropertiesPreservingFormat(targetFile, propsToSave,
                                propertyComponents.keySet());

                        isProgrammaticChange = true;
                        try {
                            this.loadedProfileName = name;
                            this.savedProfile = new NodeProfile(name);
                            this.savedProfile.setProperties(propsToSave);
                            this.propertiesFile = targetFile;

                            refreshProfileList();
                            profileComboBox.setSelectedItem(name);
                        } finally {
                            isProgrammaticChange = false;
                        }
                        saveProfileLinks(name);
                        updateProfileComboBoxColor();
                        updateProfileComboBoxColor();

                        updateDirtyStatus();
                        refreshUIColors();
                        JOptionPane.showMessageDialog(this,
                                "Profile '" + name
                                        + "' saved successfully. A restart is required for changes to take effect.",
                                "Success", JOptionPane.INFORMATION_MESSAGE);
                        return true;
                    } catch (Exception e) {
                        JOptionPane.showMessageDialog(this, "Error saving profile: " + e.getMessage(), "Error",
                                JOptionPane.ERROR_MESSAGE);
                        e.printStackTrace();
                    }
                } else if (value == discardBtn) {
                    isProgrammaticChange = true;
                    updateUIFromProperties(savedProfile.getProperties());
                    updateDirtyStatus();
                    isProgrammaticChange = false;
                    return false;
                } else {
                    return false;
                }
            }
        } finally {
            dialog.dispose();
        }
    }

    private void loadProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()
                || profileName.equals(loadedProfileName)) {
            return;
        }

        checkUnsavedChangesAndProceed(
                () -> {
                    Path targetFile = ConfigurationUtils.resolveProfilePath(confFolder, Signum.NODE_SUBFOLDER,
                            profileName + ".properties");
                    if (Files.exists(targetFile)) {
                        Properties loaded = new Properties();
                        try (FileInputStream in = new FileInputStream(targetFile.toFile())) {
                            isProgrammaticChange = true;
                            loaded.load(in);
                            savedProfile = new NodeProfile(profileName);
                            savedProfile.setProperties(loaded);
                            updateUIFromProperties(loaded);
                            this.propertiesFile = targetFile;
                            this.loadedProfileName = profileName;
                            loadProfileLinks(profileName);
                            updateDirtyStatus();
                            isProgrammaticChange = false;
                            updateProfileComboBoxColor();
                        } catch (Exception e) {
                            JOptionPane.showMessageDialog(this, "Error loading profile file: " + e.getMessage(),
                                    "Error",
                                    JOptionPane.ERROR_MESSAGE);
                            // Revert to headless if loading fails
                            isProgrammaticChange = true;
                            profileComboBox.setSelectedItem(Signum.NODE_SUBFOLDER);
                            loadProfile(Signum.NODE_SUBFOLDER + "-default");
                            isProgrammaticChange = false;
                        }
                    }
                },
                () -> profileComboBox.setSelectedItem(loadedProfileName));
    }

    public boolean checkUnsavedChangesAndProceed(Runnable onProceed, Runnable onCancel) {
        String report = getUnsavedChangesReport();
        if (report == null) {
            if (onProceed != null)
                onProceed.run();
            return true;
        }

        Object[] message = {
                "You have unsaved changes in profile '" + loadedProfileName + "'.",
                report,
                "What would you like to do?"
        };
        Object[] options = { "Save Profile As", "Discard", "Cancel" };
        int result = JOptionPane.showOptionDialog(this, message, "Unsaved Changes",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);

        if (result == JOptionPane.YES_OPTION) {
            if (saveProfile()) {
                if (onProceed != null)
                    onProceed.run();
                return true;
            }
            return false;
        } else if (result == JOptionPane.NO_OPTION) {
            isProgrammaticChange = true;
            updateUIFromProperties(savedProfile.getProperties());
            updateDirtyStatus();
            isProgrammaticChange = false;
            if (onProceed != null)
                onProceed.run();
            return true;
        } else {
            if (onCancel != null)
                onCancel.run();
            return false;
        }
    }

    private String getUnsavedChangesReport() {
        StringBuilder report = new StringBuilder(
                "<html><b>Unsaved changes in Node Configuration (Profile: '" + loadedProfileName + "'):</b><ul>");
        boolean changesFound = false;
        for (PropertyRow row : allPropertyRows) {
            if (isRowDirty(row)) {
                changesFound = true;
                String savedValue = savedProfile.getProperty(row.prop.getName());
                if (savedValue == null)
                    savedValue = getSafeDefault(row.prop);
                Supplier<String> supplier = valueSuppliers.get(row.prop.getName());
                String newValue = (supplier != null) ? supplier.get() : "";
                if (newValue == null)
                    newValue = "";

                boolean isList = (row.input instanceof JScrollPane) ||
                        (row.input instanceof JPanel && Props.NODE_PK_CHECKS.getName().equals(row.prop.getName()));

                String displaySaved = savedValue;
                String displayNew = newValue;

                if (isList) {
                    displaySaved = normalizeListValue(savedValue, ";");
                    displayNew = normalizeListValue(newValue, "\n");
                }

                report.append("<li>").append(row.labelText).append(": '")
                        .append(displaySaved.length() > 50 ? displaySaved.substring(0, 47) + "..." : displaySaved)
                        .append("' &rarr; '")
                        .append(displayNew.length() > 50 ? displayNew.substring(0, 47) + "..." : displayNew)
                        .append("'</li>");
            }
        }
        report.append("</ul></html>");
        return changesFound ? report.toString() : null;
    }

    private void reloadProfile() {
        if (loadedProfileName != null) {
            if (hasUnsavedChanges()) {
                String message = "You have unsaved changes. Are you sure you want to reload from disk and discard these changes?";
                Object[] options = { "Discard and Reload", "Cancel" };
                int result = JOptionPane.showOptionDialog(this, message, "Confirm Reload",
                        JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                        null, options, options[1]);
                if (result != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            Path targetFile = ConfigurationUtils.resolveProfilePath(confFolder, Signum.NODE_SUBFOLDER,
                    loadedProfileName + ".properties");
            if (Files.exists(targetFile)) {
                Properties loaded = new Properties();
                try (FileInputStream in = new FileInputStream(targetFile.toFile())) {
                    isProgrammaticChange = true;
                    loaded.load(in);
                    savedProfile = new NodeProfile(loadedProfileName);
                    savedProfile.setProperties(loaded);
                    updateUIFromProperties(loaded);
                    updateDirtyStatus();
                    updateProfileComboBoxColor();
                    isProgrammaticChange = false;
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(this, "Error reloading profile: " + e.getMessage(), "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }

    private void createNewProfile() {
        checkUnsavedChangesAndProceed(() -> {
            String name = (String) JOptionPane.showInputDialog(this, "Enter new profile name:", "New Profile",
                    JOptionPane.PLAIN_MESSAGE, null, null, "");
            if (name == null || name.trim().isEmpty()
                    || (Signum.NODE_SUBFOLDER + "-default").equalsIgnoreCase(name.trim()))
                return;

            Path targetFile = ConfigurationUtils.resolveProfilePath(confFolder, Signum.NODE_SUBFOLDER,
                    name + ".properties");
            if (Files.exists(targetFile)) {
                JOptionPane.showMessageDialog(this, "Profile '" + name + "' already exists.", "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            isProgrammaticChange = true;

            try {
                for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
                    String key = entry.getKey();
                    JComponent comp = entry.getValue();
                    String defaultValue = defaultValues.get(key);

                    if (comp instanceof JCheckBox) {
                        ((JCheckBox) comp).setSelected(Boolean.parseBoolean(defaultValue));
                    } else if (comp instanceof JComboBox) {
                        ((JComboBox<?>) comp).setSelectedItem(defaultValue);
                    } else if (comp instanceof javax.swing.text.JTextComponent) {
                        ((javax.swing.text.JTextComponent) comp).setText(defaultValue);
                    } else if (comp instanceof JScrollPane
                            && ((JScrollPane) comp).getViewport().getView() instanceof JTextArea) {
                        ((JTextArea) ((JScrollPane) comp).getViewport().getView())
                                .setText(defaultValue.replace(";", "\n"));
                    }
                }

                Properties propsToSave = getPropertiesFromUI();
                ConfigurationUtils.savePropertiesPreservingFormat(targetFile, propsToSave, propertyComponents.keySet());

                this.loadedProfileName = name; // Update early to prevent redundant load prompts during refresh
                refreshProfileList();
                profileComboBox.setSelectedItem(name);
                this.savedProfile = new NodeProfile(name);
                this.savedProfile.setProperties(propsToSave);
                this.propertiesFile = targetFile;
                loadProfileLinks(name);
                updateDirtyStatus();
                updateUIFromProperties(propsToSave);
                updateProfileComboBoxColor();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Error creating profile: " + e.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
            } finally {
                isProgrammaticChange = false;
            }
        }, null);
    }

    private void resetToDefaults() {
        Properties defaultProps = new Properties();
        for (Map.Entry<String, String> entry : defaultValues.entrySet()) {
            defaultProps.setProperty(entry.getKey(), entry.getValue());
        }
        isProgrammaticChange = true;
        updateUIFromProperties(defaultProps);
        loadProfileLinks(loadedProfileName);
        updateDirtyStatus();
        refreshUIColors();
        isProgrammaticChange = false;
        JOptionPane.showMessageDialog(this,
                "All settings reset to application defaults. Remember to save if you want to keep these changes.",
                "Reset to Defaults", JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateProfileButtonStates() {
        String selected = (String) profileComboBox.getSelectedItem();
        boolean isReadOnly = Signum.NODE_SUBFOLDER.equals(selected)
                || (Signum.NODE_SUBFOLDER + "-default").equals(selected);
        resetToDefaultsBtn.setEnabled(true); // Always enable reset to defaults
        renameProfileBtn.setEnabled(!isReadOnly);
        deleteProfileBtn.setEnabled(!isReadOnly);
    }

    public void renameProfile(String oldProfileName) {
        if (oldProfileName == null || oldProfileName.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No profile selected to rename.", "Rename Profile",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        String newProfileName = (String) JOptionPane.showInputDialog(
                this,
                "Enter new name for profile '" + oldProfileName + "':",
                "Rename Profile",
                JOptionPane.PLAIN_MESSAGE,
                null,
                null,
                oldProfileName);

        if (newProfileName == null || newProfileName.trim().isEmpty() || newProfileName.equals(oldProfileName)
                || (Signum.NODE_SUBFOLDER + "-default").equalsIgnoreCase(newProfileName.trim())) {
            return; // User cancelled or entered the same name
        }

        try {
            Path oldFile = ConfigurationUtils.resolveProfilePath(confFolder, Signum.NODE_SUBFOLDER,
                    oldProfileName + ".properties");
            Path newFile = ConfigurationUtils.resolveProfilePath(confFolder, Signum.NODE_SUBFOLDER,
                    newProfileName + ".properties");

            if (ConfigurationUtils.confirmAndRenameProfile(this, oldFile, newFile, oldProfileName, newProfileName)) {
                refreshProfileList();
                profileComboBox.setSelectedItem(newProfileName);
                if (oldProfileName.equals(activeProfileName)) {
                    ConfigurationUtils.updateAppliedProfile(
                            ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.NODE_SUBFOLDER),
                            newProfileName);
                    this.activeProfileName = newProfileName;
                }
                if (oldProfileName.equals(loadedProfileName)) {
                    this.loadedProfileName = newProfileName;
                    this.propertiesFile = newFile;
                }
                updateProfileComboBoxColor();
                updateDirtyStatus();

                JOptionPane.showMessageDialog(this,
                        "Profile '" + oldProfileName + "' renamed to '" + newProfileName + "' successfully.", "Success",
                        JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, "Profile '" + oldProfileName + "' not found.", "Error",
                        JOptionPane.ERROR_MESSAGE);
            }

        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error renaming profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    private void deleteProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()) {
            return;
        }
        if ((Signum.NODE_SUBFOLDER + "-default").equals(profileName)) {
            JOptionPane.showMessageDialog(this, "The system profiles cannot be deleted.", "Action Not Allowed",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        int choice = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to delete profile '" + profileName + "'?",
                "Confirm Deletion",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            Path file = ConfigurationUtils.resolveProfilePath(confFolder, Signum.NODE_SUBFOLDER,
                    profileName + ".properties");
            if (Files.exists(file)) {
                Files.delete(file);
                refreshProfileList();
                profileComboBox.setSelectedItem(Signum.NODE_SUBFOLDER);
                loadProfile(Signum.NODE_SUBFOLDER);
                JOptionPane.showMessageDialog(this, "Profile '" + profileName + "' deleted successfully.",
                        "Success", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error deleting profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    private void showProfileHelp() {
        String message = "<html><body style='width: 400px'>" +
                "<h2>Node Configuration Profiles</h2>" +
                "<p>Profiles allow you to maintain multiple sets of node configurations. Use the toolbar buttons to perform the following actions:</p>"
                +
                "<ul>" +
                "<li><b>New Default Profile</b>: Creates a new configuration profile initialized with application defaults.</li>"
                +
                "<li><b>Save Profile As</b>: Saves the current settings from all tabs into the selected or a new profile.</li>"
                +
                "<li><b>Apply Profile</b>: Activates the selected profile. You can choose to apply it for the next startup or restart the node service immediately to apply changes.</li>"
                +
                "<li><b>Rename Profile</b>: Changes the name of the currently selected configuration profile.</li>"
                +
                "<li><b>Delete Profile</b>: Permanently removes the selected configuration profile from the disk.</li>"
                +
                "<li><b>Reset to Defaults</b>: Resets all current settings to their application default values without saving.</li>"
                +
                "<li><b>Reload Profile</b>: Reloads settings from the profile file on disk, discarding any unsaved changes in the UI.</li>"
                +
                "<li><b>Refresh Profiles</b>: Synchronizes the profile list with the files currently available on disk.</li>"
                +
                "</ul>" +
                "<p>Profiles are stored as \".properties\" files within the node sub-directory of the configuration folder.</p>"
                +
                "</body></html>";

        JOptionPane.showMessageDialog(this, message, "About Configuration Profiles", JOptionPane.INFORMATION_MESSAGE);
    }

    private boolean hasUnsavedChanges() {
        for (PropertyRow row : allPropertyRows) {
            if (isRowDirty(row)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRowDirty(PropertyRow row) {
        String savedValue = savedProfile.getProperty(row.prop.getName());
        boolean isDefault = (savedValue == null);
        // 'current' here represents the effective value from the file, considering
        // defaults
        String saved = isDefault ? getSafeDefault(row.prop) : savedValue;
        if (saved == null)
            saved = "";
        saved = saved.trim();

        Supplier<String> supplier = valueSuppliers.get(row.prop.getName());
        String val = (supplier != null) ? supplier.get() : "";
        if (val == null)
            val = ""; // Ensure non-null for comparison

        boolean dirty = false;

        // Special handling for JCheckBox as its value is boolean, not string directly
        if (row.input instanceof JCheckBox) {
            boolean savedBool = "true".equalsIgnoreCase(saved) || "yes".equalsIgnoreCase(saved)
                    || "1".equals(saved) || "on".equalsIgnoreCase(saved);
            boolean valBool = "true".equalsIgnoreCase(val);
            return (savedBool != valBool);
        }

        // List property detection: either a JScrollPane or our special JPanel wrapper
        // for PK checks
        boolean isList = (row.input instanceof JScrollPane) ||
                (row.input instanceof JPanel && Props.NODE_PK_CHECKS.getName().equals(row.prop.getName()));

        if (isList) {
            String normalizedVal = normalizeListValue(val, "\n");
            String normalizedSaved = normalizeListValue(saved, ";");
            dirty = !normalizedVal.equals(normalizedSaved);
        } else {
            dirty = !val.trim().equals(saved.trim());
        }

        if (!dirty && Props.DB_URL.getName().equals(row.prop.getName())) {
            dirty = isComponentDirty(Props.DB_USERNAME) || isComponentDirty(Props.DB_PASSWORD);
        }

        return dirty;
    }

    private boolean isComponentDirty(Prop<?> prop) {
        String key = prop.getName();
        String saved = savedProfile.getProperty(key, getSafeDefault(prop));
        String current = valueSuppliers.get(key) != null ? valueSuppliers.get(key).get() : "";
        return !current.trim().equals(saved.trim());
    }

    private void updateAutoDbCheckboxesState() {
        if (linkedDbPanel == null || autoStartDbCheck == null || autoStopDbCheck == null) {
            return;
        }
        JComboBox<DatabaseConfigurationPanel.DatabaseEngine> engineCombo = (JComboBox<DatabaseConfigurationPanel.DatabaseEngine>) linkedDbPanel
                .getEngineCombo();
        DatabaseConfigurationPanel.DatabaseEngine selectedEngine = (DatabaseConfigurationPanel.DatabaseEngine) engineCombo
                .getSelectedItem();

        boolean enableCheckboxes = (selectedEngine != null
                && selectedEngine != DatabaseConfigurationPanel.DatabaseEngine.SQLITE);

        autoStartDbCheck.setEnabled(enableCheckboxes);
        autoStopDbCheck.setEnabled(enableCheckboxes);

        // If disabled (SQLite), ensure they are unchecked to avoid confusion.
        if (!enableCheckboxes) {
            boolean wasProgrammatic = isProgrammaticChange;
            isProgrammaticChange = true;
            autoStartDbCheck.setSelected(false);
            autoStopDbCheck.setSelected(false);
            isProgrammaticChange = wasProgrammatic;
        }
    }

    private void updateDirtyStatus() {
        boolean overallDirty = false;
        for (int i = 0; i < categoryTabbedPane.getTabCount(); i++) {
            boolean tabDirty = false;
            for (PropertyRow row : allPropertyRows) {
                if (row.tabIndex == i && isRowDirty(row)) {
                    tabDirty = true;
                    break;
                }
            }

            if (i == linkedProfilesTabIndex && !tabDirty) {
                tabDirty = isLinkedProfileDirty();
            }

            String title = categoryTabbedPane.getTitleAt(i);
            if (tabDirty) {
                if (!title.endsWith(" *"))
                    categoryTabbedPane.setTitleAt(i, title + " *");
                overallDirty = true;
            } else {
                if (title.endsWith(" *"))
                    categoryTabbedPane.setTitleAt(i, title.substring(0, title.length() - 2));
            }
        }
        saveProfileBtn.setText(overallDirty ? "Save Profile As *" : "Save Profile As");

        ConfigurationUtils.fixComponentSize(saveProfileBtn);
        if (saveProfileBtn.getParent() != null) {
            saveProfileBtn.getParent().revalidate();
        }
    }

    private boolean isLinkedProfileDirty() {
        return !savedLinkedLog.equals(getLinkedLoggingProfile()) ||
                !savedLinkedLaf.equals(getLinkedLafProfile()) ||
                !savedLinkedDb.equals(getLinkedDbProfile()) ||
                (autoStartDbCheck.isEnabled() && savedDbAutoStart != autoStartDbCheck.isSelected()) ||
                (autoStopDbCheck.isEnabled() && savedDbAutoStop != autoStopDbCheck.isSelected());
    }

    private void applyProfile() {
        String selected = (String) profileComboBox.getSelectedItem();
        if (selected == null)
            return;

        if (!checkUnsavedChangesAndProceed(null, null)) {
            return;
        }

        String message = "Apply profile '" + selected + "'?";
        Object[] options = { "Apply and Restart", "Apply for Next Startup", "Cancel" };
        int choice = JOptionPane.showOptionDialog(this,
                message,
                "Apply Profile",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);

        if (choice == 0 || choice == 1) {
            ConfigurationUtils.updateAppliedProfile(
                    ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.NODE_SUBFOLDER),
                    selected);
            this.activeProfileName = selected;
            updateProfileComboBoxColor();
            if (choice == 0 && restartAction != null) {
                restartAction.run();
            }
        }
    }

    private Path getProfileMetadataPath() {
        return PathUtils.resolvePath(confFolder).resolve(Signum.NODE_SUBFOLDER).resolve("profile.json");
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

            // Unwrap JPanel for PK checks to access the actual scroll pane
            if (comp instanceof JPanel && Props.NODE_PK_CHECKS.getName().equals(key)) {
                for (Component inner : ((JPanel) comp).getComponents()) {
                    if (inner instanceof JScrollPane) {
                        comp = (JComponent) inner;
                        break;
                    }
                }
            }

            if (comp instanceof JPanel && Props.DB_URL.getName().equals(key)) {
                String val = props.getProperty(key, defaultValues.get(key));
                String user = props.getProperty(Props.DB_USERNAME.getName(),
                        defaultValues.get(Props.DB_USERNAME.getName()));
                String pass = props.getProperty(Props.DB_PASSWORD.getName(),
                        defaultValues.get(Props.DB_PASSWORD.getName()));

                JdbcManualConfigurationPanel mp = (JdbcManualConfigurationPanel) comp.getClientProperty("manualPanel");
                if (mp != null) {
                    mp.updateFromUrl(val);
                    mp.setCredentials(user, pass);
                }
                JCheckBox useProfileCheck = (JCheckBox) comp.getClientProperty("useProfileCheck");
                if (useProfileCheck != null && useProfileCheck.isSelected()) {
                    useProfileCheck.setSelected(false);
                    JPanel cardPanel = (JPanel) comp.getClientProperty("cardPanel");
                    ((CardLayout) cardPanel.getLayout()).show(cardPanel, "MANUAL");
                }
                updateColor(comp, key, defaultValues.get(key));
                continue;
            }

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
        String current = savedProfile.getProperty(propName);
        if (current != null && !current.isEmpty()) {
            suggestions.add(current);
        }

        // TODO check this part (why don't use Signum constants and utility method for
        // this?)
        Path[] paths = {
                PathUtils.resolvePath(confFolder).resolve("node").resolve("node-default.properties"),
                PathUtils.resolvePath("conf/node/node-default.properties"),
                PathUtils.resolvePath("../conf/node/node-default.properties"),
                PathUtils.resolvePath("node/node/node-default.properties")
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

    public void loadAppliedProperties() {
        application.module.node.props.PropertyService service = application.module.node.Signum.getPropertyService();
        if (service == null)
            return;

        for (PropertyRow row : allPropertyRows) {
            String val = getServiceValueAsString(service, row.prop);
            if (val != null) {
                appliedProfile.setProperty(row.prop.getName(), val);
            }
        }

        // Special case for database credentials which are part of DB_URL composite
        // property
        String appliedUser = getServiceValueAsString(service, Props.DB_USERNAME);
        if (appliedUser != null && !appliedUser.isEmpty())
            appliedProfile.setProperty(Props.DB_USERNAME.getName(), appliedUser);

        String appliedPass = getServiceValueAsString(service, Props.DB_PASSWORD);
        if (appliedPass != null && !appliedPass.isEmpty())
            appliedProfile.setProperty(Props.DB_PASSWORD.getName(), appliedPass);

        refreshUIColors();
    }

    private String getServiceValueAsString(application.module.node.props.PropertyService service, Prop prop) {
        Object defaultValue = prop.getDefaultValue();
        if (defaultValue instanceof Boolean) {
            return String.valueOf(service.getBoolean(prop));
        } else if (defaultValue instanceof Integer) {
            return String.valueOf(service.getInt(prop));
        } else if (defaultValue instanceof List) {
            List<String> list = service.getStringList(prop);
            return list != null ? String.join(";", list) : "";
        }
        try {
            return service.getString(prop);
        } catch (Exception e) {
            return null;
        }
    }

    private void addSectionHeader(JPanel panel, String title, boolean isFirst) {
        JLabel label = new JLabel(title);
        label.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 14f));
        panel.add(label, (isFirst ? "" : "gaptop 15, ") + "span, growx, wrap, gapbottom 5");
    }

    private void addProperty(JPanel panel, Prop<?> prop, String labelText, String[] options, boolean editable) {
        // Label
        PropertyRow row = new PropertyRow(prop, labelText, panel, currentAddingTabIndex);
        JLabel label = new JLabel(labelText);
        row.label = label;
        row.labelConstraints = "align label";
        panel.add(label, row.labelConstraints);

        // Input Component
        String savedValue = savedProfile.getProperty(prop.getName());
        if (savedValue == null) {
            savedValue = getSafeDefault(prop);
        }
        defaultValues.put(prop.getName(), getSafeDefault(prop));

        JComponent inputComponent;

        if (prop.getDefaultValue() instanceof Boolean) {
            JCheckBox checkBox = new JCheckBox();
            boolean isSelected = "true".equalsIgnoreCase(savedValue) || "yes".equalsIgnoreCase(savedValue)
                    || "1".equals(savedValue) || "on".equalsIgnoreCase(savedValue);
            checkBox.setSelected(isSelected);
            inputComponent = checkBox;
            valueSuppliers.put(prop.getName(), () -> String.valueOf(checkBox.isSelected()));
            checkBox.addActionListener(e -> {
                if (isProgrammaticChange)
                    return;
                updateColor(checkBox, prop.getName(), getSafeDefault(prop));
                updateDirtyStatus();
            });
        } else if (options != null) {
            JComboBox<String> comboBox = new JComboBox<>(options);
            comboBox.setPrototypeDisplayValue("Prototype");
            comboBox.setSelectedItem(savedValue);
            comboBox.setEditable(editable);
            // If current value is not in options (e.g. custom), add it or handle gracefully
            if (comboBox.getSelectedItem() == null && savedValue != null) {
                comboBox.setEditable(true);
                comboBox.setSelectedItem(savedValue);
            }
            ConfigurationUtils.fixComponentSize(comboBox);
            inputComponent = comboBox;
            valueSuppliers.put(prop.getName(), () -> (String) comboBox.getSelectedItem());

            comboBox.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                        boolean isSelected, boolean cellHasFocus) {
                    Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    String savedVal = savedProfile.getProperty(prop.getName());
                    if (savedVal == null)
                        savedVal = getSafeDefault(prop);

                    String applied = appliedProfile.getProperty(prop.getName());
                    boolean hasApplied = appliedProfile.getProperties().containsKey(prop.getName());
                    if (applied == null)
                        applied = getSafeDefault(prop);

                    if (hasApplied && value != null && value.toString().trim().equals(applied.trim())) {
                        c.setForeground(GuiColors.getApplied());
                    } else if (value != null && value.toString().trim().equals(savedVal.trim())) {
                        c.setForeground(GuiColors.getSaved());
                    } else {
                        c.setForeground(GuiColors.getUnsaved());
                    }
                    return c;
                }
            });
            comboBox.addActionListener(e -> {
                if (isProgrammaticChange)
                    return;
                updateColor(comboBox, prop.getName(), getSafeDefault(prop));
                updateDirtyStatus();
            });
        } else {
            JTextField textField = new JTextField(savedValue);
            ConfigurationUtils.styleInputComponent(textField);

            // Fix dimensions to match standard text fields
            ConfigurationUtils.fixComponentSize(textField);

            inputComponent = textField;
            valueSuppliers.put(prop.getName(), textField::getText);
            textField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {

                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    if (isProgrammaticChange)
                        return;
                    SwingUtilities.invokeLater(() -> {
                        updateColor(textField, prop.getName(), getSafeDefault(prop));
                        updateDirtyStatus();
                    });
                }

                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    if (isProgrammaticChange)
                        return;
                    SwingUtilities.invokeLater(() -> {
                        updateColor(textField, prop.getName(), getSafeDefault(prop));
                        updateDirtyStatus();
                    });
                }

                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    if (isProgrammaticChange)
                        return;
                    SwingUtilities.invokeLater(() -> {
                        updateColor(textField, prop.getName(), getSafeDefault(prop));
                        updateDirtyStatus();
                    });
                }
            });
        }

        updateColor(inputComponent, prop.getName(), getSafeDefault(prop));
        if (inputComponent instanceof JCheckBox) {
            row.inputConstraints = "split 2, height pref!";
            panel.add(inputComponent, row.inputConstraints);
        } else {
            row.inputConstraints = "split 2, growx, height pref!";
            panel.add(inputComponent, row.inputConstraints);
        }
        propertyComponents.put(prop.getName(), inputComponent);

        // Help Button
        JButton helpBtn = new HelpButton();
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info");
        helpBtn.addActionListener(e ->

        showHelp(prop, labelText));

        row.input = inputComponent;
        row.help = helpBtn;
        row.helpConstraints = "wrap";
        row.separator = new JSeparator();
        row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";

        panel.add(helpBtn, row.helpConstraints);
        panel.add(row.separator, row.separatorConstraints);

        allPropertyRows.add(row);
    }

    private void parseJdbcUrl(String url, JComboBox<DatabaseConfigurationPanel.DatabaseEngine> engineCombo,
            JTextField hostField, JTextField portField, JTextField dbNameField, JTextField postfixField) {
        if (url == null || url.isEmpty())
            return;

        if (url.startsWith("jdbc:sqlite:")) {
            engineCombo.setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.SQLITE);
            hostField.setText("");
            portField.setText("");
            dbNameField.setText(url.substring("jdbc:sqlite:".length()));
            postfixField.setText("");
            return;
        }

        Matcher matcher = DatabaseConfigurationUtils.JDBC_URL_PATTERN.matcher(url);
        if (matcher.find()) {
            String engine = matcher.group(1);
            if ("mariadb".equalsIgnoreCase(engine)) {
                engineCombo.setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.MARIADB);
            } else if ("postgresql".equalsIgnoreCase(engine)) {
                engineCombo.setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.POSTGRESQL);
            }
            hostField.setText(matcher.group(2));
            portField.setText(matcher.group(3) != null ? matcher.group(3) : "");
            dbNameField.setText(matcher.group(4));
            postfixField.setText(matcher.group(5) != null ? matcher.group(5) : "");
        }
    }

    private String buildJdbcUrl(DatabaseConfigurationPanel.DatabaseEngine engine, String host, String port,
            String dbName, String postfix) {
        if (engine == DatabaseConfigurationPanel.DatabaseEngine.SQLITE) {
            return "jdbc:sqlite:" + dbName;
        }
        String protocol = engine == DatabaseConfigurationPanel.DatabaseEngine.MARIADB ? "mariadb"
                : (engine == DatabaseConfigurationPanel.DatabaseEngine.POSTGRESQL ? "postgresql" : "");
        StringBuilder sb = new StringBuilder("jdbc:").append(protocol).append("://").append(host);
        if (port != null && !port.trim().isEmpty()) {
            sb.append(":").append(port.trim());
        }
        sb.append("/").append(dbName);
        if (postfix != null && !postfix.trim().isEmpty()) {
            sb.append(postfix.trim());
        }
        return sb.toString();
    }

    private void addJdbcUrlProperty(JPanel panel, Prop<String> prop, String labelText) {
        PropertyRow row = new PropertyRow(prop, labelText, panel, currentAddingTabIndex);
        JLabel label = new JLabel(labelText);
        row.label = label;
        row.labelConstraints = "align label, aligny top";
        panel.add(label, row.labelConstraints);

        JPanel wrapper = new JPanel(new MigLayout("insets 0, fillx, gap 2", "[grow]", "[]5[]"));
        wrapper.setOpaque(false);

        JCheckBox useProfileCheck = new JCheckBox("Linked Database Profile");
        useProfileCheck.setOpaque(false);
        wrapper.add(useProfileCheck, "wrap");

        CardLayout cardLayout = new CardLayout();
        JPanel cardPanel = new JPanel(cardLayout);
        cardPanel.setOpaque(false);

        final boolean[] jdbcInitialized = { false };
        Runnable jdbcOnChange = () -> {
            if (jdbcInitialized[0]) {
                updateColor(wrapper, prop.getName(), defaultValues.get(prop.getName()));
                updateDirtyStatus();
            }
        };

        JdbcManualConfigurationPanel manualPanel = new JdbcManualConfigurationPanel(jdbcOnChange);
        JdbcProfileConfigurationPanel profilePanel = new JdbcProfileConfigurationPanel(confFolder, jdbcOnChange);

        cardPanel.add(manualPanel, "MANUAL");
        cardPanel.add(profilePanel, "PROFILE");
        wrapper.add(cardPanel, "growx");

        JComboBox<?> engineCombo = (JComboBox<?>) profilePanel.getEngineCombo();
        JComboBox<?> profileCombo = (JComboBox<?>) profilePanel.getProfileCombo();

        // Add sync listener to profile panel
        ActionListener profileSyncListener = e -> {
            if (isProgrammaticChange)
                return;
            String engine = engineCombo.getSelectedItem().toString();
            String profile = (String) profileCombo.getSelectedItem();
            if (useProfileCheck.isSelected() && profile != null) {
                setLinkedDbProfile(engine + ":" + profile);
            }
        };
        engineCombo.addActionListener(profileSyncListener);
        profileCombo.addActionListener(profileSyncListener);

        useProfileCheck.addActionListener(e -> {
            cardLayout.show(cardPanel, useProfileCheck.isSelected() ? "PROFILE" : "MANUAL");
            updateDirtyStatus();
            refreshUIColors();
        });

        wrapper.putClientProperty("manualPanel", manualPanel);
        wrapper.putClientProperty("profilePanel", profilePanel);
        wrapper.putClientProperty("cardPanel", cardPanel);
        wrapper.putClientProperty("useProfileCheck", useProfileCheck);

        // Register sub-components for coloring and dirty status tracking
        wrapper.putClientProperty("engineCombo", manualPanel.getEngineCombo());
        wrapper.putClientProperty("hostField", manualPanel.getHostField());
        wrapper.putClientProperty("portField", manualPanel.getPortField());
        wrapper.putClientProperty("dbNameField", manualPanel.getDbNameField());
        wrapper.putClientProperty("suffixField", manualPanel.getSuffixField());
        wrapper.putClientProperty("userField", manualPanel.getUserField());
        wrapper.putClientProperty("passField", manualPanel.getPassField());

        wrapper.putClientProperty("engineLabel", manualPanel.getEngineLabel());
        wrapper.putClientProperty("hostLabel", manualPanel.getHostLabel());
        wrapper.putClientProperty("portLabel", manualPanel.getPortLabel());
        wrapper.putClientProperty("dbNameLabel", manualPanel.getDbNameLabel());
        wrapper.putClientProperty("suffixLabel", manualPanel.getSuffixLabel());
        wrapper.putClientProperty("userLabel", manualPanel.getUserLabel());
        wrapper.putClientProperty("passLabel", manualPanel.getPassLabel());

        // Register profile panel components for sub-coloring
        wrapper.putClientProperty("pEngineCombo", profilePanel.getEngineCombo());
        wrapper.putClientProperty("pProfileCombo", profilePanel.getProfileCombo());
        wrapper.putClientProperty("pDbCombo", profilePanel.getDbCombo());
        wrapper.putClientProperty("pHostCombo", profilePanel.getHostField());
        wrapper.putClientProperty("pPortField", profilePanel.getPortField());
        wrapper.putClientProperty("pSuffixField", profilePanel.getSuffixField());
        wrapper.putClientProperty("pUserCombo", profilePanel.getUserCombo());
        wrapper.putClientProperty("pPassField", profilePanel.getPassField());
        wrapper.putClientProperty("pEngineLabel", profilePanel.getEngineLabel());
        wrapper.putClientProperty("pProfileLabel", profilePanel.getProfileLabel());
        wrapper.putClientProperty("pDbLabel", profilePanel.getDbLabel());
        wrapper.putClientProperty("pHostLabel", profilePanel.getHostLabel());
        wrapper.putClientProperty("pPortLabel", profilePanel.getPortLabel());
        wrapper.putClientProperty("pSuffixLabel", profilePanel.getSuffixLabel());
        wrapper.putClientProperty("pUserLabel", profilePanel.getUserLabel());
        wrapper.putClientProperty("pPassLabel", profilePanel.getPassLabel());

        wrapper.putClientProperty("resultLabel", manualPanel.getResultField());

        // Populate valueSuppliers and propertyComponents for DB_URL, DB_USERNAME,
        // DB_PASSWORD early
        // to prevent NullPointerException when manualPanel.updateFromUrl/setCredentials
        // trigger the onChange callback, which calls updateColor.
        valueSuppliers.put(Props.DB_USERNAME.getName(),
                () -> useProfileCheck.isSelected() ? profilePanel.getUsername() : manualPanel.getUsername());
        valueSuppliers.put(Props.DB_PASSWORD.getName(),
                () -> useProfileCheck.isSelected() ? profilePanel.getPassword() : manualPanel.getPassword());
        valueSuppliers.put(prop.getName(), // This is Props.DB_URL
                () -> useProfileCheck.isSelected() ? profilePanel.getJdbcUrl() : manualPanel.getJdbcUrl());

        // Register separate components for DB_USERNAME and DB_PASSWORD
        propertyComponents.put(Props.DB_USERNAME.getName(), (JComponent) manualPanel.getUserField());
        propertyComponents.put(Props.DB_PASSWORD.getName(), (JComponent) manualPanel.getPassField());

        String savedValue = savedProfile.getProperty(prop.getName(), getSafeDefault(prop));
        String savedUser = savedProfile.getProperty(Props.DB_USERNAME.getName(),
                getSafeDefault(Props.DB_USERNAME));
        String savedPass = savedProfile.getProperty(Props.DB_PASSWORD.getName(),
                getSafeDefault(Props.DB_PASSWORD));

        jdbcInitialized[0] = true;
        manualPanel.updateFromUrl(savedValue);
        manualPanel.setCredentials(savedUser, savedPass);

        defaultValues.put(prop.getName(), getSafeDefault(prop));
        defaultValues.put(Props.DB_USERNAME.getName(), getSafeDefault(Props.DB_USERNAME));
        defaultValues.put(Props.DB_PASSWORD.getName(), getSafeDefault(Props.DB_PASSWORD));

        row.input = wrapper;
        row.inputConstraints = "split 2, growx";
        panel.add(wrapper, row.inputConstraints);
        propertyComponents.put(prop.getName(), wrapper);

        JButton helpBtn = new HelpButton();
        helpBtn.addActionListener(e -> showHelp(prop, labelText));
        row.help = helpBtn;
        row.helpConstraints = "wrap, aligny top";
        panel.add(helpBtn, row.helpConstraints);
        row.separator = new JSeparator();
        row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";
        panel.add(row.separator, row.separatorConstraints);
        allPropertyRows.add(row);
        updateColor(wrapper, prop.getName(), defaultValues.get(prop.getName()));
    }

    private void addPasswordProperty(JPanel panel, Prop<String> prop, String labelText) {
        // Label
        PropertyRow row = new PropertyRow(prop, labelText, panel, currentAddingTabIndex);
        JLabel label = new JLabel(labelText);
        row.label = label;
        row.labelConstraints = "align label";
        panel.add(label, row.labelConstraints);

        // Input Component
        String savedValue = savedProfile.getProperty(prop.getName());
        if (savedValue == null) {
            savedValue = getSafeDefault(prop);
        }
        defaultValues.put(prop.getName(), getSafeDefault(prop));
        JPasswordField passwordField = new JPasswordField(savedValue);
        passwordField.setColumns(20);
        ConfigurationUtils.styleInputComponent(passwordField);

        // Fix dimensions to match standard text fields (consistent with addProperty)
        ConfigurationUtils.fixComponentSize(passwordField);

        char defaultEchoChar = passwordField.getEchoChar();

        passwordField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                if (isProgrammaticChange)
                    return;
                SwingUtilities.invokeLater(() -> {
                    updateColor(passwordField, prop.getName(), getSafeDefault(prop));
                    updateDirtyStatus();
                });
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                if (isProgrammaticChange)
                    return;
                SwingUtilities.invokeLater(() -> {
                    updateColor(passwordField, prop.getName(), getSafeDefault(prop));
                    updateDirtyStatus();
                });
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                if (isProgrammaticChange)
                    return;
                SwingUtilities.invokeLater(() -> {
                    updateColor(passwordField, prop.getName(), getSafeDefault(prop));
                    updateDirtyStatus();
                });
            }
        });
        updateColor(passwordField, prop.getName(), getSafeDefault(prop));
        row.inputConstraints = "split 2, growx, height pref!";
        panel.add(passwordField, row.inputConstraints);

        // Help Button
        JButton helpBtn = new HelpButton();
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info");
        helpBtn.addActionListener(e -> showHelp(prop, labelText));
        row.help = helpBtn;
        row.helpConstraints = "wrap";
        panel.add(helpBtn, row.helpConstraints);

        // Show/Hide Checkbox
        JCheckBox showPass = new JCheckBox("Show Password");
        showPass.addActionListener(e -> {
            passwordField.setEchoChar(showPass.isSelected() ? (char) 0 : defaultEchoChar);
        });
        row.extra = showPass;
        row.extraConstraints = "skip 1, wrap";
        panel.add(showPass, row.extraConstraints);

        row.separator = new JSeparator();
        row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";
        panel.add(row.separator, row.separatorConstraints);

        valueSuppliers.put(prop.getName(), () -> new String(passwordField.getPassword()));
        propertyComponents.put(prop.getName(), passwordField);

        row.input = passwordField;
        allPropertyRows.add(row);
    }

    private void addLinkedProfileRow(JPanel panel, String labelText, JComboBox<String> combo) {
        panel.add(new JLabel(labelText), "align label");
        ConfigurationUtils.fixComponentSize(combo);
        panel.add(combo, "split 2, growx, height pref!");

        combo.addActionListener(e -> {
            if (isProgrammaticChange)
                return;
            updateDirtyStatus();
        });

        JButton helpBtn = new HelpButton();
        panel.add(helpBtn, "wrap");
        panel.add(new JSeparator(), "span, growx, wrap, gaptop 2, gapbottom 2");
    }

    private void addLinkedProfileRowWithButtons(JPanel panel, String labelText, JComboBox<String> combo, String type) {
        panel.add(new JLabel(labelText), "align label");
        ConfigurationUtils.fixComponentSize(combo);

        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                    boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (isSelected || value == null)
                    return c;

                String val = value.toString();
                String linked = KEY_LOGGING.equals(type) ? getLinkedLoggingProfile() : getLinkedLafProfile();
                String active = KEY_LOGGING.equals(type) ? Signum.getActiveLoggingProfile() : "gui";

                if (val.equals(linked)) {
                    c.setForeground(GuiColors.getSaved());
                } else if (val.equals(active)) {
                    c.setForeground(GuiColors.getApplied());
                }
                return c;
            }
        });

        combo.addActionListener(e -> {
            if (!isProgrammaticChange) {
                updateDirtyStatus();
            }
        });

        JButton refreshBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.REFRESH, GuiConstants.getHelpIconSize(),
                GuiColors.getApplied()));
        refreshBtn.setToolTipText("Update link in profile immediately");
        refreshBtn.addActionListener(e -> {
            saveProfileLinks(loadedProfileName);
            updateDirtyStatus();
            combo.repaint();
        });

        JButton deleteBtn = new JButton(
                IconFontSwing.buildIcon(FontAwesome.TRASH, GuiConstants.getHelpIconSize(), GuiColors.getContrastRed()));
        deleteBtn.setToolTipText("Remove link");
        deleteBtn.addActionListener(e -> {
            isProgrammaticChange = true;
            combo.setSelectedItem("");
            isProgrammaticChange = false;
            saveProfileLinks(loadedProfileName);
            updateDirtyStatus();
        });

        JPanel comboPanel = new JPanel(new MigLayout("insets 0", "[grow][pref!][pref!]", "[]"));
        comboPanel.setOpaque(false);
        comboPanel.add(combo, "growx");
        comboPanel.add(refreshBtn);
        comboPanel.add(deleteBtn);

        panel.add(comboPanel, "split 2, growx, height pref!");
        panel.add(new HelpButton(), "wrap");
        panel.add(new JSeparator(), "span, growx, wrap, gaptop 2, gapbottom 2");
    }

    private void syncDbProfileSelection(String value) {
        JComponent jdbcComp = propertyComponents.get(Props.DB_URL.getName());
        if (jdbcComp != null) {
            JdbcProfileConfigurationPanel pp = (JdbcProfileConfigurationPanel) jdbcComp
                    .getClientProperty("profilePanel");
            JCheckBox cb = (JCheckBox) jdbcComp.getClientProperty("useProfileCheck");
            if (pp != null && value != null && value.contains(":")) {
                String[] parts = value.split(":");
                isProgrammaticChange = true;
                ((JComboBox<?>) pp.getEngineCombo())
                        .setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.fromDisplayName(parts[0]));
                ((JComboBox<?>) pp.getProfileCombo()).setSelectedItem(parts[1]);
                if (cb != null)
                    cb.setSelected(true);
                isProgrammaticChange = false;
            }
        }
    }

    public String getLinkedDbProfile() {
        if (linkedDbPanel == null)
            return "";
        Object engine = ((JComboBox<?>) linkedDbPanel.getEngineCombo()).getSelectedItem();
        Object profile = ((JComboBox<?>) linkedDbPanel.getProfileCombo()).getSelectedItem();
        if (engine == null || profile == null || profile.toString().isEmpty())
            return "";
        return engine.toString() + ":" + profile.toString();
    }

    public String getLinkedLoggingProfile() {
        String sel = (linkedLogCombo != null) ? (String) linkedLogCombo.getSelectedItem() : "";
        return sel != null ? sel : "";
    }

    public String getLinkedLafProfile() {
        String sel = (linkedLafCombo != null) ? (String) linkedLafCombo.getSelectedItem() : "";
        return sel != null ? sel : "";
    }

    public void setLinkedDbProfile(String value) {
        isProgrammaticChange = true;
        if (linkedDbPanel != null) {
            if (value == null || value.isEmpty()) {
                ((JComboBox<?>) linkedDbPanel.getProfileCombo()).setSelectedItem("");
            } else if (value.contains(":")) {
                String[] parts = value.split(":");
                ((JComboBox<DatabaseConfigurationPanel.DatabaseEngine>) linkedDbPanel.getEngineCombo())
                        .setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.fromDisplayName(parts[0]));
                ((JComboBox<String>) linkedDbPanel.getProfileCombo()).setSelectedItem(parts[1]);
            }
            syncDbProfileSelection(value);
        }
        isProgrammaticChange = false;
        saveProfileLinks(loadedProfileName);
    }

    public void setLinkedLoggingProfile(String value) {
        isProgrammaticChange = true;
        linkedLogCombo.setSelectedItem(value != null ? value : "");
        isProgrammaticChange = false;
        saveProfileLinks(loadedProfileName);
    }

    public void setLinkedLafProfile(String value) {
        isProgrammaticChange = true;
        linkedLafCombo.setSelectedItem(value != null ? value : "");
        isProgrammaticChange = false;
        saveProfileLinks(loadedProfileName);
    }

    private void addListProperty(JPanel panel, Prop<?> prop, String labelText) {
        PropertyRow row = new PropertyRow(prop, labelText, panel, currentAddingTabIndex);
        JLabel label = new JLabel(labelText);
        row.label = label;
        row.labelConstraints = "align label, aligny top";
        panel.add(label, row.labelConstraints);

        String savedValue = savedProfile.getProperty(prop.getName());
        if (savedValue == null) {
            savedValue = getSafeDefault(prop);
        }
        defaultValues.put(prop.getName(), normalizeListValue(getSafeDefault(prop), ";"));
        // Split by semicolon and join with newlines for display
        String[] items = savedValue.split(";");
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
                if (isProgrammaticChange)
                    return;
                SwingUtilities.invokeLater(() -> {
                    updateColor(textArea, prop.getName(), getSafeDefault(prop));
                    updateDirtyStatus();
                });
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                if (isProgrammaticChange)
                    return;
                SwingUtilities.invokeLater(() -> {
                    updateColor(textArea, prop.getName(), getSafeDefault(prop));
                    updateDirtyStatus();
                });
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                if (isProgrammaticChange)
                    return;
                SwingUtilities.invokeLater(() -> {
                    updateColor(textArea, prop.getName(), getSafeDefault(prop));
                    updateDirtyStatus();
                });
            }
        });
        updateColor(textArea, prop.getName(), getSafeDefault(prop));

        boolean isPkCheck = Props.NODE_PK_CHECKS.getName().equals(prop.getName());
        if (isPkCheck) {
            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.setOpaque(false);
            wrapper.add(scrollPane, BorderLayout.CENTER);

            JButton convertBtn = new JButton(
                    IconFontSwing.buildIcon(FontAwesome.MAGIC, GuiConstants.getHelpIconSize(),
                            GuiColors.getHelpIcon()));
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

            row.inputConstraints = "split 2, growx, hmin 80";
            panel.add(wrapper, row.inputConstraints);
            propertyComponents.put(prop.getName(), wrapper); // Store wrapper for visibility handling
        } else {
            row.inputConstraints = "split 2, growx, hmin 80";
            panel.add(scrollPane, row.inputConstraints);
            propertyComponents.put(prop.getName(), scrollPane);
        }

        // Help Button
        JButton helpBtn = new HelpButton();
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info");
        helpBtn.addActionListener(e -> showHelp(prop, labelText));
        row.help = helpBtn;
        row.helpConstraints = "wrap, aligny top";
        panel.add(helpBtn, row.helpConstraints);
        row.separator = new JSeparator();
        row.separatorConstraints = "span, growx, wrap, gaptop 2, gapbottom 2";
        panel.add(row.separator, row.separatorConstraints);

        row.input = propertyComponents.get(prop.getName());
        allPropertyRows.add(row);

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

    private String getSafeDefault(Prop<?> prop) {
        Object def = prop.getDefaultValue();
        return def == null ? "" : String.valueOf(def);
    }

    private void updateColor(JComponent comp, String propName, String defaultValue) {
        String value = "";
        JComponent target = comp;
        Color color = GuiColors.getUnsaved();

        // Unwrap wrappers for lists and special components
        if (comp instanceof JScrollPane && ((JScrollPane) comp).getViewport().getView() instanceof JTextArea) {
            target = (JComponent) ((JScrollPane) comp).getViewport().getView();
        } else if (comp instanceof JPanel && Props.NODE_PK_CHECKS.getName().equals(propName)) {
            for (Component inner : ((JPanel) comp).getComponents()) {
                if (inner instanceof JScrollPane) {
                    Component view = ((JScrollPane) inner).getViewport().getView();
                    if (view instanceof JTextArea) {
                        target = (JComponent) view;
                        break;
                    }
                }
            }
        }

        if (comp instanceof JPanel && Props.DB_URL.getName().equals(propName)) {
            JComponent resultComp = (JComponent) comp.getClientProperty("resultLabel");
            target = resultComp != null ? resultComp : target;
            Supplier<String> supplier = valueSuppliers.get(propName);
            String supplierVal = supplier != null ? supplier.get() : "";
            value = supplierVal != null ? supplierVal.trim() : "";
        }

        String savedValue = savedProfile.getProperty(propName, defaultValue);
        String applied = appliedProfile.getProperty(propName, defaultValue);

        if (savedValue == null)
            savedValue = "";
        if (applied == null)
            applied = "";
        savedValue = savedValue.trim();
        applied = applied.trim();

        if (comp instanceof JCheckBox) {
            value = String.valueOf(((JCheckBox) comp).isSelected());
            boolean savedBool = "true".equalsIgnoreCase(savedValue) || "yes".equalsIgnoreCase(savedValue)
                    || "1".equals(savedValue)
                    || "on".equalsIgnoreCase(savedValue);
            boolean appliedBool = "true".equalsIgnoreCase(applied) || "yes".equalsIgnoreCase(applied)
                    || "1".equals(applied) || "on".equalsIgnoreCase(applied);
            boolean valBool = "true".equalsIgnoreCase(value);

            if (valBool == appliedBool)
                color = GuiColors.getApplied();
            else if (valBool == savedBool)
                color = GuiColors.getSaved();
            else
                color = GuiColors.getUnsaved();
        } else if (comp instanceof JComboBox) {
            Object item = ((JComboBox<?>) comp).getSelectedItem();
            value = item == null ? "" : item.toString();
            if (((JComboBox<?>) comp).isEditable()) {
                target = (JComponent) ((JComboBox<?>) comp).getEditor().getEditorComponent();
            }
        } else if (target instanceof javax.swing.text.JTextComponent) {
            value = ((javax.swing.text.JTextComponent) target).getText();
            if (target instanceof JTextArea) {
                value = normalizeListValue(value, "\n");
                savedValue = normalizeListValue(savedValue, ";");
                applied = normalizeListValue(applied, ";");
            } else {
                value = value.trim();
                savedValue = savedValue.trim();
                applied = applied.trim();
            }
        }

        if (!(comp instanceof JCheckBox)) {
            if (value.trim().equals(applied)) {
                color = GuiColors.getApplied();
            } else if (value.trim().equals(savedValue)) {
                color = GuiColors.getSaved();
            }
        }

        // Detailed sub-component coloring and asterisk logic for JDBC URL panel
        if (comp instanceof JPanel && Props.DB_URL.getName().equals(propName)) {
            updateJdbcSubComponents(comp, savedValue, applied);
        }

        PropertyRow row = allPropertyRows.stream()
                .filter(r -> r.prop != null && r.prop.getName().equals(propName))
                .findFirst()
                .orElse(null);
        if (row != null && row.label != null) {
            boolean isDirty;
            if (comp instanceof JCheckBox) {
                boolean savedBool = "true".equalsIgnoreCase(savedValue) || "yes".equalsIgnoreCase(savedValue)
                        || "1".equals(savedValue) || "on".equalsIgnoreCase(savedValue);
                boolean valBool = "true".equalsIgnoreCase(value);
                isDirty = (valBool != savedBool);
            } else {
                isDirty = !value.equals(savedValue);
            }
            row.label.setText(isDirty ? row.labelText + " *" : row.labelText);
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

    private void updateJdbcSubComponents(JComponent panel, String savedUrl, String appliedUrl) {
        JCheckBox useProfileCheck = (JCheckBox) panel.getClientProperty("useProfileCheck");
        boolean useProfile = useProfileCheck != null && useProfileCheck.isSelected();

        JComponent engineCombo = (JComponent) panel.getClientProperty(useProfile ? "pEngineCombo" : "engineCombo");
        JComponent hostField = (JComponent) panel.getClientProperty(useProfile ? "pHostCombo" : "hostField");
        JComponent portField = (JComponent) panel.getClientProperty(useProfile ? "pPortField" : "portField");
        JComponent dbNameField = (JComponent) panel.getClientProperty(useProfile ? "pDbCombo" : "dbNameField");
        JComponent suffixField = (JComponent) panel.getClientProperty(useProfile ? "pSuffixField" : "suffixField");
        JComponent userField = (JComponent) panel.getClientProperty(useProfile ? "pUserCombo" : "userField");
        JComponent passField = (JComponent) panel.getClientProperty(useProfile ? "pPassField" : "passField");

        JLabel engineLabel = (JLabel) panel.getClientProperty(useProfile ? "pEngineLabel" : "engineLabel");
        JLabel hostLabel = (JLabel) panel.getClientProperty(useProfile ? "pHostLabel" : "hostLabel");
        JLabel portLabel = (JLabel) panel.getClientProperty(useProfile ? "pPortLabel" : "portLabel");
        JLabel dbNameLabel = (JLabel) panel.getClientProperty(useProfile ? "pDbLabel" : "dbLabel");
        JLabel suffixLabel = (JLabel) panel.getClientProperty(useProfile ? "pSuffixLabel" : "suffixLabel");
        JLabel userLabel = (JLabel) panel.getClientProperty(useProfile ? "pUserLabel" : "userLabel");
        JLabel passLabel = (JLabel) panel.getClientProperty(useProfile ? "pPassLabel" : "passLabel");

        Map<String, String> savedParts = getJdbcUrlParts(savedUrl);
        Map<String, String> appliedParts = getJdbcUrlParts(appliedUrl);

        String engineVal = "";
        if (engineCombo instanceof JComboBox) {
            Object sel = ((JComboBox<?>) engineCombo).getSelectedItem();
            engineVal = sel != null ? sel.toString() : "";
        }
        updateJdbcPart(engineCombo, engineLabel, "Engine:", engineVal,
                savedParts.get("engine"), appliedParts.get("engine"));

        String hostVal = getCompValue(hostField);
        updateJdbcPart(hostField, hostLabel, "Host:", hostVal, savedParts.get("host"), appliedParts.get("host"));

        String portVal = getCompValue(portField);
        updateJdbcPart(portField, portLabel, "Port:", portVal, savedParts.get("port"), appliedParts.get("port"));

        String dbVal = getCompValue(dbNameField);
        updateJdbcPart(dbNameField, dbNameLabel, "Database:", dbVal, savedParts.get("dbName"),
                appliedParts.get("dbName"));

        String suffixVal = getCompValue(suffixField);
        updateJdbcPart(suffixField, suffixLabel, "Suffix:", suffixVal, savedParts.get("suffix"),
                appliedParts.get("suffix"));

        String userKey = Props.DB_USERNAME.getName();
        String passKey = Props.DB_PASSWORD.getName();

        String userVal = getCompValue(userField);
        updateJdbcPart(userField, userLabel, "Username:", userVal,
                savedProfile.getProperty(userKey, getSafeDefault(Props.DB_USERNAME)),
                appliedProfile.getProperty(userKey, getSafeDefault(Props.DB_USERNAME)));

        String passVal = "";
        if (passField instanceof JPasswordField) {
            passVal = new String(((JPasswordField) passField).getPassword());
        }
        updateJdbcPart(passField, passLabel, "Password:", passVal,
                savedProfile.getProperty(passKey, getSafeDefault(Props.DB_PASSWORD)),
                appliedProfile.getProperty(passKey, getSafeDefault(Props.DB_PASSWORD)));
    }

    private String getCompValue(JComponent comp) {
        if (comp instanceof javax.swing.text.JTextComponent)
            return ((javax.swing.text.JTextComponent) comp).getText();
        if (comp instanceof JComboBox) {
            Object sel = ((JComboBox<?>) comp).getSelectedItem();
            return sel != null ? sel.toString() : "";
        }
        return "";
    }

    private void updateJdbcPart(JComponent input, JLabel label, String baseText, String current, String saved,
            String applied) {
        if (input == null)
            return;
        current = current != null ? current.trim() : "";
        saved = saved != null ? saved.trim() : "";
        applied = applied != null ? applied.trim() : "";

        Color c;
        if (current.equals(applied))
            c = GuiColors.getApplied();
        else if (current.equals(saved))
            c = GuiColors.getSaved();
        else
            c = GuiColors.getUnsaved();

        input.setForeground(c);
        if (label != null) {
            label.setText(current.equals(saved) ? baseText : baseText + " *");
        }
    }

    private Map<String, String> getJdbcUrlParts(String url) {
        Map<String, String> parts = new HashMap<>();
        parts.put("engine", "");
        parts.put("host", "");
        parts.put("port", "");
        parts.put("dbName", "");
        parts.put("suffix", "");
        if (url == null || url.isEmpty())
            return parts;
        if (url.startsWith("jdbc:sqlite:")) {
            parts.put("engine", "SQLite");
            parts.put("dbName", url.substring("jdbc:sqlite:".length()));
        } else {
            Matcher matcher = DatabaseConfigurationUtils.JDBC_URL_PATTERN.matcher(url);
            if (matcher.find()) {
                String proto = matcher.group(1);
                if ("mariadb".equalsIgnoreCase(proto))
                    parts.put("engine", "MariaDB");
                else if ("postgresql".equalsIgnoreCase(proto))
                    parts.put("engine", "PostgreSQL");
                parts.put("host", matcher.group(2));
                parts.put("port", matcher.group(3) != null ? matcher.group(3) : "");
                parts.put("dbName", matcher.group(4));
                parts.put("suffix", matcher.group(5) != null ? matcher.group(5) : "");
            }
        }
        return parts;
    }

    private JPanel createLegendPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        panel.setBorder(new EmptyBorder(0, 0, 5, 0));

        panel.add(createLegendItem(GuiColors.getUnsaved(), "Unsaved values"));
        panel.add(createLegendItem(GuiColors.getSaved(), "Saved values"));
        panel.add(createLegendItem(GuiColors.getApplied(), "Applied values"));

        JButton helpBtn = new HelpButton();
        helpBtn.setToolTipText("Detailed Color Legend");
        helpBtn.addActionListener(e -> showColorLegendHelp());
        panel.add(helpBtn);

        return panel;
    }

    private void showColorLegendHelp() {
        String msg = "<html><body style='width: 350px'>" +
                "<h3>Color Coding Legend</h3>" +
                "<p>The configuration values are color-coded to indicate their current status:</p>" +
                "<ul>" +
                "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getUnsaved())
                + "'>\u25A0 Unsaved Values:</font></b> " +
                "These values have been modified in the UI but have not yet been saved to the configuration file. " +
                "Properties with unsaved changes are marked with an asterisk (*).</li>" +
                "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getSaved())
                + "'>\u25A0 Saved Values:</font></b> " +
                "These values are saved in the currently loaded profile on disk, but they differ from the values " +
                "currently being used by the running node.</li>" +
                "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getApplied())
                + "'>\u25A0 Applied Values:</font></b> " +
                "These values match exactly what the node is currently using. Note that most changes require a restart to take effect.</li>"
                +
                "</ul>" +
                "</body></html>";
        JOptionPane.showMessageDialog(this, msg, "Color Legend", JOptionPane.INFORMATION_MESSAGE);
    }

    private JPanel createLegendItem(Color color, String text) {
        JPanel item = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JLabel colorBox = new JLabel("\u25A0");
        colorBox.setForeground(color);
        item.add(colorBox);
        item.add(new JLabel(text));
        return item;
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
        if ("node-default".equals(loadedProfileName))
            return;

        Properties propsToSave = getPropertiesFromUI();
        Path targetFile = ConfigurationUtils.resolveProfilePath(confFolder, "node", loadedProfileName + ".properties");

        try {
            ConfigurationUtils.savePropertiesPreservingFormat(targetFile, propsToSave, propertyComponents.keySet());
            // After saving, update savedProfile to reflect the new saved state
            savedProfile.setProperties(propsToSave);
            updateDirtyStatus();
            refreshUIColors();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error saving properties: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
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
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file."
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

        helpTexts.put(Props.DB_ARCHIVAL_MODE.getName(),
                "Sets the database maintenance and history mode:<br/>"
                        + "<ul>"
                        + "<li><b>ARCHIVE:</b> Full database, no trimming or pruning. Keeps every piece of history and derived data. Uses the most disk space.</li>"
                        + "<li><b>TRIM:</b> (Default) Periodically prunes derived tables (like account balances history) to save space, but <b>keeps all blocks and transactions</b>. The node remains archival.</li>"
                        + "<li><b>PRUNE:</b> Trims derived tables AND physically deletes old blocks and transactions beyond the safe rollback limit ("
                        + Constants.MAX_ROLLBACK
                        + " blocks). ⚠️ The node becomes non-archival and cannot serve old history to peers.</li>"
                        + "</ul>");

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

        helpTexts.put(Props.NODE_BLOCK_CACHE_MB.getName(),
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
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file."
                        + "<br>This helps the node to quickly find other peers and join the network.");

        helpTexts.put(Props.P2P_REBROADCAST_TO.getName(),
                "A list of peers to which this node will always rebroadcast transactions."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (<code>;</code>)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file."
                        + "<br>Useful for ensuring transactions reach specific nodes (e.g., pools or exchanges).");

        helpTexts.put(Props.P2P_NUM_BOOTSTRAP_CONNECTIONS.getName(),
                "The number of bootstrap peers to connect to when the node starts."
                        + "<br>Increasing this may help with initial connectivity but increases startup load.");

        helpTexts.put(Props.P2P_BLACKLISTED_PEERS.getName(),
                "A list of peer addresses that are permanently banned from connecting to your node."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (<code>;</code>)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file.");

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
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file."
                        + "<br>This allows miners to use the 'submitNonce' API without sending their secret phrase over the network."
                        + "<br><b>Security Warning:</b> Do not use on public-facing nodes or nodes accessible by others, as it stores secret phrases in the configuration file.");

        helpTexts.put(Props.REWARD_RECIPIENT_PASSPHRASES.getName(),
                "A list of passphrases for reward recipient accounts, used in pool mining."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (<code>;</code>)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file."
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
                        + "<br>Use with caution."
                        + "<br><br><b>Enabled Features:</b>"
                        + "<ul>"
                        + "<li><b>Time Tracking:</b> Displays <b>Total Time</b> (time since node start) and <b>Sync In Progress</b> (time spent actively syncing blocks) in the footer.</li>"
                        + "</ul>");

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

        helpTexts.put(Props.POP_OFF_SKIP_DB_CHECK.getName(),
                "If enabled, skips the database consistency check after each block is removed during a manual pop-off operation."
                        + "<br><b>Enabling this significantly speeds up large pop-offs</b> because the node won't re-calculate the entire database state for every single block removed."
                        + "<br><br><b>Warning:</b> This risks leaving the database in an inconsistent state if an error occurs during the process.");

        helpTexts.put(Props.AUTO_CONSISTENCY_RESOLVE_ENABLED.getName(),
                "Enables automatic resolution of database inconsistencies at startup.");

        helpTexts.put(Props.INDIRECT_INCOMING_SERVICE_ENABLE.getName(),
                "Enables the service to track indirect incoming payments (e.g., from multi-out transactions).");

        helpTexts.put(Props.NODE_AT_PROCESSOR_CACHE_BLOCK_COUNT.getName(),
                "The number of blocks to cache for the Automated Transaction (AT) processor."
                        + "<br>A larger cache can improve AT execution performance but uses more memory.");

        helpTexts.put(Props.NODE_SHUTDOWN_TIMEOUT.getName(),
                "The maximum time in seconds to wait for a graceful shutdown before forcing exit.");

        helpTexts.put(Props.NODE_CHECKPOINT_HEIGHT.getName(),
                "The block height of a known valid checkpoint. Used to verify chain integrity during sync."
                        + "<br>Set to -1 to disable checkpoint verification and check the entire chain from genesis.");

        helpTexts.put(Props.NODE_CHECKPOINT_HASH.getName(),
                "The hash of the checkpoint block.");

        helpTexts.put(Props.NODE_PK_CHECKS.getName(),
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

        helpTexts.put(Props.NODE_TEST_UNCONFIRMED_TRANSACTIONS.getName(),
                "Developer setting to test unconfirmed transaction handling."
                        + "<br>Should not be enabled for normal operation.");

        helpTexts.put(Props.DEV_DUMP_PEERS_VERSION.getName(),
                "Dumps the versions of connected peers to the log on exit.");

        helpTexts.put(Props.NODE_DEBUG_TRACE_ENABLED.getName(),
                "Enables debug tracing for detailed logging.");

        helpTexts.put(Props.NODE_DEBUG_TRACE_QUOTE.getName(),
                "The quote character used in debug trace logs.");

        helpTexts.put(Props.NODE_DEBUG_TRACE_SEPARATOR.getName(),
                "The separator character used in debug trace logs.");

        helpTexts.put(Props.NODE_DEBUG_LOG_CONFIRMED.getName(),
                "Logs confirmed transactions for debugging.");

        helpTexts.put(Props.NODE_DEBUG_TRACE_ACCOUNTS.getName(),
                "A list of account IDs to trace in debug logs."
                        + "<br>In this field, you can list entries on new lines, or on a single line separated by semicolons (<code>;</code>)."
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file.");

        helpTexts.put(Props.NODE_DEBUG_TRACE_LOG.getName(),
                "The file path for the debug trace log.");

        helpTexts.put(Props.NODE_COMMUNICATION_LOGGING_MASK.getName(),
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
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file.");

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
                        + "<br>The configuration is stored as a single semicolon-separated list in the configuration file.");

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

    private static class PropertyRow {
        final Prop<?> prop;
        final String labelText;
        final JPanel originalParent;
        final int tabIndex;

        JLabel label;
        String labelConstraints;

        JComponent input;
        String inputConstraints;

        JComponent extra; // Checkbox, MagicWand, etc.
        String extraConstraints;

        JButton help;
        String helpConstraints;

        JSeparator separator;
        String separatorConstraints;

        PropertyRow(Prop<?> prop, String labelText, JPanel originalParent, int tabIndex) {
            this.prop = prop;
            this.labelText = labelText;
            this.originalParent = originalParent;
            this.tabIndex = tabIndex;
        }
    }
}