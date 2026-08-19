package application.module.node.gui.metrics;

import application.module.node.Account;
import application.module.node.Block;
import application.module.node.Blockchain;
import application.module.node.BlockchainProcessor;
import application.module.node.Constants;
import application.module.node.Generator;
import application.module.node.Signum;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.gui.dialog.MinersDeadlinesDialog;
import application.module.node.gui.dialog.MinersListDialog;
import application.module.node.util.Convert;
import application.module.node.util.DurationFormatter;
import application.module.node.util.Listener;
import application.utils.gui.ColorPaletteManager;
import application.utils.gui.ContextMenuUtils;
import application.utils.gui.CustomDrawingIcon;
import application.utils.gui.CustomDrawings;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.SmartTable;
import application.utils.gui.GuiFontManager;
import application.utils.gui.TableUtils;
import application.utils.math.MovingAverage;
import signumj.entity.SignumAddress;
import signumj.entity.SignumID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.miginfocom.swing.MigLayout;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.labels.PieSectionLabelGenerator;
import org.jfree.chart.labels.PieToolTipGenerator;
import org.jfree.chart.labels.XYToolTipGenerator;
import org.jfree.chart.plot.PiePlot;
import org.jfree.data.general.DefaultPieDataset;
import org.jfree.data.general.PieDataset;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.plot.DatasetRenderingOrder;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.renderer.xy.StandardXYBarPainter;
import org.jfree.chart.renderer.xy.XYBarRenderer;
import org.jfree.chart.ui.RectangleInsets;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYDataset;
import org.jfree.data.xy.XYSeriesCollection;
import javax.swing.table.TableColumn;
import javax.swing.border.TitledBorder;
import javax.swing.*;
import javax.swing.table.JTableHeader;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.text.AttributedString;
import java.awt.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Collections;
import java.util.AbstractMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A panel that displays metrics related to block generation (mining).
 * <p>
 * This panel provides a comprehensive view of the mining status, including:
 * <ul>
 * <li>Current block height, difficulty, and cumulative difficulty.</li>
 * <li>Historical charts for deadlines, network size, commitment, and miner
 * counts.</li>
 * <li>A pie chart visualizing the share of blocks mined by this node versus the
 * network.</li>
 * <li>A detailed table of connected miners and their submitted deadlines.</li>
 * </ul>
 * </p>
 */
@SuppressWarnings("serial")
public class BlockGenerationMetricsPanel extends JPanel {

    // Constants for chart dataset and axis indices to improve readability
    private static final int DATASET_DEADLINE_BARS = 0;
    private static final int DATASET_DEADLINE_MA = 1;
    private static final int DATASET_NETWORK_SIZE = 2;
    private static final int DATASET_COMMITMENT = 3;
    private static final int DATASET_BASE_TARGET = 4;
    private static final int DATASET_COUNTS = 5;
    private static final int DATASET_SHARE = 6;

    private static final int AXIS_DEADLINE = 0;
    private static final int AXIS_NETWORK_SIZE = 1;
    private static final int AXIS_COMMITMENT = 2;
    private static final int AXIS_BASE_TARGET = 3;
    private static final int AXIS_COUNTS = 4;
    private static final int AXIS_SHARE = 5;

    private static final BasicStroke CHART_STROKE = new BasicStroke(1.2f);

    private static final int[] MA_WINDOW_VALUES = { 1, 10, 100, 500 };

    private static final Logger logger = LoggerFactory.getLogger(BlockGenerationMetricsPanel.class);

    private final JFrame parentFrame;
    private JLabel heightLabel;
    private JLabel difficultyLabel;
    private JLabel cumulativeDifficultyLabel;

    private JLabel baseTargetLabel;

    public static final int CHART_HISTORY_SIZE = 1000;
    private final List<BlockHistoryEntry> recentGenerators = new CopyOnWriteArrayList<>();
    private int movingAverageWindow = 100;
    private int currentZoomRange = CHART_HISTORY_SIZE;
    private final MovingAverage baseTargetMA = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage minerCountMA = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage bestBlockchainDeadlineMA = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage bestNodeDeadlineMA = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage receivedDeadlineCountMA = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage nodeShareMA = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage networkMinersMA = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage commitmentMA = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);
    private final MovingAverage networkSizeMA = new MovingAverage(CHART_HISTORY_SIZE, movingAverageWindow);

    private final Map<Long, Color> minerColors = new HashMap<>();
    private final List<Color> colorPalette = new ArrayList<>();
    private int nextColorIndex = 0;

    private volatile boolean showNodeShare = true;
    private volatile boolean showNetworkShare = true;
    private JLabel nodeShareLegendLabel;
    private JLabel networkShareLegendLabel;
    private JLabel nodeMinersCountLabel;
    private JLabel networkMinersCountLabel;

    private JProgressBar baseTargetProgressBar;
    private JProgressBar minerCountProgressBar;
    private JProgressBar commitmentProgressBar;
    private JProgressBar networkSizeProgressBar;
    private JProgressBar networkMinersProgressBar;
    private JProgressBar bestBlockchainDeadlineProgressBar;
    private JProgressBar bestNodeDeadlineProgressBar;
    private JProgressBar receivedDeadlineCountProgressBar;
    private JProgressBar nodeShareProgressBar;

    private JTable minersTable;
    private MinersTableModel minersTableModel;
    private JTextField filterTextField;
    private TableRowSorter<MinersTableModel> sorter;

    private XYSeries acceptedDeadlineSeries; // Deadline of the block that was accepted by the network
    private XYSeries nodeDeadlineSeries; // Best deadline submitted by any miner connected to this node
    private XYSeries minedBlockSeries;
    private XYSeries acceptedDeadlineMASeries;
    private XYSeries nodeDeadlineMASeries;
    private XYSeries networkSizeMASeries;
    private XYSeries commitmentMASeries;
    private XYSeries baseTargetMASeries;
    private XYSeries nodeMinersMASeries;
    private XYSeries networkMinersMASeries;
    private XYSeries receivedDeadlinesMASeries;
    private XYSeries nodeShareMASeries;
    private ChartPanel chartPanel;
    private volatile BigInteger nodeBestDeadline;
    private volatile int deadlineReceivedCountSinceLastBlock = 0;
    private final List<MinerEntry> currentBlockDeadlines = new CopyOnWriteArrayList<>();
    private final Map<Integer, LocalBlockInfo> localMinedBlocks = new ConcurrentHashMap<>();
    private final Map<Integer, List<MinerEntry>> nodeDeadlineHistory = new ConcurrentHashMap<>();
    private final Insets labelInsets = new Insets(2, 5, 2, 0);

    private DefaultPieDataset pieDataset;
    private ChartPanel pieChartPanel;

    // State variables for UI refresh
    private volatile double lastNetworkSizeBytes = 0;
    private volatile long lastCapacityBaseTarget = 0;
    private volatile double lastAcceptedDeadline = 0;
    private volatile boolean lastMinedByNode = false;

    private final ExecutorService updateExecutor;
    private final Object updateLock = new Object();

    private final Shape tooltipHitShape = new java.awt.geom.Ellipse2D.Double(-10.0, -10.0, 20.0, 20.0);

    /**
     * Indicates whether this panel is currently the active/visible tab.
     * <p>
     * Used to determine if UI updates should be performed or skipped to save
     * resources.
     * Updated via HierarchyListener.
     * </p>
     */
    private boolean isTabActive = false;

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
    private volatile MinerUpdateData lastMinerData;
    private volatile PieChartUpdateData lastPieData;
    private volatile BlockchainUpdateData lastBlockchainData;
    private volatile BlockUpdateData lastBlockUpdateData;

    private boolean migLayoutDebug = false;

    private final Listener<Generator.GeneratorState> nonceSubmittedListener = this::processNonceSubmission;
    private final Listener<Block> blockPushedListener = this::processBlockPushed;
    private final Listener<Block> blockPoppedListener = this::onBlockPopped;

    /**
     * Profile-aware context providing access to node components.
     * Replaces static {@code Signum.getXxx()} calls.
     */
    private final MetricsPanelContext ctx;

    /**
     * Creates a new BlockGenerationMetricsPanel with profile-aware context.
     *
     * @param parentFrame    The parent JFrame.
     * @param sharedExecutor The shared ExecutorService for background tasks.
     * @param ctx the profile-aware metrics context (may be null for backward compatibility)
     */
    public BlockGenerationMetricsPanel(JFrame parentFrame, ExecutorService sharedExecutor, MetricsPanelContext ctx) {
        this.updateExecutor = sharedExecutor;
        this.ctx = ctx;
        setBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5));
        setLayout(new MigLayout((migLayoutDebug ? "debug, " : "") + "insets 0, fillx", "[]5![]5![]5![grow]",
                "[grow, fill]"));
        this.parentFrame = parentFrame;
        initializeColorPalette();
        layoutComponents();
    }

    /**
     * Creates a new BlockGenerationMetricsPanel without profile context.
     * @deprecated Use {@link #BlockGenerationMetricsPanel(JFrame, ExecutorService, MetricsPanelContext)} instead.
     * Retained for backward compatibility during migration.
     *
     * @param parentFrame    The parent JFrame.
     * @param sharedExecutor The shared ExecutorService for background tasks.
     */
    @Deprecated(since = "4.0", forRemoval = true)
    public BlockGenerationMetricsPanel(JFrame parentFrame, ExecutorService sharedExecutor) {
        this(parentFrame, sharedExecutor, null);
    }

    private void initializeColorPalette() {
        // Use a fixed set of distinct colors similar to default JFreeChart but extended
        colorPalette.add(new Color(0xFF, 0x55, 0x55)); // Red
        colorPalette.add(new Color(0x55, 0x55, 0xFF)); // Blue
        colorPalette.add(new Color(0x55, 0xFF, 0x55)); // Green
        colorPalette.add(new Color(0xFF, 0xFF, 0x55)); // Yellow
        colorPalette.add(new Color(0xFF, 0x55, 0xFF)); // Magenta
        colorPalette.add(new Color(0x55, 0xFF, 0xFF)); // Cyan
        colorPalette.add(new Color(0xFF, 0xAA, 0x00)); // Orange
        colorPalette.add(new Color(0xAA, 0x00, 0xFF)); // Purple
        colorPalette.add(new Color(0x00, 0xAA, 0xFF)); // Light Blue
        colorPalette.add(new Color(0xAA, 0xFF, 0x00)); // Lime

        // Fill the rest with HSB to ensure we have enough
        for (int i = 0; i < 24; i++) {
            colorPalette.add(Color.getHSBColor(i / 24.0f, 0.75f, 0.95f));
        }
    }

    /**
     * Initializes the panel, loads initial data, and registers event listeners.
     */
    public void init() {
        // Initial update on EDT is fine as listeners aren't active yet
        BlockchainUpdateData data = calculateBlockchainInfo(false);
        if (data != null) {
            updateBlockchainInfoUI(data);
        }

        Blockchain blockchain = ctx != null ? ctx.getBlockchain() : null;
        Generator generator = ctx != null ? ctx.getGenerator() : null;
        if (currentBlockDeadlines.isEmpty() && generator != null && blockchain != null) {
            Block lastBlock = blockchain.getLastBlock();
            int nextHeight = (lastBlock != null ? lastBlock.getHeight() : 0) + 1;
            for (Generator.GeneratorState state : generator.getAllGenerators()) {
                currentBlockDeadlines.add(new MinerEntry(state.getAccountId(), state.getDeadline(),
                        MinerEntry.Type.ACTIVE_LOCAL, nextHeight, System.currentTimeMillis(), 0));
            }
        }
        updateMinersUI(calculateMinerData(false));
        updatePieChartUI(calculatePieChartData());
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
    }

    /**
     * Cleans up resources used by the panel.
     */
    public void shutdown() {
        try {
            Generator generator = ctx != null ? ctx.getGenerator() : null;
            if (generator != null) {
                generator.removeListener(nonceSubmittedListener, Generator.Event.NONCE_SUBMITTED);
            }
        } catch (Throwable t) {
            logger.warn("Error removing Generator listeners", t);
        }
        try {
            BlockchainProcessor processor = ctx != null ? ctx.getBlockchainProcessor() : null;
            if (processor != null) {
                processor.removeListener(blockPushedListener, BlockchainProcessor.Event.BLOCK_PUSHED);
                processor.removeListener(blockPoppedListener, BlockchainProcessor.Event.BLOCK_MANUAL_POPPED);
                processor.removeListener(blockPoppedListener, BlockchainProcessor.Event.BLOCK_AUTO_POPPED);
            }
        } catch (Throwable t) {
            logger.warn("Error removing BlockchainProcessor listeners", t);
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
     * Refreshes all UI components with the latest available data.
     * Scheduled on the EDT.
     */
    private void refreshUI() {
        SwingUtilities.invokeLater(() -> {
            if (lastBlockchainData != null)
                updateBlockchainInfoUI(lastBlockchainData);
            if (lastBlockUpdateData != null)
                applyBlockUpdate(lastBlockUpdateData);
            if (lastMinerData != null)
                updateMinersUI(lastMinerData);
            if (lastPieData != null)
                updatePieChartUI(lastPieData);
            updateChartRange();
            if (chartPanel != null) {
                chartPanel.getChart().getXYPlot().setNotify(true);
            }
            if (pieChartPanel != null) {
                pieChartPanel.getChart().setNotify(true);
            }
        });
    }

    private void layoutComponents() {

        String heightTooltip = """
                The height of the block currently being generated.

                This represents the next block height in the blockchain (Last Block Height + 1).
                """;
        heightLabel = createInfoLabel("Block Generation Height", "-", heightTooltip);
        String difficultyTooltip = """
                The current mining difficulty.

                Difficulty is a measure of how hard it is to find a valid nonce for a new block. It is inversely proportional to the Base Target.

                Calculation: 18,446,744,073,709,551,616 (2^64) / Base Target.
                """;
        difficultyLabel = createInfoLabel("Difficulty", "-", difficultyTooltip);
        String cumDiffTooltip = """
                The cumulative difficulty of the blockchain.

                This value represents the sum of the difficulty of all blocks in the chain. It is a measure of the total computational work required to build the chain up to the current height and is used to determine the 'winning' chain in case of forks (the chain with the highest cumulative difficulty is chosen).
                """;
        cumulativeDifficultyLabel = createInfoLabel("Cumulative Diff", "-", cumDiffTooltip);

        // --- Chart (Middle Left) ---
        chartPanel = createChartPanel();
        registerLongTooltip(chartPanel);

        // --- Pie Chart (Right) ---
        pieChartPanel = createPieChartPanel();
        registerLongTooltip(pieChartPanel);

        // --- Left Metrics Panel (Labels + Bars) ---
        JPanel leftMetricsPanel = new JPanel(new MigLayout((migLayoutDebug ? "debug, " : "") + "insets 0, wrap 2",
                "[align right]5[fill, 350!]", "[]2[]"));

        leftMetricsPanel.add(heightLabel, "span 2, growx, align right");
        leftMetricsPanel.add(difficultyLabel, "span 2, growx, align right");
        leftMetricsPanel.add(cumulativeDifficultyLabel, "span 2, growx, align right");

        JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
        leftMetricsPanel.add(separator, "span 2, growx, gaptop 5, gapbottom 5");

        String networkSizeTooltip = """
                The moving average of the estimated total network mining capacity (Network Size).

                This value is derived from the current difficulty. It represents the total size of plot files on the network that are effectively mining.

                --- Difficulty ---
                Difficulty is a measure of how hard it is to find a valid nonce for a new block. It is inversely proportional to the Base Target. A higher difficulty means it is statistically harder to mine a block.
                Source: Calculated from the Base Target of the most recent block.
                Calculation: 18,446,744,073,709,551,616 (2^64) / Base Target.
                Significance: A higher difficulty means the network capacity is high, requiring more storage space to find a valid block within the target time (4 minutes).

                --- Network Size ---
                Source: Calculated from Difficulty.
                Calculation: (Difficulty * 256 KB) / 240 seconds.
                Significance: Represents the total storage capacity securing the network.

                The progress bar displays:
                - C: Current estimated network size.
                - MA: Moving Average network size over the last %d blocks.
                - max: Maximum Moving Average network size observed in the chart history (last %d blocks).
                - Bar length: Indicates the current MA value relative to the maximum observed MA value.
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE);
        JLabel networkSizeLabel = createLabel("Network Size (MA):", "blockgen.network.size",
                networkSizeTooltip);
        networkSizeProgressBar = createProgressBar(0, 100, null, "C: 0 B | MA: 0 B - max: 0 B",
                GuiConstants.PROGRESS_BAR_SIZE_LARGE);
        addToggleListener(networkSizeLabel, chartPanel, "Network Size (MA)");
        leftMetricsPanel.add(networkSizeLabel);
        leftMetricsPanel.add(networkSizeProgressBar);

        String commitmentTooltip = """
                The moving average of network commitment in SIGNA per Terabyte (TB) of plot size.

                This metric shows how much SIGNA is staked on average for each TB of plot space on the network, based on the active miners observed in the last %d blocks. A higher value indicates a higher economic stake backing the network's security.

                The value is averaged over the configured window (default %d blocks).

                The progress bar displays:
                - C: Current SIGNA/TB for the miners in the history window.
                - MA: Moving Average of SIGNA/TB over the last %d blocks.
                - max: Maximum Moving Average of SIGNA/TB observed in the chart history (last %d blocks).
                - Bar length: Indicates the current MA value relative to the maximum observed MA value.
                """
                .formatted(CHART_HISTORY_SIZE, movingAverageWindow, movingAverageWindow, CHART_HISTORY_SIZE);
        JLabel commitmentLabel = createLabel("Commitment (MA):", "blockgen.commitment",
                commitmentTooltip);
        commitmentProgressBar = createProgressBar(0, 100, null, "C: 0.00 | MA: 0.00 - max: 0.00",
                GuiConstants.PROGRESS_BAR_SIZE_LARGE);
        addToggleListener(commitmentLabel, chartPanel, "Commitment (MA)");
        leftMetricsPanel.add(commitmentLabel);
        leftMetricsPanel.add(commitmentProgressBar);

        String baseTargetTooltip = """
                The moving average of the Base Target.

                The Base Target determines the target value for the Proof of Capacity. It adjusts dynamically to ensure that blocks are generated approximately every 4 minutes. A lower Base Target implies higher difficulty.

                Source: The 'baseTarget' field of the most recent block. In PoC+, this refers to the capacity component.
                Calculation: Adjusted by the consensus algorithm based on the time taken to mine previous blocks.
                Significance: Inversely proportional to difficulty. A lower Base Target means higher difficulty. The mining deadline is calculated as: Deadline = Hit / Base Target.

                --- Hit ---
                The 'Hit' is a 64-bit value derived from the shabal256 hash of the generation signature and the scoop data from your plot files. It represents the quality of your nonce for the current block. A lower Hit value results in a lower Deadline.

                The value is averaged over the configured window (default %d blocks).

                The progress bar displays:
                - C: Current Base Target of the latest block.
                - MA: Moving Average Base Target over the last %d blocks.
                - max: Maximum Moving Average Base Target observed in the chart history (last %d blocks).
                - Bar length: Indicates the current MA Base Target relative to the maximum observed MA Base Target.
                """
                .formatted(movingAverageWindow, movingAverageWindow, CHART_HISTORY_SIZE);
        baseTargetLabel = createLabel("Base Target (MA):", "blockgen.base.target",
                baseTargetTooltip);
        addToggleListener(baseTargetLabel, chartPanel, "Base Target (MA)");
        baseTargetProgressBar = createProgressBar(0, 100, null, "C: 0 | MA: 0 - max: 0",
                GuiConstants.PROGRESS_BAR_SIZE_LARGE);
        leftMetricsPanel.add(baseTargetLabel);
        leftMetricsPanel.add(baseTargetProgressBar);

        String nodeShareTooltip = """
                The moving average percentage of blocks mined by this specific node.

                This represents the node's success rate in forging blocks relative to the rest of the network.

                The value is averaged over the configured window (default %d blocks).

                The progress bar displays:
                - C: Whether the current block was mined by this node's connected miners (100%%) or not (0%%).
                - MA: Moving Average share percentage over the last %d blocks.
                - max: Maximum Moving Average share percentage observed in the chart history (last %d blocks).
                - Bar length: Indicates the current MA share relative to the maximum observed MA share.
                """.formatted(movingAverageWindow, movingAverageWindow, CHART_HISTORY_SIZE);
        JLabel nodeShareLabel = createLabel("Node Share (MA)", "blockgen.node.share", nodeShareTooltip);
        addToggleListener(nodeShareLabel, chartPanel, "Node Share (MA)");
        nodeShareProgressBar = createProgressBar(0, 100, null, "C: 0.00% | MA: 0.00% - max: 0.00%",
                GuiConstants.PROGRESS_BAR_SIZE_LARGE);
        leftMetricsPanel.add(nodeShareLabel);
        leftMetricsPanel.add(nodeShareProgressBar);

        String minerCountTooltip = """
                The moving average of the number of unique miners discovered (connected) to this node in the recent history.

                This metric indicates the size of the mining pool directly connected to this node.

                The progress bar displays:
                - C: Active Miners / Discovered Miners.
                  * Active: Miners that submitted a nonce for the current block.
                  * Discovered: Total unique miners observed in the last %d blocks.
                - MA: Moving Average of discovered miners over the last %d blocks.
                - min: Minimum Moving Average of discovered miners observed in the chart history.
                - max: Maximum Moving Average of discovered miners observed in the chart history.
                - Bar length: Indicates the current MA discovered miner count relative to the maximum observed MA.
                """
                .formatted(CHART_HISTORY_SIZE, movingAverageWindow);
        JLabel minerCountLabel = createLabel("Node Miners (MA)", "blockgen.node.miners",
                minerCountTooltip);
        addToggleListener(minerCountLabel, chartPanel, "Node Miners (MA)");
        minerCountProgressBar = createProgressBar(0, 100, null, "C: 0 / 0 | MA: 0.0 - min: 0.0 - max: 0.0",
                GuiConstants.PROGRESS_BAR_SIZE_LARGE);
        leftMetricsPanel.add(minerCountLabel);
        leftMetricsPanel.add(minerCountProgressBar);

        String networkMinersTooltip = """
                The moving average of the estimated number of active miners on the network.

                This value represents the unique miners discovered on the network based on the block history data (last %d blocks).

                The progress bar displays:
                - C: Current count of unique generators in the history window.
                - MA: Moving Average of the unique generator count over the last %d blocks.
                - max: Maximum Moving Average of the unique generator count observed in the chart history (last %d blocks).
                - Bar length: Indicates the current MA value relative to the maximum observed MA value.
                """
                .formatted(CHART_HISTORY_SIZE, movingAverageWindow, CHART_HISTORY_SIZE);
        JLabel networkMinersLabel = createLabel("Network Miners (MA)", "blockgen.network.miners",
                networkMinersTooltip);
        addToggleListener(networkMinersLabel, chartPanel, "Network Miners (MA)");
        networkMinersProgressBar = createProgressBar(0, 100, null, "C: 0 | MA: 0 - max: 0",
                GuiConstants.PROGRESS_BAR_SIZE_LARGE);
        leftMetricsPanel.add(networkMinersLabel);
        leftMetricsPanel.add(networkMinersProgressBar);

        String deadlinesRxTooltip = """
                The moving average of the number of deadlines received (Rx) by this node per block interval.

                This reflects the activity of connected miners submitting potential solutions.

                The value is averaged over the configured window (default %d blocks).

                The progress bar displays:
                - C: Number of deadlines received for the current block.
                - MA: Moving Average of received deadlines over the last %d blocks.
                - max: Maximum Moving Average of received deadlines observed in the chart history (last %d blocks).
                - Bar length: Indicates the current MA value relative to the maximum observed MA value.
                """.formatted(movingAverageWindow, movingAverageWindow, CHART_HISTORY_SIZE);
        JLabel receivedDeadlineCountLabel = createLabel("Deadlines Rx (MA)", "blockgen.deadlines.rx",
                deadlinesRxTooltip);
        addToggleListener(receivedDeadlineCountLabel, chartPanel, "Deadlines Rx (MA)");
        receivedDeadlineCountProgressBar = createProgressBar(0, 100, null, "C: 0 | MA: 0.0 - max: 0.0",
                GuiConstants.PROGRESS_BAR_SIZE_LARGE);
        leftMetricsPanel.add(receivedDeadlineCountLabel);
        leftMetricsPanel.add(receivedDeadlineCountProgressBar);

        String bestNodeDeadlineTooltip = """
                The best (lowest) deadline value in seconds submitted by any of the miners connected to this node for the current block generation cycle.

                A lower deadline increases the probability of mining the next block.

                The progress bar displays:
                - C: Best deadline submitted for the current block.
                - MA: Moving Average of the best node deadline over the last %d blocks.
                - min: Minimum Moving Average of the best node deadline observed in the chart history (last %d blocks).
                - max: Maximum Moving Average of the best node deadline observed in the chart history (last %d blocks).
                - Bar length: Indicates the current MA value relative to the maximum observed MA value.
                """
                .formatted(movingAverageWindow, CHART_HISTORY_SIZE, CHART_HISTORY_SIZE);
        JLabel bestNodeDeadlineLabel = createLabel("Best Deadline (Node)", "blockgen.active.miner",
                bestNodeDeadlineTooltip);
        addToggleListener(bestNodeDeadlineLabel, chartPanel, "Node Deadline", "Node Deadline (MA)", "Mined Block");
        bestNodeDeadlineProgressBar = createProgressBar(0, 100, null, "C: 0 s | MA: 0 s - min: 0 s - max: 0 s",
                GuiConstants.PROGRESS_BAR_SIZE_LARGE);
        leftMetricsPanel.add(bestNodeDeadlineLabel);
        leftMetricsPanel.add(bestNodeDeadlineProgressBar);

        String bestChainDeadlineTooltip = """
                The moving average of the accepted deadline for blocks added to the blockchain, measured in seconds.

                Note: This value is estimated based on the timestamp difference between consecutive blocks. This estimation is used to be resource-efficient, avoiding the computationally expensive recalculation of the exact deadline from the nonce.

                The value is averaged over the configured window (default %d blocks).

                The progress bar displays:
                - C: Accepted deadline (block time) for the current block.
                - MA: Moving Average of accepted deadlines over the last %d blocks.
                - min: Minimum Moving Average of accepted deadlines observed in the chart history (last %d blocks).
                - max: Maximum Moving Average of accepted deadlines observed in the chart history (last %d blocks).
                - Bar length: Indicates the current MA value relative to the maximum observed MA value.
                """
                .formatted(movingAverageWindow, movingAverageWindow, CHART_HISTORY_SIZE, CHART_HISTORY_SIZE);
        JLabel bestBlockchainDeadlineLabel = createLabel("Best Deadline (Chain)", "blockgen.chain.deadline",
                bestChainDeadlineTooltip);
        addToggleListener(bestBlockchainDeadlineLabel, chartPanel, "Accepted Deadline", "Accepted Deadline (MA)");
        bestBlockchainDeadlineProgressBar = createProgressBar(0, 100, null, "C: 0 s | MA: 0 s - min: 0 s - max: 0 s",
                GuiConstants.PROGRESS_BAR_SIZE_LARGE);
        leftMetricsPanel.add(bestBlockchainDeadlineLabel);
        leftMetricsPanel.add(bestBlockchainDeadlineProgressBar);

        // Add Pie Chart Panel to statsPanel
        JPanel rightMetricsPanel = new JPanel(
                new MigLayout((migLayoutDebug ? "debug, " : "") + "insets 0, wrap 1, gapy 4", "[center]"));

        JPanel minersCountPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        minersCountPanel.setOpaque(false);
        nodeMinersCountLabel = new JLabel() {
            @Override
            public void updateUI() {
                super.updateUI();
                GuiFontManager.applyDefaultFont(this);
                refreshMinerLabelsText();
            }
        };
        nodeMinersCountLabel.setText("<html>Node Miners: <font color='" + toHex(GuiColors.getBlockGenActiveMiner())
                + "'>0</font> / <font color='"
                + toHex(GuiColors.getBlockGenNodeMiners()) + "'>0</font></html>");
        // (z)
        // uses new
        // COLOR_NODE_MINERS
        addMinerListListener(nodeMinersCountLabel, 0);

        networkMinersCountLabel = new JLabel() {
            @Override
            public void updateUI() {
                super.updateUI();
                GuiFontManager.applyDefaultFont(this);
                refreshMinerLabelsText();
            }
        };
        networkMinersCountLabel
                .setText("<html>Network Miners: <font color='" + toHex(GuiColors.getBlockGenNetworkMiners())
                        + "'>0</font></html>");
        // COLOR_NETWORK_MINERS
        addMinerListListener(networkMinersCountLabel, 1);

        minersCountPanel.add(nodeMinersCountLabel);
        minersCountPanel.add(new JLabel(" / "));
        minersCountPanel.add(networkMinersCountLabel);

        rightMetricsPanel.add(minersCountPanel);
        rightMetricsPanel.add(pieChartPanel);

        JPanel legendPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        legendPanel.setOpaque(false);

        nodeShareLegendLabel = new JLabel("Node Share: 0.00%") {
            @Override
            public void updateUI() {
                super.updateUI();
                GuiFontManager.applyDefaultFont(this);
                setForeground(GuiColors.getBlockGenNodeShareLegend());
                GuiFontManager.updateLabelStrikethrough(this, showNodeShare);
            }
        };
        nodeShareLegendLabel.setForeground(GuiColors.getBlockGenNodeShareLegend());
        String nodeShareLegendTooltip = """
                The percentage of blocks mined by this node's connected miners relative to the total blocks in the chart history.

                This metric is calculated over the last %d blocks.

                - <span style='color:%s'>&#9632;</span> slices in the pie chart represent blocks mined by this node.
                - Clicking this label toggles the visibility of local miners in the pie chart.
                """
                .formatted(CHART_HISTORY_SIZE, toHex(GuiColors.getBlockGenNodeShareLegend())); // colorKey is null
        ContextMenuUtils.addInfoTooltip(parentFrame, nodeShareLegendLabel, nodeShareLegendTooltip, null);
        nodeShareLegendLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    showNodeShare = !showNodeShare;
                    nodeShareLegendLabel.updateUI();
                    triggerPieChartUpdate();
                }
            }
        });

        networkShareLegendLabel = new JLabel("Network Share: 0.00%") {
            @Override
            public void updateUI() {
                super.updateUI();
                GuiFontManager.applyDefaultFont(this);
                setForeground(GuiColors.getBlockGenNetworkShareLegend());
                GuiFontManager.updateLabelStrikethrough(this, showNetworkShare);
            }
        };
        networkShareLegendLabel.setForeground(GuiColors.getBlockGenNetworkShareLegend());
        String networkShareTooltip = """
                The percentage of blocks mined by other nodes in the network relative to the total blocks in the chart history.

                This metric is calculated over the last %d blocks.

                - <span style='color:%s'>&#9632;</span> slices in the pie chart represent blocks mined by other nodes.
                - Clicking this label toggles the visibility of remote miners in the pie chart.
                """
                .formatted(CHART_HISTORY_SIZE, toHex(GuiColors.getBlockGenNetworkShareLegend())); // colorKey is null
        ContextMenuUtils.addInfoTooltip(parentFrame, networkShareLegendLabel, networkShareTooltip, null);
        networkShareLegendLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    showNetworkShare = !showNetworkShare;
                    networkShareLegendLabel.updateUI();
                    triggerPieChartUpdate();
                }
            }
        });

        legendPanel.add(nodeShareLegendLabel);
        legendPanel.add(new JLabel(" / "));
        legendPanel.add(networkShareLegendLabel);

        rightMetricsPanel.add(legendPanel);
        rightMetricsPanel.add(createControlsPanel());

        // --- Miners Table (Center) ---
        JPanel tablePanel = new JPanel(new BorderLayout(0, 0)) {
            @Override
            public boolean isValidateRoot() {
                return true;
            }
        };
        TitledBorder titledBorder = BorderFactory.createTitledBorder("Miners & Deadlines");
        tablePanel.setBorder(titledBorder);

        MouseAdapter titleMouseAdapter = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if ((SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isRightMouseButton(e)) && isTitleHit(e)) {
                    MinersDeadlinesDialog.showDialog(parentFrame, minersTableModel);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                if (isTitleHit(e)) {
                    tablePanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                } else {
                    tablePanel.setCursor(Cursor.getDefaultCursor());
                }
            }

            private boolean isTitleHit(MouseEvent e) {
                Insets insets = tablePanel.getInsets();
                if (e.getY() < insets.top) {
                    Font font = titledBorder.getTitleFont();
                    if (font == null)
                        font = UIManager.getFont("TitledBorder.font");
                    if (font == null)
                        font = UIManager.getFont("Label.font");
                    if (font == null)
                        font = tablePanel.getFont();
                    if (font == null)
                        return false;

                    FontMetrics fm = tablePanel.getFontMetrics(font);
                    int titleWidth = fm.stringWidth(titledBorder.getTitle());
                    return e.getX() >= 0 && e.getX() <= titleWidth + 30;
                }
                return false;
            }
        };
        tablePanel.addMouseListener(titleMouseAdapter);
        tablePanel.addMouseMotionListener(titleMouseAdapter);

        // Filter field
        JPanel filterPanel = new JPanel(new BorderLayout());
        filterPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        filterPanel.add(new JLabel("Filter: "), BorderLayout.WEST);
        filterTextField = new JTextField();
        filterPanel.add(filterTextField, BorderLayout.CENTER);

        tablePanel.add(filterPanel, BorderLayout.NORTH);

        minersTableModel = new MinersTableModel();
        minersTable = new SmartTable(minersTableModel) {
            @Override
            public void updateUI() {
                super.updateUI();
                // Re-apply the custom renderer after L&F change, as super.updateUI() resets
                // default renderers.
                setDefaultRenderer(Object.class, new MinerTableCellRenderer());
                // Also re-apply column-specific settings that might be reset
                try {
                    TableColumn ioColumn = getColumn(MinersTableModel.COL_IO);
                    ioColumn.setPreferredWidth(30);
                    ioColumn.setMinWidth(30);
                    ioColumn.setMaxWidth(30);
                } catch (IllegalArgumentException e) {
                    // Column might not exist during initial setup, ignore.
                }
            }

            @Override
            protected JTableHeader createDefaultTableHeader() {
                JTableHeader header = super.createDefaultTableHeader();
                header.addMouseListener(new MouseAdapter() {
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
                return header;
            }
        };
        minersTable.addMouseListener(new MouseAdapter() {
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
        minersTable.setFillsViewportHeight(true);
        minersTable.setDefaultRenderer(Object.class, new MinerTableCellRenderer());
        minersTable.setCellSelectionEnabled(true);

        // Set fixed width for I/O column to fit the chevron icon
        minersTable.getColumn(MinersTableModel.COL_IO).setPreferredWidth(30);
        minersTable.getColumn(MinersTableModel.COL_IO).setMinWidth(30);
        minersTable.getColumn(MinersTableModel.COL_IO).setMaxWidth(30);

        minersTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showLegendPopup(e);
                } else if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    // Open dialog on double click since title label is gone
                    MinersDeadlinesDialog.showDialog(parentFrame, minersTableModel);
                }
            }
        });

        // Sorting
        sorter = new TableRowSorter<MinersTableModel>(minersTableModel) {
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
        // Custom comparator to keep Winner on top if sorting by default (handled by
        // model order mostly)
        // but allowing column sorts.
        minersTable.setRowSorter(sorter);

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
                    sorter.setRowFilter(RowFilter.regexFilter("(?i)" + text));
                }
            }
        });

        JScrollPane tableScrollPane = new JScrollPane(minersTable);
        tableScrollPane.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        tableScrollPane.setViewportBorder(null); // NOSONAR
        tablePanel.add(tableScrollPane, BorderLayout.CENTER);
        tablePanel.setPreferredSize(
                new Dimension(GuiConstants.TABLE_PANEL_WIDTH, GuiConstants.CHART_DIMENSION_LARGE.height));
        tablePanel.setMinimumSize(
                new Dimension(GuiConstants.TABLE_PANEL_WIDTH, GuiConstants.CHART_DIMENSION_LARGE.height));

        add(leftMetricsPanel, "growy, aligny top");
        add(chartPanel, "growy");
        add(rightMetricsPanel, "growy, aligny top");
        add(tablePanel, "grow, aligny top");
    }

    private JPanel createControlsPanel() {
        JPanel maWindowPanel = new JPanel(new MigLayout("insets 0", "[][grow, center][]"));
        maWindowPanel.setOpaque(false);
        String maWindowTooltip = """
                The size of the sliding window (in blocks) used to calculate the Moving Average (MA) for the displayed metrics.

                A larger window provides a smoother trend, while a smaller window is more responsive to recent changes.
                """;
        JLabel maWindowLabel = createLabel("MA Window", null, maWindowTooltip);

        int initialSliderValue = 2; // Default to 100 (index in MA_WINDOW_VALUES)
        for (int i = 0; i < MA_WINDOW_VALUES.length; i++) {
            if (movingAverageWindow == MA_WINDOW_VALUES[i]) {
                initialSliderValue = i;
                break;
            }
        }
        JSlider movingAverageSlider = new JSlider(JSlider.HORIZONTAL, 0, MA_WINDOW_VALUES.length - 1,
                initialSliderValue);
        movingAverageSlider.setMajorTickSpacing(1);
        movingAverageSlider.setPaintTicks(true);
        movingAverageSlider.setSnapToTicks(true);

        java.util.Hashtable<Integer, JLabel> labelTable = new java.util.Hashtable<>();
        for (int i = 0; i < MA_WINDOW_VALUES.length; i++) {
            labelTable.put(i, new JLabel(String.valueOf(MA_WINDOW_VALUES[i])));
        }
        movingAverageSlider.setLabelTable(labelTable);
        movingAverageSlider.setPaintLabels(true);

        movingAverageSlider.addPropertyChangeListener("UI", e -> {
            SwingUtilities.invokeLater(() -> {
                // To force a full recalculation of label sizes and positions on L&F change,
                // we nullify the label table and then set a completely new one.
                // This is more robust than just calling revalidate().
                movingAverageSlider.setLabelTable(null);
                java.util.Hashtable<Integer, JLabel> newLabelTable = new java.util.Hashtable<>();
                for (int i = 0; i < MA_WINDOW_VALUES.length; i++) {
                    newLabelTable.put(i, new JLabel(String.valueOf(MA_WINDOW_VALUES[i])));
                }
                movingAverageSlider.setLabelTable(newLabelTable);
            });
        });

        movingAverageSlider.addChangeListener(e -> {
            JSlider source = (JSlider) e.getSource();
            int newValue = MA_WINDOW_VALUES[source.getValue()];
            updateExecutor.submit(() -> {
                synchronized (updateLock) {
                    movingAverageWindow = newValue;
                    baseTargetMA.setWindowSize(movingAverageWindow);
                    minerCountMA.setWindowSize(movingAverageWindow);
                    bestBlockchainDeadlineMA.setWindowSize(movingAverageWindow);
                    bestNodeDeadlineMA.setWindowSize(movingAverageWindow);
                    receivedDeadlineCountMA.setWindowSize(movingAverageWindow);
                    nodeShareMA.setWindowSize(movingAverageWindow);
                    networkMinersMA.setWindowSize(movingAverageWindow);
                    commitmentMA.setWindowSize(movingAverageWindow);
                    networkSizeMA.setWindowSize(movingAverageWindow);
                }
            });
        });

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

        zoomPanel.add(zoomOutLabel);
        zoomPanel.add(zoomInLabel);

        maWindowPanel.add(maWindowLabel);
        maWindowPanel.add(movingAverageSlider, "w " + GuiConstants.SLIDER_WIDTH + "!");
        maWindowPanel.add(zoomPanel);

        return maWindowPanel;
    }

    private ChartPanel createChartPanel() {
        acceptedDeadlineSeries = new XYSeries("Accepted Deadline", true, false);
        nodeDeadlineSeries = new XYSeries("Node Deadline", true, false);
        minedBlockSeries = new XYSeries("Mined Block", true, false);
        acceptedDeadlineMASeries = new XYSeries("Accepted Deadline (MA)", true, false);
        nodeDeadlineMASeries = new XYSeries("Node Deadline (MA)", true, false);
        networkSizeMASeries = new XYSeries("Network Size (MA)", true, false);
        commitmentMASeries = new XYSeries("Commitment (MA)", true, false);
        baseTargetMASeries = new XYSeries("Base Target (MA)", true, false);
        nodeMinersMASeries = new XYSeries("Node Miners (MA)", true, false);
        networkMinersMASeries = new XYSeries("Network Miners (MA)", true, false);
        receivedDeadlinesMASeries = new XYSeries("Deadlines Rx (MA)", true, false);
        nodeShareMASeries = new XYSeries("Node Share (MA)", true, false);

        // Dataset 0: Deadlines (Bars)
        XYSeriesCollection barDataset = new XYSeriesCollection();
        barDataset.addSeries(minedBlockSeries);
        barDataset.addSeries(acceptedDeadlineSeries);
        barDataset.addSeries(nodeDeadlineSeries);
        barDataset.setIntervalWidth(1.0);

        // Dataset 1: Time MA Lines (Deadlines)
        XYSeriesCollection timeMaDataset = new XYSeriesCollection();
        timeMaDataset.addSeries(acceptedDeadlineMASeries);
        timeMaDataset.addSeries(nodeDeadlineMASeries);
        // Dataset 2: Network Size
        XYSeriesCollection netSizeDataset = new XYSeriesCollection();
        netSizeDataset.addSeries(networkSizeMASeries);

        // Dataset 3: Commitment
        XYSeriesCollection commitmentDataset = new XYSeriesCollection();
        commitmentDataset.addSeries(commitmentMASeries);

        // Dataset 4: Base Target
        XYSeriesCollection baseTargetDataset = new XYSeriesCollection();
        baseTargetDataset.addSeries(baseTargetMASeries);

        // Dataset 5: Counts (Miners, Rx Deadlines)
        XYSeriesCollection countsDataset = new XYSeriesCollection();
        countsDataset.addSeries(nodeMinersMASeries);
        countsDataset.addSeries(networkMinersMASeries);
        countsDataset.addSeries(receivedDeadlinesMASeries);

        // Dataset 6: Share
        XYSeriesCollection shareDataset = new XYSeriesCollection();
        shareDataset.addSeries(nodeShareMASeries);

        JFreeChart chart = ChartFactory.createXYLineChart(
                null, null, null, null);

        XYPlot plot = chart.getXYPlot();
        plot.setOutlineVisible(false);
        plot.setDatasetRenderingOrder(DatasetRenderingOrder.FORWARD);
        plot.getDomainAxis().setLowerMargin(0.0);
        plot.getDomainAxis().setUpperMargin(0.0);
        plot.setBackgroundPaint(UIManager.getColor("Table.background"));
        plot.setDomainGridlinesVisible(false);
        plot.setRangeGridlinesVisible(false);
        plot.getDomainAxis().setTickLabelsVisible(false);
        plot.getDomainAxis().setTickMarksVisible(false);
        plot.getDomainAxis().setAxisLineVisible(false);

        // Configure Range Axes
        // Axis 0 is default (Time)
        plot.getRangeAxis(AXIS_DEADLINE).setTickLabelsVisible(false);
        plot.getRangeAxis(AXIS_DEADLINE).setTickMarksVisible(false);
        plot.getRangeAxis(AXIS_DEADLINE).setAxisLineVisible(false);
        plot.getRangeAxis(AXIS_DEADLINE).setLowerMargin(0.0);
        plot.getRangeAxis(AXIS_DEADLINE).setUpperMargin(0.05);

        // Create additional axes for other datasets
        for (int i = AXIS_NETWORK_SIZE; i <= AXIS_SHARE; i++) {
            NumberAxis axis = new NumberAxis();
            axis.setTickLabelsVisible(false);
            axis.setTickMarksVisible(false);
            axis.setAxisLineVisible(false);
            axis.setLowerMargin(0.0);
            axis.setUpperMargin(0.05);
            axis.setVisible(false); // Hide axis line to keep clean look
            plot.setRangeAxis(i, axis);
        }

        // Renderer 0: Bars
        plot.setDataset(DATASET_DEADLINE_BARS, barDataset);
        XYBarRenderer barRenderer = new XYBarRenderer();
        barRenderer.setBarPainter(new StandardXYBarPainter());
        barRenderer.setShadowVisible(false);
        barRenderer.setMargin(0.0); // As per SyncPanel settings

        Map<String, Paint> barPaints = new HashMap<>();
        barPaints.put("Accepted Deadline", GuiColors.getBlockGenChainDeadline());
        barPaints.put("Node Deadline", GuiColors.getBlockGenActiveMiner());
        barPaints.put("Mined Block", GuiColors.getBlockGenNodeMiners());
        assignSeriesPaints(barRenderer, barDataset, barPaints);
        barRenderer.setDefaultToolTipGenerator(new BlockChartToolTipGenerator());

        plot.setRenderer(DATASET_DEADLINE_BARS, barRenderer);
        plot.mapDatasetToRangeAxis(DATASET_DEADLINE_BARS, AXIS_DEADLINE);

        // Renderer 1: Time MA Lines
        plot.setDataset(DATASET_DEADLINE_MA, timeMaDataset);
        XYLineAndShapeRenderer timeMaRenderer = new XYLineAndShapeRenderer(true, false);

        Map<String, Paint> timeMaPaints = new HashMap<>();
        timeMaPaints.put("Accepted Deadline (MA)", GuiColors.getBlockGenChainDeadlineMa());
        timeMaPaints.put("Node Deadline (MA)", GuiColors.getBlockGenNodeDeadlineMa());

        configureLineRenderer(timeMaRenderer, timeMaDataset, timeMaPaints);
        plot.setRenderer(DATASET_DEADLINE_MA, timeMaRenderer);
        plot.mapDatasetToRangeAxis(DATASET_DEADLINE_MA, AXIS_DEADLINE);

        // Renderer 2: Network Size
        plot.setDataset(DATASET_NETWORK_SIZE, netSizeDataset);
        XYLineAndShapeRenderer netSizeRenderer = new XYLineAndShapeRenderer(true, false);
        Map<String, Paint> netSizePaints = new HashMap<>();
        netSizePaints.put(networkSizeMASeries.getKey().toString(), GuiColors.getBlockGenNetworkSize());
        configureLineRenderer(netSizeRenderer, netSizeDataset, netSizePaints);
        plot.setRenderer(DATASET_NETWORK_SIZE, netSizeRenderer);
        plot.mapDatasetToRangeAxis(DATASET_NETWORK_SIZE, AXIS_NETWORK_SIZE);

        // Renderer 3: Commitment
        plot.setDataset(DATASET_COMMITMENT, commitmentDataset);
        XYLineAndShapeRenderer commitmentRenderer = new XYLineAndShapeRenderer(true, false);
        Map<String, Paint> commitmentPaints = new HashMap<>();
        commitmentPaints.put(commitmentMASeries.getKey().toString(), GuiColors.getBlockGenCommitment());
        configureLineRenderer(commitmentRenderer, commitmentDataset, commitmentPaints);
        plot.setRenderer(DATASET_COMMITMENT, commitmentRenderer);
        plot.mapDatasetToRangeAxis(DATASET_COMMITMENT, AXIS_COMMITMENT);

        // Renderer 4: Base Target
        plot.setDataset(DATASET_BASE_TARGET, baseTargetDataset);
        XYLineAndShapeRenderer baseTargetRenderer = new XYLineAndShapeRenderer(true, false);
        Map<String, Paint> baseTargetPaints = new HashMap<>();
        baseTargetPaints.put(baseTargetMASeries.getKey().toString(), GuiColors.getBlockGenBaseTarget());
        configureLineRenderer(baseTargetRenderer, baseTargetDataset, baseTargetPaints);
        plot.setRenderer(DATASET_BASE_TARGET, baseTargetRenderer);
        plot.mapDatasetToRangeAxis(DATASET_BASE_TARGET, AXIS_BASE_TARGET);

        // Renderer 5: Counts
        plot.setDataset(DATASET_COUNTS, countsDataset);
        XYLineAndShapeRenderer countsRenderer = new XYLineAndShapeRenderer(true, false);

        Map<String, Paint> countsPaints = new HashMap<>();
        countsPaints.put("Node Miners (MA)", GuiColors.getBlockGenNodeMiners()); // Uses new COLOR_NODE_MINERS
        countsPaints.put("Network Miners (MA)", GuiColors.getBlockGenNetworkMiners()); // Uses new COLOR_NETWORK_MINERS
        countsPaints.put("Deadlines Rx (MA)", GuiColors.getBlockGenDeadlinesRx());

        configureLineRenderer(countsRenderer, countsDataset, countsPaints);
        plot.setRenderer(DATASET_COUNTS, countsRenderer);
        plot.mapDatasetToRangeAxis(DATASET_COUNTS, AXIS_COUNTS);

        // Renderer 6: Share
        plot.setDataset(DATASET_SHARE, shareDataset);
        XYLineAndShapeRenderer shareRenderer = new XYLineAndShapeRenderer(true, false);
        Map<String, Paint> sharePaints = new HashMap<>();
        sharePaints.put(nodeShareMASeries.getKey().toString(), GuiColors.getBlockGenNodeShare());
        configureLineRenderer(shareRenderer, shareDataset, sharePaints);
        plot.setRenderer(DATASET_SHARE, shareRenderer);
        plot.mapDatasetToRangeAxis(DATASET_SHARE, AXIS_SHARE);

        // Remove all padding around the plot area
        plot.setInsets(new RectangleInsets(0, 0, 0, 0));
        plot.setAxisOffset(new RectangleInsets(0, 0, 0, 0));

        chart.removeLegend();
        chart.setBorderVisible(false);

        ChartPanel cp = new ChartPanel(chart);
        cp.setPreferredSize(GuiConstants.CHART_DIMENSION_LARGE);
        cp.setMinimumSize(GuiConstants.CHART_DIMENSION_LARGE);
        cp.setDisplayToolTips(true);
        ToolTipManager.sharedInstance().registerComponent(cp);
        return cp;
    }

    private void updateChartColors() {
        if (chartPanel == null)
            return;

        XYPlot plot = chartPanel.getChart().getXYPlot();
        plot.setBackgroundPaint(UIManager.getColor("Table.background"));

        // Bar Renderer
        XYBarRenderer barRenderer = (XYBarRenderer) plot.getRenderer(DATASET_DEADLINE_BARS);
        if (barRenderer != null) {
            barRenderer.setSeriesPaint(0, GuiColors.getBlockGenNodeMiners()); // Mined Block
            barRenderer.setSeriesPaint(1, GuiColors.getBlockGenChainDeadline()); // Accepted Deadline
            barRenderer.setSeriesPaint(2, GuiColors.getBlockGenActiveMiner()); // Node Deadline
        }

        // Line Renderers
        XYLineAndShapeRenderer timeMaRenderer = (XYLineAndShapeRenderer) plot.getRenderer(DATASET_DEADLINE_MA);
        if (timeMaRenderer != null) {
            timeMaRenderer.setSeriesPaint(0, GuiColors.getBlockGenChainDeadlineMa());
            timeMaRenderer.setSeriesPaint(1, GuiColors.getBlockGenNodeDeadlineMa());
        }

        XYLineAndShapeRenderer netSizeRenderer = (XYLineAndShapeRenderer) plot.getRenderer(DATASET_NETWORK_SIZE);
        if (netSizeRenderer != null) {
            netSizeRenderer.setSeriesPaint(0, GuiColors.getBlockGenNetworkSize());
        }

        XYLineAndShapeRenderer commitmentRenderer = (XYLineAndShapeRenderer) plot.getRenderer(DATASET_COMMITMENT);
        if (commitmentRenderer != null) {
            commitmentRenderer.setSeriesPaint(0, GuiColors.getBlockGenCommitment());
        }

        XYLineAndShapeRenderer baseTargetRenderer = (XYLineAndShapeRenderer) plot.getRenderer(DATASET_BASE_TARGET);
        if (baseTargetRenderer != null) {
            baseTargetRenderer.setSeriesPaint(0, GuiColors.getBlockGenBaseTarget());
        }

        XYLineAndShapeRenderer countsRenderer = (XYLineAndShapeRenderer) plot.getRenderer(DATASET_COUNTS);
        if (countsRenderer != null) {
            countsRenderer.setSeriesPaint(0, GuiColors.getBlockGenNodeMiners());
            countsRenderer.setSeriesPaint(1, GuiColors.getBlockGenNetworkMiners());
            countsRenderer.setSeriesPaint(2, GuiColors.getBlockGenDeadlinesRx());
        }

        XYLineAndShapeRenderer shareRenderer = (XYLineAndShapeRenderer) plot.getRenderer(DATASET_SHARE);
        if (shareRenderer != null) {
            shareRenderer.setSeriesPaint(0, GuiColors.getBlockGenNodeShare());
        }
    }

    private void assignSeriesPaints(org.jfree.chart.renderer.xy.XYItemRenderer renderer, XYSeriesCollection dataset,
            Map<String, Paint> paintMap) {
        for (int i = 0; i < dataset.getSeriesCount(); i++) {
            Comparable key = dataset.getSeriesKey(i);
            if (paintMap.containsKey(key)) {
                renderer.setSeriesPaint(i, paintMap.get(key));
            }
        }
    }

    private void configureLineRenderer(XYLineAndShapeRenderer renderer, XYSeriesCollection dataset,
            Map<String, Paint> paints) {
        renderer.setDrawSeriesLineAsPath(true);
        renderer.setUseFillPaint(true);
        renderer.setDefaultFillPaint(new Color(0, 0, 0, 0));
        renderer.setDrawOutlines(false);
        renderer.setDefaultShape(tooltipHitShape);
        renderer.setDefaultToolTipGenerator(new BlockChartToolTipGenerator());

        for (int i = 0; i < dataset.getSeriesCount(); i++) {
            renderer.setSeriesShape(i, tooltipHitShape);
            renderer.setSeriesStroke(i, CHART_STROKE);
            Comparable key = dataset.getSeriesKey(i);
            if (paints.containsKey(key.toString())) {
                renderer.setSeriesPaint(i, paints.get(key.toString()));
            }
        }
    }

    private ChartPanel createPieChartPanel() {
        pieDataset = new DefaultPieDataset();

        JFreeChart chart = ChartFactory.createPieChart(null, pieDataset, false, true, false);
        chart.setBackgroundPaint(null);
        chart.setBorderVisible(false);

        PiePlot plot = (PiePlot) chart.getPlot();
        plot.setToolTipGenerator(new MinerPieToolTipGenerator());
        plot.setLabelGenerator(new MinerPieSectionLabelGenerator());
        plot.setSimpleLabels(true);
        plot.setBackgroundPaint(null);
        plot.setOutlineVisible(false);
        plot.setShadowPaint(null);
        plot.setCircular(true);
        plot.setInsets(new RectangleInsets(0, 0, 0, 0));
        plot.setInteriorGap(0.02);

        ChartPanel cp = new ChartPanel(chart);
        cp.setPreferredSize(GuiConstants.PIE_CHART_DIMENSION);
        cp.setMinimumSize(GuiConstants.PIE_CHART_DIMENSION);
        cp.setMaximumSize(GuiConstants.PIE_CHART_DIMENSION);
        cp.setDisplayToolTips(true);
        ToolTipManager.sharedInstance().registerComponent(cp);
        cp.setOpaque(false);
        cp.setMinimumDrawWidth(0);
        cp.setMinimumDrawHeight(0);
        cp.setMaximumDrawWidth(Integer.MAX_VALUE);
        cp.setMaximumDrawHeight(Integer.MAX_VALUE);
        return cp;
    }

    private JLabel createInfoLabel(String title, String initialValue, String tooltip) {
        JLabel label = new JLabel("<html><b>" + title + ":</b> " + initialValue + "</html>") {
            @Override
            public void updateUI() {
                super.updateUI();
                GuiFontManager.applyDefaultFont(this);
            }
        };
        label.setHorizontalAlignment(SwingConstants.CENTER);
        if (tooltip != null) {
            ContextMenuUtils.addInfoTooltip(parentFrame, label, tooltip, null);
        }
        return label;
    }

    private void updateInfoLabel(JLabel label, String title, String value) {
        label.setText("<html><b>" + title + ":</b> " + value + "</html>");
    }

    private void initListeners() {
        Generator generator = ctx != null ? ctx.getGenerator() : null;
        if (generator != null) {
            generator.addListener(nonceSubmittedListener, Generator.Event.NONCE_SUBMITTED);
        }

        BlockchainProcessor processor = ctx != null ? ctx.getBlockchainProcessor() : null;
        if (processor != null) {
            processor.addListener(blockPushedListener, BlockchainProcessor.Event.BLOCK_PUSHED);

            processor.addListener(blockPoppedListener, BlockchainProcessor.Event.BLOCK_MANUAL_POPPED);
            processor.addListener(blockPoppedListener, BlockchainProcessor.Event.BLOCK_AUTO_POPPED);
        }
    }

    private static class BlockchainUpdateData {
        String heightStr;
        String difficultyStr;
        String cumDiffStr;
        double networkSizeBytes;
        long capacityBaseTarget;

        double avgNetworkSize;
        double maxNetworkSize;
        double avgBaseTarget;
        double maxBaseTarget;
    }

    private BlockchainUpdateData calculateBlockchainInfo(boolean updateMA) {
        Blockchain blockchain = ctx != null ? ctx.getBlockchain() : null;
        if (blockchain == null) {
            logger.debug("Blockchain not available, returning null for blockchain info");
            return null;
        }
        return calculateBlockchainInfo(blockchain.getLastBlock(), updateMA);
    }

    private BlockchainUpdateData calculateBlockchainInfo(Block lastBlock, boolean updateMA) {
        BlockchainUpdateData data = new BlockchainUpdateData();
        if (lastBlock == null)
            return null;

        data.heightStr = String.valueOf(lastBlock.getHeight() + 1);
        data.cumDiffStr = lastBlock.getCumulativeDifficulty().toString();

        // Calculate difficulty for Network Size
        long capacityBaseTarget = lastBlock.getCapacityBaseTarget();
        double difficulty = 1.8446744e19 / capacityBaseTarget; // Approx 2^64 / BaseTarget
        if (capacityBaseTarget > 0) {
            data.difficultyStr = BigInteger.ONE.shiftLeft(64).divide(BigInteger.valueOf(capacityBaseTarget)).toString();
        } else {
            data.difficultyStr = "-";
        }

        double networkSizeBytes = (difficulty * 262144.0) / 240.0; // 256 KB per nonce / 240s block time
        data.networkSizeBytes = networkSizeBytes;
        data.capacityBaseTarget = capacityBaseTarget;

        if (updateMA) {
            networkSizeMA.add(networkSizeBytes);
            baseTargetMA.add(capacityBaseTarget);
        }

        data.avgNetworkSize = networkSizeMA.getAverage();
        data.maxNetworkSize = networkSizeMA.getMax();
        data.avgBaseTarget = baseTargetMA.getAverage();
        data.maxBaseTarget = baseTargetMA.getMax();

        return data;
    }

    private void updateBlockchainInfoUI(BlockchainUpdateData data) {
        this.lastBlockchainData = data;
        if (data == null)
            return;

        updateInfoLabel(heightLabel, "Block Generation Height", data.heightStr);
        updateInfoLabel(difficultyLabel, "Difficulty", data.difficultyStr);
        updateInfoLabel(cumulativeDifficultyLabel, "Cumulative Diff", data.cumDiffStr);

        if (uiOptimizationEnabled && !isTabActive)
            return;

        lastNetworkSizeBytes = data.networkSizeBytes;
        updateProgressBar(networkSizeProgressBar, data.avgNetworkSize, data.maxNetworkSize,
                val -> String.format("C: %s | MA: %s - max: %s",
                        formatDataSize(lastNetworkSizeBytes), formatDataSize(val),
                        formatDataSize(data.maxNetworkSize)));

        lastCapacityBaseTarget = data.capacityBaseTarget;
        updateProgressBar(baseTargetProgressBar, data.avgBaseTarget, data.maxBaseTarget,
                val -> String.format("C: %d | MA: %.0f - max: %.0f", lastCapacityBaseTarget, val, data.maxBaseTarget));
    }

    /**
     * Handles the submission of a new nonce by a local miner.
     * Updates the current block deadlines list and refreshes the UI.
     *
     * @param state The generator state containing the nonce and deadline.
     */
    private void processNonceSubmission(Generator.GeneratorState state) {
        updateExecutor.submit(() -> {
            synchronized (updateLock) {
                // --- BACKGROUND WORK ---
                Blockchain blockchain = ctx != null ? ctx.getBlockchain() : null;
                Block lastBlock = blockchain != null ? blockchain.getLastBlock() : null;
                final int nextHeight = (lastBlock != null ? lastBlock.getHeight() : 0) + 1;
                if (state.getBlock() == nextHeight) {
                    MinerEntry entry = new MinerEntry(state.getAccountId(), state.getDeadline(),
                            MinerEntry.Type.ACTIVE_LOCAL, nextHeight, System.currentTimeMillis(), 0);

                    deadlineReceivedCountSinceLastBlock++;
                    currentBlockDeadlines.add(entry);
                    nodeDeadlineHistory.computeIfAbsent(nextHeight, k -> new CopyOnWriteArrayList<>()).add(entry);

                    MinerUpdateData minerData = calculateMinerData(false);
                    PieChartUpdateData pieData = calculatePieChartData();

                    // Prepare UI updates
                    SwingUtilities.invokeLater(() -> {
                        updateMinersUI(minerData);
                        // updateReceivedDeadlineProgressBar is handled in updateMinersUI via DTO
                        updatePieChartUI(pieData);
                        if (minerData.bestDeadline != null) {
                            updateCurrentNodeDeadlineOnChart(nextHeight, minerData.bestDeadline.doubleValue());
                        }
                    });
                }
            }
        });
    }

    /**
     * Handles the event when a new block is pushed to the blockchain.
     * Updates historical data, charts, and resets current block state.
     *
     * @param lastBlock The new block that was pushed.
     */
    private void processBlockPushed(Block lastBlock) {
        if (lastBlock != null) {
            updateExecutor.submit(() -> {
                synchronized (updateLock) {
                    // --- BACKGROUND WORK ---
                    receivedDeadlineCountMA.add(deadlineReceivedCountSinceLastBlock);
                    // calculateBlockchainInfo(true) updates networkSizeMA and baseTargetMA
                    BlockchainUpdateData blockchainData = calculateBlockchainInfo(lastBlock, true);

                    // Calculate chart and state updates
                    BlockUpdateData updateData = calculateBlockUpdate(lastBlock);

                    // Calculate miner data BEFORE clearing current deadlines to capture the final
                    // state of the block
                    MinerUpdateData minerData = calculateMinerData(true);
                    PieChartUpdateData pieData = calculatePieChartData();

                    deadlineReceivedCountSinceLastBlock = 0;
                    currentBlockDeadlines.clear();
                    nodeBestDeadline = null; // Reset for next block

                    // --- UI UPDATES ---
                    SwingUtilities.invokeLater(() -> {
                        updateBlockchainInfoUI(blockchainData);
                        applyBlockUpdate(updateData);
                        updateMinersUI(minerData);
                        updatePieChartUI(pieData);
                        updateChartRange();
                    });
                }
            });
        }
    }

    private void zoomIn() {
        int maxItems = 0;
        if (acceptedDeadlineSeries != null && acceptedDeadlineSeries.getItemCount() > 0) {
            maxItems = acceptedDeadlineSeries.getItemCount();
        }
        if (nodeDeadlineSeries != null && nodeDeadlineSeries.getItemCount() > maxItems) {
            maxItems = nodeDeadlineSeries.getItemCount();
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
        updateChartRange();
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
        updateChartRange();
    }

    private void updateChartRange() {
        double lastX = 0;
        int itemCount = 0;

        if (acceptedDeadlineSeries.getItemCount() > 0) {
            lastX = acceptedDeadlineSeries.getX(acceptedDeadlineSeries.getItemCount() - 1).doubleValue();
            itemCount = acceptedDeadlineSeries.getItemCount();
        }

        if (nodeDeadlineSeries.getItemCount() > 0) {
            double nodeLastX = nodeDeadlineSeries.getX(nodeDeadlineSeries.getItemCount() - 1).doubleValue();
            if (nodeLastX > lastX) {
                lastX = nodeLastX;
            }
        }

        int effectiveCount = Math.max(itemCount, nodeDeadlineSeries.getItemCount());
        if (effectiveCount == 0) {
            return;
        }

        int range = Math.min(Math.max(effectiveCount, 10), currentZoomRange);
        chartPanel.getChart().getXYPlot().getDomainAxis().setRange(lastX - range + 0.5, lastX + 0.5);
    }

    private static class BlockUpdateData {
        Block block;
        double acceptedVal;
        boolean minedByNode;
        int minHeight;
        double signaPerTB;

        // Series Updates
        Map<XYSeries, Point.Double> seriesUpdates = new IdentityHashMap<>();

        // Stats for Progress Bars
        double avgBlockchainDeadline;
        double maxBlockchainDeadline;
        double minBlockchainDeadline;

        double avgNodeShare;
        double maxNodeShare;

        double avgNetworkMiners;
        double maxNetworkMiners;

        double avgCommitment;
        double maxCommitment;

        double avgReceivedDeadlines;
        double maxReceivedDeadlines;
    }

    private BlockUpdateData calculateBlockUpdate(Block block) {
        BlockUpdateData data = new BlockUpdateData();
        data.block = block;

        if (block.getHeight() <= 1)
            return data;

        Blockchain blockchain = ctx != null ? ctx.getBlockchain() : null;
        Block prevBlock = blockchain != null ? blockchain.getBlock(block.getPreviousBlockId()) : null;
        if (prevBlock == null)
            return data;

        // Calculate accepted deadline based on timestamp difference
        BigInteger acceptedDeadline = BigInteger.valueOf(Math.max(0, block.getTimestamp() - prevBlock.getTimestamp()));

        // Check if we mined it
        boolean minedByNode = false;
        BigInteger minedDeadline = null;

        // First check if we have a record of mining this block height with this
        // generator ID
        LocalBlockInfo localInfo = localMinedBlocks.get(block.getHeight());
        if (localInfo != null && localInfo.generatorId == block.getGeneratorId()) {
            minedByNode = true;
            minedDeadline = localInfo.deadline;
        } else {
            // Check if any of our local miners submitted a nonce for this block
            for (MinerEntry entry : currentBlockDeadlines) {
                if (entry.accountId == block.getGeneratorId()) {
                    minedByNode = true;
                    if (minedDeadline == null || entry.deadline.compareTo(minedDeadline) < 0) {
                        minedDeadline = entry.deadline;
                    }
                }
            }
        }

        if (!minedByNode) {
            List<MinerEntry> historyEntries = nodeDeadlineHistory.get(block.getHeight());
            if (historyEntries != null) {
                for (MinerEntry entry : historyEntries) {
                    if (entry.accountId == block.getGeneratorId()) {
                        minedByNode = true;
                        minedDeadline = entry.deadline;
                        break;
                    }
                }
            }
        }

        if (!minedByNode) {
            Generator generator = ctx != null ? ctx.getGenerator() : null;
            if (generator != null) {
                for (Generator.GeneratorState state : generator.getAllGenerators()) {
                    if (state.getAccountId().equals(block.getGeneratorId())) {
                        minedByNode = true;
                        break;
                    }
                }
            }
        }

        double acceptedVal = acceptedDeadline.doubleValue();

        if (minedByNode) {
            if (minedDeadline != null) {
                acceptedVal = minedDeadline.doubleValue();
            }
        }

        data.acceptedVal = acceptedVal;
        data.minedByNode = minedByNode;

        // Update MAs on background thread
        lastAcceptedDeadline = acceptedVal; // Accessed by UI, but updated here. Ideally should be passed.
        bestBlockchainDeadlineMA.add(acceptedVal);
        lastMinedByNode = minedByNode;

        if (minedByNode) {
            localMinedBlocks.put(block.getHeight(), new LocalBlockInfo(block.getGeneratorId(), minedDeadline));
        }
        int minHeight = block.getHeight() - CHART_HISTORY_SIZE;
        data.minHeight = minHeight;

        localMinedBlocks.entrySet().removeIf(e -> e.getKey() <= minHeight);
        nodeDeadlineHistory.entrySet().removeIf(e -> e.getKey() <= minHeight);

        nodeShareMA.add(minedByNode ? 100.0 : 0.0);

        recentGenerators.add(
                new BlockHistoryEntry(block.getId(), block.getGeneratorId(), block.getHeight(), block.getTimestamp()));
        while (recentGenerators.size() > CHART_HISTORY_SIZE) {
            recentGenerators.remove(0);
        }

        long uniqueGenerators = recentGenerators.stream().map(e -> e.generatorId).distinct().count();
        networkMinersMA.add((double) uniqueGenerators);

        double signaPerTB = 0;
        application.module.node.fluxcapacitor.FluxCapacitor fc = ctx != null ? ctx.getFluxCapacitor() : null;
        if (fc != null && fc.getValue(FluxValues.POC_PLUS, block.getHeight())) {
            signaPerTB = (double) block.getAverageCommitment() / Constants.ONE_SIGNA;
        }
        data.signaPerTB = signaPerTB;

        commitmentMA.add(signaPerTB);

        // Collect Stats
        data.avgBlockchainDeadline = bestBlockchainDeadlineMA.getAverage();
        data.maxBlockchainDeadline = bestBlockchainDeadlineMA.getMax();
        data.minBlockchainDeadline = bestBlockchainDeadlineMA.getMin();

        data.avgNodeShare = nodeShareMA.getAverage();
        data.maxNodeShare = nodeShareMA.getMax();

        data.avgNetworkMiners = networkMinersMA.getAverage();
        data.maxNetworkMiners = networkMinersMA.getMax();

        data.avgCommitment = commitmentMA.getAverage();
        data.maxCommitment = commitmentMA.getMax();

        data.avgReceivedDeadlines = receivedDeadlineCountMA.getAverage();
        data.maxReceivedDeadlines = receivedDeadlineCountMA.getMax();

        // Prepare Series Updates
        data.seriesUpdates.put(acceptedDeadlineMASeries,
                new Point.Double(block.getHeight(), data.avgBlockchainDeadline));
        data.seriesUpdates.put(nodeDeadlineMASeries,
                new Point.Double(block.getHeight(), bestNodeDeadlineMA.getAverage()));
        data.seriesUpdates.put(networkSizeMASeries, new Point.Double(block.getHeight(), networkSizeMA.getAverage()));
        data.seriesUpdates.put(commitmentMASeries, new Point.Double(block.getHeight(), data.avgCommitment));
        data.seriesUpdates.put(baseTargetMASeries, new Point.Double(block.getHeight(), baseTargetMA.getAverage()));
        data.seriesUpdates.put(nodeMinersMASeries, new Point.Double(block.getHeight(), minerCountMA.getAverage()));
        data.seriesUpdates.put(networkMinersMASeries, new Point.Double(block.getHeight(), data.avgNetworkMiners));
        data.seriesUpdates.put(receivedDeadlinesMASeries,
                new Point.Double(block.getHeight(), data.avgReceivedDeadlines));
        data.seriesUpdates.put(nodeShareMASeries, new Point.Double(block.getHeight(), data.avgNodeShare));

        // Note: Bar series (acceptedDeadlineSeries, nodeDeadlineSeries,
        // minedBlockSeries) are updated directly in applyBlockUpdate
        // because they need addOrUpdate logic which is slightly more complex than just
        // adding a point.

        return data;
    }

    /**
     * Applies updates related to a new block to the charts and progress bars.
     *
     * @param data The calculated update data for the block.
     */
    private void applyBlockUpdate(BlockUpdateData data) {
        this.lastBlockUpdateData = data;
        if (data.block == null || data.block.getHeight() <= 1)
            return;

        Block block = data.block;

        if (!uiOptimizationEnabled || isTabActive) {
            // Update Progress Bars using DTO data
            updateProgressBar(bestBlockchainDeadlineProgressBar, data.avgBlockchainDeadline, data.maxBlockchainDeadline,
                    val -> String.format("C: %d s | MA: %.0f s - min: %.0f s - max: %.0f s",
                            (long) lastAcceptedDeadline, val, data.minBlockchainDeadline, data.maxBlockchainDeadline));

            updateProgressBar(nodeShareProgressBar, data.avgNodeShare, data.maxNodeShare,
                    val -> String.format("C: %s | MA: %.2f%% - max: %.2f%%", lastMinedByNode ? "100.00%" : "0.00%", val,
                            data.maxNodeShare));

            updateProgressBar(networkMinersProgressBar, data.avgNetworkMiners, data.maxNetworkMiners,
                    val -> String.format("C: %d | MA: %.0f - max: %.0f", (long) networkMinersMA.getLast(), val,
                            data.maxNetworkMiners));
            networkMinersCountLabel.setText("<html>Network Miners: <font color='"
                    + toHex(GuiColors.getBlockGenNetworkMiners()) + "'>"
                    + (long) networkMinersMA.getLast() + "</font></html>");

            updateProgressBar(commitmentProgressBar, data.avgCommitment, data.maxCommitment,
                    val -> String.format("C: %.2f | MA: %.2f - max: %.2f", data.signaPerTB, val, data.maxCommitment));
        }

        // Apply Series Updates - ALWAYS update data model to prevent gaps
        data.seriesUpdates.forEach((series, point) -> {
            series.addOrUpdate(point.x, point.y);
        });

        JFreeChart chart = chartPanel.getChart();
        chart.getXYPlot().setNotify(false);
        try {
            acceptedDeadlineSeries.addOrUpdate((double) block.getHeight(), data.acceptedVal);
            if (nodeBestDeadline != null) {
                nodeDeadlineSeries.addOrUpdate((double) block.getHeight(), nodeBestDeadline.doubleValue());
            } else if (data.minedByNode) {
                nodeDeadlineSeries.addOrUpdate((double) block.getHeight(), data.acceptedVal);
            }

            if (data.minedByNode) {
                minedBlockSeries.addOrUpdate((double) block.getHeight(), data.acceptedVal);
            }

            // Remove old items based on height to maintain a fixed window on X-axis
            removeOldItems(acceptedDeadlineSeries, data.minHeight);
            removeOldItems(nodeDeadlineSeries, data.minHeight);
            removeOldItems(acceptedDeadlineMASeries, data.minHeight);
            removeOldItems(nodeDeadlineMASeries, data.minHeight);
            removeOldItems(networkSizeMASeries, data.minHeight);
            removeOldItems(commitmentMASeries, data.minHeight);
            removeOldItems(baseTargetMASeries, data.minHeight);
            removeOldItems(nodeMinersMASeries, data.minHeight);
            removeOldItems(networkMinersMASeries, data.minHeight);
            removeOldItems(receivedDeadlinesMASeries, data.minHeight);
            removeOldItems(nodeShareMASeries, data.minHeight);
            removeOldItems(minedBlockSeries, data.minHeight);
        } finally {
            if (!uiOptimizationEnabled || isTabActive) {
                chart.getXYPlot().setNotify(true);
            }
        }
    }

    private void updateCurrentNodeDeadlineOnChart(int height, double deadline) {
        JFreeChart chart = chartPanel.getChart();
        chart.getXYPlot().setNotify(false);
        try {
            nodeDeadlineSeries.addOrUpdate((double) height, deadline);
            if (!uiOptimizationEnabled || isTabActive) {
                updateChartRange();
            }
        } finally {
            if (!uiOptimizationEnabled || isTabActive) {
                chart.getXYPlot().setNotify(true);
            }
        }
    }

    /**
     * Handles the event when a block is popped from the blockchain (reorg).
     * Removes invalid historical data and refreshes the UI.
     *
     * @param block The block that was popped.
     */
    private void onBlockPopped(Block block) {
        int height = block.getHeight();
        updateExecutor.submit(() -> {
            synchronized (updateLock) {
                // --- BACKGROUND WORK ---
                localMinedBlocks.entrySet().removeIf(e -> e.getKey() > height);
                recentGenerators.removeIf(entry -> entry.height > height);
                deadlineReceivedCountSinceLastBlock = 0;
                currentBlockDeadlines.clear();
                nodeBestDeadline = null;

                // Recalculate state after pop-off
                BlockchainUpdateData blockchainData = calculateBlockchainInfo(false);
                MinerUpdateData minerData = calculateMinerData(false);
                PieChartUpdateData pieData = calculatePieChartData();

                // --- UI UPDATES ---
                SwingUtilities.invokeLater(() -> {
                    lastBlockUpdateData = null;
                    JFreeChart mainChart = chartPanel.getChart();
                    mainChart.getXYPlot().setNotify(false);
                    try {
                        truncateSeries(acceptedDeadlineSeries, height);
                        truncateSeries(nodeDeadlineSeries, height);
                        truncateSeries(minedBlockSeries, height);
                        truncateSeries(acceptedDeadlineMASeries, height);
                        truncateSeries(nodeDeadlineMASeries, height);
                        truncateSeries(networkSizeMASeries, height);
                        truncateSeries(commitmentMASeries, height);
                        truncateSeries(baseTargetMASeries, height);
                        truncateSeries(nodeMinersMASeries, height);
                        truncateSeries(networkMinersMASeries, height);
                        truncateSeries(receivedDeadlinesMASeries, height);
                        truncateSeries(nodeShareMASeries, height);

                        updateBlockchainInfoUI(blockchainData);

                        updateMinersUI(minerData);
                        updateProgressBarsFromSeries();
                        updatePieChartUI(pieData);
                        updateChartRange();
                    } finally {
                        if (!uiOptimizationEnabled || isTabActive) {
                            mainChart.getXYPlot().setNotify(true);
                        }
                    }
                });
            }
        });
    }

    private void updateProgressBarsFromSeries() {
        // Restore progress bars from truncated series data to avoid using dirty MAs
        double avgNetworkSize = getLastValue(networkSizeMASeries);
        double maxNetworkSize = getMaxValue(networkSizeMASeries);
        updateProgressBar(networkSizeProgressBar, avgNetworkSize, maxNetworkSize,
                val -> String.format("C: %s | MA: %s - max: %s",
                        formatDataSize(lastNetworkSizeBytes), formatDataSize(val), formatDataSize(maxNetworkSize)));

        double avgBaseTarget = getLastValue(baseTargetMASeries);
        double maxBaseTarget = getMaxValue(baseTargetMASeries);
        updateProgressBar(baseTargetProgressBar, avgBaseTarget, maxBaseTarget,
                val -> String.format("C: %d | MA: %.0f - max: %.0f", lastCapacityBaseTarget, val, maxBaseTarget));

        double avgMiners = getLastValue(nodeMinersMASeries);
        double maxMiners = getMaxValue(nodeMinersMASeries);
        double minMiners = getMinValue(nodeMinersMASeries);
        // Note: Active miner count (C) is updated via updateMinersUI separately
        updateProgressBar(minerCountProgressBar, avgMiners, maxMiners,
                val -> String.format("C: %d / %d | MA: %.1f - min: %.1f - max: %.1f",
                        currentBlockDeadlines.stream().map(e -> e.accountId).distinct().count(),
                        (long) minerCountMA.getLast(), val, minMiners, maxMiners));

        double avgNetworkMiners = getLastValue(networkMinersMASeries);
        double maxNetworkMiners = getMaxValue(networkMinersMASeries);
        updateProgressBar(networkMinersProgressBar, avgNetworkMiners, maxNetworkMiners,
                val -> String.format("C: %d | MA: %.0f - max: %.0f", (long) networkMinersMA.getLast(), val,
                        maxNetworkMiners));

        double avgReceived = getLastValue(receivedDeadlinesMASeries);
        double maxReceived = getMaxValue(receivedDeadlinesMASeries);
        updateProgressBar(receivedDeadlineCountProgressBar, avgReceived, maxReceived,
                val -> String.format("C: %d | MA: %.1f - max: %.1f", deadlineReceivedCountSinceLastBlock, val,
                        maxReceived));

        double avgNodeShare = getLastValue(nodeShareMASeries);
        double maxNodeShare = getMaxValue(nodeShareMASeries);
        updateProgressBar(nodeShareProgressBar, avgNodeShare, maxNodeShare,
                val -> String.format("C: %s | MA: %.2f%% - max: %.2f%%", lastMinedByNode ? "100.00%" : "0.00%", val,
                        maxNodeShare));

        double avgNodeDeadline = getLastValue(nodeDeadlineMASeries);
        double maxNodeDeadline = getMaxValue(nodeDeadlineMASeries);
        double minNodeDeadline = getMinValue(nodeDeadlineMASeries);
        updateProgressBar(bestNodeDeadlineProgressBar, avgNodeDeadline, maxNodeDeadline,
                val -> String.format("C: %s | MA: %.0f s - min: %.0f s - max: %.0f s",
                        nodeBestDeadline != null ? nodeBestDeadline.longValue() + " s" : "0 s", val, minNodeDeadline,
                        maxNodeDeadline));

        double avgChainDeadline = getLastValue(acceptedDeadlineMASeries);
        double maxChainDeadline = getMaxValue(acceptedDeadlineMASeries);
        double minChainDeadline = getMinValue(acceptedDeadlineMASeries);
        updateProgressBar(bestBlockchainDeadlineProgressBar, avgChainDeadline, maxChainDeadline,
                val -> String.format("C: %d s | MA: %.0f s - min: %.0f s - max: %.0f s",
                        (long) lastAcceptedDeadline, val, minChainDeadline, maxChainDeadline));
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

    private void removeOldItems(XYSeries series, int minHeight) {
        while (series.getItemCount() > 0 && series.getX(0).intValue() <= minHeight) {
            series.remove(0);
        }
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

    private static class PieChartUpdateData {
        double nodeSharePercent;
        double networkSharePercent;
        List<Map.Entry<String, Double>> slices;
        boolean isEmpty;
        boolean isFilteredOut;
    }

    private void triggerPieChartUpdate() {
        updateExecutor.submit(() -> {
            synchronized (updateLock) {
                PieChartUpdateData data = calculatePieChartData();
                SwingUtilities.invokeLater(() -> updatePieChartUI(data));
            }
        });
    }

    private PieChartUpdateData calculatePieChartData() {
        PieChartUpdateData data = new PieChartUpdateData();
        Set<Long> localGeneratorIds = new HashSet<>();
        Generator generator = ctx != null ? ctx.getGenerator() : null;
        if (generator != null) {
            for (Generator.GeneratorState state : generator.getAllGenerators()) {
                localGeneratorIds.add(state.getAccountId());
            }
        }

        long totalHistory = recentGenerators.size();

        if (totalHistory > 0) {
            long nodeCount = recentGenerators.stream().map(e -> e.generatorId).filter(localGeneratorIds::contains)
                    .count();
            data.nodeSharePercent = (double) nodeCount * 100.0 / totalHistory;
            data.networkSharePercent = 100.0 - data.nodeSharePercent;
        } else {
            data.isEmpty = true;
        }

        List<Long> filteredGenerators = new ArrayList<>();
        for (BlockHistoryEntry entry : recentGenerators) {
            long id = entry.generatorId;
            boolean isLocal = localGeneratorIds.contains(id);
            if ((isLocal && showNodeShare) || (!isLocal && showNetworkShare)) {
                filteredGenerators.add(id);
            }
        }

        Map<Long, Long> counts = filteredGenerators.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        data.slices = new ArrayList<>();
        long total = filteredGenerators.size();

        if (total == 0 && !data.isEmpty) {
            data.isFilteredOut = true;
        }

        if (total > 0) {
            // Calculate slices
            if (total > 0) {
                // To avoid clutter, let's group small shares into "Others"
                final double threshold = 1.0; // 1%
                double othersShare = 0;
                int othersCount = 0;

                Map<Long, Double> shares = new HashMap<>();
                for (Map.Entry<Long, Long> entry : counts.entrySet()) {
                    shares.put(entry.getKey(), (double) entry.getValue() * 100.0 / total);
                }

                // Sort by share descending
                List<Map.Entry<Long, Double>> sortedShares = new ArrayList<>(shares.entrySet());
                sortedShares.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));

                for (Map.Entry<Long, Double> entry : sortedShares) {
                    if (entry.getValue() >= threshold) {
                        data.slices.add(new AbstractMap.SimpleEntry<>(String.valueOf(entry.getKey()),
                                entry.getValue()));
                    } else {
                        othersShare += entry.getValue();
                        othersCount++;
                    }
                }
                if (othersCount > 0) {
                    data.slices
                            .add(new AbstractMap.SimpleEntry<>("Others (" + othersCount + ")", othersShare));
                }
            }
        }
        return data;
    }

    /**
     * Updates the pie chart showing miner shares.
     * Respects UI optimization settings.
     *
     * @param data The update data for the pie chart.
     */
    private void updatePieChartUI(PieChartUpdateData data) {
        this.lastPieData = data;

        if (uiOptimizationEnabled && !isTabActive)
            return;

        updateNodeShareLegend(data.nodeSharePercent);
        updateNetworkShareLegend(data.networkSharePercent);

        JFreeChart chart = pieChartPanel.getChart();
        chart.setNotify(false);
        try {
            pieDataset.clear();
            if (!data.slices.isEmpty()) {
                for (Map.Entry<String, Double> entry : data.slices) {
                    pieDataset.setValue(entry.getKey(), entry.getValue());
                }
            } else {
                if (data.isEmpty) {
                    pieDataset.setValue("Waiting for blocks...", 100);
                } else if (data.isFilteredOut) {
                    pieDataset.setValue("Filtered out", 100);
                }
            }

            // Set persistent colors for each slice to prevent flickering
            PiePlot plot = (PiePlot) chart.getPlot();
            for (Object keyObject : pieDataset.getKeys()) {
                String key = (String) keyObject;
                if (key.startsWith("Others")) {
                    plot.setSectionPaint(key, GuiColors.getBlockGenPieOthers());
                } else if ("Waiting for blocks...".equals(key)) {
                    plot.setSectionPaint(key, GuiColors.getBlockGenPieWaiting());
                } else if ("Filtered out".equals(key)) {
                    plot.setSectionPaint(key, GuiColors.getBlockGenPieFiltered());
                } else {
                    try {
                        long generatorId = Long.parseLong(key);
                        if (!minerColors.containsKey(generatorId)) {
                            minerColors.put(generatorId, colorPalette.get(nextColorIndex % colorPalette.size()));
                            nextColorIndex++;
                        }
                        plot.setSectionPaint(key, minerColors.get(generatorId));
                    } catch (NumberFormatException e) {
                        plot.setSectionPaint(key, Color.BLACK); // Fallback
                    }
                }
            }
        } finally {
            if (!uiOptimizationEnabled || isTabActive) {
                chart.setNotify(true);
            }
        }

        // Refresh tooltip if mouse is over the chart
        Point mousePos = pieChartPanel.getMousePosition();
        if (mousePos != null) {
            pieChartPanel.dispatchEvent(new MouseEvent(pieChartPanel, MouseEvent.MOUSE_MOVED,
                    System.currentTimeMillis(), 0, mousePos.x, mousePos.y, 0, false));
        }
    }

    private static class MinerUpdateData {
        List<MinerEntry> entries;
        long activeMinerCount;
        long uniqueNodeMiners;
        BigInteger bestDeadline;

        double avgMiners;
        double maxMiners;
        double minMiners;
        double avgNodeDeadline;
        double maxNodeDeadline;
        double minNodeDeadline;

        double avgReceivedDeadlines;
        double maxReceivedDeadlines;
    }

    private MinerUpdateData calculateMinerData(boolean updateHistory) {
        MinerUpdateData data = new MinerUpdateData();
        Generator generator = ctx != null ? ctx.getGenerator() : null;
        if (generator == null) {
            data.entries = Collections.emptyList();
            return data;
        }

        List<Generator.GeneratorState> states = new ArrayList<>(generator.getAllGenerators());
        Set<Long> localGeneratorIds = new HashSet<>();
        for (Generator.GeneratorState state : states) {
            localGeneratorIds.add(state.getAccountId());
        }

        Blockchain blockchain = ctx != null ? ctx.getBlockchain() : null;
        Block lastBlock = blockchain != null ? blockchain.getLastBlock() : null;

        // Prepare Miner Entries
        data.entries = new ArrayList<>();

        // 1. Add Last Block Winner (if available)
        if (lastBlock != null && lastBlock.getHeight() > 0) {
            long generatorId = lastBlock.getGeneratorId();
            LocalBlockInfo localInfo = localMinedBlocks.get(lastBlock.getHeight());
            boolean isLocal = localInfo != null && localInfo.generatorId == generatorId;
            if (!isLocal) {
                isLocal = localGeneratorIds.contains(generatorId);
            }
            // If not found in current states, check recent generators history to see if it
            // was us
            // But simpler: we can just check if the account is in our wallet?
            // For now, let's assume if it's in 'states' it's local, otherwise remote.
            // Actually, 'states' are active miners for NEXT block. The winner might have
            // stopped.
            // Let's use the 'minedByMe' logic from updateChartWithBlock or similar.
            // A robust way: check if we have the secret phrase? No, Generator holds active
            // nonces.
            // Let's assume if we have a GeneratorState for it, it's local.

            Block prevBlock = blockchain != null ? blockchain.getBlock(lastBlock.getPreviousBlockId()) : null;
            long deadline = 0;
            if (prevBlock != null) {
                deadline = lastBlock.getTimestamp() - prevBlock.getTimestamp();
            }

            data.entries.add(new MinerEntry(generatorId, BigInteger.valueOf(deadline),
                    isLocal ? MinerEntry.Type.WINNER_LOCAL : MinerEntry.Type.WINNER_REMOTE, lastBlock.getHeight(),
                    Convert.fromEpochTime(lastBlock.getTimestamp()).getTime(), lastBlock.getId()));
        }

        // 2. Add Active Miners (Sorted by arrival)
        if (showNodeShare) {
            List<MinerEntry> activeMiners = new ArrayList<>(currentBlockDeadlines);
            activeMiners.sort(Comparator.comparing(e -> e.deadline));
            data.entries.addAll(activeMiners);
        }

        // 3. Add History (Recent Generators)
        int historySize = recentGenerators.size();
        for (int i = historySize - 2; i >= 0; i--) {
            BlockHistoryEntry entry = recentGenerators.get(i);
            LocalBlockInfo localInfo = localMinedBlocks.get(entry.height);
            boolean isLocal = localInfo != null && localInfo.generatorId == entry.generatorId;
            if (!isLocal) {
                isLocal = localGeneratorIds.contains(entry.generatorId);
            }

            BigInteger deadline = null;
            if (i > 0) {
                BlockHistoryEntry prevEntry = recentGenerators.get(i - 1);
                if (entry.height == prevEntry.height + 1) {
                    deadline = BigInteger.valueOf(Math.max(0, (long) entry.timestamp - prevEntry.timestamp));
                }
            }
            data.entries.add(new MinerEntry(entry.generatorId, deadline,
                    isLocal ? MinerEntry.Type.HISTORY_LOCAL : MinerEntry.Type.HISTORY_REMOTE, entry.height,
                    Convert.fromEpochTime(entry.timestamp).getTime(), entry.blockId));
        }

        data.activeMinerCount = currentBlockDeadlines.stream()
                .map(e -> e.accountId)
                .distinct()
                .count();

        data.uniqueNodeMiners = nodeDeadlineHistory.values().stream()
                .flatMap(List::stream)
                .map(e -> e.accountId)
                .distinct()
                .count();

        data.bestDeadline = currentBlockDeadlines.stream()
                .map(e -> e.deadline)
                .min(BigInteger::compareTo)
                .orElse(null);

        // Update MAs in background
        if (updateHistory) {
            minerCountMA.add((double) data.uniqueNodeMiners);
            if (data.bestDeadline != null) {
                bestNodeDeadlineMA.add(data.bestDeadline.doubleValue());
            }
        }

        // Collect Stats
        data.avgMiners = minerCountMA.getAverage();
        data.maxMiners = minerCountMA.getMax();
        data.minMiners = minerCountMA.getMin();
        data.avgNodeDeadline = bestNodeDeadlineMA.getAverage();
        data.maxNodeDeadline = bestNodeDeadlineMA.getMax();
        data.minNodeDeadline = bestNodeDeadlineMA.getMin();

        data.avgReceivedDeadlines = receivedDeadlineCountMA.getAverage();
        data.maxReceivedDeadlines = receivedDeadlineCountMA.getMax();

        return data;
    }

    /**
     * Updates the miners table and related progress bars.
     * Respects UI optimization settings.
     *
     * @param data The update data containing miner entries and statistics.
     */
    private void updateMinersUI(MinerUpdateData data) {
        this.lastMinerData = data;
        if (data == null)
            return;

        if (data.bestDeadline != null) {
            nodeBestDeadline = data.bestDeadline;
        }

        boolean deadlinesDialogVisible = MinersDeadlinesDialog.isDialogVisible();
        boolean listDialogVisible = MinersListDialog.isDialogVisible();

        if (uiOptimizationEnabled && !isTabActive && !deadlinesDialogVisible && !listDialogVisible)
            return;

        minersTableModel.setData(data.entries);

        updateProgressBar(minerCountProgressBar, data.avgMiners, data.maxMiners,
                val -> String.format("C: %d / %d | MA: %.1f - min: %.1f - max: %.1f", data.activeMinerCount,
                        data.uniqueNodeMiners, val, data.minMiners, data.maxMiners));

        refreshMinerLabelsText();

        String currentBestStr = (data.bestDeadline != null ? data.bestDeadline.longValue() + " s"
                : "0 s");

        updateProgressBar(bestNodeDeadlineProgressBar, data.avgNodeDeadline, data.maxNodeDeadline,
                val -> String.format("C: %s | MA: %.0f s - min: %.0f s - max: %.0f s", currentBestStr, val,
                        data.minNodeDeadline, data.maxNodeDeadline));

        updateProgressBar(receivedDeadlineCountProgressBar, data.avgReceivedDeadlines, data.maxReceivedDeadlines,
                val -> String.format("C: %d | MA: %.1f - max: %.1f",
                        deadlineReceivedCountSinceLastBlock, val, data.maxReceivedDeadlines));

        if (minersTable.isShowing()) {
            for (int i = 0; i < minersTable.getColumnCount(); i++) {
                String columnName = minersTable.getColumnName(i);
                if (MinersTableModel.COL_HEIGHT.equals(columnName)
                        || MinersTableModel.COL_DEADLINE.equals(columnName)) {
                    TableUtils.packColumn(minersTable, i, 2);
                }
            }
        }

        MinersDeadlinesDialog.packColumns();
        MinersListDialog.updateIfVisible(recentGenerators, nodeDeadlineHistory);
    }

    private JLabel createLabel(String text, String colorKey, String tooltip) {
        // Using an anonymous inner class to override updateUI, making the label
        // theme-aware.
        JLabel label = new JLabel(text) {
            @Override
            public void updateUI() {
                super.updateUI();
                GuiFontManager.applyDefaultFont(this);
                // Re-apply color from palette on UI update, ensuring it stays in sync with
                // theme changes.
                if (colorKey != null) {
                    setForeground(ColorPaletteManager.getColor(colorKey));
                }
                // Re-apply strikethrough if this is a toggle-able label
                Object visibleProp = getClientProperty("visible");
                if (visibleProp instanceof Boolean) {
                    GuiFontManager.updateLabelStrikethrough(this, (Boolean) visibleProp);
                }
            }
        };
        if (colorKey != null) {
            label.setForeground(ColorPaletteManager.getColor(colorKey));
        }
        if (tooltip != null) {
            ContextMenuUtils.addInfoTooltip(parentFrame, label, tooltip, colorKey);
        }
        return label;
    }

    private void refreshMinerLabelsText() {
        if (nodeMinersCountLabel == null || networkMinersCountLabel == null)
            return;

        long active = currentBlockDeadlines.stream().map(e -> e.accountId).distinct().count();
        long discovered = (long) minerCountMA.getLast();
        long network = (long) networkMinersMA.getLast();

        nodeMinersCountLabel.setText("<html>Node Miners: <font color='" + toHex(GuiColors.getBlockGenActiveMiner())
                + "'>" + active + "</font> / <font color='"
                + toHex(GuiColors.getBlockGenNodeMiners()) + "'>" + discovered + "</font></html>");

        networkMinersCountLabel
                .setText("<html>Network Miners: <font color='" + toHex(GuiColors.getBlockGenNetworkMiners())
                        + "'>" + network + "</font></html>");
    }

    private void addMinerListListener(JLabel label, int tabIndex) {
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isRightMouseButton(e)) {
                    MinersListDialog.showDialog(parentFrame, tabIndex, recentGenerators, nodeDeadlineHistory, ctx.getBlockchain());
                }
            }
        });
    }

    private JProgressBar createProgressBar(int min, int max, Color color, String initialString, Dimension size) {
        JProgressBar bar = new JProgressBar(min, max);
        if (color != null) {
            bar.setForeground(color);
        }
        bar.setBorder(BorderFactory.createEmptyBorder());
        bar.setPreferredSize(size);
        bar.setMinimumSize(size);
        bar.setString(initialString);
        bar.setValue(min);
        GuiFontManager.setupProgressBar(bar, null);
        allProgressBars.add(bar);
        return bar;
    }

    private final List<JProgressBar> allProgressBars = new ArrayList<>();

    @Override
    public void updateUI() {
        super.updateUI();
        if (allProgressBars != null) {
            for (JProgressBar bar : allProgressBars) {
                GuiFontManager.setupProgressBar(bar, null);
            }
        }
        // After a Look and Feel change, chart colors are not automatically updated.
        // We need to manually re-apply them from the ColorPaletteManager.
        if (chartPanel != null) {
            updateChartColors();
        }
        if (pieChartPanel != null) {
            // Re-calculating data also re-applies colors.
            PieChartUpdateData data = calculatePieChartData();
            if (data != null) {
                updatePieChartUI(data);
            }
        }
        updateChartFonts();
    }

    private void updateChartFonts() {
        Font font = UIManager.getFont("Label.font");
        if (font == null)
            return;

        if (chartPanel != null && chartPanel.getChart() != null) {
            GuiFontManager.applyFontToChart(chartPanel.getChart(), font);
        }

        if (pieChartPanel != null && pieChartPanel.getChart() != null) {
            GuiFontManager.applyFontToChart(pieChartPanel.getChart(), font);
        }
    }

    private void addLabelToggleListener(JLabel label, Consumer<Boolean> onToggleAction) {
        label.putClientProperty("visible", true);

        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent evt) {
                if (SwingUtilities.isLeftMouseButton(evt)) {
                    // Toggle the visibility state
                    boolean isVisible = !((boolean) label.getClientProperty("visible"));
                    label.putClientProperty("visible", isVisible);

                    // Trigger a UI update on the label to re-apply font and style
                    label.updateUI();

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

    private void updateProgressBar(JProgressBar bar, double value, double max,
            java.util.function.Function<Double, String> stringFormatter) {
        if (max > 0) {
            bar.setValue((int) ((value / max) * 100));
        } else {
            bar.setValue(0);
        }
        bar.setString(stringFormatter.apply(value));
    }

    private void registerLongTooltip(JComponent component) {
        component.addMouseListener(new MouseAdapter() {
            final int defaultDismissDelay = ToolTipManager.sharedInstance().getDismissDelay();
            final int defaultInitialDelay = ToolTipManager.sharedInstance().getInitialDelay();
            final int defaultReshowDelay = ToolTipManager.sharedInstance().getReshowDelay();

            @Override
            public void mouseEntered(MouseEvent e) {
                ToolTipManager.sharedInstance().setDismissDelay(120000); // 2 minutes
                ToolTipManager.sharedInstance().setInitialDelay(100); // Fast show
                ToolTipManager.sharedInstance().setReshowDelay(100); // Fast reshow
            }

            @Override
            public void mouseExited(MouseEvent e) {
                ToolTipManager.sharedInstance().setDismissDelay(defaultDismissDelay);
                ToolTipManager.sharedInstance().setInitialDelay(defaultInitialDelay);
                ToolTipManager.sharedInstance().setReshowDelay(defaultReshowDelay);
            }
        });
    }

    private void updateNodeShareLegend(double percent) {
        nodeShareLegendLabel.setText(String.format("Node Share: %.2f%%", percent));
        GuiFontManager.updateLabelStrikethrough(nodeShareLegendLabel, showNodeShare);
    }

    private void updateNetworkShareLegend(double percent) {
        networkShareLegendLabel.setText(String.format("Network Share: %.2f%%", percent));
        GuiFontManager.updateLabelStrikethrough(networkShareLegendLabel, showNetworkShare);
    }

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
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

    private void showLegendPopup(MouseEvent e) {
        JPopupMenu popup = new JPopupMenu();
        JLabel label = new JLabel(getLegendHtml());
        label.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        popup.add(label);
        popup.show(e.getComponent(), e.getX(), e.getY());
    }

    public static String getLegendHtml() {
        return "<html><body style='width: 350px'>" +
                "<h3>Table Legend & Information</h3>" +
                "<b>Row Colors (Text):</b><br>" +
                "<span style='color:" + toHex(GuiColors.getBlockGenNodeMiners())
                + "'>&#9632;</span> <b>Light Green:</b> Local Block (Mined by this node)<br>" +
                "<span style='color:" + toHex(GuiColors.getBlockGenChainDeadline())
                + "'>&#9632;</span> <b>Dark Green:</b> Remote Block (Mined by the network)<br>" +
                "<span style='color:" + toHex(GuiColors.getBlockGenActiveMiner())
                + "'>&#9632;</span> <b>Goldenrod:</b> Active Deadline (Submitted to this node)<br><br>"
                +
                "<b>Indicators (" + MinersTableModel.COL_IO + " Column):</b><br>" +
                "<b>&#8963; (Up):</b> Local Generation (Outgoing)<br>" +
                "<b>&#8964; (Down):</b> Remote Generation (Incoming)<br><br>" +
                "<b>Data Columns:</b><br>" +
                "<b>" + MinersTableModel.COL_HEIGHT + ":</b> The block height in the blockchain.<br>" +
                "<b>" + MinersTableModel.COL_IO + ":</b> Input/Output direction (Local vs Remote).<br>" +
                "<b>" + MinersTableModel.COL_BLOCK_ID + ":</b> The unique identifier of the block.<br>" +
                "<b>" + MinersTableModel.COL_TIME + ":</b> The timestamp of block generation.<br>" +
                "<b>" + MinersTableModel.COL_NAME + ":</b> The name of the miner (if available).<br>" +
                "<b>" + MinersTableModel.COL_ACCOUNT_ID + ":</b> The numeric account ID of the miner.<br>" +
                "<b>" + MinersTableModel.COL_DEADLINE + ":</b> The deadline value submitted by the miner.<br><br>" +
                "<b>History Size:</b> " + CHART_HISTORY_SIZE + " blocks" +
                "</body></html>";
    }

    private static class MinerPieToolTipGenerator implements PieToolTipGenerator {
        @Override
        public String generateToolTip(PieDataset dataset, Comparable key) {
            if (key == null) {
                return null;
            }
            String keyString = (String) key;
            try {
                if (keyString.startsWith("Others")) {
                    double share = dataset.getValue(key).doubleValue();
                    String count = keyString.replaceAll("[^0-9]", "");
                    if (count.isEmpty())
                        count = "N/A";
                    return String.format("<html><b>Others (%s)</b><br><br>" +
                            "This category aggregates multiple miners, each with an individual share of less than 1%% of the last %d blocks.<br><br>"
                            +
                            "<b>Combined Share:</b> %.2f%%<br>" +
                            "<b>Number of Miners:</b> %s</html>",
                            count, CHART_HISTORY_SIZE, share, count);
                }

                if ("Waiting for blocks...".equals(keyString)) {
                    return "Waiting for block data to populate the chart";
                }

                if ("Filtered out".equals(keyString)) {
                    return "Data filtered out by current selection";
                }

                long generatorId = Long.parseLong(keyString);
                double share = dataset.getValue(key).doubleValue();

                String accountRS = SignumAddress.fromId(SignumID.fromLong(generatorId)).toString();
                application.module.node.Account account = application.module.node.Account.getAccount(generatorId);
                String name = (account != null && account.getName() != null && !account.getName().isEmpty())
                        ? account.getName()
                        : "N/A";

                return String.format(
                        "<html><b>Miner:</b> %s<br><b>ID:</b> %s<br><b>Address:</b> %s<br><b>Share:</b> %.2f%%</html>",
                        name,
                        Convert.toUnsignedLong(generatorId),
                        accountRS,
                        share);
            } catch (Exception e) {
                return keyString;
            }
        }
    }

    private class BlockChartToolTipGenerator implements XYToolTipGenerator {
        @Override
        public String generateToolTip(XYDataset dataset, int series, int item) {
            String name = dataset.getSeriesKey(series).toString();
            double x = dataset.getXValue(series, item);
            double y = dataset.getYValue(series, item);

            String valueStr;
            if (name.contains("Network Size")) {
                valueStr = formatDataSize(y);
            } else if (name.contains("Share")) {
                valueStr = String.format("%.2f%%", y);
            } else if (name.contains("Deadline") || name.contains("Mined Block")) {
                valueStr = String.format("%.0f s", y);
            } else if (name.contains("Base Target") || name.contains("Miners") || name.contains("Deadlines Rx")) {
                valueStr = String.format("%.0f", y);
            } else {
                valueStr = String.format("%.2f", y);
            }

            return String.format("<html><b>%s:</b> %s<br><b>Height:</b> %d</html>", name, valueStr, (int) x);
        }
    }

    private static class MinerPieSectionLabelGenerator implements PieSectionLabelGenerator {
        @Override
        public String generateSectionLabel(PieDataset dataset, Comparable key) {
            if (dataset == null || key == null) {
                return null;
            }
            Number value = dataset.getValue(key);
            if (value == null) {
                return null;
            }

            double percent = value.doubleValue();

            if (percent < 5.0) {
                return null;
            }

            String keyString = (String) key;
            String label;

            if (keyString.startsWith("Others") || keyString.startsWith("Waiting") || keyString.startsWith("Filtered")) {
                label = keyString;
            } else {
                try {
                    long generatorId = Long.parseLong(keyString);
                    application.module.node.Account account = application.module.node.Account.getAccount(generatorId);
                    String name = (account != null && account.getName() != null && !account.getName().isEmpty())
                            ? account.getName()
                            : Convert.toUnsignedLong(generatorId);
                    label = name;
                } catch (NumberFormatException e) {
                    label = keyString;
                }
            }

            return String.format("%s %.1f%%", label, percent);
        }

        @Override
        public AttributedString generateAttributedSectionLabel(PieDataset dataset, Comparable key) {
            return null;
        }
    }

    // --- Block History Entry Class ---
    public static class BlockHistoryEntry {
        final long blockId;
        final long generatorId;
        final int height;
        final int timestamp;

        BlockHistoryEntry(long blockId, long generatorId, int height, int timestamp) {
            this.blockId = blockId;
            this.generatorId = generatorId;
            this.height = height;
            this.timestamp = timestamp;
        }

        public long getBlockId() {
            return blockId;
        }

        public long getGeneratorId() {
            return generatorId;
        }

        public int getHeight() {
            return height;
        }

        public int getTimestamp() {
            return timestamp;
        }
    }

    static class LocalBlockInfo {
        final long generatorId;
        final BigInteger deadline;

        LocalBlockInfo(long generatorId, BigInteger deadline) {
            this.generatorId = generatorId;
            this.deadline = deadline;
        }
    }

    // --- Miner Entry Class ---
    public static class MinerEntry implements Comparable<MinerEntry> {
        public enum Type {
            WINNER_LOCAL,
            WINNER_REMOTE,
            ACTIVE_LOCAL,
            HISTORY_LOCAL,
            HISTORY_REMOTE
        }

        final long accountId;
        final String accountRS;
        final String minerName;
        final BigInteger deadline;
        final Type type;
        final int height;
        final long timestamp;
        final long blockId;

        MinerEntry(long accountId, BigInteger deadline, Type type, int height, long timestamp, long blockId) {
            this.accountId = accountId;
            this.accountRS = SignumAddress.fromId(SignumID.fromLong(accountId)).toString();
            application.module.node.Account account = application.module.node.Account.getAccount(accountId);
            this.minerName = (account != null && account.getName() != null) ? account.getName() : "";
            this.deadline = deadline;
            this.type = type;
            this.height = height;
            this.timestamp = timestamp;
            this.blockId = blockId;
        }

        public long getAccountId() {
            return accountId;
        }

        public String getAccountRS() {
            return accountRS;
        }

        public String getMinerName() {
            return minerName;
        }

        public BigInteger getDeadline() {
            return deadline;
        }

        public Type getType() {
            return type;
        }

        public int getHeight() {
            return height;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public long getBlockId() {
            return blockId;
        }

        @Override
        public int compareTo(MinerEntry o) {
            boolean thisLocal = isLocal(this.type);
            boolean otherLocal = isLocal(o.type);
            if (thisLocal == otherLocal)
                return 0;
            return thisLocal ? 1 : -1;
        }

        private boolean isLocal(Type t) {
            return t == Type.WINNER_LOCAL || t == Type.HISTORY_LOCAL || t == Type.ACTIVE_LOCAL;
        }
    }

    // --- Custom Renderer ---
    public static class MinerTableCellRenderer extends DefaultTableCellRenderer implements javax.swing.plaf.UIResource {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            MinersTableModel model = (MinersTableModel) table.getModel();
            // Convert view row to model row
            int modelRow = table.convertRowIndexToModel(row);
            MinerEntry entry = model.getRow(modelRow);

            if (!isSelected) {
                c.setBackground(table.getBackground()); // Default background
                switch (entry.type) {
                    case WINNER_LOCAL: // Uses new COLOR_NODE_MINERS
                    case HISTORY_LOCAL: // Uses new COLOR_NODE_MINERS
                        c.setForeground(GuiColors.getBlockGenNodeMiners()); // Lime Green (Light Green)
                        break;
                    case WINNER_REMOTE:
                    case HISTORY_REMOTE:
                        c.setForeground(GuiColors.getBlockGenChainDeadline()); // Dark Green
                        break;
                    case ACTIVE_LOCAL:
                        c.setForeground(GuiColors.getBlockGenActiveMiner()); // Goldenrod (Readable Yellow)
                        break;
                    default:
                        c.setForeground(table.getForeground());
                }
            } else {
                c.setForeground(table.getSelectionForeground());
                c.setBackground(table.getSelectionBackground());
            }

            // Icon logic for I/O column (index 1)
            if (table.getColumnName(column).equals(MinersTableModel.COL_IO)) {
                setText("");
                if (entry.type == MinerEntry.Type.WINNER_LOCAL || entry.type == MinerEntry.Type.HISTORY_LOCAL
                        || entry.type == MinerEntry.Type.ACTIVE_LOCAL) {
                    setIcon(new CustomDrawingIcon(CustomDrawings.Chevron.UP, c.getForeground()));
                } else if (entry.type == MinerEntry.Type.WINNER_REMOTE
                        || entry.type == MinerEntry.Type.HISTORY_REMOTE) {
                    setIcon(new CustomDrawingIcon(CustomDrawings.Chevron.DOWN, c.getForeground()));
                } else {
                    setIcon(null);
                }
                setHorizontalAlignment(SwingConstants.CENTER);
            } else {
                setIcon(null);
                setHorizontalAlignment(SwingConstants.LEFT);
            }
            return c;
        }
    }

    // --- Table Model ---

    public static class MinersTableModel extends AbstractTableModel {
        public static final String COL_HEIGHT = "Height";
        public static final String COL_IO = "I/O";
        public static final String COL_BLOCK_ID = "Block ID";
        public static final String COL_TIME = "Time";
        public static final String COL_NAME = "Name";
        public static final String COL_ACCOUNT_ID = "Account ID";
        public static final String COL_DEADLINE = "Deadline";

        private final String[] columnNames = { COL_HEIGHT, COL_IO, COL_BLOCK_ID, COL_TIME, COL_NAME, COL_ACCOUNT_ID,
                COL_DEADLINE };
        private List<MinerEntry> data = new ArrayList<>();

        public void setData(List<MinerEntry> newData) {
            this.data = newData;
            fireTableDataChanged();
        }

        public MinerEntry getRow(int row) {
            return data.get(row);
        }

        @Override
        public int getRowCount() {
            return data.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            MinerEntry entry = data.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return String.valueOf(entry.height);
                case 1:
                    return entry;
                case 2:
                    if (entry.blockId == 0)
                        return "-";
                    return Convert.toUnsignedLong(entry.blockId);
                case 3:
                    if (entry.timestamp > 0) {
                        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(entry.timestamp));
                    }
                    return "-";
                case 4:
                    return entry.minerName;
                case 5:
                    return Convert.toUnsignedLong(entry.accountId);
                case 6:
                    if (entry.deadline == null) {
                        return "-";
                    }
                    return entry.deadline.longValue() + " s";
                default:
                    return null;
            }
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            if (COL_IO.equals(getColumnName(columnIndex)))
                return MinerEntry.class;
            return String.class;
        }
    }

    @Override
    public boolean isValidateRoot() {
        return true;
    }
}
