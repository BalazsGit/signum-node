package application.kernel;

import application.api.ShutdownPriority;
import application.api.Shutdownable;
import application.api.Shutdownable.ShutdownException;
import application.kernel.ApplicationShutdown.ShutdownResult;
import application.kernel.ApplicationShutdown.ShutdownStepResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;

/**
 * Unit tests for ApplicationShutdown orchestrator.
 * Follows AAA pattern (Arrange-Act-Assert) with JUnit 5.
 */
class ApplicationShutdownTest {

    private ApplicationShutdown shutdown;

    @BeforeEach
    void setUp() {
        ApplicationShutdown.resetInstance();
        shutdown = ApplicationShutdown.getInstance();
    }

    @AfterEach
    void tearDown() {
        ApplicationShutdown.resetInstance();
    }

    // ================================================================
    // Registration tests
    // ================================================================

    @Test
    void register_GivenValidComponent_IncreasesCount() {
        // Arrange
        Shutdownable component = createMockComponent("test");
        assertEquals(0, shutdown.getRegisteredCount());

        // Act
        shutdown.register(component);

        // Assert
        assertEquals(1, shutdown.getRegisteredCount());
    }

    @Test
    void register_GivenNullComponent_ThrowsIllegalArgumentException() {
        // Arrange
        assertEquals(0, shutdown.getRegisteredCount());

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> shutdown.register(null));
        assertEquals(0, shutdown.getRegisteredCount());
    }

    @Test
    void unregister_GivenRegisteredComponent_RemovesIt() {
        // Arrange
        Shutdownable component = createMockComponent("test");
        shutdown.register(component);
        assertEquals(1, shutdown.getRegisteredCount());

        // Act
        boolean removed = shutdown.unregister(component);

        // Assert
        assertTrue(removed);
        assertEquals(0, shutdown.getRegisteredCount());
    }

    @Test
    void unregister_GivenUnregisteredComponent_ReturnsFalse() {
        // Arrange
        Shutdownable component = createMockComponent("test");

        // Act
        boolean removed = shutdown.unregister(component);

        // Assert
        assertFalse(removed);
    }

    // ================================================================
    // State query tests
    // ================================================================

    @Test
    void isShutdownInitiated_GivenNoShutdownCalled_ReturnsFalse() {
        // Arrange (nothing yet)

        // Act & Assert
        assertFalse(shutdown.isShutdownInitiated());
        assertNull(shutdown.getLastResult());
    }

    @Test
    void isShutdownInitiated_GivenShutdownExecuted_ReturnsTrue() {
        // Arrange
        shutdown.register(createMockComponent("test"));

        // Act
        shutdown.executeShutdownSequence();

        // Assert
        assertTrue(shutdown.isShutdownInitiated());
        assertNotNull(shutdown.getLastResult());
    }

    // ================================================================
    // Shutdown sequence tests
    // ================================================================

    @Test
    void executeShutdownSequence_GivenSingleComponent_CallsShutdown() {
        // Arrange
        TestableComponent component = new TestableComponent("test");
        shutdown.register(component);

        // Act
        ShutdownResult result = shutdown.executeShutdownSequence();

        // Assert
        assertTrue(component.isShutdownCalled());
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(1, result.getSteps().size());
    }

    @Test
    void executeShutdownSequence_GivenNoComponents_ReturnsEmptySuccess() {
        // Arrange (no components registered)

        // Act
        ShutdownResult result = shutdown.executeShutdownSequence();

        // Assert
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertTrue(result.getSteps().isEmpty());
    }

    @Test
    void executeShutdownSequence_GivenFailingComponent_TracksFailure() {
        // Arrange
        FailingComponent component = new FailingComponent("failing");
        shutdown.register(component);

        // Act
        ShutdownResult result = shutdown.executeShutdownSequence();

        // Assert
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals(1, result.getSteps().size());
        assertFalse(result.getSteps().get(0).isSuccess());
        assertNotNull(result.getSteps().get(0).getErrorMessage());
    }

    @Test
    void executeShutdownSequence_GivenDuplicateTrigger_IsIdempotent() {
        // Arrange
        TestableComponent component = new TestableComponent("test");
        shutdown.register(component);

        // Act
        ShutdownResult result1 = shutdown.executeShutdownSequence();
        ShutdownResult result2 = shutdown.executeShutdownSequence();

        // Assert
        assertSame(result1, result2);
        assertEquals(1, component.getShutdownCallCount()); // Only called once
    }

    @Test
    void executeShutdownSequence_GivenMultiplePriorities_ShutsDownInOrder() {
        // Arrange - components that record their shutdown order
        List<String> shutdownOrder = new ArrayList<>();

        Shutdownable highPriority = new OrderedComponent("high", ShutdownPriority.HIGHEST, shutdownOrder);
        Shutdownable normalPriority = new OrderedComponent("normal", ShutdownPriority.NORMAL, shutdownOrder);
        Shutdownable lowPriority = new OrderedComponent("low", ShutdownPriority.LOWEST, shutdownOrder);

        // Register in different order to verify priority-based ordering
        shutdown.register(normalPriority);
        shutdown.register(lowPriority);
        shutdown.register(highPriority);

        // Act
        shutdown.executeShutdownSequence();

        // Assert - HIGHEST first, then NORMAL, then LOWEST
        assertEquals("high", shutdownOrder.get(0));
        assertEquals("normal", shutdownOrder.get(1));
        assertEquals("low", shutdownOrder.get(2));
    }

    @Test
    void executeShutdownSequence_CompletionHookIsCalled() {
        // Arrange
        AtomicBoolean hookCalled = new AtomicBoolean(false);
        shutdown.register(createMockComponent("test"));
        shutdown.addOnCompleteHook(() -> hookCalled.set(true));

        // Act
        shutdown.executeShutdownSequence();

        // Assert
        assertTrue(hookCalled.get());
    }

    @Test
    void executeShutdownSequence_GivenMixedSuccessAndFailure_PartialResult() {
        // Arrange
        shutdown.register(new TestableComponent("good"));
        shutdown.register(new FailingComponent("bad"));

        // Act
        ShutdownResult result = shutdown.executeShutdownSequence();

        // Assert
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals(2, result.getSteps().size());
        
        int successCount = 0;
        int failCount = 0;
        for (ShutdownStepResult step : result.getSteps()) {
            if (step.isSuccess()) successCount++;
            else failCount++;
        }
        assertEquals(1, successCount);
        assertEquals(1, failCount);
    }

    @Test
    void shutdownResult_GetDurationMs_ReturnsPositiveValue() {
        // Arrange
        shutdown.register(new SlowComponent("slow", 50));

        // Act
        ShutdownResult result = shutdown.executeShutdownSequence();

        // Assert
        assertTrue(result.getDurationMs() >= 0);
        assertNotNull(result.getSummary());
    }

    // ================================================================
    // Helper classes for testing
    // ================================================================

    /** Simple mock component that tracks if shutdown was called */
    private static class TestableComponent implements Shutdownable {
        private final String name;
        private boolean shutdownCalled = false;
        private int shutdownCallCount = 0;

        TestableComponent(String name) {
            this.name = name;
        }

        @Override
        public void shutdown() {
            shutdownCalled = true;
            shutdownCallCount++;
        }

        @Override
        public String getComponentName() {
            return name;
        }

        public boolean isShutdownCalled() {
            return shutdownCalled;
        }

        public int getShutdownCallCount() {
            return shutdownCallCount;
        }

        @Override
        public ShutdownPriority getShutdownPriority() {
            return ShutdownPriority.NORMAL;
        }
    }

    /** Component that always throws on shutdown */
    private static class FailingComponent implements Shutdownable {
        private final String name;

        FailingComponent(String name) {
            this.name = name;
        }

        @Override
        public void shutdown() throws ShutdownException {
            throw new ShutdownException(name, "Simulated failure");
        }

        @Override
        public String getComponentName() {
            return name;
        }
    }

    /** Component that records its shutdown order */
    private static class OrderedComponent implements Shutdownable {
        private final String name;
        private final ShutdownPriority priority;
        private final List<String> order;

        OrderedComponent(String name, ShutdownPriority priority, List<String> order) {
            this.name = name;
            this.priority = priority;
            this.order = order;
        }

        @Override
        public void shutdown() {
            order.add(name);
        }

        @Override
        public String getComponentName() {
            return name;
        }

        @Override
        public ShutdownPriority getShutdownPriority() {
            return priority;
        }
    }

    /** Component that sleeps during shutdown */
    private static class SlowComponent implements Shutdownable {
        private final String name;
        private final long sleepMs;

        SlowComponent(String name, long sleepMs) {
            this.name = name;
            this.sleepMs = sleepMs;
        }

        @Override
        public void shutdown() {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ignored) {
            }
        }

        @Override
        public String getComponentName() {
            return name;
        }
    }

    /** Creates a simple mock component */
    private Shutdownable createMockComponent(String name) {
        return new TestableComponent(name);
    }
}