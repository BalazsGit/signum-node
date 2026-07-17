package application.module.node.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import application.utils.logging.ProfileLogger;
import application.utils.logging.SystemLogger;
import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;
import java.util.function.Consumer;

/**
 * Unit tests for {@link NodeProfileAdapter}.
 */
@DisplayName("NodeProfileAdapter Tests")
class NodeProfileAdapterTest {

    @AfterEach
    void tearDown() {
        SystemLogger.resetInstance();
    }

    @Test
    void constructor_setsDelegate() {
        ProfileLogger delegate = new ProfileLogger("node", "test");
        NodeProfileAdapter adapter = new NodeProfileAdapter(delegate);
        assertSame(delegate, adapter.getDelegate());
    }

    @Test
    void getName_returnsDelegateName() {
        ProfileLogger delegate = new ProfileLogger("node", "mainnet");
        NodeProfileAdapter adapter = new NodeProfileAdapter(delegate);
        assertEquals("node.mainnet", adapter.getName());
    }

    @Test
    void info_ForwardsToProfileLogger() {
        ProfileLogger delegate = new ProfileLogger("node", "test");
        AtomicReference<LogEvent> received = new AtomicReference<>();
        delegate.addSubscriber(new TestSubscriber(e -> received.set(e)));

        NodeProfileAdapter adapter = new NodeProfileAdapter(delegate);
        adapter.info("hello world");

        assertNotNull(received.get());
        assertEquals(LogLevel.INFO, received.get().getLevel());
        assertTrue(received.get().getMessage().contains("hello world"));
    }

    @Test
    void error_WithThrowable_ForwardsToProfileLogger() {
        ProfileLogger delegate = new ProfileLogger("node", "test");
        AtomicReference<LogEvent> received = new AtomicReference<>();
        delegate.addSubscriber(new TestSubscriber(e -> received.set(e)));

        NodeProfileAdapter adapter = new NodeProfileAdapter(delegate);
        RuntimeException cause = new RuntimeException("boom");
        adapter.error("failed", cause);

        assertNotNull(received.get());
        assertEquals(LogLevel.ERROR, received.get().getLevel());
    }

    @Test
    void debug_BelowMinLevel_notDispatched() {
        ProfileLogger delegate = new ProfileLogger("node", "test");
        // Default is INFO, so DEBUG should be filtered
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);
        delegate.addSubscriber(new TestSubscriber(e -> count.incrementAndGet()));

        NodeProfileAdapter adapter = new NodeProfileAdapter(delegate);
        adapter.debug("hidden");

        assertEquals(0, count.get());
    }

    @Test
    void forwardedToSystemLogger() {
        AtomicReference<LogEvent> systemReceived = new AtomicReference<>();
        SystemLogger.getInstance().addSubscriber(new TestSubscriber(e -> systemReceived.set(e)));

        ProfileLogger delegate = new ProfileLogger("node", "test");
        NodeProfileAdapter adapter = new NodeProfileAdapter(delegate);
        adapter.info("to system");

        assertNotNull(systemReceived.get());
        assertTrue(systemReceived.get().getMessage().contains("to system"));
    }

    // ── Test Helper ─────────────────────────────────────────────────────

    private static class TestSubscriber implements LogSubscriber {
        private final Consumer<LogEvent> onEvent;
        TestSubscriber(Consumer<LogEvent> onEvent) { this.onEvent = onEvent; }
        @Override public void onLogEvent(LogEvent event) { if (onEvent != null) onEvent.accept(event); }
        @Override public application.utils.logging.event.LogFilter getFilter() { return null; }
    }
}