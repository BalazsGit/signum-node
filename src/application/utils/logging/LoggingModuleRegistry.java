package application.utils.logging;

import application.utils.profile.ProfileModule;
import application.utils.profile.ProfileRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton registry for module-specific logging providers.
 * <p>
 * Each functional module (node, database, pool, etc.) registers its {@link ModuleLoggingProvider}
 * at startup. Module storage is delegated to the module-independent {@link ProfileRegistry}
 * (single source of truth); this facade exposes a logging-typed view and bridges
 * registration notifications to logging-typed listeners.
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
 * LoggingModuleRegistry.getInstance().addRegisteredListener(module -> {
 *     panel.addModuleCheckbox(module);
 * });
 * }</pre>
 *
 * @see ModuleLoggingProvider
 * @see ProfileRegistry
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

    /** The module-independent registry that owns the module set (R1 single source of truth, P6.1). */
    private final ProfileRegistry delegate = ProfileRegistry.getInstance();

    /**
     * Bridges a logging-typed add-listener to a {@link ProfileModule}-typed one so it can be
     * registered on the delegate; keyed so {@link #removeRegisteredListener} finds the bridge.
     */
    private final Map<Consumer<ModuleLoggingProvider>, Consumer<ProfileModule>> addedBridges =
            new ConcurrentHashMap<>();

    /** Unregistered (String) listeners registered on the delegate, so {@link #clear()} can drop them. */
    private final Set<Consumer<String>> unregisteredListeners = ConcurrentHashMap.newKeySet();

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
        LOGGER.info("Registering logging provider for module '{}': {}",
                provider.getModuleId(), provider.getProfile().getDisplayName());
        delegate.register(provider); // ModuleLoggingProvider IS-A ProfileModule (R1)
    }

    /**
     * Unregisters the provider for the given module ID.
     *
     * @param moduleId The module identifier
     * @return true if a provider was removed, false if none existed
     */
    public synchronized boolean unregister(String moduleId) {
        boolean removed = delegate.unregister(moduleId);
        if (removed) {
            LOGGER.info("Unregistered logging provider for module '{}'", moduleId);
        }
        return removed;
    }

    /**
     * Retrieves the provider for a specific module.
     *
     * @param moduleId The module identifier
     * @return The provider, or null if not registered
     */
    public synchronized ModuleLoggingProvider getProvider(String moduleId) {
        ProfileModule module = delegate.getModule(moduleId);
        return (module instanceof ModuleLoggingProvider provider) ? provider : null;
    }

    /**
     * Returns an unmodifiable snapshot of all registered providers.
     *
     * @return List of currently registered providers (never null)
     */
    public synchronized List<ModuleLoggingProvider> getAllProviders() {
        List<ModuleLoggingProvider> result = new ArrayList<>();
        for (ProfileModule module : delegate.getAllModules()) {
            if (module instanceof ModuleLoggingProvider provider) {
                result.add(provider);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns the set of registered module IDs.
     *
     * @return Unmodifiable set of module identifiers (never null)
     */
    public synchronized Set<String> getModuleIds() {
        return delegate.getModuleIds();
    }

    /**
     * Returns the total number of registered providers.
     */
    public synchronized int size() {
        return delegate.size();
    }

    // ── Observer registration ─────────────────────────────────────────

    /**
     * Registers a callback to be invoked whenever a new provider is registered.
     * The listener executes on the same thread that calls {@link #register(ModuleLoggingProvider)}.
     *
     * @param listener Callback receiving the newly registered provider
     */
    public void addRegisteredListener(Consumer<ModuleLoggingProvider> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        Consumer<ProfileModule> bridge = module -> {
            if (module instanceof ModuleLoggingProvider provider) {
                listener.accept(provider);
            }
        };
        addedBridges.put(listener, bridge);
        delegate.addRegisteredListener(bridge);
    }

    /**
     * Removes a previously registered add-listener.
     *
     * @param listener The listener to remove
     */
    public void removeRegisteredListener(Consumer<ModuleLoggingProvider> listener) {
        Consumer<ProfileModule> bridge = addedBridges.remove(listener);
        if (bridge != null) {
            delegate.removeRegisteredListener(bridge);
        }
    }

    /**
     * Registers a callback to be invoked whenever a provider is unregistered.
     *
     * @param listener Callback receiving the module ID that was removed
     */
    public void addUnregisteredListener(Consumer<String> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        unregisteredListeners.add(listener);
        delegate.addUnregisteredListener(listener);
    }

    /**
     * Removes a previously registered remove-listener.
     *
     * @param listener The listener to remove
     */
    public void removeUnregisteredListener(Consumer<String> listener) {
        unregisteredListeners.remove(listener);
        delegate.removeUnregisteredListener(listener);
    }

    // ── Debug / housekeeping ──────────────────────────────────────────

    /**
     * Clears all registered providers and fires removal events for each.
     * Intended for testing or application shutdown cleanup.
     */
    public synchronized void clear() {
        addedBridges.values().forEach(delegate::removeRegisteredListener);
        addedBridges.clear();
        unregisteredListeners.forEach(delegate::removeUnregisteredListener);
        unregisteredListeners.clear();
        delegate.clear();
        LOGGER.info("LoggingModuleRegistry cleared (delegated to ProfileRegistry)");
    }

    @Override
    public String toString() {
        return "LoggingModuleRegistry{registeredModules=" + getModuleIds() + '}';
    }
}