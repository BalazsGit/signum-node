package brs;

import java.awt.*;
import java.awt.TrayIcon.MessageType;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.text.BreakIterator;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collection;
import brs.util.Listener;
import java.util.stream.Collectors;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.LookAndFeel;
import javax.swing.SwingConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.Timer;
import javax.swing.UIManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brs.fluxcapacitor.FluxValues;
import brs.props.PropertyService;
import brs.props.Props;
import brs.peer.Peer;
import brs.util.Convert;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import org.pushingpixels.radiance.animation.api.Timeline;
import org.pushingpixels.radiance.animation.api.Timeline.TimelineState;
import org.pushingpixels.radiance.animation.api.callback.TimelineCallbackAdapter;
import org.pushingpixels.radiance.animation.api.ease.Spline;
import org.pushingpixels.radiance.animation.api.swing.SwingComponentTimeline;
import org.pushingpixels.radiance.animation.api.swing.SwingRepaintTimeline;

@SuppressWarnings("serial")
public class SignumGUI extends JFrame {
    private static final String FAILED_TO_START_MESSAGE = "Signum caught exception while starting";
    private static final String UNEXPECTED_EXIT_MESSAGE = "Signum Quit unexpectedly! Exit code ";

    public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("HH:mm:ss yyyy-MM-dd");

    private static final int OUTPUT_MAX_LINES = 500;

    private static final Logger LOGGER = LoggerFactory.getLogger(SignumGUI.class);
    private static String[] args;

    private String iconLocation;
    private TrayIcon trayIcon = null;
    private JPanel toolBar = null;
    private JLabel latestBlockHeightLabel = null;
    private JLabel latestBlockTimestampLabel = null;
    private JPanel infoPanel;
    private JProgressBar syncProgressBar = null;
    private JScrollPane textScrollPane = null;
    private String programName = null;
    private String version = null;
    private final Color iconColor;
    private JPanel topPanel;

    private JLabel connectedPeersLabel;
    private JLabel peersCountLabel;
    private JLabel blacklistedPeersLabel;
    private JLabel uploadVolumeLabel;
    private JLabel downloadVolumeLabel;
    private JCheckBox showPopOffCheckbox;
    private JLabel popOffBlocksLabel;
    private JSeparator popOffSeparator;
    private JCheckBox showMetricsCheckbox;
    private JLabel trimHeightLabel;
    private JSeparator trimSeparator;
    private JSeparator trimProgressSeparator;
    private boolean showMetrics = false;
    private boolean showPopOff = false;
    private boolean isSyncStopped = false;

    private JButton openPhoenixButton;
    private JButton openClassicButton;
    private JButton openApiButton;
    private JButton editConfButton;
    private JButton popOff10Button;
    private JButton popOff100Button;
    private JButton syncButton;
    private JButton shutdownButton;
    private JButton autoPopOffTestButton;
    private JButton popOffToTestButton;
    private JButton processForkTestButton;
    private JButton manualTrimButton;
    private JPanel testButtons;
    private JButton dbCheckButton;
    // private JButton restartButton;

    private MetricsPanel metricsPanel;

    private final JPanel checkboxPanel;
    private JLabel measurementLabel;
    private JLabel experimentalLabel;
    private JSeparator measurementSeparator;
    private JSeparator experimentalSeparator;
    private JPanel measurementPanel;
    private JPanel experimentalPanel;
    private final Dimension verticalSeparatorSize = new Dimension(2, 20);

    private Dimension progressBarSize2 = new Dimension(150, 20);

    /**
     * Panel to hold the time tracking labels. Only visible when experimental
     * features are enabled.
     */
    private JPanel timePanel;

    private JSeparator timeSeparator;

    /**
     * Label to display the total elapsed time since the GUI was started.
     */
    private JLabel totalTimeLabel;
    /**
     * Label to display the accumulated time spent syncing the blockchain.
     */
    private JLabel syncInProgressTimeLabel;
    /**
     * Stores the total elapsed time in milliseconds, updated by the GUI timer.
     */
    private long guiAccumulatedSyncTimeMs = 0;
    /**
     * Stores the accumulated time in milliseconds spent actively syncing (when more
     * than 10 blocks behind).
     */
    private long guiAccumulatedSyncInProgressTimeMs = 0;
    /**
     * Flag for the hysteresis logic, indicating if the node is currently considered
     * to be syncing.
     */
    private boolean isSyncing = false; // For hysteresis
    /**
     * Label for the separator between time labels.
     */
    private JLabel timeSeparatorLabel;
    /**
     * Timer to update the GUI time labels every second.
     */
    private Timer guiTimer;
    private final AtomicBoolean guiTimerStarted = new AtomicBoolean(false);

    private JLabel createLabel(String text, Color color, String tooltip) {
        JLabel label = new JLabel(text);
        if (color != null) {
            label.setForeground(color);
        }
        if (tooltip != null) {
            addInfoTooltip(label, tooltip);
        }
        return label;
    }

    private void addInfoTooltip(JComponent component, String text) {
        component.setToolTipText("Right-click for more info");
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    String title = "";
                    if (component instanceof JLabel) {
                        title = ((JLabel) component).getText();
                    } else if (component instanceof JButton) {
                        title = ((JButton) component).getText();
                    }
                    showInfoDialog(title, text, 300);
                }
            }
        });
    }

    private void showInfoDialog(String title, String text, int width) {
        if (title.endsWith(":")) {
            title = title.substring(0, title.length() - 1);
        }
        String htmlText = "<html><body><p style='width: " + width + "px;'>" + text.replace("\n", "<br>")
                + "</p></body></html>";
        JOptionPane.showMessageDialog(SignumGUI.this, htmlText, title, JOptionPane.PLAIN_MESSAGE);
    }

    private enum PeerCategory {
        ACTIVE("Active", p -> p.getState() != Peer.State.NON_CONNECTED),
        CONNECTED("Connected", p -> p.getState() == Peer.State.CONNECTED),
        BLACKLISTED("Blacklisted", Peer::isBlacklisted),
        ALL("All Known", p -> true);

        private final String title;
        private final Predicate<Peer> filter;

        PeerCategory(String title, Predicate<Peer> filter) {
            this.title = title;
            this.filter = filter;
        }
    }

    private JFrame peersDialog;

    private void updatePeerListScrollPane(JScrollPane scrollPane, List<Peer> peers, PeerCategory category) {
        JEditorPane editorPane = (JEditorPane) scrollPane.getViewport().getView();
        peers.sort(Comparator.comparing(Peer::getPeerAddress));
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<html><body style='font-family:monospaced; font-size:12pt;'>");

        if (peers.isEmpty()) {
            sb.append("No peers in this category.");
        } else {
            for (Peer p : peers) {
                String color;
                if (p.isBlacklisted()) {
                    color = "red";
                } else if (p.getState() != Peer.State.NON_CONNECTED) {
                    color = "green";
                } else {
                    // Only the 'ALL' category shows non-active, non-blacklisted peers
                    color = category == PeerCategory.ALL ? "yellow" : "green";
                }
                sb.append("<font color='").append(color).append("'>");
                sb.append(p.getPeerAddress()).append(" (").append(p.getVersion().toStringIfNotEmpty()).append(")");
                sb.append("</font><br>");
            }
        }
        sb.append("</body></html>");
        editorPane.setText(sb.toString());
        editorPane.setCaretPosition(0);
    }

    private JScrollPane createPeerListScrollPane() {
        JEditorPane editorPane = new JEditorPane();
        editorPane.setContentType("text/html");
        editorPane.setEditable(false);
        editorPane.setBackground(UIManager.getColor("Panel.background"));
        JScrollPane scrollPane = new JScrollPane(editorPane);
        // Set a preferred size similar to the old JTextArea(15, 50)
        scrollPane.setPreferredSize(new Dimension(400, 250));
        return scrollPane;
    }

    private void showPeersDialog() {
        if (peersDialog != null && peersDialog.isVisible()) {
            peersDialog.toFront();
            return;
        }

        peersDialog = new JFrame("Peer Information");
        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JTextArea legendArea = new JTextArea();
        legendArea.setEditable(false);
        legendArea.setLineWrap(true);
        legendArea.setWrapStyleWord(true);
        legendArea.setBackground(UIManager.getColor("Panel.background"));
        legendArea.setText(
                "Peers: Active / All Known (BL: Blacklisted)\n\n" +
                        "• Active: Peers your node is currently communicating with.\n" +
                        "• Connected: A subset of active peers with a stable connection.\n" +
                        "• Blacklisted: Peers temporarily banned for sending invalid data.\n" +
                        "• All Known: All peers your node has ever discovered.");
        mainPanel.add(legendArea, BorderLayout.NORTH);

        JTabbedPane tabbedPane = new JTabbedPane();

        Runnable updateTabs = () -> {
            Collection<Peer> allPeers = Signum.getBlockchainProcessor().getAllPeers();
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                PeerCategory category = PeerCategory.values()[i];
                List<Peer> filteredPeers = allPeers.stream().filter(category.filter).collect(Collectors.toList());
                tabbedPane.setTitleAt(i, category.title + " (" + filteredPeers.size() + ")");
                updatePeerListScrollPane((JScrollPane) tabbedPane.getComponentAt(i), filteredPeers, category);
            }
        };

        for (PeerCategory category : PeerCategory.values()) {
            tabbedPane.addTab(category.title, createPeerListScrollPane());
        }
        updateTabs.run(); // Initial population and titles

        Listener<Block> peerListener = block -> SwingUtilities.invokeLater(updateTabs);
        Signum.getBlockchainProcessor().addListener(peerListener, BlockchainProcessor.Event.PEER_COUNT_CHANGED);

        peersDialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                Signum.getBlockchainProcessor().removeListener(peerListener,
                        BlockchainProcessor.Event.PEER_COUNT_CHANGED);
                peersDialog = null;
            }
        });

        mainPanel.add(tabbedPane, BorderLayout.CENTER);
        peersDialog.add(mainPanel);
        peersDialog.pack();
        peersDialog.setLocationRelativeTo(this);
        peersDialog.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        peersDialog.setVisible(true);
    }

    public static void main(String[] args) {
        new SignumGUI("Signum Node", Props.ICON_LOCATION.getDefaultValue(), Signum.VERSION.toString(), args);
    }

    public SignumGUI(String programName, String iconLocation, String version, String[] args) {
        try {
            // SecurityManager removed (Java 17+ deprecation).
            // Install a simple shutdown hook instead for cleanup if needed.
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        // TODO: add GUI cleanup here if required
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }));
            } catch (Throwable t) {
                // ignore
            }

        } catch (UnsupportedOperationException e) {
            // Java 17+ / 21+: Setting a SecurityManager is not supported anymore
            System.err.println("SecurityManager not supported, skipping setup");
        }
        SignumGUI.args = args;
        this.programName = programName;
        this.version = version;
        setTitle(programName + " " + version);
        this.iconLocation = iconLocation;

        Class<?> lafc = null;
        try {
            lafc = Class.forName("com.sun.java.swing.plaf.nimbus.NimbusLookAndFeel");
        } catch (Exception e) {
        }
        if (lafc == null) {
            try {
                lafc = Class.forName("javax.swing.plaf.nimbus.NimbusLookAndFeel");
            } catch (Exception e) {
            }
        }
        if (lafc != null) {
            try {
                UIManager.put("control", new Color(128, 128, 128));
                UIManager.put("info", new Color(128, 128, 128));
                UIManager.put("nimbusBase", new Color(18, 30, 49));
                UIManager.put("nimbusAlertYellow", new Color(248, 187, 0));
                UIManager.put("nimbusDisabledText", new Color(128, 128, 128));
                UIManager.put("nimbusFocus", new Color(115, 164, 209));
                UIManager.put("nimbusGreen", new Color(176, 179, 50));
                UIManager.put("nimbusInfoBlue", new Color(66, 139, 221));
                UIManager.put("nimbusLightBackground", new Color(18, 30, 49));
                UIManager.put("nimbusOrange", new Color(191, 98, 4));
                UIManager.put("nimbusRed", new Color(169, 46, 34));
                UIManager.put("nimbusSelectedText", new Color(255, 255, 255));
                UIManager.put("nimbusSelectionBackground", new Color(104, 93, 156));
                UIManager.put("text", new Color(230, 230, 230));
                LookAndFeel laf = (LookAndFeel) lafc.getConstructor().newInstance();
                UIManager.setLookAndFeel(laf);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        IconFontSwing.register(FontAwesome.getIconFont());
        JTextArea textArea = new JTextArea() {
            @Override
            public void append(String str) {
                super.append(str);

                while (getText().split("\n", -1).length > OUTPUT_MAX_LINES) {
                    int fle = getText().indexOf('\n');
                    super.replaceRange("", 0, fle + 1);
                }
                JScrollBar vertical = textScrollPane.getVerticalScrollBar();
                vertical.setValue(vertical.getMaximum());
            }
        };
        iconColor = textArea.getForeground();
        textArea.setEditable(false);
        sendJavaOutputToTextArea(textArea);
        textScrollPane = new JScrollPane(textArea);
        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        setContentPane(content);

        toolBar = new JPanel();
        toolBar.setLayout(new BoxLayout(toolBar, BoxLayout.X_AXIS));

        content.add(toolBar, BorderLayout.PAGE_START);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        content.add(bottomPanel, BorderLayout.PAGE_END);

        syncProgressBar = new JProgressBar(0, 100);
        syncProgressBar.setStringPainted(true);
        String syncTooltipText = "Indicates the synchronization progress of the blockchain, displayed as a percentage. This value is calculated by comparing your node's current block height to the estimated highest block height known in the network.\n\nA value of 100% means your node is fully synchronized and has the complete, up-to-date ledger. During synchronization, this bar will gradually fill as the node downloads and processes blocks.";
        syncProgressBar.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    String title = "Synchronization Progress";
                    String htmlText = "<html><body><p style='width: 300px;'>" + syncTooltipText.replace("\n", "<br>")
                            + "</p></body></html>";
                    JOptionPane.showMessageDialog(SignumGUI.this, htmlText, title, JOptionPane.PLAIN_MESSAGE);
                }
            }
        });

        JPanel latestBlockInfoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        latestBlockHeightLabel = new JLabel("Latest block: -");
        latestBlockTimestampLabel = new JLabel("Timestamp: -");
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setPreferredSize(verticalSeparatorSize);

        latestBlockInfoPanel.add(latestBlockHeightLabel);
        latestBlockInfoPanel.add(separator);
        latestBlockInfoPanel.add(latestBlockTimestampLabel);

        String blockInfoTooltip = "Displays critical information about the most recent block processed by your node. This includes:\n\n"
                + "- Latest block: The sequential number of the latest block synchronized by your node.\n"
                + "- Timestamp: The date and time when the block was generated by a miner.\n\n"
                + "This information is essential for confirming that your node is connected to the network and processing new blocks as they are created.";
        addInfoTooltip(latestBlockHeightLabel, blockInfoTooltip);
        addInfoTooltip(latestBlockTimestampLabel, blockInfoTooltip);
        metricsPanel = new MetricsPanel(this);

        trimSeparator = new JSeparator(SwingConstants.VERTICAL);
        trimSeparator.setPreferredSize(verticalSeparatorSize);
        String trimTooltip = "The minimum height to which the blockchain can be rolled back. Older data is pruned to save space.";
        trimHeightLabel = createLabel("Trim height: -", null, trimTooltip);

        latestBlockInfoPanel.add(trimSeparator);
        latestBlockInfoPanel.add(trimHeightLabel);

        popOffSeparator = new JSeparator(SwingConstants.VERTICAL);
        popOffSeparator.setPreferredSize(verticalSeparatorSize);
        String popOffTooltip = "Shows the number of blocks remaining to be removed from the blockchain during a 'pop-off' operation.\n\nThis counter appears only when a pop-off is in progress and helps monitor its advancement.";
        popOffBlocksLabel = createLabel("Pop off blocks: 0", null, popOffTooltip);

        latestBlockInfoPanel.add(popOffSeparator);
        latestBlockInfoPanel.add(popOffBlocksLabel);
        setPopOffLabelVisible(false);

        // === Add checkboxes to toolBar ===
        checkboxPanel = new JPanel();
        checkboxPanel.setLayout(new BoxLayout(checkboxPanel, BoxLayout.Y_AXIS));

        showPopOffCheckbox = new JCheckBox("Pop off");
        // showPopOffCheckbox.setHorizontalTextPosition(SwingConstants.LEFT);
        showPopOffCheckbox.setSelected(showPopOff);
        showPopOffCheckbox.addActionListener(e -> {
            showPopOff = showPopOffCheckbox.isSelected();
            popOff10Button.setVisible(showPopOff);
            popOff100Button.setVisible(showPopOff);
        });

        showMetricsCheckbox = new JCheckBox("Metrics");
        // showMetricsCheckbox.setHorizontalTextPosition(SwingConstants.LEFT);
        showMetricsCheckbox.setSelected(showMetrics); // default visible
        showMetricsCheckbox.addActionListener(e -> {
            showMetrics = showMetricsCheckbox.isSelected();
            animateMetricsPanelTrident(showMetrics);
        });

        checkboxPanel.add(showPopOffCheckbox);
        checkboxPanel.add(showMetricsCheckbox);

        topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.add(toolBar);
        topPanel.add(metricsPanel);
        metricsPanel.setVisible(showMetrics);

        // Use GridBagLayout for infoPanel to allow precise vertical alignment
        infoPanel = new JPanel(new GridBagLayout());

        content.add(topPanel, BorderLayout.NORTH);
        content.add(textScrollPane, BorderLayout.CENTER);

        // --- Time Labels ---
        String tooltip;
        timePanel = new JPanel();
        timePanel.setLayout(new BoxLayout(timePanel, BoxLayout.X_AXIS));
        timePanel.setOpaque(false);
        String timeTooltip = "Displays the total elapsed time since the node application was started.";
        totalTimeLabel = createLabel("0s", null, timeTooltip);
        String syncTimeTooltip = "Displays the total time the node has spent in synchronization mode. The timer is active only when the blockchain is more than 10 blocks behind the network.";
        syncInProgressTimeLabel = createLabel("0s", null, syncTimeTooltip);

        timeSeparator = new JSeparator(SwingConstants.VERTICAL);
        timeSeparator.setPreferredSize(verticalSeparatorSize);
        timeSeparatorLabel = new JLabel(" / ");

        timePanel.add(totalTimeLabel);
        timePanel.add(timeSeparatorLabel);
        timePanel.add(syncInProgressTimeLabel);
        timePanel.add(Box.createHorizontalStrut(5));
        timePanel.add(timeSeparator);
        timePanel.add(Box.createHorizontalStrut(5));
        timePanel.setVisible(false); // Visibility controlled by experimental features

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0; // All components in the same row
        gbc.anchor = GridBagConstraints.CENTER; // Vertically center all components
        gbc.insets = new Insets(0, 0, 0, 0); // Default no padding

        // Add timePanel
        gbc.gridx = 0;
        infoPanel.add(timePanel, gbc);

        // --- Peers ---
        JPanel peersPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tooltip = "Active Peers: The number of peers your node is currently communicating with.";
        connectedPeersLabel = createLabel("0", null, tooltip); // Represents 'Active' peers now
        tooltip = "Total Discovered Peers: The total number of peers your node has ever discovered, including active, disconnected, and blacklisted ones.";
        peersCountLabel = createLabel("0", null, tooltip); // Represents 'All Known' peers
        tooltip = "Blacklisted Peers: The number of peers that have been temporarily banned for sending invalid data or other network violations.";
        blacklistedPeersLabel = createLabel("0", null, tooltip);

        peersPanel.add(new JLabel("Peers: "));
        peersPanel.add(connectedPeersLabel);
        peersPanel.add(new JLabel(" / "));
        peersPanel.add(peersCountLabel);
        peersPanel.add(new JLabel(" (BL: "));
        peersPanel.add(blacklistedPeersLabel);
        peersPanel.add(new JLabel(")"));

        // Add peersPanel
        peersPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showPeersDialog();
            }
        });
        peersPanel.setToolTipText("Click to see detailed peer information.");
        gbc.gridx = 1;
        infoPanel.add(peersPanel, gbc); // No left inset needed, timePanel provides right spacing

        // Add separator after peersPanel
        gbc.gridx = 2;
        gbc.insets = new Insets(0, 5, 0, 5); // Spacing around separator
        JSeparator peersSeparator = new JSeparator(SwingConstants.VERTICAL);
        peersSeparator.setPreferredSize(verticalSeparatorSize);
        infoPanel.add(peersSeparator, gbc);
        gbc.insets = new Insets(0, 0, 0, 0); // Reset insets

        // --- Volume ---
        JPanel volumePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));

        // Upload
        tooltip = "The total amount of data your node has uploaded to other peers since the application started. This data primarily consists of blocks and transactions that you are sharing with the rest of the network, contributing to its health and decentralization.";
        uploadVolumeLabel = createLabel("▲ 0 MB", null, tooltip);

        // Download
        tooltip = "The total amount of data your node has downloaded from other peers since the application started. This data includes blocks and transactions required to synchronize your local copy of the blockchain with the network.";
        downloadVolumeLabel = createLabel("▼ 0 MB", null, tooltip);

        volumePanel.add(uploadVolumeLabel);
        volumePanel.add(new JLabel(" / "));
        volumePanel.add(downloadVolumeLabel);

        // Add volumePanel
        gbc.gridx = 3;
        infoPanel.add(volumePanel, gbc);

        // Add separator after volumePanel
        gbc.gridx = 4;
        gbc.insets = new Insets(0, 5, 0, 5);
        JSeparator volumeSeparator = new JSeparator(SwingConstants.VERTICAL);
        volumeSeparator.setPreferredSize(verticalSeparatorSize);
        infoPanel.add(volumeSeparator, gbc);
        gbc.insets = new Insets(0, 0, 0, 0);

        // --- Measurement ---
        measurementPanel = new JPanel();
        measurementPanel.setLayout(new BoxLayout(measurementPanel, BoxLayout.X_AXIS));
        measurementPanel.setOpaque(false);
        measurementSeparator = new JSeparator(SwingConstants.VERTICAL);
        measurementSeparator.setPreferredSize(verticalSeparatorSize);
        tooltip = "Performance measurement is active.\n"
                + "Detailed synchronization data is being collected for each block and saved to:\n"
                + "- measurement/sync_measurement.csv\n"
                + "- measurement/sync_progress.csv\n" + "for analysis.";
        measurementLabel = createLabel("🔬 MEAS", null, tooltip);
        measurementPanel.setVisible(false);

        measurementPanel.add(measurementLabel);
        measurementPanel.add(Box.createHorizontalStrut(5));
        measurementPanel.add(measurementSeparator);
        measurementPanel.add(Box.createHorizontalStrut(5));

        // Add measurementPanel
        gbc.gridx = 5;
        infoPanel.add(measurementPanel, gbc);

        // --- Experimental ---
        experimentalPanel = new JPanel();
        experimentalPanel.setLayout(new BoxLayout(experimentalPanel, BoxLayout.X_AXIS));
        experimentalPanel.setOpaque(false);
        experimentalSeparator = new JSeparator(SwingConstants.VERTICAL);
        experimentalSeparator.setPreferredSize(verticalSeparatorSize);
        tooltip = "Experimental feature is enabled.\n" + "Simplified data is being collected and saved to:\n"
                + "- measurement/sync_progress.csv\n" + "for analysis.";
        experimentalLabel = createLabel("⚗ EXP", null, tooltip);
        experimentalPanel.setVisible(false);

        experimentalPanel.add(experimentalLabel);
        experimentalPanel.add(Box.createHorizontalStrut(5));
        experimentalPanel.add(experimentalSeparator);
        experimentalPanel.add(Box.createHorizontalStrut(5));

        // Add experimentalPanel
        gbc.gridx = 6;
        infoPanel.add(experimentalPanel, gbc);

        gbc.gridx = 7;
        gbc.weightx = 1.0; // Allow progress bar to take up remaining horizontal space
        gbc.fill = GridBagConstraints.HORIZONTAL; // Fill horizontally
        infoPanel.add(syncProgressBar, gbc);

        bottomPanel.add(latestBlockInfoPanel, BorderLayout.CENTER);
        bottomPanel.add(infoPanel, BorderLayout.LINE_END);

        try {
            setIconImage(ImageIO.read(getClass().getResourceAsStream(iconLocation)));
        } catch (IOException e) {
            e.printStackTrace();
        }

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (trayIcon == null) {
                    if (JOptionPane.showConfirmDialog(SignumGUI.this,
                            "This will stop the node. Are you sure?", "Exit and stop node",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
                        shutdown();
                    }
                } else {
                    trayIcon.displayMessage("Signum GUI closed", "Note that Signum is still running", MessageType.INFO);
                    setVisible(false);
                }
            }
        });

        pack();
        setSize(Math.max(topPanel.getPreferredSize().width, metricsPanel.getPreferredSize().width), 800);
        setLocationRelativeTo(null);
        showWindow();

        // Start BRS
        new Thread(this::startSignumWithGUI).start();
    }

    private final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);
    private Timer shutdownMonitor;

    /**
     * Initiates the shutdown process. This method starts the graceful shutdown in a
     * background thread
     * and starts a Swing Timer to monitor the process. If the shutdown takes too
     * long, a dialog
     * will be presented to the user to offer a forced shutdown.
     */
    private void shutdown() {
        // Start the monitor timer.
        shutdownMonitor.start();
        if (!shutdownInProgress.compareAndSet(false, true)) {
            return; // Shutdown already in progress
        }

        // Update the title to indicate shutdown is in progress
        SwingUtilities.invokeLater(() -> setTitle(getTitle() + " (Node is shutting down...)"));

        // Start the graceful shutdown in a background thread.
        // This ensures the main shutdown logic does not block the GUI thread.
        Thread shutdownThread = new Thread(() -> {
            Signum.shutdown(false);
            // If shutdown completes gracefully, exit the application.
            SwingUtilities.invokeLater(() -> {
                shutdownMonitor.stop();
                cleanupAndExit();
            });
        }, "GracefulShutdownThread");
        shutdownThread.start();
    }

    private void showForceShutdownDialog(String message, String title) {
        shutdownMonitor.stop();
        SwingUtilities.invokeLater(() -> {
            int choice = JOptionPane.showOptionDialog(
                    SignumGUI.this,
                    message,
                    title,
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    new String[] { "Force Shutdown", "Wait" },
                    "Wait");

            if (choice == JOptionPane.YES_OPTION) {
                cleanupAndExit();
            }
            if (choice == JOptionPane.NO_OPTION) {
                shutdownMonitor.restart();
            }
            if (choice == JOptionPane.CLOSED_OPTION) {
                shutdownMonitor.restart();
            }
        });
    }

    /**
     * Performs cleanup operations and exits the application.
     * This method stops the GUI timer and calls {@link #safeExit(int)} to terminate
     * the JVM.
     */
    private void cleanupAndExit() {
        if (guiTimer != null) {
            guiTimer.stop();
        }
        if (shutdownMonitor != null) {
            shutdownMonitor.stop();
        }
        safeExit(0);
    }

    private void showTrayIcon() {
        if (trayIcon == null) { // Don't start running in tray twice
            trayIcon = createTrayIcon();
        }
    }

    private TrayIcon createTrayIcon() {
        PopupMenu popupMenu = new PopupMenu();
        MenuItem openPheonixWalletItem = new MenuItem("Phoenix Wallet");
        MenuItem openClassicWalletItem = new MenuItem("Classic Wallet");
        MenuItem openApiItem = new MenuItem("API doc");
        MenuItem showItem = new MenuItem("Show the node window");
        MenuItem shutdownItem = new MenuItem("Shutdown the node");

        openPhoenixButton = new JButton(openPheonixWalletItem.getLabel(),
                IconFontSwing.buildIcon(FontAwesome.FIRE, 18, iconColor));
        openClassicButton = new JButton(openClassicWalletItem.getLabel(),
                IconFontSwing.buildIcon(FontAwesome.WINDOW_RESTORE, 18, iconColor));
        openApiButton = new JButton(openApiItem.getLabel(),
                IconFontSwing.buildIcon(FontAwesome.BOOK, 18, iconColor));
        editConfButton = new JButton("Edit conf file",
                IconFontSwing.buildIcon(FontAwesome.PENCIL, 18, iconColor));
        popOff10Button = new JButton("Pop off 10 blocks",
                IconFontSwing.buildIcon(FontAwesome.STEP_BACKWARD, 18, iconColor));
        popOff100Button = new JButton("Pop off 100 blocks",
                IconFontSwing.buildIcon(FontAwesome.BACKWARD, 18, iconColor));
        syncButton = new JButton("Stop Sync",
                IconFontSwing.buildIcon(FontAwesome.PAUSE, 18, iconColor));
        /*
         * restartButton = new JButton("Restart",
         */
        autoPopOffTestButton = new JButton("Auto PopOff Test",
                IconFontSwing.buildIcon(FontAwesome.EXCLAMATION_TRIANGLE, 18, iconColor));
        popOffToTestButton = new JButton("PopOff To Test",
                IconFontSwing.buildIcon(FontAwesome.HISTORY, 18, iconColor));
        processForkTestButton = new JButton("Process Fork Test",
                IconFontSwing.buildIcon(FontAwesome.CODE_FORK, 18, iconColor));
        manualTrimButton = new JButton("Trim DB",
                IconFontSwing.buildIcon(FontAwesome.SCISSORS, 18, iconColor));
        dbCheckButton = new JButton("Database check",
                IconFontSwing.buildIcon(FontAwesome.DATABASE, 18, iconColor));

        addInfoTooltip(openPhoenixButton, "Opens the modern Phoenix Wallet in your default web browser.");
        addInfoTooltip(openClassicButton, "Opens the Classic Wallet in your default web browser.");
        addInfoTooltip(openApiButton, "Opens the interactive API documentation in your default web browser.");
        addInfoTooltip(editConfButton,
                "Opens the node's configuration file (node.properties or node-default.properties) in your default text editor for easy modification.");

        addInfoTooltip(autoPopOffTestButton,
                "TESTING ONLY: Simulates a condition that triggers an automatic pop-off to test the node's fork resolution capabilities.");
        addInfoTooltip(popOffToTestButton,
                "TESTING ONLY: Pops off a predefined number of blocks to a specific test height.");
        addInfoTooltip(processForkTestButton,
                "TESTING ONLY: Initiates a test fork scenario to verify the node's ability to switch between competing blockchain branches.");

        addInfoTooltip(popOff10Button,
                "Removes the last 10 blocks from your local blockchain. This can help resolve a local fork if your node is stuck.");
        addInfoTooltip(manualTrimButton,
                "Removes the last 10 blocks from your local blockchain. This can help resolve a local fork if your node is stuck.");
        addInfoTooltip(popOff100Button,
                "Removes the last 100 blocks from your local blockchain. Use this if a smaller pop-off does not resolve a fork.");
        addInfoTooltip(syncButton,
                "Toggles the synchronization process. 'Pause Sync' pauses the downloading and processing of new blocks. 'Resume Sync' continues the process.");
        addInfoTooltip(dbCheckButton,
                "Performs a manual consistency check on the database to ensure data integrity.");
        /*
         * restartButton = new JButton("Restart",
         * IconFontSwing.buildIcon(FontAwesome.REFRESH, 18, iconColor));
         */

        shutdownButton = new JButton("Shutdown",
                IconFontSwing.buildIcon(FontAwesome.POWER_OFF, 18, iconColor));
        // TODO: find a way to actually store permanently the max block available to
        addInfoTooltip(shutdownButton,
                "Initiates a graceful shutdown of the node, ensuring all data is saved correctly. If the node does not shut down in a timely manner, you will be prompted to force the exit.");
        // pop-off, otherwise we can break it
        // JButton popOffMaxButton = new JButton("Pop off max",
        // IconFontSwing.buildIcon(FontAwesome.FAST_BACKWARD, 18, iconColor));

        openPhoenixButton.addActionListener(e -> openWebUi("/phoenix"));
        openClassicButton.addActionListener(e -> openWebUi("/classic"));
        openApiButton.addActionListener(e -> openWebUi("/api-doc"));
        editConfButton.addActionListener(e -> editConf());
        popOff10Button.addActionListener(e -> popOff(10));
        popOff100Button.addActionListener(e -> popOff(100));
        dbCheckButton.addActionListener(e -> {
            new Thread(() -> {
                BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
                final int height = blockchainProcessor.getLastCheckHeight();
                final int result = blockchainProcessor.checkDatabaseState();
                final long totalMined = blockchainProcessor.getLastCheckTotalMined();
                final double totalMinedSigna = (double) totalMined / Constants.ONE_SIGNA;
                final long totalEffectiveBalance = blockchainProcessor.getLastCheckTotalEffectiveBalance();
                final double totalEffectiveBalanceSigna = (double) totalEffectiveBalance / Constants.ONE_SIGNA;
                final long difference = totalMined - totalEffectiveBalance;

                SwingUtilities.invokeLater(() -> {
                    String message;
                    Icon icon;
                    if (result == 0) {
                        message = String.format("Database is consistent at block height %d.\n\n" +
                                "Total Mined: %,.2f SIGNA (%,d NQT)\n" +
                                "Total Effective Balance: %,.2f SIGNA (%,d NQT)",
                                height,
                                totalMinedSigna, totalMined,
                                totalEffectiveBalanceSigna, totalEffectiveBalance);
                        icon = IconFontSwing.buildIcon(FontAwesome.CHECK_CIRCLE, 32, new Color(0, 128, 0));
                    } else {
                        String inconsistencyType;
                        if (result > 0) {
                            inconsistencyType = "Total mined is greater than total effective balance.";
                        } else {
                            inconsistencyType = "Total mined is less than total effective balance.";
                        }
                        message = String.format("Database is INCONSISTENT!\n\n%s\n\n" +
                                "Total Mined: %,.2f SIGNA (%,d NQT)\n" +
                                "Total Effective Balance: %,.2f SIGNA (%,d NQT)\n\n" +
                                "Difference: %,d NQT\n\nCheck logs for more details at block height %d.",
                                inconsistencyType,
                                totalMinedSigna, totalMined,
                                totalEffectiveBalanceSigna, totalEffectiveBalance,
                                difference, height);
                        icon = IconFontSwing.buildIcon(FontAwesome.TIMES_CIRCLE, 32, Color.RED);
                    }
                    JOptionPane.showMessageDialog(SignumGUI.this, message, "Database Consistency Check",
                            JOptionPane.INFORMATION_MESSAGE, icon);
                });
            }).start();
        });

        syncButton.addActionListener(e -> {
            isSyncStopped = !isSyncStopped;
            if (isSyncStopped) {
                Signum.getBlockchainProcessor().setGetMoreBlocksPause(true);
                Signum.getBlockchainProcessor().setBlockImporterPause(true);
                syncButton.setText("Resume Sync");
                syncButton.setIcon(IconFontSwing.buildIcon(FontAwesome.PLAY, 18, iconColor));
                if (guiTimer != null) {
                    guiTimer.stop();
                }
            } else {
                Signum.getBlockchainProcessor().setGetMoreBlocksPause(false);
                Signum.getBlockchainProcessor().setBlockImporterPause(false);
                syncButton.setText("Pause Sync");
                syncButton.setIcon(IconFontSwing.buildIcon(FontAwesome.PAUSE, 18, iconColor));
                if (guiTimer != null) {
                    guiTimer.start();
                }
            }
            updateTitle();
        });
        // Test mode only
        autoPopOffTestButton.addActionListener(e -> showAutoPopOffTestDialog());
        popOffToTestButton.addActionListener(e -> showPopOffToTestDialog());
        processForkTestButton.addActionListener(e -> showProcessForkTestDialog());
        manualTrimButton.addActionListener(e -> {
            String message = "This operation will permanently delete historical data from the derived tables older than "
                    + Constants.MAX_ROLLBACK
                    + " blocks from the current height. This can free up disk space but makes it impossible to roll back the blockchain past this point.\n\n"
                    + "Are you sure you want to proceed with trimming the database?";
            int choice = JOptionPane.showConfirmDialog(SignumGUI.this,
                    message,
                    "Confirm Database Trim",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                manualTrim();
            }
        });
        // End test mode only
        // popOffMaxButton.addActionListener(e -> popOff(0));

        JPanel buttonsContainer = new JPanel();
        buttonsContainer.setLayout(new BoxLayout(buttonsContainer, BoxLayout.Y_AXIS));
        buttonsContainer.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));

        JPanel normalButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        testButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));

        buttonsContainer.add(normalButtons);

        JPanel testButtonsSeparatorPanel = new JPanel(new GridBagLayout());
        testButtonsSeparatorPanel.setBorder(BorderFactory.createEmptyBorder(5, 20, 5, 0));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(0, 0, 0, 5);
        testButtonsSeparatorPanel.add(new JLabel("Test Buttons"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        testButtonsSeparatorPanel.add(new JSeparator(), gbc);
        buttonsContainer.add(testButtonsSeparatorPanel);
        buttonsContainer.add(testButtons);

        File phoenixIndex = new File("html/ui/phoenix/index.html");

        shutdownButton.addActionListener(e -> {
            if (shutdownInProgress.get()) {
                // If shutdown is already in progress, show the force shutdown dialog
                // immediately.
                showForceShutdownDialog(
                        "Shutdown is already in progress.\nDo you want to force it to close?",
                        "Force Shutdown?");
            } else {
                if (JOptionPane.showConfirmDialog(SignumGUI.this,
                        "This will stop the node. Are you sure?", "Shutdown Node",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
                    new Thread(this::shutdown).start();
                }
            }
        });

        // Initialize the shutdown monitor timer.
        shutdownMonitor = new Timer(240000, ev -> {
            // This action is performed every 10 seconds during shutdown.
            if (shutdownInProgress.get()) {
                showForceShutdownDialog(
                        "The node is taking a long time to shut down.\nDo you want to force it to close?",
                        "Force Shutdown?");
            }
        });
        shutdownMonitor.setRepeats(false); // Only show the dialog once per timer fire.
        shutdownMonitor.stop();
        /*
         * restartButton.addActionListener(e -> {
         * 
         * if (JOptionPane.showConfirmDialog(SignumGUI.this,
         * "This will restart the node. Are you sure?", "Restart node",
         * JOptionPane.YES_NO_OPTION,
         * JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
         * restart();
         * }
         * });
         */

        if (phoenixIndex.isFile() && phoenixIndex.exists()) {
            normalButtons.add(openPhoenixButton);
        }
        File classicIndex = new File("html/ui/classic/index.html");
        if (classicIndex.isFile() && classicIndex.exists()) {
            normalButtons.add(openClassicButton);
        }
        normalButtons.add(editConfButton);
        normalButtons.add(openApiButton);

        normalButtons.add(popOff10Button);
        popOff10Button.setVisible(showPopOff);
        normalButtons.add(popOff100Button);
        popOff100Button.setVisible(showPopOff);

        normalButtons.add(syncButton);
        normalButtons.add(dbCheckButton);
        normalButtons.add(manualTrimButton);
        normalButtons.add(shutdownButton);

        testButtons.add(autoPopOffTestButton);
        autoPopOffTestButton.setVisible(showPopOff);
        testButtons.add(popOffToTestButton);
        popOffToTestButton.setVisible(showPopOff);
        testButtons.add(processForkTestButton);
        processForkTestButton.setVisible(showPopOff);

        toolBar.add(buttonsContainer);
        toolBar.add(Box.createHorizontalGlue());
        toolBar.add(checkboxPanel);
        toolBar.add(Box.createHorizontalStrut(10));

        openPheonixWalletItem.addActionListener(e -> openWebUi("/phoenix"));
        openClassicWalletItem.addActionListener(e -> openWebUi("/classic"));
        showItem.addActionListener(e -> showWindow());
        shutdownItem.addActionListener(e -> shutdown());

        popupMenu.add(openClassicWalletItem);
        popupMenu.add(showItem);
        popupMenu.add(shutdownItem);

        getContentPane().validate();

        try {
            String newIconLocation = Signum.getPropertyService().getString(Props.ICON_LOCATION);
            if (!newIconLocation.equals(iconLocation)) {
                // update the icon
                iconLocation = newIconLocation;
                setIconImage(ImageIO.read(getClass().getResourceAsStream(iconLocation)));
            }
            TrayIcon newTrayIcon = new TrayIcon(
                    Toolkit.getDefaultToolkit().createImage(SignumGUI.class.getResource(iconLocation)), "Signum Node",
                    popupMenu);
            newTrayIcon.setImage(
                    newTrayIcon.getImage().getScaledInstance(newTrayIcon.getSize().width, -1, Image.SCALE_SMOOTH));
            if (phoenixIndex.isFile() && phoenixIndex.exists()) {
                newTrayIcon.addActionListener(e -> openWebUi("/phoenix"));
            }

            SystemTray systemTray = SystemTray.getSystemTray();
            systemTray.add(newTrayIcon);

            newTrayIcon.displayMessage("Signum Running",
                    "Signum is running on background, use this icon to interact with it.", MessageType.INFO);

            return newTrayIcon;
        } catch (Exception e) {
            LOGGER.info("Could not create tray icon");
            return null;
        }
    }

    private void showWindow() {
        setVisible(true);
    }

    private void popOff(int blocks) {
        new Thread(() -> {
            Signum.getBlockchainProcessor().popOff(blocks);
            // Signum.getBlockchainProcessor().popOffTest(blocks);
        }).start();
    }

    // Trim DB in test mode
    /*
     * This method initiates a trim operation.
     * It runs the trim operation in a separate thread to avoid blocking the GUI.
     */
    private void manualTrim() {
        new Thread(() -> Signum.getBlockchainProcessor().manualTrim()).start();
    }

    private void showAutoPopOffTestDialog() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        String description = "<html><p style='width: 350px;'>"
                + "This test simulates a node getting stuck by forcing a <b>BlockNotAcceptedException</b> at a future block height. "
                + "This triggers the automatic pop-off mechanism, which attempts to resolve the issue by popping off an increasing number of blocks after each failure."
                + "</p></html>";
        panel.add(new JLabel(description), gbc);

        // Height Offset
        SpinnerNumberModel heightOffsetModel = new SpinnerNumberModel(500, 1, 10000, 1);
        JSpinner heightOffsetSpinner = new JSpinner(heightOffsetModel);
        JLabel heightOffsetLabel = new JLabel("Start exceptions at height offset:");
        addInfoTooltip(heightOffsetLabel,
                "The number of blocks from the current height to start forcing exceptions. Default: 500.");
        gbc.gridwidth = 1;
        panel.add(heightOffsetLabel, gbc);
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        panel.add(heightOffsetSpinner, gbc);

        // Exception Count
        SpinnerNumberModel countModel = new SpinnerNumberModel(10, 1, 100, 1);
        JSpinner countSpinner = new JSpinner(countModel);
        JLabel countLabel = new JLabel("Number of exceptions to force:");
        addInfoTooltip(countLabel,
                "The number of times the exception will be thrown, triggering consecutive pop-offs. Default: 10.");
        gbc.gridwidth = 1;
        panel.add(countLabel, gbc);
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        panel.add(countSpinner, gbc);

        int result = JOptionPane.showConfirmDialog(this, panel, "Auto Pop-Off Test Configuration",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            int heightOffset = (Integer) heightOffsetSpinner.getValue();
            int count = (Integer) countSpinner.getValue();
            new Thread(() -> Signum.getBlockchainProcessor().autoPopOffTest(heightOffset, count)).start();
        }
    }

    private void showPopOffToTestDialog() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        String description = "<html><p style='width: 350px;'>"
                + "This test manually pops off a specified number of blocks from the end of the local blockchain. "
                + "This is useful for simulating a fork or recovering from a stuck state. The operation will not pop off blocks beyond the minimum rollback height ("
                + Constants.MAX_ROLLBACK + " blocks)."
                + "</p></html>";
        panel.add(new JLabel(description), gbc);

        SpinnerNumberModel blocksModel = new SpinnerNumberModel(1000, 1, 100000, 10);
        JSpinner blocksSpinner = new JSpinner(blocksModel);
        JLabel blocksLabel = new JLabel("Number of blocks to pop off:");
        addInfoTooltip(blocksLabel, "The number of blocks to remove from the end of your blockchain. Default: 1000.");

        gbc.gridwidth = 1;
        panel.add(blocksLabel, gbc);
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        panel.add(blocksSpinner, gbc);

        int result = JOptionPane.showConfirmDialog(this, panel, "Pop-Off Test Configuration",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            int blocks = (Integer) blocksSpinner.getValue();
            new Thread(() -> Signum.getBlockchainProcessor().popOffToTest(blocks)).start();
        }
    }

    private void showProcessForkTestDialog() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        String description = "<html><p style='width: 350px;'>"
                + "This test simulates a blockchain fork. It first pops off a number of blocks (Fork Depth), "
                + "then generates a new, competing chain of a specified length (New Fork Length). "
                + "Finally, it re-introduces the original blocks to trigger and test the node's fork resolution mechanism."
                + "</p></html>";
        panel.add(new JLabel(description), gbc);

        // Fork Depth
        SpinnerNumberModel depthModel = new SpinnerNumberModel(1000, 1, 100000, 10);
        JSpinner depthSpinner = new JSpinner(depthModel);
        JLabel depthLabel = new JLabel("Fork Depth (blocks to pop off):");
        addInfoTooltip(depthLabel, "How many blocks to go back to start the fork. Default: 1000.");
        gbc.gridwidth = 1;
        panel.add(depthLabel, gbc);
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        panel.add(depthSpinner, gbc);

        // New Fork Length
        SpinnerNumberModel lengthModel = new SpinnerNumberModel(100, 1, 1000, 10);
        JSpinner lengthSpinner = new JSpinner(lengthModel);
        JLabel lengthLabel = new JLabel("New Fork Length (blocks to generate):");
        addInfoTooltip(lengthLabel, "The length of the new competing fork to generate. Default: 100.");
        gbc.gridwidth = 1;
        panel.add(lengthLabel, gbc);
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        panel.add(lengthSpinner, gbc);

        int result = JOptionPane.showConfirmDialog(this, panel, "Process Fork Test Configuration",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            int forkDepth = (Integer) depthSpinner.getValue();
            int newForkLength = (Integer) lengthSpinner.getValue();
            new Thread(() -> Signum.getBlockchainProcessor().processForkTest(forkDepth, newForkLength)).start();
        }
    }

    // End test mode only

    /*
     * private void restart() {
     * new Thread(() -> Signum.restart()).start();
     * }
     */

    private void editConf() {
        File file = new File(Signum.CONF_FOLDER, Signum.PROPERTIES_NAME);
        if (!file.exists()) {
            file = new File(Signum.CONF_FOLDER, Signum.DEFAULT_PROPERTIES_NAME);
            if (!file.exists()) {
                file = new File(Signum.DEFAULT_PROPERTIES_NAME);
            }
        }

        if (!file.exists()) {
            JOptionPane.showMessageDialog(this, "Could not find conf file: " + Signum.DEFAULT_PROPERTIES_NAME,
                    "File not found", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            Desktop.getDesktop().open(file);
        } catch (IOException e) {
            LOGGER.error("Could not edit conf file", e);
        }
    }

    private void openWebUi(String path) {
        try {
            PropertyService propertyService = Signum.getPropertyService();
            int port = propertyService.getInt(Props.API_PORT);
            String httpPrefix = propertyService.getBoolean(Props.API_SSL) ? "https://" : "http://";
            String address = httpPrefix + "localhost:" + port + path;
            try {
                Desktop.getDesktop().browse(new URI(address));
            } catch (Exception e) { // Catches parse exception or exception when opening browser
                LOGGER.error("Could not open browser", e);
                showMessage("Error opening web UI. Please open your browser and navigate to " + address);
            }
        } catch (Exception e) { // Catches error accessing PropertyService
            LOGGER.error("Could not access PropertyService", e);
            showMessage("Could not open web UI as could not read the configuration file.");
        }
    }

    private void initListeners() {
        BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
        blockchainProcessor.addListener(block -> onPeerCountChanged(), BlockchainProcessor.Event.PEER_COUNT_CHANGED);
        blockchainProcessor.addListener(block -> onNetVolumeChanged(), BlockchainProcessor.Event.NET_VOLUME_CHANGED);
        blockchainProcessor.addListener(block -> onBlockPopped(), BlockchainProcessor.Event.BLOCK_POPPED);
        blockchainProcessor.addListener(block -> onPopOffProgress(), BlockchainProcessor.Event.BLOCK_POPPED);
        blockchainProcessor.addListener(block -> updateTrimHeightLabel(),
                BlockchainProcessor.Event.TRIM_HEIGHT_CHANGED);
        blockchainProcessor.addListener(this::onTrimProgress,
                BlockchainProcessor.Event.TRIM_PROGRESS_UPDATED);
        blockchainProcessor.addListener(this::onBlockPushed, BlockchainProcessor.Event.BLOCK_PUSHED);
    }

    public void onPeerCountChanged() {
        BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
        Collection<Peer> allPeers = blockchainProcessor.getAllPeers();
        SwingUtilities.invokeLater(() -> updatePeerCount(allPeers));
    }

    public void onNetVolumeChanged() {
        BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
        long newDownloadedVolume = blockchainProcessor.getDownloadedVolume();
        SwingUtilities.invokeLater(() -> {
            uploadVolumeLabel.setText("▲ " + formatDataSize(blockchainProcessor.getUploadedVolume()));
            downloadVolumeLabel.setText("▼ " + formatDataSize(newDownloadedVolume));

            // Initial check for sync status before any timers start, to ensure the
            // sync_in_progress timer starts correctly.
            if (Signum.getBlockchain() != null) {
                Block lastBlock = Signum.getBlockchain().getLastBlock();
                if (lastBlock != null) {
                    Date blockDate = Convert.fromEpochTime(lastBlock.getTimestamp());
                    Date now = new Date();
                    long blockTime = Signum.getFluxCapacitor().getValue(FluxValues.BLOCK_TIME);
                    int missingBlocks = (int) ((now.getTime() - blockDate.getTime()) / (blockTime * 1000));
                    isSyncing = missingBlocks > 10;
                }
            }

            // Start the GUI timer only once, when the first download volume is received,
            // and if experimental features are enabled in the config.
            if (Signum.getPropertyService().getBoolean(Props.EXPERIMENTAL)
                    && blockchainProcessor.getDownloadedVolume() > 0
                    && !guiTimerStarted.getAndSet(true)) {
                startGuiTimer();
            }
        });
    }

    private void startGuiTimer() {
        guiTimer = new Timer(1000, e -> {
            if (Signum.getBlockchain() != null && Signum.getBlockchainProcessor() != null) {
                guiAccumulatedSyncTimeMs += 1000;
                totalTimeLabel.setText("🕒 " + formatDuration(guiAccumulatedSyncTimeMs));

                if (isSyncing) {
                    guiAccumulatedSyncInProgressTimeMs += 1000;
                }
                syncInProgressTimeLabel
                        .setText("🔄 " + formatDuration(guiAccumulatedSyncInProgressTimeMs));
                updateTimeLabelVisibility();
            }
        });
        guiTimer.start();
    }

    private void onBlockPopped() {
        SwingUtilities.invokeLater(() -> {
            updateLatestBlock(Signum.getBlockchain().getLastBlock());
        });
    }

    private void onPopOffProgress() {
        SwingUtilities.invokeLater(() -> {
            int remaining = Signum.getBlockchainProcessor().getPopOffBlocksCount();
            popOffBlocksLabel.setText("Pop off blocks: " + remaining);
            setPopOffLabelVisible(remaining > 0);
        });
    }

    private void setPopOffLabelVisible(boolean isVisible) {
        popOffBlocksLabel.setVisible(isVisible);
        popOffSeparator.setVisible(isVisible);
    }

    private void onTrimProgress(Block block) {
        // The 'block' parameter is a placeholder here, we get the real data from the
        // source.
        // In a real implementation, we might pass the table name through a custom event
        // object.
        String currentTable = Signum.getBlockchainProcessor().getCurrentlyTrimmingTable();
        int oldTrimHeight = Signum.getBlockchainProcessor().getCurrentTrimHeight().get();
        int newTrimHeight = Signum.getBlockchainProcessor().getLastTrimHeight().get();
        SwingUtilities.invokeLater(() -> {
            if (currentTable != null) {
                if (newTrimHeight > oldTrimHeight) {
                    trimHeightLabel.setText(String.format("Trim height: %d ➔ %d", oldTrimHeight, newTrimHeight));
                }
                trimHeightLabel.setForeground(Color.GREEN);
            } else {
                trimHeightLabel.setForeground(iconColor);
            }
        });
    }

    private void onBlockPushed(Block block) {
        if (block == null)
            return;
        SwingUtilities.invokeLater(() -> {
            updateLatestBlock(block);

            // Start the GUI timer only once, when the first block is pushed,
            // and if experimental features are enabled in the config.
            if (Signum.getPropertyService().getBoolean(Props.EXPERIMENTAL) && !guiTimerStarted.getAndSet(true)) {
                startGuiTimer();
            }
        });
    }

    public void startSignumWithGUI() {
        try {
            // signum.init();
            Signum.main(args);

            // Now that properties are loaded, set the correct values for the GUI
            showPopOff = Signum.getPropertyService().getBoolean(Props.EXPERIMENTAL);
            showMetrics = Signum.getPropertyService().getBoolean(Props.EXPERIMENTAL);
            boolean measurementActive = Signum.getPropertyService().getBoolean(Props.MEASUREMENT_ACTIVE);
            boolean experimentalActive = Signum.getPropertyService().getBoolean(Props.EXPERIMENTAL);
            boolean trimEnabled = Signum.getPropertyService().getBoolean(Props.DB_TRIM_DERIVED_TABLES);

            try {
                SwingUtilities.invokeLater(() -> {
                    metricsPanel.init();
                    showTrayIcon();

                    // Sync checkbox states with loaded properties
                    showPopOffCheckbox.setSelected(showPopOff);
                    showMetricsCheckbox.setSelected(showMetrics);

                    // Sync panel visibility with loaded properties
                    metricsPanel.setVisible(showMetrics);
                    if (measurementActive) {
                        measurementPanel.setVisible(true);
                    }
                    if (experimentalActive) {
                        experimentalPanel.setVisible(true);
                        timePanel.setVisible(true);
                    }

                    if (!trimEnabled) {
                        trimHeightLabel.setVisible(false);
                        trimSeparator.setVisible(false);
                    }
                });

                updateTitle();

                initListeners();
                if (Signum.getPropertyService().getBoolean(Props.EXPERIMENTAL)) {
                    // Initialize timers from the log file.
                    BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
                    if (blockchainProcessor != null) {
                        this.guiAccumulatedSyncTimeMs = blockchainProcessor.getAccumulatedSyncTimeMs();
                        this.guiAccumulatedSyncInProgressTimeMs = blockchainProcessor
                                .getAccumulatedSyncInProgressTimeMs();
                    }
                    // Update labels with initial values from log file
                    SwingUtilities.invokeLater(() -> {
                        totalTimeLabel.setText("🕒 " + formatDuration(guiAccumulatedSyncTimeMs));
                        syncInProgressTimeLabel
                                .setText("🔄 " + formatDuration(guiAccumulatedSyncInProgressTimeMs));
                        updateTimeLabelVisibility(); // Initial visibility check
                    });
                }
                if (Signum.getBlockchain() == null) {
                    onBrsStopped();
                } else {
                    // Initialize with the current last block
                    updateLatestBlock(Signum.getBlockchain().getLastBlock());
                }
            } catch (Exception t) {
                LOGGER.error("Could not determine if running in testnet mode", t);
            }
        } catch (Exception t) {
            LOGGER.error(FAILED_TO_START_MESSAGE, t);
            showMessage(FAILED_TO_START_MESSAGE);
            onBrsStopped();
        }

        updateTrimHeightLabel();

    }

    public static class PanelHeight {
        private final JPanel metricsPanel;
        private final JPanel topPanel;

        public PanelHeight(JPanel metricsPanel, JPanel topPanel) {
            this.metricsPanel = metricsPanel;
            this.topPanel = topPanel;
        }

        public void setHeight(int height) {
            if (metricsPanel != null && topPanel != null) {
                metricsPanel.setPreferredSize(new Dimension(metricsPanel.getWidth(), height));
                topPanel.revalidate();
            }
        }

        public int getHeight() {
            return metricsPanel != null ? metricsPanel.getHeight() : 0;
        }
    }

    private Timeline metricsPanelTimeline = null;

    private int metricsPanelExpandedHeight = -1;

    private void animateMetricsPanelTrident(boolean show) {
        if (metricsPanelTimeline != null && metricsPanelTimeline.getState() != TimelineState.IDLE) {
            metricsPanelTimeline.abort();
        }

        final int startHeight = metricsPanel.getHeight();
        metricsPanel.setVisible(true);
        topPanel.revalidate();

        if (metricsPanelExpandedHeight <= 0) {
            // csak egyszer számoljuk ki
            metricsPanelExpandedHeight = metricsPanel.getPreferredSize().height;
        }

        final int targetHeight = show ? metricsPanelExpandedHeight : 0;

        PanelHeight panelHeight = new PanelHeight(metricsPanel, topPanel);
        panelHeight.setHeight(startHeight);

        metricsPanelTimeline = Timeline.builder(panelHeight)
                .addPropertyToInterpolate("height", startHeight, targetHeight)
                .addCallback(new TimelineCallbackAdapter() {
                    @Override
                    public void onTimelineStateChanged(TimelineState oldState, TimelineState newState,
                            float durationFraction, float timelinePosition) {
                        if (newState == TimelineState.PLAYING_FORWARD && oldState == TimelineState.IDLE) {
                            metricsPanel.setVisible(true);
                        }
                        if (newState == TimelineState.DONE) {
                            metricsPanel.setVisible(show);
                            topPanel.revalidate();
                        }
                    }
                })
                .setEase(new Spline(0.5f))
                .setDuration(350)
                .build();

        metricsPanelTimeline.play();
    }

    private void updateTimeLabelVisibility() {
        boolean showTotalTime = guiAccumulatedSyncTimeMs != guiAccumulatedSyncInProgressTimeMs;
        totalTimeLabel.setVisible(showTotalTime);
        timeSeparatorLabel.setVisible(showTotalTime);
    }

    /**
     * This method is called when the Signum service is restarted.
     * It re-initializes the GUI components and updates the state to reflect the new
     * service instances.
     */
    /*
     * public void reinitOnRestart() {
     * // Re-register listeners to the new service instances
     * initListeners();
     * 
     * // Manually update the UI with the current state after restart
     * updateTitle();
     * if (Signum.getBlockchain() != null) {
     * updateLatestBlock(Signum.getBlockchain().getLastBlock());
     * }
     * updatePeerCount(Peers.getAllPeers().size(), Peers.getActivePeers().size());
     * }
     */

    private void updateTitle() {
        String networkName = Signum.getPropertyService().getString(Props.NETWORK_NAME);
        String title = this.programName + " [" + networkName + "] " + this.version;
        if (isSyncStopped) {
            title += " (Sync paused)";
        }
        final String finalTitle = title;
        SwingUtilities.invokeLater(() -> setTitle(finalTitle));
        if (trayIcon != null) {
            trayIcon.setToolTip(finalTitle);
        }
    }

    private void updateLatestBlock(Block block) {
        if (block == null) {
            return;
        }
        Date blockDate = Convert.fromEpochTime(block.getTimestamp());
        latestBlockHeightLabel.setText("Latest block: " + block.getHeight());
        latestBlockTimestampLabel.setText("Timestamp: " + DATE_FORMAT.format(blockDate));

        Date now = new Date();
        long blockTime = Signum.getFluxCapacitor().getValue(FluxValues.BLOCK_TIME);

        int missingBlocks = (int) ((now.getTime() - blockDate.getTime()) / (blockTime * 1000));

        // Start syncing if more than 10 block times behind, stop if 1 or less.
        // This is more reliable than peer height difference, especially at startup.
        if (!isSyncing && missingBlocks > 10) {
            isSyncing = true;
        } else if (isSyncing && missingBlocks <= 1) {
            isSyncing = false;
        }

        if (missingBlocks < 0) {
            missingBlocks = 0;
        }

        float prog = 0;
        int totalBlocks = block.getHeight() + missingBlocks;
        if (totalBlocks > 0) {
            // Use 100.0f to force floating-point division, preserving decimal places
            prog = (float) block.getHeight() * 100.0f / totalBlocks;
        }

        if (prog > 100.0f) {
            prog = 100.0f;
        }
        syncProgressBar.setValue((int) prog);
        syncProgressBar.setPreferredSize(progressBarSize2);
        if (Signum.getBlockchainProcessor().isScanning()) {
            syncProgressBar.setString(String.format("Rescan: %.2f %%", prog));
            return;
        }
        syncProgressBar.setMaximumSize(progressBarSize2);
        syncProgressBar.setMinimumSize(progressBarSize2);
        syncProgressBar.setString(String.format("%.2f %%", prog));
    }

    private void updatePeerCount(Collection<Peer> peers) {
        long activeCount = peers.stream().filter(p -> p.getState() != Peer.State.NON_CONNECTED).count();
        long allKnownCount = peers.size();
        long blacklistedCount = peers.stream().filter(Peer::isBlacklisted).count();

        // The label previously for 'connected' now shows 'active' peers.
        connectedPeersLabel.setText(String.valueOf(activeCount));
        peersCountLabel.setText(String.valueOf(allKnownCount));
        blacklistedPeersLabel.setText(blacklistedCount + "");
    }

    private String formatDataSize(double bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        String[] units = { "B", "KB", "MB", "GB", "TB", "PB", "EB" };
        int unitIndex = 0;
        while (bytes >= 1024 && unitIndex < units.length - 1) {
            bytes /= 1024;
            unitIndex++;
        }
        return String.format("%.2f %s", bytes, units[unitIndex]);
    }

    private void updateTrimHeightLabel() {
        SwingUtilities.invokeLater(() -> {
            trimHeightLabel.setText("Trim height: " + Signum.getBlockchainProcessor().getCurrentTrimHeight());
        });
    }

    private void onBrsStopped() {
        SwingUtilities.invokeLater(() -> setTitle(getTitle() + " (STOPPED)"));
        if (trayIcon != null)
            trayIcon.setToolTip(trayIcon.getToolTip() + " (STOPPED)");
    }

    private void sendJavaOutputToTextArea(JTextArea textArea) {
        System.setOut(new PrintStream(new TextAreaOutputStream(textArea, System.out)));
        System.setErr(new PrintStream(new TextAreaOutputStream(textArea, System.err)));
    }

    private void showMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            System.err.println("Showing message: " + message);
            JOptionPane.showMessageDialog(this, message, "Signum Message", JOptionPane.ERROR_MESSAGE);
        });
    }

    /**
     * Formats a duration in milliseconds into a human-readable string (e.g.,
     * "1y:02d:03h:04m:05s").
     * Omits larger units if they are zero.
     *
     * @param millis The duration in milliseconds.
     * @return A formatted string representing the duration.
     */
    private String formatDuration(long millis) {
        if (millis <= 0) {
            return "0s";
        }

        long totalSeconds = millis / 1000;

        final long SEC_PER_MINUTE = 60;
        final long SEC_PER_HOUR = SEC_PER_MINUTE * 60;
        final long SEC_PER_DAY = SEC_PER_HOUR * 24;
        final long SEC_PER_YEAR = SEC_PER_DAY * 365; // Approximation

        long years = totalSeconds / SEC_PER_YEAR;
        long secondsAfterYears = totalSeconds % SEC_PER_YEAR;

        long days = secondsAfterYears / SEC_PER_DAY;
        long secondsAfterDays = secondsAfterYears % SEC_PER_DAY;

        long hours = secondsAfterDays / SEC_PER_HOUR;
        long secondsAfterHours = secondsAfterDays % SEC_PER_HOUR;

        long minutes = secondsAfterHours / SEC_PER_MINUTE;
        long seconds = secondsAfterHours % SEC_PER_MINUTE;

        StringBuilder sb = new StringBuilder();
        if (years > 0) {
            sb.append(years).append("y:");
        }
        if (days > 0 || sb.length() > 0) {
            sb.append(String.format(sb.length() > 0 ? "%02d" : "%d", days)).append("d:");
        }
        if (hours > 0 || sb.length() > 0) {
            sb.append(String.format(sb.length() > 0 ? "%02d" : "%d", hours)).append("h:");
        }
        if (minutes > 0 || sb.length() > 0) {
            sb.append(String.format(sb.length() > 0 ? "%02d" : "%d", minutes)).append("m:");
        }
        sb.append(String.format(sb.length() > 0 ? "%02d" : "%d", seconds)).append("s");

        return sb.toString();
    }

    private static class TextAreaOutputStream extends OutputStream {
        private final JTextArea textArea;
        private final PrintStream actualOutput;

        private StringBuilder lineBuilder = new StringBuilder();

        private TextAreaOutputStream(JTextArea textArea, PrintStream actualOutput) {
            this.textArea = textArea;
            this.actualOutput = actualOutput;
        }

        @Override
        public void write(int b) {
            writeString(new String(new byte[] { (byte) b }));
        }

        @Override
        public void write(byte[] b) {
            writeString(new String(b));
        }

        @Override
        public void write(byte[] b, int off, int len) {
            writeString(new String(b, off, len));
        }

        private void writeString(String string) {
            lineBuilder.append(string);
            String line = lineBuilder.toString();
            if (line.contains("\n")) {
                actualOutput.print(line);
                if (textArea != null)
                    SwingUtilities.invokeLater(() -> textArea.append(line));
                lineBuilder.delete(0, lineBuilder.length());
            }
        }
    }

    // Removed deprecated SignaGUISecurityManager (Java 17+)

    /**
     * Unified exit method replacing SecurityManager-based exit interception.
     * Use this instead of System.exit() for graceful shutdown from the GUI.
     */
    private void safeExit(int status) {
        try {
            // Place any confirmation dialogs or cleanup here if needed.
        } finally {
            System.exit(status);
        }
    }
}
