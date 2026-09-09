package application.utils.logging;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the per-node level filter on the <b>JUL bridge path</b>: a {@link ProfileLogger} with a
 * raised minimum level must NOT deliver events routed to it by {@link SystemLoggerJulHandler}
 * when they are below that level (the "bridge bypass" fix, G1).
 */
@DisplayName("ProfileLogger bridge level filter")
class ProfileLoggerBridgeLevelTest {

    private static final String PROFILE = "leveltest";

    @AfterEach
    void tearDown() {
        NodeLogContext.clear();
        NodeLoggerRegistry.unregister("node", PROFILE);
        SystemLogger.resetInstance();
    }

    @Test
    @DisplayName("bridge-routed events below the per-node min level are filtered; at/above are delivered")
    void bridgePath_respectsPerNodeMinLevel() {
        ProfileLogger logger = NodeLoggerRegistry.getOrCreate("node", PROFILE);
        logger.setForwardToSystem(false);
        logger.setLogLevel(LogLevel.WARN);
        AtomicInteger delivered = new AtomicInteger();
        logger.addSubscriber(new LogSubscriber() {
            @Override
            public void onLogEvent(LogEvent event) {
                delivered.incrementAndGet();
            }

            @Override
            public LogFilter getFilter() {
                return null;
            }
        });

        publish(Level.INFO);
        assertEquals(0, delivered.get(), "INFO (below WARN) must be filtered on the bridge path");

        publish(Level.SEVERE);
        assertEquals(1, delivered.get(), "SEVERE (at/above WARN) must be delivered");
    }

    private void publish(Level julLevel) {
        LogRecord record = new LogRecord(julLevel, "hello");
        record.setLoggerName("application.module.node.Signum");
        NodeLogContext.runIn("node", PROFILE, () -> SystemLoggerJulHandler.getInstance().publish(record));
    }
}