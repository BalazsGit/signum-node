package brs.gui;

import java.awt.*;
import java.awt.TrayIcon.MessageType;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JDialog;
import brs.gui.animations.RotatingSvgIcon;
import javax.swing.JDialog;
import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;
import java.awt.geom.Rectangle2D;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.LookAndFeel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.net.URL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brs.Signum;
import brs.BlockchainProcessor;
import brs.Constants;
import brs.gui.util.CustomDrawings;
import brs.Block;
import brs.peer.Peer;
import brs.fluxcapacitor.FluxValues;
import brs.props.PropertyService;
import brs.props.Props;
import brs.util.DurationFormatter;
import brs.util.Convert;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

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
    private JLabel elapsedTimeLabel = null;
    private JSeparator elapsedTimeSeparator = null;
    private Timer elapsedTimeTimer = null;
    private long elapsedTimeCounter = 0;
    private JPanel infoPanel;
    private JProgressBar syncProgressBar = null;
    private JScrollPane textScrollPane = null;
    private String programName = null;
    private String version = null;
    private final Color iconColor;
    private final Color contrastRed = new Color(255, 120, 120);

    private JLabel connectedPeersLabel;
    private JLabel peersCountLabel;
    private JLabel blacklistedPeersLabel;
    private JLabel uploadVolumeLabel;
    private JLabel downloadVolumeLabel;
    private JLabel trimHeightLabel;
    private JSeparator trimSeparator;
    private JLabel popOffBlockCountLabel;
    private JLabel popOffBlockHeightLabel;
    private JSeparator popOffSeparator1;
    private JSeparator popOffSeparator2;
    private boolean showPopOff = false;
    private boolean isSyncStopped = false;
    private boolean isShuttingDown = false;

    private boolean measurementActive = false;
    private boolean experimentalActive = false;
    private boolean trimEnabled = false;
    private boolean autoResolveEnabled = false;

    private JButton openPhoenixButton;
    private JButton openClassicButton;
    private JButton openApiButton;
    private JButton editConfButton;
    private JButton popOff10Button;
    private JButton popOff100Button;
    private JButton dbCheckButton;
    private JButton syncButton;
    private JButton shutdownButton;
    // private JButton restartButton;

    private MetricsPanel metricsPanel;

    private JComponent popOffToggle;
    private JComponent hamburgerMenu;
    private JLabel measurementLabel;
    private JLabel experimentalLabel;
    private JLabel trimLabel;
    private JLabel autoResolveLabel;
    private JSeparator measurementSeparator;
    private JSeparator experimentalSeparator;
    private JSeparator trimIconSeparator;
    private JSeparator autoResolveSeparator;
    private JPanel measurementPanel;
    private JPanel experimentalPanel;
    private JPanel trimPanel;
    private JPanel autoResolvePanel;

    private final AtomicBoolean isDbCheckRunning = new AtomicBoolean(false);

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

    private JDialog waitDialog;

    private JLabel createLabel(String text, Color color, String tooltip) {
        return createLabel(text, color, tooltip, null);
    }

    private JLabel createLabel(String text, Color color, String tooltip, String title) {
        JLabel label = new JLabel(text);
        if (color != null) {
            label.setForeground(color);
        }
        if (tooltip != null) {
            String shortTooltip = tooltip.split("\n")[0];
            label.setToolTipText(shortTooltip);
            addInfoTooltip(label, tooltip, title);
        }
        return label;
    }

    private void addInfoTooltip(JLabel label, String text, String titleOverride) {
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    String title;
                    if (titleOverride != null) {
                        title = label.getText() + " " + titleOverride;
                    } else {
                        title = label.getText();
                        // Remove trailing colon for a cleaner title
                        if (title.endsWith(":")) {
                            title = title.substring(0, title.length() - 1);
                        }
                    }
                    // Wrap the text in HTML to control the width of the dialog.
                    String htmlText = "<html><body><p style='width: 300px;'>" + text.replace("\n", "<br>")
                            + "</p></body></html>";
                    JOptionPane.showMessageDialog(SignumGUI.this, htmlText, title, JOptionPane.PLAIN_MESSAGE);
                }
            }
        });
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
                UIManager.put("nimbusDisabledText", new Color(90, 90, 90));
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

                try {
                    int lineCount = getLineCount();
                    if (lineCount > OUTPUT_MAX_LINES) {
                        int endOffset = getLineEndOffset(lineCount - OUTPUT_MAX_LINES - 1);
                        replaceRange("", 0, endOffset);
                    }
                } catch (Exception e) {
                    // ignore
                }
                if (textScrollPane != null) {
                    JScrollBar vertical = textScrollPane.getVerticalScrollBar();
                    vertical.setValue(vertical.getMaximum());
                }
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

        elapsedTimeSeparator = new JSeparator(SwingConstants.VERTICAL);
        elapsedTimeSeparator.setPreferredSize(verticalSeparatorSize);
        elapsedTimeLabel = new JLabel("Elapsed Time: -");
        String elapsedTooltip = "Displays the time elapsed in seconds since the last block was generated.\n\n"
                + "This counter resets every time a new block is received. Since the target block time is 240 seconds (4 minutes), this helps visualize how long it has been since the last network update.";
        addInfoTooltip(elapsedTimeLabel, elapsedTooltip);
        elapsedTimeSeparator.setVisible(false);
        elapsedTimeLabel.setVisible(false);
        latestBlockInfoPanel.add(elapsedTimeSeparator);
        latestBlockInfoPanel.add(elapsedTimeLabel);

        String blockInfoTooltip = "Displays critical information about the most recent block processed by your node. This includes:\n\n"
                + "- Latest block: The sequential number of the latest block synchronized by your node.\n"
                + "- Timestamp: The date and time when the block was generated by a miner.\n\n"
                + "This information is essential for confirming that your node is connected to the network and processing new blocks as they are created.";
        addInfoTooltip(latestBlockHeightLabel, blockInfoTooltip);
        addInfoTooltip(latestBlockTimestampLabel, blockInfoTooltip);
        metricsPanel = new MetricsPanel(this);
        metricsPanel.setVisible(false);

        trimSeparator = new JSeparator(SwingConstants.VERTICAL);
        trimSeparator.setPreferredSize(verticalSeparatorSize);
        String trimTooltip = "The minimum height to which the blockchain can be rolled back. Older data is pruned to save space.\n"
                + "Trimming occurs every " + brs.Constants.TRIM_PERIOD + " blocks.\n\n"
                + "If 'est.' (estimated) is shown, the actual trim height is unknown (e.g. after restart),\n"
                + "so it is calculated based on the trim period.";
        trimHeightLabel = createLabel("Trim height: -", null, trimTooltip);

        trimSeparator.setVisible(false);
        trimHeightLabel.setVisible(false);
        latestBlockInfoPanel.add(trimSeparator);
        latestBlockInfoPanel.add(trimHeightLabel);

        popOffSeparator1 = new JSeparator(SwingConstants.VERTICAL);
        popOffSeparator1.setPreferredSize(verticalSeparatorSize);

        String popOffCountTooltip = "Shows the number of blocks remaining to be removed from the blockchain during a 'pop-off' operation.\n\nThis counter appears only when a pop-off is in progress and helps monitor its advancement.";
        popOffBlockCountLabel = createLabel("Pop off blocks: 0", null, popOffCountTooltip);

        popOffSeparator2 = new JSeparator(SwingConstants.VERTICAL);
        popOffSeparator2.setPreferredSize(verticalSeparatorSize);

        String popOffHeightTooltip = "Displays the target block height after the pop-off operation completes, along with the current block height before the pop-off.\n\nThis information is crucial for understanding the state of your blockchain during a pop-off, which is used to resolve forks or other issues by reverting to a previous state.";
        popOffBlockHeightLabel = createLabel("- 🡸 -", null, popOffHeightTooltip);

        latestBlockInfoPanel.add(popOffSeparator1);
        latestBlockInfoPanel.add(popOffBlockCountLabel);
        latestBlockInfoPanel.add(popOffSeparator2);
        latestBlockInfoPanel.add(popOffBlockHeightLabel);
        setPopOffLabelVisible(false);

        // === Add toggle to toolBar ===
        popOffToggle = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                (showPopOff ? CustomDrawings.Chevron.LEFT : CustomDrawings.Chevron.RIGHT)
                        .draw((Graphics2D) g, getWidth(), getHeight(), iconColor);
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(16, 20);
            }

            @Override
            public Dimension getMaximumSize() {
                return getPreferredSize();
            }
        };
        popOffToggle.setToolTipText("Toggle Pop-off buttons");
        popOffToggle.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showPopOff = !showPopOff;
                popOff10Button.setVisible(showPopOff);
                popOff100Button.setVisible(showPopOff);
                popOffToggle.repaint();
            }
        });

        // Hamburger Menu
        hamburgerMenu = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                CustomDrawings.HAMBURGER.draw((Graphics2D) g, getWidth(), getHeight(), iconColor);
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(16, 20);
            }

            @Override
            public Dimension getMaximumSize() {
                return getPreferredSize();
            }
        };
        hamburgerMenu.setToolTipText("Menu");

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.add(toolBar);
        topPanel.add(metricsPanel);

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

        MouseAdapter peersMouseAdapter = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isRightMouseButton(e)) {
                    PeersDialog.showPeersDialog(SignumGUI.this);
                }
            }
        };

        tooltip = "Connected Peers: The number of peers with a stable, established connection to your node.";
        connectedPeersLabel = new JLabel("0");
        connectedPeersLabel.setToolTipText(tooltip);
        tooltip = "Total Discovered Peers: The total number of peers your node has ever discovered, including active, disconnected, and blacklisted ones.";
        peersCountLabel = new JLabel("0"); // Represents 'All Known' peers
        peersCountLabel.setToolTipText(tooltip);
        tooltip = "Blacklisted Peers: The number of peers that have been temporarily banned for sending invalid data or other network violations.";
        blacklistedPeersLabel = new JLabel("0");
        blacklistedPeersLabel.setToolTipText(tooltip);

        peersPanel.add(new JLabel("Peers: "));
        peersPanel.add(connectedPeersLabel);
        peersPanel.add(new JLabel(" / "));
        peersPanel.add(peersCountLabel);
        peersPanel.add(new JLabel(" (BL: "));
        peersPanel.add(blacklistedPeersLabel);
        peersPanel.add(new JLabel(")"));

        // Add peersPanel
        peersPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        peersPanel.addMouseListener(peersMouseAdapter);
        peersPanel.setToolTipText("Click to see detailed peer information.");

        for (Component comp : peersPanel.getComponents()) {
            comp.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            comp.addMouseListener(peersMouseAdapter);
        }

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
        measurementSeparator.setMaximumSize(verticalSeparatorSize);
        tooltip = "Performance measurement is active.\n"
                + "Detailed synchronization data is being collected for each block and saved to:\n"
                + "- measurement/sync_measurement.csv\n"
                + "- measurement/sync_progress.csv\n" + "for analysis."
                + "\n\nEnabled by property: node.measurementActive = true";
        measurementLabel = createLabel("🔬︎", null, tooltip, "MEASUREMENT");
        measurementLabel.setFont(measurementLabel.getFont().deriveFont(18.0f));
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
        experimentalSeparator.setMaximumSize(verticalSeparatorSize);
        tooltip = "Experimental feature is enabled.\n" + "Simplified data is being collected and saved to:\n"
                + "- measurement/sync_progress.csv\n" + "for analysis."
                + "\n\nEnabled by property: node.experimental = true";
        experimentalLabel = createLabel("⚗", null, tooltip, "EXPERIMENTAL");
        experimentalLabel.setFont(experimentalLabel.getFont().deriveFont(18.0f));
        experimentalPanel.setVisible(false);

        experimentalPanel.add(experimentalLabel);
        experimentalPanel.add(Box.createHorizontalStrut(5));
        experimentalPanel.add(experimentalSeparator);
        experimentalPanel.add(Box.createHorizontalStrut(5));

        // Add experimentalPanel
        gbc.gridx = 6;
        infoPanel.add(experimentalPanel, gbc);

        // --- Trim ---
        trimPanel = new JPanel();
        trimPanel.setLayout(new BoxLayout(trimPanel, BoxLayout.X_AXIS));
        trimPanel.setOpaque(false);
        trimIconSeparator = new JSeparator(SwingConstants.VERTICAL);
        trimIconSeparator.setPreferredSize(verticalSeparatorSize);
        trimIconSeparator.setMaximumSize(verticalSeparatorSize);
        tooltip = "Automatic table trimming is active.\n" +
                "Derived tables are being periodically pruned to save disk space.\n" +
                "This happens every " + (brs.Constants.MAX_ROLLBACK * 10) + " blocks."
                + "\n\nEnabled by property: DB.trimDerivedTables = true";
        trimLabel = createLabel("✂", null, tooltip, "TRIM");
        trimLabel.setFont(trimLabel.getFont().deriveFont(18.0f));
        trimPanel.setVisible(false);

        trimPanel.add(trimLabel);
        trimPanel.add(Box.createHorizontalStrut(5));
        trimPanel.add(trimIconSeparator);
        trimPanel.add(Box.createHorizontalStrut(5));

        // Add trimPanel
        gbc.gridx = 7;
        infoPanel.add(trimPanel, gbc);

        // --- Auto Resolve ---
        autoResolvePanel = new JPanel();
        autoResolvePanel.setLayout(new BoxLayout(autoResolvePanel, BoxLayout.X_AXIS));
        autoResolvePanel.setOpaque(false);
        autoResolveSeparator = new JSeparator(SwingConstants.VERTICAL);
        autoResolveSeparator.setPreferredSize(verticalSeparatorSize);
        autoResolveSeparator.setMaximumSize(verticalSeparatorSize);
        tooltip = "Auto-Resolve is enabled.\n" +
                "If database inconsistency is detected at startup, the node will automatically attempt to resolve it by rolling back blocks."
                + "\n\nEnabled by property: node.autoConsistencyResolve = true";
        autoResolveLabel = createLabel("🔧", null, tooltip, "AUTO RESOLVE");
        autoResolveLabel.setFont(autoResolveLabel.getFont().deriveFont(18.0f));
        autoResolvePanel.setVisible(false);

        autoResolvePanel.add(autoResolveLabel);
        autoResolvePanel.add(Box.createHorizontalStrut(5));
        autoResolvePanel.add(autoResolveSeparator);
        autoResolvePanel.add(Box.createHorizontalStrut(5));

        // Add autoResolvePanel
        gbc.gridx = 8;
        infoPanel.add(autoResolvePanel, gbc);

        gbc.gridx = 9;
        gbc.weightx = 1.0; // Allow progress bar to take up remaining horizontal space
        gbc.fill = GridBagConstraints.HORIZONTAL; // Fill horizontally
        infoPanel.add(syncProgressBar, gbc);

        bottomPanel.add(latestBlockInfoPanel, BorderLayout.CENTER);
        bottomPanel.add(infoPanel, BorderLayout.LINE_END);

        try {
            java.io.InputStream iconStream = getClass().getResourceAsStream(iconLocation);
            if (iconStream != null) {
                setIconImage(ImageIO.read(iconStream));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        initGlassPane();

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (trayIcon == null) {
                    if (JOptionPane.showConfirmDialog(SignumGUI.this,
                            "This will stop the node. Are you sure?", "Exit and stop node",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
                        new Thread(SignumGUI.this::shutdown).start();
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

    private void initGlassPane() {
        JPanel glassPane = new GlassPane();
        setGlassPane(glassPane);
        glassPane.setVisible(true);
    }

    private void shutdown() {

        isShuttingDown = true;
        updateTitle();

        // The main node shutdown is handled by Signum.shutdown()
        // This is the most critical part.
        try {
            Signum.shutdown(false);
        } catch (Throwable t) {
            // Signum.shutdown() is designed to handle its own errors and logging via
            // ShutdownManager.
            // This catch block is a final safeguard.
            LOGGER.error("Unexpected error during Signum core shutdown", t);
        }

        // The rest is GUI resource cleanup. We'll do a best-effort cleanup.
        if (elapsedTimeTimer != null) {
            try {
                elapsedTimeTimer.stop();
            } catch (Throwable t) {
                LOGGER.warn("Error stopping elapsed time timer", t);
            }
        }
        if (trayIcon != null && SystemTray.isSupported()) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (Throwable t) {
                LOGGER.warn("Error removing tray icon", t);
            }
        }
        if (metricsPanel != null) {
            try {
                metricsPanel.shutdown();
            } catch (Throwable t) {
                LOGGER.warn("Error shutting down metrics panel", t);
            }
        }

        // Finally, exit the application.
        System.exit(0);
    }

    private void showTrayIcon() {
        if (trayIcon == null) { // Don't start running in tray twice
            trayIcon = createTrayIcon();
        }
    }

    private TrayIcon createTrayIcon() {
        PopupMenu popupMenu = new PopupMenu();

        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));

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
        dbCheckButton = new JButton("Database check",
                IconFontSwing.buildIcon(FontAwesome.DATABASE, 18, iconColor));
        syncButton = new JButton("Stop Sync",
                IconFontSwing.buildIcon(FontAwesome.PAUSE, 18, iconColor));
        /*
         * restartButton = new JButton("Restart",
         * IconFontSwing.buildIcon(FontAwesome.REFRESH, 18, iconColor));
         */
        shutdownButton = new JButton("Shutdown",
                IconFontSwing.buildIcon(FontAwesome.POWER_OFF, 18, iconColor));
        // TODO: find a way to actually store permanently the max block available to
        // pop-off, otherwise we can break it
        // JButton popOffMaxButton = new JButton("Pop off max",
        // IconFontSwing.buildIcon(FontAwesome.FAST_BACKWARD, 18, iconColor));

        addInfoTooltip(openPhoenixButton, "Opens the modern Phoenix Wallet in your default web browser.");
        addInfoTooltip(openClassicButton, "Opens the Classic Wallet in your default web browser.");
        addInfoTooltip(openApiButton, "Opens the interactive API documentation in your default web browser.");
        addInfoTooltip(editConfButton,
                "Opens the node's configuration file (node.properties or node-default.properties) in your default text editor for easy modification.");
        addInfoTooltip(popOff10Button,
                "Removes the last 10 blocks from your local blockchain. This can help resolve a local fork if your node is stuck.");
        addInfoTooltip(popOff100Button,
                "Removes the last 100 blocks from your local blockchain. Use this if a smaller pop-off does not resolve a fork.");
        addInfoTooltip(dbCheckButton,
                "Performs a manual consistency check on the database to ensure data integrity.");
        addInfoTooltip(syncButton,
                "Toggles the synchronization process. 'Pause Sync' pauses the downloading and processing of new blocks. 'Resume Sync' continues the process.");
        addInfoTooltip(shutdownButton,
                "Safely stops the Signum node application. This ensures all data is saved correctly and prevents potential database corruption. A confirmation dialog will be shown before shutting down.");

        openPhoenixButton.addActionListener(e -> openWebUi("/phoenix"));
        openClassicButton.addActionListener(e -> openWebUi("/classic"));
        openApiButton.addActionListener(e -> openWebUi("/api-doc"));
        editConfButton.addActionListener(e -> editConf());
        popOff10Button.addActionListener(e -> popOff(10));
        popOff100Button.addActionListener(e -> popOff(100));
        // popOffMaxButton.addActionListener(e -> popOff(0));

        File phoenixIndex = new File("html/ui/phoenix/index.html");
        File classicIndex = new File("html/ui/classic/index.html");

        dbCheckButton.addActionListener(e -> dbCheckAction());

        syncButton.addActionListener(e -> syncButtonAction());
        shutdownButton.addActionListener(e -> shutdownAction());
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
            leftButtons.add(openPhoenixButton);
        }
        if (classicIndex.isFile() && classicIndex.exists()) {
            leftButtons.add(openClassicButton);
        }
        leftButtons.add(editConfButton);
        leftButtons.add(openApiButton);

        leftButtons.add(popOff10Button);
        popOff10Button.setVisible(showPopOff);
        leftButtons.add(popOff100Button);
        popOff100Button.setVisible(showPopOff);
        // leftButtons.add(popOffMaxButton);

        leftButtons.add(dbCheckButton);
        leftButtons.add(syncButton);

        // leftButtons.add(restartButton);
        leftButtons.add(shutdownButton);
        leftButtons.add(popOffToggle);

        toolBar.add(leftButtons);
        toolBar.add(Box.createHorizontalGlue());
        toolBar.add(hamburgerMenu);
        toolBar.add(Box.createHorizontalStrut(5));
        JLabel globeLabel = new JLabel("🌍");
        globeLabel.setFont(globeLabel.getFont().deriveFont(18.0f));
        toolBar.add(globeLabel);
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

    private void syncButtonAction() {
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
    }

    private void shutdownAction() {
        if (JOptionPane.showConfirmDialog(SignumGUI.this,
                "This will stop the node. Are you sure?", "Shutdown Node",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
            new Thread(this::shutdown).start();
        }
    }

    /**
     * Performs a database consistency check and handles the UI response based on
     * the result and configuration.
     * <p>
     * This method runs on a background thread to avoid blocking the EDT during the
     * check.
     * The UI feedback logic handles the following scenarios:
     * <ul>
     * <li><b>Consistent:</b> Displays a success message with database
     * statistics.</li>
     * <li><b>Inconsistent:</b>
     * <ul>
     * <li><b>Auto-Resolve Triggered:</b> If auto-resolve is enabled and this check
     * triggered it
     * (state transition to INCONSISTENT), displays an information message that
     * automatic resolution has started.</li>
     * <li><b>Already Active:</b> If a resolution process was already running before
     * this check,
     * displays a warning that resolution is in progress.</li>
     * <li><b>Manual Action Required:</b> If auto-resolve is disabled or did not
     * trigger (e.g., persistent inconsistency),
     * displays an error dialog offering the user to manually start the resolution
     * process.</li>
     * </ul>
     * </li>
     * </ul>
     */
    private void dbCheckAction() {
        BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();

        String statusMessage;
        if (blockchainProcessor.getResolutionState() == BlockchainProcessor.ResolutionState.ACTIVE) {
            statusMessage = "Auto database resolve ongoing. Database check will run after resolution is finished...";
        } else if (blockchainProcessor.isTrimming()) {
            statusMessage = "Trim ongoing. Database check will run after trim is finished...";
        } else if (blockchainProcessor.getManualPopOffBlocksCount() > 0
                || blockchainProcessor.getAutoPopOffBlocksCount() > 0) {
            statusMessage = "Pop-off ongoing. Database check will run after pop-off is finished...";
        } else {
            statusMessage = "Database consistency check in progress...";
        }

        waitDialog = new JDialog(SignumGUI.this, "Database Check", true);
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel messageLabel = new JLabel(statusMessage);
        messageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(messageLabel);

        panel.add(Box.createRigidArea(new Dimension(0, 15)));

        final RotatingSvgIcon rotatingIcon = new RotatingSvgIcon(0.5);
        rotatingIcon.setPreferredSize(new Dimension(64, 64));
        rotatingIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(rotatingIcon);
        rotatingIcon.start();

        waitDialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                rotatingIcon.stop();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                rotatingIcon.stop();
            }
        });

        waitDialog.setContentPane(panel);
        waitDialog.pack();
        waitDialog.setLocationRelativeTo(SignumGUI.this);
        waitDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        if (isDbCheckRunning.get()) {
            waitDialog.setVisible(true);
            return;
        }

        isDbCheckRunning.set(true);

        new Thread(() -> {
            try {
                // Check if resolution was already active before we requested the check
                boolean wasResolutionActive = blockchainProcessor
                        .getResolutionState() == BlockchainProcessor.ResolutionState.ACTIVE;

                final int result = blockchainProcessor.checkDatabaseStateRequest();
                final int height = blockchainProcessor.getLastCheckHeight();
                final long totalMined = blockchainProcessor.getLastCheckTotalMined();
                final long totalEffectiveBalance = blockchainProcessor.getLastCheckTotalEffectiveBalance();

                final int finalLimitHeight = blockchainProcessor.getSafeRollbackHeight();
                int lastTrimHeight = blockchainProcessor.getLastTrimHeight().get();
                final int finalLastTrimHeight = lastTrimHeight;

                SwingUtilities.invokeLater(() -> {
                    if (waitDialog.isDisplayable()) {
                        rotatingIcon.stop();
                        waitDialog.dispose();
                    }
                    showDbCheckResult(result, height, totalMined, totalEffectiveBalance, wasResolutionActive,
                            finalLimitHeight, finalLastTrimHeight);
                });
            } catch (Exception e) {
                LOGGER.error("Error during DB check", e);
                SwingUtilities.invokeLater(() -> {
                    if (waitDialog.isDisplayable()) {
                        rotatingIcon.stop();
                        waitDialog.dispose();
                    }
                    String message = "An error occurred during the database check.";
                    if (e instanceof IllegalStateException && e.getMessage().contains("already in progress")) {
                        message = "A database check is already running in the background.";
                    }
                    JOptionPane.showMessageDialog(SignumGUI.this, message,
                            "Error", JOptionPane.ERROR_MESSAGE);
                });
            } finally {
                isDbCheckRunning.set(false);
            }
        }).start();
        waitDialog.setVisible(true);
    }

    private void showDbCheckResult(int result, int height, long totalMined, long totalEffectiveBalance,
            boolean wasResolutionActive, int limitHeight, int lastTrimHeight) {
        BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
        final double totalMinedSigna = (double) totalMined / Constants.ONE_SIGNA;
        final double totalEffectiveBalanceSigna = (double) totalEffectiveBalance / Constants.ONE_SIGNA;
        final long difference = totalMined - totalEffectiveBalance;

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
            JOptionPane.showMessageDialog(SignumGUI.this, message, "Database Consistency Check",
                    JOptionPane.INFORMATION_MESSAGE, icon);
        } else {
            String inconsistencyType;
            if (result > 0) {
                inconsistencyType = "Total mined is greater than total effective balance.";
            } else {
                inconsistencyType = "Total mined is less than total effective balance.";
            }
            String infoMessage = String.format("Database is INCONSISTENT!\n\n%s\n\n" +
                    "Total Mined: %,.2f SIGNA (%,d NQT)\n" +
                    "Total Effective Balance: %,.2f SIGNA (%,d NQT)\n\n" +
                    "Difference: %,d NQT\n\nCheck logs for more details at block height %d.",
                    inconsistencyType,
                    totalMinedSigna, totalMined,
                    totalEffectiveBalanceSigna, totalEffectiveBalance,
                    difference, height);

            String resolveMessage = "This tool can try to automatically resolve the inconsistency by popping off blocks.\n"
                    + "It will rollback blocks until the database becomes consistent or the safe rollback limit is reached.\n\n"
                    + "The safe rollback limit is calculated as follows:\n";

            if (trimEnabled) {
                resolveMessage += "- Trimming enabled. Limit is the last trim height: " + limitHeight + ".\n";
                if (lastTrimHeight <= 0) {
                    resolveMessage += "  (Estimated using modulo of current height and trim period "
                            + Constants.TRIM_PERIOD + ")\n";
                }
            } else {
                resolveMessage += "- Trimming disabled. Limit is " + Constants.MAX_ROLLBACK + " blocks back: "
                        + limitHeight + ".\n";
            }
            resolveMessage += "The process stops if consistency is restored before reaching this limit.";

            icon = IconFontSwing.buildIcon(FontAwesome.EXCLAMATION_TRIANGLE, 32, contrastRed);

            if (blockchainProcessor.getResolutionState() == BlockchainProcessor.ResolutionState.ACTIVE) {
                String activeMessage;
                String title;
                int messageType;

                if (!wasResolutionActive
                        && Signum.getPropertyService().getBoolean(Props.AUTO_CONSISTENCY_RESOLVE_ENABLED)) {
                    activeMessage = "The database is INCONSISTENT.\n\n" +
                            "An automatic consistency resolution has been started.\n" +
                            "Please check the logs for progress.";
                    title = "Automatic Resolution Started";
                    messageType = JOptionPane.INFORMATION_MESSAGE;
                } else {
                    activeMessage = "Consistency resolution is currently IN PROGRESS.\n" +
                            "Please check the logs for progress.";
                    title = "Database Consistency Check";
                    messageType = JOptionPane.WARNING_MESSAGE;
                }

                Object[] messageContent = { infoMessage, Box.createVerticalStrut(10), new JSeparator(),
                        Box.createVerticalStrut(10), activeMessage };

                JOptionPane.showMessageDialog(SignumGUI.this, messageContent, title,
                        messageType, icon);
                return;
            }

            Object[] messageContent = {
                    infoMessage,
                    Box.createVerticalStrut(10),
                    new JSeparator(),
                    Box.createVerticalStrut(10),
                    resolveMessage
            };

            Object[] options = { "Start Auto Resolve Database Consistency", "Cancel" };
            int n = JOptionPane.showOptionDialog(SignumGUI.this, messageContent, "Database Consistency Check",
                    JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE, icon, options, options[1]);

            if (n == 0) {
                blockchainProcessor.manualResolveDatabaseConsistency();
            }
        }
    }

    private void showWindow() {
        setVisible(true);
    }
    /*
     * private void popOff(int blocks) {
     * LOGGER.info("Pop off requested, this can take a while...");
     * int height = blocks > 0 ? Signum.getBlockchain().getLastBlock().getHeight() -
     * blocks
     * : Signum.getBlockchainProcessor().getMinRollbackHeight();
     * new Thread(() -> Signum.getBlockchainProcessor().popOffTo(height)).start();
     * }
     */

    private void popOff(int count) {
        // LOGGER.info("Pop off requested, this can take a while...");
        new Thread(() -> Signum.getBlockchainProcessor().popOff(count)).start();
    }

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
        blockchainProcessor.addListener(block -> onPeersUpdated(), BlockchainProcessor.Event.PEERS_UPDATED);
        blockchainProcessor.addListener(block -> onNetVolumeChanged(), BlockchainProcessor.Event.NET_VOLUME_CHANGED);
        blockchainProcessor.addListener(this::onBlockPushed, BlockchainProcessor.Event.BLOCK_PUSHED);
        blockchainProcessor.addListener(block -> onBlockPopped(), BlockchainProcessor.Event.BLOCK_MANUAL_POPPED);
        blockchainProcessor.addListener(block -> onBlockPopped(), BlockchainProcessor.Event.BLOCK_AUTO_POPPED);
        blockchainProcessor.addListener(block -> onManualPopOffProgress(),
                BlockchainProcessor.Event.BLOCK_MANUAL_POPPED);
        blockchainProcessor.addListener(block -> onAutoPopOffProgress(), BlockchainProcessor.Event.BLOCK_AUTO_POPPED);

        if (trimEnabled) {
            blockchainProcessor.addListener(block -> onTrimStart(),
                    BlockchainProcessor.Event.TRIM_START);
            blockchainProcessor.addListener(block -> onTrimHeightChanged(),
                    BlockchainProcessor.Event.TRIM_END);
            blockchainProcessor.addListener(block -> onConsistencyUpdate(),
                    BlockchainProcessor.Event.DATABASE_CONSISTENCY_UPDATE);
        }
    }

    public void onPeersUpdated() {
        BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
        Collection<Peer> allPeers = blockchainProcessor.getAllPeers();
        long connectedCount = allPeers.stream().filter(p -> p.getState() == Peer.State.CONNECTED).count();
        long allKnownCount = allPeers.size();
        long blacklistedCount = allPeers.stream().filter(Peer::isBlacklisted).count();
        SwingUtilities.invokeLater(() -> updatePeerCount(connectedCount, allKnownCount, blacklistedCount));
    }

    public void onNetVolumeChanged() {
        BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
        long uploaded = blockchainProcessor.getUploadedVolume();
        long downloaded = blockchainProcessor.getDownloadedVolume();
        SwingUtilities.invokeLater(() -> {
            uploadVolumeLabel.setText("▲ " + formatDataSize(uploaded));
            downloadVolumeLabel.setText("▼ " + formatDataSize(downloaded));

            // Start the GUI timer only once, when the first download volume is received,
            // and if experimental features are enabled in the config.
            if (Signum.getPropertyService().getBoolean(Props.EXPERIMENTAL)
                    && downloaded > 0
                    && !guiTimerStarted.getAndSet(true)) {
                startGuiTimer();
            }
        });
    }

    private void startGuiTimer() {
        guiTimer = new Timer(1000, e -> {
            if (Signum.getBlockchain() != null && Signum.getBlockchainProcessor() != null) {
                guiAccumulatedSyncTimeMs += 1000;
                totalTimeLabel.setText("🕒 " + DurationFormatter.format(guiAccumulatedSyncTimeMs,
                        DurationFormatter.Unit.YEAR, DurationFormatter.Unit.SECOND));

                if (isSyncing) {
                    guiAccumulatedSyncInProgressTimeMs += 1000;
                }
                syncInProgressTimeLabel
                        .setText("🔄 " + DurationFormatter.format(guiAccumulatedSyncInProgressTimeMs,
                                DurationFormatter.Unit.YEAR, DurationFormatter.Unit.SECOND));
                updateTimeLabelVisibility();
            }
        });
        guiTimer.start();
    }

    private void onTrimStart() {

        int currentTrimHeight = Signum.getBlockchainProcessor().getCurrentTrimHeight().get();
        int lastTrimHeight = Signum.getBlockchainProcessor().getLastTrimHeight().get();
        SwingUtilities.invokeLater(() -> {

            if (lastTrimHeight > currentTrimHeight) {
                if (currentTrimHeight < 0) {
                    trimHeightLabel.setText(String.format("- 🡺 %d", lastTrimHeight));
                } else {
                    trimHeightLabel
                            .setText(String.format("%d 🡺 %d", currentTrimHeight, lastTrimHeight));
                }
            }
            trimHeightLabel.setForeground(Color.GREEN);
        });
    }

    private void onConsistencyUpdate() {
        BlockchainProcessor.ConsistencyState state = Signum.getBlockchainProcessor().getConsistencyState();
        SwingUtilities.invokeLater(() -> {

            Color color;
            switch (state) {
                case CONSISTENT:
                    color = Color.GREEN;
                    break;
                case INCONSISTENT:
                    color = contrastRed;
                    break;
                default: // UNDEFINED
                    color = iconColor;
            }
            dbCheckButton.setIcon(IconFontSwing.buildIcon(FontAwesome.DATABASE, 18, color));
        });
    }

    private void onBlockPushed(Block block) {
        if (block == null)
            return;
        int maxPeerHeight = calculateMaxPeerHeight();
        long blockTime = Signum.getFluxCapacitor().getValue(FluxValues.BLOCK_TIME);
        SwingUtilities.invokeLater(() -> {
            updateLatestBlock(block, maxPeerHeight, blockTime);

            // Start the GUI timer only once, when the first block is pushed,
            // and if experimental features are enabled in the config.
            if (Signum.getPropertyService().getBoolean(Props.EXPERIMENTAL) && !guiTimerStarted.getAndSet(true)) {
                startGuiTimer();
            }
        });
    }

    private void onBlockPopped() {
        Block lastBlock = Signum.getBlockchain().getLastBlock();
        int maxPeerHeight = calculateMaxPeerHeight();
        long blockTime = Signum.getFluxCapacitor().getValue(FluxValues.BLOCK_TIME);
        SwingUtilities.invokeLater(() -> {
            updateLatestBlock(lastBlock, maxPeerHeight, blockTime);
        });
    }

    private void onManualPopOffProgress() {
        int remaining = Signum.getBlockchainProcessor().getManualPopOffBlocksCount();
        int blockHeight = Signum.getBlockchainProcessor().getBeforeRollbackHeight();
        int targetHeight = Signum.getBlockchainProcessor().getManualLastPopOffHeight();
        SwingUtilities.invokeLater(() -> {
            popOffBlockCountLabel.setText("Pop off blocks: " + remaining);
            popOffBlockHeightLabel.setText(targetHeight < 0 ? "-" : targetHeight + " 🡸 " + blockHeight);
            if (remaining > 0) {
                popOffBlockCountLabel.setForeground(Color.YELLOW);
                popOffBlockHeightLabel.setForeground(Color.YELLOW);
            } else {
                popOffBlockCountLabel.setForeground(iconColor);
                popOffBlockHeightLabel.setForeground(iconColor);
            }
            setPopOffLabelVisible(remaining > 0);
        });
    }

    private void onAutoPopOffProgress() {
        int remaining = Signum.getBlockchainProcessor().getAutoPopOffBlocksCount();
        int blockHeight = Signum.getBlockchainProcessor().getBeforeRollbackHeight();
        int targetHeight = Signum.getBlockchainProcessor().getAutoLastPopOffHeight();
        SwingUtilities.invokeLater(() -> {
            popOffBlockCountLabel.setText("Pop off blocks: " + remaining);
            popOffBlockHeightLabel.setText(targetHeight < 0 ? "-" : targetHeight + " 🡸 " + blockHeight);

            if (Signum.getBlockchainProcessor().getResolutionState() == BlockchainProcessor.ResolutionState.ACTIVE) {
                if (remaining > 0) {
                    popOffBlockCountLabel.setForeground(contrastRed);
                    popOffBlockHeightLabel.setForeground(contrastRed);
                } else {
                    popOffBlockCountLabel.setForeground(iconColor);
                    popOffBlockHeightLabel.setForeground(iconColor);
                }
            } else {
                if (remaining > 0) {
                    popOffBlockCountLabel.setForeground(Color.ORANGE);
                    popOffBlockHeightLabel.setForeground(Color.ORANGE);
                } else {
                    popOffBlockCountLabel.setForeground(iconColor);
                    popOffBlockHeightLabel.setForeground(iconColor);
                }
            }
            setPopOffLabelVisible(remaining > 0);
        });
    }

    private void setPopOffLabelVisible(boolean isVisible) {
        popOffSeparator1.setVisible(isVisible);
        popOffBlockCountLabel.setVisible(isVisible);
        popOffSeparator2.setVisible(isVisible);
        popOffBlockHeightLabel.setVisible(isVisible);
    }

    public void startSignumWithGUI() {
        try {
            // signum.init();
            Signum.main(args);

            // Now that properties are loaded, set the correct values for the GUI
            showPopOff = Signum.getPropertyService().getBoolean(Props.EXPERIMENTAL);
            measurementActive = Signum.getPropertyService().getBoolean(Props.MEASUREMENT_ACTIVE);
            experimentalActive = Signum.getPropertyService().getBoolean(Props.EXPERIMENTAL);
            trimEnabled = Signum.getPropertyService().getBoolean(Props.DB_TRIM_DERIVED_TABLES);
            autoResolveEnabled = Signum.getPropertyService().getBoolean(Props.AUTO_CONSISTENCY_RESOLVE_ENABLED);

            Block lastBlock = Signum.getBlockchain().getLastBlock();
            int maxPeerHeight = calculateMaxPeerHeight();
            BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
            Collection<Peer> allPeers = blockchainProcessor.getAllPeers();
            long connectedCount = allPeers.stream().filter(p -> p.getState() == Peer.State.CONNECTED).count();
            long allKnownCount = allPeers.size();
            long blacklistedCount = allPeers.stream().filter(Peer::isBlacklisted).count();
            long blockTime = Signum.getFluxCapacitor().getValue(FluxValues.BLOCK_TIME);

            try {
                SwingUtilities.invokeLater(() -> {
                    metricsPanel.init();
                    metricsPanel.setVisible(true);
                    showTrayIcon();
                    // Sync checkbox states with loaded properties
                    popOffToggle.repaint();

                    if (measurementActive) {
                        measurementPanel.setVisible(true);
                    }

                    if (experimentalActive) {
                        experimentalPanel.setVisible(true);
                        timePanel.setVisible(true);
                    }

                    if (trimEnabled) {
                        trimPanel.setVisible(true);
                        trimHeightLabel.setVisible(true);
                        trimSeparator.setVisible(true);
                    } else {
                        trimPanel.setVisible(false);
                        trimHeightLabel.setVisible(false);
                        trimSeparator.setVisible(false);
                    }

                    if (autoResolveEnabled) {
                        autoResolvePanel.setVisible(true);
                    } else {
                        autoResolvePanel.setVisible(false);
                    }

                    onTrimHeightChanged();
                    onConsistencyUpdate();
                    onManualPopOffProgress();
                    onAutoPopOffProgress();

                    updateLatestBlock(lastBlock, maxPeerHeight, blockTime);
                    updatePeerCount(connectedCount, allKnownCount, blacklistedCount);
                });

                updateTitle();

                initListeners();
                if (Signum.getPropertyService().getBoolean(Props.EXPERIMENTAL)) {
                    // Initialize timers from the log file.
                    if (blockchainProcessor != null) {
                        this.guiAccumulatedSyncTimeMs = blockchainProcessor.getAccumulatedSyncTimeMs();
                        this.guiAccumulatedSyncInProgressTimeMs = blockchainProcessor
                                .getAccumulatedSyncInProgressTimeMs();
                    }
                    // Update labels with initial values from log file
                    SwingUtilities.invokeLater(() -> {
                        totalTimeLabel.setText("🕒 " + DurationFormatter.format(guiAccumulatedSyncTimeMs,
                                DurationFormatter.Unit.YEAR, DurationFormatter.Unit.SECOND));
                        syncInProgressTimeLabel
                                .setText("🔄 " + DurationFormatter.format(guiAccumulatedSyncInProgressTimeMs,
                                        DurationFormatter.Unit.YEAR, DurationFormatter.Unit.SECOND));
                        updateTimeLabelVisibility(); // Initial visibility check
                    });
                }
                if (Signum.getBlockchain() == null) {
                    onBrsStopped();
                }
            } catch (Exception t) {
                LOGGER.error("Could not determine if running in testnet mode", t);
            }
        } catch (Exception t) {
            LOGGER.error(FAILED_TO_START_MESSAGE, t);
            showMessage(FAILED_TO_START_MESSAGE);
            onBrsStopped();
        }

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
        } else if (isShuttingDown) {
            title += " (Shutting Down...)";
        }
        final String finalTitle = title;
        SwingUtilities.invokeLater(() -> setTitle(finalTitle));
        if (trayIcon != null) {
            trayIcon.setToolTip(finalTitle);
        }
    }

    private void updateLatestBlock(Block block, int maxPeerHeight, long blockTime) {
        if (block == null) {
            return;
        }
        Date blockDate = Convert.fromEpochTime(block.getTimestamp());

        int missingBlocks;
        if (maxPeerHeight > 0) {
            // We have peers, use their height as the source of truth.
            missingBlocks = Math.max(0, maxPeerHeight - block.getHeight());
        } else {
            // No peers, fall back to time-based estimation.
            Date now = new Date();
            long secondsSinceLastBlock = (now.getTime() - blockDate.getTime()) / 1000;
            missingBlocks = secondsSinceLastBlock > 0 ? (int) (secondsSinceLastBlock / blockTime) : 0;
        }

        boolean isEffectivelySynced = missingBlocks == 0;

        elapsedTimeLabel.setVisible(isEffectivelySynced);
        elapsedTimeSeparator.setVisible(isEffectivelySynced);

        if (isEffectivelySynced) {
            elapsedTimeCounter = (System.currentTimeMillis() - blockDate.getTime()) / 1000;
            if (elapsedTimeCounter < 0) {
                elapsedTimeCounter = 0;
            }
            elapsedTimeLabel.setText("Elapsed Time: " + elapsedTimeCounter + "s");
        } else {
            elapsedTimeCounter = 0;
        }

        if (elapsedTimeTimer == null) {
            elapsedTimeTimer = new Timer(1000, e -> updateElapsedTime());
            elapsedTimeTimer.start();
        }
        latestBlockHeightLabel.setText("Latest block: " + block.getHeight());
        latestBlockTimestampLabel.setText("Timestamp: " + DATE_FORMAT.format(blockDate));

        // Start syncing if more than 10 block times behind, stop if 1 or less.
        // This is more reliable than peer height difference, especially at startup.
        if (!isSyncing && missingBlocks > 10) {
            isSyncing = true;
        } else if (isSyncing && missingBlocks <= 1) {
            isSyncing = false;
        }

        String tooltipText = "Synchronized";
        if (missingBlocks > 0) {
            tooltipText = "Estimated blocks behind: " + missingBlocks;
        }

        if (maxPeerHeight > block.getHeight()) {
            tooltipText = "Network Height: " + maxPeerHeight + " (Behind: " + (maxPeerHeight - block.getHeight()) + ")";
        } else if (maxPeerHeight > 0 && missingBlocks == 0) {
            tooltipText = "Synchronized (Network Height: " + maxPeerHeight + ")";
        }
        syncProgressBar.setToolTipText(tooltipText);

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
        syncProgressBar.setMaximumSize(progressBarSize2);
        syncProgressBar.setMinimumSize(progressBarSize2);
        syncProgressBar.setString(String.format("%.2f %%", prog));
    }

    private int calculateMaxPeerHeight() {
        try {
            return Signum.getBlockchainProcessor().getAllPeers().stream()
                    .filter(p -> p.getState() == Peer.State.CONNECTED)
                    .mapToInt(p -> (int) p.getHeight())
                    .max()
                    .orElse(0);
        } catch (Exception e) {
            return 0;
        }
    }

    private void updateElapsedTime() {
        if (!elapsedTimeLabel.isVisible()) {
            return;
        }
        elapsedTimeCounter++;
        elapsedTimeLabel.setText("Elapsed Time: " + elapsedTimeCounter + "s");
    }

    private void onTrimHeightChanged() {
        int currentTrimHeight = Signum.getBlockchainProcessor().getCurrentTrimHeight().get();
        int estimatedTrimHeight = (currentTrimHeight == -1) ? Signum.getBlockchainProcessor().getEstimatedTrimHeight()
                : 0;
        SwingUtilities.invokeLater(() -> {

            if (currentTrimHeight != -1) {
                trimHeightLabel.setText("Trim height: " + currentTrimHeight);
            } else {
                trimHeightLabel.setText("Trim height: est. " + estimatedTrimHeight);
            }
            trimHeightLabel.setForeground(iconColor);
        });
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
        JOptionPane.showMessageDialog(this, htmlText, title, JOptionPane.PLAIN_MESSAGE);
    }

    private void updatePeerCount(long connectedCount, long allKnownCount, long blacklistedCount) {
        // The label previously for 'connected' now shows 'active' peers.
        connectedPeersLabel.setText(String.valueOf(connectedCount));
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

    private static class TextAreaOutputStream extends OutputStream {
        private final JTextArea textArea;
        private final PrintStream actualOutput;
        private final StringBuilder buffer = new StringBuilder();
        private final Timer timer;

        private TextAreaOutputStream(JTextArea textArea, PrintStream actualOutput) {
            this.textArea = textArea;
            this.actualOutput = actualOutput;
            this.timer = new Timer(500, e -> flush());
            this.timer.setRepeats(true);
            this.timer.start();
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

        private synchronized void writeString(String string) {
            actualOutput.print(string);
            buffer.append(string);
        }

        @Override
        public void flush() {
            String text;
            synchronized (this) {
                if (buffer.length() == 0)
                    return;
                text = buffer.toString();
                buffer.setLength(0);
            }
            textArea.append(text);
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
