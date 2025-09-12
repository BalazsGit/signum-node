package brs;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.StandardXYBarPainter;
import org.jfree.chart.renderer.xy.XYBarRenderer;
import org.jfree.chart.renderer.xy.XYStepAreaRenderer;
import org.jfree.chart.ui.RectangleInsets;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import brs.at.AtController;
import brs.props.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("serial")
public class MetricsPanel extends JPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricsPanel.class);
    private static final int CHART_HISTORY_SIZE = 1000;
    private static final int SPEED_HISTORY_SIZE = 1000;
    private static final int MAX_SPEED_BPS = 10 * 1024 * 1024; // 10 MB/s

    private final LinkedList<Long> blockTimestamps = new LinkedList<>();
    private final LinkedList<Integer> transactionCounts = new LinkedList<>();
    private final LinkedList<Long> pushTimes = new LinkedList<>();
    private final LinkedList<Long> validationTimes = new LinkedList<>();
    private final LinkedList<Long> txLoopTimes = new LinkedList<>();
    private final LinkedList<Long> housekeepingTimes = new LinkedList<>();
    private final LinkedList<Long> txApplyTimes = new LinkedList<>();
    private final LinkedList<Long> commitTimes = new LinkedList<>();
    private final LinkedList<Long> atTimes = new LinkedList<>();
    private final LinkedList<Long> subscriptionTimes = new LinkedList<>();
    private final LinkedList<Long> blockApplyTimes = new LinkedList<>();
    private final LinkedList<Long> miscTimes = new LinkedList<>();
    private final LinkedList<Double> blocksPerSecondHistory = new LinkedList<>();
    private final LinkedList<Double> transactionsPerSecondHistory = new LinkedList<>();
    private int movingAverageWindow = 100; // Default value
    private XYSeries blocksPerSecondSeries;
    private XYSeriesCollection transactionsPerBlockDataset;
    private XYSeries transactionsPerSecondSeries;
    private XYSeries transactionsPerBlockSeries;
    private XYSeries pushTimePerBlockSeries;
    private XYSeries uploadSpeedSeries;
    private XYSeries downloadSpeedSeries;
    private XYSeries validationTimePerBlockSeries;
    private XYSeries txLoopTimePerBlockSeries;
    private XYSeries housekeepingTimePerBlockSeries;
    private XYSeries commitTimePerBlockSeries;
    private XYSeries atTimePerBlockSeries;
    private XYSeries txApplyTimePerBlockSeries;
    private XYSeries subscriptionTimePerBlockSeries;
    private XYSeries blockApplyTimePerBlockSeries;
    private XYSeries miscTimePerBlockSeries;

    private JProgressBar blocksPerSecondProgressBar;
    private JProgressBar transactionsPerSecondProgressBar;
    private JProgressBar transactionsPerBlockProgressBar;
    private int oclUnverifiedQueueThreshold;
    private JSlider movingAverageSlider;
    private JLabel uploadSpeedLabel;
    private JLabel downloadSpeedLabel;
    private JLabel metricsUploadVolumeLabel;
    private JLabel metricsDownloadVolumeLabel;
    private String tooltip;

    private long lastNetVolumeUpdateTime = 0;
    private long lastUploadedVolume = 0;
    private long lastDownloadedVolume = 0;

    private JLabel pushTimeLabel;
    private JLabel validationTimeLabel;
    private JLabel txLoopTimeLabel;
    private JLabel housekeepingTimeLabel;
    private JLabel commitTimeLabel;
    private JLabel atTimeLabel;
    private JProgressBar pushTimeProgressBar;
    private JProgressBar validationTimeProgressBar;
    private JProgressBar txLoopTimeProgressBar;
    private JProgressBar housekeepingTimeProgressBar;
    private JProgressBar commitTimeProgressBar;
    private JProgressBar atTimeProgressBar;
    private JLabel txApplyTimeLabel;
    private JProgressBar txApplyTimeProgressBar;
    private JLabel subscriptionTimeLabel;
    private JProgressBar subscriptionTimeProgressBar;
    private JLabel blockApplyTimeLabel;
    private JProgressBar blockApplyTimeProgressBar;
    private JLabel miscTimeLabel;
    private JProgressBar miscTimeProgressBar;
    private JProgressBar uploadSpeedProgressBar;
    private JProgressBar downloadSpeedProgressBar;

    private ChartPanel performanceChartPanel;
    private ChartPanel timingChartPanel;
    private ChartPanel netSpeedChartPanel;

    private final LinkedList<Double> uploadSpeedHistory = new LinkedList<>();
    private final LinkedList<Double> downloadSpeedHistory = new LinkedList<>();

    private XYSeries uploadVolumeSeries;
    private XYSeries downloadVolumeSeries;

    private long uploadedVolume = 0;
    private long downloadedVolume = 0;

    private final Dimension chartDimension1 = new Dimension(280, 210);
    private final Dimension chartDimension2 = new Dimension(280, 105);

    private final Dimension progressBarSize1 = new Dimension(200, 20);
    private final Dimension progressBarSize2 = new Dimension(150, 20);

    private final Insets labelInsets = new Insets(2, 5, 2, 0);
    private final Insets barInsets = new Insets(2, 5, 2, 5);

    private final ExecutorService chartUpdateExecutor = Executors.newSingleThreadExecutor();

    private JProgressBar syncProgressBarDownloadedBlocks;
    private JProgressBar syncProgressBarUnverifiedBlocks;
    private final JFrame parentFrame;

    public MetricsPanel(JFrame parentFrame) {
        super(new GridBagLayout());
        this.parentFrame = parentFrame;
        transactionsPerBlockSeries = new XYSeries("Transactions/Block (MA)");
        transactionsPerBlockDataset = new XYSeriesCollection(transactionsPerBlockSeries);
        performanceChartPanel = createPerformanceChartPanel();
        timingChartPanel = createTimingChartPanel();
        netSpeedChartPanel = createNetSpeedChartPanel();
        layoutComponents();
    }

    public void init() {
        oclUnverifiedQueueThreshold = Signum.getPropertyService().getInt(Props.GPU_UNVERIFIED_QUEUE);
        initListeners();
    }

    private void layoutComponents() {
        setBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5));

        // === Performance Metrics Panel ===
        JPanel performanceMetricsPanel = new JPanel(new GridBagLayout());

        // SyncPanel (Progress Bars)
        JPanel SyncPanel = new JPanel(new GridBagLayout());

        // Verified/Total Blocks
        tooltip = "Shows the number of blocks in the download queue that have passed PoC verification against the total number of blocks in the queue.\n\n- Verified: PoC signature has been checked (CPU/GPU intensive).\n- Total: All blocks currently in the download queue.\n\nA high number of unverified blocks may indicate a slow verification process.";
        JLabel verifLabel = createLabel("Verified/Total Blocks:", null, tooltip);
        syncProgressBarDownloadedBlocks = createProgressBar(0, 100, Color.GREEN, "0 / 0 - 0%", progressBarSize1);
        addComponent(SyncPanel, verifLabel, 0, 0, 1, 0, 0, GridBagConstraints.LINE_END, GridBagConstraints.NONE,
                labelInsets);
        addComponent(SyncPanel, syncProgressBarDownloadedBlocks, 1, 0, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Unverified Blocks
        tooltip = "The number of blocks in the download queue that are waiting for Proof-of-Capacity (PoC) verification.\n\nA persistently high number might indicate that the CPU or GPU is a bottleneck and cannot keep up with the network's block generation rate.";
        JLabel unVerifLabel = createLabel("Unverified Blocks:", null, tooltip);
        syncProgressBarUnverifiedBlocks = createProgressBar(0, 2000, Color.GREEN, "0", progressBarSize1);
        addComponent(SyncPanel, unVerifLabel, 0, 1, 1, 0, 0, GridBagConstraints.LINE_END, GridBagConstraints.NONE,
                labelInsets);
        addComponent(SyncPanel, syncProgressBarUnverifiedBlocks, 1, 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Separator
        JSeparator separator1 = new JSeparator(SwingConstants.HORIZONTAL);
        addComponent(SyncPanel, separator1, 0, 2, 2, 1, 0, GridBagConstraints.CENTER, GridBagConstraints.HORIZONTAL,
                barInsets);

        // Blocks/Second (Moving Average)
        tooltip = "The moving average of blocks processed per second. This is a key indicator of the node's synchronization speed.\n\nA higher value means the node is rapidly catching up with the current state of the blockchain. This metric is particularly useful during the initial sync or after a period of being offline.";
        JLabel blocksPerSecondLabel = createLabel("Blocks/Sec (MA):", Color.CYAN, tooltip);
        blocksPerSecondProgressBar = createProgressBar(0, 200, null, "0", progressBarSize1);
        addComponent(SyncPanel, blocksPerSecondLabel, 0, 3, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(SyncPanel, blocksPerSecondProgressBar, 1, 3, 1, 0, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Transactions/Second (Moving Average)
        tooltip = "The moving average of transactions processed per second. This metric reflects the current transactional throughput of the network as seen by your node.\n\nIt is calculated based on the number of transactions in recent blocks and the time taken to process those blocks. A higher value indicates a busy network with many transactions being confirmed.";
        JLabel txPerSecondLabel = createLabel("Transactions/Sec (MA):", Color.GREEN, tooltip);
        transactionsPerSecondProgressBar = createProgressBar(0, 2000, null, "0", progressBarSize1);
        addComponent(SyncPanel, txPerSecondLabel, 0, 4, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(SyncPanel, transactionsPerSecondProgressBar, 1, 4, 1, 0, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Transactions/Block (Moving Average)
        tooltip = "The moving average of the number of transactions included in each block. This provides insight into how full blocks are on average.\n\nIt helps to understand the network's capacity utilization. A value close to the maximum block capacity (255 transactions) suggests high demand for block space.";
        JLabel txPerBlockLabel = createLabel("Transactions/Block (MA):", new Color(255, 165, 0), tooltip);
        transactionsPerBlockProgressBar = createProgressBar(0, 255, null, "0", progressBarSize1);
        addComponent(SyncPanel, txPerBlockLabel, 0, 5, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(SyncPanel, transactionsPerBlockProgressBar, 1, 5, 1, 0, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Separator
        JSeparator separator2 = new JSeparator(SwingConstants.HORIZONTAL);
        addComponent(SyncPanel, separator2, 0, 6, 2, 1, 0, GridBagConstraints.CENTER, GridBagConstraints.HORIZONTAL,
                barInsets);

        // Moving Average Slider
        tooltip = "The number of recent blocks used to calculate the moving average for performance metrics. A larger window provides a smoother but less responsive trend, while a smaller window is more reactive to recent changes.";
        JLabel maWindowLabel = createLabel("MA Window (Blocks):", null, tooltip);

        // Define the discrete values for the slider
        final int[] maWindowValues = { 10, 100, 200, 300, 400, 500 };
        // Find the initial index for the default movingAverageWindow
        int initialIndex = -1;
        for (int i = 0; i < maWindowValues.length; i++) {
            if (maWindowValues[i] == movingAverageWindow) {
                initialIndex = i;
                break;
            }
        }
        if (initialIndex == -1) { // If default is not in our list, use a sane default
            initialIndex = 1; // 100
            movingAverageWindow = maWindowValues[initialIndex];
        }

        movingAverageSlider = new JSlider(JSlider.HORIZONTAL, 0, maWindowValues.length - 1, initialIndex);
        movingAverageSlider.setSnapToTicks(true);
        movingAverageSlider.setMajorTickSpacing(1);
        movingAverageSlider.setPaintTicks(true);
        movingAverageSlider.setPaintLabels(true);
        movingAverageSlider.setPreferredSize(new Dimension(150, 45));

        Hashtable<Integer, JLabel> labelTable = new Hashtable<>();
        for (int i = 0; i < maWindowValues.length; i++) {
            labelTable.put(i, new JLabel(String.valueOf(maWindowValues[i])));
        }

        movingAverageSlider.setLabelTable(labelTable);

        movingAverageSlider.addChangeListener(e -> {
            JSlider source = (JSlider) e.getSource();
            if (!source.getValueIsAdjusting()) {
                movingAverageWindow = maWindowValues[source.getValue()];
            }
        });

        addComponent(SyncPanel, maWindowLabel, 0, 7, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE,
                labelInsets);
        addComponent(SyncPanel, movingAverageSlider, 1, 7, 2, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Add SyncPanel to performanceMetricsPanel
        addComponent(performanceMetricsPanel, SyncPanel, 0, 0, 1, 0, 0, GridBagConstraints.NORTHWEST,
                GridBagConstraints.NONE, new Insets(0, 0, 0, 0));

        // Performance chart
        JPanel performanceChartContainer = new JPanel();
        performanceChartContainer.setLayout(new BoxLayout(performanceChartContainer, BoxLayout.Y_AXIS));
        performanceChartContainer.add(performanceChartPanel);
        addComponent(performanceMetricsPanel, performanceChartContainer, 1, 0, 1, 0, 0, GridBagConstraints.NORTHWEST,
                GridBagConstraints.NONE, new Insets(0, 0, 0, 0));

        addToggleListener(blocksPerSecondLabel, performanceChartPanel, 0, 0);
        addToggleListener(txPerSecondLabel, performanceChartPanel, 0, 1);
        // End Performance Metrics Panel

        // === Timing Metrics Panel ===
        JPanel timingMetricsPanel = new JPanel(new GridBagLayout());

        JPanel timingInfoPanel = new JPanel(new GridBagLayout());
        int y = 0;

        Insets timerLabelInsets = new Insets(2, 5, 0, 0);
        Insets timerBarInsets = new Insets(0, 5, 2, 5);

        // --- Row 1: Push Time / Validation Time ---
        // Push Time (Left)
        tooltip = "The moving average of the total time taken to process and push a new block. This value is the sum of all individual timing components measured during block processing.\n\nIt includes:\n- Validation Time\n- TX Loop Time\n- Housekeeping Time\n- TX Apply Time\n- AT Time\n- Subscription Time\n- Block Apply Time\n- Commit Time\n- Complementer (miscellaneous) Time";
        pushTimeLabel = createLabel("Push Time/Block (MA):", Color.BLUE, tooltip);
        pushTimeProgressBar = createProgressBar(0, 100, null, "0 ms", progressBarSize1);
        addComponent(timingInfoPanel, pushTimeLabel, 0, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, pushTimeProgressBar, 0, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);

        // Validation Time (Right)
        tooltip = "The moving average of the time spent on block-level validation, excluding the per-transaction validation loop. This is a CPU-intensive task.\n\nMeasured steps include:\n- Verifying block version and timestamp\n- Checking previous block reference\n- Verifying block and generation signatures\n- Validating payload hash and total amounts/fees after transaction processing";
        validationTimeLabel = createLabel("Validation Time (MA):", Color.YELLOW, tooltip);
        validationTimeProgressBar = createProgressBar(0, 100, null, "0 ms", progressBarSize1);
        addComponent(timingInfoPanel, validationTimeLabel, 2, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, validationTimeProgressBar, 2, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);
        y += 2;

        // --- Row 2: TX Loop / Housekeeping ---
        // TX Loop Time (Left)
        tooltip = "The moving average of the time spent iterating through and validating all transactions within a block. This involves both CPU and database read operations.\n\nFor each transaction, this includes:\n- Checking timestamps and deadlines\n- Verifying signatures and public keys\n- Validating referenced transactions\n- Checking for duplicates\n- Executing transaction-specific business logic";
        txLoopTimeLabel = createLabel("TX Loop Time (MA):", new Color(128, 0, 128), tooltip);
        txLoopTimeProgressBar = createProgressBar(0, 100, null, "0 ms", progressBarSize1);
        addComponent(timingInfoPanel, txLoopTimeLabel, 0, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, txLoopTimeProgressBar, 0, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);

        // Housekeeping Time (Right)
        tooltip = "The moving average of the time spent on various 'housekeeping' tasks during block processing.\n\nThis includes:\n- Re-queuing unconfirmed transactions that were not included in the new block\n- Updating peer states and other miscellaneous tasks";
        housekeepingTimeLabel = createLabel("Housekeeping Time (MA):", new Color(42, 223, 223), tooltip);
        housekeepingTimeProgressBar = createProgressBar(0, 100, null, "0 ms", progressBarSize1);
        addComponent(timingInfoPanel, housekeepingTimeLabel, 2, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, housekeepingTimeProgressBar, 2, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);
        y += 2;

        // --- Row 3: TX Apply / AT Time ---
        // TX Apply Time (Left)
        tooltip = "The moving average of the time spent applying the effects of each transaction within the block to the in-memory state. This step handles changes to account balances, aliases, assets, etc., based on the transaction type. It is the first major operation within the 'apply' phase.";
        txApplyTimeLabel = createLabel("TX Apply Time (MA):", new Color(255, 165, 0), tooltip);
        txApplyTimeProgressBar = createProgressBar(0, 100, null, "0 ms", progressBarSize1);
        addComponent(timingInfoPanel, txApplyTimeLabel, 0, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, txApplyTimeProgressBar, 0, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);

        // AT Time (Right)
        tooltip = "The moving average of the time spent validating and processing all Automated Transactions (ATs) within the block. This is a separate computational step that occurs after 'TX Apply Time'.";
        atTimeLabel = createLabel("AT Time/Block (MA):", new Color(153, 0, 76), tooltip);
        atTimeProgressBar = createProgressBar(0, 100, null, "0 ms", progressBarSize1);
        addComponent(timingInfoPanel, atTimeLabel, 2, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, atTimeProgressBar, 2, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);
        y += 2;

        // --- Row 4: Subscription / Block Apply ---
        // Subscription Time (Left)
        tooltip = "The moving average of the time spent processing recurring subscription payments for the block. This is a separate step that occurs after AT processing.";
        subscriptionTimeLabel = createLabel("Subscription Time (MA):", new Color(255, 105, 100), tooltip); // Hot pink
        subscriptionTimeProgressBar = createProgressBar(0, 100, null, "0 ms", progressBarSize1);
        addComponent(timingInfoPanel, subscriptionTimeLabel, 0, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, subscriptionTimeProgressBar, 0, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);

        // Block Apply Time (Right)
        tooltip = "The moving average of the time spent applying block-level changes. This includes distributing the block reward to the generator, updating escrow services, and notifying listeners about the applied block. This is the final step before the 'Commit' phase.";
        blockApplyTimeLabel = createLabel("Block Apply Time (MA):", new Color(0, 100, 100), tooltip); // Teal
        blockApplyTimeProgressBar = createProgressBar(0, 100, null, "0 ms", progressBarSize1);
        addComponent(timingInfoPanel, blockApplyTimeLabel, 2, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, blockApplyTimeProgressBar, 2, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);
        y += 2;

        // --- Row 5: Commit / Misc. Time ---
        // Commit Time (Left)
        tooltip = "The moving average of the time spent committing all in-memory state changes to the database on disk. This is a disk I/O-intensive operation.";
        commitTimeLabel = createLabel("Commit Time (MA):", new Color(150, 0, 200), tooltip);
        commitTimeProgressBar = createProgressBar(0, 100, null, "0 ms", progressBarSize1);
        addComponent(timingInfoPanel, commitTimeLabel, 0, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, commitTimeProgressBar, 0, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);

        // Misc. Time (Right)
        tooltip = "The moving average of the time spent on miscellaneous, 'unaccounted for' calculations during block processing. This value is calculated by subtracting the sum of all explicitly measured components from the 'Total Push Time'. These components are: Validation, TX Loop, Housekeeping, TX Apply, AT, Subscription, Block Apply, and Commit. A consistently high value may indicate performance overhead in parts of the code that are not explicitly timed, such as memory management or other background tasks.";
        miscTimeLabel = createLabel("Misc. Time (MA):", Color.LIGHT_GRAY, tooltip);
        miscTimeProgressBar = createProgressBar(0, 100, null, "0 ms", progressBarSize1);
        addComponent(timingInfoPanel, miscTimeLabel, 2, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, miscTimeProgressBar, 2, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);
        y += 2;

        // Add timingInfoPanel to timingMetricsPanel
        addComponent(timingMetricsPanel, timingInfoPanel, 0, 0, 1, 0, 0, GridBagConstraints.NORTHWEST,
                GridBagConstraints.NONE, new Insets(0, 0, 0, 0));

        // --- Timing Chart Panel ---
        JPanel timingChartContainer = new JPanel();
        timingChartContainer.setLayout(new BoxLayout(timingChartContainer, BoxLayout.Y_AXIS));
        timingChartContainer.add(timingChartPanel);
        addComponent(timingMetricsPanel, timingChartContainer, 1, 0, 1, 0, 0, GridBagConstraints.NORTHWEST,
                GridBagConstraints.NONE, new Insets(0, 0, 0, 0));
        // End Timing Metrics Panel

        // --- Net Speed Chart Panel ---
        JPanel netSpeedChartContainer = new JPanel();
        netSpeedChartContainer.setLayout(new BoxLayout(netSpeedChartContainer, BoxLayout.Y_AXIS));
        netSpeedChartContainer.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        netSpeedChartContainer.add(netSpeedChartPanel);

        JPanel netSpeedInfoPanel = new JPanel(new GridBagLayout());
        netSpeedInfoPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));

        // Separator3
        JSeparator separator3 = new JSeparator(SwingConstants.HORIZONTAL);
        addComponent(netSpeedInfoPanel, separator3, 0, 0, 2, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.HORIZONTAL,
                barInsets);

        // --- Upload Speed ---
        tooltip = "The moving average of data upload speed to other peers in the network.\n\nThis reflects how much data your node is sharing, which includes:\n- Blocks\n- Transactions\n- Peer information";
        uploadSpeedLabel = createLabel("▲ Speed (MA):", new Color(128, 0, 0), tooltip);
        uploadSpeedProgressBar = createProgressBar(0, MAX_SPEED_BPS, null, "0 B/s", progressBarSize2);
        addComponent(netSpeedInfoPanel, uploadSpeedLabel, 0, 1, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(netSpeedInfoPanel, uploadSpeedProgressBar, 1, 1, 1, 0, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // --- Download Speed ---
        tooltip = "The moving average of data download speed from other peers in the network.\n\nThis indicates how quickly your node is receiving data, which includes:\n- Blocks\n- Transactions\n- Peer information";
        downloadSpeedLabel = createLabel("▼ Speed (MA):", new Color(0, 100, 0), tooltip);
        downloadSpeedProgressBar = createProgressBar(0, MAX_SPEED_BPS, null, "0 B/s", progressBarSize2);
        addComponent(netSpeedInfoPanel, downloadSpeedLabel, 0, 2, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(netSpeedInfoPanel, downloadSpeedProgressBar, 1, 2, 1, 0, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // --- Combined Volume ---
        JPanel combinedVolumePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        combinedVolumePanel.setOpaque(false);
        tooltip = "The total amount of data uploaded to and downloaded from the network during this session. The format is Uploaded / Downloaded.";
        JLabel volumeTitleLabel = createLabel("Volume:", null, tooltip);
        tooltip = "The total amount of data uploaded to the network during this session.";
        metricsUploadVolumeLabel = createLabel("", new Color(233, 150, 122), tooltip);
        tooltip = "The total amount of data downloaded from the network during this session.";
        metricsDownloadVolumeLabel = createLabel("", new Color(50, 205, 50), tooltip);
        combinedVolumePanel.add(volumeTitleLabel);
        combinedVolumePanel.add(metricsUploadVolumeLabel);
        combinedVolumePanel.add(new JLabel(" / "));
        combinedVolumePanel.add(metricsDownloadVolumeLabel);
        addComponent(netSpeedInfoPanel, combinedVolumePanel, 0, 3, 2, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, barInsets);

        netSpeedChartContainer.add(netSpeedInfoPanel);
        addComponent(this, netSpeedChartContainer, 3, 0, 1, 0, 0, GridBagConstraints.NORTH,
                GridBagConstraints.NONE, new Insets(0, 0, 0, 5));

        // Add performanceMetricsPanel to main metrics panel
        addComponent(this, performanceMetricsPanel, 0, 0, 1, 0, 0, GridBagConstraints.NORTH,
                GridBagConstraints.NONE, new Insets(0, 0, 0, 5));

        // Vertical Separator
        JSeparator mainVerticalSeparator = new JSeparator(SwingConstants.VERTICAL);
        addComponent(this, mainVerticalSeparator, 1, 0, 1, 0, 1, GridBagConstraints.CENTER,
                GridBagConstraints.VERTICAL, new Insets(0, 5, 0, 5));

        // Add timingMetricsPanel to main metrics panel
        addComponent(this, timingMetricsPanel, 2, 0, 1, 0, 0, GridBagConstraints.NORTH,
                GridBagConstraints.NONE, new Insets(0, 0, 0, 0));
        // END Metrics Panel

        addDualChartToggleListener(txPerBlockLabel, performanceChartPanel, 1, 0, timingChartPanel, 1, 0);
        addToggleListener(pushTimeLabel, timingChartPanel, 0, 0);
        addToggleListener(validationTimeLabel, timingChartPanel, 0, 1);
        addToggleListener(txLoopTimeLabel, timingChartPanel, 0, 2);
        addToggleListener(housekeepingTimeLabel, timingChartPanel, 0, 3);
        addToggleListener(txApplyTimeLabel, timingChartPanel, 0, 4);
        addToggleListener(atTimeLabel, timingChartPanel, 0, 5);
        addToggleListener(subscriptionTimeLabel, timingChartPanel, 0, 6);
        addToggleListener(blockApplyTimeLabel, timingChartPanel, 0, 7);
        addToggleListener(commitTimeLabel, timingChartPanel, 0, 8);
        addToggleListener(miscTimeLabel, timingChartPanel, 0, 9);
        addToggleListener(uploadSpeedLabel, netSpeedChartPanel, 0, 0);
        addToggleListener(downloadSpeedLabel, netSpeedChartPanel, 0, 1);

        Color uploadVolumeColor = new Color(233, 150, 122, 128); // Red
        Color downloadVolumeColor = new Color(50, 205, 50, 128); // Green
        addPaintToggleListener(metricsUploadVolumeLabel, netSpeedChartPanel, 1, 1, uploadVolumeColor);
        addPaintToggleListener(metricsDownloadVolumeLabel, netSpeedChartPanel, 1, 0, downloadVolumeColor);

        // Timer to periodically update the network speed chart so it flows even with no
        // traffic
        Timer netSpeedChartUpdater = new Timer(100, e -> {
            updateNetVolumeAndSpeedChart(uploadedVolume, downloadedVolume);
        });
        netSpeedChartUpdater.start();
    }

    private void initListeners() {
        BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
        if (blockchainProcessor != null) {
            blockchainProcessor.addListener(block -> onQueueStatus(), BlockchainProcessor.Event.QUEUE_STATUS_CHANGED);
            blockchainProcessor.addListener(block -> onNetVolumeChanged(),
                    BlockchainProcessor.Event.NET_VOLUME_CHANGED);
            blockchainProcessor.addListener(this::onPerformanceStatsUpdated,
                    BlockchainProcessor.Event.PERFORMANCE_STATS_UPDATED);
            blockchainProcessor.addListener(this::onBlockPushed, BlockchainProcessor.Event.BLOCK_PUSHED);
        }
    }

    public void shutdown() {
        chartUpdateExecutor.shutdown();
    }

    public void onQueueStatus() {
        BlockchainProcessor.QueueStatus status = Signum.getBlockchainProcessor().getQueueStatus();
        if (status != null) {
            SwingUtilities.invokeLater(
                    () -> updateQueueStatus(status.unverifiedSize, status.verifiedSize, status.totalSize));
        }
    }

    public void onNetVolumeChanged() {
        BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
        SwingUtilities.invokeLater(() -> updateNetVolume(blockchainProcessor.getUploadedVolume(),
                blockchainProcessor.getDownloadedVolume()));
    }

    public void onPerformanceStatsUpdated(Block block) {
        BlockchainProcessor.PerformanceStats stats = Signum.getBlockchainProcessor().getPerformanceStats();
        if (stats != null && block != null) {
            chartUpdateExecutor.submit(() -> {
                updateTimingChart(stats.totalTimeMs, stats.validationTimeMs, stats.txLoopTimeMs,
                        stats.housekeepingTimeMs, stats.txApplyTimeMs, stats.atTimeMs, stats.subscriptionTimeMs,
                        stats.blockApplyTimeMs, stats.commitTimeMs, stats.miscTimeMs, block);
            });
        }
    }

    private void onBlockPushed(Block block) {
        if (block == null)
            return;
        chartUpdateExecutor.submit(() -> updatePerformanceChart(block));
    }

    private JProgressBar createProgressBar(int min, int max, Color color, String initialString, Dimension size) {
        JProgressBar bar = new JProgressBar(min, max);
        bar.setBackground(color);
        bar.setPreferredSize(size);
        bar.setMinimumSize(size);
        bar.setStringPainted(true);
        bar.setString(initialString);
        bar.setValue(min);
        return bar;
    }

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

    private void addInfoTooltip(JLabel label, String text) {
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    String title = label.getText();
                    // Remove trailing colon for a cleaner title
                    if (title.endsWith(":")) {
                        title = title.substring(0, title.length() - 1);
                    }
                    // Wrap the text in HTML to control the width of the dialog.
                    String htmlText = "<html><body><p style='width: 300px;'>" + text.replace("\n", "<br>")
                            + "</p></body></html>";
                    JOptionPane.showMessageDialog(parentFrame, htmlText, title, JOptionPane.PLAIN_MESSAGE);
                }
            }
        });
    }

    private void addComponent(JPanel panel, Component comp, int x, int y, int gridwidth, int weightx, int weighty,
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

    private void addLabelToggleListener(JLabel label, Consumer<Boolean> onToggleAction) {
        label.putClientProperty("visible", true);
        final Font originalFont = label.getFont();
        // Create a strikethrough version of the font to indicate a disabled state
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

    private void addToggleListener(JLabel label, ChartPanel chartPanel, int rendererIndex, int seriesIndex) {
        addLabelToggleListener(label, isVisible -> chartPanel.getChart().getXYPlot().getRenderer(rendererIndex)
                .setSeriesVisible(seriesIndex, isVisible));
    }

    private void addPaintToggleListener(JLabel label, ChartPanel chartPanel, int rendererIndex, int seriesIndex,
            Color originalColor) {
        final Color transparentColor = new Color(0, 0, 0, 0);
        addLabelToggleListener(label, isVisible -> {
            org.jfree.chart.renderer.xy.AbstractXYItemRenderer renderer = (org.jfree.chart.renderer.xy.AbstractXYItemRenderer) chartPanel
                    .getChart().getXYPlot().getRenderer(rendererIndex);

            renderer.setSeriesVisible(seriesIndex, isVisible);
            renderer.setSeriesPaint(seriesIndex, isVisible ? originalColor : transparentColor);
        });
    }

    private void addDualChartToggleListener(JLabel label,
            ChartPanel chartPanel1, int rendererIndex1, int seriesIndex1,
            ChartPanel chartPanel2, int rendererIndex2, int seriesIndex2) {
        addLabelToggleListener(label, isVisible -> {
            chartPanel1.getChart().getXYPlot().getRenderer(rendererIndex1).setSeriesVisible(seriesIndex1, isVisible);
            chartPanel2.getChart().getXYPlot().getRenderer(rendererIndex2).setSeriesVisible(seriesIndex2, isVisible);
        });
    }

    private void updateQueueStatus(int downloadCacheUnverifiedSize,
            int downloadCacheVerifiedSize,
            int downloadCacheTotalSize) {

        syncProgressBarDownloadedBlocks.setStringPainted(true);
        syncProgressBarUnverifiedBlocks.setStringPainted(true);

        if (downloadCacheTotalSize != 0) {
            syncProgressBarDownloadedBlocks.setString(downloadCacheVerifiedSize + " / " + downloadCacheTotalSize + " - "
                    + 100 * downloadCacheVerifiedSize / downloadCacheTotalSize + "%");
            syncProgressBarDownloadedBlocks.setValue(100 * downloadCacheVerifiedSize / downloadCacheTotalSize);

        } else {
            syncProgressBarDownloadedBlocks.setString("0 / 0 - 0%");
            syncProgressBarDownloadedBlocks.setValue(0);
        }

        syncProgressBarUnverifiedBlocks.setString(downloadCacheUnverifiedSize + "");
        syncProgressBarUnverifiedBlocks.setValue(downloadCacheUnverifiedSize);

        if (downloadCacheUnverifiedSize > oclUnverifiedQueueThreshold) {
            syncProgressBarUnverifiedBlocks.setForeground(Color.RED);
        } else {
            syncProgressBarUnverifiedBlocks.setForeground(Color.GREEN);
        }
    }

    private void updateNetVolumeAndSpeedChart(long uploadedVolume, long downloadedVolume) {
        chartUpdateExecutor.submit(() -> {
            // --- Calculations on background thread ---
            long currentTime = System.currentTimeMillis();
            if (lastNetVolumeUpdateTime == 0) {
                lastNetVolumeUpdateTime = currentTime;
                lastUploadedVolume = uploadedVolume;
                lastDownloadedVolume = downloadedVolume;
                return;
            }

            long deltaTime = currentTime - lastNetVolumeUpdateTime;
            if (deltaTime <= 0) {
                return; // Avoid division by zero or negative time intervals
            }

            long deltaUploaded = uploadedVolume - lastUploadedVolume;
            long deltaDownloaded = downloadedVolume - lastDownloadedVolume;

            double currentUploadSpeed = (double) deltaUploaded * 1000 / deltaTime; // bytes per second
            double currentDownloadSpeed = (double) deltaDownloaded * 1000 / deltaTime; // bytes per second

            // Add current speed to history and maintain size
            uploadSpeedHistory.add(currentUploadSpeed);
            if (uploadSpeedHistory.size() > SPEED_HISTORY_SIZE) {
                uploadSpeedHistory.removeFirst();
            }

            downloadSpeedHistory.add(currentDownloadSpeed);
            if (downloadSpeedHistory.size() > SPEED_HISTORY_SIZE) {
                downloadSpeedHistory.removeFirst();
            }

            int currentWindowSize = Math.min(uploadSpeedHistory.size(), movingAverageWindow);
            if (currentWindowSize < 1) {
                return;
            }

            double avgUploadSpeed = uploadSpeedHistory.stream()
                    .skip(Math.max(0, uploadSpeedHistory.size() - currentWindowSize))
                    .mapToDouble(d -> d)
                    .average().orElse(0.0);

            double avgDownloadSpeed = downloadSpeedHistory.stream()
                    .skip(Math.max(0, downloadSpeedHistory.size() - currentWindowSize))
                    .mapToDouble(d -> d)
                    .average().orElse(0.0);

            lastNetVolumeUpdateTime = currentTime;
            lastUploadedVolume = uploadedVolume;
            lastDownloadedVolume = downloadedVolume;

            // --- UI Updates on EDT ---
            SwingUtilities.invokeLater(() -> {
                if (metricsUploadVolumeLabel != null) {
                    metricsUploadVolumeLabel.setText("▲ " + formatDataSize(uploadedVolume));
                }
                if (metricsDownloadVolumeLabel != null) {
                    metricsDownloadVolumeLabel.setText("▼ " + formatDataSize(downloadedVolume));
                }

                uploadSpeedProgressBar.setValue((int) avgUploadSpeed);
                uploadSpeedProgressBar.setString(formatDataRate(avgUploadSpeed));
                downloadSpeedProgressBar.setValue((int) avgDownloadSpeed);
                downloadSpeedProgressBar.setString(formatDataRate(avgDownloadSpeed));

                if (uploadedVolume > 0 || downloadedVolume > 0) {
                    uploadSpeedSeries.add(currentTime, avgUploadSpeed);
                    downloadSpeedSeries.add(currentTime, avgDownloadSpeed);
                    uploadVolumeSeries.add(currentTime, uploadedVolume);
                    downloadVolumeSeries.add(currentTime, downloadedVolume);

                    while (uploadSpeedSeries.getItemCount() > SPEED_HISTORY_SIZE) {
                        uploadSpeedSeries.remove(0);
                    }
                    while (downloadSpeedSeries.getItemCount() > SPEED_HISTORY_SIZE) {
                        downloadSpeedSeries.remove(0);
                    }
                    while (uploadVolumeSeries.getItemCount() > SPEED_HISTORY_SIZE) {
                        uploadVolumeSeries.remove(0);
                    }
                    while (downloadVolumeSeries.getItemCount() > SPEED_HISTORY_SIZE) {
                        downloadVolumeSeries.remove(0);
                    }
                }
            });
        });
    }

    private void updateNetVolume(long uploadedVolume, long downloadedVolume) {
        this.uploadedVolume = uploadedVolume;
        this.downloadedVolume = downloadedVolume;
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

    private String formatDataRate(double bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            return "0 B/s";
        }
        String[] units = { "B", "KB", "MB", "GB", "TB", "PB", "EB" };
        int unitIndex = 0;
        while (bytesPerSecond >= 1024 && unitIndex < units.length - 1) {
            bytesPerSecond /= 1024;
            unitIndex++;
        }
        return String.format("%.2f %s/s", bytesPerSecond, units[unitIndex]);
    }

    private void updateTimingChart(long totalTimeMs, long validationTimeMs, long txLoopTimeMs, long housekeepingTimeMs,
            long txApplyTimeMs, long atTimeMs, long subscriptionTimeMs, long blockApplyTimeMs, long commitTimeMs,
            long miscTimeMs, Block block) {

        if (block == null) {
            return;
        }

        int blockHeight = block.getHeight();

        pushTimes.add(totalTimeMs);
        validationTimes.add(validationTimeMs);
        txLoopTimes.add(txLoopTimeMs);
        housekeepingTimes.add(housekeepingTimeMs);
        txApplyTimes.add(txApplyTimeMs);
        commitTimes.add(commitTimeMs);
        atTimes.add(atTimeMs);
        subscriptionTimes.add(subscriptionTimeMs);
        blockApplyTimes.add(blockApplyTimeMs);
        miscTimes.add(miscTimeMs);

        while (pushTimes.size() > CHART_HISTORY_SIZE) {
            pushTimes.removeFirst();
        }
        while (validationTimes.size() > CHART_HISTORY_SIZE) {
            validationTimes.removeFirst();
        }
        while (txLoopTimes.size() > CHART_HISTORY_SIZE) {
            txLoopTimes.removeFirst();
        }
        while (housekeepingTimes.size() > CHART_HISTORY_SIZE) {
            housekeepingTimes.removeFirst();
        }
        while (commitTimes.size() > CHART_HISTORY_SIZE) {
            commitTimes.removeFirst();
        }
        while (atTimes.size() > CHART_HISTORY_SIZE) {
            atTimes.removeFirst();
        }
        while (txApplyTimes.size() > CHART_HISTORY_SIZE) {
            txApplyTimes.removeFirst();
        }
        while (subscriptionTimes.size() > CHART_HISTORY_SIZE) {
            subscriptionTimes.removeFirst();
        }
        while (blockApplyTimes.size() > CHART_HISTORY_SIZE) {
            blockApplyTimes.removeFirst();
        }
        while (miscTimes.size() > CHART_HISTORY_SIZE) {
            miscTimes.removeFirst();
        }

        int currentWindowSize = Math.min(pushTimes.size(), movingAverageWindow);
        if (currentWindowSize < 1) {
            return;
        }

        long maxPushTime = pushTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        long maxValidationTime = validationTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        long maxTxLoopTime = txLoopTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        long maxHousekeepingTime = housekeepingTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        long maxCommitTime = commitTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        long maxAtTime = atTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        long maxTxApplyTime = txApplyTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        long maxSubscriptionTime = subscriptionTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        long maxBlockApplyTime = blockApplyTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        long maxMiscTime = miscTimes.stream().mapToLong(Long::longValue).max().orElse(0);

        long displayPushTime = (long) pushTimes.stream()
                .skip(Math.max(0, pushTimes.size() - currentWindowSize))
                .mapToLong(Long::longValue)
                .average().orElse(0.0);

        long displayValidationTime = (long) validationTimes.stream()
                .skip(Math.max(0, validationTimes.size() - currentWindowSize))
                .mapToLong(Long::longValue)
                .average().orElse(0.0);

        long displayTxLoopTime = (long) txLoopTimes.stream()
                .skip(Math.max(0, txLoopTimes.size() - currentWindowSize))
                .mapToLong(Long::longValue)
                .average().orElse(0.0);

        long displayHousekeepingTime = (long) housekeepingTimes.stream()
                .skip(Math.max(0, housekeepingTimes.size() - currentWindowSize))
                .mapToLong(Long::longValue)
                .average().orElse(0.0);

        long displayCommitTime = (long) commitTimes.stream()
                .skip(Math.max(0, commitTimes.size() - currentWindowSize))
                .mapToLong(Long::longValue)
                .average().orElse(0.0);

        long displayAtTime = (long) atTimes.stream()
                .skip(Math.max(0, atTimes.size() - currentWindowSize))
                .mapToLong(Long::longValue)
                .average().orElse(0.0);

        long displayTxApplyTime = (long) txApplyTimes.stream()
                .skip(Math.max(0, txApplyTimes.size() - currentWindowSize))
                .mapToLong(Long::longValue)
                .average().orElse(0.0);

        long displaySubscriptionTime = (long) subscriptionTimes.stream()
                .skip(Math.max(0, subscriptionTimes.size() - currentWindowSize))
                .mapToLong(Long::longValue)
                .average().orElse(0.0);

        long displayBlockApplyTime = (long) blockApplyTimes.stream()
                .skip(Math.max(0, blockApplyTimes.size() - currentWindowSize))
                .mapToLong(Long::longValue)
                .average().orElse(0.0);

        long displayMiscTime = (long) miscTimes.stream()
                .skip(Math.max(0, miscTimes.size() - currentWindowSize))
                .mapToLong(Long::longValue)
                .average().orElse(0.0);

        SwingUtilities.invokeLater(() -> {
            pushTimeProgressBar.setValue((int) displayPushTime);
            pushTimeProgressBar.setString(String.format("%d ms - max: %d ms", displayPushTime, maxPushTime));
            validationTimeProgressBar.setValue((int) displayValidationTime);
            validationTimeProgressBar
                    .setString(String.format("%d ms - max: %d ms", displayValidationTime, maxValidationTime));
            txLoopTimeProgressBar.setValue((int) displayTxLoopTime);
            txLoopTimeProgressBar.setString(String.format("%d ms - max: %d ms", displayTxLoopTime, maxTxLoopTime));
            housekeepingTimeProgressBar.setValue((int) displayHousekeepingTime);
            housekeepingTimeProgressBar
                    .setString(String.format("%d ms - max: %d ms", displayHousekeepingTime, maxHousekeepingTime));
            commitTimeProgressBar.setValue((int) displayCommitTime);
            commitTimeProgressBar.setString(String.format("%d ms - max: %d ms", displayCommitTime, maxCommitTime));
            atTimeProgressBar.setValue((int) displayAtTime);
            atTimeProgressBar.setString(String.format("%d ms - max: %d ms", displayAtTime, maxAtTime));
            txApplyTimeProgressBar.setValue((int) displayTxApplyTime);
            txApplyTimeProgressBar.setString(String.format("%d ms - max: %d ms", displayTxApplyTime, maxTxApplyTime));
            subscriptionTimeProgressBar.setValue((int) displaySubscriptionTime);
            subscriptionTimeProgressBar
                    .setString(String.format("%d ms - max: %d ms", displaySubscriptionTime, maxSubscriptionTime));
            blockApplyTimeProgressBar.setValue((int) displayBlockApplyTime);
            blockApplyTimeProgressBar
                    .setString(String.format("%d ms - max: %d ms", displayBlockApplyTime, maxBlockApplyTime));
            miscTimeProgressBar.setValue((int) displayMiscTime);
            miscTimeProgressBar.setString(String.format("%d ms - max: %d ms", displayMiscTime, maxMiscTime));

            // Update timing chart series
            pushTimePerBlockSeries.add(blockHeight, displayPushTime);
            validationTimePerBlockSeries.add(blockHeight, displayValidationTime);
            txLoopTimePerBlockSeries.add(blockHeight, displayTxLoopTime);
            housekeepingTimePerBlockSeries.add(blockHeight, displayHousekeepingTime);
            commitTimePerBlockSeries.add(blockHeight, displayCommitTime);
            atTimePerBlockSeries.add(blockHeight, displayAtTime);
            txApplyTimePerBlockSeries.add(blockHeight, displayTxApplyTime);
            subscriptionTimePerBlockSeries.add(blockHeight, displaySubscriptionTime);
            miscTimePerBlockSeries.add(blockHeight, displayMiscTime);
            blockApplyTimePerBlockSeries.add(blockHeight, displayBlockApplyTime);

            // Keep history size for timing chart
            while (pushTimePerBlockSeries.getItemCount() > CHART_HISTORY_SIZE) {
                pushTimePerBlockSeries.remove(0);
            }
            while (validationTimePerBlockSeries.getItemCount() > CHART_HISTORY_SIZE) {
                validationTimePerBlockSeries.remove(0);
            }
            while (txLoopTimePerBlockSeries.getItemCount() > CHART_HISTORY_SIZE) {
                txLoopTimePerBlockSeries.remove(0);
            }
            while (housekeepingTimePerBlockSeries.getItemCount() > CHART_HISTORY_SIZE) {
                housekeepingTimePerBlockSeries.remove(0);
            }
            while (commitTimePerBlockSeries.getItemCount() > CHART_HISTORY_SIZE) {
                commitTimePerBlockSeries.remove(0);
            }
            while (atTimePerBlockSeries.getItemCount() > CHART_HISTORY_SIZE) {
                atTimePerBlockSeries.remove(0);
            }
            while (txApplyTimePerBlockSeries.getItemCount() > CHART_HISTORY_SIZE) {
                txApplyTimePerBlockSeries.remove(0);
            }
            while (subscriptionTimePerBlockSeries.getItemCount() > CHART_HISTORY_SIZE) {
                subscriptionTimePerBlockSeries.remove(0);
            }
            while (blockApplyTimePerBlockSeries.getItemCount() > CHART_HISTORY_SIZE) {
                blockApplyTimePerBlockSeries.remove(0);
            }
            while (miscTimePerBlockSeries.getItemCount() > CHART_HISTORY_SIZE) {
                miscTimePerBlockSeries.remove(0);
            }
        });
    }

    private ChartPanel createPerformanceChartPanel() {
        blocksPerSecondSeries = new XYSeries("Blocks/Second (MA)");
        transactionsPerSecondSeries = new XYSeries("Transactions/Second (MA)");

        XYSeriesCollection lineDataset = new XYSeriesCollection();
        lineDataset.addSeries(blocksPerSecondSeries);
        lineDataset.addSeries(transactionsPerSecondSeries);

        // Create chart with no title or axis labels to save space
        JFreeChart chart = ChartFactory.createXYLineChart(
                null, // No title
                null, // No X-axis label
                null, // No Y-axis label
                lineDataset);

        // Remove the legend to maximize plot area
        chart.removeLegend();
        chart.setBorderVisible(false);

        XYPlot plot = chart.getXYPlot();
        plot.getDomainAxis().setLowerMargin(0.0);
        plot.getDomainAxis().setUpperMargin(0.0);
        plot.setBackgroundPaint(Color.DARK_GRAY);
        plot.setDomainGridlinesVisible(false);
        plot.setRangeGridlinesVisible(false);

        plot.getRenderer().setSeriesPaint(0, Color.CYAN);
        plot.getRenderer().setSeriesPaint(1, Color.GREEN);

        // Set line thickness
        plot.getRenderer().setSeriesStroke(0, new java.awt.BasicStroke(1.2f));
        plot.getRenderer().setSeriesStroke(1, new java.awt.BasicStroke(1.2f));

        // Hide axis tick labels (the numbers on the axes)
        plot.getDomainAxis().setTickLabelsVisible(false);
        plot.getRangeAxis().setTickLabelsVisible(false);

        // Second Y-axis for transaction count
        NumberAxis transactionAxis = new NumberAxis(null); // No label for the second axis
        transactionAxis.setTickLabelsVisible(false);
        plot.setRangeAxis(1, transactionAxis);
        plot.setDataset(1, transactionsPerBlockDataset);
        plot.mapDatasetToRangeAxis(1, 1);

        // Renderer for transaction bars
        XYBarRenderer transactionRenderer = new XYBarRenderer(0);
        transactionRenderer.setBarPainter(new StandardXYBarPainter());
        transactionRenderer.setShadowVisible(false);
        transactionRenderer.setSeriesPaint(0, new Color(255, 165, 0, 128)); // Orange, semi-transparent
        plot.setRenderer(1, transactionRenderer);

        // Remove all padding around the plot area
        plot.setInsets(new RectangleInsets(0, 0, 0, 0));
        plot.setAxisOffset(new RectangleInsets(0, 0, 0, 0));

        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(chartDimension1);
        chartPanel.setMinimumSize(chartDimension1);
        chartPanel.setMaximumSize(chartDimension1);
        return chartPanel;
    }

    private ChartPanel createTimingChartPanel() {
        pushTimePerBlockSeries = new XYSeries("Push Time/Block (MA)");
        validationTimePerBlockSeries = new XYSeries("Validation Time/Block (MA)");
        txLoopTimePerBlockSeries = new XYSeries("TX Loop Time/Block (MA)");
        housekeepingTimePerBlockSeries = new XYSeries("Housekeeping Time/Block (MA)");
        txApplyTimePerBlockSeries = new XYSeries("TX Apply Time/Block (MA)");
        atTimePerBlockSeries = new XYSeries("AT Time/Block (MA)");
        subscriptionTimePerBlockSeries = new XYSeries("Subscription Time/Block (MA)");
        blockApplyTimePerBlockSeries = new XYSeries("Block Apply Time/Block (MA)");
        commitTimePerBlockSeries = new XYSeries("Commit Time/Block (MA)");
        miscTimePerBlockSeries = new XYSeries("Misc. Time/Block (MA)");

        XYSeriesCollection lineDataset = new XYSeriesCollection();
        lineDataset.addSeries(pushTimePerBlockSeries);
        lineDataset.addSeries(validationTimePerBlockSeries);
        lineDataset.addSeries(txLoopTimePerBlockSeries);
        lineDataset.addSeries(housekeepingTimePerBlockSeries);
        lineDataset.addSeries(txApplyTimePerBlockSeries);
        lineDataset.addSeries(atTimePerBlockSeries);
        lineDataset.addSeries(subscriptionTimePerBlockSeries);
        lineDataset.addSeries(blockApplyTimePerBlockSeries);
        lineDataset.addSeries(commitTimePerBlockSeries);
        lineDataset.addSeries(miscTimePerBlockSeries);

        // Create chart with no title or axis labels to save space
        JFreeChart chart = ChartFactory.createXYLineChart(
                null, // No title
                null, // No X-axis label
                null, // No Y-axis label
                lineDataset);

        // Remove the legend to maximize plot area
        chart.removeLegend();
        chart.setBorderVisible(false);

        XYPlot plot = chart.getXYPlot();
        plot.getDomainAxis().setLowerMargin(0.0);
        plot.getDomainAxis().setUpperMargin(0.0);
        plot.setBackgroundPaint(Color.DARK_GRAY);
        plot.setDomainGridlinesVisible(false);
        plot.setRangeGridlinesVisible(false);

        plot.getRenderer().setSeriesPaint(0, Color.BLUE);
        plot.getRenderer().setSeriesPaint(1, Color.YELLOW);
        plot.getRenderer().setSeriesPaint(2, new Color(128, 0, 128)); // Purple for TX Loop
        plot.getRenderer().setSeriesPaint(3, new Color(42, 223, 223)); // Cyan for Housekeeping
        plot.getRenderer().setSeriesPaint(4, new Color(255, 165, 0)); // Orange for TX Apply
        plot.getRenderer().setSeriesPaint(5, new Color(153, 0, 76)); // Dark Red for AT
        plot.getRenderer().setSeriesPaint(6, new Color(255, 105, 100)); // Hot Pink for Subscription
        plot.getRenderer().setSeriesPaint(7, new Color(0, 100, 100)); // Teal for Block Apply
        plot.getRenderer().setSeriesPaint(8, new Color(150, 0, 200)); // Magenta for Commit
        plot.getRenderer().setSeriesPaint(9, Color.LIGHT_GRAY); // Light Gray for Misc

        // Set line thickness
        plot.getRenderer().setSeriesStroke(0, new java.awt.BasicStroke(1.2f));
        plot.getRenderer().setSeriesStroke(1, new java.awt.BasicStroke(1.2f));
        plot.getRenderer().setSeriesStroke(2, new java.awt.BasicStroke(1.2f));
        plot.getRenderer().setSeriesStroke(3, new java.awt.BasicStroke(1.2f));
        plot.getRenderer().setSeriesStroke(4, new java.awt.BasicStroke(1.2f));
        plot.getRenderer().setSeriesStroke(5, new java.awt.BasicStroke(1.2f));
        plot.getRenderer().setSeriesStroke(6, new java.awt.BasicStroke(1.2f));
        plot.getRenderer().setSeriesStroke(7, new java.awt.BasicStroke(1.2f));
        plot.getRenderer().setSeriesStroke(8, new java.awt.BasicStroke(1.2f));
        plot.getRenderer().setSeriesStroke(9, new java.awt.BasicStroke(1.2f));

        // Hide axis tick labels (the numbers on the axes)
        plot.getDomainAxis().setTickLabelsVisible(false);
        plot.getRangeAxis().setTickLabelsVisible(false);

        // Second Y-axis for transaction count
        NumberAxis transactionAxis = new NumberAxis(null); // No label for the second axis
        transactionAxis.setTickLabelsVisible(false);
        plot.setRangeAxis(1, transactionAxis);
        plot.setDataset(1, transactionsPerBlockDataset);
        plot.mapDatasetToRangeAxis(1, 1);

        // Renderer for transaction bars
        XYBarRenderer transactionRenderer = new XYBarRenderer(0);
        transactionRenderer.setBarPainter(new StandardXYBarPainter());
        transactionRenderer.setShadowVisible(false);
        transactionRenderer.setSeriesPaint(0, new Color(255, 165, 0, 128)); // Orange, semi-transparent
        plot.setRenderer(1, transactionRenderer);

        // Remove all padding around the plot area
        plot.setInsets(new RectangleInsets(0, 0, 0, 0));
        plot.setAxisOffset(new RectangleInsets(0, 0, 0, 0));

        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(chartDimension1);
        chartPanel.setMinimumSize(chartDimension1);
        chartPanel.setMaximumSize(chartDimension1);
        return chartPanel;
    }

    private ChartPanel createNetSpeedChartPanel() {
        uploadSpeedSeries = new XYSeries("Upload Speed");
        downloadSpeedSeries = new XYSeries("Download Speed");
        uploadVolumeSeries = new XYSeries("Upload Volume");
        downloadVolumeSeries = new XYSeries("Download Volume");

        XYSeriesCollection lineDataset = new XYSeriesCollection();
        lineDataset.addSeries(uploadSpeedSeries);
        lineDataset.addSeries(downloadSpeedSeries);

        JFreeChart chart = ChartFactory.createXYLineChart(
                null, // No title
                null, // No X-axis label
                null, // No Y-axis label
                lineDataset);

        // Remove the legend to maximize plot area
        chart.removeLegend();
        chart.setBorderVisible(false);

        XYPlot plot = chart.getXYPlot();
        plot.getDomainAxis().setLowerMargin(0.0);
        plot.getDomainAxis().setUpperMargin(0.0);
        plot.setBackgroundPaint(Color.DARK_GRAY);
        plot.setDomainGridlinesVisible(false);
        plot.setRangeGridlinesVisible(false);

        plot.getRenderer().setSeriesPaint(0, new Color(128, 0, 0)); // Upload - Red, semi-transparent
        plot.getRenderer().setSeriesPaint(1, new Color(0, 100, 0)); // Download - Green, semi-transparent

        // Set line thickness
        plot.getRenderer().setSeriesStroke(0, new java.awt.BasicStroke(1.2f));
        plot.getRenderer().setSeriesStroke(1, new java.awt.BasicStroke(1.2f));

        // Hide axis tick labels (the numbers on the axes)
        plot.getDomainAxis().setTickLabelsVisible(false);
        plot.getRangeAxis().setTickLabelsVisible(false);

        // Second Y-axis for volume
        NumberAxis volumeAxis = new NumberAxis(null); // No label for the second axis
        volumeAxis.setTickLabelsVisible(false);
        plot.setRangeAxis(1, volumeAxis); // Use axis index 1 for volume

        // A single dataset and renderer for both volume series.
        XYSeriesCollection volumeDataset = new XYSeriesCollection();
        volumeDataset.addSeries(downloadVolumeSeries); // Series 0: Download (top layer)
        volumeDataset.addSeries(uploadVolumeSeries); // Series 1: Upload (bottom layer)

        XYStepAreaRenderer volumeRenderer = new XYStepAreaRenderer();
        volumeRenderer.setShapesVisible(false);
        volumeRenderer.setSeriesPaint(0, new Color(50, 205, 50, 128)); // Download - Green
        volumeRenderer.setSeriesPaint(1, new Color(233, 150, 122, 128)); // Upload - Red
        plot.setDataset(1, volumeDataset);
        plot.setRenderer(1, volumeRenderer);
        plot.mapDatasetToRangeAxis(1, 1);

        // Remove all padding around the plot area
        plot.setInsets(new RectangleInsets(0, 0, 0, 0));
        plot.setAxisOffset(new RectangleInsets(0, 0, 0, 0));

        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(chartDimension2);
        chartPanel.setMinimumSize(chartDimension2);
        chartPanel.setMaximumSize(chartDimension2);
        return chartPanel;
    }

    private void updatePerformanceChart(Block block) {

        blockTimestamps.add(System.currentTimeMillis());

        int totalTxCount = block.getTransactions().size();
        if (block.getBlockAts() != null) {
            try {
                totalTxCount += AtController.getATsFromBlock(block.getBlockAts()).size();
            } catch (Exception e) {
                LOGGER.warn("Could not parse ATs from block", e);
            }
        }
        transactionCounts.add(totalTxCount);

        while (blockTimestamps.size() > CHART_HISTORY_SIZE) {
            blockTimestamps.removeFirst();
            transactionCounts.removeFirst();
            if (!blocksPerSecondHistory.isEmpty()) {
                blocksPerSecondHistory.removeFirst();
            }
            if (!transactionsPerSecondHistory.isEmpty()) {
                transactionsPerSecondHistory.removeFirst();
            }
        }

        long timeSpanMs = blockTimestamps.getLast()
                - blockTimestamps.get(blockTimestamps.size() - Math.min(blockTimestamps.size(), movingAverageWindow));
        double blocksPerSecond = (timeSpanMs > 0)
                ? (double) Math.min(blockTimestamps.size(), movingAverageWindow) * 1000.0 / timeSpanMs
                : 0;
        blocksPerSecondHistory.add(blocksPerSecond);

        double avgTransactions = transactionCounts.stream()
                .skip(Math.max(0, transactionCounts.size() - Math.min(transactionCounts.size(), movingAverageWindow)))
                .mapToInt(Integer::intValue)
                .average().orElse(0.0);

        double transactionsPerSecond = avgTransactions * blocksPerSecond;
        transactionsPerSecondHistory.add(transactionsPerSecond);

        double maxBlocksPerSecond = blocksPerSecondHistory.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        int maxTransactionsPerBlock = transactionCounts.stream().mapToInt(Integer::intValue).max().orElse(0);
        double maxTransactionsPerSecond = transactionsPerSecondHistory.stream().mapToDouble(Double::doubleValue).max()
                .orElse(0.0);

        // Now, schedule only the UI updates on the EDT
        SwingUtilities.invokeLater(() -> {
            // Prune series on EDT before adding new data
            while (blocksPerSecondSeries.getItemCount() >= CHART_HISTORY_SIZE) {
                blocksPerSecondSeries.remove(0);
            }
            while (transactionsPerSecondSeries.getItemCount() >= CHART_HISTORY_SIZE) {
                transactionsPerSecondSeries.remove(0);
            }
            while (transactionsPerBlockSeries.getItemCount() >= CHART_HISTORY_SIZE) {
                transactionsPerBlockSeries.remove(0);
            }

            blocksPerSecondSeries.add(block.getHeight(), blocksPerSecond);
            transactionsPerBlockSeries.add(block.getHeight(), avgTransactions);
            blocksPerSecondProgressBar.setValue((int) (blocksPerSecond));
            blocksPerSecondProgressBar
                    .setString(String.format("%.2f - max: %.2f", blocksPerSecond, maxBlocksPerSecond));

            transactionsPerSecondSeries.add(block.getHeight(), transactionsPerSecond);
            transactionsPerSecondProgressBar.setValue((int) transactionsPerSecond);
            transactionsPerSecondProgressBar
                    .setString(String.format("%.2f - max: %.2f", transactionsPerSecond, maxTransactionsPerSecond));

            transactionsPerBlockProgressBar.setValue((int) avgTransactions);
            transactionsPerBlockProgressBar
                    .setString(String.format("%.2f - max: %d", avgTransactions, maxTransactionsPerBlock));
        });
    }
}