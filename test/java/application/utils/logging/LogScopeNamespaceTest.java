package application.utils.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;
import application.utils.logging.event.ProfileFilter;

/**
 * Pins the composite {@code module.profile} namespacing of the logging system:
 * profiles with the same name in different modules must never collide, and bare
 * profile names keep working for backward compatibility.
 */
class LogScopeNamespaceTest {

    @AfterEach
    void tearDown() {
        NodeLogContext.clear();
        NodeLoggerRegistry.unregister("node", "mainnet");
        NodeLoggerRegistry.unregister("database", "mainnet");
        NodeLoggerRegistry.unregister("node", "ns-stamp");
        SystemLogger.resetInstance();
    }

    @Test
    void logScope_valueSemantics() {
        assertEquals(LogScope.of("node", "mainnet"), LogScope.of("node", "mainnet"));
        assertEquals(LogScope.of("node", "mainnet").hashCode(), LogScope.of("node", "mainnet").hashCode());
        assertNotEquals(LogScope.of("node", "mainnet"), LogScope.of("database", "mainnet"));
        assertEquals("node.mainnet", LogScope.of("node", "mainnet").qualifiedName());
    }

    @Test
    void registry_sameProfileNameAcrossModules_isDistinct() {
        ProfileLogger node = NodeLoggerRegistry.getOrCreate("node", "mainnet");
        ProfileLogger database = NodeLoggerRegistry.getOrCreate("database", "mainnet");
        assertNotSame(node, database, "same profile name in different modules must not collide");
        assertSame(node, NodeLoggerRegistry.get("node", "mainnet"));
        assertSame(database, NodeLoggerRegistry.get("database", "mainnet"));
    }

    @Test
    void registry_getOrCreate_isStable() {
        assertSame(NodeLoggerRegistry.getOrCreate("node", "mainnet"),
                NodeLoggerRegistry.getOrCreate("node", "mainnet"));
    }

    @Test
    void logEvent_withModule_qualifiedName() {
        LogEvent e = new LogEvent.Builder().level(LogLevel.INFO).message("m")
                .module("node").profileName("mainnet").build();
        assertEquals("node", e.getModule());
        assertEquals("node.mainnet", e.getQualifiedName());
        assertEquals(LogScope.of("node", "mainnet"), e.getScope());
    }

    @Test
    void logEvent_withoutModule_bareName() {
        LogEvent e = new LogEvent.Builder().level(LogLevel.INFO).message("m").profileName("mainnet").build();
        assertNull(e.getModule());
        assertEquals("mainnet", e.getQualifiedName());
        assertNull(e.getScope());
    }

    @Test
    void profileFilter_qualifiedAndBareMatch() {
        LogEvent e = new LogEvent.Builder().level(LogLevel.INFO).message("m")
                .module("node").profileName("mainnet").build();
        assertTrue(ProfileFilter.including("node.mainnet").matches(e), "qualified name must match");
        assertTrue(ProfileFilter.including("mainnet").matches(e), "bare name must still match");
        assertFalse(ProfileFilter.including("other").matches(e));
        assertFalse(ProfileFilter.excluding("node.mainnet").matches(e), "exclude by qualified name blocks it");
        LogEvent other = new LogEvent.Builder().level(LogLevel.INFO).message("m")
                .module("node").profileName("testnet").build();
        assertTrue(ProfileFilter.excluding("node.mainnet").matches(other), "a different profile is not blocked");
    }

    @Test
    void colorScheme_bareCustomColor_fallbackForQualified() {
        ConsoleColorScheme scheme = new ConsoleColorScheme();
        Color custom = new Color(11, 22, 33);
        scheme.setCustomColor("mainnet", custom);
        assertEquals(custom, scheme.resolveEventColor("node.mainnet", "mainnet"),
                "legacy bare-name custom color must resolve for the qualified scope");
    }

    @Test
    void colorScheme_qualifiedCustomColor_winsOverBare() {
        ConsoleColorScheme scheme = new ConsoleColorScheme();
        scheme.setCustomColor("mainnet", new Color(1, 2, 3));
        Color qualified = new Color(9, 8, 7);
        scheme.setCustomColor("node.mainnet", qualified);
        assertEquals(qualified, scheme.resolveEventColor("node.mainnet", "mainnet"));
    }

    @Test
    void handler_stampsScopeFromContext() {
        final String profile = "ns-stamp";
        ProfileLogger logger = NodeLoggerRegistry.getOrCreate("node", profile);
        logger.setForwardToSystem(false);
        AtomicReference<LogEvent> captured = new AtomicReference<>();
        logger.addSubscriber(new LogSubscriber() {
            @Override public void onLogEvent(LogEvent event) { captured.set(event); }
            @Override public LogFilter getFilter() { return null; }
        });
        LogRecord record = new LogRecord(Level.INFO, "hello");
        record.setLoggerName("application.module.node.Signum");
        NodeLogContext.runIn("node", profile, () -> SystemLoggerJulHandler.getInstance().publish(record));
        LogEvent e = captured.get();
        assertNotNull(e, "the ProfileLogger must receive the routed event");
        assertEquals("node", e.getModule());
        assertEquals(profile, e.getProfileName());
        assertEquals("node." + profile, e.getQualifiedName());
    }
}
