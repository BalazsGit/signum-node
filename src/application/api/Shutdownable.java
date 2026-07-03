package application.api;

/**
 * Interface for components that require explicit cleanup during application shutdown.
 * 
 * This interface follows the Command Pattern - each component knows how to shut
 * itself down. The ApplicationShutdown orchestrator collects all Shutdownable
 * components and executes them in priority order.
 * 
 * Design note for Solution B migration: In the future multi-instance architecture,
 * each profile will create its own independent object graph where every component
 * implements this interface. The current Solution A uses it at the Module level,
 * while Solution B will use it at the individual component level within each
 * isolated NodeInstance.
 * 
 * Implementations MUST be safe to call multiple times (idempotent).
 * If the component is already shut down, subsequent calls should be no-ops.
 */
public interface Shutdownable {
    
    /**
     * Gracefully shut down this component and release all held resources.
     * 
     * This method is called during the application shutdown sequence in priority
     * order. Implementations should:
     * <ul>
     *   <li>Stop active processing (blockchains, network connections, etc.)</li>
     *   <li>Close open resources (connections, files, sockets)</li>
     *   <li>Flush and persist any pending state</li>
     *   <li>Clean up background threads</li>
     * </ul>
     * 
     * The method is idempotent - calling it multiple times is safe.
     * If the component has already been shut down, this is a no-op.
     * 
     * @throws ShutdownException if shutdown fails and should be tracked as an error
     */
    void shutdown() throws ShutdownException;
    
    /**
     * Returns a unique identifier for this component, used for logging and
     * shutdown state tracking.
     * 
     * @return A human-readable component name (e.g., "NodeModule", "DatabaseModule")
     */
    String getComponentName();
    
    /**
     * Returns the shutdown priority for this component.
     * Components with higher priority values are shut down earlier in the sequence.
     * 
     * Default implementation returns NORMAL priority. Override if your component
     * requires specific ordering (e.g., network components should shut down
     * before database connections).
     * 
     * @return The shutdown priority for this component
     */
    default ShutdownPriority getShutdownPriority() {
        return ShutdownPriority.NORMAL;
    }
    
    /**
     * Exception thrown when a component fails to shut down cleanly.
     * The ApplicationShutdown orchestrator captures these exceptions and reports
     * them in the final shutdown result.
     */
    class ShutdownException extends Exception {
        
        private final String componentName;
        
        public ShutdownException(String componentName, String message) {
            super("Failed to shutdown component '" + componentName + "': " + message);
            this.componentName = componentName;
        }
        
        public ShutdownException(String componentName, String message, Throwable cause) {
            super("Failed to shutdown component '" + componentName + "': " + message, cause);
            this.componentName = componentName;
        }
        
        public String getComponentName() {
            return componentName;
        }
    }
}