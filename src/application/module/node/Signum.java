package application.module.node;

import application.module.node.props.CaselessProperties;
import application.module.node.props.PropertyService;
import application.module.node.props.PropertyServiceImpl;
import application.module.node.props.Props;
import application.module.node.web.server.WebServer;
import application.module.node.instance.NodeStartupException;
import application.module.node.profile.NodeProfile;
import application.utils.config.PropertiesProfileLoader;
import application.utils.io.PathUtils;

import application.module.node.at.AT;
import application.module.node.at.AtConstants;
import application.module.node.at.ATProcessorCache;
import application.module.node.at.ATProcessingContext;
import application.module.node.at.ATServiceImpl;
import application.module.node.at.AtController;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.assetexchange.AssetExchangeImpl;
import application.module.node.db.BlockDb;
import application.module.node.db.TransactionDb;
import application.module.node.db.cache.DBCacheManagerImpl;
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
import application.module.node.web.server.WebServerContext;
import application.module.node.web.server.WebServerImpl;
import application.utils.logging.ProfileLogger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import signum.net.NetworkParameters;
import signumj.util.SignumUtils;

/**
 * The main class of the Signum node.
 * <p>
 * Represents a single independent Signum node instance.
 * Owns all runtime components directly.
 * Provides state machine, lifecycle management, and component access.
 * </p>
 */
public final class Signum {

    // =========================================================================
    // Public constants (safe – no instance state)
    // =========================================================================

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

    // =========================================================================
    // State Machine
    // =========================================================================

    public enum State { CREATED, INITIALIZED, STARTING, RUNNING, STOPPING, STOPPED, ERROR }

    public enum OperatingState { SYNC_IDLE, SYNCING, PAUSED_USER, PAUSED_SYSTEM, GENERATING }

    @FunctionalInterface
    public interface StateListener {
        void onStateChanged(Signum signum, State oldState, State newState);
        default void onOperatingStateChanged(Signum signum, OperatingState oldState, OperatingState newState) {}
        default void onStatusMessage(Signum signum, String message) {}
        default void onError(Signum signum, String message) {}
    }

    private volatile State state = State.CREATED;
    private final List<StateListener> stateListeners = new CopyOnWriteArrayList<>();

    /**
     * Tracks operating substate with hysteresis.
     */
    private volatile OperatingState operatingState = OperatingState.SYNC_IDLE;
    private volatile long missingBlocks = 0;
    /** Reason the node is currently paused (null when not paused). */
    private volatile String pauseReason;

    // =========================================================================
    // Instance state
    // =========================================================================

    private NodeProfile profile;
    private Path confFolder;

    // =========================================================================
    // GUI panel (owned by this Signum — non-null only in GUI mode)
    // =========================================================================

    /**
     * The GUI profile panel for this specific Signum, owned by the node itself.
     * <p>
     * In the multi-node architecture there is no "active node": every GUI element
     * belongs to a specific {@code Signum} instance. When the application runs in
     * GUI mode, {@link application.module.node.gui.NodeProfilePanel} registers itself
     * here via {@link #setGuiPanel(Object)}. In headless mode this stays {@code null}.
     * </p>
     * <p>
     * Declared as {@code Object} to keep this class decoupled from the Swing GUI
     * package; the concrete type is {@code NodeProfilePanel}.
     * </p>
     */
    private volatile Object guiPanel;

    /**
     * Sets the GUI profile panel owned by this node (called by the panel on construction).
     *
     * @param panel the NodeProfilePanel for this Signum, or {@code null} to detach
     */
    public void setGuiPanel(Object panel) {
        this.guiPanel = panel;
    }

    /**
     * Returns the GUI profile panel owned by this node, or {@code null} in headless mode.
     *
     * @return the NodeProfilePanel (concrete type) or {@code null}
     */
    public Object getGuiPanel() {
        return guiPanel;
    }

    // =========================================================================
    // Component fields (absorbed from NodeCoreContext)
    // =========================================================================

    /** Resolved properties for this profile. */
    private PropertyService propertyService;

    // ── Infrastructure ──
    private DbContext dbContext;
    private DBCacheManagerImpl dbCacheManager;
    private Stores stores;
    private Dbs dbs;
    private DerivedTableManager derivedTableManager;
    private StatisticsManagerImpl statisticsManager;
    private ThreadPool threadPool;
    private WebServer webServer;
    private ShutdownManager shutdownManager;

    // ── Network & Mining ──
    private NetworkParameters networkParameters;
    private DownloadCacheImpl downloadCache;
    private EconomicClustering economicClustering;

    // ── Blockchain components ──
    private BlockchainImpl blockchain;
    private BlockchainProcessor blockchainProcessor;
    private TransactionProcessorImpl transactionProcessor;
    private Generator generator;
    private FluxCapacitor fluxCapacitor;

    // ── Service layer ──
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

    // ── Web/API components ──
    private APITransactionManager apiTransactionManager;
    private FeeSuggestionCalculator feeSuggestionCalculator;
    private DeeplinkQRCodeGenerator deeplinkQRCodeGenerator;
    private TransactionApplyContext transactionApplyContext;
    private AtConstants atConstants;
    private ATProcessorCache atProcessorCache;
    private ATProcessingContext atProcessingContext;
    private PeerManager peerManager;
    private DebugTrace debugTrace;

    // ── Logging (per-node instance) ──
    private ProfileLogger profileLogger;

    // ── Lifecycle flags ──
    private final AtomicBoolean contextStarted = new AtomicBoolean(false);
    private final AtomicBoolean contextStopped = new AtomicBoolean(false);

    // =========================================================================
    // Logging (class-level, stateless; per-node routing goes through ProfileLogger)
    // =========================================================================

    private static final Logger LOGGER = LoggerFactory.getLogger(Signum.class);

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Creates a new Signum instance with the given profile.
     *
     * @param profile    the node profile identity (must not be null)
     * @param confFolder the base configuration folder path (must not be null)
     */
    public Signum(NodeProfile profile, Path confFolder) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.confFolder = Objects.requireNonNull(confFolder, "confFolder must not be null");

        // ── Step 1: Obtain the per-node ProfileLogger (moved here from doInitialize) ──
        // Acquiring it in the constructor — before init()/start() — lets GUI consoles
        // attach before the node starts, and ProfileLogger's replay buffer guarantees
        // that no log line is lost even when the subscriber attaches late: every event
        // logged before the attach is replayed to it in chronological order.
        // getOrCreate() adopts the instance the GUI panel may have created early (see
        // NodeProfilePanel), so the GUI-construction logs captured before the Signum
        // existed are preserved; otherwise it creates + registers a fresh one. Either
        // way the ProfileLogger's forwarding to SystemLogger stays disabled (the
        // SystemLoggerJulHandler already forwards), avoiding System Console duplicates.
        this.profileLogger = application.utils.logging.NodeLoggerRegistry.getOrCreate("node", profile.getName());
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Initializes the node: loads configuration, creates and wires all components.
     * <b>No side effects</b> — no port binding, no DB connections, no threads.
     * <p>
     * Must be called before {@link #start()}. After this call the state
     * transitions from {@code CREATED} to {@code INITIALIZED}.
     * </p>
     *
     * @throws IllegalStateException if already initialized or started
     */
    public synchronized void init() {
        if (this.state != State.CREATED) {
            throw new IllegalStateException("Cannot init from state " + this.state);
        }
        if (this.propertyService == null) {
            try {
                this.propertyService = Signum.loadPropertiesForProfile(this.confFolder.toString(), this.profile.getName());
            } catch (Exception e) {
                throw new NodeStartupException(
                        "Failed to load properties for profile '" + this.profile.getName() + "' from " + this.confFolder, e);
            }
        }
        setState(State.INITIALIZED);
    }

    /**
     * Starts the node: opens DB connections, binds ports, starts threads.
     * <b>Port conflicts INHIBIT startup</b> — state transitions to {@code ERROR}.
     * <p>
     * Requires prior call to {@link #init()}.
     * </p>
     *
     * @throws IllegalStateException if not initialized
     * @throws application.module.node.instance.NodeStartupException if startup fails
     */
    public synchronized void start() {
        // Route this startup's SLF4J output to this node's ProfileLogger (Node Console).
        // The SystemLoggerJulHandler forwards an event to the per-profile ProfileLogger
        // only when NodeLogContext.current() carries the profile name; otherwise every
        // line (DB init, sync start, …) goes to SystemLogger alone and the per-profile
        // console stays empty. Binding the context on the thread that runs init() +
        // doInitialize() is what lets the ProfileLogger's replay buffer actually capture
        // those lines for late-attaching GUI subscribers (v2 plan D4).
        withProfileLogContext(() -> {
            if (this.state == State.CREATED) {
                this.init();
            }
            if (this.state != State.INITIALIZED && this.state != State.STOPPED) {
                throw new IllegalStateException("Cannot start from state " + this.state);
            }
            setState(State.STARTING);
            try {
                doInitialize();
                setState(State.RUNNING);
            } catch (Exception e) {
                setState(State.ERROR);
                throw e;
            }
        });
    }

    public void stop() {
        if (this.state == State.RUNNING) {
            // Same log-context binding as start() so shutdown lines reach the profile console.
            withProfileLogContext(() -> {
                setState(State.STOPPING);
                try {
                    doShutdown();
                    setState(State.STOPPED);
                } catch (Exception e) {
                    setState(State.ERROR);
                    throw e;
                }
            });
        }
    }

    /**
     * Runs the given lifecycle block on the current thread with the
     * {@link application.utils.logging.NodeLogContext} bound to this node's profile,
     * so SLF4J log output is routed to this node's {@link ProfileLogger} (Node Console
     * tab) in addition to {@link application.utils.logging.SystemLogger}. The previous
     * context (if any) is restored afterwards.
     * <p>
     * Without this binding the startup/shutdown thread has no active profile context,
     * so the JUL bridge ({@code SystemLoggerJulHandler}) would drop those events to the
     * per-profile console — leaving the Node Console empty even though the replay buffer
     * (see {@link ProfileLogger}) is otherwise correct.
     * </p>
     *
     * @param task the lifecycle block to execute (init/doInitialize or doShutdown)
     */
    private void withProfileLogContext(Runnable task) {
        application.utils.logging.LogScope previousContext = application.utils.logging.NodeLogContext.current();
        boolean bound = this.profile != null;
        if (bound) {
            application.utils.logging.NodeLogContext.set("node", this.profile.getName());
        }
        try {
            task.run();
        } finally {
            if (bound) {
                if (previousContext != null) {
                    application.utils.logging.NodeLogContext.set(previousContext);
                } else {
                    application.utils.logging.NodeLogContext.clear();
                }
            }
        }
    }

    public void restart() {
        this.stop();
        this.start();
    }

    // =========================================================================
    // Identity
    // =========================================================================

    public NodeProfile getProfile() {
        return profile;
    }

    public String getProfileName() {
        return profile != null ? profile.getName() : null;
    }

    public boolean isRunning() {
        return this.state == State.RUNNING;
    }

    // =========================================================================
    // State access (PULL)
    // =========================================================================

    public State getState() {
        return state;
    }

    public OperatingState getOperatingState() {
        return operatingState;
    }

    public long getMissingBlocks() {
        return missingBlocks;
    }

    /**
     * Reports sync progress from blockchain processor.
     * Uses hysteresis: SYNC_IDLE → SYNCING when missing > 10, SYNCING → SYNC_IDLE when missing < 1.
     */
    public void reportSyncProgress(long missingBlocks) {
        this.missingBlocks = missingBlocks;
        OperatingState old = this.operatingState;
        if (old == OperatingState.SYNC_IDLE && missingBlocks > 10) {
            this.operatingState = OperatingState.SYNCING;
        } else if (old == OperatingState.SYNCING && missingBlocks < 1) {
            this.operatingState = OperatingState.SYNC_IDLE;
        }
        if (old != this.operatingState) {
            for (StateListener l : stateListeners) {
                try { l.onOperatingStateChanged(this, old, this.operatingState); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Pauses blockchain synchronization on user request.
     * <p>v4 (P1.3): the pause action (sync stop) + state change + PUSH notify are
     * owned here — the GUI only renders the state, it never pauses directly.</p>
     */
    public void pauseByUser() {
        setPaused(OperatingState.PAUSED_USER, "paused by user request");
    }

    /**
     * Pauses blockchain synchronization for a system-level reason (e.g., DB error).
     */
    public void pauseBySystem(String reason) {
        setPaused(OperatingState.PAUSED_SYSTEM, "paused by system: " + reason);
        for (StateListener l : stateListeners) {
            try { l.onStatusMessage(this, "Paused by system: " + reason); } catch (Exception ignored) {}
        }
    }

    public void resumeByUser() {
        resumePaused();
    }

    public void resumeBySystem() {
        resumePaused();
    }

    private void setPaused(OperatingState newState, String reason) {
        OperatingState old = this.operatingState;
        this.operatingState = newState;
        this.pauseReason = reason;
        BlockchainProcessor proc = getBlockchainProcessor();
        if (proc != null) {
            try { proc.setSyncPaused(true); } catch (Exception ignored) {}
        }
        if (old != newState) {
            for (StateListener l : stateListeners) {
                try { l.onOperatingStateChanged(this, old, newState); } catch (Exception ignored) {}
            }
        }
    }

    private void resumePaused() {
        OperatingState old = this.operatingState;
        this.operatingState = missingBlocks > 10 ? OperatingState.SYNCING : OperatingState.SYNC_IDLE;
        this.pauseReason = null;
        BlockchainProcessor proc = getBlockchainProcessor();
        if (proc != null) {
            try { proc.setSyncPaused(false); } catch (Exception ignored) {}
        }
        if (old != this.operatingState) {
            for (StateListener l : stateListeners) {
                try { l.onOperatingStateChanged(this, old, this.operatingState); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Returns the reason the node is currently paused, or {@code null} when not paused.
     */
    public String getPauseReason() {
        return pauseReason;
    }

    // =========================================================================
    // State Listener (PUSH)
    // =========================================================================

    public void addStateListener(StateListener listener) {
        if (listener != null) stateListeners.add(listener);
    }

    public void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    /**
     * PULL accessor (v4 P2.2): returns an unmodifiable snapshot of the currently
     * registered state listeners. Used to verify the single-listener binding
     * contract (exactly one listener per owning GUI panel).
     *
     * @return unmodifiable list of registered listeners (never null)
     */
    public java.util.List<StateListener> getStateListeners() {
        return java.util.List.copyOf(stateListeners);
    }

    private void setState(State newState) {
        State oldState = this.state;
        this.state = newState;
        for (StateListener l : stateListeners) {
            try { l.onStateChanged(this, oldState, newState); }
            catch (Exception ignored) {}
        }
    }

    // =========================================================================
    // Component access (all direct fields)
    // =========================================================================

    public Path getConfFolder() {
        return confFolder;
    }

    public PropertyService getPropertyService() {
        return propertyService;
    }

    public DbContext getDbContext() {
        return dbContext;
    }

    public DBCacheManagerImpl getDbCacheManager() {
        return dbCacheManager;
    }

    public Stores getStores() {
        return stores;
    }

    public Dbs getDbs() {
        return dbs;
    }

    public DerivedTableManager getDerivedTableManager() {
        return derivedTableManager;
    }

    public StatisticsManagerImpl getStatisticsManager() {
        return statisticsManager;
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

    public ThreadPool getThreadPool() {
        return threadPool;
    }

    public WebServer getWebServer() {
        return webServer;
    }

    public ShutdownManager getShutdownManager() {
        return shutdownManager;
    }

    public NetworkParameters getNetworkParameters() {
        return networkParameters;
    }

    public DownloadCacheImpl getDownloadCache() {
        return downloadCache;
    }

    public EconomicClustering getEconomicClustering() {
        return economicClustering;
    }

    public FluxCapacitor getFluxCapacitor() {
        return fluxCapacitor;
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

    public TimeService getTimeService() {
        return timeService;
    }

    public APITransactionManager getApiTransactionManager() {
        return apiTransactionManager;
    }

    public FeeSuggestionCalculator getFeeSuggestionCalculator() {
        return feeSuggestionCalculator;
    }

    public DeeplinkQRCodeGenerator getDeeplinkQRCodeGenerator() {
        return deeplinkQRCodeGenerator;
    }

    public TransactionApplyContext getTransactionApplyContext() {
        return transactionApplyContext;
    }

    public AtConstants getAtConstants() {
        return atConstants;
    }

    public ATProcessorCache getAtProcessorCache() {
        return atProcessorCache;
    }

    public ATProcessingContext getAtProcessingContext() {
        return atProcessingContext;
    }

    public PeerManager getPeerManager() {
        return peerManager;
    }

    public DebugTrace getDebugTrace() {
        return debugTrace;
    }

    // ── Logging (per-node ProfileLogger) ──

    /**
     * Returns this node's {@link ProfileLogger} instance.
     * <p>
     * The ProfileLogger is the per-node logging entry point. GUI panels
     * (NodeConsolePanel) subscribe to it via {@code addSubscriber()}.
     * By default, events are also forwarded to {@code SystemLogger} so
     * the SystemConsole sees all logs.
     * </p>
     *
     * @return the profile logger (created in the constructor for profile-based
     *         instances; never null there)
     * @since 4.1
     */
    public ProfileLogger getProfileLogger() {
        return profileLogger;
    }

    // =========================================================================
    // Profile management
    // =========================================================================

    // v4: Global "active node profile" statics removed — profile identity is
    // per-node (NodeProfile / NodeModule registry). Use
    // NodeModule.getInstance().get(profileName) for node resolution.
    // (The legacy getActiveLoggingProfile() constant-alias was removed as well;
    //  callers use the Signum.LOGGING_PROPERTIES_NAME constant directly.)

    // =========================================================================
    // Properties loading
    // =========================================================================

    /**
     * Loads properties for a specific named profile using the unified PropertiesProfileLoader.
     * Architecture: Hardcoded defaults (Props.java) -> Profile .properties file only.
     * Path schema: {confFolder}/node/profiles/{profileName}.properties
     *
     * @param confFolder  The base configuration folder for this node (e.g., "./conf",
     *                    or "conf/junit" in tests) — the profile is resolved RELATIVE TO IT
     * @param profileName The profile name to load (without .properties extension)
     * @return PropertyService with loaded properties, or empty one if file not found
     */
    public static PropertyService loadPropertiesForProfile(String confFolder, String profileName) {
        CaselessProperties properties = new CaselessProperties();

        // Use unified profile loader for path resolution: {confFolder}/node/profiles/{profileName}.properties
        // (v4: the confFolder parameter is the node's configuration root — honoring it is what
        // makes per-node conf roots work, e.g. the JUnit mock profile under "conf/junit".)
        Path profileFile = PropertiesProfileLoader.resolveProfileFile(
                confFolder, "node", "profiles", profileName);

        LOGGER.info("Initializing Signum Node version {}", VERSION);
        LOGGER.info("Looking for profile '{}' at {}", profileName, profileFile.toAbsolutePath());

        if (Files.exists(profileFile)) {
            try (Reader reader = new InputStreamReader(new FileInputStream(profileFile.toFile()),
                    StandardCharsets.UTF_8)) {
                LOGGER.info("Loading profile '{}' from {}", profileName, profileFile.toAbsolutePath());
                properties.load(reader);
            } catch (IOException e) {
                LOGGER.warn("Error loading profile '{}', using internal defaults.", profileName, e);
            }
        } else {
            LOGGER.info("No profile file found for '{}'. Using internal defaults from Props.java.", profileName);
        }

        // Ensure SETTINGS_DIR is set if not in file
        if (properties.getProperty(Props.SETTINGS_DIR.getName()) == null) {
            properties.setProperty(Props.SETTINGS_DIR.getName(), Props.SETTINGS_DIR.getDefaultValue());
        }

        return new PropertyServiceImpl(properties);
    }

    // =========================================================================
    // Main / init / shutdown (legacy entry points)
    // =========================================================================

    /**
     * The headless entry point for the node.
     * <p>
     * v4 (P0.1): delegates to the single composition root — {@link NodeModule}
     * boots every autostart-enabled profile. When no profile is autostart-enabled,
     * the default {@code "node"} profile is started so legacy
     * {@code -c <confFolder>} CLI usage keeps working.
     * </p>
     *
     * @param args arguments for the node.
     */
    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(NodeModule.getInstance()::stop));
        String confFolder = CONF_FOLDER;
        try {
            CommandLine cmd = new DefaultParser().parse(CLI_OPTIONS, args, true);
            if (cmd.hasOption(CONF_FOLDER_OPTION.getOpt())) {
                confFolder = cmd.getOptionValue(CONF_FOLDER_OPTION.getOpt());
            }
        } catch (Exception e) {
            System.err.println("Exception parsing command line arguments: " + e.getMessage());
        }
        LOGGER.info("Headless boot: delegating to NodeModule (confFolder={})", confFolder);
        NodeModule module = NodeModule.getInstance();
        Path confRoot = PathUtils.resolvePath(confFolder);
        module.start();
        if (module.size() == 0) {
            LOGGER.info("No autostart profile registered — starting default profile '{}'", PROPERTIES_NAME);
            module.startNode(PROPERTIES_NAME, confRoot);
        }
    }

    // v4: The legacy global lifecycle statics (startNode / shutdownNode /
    // initShutdown / init(CaselessProperties) / init(String) / shutdown(boolean))
    // were removed. NodeModule is the sole lifecycle entry point:
    //   NodeModule.getInstance().startNode(profileName)

    // =========================================================================
    // Console command handler
    // =========================================================================

    /**
     * Instance-scoped command processing.
     * Operates on this Signum instance's components directly.
     *
     * @param command the console command string
     */
    public void processCommandInstance(String command) {
        LOGGER.debug("received command: >{}<", command);

        BlockchainProcessor proc = this.blockchainProcessor;
        Blockchain chain = this.blockchain;

        if (command.equals(".shutdown")) {
            // v4 (P0.1): shutdown goes through the single composition root
            NodeModule.getInstance().stop();
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
            LOGGER.info("Available commands:");
            LOGGER.info("  .shutdown     - Gracefully shuts down the node.");
            LOGGER.info("  .restart      - Restarts the node application.");
            LOGGER.info("  .pause        - Pauses blockchain synchronization.");
            LOGGER.info("  .resume       - Resumes blockchain synchronization.");
            LOGGER.info("  .autoresolve  - Triggers manual database consistency resolution.");
            LOGGER.info("  .trim         - Schedules a database trim.");
            LOGGER.info("  .dbcheck      - Performs a database consistency check.");
            LOGGER.info("  .popoff <n>   - Pops off the last n blocks from the blockchain (e.g., .popoff 10).");
            LOGGER.info("  .help         - Displays this help message.");
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
            LOGGER.info("Unknown command: \"{}\". Type .help to see the list of available commands.", command);
        }
    }

    // v4 (P0.1): the legacy static Signum.shutdown() was removed — application
    // shutdown goes through NodeModule.stop() (stopAll + logging teardown).

    // =========================================================================
    // Internal bootstrap / teardown (absorbed from NodeCoreContext)
    // =========================================================================

    /**
     * Bootstrap logic for starting all node components.
     */
    private void doInitialize() {
        String profileName = this.profile.getName();

        // ── Step 1: ProfileLogger ── created in the Signum(NodeProfile, Path) constructor
        // (moved out of here) so GUI consoles can attach before start() and no
        // bootstrap log line is lost; nothing to do at this point.

        // ── Step 2.5: Create profile-scoped ShutdownManager ──
        this.shutdownManager = new ShutdownManager(this.propertyService, profileName);

        // ── Step 3: Database Foundation ──
        try {
            initDatabaseFoundation();
        } catch (NodeStartupException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NodeStartupException(
                    "Database initialization failed for profile '" + profileName + "'", e);
        }

        // ── Step 3.5: Blockchain Core ──
        try {
            initBlockchainCore();
        } catch (NodeStartupException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NodeStartupException(
                    "Blockchain core initialization failed for profile '" + profileName + "'", e);
        }

        // ── Step 3.75: Services & Hooks ──
        try {
            initServicesAndHooks();
        } catch (NodeStartupException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NodeStartupException(
                    "Services initialization failed for profile '" + profileName + "'", e);
        }

        // ── Step 4: Log initialization summary ──
        LOGGER.info("Node profile '{}' initialization completed.", profileName);
        LOGGER.info("Running network: {}", propertyService.getString(Props.NETWORK_NAME));
    }

    /**
     * Initializes database foundation components.
     */
    private void initDatabaseFoundation() {
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

        // Address prefix configuration
        SignumUtils.setAddressPrefix(this.propertyService.getString(Props.ADDRESS_PREFIX));
        SignumUtils.addAddressPrefix("BURST");
        SignumUtils.setValueSuffix(this.propertyService.getString(Props.VALUE_SUFIX));

        // TimeService
        this.timeService = new TimeServiceImpl();

        // DerivedTableManager
        this.derivedTableManager = new DerivedTableManager();

        // StatisticsManager + DBCacheManager
        this.statisticsManager = new StatisticsManagerImpl(this.timeService);
        this.dbCacheManager = new DBCacheManagerImpl(this.statisticsManager);

        // ThreadPool
        this.threadPool = new ThreadPool(this.propertyService);
        // Scope all pool tasks to this node's profile so log events route to the
        // correct per-node ProfileLogger (Node Console).
        this.threadPool.setProfileName(this.profile.getName());

        // Db.init + Dbs + Stores
        this.dbContext = DbContext.create(this.propertyService, this.dbCacheManager);
        this.dbs = this.dbContext.getDbsByDatabaseType();

        TransactionDb transactionDb = dbs.getTransactionDb();
        BlockDb blockDb = dbs.getBlockDb();

        StoreDependencies storeDeps = new StoreDependencies(null, this.propertyService, null, this.dbs, this.dbContext);
        this.stores = new Stores(this.derivedTableManager, this.dbCacheManager, this.timeService,
                this.propertyService, transactionDb, blockDb, this.networkParameters, storeDeps);
    }

    /**
     * Initializes blockchain core components.
     */
    private void initBlockchainCore() {
        final TransactionDb transactionDb = this.dbs.getTransactionDb();
        final BlockDb blockDb = this.dbs.getBlockDb();
        final BlockchainStore blockchainStore = this.stores.getBlockchainStore();

        this.blockchain = NodeComponentFactory.createBlockchain(
                transactionDb, blockDb, blockchainStore, this.propertyService);

        // Wire the per-node blockchain reference into the stores that were created
        // before the blockchain existed (breaks the circular dependency).
        this.stores.wireDependencies(this.blockchain);

        UnconfirmedTransactionStore unconfirmedTransactionStore = this.stores.getUnconfirmedTransactionStore();
        unconfirmedTransactionStore.setBlockchain(this.blockchain);

        this.fluxCapacitor = new FluxCapacitorImpl(this.blockchain, this.propertyService);
        // Wire the flux capacitor into the stores that were created before it
        // existed (breaks the circular dependency, same pattern as wireDependencies).
        this.stores.wireFluxCapacitor(this.fluxCapacitor);
        // Same for the DB layer: SqlBlockDb builds Block objects with it.
        this.dbs.setFluxCapacitor(this.fluxCapacitor);
        // And for the unconfirmed transaction store (created in Stores before the
        // FluxCapacitor existed).
        unconfirmedTransactionStore.setFluxCapacitor(this.fluxCapacitor);
        this.aliasService = new AliasServiceImpl(
                this.stores.getAliasStore(), this.stores, this.fluxCapacitor, this.propertyService);
        this.aliasService.addDefaultTLDs();

        this.economicClustering = new EconomicClustering(this.blockchain, this.fluxCapacitor);

        this.accountService = new AccountServiceImpl(
                this.stores.getAccountStore(), this.stores.getAssetTransferStore(), this.blockchain);

        this.downloadCache = new DownloadCacheImpl(
                this.propertyService, this.fluxCapacitor, this.blockchain);

        boolean mockMining = this.propertyService.getBoolean(Props.DEV_MOCK_MINING);
        this.generator = NodeComponentFactory.createGenerator(
                mockMining, this.propertyService, this.blockchain,
                this.accountService, this.timeService, this.fluxCapacitor, this.downloadCache);

        this.transactionService = new TransactionServiceImpl(
                this.accountService, this.blockchain);

        this.transactionProcessor = new TransactionProcessorImpl(
                this.propertyService, this.economicClustering, this.blockchain,
                this.stores, this.timeService, this.dbs,
                this.accountService, this.transactionService,
                this.fluxCapacitor, this.threadPool);
    }

    /**
     * Initializes remaining services and blockchain hooks.
     */
    private void initServicesAndHooks() {
        final TransactionDb transactionDb = this.dbs.getTransactionDb();
        final BlockDb blockDb = this.dbs.getBlockDb();
        final BlockchainStore blockchainStore = this.stores.getBlockchainStore();

        this.atProcessorCache = new ATProcessorCache(
                this.propertyService, this.stores.getAtStore(), this.dbContext, this.dbs);

        this.atConstants = new AtConstants(this.fluxCapacitor);

        ((SqlATStore) this.stores.getAtStore()).setAtConstants(this.atConstants);
        AtController.setAtConstants(this.atConstants);

        AliasStore aliasStore = this.stores.getAliasStore();
        this.subscriptionService = new SubscriptionServiceImpl(
                this.stores.getSubscriptionStore(), transactionDb, this.blockchain,
                this.fluxCapacitor, this.aliasService, aliasStore, this.accountService);
        ((AliasServiceImpl) this.aliasService).setSubscriptionService(this.subscriptionService);

        this.digitalGoodsStoreService = new DGSGoodsStoreServiceImpl(
                this.blockchain, this.stores.getDigitalGoodsStoreStore(), this.accountService);

        this.escrowService = new EscrowServiceImpl(
                this.stores.getEscrowStore(), this.blockchain, this.aliasService,
                this.accountService, transactionDb, this.fluxCapacitor);

        this.assetExchange = new AssetExchangeImpl(
                this.blockchain, this.accountService,
                this.stores.getTradeStore(), this.stores.getAccountStore(),
                this.stores.getAssetTransferStore(), this.stores.getAssetStore(),
                this.stores.getOrderStore());

        this.indirectIncomingService = new IndirectIncomingServiceImpl(
                this.stores.getIndirectIncomingStore(), this.propertyService);

        this.atProcessingContext = new ATProcessingContext(
                this.atConstants, this.atProcessorCache, this.propertyService,
                this.fluxCapacitor, this.blockchain, this.stores.getAtStore(),
                this.stores.getAccountStore(), this.accountService,
                this.assetExchange, this.stores.getIndirectIncomingStore(),
                this.stores.getAssetStore());

        this.atService = new ATServiceImpl(
                this.stores.getAtStore(), this.atConstants, this.atProcessorCache,
                this.propertyService, this.fluxCapacitor, this.blockchain,
                this.stores.getAccountStore(), this.accountService,
                this.assetExchange, this.stores.getIndirectIncomingStore(),
                this.stores.getAssetStore());

        NetworkParameters params = this.networkParameters;
        this.blockService = new BlockServiceImpl(
                this.accountService, this.transactionService, this.blockchain,
                this.downloadCache, this.generator, this.fluxCapacitor,
                this.propertyService, params);

        this.blockchainProcessor = NodeComponentFactory.createBlockchainProcessor(
                this.threadPool, this.blockService, this.transactionProcessor,
                this.blockchain, this.propertyService, this.subscriptionService,
                this.timeService, this.derivedTableManager, blockDb, transactionDb,
                this.economicClustering, blockchainStore, this.stores,
                this.escrowService, this.transactionService, this.downloadCache,
                this.generator, this.statisticsManager, this.dbCacheManager,
                this.accountService, this.indirectIncomingService,
                this.aliasService, this.fluxCapacitor, this.atService,
                this.atProcessorCache);

        this.derivedTableManager.setMinRollbackHeightSupplier(this.blockchainProcessor::getMinRollbackHeight);
        this.downloadCache.setBlockchainProcessor(this.blockchainProcessor);
        this.generator.generateForBlockchainProcessor(this.threadPool, this.blockchainProcessor);

        this.deeplinkQRCodeGenerator = new DeeplinkQRCodeGenerator();

        this.parameterService = new ParameterServiceImpl(
                this.accountService, this.aliasService, this.assetExchange,
                this.digitalGoodsStoreService, this.blockchain, this.blockchainProcessor,
                this.transactionProcessor, this.atService, this.stores.getAccountStore());

        // addBlockchainListeners
        AT.HandleATBlockTransactionsListener handleAtBlockTransactionListener =
                new AT.HandleATBlockTransactionsListener(this.atProcessingContext, transactionDb);
        this.blockchainProcessor.addListener(handleAtBlockTransactionListener, BlockchainProcessor.Event.AFTER_BLOCK_APPLY);

        DGSGoodsStoreServiceImpl.ExpiredPurchaseListener expiredPurchaseListener =
                new DGSGoodsStoreServiceImpl.ExpiredPurchaseListener(this.accountService, this.digitalGoodsStoreService);
        this.blockchainProcessor.addListener(expiredPurchaseListener, BlockchainProcessor.Event.AFTER_BLOCK_APPLY);

        this.apiTransactionManager = new APITransactionManagerImpl(
                this.parameterService, this.transactionProcessor, this.blockchain,
                this.accountService, this.transactionService, this.propertyService,
                this.fluxCapacitor);

        this.peerManager = new PeerManager(
                this.propertyService, this.blockchain, this.threadPool, this.timeService);
        this.peerManager.start(
                this.timeService, this.accountService, this.blockchain,
                this.transactionProcessor, this.blockchainProcessor,
                this.propertyService, this.threadPool,
                this.fluxCapacitor, this.dbs, this.stores);
        // Bind the profile's peer manager to the processors constructed above
        this.blockchainProcessor.setPeerManager(this.peerManager);
        this.transactionProcessor.setPeerManager(this.peerManager);

        if (this.networkParameters != null) {
            this.networkParameters.initialize(this.parameterService, this.accountService, this.apiTransactionManager);
        }

        this.feeSuggestionCalculator = new FeeSuggestionCalculator(
                this.blockchainProcessor, this.stores.getUnconfirmedTransactionStore(),
                this.blockchain, this.fluxCapacitor);

        this.webServer = new WebServerImpl(new WebServerContext(
                this.transactionProcessor, this.blockchain, this.blockchainProcessor,
                this.parameterService, this.accountService, this.aliasService,
                this.assetExchange, this.escrowService, this.digitalGoodsStoreService,
                this.subscriptionService, this.atService, this.timeService,
                this.economicClustering, this.propertyService, this.threadPool,
                this.transactionService, this.blockService, this.generator,
                this.apiTransactionManager, this.feeSuggestionCalculator,
                this.deeplinkQRCodeGenerator, this.indirectIncomingService,
                this.networkParameters, this.atConstants, this.fluxCapacitor, this.stores,
                this.peerManager));
        try {
            this.webServer.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start WebServer", e);
        }

        // ThreadPool.start() with timewarp
        boolean offline = this.propertyService.getBoolean(Props.DEV_OFFLINE);
        int timeMultiplier = offline
                ? Math.max(this.propertyService.getInt(Props.DEV_TIMEWARP), 1)
                : 1;
        this.threadPool.start(timeMultiplier);

        if (timeMultiplier > 1) {
            this.timeService.setTime(new application.module.node.util.Time.FasterTime(
                    Math.max(this.timeService.getEpochTime(), this.blockchain.getLastBlock().getTimestamp()),
                    timeMultiplier));
            LOGGER.info("TIME WILL FLOW {} TIMES FASTER!", timeMultiplier);
        }

        this.transactionApplyContext = new TransactionApplyContext(
                this.blockchain, this.fluxCapacitor, this.accountService,
                this.digitalGoodsStoreService, this.aliasService, this.assetExchange,
                this.subscriptionService, this.escrowService, this.propertyService,
                this.stores.getAtStore(), this.atConstants, this.stores.getAccountStore(),
                this.atProcessingContext);

        this.threadPool.setTransactionApplyContext(this.transactionApplyContext);
        TransactionType.setContext(this.transactionApplyContext);
        if (this.webServer instanceof WebServerImpl) {
            ((WebServerImpl) this.webServer).bindTransactionContext(this.transactionApplyContext);
        }

        this.debugTrace = DebugTrace.create(
                this.propertyService, this.blockchainProcessor, this.accountService,
                this.assetExchange, this.digitalGoodsStoreService,
                this.blockchain, this.stores.getAccountStore());
    }

    /**
     * Ensures the database directory exists for SQLite-based configurations.
     */
    private void ensureDatabaseDirectory(PropertyService props) {
        String dbUrl = props.getString(Props.DB_URL);
        if (dbUrl == null || !dbUrl.toLowerCase().startsWith("jdbc:sqlite:")) {
            return;
        }

        String pathPart = dbUrl.substring("jdbc:sqlite:".length());

        if (pathPart.toLowerCase().startsWith("file:")) {
            pathPart = pathPart.substring(5);
            if (pathPart.startsWith("///")) {
                pathPart = pathPart.substring(2);
            } else if (pathPart.startsWith("//") && !pathPart.startsWith("//", 2)) {
                pathPart = pathPart.substring(2);
            }
        }

        if (pathPart.isEmpty() || pathPart.equalsIgnoreCase(":memory:") || pathPart.startsWith(":")) {
            return;
        }

        int queryIdx = pathPart.indexOf('?');
        if (queryIdx != -1) {
            pathPart = pathPart.substring(0, queryIdx);
        }

        try {
            Path dbPath = PathUtils.resolvePath(pathPart);
            Path parent = dbPath.getParent();
            if (parent != null && Files.notExists(parent)) {
                Files.createDirectories(parent);
                LOGGER.info("Created missing database directory: {}", parent.toAbsolutePath());
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to ensure database directory exists: {}", e.getMessage());
        }
    }

    /**
     * Teardown logic - stops all components in reverse-dependency order.
     */
    private void doShutdown() {
        String profileName = this.profile != null ? this.profile.getName() : "unknown";
        try {
            if (this.webServer != null) {
                try {
                    this.webServer.shutdown();
                } catch (Throwable t) {
                    if (this.shutdownManager != null) this.shutdownManager.markFailure("WebServer");
                    LOGGER.error("Error shutting down WebServer for profile '{}'", profileName, t);
                }
            }

            if (this.blockchainProcessor != null) {
                try {
                    this.blockchainProcessor.shutdown();
                } catch (Throwable t) {
                    if (this.shutdownManager != null) this.shutdownManager.markFailure("BlockchainProcessor");
                    LOGGER.error("Error shutting down BlockchainProcessor for profile '{}'", profileName, t);
                }
            }

            if (this.peerManager != null) {
                try {
                    this.peerManager.shutdown(this.threadPool);
                } catch (Throwable t) {
                    if (this.shutdownManager != null) this.shutdownManager.markFailure("PeerManager");
                    LOGGER.error("Error shutting down PeerManager for profile '{}'", profileName, t);
                }
            }

            if (this.threadPool != null) {
                try {
                    this.threadPool.shutdown();
                } catch (Throwable t) {
                    if (this.shutdownManager != null) this.shutdownManager.markFailure("ThreadPool");
                    LOGGER.error("Error shutting down ThreadPool for profile '{}'", profileName, t);
                }
            }

            if (this.dbCacheManager != null) {
                try {
                    this.dbCacheManager.close();
                } catch (Throwable t) {
                    if (this.shutdownManager != null) this.shutdownManager.markFailure("DBCacheManager");
                    LOGGER.error("Error closing DBCacheManager for profile '{}'", profileName, t);
                }
            }

            try {
                if (this.dbContext != null) this.dbContext.shutdown();
            } catch (Throwable t) {
                if (this.shutdownManager != null) this.shutdownManager.markFailure("Database");
                LOGGER.error("Error shutting down DB for profile '{}'", profileName, t);
            }

            if (this.shutdownManager != null) {
                this.shutdownManager.finishShutdown();
            }

            LOGGER.info("Node profile '{}' stopped.", profileName);
        } catch (Exception e) {
            LOGGER.error("Unexpected error during shutdown of profile '{}'", profileName, e);
        } finally {
            contextStarted.set(false);
            if (this.profileLogger != null) {
                this.profileLogger.close();
                this.profileLogger = null;
                application.utils.logging.NodeLoggerRegistry.unregister("node", profileName);
            }
            // ProfileThreadContext removed — ProfileLogger subscriber model replaces MDC routing
        }
    }
}