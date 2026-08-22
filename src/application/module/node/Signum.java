package application.module.node;

import application.module.node.props.CaselessProperties;
import application.module.node.props.PropertyService;
import application.module.node.props.PropertyServiceImpl;
import application.module.node.props.Props;
import application.module.node.util.LoggerConfigurator;
import application.module.node.web.server.WebServer;
import application.module.node.instance.NodeCoreContext;
import application.module.node.instance.NodeCoreContextBuilder;
import application.module.node.instance.NodeFactory;
import application.module.node.lifecycle.NodeLifecycleManager;
import application.module.node.profile.NodeProfile;
import application.utils.config.ConfigPaths;
import application.utils.config.PropertiesProfileLoader;
import application.utils.io.PathUtils;

import java.util.Objects;

import java.io.BufferedReader;
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
import signumj.util.SignumUtils;

/**
 * The main class of the Signum node.
 * <p>
 * This is the public facade for a single Signum node instance.
 * It owns a {@link NodeCoreContext} that holds all runtime components,
 * and delegates lifecycle + service access to it.
 * </p>
 *
 * @since 4.0 Greenfield facade architecture (Phase E1-E4)
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

    // Profile tracking (lightweight, safe to keep static for legacy bootstrap)
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

    // =========================================================================
    // Instance state (Facade)
    // =========================================================================

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

    // =========================================================================
    // Legacy bootstrap helpers (static, transitional)
    // =========================================================================

    /** Logger used only by the legacy bootstrap / init path. */
    private static Logger logger;

    /** Tracks whether the legacy init() has already run. */
    private static AtomicBoolean isInitialized = new AtomicBoolean(false);

    /** Tracks whether a shutdown is in progress (legacy). */
    private static AtomicBoolean isShutdown = new AtomicBoolean(false);

    /** Tracks whether the node was stopped (legacy). */
    private static AtomicBoolean nodeStopped = new AtomicBoolean(false);

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Private constructor for legacy static bootstrap (Signum.init/loadWallet).
     * Creates no context -- legacy path populates NodeCoreContext via builder.
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

    // =========================================================================
    // Lifecycle delegation
    // =========================================================================

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

    // =========================================================================
    // Identity
    // =========================================================================

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

    /**
     * Returns the owned NodeCoreContext.
     * Returns null when using legacy static bootstrap path.
     * <p>
     * This method is intentionally public so that infrastructure components
     * like {@link application.module.node.lifecycle.NodeProfileRuntime} can
     * access the context for lifecycle management and backwards compatibility.
     * </p>
     *
     * @return the NodeCoreContext, or null
     */
    public NodeCoreContext getContext() {
        return context;
    }

    // =========================================================================
    // Profile management
    // =========================================================================

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

    // =========================================================================
    // Bootstrap helpers (logger)
    // =========================================================================

    public static void setLogger(Logger l) {
        logger = l;
    }

    private static void ensureLogger() {
        if (logger == null) {
            logger = LoggerFactory.getLogger(Signum.class);
        }
    }

    // =========================================================================
    // Properties loading
    // =========================================================================

    /**
     * Loads properties for a specific named profile using the unified PropertiesProfileLoader.
     * Architecture: Hardcoded defaults (Props.java) -> Profile .properties file only.
     * Path schema: ../conf/node/profiles/{profileName}.properties
     *
     * @param confFolder  The base configuration folder (e.g., "../conf")
     * @param profileName The profile name to load (without .properties extension)
     * @return PropertyService with loaded properties, or empty one if file not found
     */
    public static PropertyService loadPropertiesForProfile(String confFolder, String profileName) {
        application.module.node.props.CaselessProperties properties = new application.module.node.props.CaselessProperties();

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
        String defaultProfile = PROPERTIES_NAME;
        return loadPropertiesForProfile(confFolder, defaultProfile);
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

    // =========================================================================
    // Main / init / shutdown (legacy entry points – delegate to lifecycle mgr)
    // =========================================================================

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

    private static boolean validateVersionNotDev(PropertyService propertyService) {
        if (VERSION.isPrelease()
                && propertyService.getString(Props.NETWORK_NAME)
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
     * Shuts down all running node profiles via NodeLifecycleManager.
     * Delegates to modern lifecycle management and performs legacy cleanup as fallback.
     */
    public static void shutdownNode() {
        ensureLogger();
        NodeLifecycleManager.getInstance().stopAllProfiles();
        if (!isShutdown.get()) {
            shutdown(false);
        }
    }

    /**
     * Starts the default node profile via NodeLifecycleManager.
     * Delegates to modern lifecycle management. Falls back to legacy init only when no active profile is set.
     */
    public static void startNode() {
        initShutdown();
        String profile = getActiveNodeProfile();
        NodeLifecycleManager manager = NodeLifecycleManager.getInstance();
        manager.discoverProfiles();
        if (profile != null && !profile.isEmpty()) {
            manager.startProfile(profile);
        } else {
            // Fallback: legacy init when no profile discovered
            logger.warn("No active node profile configured. Falling back to legacy Signum.init()");
            init(CONF_FOLDER);
        }
    }

    public static void init(CaselessProperties customProperties) {
        if (isInitialized.compareAndSet(false, true)) {
            ensureLogger();
            // Delegate to NodeCoreContext builder + lifecycle manager for greenfield path
            String profileName = getActiveNodeProfile();
            try {
                NodeProfile profile = new NodeProfile(profileName != null ? profileName : PROPERTIES_NAME);
                Path confFolder = PathUtils.resolvePath(CONF_FOLDER);
                NodeCoreContext ctx = new NodeCoreContextBuilder(profile.getName(), confFolder).build();
                Signum signum = new Signum(profile, ctx);
                signum.start();
                NodeFactory.getInstance().register(signum);
            } catch (Exception e) {
                logger.error("Failed to initialize node via greenfield path", e);
                throw new RuntimeException("Failed to initialize Signum node", e);
            }
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

        // Use greenfield builder path
        String profileName = getActiveNodeProfile();
        NodeProfile profile = new NodeProfile(profileName != null ? profileName : PROPERTIES_NAME);
        try {
            NodeCoreContext ctx = new NodeCoreContextBuilder(profile.getName(), confPath).build();
            Signum signum = new Signum(profile, ctx);
            signum.start();
            NodeFactory.getInstance().register(signum);
        } catch (Exception e) {
            logger.error("Failed to initialize node via greenfield builder", e);
            throw new RuntimeException("Failed to initialize Signum node", e);
        }
    }

    // =========================================================================
    // Console command handler (legacy)
    // =========================================================================

    private static void commandHandler() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        try {
            String command;
            while ((command = reader.readLine()) != null) {
                Signum node = NodeFactory.getInstance().get(getActiveNodeProfile());
                if (node != null) {
                    node.processCommandInstance(command);
                }
            }
        } catch (IOException e) {
            // ignore
        }
    }

    /**
     * Instance-scoped command processing.
     * Operates on this Signum instance's context directly (no static bridge).
     *
     * @param command the console command string
     */
    public void processCommandInstance(String command) {
        ensureLogger();
        logger.debug("received command: >{}<", command);

        NodeCoreContext ctx = this.context;
        BlockchainProcessor proc = ctx != null ? ctx.getBlockchainProcessor() : null;
        Blockchain chain = ctx != null ? (Blockchain) ctx.getBlockchain() : null;

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
     * handles the full teardown. Otherwise falls back to logging only.
     *
     * @param ignoreDbShutdown if true, shuts down everything but the database.
     */
    public static void shutdown(boolean ignoreDbShutdown) {
        ensureLogger();

        // Multi-node mode: stop all registered nodes via NodeFactory
        if (NodeFactory.getInstance().size() > 0) {
            logger.info("Shutting down all {} registered node(s)", NodeFactory.getInstance().size());
            NodeFactory.getInstance().stopAll();
            isShutdown.set(true);
            nodeStopped.set(true);
            LoggerConfigurator.shutdown();
            return;
        }

        // Legacy static mode (should no longer be reached in normal operation)
        if (isShutdown.get() && !nodeStopped.get()) {
            logger.info("Already shutting down...");
        }

        synchronized (isShutdown) {
            if (isShutdown.getAndSet(true)) {
                return;
            }

            logger.info("Shutting down...");
            logger.info("Do not force exit or kill the node process.");

            // No static components to shut down – everything is in NodeCoreContext
            logger.info("BRS {} stopped.", VERSION);
            LoggerConfigurator.shutdown();
            nodeStopped.set(true);
        }
    }
}