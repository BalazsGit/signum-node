package brs;

import brs.assetexchange.AssetExchange;
import brs.assetexchange.AssetExchangeImpl;
import brs.at.AT;
import brs.db.BlockDb;
import brs.db.TransactionDb;
import brs.db.cache.DBCacheManagerImpl;
import brs.db.sql.Db;
import brs.db.store.BlockchainStore;
import brs.db.store.Dbs;
import brs.db.store.DerivedTableManager;
import brs.db.store.Stores;
import brs.deeplink.DeeplinkQRCodeGenerator;
import brs.feesuggestions.FeeSuggestionCalculator;
import brs.fluxcapacitor.FluxCapacitor;
import brs.fluxcapacitor.FluxCapacitorImpl;
import brs.peer.Peers;
import brs.props.CaselessProperties;
import brs.props.PropertyService;
import brs.props.PropertyServiceImpl;
import brs.props.Props;
import brs.services.ATService;
import brs.services.AccountService;
import brs.services.AliasService;
import brs.services.BlockService;
import brs.services.DGSGoodsStoreService;
import brs.services.EscrowService;
import brs.services.IndirectIncomingService;
import brs.services.ParameterService;
import brs.services.SubscriptionService;
import brs.services.TimeService;
import brs.services.TransactionService;
import brs.services.impl.ATServiceImpl;
import brs.services.impl.AccountServiceImpl;
import brs.services.impl.AliasServiceImpl;
import brs.services.impl.BlockServiceImpl;
import brs.services.impl.DGSGoodsStoreServiceImpl;
import brs.services.impl.EscrowServiceImpl;
import brs.services.impl.IndirectIncomingServiceImpl;
import brs.services.impl.ParameterServiceImpl;
import brs.services.impl.SubscriptionServiceImpl;
import brs.services.impl.TimeServiceImpl;
import brs.services.impl.TransactionServiceImpl;
import brs.statistics.StatisticsManagerImpl;
import brs.util.DownloadCacheImpl;
import brs.util.LoggerConfigurator;
import brs.util.ThreadPool;
import brs.util.Time;
import brs.web.api.http.common.APITransactionManager;
import brs.web.api.http.common.APITransactionManagerImpl;
import brs.web.server.WebServer;
import brs.web.server.WebServerContext;
import brs.web.server.WebServerImpl;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import signum.net.NetworkParameters;
import signumj.util.SignumUtils;

/**
 * The main class for the Signum node application.
 * <p>
 * This class orchestrates the entire lifecycle of a Signum node instance. It is
 * responsible for:
 * <ul>
 * <li>Parsing command-line arguments.</li>
 * <li>Loading configuration properties.</li>
 * <li>Initializing all core components and services, such as the database,
 * blockchain, transaction processor, peer-to-peer network, and the web API
 * server.</li>
 * <li>Managing the application lifecycle, including startup, graceful shutdown,
 * and automatic
 * restart upon configuration changes.</li>
 * </ul>
 * <p>
 * An instance of this class represents a single, running Signum node. While
 * many core components
 * are exposed as static fields for legacy reasons, their lifecycle
 * (initialization and shutdown)
 * is managed by the {@code Signum} instance.
 */
public final class Signum {

    /** The current version of the Signum node software. */
    public static final Version VERSION = Version.parse("v3.9.3");
    /** The application identifier used in peer-to-peer communication. */
    public static final String APPLICATION = "BRS";
    /** The default path for the configuration folder. */
    public static final String CONF_FOLDER = "./conf";
    /** The name of the default properties file. */
    public static final String DEFAULT_PROPERTIES_NAME = "node-default.properties";
    /** The name of the user-customizable properties file. */
    public static final String PROPERTIES_NAME = "node.properties";
    /** The name of the user-customizable logging properties file. */
    public static final String LOGGING_PROPERTIES_NAME = "logging.properties";
    /** Represents the current state of the node. 0 for stopped, 1 for running. */
    public int NODE_STATE = 0;

    private String[] args = null;

    /** Command-line option to specify a custom configuration folder. */
    public static final Option CONF_FOLDER_OPTION = Option.builder("c")
            .longOpt("config")
            .argName("conf folder")
            .numberOfArgs(1)
            .desc("The configuration folder to use")
            .build();

    /** The set of all supported command-line options. */
    public static final Options CLI_OPTIONS = new Options()
            .addOption(CONF_FOLDER_OPTION)
            .addOption(Option.builder("l")
                    .longOpt("headless")
                    .desc("Run in headless mode")
                    .build())
            .addOption(Option.builder("h")
                    .longOpt("help")
                    .build());

    private static final Logger logger = LoggerFactory.getLogger(Signum.class);

    /** The manager for all data repositories for various blockchain entities. */
    private static Stores stores;

    /**
     * Provides access to the blockchain data store, which contains blocks and
     * transactions.
     */
    private BlockchainStore blockchainStore;

    /**
     * Provides access to the underlying database implementations (BlockDb,
     * TransactionDb, etc.).
     */
    private static Dbs dbs;

    /**
     * The database access object for confirmed transactions.
     * <p>
     * This component acts as a repository (DAO) for managing the persistence of
     * transactions
     * that have been included in a block. It abstracts the underlying database
     * operations,
     * providing a clean interface for saving and retrieving transaction data. It is
     * used by
     * core components like {@link BlockchainProcessor} and {@link Blockchain} to
     * interact
     * with historical transaction records.
     */
    private TransactionDb transactionDb;

    /** The database for managing blocks and their associated data. */
    private BlockDb blockDb;

    /**
     * The service for managing time-related operations, such as epoch time and time
     * adjustments.
     */
    public TimeService timeService;

    /**
     * The manager for derived tables, which are used to optimize database queries
     * and performance.
     */
    private DerivedTableManager derivedTableManager;

    /** The service for managing statistics and performance metrics. */
    private StatisticsManagerImpl statisticsManager;

    /** The service for managing the blockchain and its associated operations. */
    private EconomicClustering economicClustering;

    /**
     * The service for managing the alias system, including domain names and
     * subdomains.
     */
    private AccountService accountService;

    /** The service for managing the Automated Transactions (AT) system. */
    private ATService atService;

    /**
     * The service for managing escrow transactions, which handle conditional
     * payments and releases.
     */
    private EscrowService escrowService;

    /**
     * The service for managing the Digital Goods Store (DGS), which handles goods
     * listings and purchases.
     */
    private DGSGoodsStoreService digitalGoodsStoreService;

    /** A shared thread pool for managing concurrent tasks within the node. */
    public ThreadPool threadPool;

    /** The manager for peer-to-peer network operations. */
    public Peers peers;

    /**
     * The network parameters defining the rules and configurations for the Signum
     * network.
     */
    private NetworkParameters params = null;

    /** The central blockchain data structure manager. */
    private static BlockchainImpl blockchain;

    /**
     * The main processor responsible for handling new blocks and maintaining
     * blockchain consistency.
     */
    private static BlockchainProcessorImpl blockchainProcessor;

    /**
     * The service for managing the alias system, including domain names and
     * subdomains.
     */
    private AliasService aliasService;

    /** The service for managing indirect incoming transactions. */
    private IndirectIncomingService indirectIncomingService;

    /** The download cache for managing data downloads and caching. */
    private DownloadCacheImpl downloadCache;

    /**
     * The service for managing blocks, including block creation and validation.
     * <p>
     * This component is responsible for handling the lifecycle of blocks in the
     * blockchain,
     * including creating new blocks, validating them, and applying them to the
     * blockchain.
     * It is typically used by miners or nodes that participate in block creation.
     */
    private BlockService blockService;

    /**
     * The generator responsible for creating new blocks and transactions.
     * <p>
     * This component is responsible for generating new blocks in the blockchain,
     * including
     * selecting transactions, calculating fees, and ensuring the block meets the
     * network's
     * consensus rules. It is typically used by miners or nodes that participate in
     * block creation.
     */
    private DeeplinkQRCodeGenerator deepLinkQrCodeGenerator;

    /**
     * The service for managing parameters and configurations related to the
     * blockchain and transactions.
     * <p>
     * This component provides access to various parameters that can be used to
     * customize the behavior
     * of the blockchain, such as transaction fees, block generation rules, and
     * other network settings.
     */
    private ParameterService parameterService;

    /**
     * The generator responsible for creating new blocks and transactions.
     * <p>
     * This component is responsible for generating new blocks in the blockchain,
     * including
     * selecting transactions, calculating fees, and ensuring the block meets the
     * network's
     * consensus rules. It is typically used by miners or nodes that participate in
     * block creation.
     */
    private Generator generator;

    /**
     * The manager for handling API transactions, providing a bridge between the
     * API layer and the
     * transaction processing logic.
     */
    private APITransactionManager apiTransactionManager;

    /**
     * The calculator for suggesting transaction fees based on current network
     * conditions.
     * <p>
     * This component analyzes recent transactions and network activity to provide
     * fee suggestions
     * that help users set appropriate fees for their transactions, ensuring timely
     * processing.
     */
    private FeeSuggestionCalculator feeSuggestionCalculator;

    /**
     * The processor responsible for validating and managing unconfirmed
     * transactions.
     */
    private static TransactionProcessorImpl transactionProcessor;
    /** The service layer for transaction-related operations. */
    private static TransactionService transactionService;
    /** The service for managing recurring payment subscriptions. */
    private static SubscriptionService subscriptionService;
    /** The engine for the decentralized asset exchange. */
    private static AssetExchange assetExchange;

    /** The service for accessing and managing configuration properties. */
    private static PropertyService propertyService;
    /** The manager for hard forks and feature activation heights. */
    private static FluxCapacitor fluxCapacitor;

    /** The manager for the database entity cache. */
    private static DBCacheManagerImpl dbCacheManager;

    /** The embedded web server for the HTTP API. */
    private static WebServer webServer;

    /** A flag indicating if the shutdown process has been initiated. */
    private static AtomicBoolean shuttingdown = new AtomicBoolean(false);

    /** The folder where configuration files are located. */
    private static String confFolder;
    /** A flag to ensure the command handler thread is only started once. */
    private static final AtomicBoolean commandHandlerRunning = new AtomicBoolean(false);
    /** A lock to prevent concurrent restarts. */
    private static final AtomicBoolean isRestarting = new AtomicBoolean(false);
    /** The thread that watches for property file changes. */
    private static Thread propertiesFileWatcher;
    /** The class name for custom network parameters, if specified. */
    private String networkParametersClass;
    /**
     * The multiplier for time adjustments, used in testing or development modes.
     */
    int timeMultiplier;

    /**
     * Loads configuration properties from the specified folder.
     * It first loads {@code node-default.properties} and then overrides them with
     * any values
     * found in {@code node.properties}.
     *
     * @param confFolder The path to the configuration folder.
     * @return A {@link PropertyService} instance containing the loaded properties.
     * @throws RuntimeException if the default properties file cannot be loaded.
     */
    private static PropertyService loadProperties(String confFolder) {
        logger.info("Initializing Signum Node version {}", VERSION);

        logger.info("Configurations from folder {}", confFolder);

        CaselessProperties defaultProperties = new CaselessProperties();
        File defaultPropsFile = new File(confFolder, DEFAULT_PROPERTIES_NAME);
        try (Reader reader = new InputStreamReader(new FileInputStream(defaultPropsFile), StandardCharsets.UTF_8)) {
            defaultProperties.load(reader);
        } catch (IOException e) {
            throw new RuntimeException("Error loading " + DEFAULT_PROPERTIES_NAME, e);
        }

        CaselessProperties properties = new CaselessProperties(defaultProperties);
        File propsFile = new File(confFolder, PROPERTIES_NAME);
        if (propsFile.exists()) {
            try (Reader reader = new InputStreamReader(new FileInputStream(propsFile), StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException e) {
                logger.info("Custom user properties file {} not loaded", PROPERTIES_NAME, e);
            }
        } else {
            logger.info("Custom user properties file {} not found", PROPERTIES_NAME);
        }

        return new PropertyServiceImpl(properties);
    }

    /**
     * Creates a new Signum node instance with specified command-line arguments.
     * This constructor will parse the provided arguments to determine the
     * configuration folder.
     *
     * @param args The command-line arguments.
     */
    public Signum(String[] args) {
        this.args = args;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown()));
        confFolder = CONF_FOLDER;
        try {
            CommandLine cmd = new DefaultParser().parse(CLI_OPTIONS, args);
            if (cmd.hasOption(CONF_FOLDER_OPTION.getOpt())) {
                confFolder = cmd.getOptionValue(CONF_FOLDER_OPTION.getOpt());
            }
        } catch (Exception e) {
            logger.error("Exception parsing command line arguments", e);
        }
    }

    /**
     * Returns the singleton instance of the Blockchain.
     * 
     * @return The application's {@link Blockchain} instance.
     */
    public static Blockchain getBlockchain() {
        return blockchain;
    }

    /**
     * Returns the singleton instance of the BlockchainProcessor.
     * 
     * @return The application's {@link BlockchainProcessorImpl} instance.
     */
    public static BlockchainProcessorImpl getBlockchainProcessor() {
        return blockchainProcessor;
    }

    /**
     * Returns the singleton instance of the TransactionProcessor.
     * 
     * @return The application's {@link TransactionProcessorImpl} instance.
     */
    public static TransactionProcessorImpl getTransactionProcessor() {
        return transactionProcessor;
    }

    /**
     * Returns the singleton instance of the TransactionService.
     * 
     * @return The application's {@link TransactionService} instance.
     */
    public static TransactionService getTransactionService() {
        return transactionService;
    }

    /**
     * Returns the singleton instance of the SubscriptionService.
     * 
     * @return The application's {@link SubscriptionService} instance.
     */
    public static SubscriptionService getSubscriptionService() {
        return subscriptionService;
    }

    /**
     * Returns the singleton instance of the AssetExchange.
     * 
     * @return The application's {@link AssetExchange} instance.
     */
    public static AssetExchange getAssetExchange() {
        return assetExchange;
    }

    /**
     * Returns the singleton instance of the Stores.
     * 
     * @return The application's {@link Stores} instance, providing access to data
     *         stores.
     */
    public static Stores getStores() {
        return stores;
    }

    /**
     * Returns the singleton instance of the Dbs.
     * 
     * @return The application's {@link Dbs} instance, providing access to database
     *         tables.
     */
    public static Dbs getDbs() {
        return dbs;
    }

    /**
     * Validates that a pre-release (development) version is not running on the
     * mainnet.
     *
     * @param propertyService The property service to check the network name.
     * @return {@code false} if a pre-release version is running on mainnet,
     *         {@code true} otherwise.
     */
    private static boolean validateVersionNotDev(PropertyService propertyService) {
        if (VERSION.isPrelease()
                && propertyService
                        .getString(Props.NETWORK_NAME)
                        .equals(Constants.SIGNUM_NETWORK_NAME)) {
            logger.error("THIS IS A DEVELOPMENT VERSION, PLEASE DO NOT USE THIS ON Signum MAINNET");
            return false;
        }
        return true;
    }

    /**
     * Initializes the node with a custom set of properties.
     * This is typically used for testing or special configurations.
     *
     * @param customProperties The custom properties to use for initialization.
     */
    public void init(CaselessProperties customProperties) {
        initNode(new PropertyServiceImpl(customProperties));
    }

    /**
     * Initializes the node with properties from the specified configuration folder.
     * This is a convenience method that loads properties before calling the main
     * initialization logic.
     *
     * @param confFolder The path to the configuration folder.
     */
    private void init(String confFolder) {
        initNode(loadProperties(confFolder));
    }

    /**
     * Initializes the node with properties from the specified configuration folder.
     *
     * This is the main entry point for starting the node. It triggers the loading
     * of
     * properties and the full initialization of all services.
     */
    public void init() {
        initNode(loadProperties(confFolder));
    }

    /**
     * The central initialization method for the Signum node.
     * <p>
     * This method orchestrates the entire startup sequence. It is responsible for:
     * <ul>
     * <li>Configuring logging.</li>
     * <li>Setting up network parameters and address formats.</li>
     * <li>Initializing the database connection and schema (via
     * {@link Db#init}).</li>
     * <li>Creating and wiring all services (Account, Alias, Asset, etc.).</li>
     * <li>Instantiating the main processing engines ({@link BlockchainProcessor},
     * {@link TransactionProcessor}).</li>
     * <li>Initializing the peer-to-peer network.</li>
     * <li>Starting the web server for the API.</li>
     * <li>Starting the main thread pool to begin node operation.</li>
     * </ul>
     * 
     * @param propertyService The fully-loaded property service to be used for
     *                        configuration.
     */
    private void initNode(PropertyService propertyService) {
        // Initialize the logger configuration.
        LoggerConfigurator.init();

        // Set the property service as a static field for global access.
        Signum.propertyService = propertyService;

        // Set the configuration folder.
        threadPool = new ThreadPool(propertyService);
        peers = new Peers();

        // Load network parameters from the specified class, if provided.
        networkParametersClass = propertyService.getString(Props.NETWORK_PARAMETERS);
        params = null;
        if (networkParametersClass != null) {
            try {
                params = (NetworkParameters) Class
                        .forName(networkParametersClass)
                        .getConstructor()
                        .newInstance();
                propertyService.setNetworkParameters(params);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                logger.error("Failed to load custom network parameters class: {}", networkParametersClass);
                System.exit(1);
            }
        } else {
            logger.info("No custom network parameters class specified, using default Signum parameters.");
        }

        // Validate that a pre-release version is not running on the mainnet.
        if (!validateVersionNotDev(propertyService)) {
            return;
        }

        try {
            long startTime = System.currentTimeMillis();

            // Address prefix and coin name
            // TODO: check
            SignumUtils.setAddressPrefix(propertyService.getString(Props.ADDRESS_PREFIX));
            SignumUtils.addAddressPrefix("BURST");
            SignumUtils.setValueSuffix(propertyService.getString(Props.VALUE_SUFIX));

            timeService = new TimeServiceImpl();

            derivedTableManager = new DerivedTableManager();

            statisticsManager = new StatisticsManagerImpl(timeService);
            dbCacheManager = new DBCacheManagerImpl(statisticsManager);

            // Initialize the database connection and schema.
            Db.init(
                    propertyService,
                    dbCacheManager);

            // Initialize the database access objects.
            dbs = Db.getDbsByDatabaseType();

            // Initialize the database stores.
            stores = new Stores(
                    derivedTableManager,
                    dbCacheManager,
                    timeService,
                    propertyService,
                    dbs.getTransactionDb(),
                    params);

            // Initialize the database access objects for transactions and blocks.
            transactionDb = dbs.getTransactionDb();

            // Initialize the BlockDb instance.
            blockDb = dbs.getBlockDb();

            // Initialize the blockchain store.
            blockchainStore = stores.getBlockchainStore();

            // Initialize the blockchain instance.
            blockchain = new BlockchainImpl(
                    transactionDb,
                    blockDb,
                    blockchainStore,
                    propertyService);

            // Initialize aliasService for managing the alias system, including domain names
            // and subdomains.
            aliasService = new AliasServiceImpl(stores.getAliasStore());

            // Initialize the Flux Capacitor for managing hard forks and feature
            fluxCapacitor = new FluxCapacitorImpl(
                    blockchain,
                    propertyService);

            /**
             * Initializes the Alias Service with default TLDs.
             * This method is called to ensure that the alias service is ready to handle
             * alias registrations
             * and queries with a predefined set of top-level domains (TLDs).
             */
            aliasService.addDefaultTLDs();

            /**
             * Initializes the Economic Clustering service.
             * This service is responsible for managing economic clustering
             * operations within the blockchain.
             * It is initialized with the blockchain instance to access blockchain data
             * and perform clustering
             * operations.
             */
            economicClustering = new EconomicClustering(blockchain);

            // Initialize the Account Service for managing accounts and their balances.
            accountService = new AccountServiceImpl(
                    stores.getAccountStore(),
                    stores.getAssetTransferStore());

            // Initialize the download cache for managing data downloads and caching.
            downloadCache = new DownloadCacheImpl(
                    propertyService,
                    fluxCapacitor,
                    blockchain);

            // Initialize the generator for creating new blocks and transactions.
            generator = propertyService.getBoolean(Props.DEV_MOCK_MINING)
                    ? new GeneratorImpl.MockGenerator(
                            propertyService,
                            blockchain,
                            accountService,
                            timeService,
                            fluxCapacitor)
                    : new GeneratorImpl(
                            blockchain,
                            downloadCache,
                            accountService,
                            timeService,
                            fluxCapacitor);

            // Initialize the transaction processor for handling unconfirmed transactions.
            transactionService = new TransactionServiceImpl(
                    accountService,
                    blockchain);

            // Initialize the transaction processor with all necessary services.
            transactionProcessor = new TransactionProcessorImpl(
                    propertyService,
                    economicClustering,
                    blockchain,
                    stores,
                    timeService, dbs,
                    accountService,
                    transactionService,
                    threadPool,
                    peers);

            // Initialize the AT service for managing Automated Transactions.
            atService = new ATServiceImpl(
                    stores.getAtStore());

            // Initialize the subscription service for managing recurring payments.
            subscriptionService = new SubscriptionServiceImpl(
                    stores.getSubscriptionStore(),
                    transactionDb,
                    blockchain,
                    aliasService,
                    accountService);

            // Initialize the Digital Goods Store service for managing goods listings and
            // purchases.
            digitalGoodsStoreService = new DGSGoodsStoreServiceImpl(
                    blockchain,
                    stores.getDigitalGoodsStoreStore(),
                    accountService);

            // Initialize the Escrow Service for managing conditional payments and releases.
            escrowService = new EscrowServiceImpl(
                    stores.getEscrowStore(),
                    blockchain,
                    aliasService,
                    accountService);

            // Initialize the Asset Exchange service for managing asset trading.
            assetExchange = new AssetExchangeImpl(
                    accountService,
                    stores.getTradeStore(),
                    stores.getAccountStore(),
                    stores.getAssetTransferStore(),
                    stores.getAssetStore(),
                    stores.getOrderStore());

            // Initialize the Indirect Incoming Service for managing indirect incoming
            // transactions.
            indirectIncomingService = new IndirectIncomingServiceImpl(
                    stores.getIndirectIncomingStore(),
                    propertyService);

            // Initialize the TransactionType with all necessary services.
            TransactionType.init(
                    blockchain,
                    fluxCapacitor,
                    accountService,
                    digitalGoodsStoreService,
                    aliasService,
                    assetExchange,
                    subscriptionService,
                    escrowService);

            // Initialize the BlockService for managing block creation and validation.
            blockService = new BlockServiceImpl(
                    accountService,
                    transactionService,
                    blockchain,
                    downloadCache,
                    generator,
                    params);

            // Initialize the BlockchainProcessor with all necessary services.
            blockchainProcessor = new BlockchainProcessorImpl(
                    threadPool,
                    blockService,
                    transactionProcessor,
                    blockchain,
                    propertyService,
                    subscriptionService,
                    timeService,
                    derivedTableManager,
                    blockDb,
                    transactionDb,
                    economicClustering,
                    blockchainStore,
                    stores,
                    escrowService,
                    transactionService,
                    downloadCache,
                    generator,
                    statisticsManager,
                    dbCacheManager,
                    accountService,
                    indirectIncomingService,
                    aliasService,
                    peers);

            /**
             * Initializes the Generator for the Blockchain Processor.
             * This generator is responsible for creating new blocks
             * and transactions within the blockchain.
             * It integrates with the blockchain processor to ensure
             * the generation of blocks
             * is synchronized with the blockchain state and
             * transaction processing logic.
             */
            generator.generateForBlockchainProcessor(
                    threadPool,
                    blockchainProcessor);

            /**
             * Initializes the Deeplink QR Code Generator.
             * This generator is responsible for creating QR codes that
             * encode deep links to specific
             * Signum transactions or resources.
             * It is used to facilitate easy sharing and access to
             * Signum-related content via QR codes.
             */
            deepLinkQrCodeGenerator = new DeeplinkQRCodeGenerator();

            // Initialize the ParameterService for managing network parameters and
            // configurations.
            parameterService = new ParameterServiceImpl(
                    accountService,
                    aliasService,
                    assetExchange,
                    digitalGoodsStoreService,
                    blockchain,
                    blockchainProcessor,
                    transactionProcessor,
                    atService);

            // Register blockchain listeners for Automated Transactions (AT) and Digital
            // Goods Store (DGS) functionalities.
            addBlockchainListeners(
                    blockchainProcessor,
                    accountService,
                    assetExchange,
                    digitalGoodsStoreService,
                    blockchain,
                    dbs.getTransactionDb());

            /**
             * Initializes the API Transaction Manager.
             * This manager is responsible for handling
             * API transactions, which are
             * transactions initiated through the API layer.
             * It integrates with the transaction processor,
             * blockchain, and account service to manage
             * the lifecycle of API transactions.
             */
            apiTransactionManager = new APITransactionManagerImpl(
                    parameterService,
                    transactionProcessor,
                    blockchain,
                    accountService,
                    transactionService);

            // Initialize the Peers manager for handling peer-to-peer network operations.
            peers.init(
                    timeService,
                    accountService,
                    blockchain,
                    transactionProcessor,
                    blockchainProcessor,
                    propertyService,
                    threadPool);

            // Start the peer-to-peer network.
            peers.start(timeService,
                    accountService,
                    blockchain,
                    transactionProcessor,
                    blockchainProcessor,
                    propertyService,
                    threadPool);

            // Initialize the network parameters if provided.
            if (params != null) {
                params.initialize(
                        parameterService,
                        accountService,
                        apiTransactionManager);
                // Set the network parameters for TransactionType.
                TransactionType.setNetworkParameters(params);
            }

            // Initialize the Fee Suggestion Calculator for suggesting transaction fees.
            feeSuggestionCalculator = new FeeSuggestionCalculator(
                    blockchainProcessor,
                    stores.getUnconfirmedTransactionStore());

            // Initialize the web server for the API.
            // It provides the HTTP API for interacting with the Signum node.
            webServer = new WebServerImpl(new WebServerContext(
                    transactionProcessor,
                    blockchain,
                    blockchainProcessor,
                    parameterService,
                    accountService,
                    aliasService,
                    assetExchange,
                    escrowService,
                    digitalGoodsStoreService,
                    subscriptionService,
                    atService,
                    timeService,
                    economicClustering,
                    propertyService,
                    threadPool,
                    transactionService,
                    blockService,
                    generator,
                    apiTransactionManager,
                    feeSuggestionCalculator,
                    deepLinkQrCodeGenerator,
                    indirectIncomingService,
                    params));

            // Start the web server.
            webServer.start();

            // Initialize the properties file watcher to monitor changes in the
            // configuration properties file.
            if (propertyService.getBoolean(Props.BRS_DEBUG_TRACE_ENABLED)) {
                DebugTrace.init(
                        propertyService,
                        blockchainProcessor,
                        accountService,
                        assetExchange,
                        digitalGoodsStoreService);
            }

            // Start background threads, with optional time acceleration for development
            // mode.
            timeMultiplier = (propertyService.getBoolean(Props.DEV_OFFLINE))
                    ? Math.max(propertyService.getInt(Props.DEV_TIMEWARP), 1)
                    : 1;

            /**
             * Starts the thread pool for executing background tasks.
             * This method initializes the thread pool with the specified time multiplier,
             * which can be used to accelerate
             * the execution of tasks for testing or development purposes.
             */
            threadPool.start(timeMultiplier);
            if (timeMultiplier > 1) {
                timeService.setTime(new Time.FasterTime(
                        Math.max(
                                timeService.getEpochTime(),
                                getBlockchain()
                                        .getLastBlock()
                                        .getTimestamp()),
                        timeMultiplier));
                logger.info("TIME WILL FLOW {} TIMES FASTER!", timeMultiplier);
            }

            // Log the successful initialization of the node.
            long currentTime = System.currentTimeMillis();
            logger.info("Initialization took {} ms", currentTime - startTime);
            logger.info("Signum Multiverse {} started successfully.", VERSION);
            logger.info("Running network: {}", propertyService.getString(Props.NETWORK_NAME));

        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            logger.error("Failed to initialize Signum Node, shutting down.");
            // If any error occurs during initialization, we perform a full shutdown.
            System.exit(1);
        }

        /**
         * Starts the command handler thread to listen for interactive commands
         * from the console.
         */
        // The command handler should only ever be started once per JVM run.
        if (commandHandlerRunning.compareAndSet(false, true)) {
            (new Thread(this::commandHandler, "CommandHandler")).start();
        }

        // Start the properties file watcher thread to monitor changes in the
        // properties file.
        if (propertiesFileWatcher == null || !propertiesFileWatcher.isAlive()) {

            /**
             * Starts the Properties File Watcher thread.
             * This thread monitors the properties file for changes
             * and reloads the
             * properties dynamically.
             * It ensures that any changes made to the properties file
             * are applied
             * without requiring a restart of the node.
             */
            propertiesFileWatcher = new Thread(new PropertiesFileWatcher(threadPool), "PropertiesFileWatcher");
            propertiesFileWatcher.start();
        }
    }

    /**
     * Registers listeners for blockchain events.
     * <p>
     * This method attaches the listeners required to handle the logic for Automated
     * Transactions (AT) and
     * the Digital Goods Store (DGS) upon block application.
     *
     * @param blockchainProcessor The blockchain processor to which listeners are
     *                            added.
     * @param accountService      The account service, required by the listeners.
     * @param assetExchange       The asset exchange, currently unused by these
     *                            listeners but passed for future use.
     * @param goodsService        The DGS service, required by the listeners.
     * @param blockchain          The blockchain instance, currently unused but
     *                            passed for future use.
     * @param transactionDb       The transaction database, required by the
     *                            listeners.
     */
    private static void addBlockchainListeners(
            BlockchainProcessor blockchainProcessor,
            AccountService accountService,
            AssetExchange assetExchange,
            DGSGoodsStoreService goodsService,
            Blockchain blockchain,
            TransactionDb transactionDb) {

        // Register listeners for Automated Transactions (AT) and Digital Goods Store
        // (DGS) functionalities.
        @SuppressWarnings("checkstyle:linelengthcheck")
        final AT.HandleATBlockTransactionsListener handleAtBlockTransactionListener = new AT.HandleATBlockTransactionsListener(
                accountService,
                transactionDb);

        // Register a listener for expired purchases in the Digital Goods Store.
        @SuppressWarnings("checkstyle:linelengthcheck")
        final DGSGoodsStoreServiceImpl.ExpiredPurchaseListener devNullListener = new DGSGoodsStoreServiceImpl.ExpiredPurchaseListener(
                accountService,
                goodsService);

        // Add the listeners to the blockchain processor for AFTER_BLOCK_APPLY events.
        blockchainProcessor.addListener(
                handleAtBlockTransactionListener,
                BlockchainProcessor.Event.AFTER_BLOCK_APPLY);

        // Add the listener for expired purchases in the Digital Goods Store.
        blockchainProcessor.addListener(
                devNullListener,
                BlockchainProcessor.Event.AFTER_BLOCK_APPLY);
    }

    /**
     * Starts a thread to handle interactive commands from the console (System.in).
     * Supported commands:
     * <ul>
     * <li>{@code .shutdown} - Gracefully shuts down the node.</li>
     * <li>{@code .restart} - Gracefully restarts the node.</li>
     * <li>{@code .popoff <n>} - Removes the last 'n' blocks from the blockchain
     * (for maintenance).</li>
     * </ul>
     */
    private void commandHandler() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        try {
            String command;
            while ((command = reader.readLine()) != null) {
                logger.debug("received command: >{}<", command);
                if (command.equals(".shutdown")) {
                    shutdown(false);
                    System.exit(0);
                } else if (command.equals(".restart")) {
                    restart();
                } else if (command.startsWith(".popoff ")) {
                    Pattern r = Pattern.compile("^\\.popoff (\\d+)$");
                    Matcher m = r.matcher(command);
                    if (m.find()) {
                        int numBlocks = Integer.parseInt(m.group(1));
                        if (numBlocks > 0) {
                            blockchainProcessor.popOffTo(blockchain.getHeight() - numBlocks);
                        }
                    }
                }
            }
        } catch (IOException e) {
            // Log the error if reading from the command input fails.
            logger.error("Error reading command input", e);
        }
    }

    /**
     * Restarts the node by shutting down all services and re-initializing
     * completely.
     * This method is thread-safe and prevents concurrent restarts.
     *
     * It performs a full shutdown of all components, including the web server, peer
     * network,
     * and thread pools. It then resets all static state variables to ensure a clean
     * start
     * before calling {@link #init()} to re-initialize the node with the current
     * configuration.
     */
    private void restart() {
        if (isRestarting.compareAndSet(false, true)) {
            try {

                // Perform a full, clean shutdown. This will stop all services,
                // including the ThreadPool and the propertiesFileWatcher thread.
                logger.info("Restarting node...");

                if (webServer != null) {
                    webServer.shutdown();
                }

                if (blockchainProcessor != null) {
                    blockchainProcessor.shutdown();
                }

                if (threadPool != null) {
                    peers.shutdown();
                    threadPool.shutdown();
                }

                Db.shutdown();

                if (dbCacheManager != null) {
                    dbCacheManager.close();
                }

                if (blockchainProcessor != null && blockchainProcessor.getOclVerify()) {
                    // If OpenCL Proof of Capacity is enabled, destroy the OCLPoC instance.
                    // This is necessary to release any OpenCL resources before re-initialization.
                    logger.info("Destroying OCLPoC instance for restart.");
                    OCLPoC.destroy();
                }

                if (propertiesFileWatcher != null) {
                    propertiesFileWatcher.interrupt();
                }

                logger.info("BRS {} stopped.", VERSION);
                LoggerConfigurator.shutdown();

                // Wait for all threads to finish gracefully.
                try {
                    // A small delay helps ensure system resources are released.
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Reset all static state variables to null. This is the crucial step
                // to ensure that no old object instances with terminated resources are
                // accidentally reused. This creates a clean slate for re-initialization.
                // Note: This is safe because the static fields are only accessed in a
                // single-threaded context
                // during the node's lifecycle, and we are currently in the main thread.
                stores = null;
                dbs = null;
                threadPool = null;
                blockchain = null;
                blockchainProcessor = null;
                transactionProcessor = null;
                transactionService = null;
                subscriptionService = null;
                assetExchange = null;
                propertyService = null;
                fluxCapacitor = null;
                dbCacheManager = null;
                webServer = null;
                propertiesFileWatcher = null;

                NODE_STATE = 1;
                init();

                logger.info("Node restarted successfully.");

            } catch (Exception e) {
                logger.error("Failed to restart node, forcing full shutdown.", e);
                // If any error occurs during restart, we perform a full shutdown.
                // This ensures that the node does not remain in an inconsistent state.
                shutdown(false);
                System.exit(1);
            } finally {
                isRestarting.set(false);
            }
        }
    }

    /**
     * Initiates a graceful shutdown of the node.
     * This is a convenience method that calls {@link #shutdown(boolean)} with
     * {@code false}.
     */
    private void shutdown() {
        shutdown(false);
    }

    /**
     * Cleans up all node components and services before shutting down.
     * <p>
     * This method stops the web server, shuts down the peer network, terminates the
     * thread pool,
     * and closes the database connection. It ensures that all resources are
     * released gracefully.
     *
     * @param ignoreDbShutdown if {@code true}, the database connection will not be
     *                         closed.
     *                         This is used in cases like a GUI restart where the
     *                         underlying
     *                         node is stopped, but the Java process continues to
     *                         run, and the
     *                         database should remain open for the new node
     *                         instance.
     */
    public void shutdown(boolean ignoreDbShutdown) {

        if (!shuttingdown.get()) {
            logger.info("Shutting down...");
            logger.info("Do not force exit or kill the node process.");
        }

        if (webServer != null) {
            webServer.shutdown();
        }
        if (threadPool != null) {
            peers.shutdown();
            threadPool.shutdown();
        }
        if (!ignoreDbShutdown && !shuttingdown.get()) {
            shuttingdown.set(true);
            Db.shutdown();
        }

        if (dbCacheManager != null) {
            dbCacheManager.close();
        }
        if (blockchainProcessor != null && blockchainProcessor.getOclVerify()) {
            OCLPoC.destroy();
        }

        if (propertiesFileWatcher != null) {
            propertiesFileWatcher.interrupt();
        }

        logger.info("BRS {} stopped.", VERSION);
        LoggerConfigurator.shutdown();
    }

    /**
     * Returns the singleton instance of the PropertyService.
     * 
     * @return The application's {@link PropertyService} instance.
     */
    public static PropertyService getPropertyService() {
        return propertyService;
    }

    /**
     * Returns the singleton instance of the FluxCapacitor.
     * 
     * @return The application's {@link FluxCapacitor} instance, which manages hard
     *         forks.
     */
    public static FluxCapacitor getFluxCapacitor() {
        return fluxCapacitor;
    }

    /**
     * A background thread that watches for changes in {@code node.properties} and
     * {@code logging.properties} files and triggers a node restart.
     * <p>
     * This thread uses a {@link WatchService} to monitor the configuration folder
     * for file modifications.
     * When a change is detected in either of the properties files, it calculates
     * the new hash
     * and compares it with the previous hash.
     * If the hashes differ, it logs the change and starts a new thread to restart
     * the node.
     * <p>
     * This watcher is designed to run continuously in the background and will
     * automatically restart the node when configuration changes are detected.
     * It handles exceptions gracefully and logs errors without crashing the node.
     */
    private class PropertiesFileWatcher implements Runnable {

        private final Path confPath;
        private byte[] nodePropertiesHash;
        private byte[] loggingPropertiesHash;

        PropertiesFileWatcher(ThreadPool threadPool) {
            this.confPath = Paths.get(Signum.confFolder).toAbsolutePath();
            try {
                this.nodePropertiesHash = calculateFileHash(confPath.resolve(PROPERTIES_NAME));
                this.loggingPropertiesHash = calculateFileHash(confPath.resolve(LOGGING_PROPERTIES_NAME));
            } catch (IOException | NoSuchAlgorithmException e) {
                // If we can't create the initial hashes, we cannot reliably detect changes.
                // We log the error and the watcher will be effectively disabled as hashes will
                // be null.
                logger.error("Could not create initial hash for property files. File watcher will be disabled.", e);
                this.nodePropertiesHash = null;
                this.loggingPropertiesHash = null;
            }
        }

        private byte[] calculateFileHash(Path path) throws IOException, NoSuchAlgorithmException {
            if (!Files.exists(path)) {
                return new byte[0];
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fileBytes = Files.readAllBytes(path);
            return digest.digest(fileBytes);
        }

        @Override
        public void run() {
            // If initial hashing failed, do not start the watcher loop.
            if (nodePropertiesHash == null || loggingPropertiesHash == null) {
                logger.warn("PropertiesFileWatcher is disabled due to an initial hashing error.");
                return;
            }

            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                confPath.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
                logger.info("Started watching for changes in {}", confPath);

                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key;
                    try {
                        // block until a file change event is received
                        key = watchService.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    // A small delay to coalesce multiple events from some editors.
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }

                        try {
                            if (event.context() instanceof Path) {
                                Path changedFilePath = (Path) event.context();
                                String changedFile = changedFilePath.toString();
                                Path fullPath = confPath.resolve(changedFile);

                                if (changedFile.equals(PROPERTIES_NAME)) {
                                    byte[] newNodePropertiesHash = calculateFileHash(fullPath);
                                    if (!Arrays.equals(nodePropertiesHash, newNodePropertiesHash)) {
                                        logger.info("Configuration file {} changed, restarting...", changedFile);
                                        nodePropertiesHash = newNodePropertiesHash;
                                        new Thread(Signum.this::restart, "RestartThread").start();
                                        Thread.currentThread().interrupt();
                                        break; // Exit the inner loop as we are restarting
                                    }
                                } else if (changedFile.equals(LOGGING_PROPERTIES_NAME)) {
                                    byte[] newLoggingPropertiesHash = calculateFileHash(fullPath);
                                    if (!Arrays.equals(loggingPropertiesHash, newLoggingPropertiesHash)) {
                                        logger.info("Configuration file {} changed, restarting...", changedFile);
                                        loggingPropertiesHash = newLoggingPropertiesHash;
                                        new Thread(Signum.this::restart, "RestartThread").start();
                                        Thread.currentThread().interrupt();
                                        break; // Exit the inner loop as we are restarting
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("Error processing file watch event", e);
                        }
                    }

                    // Reset the key to receive further watch events.
                    if (!key.reset()) {
                        break;
                    }
                }
            } catch (IOException e) {
                logger.error("Error in properties file watcher", e);
            }
            logger.info("Properties file watcher stopped.");
        }
    }
}
