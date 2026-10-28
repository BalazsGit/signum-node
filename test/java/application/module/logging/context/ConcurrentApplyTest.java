package application.module.logging.context;

import application.module.logging.LoggingProfileRepository;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T11 (acceptance, plan §13/P7.1): concurrent configuration MODIFICATIONS on
 * the SAME node — full Apply (file + reconfigure, under the node applyLock)
 * mixed with the in-memory "fast path" level change — must stay isolated per
 * node: no deadlock, no exception, and each node always ends in ONE coherent
 * configuration (never a half-reconfigured / mixed LoggerConfig state, plan
 * §14.4-A3). The join-with-timeout doubles as the deadlock detector.
 * <p>
 * Headless-safe (no Swing).
 */
@DisplayName("T11 - concurrent config modification on the same node")
class ConcurrentApplyTest {

    @TempDir
    Path confRoot;

    private void writeL1(Properties props) throws Exception {
        Path file = new LoggingProfileRepository(confRoot.toString())
                .getProfileFile("node", LoggingProfileRepository.RESERVED_PROFILE_NAME);
        if (!props.isEmpty()) {
            Files.createDirectories(file.getParent());
            try (var w = Files.newBufferedWriter(file)) {
                props.store(w, "L1");
            }
        } else {
            Files.deleteIfExists(file);
        }
    }

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) p.setProperty(kv[i], kv[i + 1]);
        return p;
    }

    private static String rootLevel(NodeLoggingContext ctx) {
        return String.valueOf(ctx.loggerContext().getConfiguration().getRootLogger().getLevel());
    }

    @Test
    @DisplayName("concurrent full Apply on 2 nodes (8 threads): no deadlock, no exception, coherent end state")
    void concurrentApply_sameNode_noDeadlockCoherent() throws Exception {
        writeL1(props("node.level", "INFO"));
        NodeLoggingContextManager manager = NodeLoggingContextManager.shared();
        String a = "t11-a-" + System.nanoTime();
        String b = "t11-b-" + System.nanoTime();
        try {
            NodeLoggingContext ctxA = NodeLoggingContextApplier.applyFull(confRoot, manager, a);
            NodeLoggingContext ctxB = NodeLoggingContextApplier.applyFull(confRoot, manager, b);
            final int rounds = 10;
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            List<Future<?>> futures = new CopyOnWriteArrayList<>();
            try {
                // the first six threads alternate full Applies across both nodes;
                // the last two also mutate the in-memory level (fast path, without the
                // applyLock). The full Apply must always leave a coherent file state.
                for (int t = 0; t < 8; t++) {
                    final NodeLoggingContext target = (t % 2 == 0) ? ctxA : ctxB;
                    final String name = (t % 2 == 0) ? a : b;
                    final boolean fastPath = t >= 6;
                    futures.add(pool.submit(() -> {
                        for (int i = 0; i < rounds; i++) {
                            try {
                                if (fastPath) {
                                    var lc = target.loggerContext();
                                    lc.getConfiguration().getRootLogger()
                                            .setLevel((i % 2 == 0) ? Level.DEBUG : Level.WARN);
                                    lc.updateLoggers();
                                } else {
                                    NodeLoggingContextApplier.applyFull(confRoot, manager, name);
                                }
                            } catch (Throwable th) {
                                errors.add(th);
                                return;
                            }
                        }
                    }));
                }
                // join-with-timeout = the deadlock detector (plan T11)
                for (Future<?> f : futures) {
                    f.get(60, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            assertTrue(errors.isEmpty(),
                    "concurrent Apply/fast-path must not throw: " + errors);
            // one final full Apply per node: the file-defined (L1) state must win
            NodeLoggingContextApplier.applyFull(confRoot, manager, a);
            NodeLoggingContextApplier.applyFull(confRoot, manager, b);
            assertTrue(ctxA.isStarted() && ctxB.isStarted());
            assertEquals("INFO", rootLevel(ctxA), "node A must settle on the L1-defined config");
            assertEquals("INFO", rootLevel(ctxB), "node B must settle on the L1-defined config");
        } finally {
            manager.remove(a);
            manager.remove(b);
        }
    }

    @Test
    @DisplayName("fast-path level change is overridden by the next full Apply (plan §9.2 route B)")
    void fastPathChangeOverriddenByFullApply() throws Exception {
        writeL1(props("node.level", "INFO"));
        NodeLoggingContextManager manager = NodeLoggingContextManager.shared();
        String name = "t11-fast-" + System.nanoTime();
        try {
            NodeLoggingContext ctx = NodeLoggingContextApplier.applyFull(confRoot, manager, name);
            assertEquals("INFO", rootLevel(ctx));
            // in-memory fast path (no Apply, no file write): change the level live
            var lc = ctx.loggerContext();
            lc.getConfiguration().getRootLogger().setLevel(Level.DEBUG);
            lc.updateLoggers();
            assertEquals("DEBUG", rootLevel(ctx), "fast path changes the level without a file write");
            // the next full Apply restores the file-defined state (fast path keeps no saved state)
            NodeLoggingContextApplier.applyFull(confRoot, manager, name);
            assertEquals("INFO", rootLevel(ctx), "full Apply must override the fast-path change");
        } finally {
            manager.remove(name);
        }
    }

    /** A minimal valid Log4j2 properties config (root {@code level} + a console appender). */
    private static String nodeConfig(final String level) {
        return "status = ERROR\n"
                + "configWatch = false\n"
                + "rootLogger.level = " + level + "\n"
                + "appender.console.type = Console\n"
                + "appender.console.name = console\n"
                + "appender.console.layout.type = PatternLayout\n"
                + "appender.console.layout.pattern = %msg%n\n"
                + "rootLogger.appenderRef.console.ref = console\n";
    }

    private Path writeConfigFile(final String fileName, final String level) throws Exception {
        Path f = confRoot.resolve(fileName);
        Files.writeString(f, nodeConfig(level));
        return f;
    }

    @Test
    @DisplayName("concurrent reconfigure()/reconfigureFrom() on ONE context: applyLock keeps every observed state coherent (no torn config, T11)")
    void concurrentReconfigure_noTornState() throws Exception {
        NodeLoggingContextManager manager = NodeLoggingContextManager.shared();
        String name = "t11x-" + System.nanoTime();
        // two coherent target configs: A = INFO, B = DEBUG
        Path fileA = writeConfigFile(name + "-a.log4j2.properties", "INFO");
        Path fileB = writeConfigFile(name + "-b.log4j2.properties", "DEBUG");
        NodeLoggingContext ctx = manager.getOrCreate(name, fileA.toUri());
        final int threads = 8;
        final int rounds = 20;
        // every root level observed mid-churn: must be one of the two coherent configs (INFO/DEBUG)
        final Set<Level> observed = ConcurrentHashMap.newKeySet();
        try {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            List<Future<?>> futures = new CopyOnWriteArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int id = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < rounds; i++) {
                        try {
                            int op = (i + id) % 3;
                            if (op == 0) {
                                ctx.reconfigureFrom(fileA.toUri());
                            } else if (op == 1) {
                                ctx.reconfigureFrom(fileB.toUri());
                            } else {
                                ctx.reconfigure();
                            }
                            Level lvl = ctx.loggerContext().getConfiguration().getRootLogger().getLevel();
                            if (lvl != null) {
                                observed.add(lvl);
                            }
                        } catch (Throwable th) {
                            errors.add(th);
                            return;
                        }
                    }
                }));
            }
            try {
                // join-with-timeout = the deadlock detector (plan T11)
                for (Future<?> f : futures) {
                    f.get(60, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            assertTrue(errors.isEmpty(), "concurrent reconfigure()/reconfigureFrom() must not throw: " + errors);
            for (Level lvl : observed) {
                assertTrue(lvl == Level.INFO || lvl == Level.DEBUG,
                        "every observed root level must be a coherent config (INFO/DEBUG), never a torn state: " + lvl);
            }
            // settle deterministically: a final switch to config A must leave a coherent, started, logging context
            assertTrue(ctx.reconfigureFrom(fileA.toUri()), "the final reconfigureFrom must apply");
            assertEquals(Level.INFO, ctx.loggerContext().getConfiguration().getRootLogger().getLevel(),
                    "the settled context must be coherent (INFO from config A)");
            assertTrue(ctx.isStarted(), "the context must still be started after the concurrent churn");
            assertDoesNotThrow(() -> ctx.loggerContext().getLogger("t11x.coherent").info("still coherent"),
                    "the node must still be able to log after the concurrent churn");
        } finally {
            manager.remove(name);
        }
    }
}