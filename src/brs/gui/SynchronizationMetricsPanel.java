package brs.gui;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.labels.XYToolTipGenerator;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.plot.DatasetRenderingOrder;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.renderer.xy.StandardXYBarPainter;
import org.jfree.chart.renderer.xy.XYBarRenderer;
import org.jfree.chart.renderer.xy.XYStepAreaRenderer;
import org.jfree.chart.ui.RectangleInsets;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.jfree.data.xy.XYDataset;

import javax.swing.*;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import brs.Signum;
import brs.gui.util.MovingAverage;
import brs.Block;
import brs.BlockchainProcessor;
import brs.props.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.font.TextAttribute;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.IdentityHashMap;

@SuppressWarnings("serial")
public class SynchronizationMetricsPanel extends JPanel {

    // Constants for chart dataset and axis indices to improve readability
    private static final int PERF_TIMING_DATASET_BARS = 0;
    private static final int PERF_TIMING_DATASET_LINES = 1;
    private static final int PERF_TIMING_AXIS_LINES = 0;
    private static final int PERF_TIMING_AXIS_BARS = 1;

    private static final int NET_SPEED_DATASET_VOLUME = 0;
    private static final int NET_SPEED_DATASET_SPEED = 1;
    private static final int NET_SPEED_AXIS_SPEED = 0;
    private static final int NET_SPEED_AXIS_VOLUME = 1;

    private static final Logger LOGGER = LoggerFactory.getLogger(SynchronizationMetricsPanel.class);
    private static final int CHART_HISTORY_SIZE = 1000;
    private static final int SPEED_HISTORY_SIZE = 1000;
    private static final int MAX_SPEED_BPS = 10 * 1024 * 1024; // 10 MB/s

    private int movingAverageWindow = 100;
    private int currentZoomRange = CHART_HISTORY_SIZE;
    private final MovingAverage blockTimestamps = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage blocksPerSec = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage allTransactionsPerBlock = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage systemTransactionsPerBlock = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage allTransactionsPerSec = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage systemTransactionsPerSec = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
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
    private final MovingAverage atCountsPerBlock = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage payloadSize = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage uploadSpeeds = new MovingAverage(SPEED_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage downloadSpeeds = new MovingAverage(SPEED_HISTORY_SIZE, movingAverageWindow);

    private XYSeries blocksPerSecondSeries;
    private XYSeries allTransactionsPerSecondSeries;
    private XYSeries allTransactionsPerBlockSeries;
    private XYSeries systemTransactionsPerBlockSeries;
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
    private XYSeries payloadFullnessSeries;

    private JProgressBar blocksPerSecondProgressBar;
    private JLabel blocksPerSecondLabel;
    private JLabel txPerSecondLabel;
    private JLabel txPerBlockLabel;
    private JProgressBar allTransactionsPerSecondProgressBar;
    private JProgressBar allTransactionsPerBlockProgressBar;
    private int oclUnverifiedQueueThreshold;
    private boolean oclEnabled = false;
    private int maxUnverifiedQueueSize;
    private int maxUnconfirmedTxs;
    private int maxPayloadSize;
    private int downloadCacheSize;
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
    private JLabel payloadFullnessLabel;
    private JProgressBar payloadFullnessProgressBar;
    private JLabel atCountLabel;
    private JProgressBar atCountsPerBlockProgressBar;
    private JLabel systemTxPerBlockLabel;
    private JProgressBar systemTransactionsPerBlockProgressBar;
    private JLabel systemTxPerSecondLabel;
    private JProgressBar systemTransactionsPerSecondProgressBar;
    private JProgressBar uploadSpeedProgressBar;

    private JProgressBar downloadSpeedProgressBar;

    private JProgressBar unconfirmedTxsProgressBar;
    private JLabel cacheFullnessLabel;
    private JProgressBar cacheFullnessProgressBar;
    private JLabel forkCacheLabel;
    private JProgressBar forkCacheProgressBar;

    private ChartPanel performanceChartPanel;
    private ChartPanel timingChartPanel;
    private ChartPanel uploadChartPanel;
    private ChartPanel downloadChartPanel;
    private Timer netSpeedChartUpdater;

    private XYSeries uploadVolumeSeries;
    private XYSeries downloadVolumeSeries;

    private volatile long uploadedVolume = 0;
    private volatile long downloadedVolume = 0;
    private final int netSpeedUpdateTime = 100; // milliseconds

    private final Dimension chartDimension1 = new Dimension(320, 240);
    private final Dimension splitChartDimension = new Dimension(320, 60);
    private final Dimension chartDimension = new Dimension(360, 270);

    private final Dimension progressBarSize1 = new Dimension(200, 20);
    private final Dimension wideProgressBarSize = new Dimension(progressBarSize1.width * 2 + 5,
            progressBarSize1.height);
    private final Dimension progressBarSize2 = new Dimension(150, 20);

    private final Insets labelInsets = new Insets(2, 5, 2, 0);
    private final Insets barInsets = new Insets(2, 5, 2, 5);

    private static final Color COLOR_SYSTEM_TX_PER_BLOCK = new Color(64, 64, 192); // Blue-ish
    private static final Color COLOR_ALL_TX_PER_BLOCK = new Color(235, 165, 50); // Orange-ish

    private static final Color COLOR_UPLOAD_VOLUME = new Color(185, 120, 95); // Red-ish
    private static final Color COLOR_DOWNLOAD_VOLUME = new Color(40, 165, 40); // Green

    private static final Color COLOR_PUSH_TIME = Color.BLUE; // Blue
    private static final Color COLOR_VALIDATION_TIME = Color.YELLOW; // Yellow
    private static final Color COLOR_TX_LOOP_TIME = new Color(128, 0, 128); // Purple
    private static final Color COLOR_HOUSEKEEPING_TIME = new Color(42, 223, 223); // Cyan
    private static final Color COLOR_TX_APPLY_TIME = new Color(255, 165, 0); // Orange
    private static final Color COLOR_AT_TIME = new Color(153, 0, 76); // Dark Red
    private static final Color COLOR_SUBSCRIPTION_TIME = new Color(255, 105, 100); // Hot Pink
    private static final Color COLOR_BLOCK_APPLY_TIME = new Color(0, 100, 100); // Teal
    private static final Color COLOR_COMMIT_TIME = new Color(220, 130, 255); // Lighter Purple
    private static final Color COLOR_MISC_TIME = Color.LIGHT_GRAY; // Light Gray
    private static final Color COLOR_PAYLOAD_FULLNESS = Color.WHITE; // White

    private static final Color COLOR_BLOCKS_PER_SEC = Color.CYAN; // Cyan
    private static final Color COLOR_ALL_TX_PER_SEC = Color.GREEN; // Green
    private static final Color COLOR_SYSTEM_TX_PER_SEC = new Color(135, 206, 250); // Light Sky Blue
    private static final Color COLOR_AT_COUNT_PER_BLOCK = new Color(153, 0, 76); // Deep Pink

    private static final Color COLOR_UPLOAD_SPEED = new Color(128, 0, 0); // Dark Red
    private static final Color COLOR_DOWNLOAD_SPEED = new Color(0, 100, 0); // Dark Green

    private static final BasicStroke CHART_STROKE = new BasicStroke(1.2f);

    private final ExecutorService chartUpdateExecutor = Executors.newSingleThreadExecutor();

    private final Shape tooltipHitShape = new Ellipse2D.Double(-10.0, -10.0, 20.0, 20.0);

    private JProgressBar syncProgressBarDownloadedBlocks;

    private boolean isTabActive = false;
    private boolean uiOptimizationEnabled = true;
    private TimingUpdateData lastTimingData;
    private PerformanceUpdateData lastPerformanceData;
    private SharedBarChartUpdateData lastSharedData;
    private Runnable lastNetSpeedUpdate;

    // Data Transfer Objects for UI updates
    private static class TimingUpdateData {
        Map<JProgressBar, Runnable> progressBarUpdates = new HashMap<>();
        Map<XYSeries, Point.Double> seriesUpdates = new IdentityHashMap<>();
    }

    private ChartPanel createPerformanceChartPanel() {
        blocksPerSecondSeries = new XYSeries("Blocks/Second (MA)", true, false);
        allTransactionsPerSecondSeries = new XYSeries("All Txs/Sec (MA)", true, false);
        systemTransactionsPerSecondSeries = new XYSeries("System Txs/Sec (MA)", true, false);
        atCountPerBlockSeries = new XYSeries("ATs/Block (MA)", true, false);

        blocksPerSecondSeries.setMaximumItemCount(CHART_HISTORY_SIZE);
        allTransactionsPerSecondSeries.setMaximumItemCount(CHART_HISTORY_SIZE);
        systemTransactionsPerSecondSeries.setMaximumItemCount(CHART_HISTORY_SIZE);
        atCountPerBlockSeries.setMaximumItemCount(CHART_HISTORY_SIZE);

        XYSeriesCollection lineDataset = new XYSeriesCollection();
        lineDataset.addSeries(blocksPerSecondSeries);
        lineDataset.addSeries(allTransactionsPerSecondSeries);
        lineDataset.addSeries(systemTransactionsPerSecondSeries);
        lineDataset.addSeries(atCountPerBlockSeries);

        // Create chart with no title or axis labels to save space
        JFreeChart chart = ChartFactory.createXYLineChart(
                null, // No title
                null, // No X-axis label
                null, // No Y-axis label
                null);

        // Remove the legend to maximize plot area
        chart.removeLegend();
        chart.setBorderVisible(false);

        XYPlot plot = chart.getXYPlot();
        plot.getDomainAxis().setLowerMargin(0.0);
        plot.getDomainAxis().setUpperMargin(0.0);
        plot.setBackgroundPaint(Color.DARK_GRAY);
        plot.setDomainGridlinesVisible(false);
        plot.setRangeGridlinesVisible(false);
        plot.setDatasetRenderingOrder(DatasetRenderingOrder.FORWARD);

        // Hide axis tick labels (the numbers on the axes)
        plot.getDomainAxis().setTickLabelsVisible(false);
        plot.getDomainAxis().setTickMarksVisible(false);
        plot.getDomainAxis().setAxisLineVisible(false);
        plot.getRangeAxis().setTickLabelsVisible(false);
        plot.getRangeAxis().setTickMarksVisible(false);
        plot.getRangeAxis().setAxisLineVisible(false);
        plot.getRangeAxis().setLowerMargin(0.0);
        plot.getRangeAxis().setUpperMargin(0.05);

        // Second Y-axis for transaction count
        NumberAxis transactionAxis = new NumberAxis(null);
        transactionAxis.setTickLabelsVisible(false);
        transactionAxis.setTickMarksVisible(false);
        transactionAxis.setAxisLineVisible(false);
        transactionAxis.setLowerMargin(0.0);
        transactionAxis.setUpperMargin(0.05);
        XYSeriesCollection barDataset = new XYSeriesCollection();
        barDataset.addSeries(systemTransactionsPerBlockSeries); // Index 0
        barDataset.addSeries(allTransactionsPerBlockSeries); // Index 1
        barDataset.setIntervalWidth(1.0);
        plot.setDataset(PERF_TIMING_DATASET_BARS, barDataset);
        plot.setRangeAxis(PERF_TIMING_AXIS_BARS, transactionAxis);
        plot.mapDatasetToRangeAxis(PERF_TIMING_DATASET_BARS, PERF_TIMING_AXIS_BARS);

        // Renderer for transaction bars
        // XYStepAreaRenderer transactionRenderer = new XYStepAreaRenderer();
        // transactionRenderer.setShapesVisible(false);

        XYBarRenderer transactionRenderer = new XYBarRenderer();
        Map<String, Color> barColors = new HashMap<>();
        barColors.put(systemTransactionsPerBlockSeries.getKey().toString(), COLOR_SYSTEM_TX_PER_BLOCK);
        barColors.put(allTransactionsPerBlockSeries.getKey().toString(), COLOR_ALL_TX_PER_BLOCK);
        configureBarRenderer(transactionRenderer, barDataset, barColors);

        plot.setRenderer(PERF_TIMING_DATASET_BARS, transactionRenderer);

        // Renderer for lines (Dataset 1)
        plot.setDataset(PERF_TIMING_DATASET_LINES, lineDataset);
        XYLineAndShapeRenderer lineRenderer = new XYLineAndShapeRenderer(true, false);

        Map<String, Color> lineColors = new HashMap<>();
        lineColors.put(blocksPerSecondSeries.getKey().toString(), COLOR_BLOCKS_PER_SEC);
        lineColors.put(allTransactionsPerSecondSeries.getKey().toString(), COLOR_ALL_TX_PER_SEC);
        lineColors.put(systemTransactionsPerSecondSeries.getKey().toString(), COLOR_SYSTEM_TX_PER_SEC);
        lineColors.put(atCountPerBlockSeries.getKey().toString(), COLOR_AT_COUNT_PER_BLOCK);

        configureLineRenderer(lineRenderer, lineDataset, lineColors);

        plot.setRenderer(PERF_TIMING_DATASET_LINES, lineRenderer);
        plot.mapDatasetToRangeAxis(PERF_TIMING_DATASET_LINES, PERF_TIMING_AXIS_LINES);

        // Remove all padding around the plot area
        plot.setInsets(new RectangleInsets(0, 0, 0, 0));
        plot.setAxisOffset(new RectangleInsets(0, 0, 0, 0));

        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(chartDimension);
        chartPanel.setMinimumSize(chartDimension);
        chartPanel.setMaximumSize(chartDimension);
        chartPanel.setDisplayToolTips(true);
        ToolTipManager.sharedInstance().registerComponent(chartPanel);
        return chartPanel;
    }

    private ChartPanel createTimingChartPanel() {
        pushTimePerBlockSeries = new XYSeries("Push Time (MA)", true, false);
        validationTimePerBlockSeries = new XYSeries("Validation Time (MA)", true, false);
        txLoopTimePerBlockSeries = new XYSeries("TX Loop Time (MA)", true, false);
        housekeepingTimePerBlockSeries = new XYSeries("Housekeeping Time (MA)", true, false);

        txApplyTimePerBlockSeries = new XYSeries("TX Apply Time (MA)", true, false);
        atTimePerBlockSeries = new XYSeries("AT Time (MA)", true, false);
        subscriptionTimePerBlockSeries = new XYSeries("Subscription Time (MA)", true, false);
        blockApplyTimePerBlockSeries = new XYSeries("Block Apply Time (MA)", true, false);
        commitTimePerBlockSeries = new XYSeries("Commit Time (MA)", true, false);
        miscTimePerBlockSeries = new XYSeries("Misc. Time (MA)", true, false);

        pushTimePerBlockSeries.setMaximumItemCount(CHART_HISTORY_SIZE);
        validationTimePerBlockSeries.setMaximumItemCount(CHART_HISTORY_SIZE);
        txLoopTimePerBlockSeries.setMaximumItemCount(CHART_HISTORY_SIZE);
        housekeepingTimePerBlockSeries.setMaximumItemCount(CHART_HISTORY_SIZE);
        txApplyTimePerBlockSeries.setMaximumItemCount(CHART_HISTORY_SIZE);
        atTimePerBlockSeries.setMaximumItemCount(CHART_HISTORY_SIZE);
        subscriptionTimePerBlockSeries.setMaximumItemCount(CHART_HISTORY_SIZE);
        blockApplyTimePerBlockSeries.setMaximumItemCount(CHART_HISTORY_SIZE);
        commitTimePerBlockSeries.setMaximumItemCount(CHART_HISTORY_SIZE);
        miscTimePerBlockSeries.setMaximumItemCount(CHART_HISTORY_SIZE);

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
        lineDataset.addSeries(payloadFullnessSeries);

        // Create chart with no title or axis labels to save space
        JFreeChart chart = ChartFactory.createXYLineChart(
                null, // No title
                null, // No X-axis label
                null, // No Y-axis label
                null);

        // Remove the legend to maximize plot area
        chart.removeLegend();
        chart.setBorderVisible(false);

        XYPlot plot = chart.getXYPlot();
        plot.getDomainAxis().setLowerMargin(0.0);
        plot.getDomainAxis().setUpperMargin(0.0);
        plot.setBackgroundPaint(Color.DARK_GRAY);
        plot.setDomainGridlinesVisible(false);
        plot.setRangeGridlinesVisible(false);
        plot.setDatasetRenderingOrder(DatasetRenderingOrder.FORWARD);

        // Hide axis tick labels (the numbers on the axes)
        plot.getDomainAxis().setTickLabelsVisible(false);
        plot.getDomainAxis().setTickMarksVisible(false);
        plot.getDomainAxis().setAxisLineVisible(false);
        plot.getRangeAxis().setTickLabelsVisible(false);
        plot.getRangeAxis().setTickMarksVisible(false);
        plot.getRangeAxis().setAxisLineVisible(false);
        plot.getRangeAxis().setLowerMargin(0.0);
        plot.getRangeAxis().setUpperMargin(0.05);

        // Second Y-axis for transaction count
        NumberAxis transactionAxis = new NumberAxis(null);
        transactionAxis.setTickLabelsVisible(false);
        transactionAxis.setTickMarksVisible(false);
        transactionAxis.setAxisLineVisible(false);
        transactionAxis.setLowerMargin(0.0);
        transactionAxis.setUpperMargin(0.05);
        XYSeriesCollection barDataset = new XYSeriesCollection();
        barDataset.addSeries(systemTransactionsPerBlockSeries); // Index 0
        barDataset.addSeries(allTransactionsPerBlockSeries); // Index 1
        barDataset.setIntervalWidth(1.0);
        plot.setDataset(PERF_TIMING_DATASET_BARS, barDataset);
        plot.setRangeAxis(PERF_TIMING_AXIS_BARS, transactionAxis);
        plot.mapDatasetToRangeAxis(PERF_TIMING_DATASET_BARS, PERF_TIMING_AXIS_BARS);

        // Renderer for transaction bars
        // XYStepAreaRenderer transactionRenderer = new XYStepAreaRenderer();
        // transactionRenderer.setShapesVisible(false);

        XYBarRenderer transactionRenderer = new XYBarRenderer();
        Map<String, Color> barColors = new HashMap<>();
        barColors.put(systemTransactionsPerBlockSeries.getKey().toString(), COLOR_SYSTEM_TX_PER_BLOCK);
        barColors.put(allTransactionsPerBlockSeries.getKey().toString(), COLOR_ALL_TX_PER_BLOCK);
        configureBarRenderer(transactionRenderer, barDataset, barColors);

        plot.setRenderer(PERF_TIMING_DATASET_BARS, transactionRenderer);

        // Renderer for lines (Dataset 1)
        plot.setDataset(PERF_TIMING_DATASET_LINES, lineDataset);
        XYLineAndShapeRenderer lineRenderer = new XYLineAndShapeRenderer(true, false);

        Map<String, Color> lineColors = new HashMap<>();
        lineColors.put(pushTimePerBlockSeries.getKey().toString(), COLOR_PUSH_TIME);
        lineColors.put(validationTimePerBlockSeries.getKey().toString(), COLOR_VALIDATION_TIME);
        lineColors.put(txLoopTimePerBlockSeries.getKey().toString(), COLOR_TX_LOOP_TIME);
        lineColors.put(housekeepingTimePerBlockSeries.getKey().toString(), COLOR_HOUSEKEEPING_TIME);
        lineColors.put(txApplyTimePerBlockSeries.getKey().toString(), COLOR_TX_APPLY_TIME);
        lineColors.put(atTimePerBlockSeries.getKey().toString(), COLOR_AT_TIME);
        lineColors.put(subscriptionTimePerBlockSeries.getKey().toString(), COLOR_SUBSCRIPTION_TIME);
        lineColors.put(blockApplyTimePerBlockSeries.getKey().toString(), COLOR_BLOCK_APPLY_TIME);
        lineColors.put(commitTimePerBlockSeries.getKey().toString(), COLOR_COMMIT_TIME);
        lineColors.put(miscTimePerBlockSeries.getKey().toString(), COLOR_MISC_TIME);
        lineColors.put(payloadFullnessSeries.getKey().toString(), COLOR_PAYLOAD_FULLNESS);

        configureLineRenderer(lineRenderer, lineDataset, lineColors);

        plot.setRenderer(PERF_TIMING_DATASET_LINES, lineRenderer);
        plot.mapDatasetToRangeAxis(PERF_TIMING_DATASET_LINES, PERF_TIMING_AXIS_LINES);

        // Remove all padding around the plot area
        plot.setInsets(new RectangleInsets(0, 0, 0, 0));
        plot.setAxisOffset(new RectangleInsets(0, 0, 0, 0));

        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(chartDimension1);
        chartPanel.setMinimumSize(chartDimension1);
        chartPanel.setMaximumSize(chartDimension1);
        chartPanel.setDisplayToolTips(true);
        ToolTipManager.sharedInstance().registerComponent(chartPanel);
        return chartPanel;
    }

    private ChartPanel createUploadChartPanel() {
        uploadSpeedSeries = new XYSeries("Upload Speed", true, false);
        uploadVolumeSeries = new XYSeries("Upload Volume", true, false);
        uploadSpeedSeries.setMaximumItemCount(SPEED_HISTORY_SIZE);
        uploadVolumeSeries.setMaximumItemCount(SPEED_HISTORY_SIZE);

        XYSeriesCollection lineDataset = new XYSeriesCollection();
        lineDataset.addSeries(uploadSpeedSeries);

        JFreeChart chart = ChartFactory.createXYLineChart(
                null, // No title
                null, // No X-axis label
                null, // No Y-axis label
                null);

        // Remove the legend to maximize plot area
        chart.removeLegend();
        chart.setBorderVisible(false);

        XYPlot plot = chart.getXYPlot();
        plot.getDomainAxis().setLowerMargin(0.0);
        plot.getDomainAxis().setUpperMargin(0.0);
        plot.setBackgroundPaint(Color.DARK_GRAY);
        plot.setDomainGridlinesVisible(false);
        plot.setRangeGridlinesVisible(false);
        plot.setDatasetRenderingOrder(DatasetRenderingOrder.FORWARD);

        // Hide axis tick labels (the numbers on the axes)
        plot.getDomainAxis().setTickLabelsVisible(false);
        plot.getDomainAxis().setTickMarksVisible(false);
        plot.getDomainAxis().setAxisLineVisible(false);
        plot.getRangeAxis().setTickLabelsVisible(false);
        plot.getRangeAxis().setTickMarksVisible(false);
        plot.getRangeAxis().setAxisLineVisible(false);
        plot.getRangeAxis().setLowerMargin(0.0);
        plot.getRangeAxis().setUpperMargin(0.0);

        // Second Y-axis for volume
        NumberAxis volumeAxis = new NumberAxis(null); // No label for the second axis
        volumeAxis.setTickLabelsVisible(false);
        volumeAxis.setTickMarksVisible(false);
        volumeAxis.setAxisLineVisible(false);
        volumeAxis.setLowerMargin(0.0);
        volumeAxis.setUpperMargin(0.0);
        plot.setRangeAxis(NET_SPEED_AXIS_VOLUME, volumeAxis);

        // Dataset and renderer for volume series.
        XYSeriesCollection volumeDataset = new XYSeriesCollection();
        volumeDataset.addSeries(uploadVolumeSeries);

        XYStepAreaRenderer volumeRenderer = new XYStepAreaRenderer();
        volumeRenderer.setShapesVisible(false);
        volumeRenderer.setSeriesPaint(0, COLOR_UPLOAD_VOLUME); // Upload - Red
        volumeRenderer.setDefaultToolTipGenerator(new SyncChartToolTipGenerator());
        plot.setDataset(NET_SPEED_DATASET_VOLUME, volumeDataset);
        plot.setRenderer(NET_SPEED_DATASET_VOLUME, volumeRenderer);
        plot.mapDatasetToRangeAxis(NET_SPEED_DATASET_VOLUME, NET_SPEED_AXIS_VOLUME);

        // Dataset 1: Speed (Line)
        plot.setDataset(NET_SPEED_DATASET_SPEED, lineDataset);
        XYLineAndShapeRenderer lineRenderer = new XYLineAndShapeRenderer(true, false);

        Map<String, Color> lineColors = new HashMap<>();
        lineColors.put(uploadSpeedSeries.getKey().toString(), COLOR_UPLOAD_SPEED);
        configureLineRenderer(lineRenderer, lineDataset, lineColors);

        plot.setRenderer(NET_SPEED_DATASET_SPEED, lineRenderer);
        plot.mapDatasetToRangeAxis(NET_SPEED_DATASET_SPEED, NET_SPEED_AXIS_SPEED);

        // Remove all padding around the plot area
        plot.setInsets(new RectangleInsets(0, 0, 0, 0));
        plot.setAxisOffset(new RectangleInsets(0, 0, 0, 0));

        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(splitChartDimension);
        chartPanel.setMinimumSize(splitChartDimension);
        chartPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, splitChartDimension.height));
        chartPanel.setDisplayToolTips(true);
        ToolTipManager.sharedInstance().registerComponent(chartPanel);
        return chartPanel;
    }

    private ChartPanel createDownloadChartPanel() {
        downloadSpeedSeries = new XYSeries("Download Speed", true, false);
        downloadVolumeSeries = new XYSeries("Download Volume", true, false);
        downloadSpeedSeries.setMaximumItemCount(SPEED_HISTORY_SIZE);
        downloadVolumeSeries.setMaximumItemCount(SPEED_HISTORY_SIZE);

        XYSeriesCollection lineDataset = new XYSeriesCollection();
        lineDataset.addSeries(downloadSpeedSeries);

        JFreeChart chart = ChartFactory.createXYLineChart(
                null, // No title
                null, // No X-axis label
                null, // No Y-axis label
                null);

        // Remove the legend to maximize plot area
        chart.removeLegend();
        chart.setBorderVisible(false);

        XYPlot plot = chart.getXYPlot();
        plot.getDomainAxis().setLowerMargin(0.0);
        plot.getDomainAxis().setUpperMargin(0.0);
        plot.setBackgroundPaint(Color.DARK_GRAY);
        plot.setDomainGridlinesVisible(false);
        plot.setRangeGridlinesVisible(false);
        plot.setDatasetRenderingOrder(DatasetRenderingOrder.FORWARD);

        // Hide axis tick labels (the numbers on the axes)
        plot.getDomainAxis().setTickLabelsVisible(false);
        plot.getDomainAxis().setTickMarksVisible(false);
        plot.getDomainAxis().setAxisLineVisible(false);
        plot.getRangeAxis().setTickLabelsVisible(false);
        plot.getRangeAxis().setTickMarksVisible(false);
        plot.getRangeAxis().setAxisLineVisible(false);
        plot.getRangeAxis().setLowerMargin(0.0);
        plot.getRangeAxis().setUpperMargin(0.0);

        // Second Y-axis for volume
        NumberAxis volumeAxis = new NumberAxis(null); // No label for the second axis
        volumeAxis.setTickLabelsVisible(false);
        volumeAxis.setTickMarksVisible(false);
        volumeAxis.setAxisLineVisible(false);
        volumeAxis.setLowerMargin(0.0);
        volumeAxis.setUpperMargin(0.0);
        plot.setRangeAxis(NET_SPEED_AXIS_VOLUME, volumeAxis);

        // Dataset and renderer for volume series.
        XYSeriesCollection volumeDataset = new XYSeriesCollection();
        volumeDataset.addSeries(downloadVolumeSeries);

        XYStepAreaRenderer volumeRenderer = new XYStepAreaRenderer();
        volumeRenderer.setShapesVisible(false);
        volumeRenderer.setSeriesPaint(0, COLOR_DOWNLOAD_VOLUME); // Download - Green
        volumeRenderer.setDefaultToolTipGenerator(new SyncChartToolTipGenerator());
        plot.setDataset(NET_SPEED_DATASET_VOLUME, volumeDataset);
        plot.setRenderer(NET_SPEED_DATASET_VOLUME, volumeRenderer);
        plot.mapDatasetToRangeAxis(NET_SPEED_DATASET_VOLUME, NET_SPEED_AXIS_VOLUME);

        // Dataset 1: Speed (Line)
        plot.setDataset(NET_SPEED_DATASET_SPEED, lineDataset);
        XYLineAndShapeRenderer lineRenderer = new XYLineAndShapeRenderer(true, false);

        Map<String, Color> lineColors = new HashMap<>();
        lineColors.put(downloadSpeedSeries.getKey().toString(), COLOR_DOWNLOAD_SPEED);
        configureLineRenderer(lineRenderer, lineDataset, lineColors);

        plot.setRenderer(NET_SPEED_DATASET_SPEED, lineRenderer);
        plot.mapDatasetToRangeAxis(NET_SPEED_DATASET_SPEED, NET_SPEED_AXIS_SPEED);

        // Remove all padding around the plot area
        plot.setInsets(new RectangleInsets(0, 0, 0, 0));
        plot.setAxisOffset(new RectangleInsets(0, 0, 0, 0));

        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(splitChartDimension);
        chartPanel.setMinimumSize(splitChartDimension);
        chartPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, splitChartDimension.height));
        chartPanel.setDisplayToolTips(true);
        ToolTipManager.sharedInstance().registerComponent(chartPanel);
        return chartPanel;
    }

    private static class PerformanceUpdateData {
        Map<JProgressBar, Runnable> progressBarUpdates = new HashMap<>();
        Map<XYSeries, Point.Double> seriesUpdates = new IdentityHashMap<>();
    }

    // DTO for shared bar chart updates
    private static class SharedBarChartUpdateData {
        Map<JProgressBar, Runnable> progressBarUpdates = new HashMap<>();
        Map<XYSeries, Point.Double> seriesUpdates = new IdentityHashMap<>();
    }

    private JProgressBar syncProgressBarUnverifiedBlocks;
    private final JFrame parentFrame;

    public SynchronizationMetricsPanel(JFrame parentFrame) {
        super(new GridBagLayout());
        try {
            this.parentFrame = parentFrame;
            allTransactionsPerBlockSeries = new XYSeries("All Txs/Block (MA)", true, false); // Orange
            allTransactionsPerBlockSeries.setMaximumItemCount(CHART_HISTORY_SIZE);
            systemTransactionsPerBlockSeries = new XYSeries("System Txs/Block (MA)", true, false); // Blue
            systemTransactionsPerBlockSeries.setMaximumItemCount(CHART_HISTORY_SIZE);
            payloadFullnessSeries = new XYSeries("Payload Fullness (MA)", true, false);
            payloadFullnessSeries.setMaximumItemCount(CHART_HISTORY_SIZE);
            performanceChartPanel = createPerformanceChartPanel();
            timingChartPanel = createTimingChartPanel();
            uploadChartPanel = createUploadChartPanel();
            downloadChartPanel = createDownloadChartPanel();
            layoutComponents();
        } catch (Exception e) {
            LOGGER.error("Failed to initialize SynchronizationMetricsPanel", e);
            throw new RuntimeException("Could not initialize SynchronizationMetricsPanel", e);
        }
    }

    public void init() {
        try {
            oclUnverifiedQueueThreshold = Signum.getPropertyService().getInt(Props.GPU_UNVERIFIED_QUEUE);
            oclEnabled = Signum.getPropertyService().getBoolean(Props.GPU_ACCELERATION);
            if (oclEnabled) {
                maxUnverifiedQueueSize = oclUnverifiedQueueThreshold * 2;
            } else {
                maxUnverifiedQueueSize = oclUnverifiedQueueThreshold;
            }
            maxUnconfirmedTxs = Signum.getPropertyService().getInt(Props.P2P_MAX_UNCONFIRMED_TRANSACTIONS);
            maxPayloadSize = (Signum.getFluxCapacitor().getValue(brs.fluxcapacitor.FluxValues.MAX_PAYLOAD_LENGTH,
                    Signum.getBlockchain().getHeight()) / 1024);
            String payloadTooltip = """
                    Shows the percentage of the block's data section (payload) that is filled with transactions. This is a measure of block space utilization and network activity.

                    The maximum payload size is currently %d KB.

                    Legend:
                    - Current Moving Average (MA) payload fullness percentage [%%] over the last %d blocks.
                    - C: Current block fullness percentage [%%] (current payload size in bytes / maximum payload size in bytes).
                    - min: Minimum Moving Average (MA) payload fullness percentage [%%] observed in the current chart history window (%d blocks).
                    - max: Maximum Moving Average (MA) payload fullness percentage [%%] observed in the current chart history window (%d blocks).
                    """
                    .formatted(movingAverageWindow, maxPayloadSize, movingAverageWindow, movingAverageWindow);
            addInfoTooltip(payloadFullnessLabel, payloadTooltip);

            downloadCacheSize = Signum.getPropertyService().getInt(Props.BRS_BLOCK_CACHE_MB);
            String cacheTooltip = """
                    The percentage of the allocated download cache memory that is currently in use.

                    This indicates how much space is available for downloading new blocks before they are processed and added to the blockchain.

                    Configured cache size: %d MB.

                    The progress bar displays:
                    - Download Cache: Used [MB] / Total [MB] | Percentage [%%] used.
                    - Bar length: Indicates the current cache usage relative to the total allocated cache size.
                    """
                    .formatted(downloadCacheSize);
            addInfoTooltip(cacheFullnessLabel, cacheTooltip);

            syncProgressBarUnverifiedBlocks.setMaximum(maxUnverifiedQueueSize);
            unconfirmedTxsProgressBar.setMaximum(maxUnconfirmedTxs);
            unconfirmedTxsProgressBar.setString(0 + " / " + maxUnconfirmedTxs);
            initListeners();

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

            LOGGER.info("SynchronizationMetricsPanel initialized successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize SynchronizationMetricsPanel components", e);
            throw new RuntimeException("Could not initialize SynchronizationMetricsPanel components", e);
        }
    }

    public void setUiOptimizationEnabled(boolean enabled) {
        this.uiOptimizationEnabled = enabled;
        if (!enabled) {
            refreshUI();
        }
    }

    private void refreshUI() {
        SwingUtilities.invokeLater(() -> {
            if (lastTimingData != null) {
                applyProgressBarUpdates(lastTimingData.progressBarUpdates);
            }
            if (lastPerformanceData != null) {
                applyProgressBarUpdates(lastPerformanceData.progressBarUpdates);
            }
            if (lastSharedData != null) {
                applyProgressBarUpdates(lastSharedData.progressBarUpdates);
            }
            if (lastNetSpeedUpdate != null) {
                lastNetSpeedUpdate.run();
            }
            updateChartRanges();
            setPerformanceChartsNotification(true);
        });
    }

    private void layoutComponents() {
        setBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5));

        // === Performance Metrics Panel ===
        JPanel performanceMetricsPanel = new JPanel(new GridBagLayout());

        // SyncPanel (Progress Bars)
        JPanel SyncPanel = new JPanel(new GridBagLayout());

        int yPos = 0;

        // Fork Cache
        tooltip = """
                The number of blocks in the fork cache.

                The progress bar displays:
                - Fork Cache: Current cache size / maximum rollback limit.
                - Bar length: Indicates the current fork cache size relative to the maximum rollback limit.
                """;
        forkCacheLabel = createLabel("Fork Cache", null, tooltip);
        forkCacheProgressBar = createProgressBar(0, brs.Constants.MAX_ROLLBACK, Color.MAGENTA,
                "0 / " + brs.Constants.MAX_ROLLBACK, progressBarSize1);
        addComponent(SyncPanel, forkCacheLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END, GridBagConstraints.NONE,
                labelInsets);
        addComponent(SyncPanel, forkCacheProgressBar, 1, yPos++, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Cache Fullness
        cacheFullnessLabel = createLabel("Download Cache", null, null); // Tooltip is set in init()
        cacheFullnessProgressBar = createProgressBar(0, 100, Color.ORANGE, "0.00 / 0.00 MB | 0%", progressBarSize1);
        addComponent(SyncPanel, cacheFullnessLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE,
                labelInsets);
        addComponent(SyncPanel, cacheFullnessProgressBar, 1, yPos++, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Verified / Total Blocks
        tooltip = """
                Shows the number of blocks in the download queue that have passed PoC verification against the total number of blocks in the queue.

                - Verified: PoC signature has been checked (CPU/GPU intensive).
                - Total: All blocks currently in the download queue.

                A high number of unverified blocks may indicate a slow verification process.

                The progress bar displays:
                - Verified / Total: Number of verified blocks / Total blocks in queue | Percentage [%%].
                - Bar length: Indicates the number of verified blocks relative to the total number of blocks in the download queue.
                """;
        JLabel verifLabel = createLabel("Verified / Total Blocks", null, tooltip);
        syncProgressBarDownloadedBlocks = createProgressBar(0, 100, Color.GREEN, "0 / 0 | 0%", progressBarSize1);
        addComponent(SyncPanel, verifLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END, GridBagConstraints.NONE,
                labelInsets);
        addComponent(SyncPanel, syncProgressBarDownloadedBlocks, 1, yPos++, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Unverified Blocks
        tooltip = """
                The number of blocks in the download queue that are waiting for Proof-of-Capacity (PoC) verification.

                A persistently high number might indicate that the CPU or GPU is a bottleneck and cannot keep up with the network's block generation rate.

                The progress bar displays:
                - Unverified Blocks: Current count of unverified blocks.
                - Color Status: The text turns red if the count exceeds the 'GPU.UnverifiedQueue' threshold. If GPU acceleration 'GPU.Acceleration' is enabled, this indicates that OCL acceleration is active. Otherwise, it remains green.
                - Bar length: Indicates the current unverified block count relative to 'GPU.UnverifiedQueue' threshold if GPU acceleration is disabled. If GPU acceleration is enabled, the length indicates the count relative to double the 'GPU.UnverifiedQueue' threshold.
                """;
        JLabel unVerifLabel = createLabel("Unverified Blocks", null, tooltip);
        syncProgressBarUnverifiedBlocks = createProgressBar(0, 1000, Color.GREEN, "0", progressBarSize1);
        addComponent(SyncPanel, unVerifLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END, GridBagConstraints.NONE,
                labelInsets);
        addComponent(SyncPanel, syncProgressBarUnverifiedBlocks, 1, yPos++, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Unconfirmed Transactions
        tooltip = """
                The number of transactions waiting in the memory pool to be included in the next block.

                A high number indicates significant network activity. If this number grows continuously without being cleared, it might suggest that transaction fees are too low or the network is under heavy load.

                The progress bar displays:
                - Unconfirmed Txs: Current count / Maximum capacity.
                - Bar length: Indicates the current number of unconfirmed transactions relative to the maximum memory pool capacity.
                """;
        JLabel unconfirmedTxsLabel = createLabel("Unconfirmed Txs", null, tooltip);
        unconfirmedTxsProgressBar = createProgressBar(0, 1000, Color.GREEN, "0 / 0", progressBarSize1);
        addComponent(SyncPanel, unconfirmedTxsLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(SyncPanel, unconfirmedTxsProgressBar, 1, yPos++, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Separator
        JSeparator separator1 = new JSeparator(SwingConstants.HORIZONTAL);
        addComponent(SyncPanel, separator1, 0, yPos++, 2, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.HORIZONTAL,
                barInsets);

        // Blocks/Second (Moving Average)
        tooltip = """
                The moving average of blocks processed per second. This is a key indicator of the node's synchronization speed.

                A higher value means the node is rapidly catching up with the current state of the blockchain. This metric is particularly useful during the initial sync or after a period of being offline.

                The progress bar displays:
                - Blocks/Sec (MA): Current Moving Average (MA) value calculated over the last %d blocks.
                - max: Maximum Moving Average (MA) value observed in the current chart history window (%d blocks).
                - Bar length: Indicates the current Blocks/Sec (MA) relative to the maximum observed Blocks/Sec (MA) in the current chart history window (%d blocks).
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE, CHART_HISTORY_SIZE);
        blocksPerSecondLabel = createLabel("Blocks/Sec (MA)", COLOR_BLOCKS_PER_SEC, tooltip);
        blocksPerSecondProgressBar = createProgressBar(0, 200, null, "0.00 - max: 0.00", progressBarSize1);
        addComponent(SyncPanel, blocksPerSecondLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(SyncPanel, blocksPerSecondProgressBar, 1, yPos++, 1, 0, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // All Transactions/Second (Moving Average)
        tooltip = """
                The moving average of the total number of transactions (user-submitted and AT-generated) processed per second.
                This metric reflects the total transactional throughput of the network as seen by your node.

                Includes:
                - Payments (Ordinary, Multi-Out, Multi-Same-Out)
                - Messages (Arbitrary, Alias, Account Info, TLD)
                - Assets (Issuance, Transfer, Orders, Minting, Distribution)
                - Digital Goods (Listing, Delisting, Price Change, Quantity Change, Purchase, Delivery, Feedback, Refund)
                - Account Control (Leasing)
                - Mining (Reward Recipient, Commitment)
                - Advanced Payments (Escrow, Subscriptions)
                - Automated Transactions (ATs)

                The progress bar displays:
                - All Txs/Sec (MA): Current Moving Average (MA) value calculated over the last %d blocks.
                - max: Maximum Moving Average (MA) value observed in the current chart history window (%d blocks).
                - Bar length: Indicates the current All Txs/Sec (MA) relative to the maximum observed All Txs/Sec (MA) in the current chart history window (%d blocks).
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE, CHART_HISTORY_SIZE);
        txPerSecondLabel = createLabel("All Txs/Sec (MA)", COLOR_ALL_TX_PER_SEC, tooltip);
        allTransactionsPerSecondProgressBar = createProgressBar(0, 2000, null, "0.00 - max: 0.00", progressBarSize1);
        addComponent(SyncPanel, txPerSecondLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(SyncPanel, allTransactionsPerSecondProgressBar, 1, yPos++, 1, 0, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // System Transactions/Second (Moving Average)
        tooltip = """
                The moving average of system-generated transactions processed per second. This includes payments from Automated Transactions (ATs), Escrow results, and Subscription payments.

                The progress bar displays:
                - System Txs/Sec (MA): Current Moving Average (MA) value calculated over the last %d blocks.
                - max: Maximum Moving Average (MA) value observed in the current chart history window (%d blocks).
                - Bar length: Indicates the current System Txs/Sec (MA) relative to the maximum observed System Txs/Sec (MA) in the current chart history window (%d blocks).
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE, CHART_HISTORY_SIZE);
        systemTxPerSecondLabel = createLabel("System Txs/Sec (MA)", COLOR_SYSTEM_TX_PER_SEC, tooltip); // LightSkyBlue
        systemTransactionsPerSecondProgressBar = createProgressBar(0, 2000, null, "0.00 - max: 0.00", progressBarSize1);
        addComponent(SyncPanel, systemTxPerSecondLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(SyncPanel, systemTransactionsPerSecondProgressBar, 1, yPos++, 1, 0, 0,
                GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // All Transactions/Block (Moving Average)
        tooltip = """
                The moving average of the total number of transactions (user-submitted and AT-generated) included in each block. This metric provides insight into the network's activity and block space utilization.

                Includes:
                - Payments (Ordinary, Multi-Out, Multi-Same-Out)
                - Messages (Arbitrary, Alias, Account Info, TLD)
                - Assets (Issuance, Transfer, Orders, Minting, Distribution)
                - Digital Goods (Listing, Delisting, Price Change, Quantity Change, Purchase, Delivery, Feedback, Refund)
                - Account Control (Leasing)
                - Mining (Reward Rec. Assignment, Commitment)
                - Advanced Payments (Escrow, Subscriptions)
                - Automated Transactions (ATs)

                The progress bar displays:
                - All Txs/Block (MA): Current Moving Average (MA) value calculated over the last %d blocks.
                - max: Maximum Moving Average (MA) value observed in the current chart history window (%d blocks).
                - Bar length: Indicates the current All Txs/Block (MA) relative to the maximum observed All Txs/Block (MA) in the current chart history window (%d blocks).
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE, CHART_HISTORY_SIZE);
        txPerBlockLabel = createLabel("All Txs/Block (MA)", COLOR_ALL_TX_PER_BLOCK, tooltip);
        allTransactionsPerBlockProgressBar = createProgressBar(0, 255, null, "0.00 - max: 0.00", progressBarSize1);
        addComponent(SyncPanel, txPerBlockLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(SyncPanel, allTransactionsPerBlockProgressBar, 1, yPos++, 1, 0, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // System Transactions/Block (Moving Average)
        tooltip = """
                The moving average of system-generated transactions included in each block. This includes payments from Automated Transactions (ATs), Escrow results, and Subscription payments.

                The progress bar displays:
                - System Txs/Block (MA): Current Moving Average (MA) value calculated over the last %d blocks.
                - max: Maximum Moving Average (MA) value observed in the current chart history window (%d blocks).
                - Bar length: Indicates the current System Txs/Block (MA) relative to the maximum observed System Txs/Block (MA) in the current chart history window (%d blocks).
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE, CHART_HISTORY_SIZE);
        systemTxPerBlockLabel = createLabel("System Txs/Block (MA)", COLOR_SYSTEM_TX_PER_BLOCK, tooltip);
        systemTransactionsPerBlockProgressBar = createProgressBar(0, 255, null, "0.00 - max: 0.00", progressBarSize1);
        addComponent(SyncPanel, systemTxPerBlockLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(SyncPanel, systemTransactionsPerBlockProgressBar, 1, yPos++, 1, 0, 0,
                GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // ATs/Block (Moving Average)
        tooltip = """
                The moving average of the number of Automated Transactions (ATs) executed per block. This metric shows the activity level of smart contracts on the network.

                The progress bar displays:
                - ATs/Block (MA): Current Moving Average (MA) value calculated over the last %d blocks.
                - max: Maximum Moving Average (MA) value observed in the current chart history window (%d blocks).
                - Bar length: Indicates the current ATs/Block (MA) relative to the maximum observed ATs/Block (MA) in the current chart history window (%d blocks).
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE, CHART_HISTORY_SIZE);
        atCountLabel = createLabel("ATs/Block (MA)", COLOR_AT_COUNT_PER_BLOCK, tooltip); // Deep Pink
        atCountsPerBlockProgressBar = createProgressBar(0, 100, null, "0.00 - max: 0.00", progressBarSize1);
        addComponent(SyncPanel, atCountLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(SyncPanel, atCountsPerBlockProgressBar, 1, yPos++, 1, 0, 0, GridBagConstraints.LINE_START,
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

        // Add toggle listeners for performance chart
        addToggleListener(blocksPerSecondLabel, performanceChartPanel, "Blocks/Second (MA)");
        addToggleListener(txPerSecondLabel, performanceChartPanel, "All Txs/Sec (MA)");
        addToggleListener(systemTxPerSecondLabel, performanceChartPanel, "System Txs/Sec (MA)");
        addToggleListener(atCountLabel, performanceChartPanel, "ATs/Block (MA)");

        // End Performance Metrics Panel

        // === Timing Metrics Panel ===
        JPanel timingMetricsPanel = new JPanel(new GridBagLayout());

        JPanel timingInfoPanel = new JPanel(new GridBagLayout());
        yPos = 0;

        Insets timerLabelInsets = new Insets(2, 5, 0, 0);

        // Push Time
        tooltip = """
                The moving average of the total time taken to process and push a new block.
                This value is the sum of all individual timing components measured during block processing.

                It includes:
                - Validation Time
                - TX Loop Time
                - Housekeeping Time
                - TX Apply Time
                - AT Time
                - Subscription Time
                - Block Apply Time
                - Commit Time
                - Misc. Time

                The progress bar displays:
                - Push Time (MA): Current Moving Average (MA) value in [ms] calculated over the last %d blocks.
                - max: Maximum Moving Average (MA) value in [ms] observed in the current chart history window (%d blocks).
                - Bar length: Indicates the current Push Time (MA) [ms] relative to the maximum observed Push Time (MA) in the current chart history window (%d blocks).
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE, CHART_HISTORY_SIZE);
        pushTimeLabel = createLabel("Push Time (MA)", COLOR_PUSH_TIME, tooltip);
        pushTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms", progressBarSize1);

        // The toggle slider is now next to the payload bar, so we just add the label
        // here.
        addComponent(timingInfoPanel, pushTimeLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(timingInfoPanel, pushTimeProgressBar, 1, yPos++, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Validation Time
        tooltip = """
                The moving average of the time spent on block-level validation, excluding the per-transaction validation loop.
                This is a CPU-intensive task.

                Measured steps include:
                - Verifying block version and timestamp
                - Checking previous block reference
                - Verifying block and generation signatures
                - Validating payload hash and total amounts/fees after transaction processing

                The progress bar displays:
                - Validation Time (MA): Current Moving Average (MA) value in [ms] calculated over the last %d blocks.
                - max: Maximum Moving Average (MA) value in [ms] observed in the current chart history window (%d blocks).
                - Percentage [%%]: The component's contribution to the 'Push Time (MA)' in percentage.
                - Bar length: Indicates the current Validation Time (MA) relative to Push Time (MA) which value is shown by the Percentage [%%].
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE);
        validationTimeLabel = createLabel("Validation Time (MA)", COLOR_VALIDATION_TIME, tooltip);
        validationTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms | 0%", progressBarSize1);
        addComponent(timingInfoPanel, validationTimeLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(timingInfoPanel, validationTimeProgressBar, 1, yPos++, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // TX Loop Time
        tooltip = """
                The moving average of the time spent iterating through and validating all transactions within a block.
                This involves both CPU and database read operations.

                Measured steps for each transaction include:
                - Checking timestamps and deadlines
                - Verifying signatures and public keys
                - Validating referenced transactions
                - Checking for duplicates
                - Executing transaction-specific business logic

                The progress bar displays:
                - TX Loop Time (MA): Current Moving Average (MA) value in [ms] calculated over the last %d blocks.
                - max: Maximum Moving Average (MA) value in [ms] observed in the current chart history window (%d blocks).
                - Percentage [%%]: The component's contribution to the 'Push Time (MA)' in percentage.
                - Bar length: Indicates the current TX Loop Time (MA) relative to Push Time (MA) which value is shown by the Percentage [%%].
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE);
        txLoopTimeLabel = createLabel("TX Loop Time (MA)", COLOR_TX_LOOP_TIME, tooltip);
        txLoopTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms | 0%", progressBarSize1);
        addComponent(timingInfoPanel, txLoopTimeLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(timingInfoPanel, txLoopTimeProgressBar, 1, yPos++, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Housekeeping Time
        tooltip = """
                The moving average of the time spent on various 'housekeeping' tasks during block processing.
                This ensures the node's state remains consistent.

                Measured steps include:
                - Re-queuing unconfirmed transactions not included in the new block
                - Updating peer states
                - Other miscellaneous maintenance tasks

                The progress bar displays:
                - Housekeeping Time (MA): Current Moving Average (MA) value in [ms] calculated over the last %d blocks.
                - max: Maximum Moving Average (MA) value in [ms] observed in the current chart history window (%d blocks).
                - Percentage [%%]: The component's contribution to the 'Push Time (MA)' in percentage.
                - Bar length: Indicates the current Housekeeping Time (MA) relative to Push Time (MA) which value is shown by the Percentage [%%].
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE);
        housekeepingTimeLabel = createLabel("Housekeeping Time (MA)", COLOR_HOUSEKEEPING_TIME, tooltip);
        housekeepingTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms | 0%", progressBarSize1);
        addComponent(timingInfoPanel, housekeepingTimeLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(timingInfoPanel, housekeepingTimeProgressBar, 1, yPos++, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // TX Apply Time
        tooltip = """
                The moving average of the time spent applying the effects of each transaction within the block to the in-memory state.
                This step handles changes to account balances, aliases, assets, etc., based on the transaction type.

                Measured steps include:
                - Updating sender and recipient balances
                - Applying asset transfers and order updates
                - Updating alias data
                - Processing other transaction-specific state changes

                The progress bar displays:
                - TX Apply Time (MA): Current Moving Average (MA) value in [ms] calculated over the last %d blocks.
                - max: Maximum Moving Average (MA) value in [ms] observed in the current chart history window (%d blocks).
                - Percentage [%%]: The component's contribution to the 'Push Time (MA)' in percentage.
                - Bar length: Indicates the current TX Apply Time (MA) relative to Push Time (MA) which value is shown by the Percentage [%%].
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE);
        txApplyTimeLabel = createLabel("TX Apply Time (MA)", COLOR_TX_APPLY_TIME, tooltip);
        txApplyTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms | 0%", progressBarSize1);
        addComponent(timingInfoPanel, txApplyTimeLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(timingInfoPanel, txApplyTimeProgressBar, 1, yPos++, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // AT Time
        tooltip = """
                The moving average of the time spent validating and processing all Automated Transactions (ATs) within the block.
                This is a separate computational step that occurs after 'TX Apply Time'.

                Measured steps include:
                - Loading ATs associated with the block
                - Executing AT code (smart contracts)
                - Applying state changes resulting from AT execution

                The progress bar displays:
                - AT Time (MA): Current Moving Average (MA) value in [ms] calculated over the last %d blocks.
                - max: Maximum Moving Average (MA) value in [ms] observed in the current chart history window (%d blocks).
                - Percentage [%%]: The component's contribution to the 'Push Time (MA)' in percentage.
                - Bar length: Indicates the current AT Time (MA) relative to Push Time (MA) which value is shown by the Percentage [%%].
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE);
        atTimeLabel = createLabel("AT Time (MA)", COLOR_AT_TIME, tooltip);
        atTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms | 0%", progressBarSize1);
        addComponent(timingInfoPanel, atTimeLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(timingInfoPanel, atTimeProgressBar, 1, yPos++, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Subscription Time
        tooltip = """
                The moving average of the time spent processing recurring subscription payments for the block.
                This is a separate step that occurs after AT processing.

                Measured steps include:
                - Identifying active subscriptions due for payment
                - Verifying sufficient balances
                - Executing subscription payments

                The progress bar displays:
                - Subscription Time (MA): Current Moving Average (MA) value in [ms] calculated over the last %d blocks.
                - max: Maximum Moving Average (MA) value in [ms] observed in the current chart history window (%d blocks).
                - Percentage [%%]: The component's contribution to the 'Push Time (MA)' in percentage.
                - Bar length: Indicates the current Subscription Time (MA) relative to Push Time (MA) which value is shown by the Percentage [%%].
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE);
        subscriptionTimeLabel = createLabel("Subscription Time (MA)", COLOR_SUBSCRIPTION_TIME, tooltip); // Hot pink
        subscriptionTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms | 0%", progressBarSize1);
        addComponent(timingInfoPanel, subscriptionTimeLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(timingInfoPanel, subscriptionTimeProgressBar, 1, yPos++, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Block Apply Time
        tooltip = """
                The moving average of the time spent applying block-level changes.
                This is the final step before the 'Commit' phase.

                Measured steps include:
                - Distributing the block reward to the generator
                - Updating escrow services
                - Notifying listeners about the applied block

                The progress bar displays:
                - Block Apply Time (MA): Current Moving Average (MA) value in [ms] calculated over the last %d blocks.
                - max: Maximum Moving Average (MA) value in [ms] observed in the current chart history window (%d blocks).
                - Percentage [%%]: The component's contribution to the 'Push Time (MA)' in percentage.
                - Bar length: Indicates the current Block Apply Time (MA) relative to Push Time (MA) which value is shown by the Percentage [%%].
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE);
        blockApplyTimeLabel = createLabel("Block Apply Time (MA)", COLOR_BLOCK_APPLY_TIME, tooltip); // Teal
        blockApplyTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms | 0%", progressBarSize1);
        addComponent(timingInfoPanel, blockApplyTimeLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(timingInfoPanel, blockApplyTimeProgressBar, 1, yPos++, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Commit Time
        tooltip = """
                The moving average of the time spent committing all in-memory state changes to the database on disk.
                This is a disk I/O-intensive operation.

                Measured steps include:
                - Flushing dirty pages to disk
                - Updating database indexes
                - Committing the database transaction

                The progress bar displays:
                - Commit Time (MA): Current Moving Average (MA) value in [ms] calculated over the last %d blocks.
                - max: Maximum Moving Average (MA) value in [ms] observed in the current chart history window (%d blocks).
                - Percentage [%%]: The component's contribution to the 'Push Time (MA)' in percentage.
                - Bar length: Indicates the current Commit Time (MA) relative to Push Time (MA) which value is shown by the Percentage [%%].
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE);
        commitTimeLabel = createLabel("Commit Time (MA)", COLOR_COMMIT_TIME, tooltip);
        commitTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms | 0%", progressBarSize1);
        addComponent(timingInfoPanel, commitTimeLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(timingInfoPanel, commitTimeProgressBar, 1, yPos++, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);

        // Misc. Time
        tooltip = """
                The moving average of the time spent on miscellaneous operations not explicitly measured in other timing categories.
                This value is the difference between the 'Total Push Time' and the sum of all other measured components.

                It captures overhead from:
                - Memory management (GC)
                - Thread context switching
                - Other background tasks not individually timed

                The progress bar displays:
                - Misc. Time (MA): Current Moving Average (MA) value in [ms] calculated over the last %d blocks.
                - max: Maximum Moving Average (MA) value in [ms] observed in the current chart history window (%d blocks).
                - Percentage [%%]: The component's contribution to the 'Push Time (MA)' in percentage.
                - Bar length: Indicates the current Misc. Time (MA) relative to Push Time (MA) which value is shown by the Percentage [%%].
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE);
        miscTimeLabel = createLabel("Misc. Time (MA)", COLOR_MISC_TIME, tooltip);
        miscTimeProgressBar = createProgressBar(0, 100, null, "0 ms - max: 0 ms | 0%", progressBarSize1);
        addComponent(timingInfoPanel, miscTimeLabel, 0, yPos, 1, 0, 0, GridBagConstraints.LINE_END,
                GridBagConstraints.NONE, labelInsets);
        addComponent(timingInfoPanel, miscTimeProgressBar, 1, yPos++, 1, 1, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.HORIZONTAL, barInsets);
        // --- Payload Fullness Panel (spanning below both columns) ---
        JPanel payloadPanel = new JPanel(new GridBagLayout());
        payloadFullnessLabel = createLabel("Payload Fullness (MA)", COLOR_PAYLOAD_FULLNESS, null); // Tooltip is set in
                                                                                                   // init()
        payloadFullnessProgressBar = createProgressBar(0, 100, null,
                "000.00% - C: 000% (000000 / 0 bytes) - min: 000% - max: 000%", wideProgressBarSize); // by layout

        // Add payloadPanel to timingMetricsPanel, spanning 2 columns
        addComponent(payloadPanel, payloadFullnessLabel, 0, 0, 1, 0, 0, GridBagConstraints.LINE_START,
                GridBagConstraints.NONE, timerLabelInsets);
        addComponent(payloadPanel, payloadFullnessProgressBar, 1, 0, 1, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.HORIZONTAL, timerLabelInsets);
        addComponent(timingMetricsPanel, payloadPanel, 0, 1, 2, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.HORIZONTAL, new Insets(5, 0, 0, 0));

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
        netSpeedChartContainer.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        netSpeedChartContainer.add(uploadChartPanel);
        netSpeedChartContainer.add(downloadChartPanel);

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
        tooltip = """
                The moving average of data upload speed to other peers in the network.

                This reflects how much data your node is sharing, which includes:
                - Blocks
                - Transactions
                - Peer information

                The progress bar displays:
                - Upload Speed (MA): Current Moving Average (MA) speed calculated over the last %d seconds.
                - max: Maximum observed Upload Speed (MA) in the current chart history window (%d seconds).
                - Bar length: Indicates the current Upload Speed (MA) relative to the maximum observed Upload Speed (MA) in the current chart history window (%d seconds).
                """
                .formatted(movingAverageWindow * netSpeedUpdateTime / 1000,
                        SPEED_HISTORY_SIZE * netSpeedUpdateTime / 1000, SPEED_HISTORY_SIZE * netSpeedUpdateTime / 1000);
        uploadSpeedLabel = createLabel("▲ Speed (MA)", COLOR_UPLOAD_SPEED, tooltip);
        uploadSpeedProgressBar = createProgressBar(0, MAX_SPEED_BPS, null, "0.00 B/s", progressBarSize2);
        uploadSpeedPanel.add(uploadSpeedLabel);
        uploadSpeedPanel.add(uploadSpeedProgressBar);
        addComponent(netSpeedInfoPanel, uploadSpeedPanel, 0, 1, 2, 0, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, labelInsets);

        // --- Download Speed ---
        JPanel downloadSpeePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        downloadSpeePanel.setOpaque(false);
        tooltip = """
                The moving average of data download speed from other peers in the network.

                This indicates how quickly your node is receiving data, which includes:
                - Blocks
                - Transactions
                - Peer information

                The progress bar displays:
                - Download Speed (MA): Current Moving Average (MA) speed calculated over the last %d seconds.
                - max: Maximum observed Download Speed (MA) in the current chart history window (%d seconds).
                - Bar length: Indicates the current Download Speed (MA) relative to the maximum observed Download Speed (MA) in the current chart history window (%d seconds).
                """
                .formatted(movingAverageWindow * netSpeedUpdateTime / 1000,
                        SPEED_HISTORY_SIZE * netSpeedUpdateTime / 1000, SPEED_HISTORY_SIZE * netSpeedUpdateTime / 1000);
        downloadSpeedLabel = createLabel("▼ Speed (MA)", COLOR_DOWNLOAD_SPEED, tooltip);
        downloadSpeedProgressBar = createProgressBar(0, MAX_SPEED_BPS, null, "0.00 B/s", progressBarSize2);
        downloadSpeePanel.add(downloadSpeedLabel);
        downloadSpeePanel.add(downloadSpeedProgressBar);
        addComponent(netSpeedInfoPanel, downloadSpeePanel, 0, 2, 2, 0, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, labelInsets);

        // --- Combined Volume ---
        JPanel combinedVolumePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        combinedVolumePanel.setOpaque(false);
        tooltip = """
                The total amount of data uploaded to and downloaded from the network during this session. The format is Uploaded / Downloaded.
                """;
        JLabel volumeTitleLabel = createLabel("Volume", null, tooltip);
        tooltip = """
                The total amount of data uploaded to the network during this session.
                """;
        metricsUploadVolumeLabel = createLabel("", COLOR_UPLOAD_VOLUME, tooltip);
        tooltip = """
                The total amount of data downloaded from the network during this session.
                """;
        metricsDownloadVolumeLabel = createLabel("", COLOR_DOWNLOAD_VOLUME, tooltip);

        combinedVolumePanel.add(volumeTitleLabel);
        combinedVolumePanel.add(metricsUploadVolumeLabel);
        combinedVolumePanel.add(new JLabel(" / "));
        combinedVolumePanel.add(metricsDownloadVolumeLabel);
        addComponent(netSpeedInfoPanel, combinedVolumePanel, 0, 3, 2, 1, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, barInsets);

        // --- Moving Average Window ---
        JPanel maWindowPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        maWindowPanel.setOpaque(false);
        tooltip = """
                The number of recent blocks used to calculate the moving average (MA) for the displayed metrics.
                A larger window produces a smoother but less responsive trend, while a smaller window reacts more quickly to recent changes.
                """;
        JLabel maWindowLabel = createLabel("MA Window", null, tooltip);
        maWindowPanel.add(maWindowLabel);

        final int[] maWindowValues = { 10, 100, 200, 300, 400, 500 };
        int initialSliderValue = 1; // Default to 100
        for (int i = 0; i < maWindowValues.length; i++) {
            if (movingAverageWindow == maWindowValues[i]) {
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
                movingAverageWindow = newValue;
                blockTimestamps.setWindowSize(movingAverageWindow);
                allTransactionsPerBlock.setWindowSize(movingAverageWindow);
                systemTransactionsPerBlock.setWindowSize(movingAverageWindow);
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
                atCountsPerBlock.setWindowSize(movingAverageWindow);
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
                    zoomIn();
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
                    zoomOut();
                }
            }
        });

        GridBagConstraints gbcZoom = new GridBagConstraints();
        gbcZoom.gridx = 0;
        gbcZoom.gridy = 0;
        gbcZoom.insets = new Insets(0, 0, 0, 5);
        zoomPanel.add(zoomOutLabel, gbcZoom);
        gbcZoom.gridx = 1;
        zoomPanel.add(zoomInLabel, gbcZoom);
        maWindowPanel.add(zoomPanel);
        addComponent(netSpeedInfoPanel, maWindowPanel, 0, 4, 2, 0, 0, GridBagConstraints.CENTER,
                GridBagConstraints.NONE, new Insets(2, 0, 2, 0));

        netSpeedChartContainer.add(netSpeedInfoPanel);
        addComponent(this, netSpeedChartContainer, 3, 0, 1, 1.0, 0, GridBagConstraints.NORTH,
                GridBagConstraints.HORIZONTAL, new Insets(0, 5, 0, 0));

        // Add performanceMetricsPanel to main metrics panel
        addComponent(this, performanceMetricsPanel, 0, 0, 1, 0, 0, GridBagConstraints.NORTH,
                GridBagConstraints.NONE, new Insets(0, 0, 0, 0));

        // Vertical Separator
        JSeparator mainVerticalSeparator = new JSeparator(SwingConstants.VERTICAL);
        addComponent(this, mainVerticalSeparator, 1, 0, 1, 0, 1, GridBagConstraints.CENTER,
                GridBagConstraints.VERTICAL, new Insets(0, 5, 0, 5));

        // Add timingMetricsPanel to main metrics panel
        addComponent(this, timingMetricsPanel, 2, 0, 1, 0, 0, GridBagConstraints.NORTH,
                GridBagConstraints.NONE, new Insets(0, 0, 0, 0));
        // END Metrics Panel

        addToggleListener(pushTimeLabel, timingChartPanel, "Push Time (MA)");
        addToggleListener(validationTimeLabel, timingChartPanel, "Validation Time (MA)");
        addToggleListener(txLoopTimeLabel, timingChartPanel, "TX Loop Time (MA)");
        addToggleListener(housekeepingTimeLabel, timingChartPanel, "Housekeeping Time (MA)");
        addToggleListener(txApplyTimeLabel, timingChartPanel, "TX Apply Time (MA)");
        addToggleListener(atTimeLabel, timingChartPanel, "AT Time (MA)");
        addToggleListener(subscriptionTimeLabel, timingChartPanel, "Subscription Time (MA)");
        addToggleListener(blockApplyTimeLabel, timingChartPanel, "Block Apply Time (MA)");
        addToggleListener(commitTimeLabel, timingChartPanel, "Commit Time (MA)");
        addToggleListener(miscTimeLabel, timingChartPanel, "Misc. Time (MA)");
        addToggleListener(payloadFullnessLabel, timingChartPanel, "Payload Fullness (MA)");
        addToggleListener(uploadSpeedLabel, uploadChartPanel, "Upload Speed");
        addToggleListener(downloadSpeedLabel, downloadChartPanel, "Download Speed");
        addPaintToggleListener(systemTxPerBlockLabel, performanceChartPanel, "System Txs/Block (MA)", timingChartPanel,
                "System Txs/Block (MA)",
                COLOR_SYSTEM_TX_PER_BLOCK);
        addPaintToggleListener(txPerBlockLabel, performanceChartPanel, "All Txs/Block (MA)", timingChartPanel,
                "All Txs/Block (MA)",
                COLOR_ALL_TX_PER_BLOCK);

        addPaintToggleListener(metricsUploadVolumeLabel, uploadChartPanel, "Upload Volume", COLOR_UPLOAD_VOLUME);
        addPaintToggleListener(metricsDownloadVolumeLabel, downloadChartPanel, "Download Volume",
                COLOR_DOWNLOAD_VOLUME);

        // Timer to periodically update the network speed chart so it flows even with no
        // traffic
        netSpeedChartUpdater = new Timer(netSpeedUpdateTime, e -> {
            updateNetVolumeAndSpeedChart(uploadedVolume, downloadedVolume);
        });
        netSpeedChartUpdater.start();
    }

    private void initListeners() {
        BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
        if (blockchainProcessor != null) {
            blockchainProcessor.addListener(block -> onQueueStatus(), BlockchainProcessor.Event.QUEUE_STATUS_CHANGED);
            blockchainProcessor.addListener(block -> onForkCacheChanged(),
                    BlockchainProcessor.Event.FORK_CACHE_CHANGED);
            blockchainProcessor.addListener(block -> onNetVolumeChanged(),
                    BlockchainProcessor.Event.NET_VOLUME_CHANGED);
            blockchainProcessor.addPerformanceStatsListener(this::onPerformanceStatsUpdated);
            blockchainProcessor.addListener(this::onBlockPopped, BlockchainProcessor.Event.BLOCK_MANUAL_POPPED);
            blockchainProcessor.addListener(this::onBlockPopped, BlockchainProcessor.Event.BLOCK_AUTO_POPPED);

            brs.TransactionProcessor transactionProcessor = Signum.getTransactionProcessor();
            transactionProcessor.addListener(transactions -> onUnconfirmedTransactionCountChanged(),
                    brs.TransactionProcessor.Event.ADDED_UNCONFIRMED_TRANSACTIONS);
            transactionProcessor.addListener(transactions -> onUnconfirmedTransactionCountChanged(),
                    brs.TransactionProcessor.Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
        }
    }

    public void shutdown() {
        chartUpdateExecutor.shutdown();
        if (netSpeedChartUpdater != null) {
            netSpeedChartUpdater.stop();
        }
    }

    public void onQueueStatus() {
        chartUpdateExecutor.submit(() -> {
            BlockchainProcessor.QueueStatus status = Signum.getBlockchainProcessor().getQueueStatus();
            if (status != null) {
                SwingUtilities.invokeLater(() -> updateQueueStatus(status.unverifiedSize,
                        status.verifiedSize, status.totalSize, status.cacheFullness));
            }
        });
    }

    public void onForkCacheChanged() {
        chartUpdateExecutor.submit(() -> {
            int forkCacheSize = Signum.getBlockchainProcessor().getForkCacheSize();
            SwingUtilities.invokeLater(() -> updateForkCacheStatus(forkCacheSize));
        });
    }

    public void onUnconfirmedTransactionCountChanged() {
        chartUpdateExecutor.submit(() -> {
            int count = Signum.getTransactionProcessor().getAmountUnconfirmedTransactions();
            SwingUtilities.invokeLater(() -> updateUnconfirmedTxCount(count));
        });
    }

    public void onNetVolumeChanged() {
        BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
        this.uploadedVolume = blockchainProcessor.getUploadedVolume();
        this.downloadedVolume = blockchainProcessor.getDownloadedVolume();
    }

    public void onPerformanceStatsUpdated(BlockchainProcessor.PerformanceStats stats) {
        if (stats != null) {
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

    private void addPaintToggleListener(JLabel label, ChartPanel chartPanel, String seriesKey,
            Color originalColor) {
        final Color transparentColor = new Color(0, 0, 0, 0);
        addLabelToggleListener(label,
                isVisible -> toggleSeriesPaint(chartPanel, seriesKey, isVisible, originalColor, transparentColor));
    }

    private void addPaintToggleListener(JLabel label,
            ChartPanel chartPanel1, String seriesKey1,
            ChartPanel chartPanel2, String seriesKey2,
            Color originalColor) {
        final Color transparentColor = new Color(0, 0, 0, 0);
        addLabelToggleListener(label, isVisible -> {
            toggleSeriesPaint(chartPanel1, seriesKey1, isVisible, originalColor, transparentColor);
            toggleSeriesPaint(chartPanel2, seriesKey2, isVisible, originalColor, transparentColor);
        });
    }

    private void toggleSeriesPaint(ChartPanel chartPanel, String seriesKey, boolean isVisible, Color originalColor,
            Color transparentColor) {
        XYPlot plot = chartPanel.getChart().getXYPlot();
        int datasetCount = plot.getDatasetCount();
        for (int i = 0; i < datasetCount; i++) {
            XYDataset dataset = plot.getDataset(i);
            if (dataset instanceof XYSeriesCollection) {
                XYSeriesCollection seriesCollection = (XYSeriesCollection) dataset;
                int seriesIndex = seriesCollection.getSeriesIndex(seriesKey);
                if (seriesIndex >= 0) {
                    org.jfree.chart.renderer.xy.AbstractXYItemRenderer renderer = (org.jfree.chart.renderer.xy.AbstractXYItemRenderer) plot
                            .getRenderer(i);
                    if (renderer != null) {
                        renderer.setSeriesVisible(seriesIndex, isVisible);
                        renderer.setSeriesPaint(seriesIndex, isVisible ? originalColor : transparentColor);
                    }
                    break;
                }
            }
        }
    }

    private void onBlockPopped(Block block) {
        int height = block.getHeight();
        chartUpdateExecutor.submit(() -> {
            SwingUtilities.invokeLater(() -> {
                lastTimingData = null;
                lastPerformanceData = null;
                lastSharedData = null;
                setPerformanceChartsNotification(false);
                try {
                    truncateSeries(blocksPerSecondSeries, height);
                    truncateSeries(allTransactionsPerSecondSeries, height);
                    truncateSeries(systemTransactionsPerSecondSeries, height);
                    truncateSeries(atCountPerBlockSeries, height);

                    truncateSeries(allTransactionsPerBlockSeries, height);
                    truncateSeries(systemTransactionsPerBlockSeries, height);

                    truncateSeries(pushTimePerBlockSeries, height);
                    truncateSeries(validationTimePerBlockSeries, height);
                    truncateSeries(txLoopTimePerBlockSeries, height);
                    truncateSeries(housekeepingTimePerBlockSeries, height);
                    truncateSeries(txApplyTimePerBlockSeries, height);
                    truncateSeries(atTimePerBlockSeries, height);
                    truncateSeries(subscriptionTimePerBlockSeries, height);
                    truncateSeries(blockApplyTimePerBlockSeries, height);
                    truncateSeries(commitTimePerBlockSeries, height);
                    truncateSeries(miscTimePerBlockSeries, height);
                    truncateSeries(payloadFullnessSeries, height);

                    if (!uiOptimizationEnabled || isTabActive) {
                        updateChartRanges();
                        updateProgressBarsFromSeries();
                    }
                } finally {
                    if (!uiOptimizationEnabled || isTabActive) {
                        setPerformanceChartsNotification(true);
                    }
                }
            });
        });
    }

    private void truncateSeries(XYSeries series, int height) {
        if (series == null)
            return;
        while (!series.isEmpty()) {
            if (series.getX(series.getItemCount() - 1).doubleValue() > height) {
                series.remove(series.getItemCount() - 1);
            } else {
                break;
            }
        }
    }

    private void updateProgressBarsFromSeries() {
        // Timing Metrics
        double pushTime = getLastValue(pushTimePerBlockSeries);
        double pushTimeMax = getMaxValue(pushTimePerBlockSeries);

        double validationTime = getLastValue(validationTimePerBlockSeries);
        double validationTimeMax = getMaxValue(validationTimePerBlockSeries);

        double txLoopTime = getLastValue(txLoopTimePerBlockSeries);
        double txLoopTimeMax = getMaxValue(txLoopTimePerBlockSeries);

        double housekeepingTime = getLastValue(housekeepingTimePerBlockSeries);
        double housekeepingTimeMax = getMaxValue(housekeepingTimePerBlockSeries);

        double txApplyTime = getLastValue(txApplyTimePerBlockSeries);
        double txApplyTimeMax = getMaxValue(txApplyTimePerBlockSeries);

        double atTime = getLastValue(atTimePerBlockSeries);
        double atTimeMax = getMaxValue(atTimePerBlockSeries);

        double subscriptionTime = getLastValue(subscriptionTimePerBlockSeries);
        double subscriptionTimeMax = getMaxValue(subscriptionTimePerBlockSeries);

        double blockApplyTime = getLastValue(blockApplyTimePerBlockSeries);
        double blockApplyTimeMax = getMaxValue(blockApplyTimePerBlockSeries);

        double commitTime = getLastValue(commitTimePerBlockSeries);
        double commitTimeMax = getMaxValue(commitTimePerBlockSeries);

        double miscTime = getLastValue(miscTimePerBlockSeries);
        double miscTimeMax = getMaxValue(miscTimePerBlockSeries);

        // Percentages
        long validationPct = (pushTime > 0) ? Math.round(100.0 * validationTime / pushTime) : 0;
        long txLoopPct = (pushTime > 0) ? Math.round(100.0 * txLoopTime / pushTime) : 0;
        long housekeepingPct = (pushTime > 0) ? Math.round(100.0 * housekeepingTime / pushTime) : 0;
        long txApplyPct = (pushTime > 0) ? Math.round(100.0 * txApplyTime / pushTime) : 0;
        long atPct = (pushTime > 0) ? Math.round(100.0 * atTime / pushTime) : 0;
        long subscriptionPct = (pushTime > 0) ? Math.round(100.0 * subscriptionTime / pushTime) : 0;
        long blockApplyPct = (pushTime > 0) ? Math.round(100.0 * blockApplyTime / pushTime) : 0;
        long commitPct = (pushTime > 0) ? Math.round(100.0 * commitTime / pushTime) : 0;
        long miscPct = (pushTime > 0) ? Math.round(100.0 * miscTime / pushTime) : 0;

        updateProgressBar(pushTimeProgressBar, pushTime, pushTimeMax,
                val -> String.format("%.0f ms - max: %.0f ms", val, pushTimeMax));
        updateProgressBar(validationTimeProgressBar, validationPct, 100,
                val -> String.format("%.0f ms - max: %.0f ms | %.0f%%", validationTime, validationTimeMax, val));
        updateProgressBar(txLoopTimeProgressBar, txLoopPct, 100,
                val -> String.format("%.0f ms - max: %.0f ms | %.0f%%", txLoopTime, txLoopTimeMax, val));
        updateProgressBar(housekeepingTimeProgressBar, housekeepingPct, 100,
                val -> String.format("%.0f ms - max: %.0f ms | %.0f%%", housekeepingTime, housekeepingTimeMax, val));
        updateProgressBar(commitTimeProgressBar, commitPct, 100,
                val -> String.format("%.0f ms - max: %.0f ms | %.0f%%", commitTime, commitTimeMax, val));
        updateProgressBar(atTimeProgressBar, atPct, 100,
                val -> String.format("%.0f ms - max: %.0f ms | %.0f%%", atTime, atTimeMax, val));
        updateProgressBar(txApplyTimeProgressBar, txApplyPct, 100,
                val -> String.format("%.0f ms - max: %.0f ms | %.0f%%", txApplyTime, txApplyTimeMax, val));
        updateProgressBar(subscriptionTimeProgressBar, subscriptionPct, 100,
                val -> String.format("%.0f ms - max: %.0f ms | %.0f%%", subscriptionTime, subscriptionTimeMax, val));
        updateProgressBar(blockApplyTimeProgressBar, blockApplyPct, 100,
                val -> String.format("%.0f ms - max: %.0f ms | %.0f%%", blockApplyTime, blockApplyTimeMax, val));
        updateProgressBar(miscTimeProgressBar, miscPct, 100,
                val -> String.format("%.0f ms - max: %.0f ms | %.0f%%", miscTime, miscTimeMax, val));

        double payloadFullness = getLastValue(payloadFullnessSeries);
        double payloadFullnessMax = getMaxValue(payloadFullnessSeries);
        double payloadFullnessMin = getMinValue(payloadFullnessSeries);
        updateProgressBar(payloadFullnessProgressBar, payloadFullness, 100,
                val -> String.format("MA: %.2f%% - min: %.0f%% - max: %.0f%%", val, payloadFullnessMin,
                        payloadFullnessMax));

        // Performance Metrics
        double blocksPerSec = getLastValue(blocksPerSecondSeries);
        double blocksPerSecMax = getMaxValue(blocksPerSecondSeries);
        updateProgressBar(blocksPerSecondProgressBar, blocksPerSec, blocksPerSecMax,
                val -> String.format("%.2f - max: %.2f", val, blocksPerSecMax), 100);

        double allTxPerSec = getLastValue(allTransactionsPerSecondSeries);
        double allTxPerSecMax = getMaxValue(allTransactionsPerSecondSeries);
        updateProgressBar(allTransactionsPerSecondProgressBar, allTxPerSec, allTxPerSecMax,
                val -> String.format("%.2f - max: %.2f", val, allTxPerSecMax), 100);

        double sysTxPerSec = getLastValue(systemTransactionsPerSecondSeries);
        double sysTxPerSecMax = getMaxValue(systemTransactionsPerSecondSeries);
        updateProgressBar(systemTransactionsPerSecondProgressBar, sysTxPerSec, sysTxPerSecMax,
                val -> String.format("%.2f - max: %.2f", val, sysTxPerSecMax), 100);

        double atCount = getLastValue(atCountPerBlockSeries);
        double atCountMax = getMaxValue(atCountPerBlockSeries);
        updateProgressBar(atCountsPerBlockProgressBar, atCount, atCountMax,
                val -> String.format("%.2f - max: %.2f", val, atCountMax), 100);

        double allTxPerBlock = getLastValue(allTransactionsPerBlockSeries);
        double allTxPerBlockMax = getMaxValue(allTransactionsPerBlockSeries);
        updateProgressBar(allTransactionsPerBlockProgressBar, allTxPerBlock, allTxPerBlockMax,
                val -> String.format("%.2f - max: %.2f", val, allTxPerBlockMax), 100);

        double sysTxPerBlock = getLastValue(systemTransactionsPerBlockSeries);
        double sysTxPerBlockMax = getMaxValue(systemTransactionsPerBlockSeries);
        updateProgressBar(systemTransactionsPerBlockProgressBar, sysTxPerBlock, sysTxPerBlockMax,
                val -> String.format("%.2f - max: %.2f", val, sysTxPerBlockMax), 100);
    }

    private void updateForkCacheStatus(int forkCacheSize) {
        forkCacheProgressBar.setValue(forkCacheSize);
        forkCacheProgressBar.setString(forkCacheSize + " / " + brs.Constants.MAX_ROLLBACK);
    }

    private void updateUnconfirmedTxCount(int count) {
        unconfirmedTxsProgressBar.setValue(count);
        unconfirmedTxsProgressBar.setString(count + " / " + maxUnconfirmedTxs);
    }

    private void updateQueueStatus(int downloadCacheUnverifiedSize,
            int downloadCacheVerifiedSize,
            int downloadCacheTotalSize, int cacheFullness) {

        long cacheSizeBytes = (long) cacheFullness;
        long cacheCapacityBytes = (long) Signum.getPropertyService().getInt(Props.BRS_BLOCK_CACHE_MB) * 1024L * 1024L;

        double cacheSizeMB = cacheSizeBytes / (1024.0 * 1024.0);
        double cacheCapacityMB = cacheCapacityBytes / (1024.0 * 1024.0);
        int cacheFullnessPercentage = cacheCapacityBytes == 0 ? 0 : (int) (100.0 * cacheSizeBytes / cacheCapacityBytes);

        syncProgressBarDownloadedBlocks.setStringPainted(true);
        cacheFullnessProgressBar.setStringPainted(true);
        cacheFullnessProgressBar
                .setString(
                        String.format("%.2f / %.2f MB | %d%%", cacheSizeMB, cacheCapacityMB, cacheFullnessPercentage));
        cacheFullnessProgressBar.setMaximum(100);
        cacheFullnessProgressBar.setValue(cacheFullnessPercentage);
        syncProgressBarUnverifiedBlocks.setStringPainted(true);

        if (downloadCacheTotalSize != 0) {
            int percentage = (int) (100.0 * downloadCacheVerifiedSize / downloadCacheTotalSize);
            syncProgressBarDownloadedBlocks.setString(String.format("%d / %d | %d%%", downloadCacheVerifiedSize,
                    downloadCacheTotalSize, percentage));
            syncProgressBarDownloadedBlocks.setValue(percentage);

        } else {
            syncProgressBarDownloadedBlocks.setString("0 / 0 | 0%");
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
            uploadSpeeds.add(currentUploadSpeed);
            downloadSpeeds.add(currentDownloadSpeed);

            int currentWindowSize = Math.min(uploadSpeeds.size(), movingAverageWindow);
            if (currentWindowSize < 1) {
                return;
            }

            double avgUploadSpeed = uploadSpeeds.getAverage();
            double avgUploadSpeedMax = uploadSpeeds.getMax();
            double avgDownloadSpeed = downloadSpeeds.getAverage();
            double avgDownloadSpeedMax = downloadSpeeds.getMax();

            lastNetVolumeUpdateTime = currentTime;
            lastUploadedVolume = uploadedVolume;
            lastDownloadedVolume = downloadedVolume;

            // --- UI Updates on EDT ---
            Runnable updateTask = () -> updateNetSpeedUI(currentTime, uploadedVolume, downloadedVolume,
                    avgUploadSpeed, avgDownloadSpeed, avgUploadSpeedMax, avgDownloadSpeedMax);
            this.lastNetSpeedUpdate = updateTask;
            SwingUtilities.invokeLater(updateTask);
        });
    }

    private void updateNetSpeedUI(long currentTime, long uploadedVolume, long downloadedVolume, double avgUploadSpeed,
            double avgDownloadSpeed, double avgUploadSpeedMax, double avgDownloadSpeedMax) {
        setNetworkChartsNotification(false);
        try {
            if (uploadedVolume > 0 || downloadedVolume > 0) {
                updateChartSeries(uploadSpeedSeries, currentTime, avgUploadSpeed, SPEED_HISTORY_SIZE);
                updateChartSeries(downloadSpeedSeries, currentTime, avgDownloadSpeed, SPEED_HISTORY_SIZE);
                updateChartSeries(uploadVolumeSeries, currentTime, uploadedVolume, SPEED_HISTORY_SIZE);
                updateChartSeries(downloadVolumeSeries, currentTime, downloadedVolume, SPEED_HISTORY_SIZE);

                if (uiOptimizationEnabled && !isTabActive)
                    return;

                if (uploadChartPanel != null) {
                    double range = SPEED_HISTORY_SIZE * netSpeedUpdateTime;
                    uploadChartPanel.getChart().getXYPlot().getDomainAxis().setRange(currentTime - range, currentTime);
                }
                if (downloadChartPanel != null) {
                    double range = SPEED_HISTORY_SIZE * netSpeedUpdateTime;
                    downloadChartPanel.getChart().getXYPlot().getDomainAxis().setRange(currentTime - range,
                            currentTime);
                }
            }

            if (uiOptimizationEnabled && !isTabActive)
                return;

            if (metricsUploadVolumeLabel != null) {
                metricsUploadVolumeLabel.setText("▲ " + formatDataSize(uploadedVolume));
            }
            if (metricsDownloadVolumeLabel != null) {
                metricsDownloadVolumeLabel.setText("▼ " + formatDataSize(downloadedVolume));
            }

            updateProgressBar(uploadSpeedProgressBar, avgUploadSpeed, avgUploadSpeedMax, this::formatDataRate);
            updateProgressBar(downloadSpeedProgressBar, avgDownloadSpeed, avgDownloadSpeedMax, this::formatDataRate);
        } finally {
            if (!uiOptimizationEnabled || isTabActive) {
                setNetworkChartsNotification(true);
            }
        }
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
        // Run calculations sequentially on the current thread (chartUpdateExecutor)
        try {
            TimingUpdateData timingData = calculateTimingUpdate(stats);
            // calculateSharedBarChartUpdate updates MovingAverages used by
            // calculatePerformanceUpdate,
            // so order matters here.
            SharedBarChartUpdateData sharedBarChartData = calculateSharedBarChartUpdate(stats);
            PerformanceUpdateData performanceData = calculatePerformanceUpdate(stats);

            this.lastTimingData = timingData;
            this.lastPerformanceData = performanceData;
            this.lastSharedData = sharedBarChartData;

            if (Signum.getBlockchain() != null && stats.height > Signum.getBlockchain().getHeight()) {
                return;
            }

            // Schedule a single UI update on the EDT
            SwingUtilities.invokeLater(() -> {
                try {
                    // Disable chart notifications to batch updates and prevent GUI freezes
                    setPerformanceChartsNotification(false);

                    // Apply series updates (always, to keep history)
                    applySeriesUpdates(timingData.seriesUpdates);
                    applySeriesUpdates(performanceData.seriesUpdates);
                    applySeriesUpdates(sharedBarChartData.seriesUpdates);

                    if (!uiOptimizationEnabled || isTabActive) {
                        applyProgressBarUpdates(timingData.progressBarUpdates);
                        applyProgressBarUpdates(performanceData.progressBarUpdates);
                        applyProgressBarUpdates(sharedBarChartData.progressBarUpdates);
                        updateChartRanges();
                    }
                } finally {
                    // Re-enable chart notifications and trigger a repaint, even if an error
                    // occurred
                    if (!uiOptimizationEnabled || isTabActive) {
                        setPerformanceChartsNotification(true);
                    }
                }
            });
        } catch (Exception e) {
            LOGGER.error("Error updating charts", e);
        }
    }

    private void zoomIn() {
        int maxItems = 0;
        if (blocksPerSecondSeries != null && blocksPerSecondSeries.getItemCount() > 0) {
            maxItems = blocksPerSecondSeries.getItemCount();
        }

        if (maxItems > 0 && currentZoomRange > maxItems) {
            currentZoomRange = maxItems;
        }

        if (currentZoomRange > 100) {
            currentZoomRange -= 100;
        } else {
            currentZoomRange -= 10;
        }
        if (currentZoomRange < 10) {
            currentZoomRange = 10;
        }
        updateChartRanges();
    }

    private void zoomOut() {
        if (currentZoomRange < 100) {
            currentZoomRange += 10;
        } else {
            currentZoomRange += 100;
        }
        if (currentZoomRange > CHART_HISTORY_SIZE) {
            currentZoomRange = CHART_HISTORY_SIZE;
        }
        updateChartRanges();
    }

    private void updateChartRanges() {
        if (performanceChartPanel != null && blocksPerSecondSeries.getItemCount() > 0) {
            double lastX = blocksPerSecondSeries.getX(blocksPerSecondSeries.getItemCount() - 1).doubleValue();
            int itemCount = blocksPerSecondSeries.getItemCount();
            int range = Math.min(Math.max(itemCount, 10), currentZoomRange);
            performanceChartPanel.getChart().getXYPlot().getDomainAxis().setRange(lastX - range + 0.5, lastX + 0.5);
        }
        if (timingChartPanel != null && pushTimePerBlockSeries.getItemCount() > 0) {
            double lastX = pushTimePerBlockSeries.getX(pushTimePerBlockSeries.getItemCount() - 1).doubleValue();
            int itemCount = pushTimePerBlockSeries.getItemCount();
            int range = Math.min(Math.max(itemCount, 10), currentZoomRange);
            timingChartPanel.getChart().getXYPlot().getDomainAxis().setRange(lastX - range + 0.5, lastX + 0.5);
        }
    }

    private void setPerformanceChartsNotification(boolean enabled) {
        if (performanceChartPanel != null) {
            performanceChartPanel.getChart().getXYPlot().setNotify(enabled);
        }
        if (timingChartPanel != null) {
            timingChartPanel.getChart().getXYPlot().setNotify(enabled);
        }
    }

    private void setNetworkChartsNotification(boolean enabled) {
        if (uploadChartPanel != null) {
            uploadChartPanel.getChart().getXYPlot().setNotify(enabled);
        }
        if (downloadChartPanel != null) {
            downloadChartPanel.getChart().getXYPlot().setNotify(enabled);
        }
    }

    private void applyProgressBarUpdates(Map<JProgressBar, Runnable> progressBarUpdates) {
        progressBarUpdates.values().forEach(Runnable::run);
    }

    private void applySeriesUpdates(Map<XYSeries, Point.Double> seriesUpdates) {
        seriesUpdates.forEach(
                (series, point) -> updateChartSeries(series, point.x, point.y, CHART_HISTORY_SIZE));
    }

    private TimingUpdateData calculateTimingUpdate(BlockchainProcessor.PerformanceStats stats) {
        TimingUpdateData data = new TimingUpdateData();
        if (stats == null) {
            return data;
        }

        int blockHeight = stats.height;
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
        payloadSize.add(stats.payloadSize);

        long avgPushTime = Math.round(pushTimes.getAverage());
        long avgValidationTime = Math.round(validationTimes.getAverage());
        long avgTxLoopTime = Math.round(txLoopTimes.getAverage());
        long avgHousekeepingTime = Math.round(housekeepingTimes.getAverage());
        long avgCommitTime = Math.round(commitTimes.getAverage());
        long avgAtTime = Math.round(atTimes.getAverage());
        long avgTxApplyTime = Math.round(txApplyTimes.getAverage());
        long avgSubscriptionTime = Math.round(subscriptionTimes.getAverage());
        long avgBlockApplyTime = Math.round(blockApplyTimes.getAverage());
        long avgMiscTime = Math.round(miscTimes.getAverage());

        long payloadFullnessPercentage = Math.round(100.0 * stats.payloadSize / stats.maxPayloadSize);

        double avgPayloadFullness = payloadSize.getAverage();
        double avgPayloadFullnessPercentage = 100.0 * avgPayloadFullness / stats.maxPayloadSize;

        double avgPayloadFullnessMax = payloadSize.getMax();
        long avgPayloadFullnessMaxPercentage = Math.round(100.0 * avgPayloadFullnessMax / stats.maxPayloadSize);

        double avgPayloadFullnessMin = payloadSize.getMin();
        long avgPayloadFullnessMinPercentage = Math.round(100.0 * avgPayloadFullnessMin / stats.maxPayloadSize);

        long avgPushTimeMax = Math.round(pushTimes.getMax());
        long avgValidationTimeMax = Math.round(validationTimes.getMax());
        long avgTxLoopTimeMax = Math.round(txLoopTimes.getMax());
        long avgHousekeepingTimeMax = Math.round(housekeepingTimes.getMax());
        long avgCommitTimeMax = Math.round(commitTimes.getMax());
        long avgAtTimeMax = Math.round(atTimes.getMax());
        long avgTxApplyTimeMax = Math.round(txApplyTimes.getMax());
        long avgSubscriptionTimeMax = Math.round(subscriptionTimes.getMax());
        long avgBlockApplyTimeMax = Math.round(blockApplyTimes.getMax());
        long avgMiscTimeMax = Math.round(miscTimes.getMax());

        long validationPercentage = (avgPushTime > 0) ? Math.round(100.0 * avgValidationTime / avgPushTime) : 0;
        long txLoopPercentage = (avgPushTime > 0) ? Math.round(100.0 * avgTxLoopTime / avgPushTime) : 0;
        long housekeepingPercentage = (avgPushTime > 0) ? Math.round(100.0 * avgHousekeepingTime / avgPushTime) : 0;
        long txApplyPercentage = (avgPushTime > 0) ? Math.round(100.0 * avgTxApplyTime / avgPushTime) : 0;
        long atPercentage = (avgPushTime > 0) ? Math.round(100.0 * avgAtTime / avgPushTime) : 0;
        long subscriptionPercentage = (avgPushTime > 0) ? Math.round(100.0 * avgSubscriptionTime / avgPushTime) : 0;
        long blockApplyPercentage = (avgPushTime > 0) ? Math.round(100.0 * avgBlockApplyTime / avgPushTime) : 0;
        long commitPercentage = (avgPushTime > 0) ? Math.round(100.0 * avgCommitTime / avgPushTime) : 0;
        long miscPercentage = (avgPushTime > 0) ? Math.round(100.0 * avgMiscTime / avgPushTime) : 0;

        // String formatters now always show both ms and percentage
        java.util.function.Function<Double, String> pushTimeStrFormatter = val -> String.format("%d ms - max: %d ms",
                avgPushTime, avgPushTimeMax);
        java.util.function.Function<Double, String> validationTimeStrFormatter = val -> String
                .format("%d ms - max: %d ms | %d%%", avgValidationTime, avgValidationTimeMax, validationPercentage);
        java.util.function.Function<Double, String> txLoopTimeStrFormatter = val -> String
                .format("%d ms - max: %d ms | %d%%", avgTxLoopTime, avgTxLoopTimeMax, txLoopPercentage);
        java.util.function.Function<Double, String> housekeepingTimeStrFormatter = val -> String.format(
                "%d ms - max: %d ms | %d%%", avgHousekeepingTime, avgHousekeepingTimeMax, housekeepingPercentage);
        java.util.function.Function<Double, String> commitTimeStrFormatter = val -> String
                .format("%d ms - max: %d ms | %d%%", avgCommitTime, avgCommitTimeMax, commitPercentage);
        java.util.function.Function<Double, String> atTimeStrFormatter = val -> String
                .format("%d ms - max: %d ms | %d%%", avgAtTime, avgAtTimeMax, atPercentage);
        java.util.function.Function<Double, String> txApplyTimeStrFormatter = val -> String
                .format("%d ms - max: %d ms | %d%%", avgTxApplyTime, avgTxApplyTimeMax, txApplyPercentage);
        java.util.function.Function<Double, String> subscriptionTimeStrFormatter = val -> String.format(
                "%d ms - max: %d ms | %d%%", avgSubscriptionTime, avgSubscriptionTimeMax, subscriptionPercentage);
        java.util.function.Function<Double, String> blockApplyTimeStrFormatter = val -> String
                .format("%d ms - max: %d ms | %d%%", avgBlockApplyTime, avgBlockApplyTimeMax, blockApplyPercentage);
        java.util.function.Function<Double, String> miscTimeStrFormatter = val -> String
                .format("%d ms - max: %d ms | %d%%", avgMiscTime, avgMiscTimeMax, miscPercentage);

        // Prepare progress bar updates
        data.progressBarUpdates.put(pushTimeProgressBar,
                () -> updateProgressBar(pushTimeProgressBar, avgPushTime, avgPushTimeMax, pushTimeStrFormatter));
        data.progressBarUpdates.put(validationTimeProgressBar, () -> updateProgressBar(validationTimeProgressBar,
                validationPercentage, 100, validationTimeStrFormatter));
        data.progressBarUpdates.put(txLoopTimeProgressBar,
                () -> updateProgressBar(txLoopTimeProgressBar, txLoopPercentage, 100, txLoopTimeStrFormatter));
        data.progressBarUpdates.put(housekeepingTimeProgressBar, () -> updateProgressBar(housekeepingTimeProgressBar,
                housekeepingPercentage, 100, housekeepingTimeStrFormatter));
        data.progressBarUpdates.put(commitTimeProgressBar,
                () -> updateProgressBar(commitTimeProgressBar, commitPercentage, 100, commitTimeStrFormatter));
        data.progressBarUpdates.put(atTimeProgressBar,
                () -> updateProgressBar(atTimeProgressBar, atPercentage, 100, atTimeStrFormatter));
        data.progressBarUpdates.put(txApplyTimeProgressBar,
                () -> updateProgressBar(txApplyTimeProgressBar, txApplyPercentage, 100, txApplyTimeStrFormatter));
        data.progressBarUpdates.put(subscriptionTimeProgressBar,
                () -> updateProgressBar(subscriptionTimeProgressBar, subscriptionPercentage, 100,
                        subscriptionTimeStrFormatter));
        data.progressBarUpdates.put(blockApplyTimeProgressBar, () -> updateProgressBar(blockApplyTimeProgressBar,
                blockApplyPercentage, 100, blockApplyTimeStrFormatter));
        data.progressBarUpdates.put(miscTimeProgressBar,
                () -> updateProgressBar(miscTimeProgressBar, miscPercentage, 100, miscTimeStrFormatter));
        data.progressBarUpdates.put(payloadFullnessProgressBar,
                () -> updateProgressBar(payloadFullnessProgressBar, avgPayloadFullnessPercentage, 100, val -> String
                        .format("%06.2f%% - C: %03d%% (%06d / %d bytes) - min: %03d%% - max: %03d%%",
                                avgPayloadFullnessPercentage, payloadFullnessPercentage, stats.payloadSize,
                                stats.maxPayloadSize, avgPayloadFullnessMinPercentage,
                                avgPayloadFullnessMaxPercentage, 100)));

        // Prepare chart series updates
        data.seriesUpdates.put(pushTimePerBlockSeries, new Point.Double(blockHeight, avgPushTime));
        data.seriesUpdates.put(validationTimePerBlockSeries, new Point.Double(blockHeight, avgValidationTime));
        data.seriesUpdates.put(txLoopTimePerBlockSeries, new Point.Double(blockHeight, avgTxLoopTime));
        data.seriesUpdates.put(housekeepingTimePerBlockSeries, new Point.Double(blockHeight, avgHousekeepingTime));
        data.seriesUpdates.put(commitTimePerBlockSeries, new Point.Double(blockHeight, avgCommitTime));
        data.seriesUpdates.put(atTimePerBlockSeries, new Point.Double(blockHeight, avgAtTime));
        data.seriesUpdates.put(txApplyTimePerBlockSeries, new Point.Double(blockHeight, avgTxApplyTime));
        data.seriesUpdates.put(subscriptionTimePerBlockSeries, new Point.Double(blockHeight, avgSubscriptionTime));
        data.seriesUpdates.put(miscTimePerBlockSeries, new Point.Double(blockHeight, avgMiscTime));
        data.seriesUpdates.put(blockApplyTimePerBlockSeries, new Point.Double(blockHeight, avgBlockApplyTime));
        data.seriesUpdates.put(payloadFullnessSeries, new Point.Double(stats.height, avgPayloadFullnessPercentage));

        return data;
    }

    private PerformanceUpdateData calculatePerformanceUpdate(BlockchainProcessor.PerformanceStats stats) {
        PerformanceUpdateData data = new PerformanceUpdateData();
        if (stats == null) {
            return data;
        }

        blockTimestamps.add(System.currentTimeMillis());

        double timeSpanMs = blockTimestamps.getLast()
                - blockTimestamps.get(blockTimestamps.size() - Math.min(blockTimestamps.size(), movingAverageWindow));

        double blocksPerSecond = 0.0;
        if (timeSpanMs > 0) {
            blocksPerSecond = (double) Math.min(blockTimestamps.size(), movingAverageWindow) * 1000.0 / timeSpanMs;
        }
        blocksPerSec.add(blocksPerSecond);
        double avgBlocksPerSecond = blocksPerSec.getAverage();
        double avgBlocksPerSecondMax = blocksPerSec.getMax();

        double allTransactionsPerSecond = 0.0;
        double systemTransactionsPerSecond = 0.0;
        if (timeSpanMs > 0) {
            allTransactionsPerSecond = allTransactionsPerBlock.getSum() * 1000.0 / timeSpanMs;
            systemTransactionsPerSecond = systemTransactionsPerBlock.getSum() * 1000.0 / timeSpanMs;
        }
        allTransactionsPerSec.add(allTransactionsPerSecond);
        double avgAllTransactionsPerSecond = allTransactionsPerSec.getAverage();
        double avgAllTransactionsPerSecondMax = allTransactionsPerSec.getMax();

        systemTransactionsPerSec.add(systemTransactionsPerSecond);
        double avgSystemTransactionsPerSecond = systemTransactionsPerSec.getAverage();
        double avgSystemTransactionsPerSecondMax = systemTransactionsPerSec.getMax();

        atCountsPerBlock.add((double) stats.atCount);
        double avgAtCount = atCountsPerBlock.getAverage();
        double avgAtCountMax = atCountsPerBlock.getMax();

        // Prepare chart series updates
        data.seriesUpdates.put(blocksPerSecondSeries, new Point.Double(stats.height, avgBlocksPerSecond));
        data.seriesUpdates.put(allTransactionsPerSecondSeries,
                new Point.Double(stats.height, avgAllTransactionsPerSecond));
        data.seriesUpdates.put(systemTransactionsPerSecondSeries,
                new Point.Double(stats.height, avgSystemTransactionsPerSecond));
        data.seriesUpdates.put(atCountPerBlockSeries, new Point.Double(stats.height, avgAtCount));

        // Prepare progress bar updates
        data.progressBarUpdates.put(blocksPerSecondProgressBar,
                () -> updateProgressBar(blocksPerSecondProgressBar, avgBlocksPerSecond, avgBlocksPerSecondMax,
                        val -> String.format("%.2f - max: %.2f", val, avgBlocksPerSecondMax), 100));
        data.progressBarUpdates.put(allTransactionsPerSecondProgressBar,
                () -> updateProgressBar(allTransactionsPerSecondProgressBar, avgAllTransactionsPerSecond,
                        avgAllTransactionsPerSecondMax,
                        val -> String.format("%.2f - max: %.2f", val, avgAllTransactionsPerSecondMax), 100));
        data.progressBarUpdates.put(systemTransactionsPerSecondProgressBar,
                () -> updateProgressBar(systemTransactionsPerSecondProgressBar, avgSystemTransactionsPerSecond,
                        avgSystemTransactionsPerSecondMax,
                        val -> String.format("%.2f - max: %.2f", val, avgSystemTransactionsPerSecondMax),
                        100));
        data.progressBarUpdates.put(atCountsPerBlockProgressBar,
                () -> updateProgressBar(atCountsPerBlockProgressBar, avgAtCount, avgAtCountMax,
                        val -> String.format("%.2f - max: %.2f", val, avgAtCountMax), 100));

        return data;
    }

    private SharedBarChartUpdateData calculateSharedBarChartUpdate(BlockchainProcessor.PerformanceStats stats) {
        SharedBarChartUpdateData data = new SharedBarChartUpdateData();
        if (stats == null) {
            return data;
        }

        allTransactionsPerBlock.add((double) stats.allTransactionCount);
        systemTransactionsPerBlock.add((double) stats.systemTransactionCount);

        double avgAllTransactions = allTransactionsPerBlock.getAverage();
        double avgAllTransactionsMax = allTransactionsPerBlock.getMax();

        double avgSystemTransactions = systemTransactionsPerBlock.getAverage();
        double avgSystemTransactionsMax = systemTransactionsPerBlock.getMax();

        // Prepare chart series updates
        data.seriesUpdates.put(allTransactionsPerBlockSeries, new Point.Double(stats.height, avgAllTransactions));
        data.seriesUpdates.put(systemTransactionsPerBlockSeries, new Point.Double(stats.height, avgSystemTransactions));

        // Prepare progress bar updates
        data.progressBarUpdates.put(allTransactionsPerBlockProgressBar,
                () -> updateProgressBar(allTransactionsPerBlockProgressBar, avgAllTransactions,
                        avgAllTransactionsMax,
                        val -> String.format("%.2f - max: %.2f", val, avgAllTransactionsMax),
                        100));
        data.progressBarUpdates.put(systemTransactionsPerBlockProgressBar,
                () -> updateProgressBar(systemTransactionsPerBlockProgressBar, avgSystemTransactions,
                        avgSystemTransactionsMax,
                        val -> String.format("%.2f - max: %.2f", val, avgSystemTransactionsMax),
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
        series.addOrUpdate(x, y);
    }

    private double getLastValue(XYSeries series) {
        if (series == null || series.isEmpty()) {
            return 0.0;
        }
        return series.getY(series.getItemCount() - 1).doubleValue();
    }

    private double getMaxValue(XYSeries series) {
        if (series == null || series.isEmpty()) {
            return 0.0;
        }
        double max = Double.MIN_VALUE;
        for (int i = 0; i < series.getItemCount(); i++) {
            max = Math.max(max, series.getY(i).doubleValue());
        }
        return max;
    }

    private double getMinValue(XYSeries series) {
        if (series == null || series.isEmpty()) {
            return 0.0;
        }
        double min = Double.MAX_VALUE;
        for (int i = 0; i < series.getItemCount(); i++) {
            min = Math.min(min, series.getY(i).doubleValue());
        }
        return min;
    }

    private void configureLineRenderer(XYLineAndShapeRenderer renderer, XYSeriesCollection dataset,
            Map<String, Color> colors) {
        renderer.setDrawSeriesLineAsPath(true);
        renderer.setUseFillPaint(true);
        renderer.setDefaultFillPaint(new Color(0, 0, 0, 0));
        renderer.setDrawOutlines(false);
        renderer.setDefaultShape(tooltipHitShape);
        renderer.setDefaultToolTipGenerator(new SyncChartToolTipGenerator());

        for (int i = 0; i < dataset.getSeriesCount(); i++) {
            renderer.setSeriesShape(i, tooltipHitShape);
            renderer.setSeriesStroke(i, CHART_STROKE);
            Comparable key = dataset.getSeriesKey(i);
            if (colors.containsKey(key)) {
                renderer.setSeriesPaint(i, colors.get(key));
            }
        }
    }

    private void configureBarRenderer(XYBarRenderer renderer, XYSeriesCollection dataset, Map<String, Color> colors) {
        renderer.setBarPainter(new StandardXYBarPainter());
        renderer.setShadowVisible(false);
        renderer.setMargin(0.0);
        renderer.setDefaultToolTipGenerator(new SyncChartToolTipGenerator());

        for (int i = 0; i < dataset.getSeriesCount(); i++) {
            Comparable key = dataset.getSeriesKey(i);
            if (colors.containsKey(key)) {
                renderer.setSeriesPaint(i, colors.get(key));
            }
        }
    }

    private class SyncChartToolTipGenerator implements XYToolTipGenerator {
        @Override
        public String generateToolTip(XYDataset dataset, int series, int item) {
            String name = dataset.getSeriesKey(series).toString();
            double x = dataset.getXValue(series, item);
            double y = dataset.getYValue(series, item);

            String valueStr;
            String xLabel = "Height";
            String xValue = String.valueOf((int) x);

            if (name.contains("Speed")) {
                valueStr = formatDataRate(y);
                xLabel = "Time";
                xValue = new SimpleDateFormat("HH:mm:ss").format(new Date((long) x));
            } else if (name.contains("Volume")) {
                valueStr = formatDataSize(y);
                xLabel = "Time";
                xValue = new SimpleDateFormat("HH:mm:ss").format(new Date((long) x));
            } else if (name.contains("Time")) {
                valueStr = String.format("%.0f ms", y);
            } else if (name.contains("Fullness")) {
                valueStr = String.format("%.2f%%", y);
            } else {
                valueStr = String.format("%.2f", y);
            }

            return String.format("<html><b>%s:</b> %s<br><b>%s:</b> %s</html>", name, valueStr, xLabel, xValue);
        }
    }
}