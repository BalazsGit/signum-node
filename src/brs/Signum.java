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
import brs.util.PathUtils;
import brs.util.ThreadPool;
import brs.util.Time;
import brs.web.api.http.common.APITransactionManager;
import brs.web.api.http.common.APITransactionManagerImpl;
import brs.web.server.WebServer;
import brs.web.server.WebServerContext;
import brs.web.server.WebServerImpl;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * The main class of the Signum node.
 */
public final class Signum {

    public static final Version VERSION = Version.parse("v3.9.8");
    public static final String APPLICATION = "BRS";

    public static final String CONF_FOLDER = "../conf";
    public static final String NODE_SUBFOLDER = "node";
    public static final String NODE_LOGGING_SUBFOLDER = "node-logging";
    public static final String DATABASE_SUBFOLDER = "database";

    public static final String NODE_CONF_DIR = NODE_SUBFOLDER;
    public static final String LOGGING_CONF_DIR = NODE_LOGGING_SUBFOLDER;
    public static final String NODE_CONF_PATH = CONF_FOLDER + "/" + NODE_CONF_DIR;
    public static final String LOGGING_CONF_PATH = CONF_FOLDER + "/" + LOGGING_CONF_DIR;
    public static final String DEFAULT_PROPERTIES_NAME = "node-default.properties";
    public static String PROPERTIES_NAME = "node.properties";
    public static final String DEFAULT_LOGGING_PROPERTIES_NAME = "logging-default.properties";
    public static String LOGGING_PROPERTIES_NAME = "logging.properties";

    /**
     * Stores log messages produced during the bootstrap phase before the GUI is
     * ready.
     */
    public static final List<String> BOOTSTRAP_LOGS = java.util.Collections
            .synchronizedList(new java.util.ArrayList<>());

    public static final Option CONF_FOLDER_OPTION = Option.builder("c")
            .longOpt("config")
            .argName("conf folder")
            .numberOfArgs(1)
            .desc("The configuration folder to use")
            .build();

    public static final Options CLI_OPTIONS = new Options()
            .addOption(CONF_FOLDER_OPTION)
            .addOption(Option.builder("l")
                    .longOpt("headless")
                    .desc("Run in headless mode")
                    .build())
            .addOption(Option.builder("h")
                    .longOpt("help")
                    .build());

    private static Logger logger;

    private static Stores stores;
    private static Dbs dbs;

    private static ThreadPool threadPool;

    private static BlockchainImpl blockchain;
    private static BlockchainProcessorImpl blockchainProcessor;
    private static TransactionProcessorImpl transactionProcessor;
    private static TransactionService transactionService;
    private static SubscriptionService subscriptionService;
    private static AssetExchange assetExchange;
    private static Generator generator;

    private static PropertyService propertyService;
    private static FluxCapacitor fluxCapacitor;

    private static DBCacheManagerImpl dbCacheManager;

    private static WebServer webServer;

    private static ShutdownManager shutdownManager;

    private static AtomicBoolean isShutdown = new AtomicBoolean(false);
    private static AtomicBoolean nodeStopped = new AtomicBoolean(false);
    private static AtomicBoolean isInitialized = new AtomicBoolean(false); // New flag for initialization

    private static PropertyService loadProperties(String confFolder) {
        Path confPath = PathUtils.resolvePath(confFolder);
        CaselessProperties properties = new CaselessProperties();
        Path nodePath = confPath.resolve(NODE_CONF_DIR);

        if (logger != null)
            logger.info("Initializing Signum Node version {}", VERSION);
        if (logger != null)
            logger.info("Configurations from folder {}", confPath.toAbsolutePath());

        Path fileToLoad = resolvePropertiesPath(nodePath, PROPERTIES_NAME, DEFAULT_PROPERTIES_NAME, confPath);

        if (fileToLoad != null) {
            try (Reader reader = new InputStreamReader(new FileInputStream(fileToLoad.toFile()),
                    StandardCharsets.UTF_8)) {
                if (logger != null)
                    logger.info("Loading properties from {}", fileToLoad.toAbsolutePath());
                properties.load(reader);
                // Update global variable to reflect actual file used
                PROPERTIES_NAME = nodePath.relativize(fileToLoad).toString();
            } catch (IOException e) {
                if (logger != null) {
                    Path fileName = fileToLoad.getFileName();
                    logger.warn("Error loading {}, using internal defaults.",
                            fileName != null ? fileName.toString() : "properties");
                }
            }
        } else {
            if (logger != null)
                logger.info("No property files found in {}. Using internal defaults.", nodePath);
        }

        // Ensure SETTINGS_DIR is set if not in file
        if (properties.getProperty(Props.SETTINGS_DIR.getName()) == null) {
            properties.setProperty(Props.SETTINGS_DIR.getName(), Props.SETTINGS_DIR.getDefaultValue());
        }

        return new PropertyServiceImpl(properties);
    }

    public static Path resolvePropertiesPath(Path dir, String fileName, String defaultFileName, Path confPath) {
        // 1. Priority: check appliedProfile in profile.json
        Path profileJsonPath = dir.resolve("profile.json");
        if (Files.exists(profileJsonPath)) {
            try (BufferedReader reader = Files.newBufferedReader(profileJsonPath, StandardCharsets.UTF_8)) {
                JsonObject profileJson = JsonParser.parseReader(reader).getAsJsonObject();
                if (profileJson.has("appliedProfile")) {
                    String appliedProfileName = profileJson.get("appliedProfile").getAsString();
                    if (appliedProfileName != null && !appliedProfileName.isEmpty()) {
                        Path profileProps = dir.resolve(appliedProfileName + ".properties");
                        if (Files.exists(profileProps)) {
                            return profileProps;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // 2. Priority: dir/fileName
        Path propsFile = dir.resolve(fileName);
        if (Files.exists(propsFile)) {
            return propsFile;
        }
        // 3. Priority: dir/defaultFileName
        Path defaultInDir = dir.resolve(defaultFileName);
        if (Files.exists(defaultInDir)) {
            return defaultInDir;
        }
        // 4. Priority: conf/defaultFileName
        Path defaultInConf = confPath.resolve(defaultFileName);
        if (Files.exists(defaultInConf)) {
            return defaultInConf;
        }
        return null;
    }

    private Signum() {
    } // never

    public static Blockchain getBlockchain() {
        return blockchain;
    }

    public static BlockchainProcessor getBlockchainProcessor() {
        return blockchainProcessor;
    }

    public static Generator getGenerator() {
        return generator;
    }

    public static TransactionProcessorImpl getTransactionProcessor() {
        return transactionProcessor;
    }

    public static TransactionService getTransactionService() {
        return transactionService;
    }

    public static SubscriptionService getSubscriptionService() {
        return subscriptionService;
    }

    public static AssetExchange getAssetExchange() {
        return assetExchange;
    }

    public static Stores getStores() {
        return stores;
    }

    public static Dbs getDbs() {
        return dbs;
    }

    /**
     * The main entry point for the node.
     *
     * @param args arguments for the node.
     */
    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(Signum::shutdown));
        String confFolder = CONF_FOLDER;
        try {
            CommandLine cmd = new DefaultParser().parse(CLI_OPTIONS, args, true);
            if (cmd.hasOption(CONF_FOLDER_OPTION.getOpt())) {
                confFolder = cmd.getOptionValue(CONF_FOLDER_OPTION.getOpt());
            }
        } catch (Exception e) {
            System.err.println("Exception parsing command line arguments: " + e.getMessage());
        }
        init(confFolder);
    }

    public static void setLogger(Logger l) {
        logger = l;
    }

    private static void ensureLogger() {
        if (logger == null)
            logger = LoggerFactory.getLogger(Signum.class);
    }

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

    // Init shutdown flags in case of restart node
    public static void initShutdown() {
        isShutdown.set(false);
        nodeStopped.set(false);
        isInitialized.set(false);
    }

    public static void init(CaselessProperties customProperties) {
        if (isInitialized.compareAndSet(false, true)) {
            ensureLogger();
            loadWallet(new PropertyServiceImpl(customProperties));
        } else {
            if (logger != null)
                logger.warn("Signum node already initialized. Skipping re-initialization.");
        }
    }

    public static void init(String confFolder) {
        if (!isInitialized.compareAndSet(false, true)) {
            if (logger != null)
                logger.warn("Signum node already initialized. Skipping.");
            return;
        }

        ensureLogger();

        // Resolve logging properties priority
        Path confPath = PathUtils.resolvePath(confFolder);
        Path loggingPath = confPath.resolve(LOGGING_CONF_DIR);
        Path loggingFileToLoad = resolvePropertiesPath(loggingPath, LOGGING_PROPERTIES_NAME,
                DEFAULT_LOGGING_PROPERTIES_NAME, confPath);
        if (loggingFileToLoad != null) {
            LOGGING_PROPERTIES_NAME = loggingPath.relativize(loggingFileToLoad).toString();
        }

        PropertyService propertyService = loadProperties(confFolder);
        loadWallet(propertyService);
    }

    private static void loadWallet(PropertyService propertyService) {
        Signum.propertyService = propertyService;

        shutdownManager = new ShutdownManager(propertyService);

        String networkParametersClass = propertyService.getString(Props.NETWORK_PARAMETERS);
        NetworkParameters params = null;
        if (networkParametersClass != null && !networkParametersClass.trim().isEmpty()
                && !"null".equalsIgnoreCase(networkParametersClass)) {
            try {
                params = (NetworkParameters) Class
                        .forName(networkParametersClass)
                        .getConstructor()
                        .newInstance();
                propertyService.setNetworkParameters(params);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load network parameters class: " + networkParametersClass, e);
            }
        }

        if (!validateVersionNotDev(propertyService)) {
            return;
        }

        try {
            final long startTime = System.currentTimeMillis();

            // Address prefix and coin name
            SignumUtils.setAddressPrefix(propertyService.getString(Props.ADDRESS_PREFIX));
            // TODO: change to coin name
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
                    dbs.getBlockDb(), params);

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

            downloadCache.setBlockchainProcessor(blockchainProcessor);

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
            logger.error("Failed to initialize Signum node", e);
            throw new RuntimeException("Failed to initialize Signum node", e);
        }
        Thread consoleThread = new Thread(Signum::commandHandler);
        consoleThread.setName("Console Command Handler");
        consoleThread.setDaemon(true);
        consoleThread.start();
    }

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

    private static void commandHandler() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        try {
            String command;
            while ((command = reader.readLine()) != null) {
                processCommand(command);
            }
        } catch (IOException e) {
            // ignore
        }
    }

    public static void processCommand(String command) {
        logger.debug("received command: >{}<", command);
        if (command.equals(".shutdown")) {
            shutdown(false);
            System.exit(0);
        } else if (command.equals(".restart")) {
            signum.Launcher.restart();
        } else if (command.equals(".autoresolve")) {
            blockchainProcessor.manualResolveDatabaseConsistency();
        } else if (command.equals(".pause") || command.equals(".stop")) {
            blockchainProcessor.setSyncPaused(true);
        } else if (command.equals(".resume")) {
            blockchainProcessor.setSyncPaused(false);
        } else if (command.equals(".trim")) {
            blockchainProcessor.scheduleTrim(blockchain.getLastBlock());
        } else if (command.equals(".dbcheck")) {
            blockchainProcessor.checkDatabaseStateRequest();
        } else if (command.equals(".help")) {
            logger.info("Available commands:");
            logger.info("  .shutdown     - Gracefully shuts down the node.");
            logger.info("  .restart      - Restarts the node application.");
            logger.info("  .pause        - Pauses blockchain synchronization.");
            logger.info("  .resume       - Resumes blockchain synchronization.");
            logger.info("  .autoresolve  - Triggers manual database consistency resolution.");
            logger.info("  .trim         - Schedules a database trim.");
            logger.info("  .dbcheck      - Performs a database consistency check.");
            logger.info("  .popoff <n>   - Pops off the last n blocks from the blockchain (e.g., .popoff 10).");
            logger.info("  .help         - Displays this help message.");
        } else if (command.startsWith(".popoff ")) {
            Pattern r = Pattern.compile("^\\.popoff (\\d+)$");
            Matcher m = r.matcher(command);
            if (m.find()) {
                int numBlocks = Integer.parseInt(m.group(1));
                if (numBlocks > 0) {
                    blockchainProcessor.popOffTo(blockchain.getHeight() - numBlocks);
                }
            }
        } else if (!command.trim().isEmpty()) {
            logger.info("Unknown command: \"{}\". Type .help to see the list of available commands.", command);
        }
    }

    private static void shutdown() {
        shutdown(false);
    }

    /**
     * Cleans up the node prior to shutting down.
     *
     * @param ignoreDbShutdown if true, shuts down everything but the database.
     */
    public static void shutdown(boolean ignoreDbShutdown) {

        if (isShutdown.get() && !nodeStopped.get()) {
            logger.info("Already shutting down...");
        }

        synchronized (isShutdown) {

            if (isShutdown.getAndSet(true)) {
                return;
            }

            if (shutdownManager != null) {
                shutdownManager.startShutdown();
            }

            logger.info("Shutting down...");
            logger.info("Do not force exit or kill the node process.");

            if (webServer != null) {
                try {
                    webServer.shutdown();
                } catch (Throwable t) {
                    if (shutdownManager != null) {
                        shutdownManager.markFailure("WebServer");
                    }
                    logger.error("Error shutting down webServer", t);
                }
            }

            if (blockchainProcessor != null) {
                try {
                    blockchainProcessor.shutdown();
                } catch (Throwable t) {
                    if (shutdownManager != null) {
                        shutdownManager.markFailure("BlockchainProcessor");
                    }
                    logger.error("Error shutting down blockchainProcessor", t);
                }
            }

            if (threadPool != null) {
                try {
                    Peers.shutdown(threadPool);
                } catch (Throwable t) {
                    if (shutdownManager != null) {
                        shutdownManager.markFailure("Peers");
                    }
                    logger.error("Error shutting down Peers", t);
                }
            }

            if (threadPool != null) {
                try {
                    threadPool.shutdown();
                } catch (Throwable t) {
                    if (shutdownManager != null) {
                        shutdownManager.markFailure("ThreadPool");
                    }
                    logger.error("Error shutting down threadPool", t);
                }
            }

            if (dbCacheManager != null) {
                try {
                    dbCacheManager.close();
                } catch (Throwable t) {
                    if (shutdownManager != null) {
                        shutdownManager.markFailure("DBCacheManager");
                    }
                    logger.error("Error closing dbCacheManager", t);
                }
            }

            if (!ignoreDbShutdown) {
                try {
                    Db.shutdown();
                } catch (Throwable t) {
                    if (shutdownManager != null) {
                        shutdownManager.markFailure("Database");
                    }
                    logger.error("Error shutting down DB", t);
                }
            }

            if (shutdownManager != null) {
                shutdownManager.finishShutdown();
            }
            logger.info("BRS {} stopped.", VERSION);
            LoggerConfigurator.shutdown();
            nodeStopped.set(true);

        }
    }

    public static PropertyService getPropertyService() {
        return propertyService;
    }

    public static FluxCapacitor getFluxCapacitor() {
        return fluxCapacitor;
    }

}
