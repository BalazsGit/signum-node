package application.module.node;

import application.module.node.assetexchange.AssetExchange;
import application.module.node.assetexchange.AssetExchangeImpl;
import application.module.node.at.AT;
import application.module.node.at.AtConstants;
import application.module.node.db.BlockDb;
import application.module.node.db.TransactionDb;
import application.module.node.db.cache.DBCacheManagerImpl;
import application.module.node.db.sql.Db;
import application.module.node.db.sql.DbContext;
import application.module.node.db.sql.StoreDependencies;
import application.module.node.db.store.BlockchainStore;
import application.module.node.db.store.Dbs;
import application.module.node.db.store.DerivedTableManager;
import application.module.node.db.store.Stores;
import application.module.node.deeplink.DeeplinkQRCodeGenerator;
import application.module.node.feesuggestions.FeeSuggestionCalculator;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxCapacitorImpl;
import application.module.node.peer.Peers;
import application.module.node.props.CaselessProperties;
import application.module.node.props.PropertyService;
import application.module.node.props.PropertyServiceImpl;
import application.module.node.props.Props;
import application.module.node.services.ATService;
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
import application.module.node.at.ATServiceImpl;
import application.module.node.services.impl.AccountServiceImpl;
import application.module.node.services.impl.AliasServiceImpl;
import application.module.node.services.impl.BlockServiceImpl;
import application.module.node.services.impl.DGSGoodsStoreServiceImpl;
import application.module.node.services.impl.EscrowServiceImpl;
import application.module.node.services.impl.IndirectIncomingServiceImpl;
import application.module.node.services.impl.ParameterServiceImpl;
import application.module.node.services.impl.SubscriptionServiceImpl;
import application.module.node.services.impl.TimeServiceImpl;
import application.module.node.services.impl.TransactionServiceImpl;
import application.module.node.statistics.StatisticsManagerImpl;
import application.module.node.util.DownloadCacheImpl;
import application.module.node.util.LoggerConfigurator;
import application.module.node.util.ThreadPool;
import application.module.node.util.Time;
import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.APITransactionManagerImpl;
import application.module.node.web.server.WebServer;
import application.module.node.web.server.WebServerContext;
import application.module.node.web.server.WebServerImpl;
import application.module.node.instance.NodeCoreContext;
import application.module.node.instance.NodeCoreContextBuilder;
import application.module.node.profile.NodeProfile;
import application.utils.config.ConfigPaths;
import application.utils.config.PropertiesProfileLoader;
import application.utils.io.PathUtils;

import java.util.Objects;

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

    public static final String CONF_FOLDER = "./conf";
    public static final String DATABASE_SUBFOLDER = "database";
    public static final String NODE_SUBFOLDER = NodeModule.ID;
    public static final String NODE_LOGGING_SUBFOLDER = NODE_SUBFOLDER + "/logging";

    public static final String NODE_CONF_DIR = NODE_SUBFOLDER;
    public static final String LOGGING_CONF_DIR = NODE_LOGGING_SUBFOLDER;
    public static final String NODE_CONF_PATH = CONF_FOLDER + "/" + NODE_CONF_DIR;
    public static final String LOGGING_CONF_PATH = CONF_FOLDER + "/" + LOGGING_CONF_DIR;
    public static final String DEFAULT_PROPERTIES_NAME = "node-default";
    public static final String PROPERTIES_NAME = "node";
    public static final String DEFAULT_LOGGING_PROPERTIES_NAME = "logging-default";
    public static final String LOGGING_PROPERTIES_NAME = "logging";

    private static String activeNodeProfile = PROPERTIES_NAME;
    private static String activeLoggingProfile = LOGGING_PROPERTIES_NAME;

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
    private static AccountService accountService;
    private static AliasService aliasService;
    private static DGSGoodsStoreService dgsGoodsStoreService;
    private static EscrowService escrowService;
    private static Generator generator;

    private static PropertyService propertyService;
    private static FluxCapacitor fluxCapacitor;

    private static DBCacheManagerImpl dbCacheManager;

    private static WebServer webServer;

    private static AtConstants atConstants;

    private static ShutdownManager shutdownManager;

    /**
     * Sets the ShutdownManager instance from the owning NodeCoreContext.
     * The context creates a profile-scoped ShutdownManager and registers it
     * here so that the legacy static shutdown path in {@link #shutdown(boolean)}
     * can still access it.
     *
     * @param manager the ShutdownManager for this profile
     */
    public static void setShutdownManager(ShutdownManager manager) {
        shutdownManager = manager;
    }

    private static AtomicBoolean isShutdown = new AtomicBoolean(false);
    private static AtomicBoolean nodeStopped = new AtomicBoolean(false);
    private static AtomicBoolean isInitialized = new AtomicBoolean(false); // New flag for initialization

    /**
     * Loads properties for a specific named profile using the unified PropertiesProfileLoader.
     * Architecture: Hardcoded defaults (Props.java) → Profile .properties file only.
     * Path schema: ../conf/node/profiles/{profileName}.properties
     *
     * @param confFolder  The base configuration folder (e.g., "../conf")
     * @param profileName The profile name to load (without .properties extension)
     * @return PropertyService with loaded properties, or empty one if file not found
     */
    public static PropertyService loadPropertiesForProfile(String confFolder, String profileName) {
        CaselessProperties properties = new CaselessProperties();

        // Use unified profile loader for path resolution: ../conf/node/profiles/{profileName}.properties
        Path profileFile = PropertiesProfileLoader.resolveProfileFile(
                ConfigPaths.RUNTIME_CONF_ROOT, "node", "profiles", profileName);

        if (logger != null)
            logger.info("Initializing Signum Node version {}", VERSION);
        if (logger != null)
            logger.info("Looking for profile '{}' at {}", profileName, profileFile.toAbsolutePath());

        if (Files.exists(profileFile)) {
            try (Reader reader = new InputStreamReader(new FileInputStream(profileFile.toFile()),
                    StandardCharsets.UTF_8)) {
                if (logger != null)
                    logger.info("Loading profile '{}' from {}", profileName, profileFile.toAbsolutePath());
                properties.load(reader);
                activeNodeProfile = profileName;
            } catch (IOException e) {
                if (logger != null) {
                    logger.warn("Error loading profile '{}', using internal defaults.", profileName, e);
                }
            }
        } else {
            if (logger != null)
                logger.info("No profile file found for '{}'. Using internal defaults from Props.java.", profileName);
        }

        // Ensure SETTINGS_DIR is set if not in file
        if (properties.getProperty(Props.SETTINGS_DIR.getName()) == null) {
            properties.setProperty(Props.SETTINGS_DIR.getName(), Props.SETTINGS_DIR.getDefaultValue());
        }

        return new PropertyServiceImpl(properties);
    }

    /**
     * Legacy method for backward compatibility.
     * Loads a profile by name using the simplified architecture.
     */
    private static PropertyService loadProperties(String confFolder) {
        // Default to first available profile or empty
        String defaultProfile = PROPERTIES_NAME;
        return loadPropertiesForProfile(confFolder, defaultProfile);
    }

    public static String getActiveNodeProfile() {
        return activeNodeProfile;
    }

    public static void setActiveNodeProfile(String profile) {
        activeNodeProfile = profile;
    }

    public static String getActiveLoggingProfile() {
        return activeLoggingProfile;
    }

    public static void setActiveLoggingProfile(String profile) {
        activeLoggingProfile = profile;
    }

    /**
     * Resolves a properties file path directly from the given directory.
     * Simplified: no cascade, no fallbacks, just direct file lookup.
     *
     * @param dir        The directory to look in
     * @param fileName   The exact filename to resolve (e.g., "myprofile.properties")
     * @param confPath   Unused (kept for signature compatibility, will be removed)
     * @return The path if the file exists, null otherwise
     * @deprecated Use direct Path.resolve() and Files.exists() instead.
     */
    @Deprecated
    public static Path resolvePropertiesPath(Path dir, String fileName, String defaultFileName, Path confPath) {
        Path propsFile = dir.resolve(fileName);
        if (Files.exists(propsFile)) {
            return propsFile;
        }
        return null;
    }

    /**
     * The NodeCoreContext owned by this Signum facade instance.
     * Null when using legacy static bootstrap path (Signum.init()).
     */
    private final NodeCoreContext context;

    /**
     * Profile identity for this Signum instance.
     * Null when using legacy static bootstrap path.
     */
    private final NodeProfile profile;

    /**
     * Private constructor for legacy static bootstrap (Signum.init/loadWallet).
     * Creates no context -- static fields are populated directly by loadWallet().
     */
    private Signum() {
        this.context = null;
        this.profile = null;
    }

    /**
     * Creates a new Signum facade instance that owns the given NodeCoreContext.
     * This is the preferred greenfield constructor for multi-node operation.
     *
     * @param profile    the node profile identity (must not be null)
     * @param confFolder the base configuration folder path (must not be null)
     */
    public Signum(NodeProfile profile, Path confFolder) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.context = new NodeCoreContextBuilder(profile.getName(), confFolder).build();
    }

    /**
     * Creates a new Signum facade instance that wraps an existing NodeCoreContext.
     * Used when the context was built externally (e.g., tests, legacy migration).
     *
     * @param profile the node profile identity (must not be null)
     * @param context the pre-built core context (must not be null)
     */
    public Signum(NodeProfile profile, NodeCoreContext context) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
    }

    // ── Lifecycle delegation ──

    /**
     * Starts the node by delegating to the owned NodeCoreContext.
     *
     * @throws application.module.node.instance.NodeStartupException if initialization fails
     */
    public void start() {
        Objects.requireNonNull(this.context, "context not available -- use legacy Signum.init() or provide context");
        this.context.start();
    }

    /**
     * Gracefully stops the node by delegating to the owned NodeCoreContext.
     */
    public void stop() {
        if (this.context != null) {
            this.context.stop();
        }
    }

    /**
     * Re-starts the node by stopping and starting the owned NodeCoreContext.
     */
    public void restart() {
        Objects.requireNonNull(this.context, "context not available");
        this.context.restart();
    }

    // ── Identity ──

    /**
     * Returns the profile identity for this Signum instance.
     *
     * @return the NodeProfile, or null if created via legacy static bootstrap
     */
    public NodeProfile getProfile() {
        return profile;
    }

    /**
     * Returns the profile name for this Signum instance.
     *
     * @return the profile name, or null if created via legacy static bootstrap
     */
    public String getProfileName() {
        return profile != null ? profile.getName() : null;
    }

    /**
     * Returns whether this node is currently running.
     */
    public boolean isRunning() {
        return this.context != null && this.context.isRunning();
    }

    // ── Context access (internal) ──

    /**
     * Returns the owned NodeCoreContext.
     * Returns null when using legacy static bootstrap path.
     *
     * @return the NodeCoreContext, or null
     */
    NodeCoreContext getContext() {
        return context;
    }

    // ── Legacy static singleton (for bridge support in Phase E2) ──

    /**
     * Active Signum instance for backwards-compatible static delegation.
     * Set during Phase E2 bridge migration so that existing callers using
     * {@code Signum.getBlockchain()} continue to work via the active facade.
     */
    private static volatile Signum activeInstance;

    /**
     * Registers a Signum instance as the active one for static bridge support.
     * This is a transitional mechanism and will be removed in Phase E4.
     *
     * @param signum the Signum instance to mark as active
     */
    static void setActive(Signum signum) {
        activeInstance = signum;
    }

    /**
     * Returns the currently active Signum instance (for bridge support).
     *
     * @return the active Signum, or null if none registered
     */
    static Signum getActiveInstance() {
        return activeInstance;
    }

    /**
     * Bridge getter: delegates to active Signum instance context when available,
     * falls back to legacy static field for backwards compatibility.
     * @deprecated Use Signum instance method or constructor injection instead.
     */
    @Deprecated
    public static Blockchain getBlockchain() {
        NodeCoreContext ctx = resolveContext();
        return ctx != null ? ctx.getBlockchain() : blockchain;
    }

    /**
     * Bridge getter: delegates to active Signum instance context when available,
     * falls back to legacy static field for backwards compatibility.
     * @deprecated Use Signum instance method or constructor injection instead.
     */
    @Deprecated
    public static BlockchainProcessor getBlockchainProcessor() {
        NodeCoreContext ctx = resolveContext();
        return ctx != null ? ctx.getBlockchainProcessor() : blockchainProcessor;
    }

    /**
     * Bridge getter: delegates to active Signum instance context when available,
     * falls back to legacy static field for backwards compatibility.
     * @deprecated Use Signum instance method or constructor injection instead.
     */
    @Deprecated
    public static Generator getGenerator() {
        NodeCoreContext ctx = resolveContext();
        return ctx != null ? ctx.getGenerator() : generator;
    }

    /**
     * Bridge getter: delegates to active Signum instance context when available,
     * falls back to legacy static field for backwards compatibility.
     * @deprecated Use Signum instance method or constructor injection instead.
     */
    @Deprecated
    public static TransactionProcessorImpl getTransactionProcessor() {
        return transactionProcessor; // Not exposed in NodeCoreContext yet
    }

    /**
     * Bridge getter: delegates to active Signum instance context when available,
     * falls back to legacy static field for backwards compatibility.
     * @deprecated Use Signum instance method or constructor injection instead.
     */
    @Deprecated
    public static TransactionService getTransactionService() {
        NodeCoreContext ctx = resolveContext();
        return ctx != null ? ctx.getTransactionService() : transactionService;
    }

    /**
     * Bridge getter: delegates to active Signum instance context when available,
     * falls back to legacy static field for backwards compatibility.
     * @deprecated Use Signum instance method or constructor injection instead.
     */
    @Deprecated
    public static SubscriptionService getSubscriptionService() {
        return subscriptionService; // Not exposed in NodeCoreContext yet
    }

    /**
     * Bridge getter: delegates to active Signum instance context when available,
     * falls back to legacy static field for backwards compatibility.
     * @deprecated Use Signum instance method or constructor injection instead.
     */
    @Deprecated
    public static AssetExchange getAssetExchange() {
        NodeCoreContext ctx = resolveContext();
        return ctx != null ? ctx.getAssetExchange() : assetExchange;
    }

    /**
     * Bridge getter: delegates to active Signum instance context when available,
     * falls back to legacy static field for backwards compatibility.
     * @deprecated Use Signum instance method or constructor injection instead.
     */
    @Deprecated
    public static Stores getStores() {
        return stores; // Not exposed in NodeCoreContext yet
    }

    /**
     * Bridge getter: delegates to active Signum instance context when available,
     * falls back to legacy static field for backwards compatibility.
     * @deprecated Use Signum instance method or constructor injection instead.
     */
    @Deprecated
    public static Dbs getDbs() {
        return dbs; // Not exposed in NodeCoreContext yet
    }

    /**
     * Resolves the NodeCoreContext from the active Signum instance, if registered.
     * Returns null when no active instance is set (legacy mode).
     */
    private static NodeCoreContext resolveContext() {
        Signum active = activeInstance;
        return active != null ? active.getContext() : null;
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

    /**
     * Shuts down the node core services.
     */
    public static void shutdownNode() {
        shutdown(false);
    }

    /**
     * Re-initializes and starts the node core services.
     */
    public static void startNode() {
        initShutdown();
        init(CONF_FOLDER);
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

        // Initialize unified profile system: sync default files (hash-based) + create empty placeholders if needed
        // This ensures ../conf/node/profiles/ and ../conf/node/logging/ directories exist with proper defaults
        NodeProfile.initialize();

        // Resolve logging properties priority
        Path confPath = PathUtils.resolvePath(confFolder);
        Path loggingPath = confPath.resolve(LOGGING_CONF_DIR);
        Path loggingFileToLoad = resolvePropertiesPath(loggingPath, LOGGING_PROPERTIES_NAME + ".properties",
                DEFAULT_LOGGING_PROPERTIES_NAME + ".properties", confPath);

        try {
            if (Files.notExists(loggingPath)) {
                Files.createDirectories(loggingPath);
                logger.info("Created missing logging configuration directory: {}", loggingPath.toAbsolutePath());
            }
        } catch (IOException e) {
            logger.warn("Failed to create logging configuration directory: {}", loggingPath.toAbsolutePath(), e);
        }
        if (loggingFileToLoad != null) {
            String fileName = loggingPath.relativize(loggingFileToLoad).toString();
            if (fileName.endsWith(".properties")) {
                activeLoggingProfile = fileName.substring(0, fileName.length() - 11);
            }
        }

        PropertyService propertyService = loadProperties(confFolder);
        loadWallet(propertyService);
    }

    private static void ensureDatabaseDirectory(PropertyService propertyService) {
        String dbUrl = propertyService.getString(Props.DB_URL);
        if (dbUrl != null && dbUrl.toLowerCase().startsWith("jdbc:sqlite:")) {
            String pathPart = dbUrl.substring("jdbc:sqlite:".length());

            // Handle file URIs according to RFC 2396 (e.g. file:./db/ or file:///path/)
            if (pathPart.toLowerCase().startsWith("file:")) {
                pathPart = pathPart.substring(5);
                // Clean unnecessary leading slashes for local filesystem
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
                // Resolve the path using PathUtils. This ensures that relative
                // paths are anchored to the application root, regardless of
                // the CWD (Current Working Directory).
                Path dbPath = PathUtils.resolvePath(pathPart);
                Path parent = dbPath.getParent();
                if (parent != null && Files.notExists(parent)) {
                    Files.createDirectories(parent);
                    logger.info("Created missing database directory: {}", parent.toAbsolutePath());
                }
            } catch (Exception e) {
                logger.warn("Failed to ensure database directory exists: {}", e.getMessage());
            }
        }
    }

    private static void loadWallet(PropertyService propertyService) {
        Signum.propertyService = propertyService;

        ensureDatabaseDirectory(propertyService);

        // ShutdownManager is now created by NodeCoreContext.doInitialize()
        // with the correct profile name. It is registered via setShutdownManager().

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

            DbContext dbContext = Db.init(propertyService, dbCacheManager);
            dbs = Db.getDbsByDatabaseType();

            final StoreDependencies storeDependencies = new StoreDependencies(
                    null, // blockchain - wired later via Stores.wireDependencies()
                    propertyService,
                    null, // fluxCapacitor - created after blockchain
                    dbs,
                    dbContext);

            stores = new Stores(derivedTableManager, dbCacheManager, timeService, propertyService,
                    dbs.getTransactionDb(),
                    dbs.getBlockDb(), params, storeDependencies);

            final TransactionDb transactionDb = dbs.getTransactionDb();
            final BlockDb blockDb = dbs.getBlockDb();
            final BlockchainStore blockchainStore = stores.getBlockchainStore();
            blockchain = new BlockchainImpl(
                    transactionDb,
                    blockDb,
                    blockchainStore,
                    propertyService);

            stores.getUnconfirmedTransactionStore().setBlockchain(blockchain);

            fluxCapacitor = new FluxCapacitorImpl(blockchain, propertyService);
            Signum.aliasService = new AliasServiceImpl(
                    stores.getAliasStore(),
                    stores,
                    fluxCapacitor,
                    propertyService);
            final AliasService aliasService = Signum.aliasService;
            aliasService.addDefaultTLDs();

            EconomicClustering economicClustering = new EconomicClustering(blockchain);

            Signum.accountService = new AccountServiceImpl(
                    stores.getAccountStore(),
                    stores.getAssetTransferStore(),
                    blockchain);
            final AccountService accountService = Signum.accountService;

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
                    fluxCapacitor,
                    aliasService,
                    stores.getAliasStore(),
                    accountService);
            ((AliasServiceImpl) aliasService).setSubscriptionService(subscriptionService);
            Signum.dgsGoodsStoreService = new DGSGoodsStoreServiceImpl(
                    blockchain,
                    stores.getDigitalGoodsStoreStore(),
                    accountService);
            final DGSGoodsStoreService digitalGoodsStoreService = Signum.dgsGoodsStoreService;
            Signum.escrowService = new EscrowServiceImpl(
                    stores.getEscrowStore(),
                    blockchain,
                    aliasService,
                    accountService,
                    transactionDb);
            final EscrowService escrowService = Signum.escrowService;

            assetExchange = new AssetExchangeImpl(
                    blockchain,
                    accountService,
                    stores.getTradeStore(),
                    stores.getAccountStore(),
                    stores.getAssetTransferStore(),
                    stores.getAssetStore(),
                    stores.getOrderStore());

            final IndirectIncomingService indirectIncomingService = new IndirectIncomingServiceImpl(
                    stores.getIndirectIncomingStore(), propertyService);


            final BlockService blockService = new BlockServiceImpl(
                    accountService,
                    transactionService,
                    blockchain,
                    downloadCache,
                    generator,
                    fluxCapacitor,
                    propertyService,
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
                    aliasService,
                    fluxCapacitor,
                    atService);

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

            // Peers.init() removed - handled by NodeCoreContext.start() via PeerManager (Phase B)
            // See: NodeCoreContext.initServicesAndHooks() → peerManager.start()
            if (params != null) {
                params.initialize(parameterService, accountService, apiTransactionManager);
                TransactionType.setNetworkParameters(params);
            }

            final FeeSuggestionCalculator feeSuggestionCalculator = new FeeSuggestionCalculator(
                    blockchainProcessor,
                    stores.getUnconfirmedTransactionStore(),
                    blockchain,
                    fluxCapacitor);

            final AtConstants atConstantsLocal = new AtConstants(fluxCapacitor);
            Signum.atConstants = atConstantsLocal;

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
                    params,
                    atConstantsLocal,
                     fluxCapacitor));
            webServer.start();

            // DebugTrace.init() removed - Phase C: Static DebugTrace will be migrated to instance scope
            // DebugTrace uses static service refs (assetExchange, dgsGoodsStoreService) which breaks multi-node isolation
            // To re-enable: refactor DebugTrace to accept instance-scoped dependencies via constructor or init(TransactionApplyContext)
            // See: greenfield_multi_node_refactoring_roadmap.md Phase C

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
        ensureLogger();
        logger.debug("received command: >{}<", command);

        // Resolve active context for greenfield mode, fall back to static fields for legacy mode
        NodeCoreContext ctx = getActiveInstance() != null ? getActiveInstance().getContext() : null;
        BlockchainProcessor proc = ctx != null ? ctx.getBlockchainProcessor() : blockchainProcessor;
        Blockchain chain = ctx != null ? ctx.getBlockchain() : blockchain;

        if (command.equals(".shutdown")) {
            shutdown(false);
            System.exit(0);
        } else if (command.equals(".restart")) {
            application.launcher.Launcher.restart();
        } else if (command.equals(".autoresolve")) {
            if (proc != null) proc.manualResolveDatabaseConsistency();
        } else if (command.equals(".pause") || command.equals(".stop")) {
            if (proc != null) proc.setSyncPaused(true);
        } else if (command.equals(".resume")) {
            if (proc != null) proc.setSyncPaused(false);
        } else if (command.equals(".trim")) {
            if (proc != null && chain != null) proc.scheduleTrim(chain.getLastBlock());
        } else if (command.equals(".dbcheck")) {
            if (proc != null) proc.checkDatabaseStateRequest();
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
                if (numBlocks > 0 && proc != null && chain != null) {
                    proc.popOffTo(chain.getHeight() - numBlocks);
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
     * Supports both legacy static mode and greenfield context-based mode.
     * When an active Signum instance owns a NodeCoreContext, that context.stop()
     * handles the full teardown. Otherwise falls back to legacy static cleanup.
     *
     * @param ignoreDbShutdown if true, shuts down everything but the database.
     */
    public static void shutdown(boolean ignoreDbShutdown) {
        ensureLogger();

        // Greenfield mode: delegate to active Signum's NodeCoreContext.stop()
        Signum active = getActiveInstance();
        if (active != null && active.getContext() != null) {
            logger.info("Greenfield shutdown: delegating to Signum[{}] context.stop()",
                    active.getProfileName());
            active.getContext().stop();
            isShutdown.set(true);
            nodeStopped.set(true);
            LoggerConfigurator.shutdown();
            return;
        }

        // Legacy static mode
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

    /**
     * @deprecated Use NodeCoreContext.getAccountService() for multi-profile support.
     * Bridge accessor for transitional compatibility during TransactionType refactoring.
     */
    @Deprecated
    public static AccountService getAccountService() {
        return accountService;
    }

    /**
     * @deprecated Use NodeCoreContext.getAliasService() for multi-profile support.
     * Bridge accessor for transitional compatibility during TransactionType refactoring.
     */
    @Deprecated
    public static AliasService getAliasService() {
        return aliasService;
    }

    /**
     * @deprecated Use NodeCoreContext.getEscrowService() for multi-profile support.
     * Bridge accessor for transitional compatibility during TransactionType refactoring.
     */
    @Deprecated
    public static EscrowService getEscrowService() {
        return escrowService;
    }

    /**
     * @deprecated Use NodeCoreContext.getDgsGoodsStoreService() for multi-profile support.
     * Bridge accessor for transitional compatibility during TransactionType refactoring.
     */
    @Deprecated
    public static DGSGoodsStoreService getDgsGoodsStoreService() {
        return dgsGoodsStoreService;
    }

    /**
     * @deprecated Use WebServerContext.getAtConstants() or TransactionApplyContext.getAtConstants().
     * Bridge accessor for transitional compatibility during AT module singleton elimination.
     */
    @Deprecated
    public static AtConstants getAtConstants() {
        return atConstants;
    }

}
