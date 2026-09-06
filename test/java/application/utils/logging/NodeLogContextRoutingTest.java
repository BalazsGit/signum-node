package application.utils.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogSubscriber;

/**
 * Verifies the SLF4J/JUL → per-profile routing contract that the Node Console
 * depends on (see {@code implementation_plan/gui_signum_binding_and_init_order_v2.md}, D4).
 * <p>
 * The {@link SystemLoggerJulHandler} forwards <b>every</b> event to
 * {@link SystemLogger}, but forwards it to the per-profile {@link ProfileLogger}
 * <b>only when</b> {@link NodeLogContext} carries the profile name on the logging
 * thread. That precondition is exactly what {@code Signum#start()/stop()} now
 * establish via {@code withProfileLogContext(...)}. These tests pin that behaviour
 * down so the "empty Node Console" regression cannot silently return.
 * </p>
 */
@DisplayName("NodeLogContext -> ProfileLogger routing Tests")
class NodeLogContextRoutingTest {

    private static final String PROFILE = "routingtest";

    private ProfileLogger profileLogger;
    private final AtomicInteger profileEvents = new AtomicInteger(0);
    private final AtomicInteger systemEvents = new AtomicInteger(0);

    private final LogSubscriber profileSub = new LogSubscriber() {
        @Override
        public void onLogEvent(LogEvent event) {
            profileEvents.incrementAndGet();
        }

        @Override
        public LogFilter getFilter() {
            return null;
        }
    };

    private final LogSubscriber systemSub = new LogSubscriber() {
        @Override
        public void onLogEvent(LogEvent event) {
            systemEvents.incrementAndGet();
        }

        @Override
        public LogFilter getFilter() {
            return null;
        }
    };

    @BeforeEach
    void setUp() {
        profileLogger = new ProfileLogger("node", PROFILE);
        // Mirror the real Signum constructor: the SystemLoggerJulHandler already forwards
        // every event to SystemLogger, so the ProfileLogger must not forward again (no dups).
        profileLogger.setForwardToSystem(false);
        profileLogger.addSubscriber(profileSub);
        SystemLogger.getInstance().addSubscriber(systemSub);
        NodeLoggerRegistry.register("node", PROFILE, profileLogger);
    }

    @AfterEach
    void tearDown() {
        NodeLogContext.clear();
        NodeLoggerRegistry.unregister("node", PROFILE);
        if (profileLogger != null) {
            profileLogger.close();
        }
        SystemLogger.resetInstance();
    }

    private LogRecord sampleRecord() {
        LogRecord record = new LogRecord(Level.INFO, "hello profile console");
        record.setLoggerName("application.module.node.Signum");
        return record;
    }

    @Test
    @DisplayName("without NodeLogContext: node-module loggers are still routed via fallback (console must not be empty)")
    void noContext_nodeModuleLogger_routesToProfileViaFallback() {
        NodeLogContext.clear();

        SystemLoggerJulHandler.getInstance().publish(sampleRecord());

        assertEquals(1, systemEvents.get(), "SystemLogger must always receive the event");
        assertEquals(1, profileEvents.get(),
                "Node-module logs (application.module.node.*) must reach the profile console "
                        + "even when the emitting thread (e.g. EDT) has no NodeLogContext");
    }

    @Test
    @DisplayName("without NodeLogContext: non-node loggers still do NOT reach the node profile console")
    void noContext_foreignLogger_notRoutedToProfile() {
        NodeLogContext.clear();

        LogRecord foreign = new LogRecord(Level.INFO, "not node module");
        foreign.setLoggerName("application.utils.logging.SomeOther");
        SystemLoggerJulHandler.getInstance().publish(foreign);

        assertEquals(1, systemEvents.get(), "SystemLogger must always receive the event");
        assertEquals(0, profileEvents.get(),
                "Foreign (non application.module.node) loggers must not leak into the node console");
    }

    @Test
    @DisplayName("with NodeLogContext: ProfileLogger receives the event (the mechanism Signum.start() now uses)")
    void withContext_routesToProfileLogger() {
        NodeLogContext.set("node", PROFILE);
        try {
            SystemLoggerJulHandler.getInstance().publish(sampleRecord());
        } finally {
            NodeLogContext.clear();
        }

        assertEquals(1, systemEvents.get(), "SystemLogger must still receive the event");
        assertEquals(1, profileEvents.get(),
                "With NodeLogContext the per-profile console must receive the event");
    }

    @Test
    @DisplayName("NodeLogContext.runIn (mirrors Signum.withProfileLogContext): event routed and context restored")
    void runIn_bindsAndRestoresContext() {
        NodeLogContext.clear();

        NodeLogContext.runIn("node", PROFILE, () -> SystemLoggerJulHandler.getInstance().publish(sampleRecord()));

        assertEquals(1, systemEvents.get());
        assertEquals(1, profileEvents.get());
        assertNull(NodeLogContext.current(), "runIn must clear the bound context afterwards");
    }
}