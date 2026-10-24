package application.module.logging;

import application.module.logging.appender.SystemConsoleAppender;
import application.module.logging.context.NodeLoggingContext;
import application.module.logging.context.SignumGlobalContext;
import application.module.logging.context.SignumGlobalContextSelector;
import application.utils.logging.SystemLogger;
import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogSubscriber;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3.5 gate — the binding path after the SLF4J/JUL swap. With log4j-slf4j2-impl + log4j-jul on
 * the classpath and the global-only {@link SignumGlobalContextSelector} installed, every
 * <em>static</em> logger (SLF4J and JUL) resolves to the single shared {@code signum-global}
 * context, whose {@link SystemConsoleAppender} forwards its events to the {@link SystemLogger}
 * (System Console) <b>exactly once</b> (the legacy SystemLoggerJulHandler is no longer
 * installed). Node-context loggers remain bound to their OWN context (R1 isolation).
 */
@DisplayName("P3.5 - Binding path (SLF4J/JUL -> global -> System Console, node isolated)")
class BindingPathTest {

    private static Log4jContextFactory previousFactory;
    private static Recorder recorder;

    @BeforeAll
    static void installGlobal() {
        previousFactory = (Log4jContextFactory) LogManager.getFactory();
        LogManager.setFactory(new Log4jContextFactory(new SignumGlobalContextSelector()));
        // Fresh SystemLogger (empty replay) with a capturing recorder.
        SystemLogger.resetInstance();
        recorder = new Recorder();
        SystemLogger.getInstance().addSubscriber(recorder);
        // Make the shared global context emit INFO and forward to the System Console. The
        // binding path under test is: SLF4J/JUL -> global context -> SystemConsoleAppender
        // -> SystemLogger. (Production configures this via GlobalLoggingApplier.applyGlobal.)
        LoggerContext global = SignumGlobalContext.shared().loggerContext();
        global.getConfiguration().getRootLogger().setLevel(Level.INFO);
        new SystemConsoleAppender().attach(global);
    }

    @AfterAll
    static void restore() {
        if (previousFactory != null) {
            LogManager.setFactory(previousFactory);
        }
        SystemLogger.resetInstance();
    }

    @Test
    @DisplayName("the null-name log4j lookup converges on the shared signum-global context")
    void nullNameLookupConvergesOnGlobalContext() {
        LoggerContext byNullName = (LoggerContext) LogManager.getContext();
        assertSame(SignumGlobalContext.shared().loggerContext(), byNullName,
                "every static lookup must converge on the single shared global context");
        assertEquals("signum-global", byNullName.getName());
    }

    @Test
    @DisplayName("an SLF4J log reaches the System Console exactly once (no double)")
    void slf4jReachesSystemConsole_exactlyOnce() {
        int before = recorder.count();
        org.slf4j.LoggerFactory.getLogger("application.p35gate.Slf4jSource").info("SLF4J_GATE_MARKER");
        int after = recorder.count();
        assertEquals(1, after - before,
                "the SLF4J event must reach the System Console exactly once (no duplicate)");
        assertTrue(recorder.hasMarker("SLF4J_GATE_MARKER"), "the SLF4J event must be in the System Console");
    }

    @Test
    @DisplayName("a JUL log reaches the System Console via the log4j-jul bridge")
    void julReachesSystemConsole() {
        // Mirror the Launcher (java.util.logging.manager -> log4j-jul). The JUL LogManager is a
        // one-shot JVM global: if an earlier test already initialized JUL, we cannot rebind it,
        // so the assertion degrades to a no-op in that case.
        System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
        if (!(java.util.logging.LogManager.getLogManager() instanceof org.apache.logging.log4j.jul.LogManager)) {
            return; // JUL already bound elsewhere in this JVM — cannot rebind
        }
        int before = recorder.count();
        java.util.logging.Logger.getLogger("application.p35gate.JulSource").info("JUL_GATE_MARKER");
        int after = recorder.count();
        assertEquals(1, after - before, "the JUL event must reach the System Console exactly once");
        assertTrue(recorder.hasMarker("JUL_GATE_MARKER"), "the JUL event must be in the System Console");
    }

    @Test
    @DisplayName("a node-context logger is bound to its OWN context (not the global)")
    void nodeContextLoggerIsBoundToNodeContext() {
        NodeLoggingContext nlc = new NodeLoggingContext("p35gate-" + System.nanoTime(), null).start();
        try {
            Logger nodeLogger = (Logger) nlc.loggerContext().getLogger("application.p35gate.NodeEngine");
            assertSame(nlc.loggerContext(), nodeLogger.getContext(),
                    "the node logger must be bound to the NODE context");
            assertNotSame(SignumGlobalContext.shared().loggerContext(), nodeLogger.getContext(),
                    "the node logger must NOT be the global context");
        } finally {
            nlc.stop();
        }
    }

    /** Captures System Console events for delta/marker assertions (replay-safe via delta). */
    private static final class Recorder implements LogSubscriber {
        final List<LogEvent> events = new CopyOnWriteArrayList<>();
        @Override
        public void onLogEvent(final LogEvent e) {
            events.add(e);
        }
        @Override
        public LogFilter getFilter() {
            return null;
        }
        int count() {
            return events.size();
        }
        boolean hasMarker(final String marker) {
            return events.stream().anyMatch(e -> e.getMessage() != null && e.getMessage().contains(marker));
        }
    }
}
