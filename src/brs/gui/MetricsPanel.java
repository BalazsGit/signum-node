package brs.gui;

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
import java.awt.event.*;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import brs.Signum;
import brs.BlockchainProcessor;
import brs.Block;
import brs.at.AtController;
import brs.props.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ForkJoinPool;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.util.HashMap;

@SuppressWarnings("serial")
public class MetricsPanel extends JPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricsPanel.class);
    private static final int CHART_HISTORY_SIZE = 1000;
    private static final int SPEED_HISTORY_SIZE = 1000;
    private static final int MAX_SPEED_BPS = 10 * 1024 * 1024; // 10 MB/s

    private int movingAverageWindow = 100;
    private final MovingAverage blockTimestamps = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage transactionCounts = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage systemTransactionCounts = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage pushTimes = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage validationTimes = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage txLoopTimes = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage housekeepingTimes = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage txApplyTimes = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage commitTimes = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage atTimes = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage subscriptionTimes = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage blockApplyTimes = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage miscTimes = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage atCounts = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);

    private XYSeries blocksPerSecondSeries;
    private XYSeries transactionsPerSecondSeries;
    private XYSeries transactionsPerBlockSeries;
    private XYSeries atTransactionsPerBlockSeries;
    private XYSeries systemTransactionsPerSecondSeries;
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
    private XYSeries atCountPerBlockSeries;

    private JProgressBar blocksPerSecondProgressBar;
    private JProgressBar transactionsPerSecondProgressBar;
    private JProgressBar transactionsPerBlockProgressBar;
    private int oclUnverifiedQueueThreshold;
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
    private JLabel atCountLabel;
    private JProgressBar atCountProgressBar;
    private JLabel systemTxPerBlockLabel;
    private JProgressBar atTransactionsPerBlockProgressBar;
    private JLabel systemTxPerSecondLabel;
    private JProgressBar systemTransactionsPerSecondProgressBar;
    private JProgressBar uploadSpeedProgressBar;
    private JProgressBar downloadSpeedProgressBar;

    private ChartPanel performanceChartPanel;
    private ChartPanel timingChartPanel;
    private ChartPanel netSpeedChartPanel;
    private Timer netSpeedChartUpdater;
    private final MovingAverage uploadSpeedHistory = new MovingAverage(SPEED_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage downloadSpeedHistory = new MovingAverage(SPEED_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage downloadSpeedMAHistory = new MovingAverage(SPEED_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage uploadSpeedMAHistory = new MovingAverage(SPEED_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage blocksPerSecondHistory = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage transactionsPerSecondHistory = new MovingAverage(CHART_HISTORY_SIZE,
            movingAverageWindow);
    private final MovingAverage systemTransactionsPerSecondHistory = new MovingAverage(CHART_HISTORY_SIZE,
            movingAverageWindow);
    private final MovingAverage atTransactionsPerBlockHistory = new MovingAverage(CHART_HISTORY_SIZE,
            movingAverageWindow);
    private final MovingAverage atCountHistory = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage transactionsPerBlockHistory = new MovingAverage(CHART_HISTORY_SIZE,
            movingAverageWindow);

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

    // Data Transfer Objects for UI updates
    private static class TimingUpdateData {
        Map<JProgressBar, Runnable> progressBarUpdates = new HashMap<>();
        Map<XYSeries, Point.Double> seriesUpdates = new HashMap<>();
    }

    private static class PerformanceUpdateData {
        Map<JProgressBar, Runnable> progressBarUpdates = new HashMap<>();
        Map<XYSeries, Point.Double> seriesUpdates = new HashMap<>();
    }

    private JProgressBar syncProgressBarUnverifiedBlocks;
    private final JFrame parentFrame;

    public MetricsPanel(JFrame parentFrame) {
        super(new GridBagLayout());
        this.parentFrame = parentFrame;
        atCountPerBlockSeries = new XYSeries("ATs/Block (MA)");
        transactionsPerBlockSeries = new XYSeries("All Txs/Block (MA)"); // Orange
        atTransactionsPerBlockSeries = new XYSeries("System Txs/Block (MA)"); // Blue
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
        tooltip = "Shows the number of blocks in the download queue that have passed PoC verification against the total number of blocks in the queue.\n\n- Verified: PoC signature has been checked (CPU/GPU intensive).\n- Total: All blocks currently in the download queue.\n\nA high number of unverified blocks may indicate a slow verification process.\n\nThe progress bar displays: Verified Blocks / Total Blocks - Percentage of Verified Blocks.";
        JLabel verifLabel = createLabel("Verified/Total Blocks", null, tooltip);
        syncProgressBarDownloadedBlocks = createProgressBar(0, 100, Color.GREEN, "0 / 0 - 0%", progressBarSize1);
        addComponent(SyncPanel, verifLabel, 0, 0, 1, 0, 0, GridBagConstraints.LINE_END, GridBagConstraints.NONE,
                labelInsets);
        addComponent(SyncPanel, syncProgressBarDownloadedBlocks, 1, 0, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Unverified Blocks
        tooltip = "The number of blocks in the download queue that are waiting for Proof-of-Capacity (PoC) verification.\n\nA persistently high number might indicate that the CPU or GPU is a bottleneck and cannot keep up with the network's block generation rate.\n\nThe progress bar displays the current count of unverified blocks.";
        JLabel unVerifLabel = createLabel("Unverified Blocks", null, tooltip);
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
        tooltip = "The moving average of blocks processed per second. This is a key indicator of the node's synchronization speed.\n\nA higher value means the node is rapidly catching up with the current state of the blockchain. This metric is particularly useful during the initial sync or after a period of being offline.\n\nThe progress bar displays: Current MA Value - Max MA Value seen in this session.";
        JLabel blocksPerSecondLabel = createLabel("Blocks/Sec (MA)", Color.CYAN, tooltip);
        blocksPerSecondProgressBar = createProgressBar(0, 200, null, "0.00 - max: 0.00", progressBarSize1);
        addComponent(SyncPanel, blocksPerSecondLabel, 0, 3, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(SyncPanel, blocksPerSecondProgressBar, 1, 3, 1, 0, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // All Transactions/Second (Moving Average)
        tooltip = "The moving average of the total number of transactions (user-submitted and AT-generated) processed per second. This metric reflects the total transactional throughput of the network as seen by your node.\n\nIncludes:\n- Payments (Ordinary, Multi-Out, Multi-Same-Out)\n- Messages (Arbitrary, Alias, Account Info, TLD)\n- Assets (Issuance, Transfer, Orders, Minting, Distribution)\n- Digital Goods (Listing, Delisting, Price Change, Quantity Change, Purchase, Delivery, Feedback, Refund)\n- Account Control (Leasing)\n- Mining (Reward Recipient, Commitment)\n- Advanced Payments (Escrow, Subscriptions)\n- Automated Transactions (ATs)\n\nThe progress bar displays: Current MA Value - Max MA Value seen in this session.";
        JLabel txPerSecondLabel = createLabel("All Txs/Sec (MA)", Color.GREEN, tooltip);
        transactionsPerSecondProgressBar = createProgressBar(0, 2000, null, "0.00 - max: 0.00", progressBarSize1);
        addComponent(SyncPanel, txPerSecondLabel, 0, 4, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(SyncPanel, transactionsPerSecondProgressBar, 1, 4, 1, 0, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // All Transactions/Block (Moving Average)
        tooltip = "The moving average of the total number of transactions (user-submitted and AT-generated) included in each block. This metric provides insight into the network's activity and block space utilization.\n\nIncludes:\n- Payments (Ordinary, Multi-Out, Multi-Same-Out)\n- Messages (Arbitrary, Alias, Account Info, TLD)\n- Assets (Issuance, Transfer, Orders, Minting, Distribution)\n- Digital Goods (Listing, Delisting, Price Change, Quantity Change, Purchase, Delivery, Feedback, Refund)\n- Account Control (Leasing)\n- Mining (Reward Recipient, Commitment)\n- Advanced Payments (Escrow, Subscriptions)\n- Automated Transactions (ATs)\n\nThe progress bar displays: Current MA Value - Max MA Value seen in this session.";
        JLabel txPerBlockLabel = createLabel("All Txs/Block (MA)", new Color(255, 165, 0), tooltip); // Orange
        transactionsPerBlockProgressBar = createProgressBar(0, 255, null, "0.00 - max: 0.00", progressBarSize1);
        addComponent(SyncPanel, txPerBlockLabel, 0, 5, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(SyncPanel, transactionsPerBlockProgressBar, 1, 5, 1, 0, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // System Transactions/Second (Moving Average)
        tooltip = "The moving average of system-generated transactions processed per second. This includes payments from Automated Transactions (ATs), Escrow results, and Subscription payments.\n\nThe progress bar displays: Current MA Value - Max MA Value seen in this session.";
        systemTxPerSecondLabel = createLabel("System Txs/Sec (MA)", new Color(135, 206, 250), tooltip); // LightSkyBlue
        systemTransactionsPerSecondProgressBar = createProgressBar(0, 2000, null, "0.00 - max: 0.00", progressBarSize1);
        addComponent(SyncPanel, systemTxPerSecondLabel, 0, 6, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(SyncPanel, systemTransactionsPerSecondProgressBar, 1, 6, 1, 0, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // System Transactions/Block (Moving Average)
        tooltip = "The moving average of system-generated transactions included in each block. This includes payments from Automated Transactions (ATs), Escrow results, and Subscription payments.\n\nThe progress bar displays: Current MA Value - Max MA Value seen in this session.";
        systemTxPerBlockLabel = createLabel("System Txs/Block (MA)", Color.BLUE, tooltip);
        atTransactionsPerBlockProgressBar = createProgressBar(0, 255, null, "0.00 - max: 0.00", progressBarSize1);
        addComponent(SyncPanel, systemTxPerBlockLabel, 0, 7, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(SyncPanel, atTransactionsPerBlockProgressBar, 1, 7, 1, 0, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // ATs/Block (Moving Average)
        tooltip = "The moving average of the number of Automated Transactions (ATs) executed per block. This metric shows the activity level of smart contracts on the network.\n\nThe progress bar displays: Current MA Value - Max MA Value seen in this session.";
        atCountLabel = createLabel("ATs/Block (MA)", new Color(153, 0, 76), tooltip); // Deep Pink
        atCountProgressBar = createProgressBar(0, 100, null, "0.00 - max: 0.00", progressBarSize1);
        addComponent(SyncPanel, atCountLabel, 0, 8, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(SyncPanel, atCountProgressBar, 1, 8, 1, 0, 0, GridBagConstraints.LINE_START,
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
        addToggleListener(systemTxPerSecondLabel, performanceChartPanel, 0, 2);
        addToggleListener(atCountLabel, performanceChartPanel, 0, 3);
        addDualChartToggleListener(txPerBlockLabel, performanceChartPanel, 1, 1, timingChartPanel, 1, 1);

        // End Performance Metrics Panel

        // === Timing Metrics Panel ===
        JPanel timingMetricsPanel = new JPanel(new GridBagLayout());

        JPanel timingInfoPanel = new JPanel(new GridBagLayout());
        int y = 0;

        Insets timerLabelInsets = new Insets(2, 5, 0, 0);
        Insets timerBarInsets = new Insets(0, 5, 2, 5);

        // --- Row 1: Push Time / Validation Time ---
        // Push Time (Left)
        tooltip = "The moving average of the total time taken to process and push a new block. This value is the sum of all individual timing components measured during block processing.\n\nIt includes:\n- Validation Time\n- TX Loop Time\n- Housekeeping Time\n- TX Apply Time\n- AT Time\n- Subscription Time\n- Block Apply Time\n- Commit Time\n- Complementer (miscellaneous) Time\n\nThe progress bar displays: Current MA Value - Max MA Value seen in this session.";
        pushTimeLabel = createLabel("Push Time (MA)", Color.BLUE, tooltip);
        pushTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms", progressBarSize1);
        addComponent(timingInfoPanel, pushTimeLabel, 0, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, pushTimeProgressBar, 0, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);

        // Validation Time (Right)
        tooltip = "The moving average of the time spent on block-level validation, excluding the per-transaction validation loop. This is a CPU-intensive task.\n\nMeasured steps include:\n- Verifying block version and timestamp\n- Checking previous block reference\n- Verifying block and generation signatures\n- Validating payload hash and total amounts/fees after transaction processing\n\nThe progress bar displays: Current MA Value - Max MA Value seen in this session.";
        validationTimeLabel = createLabel("Validation Time (MA)", Color.YELLOW, tooltip);
        validationTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms", progressBarSize1);
        addComponent(timingInfoPanel, validationTimeLabel, 2, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, validationTimeProgressBar, 2, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);
        y += 2;

        // --- Row 2: TX Loop / Housekeeping ---
        // TX Loop Time (Left)
        tooltip = "The moving average of the time spent iterating through and validating all transactions within a block. This involves both CPU and database read operations.\n\nFor each transaction, this includes:\n- Checking timestamps and deadlines\n- Verifying signatures and public keys\n- Validating referenced transactions\n- Checking for duplicates\n- Executing transaction-specific business logic\n\nThe progress bar displays: Current MA Value - Max MA Value seen in this session.";
        txLoopTimeLabel = createLabel("TX Loop Time (MA)", new Color(128, 0, 128), tooltip);
        txLoopTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms", progressBarSize1);
        addComponent(timingInfoPanel, txLoopTimeLabel, 0, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, txLoopTimeProgressBar, 0, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);

        // Housekeeping Time (Right)
        tooltip = "The moving average of the time spent on various 'housekeeping' tasks during block processing.\n\nThis includes:\n- Re-queuing unconfirmed transactions that were not included in the new block\n- Updating peer states and other miscellaneous tasks\n\nThe progress bar displays: Current MA Value - Max MA Value seen in this session.";
        housekeepingTimeLabel = createLabel("Housekeeping Time (MA)", new Color(42, 223, 223), tooltip);
        housekeepingTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms", progressBarSize1);
        addComponent(timingInfoPanel, housekeepingTimeLabel, 2, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, housekeepingTimeProgressBar, 2, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);
        y += 2;

        // --- Row 3: TX Apply / AT Time ---
        // TX Apply Time (Left)
        tooltip = "The moving average of the time spent applying the effects of each transaction within the block to the in-memory state. This step handles changes to account balances, aliases, assets, etc., based on the transaction type. It is the first major operation within the 'apply' phase.\n\nThe progress bar displays: Current MA Value - Max MA Value seen in this session.";
        txApplyTimeLabel = createLabel("TX Apply Time (MA)", new Color(255, 165, 0), tooltip);
        txApplyTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms", progressBarSize1);
        addComponent(timingInfoPanel, txApplyTimeLabel, 0, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, txApplyTimeProgressBar, 0, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);

        // AT Time (Right)
        tooltip = "The moving average of the time spent validating and processing all Automated Transactions (ATs) within the block. This is a separate computational step that occurs after 'TX Apply Time'.\n\nThe progress bar displays: Current MA Value - Max MA Value seen in this session.";
        atTimeLabel = createLabel("AT Time (MA)", new Color(153, 0, 76), tooltip);
        atTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms", progressBarSize1);
        addComponent(timingInfoPanel, atTimeLabel, 2, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, atTimeProgressBar, 2, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);
        y += 2;

        // --- Row 4: Subscription / Block Apply ---
        // Subscription Time (Left)
        tooltip = "The moving average of the time spent processing recurring subscription payments for the block. This is a separate step that occurs after AT processing.\n\nThe progress bar displays: Current MA Value - Max MA Value seen in this session.";
        subscriptionTimeLabel = createLabel("Subscription Time (MA)", new Color(255, 105, 100), tooltip); // Hot pink
        subscriptionTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms", progressBarSize1);
        addComponent(timingInfoPanel, subscriptionTimeLabel, 0, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, subscriptionTimeProgressBar, 0, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);

        // Block Apply Time (Right)
        tooltip = "The moving average of the time spent applying block-level changes. This includes distributing the block reward to the generator, updating escrow services, and notifying listeners about the applied block. This is the final step before the 'Commit' phase.\n\nThe progress bar displays: Current MA Value - Max MA Value seen in this session.";
        blockApplyTimeLabel = createLabel("Block Apply Time (MA)", new Color(0, 100, 100), tooltip); // Teal
        blockApplyTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms", progressBarSize1);
        addComponent(timingInfoPanel, blockApplyTimeLabel, 2, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, blockApplyTimeProgressBar, 2, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);
        y += 2;

        // --- Row 5: Commit / Misc. Time ---
        // Commit Time (Left)
        tooltip = "The moving average of the time spent committing all in-memory state changes to the database on disk. This is a disk I/O-intensive operation.\n\nThe progress bar displays: Current MA Value - Max MA Value seen in this session.";
        commitTimeLabel = createLabel("Commit Time (MA)", new Color(150, 0, 200), tooltip);
        commitTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms", progressBarSize1);
        addComponent(timingInfoPanel, commitTimeLabel, 0, y, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(timingInfoPanel, commitTimeProgressBar, 0, y + 1, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, timerBarInsets);

        // Misc. Time (Right)
        tooltip = "The moving average of the time spent on miscellaneous operations not explicitly measured in other timing categories. This value is the difference between the 'Total Push Time' and the sum of all other measured components (Validation, TX Loop, Housekeeping, TX Apply, AT, Subscription, Block Apply, and Commit). A consistently high value may indicate performance overhead in parts of the code that are not individually timed, such as memory management or other background tasks.\n\nThe progress bar displays: Current MA Value - Max MA Value seen in this session.";
        miscTimeLabel = createLabel("Misc. Time (MA)", Color.LIGHT_GRAY, tooltip);
        miscTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms", progressBarSize1);
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
        JPanel uploadSpeedPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        uploadSpeedPanel.setOpaque(false);
        tooltip = "The moving average of data upload speed to other peers in the network.\n\nThis reflects how much data your node is sharing, which includes:\n- Blocks\n- Transactions\n- Peer information\n\nThe progress bar displays the current moving average speed.";
        uploadSpeedLabel = createLabel("▲ Speed (MA)", new Color(128, 0, 0), tooltip);
        uploadSpeedProgressBar = createProgressBar(0, MAX_SPEED_BPS, null, "0.00 B/s", progressBarSize2);
        uploadSpeedPanel.add(uploadSpeedLabel);
        uploadSpeedPanel.add(uploadSpeedProgressBar);
        addComponent(netSpeedInfoPanel, uploadSpeedPanel, 0, 1, 2, 0, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, labelInsets);

        /*
         * addComponent(netSpeedInfoPanel, uploadSpeedLabel, 0, 1, 1, 0, 0,
         * GridBagConstraints.LINE_END,
         * GridBagConstraints.NONE, labelInsets);
         * addComponent(netSpeedInfoPanel, uploadSpeedProgressBar, 1, 1, 1, 0, 0,
         * GridBagConstraints.LINE_START,
         * GridBagConstraints.HORIZONTAL, barInsets);
         */

        // --- Download Speed ---
        JPanel downloadSpeePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        downloadSpeePanel.setOpaque(false);
        tooltip = "The moving average of data download speed from other peers in the network.\n\nThis indicates how quickly your node is receiving data, which includes:\n- Blocks\n- Transactions\n- Peer information\n\nThe progress bar displays the current moving average speed.";
        downloadSpeedLabel = createLabel("▼ Speed (MA)", new Color(0, 100, 0), tooltip);
        downloadSpeedProgressBar = createProgressBar(0, MAX_SPEED_BPS, null, "0.00 B/s", progressBarSize2);
        downloadSpeePanel.add(downloadSpeedLabel);
        downloadSpeePanel.add(downloadSpeedProgressBar);
        addComponent(netSpeedInfoPanel, downloadSpeePanel, 0, 2, 2, 0, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, labelInsets);

        /*
         * addComponent(netSpeedInfoPanel, downloadSpeedLabel, 0, 2, 1, 0, 0,
         * GridBagConstraints.LINE_END,
         * GridBagConstraints.NONE, labelInsets);
         * addComponent(netSpeedInfoPanel, downloadSpeedProgressBar, 1, 2, 1, 0, 0,
         * GridBagConstraints.LINE_START,
         * GridBagConstraints.HORIZONTAL, barInsets);
         */

        // --- Combined Volume ---
        JPanel combinedVolumePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        combinedVolumePanel.setOpaque(false);
        tooltip = "The total amount of data uploaded to and downloaded from the network during this session. The format is Uploaded / Downloaded.";
        JLabel volumeTitleLabel = createLabel("Volume", null, tooltip);
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

        // --- Moving Average Window ---
        JPanel maWindowPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        maWindowPanel.setOpaque(false);
        tooltip = "The number of recent blocks used to calculate the moving average for performance metrics. A larger window provides a smoother but less responsive trend, while a smaller window is more reactive to recent changes.";
        JLabel maWindowLabel = createLabel("MA Window (Blocks)", null, tooltip);

        // Define the discrete values for the dropdown
        final Integer[] maWindowValues = { 10, 100, 200, 300, 400, 500 };
        JComboBox<Integer> movingAverageComboBox = new JComboBox<>(maWindowValues);
        movingAverageComboBox.setSelectedItem(movingAverageWindow);
        movingAverageComboBox.setPreferredSize(progressBarSize2);

        movingAverageComboBox.addActionListener(e -> {
            JComboBox<?> source = (JComboBox<?>) e.getSource();
            Object selectedItem = source.getSelectedItem();
            if (selectedItem instanceof Integer) {
                movingAverageWindow = (Integer) selectedItem;
                blockTimestamps.setWindowSize(movingAverageWindow);
                transactionCounts.setWindowSize(movingAverageWindow);
                systemTransactionCounts.setWindowSize(movingAverageWindow);
                pushTimes.setWindowSize(movingAverageWindow);
                validationTimes.setWindowSize(movingAverageWindow);
                txLoopTimes.setWindowSize(movingAverageWindow);
                housekeepingTimes.setWindowSize(movingAverageWindow);
                txApplyTimes.setWindowSize(movingAverageWindow);
                commitTimes.setWindowSize(movingAverageWindow);
                atTimes.setWindowSize(movingAverageWindow);
                subscriptionTimes.setWindowSize(movingAverageWindow);
                blockApplyTimes.setWindowSize(movingAverageWindow);
                miscTimes.setWindowSize(movingAverageWindow);
                atCounts.setWindowSize(movingAverageWindow);
            }
        });

        maWindowPanel.add(maWindowLabel);
        maWindowPanel.add(movingAverageComboBox);
        addComponent(netSpeedInfoPanel, maWindowPanel, 0, 4, 2, 0, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, new Insets(2, 0, 2, 0));

        // addComponent(netSpeedInfoPanel, maWindowLabel, 0, 4, 1, 0, 0,
        // GridBagConstraints.LINE_END,
        // GridBagConstraints.NONE, labelInsets);

        // addComponent(netSpeedInfoPanel, movingAverageComboBox, 1, 4, 1, 0, 0,
        // GridBagConstraints.LINE_START,
        // GridBagConstraints.HORIZONTAL, barInsets);

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
        addDualChartToggleListener(systemTxPerBlockLabel, performanceChartPanel, 1, 0, timingChartPanel, 1, 0);
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
        netSpeedChartUpdater = new Timer(100, e -> {
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
        }
    }

    public void shutdown() {
        chartUpdateExecutor.shutdown();
        if (netSpeedChartUpdater != null) {
            netSpeedChartUpdater.stop();
        }
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
            chartUpdateExecutor.submit(() -> updateAllCharts(stats));
        }
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
            downloadSpeedHistory.add(currentDownloadSpeed);

            int currentWindowSize = Math.min(uploadSpeedHistory.size(), movingAverageWindow);
            if (currentWindowSize < 1) {
                return;
            }

            double avgUploadSpeed = uploadSpeedHistory.getAverage();
            double avgDownloadSpeed = downloadSpeedHistory.getAverage();

            lastNetVolumeUpdateTime = currentTime;
            lastUploadedVolume = uploadedVolume;
            lastDownloadedVolume = downloadedVolume;

            // --- UI Updates on EDT ---
            SwingUtilities.invokeLater(() -> updateNetSpeedUI(currentTime, uploadedVolume, downloadedVolume,
                    avgUploadSpeed, avgDownloadSpeed));
        });
    }

    private void updateNetSpeedUI(long currentTime, long uploadedVolume, long downloadedVolume, double avgUploadSpeed,
            double avgDownloadSpeed) {
        if (metricsUploadVolumeLabel != null) {
            metricsUploadVolumeLabel.setText("▲ " + formatDataSize(uploadedVolume));
        }
        if (metricsDownloadVolumeLabel != null) {
            metricsDownloadVolumeLabel.setText("▼ " + formatDataSize(downloadedVolume));
        }

        uploadSpeedMAHistory.add(avgUploadSpeed);
        updateProgressBar(uploadSpeedProgressBar, avgUploadSpeed, uploadSpeedMAHistory.getMax(), this::formatDataRate);

        downloadSpeedMAHistory.add(avgDownloadSpeed);
        updateProgressBar(downloadSpeedProgressBar, avgDownloadSpeed, downloadSpeedMAHistory.getMax(),
                this::formatDataRate);

        if (uploadedVolume > 0 || downloadedVolume > 0) {
            updateChartSeries(uploadSpeedSeries, currentTime, avgUploadSpeed, SPEED_HISTORY_SIZE);
            updateChartSeries(downloadSpeedSeries, currentTime, avgDownloadSpeed, SPEED_HISTORY_SIZE);
            updateChartSeries(uploadVolumeSeries, currentTime, uploadedVolume, SPEED_HISTORY_SIZE);
            updateChartSeries(downloadVolumeSeries, currentTime, downloadedVolume, SPEED_HISTORY_SIZE);
        }
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

    private void updateAllCharts(BlockchainProcessor.PerformanceStats stats) {
        // Run independent calculations in parallel using CompletableFuture
        CompletableFuture<TimingUpdateData> timingFuture = CompletableFuture
                .supplyAsync(() -> calculateTimingUpdate(stats), ForkJoinPool.commonPool());
        CompletableFuture<PerformanceUpdateData> performanceFuture = CompletableFuture
                .supplyAsync(() -> calculatePerformanceUpdate(stats.block), ForkJoinPool.commonPool());

        // Wait for both to complete and then update the UI in a single batch on the EDT
        CompletableFuture.allOf(timingFuture, performanceFuture).thenRun(() -> {
            try {
                TimingUpdateData timingData = timingFuture.get();
                PerformanceUpdateData performanceData = performanceFuture.get();

                // Schedule a single UI update on the EDT
                SwingUtilities.invokeLater(() -> {
                    // Apply timing updates
                    timingData.progressBarUpdates.values().forEach(Runnable::run);
                    timingData.seriesUpdates.forEach(
                            (series, point) -> updateChartSeries(series, point.x, point.y, CHART_HISTORY_SIZE));

                    // Apply performance updates
                    performanceData.progressBarUpdates.values().forEach(Runnable::run);
                    performanceData.seriesUpdates.forEach(
                            (series, point) -> updateChartSeries(series, point.x, point.y, CHART_HISTORY_SIZE));
                });
            } catch (Exception e) {
                LOGGER.error("Error updating charts in parallel", e);
            }
        });
    }

    private TimingUpdateData calculateTimingUpdate(BlockchainProcessor.PerformanceStats stats) {
        Block block = stats.block;
        TimingUpdateData data = new TimingUpdateData();
        if (block == null)
            return data;

        int blockHeight = block.getHeight();

        pushTimes.add(stats.totalTimeMs);
        validationTimes.add(stats.validationTimeMs);
        txLoopTimes.add(stats.txLoopTimeMs);
        housekeepingTimes.add(stats.housekeepingTimeMs);
        txApplyTimes.add(stats.txApplyTimeMs);
        commitTimes.add(stats.commitTimeMs);
        atTimes.add(stats.atTimeMs);
        subscriptionTimes.add(stats.subscriptionTimeMs);
        blockApplyTimes.add(stats.blockApplyTimeMs);
        miscTimes.add(stats.miscTimeMs);

        double displayPushTime = pushTimes.getAverage();
        double displayValidationTime = validationTimes.getAverage();
        double displayTxLoopTime = txLoopTimes.getAverage();
        double displayHousekeepingTime = housekeepingTimes.getAverage();
        double displayCommitTime = commitTimes.getAverage();
        double displayAtTime = atTimes.getAverage();
        double displayTxApplyTime = txApplyTimes.getAverage();
        double displaySubscriptionTime = subscriptionTimes.getAverage();
        double displayBlockApplyTime = blockApplyTimes.getAverage();
        double displayMiscTime = miscTimes.getAverage();

        // Round values to the nearest integer for consistent display
        long roundedPushTime = Math.round(displayPushTime);
        long roundedPushTimeMax = Math.round(pushTimes.getMax());
        long roundedValidationTime = Math.round(displayValidationTime);
        long roundedValidationTimeMax = Math.round(validationTimes.getMax());
        long roundedTxLoopTime = Math.round(displayTxLoopTime);
        long roundedTxLoopTimeMax = Math.round(txLoopTimes.getMax());
        long roundedHousekeepingTime = Math.round(displayHousekeepingTime);
        long roundedHousekeepingTimeMax = Math.round(housekeepingTimes.getMax());
        long roundedCommitTime = Math.round(displayCommitTime);
        long roundedCommitTimeMax = Math.round(commitTimes.getMax());
        long roundedAtTime = Math.round(displayAtTime);
        long roundedAtTimeMax = Math.round(atTimes.getMax());
        long roundedTxApplyTime = Math.round(displayTxApplyTime);
        long roundedTxApplyTimeMax = Math.round(txApplyTimes.getMax());
        long roundedSubscriptionTime = Math.round(displaySubscriptionTime);
        long roundedSubscriptionTimeMax = Math.round(subscriptionTimes.getMax());
        long roundedBlockApplyTime = Math.round(displayBlockApplyTime);
        long roundedBlockApplyTimeMax = Math.round(blockApplyTimes.getMax());
        long roundedMiscTime = Math.round(displayMiscTime);
        long roundedMiscTimeMax = Math.round(miscTimes.getMax());

        // Prepare progress bar updates
        data.progressBarUpdates.put(pushTimeProgressBar, () -> updateProgressBar(pushTimeProgressBar, roundedPushTime,
                roundedPushTimeMax, val -> String.format("%.0f ms - max: %.0f ms", val, pushTimes.getMax())));
        data.progressBarUpdates.put(validationTimeProgressBar,
                () -> updateProgressBar(validationTimeProgressBar, roundedValidationTime, roundedValidationTimeMax,
                        val -> String.format("%.0f ms - max: %.0f ms", val, validationTimes.getMax())));
        data.progressBarUpdates.put(txLoopTimeProgressBar,
                () -> updateProgressBar(txLoopTimeProgressBar, roundedTxLoopTime, roundedTxLoopTimeMax,
                        val -> String.format("%.0f ms - max: %.0f ms", val, txLoopTimes.getMax())));
        data.progressBarUpdates.put(housekeepingTimeProgressBar,
                () -> updateProgressBar(housekeepingTimeProgressBar, roundedHousekeepingTime,
                        roundedHousekeepingTimeMax,
                        val -> String.format("%.0f ms - max: %.0f ms", val, housekeepingTimes.getMax())));
        data.progressBarUpdates.put(commitTimeProgressBar,
                () -> updateProgressBar(commitTimeProgressBar, roundedCommitTime, roundedCommitTimeMax,
                        val -> String.format("%.0f ms - max: %.0f ms", val, commitTimes.getMax())));
        data.progressBarUpdates.put(atTimeProgressBar, () -> updateProgressBar(atTimeProgressBar, roundedAtTime,
                roundedAtTimeMax, val -> String.format("%.0f ms - max: %.0f ms", val, atTimes.getMax())));
        data.progressBarUpdates.put(txApplyTimeProgressBar,
                () -> updateProgressBar(txApplyTimeProgressBar, roundedTxApplyTime, roundedTxApplyTimeMax,
                        val -> String.format("%.0f ms - max: %.0f ms", val, txApplyTimes.getMax())));
        data.progressBarUpdates.put(subscriptionTimeProgressBar,
                () -> updateProgressBar(subscriptionTimeProgressBar, roundedSubscriptionTime,
                        roundedSubscriptionTimeMax,
                        val -> String.format("%.0f ms - max: %.0f ms", val, subscriptionTimes.getMax())));
        data.progressBarUpdates.put(blockApplyTimeProgressBar,
                () -> updateProgressBar(blockApplyTimeProgressBar, roundedBlockApplyTime, roundedBlockApplyTimeMax,
                        val -> String.format("%.0f ms - max: %.0f ms", val, blockApplyTimes.getMax())));
        data.progressBarUpdates.put(miscTimeProgressBar, () -> updateProgressBar(miscTimeProgressBar, roundedMiscTime,
                roundedMiscTimeMax, val -> String.format("%.0f ms - max: %.0f ms", val, miscTimes.getMax())));

        // Prepare chart series updates
        data.seriesUpdates.put(pushTimePerBlockSeries, new Point.Double(blockHeight, displayPushTime));
        data.seriesUpdates.put(validationTimePerBlockSeries, new Point.Double(blockHeight, displayValidationTime));
        data.seriesUpdates.put(txLoopTimePerBlockSeries, new Point.Double(blockHeight, displayTxLoopTime));
        data.seriesUpdates.put(housekeepingTimePerBlockSeries, new Point.Double(blockHeight, displayHousekeepingTime));
        data.seriesUpdates.put(commitTimePerBlockSeries, new Point.Double(blockHeight, displayCommitTime));
        data.seriesUpdates.put(atTimePerBlockSeries, new Point.Double(blockHeight, displayAtTime));
        data.seriesUpdates.put(txApplyTimePerBlockSeries, new Point.Double(blockHeight, displayTxApplyTime));
        data.seriesUpdates.put(subscriptionTimePerBlockSeries, new Point.Double(blockHeight, displaySubscriptionTime));
        data.seriesUpdates.put(miscTimePerBlockSeries, new Point.Double(blockHeight, displayMiscTime));
        data.seriesUpdates.put(blockApplyTimePerBlockSeries, new Point.Double(blockHeight, displayBlockApplyTime));

        return data;
    }

    private ChartPanel createPerformanceChartPanel() {
        blocksPerSecondSeries = new XYSeries("Blocks/Second (MA)");
        transactionsPerSecondSeries = new XYSeries("All Txs/Sec (MA)");
        systemTransactionsPerSecondSeries = new XYSeries("System Txs/Sec (MA)");

        XYSeriesCollection lineDataset = new XYSeriesCollection();
        lineDataset.addSeries(blocksPerSecondSeries);
        lineDataset.addSeries(transactionsPerSecondSeries);
        lineDataset.addSeries(systemTransactionsPerSecondSeries);

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
        plot.getRenderer().setSeriesPaint(2, new Color(135, 206, 250));

        // Set line thickness
        plot.getRenderer().setSeriesStroke(0, new java.awt.BasicStroke(1.2f));
        plot.getRenderer().setSeriesStroke(1, new java.awt.BasicStroke(1.2f));
        plot.getRenderer().setSeriesStroke(2, new java.awt.BasicStroke(1.2f));

        // Hide axis tick labels (the numbers on the axes)
        plot.getDomainAxis().setTickLabelsVisible(false);
        plot.getRangeAxis().setTickLabelsVisible(false);

        // Second Y-axis for transaction count
        NumberAxis transactionAxis = new NumberAxis(null);
        transactionAxis.setTickLabelsVisible(false);
        XYSeriesCollection barDataset = new XYSeriesCollection();
        barDataset.addSeries(atTransactionsPerBlockSeries);
        barDataset.addSeries(transactionsPerBlockSeries);
        plot.setRangeAxis(1, transactionAxis);
        plot.setDataset(1, barDataset);
        plot.mapDatasetToRangeAxis(1, 1);

        // Renderer for transaction bars
        XYBarRenderer transactionRenderer = new XYBarRenderer(0);
        transactionRenderer.setBarPainter(new StandardXYBarPainter());
        transactionRenderer.setShadowVisible(false);
        transactionRenderer.setSeriesPaint(0, new Color(0, 0, 255, 128)); // System Txs/Block
        transactionRenderer.setSeriesPaint(1, new Color(255, 165, 0, 128)); // All Txs/Block
        plot.setRenderer(1, transactionRenderer);

        lineDataset.addSeries(atCountPerBlockSeries);
        plot.getRenderer(0).setSeriesPaint(3, new Color(153, 0, 76));

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
        pushTimePerBlockSeries = new XYSeries("Push Time (MA)");
        validationTimePerBlockSeries = new XYSeries("Validation Time (MA)");
        txLoopTimePerBlockSeries = new XYSeries("TX Loop Time (MA)");
        housekeepingTimePerBlockSeries = new XYSeries("Housekeeping Time (MA)");
        txApplyTimePerBlockSeries = new XYSeries("TX Apply Time (MA)");
        atTimePerBlockSeries = new XYSeries("AT Time (MA)");
        subscriptionTimePerBlockSeries = new XYSeries("Subscription Time (MA)");
        blockApplyTimePerBlockSeries = new XYSeries("Block Apply Time (MA)");
        commitTimePerBlockSeries = new XYSeries("Commit Time (MA)");
        miscTimePerBlockSeries = new XYSeries("Misc. Time (MA)");

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
        XYSeriesCollection barDataset = new XYSeriesCollection();
        barDataset.addSeries(atTransactionsPerBlockSeries);
        barDataset.addSeries(transactionsPerBlockSeries);
        plot.setDataset(1, barDataset);
        plot.mapDatasetToRangeAxis(1, 1);

        // Renderer for transaction bars
        XYBarRenderer transactionRenderer = new XYBarRenderer(0);
        transactionRenderer.setBarPainter(new StandardXYBarPainter());
        transactionRenderer.setShadowVisible(false);
        transactionRenderer.setSeriesPaint(0, new Color(0, 0, 255, 128)); // Blue, semi-transparent
        transactionRenderer.setSeriesPaint(1, new Color(255, 165, 0, 128)); // Orange, semi-transparent
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

    private PerformanceUpdateData calculatePerformanceUpdate(Block block) {
        PerformanceUpdateData data = new PerformanceUpdateData();
        if (block == null)
            return data;

        blockTimestamps.add(System.currentTimeMillis());

        int signedTxCount = block.getTransactions().size();
        int atTxCount = block.getAtTransactions().size();
        int subscriptionTxCount = block.getSubscriptionTransactions().size();
        int escrowTxCount = block.getEscrowTransactions().size();

        int systemTxCount = atTxCount + subscriptionTxCount + escrowTxCount;
        int allTxCount = signedTxCount + systemTxCount;

        int atCount = 0;

        if (block.getBlockAts() != null) {
            try {
                atCount = AtController.getATsFromBlock(block.getBlockAts()).size();
            } catch (Exception e) {
                LOGGER.warn("Could not parse ATs from block", e);
            }
        }
        transactionCounts.add((double) allTxCount);
        systemTransactionCounts.add((double) systemTxCount);
        atCounts.add((double) atCount);

        double timeSpanMs = blockTimestamps.getLast()
                - blockTimestamps.get(blockTimestamps.size() - Math.min(blockTimestamps.size(), movingAverageWindow));

        double blocksPerSecond = 0.0;
        if (timeSpanMs > 0) {
            blocksPerSecond = (double) Math.min(blockTimestamps.size(), movingAverageWindow) * 1000.0 / timeSpanMs;
        }
        blocksPerSecondHistory.add(blocksPerSecond);
        double avgBlocksPerSecond = blocksPerSecondHistory.getAverage();
        double maxBlocksPerSecond = blocksPerSecondHistory.getMax();

        double avgTransactions = transactionCounts.getAverage();
        double avgSystemTransactions = systemTransactionCounts.getAverage();
        double avgAtCount = atCounts.getAverage();

        double transactionsPerSecond = 0.0;
        if (timeSpanMs > 0) {
            transactionsPerSecond = transactionCounts.getSum() * 1000.0 / timeSpanMs;
        }
        transactionsPerSecondHistory.add(transactionsPerSecond);
        double avgTransactionsPerSecond = transactionsPerSecondHistory.getAverage();
        double maxTransactionsPerSecond = transactionsPerSecondHistory.getMax();

        double systemTransactionsPerSecond = avgSystemTransactions * blocksPerSecond;
        systemTransactionsPerSecondHistory.add(systemTransactionsPerSecond);
        double maxSystemTransactionsPerSecond = systemTransactionsPerSecondHistory.getMax();
        atTransactionsPerBlockHistory.add(avgSystemTransactions);
        double maxAtTransactionsPerBlock = atTransactionsPerBlockHistory.getMax();
        atCountHistory.add(avgAtCount);
        double maxAtCount = atCountHistory.getMax();
        transactionsPerBlockHistory.add(avgTransactions);
        double maxTransactionsPerBlock = transactionsPerBlockHistory.getMax();

        // Prepare chart series updates
        data.seriesUpdates.put(blocksPerSecondSeries,
                new Point.Double(block.getHeight(), avgBlocksPerSecond));
        data.seriesUpdates.put(transactionsPerSecondSeries,
                new Point.Double(block.getHeight(), avgTransactionsPerSecond));
        data.seriesUpdates.put(transactionsPerBlockSeries,
                new Point.Double(block.getHeight(), avgTransactions));
        data.seriesUpdates.put(atTransactionsPerBlockSeries,
                new Point.Double(block.getHeight(), avgSystemTransactions));
        data.seriesUpdates.put(atCountPerBlockSeries,
                new Point.Double(block.getHeight(), avgAtCount));
        data.seriesUpdates.put(systemTransactionsPerSecondSeries,
                new Point.Double(block.getHeight(), systemTransactionsPerSecond));

        // Prepare progress bar updates
        data.progressBarUpdates.put(blocksPerSecondProgressBar,
                () -> updateProgressBar(blocksPerSecondProgressBar, avgBlocksPerSecond, maxBlocksPerSecond,
                        val -> String.format("%.2f - max: %.2f", val, maxBlocksPerSecond), 100));
        data.progressBarUpdates.put(transactionsPerSecondProgressBar,
                () -> updateProgressBar(transactionsPerSecondProgressBar, avgTransactionsPerSecond,
                        maxTransactionsPerSecond,
                        val -> String.format("%.2f - max: %.2f", val, maxTransactionsPerSecond), 100));
        data.progressBarUpdates.put(transactionsPerBlockProgressBar,
                () -> updateProgressBar(transactionsPerBlockProgressBar, avgTransactions, maxTransactionsPerBlock,
                        val -> String.format("%.2f - max: %.2f", val, maxTransactionsPerBlock),
                        100));
        data.progressBarUpdates.put(atTransactionsPerBlockProgressBar,
                () -> updateProgressBar(atTransactionsPerBlockProgressBar, avgSystemTransactions,
                        maxAtTransactionsPerBlock,
                        val -> String.format("%.2f - max: %.2f", val, maxAtTransactionsPerBlock), 100));
        data.progressBarUpdates.put(atCountProgressBar,
                () -> updateProgressBar(atCountProgressBar, avgAtCount, maxAtCount,
                        val -> String.format("%.2f - max: %.2f", val, maxAtCount), 100));
        data.progressBarUpdates.put(systemTransactionsPerSecondProgressBar,
                () -> updateProgressBar(systemTransactionsPerSecondProgressBar, systemTransactionsPerSecond,
                        maxSystemTransactionsPerSecond,
                        val -> String.format("%.2f - max: %.2f", val, maxSystemTransactionsPerSecond),
                        100));

        return data;
    }

    private void updateProgressBar(JProgressBar bar, double value, double max,
            java.util.function.Function<Double, String> stringFormatter) {
        updateProgressBar(bar, value, max, stringFormatter, 1);
    }

    private void updateProgressBar(JProgressBar bar, double value, double max,
            java.util.function.Function<Double, String> stringFormatter, int multiplier) {
        bar.setMaximum((int) (max * multiplier));
        bar.setValue((int) (value * multiplier));
        bar.setString(stringFormatter.apply(value));
    }

    private void updateChartSeries(XYSeries series, double x, double y, int maxItems) {
        while (series.getItemCount() >= maxItems) {
            series.remove(0);
        }
        series.add(x, y);
    }
}