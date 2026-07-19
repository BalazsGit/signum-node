package application.utils.config;

import java.util.Properties;

/**
 * Contract for profile entities that are loaded via {@link PropertiesProfileLoader}.
 * <p>
 * <h3>Scope</h3>
 * This interface is specific to <b>Java {@code .properties}</b>-based profiles.
 * Other profile formats (e.g., JSON-based database profiles) do NOT implement this
 * interface and use their own dedicated loading mechanisms.
 * <p>
 * Any module-specific properties-profile class (e.g., {@code NodeProfile}) should
 * implement this interface to participate in the unified properties-profile loading pipeline.
 *
 * <h3>Design Pattern: Template Method</h3>
 * The {@link PropertiesProfileLoader#loadAll} method uses a common processing skeleton:
 * <ol>
 *   <li>Discover profile names from disk</li>
 *   <li>Create entity via factory</li>
 *   <li>{@code entity.setProperties(loadedProps)}</li>
 * </ol>
 * Concrete implementations vary only in construction and property interpretation.
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * public class NodeProfile implements PropertiesProfileEntity {
 *     private final String name;
 *     private final Properties properties = new Properties();
 *
 *     public NodeProfile(String name) { this.name = name; }
 *
 *     @Override public String getName() { return name; }
 *     @Override public Properties getProperties() { return properties; }
 *     @Override public void setProperties(Properties p) {
 *         properties.clear();
 *         properties.putAll(p);
 *     }
 * }
 * }</pre>
 *
 * @see PropertiesProfileLoader
 * @see PropertiesProfileFactory
 */
public interface PropertiesProfileEntity {

    /**
     * Returns the profile name (identifier).
     *
     * @return profile name, never null
     */
    String getName();

    /**
     * Returns the backing Properties for this profile.
     *
     * @return mutable Properties instance, never null
     */
    Properties getProperties();

    /**
     * Replaces all properties with the given set.
     * Called by {@link PropertiesProfileLoader} after loading from disk.
     *
     * @param props the loaded properties (never null)
     */
    void setProperties(Properties props);
}