package brs.gui;

import brs.BlockchainProcessor;
import brs.peer.PeerMetric;
import brs.Signum;
import brs.gui.util.MovingAverage;
import brs.gui.PeersDialog.PeerCategory;
import brs.peer.Peer;
import brs.peer.Peers;
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

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.Comparator;

public class PeerMetricsPanel extends JPanel {

    private static final int HISTORY_SIZE = 1000;
    private static final int PEER_HISTORY_SIZE = 100;
    private int movingAverageWindow = 100;

    // Constants for chart dataset and axis indices to improve readability
    private static final int DATASET_BARS = 0;
    private static final int DATASET_LINES = 1;
    private static final int AXIS_LINES = 0;
    private static final int AXIS_BARS = 1;

    private static final Color LIGHT_MAGENTA = new Color(230, 60, 230); // More vivid Magenta
    private static final Color LIGHT_RED = new Color(255, 100, 100); // Lighter Red for better contrast
    private static final Color COLOR_MIN_LATENCY = new Color(25, 120, 40); // Contrast Green
    private static final Color COLOR_MAX_LATENCY = new Color(160, 0, 0); // Darker Red

    private static final Color COLOR_RX_LATENCY = new Color(0, 100, 0); // Dark Green
    private static final Color COLOR_RX_COUNT = Color.GREEN;
    private static final Color COLOR_TX_LATENCY = Color.ORANGE;
    private static final Color COLOR_TX_COUNT = new Color(255, 70, 0); // Contrast Red-Orange
    private static final Color COLOR_OTHER_COUNT = new Color(255, 215, 0); // Gold

    private enum MetricType {
        RX, TX, OTHER
    }

    // RX Block Metrics
    private final MovingAverage rxLatencyMA = new MovingAverage(HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage rxBlockCountMA = new MovingAverage(HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage rxAbsMinMA = new MovingAverage(HISTORY_SIZE, 1);
    private final MovingAverage rxAbsMaxMA = new MovingAverage(HISTORY_SIZE, 1);

    // TX Block Metrics
    private final MovingAverage txLatencyMA = new MovingAverage(HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage txRequestCountMA = new MovingAverage(HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage txAbsMinMA = new MovingAverage(HISTORY_SIZE, 1);
    private final MovingAverage txAbsMaxMA = new MovingAverage(HISTORY_SIZE, 1);

    // Other Metrics
    private final MovingAverage otherLatencyMA = new MovingAverage(HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage otherRequestCountMA = new MovingAverage(HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage otherAbsMinMA = new MovingAverage(HISTORY_SIZE, 1);
    private final MovingAverage otherAbsMaxMA = new MovingAverage(HISTORY_SIZE, 1);

    private final Map<String, PeerHistory> peerHistories = new ConcurrentHashMap<>();

    private final Map<MetricType, ChartPanel> chartPanels = new HashMap<>();
    private final Map<MetricType, Integer> zoomRanges = new HashMap<>();

    private XYSeries rxLatencySeries;
    private XYSeries rxCountSeries;
    private XYSeries txLatencySeries;
    private XYSeries txCountSeries;
    private XYSeries otherLatencySeries;
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

    private JProgressBar rxLatencyBar, rxCountBar;
    private JProgressBar txLatencyBar, txCountBar;
    private JProgressBar otherLatencyBar, otherCountBar;
    private JProgressBar connectedPeersBar, allPeersBar, activePeersBar, blacklistedPeersBar;

    private JTable rxPeersTable;
    private PeersTableModel rxTableModel;
    private JTable txPeersTable;
    private PeersTableModel txTableModel;
    private JTable otherPeersTable;
    private PeersTableModel otherTableModel;
    private JTabbedPane overviewTablesPane;

    private final Dimension progressBarSize = new Dimension(350, 20);

    private final List<PeersDialog.PeerTabPanel> overviewPeerPanels = new ArrayList<>();
    private final List<PeersDialog.PeerCategory> overviewPeerCategories = new ArrayList<>();

    private long updateCounter = 0;

    private final ExecutorService chartUpdateExecutor = Executors.newSingleThreadExecutor();

    private final Map<MetricType, AbsLatencyStats> absLatencyStats = new ConcurrentHashMap<>();
    // private final Map<MetricType, AbsLatencyUI> absLatencyUIs = new HashMap<>();
    // // Removed, handled dynamically

    private final Shape tooltipHitShape = new java.awt.geom.Ellipse2D.Double(-10.0, -10.0, 20.0, 20.0);

    public PeerMetricsPanel() {
        super(new GridBagLayout());
        initUI();
    }

    public void init() {
        Signum.getBlockchainProcessor().addPeerMetricListener(this::onPeerMetric);
    }

    public void shutdown() {
        Signum.getBlockchainProcessor().removePeerMetricListener(this::onPeerMetric);
        chartUpdateExecutor.shutdown();
    }

    private void initUI() {
        setBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5));
        setLayout(new BorderLayout(0, 0));

        connectedPeersBar = createProgressBar();
        allPeersBar = createProgressBar();
        activePeersBar = createProgressBar();
        blacklistedPeersBar = createProgressBar();

        connectedSeries = new XYSeries("Connected", true, false);
        activeSeries = new XYSeries("Active", true, false);
        allSeries = new XYSeries("All", true, false);
        blacklistedSeries = new XYSeries("Blacklisted", true, false);

        // --- Tabs ---
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setTabPlacement(JTabbedPane.BOTTOM);

        // RX Tab
        rxLatencyBar = createProgressBar();
        rxCountBar = createProgressBar();
        rxLatencySeries = new XYSeries("Response Time (ms)", true, false);
        rxCountSeries = new XYSeries("Block Count", true, false);
        rxAbsMinSeries = new XYSeries("Abs Min Latency", true, false);
        rxAbsMaxSeries = new XYSeries("Abs Max Latency", true, false);

        String rxLatencyTooltip = "The moving average of the latency (response time) for Rx (Received) Block operations from peers.\n\n"
                +
                "Lower values indicate faster network communication.";
        String rxCountTooltip = "The moving average of the number of blocks received from peers.";

        tabbedPane.addTab("Block Rx", createMetricTab(
                "RX Block Metrics",
                rxLatencyBar, rxCountBar,
                "Block Rx Latency (MA)", "Block Rx Count (MA)",
                rxLatencyTooltip, rxCountTooltip,
                rxLatencySeries, rxCountSeries,
                rxAbsMinSeries, rxAbsMaxSeries,
                COLOR_RX_LATENCY, COLOR_RX_COUNT,
                MetricType.RX));

        // TX Tab
        txLatencyBar = createProgressBar();
        txCountBar = createProgressBar();
        txLatencySeries = new XYSeries("Response Time (ms)", true, false);
        txCountSeries = new XYSeries("Request Count", true, false);
        txAbsMinSeries = new XYSeries("Abs Min Latency", true, false);
        txAbsMaxSeries = new XYSeries("Abs Max Latency", true, false);

        String txLatencyTooltip = "The moving average of the latency (response time) for Tx (Transmitted) Block operations to peers.\n\n"
                +
                "Lower values indicate faster network communication.";
        String txCountTooltip = "The moving average of the number of block requests sent to peers.";

        tabbedPane.addTab("Block Tx", createMetricTab(
                "TX Block Metrics",
                txLatencyBar, txCountBar,
                "Block Tx Latency (MA)", "Block Tx Count (MA)",
                txLatencyTooltip, txCountTooltip,
                txLatencySeries, txCountSeries,
                txAbsMinSeries, txAbsMaxSeries,
                COLOR_TX_LATENCY, COLOR_TX_COUNT,
                MetricType.TX));

        // Other Tab
        otherLatencyBar = createProgressBar();
        otherCountBar = createProgressBar();
        otherLatencySeries = new XYSeries("Response Time (ms)", true, false);
        otherCountSeries = new XYSeries("Request Count", true, false);
        otherAbsMinSeries = new XYSeries("Abs Min Latency", true, false);
        otherAbsMaxSeries = new XYSeries("Abs Max Latency", true, false);

        String otherLatencyTooltip = "The moving average of the latency for other types of peer communication.";
        String otherCountTooltip = "The moving average of the count of other peer requests.";

        tabbedPane.addTab("Other Communication", createMetricTab(
                "Other Communication Metrics",
                otherLatencyBar, otherCountBar,
                "Other Comm. Latency (MA)", "Other Comm. Count (MA)",
                otherLatencyTooltip, otherCountTooltip,
                otherLatencySeries, otherCountSeries,
                otherAbsMinSeries, otherAbsMaxSeries,
                LIGHT_MAGENTA, COLOR_OTHER_COUNT,
                MetricType.OTHER));

        // --- Peer Counts (Overview Tab) ---
        JPanel overviewPanel = new JPanel(new GridBagLayout());

        // Left Side: Metrics + Chart
        JPanel leftOverview = new JPanel(new GridBagLayout());

        JPanel metricsOverview = new JPanel(new GridBagLayout());
        addMetricRow(metricsOverview, 0, createLabel("Connected", Color.GREEN, "Number of currently connected peers."),
                connectedPeersBar);
        addMetricRow(metricsOverview, 1, createLabel("Active", Color.CYAN, "Number of active peers (communicating)."),
                activePeersBar);
        addMetricRow(metricsOverview, 2, createLabel("All Known", Color.WHITE, "Total number of known peers."),
                allPeersBar);
        addMetricRow(metricsOverview, 3, createLabel("Blacklisted", LIGHT_RED, "Number of blacklisted peers."),
                blacklistedPeersBar);
        addComponent(leftOverview, metricsOverview, 0, 0, 1, 0.0, 0.0, GridBagConstraints.NORTHWEST,
                GridBagConstraints.NONE, new Insets(0, 0, 0, 0));

        ChartPanel overviewChartPanel = createOverviewChartPanel();
        addComponent(leftOverview, overviewChartPanel, 1, 0, 1, 0.0, 0.0, GridBagConstraints.NORTHWEST,
                GridBagConstraints.NONE, new Insets(0, 0, 0, 0));

        addComponent(overviewPanel, leftOverview, 0, 0, 1, 0.0, 1.0, GridBagConstraints.NORTHWEST,
                GridBagConstraints.NONE, new Insets(0, 0, 0, 0));

        // Right Side: Tables
        JPanel tablesWrapper = new JPanel(new BorderLayout());
        tablesWrapper.setBorder(BorderFactory.createLineBorder(Color.GRAY));

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(BorderFactory.createEmptyBorder(2, 5, 0, 0));

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

        overviewTablesPane = new JTabbedPane();
        overviewTablesPane.setPreferredSize(new Dimension(300, 0));
        overviewTablesPane.setMinimumSize(new Dimension(300, 0));
        for (PeersDialog.PeerCategory category : PeersDialog.PeerCategory.values()) {
            PeersDialog.PeerTabPanel panel = new PeersDialog.PeerTabPanel(category);
            panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            overviewTablesPane.addTab(category.getTitle() + " (0)", panel);
            overviewPeerPanels.add(panel);
            overviewPeerCategories.add(category);
        }
        tablesWrapper.add(overviewTablesPane, BorderLayout.CENTER);

        addComponent(overviewPanel, tablesWrapper, 1, 0, 1, 1.0, 1.0, GridBagConstraints.CENTER,
                GridBagConstraints.BOTH,
                new Insets(0, 5, 0, 0));

        tabbedPane.addTab("Overview", overviewPanel);

        add(tabbedPane, BorderLayout.CENTER);
    }

    private JPanel createMetricTab(String sectionTitle,
            JProgressBar latencyBar, JProgressBar countBar,
            String latencyLabel, String countLabel,
            String latencyTooltip, String countTooltip,
            XYSeries latencySeries, XYSeries countSeries,
            XYSeries absMinSeries, XYSeries absMaxSeries,
            Color lineColor, Color barColor,
            MetricType type) {

        JPanel panel = new JPanel(new GridBagLayout());

        // Left Side: Metrics + Chart
        JPanel leftPanel = new JPanel(new GridBagLayout());

        JPanel metricsPanel = new JPanel(new GridBagLayout());
        int yPos = 0;

        JLabel lLabel = createLabel(latencyLabel, lineColor, latencyTooltip);
        JLabel cLabel = createLabel(countLabel, barColor, countTooltip);

        JLabel tableTitleLabel = new JLabel(sectionTitle);
        tableTitleLabel.setHorizontalAlignment(SwingConstants.LEFT);
        tableTitleLabel.setFont(UIManager.getFont("TitledBorder.font"));
        if (tableTitleLabel.getFont() == null) {
            tableTitleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        }
        tableTitleLabel.setForeground(UIManager.getColor("TitledBorder.titleColor"));
        tableTitleLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        addMetricRow(metricsPanel, yPos++, lLabel, latencyBar);
        addMetricRow(metricsPanel, yPos++, cLabel, countBar);

        String absMaxTooltip = "The absolute maximum latency observed across all peers for " + type + " operations.\n\n"
                +
                "Displays: Current (C) | min - max over history.";
        JLabel absMaxLabel = createLabel("Abs Max Latency", COLOR_MAX_LATENCY, absMaxTooltip);
        JProgressBar absMaxLatencyBar = createProgressBar();
        addMetricRow(metricsPanel, yPos++, absMaxLabel, absMaxLatencyBar);

        String absMinTooltip = "The absolute minimum latency observed across all peers for " + type + " operations.\n\n"
                +
                "Displays: Current (C) | min - max over history.";
        JLabel absMinLabel = createLabel("Abs Min Latency", COLOR_MIN_LATENCY, absMinTooltip);
        JProgressBar absMinLatencyBar = createProgressBar();
        addMetricRow(metricsPanel, yPos++, absMinLabel, absMinLatencyBar);

        // Store UI references in client properties or map for update
        absMinLatencyBar.putClientProperty("metricType", type);
        absMinLatencyBar.putClientProperty("isMin", true);
        absMaxLatencyBar.putClientProperty("metricType", type);
        absMaxLatencyBar.putClientProperty("isMin", false);

        MovingAverage[] mas;
        if (type == MetricType.RX) {
            mas = new MovingAverage[] { rxLatencyMA, rxBlockCountMA };
        } else if (type == MetricType.TX) {
            mas = new MovingAverage[] { txLatencyMA, txRequestCountMA };
        } else {
            mas = new MovingAverage[] { otherLatencyMA, otherRequestCountMA };
        }

        JPanel sliderPanel = createControlsPanel(type, mas);
        addComponent(metricsPanel, sliderPanel, 0, yPos, 2, 0.0, 0.0, GridBagConstraints.CENTER,
                GridBagConstraints.HORIZONTAL, new Insets(5, 0, 0, 0));

        addComponent(leftPanel, metricsPanel, 0, 0, 1, 0.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE,
                new Insets(0, 0, 0, 0));

        ChartPanel chartPanel = createChartPanel(latencySeries, countSeries, absMinSeries, absMaxSeries, lineColor,
                barColor);
        chartPanels.put(type, chartPanel);
        zoomRanges.put(type, HISTORY_SIZE);
        addToggleListener(lLabel, chartPanel, DATASET_LINES, 0); // Dataset 1 (Line), Series 0
        addToggleListener(cLabel, chartPanel, DATASET_BARS, 0); // Dataset 0 (Bar), Series 0
        addToggleListener(absMinLabel, chartPanel, DATASET_LINES, 1); // Dataset 1 (Line), Series 1
        addToggleListener(absMaxLabel, chartPanel, DATASET_LINES, 2); // Dataset 1 (Line), Series 2

        addComponent(leftPanel, chartPanel, 1, 0, 1, 0.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE,
                new Insets(0, 0, 0, 0));

        addComponent(panel, leftPanel, 0, 0, 1, 0.0, 1.0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE,
                new Insets(0, 0, 0, 0));

        // Table Section (Center)
        PeersTableModel model = new PeersTableModel(type);
        JTable table = new JTable(model);
        TableRowSorter<PeersTableModel> sorter = setupTable(table, model);

        JPanel tablePanel = new JPanel(new BorderLayout(0, 0));
        tablePanel.setBorder(BorderFactory.createLineBorder(Color.GRAY));

        JPanel headerPanel = new JPanel(new BorderLayout(0, 0));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        headerPanel.add(tableTitleLabel, BorderLayout.NORTH);

        JPanel filterPanel = new JPanel(new BorderLayout());
        filterPanel.add(new JLabel("Filter: "), BorderLayout.WEST);
        JTextField filterTextField = new JTextField();
        filterPanel.add(filterTextField, BorderLayout.CENTER);

        headerPanel.add(filterPanel, BorderLayout.SOUTH);
        tablePanel.add(headerPanel, BorderLayout.NORTH);

        JScrollPane scrollPane = new JScrollPane(table);
        tablePanel.add(scrollPane, BorderLayout.CENTER);
        tablePanel.setPreferredSize(new Dimension(300, 0));
        tablePanel.setMinimumSize(new Dimension(300, 0));

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
            this.rxLatencyBar.putClientProperty("absMinBar", absMinLatencyBar);
            this.rxLatencyBar.putClientProperty("absMaxBar", absMaxLatencyBar);
        } else if (type == MetricType.TX) {
            this.txLatencyBar.putClientProperty("absMinBar", absMinLatencyBar);
            this.txLatencyBar.putClientProperty("absMaxBar", absMaxLatencyBar);
        } else {
            this.otherLatencyBar.putClientProperty("absMinBar", absMinLatencyBar);
            this.otherLatencyBar.putClientProperty("absMaxBar", absMaxLatencyBar);
        }

        // Setup renderer and click listener
        PeerMetricTableCellRenderer renderer = new PeerMetricTableCellRenderer(lineColor);
        table.setDefaultRenderer(Object.class, renderer);
        table.setDefaultRenderer(Double.class, renderer);
        table.setDefaultRenderer(Long.class, renderer);
        table.setDefaultRenderer(Integer.class, renderer);

        tableTitleLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    String legend = getLegendHtml(type, lineColor);
                    PeerTableDialog.showDialog(SwingUtilities.getWindowAncestor(PeerMetricsPanel.this), sectionTitle,
                            model, renderer, legend);
                }
            }
        });

        addComponent(panel, tablePanel, 1, 0, 1, 1.0, 1.0, GridBagConstraints.CENTER, GridBagConstraints.BOTH,
                new Insets(0, 5, 0, 0));

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
                for (MovingAverage ma : mas) {
                    ma.setWindowSize(newValue);
                }
            });
        });
        maWindowPanel.add(movingAverageSlider);

        maWindowPanel.add(Box.createHorizontalStrut(5));

        // Zoom controls
        JPanel zoomPanel = new JPanel(new GridBagLayout());
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

        GridBagConstraints gbcZoom = new GridBagConstraints();
        gbcZoom.gridx = 0;
        gbcZoom.gridy = 0;
        gbcZoom.insets = new Insets(0, 0, 0, 5);
        zoomPanel.add(zoomOutLabel, gbcZoom);
        gbcZoom.gridx = 1;
        gbcZoom.insets = new Insets(0, 0, 0, 0);
        zoomPanel.add(zoomInLabel, gbcZoom);
        maWindowPanel.add(zoomPanel);

        return maWindowPanel;
    }

    private void zoomIn(MetricType type) {
        int currentZoom = zoomRanges.getOrDefault(type, HISTORY_SIZE);
        XYSeries series = (type == MetricType.RX) ? rxLatencySeries
                : (type == MetricType.TX ? txLatencySeries : otherLatencySeries);
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

    private JProgressBar createProgressBar() {
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setPreferredSize(new Dimension(300, 20));
        bar.setMinimumSize(new Dimension(150, 20));
        bar.setStringPainted(true);
        bar.setString("0");
        return bar;
    }

    private void addMetricRow(JPanel panel, int row, JLabel label, JProgressBar bar) {
        JPanel container = new JPanel(new BorderLayout(0, 3));
        container.setOpaque(false);
        container.add(label, BorderLayout.NORTH);
        container.add(bar, BorderLayout.CENTER);

        addComponent(panel, container, 0, row, 1, 1.0, 0.0, GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL,
                new Insets(2, 5, 5, 5));
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

        XYLineAndShapeRenderer lineRenderer = new XYLineAndShapeRenderer(true, true);
        lineRenderer.setUseFillPaint(true);
        lineRenderer.setDefaultFillPaint(new Color(0, 0, 0, 0));
        lineRenderer.setDrawOutlines(false);
        lineRenderer.setDefaultShape(tooltipHitShape);

        // Avg Latency
        lineRenderer.setSeriesShape(0, tooltipHitShape);
        lineRenderer.setSeriesPaint(0, lineColor);
        // Abs Min
        lineRenderer.setSeriesShape(1, tooltipHitShape);
        lineRenderer.setSeriesPaint(1, COLOR_MIN_LATENCY);
        // Abs Max
        lineRenderer.setSeriesShape(2, tooltipHitShape);
        lineRenderer.setSeriesPaint(2, COLOR_MAX_LATENCY);

        lineRenderer.setSeriesStroke(0, new BasicStroke(1.2f));
        lineRenderer.setDefaultToolTipGenerator(new PeerChartToolTipGenerator());
        plot.setRenderer(DATASET_LINES, lineRenderer);

        plot.setDatasetRenderingOrder(DatasetRenderingOrder.FORWARD);
        plot.setInsets(new RectangleInsets(0, 0, 0, 0));
        plot.setAxisOffset(new RectangleInsets(0, 0, 0, 0));

        chart.removeLegend();
        chart.setBorderVisible(false);

        ChartPanel chartPanel = new ChartPanel(chart);
        Dimension d = new Dimension(320, 240);
        chartPanel.setPreferredSize(d);
        chartPanel.setMinimumSize(d);
        chartPanel.setMaximumSize(d);
        chartPanel.setDisplayToolTips(true);
        ToolTipManager.sharedInstance().registerComponent(chartPanel);
        return chartPanel;
    }

    private ChartPanel createOverviewChartPanel() {
        XYSeriesCollection dataset = new XYSeriesCollection();
        dataset.addSeries(connectedSeries);
        dataset.addSeries(activeSeries);
        dataset.addSeries(allSeries);
        dataset.addSeries(blacklistedSeries);

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

        plot.setInsets(new RectangleInsets(0, 0, 0, 0));
        plot.setAxisOffset(new RectangleInsets(0, 0, 0, 0));

        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, true);
        renderer.setUseFillPaint(true);
        renderer.setDefaultFillPaint(new Color(0, 0, 0, 0));
        renderer.setDrawOutlines(false);
        renderer.setDefaultShape(tooltipHitShape);
        for (int i = 0; i < dataset.getSeriesCount(); i++) {
            renderer.setSeriesShape(i, tooltipHitShape);
            renderer.setSeriesStroke(i, new BasicStroke(1.2f));
        }
        renderer.setSeriesPaint(0, Color.GREEN); // Connected
        renderer.setSeriesPaint(1, Color.CYAN); // Active
        renderer.setSeriesPaint(2, Color.WHITE); // All
        renderer.setSeriesPaint(3, LIGHT_RED); // Blacklisted

        renderer.setDefaultToolTipGenerator(new PeerChartToolTipGenerator());
        plot.setRenderer(renderer);
        plot.setDataset(dataset);
        chart.removeLegend();
        chart.setBorderVisible(false);

        ChartPanel chartPanel = new ChartPanel(chart);
        Dimension d = new Dimension(320, 240);
        chartPanel.setPreferredSize(d);
        chartPanel.setMinimumSize(d);
        chartPanel.setMaximumSize(d);
        chartPanel.setDisplayToolTips(true);
        ToolTipManager.sharedInstance().registerComponent(chartPanel);
        return chartPanel;
    }

    private void onPeerMetric(PeerMetric metric) {
        chartUpdateExecutor.submit(() -> {
            updateCounter++;

            if (metric.getType() == PeerMetric.Type.BLOCK_RX) {
                rxLatencyMA.add((double) metric.getLatency());
                if (metric.getBlocksReceived() > 0) {
                    rxBlockCountMA.add((double) metric.getBlocksReceived());
                } else {
                    rxBlockCountMA.add(0.0);
                }
            } else if (metric.getType() == PeerMetric.Type.BLOCK_TX) {
                txLatencyMA.add((double) metric.getLatency());
                // For TX, we count requests, so add 1
                txRequestCountMA.add(1.0);
            } else { // OTHER
                otherLatencyMA.add((double) metric.getLatency());
                otherRequestCountMA.add(1.0);
            }

            // Update Peer History first
            PeerHistory peerHistory = peerHistories.computeIfAbsent(metric.getPeerAddress(),
                    k -> new PeerHistory(metric.getPeerAddress()));
            peerHistory.add(metric);

            MetricType type;
            if (metric.getType() == PeerMetric.Type.BLOCK_RX) {
                type = MetricType.RX;
            } else if (metric.getType() == PeerMetric.Type.BLOCK_TX) {
                type = MetricType.TX;
            } else {
                type = MetricType.OTHER;
            }

            AbsLatencyStats stats = absLatencyStats.computeIfAbsent(type, k -> new AbsLatencyStats());
            long peerMinLatency = (type == MetricType.RX) ? peerHistory.blockMinLatency
                    : (type == MetricType.TX ? peerHistory.txBlockMinLatency : peerHistory.otherMinLatency);
            long peerMaxLatency = (type == MetricType.RX) ? peerHistory.blockMaxLatency
                    : (type == MetricType.TX ? peerHistory.txBlockMaxLatency : peerHistory.otherMaxLatency);

            if (peerMinLatency > 0 && peerMinLatency < stats.minLatency) {
                stats.minLatency = peerMinLatency;
                stats.peerWithMinLatency = peerHistory.address;
            } else if (peerHistory.address.equals(stats.peerWithMinLatency) && peerMinLatency > stats.minLatency) {
                recalculateAbsMinLatency(type, stats);
            }

            if (peerMaxLatency > stats.maxLatency) {
                stats.maxLatency = peerMaxLatency;
                stats.peerWithMaxLatency = peerHistory.address;
            } else if (peerHistory.address.equals(stats.peerWithMaxLatency) && peerMaxLatency < stats.maxLatency) {
                recalculateAbsMaxLatency(type, stats);
            }

            // Update Abs MAs
            if (type == MetricType.RX) {
                rxAbsMinMA.add(stats.minLatency == Long.MAX_VALUE ? 0 : (double) stats.minLatency);
                rxAbsMaxMA.add((double) stats.maxLatency);
            } else if (type == MetricType.TX) {
                txAbsMinMA.add(stats.minLatency == Long.MAX_VALUE ? 0 : (double) stats.minLatency);
                txAbsMaxMA.add((double) stats.maxLatency);
            } else {
                otherAbsMinMA.add(stats.minLatency == Long.MAX_VALUE ? 0 : (double) stats.minLatency);
                otherAbsMaxMA.add((double) stats.maxLatency);
            }

            // Prepare DTOs for UI update
            MetricsUpdateData data = new MetricsUpdateData();
            data.updateCounter = updateCounter;
            data.rx = new MetricSnapshot(rxLatencyMA, rxBlockCountMA);
            data.tx = new MetricSnapshot(txLatencyMA, txRequestCountMA);
            data.other = new MetricSnapshot(otherLatencyMA, otherRequestCountMA);

            data.rxAbsMin = new MetricSnapshot(rxAbsMinMA, null);
            data.rxAbsMax = new MetricSnapshot(rxAbsMaxMA, null);
            data.txAbsMin = new MetricSnapshot(txAbsMinMA, null);
            data.txAbsMax = new MetricSnapshot(txAbsMaxMA, null);
            data.otherAbsMin = new MetricSnapshot(otherAbsMinMA, null);
            data.otherAbsMax = new MetricSnapshot(otherAbsMaxMA, null);

            data.rxPeers = getPeerSnapshots(MetricType.RX);
            data.txPeers = getPeerSnapshots(MetricType.TX);
            data.otherPeers = getPeerSnapshots(MetricType.OTHER);

            data.overviewData = calculateOverviewUpdate();

            // Update UI components on EDT
            SwingUtilities.invokeLater(() -> {
                updateGlobalUI(data);
                updateTable(data);
                if (data.overviewData != null) {
                    applyOverviewUpdate(data.overviewData);
                }
            });
        });
    }

    private List<PeerStatsSnapshot> getPeerSnapshots(MetricType type) {
        List<PeerStatsSnapshot> snapshots = new ArrayList<>();
        for (PeerHistory ph : peerHistories.values()) {
            if (type == MetricType.RX && ph.blockRequestCount > 0)
                snapshots.add(new PeerStatsSnapshot(ph, type));
            else if (type == MetricType.TX && ph.txBlockRequestCount > 0)
                snapshots.add(new PeerStatsSnapshot(ph, type));
            else if (type == MetricType.OTHER && ph.otherRequestCount > 0)
                snapshots.add(new PeerStatsSnapshot(ph, type));
        }
        snapshots.sort(Comparator.comparingLong(p -> p.creationTime));
        return snapshots;
    }

    private void updateGlobalUI(MetricsUpdateData data) {
        updateMetricSet(data.rx, rxLatencyBar, rxCountBar, rxLatencySeries, rxCountSeries, "ms",
                "");
        updateChartRange(MetricType.RX);
        updateMetricSet(data.tx, txLatencyBar, txCountBar, txLatencySeries, txCountSeries, "ms",
                "");
        updateChartRange(MetricType.TX);
        updateMetricSet(data.other, otherLatencyBar, otherCountBar, otherLatencySeries,
                otherCountSeries, "ms", "");
        updateChartRange(MetricType.OTHER);

        updateAbsMetric(rxLatencyBar, data.rxAbsMin, data.rxAbsMax, rxAbsMinSeries, rxAbsMaxSeries);
        updateAbsMetric(txLatencyBar, data.txAbsMin, data.txAbsMax, txAbsMinSeries, txAbsMaxSeries);
        updateAbsMetric(otherLatencyBar, data.otherAbsMin, data.otherAbsMax, otherAbsMinSeries, otherAbsMaxSeries);
    }

    private void updateAbsMetric(JProgressBar parentBar, MetricSnapshot minSnap, MetricSnapshot maxSnap,
            XYSeries minSeries, XYSeries maxSeries) {
        JProgressBar minBar = (JProgressBar) parentBar.getClientProperty("absMinBar");
        JProgressBar maxBar = (JProgressBar) parentBar.getClientProperty("absMaxBar");

        if (minBar != null) {
            updateMetricSet(minSnap, minBar, null, minSeries, null, "ms", "");
            minBar.setString(
                    String.format("C: %.0f | min: %.0f - max: %.0f", minSnap.latCur, minSnap.latMin, minSnap.latMax));
        }
        if (maxBar != null) {
            updateMetricSet(maxSnap, maxBar, null, maxSeries, null, "ms", "");
            maxBar.setString(
                    String.format("C: %.0f | min: %.0f - max: %.0f", maxSnap.latCur, maxSnap.latMin, maxSnap.latMax));
        }
    }

    private void updateMetricSet(MetricSnapshot snapshot,
            JProgressBar latBar, JProgressBar countBar,
            XYSeries latSeries, XYSeries countSeries,
            String latUnit, String countUnit) {

        latBar.setMaximum((int) (snapshot.latMax > 0 ? snapshot.latMax : 100));
        latBar.setValue((int) snapshot.latCur);
        latBar.setString(String.format("C: %.0f | MA: %.0f - min: %.0f - max: %.0f %s", snapshot.latCur,
                snapshot.latAvg, snapshot.latMin, snapshot.latMax, latUnit));

        if (countBar != null) {
            countBar.setMaximum((int) (snapshot.countMax > 0 ? snapshot.countMax : 100));
            countBar.setValue((int) snapshot.countCur);
            countBar.setString(String.format("C: %.0f | MA: %.2f - min: %.2f - max: %.2f %s", snapshot.countCur,
                    snapshot.countAvg, snapshot.countMin, snapshot.countMax, countUnit));
        }

        latSeries.addOrUpdate((double) updateCounter, snapshot.latAvg);
        if (countSeries != null) {
            countSeries.addOrUpdate((double) updateCounter, snapshot.countAvg);
        }

        if (latSeries.getItemCount() > HISTORY_SIZE) {
            latSeries.remove(0);
            if (countSeries != null)
                countSeries.remove(0);
        }
    }

    private void updateChartRange(MetricType type) {
        ChartPanel panel = chartPanels.get(type);
        if (panel == null)
            return;

        XYSeries series = (type == MetricType.RX) ? rxLatencySeries
                : (type == MetricType.TX ? txLatencySeries : otherLatencySeries);
        if (series.getItemCount() == 0)
            return;

        double lastX = series.getX(series.getItemCount() - 1).doubleValue();
        int range = Math.min(Math.max(series.getItemCount(), 10), zoomRanges.getOrDefault(type, HISTORY_SIZE));

        panel.getChart().getXYPlot().getDomainAxis().setRange(lastX - range + 0.5, lastX + 0.5);
    }

    private OverviewUpdateData calculateOverviewUpdate() {
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
        data.updateCounter = updateCounter;

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

        return data;
    }

    private void applyOverviewUpdate(OverviewUpdateData data) {
        updateCountBar(allPeersBar, data.all, data.all);
        updateCountBar(connectedPeersBar, data.connected, data.all);
        updateCountBar(activePeersBar, data.active, data.all);
        updateCountBar(blacklistedPeersBar, data.blacklisted, data.all);

        connectedSeries.addOrUpdate((double) data.updateCounter, data.connected);
        activeSeries.addOrUpdate((double) data.updateCounter, data.active);
        allSeries.addOrUpdate((double) data.updateCounter, data.all);
        blacklistedSeries.addOrUpdate((double) data.updateCounter, data.blacklisted);

        if (connectedSeries.getItemCount() > HISTORY_SIZE) {
            connectedSeries.remove(0);
            activeSeries.remove(0);
            allSeries.remove(0);
            blacklistedSeries.remove(0);
        }

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
                overviewPeerPanels.get(i).update(list, data.maxHeight, data.latestVersion);
                if (overviewTablesPane != null) {
                    overviewTablesPane.setTitleAt(i, category.getTitle() + " (" + count + ")");
                }
            }
        }
    }

    private void updateCountBar(JProgressBar bar, int value, int max) {
        bar.setMaximum(max > 0 ? max : 100);
        bar.setValue(value);
        bar.setString(String.valueOf(value));
    }

    private void updateTable(MetricsUpdateData data) {
        rxTableModel.setData(data.rxPeers);
        txTableModel.setData(data.txPeers);
        otherTableModel.setData(data.otherPeers);
    }

    private void addComponent(JPanel panel, Component comp, int x, int y, int gridwidth, double weightx, double weighty,
            int anchor, int fill, Insets insets) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = x;
        gbc.gridy = y;
        gbc.gridwidth = gridwidth;
        gbc.weightx = weightx;
        gbc.weighty = weighty;
        gbc.anchor = anchor;
        gbc.fill = fill;
        gbc.insets = insets;
        panel.add(comp, gbc);
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

    private void addToggleListener(JLabel label, ChartPanel chartPanel, int datasetIndex, int seriesIndex) {
        label.putClientProperty("visible", true);
        final Font originalFont = label.getFont();
        final java.util.Map<java.awt.font.TextAttribute, Object> attributes = new java.util.HashMap<>(
                originalFont.getAttributes());
        attributes.put(java.awt.font.TextAttribute.STRIKETHROUGH, java.awt.font.TextAttribute.STRIKETHROUGH_ON);
        final Font strikethroughFont = originalFont.deriveFont(attributes);

        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent evt) {
                if (SwingUtilities.isLeftMouseButton(evt)) {
                    boolean isVisible = !((boolean) label.getClientProperty("visible"));
                    label.putClientProperty("visible", isVisible);
                    label.setFont(isVisible ? originalFont : strikethroughFont);

                    XYPlot plot = chartPanel.getChart().getXYPlot();
                    if (plot.getRenderer(datasetIndex) != null) {
                        plot.getRenderer(datasetIndex).setSeriesVisible(seriesIndex, isVisible);
                    }
                }
            }
        });
    }

    private void recalculateAbsMinLatency(MetricType type, AbsLatencyStats stats) {
        long newMin = Long.MAX_VALUE;
        String newPeer = null;
        for (PeerHistory ph : peerHistories.values()) {
            long val = (type == MetricType.RX) ? ph.blockMinLatency
                    : (type == MetricType.TX ? ph.txBlockMinLatency : ph.otherMinLatency);
            if (val > 0 && val < newMin) {
                newMin = val;
                newPeer = ph.address;
            }
        }
        stats.minLatency = newMin;
        stats.peerWithMinLatency = newPeer;
    }

    private void recalculateAbsMaxLatency(MetricType type, AbsLatencyStats stats) {
        long newMax = 0;
        String newPeer = null;
        for (PeerHistory ph : peerHistories.values()) {
            long val = (type == MetricType.RX) ? ph.blockMaxLatency
                    : (type == MetricType.TX ? ph.txBlockMaxLatency : ph.otherMaxLatency);
            if (val > newMax) {
                newMax = val;
                newPeer = ph.address;
            }
        }
        stats.maxLatency = newMax;
        stats.peerWithMaxLatency = newPeer;
    }

    private class PeersTableModel extends AbstractTableModel {
        private final MetricType type;
        private final String[] columns;
        private List<PeerStatsSnapshot> peerData = new ArrayList<>();

        PeersTableModel(MetricType type) {
            this.type = type;
            if (type == MetricType.RX) {
                columns = new String[] {
                        "Time", "Peer Address", "Announced", "State", "Version", "Height",
                        "Avg Lat (ms)", "Min Lat (ms)", "Max Lat (ms)", "Last Lat (ms)",
                        "Requests",
                        "Avg Blocks", "Total Blocks"
                };
            } else if (type == MetricType.TX) {
                columns = new String[] {
                        "Time", "Peer Address", "Announced", "State", "Version", "Height",
                        "Avg Lat (ms)", "Min Lat (ms)", "Max Lat (ms)", "Last Lat (ms)",
                        "Requests"
                };
            } else { // OTHER
                columns = new String[] {
                        "Time", "Peer Address", "Announced", "State", "Version", "Height",
                        "Avg Latency (ms)", "Min Latency (ms)", "Max Latency (ms)", "Last Latency (ms)",
                        "Requests"
                };
            }
        }

        void setData(List<PeerStatsSnapshot> newData) {
            this.peerData = newData;
            fireTableDataChanged();
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

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (rowIndex >= peerData.size())
                return null;
            PeerStatsSnapshot history = peerData.get(rowIndex);
            Peer peer = Peers.getPeer(history.address);

            String columnName = getColumnName(columnIndex);
            switch (columnName) {
                case "Time":
                    if (history.lastTimestamp > 0) {
                        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                .format(new java.util.Date(history.lastTimestamp));
                    }
                    return "-";
                case "Peer Address":
                    return history.address;
                case "Announced":
                    return peer != null ? peer.getAnnouncedAddress() : "-";
                case "State":
                    return peer != null ? peer.getState() : "-";
                case "Version":
                    return peer != null && peer.getVersion() != null ? peer.getVersion().toString() : "-";
                case "Height":
                    return peer != null ? peer.getHeight() : "-";
                case "Avg Lat (ms)":
                case "Avg Latency (ms)":
                    return history.avgLatency;
                case "Min Lat (ms)":
                case "Min Latency (ms)":
                    return history.minLatency;
                case "Max Lat (ms)":
                case "Max Latency (ms)":
                    return history.maxLatency;
                case "Last Lat (ms)":
                case "Last Latency (ms)":
                    return history.lastLatency;
                case "Requests":
                    return history.requestCount;
                case "Avg Blocks":
                    return history.avgBlocks;
                case "Total Blocks":
                    return history.totalBlocks;
                default:
                    return null;
            }
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            String columnName = getColumnName(columnIndex);
            switch (columnName) {
                case "Peer Address":
                case "Announced":
                case "State":
                case "Version":
                    return String.class;
                case "Height":
                    return Integer.class;
                case "Avg Lat (ms)":
                case "Avg Latency (ms)":
                case "Avg Blocks":
                    return Double.class;
                case "Min Lat (ms)":
                case "Min Latency (ms)":
                case "Max Lat (ms)":
                case "Max Latency (ms)":
                case "Last Lat (ms)":
                case "Last Latency (ms)":
                case "Requests":
                case "Total Blocks":
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
        long blockTotalLatency = 0;
        long blockTotalBlocks = 0;
        long blockRequestCount = 0;
        long blockMinLatency = 0;
        long blockMaxLatency = 0;
        int blockMinBlocks = 0;
        int blockMaxBlocks = 0;

        // TX Block stats
        final List<PeerMetric> txBlockHistory = new ArrayList<>();
        long txBlockTotalLatency = 0;
        long txBlockRequestCount = 0;
        long txBlockMinLatency = 0;
        long txBlockMaxLatency = 0;

        // Other stats
        final List<PeerMetric> otherHistory = new ArrayList<>();
        long otherTotalLatency = 0;
        long otherRequestCount = 0;
        long otherMinLatency = 0;
        long otherMaxLatency = 0;

        PeerHistory(String address) {
            this.address = address;
            this.creationTime = System.currentTimeMillis();
        }

        void add(PeerMetric metric) {
            if (metric.getType() == PeerMetric.Type.BLOCK_TX) {
                txBlockHistory.add(metric);
                txBlockTotalLatency += metric.getLatency();
                txBlockRequestCount++;
                if (txBlockHistory.size() > PEER_HISTORY_SIZE) {
                    PeerMetric removed = txBlockHistory.remove(0);
                    txBlockTotalLatency -= removed.getLatency();
                }
                recalcStats(txBlockHistory, MetricType.TX);
            } else if (metric.getType() == PeerMetric.Type.BLOCK_RX) {
                blockHistory.add(metric);
                blockTotalLatency += metric.getLatency();
                blockTotalBlocks += metric.getBlocksReceived();
                blockRequestCount++;
                if (blockHistory.size() > PEER_HISTORY_SIZE) {
                    PeerMetric removed = blockHistory.remove(0);
                    blockTotalLatency -= removed.getLatency();
                    blockTotalBlocks -= removed.getBlocksReceived();
                }
                recalcStats(blockHistory, MetricType.RX);
            } else {
                otherHistory.add(metric);
                otherTotalLatency += metric.getLatency();
                otherRequestCount++;
                if (otherHistory.size() > PEER_HISTORY_SIZE) {
                    PeerMetric removed = otherHistory.remove(0);
                    otherTotalLatency -= removed.getLatency();
                }
                recalcStats(otherHistory, MetricType.OTHER);
            }
        }

        private void recalcStats(List<PeerMetric> list, MetricType type) {
            if (list.isEmpty()) {
                if (type == MetricType.RX) {
                    blockMinLatency = 0;
                    blockMaxLatency = 0;
                    blockMinBlocks = 0;
                    blockMaxBlocks = 0;
                } else if (type == MetricType.TX) {
                    txBlockMinLatency = 0;
                    txBlockMaxLatency = 0;
                } else { // OTHER
                    otherMinLatency = 0;
                    otherMaxLatency = 0;
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
                blockMinLatency = minLat;
                blockMaxLatency = maxLat;
                blockMinBlocks = minBlk;
                blockMaxBlocks = maxBlk;
            } else if (type == MetricType.TX) {
                txBlockMinLatency = minLat;
                txBlockMaxLatency = maxLat;
            } else { // OTHER
                otherMinLatency = minLat;
                otherMaxLatency = maxLat;
            }
        }

        // --- Block Getters ---
        double getBlockAvgLatency() {
            if (blockHistory.isEmpty())
                return 0;
            return (double) blockTotalLatency / blockHistory.size();
        }

        long getBlockMinLatency() {
            return blockMinLatency;
        }

        long getBlockMaxLatency() {
            return blockMaxLatency;
        }

        long getBlockLastLatency() {
            if (blockHistory.isEmpty())
                return 0;
            return blockHistory.get(blockHistory.size() - 1).getLatency();
        }

        long getBlockTotalBlocks() {
            return blockTotalBlocks;
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
        double getTxBlockAvgLatency() {
            if (txBlockHistory.isEmpty())
                return 0;
            return (double) txBlockTotalLatency / txBlockHistory.size();
        }

        long getTxBlockRequestCount() {
            return txBlockRequestCount;
        }

        long getTxBlockMinLatency() {
            return txBlockMinLatency;
        }

        long getTxBlockMaxLatency() {
            return txBlockMaxLatency;
        }

        long getTxBlockLastLatency() {
            if (txBlockHistory.isEmpty())
                return 0;
            return txBlockHistory.get(txBlockHistory.size() - 1).getLatency();
        }

        // --- Other Getters ---
        double getOtherAvgLatency() {
            if (otherHistory.isEmpty())
                return 0;
            return (double) otherTotalLatency / otherHistory.size();
        }

        long getOtherMinLatency() {
            return otherMinLatency;
        }

        long getOtherMaxLatency() {
            return otherMaxLatency;
        }

        long getOtherLastLatency() {
            if (otherHistory.isEmpty())
                return 0;
            return otherHistory.get(otherHistory.size() - 1).getLatency();
        }

        long getOtherRequestCount() {
            return otherRequestCount;
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
        final double latAvg, latMin, latMax, latCur;
        final double countAvg, countMin, countMax, countCur;

        MetricSnapshot(MovingAverage latMA, MovingAverage countMA) { // countMA can be null
            this.latAvg = latMA.getAverage();
            this.latMin = latMA.getMin();
            this.latMax = latMA.getMax();
            this.latCur = latMA.getLast();
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
        final double avgLatency;
        final long minLatency;
        final long maxLatency;
        final long lastLatency;
        final long requestCount;
        final double avgBlocks;
        final long totalBlocks;
        final long lastTimestamp;

        PeerStatsSnapshot(PeerHistory ph, MetricType type) {
            this.address = ph.address;
            this.creationTime = ph.creationTime;
            if (type == MetricType.RX) {
                this.avgLatency = ph.getBlockAvgLatency();
                this.minLatency = ph.getBlockMinLatency();
                this.maxLatency = ph.getBlockMaxLatency();
                this.lastLatency = ph.getBlockLastLatency();
                this.requestCount = ph.getBlockRequestCount();
                this.avgBlocks = ph.getBlockAvgBlocks();
                this.totalBlocks = ph.getBlockTotalBlocks();
                this.lastTimestamp = ph.getBlockLastTimestamp();
            } else if (type == MetricType.TX) {
                this.avgLatency = ph.getTxBlockAvgLatency();
                this.minLatency = ph.getTxBlockMinLatency();
                this.maxLatency = ph.getTxBlockMaxLatency();
                this.lastLatency = ph.getTxBlockLastLatency();
                this.requestCount = ph.getTxBlockRequestCount();
                this.avgBlocks = 0;
                this.totalBlocks = 0;
                this.lastTimestamp = ph.getTxBlockLastTimestamp();
            } else {
                this.avgLatency = ph.getOtherAvgLatency();
                this.minLatency = ph.getOtherMinLatency();
                this.maxLatency = ph.getOtherMaxLatency();
                this.lastLatency = ph.getOtherLastLatency();
                this.requestCount = ph.getOtherRequestCount();
                this.avgBlocks = 0;
                this.totalBlocks = 0;
                this.lastTimestamp = ph.getOtherLastTimestamp();
            }
        }
    }

    private static class MetricsUpdateData {
        long updateCounter;
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
        OverviewUpdateData overviewData;
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

    private static class AbsLatencyStats {
        long minLatency = Long.MAX_VALUE;
        String peerWithMinLatency = null;
        long maxLatency = 0;
        String peerWithMaxLatency = null;

        AbsLatencyStats() {
        }

        AbsLatencyStats(AbsLatencyStats other) {
            this.minLatency = other.minLatency;
            this.peerWithMinLatency = other.peerWithMinLatency;
            this.maxLatency = other.maxLatency;
            this.peerWithMaxLatency = other.peerWithMaxLatency;
        }
    }

    private class PeerMetricTableCellRenderer extends DefaultTableCellRenderer {
        private final Color metricColor;
        private final String latestVersion;

        PeerMetricTableCellRenderer(Color metricColor) {
            this.metricColor = metricColor;
            this.latestVersion = Signum.VERSION.toString();
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if (value instanceof Double) {
                setText((value == null) ? "" : String.format("%.0f", (Double) value));
            }

            if (!isSelected) {
                c.setBackground(table.getBackground());

                int modelRow = table.convertRowIndexToModel(row);
                String version = null;
                javax.swing.table.TableModel model = table.getModel();
                for (int i = 0; i < model.getColumnCount(); i++) {
                    if ("Version".equals(model.getColumnName(i))) {
                        Object val = model.getValueAt(modelRow, i);
                        if (val instanceof String) {
                            version = (String) val;
                        }
                        break;
                    }
                }

                boolean isOutdated = version != null && !version.isEmpty() && !"unknown".equals(version)
                        && PeersDialog.compareVersions(version, latestVersion) < 0;

                if (isOutdated && "Version".equals(table.getColumnName(column))) {
                    c.setForeground(Color.YELLOW);
                } else {
                    c.setForeground(metricColor);
                }
            }
            return c;
        }
    }

    private String getLegendHtml(MetricType type, Color metricColor) {
        String hexColor = String.format("#%02x%02x%02x", metricColor.getRed(), metricColor.getGreen(),
                metricColor.getBlue());
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family: sans-serif; font-size: 10px;'>");
        sb.append("<h3>").append(type).append(" Metrics Legend</h3>");
        sb.append("<ul>");
        sb.append("<li><span style='color:").append(hexColor)
                .append("'>&#9632;</span> <b>Normal:</b> Peer is running the latest version.</li>");
        sb.append(
                "<li><span style='color:yellow'>&#9632;</span> <b>Outdated:</b> Peer is running an older version than this node.</li>");
        sb.append("</ul>");
        sb.append("<b>Columns:</b>");
        sb.append("<ul>");
        sb.append("<li><b>Time:</b> The timestamp of the most recent communication.</li>");
        sb.append("<li><b>Peer Address:</b> IP address or hostname of the peer.</li>");
        sb.append("<li><b>Announced:</b> The announced address of the peer (if any).</li>");
        sb.append("<li><b>State:</b> Connection state (CONNECTED, DISCONNECTED, NON_CONNECTED).</li>");
        sb.append("<li><b>Version:</b> The software version the peer is running.</li>");
        sb.append("<li><b>Height:</b> The blockchain height reported by the peer.</li>");
        sb.append("<li><b>Avg Lat (ms):</b> Average latency (response time) for requests.</li>");
        sb.append("<li><b>Min Lat (ms):</b> Minimum latency observed.</li>");
        sb.append("<li><b>Max Lat (ms):</b> Maximum latency observed.</li>");
        sb.append("<li><b>Last Lat (ms):</b> Latency of the most recent request.</li>");
        sb.append(
                "<li><b>Requests:</b> Total number of requests sent to/received from this peer in the current session/history window.</li>");

        if (type == MetricType.RX) {
            sb.append("<li><b>Avg Blocks:</b> Average number of blocks received per request.</li>");
            sb.append("<li><b>Total Blocks:</b> Total number of blocks received from this peer.</li>");
        }

        sb.append("</ul>");
        sb.append("</body></html>");
        return sb.toString();
    }
}