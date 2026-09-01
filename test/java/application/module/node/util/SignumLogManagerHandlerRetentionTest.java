package application.module.node.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import application.utils.logging.NodeLogContext;
import application.utils.logging.NodeLoggerRegistry;
import application.utils.logging.ProfileLogger;
import application.utils.logging.SystemLogger;
import application.utils.logging.SystemLoggerJulHandler;
import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogSubscriber;

/**
 * Regression tests for the "incomplete Node Console" bug.
 * <p>
 * {@code Signum.doInitialize()} Step 0 re-applies the logging configuration via
 * {@code LoggerConfigurator} → {@link java.util.logging.LogManager#readConfiguration}.
 * {@code readConfiguration} resets the ROOT logger's handlers, removing every handler
 * that was attached programmatically — including the {@link SystemLoggerJulHandler}
 * that bridges SLF4J/JUL events into the {@link SystemLogger} and the per-node
 * {@link ProfileLogger} (the Node Console). Without the fix, every startup line logged
 * after Step 0 (DB init, blockchain, peers, web server, statistics, ...) still reaches
 * the terminal but never the Node Console, which is exactly the "logs are not complete
 * even after the first start" symptom.
 * </p>
 * <p>
 * These tests pin the contract that the GUI bridge handler must survive a
 * {@code readConfiguration} and that per-profile routing keeps working afterwards.
 * </p>
 */
@DisplayName("SignumLogManager handler retention Tests")
class SignumLogManagerHandlerRetentionTest {

    private static final String PROFILE = "handlerretention";

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
        // Mirror the real Signum constructor: the SystemLoggerJulHandler already
        // forwards every event to SystemLogger, so the ProfileLogger must not
        // forward again (no duplicates).
        profileLogger.setForwardToSystem(false);
        profileLogger.addSubscriber(profileSub);
        SystemLogger.getInstance().addSubscriber(systemSub);
        NodeLoggerRegistry.register("node", PROFILE, profileLogger);
        // The GUI bridge handler must be installed on the root logger (as the Launcher does).
        SystemLoggerJulHandler.install();
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

    private static boolean rootHasBridgeHandler() {
        return Arrays.stream(Logger.getLogger("").getHandlers())
                .anyMatch(h -> h instanceof SystemLoggerJulHandler);
    }

    /**
     * A minimal, valid JUL configuration stream — the same shape Signum ships
     * ({@code conf/logging-default.properties}: root handlers + a root level).
     */
    private static ByteArrayInputStream sampleConfiguration() {
        String props = "handlers = java.util.logging.ConsoleHandler\n.level = ALL\n";
        return new ByteArrayInputStream(props.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("readConfiguration keeps the GUI bridge handler attached to the root logger")
    void readConfiguration_retainsBridgeHandler() throws Exception {
        // Simulate the handler reset that LogManager.readConfiguration performs on the
        // root: guarantee the bridge handler is ABSENT before we reconfigure.
        for (Handler h : Logger.getLogger("").getHandlers()) {
            if (h instanceof SystemLoggerJulHandler) {
                Logger.getLogger("").removeHandler(h);
            }
        }
        assertFalse(rootHasBridgeHandler(), "precondition: bridge handler removed");

        new SignumLogManager().readConfiguration(sampleConfiguration());

        assertTrue(rootHasBridgeHandler(),
                "SignumLogManager.readConfiguration must re-install the GUI bridge handler");
    }

    @Test
    @DisplayName("after reconfiguration, events with NodeLogContext still reach the ProfileLogger")
    void readConfiguration_keepsProfileRouting() throws Exception {
        NodeLogContext.set("node", PROFILE);
        try {
            // Reconfigure logging the same way Signum.doInitialize() Step 0 does.
            new SignumLogManager().readConfiguration(sampleConfiguration());

            // Emit an SLF4J/JUL log the way the node components do during startup.
            Logger.getLogger("application.module.node.BlockchainProcessorImpl").info("after reconfig");

            assertTrue(systemEvents.get() >= 1, "SystemLogger must still receive the event");
            assertTrue(profileEvents.get() >= 1,
                    "Per-profile console must still receive the event after reconfiguration");
        } finally {
            NodeLogContext.clear();
        }
    }
}
