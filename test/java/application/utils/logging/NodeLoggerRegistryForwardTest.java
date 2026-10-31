package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P8/F1: {@link NodeLoggerRegistry#getOrCreate} must create a ProfileLogger with
 * forward-to-system ENABLED — the legacy {@code SystemLoggerJulHandler} is gone (P3.5),
 * so the forward is the node's ONLY path to the System Console. A node-engine event
 * relayed through the bridge ({@link ModuleLogger#forward(LogEvent)}) must reach the
 * SystemLogger exactly once, carrying the (module, profile) scope (System Console tag +
 * color) — and the node console sees the same event once (no duplication).
 */
@DisplayName("P8/F1 - NodeLoggerRegistry forward-to-system")
class NodeLoggerRegistryForwardTest {

    private static final String MODULE = "node";
    private static final String PROFILE = "p8-forward-" + System.nanoTime();

    @BeforeEach
    void setUp() {
        SystemLogger.resetInstance();
    }

    @AfterEach
    void tearDown() {
        NodeLoggerRegistry.unregister(MODULE, PROFILE);
        SystemLogger.resetInstance();
    }

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

    @Test
    @DisplayName("getOrCreate enables forwardToSystem (the SystemLoggerJulHandler is gone)")
    void getOrCreateEnablesForward() {
        ProfileLogger bus = NodeLoggerRegistry.getOrCreate(MODULE, PROFILE);
        assertTrue(bus.isForwardToSystem(),
                "the node ProfileLogger must forward to SystemLogger (P8/F1)");
    }

    @Test
    @DisplayName("a node-engine event relayed via forward reaches the SystemLogger once, with scope")
    void nodeEventReachesSystemLogger() {
        ProfileLogger bus = NodeLoggerRegistry.getOrCreate(MODULE, PROFILE);
        assertTrue(bus.isForwardToSystem(), "precondition: forward enabled");
        Recorder nodeRecorder = new Recorder();
        bus.addSubscriber(nodeRecorder);
        Recorder systemRecorder = new Recorder();
        SystemLogger.getInstance().addSubscriber(systemRecorder);
        try {
            // Simulate the bridge: a structured event relayed through forward() (as the
            // node's isolated Log4j2 context does via ConsoleBusAppender).
            bus.forward(new LogEvent.Builder()
                    .timestamp(System.currentTimeMillis())
                    .level(LogLevel.INFO)
                    .loggerName("com.example.NodeEngine")
                    .message("processed block 5000")
                    .threadName("ImportBlocksThread")
                    .build());

            assertEquals(1, nodeRecorder.events.size(), "node console sees it once");
            assertEquals(1, systemRecorder.events.size(), "system console sees it once (no duplicate)");
            LogEvent sys = systemRecorder.events.get(0);
            assertEquals(MODULE, sys.getModule(), "system event carries the module scope");
            assertEquals(PROFILE, sys.getProfileName(), "system event carries the profile scope");
            assertEquals("com.example.NodeEngine", sys.getLoggerName(), "original logger name preserved");
            assertEquals("processed block 5000", sys.getMessage(), "raw message preserved");
        } finally {
            bus.removeSubscriber(nodeRecorder);
            SystemLogger.getInstance().removeSubscriber(systemRecorder);
        }
    }
}