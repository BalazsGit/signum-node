package application.kernel;

import application.api.ShutdownPriority;
import application.api.Shutdownable;
import application.api.Shutdownable.ShutdownException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central orchestrator for application-wide graceful shutdown.
 * 
 * Follows the Chain of Responsibility pattern combined with Observer pattern:
 * - Components register themselves as Shutdownable
 * - On shutdown trigger, components are shut down in priority order
 * - Each component's success/failure is tracked and reported
 * - Completion callbacks notify interested parties (GUI, logging)
 * 
 * Design note for Solution B migration: Currently operates at the Module level.
 * When migrating to multi-instance architecture, this same orchestrator will
 * manage individual NodeInstance objects instead. The interface contract remains
 * identical since both Module and NodeInstance implement Shutdownable.
 * 
 * Thread-safe: shutdown can be triggered from any thread (GUI thread, shutdown hook, etc.).
 */
public class ApplicationShutdown {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationShutdown.class);

    private static volatile ApplicationShutdown instance;

    /** Tracks whether shutdown has already been triggered */
    private final AtomicBoolean shutdownInitiated = new AtomicBoolean(false);

    /** Registered components, protected by CopyOnWriteArrayList for thread safety */
    private final List<RegisteredComponent> components = new CopyOnWriteArrayList<>();

    /** Callbacks executed after all components have been shut down */
    private final List<Runnable> onCompleteHooks = new CopyOnWriteArrayList<>();

    /** Results of the last shutdown sequence */
    private volatile ShutdownResult lastResult;

    /**
     * Internal record pairing a component with its registration timestamp.
     */
    private static class RegisteredComponent {
        private final Shutdownable component;
        private final long registeredAt;

        RegisteredComponent(Shutdownable component) {
            this.component = component;
            this.registeredAt = System.currentTimeMillis();
        }

        public Shutdownable getComponent() {
            return component;
        }

        public long getRegisteredAt() {
            return registeredAt;
        }
    }

    /**
     * Result of a shutdown sequence execution.
     */
    public static class ShutdownResult {
        private final long startTime;
        private final long endTime;
        private final List<ShutdownStepResult> steps;
        private final boolean success;
        private final String summary;

        public ShutdownResult(long startTime, long endTime,
                               List<ShutdownStepResult> steps) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.steps = Collections.unmodifiableList(steps);
            this.success = steps.stream().allMatch(ShutdownStepResult::isSuccess);
            this.summary = buildSummary();
        }

        private String buildSummary() {
            int total = steps.size();
            int failed = (int) steps.stream().filter(s -> !s.isSuccess).count();
            long durationMs = endTime - startTime;
            
            if (failed == 0) {
                return String.format("Shutdown completed successfully in %d ms (%d components)",
                        durationMs, total);
            } else {
                return String.format("Shutdown completed with %d/%d failures in %d ms",
                        failed, total, durationMs);
            }
        }

        public long getStartTime() { return startTime; }
        public long getEndTime() { return endTime; }
        public long getDurationMs() { return endTime - startTime; }
        public List<ShutdownStepResult> getSteps() { return steps; }
        public boolean isSuccess() { return success; }
        public String getSummary() { return summary; }
    }

    /**
     * Result of shutting down a single component.
     */
    public static class ShutdownStepResult {
        private final String componentName;
        private final ShutdownPriority priority;
        private final boolean isSuccess;
        private final long durationMs;
        private final String errorMessage;

        public ShutdownStepResult(String name, ShutdownPriority priority,
                                   boolean success, long durationMs) {
            this.componentName = name;
            this.priority = priority;
            this.isSuccess = success;
            this.durationMs = durationMs;
            this.errorMessage = null;
        }

        public ShutdownStepResult(String name, ShutdownPriority priority,
                                   String errorMessage) {
            this.componentName = name;
            this.priority = priority;
            this.isSuccess = false;
            this.durationMs = 0;
            this.errorMessage = errorMessage;
        }

        public String getComponentName() { return componentName; }
        public ShutdownPriority getPriority() { return priority; }
        public boolean isSuccess() { return isSuccess; }
        public long getDurationMs() { return durationMs; }
        public String getErrorMessage() { return errorMessage; }

        @Override
        public String toString() {
            String status = isSuccess ? "OK" : "FAILED";
            return String.format("[%s] %s - %s (%d ms)",
                    priority, componentName, status, durationMs);
        }
    }

    private ApplicationShutdown() {
        // Private constructor for singleton
    }

    /**
     * Gets or creates the singleton instance.
     */
    public static synchronized ApplicationShutdown getInstance() {
        if (instance == null) {
            instance = new ApplicationShutdown();
        }
        return instance;
    }

    /**
     * Resets the singleton. Use only for testing.
     */
    public static synchronized void resetInstance() {
        instance = null;
    }

    // ====================================================================
    // Registration API
    // ====================================================================

    /**
     * Registers a component for shutdown management.
     * Components are shut down in priority order (HIGHEST first) during shutdown.
     * Within the same priority level, components are shut down in reverse
     * registration order (LIFO - last registered, first shut down).
     *
     * @param component The component to register
     */
    public void register(Shutdownable component) {
        if (component == null) {
            throw new IllegalArgumentException("Component cannot be null");
        }
        components.add(new RegisteredComponent(component));
        LOGGER.debug("Registered shutdownable component: {} (priority: {})",
                component.getComponentName(), component.getShutdownPriority());
    }

    /**
     * Unregisters a component from shutdown management.
     *
     * @param component The component to unregister
     * @return true if the component was found and removed
     */
    public boolean unregister(Shutdownable component) {
        boolean removed = components.removeIf(
                rc -> rc.getComponent().equals(component));
        if (removed) {
            LOGGER.debug("Unregistered shutdownable component: {}",
                    component.getComponentName());
        }
        return removed;
    }

    /**
     * Gets the count of registered components.
     */
    public int getRegisteredCount() {
        return components.size();
    }

    // ====================================================================
    // Completion hooks
    // ====================================================================

    /**
     * Registers a callback to execute after all components have been shut down.
     * Hooks are executed regardless of whether shutdown succeeded or had failures.
     *
     * @param hook The callback to register
     */
    public void addOnCompleteHook(Runnable hook) {
        onCompleteHooks.add(hook);
    }

    // ====================================================================
    // State queries
    // ====================================================================

    /**
     * Checks if shutdown has been initiated.
     */
    public boolean isShutdownInitiated() {
        return shutdownInitiated.get();
    }

    /**
     * Gets the result of the last shutdown sequence, or null if none executed yet.
     */
    public ShutdownResult getLastResult() {
        return lastResult;
    }

    // ====================================================================
    // Shutdown execution
    // ====================================================================

    /**
     * Executes the full shutdown sequence.
     * 
     * The process:
     * 1. Guard against duplicate shutdown triggers (idempotent)
     * 2. Group components by priority level
     * 3. Execute shutdown in priority order (HIGHEST → LOWEST)
     * 4. Track success/failure per component
     * 5. Execute completion hooks
     * 6. Log final summary
     * 
     * If shutdown is already in progress, this method returns immediately
     * with the existing result.
     * 
     * @return The result of the shutdown sequence
     */
    public ShutdownResult executeShutdownSequence() {
        // Idempotent guard - only one thread enters the shutdown logic
        if (!shutdownInitiated.compareAndSet(false, true)) {
            LOGGER.warn("Shutdown already initiated. Returning existing result.");
            return lastResult;
        }

        LOGGER.info("=== Application Shutdown Sequence Started ===");
        long startTime = System.currentTimeMillis();
        List<ShutdownStepResult> stepResults = new ArrayList<>();

        try {
            // Group by priority and execute in order
            Map<ShutdownPriority, List<RegisteredComponent>> byPriority = new EnumMap<>(ShutdownPriority.class);
            
            for (RegisteredComponent rc : components) {
                ShutdownPriority p = rc.getComponent().getShutdownPriority();
                byPriority.computeIfAbsent(p, k -> new ArrayList<>()).add(rc);
            }

            // Execute priorities in order: HIGHEST(0) first, LOWEST(4) last
            for (ShutdownPriority priority : ShutdownPriority.values()) {
                List<RegisteredComponent> group = byPriority.get(priority);
                if (group == null || group.isEmpty()) {
                    continue;
                }

                LOGGER.info("Shutting down {} components with priority: {}", 
                        group.size(), priority);

                // Within same priority, reverse registration order (LIFO)
                List<RegisteredComponent> reversedGroup = new ArrayList<>(group);
                Collections.reverse(reversedGroup);
                for (RegisteredComponent rc : reversedGroup) {
                    executeSingleShutdown(rc, stepResults);
                }
            }

        } catch (Exception e) {
            LOGGER.error("Unexpected error during shutdown sequence", e);
        }

        long endTime = System.currentTimeMillis();
        lastResult = new ShutdownResult(startTime, endTime, stepResults);

        // Execute completion hooks
        executeCompletionHooks();

        // Log final summary
        logShutdownSummary(lastResult);

        return lastResult;
    }

    /**
     * Shuts down a single component and records the result.
     */
    private void executeSingleShutdown(RegisteredComponent rc,
                                        List<ShutdownStepResult> results) {
        Shutdownable component = rc.getComponent();
        String name = component.getComponentName();
        ShutdownPriority priority = component.getShutdownPriority();

        LOGGER.debug("Shutting down component: {}", name);
        long stepStart = System.currentTimeMillis();

        try {
            component.shutdown();
            long duration = System.currentTimeMillis() - stepStart;
            results.add(new ShutdownStepResult(name, priority, true, duration));
            LOGGER.info("Component '{}' shut down successfully ({})", name, duration + "ms");
        } catch (ShutdownException e) {
            results.add(new ShutdownStepResult(name, priority, e.getMessage()));
            LOGGER.error("Component '{}' failed to shut down: {}", name, e.getMessage(), e.getCause());
        } catch (Throwable t) {
            // Catch any unexpected throwable to prevent aborting the entire sequence
            String msg = "Unexpected error during shutdown of '" + name + "': " + t.getMessage();
            results.add(new ShutdownStepResult(name, priority, msg));
            LOGGER.error(msg, t);
        }
    }

    /**
     * Executes all registered completion hooks.
     */
    private void executeCompletionHooks() {
        LOGGER.info("Executing {} shutdown completion hook(s)", onCompleteHooks.size());
        for (Runnable hook : onCompleteHooks) {
            try {
                hook.run();
            } catch (Throwable t) {
                LOGGER.error("Error executing shutdown completion hook", t);
            }
        }
    }

    /**
     * Logs a detailed summary of the shutdown sequence.
     */
    private void logShutdownSummary(ShutdownResult result) {
        LOGGER.info("=== Shutdown Sequence Complete ===");
        LOGGER.info("Summary: {}", result.getSummary());
        
        for (ShutdownStepResult step : result.getSteps()) {
            String status = step.isSuccess() ? "SUCCESS" : "FAILED";
            if (step.isSuccess()) {
                LOGGER.info("  [{}] {} - {} ({})", 
                        step.getPriority(), step.getComponentName(), 
                        status, step.getDurationMs() + "ms");
            } else {
                LOGGER.warn("  [{}] {} - {} - {}", 
                        step.getPriority(), step.getComponentName(),
                        status, step.getErrorMessage());
            }
        }

        if (!result.isSuccess()) {
            LOGGER.warn("Shutdown completed with errors. Check logs for details.");
        }
    }
}