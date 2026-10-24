package application.module.logging;

import application.module.logging.appender.SystemConsoleAppender;
import application.module.logging.context.NodeLoggingConfigResolver;
import application.module.logging.context.NodeLoggingConfigWriter;
import application.module.logging.context.NodeLoggingContextApplier;
import application.module.logging.context.SignumGlobalContext;
import application.module.node.NodeModule;
import application.module.node.Signum;
import application.utils.config.PropertiesProfileLoader;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * The GLOBAL logging Apply pipeline (per-node isolated LoggerContext plan,
 * §9.2 "global Apply" + §9.2 propagation to running nodes).
 * <p>
 * A global Apply (an edited global/L1 layer, a global class override, or a
 * re-Apply after a node-level change) does TWO things, in this order
 * (lock order: global → node, §9.2):
 * <ol>
 *   <li>resolves the layers (the node profile's L0/L1/L2 chain — the global
 *       values are the layers' global portions), writes the generated GLOBAL
 *       file and reconfigures the shared {@link SignumGlobalContext}
 *       (dormant destination until the P3.5 binding swap, but managed from
 *       now so the pipeline is complete);</li>
 *   <li><b>re-applies every RUNNING node</b>: the node generated files embed
 *       the global layers (§8.3), so a global change must be re-resolved and
 *       reconfigured into each running node context — through
 *       {@link Signum#applyNodeLoggingConfiguration()}, which also
 *       re-attaches each node's GUI-bridge appender. No node restart
 *       (§9.1).</li>
 * </ol>
 * The per-node re-Apply uses each node's OWN conf root and profile — a
 * global change reaches a node only through the shared layers, a node's own
 * L2 assignment stays authoritative.
 * </p>
 * <p>
 * <b>Defensive</b>: every step swallows its own failure (logged, never
 * thrown), so a global Apply can never break a running node or the GUI.
 * </p>
 * <p>Headless-safe (no Swing).</p>
 */
public final class GlobalLoggingApplier {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalLoggingApplier.class);

    private GlobalLoggingApplier() {
        // Utility class
    }

    /**
     * Applies the global logging configuration using the standard conf root.
     *
     * @return true when the global context was reconfigured (node re-applies
     *         always run afterwards; their outcome is logged per node)
     */
    public static boolean applyGlobal() {
        return applyGlobal(PropertiesProfileLoader.DEFAULT_CONF_ROOT);
    }

    /**
     * Applies the global logging configuration (see the class javadoc for the
     * two-step pipeline). Never throws.
     *
     * @param confRoot the base configuration root (e.g. {@code ./conf})
     * @return true when the global context was reconfigured
     */
    public static boolean applyGlobal(String confRoot) {
        boolean globalApplied = false;
        try {
            Path root = Path.of(confRoot).toAbsolutePath();
            // Resolve from the node module's layers (the global portions are
            // L0-global + the global class overrides; the node profile's own
            // L2 only affects its own node file, not the global one).
            NodeLoggingConfigResolver.Resolved resolved = NodeLoggingConfigResolver.resolve(
                    NodeLoggingContextApplier.loadLayers(root, null),
                    NodeLoggingContextApplier.defaultFileLogDir(root));
            final Path globalFile = NodeLoggingConfigWriter.writeGlobal(root, resolved);
            SignumGlobalContext global = SignumGlobalContext.shared();
            if (global.configUri() == null || !global.configUri().equals(globalFile.toUri())) {
                global.reconfigureFrom(globalFile.toUri());
            } else {
                global.reconfigure();
            }
            attachGlobalConsole(global);
            globalApplied = true;
            LOGGER.info("Global logging configuration applied ({}); re-applying to running nodes",
                    globalFile);
        } catch (Throwable t) {
            LOGGER.warn("Failed to apply the global logging configuration (running nodes are "
                    + "re-applied from their own files below): {}", t.getMessage());
        }
        // Step 2: propagate to every RUNNING node (§9.2). Each apply is
        // node-scoped (its own lock) and independent of the others. A STOPPED
        // node is deliberately skipped: applyFull would start its (stopped)
        // context and open its log file — a stopped node must not hold log
        // file handles (two-phase start, §6.3); it picks the config up at
        // its next start.
        for (Signum signum : NodeModule.getInstance().getAll()) {
            if (signum == null || signum.getState() != Signum.State.RUNNING) {
                continue;
            }
            reapplyNode(signum);
        }
        return globalApplied;
    }

    /** Terminal (stdout) appender name on the global root (re-attach dedup key). */
    private static final String GLOBAL_CONSOLE_NAME = "globalConsole";
    /** Terminal line pattern (BriefLogFormatter shape, matching the node console appender). */
    private static final String GLOBAL_CONSOLE_PATTERN = "[%level] %d{yyyy-MM-dd HH:mm:ss} [%t] %logger{1} - %msg%n";

    /**
     * (Re)attaches the programmatic global-context appenders to the root (P3.5, plan §10.2): the
     * {@link SystemConsoleAppender} (GUI System Console bridge) and a terminal
     * {@link ConsoleAppender} (stdout). The generated global file is levels-only, and a
     * reconfigure REPLACES the configuration — dropping programmatic appenders without stopping
     * them (P0 spike fact) — so this must run after EVERY global reconfigure. Mirrors the
     * node's ProfileConsoleAppender re-attach. Idempotent (name-based dedup on the root → no
     * double delivery).
     */
    private static void attachGlobalConsole(SignumGlobalContext global) {
        try {
            LoggerConfig root = global.loggerContext().getConfiguration().getRootLogger();
            // GUI System Console bridge (programmatic; NOT in the generated file).
            new SystemConsoleAppender().attach(global.loggerContext());
            // Terminal (stdout): programmatic, matching the node console appender (§10.2).
            if (!root.getAppenders().containsKey(GLOBAL_CONSOLE_NAME)) {
                ConsoleAppender terminal = ConsoleAppender.newBuilder()
                        .setName(GLOBAL_CONSOLE_NAME)
                        .setLayout(PatternLayout.newBuilder().withPattern(GLOBAL_CONSOLE_PATTERN).build())
                        .setTarget(ConsoleAppender.Target.SYSTEM_OUT)
                        .build();
                terminal.start();
                root.addAppender(terminal, null, null);
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to attach the global console appenders: {}", t.getMessage());
        }
    }

    /**
     * Re-applies the node logging configuration of a single node
     * (resolve → generated file → same-context reconfigure → GUI-bridge
     * re-attach). Defensive: never throws. Exposed for tests and for
     * future per-node Apply triggers; the production path is
     * {@link #applyGlobal(String)}.
     *
     * @return the outcome of the node's own apply (true when reconfigured)
     */
    static boolean reapplyNode(Signum signum) {
        if (signum == null) {
            return false;
        }
        try {
            return signum.applyNodeLoggingConfiguration();
        } catch (Throwable t) {
            LOGGER.warn("Failed to re-apply the logging configuration to node '{}': {}",
                    signum.getProfileName(), t.getMessage());
            return false;
        }
    }
}