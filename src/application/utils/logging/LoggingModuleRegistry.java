package application.utils.logging;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton registry for module-specific logging providers.
 * <p>
 * Each functional module (node, database, pool, etc.) registers its {@link ModuleLoggingProvider}
 * at startup. The registry maintains a thread-safe map and notifies observers when
 * modules are added or removed.
 * </p>
 *
 * <h3>Design Pattern: Observer + Singleton</h3>
 * UI panels register listeners to be notified when the available module set changes,
 * enabling dynamic checkbox/section updates without polling.
 *
 * <h3>Lifecycle</h3>
 * <pre>{@code
 * // At application startup (typically in Module.start()):
 * LoggingModuleRegistry.getInstance().register(new NodeLoggingProvider());
 * LoggingModuleRegistry.getInstance().register(new DatabaseLoggingProvider());
 *
 * // In the logging configuration panel:
 * LoggingModuleRegistry.getInstance().addListener(module -> {
 *     panel.addModuleCheckbox(module);
 * });
 * }</pre>
 *
 * @see ModuleLoggingProvider
 * @see LoggingProfileManager
 */
public final class LoggingModuleRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingModuleRegistry.class);

    /** Lazy-holding singleton instance. */
    private static final class Holder {
        static final LoggingModuleRegistry INSTANCE = new LoggingModuleRegistry();
    }

    /**
     * Returns the singleton instance.
     *
     * @return The global {@link LoggingModuleRegistry}
     */
    public static LoggingModuleRegistry getInstance() {
        return Holder.INSTANCE;
    }

    // ── Internal state ────────────────────────────────────────────────

    /** module-id → provider mapping. Thread-safe via synchronized access. */
    private final Map<String, ModuleLoggingProvider> providers = new java.util.HashMap<>();

    /** Observer callbacks fired when a provider is registered. */
    private final List<Consumer<ModuleLoggingProvider>> addedListeners = new CopyOnWriteArrayList<>();

    /** Observer callbacks fired when a provider is unregistered. */
    private final List<Consumer<String>> removedListeners = new CopyOnWriteArrayList<>();

    private LoggingModuleRegistry() {
        // Singleton constructor
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Registers a new logging provider. If a provider with the same module ID is
     * already registered, it will be replaced and both added/removed listeners fire.
     *
     * @param provider The provider to register (must not be null)
     * @throws NullPointerException if provider is null
     */
    public synchronized void register(ModuleLoggingProvider provider) {
        Objects.requireNonNull(provider, "ModuleLoggingProvider must not be null");
        String id = provider.getProfile().getModuleId();

        ModuleLoggingProvider previous = providers.put(id, provider);
        if (previous != null) {
            LOGGER.info("Re-registered logging provider for module '{}' (replacing {})",
                    id, previous.getClass().getSimpleName());
            removedListeners.forEach(l -> l.accept(id));
        } else {
            LOGGER.info("Registered logging provider for module '{}': {}",
                    id, provider.getProfile().getDisplayName());
        }

        addedListeners.forEach(l -> l.accept(provider));
    }

    /**
     * Unregisters the provider for the given module ID.
     *
     * @param moduleId The module identifier
     * @return true if a provider was removed, false if none existed
     */
    public synchronized boolean unregister(String moduleId) {
        ModuleLoggingProvider removed = providers.remove(moduleId);
        if (removed != null) {
            LOGGER.info("Unregistered logging provider for module '{}'", moduleId);
            removedListeners.forEach(l -> l.accept(moduleId));
            return true;
        }
        return false;
    }

    /**
     * Retrieves the provider for a specific module.
     *
     * @param moduleId The module identifier
     * @return The provider, or null if not registered
     */
    public synchronized ModuleLoggingProvider getProvider(String moduleId) {
        return providers.get(moduleId);
    }

    /**
     * Returns an unmodifiable snapshot of all registered providers.
     *
     * @return List of currently registered providers (never null)
     */
    public synchronized List<ModuleLoggingProvider> getAllProviders() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(providers.values()));
    }

    /**
     * Returns the set of registered module IDs.
     *
     * @return Unmodifiable set of module identifiers (never null)
     */
    public synchronized Set<String> getModuleIds() {
        return Collections.unmodifiableSet(new HashSet<>(providers.keySet()));
    }

    /**
     * Checks whether a provider is registered for the given module ID.
     *
     * @param moduleId The module identifier
     * @return true if registered
     */
    public synchronized boolean isRegistered(String moduleId) {
        return providers.containsKey(moduleId);
    }

    /**
     * Returns the total number of registered providers.
     */
    public synchronized int size() {
        return providers.size();
    }

    // ── Observer registration ─────────────────────────────────────────

    /**
     * Registers a callback to be invoked whenever a new provider is registered.
     * The listener executes on the same thread that calls {@link #register(ModuleLoggingProvider)}.
     *
     * @param listener Callback receiving the newly registered provider
     */
    public void addRegisteredListener(Consumer<ModuleLoggingProvider> listener) {
        addedListeners.add(listener);
    }

    /**
     * Removes a previously registered add-listener.
     *
     * @param listener The listener to remove
     */
    public void removeRegisteredListener(Consumer<ModuleLoggingProvider> listener) {
        addedListeners.remove(listener);
    }

    /**
     * Registers a callback to be invoked whenever a provider is unregistered.
     *
     * @param listener Callback receiving the module ID that was removed
     */
    public void addUnregisteredListener(Consumer<String> listener) {
        removedListeners.add(listener);
    }

    /**
     * Removes a previously registered remove-listener.
     *
     * @param listener The listener to remove
     */
    public void removeUnregisteredListener(Consumer<String> listener) {
        removedListeners.remove(listener);
    }

    // ── Debug / housekeeping ──────────────────────────────────────────

    /**
     * Clears all registered providers and fires removal events for each.
     * Intended for testing or application shutdown cleanup.
     */
    public synchronized void clear() {
        Set<String> ids = new HashSet<>(providers.keySet());
        providers.clear();
        for (String id : ids) {
            removedListeners.forEach(l -> l.accept(id));
        }
        LOGGER.info("LoggingModuleRegistry cleared all {} provider(s)", ids.size());
    }

    @Override
    public String toString() {
        return "LoggingModuleRegistry{registeredModules=" + providers.keySet() + '}';
    }
}