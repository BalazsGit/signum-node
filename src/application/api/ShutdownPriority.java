package application.api;

/**
 * Defines the priority order for component shutdown during application termination.
 * Components with HIGHER priority are shut down FIRST to ensure proper teardown order.
 * 
 * This design supports both the current single-instance architecture (Solution A)
 * and the future multi-instance architecture (Solution B) where each profile
 * runs as a completely separate, independent object graph.
 * 
 * Shutdown Order (highest first):
 * 1. HIGHEST   - Active network connections, block processing, peers
 * 2. HIGH      - Web servers, API endpoints, thread pools
 * 3. NORMAL    - Business logic modules, services (default)
 * 4. LOW       - GUI components, caches, metrics
 * 5. LOWEST    - Logging, configuration persistence, cleanup hooks
 */
public enum ShutdownPriority {
    
    /** Active blockchain processing, peer connections, network handlers */
    HIGHEST(0),
    
    /** Web servers, HTTP APIs, WebSocket servers, thread pools */
    HIGH(1),
    
    /** Business logic modules, service layers (default priority) */
    NORMAL(2),
    
    /** GUI panels, caches, statistics, metrics collectors */
    LOW(3),
    
    /** Logging frameworks, config persistence, final cleanup hooks */
    LOWEST(4);
    
    private final int order;
    
    ShutdownPriority(int order) {
        this.order = order;
    }
    
    /**
     * Returns the numeric order value for sorting.
     * Lower values = higher priority (shut down first).
     */
    public int getOrder() {
        return order;
    }
}