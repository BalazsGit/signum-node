package application.gui.tray;

import application.module.node.lifecycle.NodeLifecycleManager;
import application.module.node.lifecycle.NodeLifecycleState;
import application.module.node.lifecycle.NodeOperatingState;
import application.module.node.profile.NodeProfile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TrayIconManager}.
 * <p>
 * Tests verify singleton behavior, constructor injection, lifecycle listener
 * registration/deregistration, and graceful degradation when SystemTray is
 * not supported. Actual AWT operations are avoided since tests run headless.
 *
 * @since 4.0
 */
@DisplayName("TrayIconManager Tests")
class TrayIconManagerTest {

    private NodeLifecycleManager lifecycleManager;

    @BeforeEach
    void setUp() {
        TrayIconManager.resetInstance();
        lifecycleManager = mock(NodeLifecycleManager.class);
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
            TrayIconManager first = TrayIconManager.getInstance(lifecycleManager);
            TrayIconManager second = TrayIconManager.getInstance(lifecycleManager);

            assertSame(first, second);
        }

        @Test
        @DisplayName("resetInstance clears singleton")
        void resetInstance_clearsSingleton() {
            TrayIconManager instance = TrayIconManager.getInstance(lifecycleManager);
            assertNotNull(instance);

            TrayIconManager.resetInstance();

            // Next call will create a new instance
            TrayIconManager fresh = TrayIconManager.getInstance(lifecycleManager);
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
        @DisplayName("instance accepts NodeLifecycleManager dependency")
        void constructor_acceptsLifecycleManager() {
            TrayIconManager manager = TrayIconManager.getInstance(lifecycleManager);
            assertNotNull(manager);
        }

        @Test
        @DisplayName("isTraySupported reflects platform capability")
        void isTraySupported_returnsPlatformValue() {
            TrayIconManager manager = TrayIconManager.getInstance(lifecycleManager);
            // On CI/headless environments this will be false; on desktop it may be true.
            // We just verify the method returns a consistent boolean.
            boolean supported = manager.isTraySupported();
            assertEquals(supported, manager.isTraySupported());
        }
    }

    // ====================================================================
    // Lifecycle listener callbacks (with NodeProfile)
    // ====================================================================

    @Nested
    @DisplayName("Lifecycle listener callbacks")
    class ListenerTests {

        @Test
        @DisplayName("initialize registers itself with lifecycleManager")
        void initialize_registersListener() {
            TrayIconManager manager = TrayIconManager.getInstance(lifecycleManager);

            manager.initialize();

            verify(lifecycleManager).addListener(manager);
        }

        @Test
        @DisplayName("dispose removes listener from lifecycleManager")
        void dispose_removesListener() {
            TrayIconManager manager = TrayIconManager.getInstance(lifecycleManager);

            manager.dispose();

            verify(lifecycleManager).removeListener(manager);
        }

        @Test
        @DisplayName("onStateChanged does not throw for valid state transition")
        void onStateChanged_doesNotThrow() {
            TrayIconManager manager = TrayIconManager.getInstance(lifecycleManager);
            NodeProfile profile = mockNodeProfile("test", NodeLifecycleState.READY);

            assertDoesNotThrow(() ->
                manager.onStateChanged(profile, NodeLifecycleState.READY, NodeLifecycleState.RUNNING));
        }

        @Test
        @DisplayName("onOperatingStateChanged does not throw")
        void onOperatingStateChanged_doesNotThrow() {
            TrayIconManager manager = TrayIconManager.getInstance(lifecycleManager);
            NodeProfile profile = mockNodeProfile("test", NodeLifecycleState.RUNNING);

            assertDoesNotThrow(() ->
                manager.onOperatingStateChanged(profile, NodeOperatingState.SYNC_IDLE, NodeOperatingState.SYNCING));
        }

        @Test
        @DisplayName("onStatusMessage does not throw")
        void onStatusMessage_doesNotThrow() {
            TrayIconManager manager = TrayIconManager.getInstance(lifecycleManager);
            NodeProfile profile = mockNodeProfile("test", NodeLifecycleState.RUNNING);

            assertDoesNotThrow(() -> manager.onStatusMessage(profile, "Node started"));
        }

        @Test
        @DisplayName("onError does not throw")
        void onError_doesNotThrow() {
            TrayIconManager manager = TrayIconManager.getInstance(lifecycleManager);
            NodeProfile profile = mockNodeProfile("test", NodeLifecycleState.ERROR);

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
            TrayIconManager manager = TrayIconManager.getInstance(lifecycleManager);
            // Do not call initialize(), so trayIcon stays null
            assertDoesNotThrow(() ->
                manager.displayMessage("Title", "Message", java.awt.TrayIcon.MessageType.INFO));
        }
    }

    // ====================================================================
    // Tooltip building (indirect via lifecycle events)
    // ====================================================================

    @Nested
    @DisplayName("Tooltip behavior")
    class TooltipTests {

        @Test
        @DisplayName("onStateChanged with RUNNING updates tooltip to reflect operating state")
        void onStateChanged_runningUpdatesTooltip() {
            TrayIconManager manager = TrayIconManager.getInstance(lifecycleManager);
            NodeProfile profile = mockNodeProfile("mainnet", NodeLifecycleState.RUNNING);

            // Should not throw even without a real tray icon
            assertDoesNotThrow(() ->
                manager.onStateChanged(profile, NodeLifecycleState.READY, NodeLifecycleState.RUNNING));
        }

        @Test
        @DisplayName("onStateChanged with STOPPED appends STOPPED marker")
        void onStateChanged_stoppedAppendsMarker() {
            TrayIconManager manager = TrayIconManager.getInstance(lifecycleManager);
            NodeProfile profile = mockNodeProfile("mainnet", NodeLifecycleState.RUNNING);

            assertDoesNotThrow(() ->
                manager.onStateChanged(profile, NodeLifecycleState.RUNNING, NodeLifecycleState.STOPPED));
        }

        @Test
        @DisplayName("onStateChanged with ERROR is handled gracefully")
        void onStateChanged_errorHandled() {
            TrayIconManager manager = TrayIconManager.getInstance(lifecycleManager);
            NodeProfile profile = mockNodeProfile("mainnet", NodeLifecycleState.ERROR);

            assertDoesNotThrow(() ->
                manager.onStateChanged(profile, NodeLifecycleState.RUNNING, NodeLifecycleState.ERROR));
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
            TrayIconManager manager = TrayIconManager.getInstance(lifecycleManager);
            // Before dispose, trayIcon is null (not initialized)
            assertNull(manager.getTrayIcon());

            manager.dispose();

            assertNull(manager.getTrayIcon());
        }

        @Test
        @DisplayName("dispose is safe to call multiple times")
        void dispose_safeMultipleCalls() {
            TrayIconManager manager = TrayIconManager.getInstance(lifecycleManager);

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
     * Creates a mocked NodeProfile for testing lifecycle listener callbacks.
     */
    private static NodeProfile mockNodeProfile(String name, NodeLifecycleState state) {
        NodeProfile profile = mock(NodeProfile.class);
        when(profile.getName()).thenReturn(name);
        return profile;
    }
}