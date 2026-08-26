package application.module.node;

import static application.module.node.Constants.FEE_QUANT_SIP3;
import static application.module.node.Constants.ONE_SIGNA;

import application.module.node.at.*;
import application.module.node.crypto.Crypto;
import application.module.node.db.BlockDb;
import application.module.node.db.DerivedTable;
import application.module.node.db.TransactionDb;
import application.module.node.db.cache.DBCacheManagerImpl;
import application.module.node.db.store.BlockchainStore;
import application.module.node.db.store.DerivedTableManager;
import application.module.node.db.store.Stores;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.peer.Peer;
import application.module.node.peer.PeerManager;
import application.module.node.peer.PeerMetric;
import application.module.node.peer.Peers;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.module.node.services.AccountService;
import application.module.node.services.AliasService;
import application.module.node.services.ATService;
import application.module.node.services.BlockService;
import application.module.node.services.EscrowService;
import application.module.node.services.IndirectIncomingService;
import application.module.node.services.SubscriptionService;
import application.module.node.services.TimeService;
import application.module.node.services.TransactionService;
import application.module.node.statistics.StatisticsManagerImpl;
import application.module.node.transactionduplicates.TransactionDuplicatesCheckerImpl;
import application.module.node.unconfirmedtransactions.UnconfirmedTransactionStore;
import application.module.node.util.Convert;
import application.module.node.util.DownloadCacheImpl;
import application.module.node.util.JSON;
import application.module.node.util.Listener;
import application.module.node.util.DurationFormatter;
import application.module.node.util.Listeners;
import application.module.node.util.ThreadPool;
import application.utils.io.PathUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.text.SimpleDateFormat;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.io.BufferedWriter;
import java.math.BigInteger;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.nio.file.Files;
import java.util.Date;
import java.nio.file.Paths;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.sun.management.OperatingSystemMXBean;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import signumj.entity.SignumID;

public final class BlockchainProcessorImpl implements BlockchainProcessor {

    private final Logger logger = LoggerFactory.getLogger(BlockchainProcessorImpl.class);
    private final Stores stores;
    private final BlockchainImpl blockchain;
    private final BlockService blockService;
    private final ATService atService;
    private final AccountService accountService;
    private final SubscriptionService subscriptionService;
    private final EscrowService escrowService;
    private final TimeService timeService;
    private final TransactionService transactionService;
    private final PropertyService propertyService;
    private final FluxCapacitor fluxCapacitor;
    private final TransactionProcessorImpl transactionProcessor;
    private final EconomicClustering economicClustering;
    private final BlockchainStore blockchainStore;
    private final BlockDb blockDb;
    private final TransactionDb transactionDb;
    private final DownloadCacheImpl downloadCache;
    private final DerivedTableManager derivedTableManager;
    private final StatisticsManagerImpl statisticsManager;
    private final Generator generator;
    private final DBCacheManagerImpl dbCacheManager;
    private final IndirectIncomingService indirectIncomingService;
    private final long genesisBlockId;
    private final String dbType;
    private final String dbVersion;
    private final AtomicBoolean oclInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    private final boolean logSyncProgressToCsv;
    private long lastSyncLogTimestamp;
    private long accumulatedSyncTimeMs;
    private long accumulatedSyncInProgressTimeMs;
    private final AtomicBoolean isSyncingForLog = new AtomicBoolean(false);

    private final boolean measurementActive;
    private final ExecutorService measurementLogExecutor;
    private final ExecutorService maintenanceExecutor = Executors
            .newSingleThreadExecutor(task -> new Thread(task, "ArchivalMaintenanceThread"));
    private final List<String> measurementData = Collections.synchronizedList(new ArrayList<>());
    private final String measurementDir;
    private final String syncProgressLogFilename;
    private final AtomicReference<String> syncMeasurementLogFilename = new AtomicReference<>();
    private final String dbTrimLogFilename;
    private final String dbPruneLogFilename;

    private String[] syncProgressColumnNames = {
            "Block_height", "Accumulated_sync_in_progress_time[s]", "Accumulated_sync_time[s]"
    };
    private String[] syncMeasurementColumnNames = {
            "Block_height", "Block_timestamp[s]", "Cumulative_difficulty", "Accumulated_sync_in_progress_time[ms]",
            "Accumulated_sync_time[ms]", "Push_block_time[ms]", "Validation_time[ms]", "Tx_loop_time[ms]",
            "Housekeeping_time[ms]", "Tx_apply_time[ms]", "AT_time[ms]", "Subscription_time[ms]",
            "Block_apply_time[ms]", "Commit_time[ms]", "Misc_time[ms]", "AT_count", "User_transaction_count",
            "All_transaction_count"
    };

    private static final long TARGET_AT_ID = Convert.parseUnsignedLong("9252460999283466420");

    private static final int MAX_TIMESTAMP_DIFFERENCE = 15;
    private boolean oclVerify;
    private final int oclUnverifiedQueue;

    private final Semaphore gpuUsage = new Semaphore(2);

    private final ArchivalMode archivalMode;

    // The current trim height requested from derived table datas
    private final AtomicInteger currentTrimHeight = new AtomicInteger();

    // The current prune height tracked from database
    private final AtomicInteger currentPruneHeight = new AtomicInteger();

    private final Listeners<Block, Event> blockListeners = new Listeners<>();
    private final Listeners<TrimStats, Event> trimListeners = new Listeners<>();
    private final Listeners<PruneStats, Event> pruneListeners = new Listeners<>();
    private final AtomicReference<Peer> lastBlockchainFeeder = new AtomicReference<>();
    private final AtomicInteger lastBlockchainFeederHeight = new AtomicInteger();
    private final AtomicBoolean getMoreBlocks = new AtomicBoolean(true);
    // Manual pause for user interaction
    private final AtomicBoolean getMoreBlocksPause = new AtomicBoolean(false);
    private final AtomicBoolean blockImporterPause = new AtomicBoolean(false);
    // Automatic pause for functions when needed
    private final AtomicBoolean getMoreBlocksAutoPause = new AtomicBoolean(false);
    private final AtomicBoolean blockImporterAutoPause = new AtomicBoolean(false);

    private final ReentrantReadWriteLock getMoreBlocksLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock blockImporterLock = new ReentrantReadWriteLock();

    private final AtomicBoolean isMaintenanceRunning = new AtomicBoolean(false);
    private final AtomicBoolean isTrimming = new AtomicBoolean(false);
    private final AtomicBoolean isPruning = new AtomicBoolean(false);
    private final AtomicBoolean isScanning = new AtomicBoolean(false);

    private final AtomicReference<CompletableFuture<Void>> archivalMaintenanceFuture = new AtomicReference<>();
    private final AtomicBoolean isScheduleTrimRequested = new AtomicBoolean(false);

    /**
     * Tracks the current state of the database consistency resolution process.
     * This volatile field is updated by the
     * {@link #autoResolveDatabaseConsistency()} or
     * {@link #manualResolveDatabaseConsistency()}
     * method
     * and read by the GUI and other components to reflect the status.
     */
    private volatile ResolutionState resolutionState = ResolutionState.IDLE;
    /**
     * Stores the consistency state from the previous check to detect state
     * transitions.
     * Used by {@link #checkDatabaseState()} to trigger auto-resolve only when the
     * state
     * actually changes to {@link ConsistencyState#INCONSISTENT}.
     */
    private final AtomicReference<ConsistencyState> consistencyState = new AtomicReference<>(
            ConsistencyState.UNDEFINED);
    private volatile ConsistencyState previousConsistencyState = ConsistencyState.UNDEFINED;

    private final AtomicReference<QueueStatus> queueStatus = new AtomicReference<>();
    private final AtomicReference<PerformanceStats> performanceStats = new AtomicReference<>();
    private final AtomicReference<String> currentlyTrimmingTable = new AtomicReference<>();
    private final AtomicLong uploadedVolume = new AtomicLong();
    private final AtomicLong downloadedVolume = new AtomicLong();
    private final AtomicLong lastCheckTotalMined = new AtomicLong(0);
    private final AtomicLong lastCheckTotalEffectiveBalance = new AtomicLong(0);
    private final AtomicInteger lastCheckHeight = new AtomicInteger(0);

    private int autoPopOffLastStuckHeight = 0;
    private int autoPopOffNumberOfBlocks = 0;
    private ATProcessorCache atProcessorCache;
    private long txApplyTimeNanos;
    private long atTimeNanos;
    private long subscriptionTimeNanos;
    private long blockApplyTimeNanos;
    private long atTimeMs;

    private final Listener<Peer> peerListener = peer -> blockListeners.notify(null, Event.PEERS_UPDATED);

    private final Listener<Peer> netVolumeListener = peer -> updateAndFireNetVolume();

    // Profile-scoped peer manager (setter-injected after construction, see setPeerManager)
    private volatile PeerManager peerManager;

    private final boolean autoPopOffEnabled;
    private final AtomicBoolean skipDbCheckOnManualPopOff = new AtomicBoolean(false);

    private final AtomicInteger restoreBlocksCount = new AtomicInteger(0);
    private int minRollbackHeight = 0;
    private final AtomicInteger manualPopOffBlocksCount = new AtomicInteger(0);
    private final AtomicInteger autoPopOffBlocksCount = new AtomicInteger(0);
    private AtomicInteger manualLastPopOffHeight = new AtomicInteger(-1);
    private AtomicInteger autoLastPopOffHeight = new AtomicInteger(-1);
    private AtomicInteger beforeRollbackHeight = new AtomicInteger(0);
    private volatile PopOffState manualPopOffState = PopOffState.IDLE;
    private volatile PopOffState autoPopOffState = PopOffState.IDLE;

    private final Listeners<PeerMetric, PeerMetricEvent> peerMetricListeners = new Listeners<>();
    private final Listeners<ForkCacheStats, Event> forkCacheStatsListeners = new Listeners<>();
    private final Listeners<PerformanceStats, Event> performanceStatsListeners = new Listeners<>();
    private final Listeners<QueueStatus, Event> queueStatusListeners = new Listeners<>();
    private final Listeners<Boolean, Event> syncStateListeners = new Listeners<>();

    @Override
    public void addPeerMetricListener(Listener<PeerMetric> listener) {
        peerMetricListeners.addListener(listener, PeerMetricEvent.METRIC);
    }

    @Override
    public void removePeerMetricListener(Listener<PeerMetric> listener) {
        peerMetricListeners.removeListener(listener, PeerMetricEvent.METRIC);
    }

    @Override
    public void addForkCacheStatsListener(Listener<ForkCacheStats> listener) {
        forkCacheStatsListeners.addListener(listener, Event.FORK_CACHE_CHANGED);
    }

    @Override
    public void removeForkCacheStatsListener(Listener<ForkCacheStats> listener) {
        forkCacheStatsListeners.removeListener(listener, Event.FORK_CACHE_CHANGED);
    }

    @Override
    public void notifyPeerMetric(PeerMetric metric) {
        peerMetricListeners.notify(metric, PeerMetricEvent.METRIC);
    }

    @Override
    public void addPerformanceStatsListener(Listener<PerformanceStats> listener) {
        performanceStatsListeners.addListener(listener, Event.PERFORMANCE_STATS_UPDATED);
    }

    @Override
    public void removePerformanceStatsListener(Listener<PerformanceStats> listener) {
        performanceStatsListeners.removeListener(listener, Event.PERFORMANCE_STATS_UPDATED);
    }

    @Override
    public void addQueueStatusListener(Listener<QueueStatus> listener) {
        queueStatusListeners.addListener(listener, Event.QUEUE_STATUS_CHANGED);
    }

    @Override
    public void removeQueueStatusListener(Listener<QueueStatus> listener) {
        queueStatusListeners.removeListener(listener, Event.QUEUE_STATUS_CHANGED);
    }

    @Override
    public void addTrimListener(Listener<TrimStats> listener, Event eventType) {
        trimListeners.addListener(listener, eventType);
    }

    @Override
    public void removeTrimListener(Listener<TrimStats> listener, Event eventType) {
        trimListeners.removeListener(listener, eventType);
    }

    @Override
    public void addPruneListener(Listener<PruneStats> listener, Event eventType) {
        pruneListeners.addListener(listener, eventType);
    }

    @Override
    public void removePruneListener(Listener<PruneStats> listener, Event eventType) {
        pruneListeners.removeListener(listener, eventType);
    }

    @Override
    public void addSyncStateListener(Listener<Boolean> listener) {
        syncStateListeners.addListener(listener, Event.SYNC_STATE_CHANGED);
    }

    @Override
    public void removeSyncStateListener(Listener<Boolean> listener) {
        syncStateListeners.removeListener(listener, Event.SYNC_STATE_CHANGED);
    }

    @Override
    public void shutdown() {

        if (isShutdown.getAndSet(true)) {
            return; // Already shutdown
        }

        logger.info("Shutting down blockchain processor...");

        // NEW: Shut down transaction processor first
        transactionProcessor.shutdown();

        if (isMaintenanceRunning.get()) {
            String phase = isPruning.get() ? "pruning" : "trimming";
            logger.info("Waiting for database {} maintenance to finish before shutdown...", phase);
        }

        if (isPruning.get()) {
            logger.info("Waiting for database prune to finish before shutdown...");
        }

        CompletableFuture<Void> maintenanceFuture = archivalMaintenanceFuture.get();
        if (maintenanceFuture != null && !maintenanceFuture.isDone()) {
            logger.info("Waiting for database archival maintenance task to finish before shutdown...");
            try {
                maintenanceFuture.join();
            } catch (Exception e) {
                logger.warn("Archival maintenance task finished with error during shutdown: {}", e.getMessage());
            }
        }

        if (manualPopOffState == PopOffState.ACTIVE || autoPopOffState == PopOffState.ACTIVE) {
            logger.info("Waiting for pop-off to finish before shutdown...");
        }

        // Stop getMoreBlocks and blockImporter threads
        getMoreBlocksAutoPause.set(true);
        blockImporterAutoPause.set(true);

        // Try to acquire locks non-blockingly to avoid hanging the entire shutdown
        // sequence
        boolean hasMoreBlocksLock = getMoreBlocksLock.writeLock().tryLock();
        boolean hasImporterLock = blockImporterLock.writeLock().tryLock();

        Throwable firstException = null;
        try {
            synchronized (transactionProcessor.getUnconfirmedTransactionsSyncObj()) {

                if (logSyncProgressToCsv) {
                    try {
                        long currentTime = System.currentTimeMillis();
                        long deltaTime = currentTime - lastSyncLogTimestamp;
                        accumulatedSyncTimeMs += deltaTime;
                        if (isSyncingForLog.get()) {
                            accumulatedSyncInProgressTimeMs += deltaTime;
                        }
                        writeSyncProgressLog(accumulatedSyncTimeMs, blockchain.getHeight());
                    } catch (Throwable t) {
                        logger.error("Error writing sync progress log", t);
                        if (firstException == null) {
                            firstException = t;
                        }
                    }
                }

                if (measurementActive) {
                    try {
                        writeMeasurementLog();
                    } catch (Throwable t) {
                        logger.error("Error writing measurement log", t);
                        if (firstException == null) {
                            firstException = t;
                        }
                    }
                }

                if (measurementLogExecutor != null) {
                    logger.info("Shutting down measurement log executor...");
                    measurementLogExecutor.shutdown();
                    try {
                        if (!measurementLogExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                            logger.warn("Measurement log executor did not terminate in 10 seconds.");
                            measurementLogExecutor.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        logger.warn("Interrupted while waiting for measurement log executor to terminate.");
                        measurementLogExecutor.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                }
                logger.info("Shutting down maintenance executor...");
                maintenanceExecutor.shutdown();
                try {
                    if (!maintenanceExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                        logger.warn("Maintenance executor did not terminate in 10 seconds.");
                        maintenanceExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    logger.warn("Interrupted while waiting for maintenance executor to terminate.");
                    maintenanceExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }

                blockListeners.clear();
                PeerManager peerManager = this.peerManager;
                if (peerManager != null) {
                    for (Peers.Event event : Peers.Event.values()) {
                        peerManager.removeListener(peerListener, event);
                    }
                }

                if (oclInitialized.get()) {
                    logger.info("Destroying OCLPoC instance from BlockchainProcessor.");
                    try {
                        OCLPoC.destroy();
                    } catch (Throwable t) {
                        logger.error("Error destroying OCLPoC", t);
                        if (firstException == null) {
                            firstException = t;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error("Error during BlockchainProcessor shutdown", t);
            if (firstException == null) {
                firstException = t;
            }
        } finally {
            if (hasImporterLock)
                blockImporterLock.writeLock().unlock();
            if (hasMoreBlocksLock)
                getMoreBlocksLock.writeLock().unlock();
        }

        if (firstException != null) {
            if (firstException instanceof RuntimeException) {
                throw (RuntimeException) firstException;
            }
            throw new RuntimeException("Error during BlockchainProcessor shutdown", firstException);
        }
    }

    public final void setOclVerify(Boolean b) {
        oclVerify = b;
    }

    public Boolean getOclVerify() {
        return oclVerify;
    }

    // --- Firing events ---

    public QueueStatus getQueueStatus() {
        return queueStatus.get();
    }

    @Override
    public int getForkCacheSize() {
        return downloadCache.getForkList().size();
    }

    @Override
    public int getRestoreBlocksCount() {
        return restoreBlocksCount.get();
    }

    @Override
    public int getManualPopOffBlocksCount() {
        return manualPopOffBlocksCount.get();
    }

    @Override
    public int getManualLastPopOffHeight() {
        return manualLastPopOffHeight.get();
    }

    @Override
    public int getAutoPopOffBlocksCount() {
        return autoPopOffBlocksCount.get();
    }

    @Override
    public int getAutoLastPopOffHeight() {
        return autoLastPopOffHeight.get();
    }

    @Override
    public int getBeforeRollbackHeight() {
        return beforeRollbackHeight.get();
    }

    private void notifyForkCacheStats() {
        forkCacheStatsListeners.notify(new ForkCacheStats(getForkCacheSize(), restoreBlocksCount.get()),
                Event.FORK_CACHE_CHANGED);
    }

    @Override
    public Collection<Peer> getAllPeers() {
        PeerManager peerManager = this.peerManager;
        Peers peers = peerManager != null ? peerManager.getPeers() : null;
        return peers != null ? peers.getAllPeers() : Collections.emptyList();
    }

    @Override
    public void setPeerManager(PeerManager peerManager) {
        this.peerManager = peerManager;
        Peers peers = peerManager != null ? peerManager.getPeers() : null;
        if (peers != null) {
            peers.listeners.addListener(netVolumeListener, Peers.Event.UPLOADED_VOLUME);
            peers.listeners.addListener(netVolumeListener, Peers.Event.DOWNLOADED_VOLUME);
            for (Peers.Event event : Peers.Event.values()) {
                peers.listeners.addListener(peerListener, event);
            }
        }
    }

    @Override
    public PerformanceStats getPerformanceStats() {
        return performanceStats.get();
    }

    @Override
    public long getAccumulatedSyncTimeMs() {
        return this.accumulatedSyncTimeMs;
    }

    @Override
    public long getAccumulatedSyncInProgressTimeMs() {
        return this.accumulatedSyncInProgressTimeMs;
    }

    public long getUploadedVolume() {
        return uploadedVolume.get();
    }

    public long getDownloadedVolume() {
        return downloadedVolume.get();
    }

    public BlockchainProcessorImpl(ThreadPool threadPool,
            BlockService blockService,
            TransactionProcessorImpl transactionProcessor,
            BlockchainImpl blockchain,
            PropertyService propertyService,
            SubscriptionService subscriptionService,
            TimeService timeService,
            DerivedTableManager derivedTableManager,
            BlockDb blockDb,
            TransactionDb transactionDb,
            EconomicClustering economicClustering,
            BlockchainStore blockchainStore,
            Stores stores,
            EscrowService escrowService,
            TransactionService transactionService,
            DownloadCacheImpl downloadCache,
            Generator generator,
            StatisticsManagerImpl statisticsManager,
            DBCacheManagerImpl dbCacheManager,
            AccountService accountService,
            IndirectIncomingService indirectIncomingService,
            AliasService aliasService,
            FluxCapacitor fluxCapacitor,
            ATService atService,
            ATProcessorCache atProcessorCache) {
        this.atProcessorCache = atProcessorCache;
        this.blockService = blockService;
        this.atService = atService;
        this.fluxCapacitor = fluxCapacitor;
        this.transactionProcessor = transactionProcessor;
        this.timeService = timeService;
        this.derivedTableManager = derivedTableManager;
        this.blockDb = blockDb;
        this.transactionDb = transactionDb;
        this.blockchain = blockchain;
        this.subscriptionService = subscriptionService;
        this.blockchainStore = blockchainStore;
        this.stores = stores;
        this.downloadCache = downloadCache;
        this.generator = generator;
        this.economicClustering = economicClustering;
        this.escrowService = escrowService;
        this.transactionService = transactionService;
        this.statisticsManager = statisticsManager;
        this.dbCacheManager = dbCacheManager;
        this.accountService = accountService;
        this.indirectIncomingService = indirectIncomingService;
        this.propertyService = propertyService;
        this.measurementActive = propertyService.getBoolean(Props.MEASUREMENT_ACTIVE);
        this.logSyncProgressToCsv = propertyService.getBoolean(Props.EXPERIMENTAL);

        // Determine DB Type and Version
        String dbUrl = propertyService.getString(Props.DB_URL);
        String determinedDbType = "Unknown";
        if (dbUrl != null) {
            if (dbUrl.contains(":sqlite:")) {
                determinedDbType = "SQLite";
            } else if (dbUrl.contains(":mariadb:")) {
                determinedDbType = "MariaDB";
            } else if (dbUrl.contains(":postgresql:")) {
                determinedDbType = "PostgreSQL";
            }
        }
        this.dbType = determinedDbType;

        String determinedDbVersion = "N/A";
        // Use instance-scoped DbContext from stores instead of static Db singleton
        // to enable multi-node isolation (each node has its own Connection pool)
        try (Connection con = stores.getDbContext().getConnection()) {
            DatabaseMetaData metaData = con.getMetaData();
            determinedDbVersion = metaData.getDatabaseProductVersion();
        } catch (SQLException e) {
            logger.warn("Could not get database version", e);
        }
        this.dbVersion = determinedDbVersion;

        if (logSyncProgressToCsv || measurementActive) {
            // A single-threaded executor ensures that log entries are written in order.
            this.measurementLogExecutor = Executors.newSingleThreadExecutor();
        } else {
            this.measurementLogExecutor = null;
        }

        // (peer listeners are registered in setPeerManager — the PeerManager is created
        //  after this processor, so they cannot be bound in the constructor)

        autoPopOffEnabled = propertyService.getBoolean(Props.AUTO_POP_OFF_ENABLED);

        this.skipDbCheckOnManualPopOff.set(propertyService.getBoolean(Props.POP_OFF_SKIP_DB_CHECK));

        oclVerify = propertyService.getBoolean(Props.GPU_ACCELERATION); // use GPU acceleration ?
        oclUnverifiedQueue = propertyService.getInt(Props.GPU_UNVERIFIED_QUEUE);
        if (oclVerify) {
            OCLPoC.configure(propertyService);
        }

        String archivalModeStr = propertyService.getString(Props.DB_ARCHIVAL_MODE).toUpperCase();
        ArchivalMode mode;
        try {
            mode = ArchivalMode.valueOf(archivalModeStr);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid DB.ArchivalMode: {}, defaulting to TRIM", archivalModeStr);
            mode = ArchivalMode.TRIM;
        }
        this.archivalMode = mode;

        String finalMeasurementDir;
        if (logSyncProgressToCsv || measurementActive) {
            String configuredPath = propertyService.getString(Props.MEASUREMENT_DIR);
            try {
                Path measurementPath = PathUtils.resolvePath(configuredPath);
                finalMeasurementDir = measurementPath.toString();
                Files.createDirectories(measurementPath);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create or resolve measurement directory: " + configuredPath, e);
            }
        } else {
            finalMeasurementDir = null;
        }
        this.measurementDir = finalMeasurementDir;
        this.syncProgressLogFilename = this.measurementDir != null
                ? Paths.get(this.measurementDir, "sync_progress.csv").toString()
                : null;
        this.dbTrimLogFilename = this.measurementDir != null && archivalMode != ArchivalMode.ARCHIVE
                ? Paths.get(this.measurementDir, "db_trim_log.csv").toString()
                : null;
        this.dbPruneLogFilename = this.measurementDir != null && archivalMode == ArchivalMode.PRUNE
                ? Paths.get(this.measurementDir, "db_prune_log.csv").toString()
                : null;

        genesisBlockId = Convert.parseUnsignedLong(
                propertyService.getString(Props.GENESIS_BLOCK_ID));

        blockListeners.addListener(block -> {
            if (block.getHeight() % 5000 == 0) {
                logger.info("processed block {}", block.getHeight());
            }
        }, Event.BLOCK_SCANNED);

        blockListeners.addListener(block -> {
            if (logSyncProgressToCsv || measurementActive) {
                long currentTime = System.currentTimeMillis();
                long deltaTime = currentTime - lastSyncLogTimestamp;

                lastSyncLogTimestamp = currentTime;
                accumulatedSyncTimeMs += deltaTime;

                // Use time-based check for more reliable sync status detection, especially at
                // startup.
                Date now = new Date(currentTime);
                long blockTime = fluxCapacitor.getValue(FluxValues.BLOCK_TIME);
                Date blockDate = Convert.fromEpochTime(block.getTimestamp());
                int missingBlocks = (int) ((now.getTime() - blockDate.getTime()) / (blockTime * 1000));

                if (!isSyncingForLog.get() && missingBlocks > 10) {
                    isSyncingForLog.set(true);
                } else if (isSyncingForLog.get() && missingBlocks <= 1) {
                    isSyncingForLog.set(false);
                }

                if (isSyncingForLog.get()) {
                    accumulatedSyncInProgressTimeMs += deltaTime;
                }

                if (measurementActive) {
                    PerformanceStats stats = performanceStats.get();
                    // The stats might be null if the block is the very first one (genesis)
                    if (stats != null) {

                        int userTransactionCount = block.getTransactions().size();
                        int atTransactionCount = block.getAtTransactions().size();
                        int subscriptionTransactionCount = block.getSubscriptionTransactions().size();
                        int escrowTransactionCount = block.getEscrowTransactions().size();
                        int systemTransactionCount = atTransactionCount + subscriptionTransactionCount
                                + escrowTransactionCount;
                        int allTransactionCount = userTransactionCount + systemTransactionCount;
                        int atCount = 0;

                        if (block.getBlockAts() != null) {
                            try {
                                atCount = atService.getATsFromBlock(block.getBlockAts()).size();
                            } catch (Exception e) {
                                // ignore, as this is for measurement only
                            }
                        }

                        Map<String, String> values = new HashMap<>();
                        values.put("Block_height", String.valueOf(block.getHeight()));
                        values.put("Block_timestamp[s]", String.valueOf(block.getTimestamp()));
                        values.put("Cumulative_difficulty", block.getCumulativeDifficulty().toString());
                        values.put("Accumulated_sync_in_progress_time[ms]",
                                String.valueOf(accumulatedSyncInProgressTimeMs));
                        values.put("Accumulated_sync_time[ms]", String.valueOf(accumulatedSyncTimeMs));
                        values.put("Push_block_time[ms]", String.valueOf(stats.totalTimeMs));
                        values.put("Validation_time[ms]", String.valueOf(stats.validationTimeMs));
                        values.put("Tx_loop_time[ms]", String.valueOf(stats.txLoopTimeMs));
                        values.put("Housekeeping_time[ms]", String.valueOf(stats.housekeepingTimeMs));
                        values.put("Tx_apply_time[ms]", String.valueOf(stats.txApplyTimeMs));
                        values.put("AT_time[ms]", String.valueOf(stats.atTimeMs));
                        values.put("Subscription_time[ms]", String.valueOf(stats.subscriptionTimeMs));
                        values.put("Block_apply_time[ms]", String.valueOf(stats.blockApplyTimeMs));
                        values.put("Commit_time[ms]", String.valueOf(stats.commitTimeMs));
                        values.put("Misc_time[ms]", String.valueOf(stats.miscTimeMs));
                        values.put("AT_count", String.valueOf(atCount));
                        values.put("User_transaction_count", String.valueOf(userTransactionCount));
                        values.put("All_transaction_count", String.valueOf(allTransactionCount));

                        String line = getCsvLine(syncMeasurementColumnNames, values);
                        measurementData.add(line);
                    }
                }
            }
            if (block.getHeight() % 5000 == 0) {
                logger.info("processed block {}", block.getHeight());
                if (logSyncProgressToCsv) {
                    writeSyncProgressLog(accumulatedSyncTimeMs, block.getHeight());
                }
                if (measurementActive) {
                    writeMeasurementLog();
                }
                // Db.analyzeTables(); no-op
            }
        }, Event.BLOCK_PUSHED);

        blockListeners.addListener(
                block -> transactionProcessor.revalidateUnconfirmedTransactions(),
                Event.BLOCK_PUSHED);

        addGenesisBlock();

        if (logSyncProgressToCsv) {
            initSyncProgressLogging();
        }
        if (measurementActive) {
            initMeasurementLogging();
        }

        if (archivalMode != ArchivalMode.ARCHIVE) {
            loadPersistentState();
        }

        initialCleanDatabase();
        Runnable getMoreBlocksThread = new Runnable() {
            private JsonElement getCumulativeDifficultyRequest;

            private boolean peerHasMore;

            @Override
            public void run() {
                if (propertyService.getBoolean(Props.DEV_OFFLINE)) {
                    return;
                }
                if (getCumulativeDifficultyRequest == null) {
                    JsonObject request = new JsonObject();
                    request.addProperty("requestType", "getCumulativeDifficulty");
                    getCumulativeDifficultyRequest = JSON.prepareRequest(request);
                }
                while (!Thread.currentThread().isInterrupted() && ThreadPool.running.get() && !isShutdown.get()) {
                    try {

                        if (getMoreBlocksPause.get() || getMoreBlocksAutoPause.get()) {
                            return;
                        }

                        if (!getMoreBlocks.get()) {
                            return;
                        }

                        if (downloadCache.isFull()) {
                            return;
                        }

                        getMoreBlocksLock.writeLock().lock();
                        try {
                            if (!ThreadPool.running.get() || isShutdown.get()) {
                                return;
                            }

                            // Keep the download cache below the rollback limit
                            Block lastCachedBlock = downloadCache.getLastBlock();
                            if (lastCachedBlock == null) {
                                downloadCache.resetCache();
                                return;
                            }
                            int cacheHeight = lastCachedBlock.getHeight();
                            if (fluxCapacitor.getValue(FluxValues.POC_PLUS, cacheHeight)
                                    && cacheHeight - blockchain.getHeight() > Constants.MAX_ROLLBACK / 2) {
                                logger.debug("GetMoreBlocks, skip download, wait for other threads to catch up");
                                return;
                            }

                            peerHasMore = true;
                            BigInteger curCumulativeDifficulty = downloadCache.getCumulativeDifficulty();
                            BigInteger betterCumulativeDifficulty = BigInteger.ZERO;

                            Peer peer = null;
                            do {
                                if (!ThreadPool.running.get() || isShutdown.get() || getMoreBlocksAutoPause.get()) {
                                    return;
                                }
                                Peers peers = peerManager != null ? peerManager.getPeers() : null;
                                peer = peers != null ? peers.getAnyPeer(Peer.State.CONNECTED) : null;
                                if (peer == null) {
                                    logger.debug("No peer connected.");
                                    return;
                                }
                                if (!peer.isHigherOrEqualVersionThan(
                                        fluxCapacitor.getValue(FluxValues.MIN_PEER_VERSION))
                                        || (peer.getNetworkName() != null && !peer.getNetworkName()
                                                .equals(propertyService.getString(Props.NETWORK_NAME)))) {
                                    // ignore this peer, it will be removed by the peers discovery thread
                                    continue;
                                }

                                long start = System.currentTimeMillis();
                                JsonObject response = peer.send(getCumulativeDifficultyRequest);
                                long end = System.currentTimeMillis();
                                notifyPeerMetric(new PeerMetric(peer.getPeerAddress(), end - start, 0,
                                        PeerMetric.Type.OTHER));
                                if (response == null) {
                                    continue;
                                }
                                if (response.get("blockchainHeight") != null) {
                                    lastBlockchainFeeder.set(peer);
                                    lastBlockchainFeederHeight.set(JSON.getAsInt(response.get("blockchainHeight")));
                                } else {
                                    logger.debug("Peer {} has no chainheight", peer.getAnnouncedAddress());
                                    continue;
                                }

                                if (peer.getArchivalMode() == Peer.ArchivalMode.PRUNE) {
                                    int ourHeight = blockchain.getHeight();
                                    if (lastBlockchainFeederHeight.get() - ourHeight > Constants.MAX_ROLLBACK) {
                                        logger.debug(
                                                "Peer {} is in PRUNE mode and too far ahead ({} blocks). Skipping.",
                                                peer.getAnnouncedAddress(),
                                                lastBlockchainFeederHeight.get() - ourHeight);
                                        continue;
                                    }
                                }

                                /* Cache now contains Cumulative Difficulty */
                                String peerCumulativeDifficulty = JSON
                                        .getAsString(response.get("cumulativeDifficulty"));
                                if (peerCumulativeDifficulty == null) {
                                    logger.debug("Peer CumulativeDifficulty is null");
                                    continue;
                                }
                                betterCumulativeDifficulty = new BigInteger(peerCumulativeDifficulty);
                            } while (betterCumulativeDifficulty.compareTo(curCumulativeDifficulty) <= 0
                                    && ThreadPool.running.get() && !isShutdown.get() && !getMoreBlocksAutoPause.get());

                            logger.trace("Got a better cumulative difficulty {} than current {}.",
                                    betterCumulativeDifficulty, curCumulativeDifficulty);

                            long commonBlockId = genesisBlockId;
                            long cacheLastBlockId = downloadCache.getLastBlockId();

                            // Before query where to add blocks, ensure that the cache is not empty.
                            if (downloadCache.getLastBlock() == null) {
                                downloadCache.resetCache();
                                return;
                            }
                            // Now we will find the highest common block between ourself and our peer
                            if (cacheLastBlockId != genesisBlockId) {
                                commonBlockId = getCommonMilestoneBlockId(peer);
                                if (commonBlockId == 0 || !peerHasMore) {
                                    logger.debug("We could not get a common milestone block from peer.");
                                    return;
                                }
                            }

                            // unlocking cache for writing.
                            // This must be done before we query where to add blocks.
                            // We sync the cache in event of pop off
                            synchronized (BlockchainProcessorImpl.this.downloadCache) {
                                downloadCache.unlockCache();
                            }

                            /*
                             * if we did not get the last block in chain as common block we will be
                             * downloading a
                             * fork. however if it is to far off we cannot process it anyway. canBeFork will
                             * check
                             * where in chain this common block is fitting and return true if it is worth to
                             * continue.
                             */
                            boolean saveInCache = true;
                            if (commonBlockId != cacheLastBlockId) {
                                if (downloadCache.canBeFork(commonBlockId)) {
                                    // the fork is not that old. Lets see if we can get more precise.
                                    commonBlockId = getCommonBlockId(peer, commonBlockId);
                                    if (commonBlockId == 0 || !peerHasMore) {
                                        logger.debug("Trying to get a more precise common block resulted in an error.");
                                        return;
                                    }
                                    saveInCache = false;
                                    downloadCache.resetForkBlocks();
                                } else {
                                    if (logger.isDebugEnabled()) {
                                        logger.debug("A peer wants to feed us a fork that is more than "
                                                + Constants.MAX_ROLLBACK + " blocks old.");
                                    }
                                    peer.blacklist("feeding us a too old fork");
                                    return;
                                }
                            }

                            JsonArray nextBlocks = getNextBlocks(peer, commonBlockId);
                            if (nextBlocks == null || nextBlocks.isEmpty()) {
                                logger.debug("Peer did not feed us any blocks");
                                return;
                            }

                            // download blocks from peer
                            Block lastBlock = downloadCache.getBlock(commonBlockId);
                            if (lastBlock == null) {
                                logger.info(
                                        "Error: lastBlock (common ancestor {}) is null, resetting cache.",
                                        Convert.toUnsignedLong(commonBlockId));
                                downloadCache.resetCache();
                                continue; // Re-evaluate state in the next loop
                            }

                            // loop blocks and make sure they fit in chain
                            Block block;
                            JsonObject blockData;

                            for (JsonElement o : nextBlocks) {
                                int height = lastBlock.getHeight() + 1;
                                blockData = JSON.getAsJsonObject(o);
                                if (getMoreBlocksAutoPause.get() || isShutdown.get()) {
                                    break;
                                }
                                try {
                                    if (fluxCapacitor.getValue(FluxValues.POC_PLUS, height)
                                            && height - blockchain.getHeight() >= Constants.MAX_ROLLBACK) {
                                        logger.debug("GetMoreBlocks, wait for other threads to catch up");
                                        break;
                                    }
                                    block = Block.parseBlock(blockData, height, fluxCapacitor);
                                    // Make sure it maps back to chain
                                    if (lastBlock.getId() != block.getPreviousBlockId()) {
                                        logger.debug("Discarding downloaded data. Last downloaded blocks is rubbish");
                                        logger.debug("DB blockID: {} DB blockheight: {} Downloaded previd: {}",
                                                lastBlock.getId(), lastBlock.getHeight(), block.getPreviousBlockId());
                                        return;
                                    }
                                    // set height and cumulative difficulty to block
                                    block.setHeight(height);
                                    block.setPeer(peer);
                                    block.setByteLength(JSON.toJsonString(blockData).length());
                                    blockImporterLock.readLock().lock();
                                    try {
                                        blockService.calculateBaseTarget(block, lastBlock);
                                    } finally {
                                        blockImporterLock.readLock().unlock();
                                    }
                                    if (saveInCache) {
                                        if (downloadCache.getLastBlockId() == block.getPreviousBlockId()) {
                                            // ↑ still maps back? we might have got announced/forged blocks
                                            if (!downloadCache.addBlock(block)) {
                                                // we stop the loop since cahce has been locked
                                                return;
                                            }
                                            if (logger.isDebugEnabled()) {
                                                logger.debug("Added from download: Id: {} Height: {}", block.getId(),
                                                        block.getHeight());
                                            }
                                        }
                                    } else {
                                        downloadCache.addForkBlock(block);
                                    }
                                    lastBlock = block;
                                } catch (BlockOutOfOrderException e) {
                                    logger.warn(
                                            "Structural inconsistency during download: {} - possible local state gap.",
                                            e.getMessage());
                                    if (!saveInCache) {
                                        // Trigger aggressive recovery if a structural gap is encountered while
                                        // downloading a better fork.
                                        // This implies our local database is missing historical blocks required for
                                        // consensus.
                                        Block forkBlock = blockchain.getBlock(commonBlockId);
                                        logger.error(
                                                "Structural gap detected while processing fork. Common ancestor ID: {} {}. Initiating recovery.",
                                                Convert.toUnsignedLong(commonBlockId),
                                                forkBlock != null ? "at height " + forkBlock.getHeight()
                                                        : "(NOT FOUND in DB - already pruned?)");

                                        if (forkBlock != null) {
                                            popOffTo(forkBlock, null);
                                            transactionProcessor.requeueAllUnconfirmedTransactions();
                                        }
                                    }
                                    downloadCache.resetCache();
                                    return;
                                } catch (RuntimeException | SignumException.ValidationException e) {
                                    logger.info("Failed to parse block: {}", e.getMessage());
                                    if (logger.isDebugEnabled()) {
                                        logger.debug("Failed to parse block trace: {}",
                                                Arrays.toString(e.getStackTrace()));
                                    }
                                    peer.blacklist(e, "pulled invalid data using getCumulativeDifficulty");
                                    return;
                                } catch (Exception e) {
                                    logger.warn("Unhandled exception {}" + e.toString(), e);
                                    logger.warn("Unhandled exception trace: {}", Arrays.toString(e.getStackTrace()));
                                }
                                // check if we are interrupted or shutdown in between blocks, if so we stop the
                                // loop and do not process the downloaded blocks
                                if (Thread.currentThread().isInterrupted() || !ThreadPool.running.get()
                                        || isShutdown.get()) {
                                    return;
                                }
                            } // end block loop

                            if (logger.isTraceEnabled()) {
                                logger.trace("Unverified blocks: {}", downloadCache.getUnverifiedSize());
                                logger.trace("Blocks in cache: {}", downloadCache.size());
                                logger.trace("Bytes in cache: {}", downloadCache.getBlockCacheSize());
                            }
                            if (!saveInCache) {
                                /*
                                 * Since we cannot rely on peers reported cumulative difficulty we do
                                 * a final check to see that the CumulativeDifficulty actually is bigger
                                 * before we do a popOff and switch chain.
                                 */
                                if (lastBlock.getCumulativeDifficulty().compareTo(curCumulativeDifficulty) < 0) {
                                    peer.blacklist(
                                            "peer claimed to have bigger cumulative difficulty but in reality it did not.");
                                    downloadCache.resetForkBlocks();
                                    break;
                                }
                                processFork(peer, downloadCache.getForkList(), commonBlockId);
                                blockListeners.notify(null, Event.FORK_CACHE_CHANGED);
                            }

                        } catch (SignumException.StopException e) {
                            logger.info("Blockchain download stopped: {}", e.getMessage());
                        } catch (InterruptedException ignored) {
                            // shutting down
                        } catch (Exception e) {
                            logger.info("Error in blockchain download thread", e);
                        } finally {
                            getMoreBlocksLock.writeLock().unlock();
                        } // end second try
                    } catch (Exception t) {
                        logger.info("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString(), t);
                        System.exit(1);
                    } // end first try
                } // end while
            }

            private long getCommonMilestoneBlockId(Peer peer) throws InterruptedException {

                String lastMilestoneBlockId = null;

                while (!Thread.currentThread().isInterrupted() && ThreadPool.running.get() && !isShutdown.get()
                        && !getMoreBlocksAutoPause.get()) {
                    JsonObject milestoneBlockIdsRequest = new JsonObject();
                    milestoneBlockIdsRequest.addProperty("requestType", "getMilestoneBlockIds");
                    if (lastMilestoneBlockId == null) {
                        milestoneBlockIdsRequest.addProperty("lastBlockId",
                                Convert.toUnsignedLong(downloadCache.getLastBlockId()));
                    } else {
                        milestoneBlockIdsRequest.addProperty("lastMilestoneBlockId", lastMilestoneBlockId);
                    }

                    long start = System.currentTimeMillis();
                    JsonObject response = peer.send(JSON.prepareRequest(milestoneBlockIdsRequest));
                    long end = System.currentTimeMillis();
                    if (response == null) {
                        notifyPeerMetric(new PeerMetric(peer.getPeerAddress(), end - start, 0, PeerMetric.Type.OTHER));
                        logger.debug("Got null response in getCommonMilestoneBlockId");
                        return 0;
                    }
                    JsonArray milestoneBlockIds = JSON.getAsJsonArray(response.get("milestoneBlockIds"));
                    if (milestoneBlockIds == null) {
                        notifyPeerMetric(new PeerMetric(peer.getPeerAddress(), end - start, 0, PeerMetric.Type.OTHER));
                        logger.debug("MilestoneArray is null");
                        return 0;
                    }
                    notifyPeerMetric(new PeerMetric(peer.getPeerAddress(), end - start, milestoneBlockIds.size(),
                            PeerMetric.Type.OTHER));
                    if (milestoneBlockIds.size() == 0) {
                        return genesisBlockId;
                    }
                    // prevent overloading with blockIds
                    if (milestoneBlockIds.size() > 20) {
                        peer.blacklist("obsolete or rogue peer sends too many milestoneBlockIds");
                        return 0;
                    }
                    if (Boolean.TRUE.equals(JSON.getAsBoolean(response.get("last")))) {
                        peerHasMore = false;
                    }

                    for (JsonElement milestoneBlockId : milestoneBlockIds) {
                        long blockId = Convert.parseUnsignedLong(JSON.getAsString(milestoneBlockId));

                        if (downloadCache.hasBlock(blockId)) {
                            if (lastMilestoneBlockId == null && milestoneBlockIds.size() > 1) {
                                peerHasMore = false;
                                logger.debug("Peer dont have more (cache)");
                            }
                            return blockId;
                        }
                        lastMilestoneBlockId = JSON.getAsString(milestoneBlockId);
                    }
                }
                throw new InterruptedException("interrupted");
            }

            private long getCommonBlockId(Peer peer, long commonBlockId) throws InterruptedException {

                while (!Thread.currentThread().isInterrupted() && ThreadPool.running.get() && !isShutdown.get()
                        && !getMoreBlocksAutoPause.get()) {
                    JsonObject request = new JsonObject();
                    request.addProperty("requestType", "getNextBlockIds");
                    request.addProperty("blockId", Convert.toUnsignedLong(commonBlockId));
                    long start = System.currentTimeMillis();
                    JsonObject response = peer.send(JSON.prepareRequest(request));
                    long end = System.currentTimeMillis();
                    if (response == null) {
                        notifyPeerMetric(new PeerMetric(peer.getPeerAddress(), end - start, 0, PeerMetric.Type.OTHER));
                        return 0;
                    }
                    JsonArray nextBlockIds = JSON.getAsJsonArray(response.get("nextBlockIds"));
                    notifyPeerMetric(new PeerMetric(peer.getPeerAddress(), end - start,
                            nextBlockIds != null ? nextBlockIds.size() : 0, PeerMetric.Type.OTHER));
                    if (nextBlockIds == null || nextBlockIds.size() == 0) {
                        return 0;
                    }
                    // prevent overloading with blockIds
                    if (nextBlockIds.size() > 1440) {
                        peer.blacklist("obsolete or rogue peer sends too many nextBlocks");
                        return 0;
                    }

                    for (JsonElement nextBlockId : nextBlockIds) {
                        long blockId = Convert.parseUnsignedLong(JSON.getAsString(nextBlockId));
                        if (!downloadCache.hasBlock(blockId)) {
                            return commonBlockId;
                        }
                        commonBlockId = blockId;
                    }
                }

                throw new InterruptedException("interrupted");
            }

            private JsonArray getNextBlocks(Peer peer, long curBlockId) {

                JsonObject request = new JsonObject();
                request.addProperty("requestType", "getNextBlocks");
                request.addProperty("blockId", Convert.toUnsignedLong(curBlockId));
                if (logger.isDebugEnabled()) {
                    logger.debug("Getting next Blocks after {} from {}", Convert.toUnsignedLong(curBlockId),
                            peer.getPeerAddress());
                }
                long start = System.currentTimeMillis();
                JsonObject response = peer.send(JSON.prepareRequest(request));
                long end = System.currentTimeMillis();
                int blockCount = (response != null && response.get("nextBlocks") != null)
                        ? JSON.getAsJsonArray(response.get("nextBlocks")).size()
                        : 0;
                notifyPeerMetric(new PeerMetric(peer.getPeerAddress(), end - start, blockCount,
                        PeerMetric.Type.BLOCK_RX));
                if (response == null) {
                    return null;
                }

                JsonArray nextBlocks = JSON.getAsJsonArray(response.get("nextBlocks"));
                if (nextBlocks == null) {
                    return null;
                }
                // prevent overloading with blocks
                if (nextBlocks.size() > 1440) {
                    peer.blacklist("obsolete or rogue peer sends too many nextBlocks");
                    return null;
                }
                logger.debug("Got {} blocks after {} from {}", nextBlocks.size(), curBlockId, peer.getPeerAddress());
                return nextBlocks;

            }

            private void processFork(Peer peer, final List<Block> forkBlocks, long forkBlockId) {
                logger.debug("A fork is detected. Waiting for cache to be processed.");
                downloadCache.lockCache(); // dont let anything add to cache!
                while (!Thread.currentThread().isInterrupted() && ThreadPool.running.get() && !isShutdown.get()) {
                    if (downloadCache.size() == 0) {
                        break;
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
                synchronized (BlockchainProcessorImpl.this.downloadCache) {
                    synchronized (transactionProcessor.getUnconfirmedTransactionsSyncObj()) {
                        logger.debug("Cache is now processed. Starting to process fork.");
                        Block forkBlock = blockchain.getBlock(forkBlockId);

                        // we read the current cumulative difficulty
                        BigInteger curCumulativeDifficulty = blockchain.getLastBlock().getCumulativeDifficulty();

                        // We remove blocks from chain back to where we start our fork
                        // and save it in a list if we need to restore
                        List<Block> myPoppedOffBlocks = popOffTo(forkBlock, forkBlocks);
                        logger.debug("Popped {} blocks", myPoppedOffBlocks.size());

                        // now we check that our chain is popped off.
                        // If all seems ok is we try to push fork.
                        int pushedForkBlocks = 0;
                        if (blockchain.getLastBlock().getId() == forkBlockId) {
                            for (Block block : forkBlocks) {
                                if (blockchain.getLastBlock().getId() == block.getPreviousBlockId()) {
                                    try {
                                        blockService.preVerify(block, blockchain.getLastBlock());

                                        logger.debug("Pushing block {} generator {} sig {}", block.getHeight(),
                                                SignumID.fromLong(block.getGeneratorId()),
                                                Hex.toHexString(block.getBlockSignature()));
                                        logger.debug("Block timestamp {} base target {} difficulty {} commitment {}",
                                                block.getTimestamp(), block.getBaseTarget(),
                                                block.getCumulativeDifficulty(), block.getCommitment());

                                        pushBlock(block);
                                        pushedForkBlocks += 1;
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    } catch (BlockNotAcceptedException e) {
                                        logger.warn(
                                                "Failed to push block {} (height {}) from peer {} during fork processing: {}",
                                                block.getStringId(), block.getHeight(), peer.getAnnouncedAddress(),
                                                e.getMessage());

                                        if (e.isStateRelated()) {
                                            // TRAP SCENARIO: The peer's chain has better CD, but our local state
                                            // prevents us from accepting it. We assume our local state is inconsistent.
                                            logger.error(
                                                    "Local state is likely inconsistent with a better chain. Initiating aggressive recovery at height {}.",
                                                    forkBlock.getHeight());

                                            // TRAP RECOVERY: If we are already at or above the fork block,
                                            // we must roll back further to clear the corrupted state.
                                            int recoveryHeight = forkBlock.getHeight() - 1;
                                            if (blockchain.getHeight() <= forkBlock.getHeight()) {
                                                // If rollback to ancestor is a no-op, go deeper (e.g., 10 blocks)
                                                recoveryHeight = Math.max(getMinRollbackHeight(),
                                                        blockchain.getHeight() - 10);
                                                logger.warn(
                                                        "No-op rollback detected. Forcing deeper recovery to height {}.",
                                                        recoveryHeight);
                                            }

                                            popOffTo(recoveryHeight);
                                            transactionProcessor.requeueAllUnconfirmedTransactions();
                                            logger.info(
                                                    "Recovery initiated. Node will attempt to re-sync from a clean state.");

                                            // Return immediately to bypass the 'restore chain' logic below.
                                            return;
                                        } else {
                                            // MALICIOUS/OBJECTIVE ERROR: The block is objectively invalid (bad
                                            // signature, etc.)
                                            // This is the peer's fault. We blacklist them and continue to restore our
                                            // own chain.
                                            peer.blacklist(e,
                                                    "sent objectively invalid block data during fork processing");
                                            break; // Exit the block pushing loop and fall through to restore our
                                                   // original chain.
                                        }
                                    }
                                }
                            }
                        }

                        /*
                         * we check if we succeeded to push any block. if we did we check against
                         * cumulative
                         * difficulty If it is lower we blacklist peer and set chain to be processed
                         * later.
                         */
                        if (pushedForkBlocks > 0 && blockchain.getLastBlock().getCumulativeDifficulty()
                                .compareTo(curCumulativeDifficulty) < 0) {
                            logger.warn("Fork was bad and pop off was caused by peer {}, blacklisting",
                                    peer.getPeerAddress());
                            peer.blacklist("got a bad fork");
                            List<Block> peerPoppedOffBlocks = popOffTo(forkBlock, null);
                            restoreBlocksCount.set(0);
                            notifyForkCacheStats();
                            pushedForkBlocks = 0;
                            peerPoppedOffBlocks
                                    .forEach(block -> transactionProcessor.processLater(block.getTransactions()));
                        }

                        // if we did not push any blocks we try to restore chain.
                        if (pushedForkBlocks == 0) {
                            for (int i = myPoppedOffBlocks.size() - 1; i >= 0; i--) {
                                Block block = myPoppedOffBlocks.remove(i);
                                try {
                                    if (!block.isVerified()) {
                                        blockService.preVerify(block, blockchain.getLastBlock());
                                    }
                                    pushBlock(block);
                                    restoreBlocksCount.set(myPoppedOffBlocks.size());
                                    notifyForkCacheStats();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } catch (BlockNotAcceptedException e) {
                                    logger.warn("Popped off block no longer acceptable: "
                                            + JSON.toJsonString(block.getJsonObject()), e);
                                    break;
                                }
                            }
                        } else {
                            myPoppedOffBlocks
                                    .forEach(block -> transactionProcessor.processLater(block.getTransactions()));
                            restoreBlocksCount.set(0);
                            notifyForkCacheStats();
                            logger.debug("Successfully switched to better chain.");
                        }
                        logger.info("Forkprocessing complete.");
                        restoreBlocksCount.set(0);
                        downloadCache.resetForkBlocks();
                        notifyForkCacheStats();
                        downloadCache.resetCache(); // Reset and set cached vars to chaindata.
                    }
                }
            }
        };
        threadPool.scheduleThread("GetMoreBlocks", getMoreBlocksThread,
                propertyService.getInt(Props.BLOCK_PROCESS_THREAD_DELAY),
                TimeUnit.MILLISECONDS);
        /* this should fetch first block in cache */
        // resetting cache because we have blocks that cannot be processed.
        // pushblock removes the block from cache.
        Runnable blockImporterThread = () -> {
            while (!Thread.interrupted() && ThreadPool.running.get() && !isShutdown.get() && downloadCache.size() > 0) {
                if (blockImporterPause.get() || blockImporterAutoPause.get()) {
                    return;
                }
                blockImporterLock.writeLock().lock();
                try {

                    Block lastBlock = blockchain.getLastBlock();
                    Long lastId = lastBlock.getId();
                    Block currentBlock = downloadCache.getNextBlock(lastId); /*
                                                                              * this should fetch first block in
                                                                              * cache
                                                                              */
                    if (currentBlock == null || currentBlock.getHeight() != (lastBlock.getHeight() + 1)) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("cache is reset due to orphaned block(s). CacheSize: {}",
                                    downloadCache.size());
                        }
                        // resetting cache because we have blocks that cannot be processed.
                        downloadCache.resetCache();
                        break;
                    }
                    try {
                        if (!currentBlock.isVerified()) {
                            downloadCache.removeUnverified(currentBlock.getId());
                            blockService.preVerify(currentBlock, lastBlock);
                            logger.debug("block was not preverified");
                        }
                        pushBlock(currentBlock); // pushblock removes the block from cache.
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (BlockNotAcceptedException e) {
                        logger.warn("Failed to import block {} (height {}): {}",
                                currentBlock.getStringId(), currentBlock.getHeight(), e.getMessage());

                        if (e.isStateRelated()) {
                            // Automated trap handling: if the error depends on our local state,
                            // our current tip is likely inconsistent. Perform aggressive recovery.
                            logger.error(
                                    "Local state inconsistency detected during import. Initiating aggressive recovery at height {}.",
                                    lastBlock.getHeight());

                            int recoveryHeight = lastBlock.getHeight() - 1;
                            if (blockchain.getHeight() <= lastBlock.getHeight()) {
                                // Go deeper to ensure we cross the point of divergence
                                recoveryHeight = Math.max(getMinRollbackHeight(), blockchain.getHeight() - 10);
                                logger.warn(
                                        "No-op rollback during import recovery. Forcing deeper recovery to height {}.",
                                        recoveryHeight);
                            }

                            popOffTo(recoveryHeight);
                            transactionProcessor.requeueAllUnconfirmedTransactions();
                            logger.info("Recovery initiated. The node will attempt to re-sync from a cleaner state.");
                        } else {
                            blacklistClean(currentBlock, e, "found objectively invalid data during block import");
                            autoPopOff(currentBlock.getHeight());
                        }
                        break;
                    }
                } catch (Exception exception) {
                    if (exception.toString().contains("[SQLITE_BUSY]")
                            || exception.toString().contains("[SQLITE_BUSY_SNAPSHOT]")) {
                        logger.warn("SQLite busy, trying again later...");
                    } else {
                        exception.printStackTrace();
                        logger.error("Uncaught exception in blockImporterThread", exception);
                    }
                } finally {
                    blockImporterLock.writeLock().unlock();
                }
            }
        };
        threadPool.scheduleThread("ImportBlocks", blockImporterThread,
                propertyService.getInt(Props.BLOCK_PROCESS_THREAD_DELAY),
                TimeUnit.MILLISECONDS);

        // Is there anything to verify
        // should we use Ocl?
        // is Ocl ready ?
        // verify using java
        Runnable pocVerificationThread = () -> {
            boolean verifyWithOcl;
            int oclThreshold = oclVerify ? oclUnverifiedQueue : Integer.MAX_VALUE;

            while (!Thread.interrupted() && ThreadPool.running.get() && !isShutdown.get()) {
                if (isShutdown.get()) {
                    return;
                }
                int unVerified = downloadCache.getUnverifiedSize();
                if (unVerified > 0) { // Is there anything to verify
                    if (unVerified >= oclThreshold) { // should we use Ocl?
                        verifyWithOcl = true;
                        if (!gpuUsage.tryAcquire()) { // is Ocl ready ?
                            logger.debug("already max locked");
                            verifyWithOcl = false;
                        }
                    } else {
                        verifyWithOcl = false;
                    }
                    if (verifyWithOcl) {
                        int pocVersion = 0;
                        int pos = 0;
                        HashMap<Block, Block> blocks = new HashMap<>();
                        synchronized (downloadCache) {
                            if (downloadCache.getUnverifiedSize() > 0) {
                                pocVersion = downloadCache.getPocVersion(downloadCache.getUnverifiedBlockIdFromPos(0));
                                while (pos < downloadCache.getUnverifiedSize()
                                        && blocks.size() < OCLPoC.getMaxItems()) {
                                    long blockId = downloadCache.getUnverifiedBlockIdFromPos(pos);
                                    if (downloadCache.getPocVersion(blockId) != pocVersion) {
                                        break;
                                    }
                                    Block block = downloadCache.getBlock(blockId);
                                    if (block != null) {
                                        Block prevBlock = downloadCache.getBlock(block.getPreviousBlockId());
                                        if (prevBlock == null) {
                                            prevBlock = blockchain.getBlock(block.getPreviousBlockId());
                                        }
                                        blocks.put(block, prevBlock);
                                    }
                                    pos += 1;
                                }
                            }
                        }
                        if (!blocks.isEmpty()) {
                            try {
                                oclInitialized.set(true);
                                OCLPoC.validatePoC(blocks, pocVersion, blockService);
                                downloadCache.removeUnverifiedBatch(blocks.keySet());
                            } catch (OCLPoC.PreValidateFailException e) {
                                logger.info(e.toString(), e);
                                blacklistClean(e.getBlock(), e,
                                        "found invalid pull/push data during processing the pocVerification");
                            } catch (OCLPoC.OCLCheckerException e) {
                                logger.info("Open CL error. slow verify will occur for the next " + oclUnverifiedQueue
                                        + " Blocks", e);
                            } catch (Exception e) {
                                logger.info("Unspecified Open CL error: ", e);
                            } finally {
                                gpuUsage.release();
                            }
                        } else {
                            gpuUsage.release();
                        }
                    } else { // verify using java
                        try {
                            Block block;
                            synchronized (downloadCache) {
                                if (downloadCache.getUnverifiedSize() > 0) {
                                    block = downloadCache.getFirstUnverifiedBlock();
                                } else {
                                    block = null;
                                }
                            }
                            if (block != null) {
                                Block prevBlock = downloadCache.getBlock(block.getPreviousBlockId());
                                if (prevBlock == null) {
                                    prevBlock = blockchain.getBlock(block.getPreviousBlockId());
                                }
                                blockService.preVerify(block, prevBlock);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (BlockNotAcceptedException e) {
                            logger.error("Block failed to preverify: ", e);
                        }
                    }
                } else {
                    // nothing to verify right now
                    return;
                }
            }
        };
        if (propertyService.getBoolean(Props.GPU_ACCELERATION)) {
            logger.debug("Starting preverifier thread in Open CL mode.");
            threadPool.scheduleThread("VerifyPoc", pocVerificationThread,
                    propertyService.getInt(Props.BLOCK_PROCESS_THREAD_DELAY),
                    TimeUnit.MILLISECONDS);
        } else {
            logger.debug("Starting preverifier thread in CPU mode.");
            threadPool.scheduleThreadCores(pocVerificationThread,
                    propertyService.getInt(Props.BLOCK_PROCESS_THREAD_DELAY),
                    TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Sets the AT processor cache instance.
     * Called during initialization to wire the cache after constructor injection.
     */
    public void setAtProcessorCache(ATProcessorCache cache) {
        this.atProcessorCache = cache;
    }

    @Override
    public void onQueueStatusUpdated(QueueStatus newStatus) {
        queueStatus.set(newStatus);
        blockListeners.notify(null, Event.QUEUE_STATUS_CHANGED);
        queueStatusListeners.notify(newStatus, Event.QUEUE_STATUS_CHANGED);
    }

    private void updateAndFireNetVolume() {
        PeerManager peerManager = this.peerManager;
        Peers peers = peerManager != null ? peerManager.getPeers() : null;
        List<Peer> peersList = peers != null ? peers.getActivePeers() : Collections.emptyList();
        long sumUploadedVolume = 0;
        long sumDownloadedVolume = 0;
        for (Peer peer : peersList) {
            sumUploadedVolume += peer.getUploadedVolume();
            sumDownloadedVolume += peer.getDownloadedVolume();
        }
        this.uploadedVolume.set(sumUploadedVolume);
        this.downloadedVolume.set(sumDownloadedVolume);
        blockListeners.notify(null, Event.NET_VOLUME_CHANGED);
    }

    private void blacklistClean(Block block, Exception e, String description) {
        logger.debug("Blacklisting peer and cleaning cache queue");
        if (block == null) {
            return;
        }
        Peer peer = block.getPeer();
        if (peer != null) {
            peer.blacklist(e, description);
        }
        downloadCache.resetCache();
        logger.debug("Blacklisted peer and cleaned queue");
    }

    private void autoPopOff(int height) {
        if (!autoPopOffEnabled) {
            logger.warn(
                    "Not automatically popping off as it is disabled via properties. If your node becomes stuck you will need to manually pop off.");
            return;
        }
        synchronized (transactionProcessor.getUnconfirmedTransactionsSyncObj()) {
            logger.warn("Auto popping off as failed to push block");
            if (height != autoPopOffLastStuckHeight) {
                autoPopOffLastStuckHeight = height;
                autoPopOffNumberOfBlocks = 0;
            }
            if (autoPopOffNumberOfBlocks == 0) {
                logger.warn("Not popping anything off as this was the first failure at this height");
            } else {
                logger.warn("Popping off {} blocks due to previous failures to push this block",
                        autoPopOffNumberOfBlocks);
                popOffTo(blockchain.getHeight() - autoPopOffNumberOfBlocks);
            }
            autoPopOffNumberOfBlocks++;
        }
    }

    private void writeSystemInfo(PrintWriter writer) {
        try {
            writer.println("Property;Value");
            writer.println("Signum Version;" + Signum.VERSION);
            try {
                writer.println("Hostname;" + java.net.InetAddress.getLocalHost().getHostName());
            } catch (java.net.UnknownHostException e) {
                writer.println("Hostname;Unknown");
            }
            writer.println("OS Name;" + System.getProperty("os.name"));
            writer.println("OS Version;" + System.getProperty("os.version"));
            writer.println("OS Architecture;" + System.getProperty("os.arch"));
            writer.println("Java Version;" + System.getProperty("java.version"));
            writer.println("Available Processors;" + Runtime.getRuntime().availableProcessors());
            long maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
            writer.println("Max Memory (MB);" + maxMemoryMb);
            try {
                OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
                long totalMemoryBytes = osBean.getTotalMemorySize();
                writer.println("Total RAM (MB);" + (totalMemoryBytes / (1024 * 1024)));
            } catch (Exception e) {
                writer.println("Total RAM (MB);N/A");
            }

            writer.println("Database Type;" + dbType);
            writer.println("Database Version;" + dbVersion);
            writer.println(";;"); // Separator line
        } catch (Exception e) {
            logger.error("Failed to write system info to log", e);
        }
    }

    // Init shutdown flags in case of restart node
    public void initShudown() {
        isShutdown.set(false);
        getMoreBlocksAutoPause.set(false);
        blockImporterAutoPause.set(false);
        getMoreBlocksLock.writeLock().unlock();
        blockImporterLock.writeLock().unlock();
    }

    private boolean isHeaderValidAndOrdered(String[] fileColumns, String[] requiredColumns) {
        if (fileColumns == null || requiredColumns == null) {
            return false;
        }
        if (fileColumns.length != requiredColumns.length) {
            logger.warn("Header column count mismatch. Expected: {}, Found: {}", requiredColumns.length,
                    fileColumns.length);
            return false;
        }
        for (int i = 0; i < requiredColumns.length; i++) {
            if (!fileColumns[i].trim().equalsIgnoreCase(requiredColumns[i].trim())) {
                logger.warn("Header column mismatch at index {}. Expected: '{}', Found: '{}'", i, requiredColumns[i],
                        fileColumns[i]);
                return false; // Check for order and name (case-insensitive)
            }
        }
        return true;
    }

    private void initSyncProgressLogging() {
        Map<String, Integer> colMap = new HashMap<>();
        File file = new File(this.syncProgressLogFilename);
        boolean fileExistsAndHasContent = file.exists() && file.length() > 0;

        if (fileExistsAndHasContent) {
            try (BufferedReader reader = Files.newBufferedReader(Paths.get(this.syncProgressLogFilename))) {
                String line;
                String headerLine = null;
                String lastLine = null;
                int lineCounter = 0;
                String[] readColumnNames = null;
                final int maxHeaderSearchLines = 100;
                while ((line = reader.readLine()) != null) {
                    if (headerLine == null) {
                        lineCounter++;
                        if (line.contains("Block_height")) {
                            headerLine = line;
                            readColumnNames = headerLine.trim().split(";");
                            for (int i = 0; i < readColumnNames.length; i++) {
                                colMap.put(readColumnNames[i].trim().toLowerCase(), i);
                            }
                        } else if (lineCounter >= maxHeaderSearchLines) {
                            logger.warn("CSV header not found in {}. The file might be corrupt. Re-initializing.",
                                    this.syncProgressLogFilename);
                            fileExistsAndHasContent = false;
                            break;
                        }
                    } else {
                        lastLine = line;
                    }
                }

                boolean isHeaderValid = isHeaderValidAndOrdered(readColumnNames, this.syncProgressColumnNames);

                if (isHeaderValid) {
                    Integer syncInProgressIndex = colMap.get("accumulated_sync_in_progress_time[s]");
                    Integer syncTimeIndex = colMap.get("accumulated_sync_time[s]");
                    if (lastLine != null) {
                        String[] parts = lastLine.split(";");
                        if (parts.length > Math.max(syncInProgressIndex, syncTimeIndex)) {
                            this.accumulatedSyncInProgressTimeMs = Long.parseLong(parts[syncInProgressIndex].trim())
                                    * 1000;
                            this.accumulatedSyncTimeMs = Long.parseLong(parts[syncTimeIndex].trim()) * 1000;
                            logger.info("Reading sync progress from {}:", this.syncProgressLogFilename);
                            logger.info("Accumulated Sync In Progress Time: {}s ({})",
                                    this.accumulatedSyncInProgressTimeMs / 1000,
                                    DurationFormatter.format(this.accumulatedSyncInProgressTimeMs));
                            logger.info("Accumulated Sync Time: {}s ({})", this.accumulatedSyncTimeMs / 1000,
                                    DurationFormatter.format(this.accumulatedSyncTimeMs));
                            if (readColumnNames != null) {
                                this.syncProgressColumnNames = readColumnNames;
                            }
                        } else {
                            logger.warn(
                                    "CSV last line does not have expected number of columns in {}. The file might be corrupt. Re-initializing.",
                                    this.syncProgressLogFilename);
                            fileExistsAndHasContent = false;
                        }
                    } else {
                        this.accumulatedSyncInProgressTimeMs = 0;
                        this.accumulatedSyncTimeMs = 0;
                        if (readColumnNames != null) {
                            this.syncProgressColumnNames = readColumnNames;
                        }
                    }
                } else {
                    logger.warn(
                            "CSV header mismatch in {}. Re-initializing.",
                            this.syncProgressLogFilename);
                    fileExistsAndHasContent = false;
                }
            } catch (IOException | NumberFormatException e) {
                logger.error("Failed to read or parse log file: {}. Re-initializing.", this.syncProgressLogFilename, e);
                fileExistsAndHasContent = false;
            }
        }

        if (!fileExistsAndHasContent) {
            try (PrintWriter writer = new PrintWriter(new FileWriter(file, false))) {
                logger.info("Creating log file: {}", this.syncProgressLogFilename);
                try {
                    writeSystemInfo(writer);
                } catch (Exception e) {
                    logger.error("Failed to write system info to {}", this.syncProgressLogFilename, e);
                }
                /*
                 * The file starts with a block of system information (Property;Value).
                 * The file contains periodic sync progress updates.
                 *
                 * Header:
                 * Block_height;Accumulated_sync_in_progress_time[s];Accumulated_sync_time[s]
                 * Block_height - Current block height.
                 * Accumulated_sync_in_progress_time[s] - Time spent in sync mode.
                 * Accumulated_sync_time[s] - Total time since start of node.
                 */
                writer.println(String.join(";", syncProgressColumnNames));
                writer.println(blockchain.getHeight() + ";0;0");
                this.accumulatedSyncInProgressTimeMs = 0;
                this.accumulatedSyncTimeMs = 0;
            } catch (IOException e) {
                logger.error("Failed to create or re-initialize log file: {}", this.syncProgressLogFilename, e);
            }
        }
        this.lastSyncLogTimestamp = System.currentTimeMillis();
    }

    private void initMeasurementLogging() {
        if (this.measurementDir == null) {
            return;
        }

        int fileIndex = 1;
        File file;
        // Find the latest existing file
        while (true) {
            String filename = String.format("sync_measurement_%03d.csv", fileIndex);
            File nextFile = new File(Paths.get(this.measurementDir, filename).toString());
            if (!nextFile.exists()) {
                break;
            }
            fileIndex++;
        }

        // If we found at least one file, we'll use the last one found.
        // Otherwise, we'll create the first one.
        if (fileIndex > 1) {
            fileIndex--; // Use the last existing index
        }

        String currentFilename = String.format("sync_measurement_%03d.csv", fileIndex);
        this.syncMeasurementLogFilename.set(Paths.get(this.measurementDir, currentFilename).toString());
        file = new File(this.syncMeasurementLogFilename.get());

        boolean fileExistsAndHasContent = file.exists() && file.length() > 0;

        if (fileExistsAndHasContent) {
            try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
                String line;
                String headerLine = null;
                int lineCounter = 0;
                String[] readColumnNames = null;
                final int maxHeaderSearchLines = 100;
                while ((line = reader.readLine()) != null) {
                    lineCounter++;
                    if (line.contains("Block_height")) {
                        headerLine = line;
                        readColumnNames = headerLine.trim().split(";");
                        break;
                    } else if (lineCounter >= maxHeaderSearchLines) {
                        logger.warn("CSV header not found in {}. The file might be corrupt. Re-initializing.",
                                this.syncMeasurementLogFilename.get());
                        fileExistsAndHasContent = false;
                        break;
                    }
                }
                if (fileExistsAndHasContent) {
                    boolean isHeaderValid = isHeaderValidAndOrdered(readColumnNames, this.syncMeasurementColumnNames);
                    if (isHeaderValid) {
                        this.syncMeasurementColumnNames = readColumnNames;
                    } else {
                        logger.warn("CSV header mismatch in {}. Re-initializing.",
                                this.syncMeasurementLogFilename.get());
                        fileExistsAndHasContent = false;
                    }
                }
            } catch (IOException e) {
                logger.error("Failed to read or parse log file: {}. Re-initializing.",
                        this.syncMeasurementLogFilename.get(), e);
                fileExistsAndHasContent = false;
            }
        }

        if (!fileExistsAndHasContent) {
            try (PrintWriter writer = new PrintWriter(new FileWriter(file, false))) {
                logger.info("Creating log file: {}", this.syncMeasurementLogFilename.get());
                try {
                    writeSystemInfo(writer);
                } catch (Exception e) {
                    logger.error("Failed to write system info to {}", this.syncMeasurementLogFilename.get(), e);
                }
                /*
                 * The file starts with a block of system information (Property;Value).
                 * The file contains detailed timing measurements for each pushed block during
                 * synchronization.
                 *
                 * Header:
                 * Block_height;Block_timestamp[s];Cumulative_difficulty;
                 * Accumulated_sync_in_progress_time[ms];
                 * Accumulated_sync_time[ms];Push_block_time[ms];Db_time[ms];At_time[ms];
                 * Transaction_count
                 * Block_height - Height of the pushed block.
                 * Block_timestamp[s] - Block timestamp of the pushed block (UTC) in seconds
                 * since epoch (yyyy.mm.dd.) 2014.08.11. 02:00:00 (UTC).
                 * Cumulative_difficulty - Cumulative difficulty of the pushed block.
                 * Accumulated_sync_in_progress_time[ms] - Time spent in sync mode in
                 * milliseconds.
                 * Accumulated_sync_time[ms] - Total time since start of node in milliseconds
                 * Push_block_time[ms] - Time taken to push the block in milliseconds.
                 * Validation_time[ms] - Time for block and signature validation.
                 * Tx_loop_time[ms] - Time for transaction validation loop.
                 * Housekeeping_time[ms] - Time for intermediate tasks (re-queue, add block,
                 * etc.).
                 * Tx_apply_time[ms] - Time to apply transaction state changes in memory.
                 * AT_time[ms] - Time taken for Automated Transactions processing.
                 * Subscription_time[ms] - Time for subscription processing.
                 * Block_apply_time[ms] - Time for block-level changes (rewards, etc.).
                 * Commit_time[ms] - Time to commit changes to the database disk.
                 * Misc_time[ms] - The difference between total push time and
                 * the sum of timing components that are individually and explicitly measured
                 * in the push block process.
                 * AT_count - Number of Automated Transactions executed in the pushed block.
                 * User_transaction_count - Number of user-submitted transactions in the pushed
                 * block.
                 * All_transaction_count - Total number of all transactions (including
                 * system generated) in the pushed block.
                 */
                writer.println(String.join(";", syncMeasurementColumnNames));
            } catch (IOException e) {
                logger.error("Failed to create or re-initialize log file: {}", this.syncMeasurementLogFilename.get(),
                        e);
            }
        }

        if (this.dbTrimLogFilename != null) {
            File trimLogFile = new File(this.dbTrimLogFilename);
            if (!trimLogFile.exists()) {
                try (PrintWriter writer = new PrintWriter(new FileWriter(trimLogFile, false))) {
                    logger.info("Creating log file: {}", trimLogFile.getAbsolutePath());
                    try {
                        writeSystemInfo(writer);
                    } catch (Exception e) {
                        logger.error("Failed to write system info to {}", this.dbTrimLogFilename, e);
                    }
                    /*
                     * The file starts with a block of system information (Property;Value).
                     * The file contains periodic database trim logs.
                     *
                     * Header: trim_height;trim_time[ms]
                     * trim_height - Height to which the database was trimmed.
                     * trim_time[ms] - Time taken to trim the database in milliseconds.
                     */
                    writer.println("trim_height;trim_time[ms]");
                } catch (IOException e) {
                    logger.error("Failed to create log file: {}", this.dbTrimLogFilename, e);
                }
            }
        }

        if (this.dbPruneLogFilename != null) {
            File pruneLogFile = new File(this.dbPruneLogFilename);
            if (!pruneLogFile.exists()) {
                try (PrintWriter writer = new PrintWriter(new FileWriter(pruneLogFile, false))) {
                    logger.info("Creating log file: {}", pruneLogFile.getAbsolutePath());
                    try {
                        writeSystemInfo(writer);
                    } catch (Exception e) {
                        logger.error("Failed to write system info to {}", this.dbPruneLogFilename, e);
                    }
                    /*
                     * The file starts with a block of system information (Property;Value).
                     * The file contains periodic database prune logs.
                     *
                     * Header: prune_height;prune_time[ms]
                     * prune_height - Height to which the database was pruned.
                     * prune_time[ms] - Time taken to prune the database in milliseconds.
                     */
                    writer.println("prune_height;prune_time[ms]");
                } catch (IOException e) {
                    logger.error("Failed to create log file: {}", this.dbPruneLogFilename, e);
                }
            }
        }
    }

    /*
     * Writes a new line to the sync progress log file with the accumulated sync
     * in-progress time,
     * total elapsed time, and current block height.
     * accumulatedSyncInProgressTimeMs is for the time when sync is in progress
     * totalTime is for the total time since start of node
     *
     * @param totalTime Total elapsed time in milliseconds
     *
     * @param height Current block height
     */
    private void writeSyncProgressLog(long totalTime, int height) {
        if (measurementLogExecutor == null) {
            return;
        }

        measurementLogExecutor.submit(() -> {
            try (PrintWriter writer = new PrintWriter(new FileWriter(this.syncProgressLogFilename, true))) {
                Map<String, String> values = new HashMap<>();
                values.put("Block_height", String.valueOf(height));
                values.put("Accumulated_sync_in_progress_time[s]",
                        String.valueOf(accumulatedSyncInProgressTimeMs / 1000));
                values.put("Accumulated_sync_time[s]", String.valueOf(totalTime / 1000));

                writer.println(getCsvLine(syncProgressColumnNames, values));
            } catch (IOException e) {
                logger.error("Failed to write log to {}", this.syncProgressLogFilename, e);
            }
        });
    }

    private void writeMeasurementLog() {
        if (!measurementActive || measurementLogExecutor == null) {
            return;
        }

        // Rotate the file based on block height if needed (1 million blocks per file)
        int currentHeight = blockchain.getHeight();
        String currentLogFile = this.syncMeasurementLogFilename.get();
        int fileIndexForHeight = (currentHeight / 1_000_000) + 1;
        String expectedFilename = String.format("sync_measurement_%03d.csv", fileIndexForHeight);
        Path expectedPath = Paths.get(this.measurementDir, expectedFilename);

        if (!currentLogFile.equals(expectedPath.toString())) {
            // Rotate file
            this.syncMeasurementLogFilename.set(expectedPath.toString());
            File newFile = new File(this.syncMeasurementLogFilename.get());
            if (!newFile.exists()) {
                try (PrintWriter writer = new PrintWriter(new FileWriter(newFile, false))) {
                    logger.info("Creating log file due to rotation: {}",
                            this.syncMeasurementLogFilename.get());
                    writeSystemInfo(writer);
                    writer.println(String.join(";", syncMeasurementColumnNames));
                } catch (IOException e) {
                    logger.error("Failed to create log file on rotation: {}", this.syncMeasurementLogFilename.get(), e);
                }
            }
        }
        if (measurementData.isEmpty()) {
            return;
        }
        // To avoid ConcurrentModificationException, we copy the list and clear the
        // original
        final List<String> dataToWrite;
        synchronized (measurementData) {
            if (measurementData.isEmpty()) {
                return;
            }
            dataToWrite = new ArrayList<>(measurementData);
            measurementData.clear();
        }

        measurementLogExecutor.submit(() -> {
            try (PrintWriter writer = new PrintWriter(new FileWriter(this.syncMeasurementLogFilename.get(), true))) {
                dataToWrite.forEach(writer::println);
            } catch (IOException e) {
                logger.error("Failed to write log to {}", this.syncMeasurementLogFilename.get(), e);
            }
        });
    }

    private String getCsvLine(String[] columns, Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0)
                sb.append(";");
            sb.append(values.getOrDefault(columns[i].trim(), "0"));
        }
        return sb.toString();
    }

    private void writeTrimLog(int trimHeight, long trimTimeMs) {
        if (!measurementActive || measurementLogExecutor == null || dbTrimLogFilename == null) {
            return;
        }

        measurementLogExecutor.submit(() -> {
            try (PrintWriter writer = new PrintWriter(new FileWriter(this.dbTrimLogFilename, true))) {
                writer.println(trimHeight + ";" + trimTimeMs);
            } catch (IOException e) {
                logger.error("Failed to write log to {}", this.dbTrimLogFilename, e);
            }
        });
    }

    private void writePruneLog(int pruneHeight, long pruneTimeMs) {
        if (!measurementActive || measurementLogExecutor == null || dbPruneLogFilename == null
                || archivalMode != ArchivalMode.PRUNE) {
            return;
        }

        measurementLogExecutor.submit(() -> {
            try (PrintWriter writer = new PrintWriter(new FileWriter(this.dbPruneLogFilename, true))) {
                writer.println(pruneHeight + ";" + pruneTimeMs);
            } catch (IOException e) {
                logger.error("Failed to write log to {}", this.dbPruneLogFilename, e);
            }
        });
    }

    /**
     * Checks if the automatic database consistency resolution process is required.
     * <p>
     * This method assesses the current state of the database consistency and the
     * node's configuration
     * to decide if an automatic recovery attempt is necessary. It ensures that
     * resolution is not
     * initiated if other critical blockchain operations (like trimming or pop-off)
     * are currently in progress.
     *
     * @return {@code true} if the conditions for automatic resolution are met,
     *         {@code false} otherwise.
     */
    private boolean isAutoResolutionRequired() {

        // Auto resolution trigger only if no trim or pop-off is ongoing
        // This avoids conflicts during active recovery operations
        // Pop-off and trim operations already handle consistency related issues
        // internally by rolling back erroneous transactions
        if (isMaintenanceRunning.get() || manualPopOffState != PopOffState.IDLE
                || autoPopOffState != PopOffState.IDLE) {
            return false;
        }

        // If database consistency state transitions to INCONSISTENT
        // UNDEFINED -> INCONSISTENT or
        // CONSISTENT -> INCONSISTENT
        if (consistencyState.get() == ConsistencyState.INCONSISTENT) {
            if (previousConsistencyState == ConsistencyState.UNDEFINED
                    || previousConsistencyState == ConsistencyState.CONSISTENT) {
                logger.info(
                        "Database state transitioned from {} to INCONSISTENT.",
                        previousConsistencyState);
                if (propertyService.getBoolean(Props.AUTO_CONSISTENCY_RESOLVE_ENABLED)) {
                    return true;
                } else {
                    logger.info(
                            "Auto Database Resolution is disabled.");
                    logger.info(
                            "Please try to resolve Database manually via the GUI: Database Check -> Start Auto Resolve Database Consistency.");
                    return false;
                }
            } else if (resolutionState == ResolutionState.IDLE
                    || resolutionState == ResolutionState.SUCCESS) {
                if (propertyService.getBoolean(Props.AUTO_CONSISTENCY_RESOLVE_ENABLED)) {
                    logger.info(
                            "Database is INCONSISTENT previous Database Resolution state was {}.",
                            resolutionState);
                    return true;
                } else {
                    logger.info(
                            "Auto Database Resolution is disabled.");
                    logger.info(
                            "Please try to resolve Database manually via the GUI: Database Check -> Start Auto Resolve Database Consistency.");
                    return false;
                }
            } else if (resolutionState == ResolutionState.FAILED) {
                logger.info("Database is INCONSISTENT and previous Database Resolution state was FAILED.");
                if (propertyService.getBoolean(Props.AUTO_CONSISTENCY_RESOLVE_ENABLED)) {
                    logger.info(
                            "Automatic consistency resolution will not be attempted again until manual intervention or a successful resolution occurs.");
                    logger.info("Revalidate, or Resync the node to reset the consistency.");
                    return false;
                } else {
                    logger.info(
                            "Auto Database Resolution is disabled.");
                    logger.info(
                            "Please try to resolve Database manually via the GUI: Database Check -> Start Auto Resolve Database Consistency.");
                    return false;
                }
            } else {
                logger.error(
                        "Database became inconsistent. Please try to resolve it manually via the GUI: Database Check -> Start Auto Resolve Database Consistency.");
                return false;
            }
        }
        return false;
    }

    /**
     * Initiates the automatic database consistency resolution process.
     * <p>
     * This method attempts to restore database consistency by iteratively popping
     * off blocks from the
     * blockchain until a consistent state is reached or a safety limit is
     * encountered.
     * <p>
     * The process follows these steps:
     * <ol>
     * <li>Checks if a resolution process is already active to prevent concurrent
     * executions.</li>
     * <li>Sets the resolution state to {@link ResolutionState#ACTIVE}.</li>
     * <li>Verifies the database state; if already consistent, marks as
     * {@link ResolutionState#SUCCESS}.</li>
     * <li>Calculates a rollback limit height (based on trim height or max rollback
     * constants).</li>
     * <li>Iteratively pops off the last block, rolling back derived tables and
     * caches, until consistency is restored.</li>
     * <li>Updates the resolution state to {@link ResolutionState#SUCCESS} upon
     * recovery, or {@link ResolutionState#FAILED} if the limit is reached.</li>
     * </ol>
     *
     * @see #manualResolveDatabaseConsistency()
     */
    @Override
    public void autoResolveDatabaseConsistency() {
        if (resolutionState == ResolutionState.ACTIVE) {
            logger.info("Consistency resolution is already active. Ignoring new request.");
            return;
        }

        resolutionState = ResolutionState.ACTIVE;
        blockListeners.notify(null, Event.CONSISTENCY_RESOLUTION_STARTED);
        logger.info("Resolving database consistency...");

        if (checkDatabaseState() == 0) {
            resolutionState = ResolutionState.SUCCESS;
            blockListeners.notify(null, Event.CONSISTENCY_RESOLUTION_FINISHED);
            logger.info("Database is already consistent.");
            return;
        }

        int limitHeight = getMinRollbackHeight();

        logger.info("Popping off blocks until consistent or height {}", limitHeight);

        // Initialize GUI tracking variables
        int startHeight = blockchain.getHeight();
        beforeRollbackHeight.set(startHeight);
        autoLastPopOffHeight.set(limitHeight);
        autoPopOffBlocksCount.set(startHeight - limitHeight);
        blockListeners.notify(blockchain.getLastBlock(), Event.BLOCK_AUTO_POPPED);

        boolean success = false;
        try {
            while (blockchain.getHeight() > limitHeight) {
                stores.beginTransaction();
                try {
                    Block block = popLastBlock();
                    for (DerivedTable table : derivedTableManager.getDerivedTables()) {
                        table.rollback(block.getHeight());
                    }
                    indirectIncomingService.rollback(block.getHeight());
                    dbCacheManager.flushCache();
                    downloadCache.resetCache();
                    atProcessorCache.reset();
                    stores.commitTransaction();

                    // Update GUI tracking
                    autoPopOffBlocksCount.decrementAndGet();
                    blockListeners.notify(block, Event.BLOCK_AUTO_POPPED);
                } catch (Exception e) {
                    logger.error("Error popping off block at height {}", blockchain.getHeight(), e);
                    stores.rollbackTransaction();
                    throw e;
                } finally {
                    stores.endTransaction();
                }

                if (checkDatabaseState() == 0) {
                    logger.info("Database became consistent at height {}", blockchain.getHeight());
                    success = true;
                    break;
                }
            }
            if (!success) {
                logger.warn("Reached limit height {} without restoring consistency.", limitHeight);
            }
        } catch (Exception e) {
            logger.error("Error resolving consistency", e);
        } finally {
            resolutionState = success ? ResolutionState.SUCCESS : ResolutionState.FAILED;
            if (success) {
                logger.info("Blocks popped off: {}", startHeight - blockchain.getHeight());
                logger.info("Pop-off height to {} from {}", blockchain.getHeight(), startHeight);
                logger.info("Database consistent at height {}", blockchain.getHeight());
                logger.info("Total Mined (Supply)           : {}", lastCheckTotalMined.get());
                logger.info("Total Effective Balance        : {}", lastCheckTotalEffectiveBalance.get());
            } else {
                logger.info("Database consistency resolution failed.");
            }
            // Reset GUI tracking variables
            autoPopOffBlocksCount.set(0);
            autoLastPopOffHeight.set(-1);
            blockListeners.notify(blockchain.getLastBlock(), Event.BLOCK_AUTO_POPPED);

            Block block = blockDb.findLastBlock();
            blockchain.setLastBlock(block);
            transactionProcessor.requeueAllUnconfirmedTransactions();
        }
    }

    /**
     * Manually triggers the database consistency resolution process.
     * <p>
     * This method is designed to be called from an external source (e.g., API or
     * GUI) to force a
     * consistency check and resolution attempt. Unlike
     * {@link #autoResolveDatabaseConsistency()},
     * this method spawns a new thread to perform the operation asynchronously,
     * ensuring that the
     * calling thread is not blocked.
     * <p>
     * It explicitly pauses block processing threads (downloader and importer)
     * before starting the
     * resolution logic and resumes them afterwards.
     */
    @Override
    public void manualResolveDatabaseConsistency() {
        if (resolutionState == ResolutionState.ACTIVE) {
            logger.info("Consistency resolution is already active. Ignoring new request.");
            return;
        }
        resolutionState = ResolutionState.ACTIVE;

        new Thread(() -> {
            getMoreBlocksAutoPause.set(true);
            blockImporterAutoPause.set(true);
            getMoreBlocksLock.writeLock().lock();
            blockImporterLock.writeLock().lock();
            try {
                synchronized (transactionProcessor.getUnconfirmedTransactionsSyncObj()) {
                    blockListeners.notify(null, Event.CONSISTENCY_RESOLUTION_STARTED);
                    logger.info("Resolving database consistency...");
                    if (checkDatabaseState() == 0) {
                        resolutionState = ResolutionState.SUCCESS;
                        blockListeners.notify(null, Event.CONSISTENCY_RESOLUTION_FINISHED);
                        logger.info("Database is already consistent.");
                        return;
                    }

                    int limitHeight = getMinRollbackHeight();

                    logger.info("Popping off blocks until consistent or height {}", limitHeight);

                    // Initialize GUI tracking variables
                    int startHeight = blockchain.getHeight();
                    beforeRollbackHeight.set(startHeight);
                    autoLastPopOffHeight.set(limitHeight);
                    autoPopOffBlocksCount.set(startHeight - limitHeight);
                    blockListeners.notify(blockchain.getLastBlock(), Event.BLOCK_AUTO_POPPED);

                    boolean success = false;
                    try {
                        while (blockchain.getHeight() > limitHeight) {
                            stores.beginTransaction();
                            try {
                                Block block = popLastBlock();
                                for (DerivedTable table : derivedTableManager.getDerivedTables()) {
                                    table.rollback(block.getHeight());
                                }
                                indirectIncomingService.rollback(block.getHeight());
                                dbCacheManager.flushCache();
                                downloadCache.resetCache();
                                atProcessorCache.reset();
                                stores.commitTransaction();

                                // Update GUI tracking
                                autoPopOffBlocksCount.decrementAndGet();
                                blockListeners.notify(block, Event.BLOCK_AUTO_POPPED);
                            } catch (Exception e) {
                                logger.error("Error popping off block at height {}", blockchain.getHeight() + 1, e);
                                stores.rollbackTransaction();
                                throw e;
                            } finally {
                                stores.endTransaction();
                            }

                            if (checkDatabaseState() == 0) {
                                logger.info("Database became consistent at height {}", blockchain.getHeight());
                                success = true;
                                break;
                            }
                        }
                        if (!success) {
                            logger.warn("Reached limit height {} without restoring consistency.", limitHeight);
                        }
                    } catch (Exception e) {
                        logger.error("Error resolving consistency", e);
                    } finally {
                        resolutionState = success ? ResolutionState.SUCCESS : ResolutionState.FAILED;
                        if (success) {
                            logger.info("Blocks popped off: {}", startHeight - blockchain.getHeight());
                            logger.info("Pop-off height to {} from {}", blockchain.getHeight(), startHeight);
                            logger.info("Database consistent at height {}", blockchain.getHeight());
                            logger.info("Total Mined (Supply)           : {}", lastCheckTotalMined.get());
                            logger.info("Total Effective Balance        : {}", lastCheckTotalEffectiveBalance.get());
                        } else {
                            logger.info("Database consistency resolution failed.");
                        }
                        // Reset GUI tracking variables
                        autoPopOffBlocksCount.set(0);
                        autoLastPopOffHeight.set(-1);
                        blockListeners.notify(blockchain.getLastBlock(), Event.BLOCK_AUTO_POPPED);

                        Block block = blockDb.findLastBlock();
                        blockchain.setLastBlock(block);
                        transactionProcessor.requeueAllUnconfirmedTransactions();
                    }
                }
            } finally {
                blockImporterLock.writeLock().unlock();
                getMoreBlocksLock.writeLock().unlock();
                getMoreBlocksAutoPause.set(false);
                blockImporterAutoPause.set(false);
                logger.info("Database consistency resolution finished with state: {}.", resolutionState);
                blockListeners.notify(null, Event.CONSISTENCY_RESOLUTION_FINISHED);
            }
        }).start();
    }

    private void trimDerivedTables(int targetTrimHeight) {

        List<DerivedTable> tablesToTrim = derivedTableManager.getDerivedTables();
        if (tablesToTrim.isEmpty()) {
            return;
        }

        boolean dbConsistent = false;
        this.isTrimming.set(true);
        long startTime = System.currentTimeMillis();
        int startTrimHeight = currentTrimHeight.get();

        logger.info("Trimming derived tables from height {} up to {} starting...", currentTrimHeight, targetTrimHeight);

        stores.beginTransaction();
        try {
            int tableIndex = 1;
            int totalTables = tablesToTrim.size();

            logger.info("Total derived tables to trim: {}", totalTables);

            trimListeners.notify(new TrimStats(startTrimHeight, targetTrimHeight), Event.TRIM_START);
            for (DerivedTable table : tablesToTrim) {
                if (isShutdown.get()) {
                    logger.info("Shutdown detected, aborting database trim.");
                    break;
                }

                long tableStartTime = System.currentTimeMillis();
                String tableName = table.getTable();
                this.currentlyTrimmingTable.set(tableName);

                table.trim(targetTrimHeight);

                long tableEndTime = System.currentTimeMillis();

                logger.info("#{} Table '{}' trimmed in {}", String.format("%02d", tableIndex++),
                        table.getTable(),
                        DurationFormatter.format(tableEndTime - tableStartTime));
            }

            long endTime = System.currentTimeMillis();
            if (checkDatabaseState() == 0) {
                dbConsistent = true;
                synchronized (currentTrimHeight) {
                    currentTrimHeight.set(targetTrimHeight);
                }
                logger.info("Database trim completed in {}.",
                        DurationFormatter.format(endTime - startTime));
                logger.info("Trim height updated from {} to {}",
                        startTrimHeight, currentTrimHeight.get());
                if (measurementActive) {
                    writeTrimLog(currentTrimHeight.get(), endTime - startTime);
                }
                try {
                    saveTrimHeight(currentTrimHeight.get());
                } catch (Exception e) {
                    throw new RuntimeException("Failed to save trim height to database after trim operation", e);
                }
            } else {
                logger.error(
                        "Database inconsistency detected after Trim. Rolling back changes. Min rollback height is {}",
                        getMinRollbackHeight());
                throw new RuntimeException("Database corruption detected after Trim.");
            }
        } catch (Exception e) {
            logger.error("Failed to trim derived tables up to height {}: {}",
                    targetTrimHeight, e.getMessage(), e);
            stores.rollbackTransaction();
        } finally {
            this.isTrimming.set(false);
            this.currentlyTrimmingTable.set(null);
            trimListeners.notify(new TrimStats(startTrimHeight, currentTrimHeight.get()), Event.TRIM_END);
            stores.commitTransaction();
            stores.endTransaction();
        }

        if (dbConsistent) {
            // Add optimization for derived tables after successful trim
            // Reclaming only if blockheigt - pruneheight > 2 * TRIM_PERIOD)
            if (blockchain.getHeight() - startTrimHeight > 2 * Constants.TRIM_PERIOD) {
                logger.info("Reclaiming disk space for derived tables (OPTIMIZE TABLE)...");
                for (DerivedTable table : tablesToTrim) {
                    try {
                        table.optimize();
                    } catch (Exception e) {
                        logger.warn("Failed to optimize derived table {}: {}", table.getTable(), e.getMessage());
                    }
                }
            }
        }
    }

    // Publicly available trim scheduling method
    @Override
    public void scheduleTrim(Block block) {
        if (!isScheduleTrimRequested.compareAndSet(false, true)) {
            logger.debug("Trim already requested for block {}", block.getHeight());
            return;
        }

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                getMoreBlocksAutoPause.set(true);
                blockImporterAutoPause.set(true);
                getMoreBlocksLock.writeLock().lock();
                blockImporterLock.writeLock().lock();

                if (!isShutdown.get()) {
                    executeArchivalMaintenance(block);
                }
            } catch (Throwable t) {
                logger.error("Maintenance error", t);
            } finally {
                blockImporterLock.writeLock().unlock();
                getMoreBlocksLock.writeLock().unlock();
                getMoreBlocksAutoPause.set(false);
                blockImporterAutoPause.set(false);
                isScheduleTrimRequested.set(false);
            }
        }, maintenanceExecutor);

        archivalMaintenanceFuture.set(future);
        future.whenComplete((res, ex) -> archivalMaintenanceFuture.compareAndSet(future, null));
    }

    private void executeArchivalMaintenance(Block block) {
        if (archivalMode == ArchivalMode.ARCHIVE) {
            return;
        }

        if (checkDatabaseState() != 0) {
            logger.warn("Database is inconsistent. Skipping archival maintenance.");
            isMaintenanceRunning.set(false);
            return;
        }

        if (!isMaintenanceRunning.compareAndSet(false, true)) {
            logger.info("Database archival maintenance already running, ignoring overlapping request.");
            return;
        }

        int targetHeight = Math.max(block.getHeight() - Constants.MAX_ROLLBACK, 0);

        if (archivalMode == ArchivalMode.TRIM) {
            trimDerivedTables(targetHeight);
        } else if (archivalMode == ArchivalMode.PRUNE) {
            trimDerivedTables(targetHeight);
            pruneBlocks(targetHeight);
        }

        isMaintenanceRunning.set(false);

    }

    /**
     * Physically deletes old blocks and associated data from the database.
     * This is an irreversible operation used to save disk space in PRUNE mode.
     *
     * @param pruneHeight The first existing block height on datatabase after
     *                    pruning except genesis. All blocks below this height will
     *                    be permanently deleted except genesis.
     */
    private void pruneBlocks(int targetPruneHeight) {

        this.isPruning.set(true);
        this.blockchainStore.setTotalTransactions(0);
        this.blockchainStore.setTotalDeletedTransactions(0);

        this.logger.info("Prune blockchain data from height {} up to {} starting...",
                currentPruneHeight, targetPruneHeight);

        boolean dbConsistent = false;
        long startTime = System.currentTimeMillis();
        int startPruneHeight = currentPruneHeight.get();
        int toHeight = startPruneHeight;

        stores.beginTransaction();
        try {
            // Prune in batches for progress reporting and to avoid long database locks
            int batchSize = 100;
            for (int fromHeight = startPruneHeight; fromHeight < targetPruneHeight; fromHeight += batchSize) {
                if (isShutdown.get()) {
                    logger.info("Shutdown detected, aborting database prune.");
                    break;
                }
                toHeight = Math.min(fromHeight + batchSize, targetPruneHeight);
                this.pruneListeners.notify(new BlockchainProcessor.PruneStats(fromHeight, targetPruneHeight),
                        BlockchainProcessor.Event.PRUNE_START);
                this.blockchainStore.prune(fromHeight - 1, toHeight - 1);
            }

            long endTime = System.currentTimeMillis();
            if (checkDatabaseState() == 0) {
                dbConsistent = true;
                synchronized (currentPruneHeight) {
                    currentPruneHeight.set(toHeight);
                }
                logger.info("Database prune completed in {}.",
                        DurationFormatter.format(endTime - startTime));
                logger.info("Prune height updated from {} to {}",
                        startPruneHeight, toHeight);
                if (measurementActive) {
                    writePruneLog(toHeight, endTime - startTime);
                }
                try {
                    savePruneHeight(toHeight);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to save prune height to database after prune operation", e);
                }
            } else {
                logger.error(
                        "Database inconsistency detected after Prune. Rolling back changes. Min rollback height is {}",
                        getMinRollbackHeight());
                throw new RuntimeException("Database corruption detected after Prune.");
            }
        } catch (Exception e) {
            logger.error("Failed to prune historical data up to height {}: {}", targetPruneHeight, e.getMessage(),
                    e);
            stores.rollbackTransaction();
        } finally {
            // TODO: change to only show current prune round details instead of cumulative
            // details since the start of pruning
            logger.info("Deleted Transactions: {}",
                    this.blockchainStore.getTotalDeletedTransactions());
            logger.info("Total Transactions: {}",
                    this.blockchainStore.getTotalTransactions());
            logger.info("Prune Ratio: {}",
                    this.blockchainStore.getTotalTransactions() > 0
                            ? String.format("%.2f%%",
                                    (double) this.blockchainStore.getTotalDeletedTransactions()
                                            / this.blockchainStore.getTotalTransactions() * 100)
                            : "N/A");
            isPruning.set(false);
            pruneListeners.notify(new PruneStats(startPruneHeight, currentPruneHeight.get()), Event.PRUNE_END);
            stores.commitTransaction();
            stores.endTransaction();
        }

        if (dbConsistent) {
            // Add optimization for blocks and transactions tables after successful prune
            // Reclaming only if blockheigt - pruneheight > 2 * TRIM_PERIOD)
            if (blockchain.getHeight() - startPruneHeight > 2 * Constants.TRIM_PERIOD) {
                logger.info("Reclaiming disk space for blocks (OPTIMIZE TABLE)...");
                try {
                    blockDb.optimize();
                } catch (Exception e) {
                    logger.warn("Failed to optimize blocks table: {}", e.getMessage());
                }
                logger.info("Reclaiming disk space for transactions (OPTIMIZE TABLE)...");
                try {
                    this.transactionDb.optimize();
                } catch (Exception e) {
                    logger.warn("Failed to optimize transactions table: {}", e.getMessage());
                }
            }
        }
    }

    private void saveTrimHeight(int trimHeight) {
        try {
            if (trimHeight > 0) {
                blockchainStore.setProperty("trimHeight", String.valueOf(trimHeight));
                logger.info("Saved trim height {} to database", trimHeight);
            }
        } catch (Exception e) {
            logger.error("Failed to save Trim Height to database", e);
        }
    }

    private void savePruneHeight(int pruneHeight) {
        try {
            if (pruneHeight > 0) {
                blockchainStore.setProperty("pruneHeight", String.valueOf(pruneHeight));
                logger.info("Saved prune height {} to database", pruneHeight);
            }
        } catch (Exception e) {
            logger.error("Failed to save Prune Height to database", e);
        }
    }

    private void savePersistentState() {
        try {
            if (currentTrimHeight.get() > 0) {
                blockchainStore.setProperty("trimHeight", String.valueOf(currentTrimHeight.get()));
            }
            if (currentPruneHeight.get() > 0) {
                blockchainStore.setProperty("pruneHeight", String.valueOf(currentPruneHeight.get()));
            }
        } catch (Exception e) {
            logger.error("Failed to save persistent state to database", e);
        }
    }

    private void loadPersistentState() {
        try {
            // Load trim height
            String trimH = blockchainStore.getProperty("trimHeight");
            int tHeight = (trimH != null) ? Integer.parseInt(trimH) : 0;

            if (tHeight > blockchain.getHeight()) {
                logger.warn(
                        "Persistent trim height {} is higher than current blockchain height {}. Resetting to 0.",
                        tHeight, blockchain.getHeight());
                tHeight = 0;
            }

            currentTrimHeight.set(tHeight);
            logger.info("Loaded persistent trim height: {}", tHeight);

        } catch (Exception e) {
            logger.error("Failed to load trim height from database", e);
            currentTrimHeight.set(0);
        }

        try {
            // Load prune height
            String pruneH = blockchainStore.getProperty("pruneHeight");
            int pHeight = (pruneH != null) ? Integer.parseInt(pruneH) : 0;

            if (pHeight > blockchain.getHeight()) {
                logger.warn(
                        "Persistent prune height {} is higher than current blockchain height {}. Resetting to 0.",
                        pHeight, blockchain.getHeight());
                pHeight = 0;
            }

            currentPruneHeight.set(pHeight);
            logger.info("Loaded persistent prune height: {}", currentPruneHeight.get());

        } catch (Exception e) {
            logger.error("Failed to load persistent state from database", e);
            currentPruneHeight.set(0);
        }
    }

    @Override
    public boolean addListener(Listener<Block> listener, BlockchainProcessor.Event eventType) {
        return blockListeners.addListener(listener, eventType);
    }

    @Override
    public boolean removeListener(Listener<Block> listener, Event eventType) {
        return blockListeners.removeListener(listener, eventType);
    }

    @Override
    public Peer getLastBlockchainFeeder() {
        return lastBlockchainFeeder.get();
    }

    @Override
    public int getLastBlockchainFeederHeight() {
        return lastBlockchainFeederHeight.get();
    }

    @Override
    public boolean isScanning() {
        return isScanning.get();
    }

    @Override
    public boolean isTrimming() {
        return isTrimming.get();
    }

    @Override
    public boolean isPruning() {
        return isPruning.get();
    }

    @Override
    public int getMinRollbackHeight() {
        return Math.max(currentTrimHeight.get(), currentPruneHeight.get());
    }

    @Override
    public ArchivalMode getArchivalMode() {
        return archivalMode;
    }

    @Override
    public AtomicInteger getCurrentTrimHeight() {
        return currentTrimHeight;
    }

    @Override
    public AtomicInteger getCurrentPruneHeight() {
        return currentPruneHeight;
    }

    @Override
    public int getEstimatedTrimHeight() {
        int height = blockchain.getHeight();
        return height - (height % Constants.TRIM_PERIOD);
    }

    @Override
    public String getCurrentlyTrimmingTable() {
        return currentlyTrimmingTable.get();
    }

    @Override
    public ResolutionState getResolutionState() {
        return resolutionState;
    }

    @Override
    public PopOffState getManualPopOffState() {
        return manualPopOffState;
    }

    @Override
    public PopOffState getAutoPopOffState() {
        return autoPopOffState;
    }

    @Override
    public String getDbType() {
        return dbType;
    }

    @Override
    public String getDbVersion() {
        return dbVersion;
    }

    @Override
    public void processPeerBlock(JsonObject request, Peer peer) throws SignumException {
        Block newBlock = Block.parseBlock(request, blockchain.getHeight(), fluxCapacitor);
        // * This process takes care of the blocks that is announced by peers We do not
        // want to be fed forks.
        Block chainblock = downloadCache.getLastBlock();
        if (chainblock != null && chainblock.getId() == newBlock.getPreviousBlockId()) {
            newBlock.setHeight(chainblock.getHeight() + 1);
            newBlock.setByteLength(newBlock.toString().length());
            blockService.calculateBaseTarget(newBlock, chainblock);
            downloadCache.addBlock(newBlock);
            logger.debug(
                    "Peer {} added block from Announce: Id: {} Height: {}",
                    peer.getPeerAddress(),
                    newBlock.getId(), newBlock.getHeight());
        } else {
            logger.debug(
                    "Peer {} sent us block: {} which is not the follow-up block for {}",
                    peer.getPeerAddress(),
                    newBlock.getPreviousBlockId(), chainblock.getId());
        }
    }

    @Override
    public void fullReset() {
        dbCacheManager.flushCache();
        downloadCache.resetCache();
        blockDb.deleteAll(false);
        addGenesisBlock();
        dbCacheManager.flushCache();
        downloadCache.resetCache();
    }

    @Override
    public void setGetMoreBlocksPause(boolean getMoreBlocksPause) {
        this.getMoreBlocksPause.set(getMoreBlocksPause);
    }

    @Override
    public void setBlockImporterPause(boolean blockImporterPause) {
        this.blockImporterPause.set(blockImporterPause);
    }

    @Override
    public boolean isSkipDbCheckOnManualPopOff() {
        return skipDbCheckOnManualPopOff.get();
    }

    @Override
    public void setSkipDbCheckOnManualPopOff(boolean skip) {
        this.skipDbCheckOnManualPopOff.set(skip);
    }

    @Override
    public void setSyncPaused(boolean paused) {
        setGetMoreBlocksPause(paused);
        setBlockImporterPause(paused);
        syncStateListeners.notify(paused, Event.SYNC_STATE_CHANGED);
        if (paused) {
            logger.info("Blockchain synchronization paused.");
        } else {
            logger.info("Blockchain synchronization resumed.");
        }
    }

    void setGetMoreBlocks(boolean getMoreBlocks) {
        this.getMoreBlocks.set(getMoreBlocks);
    }

    private void addBlock(Block block) {
        blockchainStore.addBlock(block);
        blockchain.setLastBlock(block);
    }

    private int checkDatabaseState() {
        logger.debug("Block height {}, checking database state...", blockchain.getHeight());
        long totalMined = blockchain.getTotalMined();

        long totalEffectiveBalance = accountService.getAllAccountsBalance();
        for (Escrow escrow : escrowService.getAllEscrowTransactions()) {
            totalEffectiveBalance += escrow.getAmountNQT();
        }

        int comparison = Long.compare(totalMined, totalEffectiveBalance);
        if (comparison != 0) {
            // Log detailed components of totalEffectiveBalance
            long accountBalances = accountService.getAllAccountsBalance();
            long escrowBalances = 0;
            for (Escrow escrow : escrowService.getAllEscrowTransactions()) {
                escrowBalances += escrow.getAmountNQT();
            }

            long diff = totalMined - totalEffectiveBalance;

            // If Auto Resolve is active, log at debug level to reduce spam
            if (resolutionState == ResolutionState.ACTIVE) {
                logger.debug("  Database inconsistency detected at height {}", blockchain.getHeight());
                logger.debug("  Total Mined (Supply)           : {}", totalMined);
                logger.debug("  Total Effective Balance        : {}", totalEffectiveBalance);
                logger.debug("  Difference (Mined - Effective) : {}", diff);
                logger.debug(" --------------------------------------------------");
                logger.debug("  Component - Account Balances   : {}", accountBalances);
                logger.debug("  Component - Escrow Balances    : {}", escrowBalances);
                logger.debug("  Calculated Sum (Acc + Escrow): {}", (accountBalances + escrowBalances));
                logger.debug("----------------------------------------------------");
            } else {
                logger.error("  DATABASE INCONSISTENCY DETECTED at height {}", blockchain.getHeight());
                logger.error("  Total Mined (Supply)           : {}", totalMined);
                logger.error("  Total Effective Balance        : {}", totalEffectiveBalance);
                logger.error("  Difference (Mined - Effective) : {}", diff);
                logger.error("  --------------------------------------------------");
                logger.error("  Component - Account Balances   : {}", accountBalances);
                logger.error("  Component - Escrow Balances    : {}", escrowBalances);
                logger.error("  Calculated Sum (Acc + Escrow): {}", (accountBalances + escrowBalances));
                logger.error("----------------------------------------------------");

                logger.warn(
                        "Block height {}, total mined {}, total effective+burnt {}",
                        blockchain.getHeight(),
                        totalMined,
                        totalEffectiveBalance);
            }
        }

        lastCheckTotalMined.set(totalMined);
        lastCheckTotalEffectiveBalance.set(totalEffectiveBalance);
        lastCheckHeight.set(blockchain.getHeight());

        // Update previous state for the next check
        previousConsistencyState = consistencyState.get();

        ConsistencyState newConsistencyState = (totalMined == totalEffectiveBalance) ? ConsistencyState.CONSISTENT
                : ConsistencyState.INCONSISTENT;
        consistencyState.set(newConsistencyState);

        /**
         * If the database becomes consistent (e.g. via manual resync or revalidate)
         * while the resolution state was FAILED, we update it to IDLE to reflect the
         * recovery.
         * This ensures the UI and other components see the correct state.
         */
        if (newConsistencyState == ConsistencyState.CONSISTENT && resolutionState == ResolutionState.FAILED) {
            resolutionState = ResolutionState.IDLE;
            logger.info("Database consistency restored. Resolution state updated to IDLE.");
        }

        blockListeners.notify(null, Event.DATABASE_CONSISTENCY_UPDATE); // Notify listeners about the state update

        if (logger.isDebugEnabled()) {
            logger.debug("Total mined {}, total effective {}", totalMined, totalEffectiveBalance);
            logger.debug("Database consistency state: {}", consistencyState.get());
        }

        return comparison;
    }

    private int checkDatabaseStateWithLog() {
        logger.debug("Block height {}, checking database state...", blockchain.getHeight());
        long totalMined = blockchain.getTotalMined();

        long totalEffectiveBalance = accountService.getAllAccountsBalance();
        for (Escrow escrow : escrowService.getAllEscrowTransactions()) {
            totalEffectiveBalance += escrow.getAmountNQT();
        }

        int comparison = Long.compare(totalMined, totalEffectiveBalance);
        if (comparison != 0) {
            // Log detailed components of totalEffectiveBalance
            long accountBalances = accountService.getAllAccountsBalance();
            long escrowBalances = 0;
            for (Escrow escrow : escrowService.getAllEscrowTransactions()) {
                escrowBalances += escrow.getAmountNQT();
            }

            long diff = totalMined - totalEffectiveBalance;

            logger.error("  DATABASE INCONSISTENCY DETECTED at height {}", blockchain.getHeight());
            logger.error("  Total Mined (Supply)           : {}", totalMined);
            logger.error("  Total Effective Balance        : {}", totalEffectiveBalance);
            logger.error("  Difference (Mined - Effective) : {}", diff);
            logger.error("  --------------------------------------------------");
            logger.error("  Component - Account Balances   : {}", accountBalances);
            logger.error("  Component - Escrow Balances    : {}", escrowBalances);
            logger.error("  Calculated Sum (Acc + Escrow): {}", (accountBalances + escrowBalances));
            logger.error("----------------------------------------------------");

            logger.warn(
                    "Block height {}, total mined {}, total effective+burnt {}",
                    blockchain.getHeight(),
                    totalMined,
                    totalEffectiveBalance);
        } else {
            logger.info("Database is consistent at height {}", blockchain.getHeight());
            logger.info("Total Mined (Supply)           : {}", totalMined);
            logger.info("Total Effective Balance        : {}", totalEffectiveBalance);
        }

        lastCheckTotalMined.set(totalMined);
        lastCheckTotalEffectiveBalance.set(totalEffectiveBalance);
        lastCheckHeight.set(blockchain.getHeight());

        // Update previous state for the next check
        previousConsistencyState = consistencyState.get();

        ConsistencyState newConsistencyState = (totalMined == totalEffectiveBalance) ? ConsistencyState.CONSISTENT
                : ConsistencyState.INCONSISTENT;
        consistencyState.set(newConsistencyState);

        /**
         * If the database becomes consistent (e.g. via manual resync or revalidate)
         * while the resolution state was FAILED, we update it to IDLE to reflect the
         * recovery.
         * This ensures the UI and other components see the correct state.
         */
        if (newConsistencyState == ConsistencyState.CONSISTENT && resolutionState == ResolutionState.FAILED) {
            resolutionState = ResolutionState.IDLE;
            logger.info("Database consistency restored. Resolution state updated to IDLE.");
        }

        blockListeners.notify(null, Event.DATABASE_CONSISTENCY_UPDATE); // Notify listeners about the state update

        return comparison;
    }

    private void initialCleanDatabase() {
        logger.info("Initial DatabaseClean popoff 1 block...");
        if (blockchain.getHeight() > getMinRollbackHeight()) {
            popOff(1);
        }

        if (Boolean.FALSE.equals(propertyService.getBoolean(Props.DB_SKIP_CHECK))) {
            // Check database state and auto-resolve if needed on startup
            logger.info("Initial database check...");
            checkDatabaseStateRequest();
            if (isAutoResolutionRequired()) {
                logger.info("Database is inconsistent on startup.");
                manualResolveDatabaseConsistency();
            }
        }
    }

    /**
     * Public entry point to request a database consistency check.
     * This method pauses block processing threads, performs the consistency check
     * using {@link #checkDatabaseState()},
     * and then resumes the processing threads.
     *
     * @return 0 if the database is consistent, otherwise a non-zero value
     *         indicating inconsistency.
     */
    public int checkDatabaseStateRequest() {
        if (isMaintenanceRunning.get()) {
            String phase = isPruning.get() ? "Pruning" : "Trim";
            logger.info("{} is in progress. Database state check will give results after maintenance finished.", phase);
        }
        if (resolutionState == ResolutionState.ACTIVE) {
            logger.info(
                    "Database consistency resolution is in progress. Database state check will give results after resolution finished.");
        } else if (manualPopOffState == PopOffState.ACTIVE || autoPopOffState == PopOffState.ACTIVE) {
            logger.info("Pop-off is in progress. Database state check will give results after pop-off finished.");
        }

        // Pause other operations
        getMoreBlocksAutoPause.set(true);
        blockImporterAutoPause.set(true);
        getMoreBlocksLock.writeLock().lock();
        blockImporterLock.writeLock().lock();
        try {
            synchronized (transactionProcessor.getUnconfirmedTransactionsSyncObj()) {
                return checkDatabaseStateWithLog();
            }
        } finally {
            // Resume other operations
            blockImporterLock.writeLock().unlock();
            getMoreBlocksLock.writeLock().unlock();
            getMoreBlocksAutoPause.set(false);
            blockImporterAutoPause.set(false);
        }
    }

    @Override
    public ConsistencyState getConsistencyState() {
        return consistencyState.get();
    }

    @Override
    public long getTotalMined() {
        return blockchain.getTotalMined();
    }

    @Override
    public long getTotalEffectiveBalance() {
        long totalEffectiveBalance = accountService.getAllAccountsBalance();
        for (Escrow escrow : escrowService.getAllEscrowTransactions()) {
            totalEffectiveBalance += escrow.getAmountNQT();
        }
        return totalEffectiveBalance;
    }

    @Override
    public long getLastCheckTotalMined() {
        return lastCheckTotalMined.get();
    }

    @Override
    public long getLastCheckTotalEffectiveBalance() {
        return lastCheckTotalEffectiveBalance.get();
    }

    @Override
    public int getLastCheckHeight() {
        return lastCheckHeight.get();
    }

    private void addGenesisBlock() {
        // Check if database is not empty (either has Genesis or is a pruned legacy DB)
        if (blockDb.hasBlock(genesisBlockId)) {
            Block lastBlock = blockDb.findLastBlock();
            if (lastBlock != null) {
                if (blockDb.hasBlock(genesisBlockId)) {
                    logger.info("Genesis block already in database");
                } else {
                    logger.info("Genesis block was pruned, database starting from height {}", lastBlock.getHeight());
                }
                blockchain.setLastBlock(lastBlock);
                logger.info("Last block height: {}, baseTarget: {}{}", lastBlock.getHeight(),
                        lastBlock.getCapacityBaseTarget(),
                        fluxCapacitor.getValue(FluxValues.POC_PLUS)
                                ? ", averageCommitmentNQT " + lastBlock.getAverageCommitment()
                                : "");
                return;
            }
        }
        logger.info("Database is empty, starting from scratch with genesis block");
        try {
            List<Transaction> transactions = new ArrayList<>();
            MessageDigest digest = Crypto.sha256();
            transactions.forEach(transaction -> digest.update(transaction.getBytes()));
            ByteBuffer bf = ByteBuffer.allocate(0);
            bf.order(ByteOrder.LITTLE_ENDIAN);
            byte[] byteAts = bf.array();
            int genesisTimestamp = propertyService.getInt(Props.GENESIS_TIMESTAMP);
            Block genesisBlock = new Block(
                    -1,
                    genesisTimestamp,
                    0,
                    0,
                    0,
                    0,
                    0,
                    transactions.size() * 128,
                    digest.digest(),
                    Genesis.getCreatorPublicKey(),
                    new byte[32],
                    Genesis.getGenesisBlockSignature(),
                    null,
                    transactions,
                    0, byteAts,
                    -1,
                    Constants.INITIAL_BASE_TARGET,
                    fluxCapacitor);
            blockService.setPrevious(genesisBlock, null);
            addBlock(genesisBlock);
        } catch (SignumException.ValidationException e) {
            logger.info(e.getMessage());
            throw new RuntimeException(e.toString(), e);
        }
    }

    private void pushBlock(final Block block) throws BlockNotAcceptedException {
        long totalStartTime = System.nanoTime();
        long validationTime = 0;
        long txLoopTime = 0;
        long housekeepingTime = 0;
        long commitTime = 0;

        synchronized (transactionProcessor.getUnconfirmedTransactionsSyncObj()) {
            stores.beginTransaction();
            int curTime = timeService.getEpochTime();
            Block previousLastBlock = null;
            try {
                long stepStart = System.nanoTime();

                previousLastBlock = blockchain.getLastBlock();

                if (previousLastBlock.getId() != block.getPreviousBlockId()) {
                    throw new BlockOutOfOrderException(
                            "Previous block id doesn't match for block " + block.getHeight()
                                    + ((previousLastBlock.getHeight() + 1) == block.getHeight() ? ""
                                            : " invalid previous height " + previousLastBlock.getHeight()));
                }

                if (block.getVersion() != getBlockVersion()) {
                    throw new BlockNotAcceptedException(
                            "Invalid version " + block.getVersion() + " for block " + block.getHeight());
                }

                if (block.getVersion() != 1
                        && !Arrays.equals(Crypto.sha256().digest(previousLastBlock.getBytes()),
                                block.getPreviousBlockHash())) {
                    throw new BlockNotAcceptedException(
                            "Previous block hash doesn't match for block " + block.getHeight());
                }
                int blockTimestamp = block.getTimestamp();
                int prevBlockTimestamp = previousLastBlock.getTimestamp();
                String peerAddress = block.getPeer() != null ? block.getPeer().getAnnouncedAddress() : "null";

                if (blockTimestamp > curTime + MAX_TIMESTAMP_DIFFERENCE) {
                    int diffSeconds = blockTimestamp - curTime;
                    throw new BlockOutOfOrderException(
                            "BLOCK TIMESTAMP TOO FAR IN THE FUTURE - possible system clock drift detected. " +
                            "Incoming block timestamp (" + blockTimestamp + ") is GREATER than local time (" + curTime + 
                            ") by +" + diffSeconds + " seconds (maximum allowed: " + MAX_TIMESTAMP_DIFFERENCE + " seconds). " +
                            "Your system clock appears to be BEHIND by approximately " + diffSeconds + " seconds. " +
                            "ACTION: Sync your system clock with NTP (Windows: w32tm /resync, Linux: sudo ntpdate -u pool.ntp.org). " +
                            "Peer: " + peerAddress);
                }
                if (blockTimestamp <= prevBlockTimestamp) {
                    int diffFromPrev = blockTimestamp - prevBlockTimestamp;
                    throw new BlockOutOfOrderException(
                            "BLOCK TIMESTAMP NOT AFTER PREVIOUS BLOCK - invalid block chain ordering. " +
                            "Incoming block timestamp (" + blockTimestamp + ") is LESS THAN or EQUAL to previous block timestamp (" + 
                            prevBlockTimestamp + "). Difference: " + diffFromPrev + " seconds. " +
                            "Every new block must have a strictly greater timestamp than its parent. " +
                            "Peer: " + peerAddress);
                }
                if (block.getId() == 0L || blockDb.hasBlock(block.getId())) {
                    throw new BlockNotAcceptedException("Duplicate block or invalid id for block " + block.getHeight());
                }
                if (!block.isVerified() && !blockService.verifyGenerationSignature(block)) {
                    throw new GenerationSignatureException(
                            "Generation signature verification failed for block " + block.getHeight());
                }
                if (!blockService.verifyBlockSignature(block)) {
                    throw new BlockNotAcceptedException(
                            "Block signature verification failed for block " + block.getHeight());
                }

                validationTime = (System.nanoTime() - stepStart);
                stepStart = System.nanoTime();

                final TransactionDuplicatesCheckerImpl transactionDuplicatesChecker = new TransactionDuplicatesCheckerImpl();
                long calculatedTotalAmount = 0;
                long calculatedTotalFee = 0;
                MessageDigest digest = Crypto.sha256();
                List<Transaction> transactions = block.getTransactions();
                long[] feeArray = new long[transactions.size()];
                int slotIdx = 0;

                int maxIndirects = propertyService.getInt(Props.MAX_INDIRECTS_PER_BLOCK);
                int indirectsCount = 0;

                for (Transaction transaction : transactions) {
                    int txTimestamp = transaction.getTimestamp();
                    String txId = transaction.getStringId();

                    if (txTimestamp > curTime + MAX_TIMESTAMP_DIFFERENCE) {
                        int txDiffSeconds = txTimestamp - curTime;
                        throw new BlockOutOfOrderException(
                                "TRANSACTION TIMESTAMP TOO FAR IN THE FUTURE. " +
                                "Transaction: " + txId + ", " +
                                "Transaction timestamp: " + txTimestamp + ", " +
                                "Local time: " + curTime + ", " +
                                "Difference: " + txDiffSeconds + " seconds, " +
                                "Maximum allowed: " + MAX_TIMESTAMP_DIFFERENCE + " seconds");
                    }
                    if (txTimestamp > block.getTimestamp() + MAX_TIMESTAMP_DIFFERENCE
                            || transaction.getExpiration() < block.getTimestamp()) {
                        throw new TransactionNotAcceptedException(
                                "TRANSACTION TIMESTAMP OUTSIDE BLOCK WINDOW. " +
                                "Transaction: " + txId + ", " +
                                "Transaction timestamp: " + txTimestamp + ", " +
                                "Block timestamp: " + block.getTimestamp() + ", " +
                                "Max future allowed: " + MAX_TIMESTAMP_DIFFERENCE + " seconds",
                                transaction);
                    }
                    if (transactionDb.hasTransaction(transaction.getId())) {
                        throw new TransactionNotAcceptedException(
                                "Transaction " + transaction.getStringId() + " is already in the blockchain",
                                transaction);
                    }
                    if (transaction.getReferencedTransactionFullHash() != null && ((!transactionDb.hasTransaction(
                            Convert.fullHashToId(transaction.getReferencedTransactionFullHash())))
                            || (!hasAllReferencedTransactions(transaction, transaction.getTimestamp(), 0)))) {
                        throw new TransactionNotAcceptedException("Missing or invalid referenced transaction "
                                + transaction.getReferencedTransactionFullHash() + " for transaction "
                                + transaction.getStringId(), transaction);
                    }
                    if (transaction.getVersion() != transactionProcessor
                            .getTransactionVersion(previousLastBlock.getHeight())) {
                        throw new TransactionNotAcceptedException("Invalid transaction version "
                                + transaction.getVersion() + " at height " + previousLastBlock.getHeight(),
                                transaction);
                    }

                    if (!transactionService.verifyPublicKey(transaction)) {
                        throw new TransactionNotAcceptedException("Wrong public key in transaction "
                                + transaction.getStringId() + " at height " + previousLastBlock.getHeight(),
                                transaction);
                    }
                    if (fluxCapacitor.getValue(FluxValues.AUTOMATED_TRANSACTION_BLOCK)
                            && !economicClustering.verifyFork(transaction)) {
                        int height = previousLastBlock.getHeight() + 1;
                        int distance = height - transaction.getEcBlockHeight();

                        if (distance > Constants.MAX_ROLLBACK) {
                            // This is an objective protocol violation, not a local state issue.
                            logger.warn(
                                    "Rejecting block {} from peer: transaction {} references impossible EC fork depth ({} blocks). Blacklisting peer.",
                                    block.getStringId(), transaction.getStringId(), distance);
                            throw new BlockNotAcceptedException("Transaction references an impossible EC block height: "
                                    + transaction.getEcBlockHeight());
                        } else {
                            logger.warn(
                                    "Block {} height {} contains transaction with failed EC verification (belongs to different fork): {} referencing ecBlockId {} at height {}",
                                    block.getStringId(), height, transaction.getStringId(),
                                    Convert.toUnsignedLong(transaction.getEcBlockId()), transaction.getEcBlockHeight());
                            throw new TransactionNotAcceptedException("Transaction belongs to a different fork",
                                    transaction);
                        }
                    }
                    if (transaction.getId() == 0L) {
                        throw new TransactionNotAcceptedException("Invalid transaction id", transaction);
                    }

                    if (transactionDuplicatesChecker.hasAnyDuplicate(transaction)) {
                        throw new TransactionNotAcceptedException(
                                "Transaction is a duplicate: " + transaction.getStringId(), transaction);
                    }

                    int txIndirects = transaction.getType().getIndirectIncomings(transaction).size();
                    if (indirectsCount + txIndirects > maxIndirects) {
                        throw new TransactionNotAcceptedException(
                                "Maximum indirects limit of " + maxIndirects + " reached: " + transaction.getStringId(),
                                transaction);
                    }
                    indirectsCount += txIndirects;

                    try {
                        transactionService.validate(transaction);
                    } catch (SignumException.ValidationException e) {
                        throw new TransactionNotAcceptedException(e.getMessage(), transaction);
                    }

                    calculatedTotalAmount = Convert.safeAdd(calculatedTotalAmount, transaction.getAmountNqt());
                    calculatedTotalFee = Convert.safeAdd(calculatedTotalFee, transaction.getFeeNqt());
                    digest.update(transaction.getBytes());
                    indirectIncomingService.processTransaction(transaction);
                    feeArray[slotIdx] = transaction.getFeeNqt();
                    slotIdx += 1;
                }

                txLoopTime = System.nanoTime() - stepStart;
                stepStart = System.nanoTime();

                if (calculatedTotalAmount > block.getTotalAmountNqt()
                        || calculatedTotalFee > block.getTotalFeeNqt()) {
                    throw new BlockNotAcceptedException(
                            "Total amount or fee don't match transaction totals for block " + block.getHeight());
                }

                if (fluxCapacitor.getValue(FluxValues.SMART_FEES, block.getHeight())) {
                    long calculatedTotalFeeCashBackNqt = 0;
                    for (Transaction transaction : transactions) {
                        calculatedTotalFeeCashBackNqt = Convert.safeAdd(calculatedTotalFeeCashBackNqt,
                                transaction.getFeeNqt() / propertyService.getInt(Props.CASH_BACK_FACTOR));
                    }
                    if (calculatedTotalFeeCashBackNqt != block.getTotalFeeCashBackNqt()) {
                        throw new BlockNotAcceptedException(
                                "Total fee cash back doesn't match transaction totals for block " + block.getHeight());
                    }
                }

                if (fluxCapacitor.getValue(FluxValues.SODIUM)
                        && !fluxCapacitor.getValue(FluxValues.SPEEDWAY)) {
                    Arrays.sort(feeArray);
                    for (int i = 0; i < feeArray.length; i++) {
                        if (feeArray[i] < Constants.FEE_QUANT_SIP3 * (i + 1)) {
                            throw new BlockNotAcceptedException(
                                    "Transaction fee is not enough to be included in this block " + block.getHeight());
                        }
                    }
                }

                if (!Arrays.equals(digest.digest(), block.getPayloadHash())) {
                    throw new BlockNotAcceptedException("Payload hash doesn't match for block " + block.getHeight());
                }

                validationTime += (System.nanoTime() - stepStart);

                stepStart = System.nanoTime();
                blockService.setPrevious(block, previousLastBlock);
                blockListeners.notify(block, Event.BEFORE_BLOCK_ACCEPT);
                transactionProcessor.removeForgedTransactions(transactions);
                transactionProcessor.requeueAllUnconfirmedTransactions();
                accountService.flushAccountTable();
                addBlock(block);

                housekeepingTime = System.nanoTime() - stepStart;

                long remainingAmount = Convert.safeSubtract(block.getTotalAmountNqt(), calculatedTotalAmount);
                long remainingFee = Convert.safeSubtract(block.getTotalFeeNqt(), calculatedTotalFee);
                accept(block, remainingAmount, remainingFee);

                long commitStart = System.nanoTime();
                derivedTableManager.getDerivedTables().forEach(DerivedTable::finish);
                stores.commitTransaction();
                commitTime = System.nanoTime() - commitStart;

                // We make sure downloadCache do not have this block anymore, but only after all
                // DBs have it
                downloadCache.removeBlock(block);
            } catch (BlockNotAcceptedException | ArithmeticException e) {
                stores.rollbackTransaction();
                blockchain.setLastBlock(previousLastBlock);
                downloadCache.resetCache();
                atProcessorCache.reset();
                throw e;
            } finally {
                stores.endTransaction();
            }

            long totalEndTime = System.nanoTime();
            long totalTimeMs = TimeUnit.NANOSECONDS.toMillis(totalEndTime - totalStartTime);
            long validationTimeMs = TimeUnit.NANOSECONDS.toMillis(validationTime);
            long txLoopTimeMs = TimeUnit.NANOSECONDS.toMillis(txLoopTime);
            long housekeepingTimeMs = TimeUnit.NANOSECONDS.toMillis(housekeepingTime);
            long commitTimeMs = TimeUnit.NANOSECONDS.toMillis(commitTime);
            long txApplyTimeMs = TimeUnit.NANOSECONDS.toMillis(txApplyTimeNanos);
            atTimeMs = TimeUnit.NANOSECONDS.toMillis(atTimeNanos);
            long subscriptionTimeMs = TimeUnit.NANOSECONDS.toMillis(subscriptionTimeNanos);
            long blockApplyTimeMs = TimeUnit.NANOSECONDS.toMillis(blockApplyTimeNanos);
            long sumTimeMs = validationTimeMs + txLoopTimeMs + housekeepingTimeMs + txApplyTimeMs + atTimeMs
                    + subscriptionTimeMs + blockApplyTimeMs + commitTimeMs;
            long miscTimeMs = totalTimeMs - sumTimeMs;

            int userTransactionCount = block.getTransactions().size();
            int atTransactionCount = block.getAtTransactions().size();
            int subscriptionTransactionCount = block.getSubscriptionTransactions().size();
            int escrowTransactionCount = block.getEscrowTransactions().size();
            int systemTransactionCount = atTransactionCount + subscriptionTransactionCount
                    + escrowTransactionCount;
            int allTransactionCount = userTransactionCount + systemTransactionCount;
            int atCount = 0;

            if (block.getBlockAts() != null) {
                try {
                    atCount = atService.getATsFromBlock(block.getBlockAts()).size();
                } catch (Exception e) {
                    // ignore, as this is for measurement only
                }
            }

            int maxPayloadSize = fluxCapacitor.getValue(FluxValues.MAX_PAYLOAD_LENGTH, block.getHeight());

            performanceStats.set(new BlockchainProcessor.PerformanceStats(totalTimeMs, validationTimeMs, txLoopTimeMs,
                    housekeepingTimeMs, txApplyTimeMs, atTimeMs, subscriptionTimeMs, blockApplyTimeMs, commitTimeMs,
                    miscTimeMs, block.getHeight(), allTransactionCount, systemTransactionCount, atCount,
                    block.getPayloadLength(), maxPayloadSize));
            performanceStatsListeners.notify(performanceStats.get(), Event.PERFORMANCE_STATS_UPDATED);

            logger.debug("Successfully pushed {} (height {})", block.getId(), block.getHeight());
            statisticsManager.blockAdded();
            blockListeners.notify(block, Event.BLOCK_PUSHED);
            if (block.getTimestamp() >= timeService.getEpochTime() - MAX_TIMESTAMP_DIFFERENCE) {
                Peers peers = peerManager != null ? peerManager.getPeers() : null;
                if (peers != null) {
                    peers.sendToSomePeers(block);
                }
            }
            if (block.getHeight() >= autoPopOffLastStuckHeight) {
                autoPopOffNumberOfBlocks = 0;
            }

            if ((block.getHeight() % Constants.MAX_ROLLBACK) == 0) {
                // Check Database consistency at rollback boundaries
                if (checkDatabaseState() == 0) {
                    // Only trim a consistent database, otherwise it would be impossible to fix it
                    // by roll back
                    if (archivalMode != ArchivalMode.ARCHIVE && block.getHeight() % Constants.TRIM_PERIOD == 0) {
                        // Execute periodic maintenance
                        executeArchivalMaintenance(block);
                    }
                } else {
                    logger.warn(
                            "Database is inconsistent at block height {}, skipping trim to preserve rollback capability.",
                            block.getHeight());
                    if (isAutoResolutionRequired()) {
                        autoResolveDatabaseConsistency();
                    }
                }
            }
        }
    }

    private void accept(Block block, Long remainingAmount, Long remainingFee)
            throws BlockNotAcceptedException {
        long start;

        // Reset timers
        txApplyTimeNanos = 0L;
        subscriptionTimeNanos = 0L;
        blockApplyTimeNanos = 0L;
        atTimeNanos = 0L;

        start = System.nanoTime();
        subscriptionService.clearRemovals();
        transactionService.startNewBlock();
        for (Transaction transaction : block.getTransactions()) {
            if (!transactionService.applyUnconfirmed(transaction)) {
                throw new TransactionNotAcceptedException(
                        "Transaction not accepted: " + transaction.getStringId(), transaction);
            }
        }
        txApplyTimeNanos = (System.nanoTime() - start);

        // AT.getOrderedATs sees the new balances after flush
        accountService.flushAccountTable();

        // ATs
        AtBlock atBlock;
        atService.clearPending(block.getHeight(), block.getGeneratorId());
        long atStartTime = 0;
        long atEndTime = 0;
        try {
            atStartTime = System.nanoTime();
            atBlock = atService.validateATs(block.getBlockAts(), blockchain.getHeight(), block.getGeneratorId());
            atEndTime = System.nanoTime();
        } catch (AtException e) {
            throw new ConsensusMismatchException(
                    "ats are not matching at block height " + blockchain.getHeight() + " (" + e + ")");
        }
        atTimeNanos = atEndTime > 0 ? atEndTime - atStartTime : 0;

        long calculatedRemainingAmount = 0;
        long calculatedRemainingFee = 0;
        calculatedRemainingAmount = Convert.safeAdd(calculatedRemainingAmount, atBlock.getTotalAmount());
        calculatedRemainingFee = Convert.safeAdd(calculatedRemainingFee, atBlock.getTotalFees());

        start = System.nanoTime();
        if (subscriptionService.isEnabled()) {
            calculatedRemainingFee = Convert.safeAdd(calculatedRemainingFee,
                    subscriptionService.applyUnconfirmed(block.getTimestamp(), block.getHeight()));
        }
        subscriptionTimeNanos = (System.nanoTime() - start);

        if (remainingAmount != null && remainingAmount != calculatedRemainingAmount) {
            throw new ConsensusMismatchException(
                    "Calculated remaining amount doesn't add up for block " + block.getHeight());
        }
        if (remainingFee != null && remainingFee != calculatedRemainingFee) {
            throw new ConsensusMismatchException(
                    "Calculated remaining fee doesn't add up for block " + block.getHeight());
        }
        if (block.getVersion() >= 4 && fluxCapacitor.getValue(FluxValues.SMART_FEES, block.getHeight())) {
            if (calculatedRemainingFee != block.getTotalFeeBurntNqt()) {
                throw new BlockNotAcceptedException(
                        "Total fee burnt doesn't match AT and subscription totals for block " + block.getHeight());
            }
        }

        start = System.nanoTime();
        blockListeners.notify(block, Event.BEFORE_BLOCK_APPLY);
        blockService.apply(block);
        blockApplyTimeNanos = (System.nanoTime() - start);

        start = System.nanoTime();
        subscriptionService.applyConfirmed(block, blockchain.getHeight());
        subscriptionTimeNanos += (System.nanoTime() - start);

        start = System.nanoTime();
        if (escrowService.isEnabled()) {
            escrowService.updateOnBlock(block, blockchain.getHeight());
        }
        blockListeners.notify(block, Event.AFTER_BLOCK_APPLY);
        if (!block.getTransactions().isEmpty()) {
            transactionProcessor.notifyListeners(block.getTransactions(),
                    TransactionProcessor.Event.ADDED_CONFIRMED_TRANSACTIONS);
        }
        blockApplyTimeNanos += (System.nanoTime() - start);
    }

    @Override
    public List<Block> popOffTo(int height) {
        // We need to acquire locks here because autoResolveDatabaseConsistency assumes
        // exclusive access, and the private popOffTo releases its locks before
        // returning.
        synchronized (downloadCache) {
            synchronized (transactionProcessor.getUnconfirmedTransactionsSyncObj()) {
                Block targetBlock = blockchain.getBlockAtHeight(height);
                if (targetBlock == null) {
                    logger.error("Rollback failed: Block at height {} not found in the database!",
                            height);
                    return Collections.emptyList();
                }
                List<Block> blocks = popOffTo(targetBlock, null);
                if (Boolean.FALSE.equals(propertyService.getBoolean(Props.DB_SKIP_CHECK))
                        && checkDatabaseState() != 0) {
                    if (isAutoResolutionRequired()) {
                        autoResolveDatabaseConsistency();
                    }
                }
                return blocks;
            }
        }
    }

    @Override
    public void popOff(int blockCount) {
        synchronized (this) {
            if (manualPopOffBlocksCount.getAndAdd(blockCount) > 0) {
                logger.info("Request adds {} blocks to pop off.", blockCount);
                if (manualLastPopOffHeight.get() > 0) {
                    manualLastPopOffHeight.set(Math.max(manualLastPopOffHeight.get() - blockCount, 0));
                    Block block = blockchain.getLastBlock();
                    blockListeners.notify(block, Event.BLOCK_MANUAL_POPPED);
                }
                return;
            }
        }
        if (manualPopOffBlocksCount.get() == 0) {
            logger.info("No blocks to pop off.");
            return;
        }
        logger.info("Request adds {} blocks to pop off.", blockCount);
        getMoreBlocksAutoPause.set(true);
        blockImporterAutoPause.set(true);
        logger.info("Block processing threads paused for pop-off.");
        if (isMaintenanceRunning.get()) {
            String phase = isPruning.get() ? "Pruning" : "Trim";
            logger.info("{} is in progress. Manual pop off will start after maintenance finished.", phase);
        }
        if (autoPopOffBlocksCount.get() > 0) {
            logger.info("Auto pop off is in progress. Manual pop off will start after auto pop off.");
        }
        getMoreBlocksLock.writeLock().lock();
        blockImporterLock.writeLock().lock();
        if (isShutdown.get()) {
            logger.info("Node is shutting down, pop-off aborted.");
            manualPopOffBlocksCount.set(0);
            manualLastPopOffHeight.set(-1);
            blockImporterLock.writeLock().unlock();
            getMoreBlocksLock.writeLock().unlock();
            return;
        }
        int poppedBlocks = 0;
        Block block = blockchain.getLastBlock();
        try {
            synchronized (transactionProcessor.getUnconfirmedTransactionsSyncObj()) {
                manualPopOffState = PopOffState.ACTIVE;
                if (manualPopOffBlocksCount.get() == 0) {
                    logger.info("No blocks to pop off.");
                    return;
                }
                logger.info("Pop off in progress...");
                stores.beginTransaction();
                try {
                    int blockHeight = block.getHeight();
                    beforeRollbackHeight.set(blockHeight);
                    manualLastPopOffHeight.set(Math.max(beforeRollbackHeight.get() - manualPopOffBlocksCount.get(), 0));
                    blockListeners.notify(block, Event.BLOCK_MANUAL_POPPED);
                    minRollbackHeight = getMinRollbackHeight();
                    while (manualPopOffBlocksCount.get() > 0) {
                        if (isShutdown.get()) {
                            logger.info("Shutdown detected, aborting manual pop-off loop.");
                            break;
                        }
                        if (block == null || block.getId() == genesisBlockId) {
                            logger.info("Blockchain is empty or at genesis block, nothing more to pop off.");
                            manualPopOffBlocksCount.set(0);
                            break;
                        }
                        if (minRollbackHeight < block.getHeight()) {
                            block = popLastBlock();
                            for (DerivedTable table : derivedTableManager.getDerivedTables()) {
                                table.rollback(block.getHeight());
                            }
                            indirectIncomingService.rollback(block.getHeight());
                            dbCacheManager.flushCache();
                            downloadCache.resetCache();
                            atProcessorCache.reset();
                            // Checking database consistency after each block popped unless skipped via
                            // property
                            if (!skipDbCheckOnManualPopOff.get() && checkDatabaseState() != 0) {
                                manualPopOffBlocksCount.set(0);
                                manualLastPopOffHeight.set(-1);
                                stores.rollbackTransaction();
                                // Get block height from datbase
                                block = blockDb.findLastBlock();
                                blockchain.setLastBlock(block);
                                logger.warn("Database could be inconsistent after popping block at height {}.",
                                        block.getHeight() + 1);
                                logger.warn("Cacelling pop-off process to prevent database consistency.");
                                logger.warn("Setting blockchain height back to {}.", block.getHeight());
                                break;
                            } else {
                                stores.commitTransaction();
                                poppedBlocks++;
                                manualPopOffBlocksCount.decrementAndGet();
                                blockListeners.notify(block, Event.BLOCK_MANUAL_POPPED);
                            }
                        } else {
                            logger.warn("Reached minimum rollback height {}, cannot pop off block at height {}.",
                                    minRollbackHeight, block.getHeight());
                            logger.warn("Pop-off stopped to prevent exceeding rollback limit.");
                            logger.warn("Derived tables may not be available for rollback below height {}.",
                                    minRollbackHeight);
                            break;
                        }
                    }
                    transactionProcessor.requeueAllUnconfirmedTransactions();
                } catch (Exception e) {
                    manualPopOffBlocksCount.set(0);
                    manualLastPopOffHeight.set(-1);
                    stores.rollbackTransaction();
                    // Get block height from datbase
                    block = blockDb.findLastBlock();
                    blockchain.setLastBlock(block);
                    logger.error("Error occurred during pop-off at height {}.", block.getHeight(), e);
                    logger.error("Cacelling pop-off process to prevent database consistency.");
                    logger.error("Setting blockchain height back to {}.", block.getHeight());
                } catch (Error e) {
                    manualPopOffBlocksCount.set(0);
                    manualLastPopOffHeight.set(-1);
                    stores.rollbackTransaction();
                    // Get block height from datbase
                    block = blockDb.findLastBlock();
                    blockchain.setLastBlock(block);
                    logger.error("Critical error during pop-off, transaction rolled back.", e);
                    logger.error("Cacelling pop-off process to prevent database consistency.");
                    logger.error("Setting blockchain height back to {}.", block.getHeight());
                } finally {
                    dbCacheManager.flushCache();
                    downloadCache.resetCache();
                    atProcessorCache.reset();
                    // Get block height from datbase
                    block = blockDb.findLastBlock();
                    blockchain.setLastBlock(block);
                    stores.endTransaction();
                }
            }
        } catch (Exception e) {
            manualPopOffBlocksCount.set(0);
            manualLastPopOffHeight.set(-1);
            stores.rollbackTransaction();
            // Get block height from datbase
            block = blockDb.findLastBlock();
            blockchain.setLastBlock(block);
            logger.error("Unhandled exception during pop-off", e);
            logger.error("Cacelling pop-off process to prevent database consistency.");
            logger.error("Setting blockchain height back to {}.", block.getHeight());
        } finally {
            logger.info("Blocks popped off: {} ", poppedBlocks);
            if (block.getHeight() < beforeRollbackHeight.get()) {
                logger.info("Pop-off height to {} from {}", block.getHeight(), beforeRollbackHeight.get());
            } else {
                logger.info("Pop-off height: {}", block.getHeight());
            }
            if (checkDatabaseState() != 0) {
                logger.warn("Pop-off failed.");
                logger.warn("Database is inconsistent after pop-off.");
                logger.warn("Please restore from backup, resync, or revalidate database.");
            } else {
                logger.info("Pop-off completed.");
                logger.info("Database is consistent after pop-off.");
            }
            manualPopOffBlocksCount.set(0);
            manualLastPopOffHeight.set(-1);
            // Get block height from datbase
            block = blockDb.findLastBlock();
            blockchain.setLastBlock(block);
            manualPopOffState = PopOffState.IDLE;
            blockListeners.notify(block, Event.BLOCK_MANUAL_POPPED);
            blockImporterLock.writeLock().unlock();
            getMoreBlocksLock.writeLock().unlock();
            getMoreBlocksAutoPause.set(false);
            blockImporterAutoPause.set(false);
            logger.info("Block processing threads resumed after pop-off.");
        }
    }

    private List<Block> popOffTo(Block commonBlock, List<Block> forkBlocks) {

        int currentHeight = blockchain.getHeight();
        if (commonBlock.getHeight() < getMinRollbackHeight()) {
            autoPopOffBlocksCount.set(0);
            throw new IllegalArgumentException("Rollback to height " + commonBlock.getHeight() + " not suppported, "
                    + "current height " + currentHeight);
        }
        if (!blockchain.hasBlock(commonBlock.getId())) {
            autoPopOffBlocksCount.set(0);
            logger.debug("Block {} not found in blockchain, nothing to pop off", commonBlock.getStringId());
            return Collections.emptyList();
        }
        if (commonBlock.getHeight() > currentHeight) {
            autoPopOffBlocksCount.set(0);
            logger.warn("Rollback to {} from {} is non-sense, ignoring pop off", commonBlock.getHeight(),
                    currentHeight);
            return Collections.emptyList();

        }
        List<Block> poppedOffBlocks = new ArrayList<>();
        synchronized (downloadCache) {
            synchronized (transactionProcessor.getUnconfirmedTransactionsSyncObj()) {
                Block block = blockchain.getLastBlock();
                try {
                    autoPopOffState = PopOffState.ACTIVE;
                    stores.beginTransaction();
                    int blockHeight = block.getHeight();
                    beforeRollbackHeight.set(blockHeight);
                    autoPopOffBlocksCount.set(Math.max(beforeRollbackHeight.get() - commonBlock.getHeight(), 0));
                    int newRollbackHeight = commonBlock.getHeight();
                    autoLastPopOffHeight.set(newRollbackHeight);
                    blockListeners.notify(block, Event.BLOCK_AUTO_POPPED);
                    logger.info("Rollback to {} from {}",
                            commonBlock.getHeight(), block.getHeight());
                    while (block.getId() != commonBlock.getId() && block.getId() != genesisBlockId) {
                        if (isShutdown.get()) {
                            logger.info("Shutdown detected, aborting auto pop-off to height {}.",
                                    commonBlock.getHeight());
                            break;
                        }
                        if (forkBlocks != null) {
                            for (Block fb : forkBlocks) {
                                if (fb.getHeight() == block.getHeight()
                                        && fb.getGeneratorId() == block.getGeneratorId()) {
                                    logger.info("Possible rewrite fork, ID {} block being monitored {}",
                                            block.getGeneratorId(), block.getHeight());
                                    blockService.watchBlock(block);
                                }
                            }
                        }
                        logger.debug("Popping block {} generator {} sig {}", block.getHeight(),
                                SignumID.fromLong(block.getGeneratorId()).getID(),
                                Hex.toHexString(block.getBlockSignature()));
                        logger.debug("Block timestamp {} base target {} difficulty {} commitment {}",
                                block.getTimestamp(), block.getBaseTarget(),
                                block.getCumulativeDifficulty(), block.getCommitment());
                        poppedOffBlocks.add(block);
                        restoreBlocksCount.set(poppedOffBlocks.size());
                        notifyForkCacheStats();
                        block = popLastBlock();
                        logger.debug("Rolling back derived tables...");
                        for (DerivedTable table : derivedTableManager.getDerivedTables()) {
                            logger.debug("Rolling back {}", table.getTable());
                            table.rollback(block.getHeight());
                        }
                        indirectIncomingService.rollback(block.getHeight());
                        dbCacheManager.flushCache();
                        downloadCache.resetCache();
                        atProcessorCache.reset();
                        stores.commitTransaction();
                        autoPopOffBlocksCount.decrementAndGet();
                        blockListeners.notify(block, Event.BLOCK_AUTO_POPPED);
                    }
                } catch (RuntimeException e) {
                    stores.rollbackTransaction();
                    // Get block height from datbase
                    block = blockDb.findLastBlock();
                    blockchain.setLastBlock(block);
                    logger.error("Error popping off to {}", commonBlock.getHeight(), e);
                    logger.error("Setting blockchain height back to {}.", block.getHeight());
                    blockListeners.notify(block, Event.BLOCK_AUTO_POPPED);
                    throw e;
                } finally {
                    dbCacheManager.flushCache();
                    downloadCache.resetCache();
                    atProcessorCache.reset();
                    autoPopOffBlocksCount.set(0);
                    autoLastPopOffHeight.set(-1);
                    // Get block height from datbase
                    block = blockDb.findLastBlock();
                    blockchain.setLastBlock(block);
                    blockListeners.notify(block, Event.BLOCK_AUTO_POPPED);
                    stores.endTransaction();
                    autoPopOffState = PopOffState.IDLE;
                }
            }
        }
        return poppedOffBlocks;
    }

    private Block popLastBlock() {
        Block block = blockchain.getLastBlock();
        if (block.getId() == genesisBlockId) {
            throw new RuntimeException("Cannot pop off genesis block");
        }
        Block previousBlock = blockDb.findBlock(block.getPreviousBlockId());
        if (previousBlock == null) {
            logger.warn(
                    "Block {} at height {} has no parent block in database (id: {}). Deleting orphan block to restore chain continuity.",
                    block.getStringId(), block.getHeight(), Convert.toUnsignedLong(block.getPreviousBlockId()));

            // Delete the orphan block
            blockDb.deleteBlocksFrom(block.getId());

            // Find the new last block from DB
            previousBlock = blockDb.findLastBlock();
            if (previousBlock == null) {
                throw new RuntimeException("Recovery failed: No blocks left in database after deleting orphan.");
            }
            // Force set the last block, bypassing the check against the old last block
            blockchain.setLastBlock(previousBlock);

            List<Transaction> txs = block.getTransactions();
            txs.forEach(Transaction::unsetBlock);
            return previousBlock;
        }
        List<Transaction> txs = block.getTransactions();
        blockchain.setLastBlock(block, previousBlock);
        if (txs != null) {
            txs.forEach(Transaction::unsetBlock);
        }
        if (blockDb.hasBlock(block.getId())) {
            blockDb.deleteBlocksFrom(block.getId());
        }
        return previousBlock;
    }

    private int getBlockVersion() {
        return fluxCapacitor.getValue(FluxValues.SMART_FEES) ? 4 : 3;
    }

    private boolean preCheckUnconfirmedTransaction(TransactionDuplicatesCheckerImpl transactionDuplicatesChecker,
            UnconfirmedTransactionStore unconfirmedTransactionStore, Transaction transaction) {
        boolean ok = !transactionDuplicatesChecker.hasAnyDuplicate(transaction)
                && !transactionDb.hasTransaction(transaction.getId());
        if (!ok) {
            unconfirmedTransactionStore.remove(transaction);
        } else {
            ok = hasAllReferencedTransactions(transaction, transaction.getTimestamp(), 0);
        }
        return ok;
    }

    @Override
    public void generateBlock(String secretPhrase, byte[] publicKey, Long nonce) throws BlockNotAcceptedException {
        synchronized (downloadCache) {
            if (consistencyState.get() == ConsistencyState.INCONSISTENT) {
                logger.warn("block generation with an inconsistent database, might make you to mine alone in a fork");
            }
            downloadCache.lockCache(); // stop all incoming blocks.
            UnconfirmedTransactionStore unconfirmedTransactionStore = stores.getUnconfirmedTransactionStore();
            SortedSet<Transaction> orderedBlockTransactions = new TreeSet<>();

            int blockSize = fluxCapacitor.getValue(FluxValues.MAX_NUMBER_TRANSACTIONS);
            int payloadSize = fluxCapacitor.getValue(FluxValues.MAX_PAYLOAD_LENGTH);

            long totalAmountNqt = 0;
            long totalFeeNqt = 0;
            long totalFeeCashBackNqt = 0;
            long totalFeeBurntNqt = 0;
            int indirectsCount = 0;

            final Block previousBlock = blockchain.getLastBlock();
            final int blockTimestamp = timeService.getEpochTime();
            final int blockHeight = previousBlock.getHeight() + 1;

            // this is just an validation. which collects all valid transactions, which fit
            // into the block
            // finally all stuff is reverted so nothing is written to the db
            // the block itself with all transactions we found is pushed using pushBlock
            // which calls
            // accept (so it's going the same way like a received/synced block)
            AtBlock atBlock = null;
            try {
                stores.beginTransaction();

                final TransactionDuplicatesCheckerImpl transactionDuplicatesChecker = new TransactionDuplicatesCheckerImpl();

                ToLongFunction<Transaction> priorityCalculator = transaction -> {
                    int age = blockTimestamp + 1 - transaction.getTimestamp();
                    if (age < 0) {
                        age = 1;
                    }

                    long feePriority = transaction.getFeeNqt()
                            * (transaction.getSize() / Constants.ORDINARY_TRANSACTION_BYTES);
                    // So the age has less priority (60 minutes to increase the priority to the next
                    // level)
                    // TODO: consider giving priority based on the last sent transaction and not
                    // transaction age to improve spam protection
                    long priority = (feePriority * 60) + fluxCapacitor.getValue(FluxValues.FEE_QUANT) * age;

                    return priority;
                };

                // Map of slot number -> transaction
                Map<Long, Transaction> transactionsToBeIncluded;
                Stream<Transaction> inclusionCandidates = unconfirmedTransactionStore.getAll().stream()
                        .filter(transaction -> // Normal filtering
                        transaction.getVersion() == transactionProcessor
                                .getTransactionVersion(previousBlock.getHeight())
                                && transaction.getExpiration() >= blockTimestamp
                                && transaction.getTimestamp() <= blockTimestamp + MAX_TIMESTAMP_DIFFERENCE
                                && (!fluxCapacitor.getValue(FluxValues.AUTOMATED_TRANSACTION_BLOCK)
                                        || economicClustering.verifyFork(transaction)))
                        // ↓ Extra check for transactions that are to be considered
                        .filter(transaction -> preCheckUnconfirmedTransaction(transactionDuplicatesChecker,
                                unconfirmedTransactionStore, transaction));

                if (fluxCapacitor.getValue(FluxValues.PRE_POC2)
                        && !fluxCapacitor.getValue(FluxValues.SPEEDWAY)) {
                    // In this step we get all unconfirmed transactions and then sort them by slot,
                    // followed by priority
                    Map<Long, TreeMap<Long, Transaction>> unconfirmedTransactionsOrderedBySlotThenPriority = new HashMap<>();
                    inclusionCandidates.collect(Collectors.toMap(Function.identity(), priorityCalculator::applyAsLong))
                            .forEach((transaction, priority) -> {
                                long slot = (transaction.getFeeNqt() - (transaction.getFeeNqt() % FEE_QUANT_SIP3))
                                        / FEE_QUANT_SIP3;
                                slot = Math.min(fluxCapacitor.getValue(FluxValues.MAX_NUMBER_TRANSACTIONS),
                                        slot);
                                TreeMap<Long, Transaction> utxInSlot = unconfirmedTransactionsOrderedBySlotThenPriority
                                        .get(slot);
                                if (utxInSlot == null) {
                                    // Use a tree map in reverse order so we automatically get a descending priority
                                    // list
                                    utxInSlot = new TreeMap<>(Collections.reverseOrder());
                                    unconfirmedTransactionsOrderedBySlotThenPriority.put(slot, utxInSlot);
                                }
                                // if we already have this identical priority, make sure they are unique
                                while (utxInSlot.get(priority) != null) {
                                    priority--;
                                }
                                utxInSlot.put(priority, transaction);
                            });

                    // Fill the unconfirmed transactions to be included from top to bottom
                    Map<Long, Transaction> slotTransactionsToBeincluded = new HashMap<>();
                    int maxSlot = fluxCapacitor.getValue(FluxValues.MAX_NUMBER_TRANSACTIONS);
                    for (long slot = maxSlot; slot >= 1; slot--) {
                        boolean slotFilled = false;
                        for (long slotUnconfirmed = maxSlot; slotUnconfirmed >= slot; slotUnconfirmed--) {
                            // using a tree map we already have it naturally sorted by priority
                            TreeMap<Long, Transaction> candidateTxs = unconfirmedTransactionsOrderedBySlotThenPriority
                                    .get(slotUnconfirmed);
                            if (candidateTxs != null) {
                                Iterator<Transaction> itTx = candidateTxs.values().iterator();
                                while (itTx.hasNext()) {
                                    Transaction tx = itTx.next();

                                    slotTransactionsToBeincluded.put(slot, tx);
                                    itTx.remove();
                                    slotFilled = true;
                                    break;
                                }
                                if (slotFilled) {
                                    break;
                                }
                            }
                        }
                    }
                    transactionsToBeIncluded = slotTransactionsToBeincluded;
                } else {
                    // Just confirm transactions by the highest priority
                    Stream<Transaction> transactionsOrderedByPriority = inclusionCandidates
                            .sorted(new Comparator<Transaction>() {
                                @Override
                                public int compare(Transaction t1, Transaction t2) {
                                    return Long.compare(priorityCalculator.applyAsLong(t2),
                                            priorityCalculator.applyAsLong(t1));
                                }
                            });
                    Map<Long, Transaction> transactionsOrderedBySlot = new HashMap<>();
                    AtomicLong currentSlot = new AtomicLong(1);
                    transactionsOrderedByPriority
                            .forEach(tx -> { // This should do highest priority to lowest priority
                                transactionsOrderedBySlot.put(currentSlot.get(), tx);
                                currentSlot.incrementAndGet();
                            });
                    transactionsToBeIncluded = transactionsOrderedBySlot;
                }

                int maxIndirects = propertyService.getInt(Props.MAX_INDIRECTS_PER_BLOCK);
                long feeQuant = fluxCapacitor.getValue(FluxValues.FEE_QUANT);
                transactionService.startNewBlock();
                for (Map.Entry<Long, Transaction> entry : transactionsToBeIncluded.entrySet()) {
                    Transaction transaction = entry.getValue();

                    if (blockSize <= 0 || payloadSize <= 0) {
                        break;
                    } else if (transaction.getSize() > payloadSize) {
                        continue;
                    }

                    int txIndirects = transaction.getType().getIndirectIncomings(transaction).size();
                    if (indirectsCount + txIndirects > maxIndirects) {
                        // skip this transaction, max indirects per block reached
                        continue;
                    }
                    indirectsCount += txIndirects;

                    long slot = entry.getKey();
                    long slotFee = fluxCapacitor.getValue(FluxValues.PRE_POC2) ? slot * FEE_QUANT_SIP3
                            : ONE_SIGNA;
                    if (fluxCapacitor.getValue(FluxValues.SPEEDWAY)) {
                        // we already got the list by priority, no need to check the fees again
                        slotFee = feeQuant;
                    }
                    if (transaction.getFeeNqt() >= slotFee) {
                        if (transactionService.applyUnconfirmed(transaction)) {
                            try {
                                transactionService.validate(transaction);
                                payloadSize -= transaction.getSize();
                                totalAmountNqt = Convert.safeAdd(totalAmountNqt, transaction.getAmountNqt());
                                totalFeeNqt = Convert.safeAdd(totalFeeNqt, transaction.getFeeNqt());
                                if (fluxCapacitor.getValue(FluxValues.SMART_FEES, blockHeight)) {
                                    totalFeeCashBackNqt = Convert.safeAdd(totalFeeCashBackNqt, transaction.getFeeNqt()
                                            / propertyService.getInt(Props.CASH_BACK_FACTOR));
                                }
                                orderedBlockTransactions.add(transaction);
                                blockSize--;
                            } catch (SignumException.NotCurrentlyValidException e) {
                                transactionService.undoUnconfirmed(transaction);
                            } catch (SignumException.ValidationException e) {
                                unconfirmedTransactionStore.remove(transaction);
                                transactionService.undoUnconfirmed(transaction);
                            }
                        } else {
                            // Drop duplicates and transactions that cannot be applied
                            unconfirmedTransactionStore.remove(transaction);
                        }
                    }
                }

                if (subscriptionService.isEnabled()) {
                    subscriptionService.clearRemovals();
                    long subscriptionFeeNqt = subscriptionService.calculateFees(blockTimestamp, blockHeight);
                    totalFeeNqt = Convert.safeAdd(totalFeeNqt, subscriptionFeeNqt);
                    if (fluxCapacitor.getValue(FluxValues.SMART_FEES, blockHeight)) {
                        totalFeeBurntNqt = Convert.safeAdd(totalFeeBurntNqt, subscriptionFeeNqt);
                    }
                }

                // ATs for block - MUST be called while temporary balance changes are still in
                // the DB
                long generatorId = Account.getId(publicKey);
                atService.clearPending(blockHeight, generatorId);
                atBlock = atService.getCurrentBlockATs(payloadSize, blockHeight, generatorId, indirectsCount);
            } catch (Exception e) {
                stores.rollbackTransaction();
                throw e;
            } finally {
                stores.rollbackTransaction();
                stores.endTransaction();
            }

            byte[] byteAts = atBlock != null ? atBlock.getBytesForBlock() : null;

            // digesting AT Bytes
            if (byteAts != null) {
                payloadSize -= byteAts.length;
                totalFeeNqt = Convert.safeAdd(totalFeeNqt, atBlock.getTotalFees());
                if (fluxCapacitor.getValue(FluxValues.SMART_FEES, blockHeight)) {
                    totalFeeBurntNqt = Convert.safeAdd(totalFeeBurntNqt, atBlock.getTotalFees());
                }
                totalAmountNqt = Convert.safeAdd(totalAmountNqt, atBlock.getTotalAmount());
            }

            // ATs for block

            MessageDigest digest = Crypto.sha256();
            orderedBlockTransactions.forEach(transaction -> digest.update(transaction.getBytes()));
            byte[] payloadHash = digest.digest();
            byte[] generationSignature = generator.calculateGenerationSignature(
                    previousBlock.getGenerationSignature(), previousBlock.getGeneratorId());
            Block block;
            byte[] previousBlockHash = Crypto.sha256().digest(previousBlock.getBytes());
            try {
                block = new Block(getBlockVersion(), blockTimestamp,
                        previousBlock.getId(), totalAmountNqt, totalFeeNqt, totalFeeCashBackNqt, totalFeeBurntNqt,
                        fluxCapacitor.getValue(FluxValues.MAX_PAYLOAD_LENGTH) - payloadSize, payloadHash,
                        publicKey,
                        generationSignature, null, previousBlockHash, new ArrayList<>(orderedBlockTransactions), nonce,
                        byteAts, previousBlock.getHeight(), Constants.INITIAL_BASE_TARGET,
                        fluxCapacitor);
            } catch (SignumException.ValidationException e) {
                // shouldn't happen because all transactions are already validated
                logger.info("Error generating block", e);
                return;
            }
            block.sign(secretPhrase);
            blockService.setPrevious(block, previousBlock);
            try {
                blockService.preVerify(block, previousBlock);
                pushBlock(block);
                blockListeners.notify(block, Event.BLOCK_GENERATED);
                if (logger.isDebugEnabled()) {
                    logger.debug("Account {} generated block {} at height {}",
                            Convert.toUnsignedLong(block.getGeneratorId()), block.getStringId(), block.getHeight());
                }
                downloadCache.resetCache();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (TransactionNotAcceptedException e) {
                logger.debug("Generate block failed: {}", e.getMessage());
                Transaction transaction = e.getTransaction();
                logger.debug("Removing invalid transaction: {}", transaction.getStringId());
                unconfirmedTransactionStore.remove(transaction);
                throw e;
            } catch (BlockNotAcceptedException e) {
                logger.debug("Generate block failed: {}", e.getMessage());
                throw e;
            }
        } // end synchronized cache
    }

    private boolean hasAllReferencedTransactions(Transaction transaction, int timestamp, int count) {
        // TODO: consider cleaning this method after the upgrade.
        if (transaction.getReferencedTransactionFullHash() == null) {
            if (fluxCapacitor.getValue(FluxValues.SPEEDWAY)) {
                return true;
            }
            return timestamp - transaction.getTimestamp() < 60 * 1440 * 60 && count < 10;
        }
        transaction = transactionDb.findTransactionByFullHash(transaction.getReferencedTransactionFullHash());
        if (!subscriptionService.isEnabled() && transaction != null && transaction.getSignature() == null) {
            transaction = null;
        }
        if (fluxCapacitor.getValue(FluxValues.SPEEDWAY)) {
            // No need to go deeper checking, if it is on the DB and confirmed already
            return transaction != null;
        }
        return transaction != null && hasAllReferencedTransactions(transaction, timestamp, count + 1);
    }
}
