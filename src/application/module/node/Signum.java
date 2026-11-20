package application.module.node;

import application.module.node.props.CaselessProperties;
import application.module.node.props.PropertyService;
import application.module.node.props.PropertyServiceImpl;
import application.module.node.props.Props;
import application.module.node.web.server.WebServer;
import application.module.node.instance.NodeStartupException;
import application.module.node.profile.EffectiveNodeProps;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.NodeProfileRepository;
import application.utils.config.ModuleIds;
import application.utils.config.PropertiesProfileLoader;
import application.utils.io.PathUtils;

import application.module.node.at.AT;
import application.module.node.at.AtConstants;
import application.module.node.at.ATProcessorCache;
import application.module.node.at.ATProcessingContext;
import application.module.node.at.ATPendingState;
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
import application.module.logging.appender.ProfileConsoleAppender;
import application.module.logging.context.NodeLoggingContext;
import application.module.logging.context.NodeLoggingContextApplier;
import application.module.logging.context.NodeLoggingContextManager;
import application.module.logging.context.NodeLoggers;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

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

    /**
     * Class-level logger (log4j, JVM-global context): Signum is the per-node orchestrator
     * with static entry points ({@code main}, {@code loadPropertiesForProfile}) and its
     * constructor runs before the isolated context's full config is applied, so Signum's
     * own logging stays on the global context (plan §7.2 static boundary). The per-node
     * components (P3-B6) each receive their own node-isolated loggers via {@link #loggers()}.
     */
    // Serves the JVM-wide bootstrap entry points that run before/without a node instance
    // (main, loadPropertiesForProfile) — legitimately global (no node context exists yet).
    private static final Logger BOOTSTRAP_LOGGER = LogManager.getLogger(Signum.class);

    // The node-scoped logger (P3-B6.5b): assigned once in the constructor from this node's
    // isolated LoggerContext (global fallback if the context could not be created). Every
    // per-node instance method logs through it, so a Signum logs to its OWN node context /
    // profile console. A later full Apply reconfigures the SAME context, which re-binds this
    // logger to the new level (P0/Q2b) — so it needs no reassignment.
    private Logger logger;

    /**
     * The ONE JVM-wide registry of the nodes' isolated Log4j2 contexts (per-node
     * isolated LoggerContext plan §6.3/§11.1): every Signum instance of this JVM
     * and the NodeLoggingApplier share it (see
     * {@link NodeLoggingContextManager#shared()}); keyed by the node profile name
     * (NodeModule contract: at most one running node per profile).
     */
    private static final NodeLoggingContextManager NODE_LOGGING_CONTEXT_MANAGER =
            NodeLoggingContextManager.shared();

    // ── Isolated node LoggerContext (per-node isolated LoggerContext plan, P2.3) ──

    /** This node's isolated Log4j2 context (minimal config until the start() full apply). */
    private volatile NodeLoggingContext nodeLoggingContext;
    /** Loggers bound to the isolated node context (plan §7.1) — the engine's target API (P3). */
    private volatile NodeLoggers nodeLoggers;
    /** The programmatic GUI-bridge appender: node context &rarr; ProfileLogger bus (plan §6.3). */
    private volatile ProfileConsoleAppender profileConsoleAppender;

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
        // existed are preserved; otherwise it creates + registers a fresh one. The
        // ProfileLogger forwards to SystemLogger (P8/F1): the legacy
        // SystemLoggerJulHandler is gone (P3.5), so this forward is the node's only
        // path to the System Console (no duplication — node events live only in the
        // node's isolated context).
        this.profileLogger = application.utils.logging.NodeLoggerRegistry.getOrCreate(ModuleIds.NODE, profile.getName());

        // F4 (console SSOT): align the replay buffer with the effective console line
        // cap, so a late-attaching node console tab replays the same history the
        // console can display (instead of the fixed DEFAULT_REPLAY_CAPACITY).
        // Best-effort: on any failure the default capacity is kept.
        try {
            this.profileLogger.setReplayCapacity(
                    NodeLoggingContextApplier.resolveConsoleMaxLines(confFolder, profile.getName()));
        } catch (Throwable t) {
            // keep the default replay capacity
        }

        // ── Step 2: the isolated node LoggerContext, minimal config first ──
        // Two-phase start (plan §6.3): the context boots with the L0-only minimal
        // config (root=INFO, console-only, NO file appender — no log file is
        // opened before start()) so the GUI-bridge appender can be attached
        // immediately and the engine (P3) can obtain NodeLoggers right away.
        // The full L0+L1+L2 config is applied in doInitialize() (start).
        // Defensive: a failure keeps the node bootable on the legacy chain.
        try {
            this.nodeLoggingContext = NodeLoggingContextApplier.applyMinimal(
                    confFolder, NODE_LOGGING_CONTEXT_MANAGER, profile.getName());
            this.nodeLoggers = new NodeLoggers(nodeLoggingContext.loggerContext());
            this.profileConsoleAppender = new ProfileConsoleAppender(this.profileLogger);
            this.profileConsoleAppender.attach(nodeLoggingContext.loggerContext());
            this.logger = this.nodeLoggers.forClass(Signum.class);
            logger.info("Isolated node LoggerContext '{}' created (minimal config; full config at start())",
                    profile.getName());
        } catch (Throwable t) {
            this.logger = BOOTSTRAP_LOGGER;
            logger.warn("Failed to create the isolated node LoggerContext for profile '{}' - "
                    + "node logging stays on the legacy chain: {}", profile.getName(), t.getMessage());
            this.nodeLoggingContext = null;
            this.nodeLoggers = null;
            this.profileConsoleAppender = null;
        }
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
     * Re-reads this profile's configuration from disk and refreshes both the in-memory
     * {@link NodeProfile} (which drives start-time conflict detection and resource
     * reservation) and the cached {@link PropertyService} (which drives the actual node).
     * <p>
     * This is what lets an in-session config edit (e.g. a changed API/P2P/WS port or
     * database) be applied on the next (re)start: the previously cached snapshot is
     * replaced by the current on-disk values. It is a safe no-op when the profile cannot
     * be loaded from disk (e.g. a name-only or purely in-memory profile) — the existing
     * profile and properties are then kept. The {@code Signum} instance itself is
     * preserved (only its configuration snapshot is swapped), so GUI/console bindings
     * survive the refresh across restarts.
     * </p>
     *
     * @since 5.0
     */
    public synchronized void refreshConfiguration() {
        NodeProfile reloaded = NodeProfileRepository.loadByName(this.profile.getName());
        if (reloaded == null) {
            logger.debug("refreshConfiguration('{}'): no on-disk profile - keeping current config",
                    this.profile.getName());
            return;
        }
        this.profile = reloaded;
        try {
            this.propertyService = Signum.loadPropertiesForProfile(this.confFolder.toString(), this.profile.getName());
        } catch (Exception e) {
            throw new NodeStartupException(
                    "Failed to refresh configuration for profile '" + this.profile.getName() + "' from " + this.confFolder, e);
        }
        logger.info("refreshConfiguration('{}'): reloaded configuration from disk ({} propert(ies))",
                this.profile.getName(), reloaded.getProperties().size());
    }

    /**
     * Starts the node: opens DB connections, binds ports, starts threads.
     * <b>Port conflicts INHIBIT startup</b> — state transitions to {@code ERROR}.
     * <p>
     * Requires prior call to {@link #init()}.
     * </p>
     * <p>
     * <b>ERROR is recoverable:</b> a node that previously failed to start
     * (state {@code ERROR}) can be stopped — {@link #stop()} accepts the
     * {@code ERROR} state, tears down whatever the failed start may have
     * partially created, and moves the node to {@code STOPPED} — and can then
     * be started again normally ({@code STOPPED -> STARTING -> ...}).
     * Neither Stop nor Restart is restricted in the {@code ERROR} state.
     * (Direct {@code start()} from {@code ERROR} is still rejected by design —
     * a failed start must be acknowledged with an explicit Stop first — and the
     * Restart action, which is Stop followed by Start, always works.)
     * </p>
     *
     * @throws IllegalStateException if in a state that cannot transition to STARTING
     * @throws application.module.node.instance.NodeStartupException if startup fails
     */
    public synchronized void start() {
        // The startup thread's own lifecycle logging (init/doInitialize) routes through
        // this node's isolated Log4j2 context, so those lines reach the ProfileLogger's
        // replay buffer for late-attaching GUI subscribers (Node Console).
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
    }

    /**
     * Stops the node.
     * <p>
     * Accepts two states:
     * <ul>
     *   <li>{@code RUNNING} — a normal stop;</li>
     *   <li>{@code ERROR} — a recovery stop after a failed start: tears down
     *       whatever the failed startup partially created (webServer /
     *       peerManager / threadPool / DB) and returns the node to {@code STOPPED},
     *       from which it can be started again normally. This keeps Stop (and
     *       therefore Restart = Stop + Start) fully available in the
     *       {@code ERROR} state.</li>
     * </ul>
     * In any other state this is a no-op (nothing to stop).
     * </p>
     */
    public void stop() {
        if (this.state == State.RUNNING || this.state == State.ERROR) {
            setState(State.STOPPING);
            try {
                doShutdown();
                setState(State.STOPPED);
            } catch (Exception e) {
                setState(State.ERROR);
                throw e;
            }
        }
    }

    /**
     * Reports a rejected start (e.g. a resource conflict with another profile) to this
     * node's {@link ProfileLogger} and the SLF4J log, so the reason is visible in the
     * Node Console tab.
     * <p>
     * This does <b>not</b> change the lifecycle state: the node is left in its current
     * (non-running) state so it can be (re)started once the conflict is resolved. This
     * is deliberately different from a genuine start failure (which transitions to
     * {@link State#ERROR}).
     * </p>
     *
     * @param reason human-readable description of the conflict (never null)
     */
    public void reportStartRejected(String reason) {
        String r = (reason == null || reason.isBlank()) ? "resource conflict" : reason;
        ProfileLogger pl = this.profileLogger;
        if (pl != null && !pl.isClosed()) {
            pl.error("Start rejected - " + r);
        }
        logger.warn("Start rejected for profile '{}': {}", getProfileName(), r);
    }

    public void restart() {
        this.stop();
        this.start();
    }

    /**
     * Terminal teardown for this Signum instance.
     * <p>
     * Unlike {@link #stop()} — which keeps the node restartable and deliberately leaves
     * the {@link ProfileLogger} alive so the GUI console subscriber persists across
     * restarts — {@code dispose()} is the <b>final</b> release: it closes the node's
     * {@link ProfileLogger} (disposing its subscribers, e.g. the Node Console) and
     * unregisters it from the {@code NodeLoggerRegistry}.
     * </p>
     * <p>
     * Call this <b>only</b> when the Signum is being permanently removed from the
     * {@code NodeModule} (profile removal / application shutdown) — <b>never</b> in the
     * normal stop/start cycle, where the logger must stay alive so the console keeps
     * receiving logs after a restart.
     * </p>
     * <p>Idempotent and safe to call from any thread.</p>
     */
    public synchronized void dispose() {
        ProfileLogger pl = this.profileLogger;
        if (pl != null) {
            pl.close();
            if (this.profile != null) {
                application.utils.logging.NodeLoggerRegistry.unregister(ModuleIds.NODE, this.profile.getName());
            }
        }
        this.profileLogger = null;
        // Remove the isolated node LoggerContext (plan §5.3: dispose → ctx close +
        // factory.removeContext via the manager); the engine's NodeLoggers are
        // then unreachable and GC-collectible (§11).
        NodeLoggingContext nlc = this.nodeLoggingContext;
        if (nlc != null) {
            try {
                NODE_LOGGING_CONTEXT_MANAGER.remove(nlc.profileName());
            } catch (Throwable t) {
                logger.warn("Failed to remove the isolated node LoggerContext for profile '{}': {}",
                        nlc.profileName(), t.getMessage());
            }
        }
        this.nodeLoggingContext = null;
        this.nodeLoggers = null;
        this.profileConsoleAppender = null;
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
     * Clears any active user/system pause so the node returns to a "syncing" baseline
     * ({@link OperatingState#SYNC_IDLE}).
     * <p>
     * Pause is only meaningful for a <b>RUNNING</b> node's blockchain sync. When the
     * lifecycle leaves the running state (stop / start / error) the pause flag is stale:
     * a (re)started node must resume syncing and the GUI sync button must return to the
     * PAUSE (not RESUME) icon. Without this reset, pausing a node and then stopping +
     * starting it would leave the node appearing PAUSED and {@code toggleSync()}-style
     * resume logic would misfire (v5 fix: stale pause persisted across a stop→start
     * cycle).
     * </p>
     * <p>
     * Listeners are notified only when the state actually changed (i.e. the node was
     * paused), so an already-unpaused node produces no redundant push.
     * </p>
     */
    private void clearPauseState() {
        OperatingState old = this.operatingState;
        if (old == OperatingState.PAUSED_USER || old == OperatingState.PAUSED_SYSTEM) {
            this.operatingState = OperatingState.SYNC_IDLE;
            this.pauseReason = null;
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
        // Pause is an attribute of a RUNNING node's blockchain sync. Whenever the
        // lifecycle leaves the running state (stop / start / error) the pause flag is
        // stale: clear it so a (re)started node resumes syncing and the GUI sync button
        // returns to the PAUSE (not RESUME) icon. (v5: fix stale pause that persisted
        // across a stop→start cycle, leaving a wrong "Resume" button on a running node.)
        if (newState != State.RUNNING) {
            clearPauseState();
        }
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
     * Loads the effective properties for a named profile using the three-layer
     * model (see {@link EffectiveNodeProps}):
     * <pre>
     *   L0: Hardcoded defaults (Props.java) — not materialized here; consumers
     *       fall back to Prop.getDefaultValue() for every missing key.
     *   L1: {confFolder}/node/profiles/default.properties — the user-editable
     *       L1 layer; its ACTIVE lines override L0 for every profile.
     *   L2: {confFolder}/node/profiles/{profileName}.properties — the profile's
     *       own file; its active lines override L0 and L1.
     * </pre>
     * Path schema: {confFolder}/node/profiles/*.properties
     *
     * @param confFolder  The base configuration folder for this node (e.g., "./conf",
     *                    or "conf/junit" in tests) — the profile is resolved RELATIVE TO IT
     * @param profileName The profile name to load (without .properties extension)
     * @return PropertyService over the merged L1+L2 properties (pure L0 fallback
     *         when neither file exists or has active lines)
     */
    public static PropertyService loadPropertiesForProfile(String confFolder, String profileName) {
        CaselessProperties properties = EffectiveNodeProps.loadEffective(confFolder, profileName);

        BOOTSTRAP_LOGGER.info("Initializing Signum Node version {}", VERSION);

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
        BOOTSTRAP_LOGGER.info("Headless boot: delegating to NodeModule (confFolder={})", confFolder);
        // Pre-warm the SQLite JDBC native library (with retries) before the node
        // database (or any other sqlite-jdbc usage) triggers the driver's
        // one-shot native load. See application.utils.sqlite.SqliteNativeSupport.
        application.utils.sqlite.SqliteNativeSupport.prewarm();
        NodeModule module = NodeModule.getInstance();
        Path confRoot = PathUtils.resolvePath(confFolder);
        module.start();
        if (module.size() == 0) {
            BOOTSTRAP_LOGGER.info("No autostart profile registered - starting default profile '{}'", PROPERTIES_NAME);
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
        logger.debug("received command: >{}<", command);

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
            if (proc == null) {
                logger.warn("Blockchain processor is not initialized. Cannot run database check.");
            } else {
                try {
                    int result = proc.checkDatabaseStateRequest();
                    if (result == 0) {
                        logger.info("Database check completed: CONSISTENT.");
                    } else {
                        logger.warn("Database check completed: INCONSISTENT. Consider running .autoresolve.");
                    }
                } catch (IllegalStateException e) {
                    logger.info(e.getMessage());
                }
            }
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

    // v4 (P0.1): the legacy static Signum.shutdown() was removed — application
    // shutdown goes through NodeModule.stop() (stopAll + logging teardown).

    // =========================================================================
    // Internal bootstrap / teardown (absorbed from NodeCoreContext)
    // =========================================================================

    /**
     * Applies the full resolved logging configuration to this node's isolated
     * Log4j2 context (per-node isolated LoggerContext plan §9.2A):
     * L0+L1+L2 resolve &rarr; generated-file write &rarr; SAME-context
     * reconfigure (no restart) &rarr; GUI-bridge appender re-attach.
     * <p>
     * Called from {@code doInitialize()} on every (re)start (step 1.7); the
     * GUI Apply path (P2.4) reuses this entry point, which is what makes a
     * live level change possible without a node restart (§9.1). Defensive: a
     * failure keeps the previous (or minimal) configuration and never breaks
     * node startup.
     * </p>
     *
     * @return true when the full configuration was applied to the context
     */
    public boolean applyNodeLoggingConfiguration() {
        if (this.profile == null) {
            return false;
        }
        try {
            this.nodeLoggingContext = NodeLoggingContextApplier.applyFull(
                    this.confFolder, NODE_LOGGING_CONTEXT_MANAGER, this.profile.getName());
            this.nodeLoggers = new NodeLoggers(this.nodeLoggingContext.loggerContext());
            if (this.profileLogger != null && this.profileConsoleAppender == null) {
                this.profileConsoleAppender = new ProfileConsoleAppender(this.profileLogger);
            }
            if (this.profileConsoleAppender != null) {
                this.profileConsoleAppender.attach(this.nodeLoggingContext.loggerContext());
            }
            logger.info("Isolated node LoggerContext '{}' reconfigured (full config: {})",
                    this.profile.getName(), this.nodeLoggingContext.configUri());
            return true;
        } catch (Throwable t) {
            logger.warn("Failed to apply the full node logging configuration for profile '{}' - "
                    + "keeping the previous configuration: {}", this.profile.getName(), t.getMessage());
            return false;
        }
    }

    /**
     * Returns the loggers of this node's isolated Log4j2 context (plan §7.1) —
     * the target API for the node engine classes (migrated package-by-package
     * in P3). Null until the context exists (i.e. the constructor succeeded);
     * the legacy SLF4J loggers stay the fallback meanwhile.
     *
     * @return the per-node logger facade, or null when the context was not created
     */
    public NodeLoggers loggers() {
        return this.nodeLoggers != null ? this.nodeLoggers : NodeLoggers.global();
    }

    /**
     * Bootstrap logic for starting all node components.
     */
    private void doInitialize() {
        String profileName = this.profile.getName();

        // ── Step 1: ProfileLogger ── created in the Signum(NodeProfile, Path) constructor
        // (moved out of here) so GUI consoles can attach before start() and no
        // bootstrap log line is lost.
        //
        // The logger is the FIRST thing established on (re)start: it must exist and be
        // registered before any component below emits a log, so every startup line is
        // captured and routed to the Node Console. getOrCreate() is idempotent — it
        // returns the SAME per-Signum logger created in the constructor. doShutdown()
        // deliberately does NOT close it (see there), so this logger survives
        // stop/start cycles and the console subscriber stays live across restarts.
        // (Mirrors the constructor, so the first start and any restart behave
        // identically.)
        this.profileLogger = application.utils.logging.NodeLoggerRegistry.getOrCreate(ModuleIds.NODE, profileName);

        // ── Step 1.5 (P2.4): REMOVED — the legacy bus-minLevel write for the node
        // module is replaced by the isolated-context apply of step 1.7 below
        // (NodeLoggingContextApplier): the node context's configuration is the
        // single level gate for node-context events, and the legacy (JUL-routed)
        // events stay gated by their own JUL levels.

        // ── Step 1.6 (F4.4): REMOVED — the per-node file log is written by the isolated
        // Log4j2 node context's RollingFile appender (node.fileLog.*); the redundant
        // bus-level ProfileFileAppender (a duplicate write to the same file) is gone.

        // ── Step 1.7: full apply of the isolated node LoggerContext config (P2.3) ──
        // L0+L1+L2 resolve -> generated-file write -> reconfigure of the SAME
        // context (no restart, plan §9.2A) -> GUI-bridge appender re-attach (a
        // reconfigure drops it without stopping it, P0 spike Q4). Defensive:
        // a failure keeps the minimal configuration, never breaks startup.
        applyNodeLoggingConfiguration();

        // ── Step 2.5: Create profile-scoped ShutdownManager ──
        this.shutdownManager = new ShutdownManager(this.propertyService, profileName, this.loggers());

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
        logger.info("Node profile '{}' initialization completed.", profileName);
        logger.info("Running network: {}", propertyService.getString(Props.NETWORK_NAME));
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
        this.derivedTableManager = new DerivedTableManager(this.loggers());

        // StatisticsManager + DBCacheManager
        this.statisticsManager = new StatisticsManagerImpl(this.timeService, this.loggers());
        this.dbCacheManager = new DBCacheManagerImpl(this.statisticsManager, this.loggers());

        // ThreadPool
        this.threadPool = new ThreadPool(this.propertyService, this.loggers());

        // Db.init + Dbs + Stores
        this.dbContext = DbContext.create(this.propertyService, this.dbCacheManager, this.loggers());
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

        this.economicClustering = new EconomicClustering(this.blockchain, this.fluxCapacitor, this.loggers());

        this.accountService = new AccountServiceImpl(
                this.stores.getAccountStore(), this.stores.getAssetTransferStore(), this.blockchain);

        this.downloadCache = new DownloadCacheImpl(
                this.propertyService, this.fluxCapacitor, this.blockchain, this.loggers());

        boolean mockMining = this.propertyService.getBoolean(Props.DEV_MOCK_MINING);
        this.generator = NodeComponentFactory.createGenerator(
                mockMining, this.propertyService, this.blockchain,
                this.accountService, this.timeService, this.fluxCapacitor, this.downloadCache, this.loggers());

        this.transactionService = new TransactionServiceImpl(
                this.accountService, this.blockchain);

        this.transactionProcessor = new TransactionProcessorImpl(
                this.propertyService, this.economicClustering, this.blockchain,
                this.stores, this.timeService, this.dbs,
                this.accountService, this.transactionService,
                this.fluxCapacitor, this.threadPool, this.loggers());
    }

    /**
     * Initializes remaining services and blockchain hooks.
     */
    private void initServicesAndHooks() {
        final TransactionDb transactionDb = this.dbs.getTransactionDb();
        final BlockDb blockDb = this.dbs.getBlockDb();
        final BlockchainStore blockchainStore = this.stores.getBlockchainStore();

        this.atProcessorCache = new ATProcessorCache(
                this.propertyService, this.stores.getAtStore(), this.dbContext, this.dbs, this.loggers());

        this.atConstants = new AtConstants(this.fluxCapacitor);

        ((SqlATStore) this.stores.getAtStore()).setAtConstants(this.atConstants);

        AliasStore aliasStore = this.stores.getAliasStore();
        this.subscriptionService = new SubscriptionServiceImpl(
                this.stores.getSubscriptionStore(), transactionDb, this.blockchain,
                this.fluxCapacitor, this.aliasService, aliasStore, this.accountService,
                this.loggers());
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
                this.stores.getIndirectIncomingStore(), this.propertyService, this.loggers());

        this.atProcessingContext = new ATProcessingContext(
                this.atConstants, this.atProcessorCache, this.propertyService,
                this.fluxCapacitor, this.blockchain, this.stores.getAtStore(),
                this.stores.getAccountStore(), this.accountService,
                this.assetExchange, this.stores.getIndirectIncomingStore(),
                this.stores.getAssetStore(), new ATPendingState(), this.loggers());

        this.atService = new ATServiceImpl(this.stores.getAtStore(), this.atProcessingContext);

        NetworkParameters params = this.networkParameters;
        this.blockService = new BlockServiceImpl(
                this.accountService, this.transactionService, this.blockchain,
                this.downloadCache, this.generator, this.fluxCapacitor,
                this.propertyService, params, this.loggers());

        this.blockchainProcessor = NodeComponentFactory.createBlockchainProcessor(
                this.threadPool, this.blockService, this.transactionProcessor,
                this.blockchain, this.propertyService, this.subscriptionService,
                this.timeService, this.derivedTableManager, blockDb, transactionDb,
                this.economicClustering, blockchainStore, this.stores,
                this.escrowService, this.transactionService, this.downloadCache,
                this.generator, this.statisticsManager, this.dbCacheManager,
                this.accountService, this.indirectIncomingService,
                this.aliasService, this.fluxCapacitor, this.atService,
                this.atProcessorCache, this.loggers());

        this.derivedTableManager.setMinRollbackHeightSupplier(this.blockchainProcessor::getMinRollbackHeight);
        this.downloadCache.setBlockchainProcessor(this.blockchainProcessor);
        this.generator.generateForBlockchainProcessor(this.threadPool, this.blockchainProcessor);

        this.deeplinkQRCodeGenerator = new DeeplinkQRCodeGenerator();

        this.parameterService = new ParameterServiceImpl(
                this.accountService, this.aliasService, this.assetExchange,
                this.digitalGoodsStoreService, this.blockchain, this.blockchainProcessor,
                this.transactionProcessor, this.atService, this.stores.getAccountStore(),
                this.loggers());

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
                this.propertyService, this.blockchain, this.threadPool, this.timeService,
                this.loggers());
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
                this.peerManager, this.loggers()));
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
            logger.info("TIME WILL FLOW {} TIMES FASTER!", timeMultiplier);
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
                this.blockchain, this.stores.getAccountStore(), this.loggers());
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
                logger.info("Created missing database directory: {}", parent.toAbsolutePath());
            }
        } catch (Exception e) {
            logger.warn("Failed to ensure database directory exists: {}", e.getMessage());
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
                    logger.error("Error shutting down WebServer for profile '{}'", profileName, t);
                }
                this.webServer = null;
            }

            if (this.blockchainProcessor != null) {
                try {
                    this.blockchainProcessor.shutdown();
                } catch (Throwable t) {
                    if (this.shutdownManager != null) this.shutdownManager.markFailure("BlockchainProcessor");
                    logger.error("Error shutting down BlockchainProcessor for profile '{}'", profileName, t);
                }
                // CRITICAL: null out the reference so getBlockchainProcessor()
                // returns null during the STOPPED state. Without this, a racing
                // EDT call to ensureRuntimeWiring() (scheduled during STARTING
                // before doInitialize() has re-created the processor) would
                // register GUI listeners on the OLD shut-down instance and mark
                // listenerOwner as wired — blocking re-registration on the new
                // instance during RUNNING, leaving the status bar dead.
                this.blockchainProcessor = null;
            }

            if (this.peerManager != null) {
                try {
                    this.peerManager.shutdown(this.threadPool);
                } catch (Throwable t) {
                    if (this.shutdownManager != null) this.shutdownManager.markFailure("PeerManager");
                    logger.error("Error shutting down PeerManager for profile '{}'", profileName, t);
                }
                this.peerManager = null;
            }

            if (this.threadPool != null) {
                try {
                    this.threadPool.shutdown();
                } catch (Throwable t) {
                    if (this.shutdownManager != null) this.shutdownManager.markFailure("ThreadPool");
                    logger.error("Error shutting down ThreadPool for profile '{}'", profileName, t);
                }
                this.threadPool = null;
            }

            if (this.dbCacheManager != null) {
                try {
                    this.dbCacheManager.close();
                } catch (Throwable t) {
                    if (this.shutdownManager != null) this.shutdownManager.markFailure("DBCacheManager");
                    logger.error("Error closing DBCacheManager for profile '{}'", profileName, t);
                }
                this.dbCacheManager = null;
            }

            try {
                if (this.dbContext != null) this.dbContext.shutdown();
            } catch (Throwable t) {
                if (this.shutdownManager != null) this.shutdownManager.markFailure("Database");
                logger.error("Error shutting down DB for profile '{}'", profileName, t);
            }
            this.dbContext = null;

            if (this.shutdownManager != null) {
                this.shutdownManager.finishShutdown();
            }

            logger.info("Node profile '{}' stopped.", profileName);
        } catch (Exception e) {
            logger.error("Unexpected error during shutdown of profile '{}'", profileName, e);
        } finally {
            contextStarted.set(false);
            // Stop the isolated node LoggerContext LAST (audit P1.2): after every
            // motor above has quiesced (web/blockchain/peer/threadpool/DB), so their
            // shutdown logs reach the node's appenders before the context (and its
            // appenders) close. In the finally so it runs even if a motor shutdown
            // threw. The context OBJECT stays in the manager map — restart re-uses
            // the same instance (start + full re-apply, P0 Q6).
            NodeLoggingContext nlc = this.nodeLoggingContext;
            if (nlc != null) {
                try {
                    nlc.stop();
                } catch (Throwable t) {
                    logger.warn("Failed to stop the isolated node LoggerContext for profile '{}': {}",
                            profileName, t.getMessage());
                }
            }
            // ── The ProfileLogger is INTENTIONALLY KEPT ALIVE across stop/start cycles ──
            // It is a per-Signum (long-lived) resource and the GUI console subscriber
            // must persist across restarts. ProfileLogger.close() DISPOSES its
            // subscribers — and BaseConsoleSubscriber.dispose() is one-way (it stops the
            // LogEventBatcher and sets disposed=true, after which onLogEvent() bails out
            // immediately). So destroying + rebuilding the logger on every stop/start
            // would permanently kill the Node Console: every log after the FIRST
            // stop/start cycle would be silently dropped. Keeping the logger stable
            // (created in the constructor, re-adopted as Step 1 of doInitialize) means
            // the console subscriber stays live, so BOTH the shutdown lines (this
            // method) and the next startup lines (doInitialize) reach it. The logger is
            // closed only on permanent removal — see Signum.dispose(), invoked by
            // NodeModule in its stopAll()/removeNode() teardown paths (never in the
            // normal stop/start cycle).
        }
    }
}
