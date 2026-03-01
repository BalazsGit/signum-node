package brs.gui;

import brs.Signum;
import brs.props.Prop;
import brs.props.Props;
import brs.util.PathUtils;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;

public class NodeConfigurationPanel extends JPanel {

    private final Properties currentProperties;
    private final Map<String, Supplier<String>> valueSuppliers = new HashMap<>();
    private final Map<String, JComponent> propertyComponents = new HashMap<>();
    private final Map<String, String> helpTexts = new HashMap<>();
    private final Map<String, String> defaultValues = new HashMap<>();
    private final Runnable restartAction;
    private final String confFolder;
    private final Path propertiesFile;

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

        initHelpTexts();
        initUI();
    }

    private void initUI() {
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

        // --- Save Button ---
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton saveApplyBtn = new JButton("Save and Apply");
        saveApplyBtn.setFont(saveApplyBtn.getFont().deriveFont(Font.BOLD));
        saveApplyBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.REFRESH, 16, Color.BLACK));
        saveApplyBtn.addActionListener(e -> {
            performSave();
            if (restartAction != null) {
                restartAction.run();
            }
        });
        buttonPanel.add(saveApplyBtn);

        JButton resetBtn = new JButton("Reset to Current");
        resetBtn.setFont(resetBtn.getFont().deriveFont(Font.BOLD));
        resetBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.UNDO, 16, Color.BLACK));
        resetBtn.addActionListener(e -> resetToCurrent());
        buttonPanel.add(resetBtn);

        JButton saveBtn = new JButton("Save Configuration");
        saveBtn.setFont(saveBtn.getFont().deriveFont(Font.BOLD));
        saveBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.FLOPPY_O, 16, Color.BLACK));
        saveBtn.addActionListener(e -> {
            performSave();
            JOptionPane.showMessageDialog(this,
                    "Configuration saved successfully!\nPlease restart the node to apply changes.", "Success",
                    JOptionPane.INFORMATION_MESSAGE);
        });
        buttonPanel.add(saveBtn);
        add(buttonPanel, BorderLayout.SOUTH);
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
            currentValue = String.valueOf(prop.getDefaultValue());
        }
        defaultValues.put(prop.getName(), String.valueOf(prop.getDefaultValue()));

        JComponent inputComponent;

        if (prop.getDefaultValue() instanceof Boolean) {
            JCheckBox checkBox = new JCheckBox();
            boolean isSelected = "true".equalsIgnoreCase(currentValue) || "yes".equalsIgnoreCase(currentValue)
                    || "1".equals(currentValue) || "on".equalsIgnoreCase(currentValue);
            checkBox.setSelected(isSelected);
            inputComponent = checkBox;
            valueSuppliers.put(prop.getName(), () -> String.valueOf(checkBox.isSelected()));
            checkBox.addActionListener(
                    e -> updateColor(checkBox, prop.getName(), String.valueOf(prop.getDefaultValue())));
        } else if (options != null) {
            JComboBox<String> comboBox = new JComboBox<>(options);
            comboBox.setSelectedItem(currentValue);
            comboBox.setEditable(editable);
            // If current value is not in options (e.g. custom), add it or handle gracefully
            if (comboBox.getSelectedItem() == null && currentValue != null) {
                comboBox.setEditable(true);
                comboBox.setSelectedItem(currentValue);
            }
            inputComponent = comboBox;
            valueSuppliers.put(prop.getName(), () -> (String) comboBox.getSelectedItem());

            comboBox.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                        boolean isSelected, boolean cellHasFocus) {
                    Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    String current = currentProperties.getProperty(prop.getName());
                    if (current == null)
                        current = String.valueOf(prop.getDefaultValue());
                    if (value != null && value.toString().equals(current)) {
                        c.setForeground(new Color(0, 128, 0));
                    } else {
                        c.setForeground(UIManager.getColor("text"));
                    }
                    return c;
                }
            });
            comboBox.addActionListener(
                    e -> updateColor(comboBox, prop.getName(), String.valueOf(prop.getDefaultValue())));
        } else {
            JTextField textField = new JTextField(currentValue);
            inputComponent = textField;
            valueSuppliers.put(prop.getName(), textField::getText);
            textField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    updateColor(textField, prop.getName(), String.valueOf(prop.getDefaultValue()));
                }

                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    updateColor(textField, prop.getName(), String.valueOf(prop.getDefaultValue()));
                }

                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    updateColor(textField, prop.getName(), String.valueOf(prop.getDefaultValue()));
                }
            });
        }

        updateColor(inputComponent, prop.getName(), String.valueOf(prop.getDefaultValue()));
        panel.add(inputComponent, "split 2, growx");
        propertyComponents.put(prop.getName(), inputComponent);

        // Help Button
        JButton helpBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.QUESTION_CIRCLE, 16, Color.LIGHT_GRAY));
        helpBtn.setBorderPainted(false);
        helpBtn.setContentAreaFilled(false);
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
            currentValue = String.valueOf(prop.getDefaultValue());
        }
        defaultValues.put(prop.getName(), String.valueOf(prop.getDefaultValue()));

        JPasswordField passwordField = new JPasswordField(currentValue);
        char defaultEchoChar = passwordField.getEchoChar();

        passwordField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updateColor(passwordField, prop.getName(), String.valueOf(prop.getDefaultValue()));
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updateColor(passwordField, prop.getName(), String.valueOf(prop.getDefaultValue()));
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updateColor(passwordField, prop.getName(), String.valueOf(prop.getDefaultValue()));
            }
        });
        updateColor(passwordField, prop.getName(), String.valueOf(prop.getDefaultValue()));
        panel.add(passwordField, "split 2, growx");

        // Help Button
        JButton helpBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.QUESTION_CIRCLE, 16, Color.LIGHT_GRAY));
        helpBtn.setBorderPainted(false);
        helpBtn.setContentAreaFilled(false);
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
            currentValue = String.valueOf(prop.getDefaultValue());
        }
        defaultValues.put(prop.getName(), String.valueOf(prop.getDefaultValue()));

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
                updateColor(textArea, prop.getName(), String.valueOf(prop.getDefaultValue()));
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updateColor(textArea, prop.getName(), String.valueOf(prop.getDefaultValue()));
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updateColor(textArea, prop.getName(), String.valueOf(prop.getDefaultValue()));
            }
        });
        updateColor(textArea, prop.getName(), String.valueOf(prop.getDefaultValue()));
        panel.add(scrollPane, "split 2, growx, hmin 80");
        propertyComponents.put(prop.getName(), scrollPane);

        // Help Button
        JButton helpBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.QUESTION_CIRCLE, 16, Color.LIGHT_GRAY));
        helpBtn.setBorderPainted(false);
        helpBtn.setContentAreaFilled(false);
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info");
        helpBtn.addActionListener(e -> showHelp(prop, labelText));
        panel.add(helpBtn, "wrap, aligny top");
        panel.add(new JSeparator(), "span, growx, wrap, gaptop 2, gapbottom 2");

        valueSuppliers.put(prop.getName(), () -> {
            String text = textArea.getText();
            // Split by newline, trim, filter empty, join with ";"
            return Arrays.stream(text.split("\n"))
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .reduce((a, b) -> a + ";" + b).orElse("");
        });
    }

    private void updateColor(JComponent comp, String propName, String defaultValue) {
        String current = currentProperties.getProperty(propName);
        if (current == null)
            current = defaultValue;

        String value = "";
        JComponent target = comp;

        if (comp instanceof JCheckBox) {
            value = String.valueOf(((JCheckBox) comp).isSelected());
            boolean curBool = "true".equalsIgnoreCase(current) || "yes".equalsIgnoreCase(current) || "1".equals(current)
                    || "on".equalsIgnoreCase(current);
            boolean valBool = Boolean.parseBoolean(value);
            if (curBool == valBool)
                target.setForeground(new Color(0, 128, 0));
            else
                target.setForeground(Color.BLACK);
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
                value = Arrays.stream(value.split("\n"))
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .reduce((a, b) -> a + ";" + b).orElse("");

                current = Arrays.stream(current.split(";"))
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .reduce((a, b) -> a + ";" + b).orElse("");
            } else {
                value = value.trim();
                current = current.trim();
            }
        }

        if (value.equals(current)) {
            target.setForeground(new Color(0, 128, 0));
        } else {
            target.setForeground(UIManager.getColor("text"));
        }
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
            currentProperties.setProperty(entry.getKey(), entry.getValue().get());
        }
        try (FileOutputStream out = new FileOutputStream(propertiesFile.toFile())) {
            currentProperties.store(out, "Signum Node Configuration");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error saving properties: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void ensurePropertiesFileExists() {
        if (!Files.exists(propertiesFile)) {
            Path defaultFile = PathUtils.resolvePath(confFolder).resolve("node-default.properties");
            if (Files.exists(defaultFile)) {
                try {
                    Files.copy(defaultFile, propertiesFile);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void initHelpTexts() {
        // API
        helpTexts.put(Props.API_SERVER.getName(),
                "Enables the HTTP API server. Required for wallets and local tools to communicate with the node.");
        helpTexts.put(Props.API_PORT.getName(), "The TCP port on which the API server listens. Default is 8125.");
        helpTexts.put(Props.API_LISTEN.getName(),
                "The interface IP to bind to. Use 0.0.0.0 to listen on all interfaces (public), or 127.0.0.1 for local only.");
        helpTexts.put(Props.API_ALLOWED.getName(),
                "List of allowed IP addresses that can access the API. Separate with semicolons.");
        helpTexts.put(Props.API_ADMIN_KEY_LIST.getName(),
                "Password (API Key) required for administrative actions (like shutdown, debug). Leave empty to disable admin actions.");
        helpTexts.put(Props.API_SSL.getName(),
                "Enables SSL (HTTPS) for the API server. Requires keystore configuration.");
        helpTexts.put(Props.API_DOC_MODE.getName(),
                "Controls the built-in API documentation (Swagger UI). 'modern' enables the new UI, 'legacy' the old one, 'off' disables it.");
        helpTexts.put(Props.API_WEBSOCKET_ENABLE.getName(),
                "Enables WebSocket support for real-time events (e.g. new blocks, transactions).");
        helpTexts.put(Props.API_WEBSOCKET_HEARTBEAT_INTERVAL.getName(),
                "The interval in seconds for WebSocket heartbeat messages to keep connections alive.");

        // Database
        helpTexts.put(Props.DB_URL.getName(),
                "The JDBC connection URL. Examples:\nSQLite: jdbc:sqlite:file:./db/signum.sqlite.db\nMariaDB: jdbc:mariadb://localhost:3306/signum\nPostgres: jdbc:postgresql://localhost:5432/signum");
        helpTexts.put(Props.DB_TRIM_DERIVED_TABLES.getName(),
                "If enabled, the node will periodically prune old data from derived tables to save disk space. Recommended for non-archival nodes.");
        helpTexts.put(Props.DB_SQLITE_JOURNAL_MODE.getName(),
                "SQLite journaling mode. WAL (Write-Ahead Logging) is recommended for performance and concurrency.");
        helpTexts.put(Props.DB_SQLITE_CACHE_SIZE.getName(),
                "Memory allocated for SQLite cache. Negative values indicate number of pages (e.g. -131072 for ~512MB).");

        // P2P
        helpTexts.put(Props.P2P_PORT.getName(),
                "The TCP port used for Peer-to-Peer communication with other nodes. Default is 8123.");
        helpTexts.put(Props.P2P_UPNP.getName(),
                "Attempts to automatically configure port forwarding on your router using UPnP.");
        helpTexts.put(Props.P2P_MY_ADDRESS.getName(),
                "Your external IP address or hostname. Useful if you are behind a NAT/Router and want to be reachable.");
        helpTexts.put(Props.P2P_BOOTSTRAP_PEERS.getName(), "List of initial peers to connect to when the node starts.");

        // Mining
        helpTexts.put(Props.GPU_ACCELERATION.getName(),
                "Enables OpenCL GPU acceleration for verifying mining nonces. Significantly improves performance during sync and mining.");
        helpTexts.put(Props.SOLO_MINING_PASSPHRASES.getName(),
                "Semicolon-separated list of passphrases for accounts that are solo mining on this node.");
        helpTexts.put(Props.REWARD_RECIPIENT_PASSPHRASES.getName(),
                "Passphrases for accounts setting a reward recipient (pool mining).");

        // System
        helpTexts.put(Props.CPU_NUM_CORES.getName(),
                "Limits the number of CPU cores used by the node. -1 means use all available cores / 2.");
        helpTexts.put(Props.BLOCK_PROCESS_THREAD_DELAY.getName(),
                "Delay in milliseconds between block processing threads. Lower values (e.g. 100) speed up sync on fast CPUs.");
        helpTexts.put(Props.AUTO_POP_OFF_ENABLED.getName(),
                "If enabled, the node will automatically attempt to resolve forks by popping off blocks.");
        helpTexts.put(Props.AUTO_CONSISTENCY_RESOLVE_ENABLED.getName(),
                "If enabled, the node will try to fix database inconsistencies automatically on startup.");

        // Jetty
        helpTexts.put(Props.JETTY_API_DOS_FILTER.getName(),
                "Enables Denial of Service (DoS) protection filter for the API.");
        helpTexts.put(Props.JETTY_P2P_DOS_FILTER.getName(),
                "Enables Denial of Service (DoS) protection filter for P2P connections.");
        helpTexts.put(Props.JETTY_API_GZIP_FILTER.getName(), "Enables GZIP compression for API responses.");
    }
}