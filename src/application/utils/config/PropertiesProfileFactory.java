package application.utils.config;

import java.util.function.Function;

/**
 * Functional interface for creating {@link PropertiesProfileEntity} instances by name.
 * <p>
 * <h3>Scope</h3>
 * This factory is specific to <b>Java {@code .properties}</b>-based profiles.
 * Other profile formats (e.g., JSON-based database profiles) use their own
 * dedicated factory mechanisms.
 * <p>
 * Used by {@link PropertiesProfileLoader#loadAll} to construct strongly-typed profile
 * entities without the loader needing knowledge of concrete classes.
 *
 * <h3>Design Pattern: Factory</h3>
 * Enables polymorphic object creation. The loader knows <em>how</em> to load
 * (discover → read → set properties), while the factory knows <em>what</em> to create.
 *
 * <h3>Usage Examples</h3>
 * <pre>{@code
 * // Method reference (most common):
 * NodeProfile[] profiles = PropertiesProfileLoader.loadAll(
 *         "conf", "node", "profiles", reserved,
 *         NodeProfile::new, NodeProfile.class);
 *
 * // Lambda with custom logic:
 * PropertiesProfileFactory<MyProfile> factory = name -> {
 *     MyProfile p = new MyProfile(name);
 *     p.setValidator(new StrictValidator());
 *     return p;
 * };
 * }</pre>
 *
 * @param <T> the type of {@link PropertiesProfileEntity} to create
 * @see PropertiesProfileLoader
 * @see PropertiesProfileEntity
 */
@FunctionalInterface
public interface PropertiesProfileFactory<T extends PropertiesProfileEntity> extends Function<String, T> {

    /**
     * Creates a new properties-profile entity with the given name.
     *
     * @param name the profile name (as discovered from disk, without extension)
     * @return a new instance of the profile entity (never null)
     */
    T create(String name);

    /**
     * Default delegation to {@link #create(String)} so the interface satisfies
     * {@link Function}. Clients should prefer calling {@code create()} directly.
     */
    @Override
    default T apply(String name) {
        return create(name);
    }
}