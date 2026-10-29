package application.module.node;

import application.module.logging.appender.ProfileConsoleAppender;
import application.module.logging.context.NodeLoggingContext;
import application.module.logging.context.NodeLoggingContextManager;
import application.module.logging.context.NodeLoggers;
import application.module.node.profile.NodeProfile;
import application.utils.logging.ProfileLogger;
import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogSubscriber;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3-B6.5b: the {@link Signum} object's OWN logger is split out of the static
 * {@code LogManager.getLogger(Signum.class)} (JVM-global) into a node-scoped instance field
 * assigned in the constructor from the node's isolated LoggerContext. This closes the last
 * remaining global logger inside the per-node {@code Signum}: a node's own lifecycle/config
 * logging now lands in its OWN node context / profile console instead of the shared JVM-global
 * context. The static entry points ({@code main} / {@code loadPropertiesForProfile}) keep a
 * global bootstrap logger — the two-tier model (L0 container vs L1 per-node Signum, plan §7.6).
 * <p>
 * Mirrors the P3-B6 {@code TopLevelBatchLoggerWiringTest} gate: a migrated component's logger
 * must be bound to the node context, and two nodes must get two separate contexts.
 * </p>
 */
@DisplayName("P3-B6.5b - Signum's own logger is node-scoped (split from the static bootstrap logger)")
class SignumLoggerWiringTest {

    @TempDir
    Path confRoot;

    /** The private node-scoped {@code logger} field of a Signum (reflection, same as the P3-B6 gate). */
    private static Logger loggerOf(Signum signum) throws Exception {
        Field f = Signum.class.getDeclaredField("logger");
        f.setAccessible(true);
        return (Logger) f.get(signum);
    }

    private static org.apache.logging.log4j.core.LoggerContext ctxOf(Logger logger) {
        return ((org.apache.logging.log4j.core.Logger) logger).getContext();
    }

    /** The private static {@code BOOTSTRAP_LOGGER} field of a Signum (reflection). */
    private static Logger bootstrapLoggerOf() throws Exception {
        Field f = Signum.class.getDeclaredField("BOOTSTRAP_LOGGER");
        f.setAccessible(true);
        return (Logger) f.get(null);
    }

    /** The private {@code nodeLoggingContext} field of a Signum (reflection). */
    private static NodeLoggingContext nodeLoggingContextOf(Signum signum) throws Exception {
        Field f = Signum.class.getDeclaredField("nodeLoggingContext");
        f.setAccessible(true);
        return (NodeLoggingContext) f.get(signum);
    }

    /** The private {@code profileConsoleAppender} field of a Signum (reflection). */
    private static ProfileConsoleAppender profileConsoleAppenderOf(Signum signum) throws Exception {
        Field f = Signum.class.getDeclaredField("profileConsoleAppender");
        f.setAccessible(true);
        return (ProfileConsoleAppender) f.get(signum);
    }

    @Test
    @DisplayName("a Signum's instance logger is bound to its own node context, NOT the JVM-global context")
    void signumLoggerBoundToNodeContext() throws Exception {
        String name = "p365b-single-" + System.nanoTime();
        Signum signum = new Signum(new NodeProfile(name), confRoot);
        NodeModule.getInstance().addNode(signum);
        try {
            Logger logger = loggerOf(signum);
            NodeLoggingContext nlc = NodeLoggingContextManager.shared().get(name);
            assertNotNull(logger, "the node-scoped instance logger must be assigned in the constructor");
            assertNotNull(nlc, "the constructor must register the node context in the shared manager");
            assertSame(nlc.loggerContext(), ctxOf(logger),
                    "Signum's own logger must be bound to its node context");
            assertNotSame(LogManager.getContext(), ctxOf(logger),
                    "Signum's own logger must NOT be the JVM-global context (that was the pre-fix behaviour)");
        } finally {
            cleanup(name);
        }
    }

    @Test
    @DisplayName("two Signum nodes each log through a logger bound to their OWN separate context")
    void twoSignumNodesIsolated() throws Exception {
        String a = "p365b-a-" + System.nanoTime();
        String b = "p365b-b-" + System.nanoTime();
        Signum sa = new Signum(new NodeProfile(a), confRoot);
        Signum sb = new Signum(new NodeProfile(b), confRoot);
        NodeModule.getInstance().addNode(sa);
        NodeModule.getInstance().addNode(sb);
        try {
            Logger la = loggerOf(sa);
            Logger lb = loggerOf(sb);
            assertNotSame(ctxOf(la), ctxOf(lb),
                    "two Signum nodes must log to two separate node contexts");
            assertSame(NodeLoggingContextManager.shared().get(a).loggerContext(), ctxOf(la),
                    "Signum A's logger must be bound to Signum A's context");
            assertSame(NodeLoggingContextManager.shared().get(b).loggerContext(), ctxOf(lb),
                    "Signum B's logger must be bound to Signum B's context");
        } finally {
            cleanup(a);
            cleanup(b);
        }
    }

    @Test
    @DisplayName("a logging-config failure at construction falls back to the legacy chain (defensive: the node still boots)")
    void constructionFailureFallsBackToLegacyChain() throws Exception {
        String name = "t19-fallback-" + System.nanoTime();
        // Force applyMinimal to fail: with {confRoot}/node a regular file, the generated
        // {confRoot}/node/logging/runtime/{profile}.log4j2.properties cannot be created
        // (Files.createDirectories throws). The Signum constructor must swallow the failure
        // and keep the node bootable on the legacy (JVM-global) chain — never throw.
        Files.createFile(confRoot.resolve("node"));
        Signum signum = assertDoesNotThrow(
                () -> new Signum(new NodeProfile(name), confRoot),
                "a logging-config failure must not throw out of node construction (defensive contract)");
        try {
            assertNull(NodeLoggingContextManager.shared().get(name),
                    "on a construction failure NO isolated node context must be registered");
            assertSame(bootstrapLoggerOf(), loggerOf(signum),
                    "on a construction failure Signum's logger must BE the static bootstrap logger (legacy chain)");
            assertSame(LogManager.getContext(false), ctxOf(signum.loggers().forClass(Signum.class)),
                    "loggers() must fall back to the global facade (NodeLoggers.global) when the node context was not created");
        } finally {
            cleanup(name);
        }
    }

    @Test
    @DisplayName("the Signum constructor wires the full no-DB logging DI graph (A8 scoped: bus, context, NodeLoggers, GUI-bridge, logger)")
    void constructorWiresLoggingDiGraph() throws Exception {
        // SCOPE (audit A8, accepted blocker): this pins the CONSTRUCTOR-level (no-DB) DI graph
        // (Steps 1-2: bus + isolated context + NodeLoggers + GUI-bridge + own logger). The
        // DB-gated components (DbContext / BlockchainProcessor / WebServerContext / ATProcessingContext /
        // services — built in doInitialize(), which requires a real database) are OUT of scope here
        // and remain the documented A8 blocker (needs a DB-backed integration test).
        String name = "a8-wiring-" + System.nanoTime();
        Signum signum = new Signum(new NodeProfile(name), confRoot);
        NodeModule.getInstance().addNode(signum);
        try {
            // Step 1: the per-node bus (ProfileLogger)
            ProfileLogger bus = signum.getProfileLogger();
            assertNotNull(bus, "the constructor must create the per-node ProfileLogger bus (Step 1)");
            // Step 2: the isolated node LoggerContext, created + started
            NodeLoggingContext nlc = nodeLoggingContextOf(signum);
            assertNotNull(nlc, "the constructor must create the isolated node LoggerContext (Step 2)");
            assertTrue(nlc.isStarted(), "the node context must be started in the constructor");
            // NodeLoggers bound to THAT context (the engine's target API)
            NodeLoggers loggers = signum.loggers();
            assertSame(nlc.loggerContext(), loggers.context(),
                    "NodeLoggers must be bound to the node's own LoggerContext");
            // the GUI-bridge appender: wired to the bus AND attached exactly once to the node root
            ProfileConsoleAppender bridge = profileConsoleAppenderOf(signum);
            assertNotNull(bridge, "the constructor must create the GUI-bridge appender");
            assertSame(bus, bridge.target(), "the GUI-bridge appender must forward to the SAME per-node bus");
            long attached = nlc.loggerContext().getConfiguration().getRootLogger().getAppenders().keySet().stream()
                    .filter(ProfileConsoleAppender.NAME::equals).count();
            assertEquals(1L, attached, "the GUI-bridge appender must be attached to the node root exactly once");
            // Signum's own logger bound to the node context (not the global)
            Logger logger = loggerOf(signum);
            assertSame(nlc.loggerContext(), ctxOf(logger),
                    "Signum's own logger must be bound to the node context");
            // end-to-end: Signum's own logger reaches the bus through the bridge (full wiring path).
            // The bus's replay buffer may have already delivered pre-attach logs (e.g. the ctor's
            // "created" line), so assert the probe adds EXACTLY one new event (post-attach delta).
            bus.setForwardToSystem(false);
            Recorder recorder = new Recorder();
            bus.addSubscriber(recorder);
            int before = recorder.events.size();
            logger.info("wiring-probe");
            assertEquals(before + 1, recorder.events.size(),
                    "a log on Signum's logger must reach the bus through the GUI bridge (exactly one new event)");
        } finally {
            cleanup(name);
        }
    }

    /** Unregisters the node from NodeModule and disposes its context from the shared manager. */
    private static void cleanup(String name) {
        Signum s = NodeModule.getInstance().get(name);
        if (s != null) {
            if (s.isRunning()) {
                try {
                    s.stop();
                } catch (Exception ignored) {
                    // best-effort teardown; the context removal below is the authoritative cleanup
                }
            }
            NodeModule.getInstance().removeNode(name);
        }
        NodeLoggingContextManager.shared().remove(name);
    }

    /** Captures every bus event for assertions (mirrors NodeLoggingContextApplierTest.Recorder). */
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
}