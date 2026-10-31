package application.module.logging.context;

import application.module.logging.LoggingProfileRepository;
import application.module.logging.appender.ProfileConsoleAppender;
import application.utils.logging.ProfileLogger;
import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogSubscriber;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.config.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2.3: {@link NodeLoggingContextApplier} — the full node-context Apply
 * pipeline (plan §9.2A) end to end on a real isolated LoggerContext:
 * minimal (constructor) phase, full (start) phase, live re-Apply without
 * restart, and the stop → apply restart path.
 */
@DisplayName("P2.3 - NodeLoggingContextApplier (full Apply pipeline)")
class NodeLoggingContextApplierTest {

    @TempDir
    Path confRoot;

    private final NodeLoggingContextManager manager = NodeLoggingContextManager.ofCurrentFactory();

    private final String profile = "applier-test-" + System.nanoTime();

    @AfterEach
    void cleanup() {
        manager.remove(profile);
    }

    /** Writes the user-editable L1 layer ({@code conf/node/logging/default.properties}). */
    private void writeL1(Properties props) throws Exception {
        Path file = new LoggingProfileRepository(confRoot.toString())
                .getProfileFile("node", LoggingProfileRepository.RESERVED_PROFILE_NAME);
        if (!props.isEmpty()) {
            Files.createDirectories(file.getParent());
            try (var w = Files.newBufferedWriter(file)) {
                props.store(w, "L1 test layer");
            }
        } else {
            Files.deleteIfExists(file);
        }
    }

    private String rootLevel(NodeLoggingContext ctx) {
        Configuration cfg = ctx.loggerContext().getConfiguration();
        return String.valueOf(cfg.getRootLogger().getLevel());
    }

    @Test
    @DisplayName("minimal phase: context starts, generated file exists, NO file appender (no log file before start)")
    void minimalApply() throws Exception {
        NodeLoggingContext ctx = NodeLoggingContextApplier.applyMinimal(confRoot, manager, profile);
        assertTrue(ctx.isStarted());
        assertEquals("INFO", rootLevel(ctx), "minimal config: L0-node default node.level=INFO or fallback INFO");
        Path nodeFile = confRoot.resolve("node").resolve("logging").resolve("runtime")
                .resolve(profile + ".log4j2.properties");
        assertTrue(Files.exists(nodeFile), "the generated node file must exist");
        Configuration cfg = ctx.loggerContext().getConfiguration();
        assertNull(cfg.getAppender(NodeLoggingConfigResolver.NODE_FILE_APPENDER),
                "the minimal config must NOT open a log file (two-phase start, plan §6.3)");
        assertNotNull(cfg.getAppender(NodeLoggingConfigResolver.NODE_CONSOLE_APPENDER),
                "the minimal config keeps a console appender");
    }

    @Test
    @DisplayName("full phase on the SAME context: L1 override wins, reconfigure without restart (plan §9.2A)")
    void fullApplyReconfiguresSameContext() throws Exception {
        NodeLoggingContext minimal = NodeLoggingContextApplier.applyMinimal(confRoot, manager, profile);
        Logger before = (Logger) minimal.loggerContext()
                .getLogger("application.module.node.applier.TestEngine");
        assertFalse(before.isDebugEnabled(), "L0 default root INFO: debug is off before the full apply");

        writeL1(props("node.level", "FINER"));
        NodeLoggingContext ctx = NodeLoggingContextApplier.applyFull(confRoot, manager, profile);
        assertSame(minimal, ctx, "the Apply must reconfigure the SAME context (no restart)");
        assertTrue(ctx.isStarted());
        assertEquals("DEBUG", rootLevel(ctx), "L1 node.level=FINER maps to Log4j2 DEBUG");
        assertTrue(before.isDebugEnabled(),
                "the pre-existing logger instance must re-bind to the new config (P0 Q2)");
        assertTrue(before.getContext().getName().startsWith("node-"),
                "the logger stays in the isolated node context");
    }

    @Test
    @DisplayName("live re-Apply: changing L1 between Applies takes effect on the running context (GUI Apply, §9.1)")
    void liveReApply() throws Exception {
        writeL1(props("node.level", "INFO"));
        NodeLoggingContext ctx = NodeLoggingContextApplier.applyFull(confRoot, manager, profile);
        assertEquals("INFO", rootLevel(ctx));

        writeL1(props("node.level", "SEVERE"));
        ctx = NodeLoggingContextApplier.applyFull(confRoot, manager, profile);
        assertEquals("ERROR", rootLevel(ctx), "SEVERE maps to Log4j2 ERROR — live, no restart");
    }

    @Test
    @DisplayName("stop -> applyFull restart path: the context starts again and picks up the config (Q6 via the applier)")
    void stopThenFullApply() throws Exception {
        writeL1(props("node.level", "FINE"));
        NodeLoggingContext ctx = NodeLoggingContextApplier.applyFull(confRoot, manager, profile);
        assertEquals("DEBUG", rootLevel(ctx));
        ctx.stop();
        assertFalse(ctx.isStarted());

        ctx = NodeLoggingContextApplier.applyFull(confRoot, manager, profile);
        assertTrue(ctx.isStarted(), "applyFull must start the stopped context again");
        assertEquals("DEBUG", rootLevel(ctx), "the config is re-applied after the stop/start cycle");
    }

    @Test
    @DisplayName("layer loaders: L0-node from the registry, L1 from disk, L2 safe without assignment")
    void layerLoaders() throws Exception {
        Properties l0Node = NodeLoggingContextApplier.loadL0Node();
        // the node provider may or may not be registered in the test JVM;
        // when it is, the L0 layer carries its hardcoded defaults
        if (l0Node.getProperty("node.level") != null) {
            assertEquals("INFO", l0Node.getProperty("node.level"));
        }

        writeL1(props("node.level", "FINE", "org.example.Gui.level", "OFF"));
        Properties l1 = NodeLoggingContextApplier.loadL1(confRoot);
        assertEquals("FINE", l1.getProperty("node.level"));
        assertEquals("OFF", l1.getProperty("org.example.Gui.level"));

        Properties l2 = new Properties();
        NodeLoggingContextApplier.loadL2(confRoot, "no-assignment-" + profile, l2);
        // an unassigned profile must not break the pipeline; without an
        // assignment the effective profile is the reserved (default) one
        for (String key : l2.stringPropertyNames()) {
            assertEquals(l1.getProperty(key), l2.getProperty(key),
                    "without an assignment L2 resolves to the reserved (default) profile");
        }

        String dir = NodeLoggingContextApplier.defaultFileLogDir(confRoot).replace('\\', '/');
        assertEquals("logs/node", dir,
                "the default file log dir is the portable RELATIVE logs/node (LoggingPaths SSOT, "
                        + "OUTSIDE the configuration tree): " + dir);
        Properties l0Global = NodeLoggingContextApplier.loadL0Global();
        // no cross-contamination: the node module's own L0 is not in L0-global
        assertNull(l0Global.getProperty("node.level"),
                "L0-global must not carry the node module's L0 keys");
    }

    @Test
    @DisplayName("global layers: the SYSTEM L1 (conf/system/logging/default.properties) overlays the node L1")
    void loadGlobalLayers_systemL1Wins() throws Exception {
        // node L1: global class at WARN, node level FINE
        writeL1(props("org.example.Gui.level", "WARN", "node.level", "FINE"));
        // system L1: the same global class at ERROR (system scope has priority)
        Path systemL1 = application.utils.config.LoggingPaths.systemL1File(confRoot.toString());
        Files.createDirectories(systemL1.getParent());
        try (var w = Files.newBufferedWriter(systemL1)) {
            props("org.example.Gui.level", "ERROR").store(w, "system L1 test layer");
        }
        try {
            NodeLoggingConfigResolver.Layers layers =
                    NodeLoggingContextApplier.loadGlobalLayers(confRoot);
            NodeLoggingConfigResolver.Resolved resolved = NodeLoggingConfigResolver.resolve(layers,
                    NodeLoggingContextApplier.defaultFileLogDir(confRoot));
            assertEquals("ERROR", resolved.globalLoggerLevels.get("org.example.Gui"),
                    "the system L1 must win over the node L1 for global-scope keys");
            assertEquals("DEBUG", resolved.nodeRootLevel,
                    "the node L1 still drives the node root level (FINE -> DEBUG)");
            // the NODE pipeline must NOT see the system L1 (node scope only)
            NodeLoggingConfigResolver.Layers nodeLayers =
                    NodeLoggingContextApplier.loadLayers(confRoot, null);
            NodeLoggingConfigResolver.Resolved nodeResolved = NodeLoggingConfigResolver.resolve(nodeLayers,
                    NodeLoggingContextApplier.defaultFileLogDir(confRoot));
            assertEquals("WARN", nodeResolved.globalLoggerLevels.get("org.example.Gui"),
                    "the node pipeline resolves only from the node L1 (no system L1 leak)");
        } finally {
            Files.deleteIfExists(systemL1);
        }
    }

    /** Captures every bus event for assertions. */
    private static final class Recorder implements LogSubscriber {
        final List<LogEvent> events = new CopyOnWriteArrayList<>();
        @Override
        public void onLogEvent(final LogEvent event) {
            events.add(event);
        }
        @Override
        public LogFilter getFilter() {
            return null;
        }
    }

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) {
            p.setProperty(kv[i], kv[i + 1]);
        }
        return p;
    }

    @Test
    @DisplayName("restart lifecycle: stop -> applyFull re-attaches the bus appender EXACTLY once (T16/Q6)")
    void restartBusDeliveryIsSingle() throws Exception {
        ProfileLogger bus = new ProfileLogger("node", "applier-bus-" + profile);
        bus.setForwardToSystem(false);
        Recorder recorder = new Recorder();
        bus.addSubscriber(recorder);
        ProfileConsoleAppender appender = new ProfileConsoleAppender(bus);
        try {
            NodeLoggingContext ctx = NodeLoggingContextApplier.applyFull(confRoot, manager, profile);
            appender.attach(ctx.loggerContext());
            Logger engine = (Logger) ctx.loggerContext()
                    .getLogger("application.module.node.applier.RestartEngine");
            engine.info("first run");
            assertEquals(1, recorder.events.size(), "one bus event per log call (first run)");

            // node stop (ctx.stop) + restart (applyFull again), SAME appender instance
            ctx.stop();
            ctx = NodeLoggingContextApplier.applyFull(confRoot, manager, profile);
            appender.attach(ctx.loggerContext());
            engine.info("second run");
            assertEquals(2, recorder.events.size(),
                    "exactly ONE event after the restart — no duplicate appender, no dead appender (P0 Q4/Q6)");

            Configuration cfg = ctx.loggerContext().getConfiguration();
            long bridgeCount = cfg.getRootLogger().getAppenders().keySet().stream()
                    .filter(ProfileConsoleAppender.NAME::equals).count();
            assertEquals(1L, bridgeCount,
                    "the GUI-bridge appender is attached to the node root exactly once after re-attach");
        } finally {
            bus.close();
        }
    }
}