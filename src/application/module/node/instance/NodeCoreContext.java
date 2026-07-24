package application.module.node.instance;

import application.module.node.Blockchain;
import application.module.node.BlockchainImpl;
import application.module.node.BlockchainProcessor;
import application.module.node.Generator;
import application.module.node.ShutdownManager;
import application.module.node.TransactionProcessorImpl;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.db.sql.DbContext;
import application.module.node.db.store.Dbs;
import application.module.node.db.store.Stores;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.props.CaselessProperties;
import application.module.node.props.PropertyService;
import application.utils.logging.ProfileThreadContext;
import application.utils.logging.ProfileLogRouter;
import application.utils.logging.ProfileLoggingApplier;
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
import application.module.node.util.ThreadPool;
import application.module.node.web.server.WebServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private Stores stores;

    /** Per-instance database type wrapper. */
    private Dbs dbs;

    /** Dedicated thread pool for background tasks. */
    private ThreadPool threadPool;

    /** Web server (REST + WebSocket). */
    private WebServer webServer;

    /** Tracks graceful shutdown progress. */
    private ShutdownManager shutdownManager;

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
            // Delegation to Signum internal init logic (moved here eventually).
            // For Phase 1 we keep the call-chain intact so Signum.init() still
            // works, but we capture the created components below.
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
     * In Phase 1 this method calls into a protected helper on Signum; in later
     * phases the code will be fully migrated here.
     */
    private void doInitialize() {
        // ── Step 1: Apply per-profile logging configuration ──
        // Resolution priority: profiles.json loggingPresets → profiles.json loggingProfile
        // → NodeProfile.properties logging.preset → hardcoded default ("standard")
        ProfileLoggingApplier.apply(confFolder.toString(), profileName);

        // ── Step 2: Tag current thread with module+profile for log routing ──
        // ProfileThreadContext sets MDC context so that all SLF4J -> JUL ->
        // ProfileLogRouter events emitted on this thread carry the correct
        // module ID + profile name and are routed to the right UI console tabs.
        ProfileThreadContext.setContext("node", profileName);

        // ── Step 2.5: Create profile-scoped ShutdownManager ──
        // The ShutdownManager persists graceful shutdown state to settings.json
        // under the hierarchical path: module -> node -> {profileName}.
        // Created here so each profile maintains independent shutdown tracking.
        this.shutdownManager = new ShutdownManager(this.propertyService, this.profileName);
        application.module.node.Signum.setShutdownManager(this.shutdownManager);

        // ── Step 3: Delegate to Signum.init() ──
        try {
            application.module.node.Signum.init((CaselessProperties) propertyService);
            captureComponentReferences();
        } catch (NodeStartupException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NodeStartupException(
                    "Initialization failed for profile '" + profileName + "'", e);
        }
    }

    /**
     * Reads component references from the Signum static bridge and stores them
     * locally. Once all code is migrated to instance-access these go away.
     */
    private void captureComponentReferences() {
        Blockchain bc = application.module.node.Signum.getBlockchain();
        this.blockchain = (bc instanceof BlockchainImpl) ? (BlockchainImpl) bc : null;
        this.blockchainProcessor = application.module.node.Signum.getBlockchainProcessor();
        this.transactionProcessor = application.module.node.Signum.getTransactionProcessor();
        this.transactionService = application.module.node.Signum.getTransactionService();
        this.subscriptionService = application.module.node.Signum.getSubscriptionService();
        this.assetExchange = application.module.node.Signum.getAssetExchange();
        this.generator = application.module.node.Signum.getGenerator();
        this.fluxCapacitor = application.module.node.Signum.getFluxCapacitor();
        this.stores = application.module.node.Signum.getStores();
        this.dbs = application.module.node.Signum.getDbs();
        // Capture the per-instance DbContext created by Db.init()
        this.dbContext = application.module.node.db.sql.Db.getActiveContext();
    }

    /**
     * Teardown logic extracted from {@code Signum.shutdown()}.
     */
    private void doShutdown() {
        try {
            application.module.node.Signum.shutdownNode();
        } catch (Exception e) {
            // Log but don't throw - shutdown must be resilient
            logger.error("Error during shutdown of profile '{}'", profileName, e);
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

    public ShutdownManager getShutdownManager() {
        return shutdownManager;
    }

    // =========================================================================
    // Lifecycle state queries
    // =========================================================================

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