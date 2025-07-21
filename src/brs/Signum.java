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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
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
 * This class serves as the primary entry point and controller for the node. It is responsible for:
 * <ul>
 *     <li>Parsing command-line arguments.</li>
 *     <li>Loading configuration properties.</li>
 *     <li>Initializing all core components and services, such as the database, blockchain,
 *         transaction processor, peer-to-peer network, and the web API server.</li>
 *     <li>Managing the application lifecycle, including startup and graceful shutdown.</li>
 * </ul>
 * This is a utility class and cannot be instantiated.
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
    /** Provides access to the underlying database implementations (BlockDb, TransactionDb, etc.). */
    private static Dbs dbs;

    /** A shared thread pool for managing concurrent tasks within the node. */
    private static ThreadPool threadPool;

    /** The central blockchain data structure manager. */
    private static BlockchainImpl blockchain;
    /** The main processor responsible for handling new blocks and maintaining blockchain consistency. */
    private static BlockchainProcessorImpl blockchainProcessor;
    /** The processor responsible for validating and managing unconfirmed transactions. */
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

    // --- Fields for reload functionality ---
    /** The folder where configuration files are located. */
    private static String confFolder;
    /** A flag to ensure the command handler thread is only started once. */
    private static final AtomicBoolean commandHandlerRunning = new AtomicBoolean(false);
    /** A lock to prevent concurrent reloads. */
    private static final AtomicBoolean isReloading = new AtomicBoolean(false);
    /** The thread that watches for property file changes. */
    private static Thread propertiesFileWatcher;

    /**
     * Loads configuration properties from the specified folder.
     * It first loads {@code node-default.properties} and then overrides them with any values
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

    private Signum() {
    } // never

    /**
     * Returns the singleton instance of the Blockchain.
     * @return The {@link Blockchain} instance.
     */
    public static Blockchain getBlockchain() {
        return blockchain;
    }

    /**
     * Returns the singleton instance of the BlockchainProcessor.
     * @return The {@link BlockchainProcessorImpl} instance.
     */
    public static BlockchainProcessorImpl getBlockchainProcessor() {
        return blockchainProcessor;
    }

    /**
     * Returns the singleton instance of the TransactionProcessor.
     * @return The {@link TransactionProcessorImpl} instance.
     */
    public static TransactionProcessorImpl getTransactionProcessor() {
        return transactionProcessor;
    }

    /**
     * Returns the singleton instance of the TransactionService.
     * @return The {@link TransactionService} instance.
     */
    public static TransactionService getTransactionService() {
        return transactionService;
    }

    /**
     * Returns the singleton instance of the SubscriptionService.
     * @return The {@link SubscriptionService} instance.
     */
    public static SubscriptionService getSubscriptionService() {
        return subscriptionService;
    }

    /**
     * Returns the singleton instance of the AssetExchange.
     * @return The {@link AssetExchange} instance.
     */
    public static AssetExchange getAssetExchange() {
        return assetExchange;
    }

    /**
     * Returns the singleton instance of the Stores.
     * @return The {@link Stores} instance.
     */
    public static Stores getStores() {
        return stores;
    }

    /**
     * Returns the singleton instance of the Dbs.
     * @return The {@link Dbs} instance.
     */
    public static Dbs getDbs() {
        return dbs;
    }

    /**
     * The main entry point for the Signum node application (headless mode).
     * <p>
     * This method initializes the shutdown hook, parses command-line arguments,
     * and starts the node initialization process.
     *
     * @param args Command-line arguments for the node.
     */
    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(Signum::shutdown));
        confFolder = CONF_FOLDER;
        try {
            CommandLine cmd = new DefaultParser().parse(CLI_OPTIONS, args);
            if (cmd.hasOption(CONF_FOLDER_OPTION.getOpt())) {
                confFolder = cmd.getOptionValue(CONF_FOLDER_OPTION.getOpt());
            }
        } catch (Exception e) {
            logger.error("Exception parsing command line arguments", e);
        }
        init(confFolder);
    }

    /**
     * Validates that a pre-release (development) version is not running on the mainnet.
     *
     * @param propertyService The property service to check the network name.
     * @return {@code false} if a pre-release version is running on mainnet, {@code true} otherwise.
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
     *
     * @param customProperties The custom properties to use for initialization.
     */
    public static void init(CaselessProperties customProperties) {
        loadWallet(new PropertyServiceImpl(customProperties));
    }

    /**
     * Initializes the node with properties from the specified configuration folder.
     *
     * @param confFolder The path to the configuration folder.
     */
    private static void init(String confFolder) {
        loadWallet(loadProperties(confFolder));
    }

    /**
     * The central initialization method for the Signum node.
     * <p>
     * This method orchestrates the entire startup sequence. It is responsible for:
     * <ul>
     *     <li>Configuring logging.</li>
     *     <li>Setting up network parameters and address formats.</li>
     *     <li>Initializing the database connection and schema (via {@link Db#init}).</li>
     *     <li>Creating and wiring all services (Account, Alias, Asset, etc.).</li>
     *     <li>Instantiating the main processing engines ({@link BlockchainProcessor}, {@link TransactionProcessor}).</li>
     *     <li>Initializing the peer-to-peer network.</li>
     *     <li>Starting the web server for the API.</li>
     *     <li>Starting the main thread pool to begin node operation.</li>
     * </ul>
     * @param propertyService The fully-loaded property service to be used for configuration.
     */
    private static void loadWallet(PropertyService propertyService) {
        LoggerConfigurator.init();

        Signum.propertyService = propertyService;

        String networkParametersClass = propertyService.getString(Props.NETWORK_PARAMETERS);
        NetworkParameters params = null;
        if (networkParametersClass != null) {
            try {
                params = (NetworkParameters) Class
                        .forName(networkParametersClass)
                        .getConstructor()
                        .newInstance();
                propertyService.setNetworkParameters(params);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                System.exit(1);
            }
        }

        if (!validateVersionNotDev(propertyService)) {
            return;
        }

        try {
            final long startTime = System.currentTimeMillis();

            // Address prefix and coin name
            SignumUtils.setAddressPrefix(propertyService.getString(Props.ADDRESS_PREFIX));
            SignumUtils.addAddressPrefix("BURST");
            SignumUtils.setValueSuffix(propertyService.getString(Props.VALUE_SUFIX));

            final TimeService timeService = new TimeServiceImpl();

            final DerivedTableManager derivedTableManager = new DerivedTableManager();

            final StatisticsManagerImpl statisticsManager = new StatisticsManagerImpl(timeService);
            dbCacheManager = new DBCacheManagerImpl(statisticsManager);

            threadPool = new ThreadPool(propertyService);

            Db.init(propertyService, dbCacheManager);
            dbs = Db.getDbsByDatabaseType();

            stores = new Stores(derivedTableManager, dbCacheManager, timeService, propertyService,
                    dbs.getTransactionDb(),
                    params);

            final TransactionDb transactionDb = dbs.getTransactionDb();
            final BlockDb blockDb = dbs.getBlockDb();
            final BlockchainStore blockchainStore = stores.getBlockchainStore();
            blockchain = new BlockchainImpl(
                    transactionDb,
                    blockDb,
                    blockchainStore,
                    propertyService);

            final AliasService aliasService = new AliasServiceImpl(stores.getAliasStore());
            fluxCapacitor = new FluxCapacitorImpl(blockchain, propertyService);
            aliasService.addDefaultTLDs();

            EconomicClustering economicClustering = new EconomicClustering(blockchain);

            final AccountService accountService = new AccountServiceImpl(stores.getAccountStore(),
                    stores.getAssetTransferStore());

            final DownloadCacheImpl downloadCache = new DownloadCacheImpl(
                    propertyService,
                    fluxCapacitor,
                    blockchain);

            final Generator generator = propertyService.getBoolean(Props.DEV_MOCK_MINING)
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

            transactionService = new TransactionServiceImpl(accountService, blockchain);

            transactionProcessor = new TransactionProcessorImpl(
                    propertyService,
                    economicClustering,
                    blockchain,
                    stores,
                    timeService, dbs,
                    accountService,
                    transactionService,
                    threadPool);

            final ATService atService = new ATServiceImpl(stores.getAtStore());
            subscriptionService = new SubscriptionServiceImpl(
                    stores.getSubscriptionStore(),
                    transactionDb,
                    blockchain,
                    aliasService,
                    accountService);
            final DGSGoodsStoreService digitalGoodsStoreService = new DGSGoodsStoreServiceImpl(
                    blockchain,
                    stores.getDigitalGoodsStoreStore(),
                    accountService);
            final EscrowService escrowService = new EscrowServiceImpl(
                    stores.getEscrowStore(),
                    blockchain,
                    aliasService,
                    accountService);

            assetExchange = new AssetExchangeImpl(
                    accountService,
                    stores.getTradeStore(),
                    stores.getAccountStore(),
                    stores.getAssetTransferStore(),
                    stores.getAssetStore(),
                    stores.getOrderStore());

            final IndirectIncomingService indirectIncomingService = new IndirectIncomingServiceImpl(
                    stores.getIndirectIncomingStore(), propertyService);

            TransactionType.init(
                    blockchain,
                    fluxCapacitor,
                    accountService,
                    digitalGoodsStoreService,
                    aliasService,
                    assetExchange,
                    subscriptionService,
                    escrowService);

            final BlockService blockService = new BlockServiceImpl(
                    accountService,
                    transactionService,
                    blockchain,
                    downloadCache,
                    generator,
                    params);
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
                    aliasService);

            generator.generateForBlockchainProcessor(threadPool, blockchainProcessor);

            final DeeplinkQRCodeGenerator deepLinkQrCodeGenerator = new DeeplinkQRCodeGenerator();

            final ParameterService parameterService = new ParameterServiceImpl(
                    accountService,
                    aliasService,
                    assetExchange,
                    digitalGoodsStoreService,
                    blockchain,
                    blockchainProcessor,
                    transactionProcessor,
                    atService);

            addBlockchainListeners(blockchainProcessor,
                    accountService,
                    assetExchange,
                    digitalGoodsStoreService,
                    blockchain,
                    dbs.getTransactionDb());

            final APITransactionManager apiTransactionManager = new APITransactionManagerImpl(
                    parameterService,
                    transactionProcessor,
                    blockchain,
                    accountService,
                    transactionService);

            Peers.init(
                    timeService,
                    accountService,
                    blockchain,
                    transactionProcessor,
                    blockchainProcessor,
                    propertyService,
                    threadPool);
            if (params != null) {
                params.initialize(parameterService, accountService, apiTransactionManager);
                TransactionType.setNetworkParameters(params);
            }

            final FeeSuggestionCalculator feeSuggestionCalculator = new FeeSuggestionCalculator(
                    blockchainProcessor,
                    stores.getUnconfirmedTransactionStore());

            webServer = new WebServerImpl(new WebServerContext(transactionProcessor,
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
            webServer.start();

            if (propertyService.getBoolean(Props.BRS_DEBUG_TRACE_ENABLED)) {
                DebugTrace.init(propertyService, blockchainProcessor, accountService, assetExchange,
                        digitalGoodsStoreService);
            }

            int timeMultiplier = (propertyService.getBoolean(Props.DEV_OFFLINE))
                    ? Math.max(propertyService.getInt(Props.DEV_TIMEWARP), 1)
                    : 1;

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

            long currentTime = System.currentTimeMillis();
            logger.info("Initialization took {} ms", currentTime - startTime);
            logger.info("Signum Multiverse {} started successfully.", VERSION);
            logger.info("Running network: {}", propertyService.getString(Props.NETWORK_NAME));
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            System.exit(1);
        }
        if (commandHandlerRunning.compareAndSet(false, true)) {
            (new Thread(Signum::commandHandler)).start();
            propertiesFileWatcher = new Thread(new PropertiesFileWatcher(), "PropertiesFileWatcher");
            propertiesFileWatcher.start();
        }
    }

    /**
     * Registers listeners for blockchain events.
     * <p>
     * This method attaches the listeners required to handle the logic for Automated Transactions (AT) and
     * the Digital Goods Store (DGS) upon block application.
     *
     * @param blockchainProcessor The blockchain processor to which listeners are added.
     * @param accountService The account service, required by the listeners.
     * @param assetExchange The asset exchange, currently unused by these listeners but passed for future use.
     * @param goodsService The DGS service, required by the listeners.
     * @param blockchain The blockchain instance, currently unused but passed for future use.
     * @param transactionDb The transaction database, required by the listeners.
     */
    private static void addBlockchainListeners(
            BlockchainProcessor blockchainProcessor,
            AccountService accountService,
            AssetExchange assetExchange,
            DGSGoodsStoreService goodsService,
            Blockchain blockchain,
            TransactionDb transactionDb) {

        @SuppressWarnings("checkstyle:linelengthcheck")
        final AT.HandleATBlockTransactionsListener handleAtBlockTransactionListener = new AT.HandleATBlockTransactionsListener(
                accountService,
                transactionDb);

        @SuppressWarnings("checkstyle:linelengthcheck")
        final DGSGoodsStoreServiceImpl.ExpiredPurchaseListener devNullListener = new DGSGoodsStoreServiceImpl.ExpiredPurchaseListener(
                accountService,
                goodsService);

        blockchainProcessor.addListener(
                handleAtBlockTransactionListener,
                BlockchainProcessor.Event.AFTER_BLOCK_APPLY);
        blockchainProcessor.addListener(
                devNullListener,
                BlockchainProcessor.Event.AFTER_BLOCK_APPLY);
    }

    /**
     * Starts a thread to handle interactive commands from the console (System.in).
     * Supported commands:
     * <ul>
     *     <li>{@code .shutdown} - Gracefully shuts down the node.</li>
     *     <li>{@code .popoff <n>} - Removes the last 'n' blocks from the blockchain.</li>
     * </ul>
     */
    private static void commandHandler() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        try {
            String command;
            while ((command = reader.readLine()) != null) {
                logger.debug("received command: >{}<", command);
                if (command.equals(".shutdown")) {
                    shutdown(false);
                    System.exit(0);
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
            // ignore
        }
    }

    /**
     * Reloads the node configuration by shutting down services and re-initializing.
     * This method is thread-safe and prevents concurrent reloads.
     */
    private static void reload() {
        if (isReloading.compareAndSet(false, true)) {
            try {
                logger.info("Configuration file change detected. Reloading...");
                shutdown(true); // ignore DB shutdown
                init(Signum.confFolder);
                logger.info("Configuration reloaded successfully.");
            } finally {
                isReloading.set(false);
            }
        }
    }

    /**
     * Initiates a graceful shutdown of the node.
     * This is a convenience method that calls {@link #shutdown(boolean)} with {@code false}.
     */
    private static void shutdown() {
        shutdown(false);
    }

    /**
     * Cleans up all node components and services before shutting down.
     * <p>
     * This method stops the web server, shuts down the peer network, terminates the thread pool,
     * and closes the database connection.
     *
     * @param ignoreDbShutdown if {@code true}, the database connection will not be closed.
     *                         This is used in cases like a GUI restart where the underlying
     *                         node is stopped, but the process continues.
     */
    public static void shutdown(boolean ignoreDbShutdown) {
        if (!shuttingdown.get()) {
            logger.info("Shutting down...");
            logger.info("Do not force exit or kill the node process.");
        }

        if (propertiesFileWatcher != null) {
            propertiesFileWatcher.interrupt();
        }

        if (webServer != null) {
            webServer.shutdown();
        }
        if (threadPool != null) {
            Peers.shutdown(threadPool);
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
        logger.info("BRS {} stopped.", VERSION);
        LoggerConfigurator.shutdown();
    }

    /**
     * Returns the singleton instance of the PropertyService.
     * @return The {@link PropertyService} instance.
     */
    public static PropertyService getPropertyService() {
        return propertyService;
    }

    /**
     * Returns the singleton instance of the FluxCapacitor.
     * @return The {@link FluxCapacitor} instance.
     */
    public static FluxCapacitor getFluxCapacitor() {
        return fluxCapacitor;
    }

    /**
     * A background thread that watches for changes in property files and triggers a reload.
     */
    private static class PropertiesFileWatcher implements Runnable {

        private final Path confPath;

        PropertiesFileWatcher() {
            this.confPath = Paths.get(Signum.confFolder).toAbsolutePath();
        }

        @Override
        public void run() {
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

                    // Some editors may trigger multiple events. A small delay helps to coalesce them.
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();

                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }

                        // The filename is the context of the event.
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;
                        Path filename = ev.context();

                        if (filename.toString().equals(PROPERTIES_NAME)) {
                            Signum.reload();
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
