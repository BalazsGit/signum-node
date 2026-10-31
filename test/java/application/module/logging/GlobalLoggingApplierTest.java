package application.module.logging;

import application.module.logging.context.NodeLoggingContext;
import application.module.logging.context.NodeLoggingContextApplier;
import application.module.logging.context.NodeLoggingContextManager;
import application.module.logging.context.NodeSystemContext;
import application.module.node.Signum;
import application.module.node.profile.NodeProfile;
import application.utils.logging.NodeLoggerRegistry;
import application.utils.logging.ProfileLogger;
import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogSubscriber;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.config.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2.4: {@link GlobalLoggingApplier} — the global Apply pipeline (plan §9.2):
 * the global context is reconfigured from the generated global file and every
 * running node re-applies the fresh layers into its own context — without a
 * restart. Includes T17 (parallel global + node apply consistency).
 */
@DisplayName("P2.4 - GlobalLoggingApplier (global Apply + node propagation)")
class GlobalLoggingApplierTest {

    @TempDir
    Path confRoot;

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

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) {
            p.setProperty(kv[i], kv[i + 1]);
        }
        return p;
    }

    @Test
    @DisplayName("global Apply: writes the generated global file and reconfigures the shared global context")
    void globalApply_writesGlobalFile_andReconfiguresSharedContext() throws Exception {
        writeL1(props("node.level", "FINE", "org.example.Gui.level", "ERROR"));

        assertTrue(GlobalLoggingApplier.applyGlobal(confRoot.toString()));

        Path globalFile = confRoot.resolve("system").resolve("logging").resolve("runtime")
                .resolve("global.log4j2.properties");
        assertTrue(Files.exists(globalFile), "the generated global file must exist");
        NodeSystemContext global = NodeSystemContext.shared();
        assertEquals(globalFile.toUri(), global.configUri(),
                "the shared global context must now be configured from the generated file");
        assertTrue(global.loggerContext().isStarted());
        assertEquals("system", global.loggerContext().getName());
    }

    @Test
    @DisplayName("global Apply twice: the SAME shared context is reconfigured in place (no second context)")
    void globalApply_secondRun_reconfiguresSameSharedContext() throws Exception {
        writeL1(props("node.level", "INFO"));
        assertTrue(GlobalLoggingApplier.applyGlobal(confRoot.toString()));
        NodeSystemContext first = NodeSystemContext.shared();

        writeL1(props("node.level", "SEVERE"));
        assertTrue(GlobalLoggingApplier.applyGlobal(confRoot.toString()));

        assertSame(first, NodeSystemContext.shared(), "exactly one global context for the JVM");
        assertTrue(NodeSystemContext.shared().loggerContext().isStarted(),
                "the global context stays started across in-place reconfigures");
    }

    @Test
    @DisplayName("node propagation: re-Apply reconfigures the Signum's context live and the bus stays single-delivery")
    void reapplyNode_reconfiguresTheSignumContext_live_noRestart() throws Exception {
        String name = "p24-global-" + System.nanoTime();
        Signum signum = new Signum(new NodeProfile(name), confRoot);
        try {
            NodeLoggingContext minimal = NodeLoggingContextManager.shared().get(name);
            assertNotNull(minimal, "the constructor created the minimal context");

            // the user saves a new assigned profile (the GUI Apply's persistence step)
            new LoggingProfileRepository(confRoot.toString())
                    .saveProps("node", "custom", props("node.level", "FINE"));
            new LoggingAssignmentStore(confRoot.toString())
                    .setAssignment(name, Map.of("node", "custom"));

            assertTrue(GlobalLoggingApplier.reapplyNode(signum), "the node's context was reconfigured");

            NodeLoggingContext ctx = NodeLoggingContextManager.shared().get(name);
            assertSame(minimal, ctx, "the propagation must reconfigure the SAME context (no restart)");
            assertEquals("DEBUG", rootLevel(ctx), "L2 node.level=FINE → DEBUG, applied live");

            // the GUI-bridge appender survives the reconfigure (re-attached) — single delivery.
            // NOTE: the ProfileLogger is a bounded replay buffer — addSubscriber() re-delivers
            // the node's own history to the new subscriber. Since B6.5b, Signum's instance
            // logger writes its "created"/"reconfigured" lifecycle lines to the node context,
            // so they land in this bus. Assert the DELTA caused by the single "hello" log,
            // not the absolute (replayed) count.
            ProfileLogger bus = NodeLoggerRegistry.get("node", name);
            assertNotNull(bus);
            Recorder recorder = new Recorder();
            bus.addSubscriber(recorder);
            int replayed = recorder.events.size();
            ((Logger) ctx.loggerContext().getLogger("application.module.node.globalapply.Engine"))
                    .info("hello");
            assertEquals(replayed + 1, recorder.events.size(),
                    "the single 'hello' log must add exactly ONE bus event — no duplicate, no dead appender after the live re-apply");
        } finally {
            signum.dispose();
        }
    }

    @Test
    @DisplayName("T17: parallel global + node Apply — no deadlock, no exceptions, consistent state, last-committed-version-wins")
    void parallelGlobalAndNodeApply_consistent() throws Exception {
        String name = "p24-parallel-" + System.nanoTime();
        writeL1(props("node.level", "INFO"));
        NodeLoggingContextManager manager = NodeLoggingContextManager.shared();
        NodeLoggingContext ctx = NodeLoggingContextApplier.applyFull(confRoot, manager, name);
        int rounds = 25;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try {
            java.util.concurrent.Future<?> globalTask = pool.submit(() -> {
                for (int i = 0; i < rounds; i++) {
                    GlobalLoggingApplier.applyGlobal(confRoot.toString());
                }
            });
            java.util.concurrent.Future<?> nodeTask = pool.submit(() -> {
                try {
                    for (int i = 0; i < rounds; i++) {
                        NodeLoggingContextApplier.applyFull(confRoot, manager, name);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
            // the get() timeouts double as the deadlock detector (plan T17)
            globalTask.get(60, java.util.concurrent.TimeUnit.SECONDS);
            nodeTask.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertTrue(errors.isEmpty(), "no exceptions during parallel applies: " + errors);
        try {
            assertTrue(ctx.isStarted());
            assertEquals("INFO", rootLevel(ctx),
                    "the node context's final config is consistent with the L1 layer");

            // Last-committed-version-wins (plan §9.2 / §14.4-A3): after the parallel storm,
            // changing L1 and re-applying must bring the node to the LATEST committed value —
            // no stale (INFO) snapshot can linger. The global Apply is serialized by
            // GLOBAL_APPLY_LOCK and every apply re-reads the source layers (L0/L1/L2), so the
            // newest committed L1 always wins.
            writeL1(props("node.level", "DEBUG"));
            NodeLoggingContextApplier.applyFull(confRoot, manager, name);
            assertEquals("DEBUG", rootLevel(ctx),
                    "the node Apply must pick up the LATEST committed L1 (DEBUG), not the stale INFO");
            writeL1(props("node.level", "WARN"));
            NodeLoggingContextApplier.applyFull(confRoot, manager, name);
            assertEquals("WARN", rootLevel(ctx), "the newest committed L1 (WARN) wins");
        } finally {
            manager.remove(name);
        }
    }

    @Test
    @DisplayName("global Apply is defensive: it never throws even with a missing conf root")
    void globalApply_invalidConfRoot_neverThrows() {
        Path missing = confRoot.resolve("does-not-exist");
        // no exception — and the global context stays usable
        GlobalLoggingApplier.applyGlobal(missing.toString());
        assertTrue(NodeSystemContext.shared().loggerContext().isStarted());
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
}