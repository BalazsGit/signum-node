package application.module.logging;

import application.module.logging.appender.ProfileConsoleAppender;
import application.module.logging.context.NodeLoggingContext;
import application.module.logging.context.NodeLoggingContextManager;
import application.module.node.NodeModule;
import application.module.node.Signum;
import application.module.node.profile.NodeProfile;
import application.utils.config.ModuleIds;
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
 * P2.4: the GUI Apply lifecycle (plan §9.1) — the old "Apply requires a
 * node restart" limitation is gone: the saved logging profile is resolved
 * into the node's generated file and reconfigured into the SAME isolated
 * LoggerContext while the node keeps running, with the GUI-bridge appender
 * re-attached (single bus delivery).
 */
@DisplayName("P2.4 - Profile apply lifecycle (GUI Apply → live reconfigure, no restart)")
class ProfileApplyLifecycleTest {

    @TempDir
    Path confRoot;

    @Test
    @DisplayName("GUI Apply routes through the isolated context: same context, live level change, single bus delivery")
    void guiApply_reconfiguresContextLive() throws Exception {
        String name = "p24-lifecycle-" + System.nanoTime();
        Signum signum = new Signum(new NodeProfile(name), confRoot);
        NodeModule.getInstance().addNode(signum);
        try {
            NodeLoggingContext minimal = NodeLoggingContextManager.shared().get(name);
            assertNotNull(minimal, "the constructor created the minimal context (L0 only)");
            Logger engine = (Logger) minimal.loggerContext()
                    .getLogger("application.module.node.lifecycle.Engine");

            // 1) the user saves a new assigned profile (the GUI Apply's persistence step)
            new LoggingProfileRepository(confRoot.toString())
                    .saveProps(ModuleIds.NODE, "ops", props("node.level", "FINEST"));
            new LoggingAssignmentStore(confRoot.toString())
                    .setAssignment(name, Map.of(ModuleIds.NODE, "ops"));

            // 2) the GUI Apply action (P2.4 routing: before this was a node RESTART)
            NodeLoggingApplier.applyForNodeProfile(confRoot.toString(), name, ModuleIds.NODE);

            // 3) the SAME context now carries the new configuration — no restart
            NodeLoggingContext ctx = NodeLoggingContextManager.shared().get(name);
            assertSame(minimal, ctx, "the Apply must reconfigure the SAME context instance");
            assertTrue(ctx.isStarted());
            assertEquals("TRACE", rootLevel(ctx), "L2 node.level=FINEST → TRACE, applied live");
            assertTrue(engine.isTraceEnabled(),
                    "the pre-existing logger re-bound to the new config (P0 Q2)");
            Path nodeFile = confRoot.resolve("node").resolve("logging").resolve("runtime")
                    .resolve(name + ".log4j2.properties");
            assertTrue(Files.exists(nodeFile), "the generated node file was (re)written in place");

            // 4) the GUI-bridge appender survived the reconfigure — single delivery
            ProfileLogger bus = NodeLoggerRegistry.get(ModuleIds.NODE, name);
            assertNotNull(bus);
            Recorder recorder = new Recorder();
            bus.addSubscriber(recorder);
            engine.trace("after apply");
            assertEquals(1, recorder.events.size(),
                    "exactly ONE bus event — the appender was re-attached, not duplicated");
        } finally {
            NodeModule.getInstance().removeNode(name); // disposes the Signum (context + bus)
        }
    }

    @Test
    @DisplayName("repeated Apply is idempotent: same context, no duplicate GUI-bridge appender on the root")
    void guiApply_isIdempotent() throws Exception {
        String name = "p24-idempotent-" + System.nanoTime();
        Signum signum = new Signum(new NodeProfile(name), confRoot);
        NodeModule.getInstance().addNode(signum);
        try {
            NodeLoggingApplier.applyForNodeProfile(confRoot.toString(), name, ModuleIds.NODE);
            NodeLoggingContext first = NodeLoggingContextManager.shared().get(name);
            NodeLoggingApplier.applyForNodeProfile(confRoot.toString(), name, ModuleIds.NODE);
            NodeLoggingContext second = NodeLoggingContextManager.shared().get(name);
            assertSame(first, second);
            Configuration cfg = second.loggerContext().getConfiguration();
            long bridgeCount = cfg.getRootLogger().getAppenders().keySet().stream()
                    .filter(n -> ProfileConsoleAppender.NAME.equals(n)).count();
            assertEquals(1L, bridgeCount,
                    "repeated Apply must not accumulate GUI-bridge appenders on the root");
        } finally {
            NodeModule.getInstance().removeNode(name);
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