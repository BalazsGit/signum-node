package application.api;

import javax.swing.JComponent;

/**
 * Core interface for application modules. Each module represents a major
 * functional area of the application (Node, Database, Appearance, etc.) and
 * follows a standard lifecycle: init → start → running → stop.
 *
 * Modules implement Shutdownable so they can be integrated into the
 * ApplicationShutdown orchestrator. The existing stop() method serves as
 * the shutdown implementation, while getId() serves as the component name.
 *
 * Design note for Solution B migration: In the future multi-instance
 * architecture, each profile will create its own Module instance with an
 * independent object graph. The Shutdownable contract ensures every module
 * can be cleanly shut down regardless of whether it runs in single-instance
 * (current) or multi-instance (future) mode.
 */
public interface Module extends Shutdownable {

    /**
     * Returns the unique identifier for this module.
     * Used as both module ID and component name for shutdown tracking.
     *
     * @return Unique module identifier (e.g., "node", "database")
     */
    String getId();

    /**
     * Returns a human-readable display name for this module.
     *
     * @return Display name shown in the UI (e.g., "Node", "Database")
     */
    String getDisplayName();

    /**
     * Initialize the module with the given context.
     * Called once during application boot before start().
     *
     * @param context The module context providing configuration and services
     */
    void init(ModuleContext context);

    /**
     * Start the module after initialization.
     * This is where modules register providers, load resources, etc.
     */
    void start();

    /**
     * Stop the module and release all held resources.
     * Called during application shutdown in reverse startup order.
     *
     * This method IS the shutdown implementation. The default Shutdownable
     * contract delegates to this method.
     */
    void stop();

    /**
     * Returns the main UI component for this module (the tab content).
     * Returns null if the module has no UI presence.
     *
     * @return The UI component, or null for headless modules
     */
    JComponent getUI();

    // =====================================================================
    // Shutdownable contract - default implementations bridge to existing API
    // =====================================================================

    /**
     * Default shutdown implementation delegates to stop().
     * Modules should implement their cleanup logic in stop() and this
     * default method will handle the delegation.
     */
    @Override
    default void shutdown() throws ShutdownException {
        try {
            stop();
        } catch (Exception e) {
            throw new ShutdownException(getId(), "Error during module stop", e);
        }
    }

    /**
     * Default component name is the module ID.
     */
    @Override
    default String getComponentName() {
        return getId();
    }

    /**
     * Default shutdown priority for modules is NORMAL.
     * Override in concrete module implementations if specific ordering is needed.
     * (e.g., NodeModule should have HIGHEST priority since it manages the core)
     */
    @Override
    default ShutdownPriority getShutdownPriority() {
        return ShutdownPriority.NORMAL;
    }
}
