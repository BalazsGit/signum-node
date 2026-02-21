package brs.gui;

import brs.BlockchainProcessor;
import brs.peer.PeerMetric;
import brs.util.Listener;
import brs.Signum;
import brs.gui.util.MovingAverage;
import brs.gui.util.TableUtils;
import brs.gui.PeersDialog.PeerCategory;
import brs.peer.Peer;
import brs.peer.Peers;
import net.miginfocom.swing.MigLayout;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.labels.XYToolTipGenerator;
import org.jfree.chart.plot.DatasetRenderingOrder;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.AbstractXYItemRenderer;
import org.jfree.chart.renderer.xy.StandardXYBarPainter;
import org.jfree.chart.renderer.xy.XYBarRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.RectangleInsets;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.jfree.data.xy.XYDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.JTableHeader;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.text.SimpleDateFormat;
import java.awt.font.TextAttribute;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.Comparator;

public class PeerMetricsPanel extends JPanel {

    private static final Logger logger = LoggerFactory.getLogger(PeerMetricsPanel.class);

    private static final int HISTORY_SIZE = 1000;
    private static final int PEER_HISTORY_SIZE = 100;
    private int movingAverageWindow = 100;

    // Constants for chart dataset and axis indices to improve readability
    private static final int DATASET_BARS = 0;
    private static final int DATASET_LINES = 1;
    private static final int AXIS_LINES = 0;
    private static final int AXIS_BARS = 1;

    private static final Color COLOR_OTHER_RESPONSE_TIME = new Color(160, 85, 230); // More vivid Magenta
    private static final Color COLOR_BLACKLISTED_PEERS = new Color(255, 100, 100); // Lighter Red for better contrast
    private static final Color COLOR_MIN_RESPONSE_TIME = new Color(80, 170, 50); // Contrast Green
    private static final Color COLOR_MAX_RESPONSE_TIME = new Color(160, 0, 0); // Darker Red

    private static final Color COLOR_RX_RESPONSE_TIME = new Color(0, 100, 0); // Dark Green
    private static final Color COLOR_RX_COUNT = Color.GREEN; // Green
    private static final Color COLOR_TX_RESPONSE_TIME = Color.ORANGE; // Orange
    private static final Color COLOR_TX_COUNT = new Color(70, 130, 255); // Lighter Blue

    private static final Color COLOR_OTHER_COUNT = new Color(255, 215, 0); // Gold

    private static final Color COLOR_CONNECTED_PEERS = Color.GREEN; // Green
    private static final Color COLOR_ACTIVE_PEERS = Color.CYAN; // Cyan
    private static final Color COLOR_ALL_PEERS = new Color(230, 230, 230); // Slightly Gray

    private static final BasicStroke CHART_STROKE = new BasicStroke(1.2f);

    private static final Border TABLE_PANEL_BORDER = BorderFactory.createEmptyBorder(0, 0, 0, 0);

    // Column Headers
    private static final String COL_TIME = "Time";
    private static final String COL_ADDRESS = "Address";
    private static final String COL_ANNOUNCED = "Announced";
    private static final String COL_STATE = "State";
    private static final String COL_VERSION = "Version";
    private static final String COL_HEIGHT = "Height";
    private static final String COL_AVG_RESP = "Avg R.";
    private static final String COL_MIN_RESP = "Min R.";
    private static final String COL_MAX_RESP = "Max R.";
    private static final String COL_LAST_RESP = "Last R.";
    private static final String COL_AVG_BLOCKS = "Avg B.";
    private static final String COL_TOTAL_BLOCKS = "Tot B.";
    private static final String COL_REQ = "Req";
    private static final String COL_AVG_ITEMS = "Avg I.";
    private static final String COL_TOTAL_ITEMS = "Tot I.";

    private enum MetricType {
        RX, TX, OTHER
    }

    // RX Block Metrics
    private final MovingAverage rxResponseTimeMA = new MovingAverage(HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage rxBlockCountMA = new MovingAverage(HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage rxAbsMinMA = new MovingAverage(HISTORY_SIZE, 1);
    private final MovingAverage rxAbsMaxMA = new MovingAverage(HISTORY_SIZE, 1);

    // TX Block Metrics
    private final MovingAverage txResponseTimeMA = new MovingAverage(HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage txBlockCountMA = new MovingAverage(HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage txAbsMinMA = new MovingAverage(HISTORY_SIZE, 1);
    private final MovingAverage txAbsMaxMA = new MovingAverage(HISTORY_SIZE, 1);

    // Other Metrics
    private final MovingAverage otherResponseTimeMA = new MovingAverage(HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage otherItemCountMA = new MovingAverage(HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage otherAbsMinMA = new MovingAverage(HISTORY_SIZE, 1);
    private final MovingAverage otherAbsMaxMA = new MovingAverage(HISTORY_SIZE, 1);

    private final Map<String, PeerHistory> peerHistories = new ConcurrentHashMap<>();

    private final Map<MetricType, ChartPanel> chartPanels = new HashMap<>();
    private final Map<MetricType, Integer> zoomRanges = new HashMap<>();
    private ChartPanel overviewChartPanel;

    private XYSeries rxResponseTimeSeries;
    private XYSeries rxCountSeries;
    private XYSeries txResponseTimeSeries;
    private XYSeries txCountSeries;
    private XYSeries otherResponseTimeSeries;
    private XYSeries otherCountSeries;

    private XYSeries rxAbsMinSeries;
    private XYSeries rxAbsMaxSeries;
    private XYSeries txAbsMinSeries;
    private XYSeries txAbsMaxSeries;
    private XYSeries otherAbsMinSeries;
    private XYSeries otherAbsMaxSeries;

    private XYSeries connectedSeries;
    private XYSeries activeSeries;
    private XYSeries allSeries;
    private XYSeries blacklistedSeries;

    private JProgressBar rxResponseTimeBar, rxCountBar;
    private JProgressBar txResponseTimeBar, txCountBar;
    private JProgressBar otherResponseTimeBar, otherCountBar;
    private JProgressBar connectedPeersBar, allPeersBar, activePeersBar, blacklistedPeersBar;

    private JTable rxPeersTable;
    private PeersTableModel rxTableModel;
    private JTable txPeersTable;
    private PeersTableModel txTableModel;
    private JTable otherPeersTable;
    private PeersTableModel otherTableModel;
    private JTabbedPane overviewTablesPane;

    private final Dimension progressBarSize = new Dimension(350, 20);
    private final Dimension chartDimension = new Dimension(320, 240);
    private final Dimension tableDimension = new Dimension(250, chartDimension.height);

    private final List<PeersDialog.PeerTabPanel> overviewPeerPanels = new ArrayList<>();
    private final List<PeersDialog.PeerCategory> overviewPeerCategories = new ArrayList<>();

    private long rxUpdateCounter = 0;
    private long txUpdateCounter = 0;
    private long otherUpdateCounter = 0;
    private long overviewUpdateCounter = 0;
    private volatile String latestNetworkVersion = Signum.VERSION.toString();

    private final ExecutorService chartUpdateExecutor;
    private final Object updateLock = new Object();

    private final Map<MetricType, AbsResponseTimeStats> absResponseTimeStats = new ConcurrentHashMap<>();
    // private final Map<MetricType, AbsLatencyUI> absLatencyUIs = new HashMap<>();
    // // Removed, handled dynamically

    private final Shape tooltipHitShape = new Ellipse2D.Double(-10.0, -10.0, 20.0, 20.0);

    /**
     * Indicates whether this panel is currently the active/visible tab.
     * <p>
     * Used to determine if UI updates should be performed or skipped to save
     * resources.
     * Updated via HierarchyListener.
     * </p>
     */
    private volatile boolean isTabActive = false;

    /**
     * Controls whether UI optimization is enabled.
     * <p>
     * If {@code true}, UI components (tables, charts, progress bars) are only
     * updated
     * when {@link #isTabActive} is true. Background data collection and processing
     * continue regardless of this setting.
     * </p>
     */
    private boolean uiOptimizationEnabled = true;
    private volatile MetricsUpdateData lastRxUpdateData;
    private volatile MetricsUpdateData lastTxUpdateData;
    private volatile MetricsUpdateData lastOtherUpdateData;
    private volatile OverviewUpdateData lastOverviewUpdateData;

    /**
     * Controls whether high-frequency updates are batched (throttled).
     * <p>
     * If {@code true}, updates are accumulated and applied at the interval defined
     * by
     * {@link #throttlingInterval} to prevent flooding the Event Dispatch Thread
     * (EDT).
     * </p>
     */
    private boolean throttlingEnabled = false;
    /**
     * The interval in milliseconds for applying batched updates when
     * {@link #throttlingEnabled} is true.
     */
    private int throttlingInterval = 500;
    private Timer uiUpdateTimer;
    private boolean rxDirty = false;
    private boolean txDirty = false;
    private boolean otherDirty = false;
    private String lastRxPeer = null;
    private String lastTxPeer = null;
    private String lastOtherPeer = null;

    private boolean migLayoutDebug = false;

    private final Listener<PeerMetric> peerMetricListener = this::onPeerMetric;
    private final Listener<brs.Block> peersUpdatedListener = this::onPeersUpdated;

    public PeerMetricsPanel(ExecutorService sharedExecutor) {
        this.chartUpdateExecutor = sharedExecutor;
        initUI();
    }

    /**
     * Initializes the panel by registering listeners and starting the update timer.
     * Should be called when the panel is added to the UI.
     */
    public void init() {
        Signum.getBlockchainProcessor().addPeerMetricListener(peerMetricListener);
        Signum.getBlockchainProcessor().addListener(peersUpdatedListener, BlockchainProcessor.Event.PEERS_UPDATED);

        setupThrottlingTimer();
    }

    /**
     * Cleans up resources, removes listeners, and stops timers.
     * Should be called when the application is shutting down or the panel is
     * destroyed.
     */
    public void shutdown() {
        try {
            BlockchainProcessor processor = Signum.getBlockchainProcessor();
            if (processor != null) {
                processor.removePeerMetricListener(peerMetricListener);
                processor.removeListener(peersUpdatedListener, BlockchainProcessor.Event.PEERS_UPDATED);
            }
        } catch (Throwable t) {
            logger.warn("Error removing BlockchainProcessor listeners", t);
        }
        try {
            if (uiUpdateTimer != null)
                uiUpdateTimer.stop();
        } catch (Throwable t) {
            logger.warn("Error stopping UI update timer", t);
        }
    }

    /**
     * Sets the UI optimization mode.
     *
     * @param enabled if true, UI updates are paused when the tab is not active.
     */
    public void setUiOptimizationEnabled(boolean enabled) {
        this.uiOptimizationEnabled = enabled;
        if (!enabled) {
            refreshUI();
        }
    }

    /**
     * Configures the UI update throttling (batching) mechanism.
     * <p>
     * Throttling prevents the Event Dispatch Thread (EDT) from being flooded with
     * update events
     * during high network activity. When enabled, updates are buffered and applied
     * in batches
     * at the specified interval.
     * </p>
     *
     * @param enabled    If {@code true}, updates are buffered and applied
     *                   periodically.
     *                   If {@code false}, updates are applied immediately as they
     *                   arrive (not recommended for high traffic).
     * @param intervalMs The interval in milliseconds between UI updates when
     *                   throttling is enabled.
     *                   Ignored if enabled is false. Default is 500ms.
     */
    public void setThrottling(boolean enabled, int intervalMs) {
        this.throttlingEnabled = enabled;
        this.throttlingInterval = intervalMs;
        setupThrottlingTimer();
    }

    private void setupThrottlingTimer() {
        if (uiUpdateTimer != null) {
            uiUpdateTimer.stop();
            uiUpdateTimer = null;
        }
        if (throttlingEnabled) {
            uiUpdateTimer = new Timer(throttlingInterval,
                    e -> chartUpdateExecutor.submit(this::processBufferedUpdates));
            uiUpdateTimer.setRepeats(true);
            uiUpdateTimer.start();
        }
    }

    /**
     * Forces a refresh of all UI components using the latest buffered data.
     * This is typically called when the tab becomes active or optimization is
     * disabled.
     */
    private void refreshUI() {
        SwingUtilities.invokeLater(() -> {
            if (lastRxUpdateData != null) {
                updateGlobalUI(lastRxUpdateData);
                updateTable(lastRxUpdateData);
            }
            if (lastTxUpdateData != null) {
                updateGlobalUI(lastTxUpdateData);
                updateTable(lastTxUpdateData);
            }
            if (lastOtherUpdateData != null) {
                updateGlobalUI(lastOtherUpdateData);
                updateTable(lastOtherUpdateData);
            }
            if (lastOverviewUpdateData != null) {
                applyOverviewUpdate(lastOverviewUpdateData);
            }
            // Force repaint of charts
            for (ChartPanel cp : chartPanels.values()) {
                cp.getChart().getXYPlot().setNotify(true);
            }
            if (overviewChartPanel != null) {
                overviewChartPanel.getChart().getXYPlot().setNotify(true);
            }
        });
    }

    private void initUI() {
        setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        setLayout(new BorderLayout());

        connectedPeersBar = createProgressBar("0");
        allPeersBar = createProgressBar("0");
        activePeersBar = createProgressBar("0");
        blacklistedPeersBar = createProgressBar("0");

        connectedSeries = new XYSeries("Connected", true, false);
        activeSeries = new XYSeries("Active", true, false);
        allSeries = new XYSeries("All", true, false);
        blacklistedSeries = new XYSeries("Blacklisted", true, false);

        // --- Tabs ---
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setTabPlacement(JTabbedPane.BOTTOM);

        // RX Tab
        rxResponseTimeBar = createProgressBar("C: 0 | MA: 0 - min: 0 - max: 0 ms");
        rxCountBar = createProgressBar("C: 0 | MA: 0.00 - min: 0.00 - max: 0.00");
        rxResponseTimeSeries = new XYSeries("Response Time (ms)", true, false);
        rxCountSeries = new XYSeries("Block Count", true, false);
        rxAbsMinSeries = new XYSeries("Abs Min Resp Time", true, false);
        rxAbsMaxSeries = new XYSeries("Abs Max Resp Time", true, false);

        String rxResponseTimeTooltip = "The moving average of the response time for Rx (Received) Block operations from peers.\n\n"
                +
                "Lower values indicate faster network communication.";
        String rxCountTooltip = "The moving average of the number of blocks received from peers.";

        tabbedPane.addTab("Block Rx", createMetricTab(
                "RX Block Metrics",
                rxResponseTimeBar, rxCountBar,
                "Block Rx Resp Time (MA)", "Block Rx Count (MA)",
                rxResponseTimeTooltip, rxCountTooltip,
                rxResponseTimeSeries, rxCountSeries,
                rxAbsMinSeries, rxAbsMaxSeries,
                COLOR_RX_RESPONSE_TIME, COLOR_RX_COUNT,
                MetricType.RX));

        // TX Tab
        txResponseTimeBar = createProgressBar("C: 0 | MA: 0 - min: 0 - max: 0 ms");
        txCountBar = createProgressBar("C: 0 | MA: 0.00 - min: 0.00 - max: 0.00");
        txResponseTimeSeries = new XYSeries("Response Time (ms)", true, false);
        txCountSeries = new XYSeries("Block Count", true, false);
        txAbsMinSeries = new XYSeries("Abs Min Resp Time", true, false);
        txAbsMaxSeries = new XYSeries("Abs Max Resp Time", true, false);

        String txResponseTimeTooltip = "The moving average of the response time for Tx (Transmitted) Block operations to peers.\n\n"
                +
                "Lower values indicate faster network communication.";
        String txCountTooltip = "The moving average of the number of blocks sent to peers.";

        tabbedPane.addTab("Block Tx", createMetricTab(
                "TX Block Metrics",
                txResponseTimeBar, txCountBar,
                "Block Tx Resp Time (MA)", "Block Tx Count (MA)",
                txResponseTimeTooltip, txCountTooltip,
                txResponseTimeSeries, txCountSeries,
                txAbsMinSeries, txAbsMaxSeries,
                COLOR_TX_RESPONSE_TIME, COLOR_TX_COUNT,
                MetricType.TX));

        // Other Tab
        otherResponseTimeBar = createProgressBar("C: 0 | MA: 0 - min: 0 - max: 0 ms");
        otherCountBar = createProgressBar("C: 0 | MA: 0.00 - min: 0.00 - max: 0.00");
        otherResponseTimeSeries = new XYSeries("Response Time (ms)", true, false);
        otherCountSeries = new XYSeries("Item Count", true, false);
        otherAbsMinSeries = new XYSeries("Abs Min Resp Time", true, false);
        otherAbsMaxSeries = new XYSeries("Abs Max Resp Time", true, false);

        String otherResponseTimeTooltip = "The moving average of the response time for other types of peer communication.";
        String otherCountTooltip = "The moving average of the number of items (e.g. block IDs) transferred during other peer operations.";

        tabbedPane.addTab("Other Communication", createMetricTab(
                "Other Communication Metrics",
                otherResponseTimeBar, otherCountBar,
                "Other Comm. Resp Time (MA)", "Other Comm. Items (MA)",
                otherResponseTimeTooltip, otherCountTooltip,
                otherResponseTimeSeries, otherCountSeries,
                otherAbsMinSeries, otherAbsMaxSeries,
                COLOR_OTHER_RESPONSE_TIME, COLOR_OTHER_COUNT,
                MetricType.OTHER));

        tabbedPane.addChangeListener(e -> {
            if (uiOptimizationEnabled && isTabActive) {
                refreshUI();
            }
        });

        // --- Peer Counts (Overview Tab) ---
        JPanel overviewPanel = new JPanel(
                new MigLayout((migLayoutDebug ? "debug, " : "") + "insets 0, fillx", "[]5![]5![grow, fill]", "[top]"));
        overviewPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));

        // Left Side: Metrics + Chart
        JPanel metricsOverview = new JPanel(
                new MigLayout((migLayoutDebug ? "debug, " : "") + "insets 0, wrap 1, gapy 4", "[fill, 300!]"));

        JLabel connectedLabel = createLabel("Connected", COLOR_CONNECTED_PEERS, "Number of currently connected peers.");
        addMetricRow(metricsOverview, connectedLabel, connectedPeersBar);

        JLabel activeLabel = createLabel("Active", COLOR_ACTIVE_PEERS, "Number of active peers (communicating).");
        addMetricRow(metricsOverview, activeLabel, activePeersBar);

        JLabel allLabel = createLabel("All Known", COLOR_ALL_PEERS, "Total number of known peers.");
        addMetricRow(metricsOverview, allLabel, allPeersBar);

        JLabel blacklistedLabel = createLabel("Blacklisted", COLOR_BLACKLISTED_PEERS, "Number of blacklisted peers.");
        addMetricRow(metricsOverview, blacklistedLabel, blacklistedPeersBar);

        overviewChartPanel = createOverviewChartPanel();
        addToggleListener(connectedLabel, overviewChartPanel, connectedSeries.getKey().toString());
        addToggleListener(activeLabel, overviewChartPanel, activeSeries.getKey().toString());
        addToggleListener(allLabel, overviewChartPanel, allSeries.getKey().toString());
        addToggleListener(blacklistedLabel, overviewChartPanel, blacklistedSeries.getKey().toString());

        overviewPanel.add(metricsOverview, "aligny top");
        overviewPanel.add(overviewChartPanel, "aligny top");

        // Right Side: Tables
        JPanel tablesWrapper = new JPanel(new BorderLayout());
        tablesWrapper.setBorder(TABLE_PANEL_BORDER);

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        JLabel peersTitleLabel = new JLabel("Peers");
        peersTitleLabel.setFont(UIManager.getFont("TitledBorder.font"));
        if (peersTitleLabel.getFont() == null) {
            peersTitleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        }
        peersTitleLabel.setForeground(UIManager.getColor("TitledBorder.titleColor"));
        peersTitleLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        peersTitleLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isRightMouseButton(e)) {
                    Window window = SwingUtilities.getWindowAncestor(PeerMetricsPanel.this);
                    if (window instanceof JFrame) {
                        PeersDialog.showPeersDialog((JFrame) window);
                    }
                }
            }
        });
        headerPanel.add(peersTitleLabel, BorderLayout.WEST);
        tablesWrapper.add(headerPanel, BorderLayout.NORTH);

        tablesWrapper.setPreferredSize(tableDimension);
        tablesWrapper.setMinimumSize(tableDimension);

        overviewTablesPane = new JTabbedPane();
        overviewTablesPane.setBorder(BorderFactory.createEmptyBorder());

        // 1. Connected Tab
        PeersDialog.PeerTabPanel connectedPanel = new PeersDialog.PeerTabPanel(PeersDialog.PeerCategory.CONNECTED);
        connectedPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        ((BorderLayout) connectedPanel.getLayout()).setHgap(0);
        ((BorderLayout) connectedPanel.getLayout()).setVgap(0);

        Component connectedCenter = ((BorderLayout) connectedPanel.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        if (connectedCenter instanceof JScrollPane) {
            JScrollPane sp = (JScrollPane) connectedCenter;
            sp.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            sp.setViewportBorder(null);
        }

        Component connectedNorth = ((BorderLayout) connectedPanel.getLayout()).getLayoutComponent(BorderLayout.NORTH);
        if (connectedNorth instanceof JPanel) {
            JPanel p = (JPanel) connectedNorth;
            p.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
            ((BorderLayout) p.getLayout()).setHgap(0);
            ((BorderLayout) p.getLayout()).setVgap(0);
        }

        overviewTablesPane.addTab(PeersDialog.PeerCategory.CONNECTED.getTitle() + " (0)", connectedPanel);
        overviewPeerPanels.add(connectedPanel);
        overviewPeerCategories.add(PeersDialog.PeerCategory.CONNECTED);

        // 2. Active Tab
        PeersDialog.PeerTabPanel activePanel = new PeersDialog.PeerTabPanel(PeersDialog.PeerCategory.ACTIVE);
        activePanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        ((BorderLayout) activePanel.getLayout()).setHgap(0);
        ((BorderLayout) activePanel.getLayout()).setVgap(0);

        Component activeCenter = ((BorderLayout) activePanel.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        if (activeCenter instanceof JScrollPane) {
            JScrollPane sp = (JScrollPane) activeCenter;
            sp.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            sp.setViewportBorder(null);
        }

        Component activeNorth = ((BorderLayout) activePanel.getLayout()).getLayoutComponent(BorderLayout.NORTH);
        if (activeNorth instanceof JPanel) {
            JPanel p = (JPanel) activeNorth;
            p.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
            ((BorderLayout) p.getLayout()).setHgap(0);
            ((BorderLayout) p.getLayout()).setVgap(0);
        }

        overviewTablesPane.addTab(PeersDialog.PeerCategory.ACTIVE.getTitle() + " (0)", activePanel);
        overviewPeerPanels.add(activePanel);
        overviewPeerCategories.add(PeersDialog.PeerCategory.ACTIVE);

        // 3. All Tab
        PeersDialog.PeerTabPanel allPanel = new PeersDialog.PeerTabPanel(PeersDialog.PeerCategory.ALL);
        allPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        ((BorderLayout) allPanel.getLayout()).setHgap(0);
        ((BorderLayout) allPanel.getLayout()).setVgap(0);

        Component allCenter = ((BorderLayout) allPanel.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        if (allCenter instanceof JScrollPane) {
            JScrollPane sp = (JScrollPane) allCenter;
            sp.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            sp.setViewportBorder(null);
        }

        Component allNorth = ((BorderLayout) allPanel.getLayout()).getLayoutComponent(BorderLayout.NORTH);
        if (allNorth instanceof JPanel) {
            JPanel p = (JPanel) allNorth;
            p.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
            ((BorderLayout) p.getLayout()).setHgap(0);
            ((BorderLayout) p.getLayout()).setVgap(0);
        }

        overviewTablesPane.addTab(PeersDialog.PeerCategory.ALL.getTitle() + " (0)", allPanel);
        overviewPeerPanels.add(allPanel);
        overviewPeerCategories.add(PeersDialog.PeerCategory.ALL);

        // 4. Blacklisted Tab
        PeersDialog.PeerTabPanel blacklistedPanel = new PeersDialog.PeerTabPanel(PeersDialog.PeerCategory.BLACKLISTED);
        blacklistedPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        ((BorderLayout) blacklistedPanel.getLayout()).setHgap(0);
        ((BorderLayout) blacklistedPanel.getLayout()).setVgap(0);

        Component blacklistedCenter = ((BorderLayout) blacklistedPanel.getLayout())
                .getLayoutComponent(BorderLayout.CENTER);
        if (blacklistedCenter instanceof JScrollPane) {
            JScrollPane sp = (JScrollPane) blacklistedCenter;
            sp.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            sp.setViewportBorder(null);
        }

        Component blacklistedNorth = ((BorderLayout) blacklistedPanel.getLayout())
                .getLayoutComponent(BorderLayout.NORTH);
        if (blacklistedNorth instanceof JPanel) {
            JPanel p = (JPanel) blacklistedNorth;
            p.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
            ((BorderLayout) p.getLayout()).setHgap(0);
            ((BorderLayout) p.getLayout()).setVgap(0);
        }

        overviewTablesPane.addTab(PeersDialog.PeerCategory.BLACKLISTED.getTitle() + " (0)", blacklistedPanel);
        overviewPeerPanels.add(blacklistedPanel);
        overviewPeerCategories.add(PeersDialog.PeerCategory.BLACKLISTED);

        tablesWrapper.add(overviewTablesPane, BorderLayout.CENTER);

        overviewTablesPane.addChangeListener(e -> {
            if (uiOptimizationEnabled && isTabActive && overviewPanel.isShowing()) {
                refreshUI();
            }
        });

        overviewPanel.add(tablesWrapper, "grow, aligny top");

        tabbedPane.addTab("Overview", overviewPanel);

        add(tabbedPane, BorderLayout.CENTER);

        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) {
                boolean showing = isShowing();
                if (showing != isTabActive) {
                    isTabActive = showing;
                    if (isTabActive && uiOptimizationEnabled)
                        refreshUI();
                }
            }
        });
        isTabActive = isShowing();
    }

    private JPanel createMetricTab(String sectionTitle,
            JProgressBar responseTimeBar, JProgressBar countBar,
            String responseTimeLabel, String countLabel,
            String responseTimeTooltip, String countTooltip,
            XYSeries responseTimeSeries, XYSeries countSeries,
            XYSeries absMinSeries, XYSeries absMaxSeries,
            Color lineColor, Color barColor,
            MetricType type) {

        JPanel panel = new JPanel(
                new MigLayout((migLayoutDebug ? "debug, " : "") + "insets 0, fillx", "[]5![]5![grow, fill]", "[top]"));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));

        JPanel metricsPanel = new JPanel(
                new MigLayout((migLayoutDebug ? "debug, " : "") + "insets 0, wrap 1, gapy 4", "[fill, 300!]"));

        JLabel lLabel = createLabel(responseTimeLabel, lineColor, responseTimeTooltip);
        JLabel cLabel = createLabel(countLabel, barColor, countTooltip);

        JLabel tableTitleLabel = new JLabel(sectionTitle);
        tableTitleLabel.setHorizontalAlignment(SwingConstants.LEFT);
        tableTitleLabel.setFont(UIManager.getFont("TitledBorder.font"));
        if (tableTitleLabel.getFont() == null) {
            tableTitleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        }
        tableTitleLabel.setForeground(UIManager.getColor("TitledBorder.titleColor"));
        tableTitleLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        addMetricRow(metricsPanel, cLabel, countBar);
        addMetricRow(metricsPanel, lLabel, responseTimeBar);

        String absMaxTooltip = "The absolute maximum response time observed across all peers for " + type
                + " operations.\n\n"
                +
                "Displays: Current (C) | min - max over history.";
        JLabel absMaxLabel = createLabel("Abs Max Resp Time", COLOR_MAX_RESPONSE_TIME, absMaxTooltip);
        JProgressBar absMaxResponseTimeBar = createProgressBar("C: 0 | min: 0 - max: 0");
        addMetricRow(metricsPanel, absMaxLabel, absMaxResponseTimeBar);

        String absMinTooltip = "The absolute minimum response time observed across all peers for " + type
                + " operations.\n\n"
                +
                "Displays: Current (C) | min - max over history.";
        JLabel absMinLabel = createLabel("Abs Min Resp Time", COLOR_MIN_RESPONSE_TIME, absMinTooltip);
        JProgressBar absMinResponseTimeBar = createProgressBar("C: 0 | min: 0 - max: 0");
        addMetricRow(metricsPanel, absMinLabel, absMinResponseTimeBar);

        // Store UI references in client properties or map for update
        absMinResponseTimeBar.putClientProperty("metricType", type);
        absMinResponseTimeBar.putClientProperty("isMin", true);
        absMaxResponseTimeBar.putClientProperty("metricType", type);
        absMaxResponseTimeBar.putClientProperty("isMin", false);

        MovingAverage[] mas;
        if (type == MetricType.RX) {
            mas = new MovingAverage[] { rxResponseTimeMA, rxBlockCountMA };
        } else if (type == MetricType.TX) {
            mas = new MovingAverage[] { txResponseTimeMA, txBlockCountMA };
        } else {
            mas = new MovingAverage[] { otherResponseTimeMA, otherItemCountMA };
        }

        JPanel sliderPanel = createControlsPanel(type, mas);
        metricsPanel.add(sliderPanel, "growx");

        ChartPanel chartPanel = createChartPanel(responseTimeSeries, countSeries, absMinSeries, absMaxSeries, lineColor,
                barColor);
        chartPanels.put(type, chartPanel);
        zoomRanges.put(type, HISTORY_SIZE);
        addToggleListener(lLabel, chartPanel, responseTimeSeries.getKey().toString());
        addToggleListener(cLabel, chartPanel, countSeries.getKey().toString());
        addToggleListener(absMinLabel, chartPanel, absMinSeries.getKey().toString());
        addToggleListener(absMaxLabel, chartPanel, absMaxSeries.getKey().toString());

        panel.add(metricsPanel, "aligny top");
        panel.add(chartPanel, "aligny top");

        // Table Section (Center)
        PeersTableModel model = new PeersTableModel(type);
        JTable table = new JTable(model) {
            @Override
            public String getToolTipText(MouseEvent e) {
                String tip = super.getToolTipText(e);
                return "".equals(tip) ? null : tip;
            }

            @Override
            protected JTableHeader createDefaultTableHeader() {
                JTableHeader header = new JTableHeader(columnModel) {
                    @Override
                    public String getToolTipText(MouseEvent e) {
                        int index = columnModel.getColumnIndexAtX(e.getPoint().x);
                        int modelIndex = index == -1 ? -1 : columnModel.getColumn(index).getModelIndex();
                        return model.getColumnTooltip(modelIndex);
                    }
                };
                header.addMouseListener(new MouseAdapter() {
                    final int defaultDismissDelay = ToolTipManager.sharedInstance().getDismissDelay();

                    @Override
                    public void mouseEntered(MouseEvent e) {
                        ToolTipManager.sharedInstance().setDismissDelay(60000); // 1 minute
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        ToolTipManager.sharedInstance().setDismissDelay(defaultDismissDelay);
                    }
                });
                return header;
            }
        };
        ToolTipManager.sharedInstance().registerComponent(table);
        table.setToolTipText("");
        table.addMouseListener(new MouseAdapter() {
            final int defaultDismissDelay = ToolTipManager.sharedInstance().getDismissDelay();

            @Override
            public void mouseEntered(MouseEvent e) {
                ToolTipManager.sharedInstance().setDismissDelay(60000);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                ToolTipManager.sharedInstance().setDismissDelay(defaultDismissDelay);
            }
        });
        TableRowSorter<PeersTableModel> sorter = setupTable(table, model);

        JPanel tablePanel = new JPanel(new BorderLayout(0, 0));
        tablePanel.setBorder(TABLE_PANEL_BORDER);

        JPanel headerPanel = new JPanel(new BorderLayout(0, 0));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        headerPanel.add(tableTitleLabel, BorderLayout.NORTH);

        JPanel filterPanel = new JPanel(new BorderLayout());
        filterPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        filterPanel.add(new JLabel("Filter: "), BorderLayout.WEST);
        JTextField filterTextField = new JTextField();
        filterPanel.add(filterTextField, BorderLayout.CENTER);

        headerPanel.add(filterPanel, BorderLayout.SOUTH);
        tablePanel.add(headerPanel, BorderLayout.NORTH);

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        scrollPane.setViewportBorder(null);
        tablePanel.add(scrollPane, BorderLayout.CENTER);
        tablePanel.setPreferredSize(tableDimension);
        tablePanel.setMinimumSize(tableDimension);

        filterTextField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                filter();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                filter();
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                filter();
            }

            private void filter() {
                String text = filterTextField.getText();
                if (text.trim().length() == 0) {
                    sorter.setRowFilter(null);
                } else {
                    try {
                        sorter.setRowFilter(RowFilter.regexFilter("(?i)" + text));
                    } catch (java.util.regex.PatternSyntaxException pse) {
                    }
                }
            }
        });

        // Assign to class fields for updates
        if (type == MetricType.RX) {
            this.rxTableModel = model;
            this.rxPeersTable = table;
        } else if (type == MetricType.TX) {
            this.txTableModel = model;
            this.txPeersTable = table;
        } else {
            this.otherTableModel = model;
            this.otherPeersTable = table;
        }

        // Store bars for updates
        if (type == MetricType.RX) {
            this.rxResponseTimeBar.putClientProperty("absMinBar", absMinResponseTimeBar);
            this.rxResponseTimeBar.putClientProperty("absMaxBar", absMaxResponseTimeBar);
        } else if (type == MetricType.TX) {
            this.txResponseTimeBar.putClientProperty("absMinBar", absMinResponseTimeBar);
            this.txResponseTimeBar.putClientProperty("absMaxBar", absMaxResponseTimeBar);
        } else {
            this.otherResponseTimeBar.putClientProperty("absMinBar", absMinResponseTimeBar);
            this.otherResponseTimeBar.putClientProperty("absMaxBar", absMaxResponseTimeBar);
        }

        // Setup renderer and click listener
        PeerMetricTableCellRenderer renderer = new PeerMetricTableCellRenderer(lineColor, barColor);
        table.setDefaultRenderer(Object.class, renderer);
        table.setDefaultRenderer(Double.class, renderer);
        table.setDefaultRenderer(Long.class, renderer);
        table.setDefaultRenderer(Integer.class, renderer);

        tableTitleLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    String legend = getLegendHtml(type, lineColor, barColor);
                    PeerTableDialog.showDialog(SwingUtilities.getWindowAncestor(PeerMetricsPanel.this), sectionTitle,
                            model, renderer, legend);
                }
            }
        });

        panel.add(tablePanel, "grow, aligny top");

        return panel;
    }

    private JPanel createControlsPanel(MetricType type, MovingAverage... mas) {
        JPanel maWindowPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        maWindowPanel.setOpaque(false);
        String maWindowTooltip = "The number of recent data points used to calculate the moving average (MA) for the displayed metrics.\n"
                +
                "A larger window produces a smoother but less responsive trend, while a smaller window reacts more quickly to recent changes.";
        JLabel maWindowLabel = createLabel("MA Window", null, maWindowTooltip);
        maWindowPanel.add(maWindowLabel);

        final int[] maWindowValues = { 10, 100, 200, 300, 400, 500 };
        int currentWindow = movingAverageWindow;
        int initialSliderValue = 1; // Default to 100
        for (int i = 0; i < maWindowValues.length; i++) {
            if (currentWindow == maWindowValues[i]) {
                initialSliderValue = i;
                break;
            }
        }

        JSlider movingAverageSlider = new JSlider(JSlider.HORIZONTAL, 0, maWindowValues.length - 1, initialSliderValue);
        movingAverageSlider.setMajorTickSpacing(1);
        movingAverageSlider.setPaintTicks(true);
        movingAverageSlider.setSnapToTicks(true);
        movingAverageSlider.setPreferredSize(new Dimension(150, 40));

        java.util.Hashtable<Integer, JLabel> labelTable = new java.util.Hashtable<>();
        for (int i = 0; i < maWindowValues.length; i++) {
            labelTable.put(i, new JLabel(String.valueOf(maWindowValues[i])));
        }
        movingAverageSlider.setLabelTable(labelTable);
        movingAverageSlider.setPaintLabels(true);

        movingAverageSlider.addChangeListener(e -> {
            JSlider source = (JSlider) e.getSource();
            int newValue = maWindowValues[source.getValue()];
            chartUpdateExecutor.submit(() -> {
                synchronized (updateLock) {
                    for (MovingAverage ma : mas) {
                        ma.setWindowSize(newValue);
                    }
                }
            });
        });
        maWindowPanel.add(movingAverageSlider);

        maWindowPanel.add(Box.createHorizontalStrut(5));

        // Zoom controls
        JPanel zoomPanel = new JPanel(new MigLayout((migLayoutDebug ? "debug, " : "") + "insets 0", "[]5[]"));
        zoomPanel.setOpaque(false);

        JLabel zoomInLabel = new JLabel("+");
        zoomInLabel.setFont(zoomInLabel.getFont().deriveFont(Font.BOLD, 18f));
        zoomInLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        zoomInLabel.setToolTipText("Zoom In: Decrease chart range");
        zoomInLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    zoomIn(type);
                }
            }
        });

        JLabel zoomOutLabel = new JLabel("\u2212");
        zoomOutLabel.setFont(zoomOutLabel.getFont().deriveFont(Font.BOLD, 18f));
        zoomOutLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        zoomOutLabel.setToolTipText("Zoom Out: Increase chart range");
        zoomOutLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    zoomOut(type);
                }
            }
        });

        zoomPanel.add(zoomOutLabel);
        zoomPanel.add(zoomInLabel);
        maWindowPanel.add(zoomPanel);

        return maWindowPanel;
    }

    private void zoomIn(MetricType type) {
        int currentZoom = zoomRanges.getOrDefault(type, HISTORY_SIZE);
        XYSeries series = (type == MetricType.RX) ? rxResponseTimeSeries
                : (type == MetricType.TX ? txResponseTimeSeries : otherResponseTimeSeries);
        int maxItems = series.getItemCount();

        if (maxItems > 0 && currentZoom > maxItems) {
            currentZoom = maxItems;
        }

        if (currentZoom > 100) {
            currentZoom -= 100;
        } else {
            currentZoom -= 10;
        }
        if (currentZoom < 10) {
            currentZoom = 10;
        }
        zoomRanges.put(type, currentZoom);
        updateChartRange(type);
    }

    private void zoomOut(MetricType type) {
        int currentZoom = zoomRanges.getOrDefault(type, HISTORY_SIZE);
        if (currentZoom < 100) {
            currentZoom += 10;
        } else {
            currentZoom += 100;
        }
        if (currentZoom > HISTORY_SIZE) {
            currentZoom = HISTORY_SIZE;
        }
        zoomRanges.put(type, currentZoom);
        updateChartRange(type);
    }

    private TableRowSorter<PeersTableModel> setupTable(JTable table, PeersTableModel model) {
        table.setFillsViewportHeight(true);
        table.setCellSelectionEnabled(true);
        // Renderer is set in createMetricTab now
        TableRowSorter<PeersTableModel> sorter = new TableRowSorter<PeersTableModel>(model) {
            @Override
            public void toggleSortOrder(int column) {
                List<? extends RowSorter.SortKey> sortKeys = getSortKeys();
                if (sortKeys.size() > 0) {
                    if (sortKeys.get(0).getColumn() == column
                            && sortKeys.get(0).getSortOrder() == SortOrder.DESCENDING) {
                        setSortKeys(null);
                        return;
                    }
                }
                super.toggleSortOrder(column);
            }
        };
        table.setRowSorter(sorter);
        return sorter;
    }

    private JProgressBar createProgressBar(String initialString) {
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setBorder(BorderFactory.createEmptyBorder());
        bar.setPreferredSize(progressBarSize);
        bar.setMinimumSize(new Dimension(150, 20));
        bar.setStringPainted(true);
        bar.setString(initialString);
        return bar;
    }

    private void addMetricRow(JPanel panel, JLabel label, JProgressBar bar) {
        JPanel container = new JPanel(new BorderLayout(0, 3));
        container.setOpaque(false);
        container.add(label, BorderLayout.NORTH);
        container.add(bar, BorderLayout.CENTER);

        panel.add(container, "growx");
    }

    private ChartPanel createChartPanel(XYSeries lineSeries, XYSeries barSeries, XYSeries absMinSeries,
            XYSeries absMaxSeries, Color lineColor, Color barColor) {
        XYSeriesCollection lineDataset = new XYSeriesCollection(lineSeries);
        lineDataset.addSeries(absMinSeries);
        lineDataset.addSeries(absMaxSeries);
        XYSeriesCollection barDataset = new XYSeriesCollection(barSeries);
        barDataset.setIntervalWidth(1.0);

        JFreeChart chart = ChartFactory.createXYLineChart(
                null, null, null, null);

        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(Color.DARK_GRAY);
        plot.setDomainGridlinesVisible(false);
        plot.setRangeGridlinesVisible(false);
        plot.getDomainAxis().setTickLabelsVisible(false);
        plot.getDomainAxis().setTickMarksVisible(false);
        plot.getDomainAxis().setAxisLineVisible(false);
        plot.getRangeAxis().setTickLabelsVisible(false);
        plot.getDomainAxis().setLowerMargin(0.0);
        plot.getDomainAxis().setUpperMargin(0.0);

        // Axis 0: Response Time (Line) - Mapped to Dataset 1
        NumberAxis axis0 = (NumberAxis) plot.getRangeAxis();
        axis0.setLabel(null);
        axis0.setAutoRangeIncludesZero(true);
        axis0.setTickLabelsVisible(false);
        axis0.setTickMarksVisible(false);
        axis0.setAxisLineVisible(false);
        axis0.setLowerMargin(0.0);
        axis0.setUpperMargin(0.05);

        // Axis 1: Blocks (Bar) - Mapped to Dataset 0
        NumberAxis axis1 = new NumberAxis(null);
        axis1.setAutoRangeIncludesZero(true);
        axis1.setTickLabelsVisible(false);
        axis1.setTickMarksVisible(false);
        axis1.setAxisLineVisible(false);
        axis1.setLowerMargin(0.0);
        axis1.setUpperMargin(0.0);
        plot.setRangeAxis(AXIS_BARS, axis1);

        // Dataset 0: Bars
        plot.setDataset(DATASET_BARS, barDataset);
        plot.mapDatasetToRangeAxis(DATASET_BARS, AXIS_BARS);

        XYBarRenderer barRenderer = new XYBarRenderer();
        barRenderer.setBarPainter(new StandardXYBarPainter());
        barRenderer.setShadowVisible(false);
        barRenderer.setMargin(0.0);
        barRenderer.setSeriesPaint(0, barColor);
        barRenderer.setDefaultToolTipGenerator(new PeerChartToolTipGenerator());
        plot.setRenderer(DATASET_BARS, barRenderer);

        // Dataset 1: Lines
        plot.setDataset(DATASET_LINES, lineDataset);
        plot.mapDatasetToRangeAxis(DATASET_LINES, AXIS_LINES);

        XYLineAndShapeRenderer lineRenderer = new XYLineAndShapeRenderer(true, false);

        Map<String, Color> lineColors = new HashMap<>();
        lineColors.put(lineSeries.getKey().toString(), lineColor);
        lineColors.put(absMinSeries.getKey().toString(), COLOR_MIN_RESPONSE_TIME);
        lineColors.put(absMaxSeries.getKey().toString(), COLOR_MAX_RESPONSE_TIME);

        configureLineRenderer(lineRenderer, lineDataset, lineColors);
        plot.setRenderer(DATASET_LINES, lineRenderer);

        plot.setDatasetRenderingOrder(DatasetRenderingOrder.FORWARD);
        plot.setInsets(new RectangleInsets(0, 0, 0, 0));
        plot.setAxisOffset(new RectangleInsets(0, 0, 0, 0));

        chart.removeLegend();
        chart.setBorderVisible(false);

        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(chartDimension);
        chartPanel.setMinimumSize(chartDimension);
        chartPanel.setMaximumSize(chartDimension);
        chartPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        chartPanel.setDisplayToolTips(true);
        ToolTipManager.sharedInstance().registerComponent(chartPanel);
        return chartPanel;
    }

    private ChartPanel createOverviewChartPanel() {
        // Dataset 0: Bars (All Peers)
        XYSeriesCollection barDataset = new XYSeriesCollection();
        barDataset.addSeries(allSeries);
        barDataset.setIntervalWidth(1.0);

        // Dataset 1: Lines (Connected, Active, Blacklisted)
        XYSeriesCollection lineDataset = new XYSeriesCollection();
        lineDataset.addSeries(connectedSeries);
        lineDataset.addSeries(activeSeries);
        lineDataset.addSeries(blacklistedSeries);

        JFreeChart chart = ChartFactory.createXYLineChart(null, null, null, null);
        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(Color.DARK_GRAY);
        plot.setDomainGridlinesVisible(false);
        plot.setRangeGridlinesVisible(false);
        plot.getDomainAxis().setTickLabelsVisible(false);
        plot.getDomainAxis().setTickMarksVisible(false);
        plot.getDomainAxis().setAxisLineVisible(false);
        plot.getRangeAxis().setTickLabelsVisible(false);
        plot.getRangeAxis().setTickMarksVisible(false);
        plot.getRangeAxis().setAxisLineVisible(false);
        plot.getDomainAxis().setLowerMargin(0.0);
        plot.getDomainAxis().setUpperMargin(0.0);

        // Axis 1: Bars (All Peers) - Mapped to Dataset 0
        NumberAxis axis1 = new NumberAxis(null);
        axis1.setTickLabelsVisible(false);
        axis1.setTickMarksVisible(false);
        axis1.setAxisLineVisible(false);
        plot.setRangeAxis(AXIS_BARS, axis1);

        plot.setInsets(new RectangleInsets(0, 0, 0, 0));
        plot.setAxisOffset(new RectangleInsets(0, 0, 0, 0));

        // Renderer 0: Bars
        XYBarRenderer barRenderer = new XYBarRenderer();
        barRenderer.setBarPainter(new StandardXYBarPainter());
        barRenderer.setShadowVisible(false);
        barRenderer.setMargin(0.0);
        barRenderer.setSeriesPaint(0, COLOR_ALL_PEERS);
        barRenderer.setDefaultToolTipGenerator(new PeerChartToolTipGenerator());
        plot.setDataset(DATASET_BARS, barDataset);
        plot.setRenderer(DATASET_BARS, barRenderer);
        plot.mapDatasetToRangeAxis(DATASET_BARS, AXIS_BARS);

        // Renderer 1: Lines
        XYLineAndShapeRenderer lineRenderer = new XYLineAndShapeRenderer(true, false);
        Map<String, Color> overviewColors = new HashMap<>();
        overviewColors.put(connectedSeries.getKey().toString(), COLOR_CONNECTED_PEERS);
        overviewColors.put(activeSeries.getKey().toString(), COLOR_ACTIVE_PEERS);
        overviewColors.put(blacklistedSeries.getKey().toString(), COLOR_BLACKLISTED_PEERS);

        configureLineRenderer(lineRenderer, lineDataset, overviewColors);
        plot.setDataset(DATASET_LINES, lineDataset);
        plot.setRenderer(DATASET_LINES, lineRenderer);
        plot.mapDatasetToRangeAxis(DATASET_LINES, AXIS_LINES);

        plot.setDatasetRenderingOrder(DatasetRenderingOrder.FORWARD);
        chart.removeLegend();
        chart.setBorderVisible(false);

        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(chartDimension);
        chartPanel.setMinimumSize(chartDimension);
        chartPanel.setMaximumSize(chartDimension);
        chartPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        chartPanel.setDisplayToolTips(true);
        ToolTipManager.sharedInstance().registerComponent(chartPanel);
        return chartPanel;
    }

    /**
     * Helper to interpret the 'blocksReceived' field of PeerMetric.
     * <p>
     * The method name {@code getBlocksReceived()} in {@link PeerMetric} is
     * historically named
     * and can be misleading. It actually represents the "count of items
     * transferred",
     * which could be blocks, transactions, or IDs, and applies to both RX
     * (Received)
     * and TX (Transmitted) operations.
     * </p>
     *
     * @param metric The metric to extract the count from.
     * @return The adjusted count. For TX and OTHER, returns 1 if the raw count is 0
     *         (representing the request itself).
     */
    private static long getAdjustedCount(PeerMetric metric) {
        long count = metric.getBlocksReceived();
        if (count > 0) {
            return count;
        }
        // For TX and OTHER, if count is 0, we assume 1 unit of work (the request
        // itself)
        // to ensure visibility in charts/tables.
        // For RX, 0 is a valid state (e.g. no new blocks found).
        if (metric.getType() == PeerMetric.Type.BLOCK_RX) {
            return 0;
        }
        return 1;
    }

    /**
     * Handles incoming peer metric events.
     * Updates internal moving averages and triggers UI updates (throttled or
     * immediate).
     * Runs on the background executor.
     *
     * @param metric The peer metric event data.
     */
    private void onPeerMetric(PeerMetric metric) {
        chartUpdateExecutor.submit(() -> {
            synchronized (updateLock) {
                // 1. Update internal state (Fast)
                MetricType type;
                long currentCounter;

                if (metric.getType() == PeerMetric.Type.BLOCK_RX) {
                    type = MetricType.RX;
                    rxUpdateCounter++;
                    currentCounter = rxUpdateCounter;
                    rxResponseTimeMA.add((double) metric.getLatency());
                    rxBlockCountMA.add((double) getAdjustedCount(metric));
                    rxDirty = true;
                    lastRxPeer = metric.getPeerAddress();
                } else if (metric.getType() == PeerMetric.Type.BLOCK_TX) {
                    type = MetricType.TX;
                    txUpdateCounter++;
                    currentCounter = txUpdateCounter;
                    txResponseTimeMA.add((double) metric.getLatency());
                    txBlockCountMA.add((double) getAdjustedCount(metric));
                    txDirty = true;
                    lastTxPeer = metric.getPeerAddress();
                } else { // OTHER
                    type = MetricType.OTHER;
                    otherUpdateCounter++;
                    currentCounter = otherUpdateCounter;
                    otherResponseTimeMA.add((double) metric.getLatency());
                    otherItemCountMA.add((double) getAdjustedCount(metric));
                    otherDirty = true;
                    lastOtherPeer = metric.getPeerAddress();
                }

                // Update Peer History first
                PeerHistory peerHistory = peerHistories.computeIfAbsent(metric.getPeerAddress(),
                        k -> new PeerHistory(metric.getPeerAddress()));
                peerHistory.add(metric);

                AbsResponseTimeStats stats = absResponseTimeStats.computeIfAbsent(type,
                        k -> new AbsResponseTimeStats());
                long peerMinResponseTime = (type == MetricType.RX) ? peerHistory.blockMinResponseTime
                        : (type == MetricType.TX ? peerHistory.txBlockMinResponseTime
                                : peerHistory.otherMinResponseTime);
                long peerMaxResponseTime = (type == MetricType.RX) ? peerHistory.blockMaxResponseTime
                        : (type == MetricType.TX ? peerHistory.txBlockMaxResponseTime
                                : peerHistory.otherMaxResponseTime);

                if (peerMinResponseTime > 0 && peerMinResponseTime < stats.minResponseTime) {
                    stats.minResponseTime = peerMinResponseTime;
                    stats.peerWithMinResponseTime = peerHistory.address;
                } else if (peerHistory.address.equals(stats.peerWithMinResponseTime)
                        && peerMinResponseTime > stats.minResponseTime) {
                    recalculateAbsMinResponseTime(type, stats);
                }

                if (peerMaxResponseTime > stats.maxResponseTime) {
                    stats.maxResponseTime = peerMaxResponseTime;
                    stats.peerWithMaxResponseTime = peerHistory.address;
                } else if (peerHistory.address.equals(stats.peerWithMaxResponseTime)
                        && peerMaxResponseTime < stats.maxResponseTime) {
                    recalculateAbsMaxResponseTime(type, stats);
                }

                // Update Abs MAs
                if (type == MetricType.RX) {
                    rxAbsMinMA.add(stats.minResponseTime == Long.MAX_VALUE ? 0 : (double) stats.minResponseTime);
                    rxAbsMaxMA.add((double) stats.maxResponseTime);
                } else if (type == MetricType.TX) {
                    txAbsMinMA.add(stats.minResponseTime == Long.MAX_VALUE ? 0 : (double) stats.minResponseTime);
                    txAbsMaxMA.add((double) stats.maxResponseTime);
                } else {
                    otherAbsMinMA.add(stats.minResponseTime == Long.MAX_VALUE ? 0 : (double) stats.minResponseTime);
                    otherAbsMaxMA.add((double) stats.maxResponseTime);
                }

                if (!throttlingEnabled) {
                    processBufferedUpdates();
                }
            }
        });
    }

    /**
     * Processes buffered metric updates and schedules a UI refresh on the EDT.
     * Used when throttling is enabled to batch high-frequency updates.
     */
    private void processBufferedUpdates() {
        synchronized (updateLock) {
            if (rxDirty) {
                MetricsUpdateData data = new MetricsUpdateData();
                data.updatedType = MetricType.RX;
                data.typeCounter = rxUpdateCounter;
                data.updatedPeerAddress = lastRxPeer;
                data.rx = new MetricSnapshot(rxResponseTimeMA, rxBlockCountMA);
                data.rxAbsMin = new MetricSnapshot(rxAbsMinMA, null);
                data.rxAbsMax = new MetricSnapshot(rxAbsMaxMA, null);
                data.rxPeers = getPeerSnapshots(MetricType.RX);
                this.lastRxUpdateData = data;
                SwingUtilities.invokeLater(() -> {
                    updateGlobalUI(data);
                    updateTable(data);
                });
                rxDirty = false;
            }
            if (txDirty) {
                MetricsUpdateData data = new MetricsUpdateData();
                data.updatedType = MetricType.TX;
                data.typeCounter = txUpdateCounter;
                data.updatedPeerAddress = lastTxPeer;
                data.tx = new MetricSnapshot(txResponseTimeMA, txBlockCountMA);
                data.txAbsMin = new MetricSnapshot(txAbsMinMA, null);
                data.txAbsMax = new MetricSnapshot(txAbsMaxMA, null);
                data.txPeers = getPeerSnapshots(MetricType.TX);
                this.lastTxUpdateData = data;
                SwingUtilities.invokeLater(() -> {
                    updateGlobalUI(data);
                    updateTable(data);
                });
                txDirty = false;
            }
            if (otherDirty) {
                MetricsUpdateData data = new MetricsUpdateData();
                data.updatedType = MetricType.OTHER;
                data.typeCounter = otherUpdateCounter;
                data.updatedPeerAddress = lastOtherPeer;
                data.other = new MetricSnapshot(otherResponseTimeMA, otherItemCountMA);
                data.otherAbsMin = new MetricSnapshot(otherAbsMinMA, null);
                data.otherAbsMax = new MetricSnapshot(otherAbsMaxMA, null);
                data.otherPeers = getPeerSnapshots(MetricType.OTHER);
                this.lastOtherUpdateData = data;
                SwingUtilities.invokeLater(() -> {
                    updateGlobalUI(data);
                    updateTable(data);
                });
                otherDirty = false;
            }
        }
    }

    /**
     * Handles the PEERS_UPDATED event.
     * Recalculates overview statistics and updates the overview tab.
     *
     * @param block The block associated with the update (unused here).
     */
    private void onPeersUpdated(brs.Block block) {
        chartUpdateExecutor.submit(() -> {
            synchronized (updateLock) {
                String latestVersion = Signum.VERSION.toString();
                Collection<Peer> allPeers = Peers.getAllPeers();
                for (Peer p : allPeers) {
                    String v = p.getVersion() != null ? p.getVersion().toString() : "";
                    if (!v.isEmpty() && !"unknown".equals(v)) {
                        if (PeersDialog.compareVersions(v, latestVersion) > 0) {
                            latestVersion = v;
                        }
                    }
                }
                this.latestNetworkVersion = latestVersion;

                overviewUpdateCounter++;
                OverviewUpdateData overviewData = calculateOverviewUpdate(overviewUpdateCounter);
                this.lastOverviewUpdateData = overviewData;
                SwingUtilities.invokeLater(() -> {
                    if (overviewData != null) {
                        applyOverviewUpdate(overviewData);
                    }
                });
            }
        });
    }

    private List<PeerStatsSnapshot> getPeerSnapshots(MetricType type) {
        List<PeerStatsSnapshot> snapshots = new ArrayList<>();
        for (PeerHistory ph : peerHistories.values()) {
            if (type == MetricType.RX && ph.blockRequestCount > 0)
                snapshots.add(new PeerStatsSnapshot(ph, type, latestNetworkVersion));
            else if (type == MetricType.TX && ph.txBlockRequestCount > 0)
                snapshots.add(new PeerStatsSnapshot(ph, type, latestNetworkVersion));
            else if (type == MetricType.OTHER && ph.otherRequestCount > 0)
                snapshots.add(new PeerStatsSnapshot(ph, type, latestNetworkVersion));
        }
        snapshots.sort((p1, p2) -> Long.compare(p2.lastTimestamp, p1.lastTimestamp));
        return snapshots;
    }

    /**
     * Updates the global UI components (charts, progress bars) for a specific
     * metric type.
     * Runs on the EDT.
     *
     * @param data The snapshot of data to display.
     */
    private void updateGlobalUI(MetricsUpdateData data) {
        ChartPanel panel = chartPanels.get(data.updatedType);
        if (panel != null) {
            panel.getChart().getXYPlot().setNotify(false);
        }
        try {
            if (data.updatedType == MetricType.RX) {
                updateMetricSet(data.rx, rxResponseTimeBar, rxCountBar, rxResponseTimeSeries, rxCountSeries, "ms", "",
                        data.typeCounter);
                updateChartRange(MetricType.RX);
                updateAbsMetric(rxResponseTimeBar, data.rxAbsMin, data.rxAbsMax, rxAbsMinSeries, rxAbsMaxSeries,
                        data.typeCounter);
            } else if (data.updatedType == MetricType.TX) {
                updateMetricSet(data.tx, txResponseTimeBar, txCountBar, txResponseTimeSeries, txCountSeries, "ms", "",
                        data.typeCounter);
                updateChartRange(MetricType.TX);
                updateAbsMetric(txResponseTimeBar, data.txAbsMin, data.txAbsMax, txAbsMinSeries, txAbsMaxSeries,
                        data.typeCounter);
            } else {
                updateMetricSet(data.other, otherResponseTimeBar, otherCountBar, otherResponseTimeSeries,
                        otherCountSeries, "ms",
                        "",
                        data.typeCounter);
                updateChartRange(MetricType.OTHER);
                updateAbsMetric(otherResponseTimeBar, data.otherAbsMin, data.otherAbsMax, otherAbsMinSeries,
                        otherAbsMaxSeries,
                        data.typeCounter);
            }
        } finally {
            if (panel != null) {
                if (!uiOptimizationEnabled || isTabActive) {
                    panel.getChart().getXYPlot().setNotify(true);
                }
            }
        }
    }

    private void updateAbsMetric(JProgressBar parentBar, MetricSnapshot minSnap, MetricSnapshot maxSnap,
            XYSeries minSeries, XYSeries maxSeries, long counter) {
        JProgressBar minBar = (JProgressBar) parentBar.getClientProperty("absMinBar");
        JProgressBar maxBar = (JProgressBar) parentBar.getClientProperty("absMaxBar");

        if (minBar != null) {
            updateMetricSet(minSnap, minBar, null, minSeries, null, "ms", "", counter);
            minBar.setString(
                    String.format("C: %.0f | min: %.0f - max: %.0f", minSnap.respCur, minSnap.respMin,
                            minSnap.respMax));
        }
        if (maxBar != null) {
            updateMetricSet(maxSnap, maxBar, null, maxSeries, null, "ms", "", counter);
            maxBar.setString(
                    String.format("C: %.0f | min: %.0f - max: %.0f", maxSnap.respCur, maxSnap.respMin,
                            maxSnap.respMax));
        }
    }

    private void updateMetricSet(MetricSnapshot snapshot,
            JProgressBar respBar, JProgressBar countBar,
            XYSeries respSeries, XYSeries countSeries,
            String respUnit, String countUnit, long counter) {

        // Update Series - ALWAYS update data model to prevent gaps
        respSeries.addOrUpdate((double) counter, snapshot.respAvg);
        if (countSeries != null) {
            countSeries.addOrUpdate((double) counter, snapshot.countAvg);
        }

        if (respSeries.getItemCount() > HISTORY_SIZE) {
            respSeries.remove(0);
            if (countSeries != null)
                countSeries.remove(0);
        }

        if (uiOptimizationEnabled && !isTabActive)
            return;

        respBar.setMaximum((int) (snapshot.respMax > 0 ? snapshot.respMax : 100));
        respBar.setValue((int) snapshot.respCur);
        respBar.setString(String.format("C: %.0f | MA: %.0f - min: %.0f - max: %.0f %s", snapshot.respCur,
                snapshot.respAvg, snapshot.respMin, snapshot.respMax, respUnit));

        if (countBar != null) {
            countBar.setMaximum((int) (snapshot.countMax > 0 ? snapshot.countMax : 100));
            countBar.setValue((int) snapshot.countCur);
            countBar.setString(String.format("C: %.0f | MA: %.2f - min: %.2f - max: %.2f %s", snapshot.countCur,
                    snapshot.countAvg, snapshot.countMin, snapshot.countMax, countUnit));
        }
    }

    private void updateChartRange(MetricType type) {
        ChartPanel panel = chartPanels.get(type);
        if (panel == null)
            return;

        XYSeries series = (type == MetricType.RX) ? rxResponseTimeSeries
                : (type == MetricType.TX ? txResponseTimeSeries : otherResponseTimeSeries);
        if (series.getItemCount() == 0)
            return;

        double lastX = series.getX(series.getItemCount() - 1).doubleValue();
        int range = Math.min(Math.max(series.getItemCount(), 10), zoomRanges.getOrDefault(type, HISTORY_SIZE));

        panel.getChart().getXYPlot().getDomainAxis().setRange(lastX - range + 0.5, lastX + 0.5);
    }

    private OverviewUpdateData calculateOverviewUpdate(long counter) {
        OverviewUpdateData data = new OverviewUpdateData();
        int all = Peers.getAllPeers().size();
        int connected = 0;
        int active = 0;
        int blacklisted = 0;

        for (Peer p : Peers.getAllPeers()) {
            if (p.getState() == Peer.State.CONNECTED)
                connected++;
            if (p.getState() != Peer.State.NON_CONNECTED)
                active++;
            if (p.isBlacklisted())
                blacklisted++;
        }

        Collection<Peer> allPeersList = Peers.getAllPeers();
        long maxHeight = 0;
        String latestVersion = Signum.VERSION.toString();

        for (Peer p : allPeersList) {
            if (p.getState() == Peer.State.CONNECTED) {
                maxHeight = Math.max(maxHeight, p.getHeight());
            }
            String v = p.getVersion() != null ? p.getVersion().toString() : "";
            if (!v.isEmpty() && !"unknown".equals(v)) {
                if (PeersDialog.compareVersions(v, latestVersion) > 0) {
                    latestVersion = v;
                }
            }
        }

        data.all = all;
        data.connected = connected;
        data.active = active;
        data.blacklisted = blacklisted;
        data.maxHeight = maxHeight;
        data.latestVersion = latestVersion;
        data.updateCounter = counter;

        // Filter lists for tabs
        data.connectedList = new ArrayList<>();
        data.activeList = new ArrayList<>();
        data.allList = new ArrayList<>();
        data.blacklistedList = new ArrayList<>();

        for (Peer p : allPeersList) {
            if (PeersDialog.PeerCategory.CONNECTED.getFilter().test(p))
                data.connectedList.add(p);
            if (PeersDialog.PeerCategory.ACTIVE.getFilter().test(p))
                data.activeList.add(p);
            if (PeersDialog.PeerCategory.ALL.getFilter().test(p))
                data.allList.add(p);
            if (PeersDialog.PeerCategory.BLACKLISTED.getFilter().test(p))
                data.blacklistedList.add(p);
        }

        // Regenerate snapshots for tables to reflect state changes (e.g. blacklisting)
        // immediately
        data.rxPeers = getPeerSnapshots(MetricType.RX);
        data.txPeers = getPeerSnapshots(MetricType.TX);
        data.otherPeers = getPeerSnapshots(MetricType.OTHER);

        return data;
    }

    private void applyOverviewUpdate(OverviewUpdateData data) {
        if (overviewChartPanel != null) {
            overviewChartPanel.getChart().getXYPlot().setNotify(false);
        }
        try {
            connectedSeries.addOrUpdate((double) data.updateCounter, (double) data.connected);
            activeSeries.addOrUpdate((double) data.updateCounter, (double) data.active);
            allSeries.addOrUpdate((double) data.updateCounter, (double) data.all);
            blacklistedSeries.addOrUpdate((double) data.updateCounter, (double) data.blacklisted);
        } finally {
            if (overviewChartPanel != null) {
                if (!uiOptimizationEnabled || isTabActive) {
                    overviewChartPanel.getChart().getXYPlot().setNotify(true);
                }
            }
        }

        if (connectedSeries.getItemCount() > HISTORY_SIZE) {
            connectedSeries.remove(0);
            activeSeries.remove(0);
            allSeries.remove(0);
            blacklistedSeries.remove(0);
        }

        if (uiOptimizationEnabled && !isTabActive)
            return;

        updateCountBar(allPeersBar, data.all, data.all);
        updateCountBar(connectedPeersBar, data.connected, data.all);
        updateCountBar(activePeersBar, data.active, data.all);
        updateCountBar(blacklistedPeersBar, data.blacklisted, data.all);

        // Update overview tabs
        for (int i = 0; i < overviewPeerCategories.size(); i++) {
            PeersDialog.PeerCategory category = overviewPeerCategories.get(i);
            List<Peer> list = null;
            int count = 0;
            switch (category) {
                case CONNECTED:
                    list = data.connectedList;
                    count = data.connected;
                    break;
                case ACTIVE:
                    list = data.activeList;
                    count = data.active;
                    break;
                case ALL:
                    list = data.allList;
                    count = data.all;
                    break;
                case BLACKLISTED:
                    list = data.blacklistedList;
                    count = data.blacklisted;
                    break;
            }
            if (list != null) {
                if (!uiOptimizationEnabled || overviewPeerPanels.get(i).isShowing()) {
                    overviewPeerPanels.get(i).update(list, data.maxHeight, data.latestVersion);
                }
                if (overviewTablesPane != null) {
                    overviewTablesPane.setTitleAt(i, category.getTitle() + " (" + count + ")");
                }
            }
        }

        // Update metric tables with fresh snapshots
        if (!uiOptimizationEnabled || rxPeersTable.isShowing()) {
            rxTableModel.setData(data.rxPeers, null);
        }
        if (!uiOptimizationEnabled || txPeersTable.isShowing()) {
            txTableModel.setData(data.txPeers, null);
        }
        if (!uiOptimizationEnabled || otherPeersTable.isShowing()) {
            otherTableModel.setData(data.otherPeers, null);
        }
    }

    private void updateCountBar(JProgressBar bar, int value, int max) {
        bar.setMaximum(max > 0 ? max : 100);
        bar.setValue(value);
        bar.setString(String.valueOf(value));
    }

    /**
     * Updates the peer tables with new data.
     * Checks for UI optimization and visibility before performing expensive table
     * operations.
     * Runs on the EDT.
     *
     * @param data The snapshot of data containing peer lists.
     */
    private void updateTable(MetricsUpdateData data) {
        JTable table;
        PeersTableModel model;

        if (data.updatedType == MetricType.RX) {
            table = rxPeersTable;
            model = rxTableModel;
        } else if (data.updatedType == MetricType.TX) {
            table = txPeersTable;
            model = txTableModel;
        } else {
            table = otherPeersTable;
            model = otherTableModel;
        }

        if (uiOptimizationEnabled && (table != null && !table.isShowing()) && !PeerTableDialog.isDialogVisible()) {
            return;
        }

        if (model != null) {
            model.setData(
                    data.updatedType == MetricType.RX ? data.rxPeers
                            : (data.updatedType == MetricType.TX ? data.txPeers : data.otherPeers),
                    data.updatedPeerAddress);
            if (table != null && table.isShowing()) {
                TableUtils.packTableColumns(table);
            }
            PeerTableDialog.packColumns();
        }
    }

    private JLabel createLabel(String text, Color color, String tooltip) {
        JLabel label = new JLabel(text);
        if (color != null) {
            if (color.getAlpha() < 255) {
                color = new Color(color.getRed(), color.getGreen(), color.getBlue());
            }
            label.setForeground(color);
        }
        if (tooltip != null) {
            addInfoTooltip(label, tooltip);
        }
        return label;
    }

    private void addInfoTooltip(JLabel label, String text) {
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    String title = label.getText();
                    if (title.endsWith(":")) {
                        title = title.substring(0, title.length() - 1);
                    }
                    String htmlText = "<html><body><p style='width: 300px;'>" + text.replace("\n", "<br>")
                            + "</p></body></html>";
                    JOptionPane.showMessageDialog(PeerMetricsPanel.this, htmlText, title, JOptionPane.PLAIN_MESSAGE);
                }
            }
        });
    }

    private void addLabelToggleListener(JLabel label, Consumer<Boolean> onToggleAction) {
        label.putClientProperty("visible", true);
        final Font originalFont = label.getFont();
        final Map<TextAttribute, Object> attributes = new HashMap<>(originalFont.getAttributes());
        attributes.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
        final Font strikethroughFont = originalFont.deriveFont(attributes);

        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent evt) {
                if (SwingUtilities.isLeftMouseButton(evt)) {
                    // Toggle the visibility state
                    boolean isVisible = !((boolean) label.getClientProperty("visible"));
                    label.putClientProperty("visible", isVisible);

                    // Update the label's font to show the state
                    label.setFont(isVisible ? originalFont : strikethroughFont);

                    // Perform the specific toggle action
                    onToggleAction.accept(isVisible);
                }
            }
        });
    }

    private void addToggleListener(JLabel label, ChartPanel chartPanel, String... seriesKeys) {
        addLabelToggleListener(label, isVisible -> {
            XYPlot plot = chartPanel.getChart().getXYPlot();
            int datasetCount = plot.getDatasetCount();
            for (String key : seriesKeys) {
                for (int i = 0; i < datasetCount; i++) {
                    XYDataset dataset = plot.getDataset(i);
                    if (dataset instanceof XYSeriesCollection) {
                        XYSeriesCollection seriesCollection = (XYSeriesCollection) dataset;
                        int seriesIndex = seriesCollection.getSeriesIndex(key);
                        if (seriesIndex >= 0) {
                            if (plot.getRenderer(i) != null) {
                                plot.getRenderer(i).setSeriesVisible(seriesIndex, isVisible);
                            }
                            break;
                        }
                    }
                }
            }
        });
    }

    private void recalculateAbsMinResponseTime(MetricType type, AbsResponseTimeStats stats) {
        long newMin = Long.MAX_VALUE;
        String newPeer = null;
        for (PeerHistory ph : peerHistories.values()) {
            long val = (type == MetricType.RX) ? ph.blockMinResponseTime
                    : (type == MetricType.TX ? ph.txBlockMinResponseTime : ph.otherMinResponseTime);
            if (val > 0 && val < newMin) {
                newMin = val;
                newPeer = ph.address;
            }
        }
        stats.minResponseTime = newMin;
        stats.peerWithMinResponseTime = newPeer;
    }

    private void recalculateAbsMaxResponseTime(MetricType type, AbsResponseTimeStats stats) {
        long newMax = 0;
        String newPeer = null;
        for (PeerHistory ph : peerHistories.values()) {
            long val = (type == MetricType.RX) ? ph.blockMaxResponseTime
                    : (type == MetricType.TX ? ph.txBlockMaxResponseTime : ph.otherMaxResponseTime);
            if (val > newMax) {
                newMax = val;
                newPeer = ph.address;
            }
        }
        stats.maxResponseTime = newMax;
        stats.peerWithMaxResponseTime = newPeer;
    }

    private class PeersTableModel extends AbstractTableModel {
        private final MetricType type;
        private final String[] columns;
        private List<PeerStatsSnapshot> peerData = new ArrayList<>();
        private String lastUpdatedPeer = null;
        double maxAvgResponseTime = -1;
        double minAvgResponseTime = Double.MAX_VALUE;
        long minMinResponseTime = Long.MAX_VALUE;
        long maxMinResponseTime = -1;
        long minMaxResponseTime = Long.MAX_VALUE;
        long maxMaxResponseTime = -1;
        long minLastResponseTime = Long.MAX_VALUE;
        long maxLastResponseTime = -1;

        PeersTableModel(MetricType type) {
            this.type = type;
            if (type == MetricType.RX || type == MetricType.TX) {
                columns = new String[] {
                        COL_TIME, COL_ADDRESS, COL_ANNOUNCED, COL_STATE, COL_VERSION, COL_HEIGHT,
                        COL_AVG_RESP, COL_MIN_RESP, COL_MAX_RESP, COL_LAST_RESP,
                        COL_AVG_BLOCKS, COL_TOTAL_BLOCKS,
                        COL_REQ
                };
            } else { // TX and OTHER
                columns = new String[] {
                        COL_TIME, COL_ADDRESS, COL_ANNOUNCED, COL_STATE, COL_VERSION, COL_HEIGHT,
                        COL_AVG_RESP, COL_MIN_RESP, COL_MAX_RESP, COL_LAST_RESP,
                        COL_AVG_ITEMS, COL_TOTAL_ITEMS,
                        COL_REQ
                };
            }
        }

        void setData(List<PeerStatsSnapshot> newData, String updatedPeer) {
            this.peerData = newData;
            if (updatedPeer != null) {
                this.lastUpdatedPeer = updatedPeer;
            }
            calculateExtremes();
            fireTableDataChanged();
        }

        private void calculateExtremes() {
            maxAvgResponseTime = -1;
            minAvgResponseTime = Double.MAX_VALUE;
            minMinResponseTime = Long.MAX_VALUE;
            maxMinResponseTime = -1;
            minMaxResponseTime = Long.MAX_VALUE;
            maxMaxResponseTime = -1;
            minLastResponseTime = Long.MAX_VALUE;
            maxLastResponseTime = -1;

            if (peerData.isEmpty())
                return;

            for (PeerStatsSnapshot p : peerData) {
                // Avg
                if (p.avgResponseTime > maxAvgResponseTime)
                    maxAvgResponseTime = p.avgResponseTime;
                if (p.avgResponseTime < minAvgResponseTime)
                    minAvgResponseTime = p.avgResponseTime;
                // Min
                if (p.minResponseTime < minMinResponseTime)
                    minMinResponseTime = p.minResponseTime;
                if (p.minResponseTime > maxMinResponseTime)
                    maxMinResponseTime = p.minResponseTime;
                // Max
                if (p.maxResponseTime < minMaxResponseTime)
                    minMaxResponseTime = p.maxResponseTime;
                if (p.maxResponseTime > maxMaxResponseTime)
                    maxMaxResponseTime = p.maxResponseTime;
                // Last
                if (p.lastResponseTime < minLastResponseTime)
                    minLastResponseTime = p.lastResponseTime;
                if (p.lastResponseTime > maxLastResponseTime)
                    maxLastResponseTime = p.lastResponseTime;
            }
        }

        PeerStatsSnapshot getSnapshotAt(int row) {
            if (row >= 0 && row < peerData.size()) {
                return peerData.get(row);
            }
            return null;
        }

        @Override
        public int getRowCount() {
            return peerData.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        String getColumnTooltip(int column) {
            if (column < 0 || column >= columns.length)
                return null;
            String name = columns[column];
            switch (name) {
                case COL_TIME:
                    return "The timestamp of the most recent communication with this peer.";
                case COL_ADDRESS:
                    return "The IP address or hostname of the peer.";
                case COL_ANNOUNCED:
                    return "The announced address of the peer (if configured).";
                case COL_STATE:
                    return "Connection state (CONNECTED, DISCONNECTED, NON_CONNECTED).";
                case COL_VERSION:
                    return "The software version the peer is running.";
                case COL_HEIGHT:
                    return "The blockchain height reported by the peer.";
                case COL_AVG_RESP:
                    return "Avg R. = Average Response Time in milliseconds.";
                case COL_MIN_RESP:
                    return "Min R. = Minimum Response Time observed in milliseconds.";
                case COL_MAX_RESP:
                    return "Max R. = Maximum Response Time observed in milliseconds.";
                case COL_LAST_RESP:
                    return "Last R. = Response Time of the most recent request in milliseconds.";
                case COL_REQ:
                    return "Req = Total number of requests sent to/received from this peer.";
                case COL_AVG_BLOCKS:
                    return "Avg B. = Average number of blocks transferred per request.";
                case COL_TOTAL_BLOCKS:
                    return "Tot B. = Total number of blocks transferred with this peer.";
                case COL_AVG_ITEMS:
                    return "Avg I. = Average number of items (e.g. block IDs) received per request.";
                case COL_TOTAL_ITEMS:
                    return "Tot I. = Total number of items received from this peer.";
                default:
                    return null;
            }
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (rowIndex >= peerData.size())
                return null;
            PeerStatsSnapshot history = peerData.get(rowIndex);

            String columnName = getColumnName(columnIndex);
            switch (columnName) {
                case COL_TIME:
                    if (history.lastTimestamp > 0) {
                        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                .format(new Date(history.lastTimestamp));
                    }
                    return "-";
                case COL_ADDRESS:
                    return history.address;
                case COL_ANNOUNCED:
                    return history.announcedAddress;
                case COL_STATE:
                    return history.state;
                case COL_VERSION:
                    return history.version;
                case COL_HEIGHT:
                    return history.height;
                case COL_AVG_RESP:
                    return history.avgResponseTime;
                case COL_MIN_RESP:
                    return history.minResponseTime;
                case COL_MAX_RESP:
                    return history.maxResponseTime;
                case COL_LAST_RESP:
                    return history.lastResponseTime;
                case COL_REQ:
                    return history.requestCount;
                case COL_AVG_BLOCKS:
                case COL_AVG_ITEMS:
                    return history.avgBlocks;
                case COL_TOTAL_BLOCKS:
                case COL_TOTAL_ITEMS:
                    return history.totalBlocks;
                default:
                    return null;
            }
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            String columnName = getColumnName(columnIndex);
            switch (columnName) {
                case COL_ADDRESS:
                case COL_ANNOUNCED:
                case COL_STATE:
                case COL_VERSION:
                    return String.class;
                case COL_HEIGHT:
                    return Integer.class;
                case COL_AVG_RESP:
                case COL_AVG_BLOCKS:
                case COL_AVG_ITEMS:
                    return Double.class;
                case COL_MIN_RESP:
                case COL_MAX_RESP:
                case COL_LAST_RESP:
                case COL_REQ:
                case COL_TOTAL_BLOCKS:
                case COL_TOTAL_ITEMS:
                    return Long.class;
                default:
                    return Object.class;
            }
        }
    }

    private static class PeerHistory {
        final String address;
        final long creationTime;

        // Block stats
        final List<PeerMetric> blockHistory = new ArrayList<>();
        long blockTotalResponseTime = 0;
        long blockTotalBlocks = 0;
        long blockRequestCount = 0;
        long blockMinResponseTime = 0;
        long blockMaxResponseTime = 0;
        int blockMinBlocks = 0;
        int blockMaxBlocks = 0;
        long cumulativeBlockTotalBlocks = 0;

        // TX Block stats
        final List<PeerMetric> txBlockHistory = new ArrayList<>();
        long txBlockTotalResponseTime = 0;
        long txBlockRequestCount = 0;
        long txBlockMinResponseTime = 0;
        long txBlockMaxResponseTime = 0;
        long txBlockTotalBlocks = 0;
        long cumulativeTxBlockTotalBlocks = 0;

        // Other stats
        final List<PeerMetric> otherHistory = new ArrayList<>();
        long otherTotalResponseTime = 0;
        long otherTotalItems = 0;
        long otherRequestCount = 0;
        long otherMinResponseTime = 0;
        long otherMaxResponseTime = 0;
        long cumulativeOtherTotalItems = 0;

        PeerHistory(String address) {
            this.address = address;
            this.creationTime = System.currentTimeMillis();
        }

        void add(PeerMetric metric) {
            if (metric.getType() == PeerMetric.Type.BLOCK_TX) {
                txBlockHistory.add(metric);
                txBlockTotalResponseTime += metric.getLatency();
                long count = getAdjustedCount(metric);
                txBlockTotalBlocks += count;
                cumulativeTxBlockTotalBlocks += count;
                if (txBlockRequestCount < Long.MAX_VALUE) {
                    txBlockRequestCount++;
                }
                if (txBlockHistory.size() > PEER_HISTORY_SIZE) {
                    PeerMetric removed = txBlockHistory.remove(0);
                    long removedCount = getAdjustedCount(removed);
                    txBlockTotalResponseTime -= removed.getLatency();
                    txBlockTotalBlocks -= removedCount;
                }
                recalcStats(txBlockHistory, MetricType.TX);
            } else if (metric.getType() == PeerMetric.Type.BLOCK_RX) {
                blockHistory.add(metric);
                blockTotalResponseTime += metric.getLatency();
                blockTotalBlocks += metric.getBlocksReceived();
                cumulativeBlockTotalBlocks += metric.getBlocksReceived();
                if (blockRequestCount < Long.MAX_VALUE) {
                    blockRequestCount++;
                }
                if (blockHistory.size() > PEER_HISTORY_SIZE) {
                    PeerMetric removed = blockHistory.remove(0);
                    blockTotalResponseTime -= removed.getLatency();
                    blockTotalBlocks -= removed.getBlocksReceived();
                }
                recalcStats(blockHistory, MetricType.RX);
            } else {
                otherHistory.add(metric);
                otherTotalResponseTime += metric.getLatency();
                long count = getAdjustedCount(metric);
                otherTotalItems += count;
                cumulativeOtherTotalItems += count;
                if (otherRequestCount < Long.MAX_VALUE) {
                    otherRequestCount++;
                }
                if (otherHistory.size() > PEER_HISTORY_SIZE) {
                    PeerMetric removed = otherHistory.remove(0);
                    long removedCount = getAdjustedCount(removed);
                    otherTotalResponseTime -= removed.getLatency();
                    otherTotalItems -= removedCount;
                }
                recalcStats(otherHistory, MetricType.OTHER);
            }
        }

        private void recalcStats(List<PeerMetric> list, MetricType type) {
            if (list.isEmpty()) {
                if (type == MetricType.RX) {
                    blockMinResponseTime = 0;
                    blockMaxResponseTime = 0;
                    blockMinBlocks = 0;
                    blockMaxBlocks = 0;
                } else if (type == MetricType.TX) {
                    txBlockMinResponseTime = 0;
                    txBlockMaxResponseTime = 0;
                } else { // OTHER
                    otherMinResponseTime = 0;
                    otherMaxResponseTime = 0;
                }
                return;
            }

            long minLat = Long.MAX_VALUE;
            long maxLat = Long.MIN_VALUE;
            int minBlk = Integer.MAX_VALUE;
            int maxBlk = Integer.MIN_VALUE;

            for (PeerMetric m : list) {
                long l = m.getLatency();
                if (l < minLat)
                    minLat = l;
                if (l > maxLat)
                    maxLat = l;

                if (type == MetricType.RX) {
                    int b = m.getBlocksReceived();
                    if (b < minBlk)
                        minBlk = b;
                    if (b > maxBlk)
                        maxBlk = b;
                }
            }

            if (type == MetricType.RX) {
                blockMinResponseTime = minLat;
                blockMaxResponseTime = maxLat;
                blockMinBlocks = minBlk;
                blockMaxBlocks = maxBlk;
            } else if (type == MetricType.TX) {
                txBlockMinResponseTime = minLat;
                txBlockMaxResponseTime = maxLat;
            } else { // OTHER
                otherMinResponseTime = minLat;
                otherMaxResponseTime = maxLat;
            }
        }

        // --- Block Getters ---
        double getBlockAvgResponseTime() {
            if (blockHistory.isEmpty())
                return 0;
            return (double) blockTotalResponseTime / blockHistory.size();
        }

        long getBlockMinResponseTime() {
            return blockMinResponseTime;
        }

        long getBlockMaxResponseTime() {
            return blockMaxResponseTime;
        }

        long getBlockLastResponseTime() {
            if (blockHistory.isEmpty())
                return 0;
            return blockHistory.get(blockHistory.size() - 1).getLatency();
        }

        long getBlockTotalBlocks() {
            return blockTotalBlocks;
        }

        long getBlockCumulativeTotalBlocks() {
            return cumulativeBlockTotalBlocks;
        }

        double getBlockAvgBlocks() {
            if (blockHistory.isEmpty())
                return 0;
            return (double) blockTotalBlocks / blockHistory.size();
        }

        int getBlockMinBlocks() {
            return blockMinBlocks;
        }

        int getBlockMaxBlocks() {
            return blockMaxBlocks;
        }

        int getBlockLastBlocks() {
            if (blockHistory.isEmpty())
                return 0;
            return blockHistory.get(blockHistory.size() - 1).getBlocksReceived();
        }

        long getBlockRequestCount() {
            return blockRequestCount;
        }

        // --- TX Block Getters ---
        double getTxBlockAvgResponseTime() {
            if (txBlockHistory.isEmpty())
                return 0;
            return (double) txBlockTotalResponseTime / txBlockHistory.size();
        }

        long getTxBlockRequestCount() {
            return txBlockRequestCount;
        }

        long getTxBlockMinResponseTime() {
            return txBlockMinResponseTime;
        }

        long getTxBlockMaxResponseTime() {
            return txBlockMaxResponseTime;
        }

        long getTxBlockLastResponseTime() {
            if (txBlockHistory.isEmpty())
                return 0;
            return txBlockHistory.get(txBlockHistory.size() - 1).getLatency();
        }

        long getTxBlockTotalBlocks() {
            return txBlockTotalBlocks;
        }

        long getTxBlockCumulativeTotalBlocks() {
            return cumulativeTxBlockTotalBlocks;
        }

        double getTxBlockAvgBlocks() {
            if (txBlockHistory.isEmpty())
                return 0;
            return (double) txBlockTotalBlocks / txBlockHistory.size();
        }

        // --- Other Getters ---
        double getOtherAvgResponseTime() {
            if (otherHistory.isEmpty())
                return 0;
            return (double) otherTotalResponseTime / otherHistory.size();
        }

        long getOtherMinResponseTime() {
            return otherMinResponseTime;
        }

        long getOtherMaxResponseTime() {
            return otherMaxResponseTime;
        }

        long getOtherLastResponseTime() {
            if (otherHistory.isEmpty())
                return 0;
            return otherHistory.get(otherHistory.size() - 1).getLatency();
        }

        long getOtherRequestCount() {
            return otherRequestCount;
        }

        long getOtherTotalItems() {
            return otherTotalItems;
        }

        long getOtherCumulativeTotalItems() {
            return cumulativeOtherTotalItems;
        }

        double getOtherAvgItems() {
            if (otherHistory.isEmpty())
                return 0;
            return (double) otherTotalItems / otherHistory.size();
        }

        long getBlockLastTimestamp() {
            if (blockHistory.isEmpty())
                return 0;
            return blockHistory.get(blockHistory.size() - 1).getTimestamp();
        }

        long getTxBlockLastTimestamp() {
            if (txBlockHistory.isEmpty())
                return 0;
            return txBlockHistory.get(txBlockHistory.size() - 1).getTimestamp();
        }

        long getOtherLastTimestamp() {
            if (otherHistory.isEmpty())
                return 0;
            return otherHistory.get(otherHistory.size() - 1).getTimestamp();
        }
    }

    // --- DTOs for UI updates ---

    private static class MetricSnapshot {
        final double respAvg, respMin, respMax, respCur;
        final double countAvg, countMin, countMax, countCur;

        MetricSnapshot(MovingAverage respMA, MovingAverage countMA) { // countMA can be null
            this.respAvg = respMA.getAverage();
            this.respMin = respMA.getMin();
            this.respMax = respMA.getMax();
            this.respCur = respMA.getLast();
            if (countMA != null) {
                this.countAvg = countMA.getAverage();
                this.countMin = countMA.getMin();
                this.countMax = countMA.getMax();
                this.countCur = countMA.getLast();
            } else {
                this.countAvg = 0;
                this.countMin = 0;
                this.countMax = 0;
                this.countCur = 0;
            }
        }
    }

    private static class PeerStatsSnapshot {
        final String address;
        final long creationTime;
        final double avgResponseTime;
        final long minResponseTime;
        final long maxResponseTime;
        final long lastResponseTime;
        final long requestCount;
        final double avgBlocks;
        final long totalBlocks;
        final long lastTimestamp;
        final String version;
        final boolean isOutdated;
        final String announcedAddress;
        final String state;
        final String height;
        final boolean isBlacklisted;
        final boolean isYellowState;

        PeerStatsSnapshot(PeerHistory ph, MetricType type, String latestVersion) {
            this.address = ph.address;
            this.creationTime = ph.creationTime;
            if (type == MetricType.RX) {
                this.avgResponseTime = ph.getBlockAvgResponseTime();
                this.minResponseTime = ph.getBlockMinResponseTime();
                this.maxResponseTime = ph.getBlockMaxResponseTime();
                this.lastResponseTime = ph.getBlockLastResponseTime();
                this.requestCount = ph.getBlockRequestCount();
                this.avgBlocks = ph.getBlockAvgBlocks();
                this.totalBlocks = ph.getBlockCumulativeTotalBlocks();
                this.lastTimestamp = ph.getBlockLastTimestamp();
            } else if (type == MetricType.TX) {
                this.avgResponseTime = ph.getTxBlockAvgResponseTime();
                this.minResponseTime = ph.getTxBlockMinResponseTime();
                this.maxResponseTime = ph.getTxBlockMaxResponseTime();
                this.lastResponseTime = ph.getTxBlockLastResponseTime();
                this.requestCount = ph.getTxBlockRequestCount();
                this.avgBlocks = ph.getTxBlockAvgBlocks();
                this.totalBlocks = ph.getTxBlockCumulativeTotalBlocks();
                this.lastTimestamp = ph.getTxBlockLastTimestamp();
            } else {
                this.avgResponseTime = ph.getOtherAvgResponseTime();
                this.minResponseTime = ph.getOtherMinResponseTime();
                this.maxResponseTime = ph.getOtherMaxResponseTime();
                this.lastResponseTime = ph.getOtherLastResponseTime();
                this.requestCount = ph.getOtherRequestCount();
                this.avgBlocks = ph.getOtherAvgItems();
                this.totalBlocks = ph.getOtherCumulativeTotalItems();
                this.lastTimestamp = ph.getOtherLastTimestamp();
            }

            Peer peer = Peers.getPeer(ph.address);
            if (peer != null) {
                this.announcedAddress = peer.getAnnouncedAddress() != null ? peer.getAnnouncedAddress() : "-";
                this.state = String.valueOf(peer.getState());
                this.height = String.valueOf(peer.getHeight());
                this.version = peer.getVersion() != null ? peer.getVersion().toString() : "-";
                this.isOutdated = !this.version.isEmpty() && !"-".equals(this.version)
                        && !"unknown".equals(this.version)
                        && PeersDialog.compareVersions(this.version, latestVersion) < 0;
                this.isBlacklisted = peer.isBlacklisted();
                this.isYellowState = peer.getState() == Peer.State.NON_CONNECTED
                        || peer.getState() == Peer.State.DISCONNECTED;
            } else {
                this.announcedAddress = "-";
                this.state = "-";
                this.height = "-";
                this.version = "-";
                this.isOutdated = false;
                this.isBlacklisted = false;
                this.isYellowState = true; // Treat unknown/null peer as disconnected/yellow
            }
        }
    }

    private static class MetricsUpdateData {
        String updatedPeerAddress;
        MetricType updatedType;
        long typeCounter;
        MetricSnapshot rx;
        MetricSnapshot tx;
        MetricSnapshot other;
        MetricSnapshot rxAbsMin;
        MetricSnapshot rxAbsMax;
        MetricSnapshot txAbsMin;
        MetricSnapshot txAbsMax;
        MetricSnapshot otherAbsMin;
        MetricSnapshot otherAbsMax;
        List<PeerStatsSnapshot> rxPeers;
        List<PeerStatsSnapshot> txPeers;
        List<PeerStatsSnapshot> otherPeers;
    }

    private static class OverviewUpdateData {
        int all, connected, active, blacklisted;
        long updateCounter;
        long maxHeight;
        String latestVersion;
        List<Peer> connectedList;
        List<Peer> activeList;
        List<Peer> allList;
        List<Peer> blacklistedList;
        List<PeerStatsSnapshot> rxPeers;
        List<PeerStatsSnapshot> txPeers;
        List<PeerStatsSnapshot> otherPeers;
    }

    private class PeerChartToolTipGenerator implements XYToolTipGenerator {
        @Override
        public String generateToolTip(XYDataset dataset, int series, int item) {
            if (dataset == null)
                return null;
            Comparable key = dataset.getSeriesKey(series);
            if (key == null)
                return null;
            String name = key.toString();
            double x = dataset.getXValue(series, item);
            double y = dataset.getYValue(series, item);

            String valueStr;
            if (name.contains("Time")) {
                valueStr = String.format("%.0f ms", y);
            } else {
                valueStr = String.format("%.0f", y);
            }

            return String.format("<html><b>%s:</b> %s<br><b>Update:</b> %.0f</html>", name, valueStr, x);
        }
    }

    private static class AbsResponseTimeStats {
        long minResponseTime = Long.MAX_VALUE;
        String peerWithMinResponseTime = null;
        long maxResponseTime = 0;
        String peerWithMaxResponseTime = null;

        AbsResponseTimeStats() {
        }

        AbsResponseTimeStats(AbsResponseTimeStats other) {
            this.minResponseTime = other.minResponseTime;
            this.peerWithMinResponseTime = other.peerWithMinResponseTime;
            this.maxResponseTime = other.maxResponseTime;
            this.peerWithMaxResponseTime = other.peerWithMaxResponseTime;
        }
    }

    private class PeerMetricTableCellRenderer extends DefaultTableCellRenderer {
        private final Color metricColor;
        private final Color countColor;

        PeerMetricTableCellRenderer(Color metricColor, Color countColor) {
            this.metricColor = metricColor;
            this.countColor = countColor;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setToolTipText(null);

            if (value instanceof Double) {
                setText((value == null) ? "" : String.format("%.0f", (Double) value));
            }

            if (value instanceof Long) {
                long val = (Long) value;
                String columnName = table.getColumnName(column);
                boolean isCountColumn = COL_REQ.equals(columnName) ||
                        COL_TOTAL_BLOCKS.equals(columnName) ||
                        COL_TOTAL_ITEMS.equals(columnName);

                if (isCountColumn) {
                    if (val >= 10_000) {
                        setText(formatCount(val));
                    }
                    setToolTipText("<html><b>" + String.format("%,d", val) + "</b></html>");
                }
            }

            if (!isSelected) {
                c.setBackground(table.getBackground());

                int modelRow = table.convertRowIndexToModel(row);
                PeersTableModel model = (PeersTableModel) table.getModel();
                PeerStatsSnapshot snapshot = model.getSnapshotAt(modelRow);

                Color fg = metricColor;
                if (snapshot != null && snapshot.address.equals(model.lastUpdatedPeer)) {
                    fg = countColor;
                }

                if (snapshot != null && snapshot.isBlacklisted) {
                    fg = Color.RED;
                } else if (snapshot != null && snapshot.isYellowState) {
                    fg = Color.YELLOW;
                }

                String columnName = table.getColumnName(column);
                boolean isOutdated = snapshot != null && snapshot.isOutdated;

                if (isOutdated && COL_VERSION.equals(columnName)) {
                    c.setForeground(Color.YELLOW);
                } else {
                    c.setForeground(fg);
                }

                if (model.getRowCount() >= 2) {
                    if (columnName.equals(COL_AVG_RESP)) {
                        if (value instanceof Double) {
                            double val = (Double) value;
                            if (Math.abs(val - model.minAvgResponseTime) < 0.0001) {
                                c.setForeground(COLOR_MIN_RESPONSE_TIME);
                            } else if (Math.abs(val - model.maxAvgResponseTime) < 0.0001) {
                                c.setForeground(COLOR_MAX_RESPONSE_TIME);
                            }
                        }
                    }
                    if (columnName.equals(COL_MIN_RESP)) {
                        if (value instanceof Long) {
                            long val = (Long) value;
                            if (val == model.minMinResponseTime) {
                                c.setForeground(COLOR_MIN_RESPONSE_TIME);
                            } else if (val == model.maxMinResponseTime) {
                                c.setForeground(COLOR_MAX_RESPONSE_TIME);
                            }
                        }
                    }
                    if (columnName.equals(COL_MAX_RESP)) {
                        if (value instanceof Long) {
                            long val = (Long) value;
                            if (val == model.minMaxResponseTime) {
                                c.setForeground(COLOR_MIN_RESPONSE_TIME);
                            } else if (val == model.maxMaxResponseTime) {
                                c.setForeground(COLOR_MAX_RESPONSE_TIME);
                            }
                        }
                    }
                    if (columnName.equals(COL_LAST_RESP)) {
                        if (value instanceof Long) {
                            long val = (Long) value;
                            if (val == model.minLastResponseTime) {
                                c.setForeground(COLOR_MIN_RESPONSE_TIME);
                            } else if (val == model.maxLastResponseTime) {
                                c.setForeground(COLOR_MAX_RESPONSE_TIME);
                            }
                        }
                    }
                }
            }
            return c;
        }
    }

    private String getLegendHtml(MetricType type, Color metricColor, Color countColor) {
        String hexColor = toHex(metricColor);
        String countHexColor = toHex(countColor);
        String minHexColor = toHex(COLOR_MIN_RESPONSE_TIME);
        String maxHexColor = toHex(COLOR_MAX_RESPONSE_TIME);
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family: sans-serif; font-size: 10px;'>");
        sb.append("<h3>").append(type).append(" Metrics Legend</h3>");
        sb.append("<ul>");
        sb.append("<li><span style='color:").append(hexColor)
                .append("'>&#9632;</span> <b>Normal:</b> Peer is running the latest version.</li>");
        sb.append("<li><span style='color:").append(countHexColor)
                .append("'>&#9632;</span> <b>Latest:</b> The most recently updated peer.</li>");
        sb.append("<li><span style='color:").append(toHex(Color.YELLOW))
                .append("'>&#9632;</span> <b>Outdated:</b> Peer is running an older version than this node.</li>");
        sb.append("<li><span style='color:").append(minHexColor)
                .append("'>&#9632;</span> <b>Best:</b> Lowest response time in column.</li>");
        sb.append("<li><span style='color:").append(maxHexColor)
                .append("'>&#9632;</span> <b>Worst:</b> Highest response time in column.</li>");
        sb.append("</ul>");
        sb.append("<b>Version Notes:</b><br>");
        sb.append("<ul>");
        sb.append(
                "<li><b>v0.0.0:</b> The peer's version is unknown. This often occurs with newly discovered or unresponsive peers.</li>");
        sb.append(
                "<li><b>- / empty:</b> The peer did not provide a version. This may happen with very old clients.</li>");
        sb.append("</ul>");
        sb.append("<b>Columns:</b>");
        sb.append("<ul>");
        sb.append("<li><b>").append(COL_TIME).append(":</b> The timestamp of the most recent communication.</li>");
        sb.append("<li><b>").append(COL_ADDRESS).append(":</b> IP address or hostname of the peer.</li>");
        sb.append("<li><b>").append(COL_ANNOUNCED).append(":</b> The announced address of the peer (if any).</li>");
        sb.append("<li><b>").append(COL_STATE)
                .append(":</b> Connection state (CONNECTED, DISCONNECTED, NON_CONNECTED).</li>");
        sb.append("<li><b>").append(COL_VERSION).append(":</b> The software version the peer is running.</li>");
        sb.append("<li><b>").append(COL_HEIGHT).append(":</b> The blockchain height reported by the peer.</li>");
        sb.append("<li><b>").append(COL_AVG_RESP).append(":</b> Average response time (ms) for requests.</li>");
        sb.append("<li><b>").append(COL_MIN_RESP).append(":</b> Minimum response time (ms) observed.</li>");
        sb.append("<li><b>").append(COL_MAX_RESP).append(":</b> Maximum response time (ms) observed.</li>");
        sb.append("<li><b>").append(COL_LAST_RESP).append(":</b> Response time (ms) of the most recent request.</li>");

        if (type == MetricType.RX || type == MetricType.TX) {
            sb.append("<li><b>").append(COL_AVG_BLOCKS)
                    .append(":</b> Average number of blocks ").append(type == MetricType.RX ? "received" : "sent")
                    .append(" per request.</li>");
            sb.append("<li><b>").append(COL_TOTAL_BLOCKS)
                    .append(":</b> Total number of blocks ").append(type == MetricType.RX ? "received from" : "sent to")
                    .append(" this peer.</li>");
        }
        if (type == MetricType.OTHER) {
            sb.append("<li><b>").append(COL_AVG_ITEMS)
                    .append(":</b> Average number of items (e.g. block IDs) received per request.</li>");
            sb.append("<li><b>").append(COL_TOTAL_ITEMS)
                    .append(":</b> Total number of items received from this peer.</li>");
        }

        sb.append("<li><b>").append(COL_REQ).append(
                ":</b> Total number of requests sent to/received from this peer in the current session/history window.</li>");

        sb.append("</ul>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private void configureLineRenderer(XYLineAndShapeRenderer renderer, XYSeriesCollection dataset,
            Map<String, Color> colors) {
        renderer.setDrawSeriesLineAsPath(true);
        renderer.setUseFillPaint(true);
        renderer.setDefaultFillPaint(new Color(0, 0, 0, 0));
        renderer.setDrawOutlines(false);
        renderer.setDefaultShape(tooltipHitShape);
        renderer.setDefaultToolTipGenerator(new PeerChartToolTipGenerator());

        for (int i = 0; i < dataset.getSeriesCount(); i++) {
            renderer.setSeriesShape(i, tooltipHitShape);
            renderer.setSeriesStroke(i, CHART_STROKE);
            Comparable key = dataset.getSeriesKey(i);
            if (colors.containsKey(key.toString())) {
                renderer.setSeriesPaint(i, colors.get(key.toString()));
            }
        }
    }

    private static String formatCount(long count) {
        if (count > 100_000_000) {
            return "> 100M";
        }
        if (count >= 1_000_000) {
            return String.format("%.1fM", count / 1_000_000.0);
        }
        if (count >= 10_000) {
            return String.format("%.0fk", count / 1000.0);
        }
        return String.valueOf(count);
    }
}