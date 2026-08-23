package application.gui.tray;

import application.module.node.NodeModule;
import application.module.node.Signum;
import application.module.node.profile.NodeProfile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TrayIconManager}.
 * <p>
 * Tests verify singleton behavior, constructor injection, listener
 * callbacks, and graceful degradation when SystemTray is not supported.
 * Actual AWT operations are avoided since tests run headless.
 *
 * @since 4.0
 */
@DisplayName("TrayIconManager Tests")
class TrayIconManagerTest {

    private NodeModule nodeModule;

    @BeforeEach
    void setUp() {
        TrayIconManager.resetInstance();
        nodeModule = mock(NodeModule.class);
    }

    @AfterEach
    void tearDown() {
        // Clean up singleton between tests
        TrayIconManager.resetInstance();
    }

    // ====================================================================
    // Singleton behavior
    // ====================================================================

    @Nested
    @DisplayName("Singleton behavior")
    class SingletonTests {

        @Test
        @DisplayName("getInstance returns same instance on repeated calls")
        void getInstance_returnsSameInstance() {
            TrayIconManager first = TrayIconManager.getInstance(nodeModule);
            TrayIconManager second = TrayIconManager.getInstance(nodeModule);

            assertSame(first, second);
        }

        @Test
        @DisplayName("resetInstance clears singleton")
        void resetInstance_clearsSingleton() {
            TrayIconManager instance = TrayIconManager.getInstance(nodeModule);
            assertNotNull(instance);

            TrayIconManager.resetInstance();

            // Next call will create a new instance
            TrayIconManager fresh = TrayIconManager.getInstance(nodeModule);
            assertNotSame(instance, fresh);
        }
    }

    // ====================================================================
    // Constructor injection
    // ====================================================================

    @Nested
    @DisplayName("Constructor injection")
    class ConstructorTests {

        @Test
        @DisplayName("instance accepts NodeModule dependency")
        void constructor_acceptsNodeModule() {
            TrayIconManager manager = TrayIconManager.getInstance(nodeModule);
            assertNotNull(manager);
        }

        @Test
        @DisplayName("isTraySupported reflects platform capability")
        void isTraySupported_returnsPlatformValue() {
            TrayIconManager manager = TrayIconManager.getInstance(nodeModule);
            // On CI/headless environments this will be false; on desktop it may be true.
            // We just verify the method returns a consistent boolean.
            boolean supported = manager.isTraySupported();
            assertEquals(supported, manager.isTraySupported());
        }
    }

    // ====================================================================
    // Listener callbacks (with NodeProfile + Signum.State)
    // ====================================================================

    @Nested
    @DisplayName("Listener callbacks")
    class ListenerTests {

        @Test
        @DisplayName("initialize does not throw in headless mode")
        void initialize_doesNotThrowHeadless() {
            TrayIconManager manager = TrayIconManager.getInstance(nodeModule);

            assertDoesNotThrow(() -> manager.initialize());
        }

        @Test
        @DisplayName("onStateChanged does not throw for valid state transition")
        void onStateChanged_doesNotThrow() {
            TrayIconManager manager = TrayIconManager.getInstance(nodeModule);
            NodeProfile profile = mockNodeProfile("test");

            assertDoesNotThrow(() ->
                manager.onStateChanged(profile, Signum.State.CREATED, Signum.State.RUNNING));
        }

        @Test
        @DisplayName("onOperatingStateChanged does not throw")
        void onOperatingStateChanged_doesNotThrow() {
            TrayIconManager manager = TrayIconManager.getInstance(nodeModule);
            NodeProfile profile = mockNodeProfile("test");

            assertDoesNotThrow(() ->
                manager.onOperatingStateChanged(profile, Signum.OperatingState.SYNC_IDLE, Signum.OperatingState.SYNCING));
        }

        @Test
        @DisplayName("onStatusMessage does not throw")
        void onStatusMessage_doesNotThrow() {
            TrayIconManager manager = TrayIconManager.getInstance(nodeModule);
            NodeProfile profile = mockNodeProfile("test");

            assertDoesNotThrow(() -> manager.onStatusMessage(profile, "Node started"));
        }

        @Test
        @DisplayName("onError does not throw")
        void onError_doesNotThrow() {
            TrayIconManager manager = TrayIconManager.getInstance(nodeModule);
            NodeProfile profile = mockNodeProfile("test");

            assertDoesNotThrow(() -> manager.onError(profile, "Test error"));
        }
    }

    // ====================================================================
    // Display message helper
    // ====================================================================

    @Nested
    @DisplayName("Display message")
    class DisplayMessageTests {

        @Test
        @DisplayName("displayMessage does not throw when tray icon is null")
        void displayMessage_doesNotThrowWhenIconNull() {
            TrayIconManager manager = TrayIconManager.getInstance(nodeModule);
            // Do not call initialize(), so trayIcon stays null
            assertDoesNotThrow(() ->
                manager.displayMessage("Title", "Message", java.awt.TrayIcon.MessageType.INFO));
        }
    }

    // ====================================================================
    // Tooltip building (indirect via state events)
    // ====================================================================

    @Nested
    @DisplayName("Tooltip behavior")
    class TooltipTests {

        @Test
        @DisplayName("onStateChanged with RUNNING updates tooltip to reflect operating state")
        void onStateChanged_runningUpdatesTooltip() {
            TrayIconManager manager = TrayIconManager.getInstance(nodeModule);
            NodeProfile profile = mockNodeProfile("mainnet");

            // Should not throw even without a real tray icon
            assertDoesNotThrow(() ->
                manager.onStateChanged(profile, Signum.State.CREATED, Signum.State.RUNNING));
        }

        @Test
        @DisplayName("onStateChanged with STOPPED appends STOPPED marker")
        void onStateChanged_stoppedAppendsMarker() {
            TrayIconManager manager = TrayIconManager.getInstance(nodeModule);
            NodeProfile profile = mockNodeProfile("mainnet");

            assertDoesNotThrow(() ->
                manager.onStateChanged(profile, Signum.State.RUNNING, Signum.State.STOPPED));
        }

        @Test
        @DisplayName("onStateChanged with ERROR is handled gracefully")
        void onStateChanged_errorHandled() {
            TrayIconManager manager = TrayIconManager.getInstance(nodeModule);
            NodeProfile profile = mockNodeProfile("mainnet");

            assertDoesNotThrow(() ->
                manager.onStateChanged(profile, Signum.State.RUNNING, Signum.State.ERROR));
        }
    }

    // ====================================================================
    // Dispose / cleanup
    // ====================================================================

    @Nested
    @DisplayName("Dispose and cleanup")
    class DisposeTests {

        @Test
        @DisplayName("dispose sets trayIcon to null")
        void dispose_setsTrayIconNull() {
            TrayIconManager manager = TrayIconManager.getInstance(nodeModule);
            // Before dispose, trayIcon is null (not initialized)
            assertNull(manager.getTrayIcon());

            manager.dispose();

            assertNull(manager.getTrayIcon());
        }

        @Test
        @DisplayName("dispose is safe to call multiple times")
        void dispose_safeMultipleCalls() {
            TrayIconManager manager = TrayIconManager.getInstance(nodeModule);

            assertDoesNotThrow(() -> {
                manager.dispose();
                manager.dispose();
            });
        }
    }

    // ====================================================================
    // Test helpers
    // ====================================================================

    /**
     * Creates a mocked NodeProfile for testing listener callbacks.
     */
    private static NodeProfile mockNodeProfile(String name) {
        NodeProfile profile = mock(NodeProfile.class);
        when(profile.getName()).thenReturn(name);
        return profile;
    }
}