package application.utils.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Module-independent, process-wide registry of {@link ProfileModule} instances (R1).
 * <p>
 * Each functional module registers its {@link ProfileModule} once at startup. The
 * registry is a thread-safe singleton with observer callbacks so UI panels and CLI
 * tooling can react to the module set changing without polling.
 * </p>
 *
 * <p>
 * This is the generic counterpart of the earlier logging-specific
 * {@code LoggingModuleRegistry}; it stores the module-agnostic {@link ProfileModule}
 * contract, so it can be shared by logging, database, and any future module.
 * </p>
 *
 * <h3>Lifecycle</h3>
 * <pre>{@code
 * ProfileRegistry.getInstance().register(new MyPrefsModule());
 * }</pre>
 *
 * @see ProfileModule
 */
public final class ProfileRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileRegistry.class);

    /** Lazy-holding singleton instance. */
    private static final class Holder {
        static final ProfileRegistry INSTANCE = new ProfileRegistry();
    }

    /**
     * @return the process-wide {@link ProfileRegistry} singleton.
     */
    public static ProfileRegistry getInstance() {
        return Holder.INSTANCE;
    }

    // ── Internal state ────────────────────────────────────────────────

    /** module-id → module mapping. Thread-safe via synchronized access. */
    private final Map<String, ProfileModule> modules = new HashMap<>();

    /** Observer callbacks fired when a module is registered. */
    private final List<Consumer<ProfileModule>> addedListeners = new CopyOnWriteArrayList<>();

    /** Observer callbacks fired when a module is unregistered. */
    private final List<Consumer<String>> removedListeners = new CopyOnWriteArrayList<>();

    private ProfileRegistry() {
        // Singleton constructor
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Registers a module. If a module with the same id is already registered it is
     * replaced and both the added and removed listeners fire.
     *
     * @param module the module to register (must not be null)
     * @throws NullPointerException if module is null
     */
    public synchronized void register(ProfileModule module) {
        Objects.requireNonNull(module, "ProfileModule must not be null");
        String id = module.getModuleId();

        ProfileModule previous = modules.put(id, module);
        if (previous != null) {
            LOGGER.info("Re-registered profile module '{}' (replacing {})",
                    id, previous.getClass().getSimpleName());
            removedListeners.forEach(l -> l.accept(id));
        } else {
            LOGGER.info("Registered profile module '{}'", id);
        }
        addedListeners.forEach(l -> l.accept(module));
    }

    /**
     * Unregisters the module with the given id.
     *
     * @param moduleId the module id
     * @return true if a module was present and removed
     */
    public synchronized boolean unregister(String moduleId) {
        ProfileModule removed = modules.remove(moduleId);
        if (removed != null) {
            LOGGER.info("Unregistered profile module '{}'", moduleId);
            removedListeners.forEach(l -> l.accept(moduleId));
            return true;
        }
        return false;
    }

    /**
     * @param moduleId the module id
     * @return the registered module, or null if not registered
     */
    public synchronized ProfileModule getModule(String moduleId) {
        return modules.get(moduleId);
    }

    /**
     * @return an unmodifiable snapshot of all registered modules (never null)
     */
    public synchronized List<ProfileModule> getAllModules() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(modules.values()));
    }

    /**
     * @return the set of registered module ids (never null)
     */
    public synchronized Set<String> getModuleIds() {
        return Collections.unmodifiableSet(new HashSet<>(modules.keySet()));
    }

    /**
     * @param moduleId the module id
     * @return true if a module is registered under this id
     */
    public synchronized boolean isRegistered(String moduleId) {
        return modules.containsKey(moduleId);
    }

    /**
     * @return the number of registered modules
     */
    public synchronized int size() {
        return modules.size();
    }

    // ── Observer registration ─────────────────────────────────────────

    /**
     * Registers a callback invoked when a module is (re)registered.
     *
     * @param listener callback receiving the registered module
     */
    public void addRegisteredListener(Consumer<ProfileModule> listener) {
        addedListeners.add(listener);
    }

    /**
     * Removes a previously registered add-listener.
     *
     * @param listener the listener to remove
     */
    public void removeRegisteredListener(Consumer<ProfileModule> listener) {
        addedListeners.remove(listener);
    }

    /**
     * Registers a callback invoked when a module is unregistered.
     *
     * @param listener callback receiving the removed module id
     */
    public void addUnregisteredListener(Consumer<String> listener) {
        removedListeners.add(listener);
    }

    /**
     * Removes a previously registered remove-listener.
     *
     * @param listener the listener to remove
     */
    public void removeUnregisteredListener(Consumer<String> listener) {
        removedListeners.remove(listener);
    }

    // ── Debug / housekeeping ──────────────────────────────────────────

    /**
     * Clears all registered modules, firing a removal event for each.
     * Intended for testing or application-shutdown cleanup.
     */
    public synchronized void clear() {
        Set<String> ids = new HashSet<>(modules.keySet());
        modules.clear();
        for (String id : ids) {
            removedListeners.forEach(l -> l.accept(id));
        }
        LOGGER.info("ProfileRegistry cleared all {} module(s)", ids.size());
    }

    @Override
    public String toString() {
        return "ProfileRegistry{registeredModules=" + modules.keySet() + '}';
    }
}
