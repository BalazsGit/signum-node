package brs;

import brs.assetexchange.AssetExchange;
import brs.db.cache.DBCacheManagerImpl;
import brs.db.sql.Db;
import brs.db.store.Dbs;
import brs.db.store.Stores;
import brs.fluxcapacitor.FluxCapacitor;
import brs.peer.Peers;
import brs.props.PropertyService;
import brs.services.SubscriptionService;
import brs.services.TransactionService;
import brs.util.ThreadPool;
import brs.web.server.WebServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates all major services and components of a running Signum node.
 * This class acts as a container to manage the lifecycle of node services,
 * making startup, shutdown, and restart operations cleaner and more robust
 * by avoiding widespread use of static variables.
 */
public final class NodeContext {

    private static final Logger logger = LoggerFactory.getLogger(NodeContext.class);

    /** Provides access to configuration properties. */
    public final PropertyService propertyService;
    /** A shared thread pool for managing concurrent tasks. */
    public final ThreadPool threadPool;
    /**
     * Provides access to the underlying database implementations (BlockDb,
     * TransactionDb, etc.).
     */
    public final Dbs dbs;
    /** The manager for all data repositories for various blockchain entities. */
    public final Stores stores;
    /** The manager for the database entity cache. */
    public final DBCacheManagerImpl dbCacheManager;
    /** The central blockchain data structure manager. */
    public final BlockchainImpl blockchain;
    /**
     * The main processor responsible for handling new blocks and maintaining
     * blockchain consistency.
     */
    public final BlockchainProcessorImpl blockchainProcessor;
    /**
     * The processor responsible for validating and managing unconfirmed
     * transactions.
     */
    public final TransactionProcessorImpl transactionProcessor;
    /** The service layer for transaction-related operations. */
    public final TransactionService transactionService;
    /** The service for managing recurring payment subscriptions. */
    public final SubscriptionService subscriptionService;
    /** The engine for the decentralized asset exchange. */
    public final AssetExchange assetExchange;
    /** The manager for hard forks and feature activation heights. */
    public final FluxCapacitor fluxCapacitor;
    /** The embedded web server for the HTTP API. */
    public final WebServer webServer;
    /** The manager for peer-to-peer network operations. */
    public final Peers peers;

    public NodeContext(
            PropertyService propertyService,
            ThreadPool threadPool,
            Dbs dbs,
            Stores stores,
            DBCacheManagerImpl dbCacheManager,
            BlockchainImpl blockchain,
            BlockchainProcessorImpl blockchainProcessor,
            TransactionProcessorImpl transactionProcessor,
            TransactionService transactionService,
            SubscriptionService subscriptionService,
            AssetExchange assetExchange,
            FluxCapacitor fluxCapacitor,
            WebServer webServer,
            Peers peers) {
        this.propertyService = propertyService;
        this.threadPool = threadPool;
        this.dbs = dbs;
        this.stores = stores;
        this.dbCacheManager = dbCacheManager;
        this.blockchain = blockchain;
        this.blockchainProcessor = blockchainProcessor;
        this.transactionProcessor = transactionProcessor;
        this.transactionService = transactionService;
        this.subscriptionService = subscriptionService;
        this.assetExchange = assetExchange;
        this.fluxCapacitor = fluxCapacitor;
        this.webServer = webServer;
        this.peers = peers;
    }

    /**
     * Shuts down all services managed by this context in a graceful order.
     * 
     * @param ignoreDbShutdown If true, the main database connection will not be
     *                         closed.
     */
    public void shutdown(boolean ignoreDbShutdown) {
        logger.info("Shutting down NodeContext...");

        if (webServer != null) {
            webServer.shutdown();
        }
        if (blockchainProcessor != null) {
            blockchainProcessor.shutdown();
        }
        if (threadPool != null) {
            if (peers != null) {
                peers.shutdown();
            }
            threadPool.shutdown();
        }
        if (!ignoreDbShutdown) {
            Db.shutdown();
        }
        if (dbCacheManager != null) {
            dbCacheManager.close();
        }
    }

    /**
     * Initiates a graceful shutdown of the node.
     * This is a convenience method that calls {@link #shutdown(boolean)} with
     * {@code false}.
     */
    public void shutdown() {
        shutdown(false);
    }
}
