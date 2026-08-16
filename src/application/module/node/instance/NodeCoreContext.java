package application.module.node.instance;

import application.module.node.BlockchainImpl;
import application.module.node.BlockchainProcessor;
import application.module.node.DebugTrace;
import application.module.node.EconomicClustering;
import application.module.node.Generator;
import application.module.node.NodeComponentFactory;
import application.module.node.ShutdownManager;
import application.module.node.TransactionApplyContext;
import application.module.node.TransactionProcessorImpl;
import application.module.node.TransactionType;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.assetexchange.AssetExchangeImpl;
import application.module.node.at.AtConstants;
import application.module.node.at.AtController;
import application.module.node.at.AT;
import application.module.node.at.ATProcessorCache;
import application.module.node.at.ATProcessingContext;
import application.module.node.db.BlockDb;
import application.module.node.db.TransactionDb;
import application.module.node.db.cache.DBCacheManagerImpl;
import application.module.node.db.sql.Db;
import application.module.node.db.sql.DbContext;
import application.module.node.db.sql.SqlATStore;
import application.module.node.db.sql.StoreDependencies;
import application.module.node.db.store.AliasStore;
import application.module.node.db.store.BlockchainStore;
import application.module.node.db.store.Dbs;
import application.module.node.db.store.DerivedTableManager;
import application.module.node.db.store.Stores;
import application.module.node.deeplink.DeeplinkQRCodeGenerator;
import application.module.node.feesuggestions.FeeSuggestionCalculator;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxCapacitorImpl;
import application.module.node.peer.PeerManager;
import application.module.node.props.Props;
import application.module.node.props.PropertyService;
import application.module.node.services.AccountService;
import application.module.node.services.AliasService;
import application.module.node.services.BlockService;
import application.module.node.services.DGSGoodsStoreService;
import application.module.node.services.EscrowService;
import application.module.node.services.IndirectIncomingService;
import application.module.node.services.ParameterService;
import application.module.node.services.SubscriptionService;
import application.module.node.services.TimeService;
import application.module.node.services.TransactionService;
import application.module.node.services.impl.AccountServiceImpl;
import application.module.node.services.impl.AliasServiceImpl;
import application.module.node.at.ATServiceImpl;
import application.module.node.services.impl.BlockServiceImpl;
import application.module.node.services.impl.DGSGoodsStoreServiceImpl;
import application.module.node.services.impl.EscrowServiceImpl;
import application.module.node.services.impl.IndirectIncomingServiceImpl;
import application.module.node.services.impl.ParameterServiceImpl;
import application.module.node.services.impl.SubscriptionServiceImpl;
import application.module.node.services.impl.TimeServiceImpl;
import application.module.node.services.impl.TransactionServiceImpl;
import application.module.node.statistics.StatisticsManagerImpl;
import application.module.node.unconfirmedtransactions.UnconfirmedTransactionStore;
import application.module.node.util.DownloadCacheImpl;
import application.module.node.util.ThreadPool;
import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.APITransactionManagerImpl;
import application.module.node.web.server.WebServer;
import application.module.node.web.server.WebServerContext;
import application.module.node.web.server.WebServerImpl;
import application.utils.logging.ProfileLoggingApplier;
import application.utils.logging.ProfileThreadContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import signum.net.NetworkParameters;
import signumj.util.SignumUtils;

/**
 * Encapsulates all instance-scoped state for a single running Signum node.
 * <p>
 * Previously, these components lived as {@code static} fields inside
 * {@link application.module.node.Signum Signum}, which prevented multi-profile
 * operation and made unit testing impossible. {@code NodeCoreContext} moves
 * every runtime component into an instance-scope so that multiple profiles
 * (mainnet, testnet, mock) can coexist side-by-side with fully isolated
 * resource pools.
 * </p>
 *
 * <h2>Lifecycle</h2>
 * <pre>{@code
 * // Build via Builder pattern (see NodeCoreContextBuilder)
 * NodeCoreContext ctx = new NodeCoreContextBuilder("mainnet", confPath).build();
 * ctx.start();        // initialise all sub-components
 * // ... node is running ...
 * ctx.stop();         // graceful teardown
 * }</pre>
 *
 * <h2>Thread-safety</h2>
 * Individual components carry their own thread-safety guarantees. The context
 * itself publishes references through final fields so callers see a stable
 * snapshot after {@link #start()} completes.
 *
 * @since 4.0
 */
public final class NodeCoreContext {

    private static final Logger logger = LoggerFactory.getLogger(NodeCoreContext.class);

    // =========================================================================
    // Identity & configuration
    // =========================================================================

    /** Human-readable profile name (e.g. "mainnet", "testnet"). */
    private final String profileName;

    /** Base configuration folder for this instance. */
    private final Path confFolder;

    /** Resolved properties for this profile. */
    private final PropertyService propertyService;

    // =========================================================================
    // Infrastructure components
    // =========================================================================

    /** Per-instance database context with isolated connection pool. */
    private DbContext dbContext;

    /** Per-instance database connection pool manager. */
    private DBCacheManagerImpl dbCacheManager;

    /** Per-instance Stores (data access layer). */
    private Stores stores;

    /** Per-instance database type wrapper. */
    private Dbs dbs;

    /** Derived table manager for cached/computed tables. */
    private DerivedTableManager derivedTableManager;

    /** Statistics manager for DB metrics tracking. */
    private StatisticsManagerImpl statisticsManager;

    /** Dedicated thread pool for background tasks. */
    private ThreadPool threadPool;

    /** Web server (REST + WebSocket). */
    private WebServer webServer;

    /** Tracks graceful shutdown progress. */
    private ShutdownManager shutdownManager;

    // =========================================================================
    // Network & Mining
    // =========================================================================

    /** Resolved NetworkParameters for this profile (null if not configured). */
    private NetworkParameters networkParameters;

    /** Block download cache. */
    private DownloadCacheImpl downloadCache;

    /** Economic clustering for transaction prioritization. */
    private EconomicClustering economicClustering;

    // =========================================================================
    // Blockchain components
    // =========================================================================

    /** Core blockchain implementation. */
    private BlockchainImpl blockchain;

    /** Processes incoming blocks. */
    private BlockchainProcessor blockchainProcessor;

    /** Validates and propagates transactions. */
    private TransactionProcessorImpl transactionProcessor;

    /** Block mining / generation strategy. */
    private Generator generator;

    /** Flux capacitor (epoch / milestone tracking). */
    private FluxCapacitor fluxCapacitor;

    // =========================================================================
    // Service layer
    // =========================================================================

    private AccountService accountService;
    private AliasService aliasService;
    private ATServiceImpl atService;
    private BlockService blockService;
    private TransactionService transactionService;
    private SubscriptionService subscriptionService;
    private AssetExchange assetExchange;
    private DGSGoodsStoreService digitalGoodsStoreService;
    private EscrowService escrowService;
    private IndirectIncomingService indirectIncomingService;
    private ParameterService parameterService;
    private TimeService timeService;

    // =========================================================================
    // Web/API components
    // =========================================================================

    private APITransactionManager apiTransactionManager;
    private FeeSuggestionCalculator feeSuggestionCalculator;
    private DeeplinkQRCodeGenerator deeplinkQRCodeGenerator;

    /**
     * Immutable snapshot of all services required for transaction type processing.
     * Provides context-aware access to eliminate static Signum.getXxx() calls.
     */
    private TransactionApplyContext transactionApplyContext;

    /** AT constants instance, shared across Web/API and store layers. */
    private AtConstants atConstants;

    /** AT processor cache (instance-scoped to eliminate static Db polling). */
    private ATProcessorCache atProcessorCache;

    /** Immutable context holder for AT processing dependencies. */
    private ATProcessingContext atProcessingContext;

    /** Per-profile peer manager for isolated peer networking. */
    private PeerManager peerManager;

    private DebugTrace debugTrace;

    // =========================================================================
    // Lifecycle flags
    // =========================================================================

    /** True once {@link #start()} has completed successfully. */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /** True when a stop sequence is in progress or finished. */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    // =========================================================================
    // Construction (use NodeCoreContextBuilder)
    // =========================================================================

    /**
     * Package-private constructor; use {@link NodeCoreContextBuilder} instead.
     */
    NodeCoreContext(String profileName, Path confFolder, PropertyService propertyService) {
        this.profileName = profileName;
        this.confFolder = confFolder;
        this.propertyService = propertyService;
    }

    // =========================================================================
    // Lifecycle operations
    // =========================================================================

    /**
     * Starts all node components. Delegates to the internal initialisation
     * routine that was previously {@code Signum.loadWallet()}.
     *
     * @throws NodeStartupException if any component fails to initialise
     */
    public void start() throws NodeStartupException {
        if (!started.compareAndSet(false, true)) {
            throw new NodeStartupException(
                    "Context '" + profileName + "' is already started");
        }
        stopped.set(false);
        try {
            doInitialize();
        } catch (NodeStartupException e) {
            started.set(false);
            throw e;
        } catch (Exception e) {
            started.set(false);
            throw new NodeStartupException(
                    "Failed to start node profile '" + profileName + "'", e);
        }
    }

    /**
     * Gracefully stops all node components in reverse-dependency order.
     */
    public void stop() {
        if (stopped.get()) {
            return; // idempotent
        }
        stopped.set(true);
        doShutdown();
    }

    /**
     * Re-initializes and restarts the core services.
     */
    public void restart() {
        stop();
        started.set(false);
        start();
    }

    // =========================================================================
    // Internal bootstrap / teardown delegates
    // =========================================================================

    /**
     * Bootstrap logic extracted from {@code Signum.loadWallet()}.
     * Phase 9.1: Database Foundation - migrates DB-init components here.
     * Phase 9.2: Blockchain Core - migrates blockchain + core services here.
     * Phase 9.3: Services & Hooks - migrates remaining services here.
     */
    private void doInitialize() {
        // ── Step 1: Apply per-profile logging configuration ──
        ProfileLoggingApplier.apply(confFolder.toString(), profileName);

        // ── Step 2: Tag current thread with module+profile for log routing ──
        ProfileThreadContext.setContext("node", profileName);

        // ── Step 2.5: Create profile-scoped ShutdownManager ──
        this.shutdownManager = new ShutdownManager(this.propertyService, this.profileName);

        // ── Step 3: Database Foundation (migrated from Signum.loadWallet) ──
        try {
            initDatabaseFoundation();
        } catch (NodeStartupException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NodeStartupException(
                    "Database initialization failed for profile '" + profileName + "'", e);
        }

        // ── Step 3.5: Blockchain Core (migrated from Signum.loadWallet lines 507-556) ──
        try {
            initBlockchainCore();
        } catch (NodeStartupException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NodeStartupException(
                    "Blockchain core initialization failed for profile '" + profileName + "'", e);
        }

        // ── Step 3.75: Services & Hooks (migrated from Signum.loadWallet lines 558-644) ──
        try {
            initServicesAndHooks();
        } catch (NodeStartupException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NodeStartupException(
                    "Services initialization failed for profile '" + profileName + "'", e);
        }

        // ── Step 4: Log initialization summary ──
        long initTime = java.lang.System.currentTimeMillis();
        logger.info("Node profile '{}' initialization completed.", profileName);
        logger.info("Running network: {}", propertyService.getString(Props.NETWORK_NAME));
    }

    /**
     * Initializes database foundation components locally.
     * Migrated from Signum.loadWallet() lines 456-505.
     */
    private void initDatabaseFoundation() {
        // ensureDatabaseDirectory for SQLite configs
        ensureDatabaseDirectory(this.propertyService);

        // Load NetworkParameters if configured
        String networkParametersClass = this.propertyService.getString(Props.NETWORK_PARAMETERS);
        if (networkParametersClass != null && !networkParametersClass.trim().isEmpty()
                && !"null".equalsIgnoreCase(networkParametersClass)) {
            try {
                this.networkParameters = (NetworkParameters) Class
                        .forName(networkParametersClass)
                        .getConstructor()
                        .newInstance();
                this.propertyService.setNetworkParameters(this.networkParameters);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to load network parameters class: " + networkParametersClass, e);
            }
        }

        // Address prefix configuration (Signum.loadWallet line 486-489)
        SignumUtils.setAddressPrefix(this.propertyService.getString(Props.ADDRESS_PREFIX));
        SignumUtils.addAddressPrefix("BURST");
        SignumUtils.setValueSuffix(this.propertyService.getString(Props.VALUE_SUFIX));

        // TimeService (line 491)
        this.timeService = new TimeServiceImpl();

        // DerivedTableManager (line 493)
        this.derivedTableManager = new DerivedTableManager();

        // StatisticsManager + DBCacheManager (lines 495-496)
        this.statisticsManager = new StatisticsManagerImpl(this.timeService);
        this.dbCacheManager = new DBCacheManagerImpl(this.statisticsManager);

        // ThreadPool (line 498)
        this.threadPool = new ThreadPool(this.propertyService);

        // Db.init + Dbs + Stores (lines 500-505)
        // Phase 9.5: Pure instance factory — no static Db.activeContext pollution
        this.dbContext = DbContext.create(this.propertyService, this.dbCacheManager);
        this.dbs = this.dbContext.getDbsByDatabaseType();

        // Bridge pattern: set static activeContext for legacy code paths that still use
        // Db.getConnection() statically (e.g., BlockchainProcessorImpl constructor).
        // This is a temporary measure until all static Db refs are migrated to instance access.
        Db.setActiveContext(this.dbContext);

        TransactionDb transactionDb = dbs.getTransactionDb();
        BlockDb blockDb = dbs.getBlockDb();

        StoreDependencies storeDeps = new StoreDependencies(null, this.propertyService, null, this.dbs, this.dbContext);
        this.stores = new Stores(this.derivedTableManager, this.dbCacheManager, this.timeService,
                this.propertyService, transactionDb, blockDb, this.networkParameters, storeDeps);
    }

    /**
     * Initializes blockchain core components locally.
     * Migrated from Signum.loadWallet() lines 507-556.
     */
    private void initBlockchainCore() {
        final TransactionDb transactionDb = this.dbs.getTransactionDb();
        final BlockDb blockDb = this.dbs.getBlockDb();
        final BlockchainStore blockchainStore = this.stores.getBlockchainStore();

        // BlockchainImpl via factory (package-private constructor)
        // Signum.loadWallet lines 510-514
        this.blockchain = NodeComponentFactory.createBlockchain(
                transactionDb,
                blockDb,
                blockchainStore,
                this.propertyService);

        // Set blockchain reference in unconfirmed transaction store (line 516)
        UnconfirmedTransactionStore unconfirmedTransactionStore = this.stores.getUnconfirmedTransactionStore();
        unconfirmedTransactionStore.setBlockchain(this.blockchain);

        // AliasService + FluxCapacitor + addDefaultTLDs (lines 518-520)
        this.fluxCapacitor = new FluxCapacitorImpl(this.blockchain, this.propertyService);
        this.aliasService = new AliasServiceImpl(
                this.stores.getAliasStore(),
                this.stores,
                this.fluxCapacitor,
                this.propertyService);
        this.aliasService.addDefaultTLDs();

        // EconomicClustering - instance-scoped (P3.7: eliminates Signum.getFluxCapacitor() calls)
        this.economicClustering = new EconomicClustering(this.blockchain, this.fluxCapacitor);

        // AccountService (lines 524-525)
        this.accountService = new AccountServiceImpl(
                this.stores.getAccountStore(),
                this.stores.getAssetTransferStore(),
                this.blockchain);

        // DownloadCache (lines 527-530)
        this.downloadCache = new DownloadCacheImpl(
                this.propertyService,
                this.fluxCapacitor,
                this.blockchain);

        // Generator via factory - normal or mock (lines 532-544)
        boolean mockMining = this.propertyService.getBoolean(Props.DEV_MOCK_MINING);
        this.generator = NodeComponentFactory.createGenerator(
                mockMining,
                this.propertyService,
                this.blockchain,
                this.accountService,
                this.timeService,
                this.fluxCapacitor,
                this.downloadCache);

        // TransactionService (line 546)
        this.transactionService = new TransactionServiceImpl(
                this.accountService,
                this.blockchain);

        // TransactionProcessor (lines 548-556)
        // FIX: Added this.fluxCapacitor as 9th parameter before this.threadPool
        this.transactionProcessor = new TransactionProcessorImpl(
                this.propertyService,
                this.economicClustering,
                this.blockchain,
                this.stores,
                this.timeService,
                this.dbs,
                this.accountService,
                this.transactionService,
                this.fluxCapacitor,
                this.threadPool);
    }

    /**
     * Initializes remaining services and blockchain hooks locally.
     * Migrated from Signum.loadWallet() lines 558-644.
     */
    private void initServicesAndHooks() {
        final TransactionDb transactionDb = this.dbs.getTransactionDb();
        final BlockDb blockDb = this.dbs.getBlockDb();
        final BlockchainStore blockchainStore = this.stores.getBlockchainStore();

        // ATService (line 558)
        this.atService = new ATServiceImpl(this.stores.getAtStore());

        // ATProcessorCache - instance-scoped with injected DbContext + Dbs (eliminates static Db polling)
        this.atProcessorCache = new ATProcessorCache(
                this.propertyService,
                this.stores.getAtStore(),
                this.dbContext,
                this.dbs);
        ATProcessorCache.setInstance(this.atProcessorCache);

        // Create AtConstants EARLY so it's available for SqlATStore wiring
        this.atConstants = new AtConstants(this.fluxCapacitor);

        // Wire AtConstants into SqlATStore (for getOrderedATs fee calculations)
        ((SqlATStore) this.stores.getAtStore()).setAtConstants(this.atConstants);

        // Initialize static AT module references with injected AtConstants
        AtController.setAtConstants(this.atConstants);

        // SubscriptionService (lines 559-566)
        AliasStore aliasStore = this.stores.getAliasStore();
        this.subscriptionService = new SubscriptionServiceImpl(
                this.stores.getSubscriptionStore(),
                transactionDb,
                this.blockchain,
                this.fluxCapacitor,
                this.aliasService,
                aliasStore,
                this.accountService);
        // Wire subscription service back into AliasServiceImpl (avoids circular dependency)
        ((AliasServiceImpl) this.aliasService).setSubscriptionService(this.subscriptionService);

        // DGSGoodsStoreService (lines 567-570)
        this.digitalGoodsStoreService = new DGSGoodsStoreServiceImpl(
                this.blockchain,
                this.stores.getDigitalGoodsStoreStore(),
                this.accountService);

        // EscrowService (lines 571-576)
        this.escrowService = new EscrowServiceImpl(
                this.stores.getEscrowStore(),
                this.blockchain,
                this.aliasService,
                this.accountService,
                transactionDb);

        // AssetExchange (lines 578-584)
        this.assetExchange = new AssetExchangeImpl(
                this.blockchain,
                this.accountService,
                this.stores.getTradeStore(),
                this.stores.getAccountStore(),
                this.stores.getAssetTransferStore(),
                this.stores.getAssetStore(),
                this.stores.getOrderStore());

        // IndirectIncomingService (lines 586-587)
        this.indirectIncomingService = new IndirectIncomingServiceImpl(
                this.stores.getIndirectIncomingStore(),
                this.propertyService);

        // Create ATProcessingContext AFTER AssetExchange is initialized
        this.atProcessingContext = new ATProcessingContext(
                this.atConstants,
                this.atProcessorCache,
                this.propertyService,
                this.fluxCapacitor,
                this.blockchain,
                this.stores.getAtStore(),
                this.stores.getAccountStore(),
                this.accountService,
                this.assetExchange,
                this.stores.getIndirectIncomingStore(),
                this.stores.getAssetStore());

        // BlockService (lines 599-605)
        NetworkParameters params = this.networkParameters;
        this.blockService = new BlockServiceImpl(
                this.accountService,
                this.transactionService,
                this.blockchain,
                this.downloadCache,
                this.generator,
                this.fluxCapacitor,
                this.propertyService,
                params);

        // BlockchainProcessor (lines 606-628) - 21 parameters!
        // Using NodeComponentFactory since BlockchainProcessorImpl has package-private constructor
        this.blockchainProcessor = NodeComponentFactory.createBlockchainProcessor(
                this.threadPool,
                this.blockService,
                this.transactionProcessor,
                this.blockchain,
                this.propertyService,
                this.subscriptionService,
                this.timeService,
                this.derivedTableManager,
                blockDb,
                transactionDb,
                this.economicClustering,
                blockchainStore,
                this.stores,
                this.escrowService,
                this.transactionService,
                this.downloadCache,
                this.generator,
                this.statisticsManager,
                this.dbCacheManager,
                this.accountService,
                this.indirectIncomingService,
                this.aliasService,
                this.fluxCapacitor,
                this.atService);

        // Wire minRollbackHeight supplier to DerivedTableManager (eliminates last Signum static ref)
        this.derivedTableManager.setMinRollbackHeightSupplier(this.blockchainProcessor::getMinRollbackHeight);

        // downloadCache.setBlockchainProcessor(blockchainProcessor) (line 630)
        this.downloadCache.setBlockchainProcessor(this.blockchainProcessor);

        // generator.generateForBlockchainProcessor(threadPool, blockchainProcessor) (line 632)
        this.generator.generateForBlockchainProcessor(
                this.threadPool, this.blockchainProcessor);

        // DeeplinkQRCodeGenerator (line 634)
        this.deeplinkQRCodeGenerator = new DeeplinkQRCodeGenerator();

        // ParameterService (lines 636-644)
        this.parameterService = new ParameterServiceImpl(
                this.accountService,
                this.aliasService,
                this.assetExchange,
                this.digitalGoodsStoreService,
                this.blockchain,
                this.blockchainProcessor,
                this.transactionProcessor,
                this.atService);

        // addBlockchainListeners (lines 646-651) - inline migration
        AT.HandleATBlockTransactionsListener handleAtBlockTransactionListener =
                new AT.HandleATBlockTransactionsListener(
                        this.atProcessingContext,
                        transactionDb);
        this.blockchainProcessor.addListener(
                handleAtBlockTransactionListener,
                BlockchainProcessor.Event.AFTER_BLOCK_APPLY);

        DGSGoodsStoreServiceImpl.ExpiredPurchaseListener expiredPurchaseListener =
                new DGSGoodsStoreServiceImpl.ExpiredPurchaseListener(
                        this.accountService,
                        this.digitalGoodsStoreService);
        this.blockchainProcessor.addListener(
                expiredPurchaseListener,
                BlockchainProcessor.Event.AFTER_BLOCK_APPLY);

        // APITransactionManager – injected dependencies eliminate static Signum calls
        this.apiTransactionManager = new APITransactionManagerImpl(
                this.parameterService,
                this.transactionProcessor,
                this.blockchain,
                this.accountService,
                this.transactionService,
                this.propertyService,
                this.fluxCapacitor);

        // PeerManager
        this.peerManager = new PeerManager(
                this.propertyService,
                this.blockchain,
                this.threadPool,
                this.timeService);
        this.peerManager.start(
                this.timeService,
                this.accountService,
                this.blockchain,
                this.transactionProcessor,
                this.blockchainProcessor,
                this.propertyService,
                this.threadPool);

        // params.initialize() + TransactionType.setNetworkParameters() - STATIC CALLS (lines 668-671)
        if (this.networkParameters != null) {
            this.networkParameters.initialize(
                    this.parameterService,
                    this.accountService,
                    this.apiTransactionManager);
            TransactionType.setNetworkParameters(this.networkParameters);
        }

        // FeeSuggestionCalculator (lines 673-677)
        this.feeSuggestionCalculator = new FeeSuggestionCalculator(
                this.blockchainProcessor,
                this.stores.getUnconfirmedTransactionStore(),
                this.blockchain,
                this.fluxCapacitor);

        // WebServerImpl + start() (lines 679-703)
        this.webServer = new WebServerImpl(new WebServerContext(
                this.transactionProcessor,
                this.blockchain,
                this.blockchainProcessor,
                this.parameterService,
                this.accountService,
                this.aliasService,
                this.assetExchange,
                this.escrowService,
                this.digitalGoodsStoreService,
                this.subscriptionService,
                this.atService,
                this.timeService,
                this.economicClustering,
                this.propertyService,
                this.threadPool,
                this.transactionService,
                this.blockService,
                this.generator,
                this.apiTransactionManager,
                this.feeSuggestionCalculator,
                this.deeplinkQRCodeGenerator,
                this.indirectIncomingService,
                this.networkParameters,
                this.atConstants,
                this.fluxCapacitor,
                this.stores));
        try {
            this.webServer.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start WebServer", e);
        }

        // ThreadPool.start() with timewarp (lines 709-723)
        boolean offline = this.propertyService.getBoolean(Props.DEV_OFFLINE);
        int timeMultiplier = offline
                ? Math.max(this.propertyService.getInt(Props.DEV_TIMEWARP), 1)
                : 1;
        this.threadPool.start(timeMultiplier);

        if (timeMultiplier > 1) {
            this.timeService.setTime(new application.module.node.util.Time.FasterTime(
                    Math.max(
                            this.timeService.getEpochTime(),
                            this.blockchain.getLastBlock().getTimestamp()),
                    timeMultiplier));
            logger.info("TIME WILL FLOW {} TIMES FASTER!", timeMultiplier);
        }

        // Create TransactionApplyContext with all services wired up
        this.transactionApplyContext = new TransactionApplyContext(
                this.blockchain,
                this.fluxCapacitor,
                this.accountService,
                this.digitalGoodsStoreService,
                this.aliasService,
                this.assetExchange,
                this.subscriptionService,
                this.escrowService,
                this.propertyService,
                this.stores.getAtStore(),
                this.atConstants);

        // Wire TransactionApplyContext into TransactionType
        TransactionType.setContext(this.transactionApplyContext);

        // DebugTrace (Phase C) - instance-scoped account tracing
        // V4.1: blockchain passed to eliminate Signum.getBlockchain() static call
        this.debugTrace = DebugTrace.create(
                this.propertyService,
                this.blockchainProcessor,
                this.accountService,
                this.assetExchange,
                this.digitalGoodsStoreService,
                this.blockchain);
    }

    /**
     * Ensures the database directory exists for SQLite-based configurations.
     * Handles file URI resolution and parameter stripping.
     * Migrated from Signum.ensureDatabaseDirectory().
     */
    private void ensureDatabaseDirectory(PropertyService props) {
        String dbUrl = props.getString(Props.DB_URL);
        if (dbUrl == null || !dbUrl.toLowerCase().startsWith("jdbc:sqlite:")) {
            return;
        }

        String pathPart = dbUrl.substring("jdbc:sqlite:".length());

        // Handle file URIs per RFC 2396
        if (pathPart.toLowerCase().startsWith("file:")) {
            pathPart = pathPart.substring(5);
            if (pathPart.startsWith("///")) {
                pathPart = pathPart.substring(2);
            } else if (pathPart.startsWith("//") && !pathPart.startsWith("//", 2)) {
                pathPart = pathPart.substring(2);
            }
        }

        // Skip in-memory or special databases
        if (pathPart.isEmpty() || pathPart.equalsIgnoreCase(":memory:") || pathPart.startsWith(":")) {
            return;
        }

        // Strip parameters (e.g. ?cache=shared)
        int queryIdx = pathPart.indexOf('?');
        if (queryIdx != -1) {
            pathPart = pathPart.substring(0, queryIdx);
        }

        try {
            Path dbPath = application.utils.io.PathUtils.resolvePath(pathPart);
            Path parent = dbPath.getParent();
            if (parent != null && Files.notExists(parent)) {
                Files.createDirectories(parent);
                logger.info("Created missing database directory: {}", parent.toAbsolutePath());
            }
        } catch (Exception e) {
            logger.warn("Failed to ensure database directory exists: {}", e.getMessage());
        }
    }


    /**
     * Teardown logic - stops all local components in reverse-dependency order.
     * Reverse of initialization order: WebServer → BlockchainProcessor → Peers → ThreadPool → DBCacheManager → Db.
     */
    private void doShutdown() {
        try {
            // 1. Shutdown WebServer
            if (this.webServer != null) {
                try {
                    this.webServer.shutdown();
                } catch (Throwable t) {
                    if (this.shutdownManager != null) {
                        this.shutdownManager.markFailure("WebServer");
                    }
                    logger.error("Error shutting down WebServer for profile '{}'", profileName, t);
                }
            }

            // 2. Shutdown BlockchainProcessor
            if (this.blockchainProcessor != null) {
                try {
                    this.blockchainProcessor.shutdown();
                } catch (Throwable t) {
                    if (this.shutdownManager != null) {
                        this.shutdownManager.markFailure("BlockchainProcessor");
                    }
                    logger.error("Error shutting down BlockchainProcessor for profile '{}'", profileName, t);
                }
            }

            // 3. Shutdown PeerManager (instance-scoped, Phase 10.5)
            if (this.peerManager != null) {
                try {
                    this.peerManager.shutdown(this.threadPool);
                } catch (Throwable t) {
                    if (this.shutdownManager != null) {
                        this.shutdownManager.markFailure("PeerManager");
                    }
                    logger.error("Error shutting down PeerManager for profile '{}'", profileName, t);
                }
            }

            // 4. Shutdown ThreadPool
            if (this.threadPool != null) {
                try {
                    this.threadPool.shutdown();
                } catch (Throwable t) {
                    if (this.shutdownManager != null) {
                        this.shutdownManager.markFailure("ThreadPool");
                    }
                    logger.error("Error shutting down ThreadPool for profile '{}'", profileName, t);
                }
            }

            // 5. Close DBCacheManager
            if (this.dbCacheManager != null) {
                try {
                    this.dbCacheManager.close();
                } catch (Throwable t) {
                    if (this.shutdownManager != null) {
                        this.shutdownManager.markFailure("DBCacheManager");
                    }
                    logger.error("Error closing DBCacheManager for profile '{}'", profileName, t);
                }
            }

            // 6. Shutdown Database — instance-scoped (not static Db.shutdown())
            try {
                if (this.dbContext != null) {
                    this.dbContext.shutdown();
                }
            } catch (Throwable t) {
                if (this.shutdownManager != null) {
                    this.shutdownManager.markFailure("Database");
                }
                logger.error("Error shutting down DB for profile '{}'", profileName, t);
            }

            // 7. Signal shutdown complete
            if (this.shutdownManager != null) {
                this.shutdownManager.finishShutdown();
            }

            logger.info("Node profile '{}' stopped.", profileName);
        } catch (Exception e) {
            logger.error("Unexpected error during shutdown of profile '{}'", profileName, e);
        } finally {
            started.set(false);
            // Clear MDC routing context so orphaned events are not misrouted.
            ProfileThreadContext.clear();
        }
    }

    // =========================================================================
    // Public getters (replaced static Signum.getXxx() calls)
    // =========================================================================

    public String getProfileName() {
        return profileName;
    }

    public Path getConfFolder() {
        return confFolder;
    }

    public PropertyService getPropertyService() {
        return propertyService;
    }

    public BlockchainImpl getBlockchain() {
        return blockchain;
    }

    public BlockchainProcessor getBlockchainProcessor() {
        return blockchainProcessor;
    }

    public TransactionProcessorImpl getTransactionProcessor() {
        return transactionProcessor;
    }

    public Generator getGenerator() {
        return generator;
    }

    public FluxCapacitor getFluxCapacitor() {
        return fluxCapacitor;
    }

    public Stores getStores() {
        return stores;
    }

    public Dbs getDbs() {
        return dbs;
    }

    /**
     * Returns the per-instance {@link DbContext}.
     *
     * @return the database context for this node instance
     */
    public DbContext getDbContext() {
        return dbContext;
    }

    public ThreadPool getThreadPool() {
        return threadPool;
    }

    public WebServer getWebServer() {
        return webServer;
    }

    public AccountService getAccountService() {
        return accountService;
    }

    public AliasService getAliasService() {
        return aliasService;
    }

    public ATServiceImpl getAtService() {
        return atService;
    }

    public BlockService getBlockService() {
        return blockService;
    }

    public TransactionService getTransactionService() {
        return transactionService;
    }

    public SubscriptionService getSubscriptionService() {
        return subscriptionService;
    }

    public AssetExchange getAssetExchange() {
        return assetExchange;
    }

    public DGSGoodsStoreService getDigitalGoodsStoreService() {
        return digitalGoodsStoreService;
    }

    public EscrowService getEscrowService() {
        return escrowService;
    }

    public IndirectIncomingService getIndirectIncomingService() {
        return indirectIncomingService;
    }

    public ParameterService getParameterService() {
        return parameterService;
    }

    public APITransactionManager getApiTransactionManager() {
        return apiTransactionManager;
    }

    public FeeSuggestionCalculator getFeeSuggestionCalculator() {
        return feeSuggestionCalculator;
    }

    public ShutdownManager getShutdownManager() {
        return shutdownManager;
    }

    /**
     * Returns the per-instance DBCacheManager.
     */
    public DBCacheManagerImpl getDbCacheManager() {
        return dbCacheManager;
    }

    /**
     * Returns the DerivedTableManager for this instance.
     */
    public DerivedTableManager getDerivedTableManager() {
        return derivedTableManager;
    }

    /**
     * Returns the StatisticsManager for this instance.
     */
    public StatisticsManagerImpl getStatisticsManager() {
        return statisticsManager;
    }

    /**
     * Returns the NetworkParameters for this profile (null if not configured).
     */
    public NetworkParameters getNetworkParameters() {
        return networkParameters;
    }

    /**
     * Returns the DownloadCache for block download management.
     */
    public DownloadCacheImpl getDownloadCache() {
        return downloadCache;
    }

    /**
     * Returns the TransactionApplyContext for transaction type processing.
     * Provides isolated, profile-scoped access to all services needed by
     * TransactionType subclasses without requiring static Signum.getXxx() calls.
     */
    public TransactionApplyContext getTransactionApplyContext() {
        return transactionApplyContext;
    }

    /**
     * Returns the ATProcessingContext for AT module processing.
     * Provides isolated, profile-scoped access to all services needed by
     * AT components without requiring static Signum.getXxx() calls.
     */
    public ATProcessingContext getAtProcessingContext() {
        return atProcessingContext;
    }

    /**
     * Returns the EconomicClustering instance for transaction prioritization.
     */
    public EconomicClustering getEconomicClustering() {
        return economicClustering;
    }

    /**
     * Returns the TimeService instance for this profile.
     */
    public TimeService getTimeService() {
        return timeService;
    }

    /**
     * Returns the PeerManager for this profile.
     */
    public PeerManager getPeerManager() {
        return peerManager;
    }

    /**
     * Returns the DebugTrace instance (null when debug tracing disabled).
     */
    public DebugTrace getDebugTrace() {
        return debugTrace;
    }

    /**
     * Returns {@code true} if this context has been started and has not been stopped.
     */
    public boolean isRunning() {
        return started.get() && !stopped.get();
    }

    /**
     * Returns {@code true} if a stop sequence is in progress or completed.
     */
    public boolean isStopped() {
        return stopped.get();
    }
}