package application.utils.logging;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manages profile-to-color mappings for multi-profile console display.
 * <p>
 * Assigns a unique, visually distinct color to each running NodeProfile so that
 * log events from different profiles can be distinguished at a glance in the
 * aggregated SystemConsole view. Colors are either custom-assigned or auto-generated
 * from a curated palette when not explicitly set.
 * </p>
 * <p>
 * <h3>Design</h3>
 * <ul>
 *   <li>Thread-safe: All public methods are synchronized on the internal map.</li>
 *   <li>Extensible palette: 20 pre-defined colors, cycles with a suffix for more profiles.</li>
 *   <li>Persistable: Supports export/import via {@link #toMap()} / {@link #fromMap(Map)}.</li>
 * </ul>
 * </p>
 * <p>
 * Usage:
 * <pre>{@code
 * ConsoleColorScheme scheme = ConsoleColorScheme.getDefault();
 * Color mainnetColor = scheme.getColorForProfile("mainnet-prune"); // auto-assigned
 * scheme.setCustomColor("testnet-prune", new Color(0, 200, 100));  // explicit override
 * }</pre>
 * </p>
 *
 * @see ProfileLogContext
 * @see ProfileConsoleSubscriber
 */
public final class ConsoleColorScheme {

    /**
     * Curated palette of visually distinct colors for profile identification.
     * Chosen for good contrast against both light and dark backgrounds.
     */
    private static final Color[] DEFAULT_PALETTE = {
        new Color(120, 180, 255),  // Light blue
        new Color(160, 255, 160),  // Light green
        new Color(255, 180, 140),  // Light orange
        new Color(210, 160, 255),  // Light purple
        new Color(255, 220, 130),  // Light yellow
        new Color(140, 220, 220),  // Teal
        new Color(255, 160, 200),  // Pink
        new Color(180, 200, 130),  // Olive green
        new Color(150, 170, 255),  // Periwinkle
        new Color(255, 160, 100),  // Dark orange
        new Color(180, 140, 255),  // Violet
        new Color(130, 255, 200),  // Mint
        new Color(255, 200, 160),  // Peach
        new Color(160, 200, 180),  // Sage
        new Color(255, 190, 220),  // Light pink
        new Color(170, 220, 140),  // Lime
        new Color(200, 170, 255),  // Lavender
        new Color(255, 230, 180),  // Wheat
        new Color(140, 200, 240),  // Sky blue
        new Color(220, 180, 160)   // Tan
    };

    /** Default color when palette is exhausted and no custom mapping exists */
    private static final Color FALLBACK_COLOR = new Color(200, 200, 200);

    // Custom (user-defined) color overrides: profileName -> Color
    private final Map<String, Color> customColors = new HashMap<>();

    // Auto-assigned colors: profileName -> palette index assigned
    private final Map<String, Integer> assignedIndices = new HashMap<>();

    // Track which palette indices have been used for auto-assignment
    private final List<Boolean> usedSlots = new ArrayList<>(Collections.nCopies(DEFAULT_PALETTE.length, false));

    /**
     * Returns the global default instance of ConsoleColorScheme.
     * Convenience singleton for applications that do not need multiple independent schemes.
     *
     * @return the default ConsoleColorScheme instance
     */
    public static ConsoleColorScheme getDefault() {
        return SingletonHolder.DEFAULT_INSTANCE;
    }

    private static final class SingletonHolder {
        static final ConsoleColorScheme DEFAULT_INSTANCE = new ConsoleColorScheme();
    }

    /** Creates a new empty color scheme. */
    public ConsoleColorScheme() {
    }

    /**
     * Gets the color for the specified profile name.
     * <p>
     * Lookup order:
     * <ol>
     *   <li>Custom color override (if set via {@link #setCustomColor(String, Color)})</li>
     *   <li>Previously auto-assigned color from palette</li>
     *   <li>New auto-assignment from next available palette slot (cycles if exhausted)</li>
     * </ol>
     *
     * @param profileName the profile identifier (never null)
     * @return the color for this profile (never null)
     */
    public synchronized Color getColorForProfile(String profileName) {
        Objects.requireNonNull(profileName, "Profile name must not be null");

        // 1. Check custom override first
        if (customColors.containsKey(profileName)) {
            return customColors.get(profileName);
        }

        // 2. Check previously auto-assigned
        if (assignedIndices.containsKey(profileName)) {
            int index = assignedIndices.get(profileName);
            return DEFAULT_PALETTE[index % DEFAULT_PALETTE.length];
        }

        // 3. Auto-assign from next available slot
        int newIndex = findNextAvailableSlot();
        assignedIndices.put(profileName, newIndex);
        if (newIndex < DEFAULT_PALETTE.length) {
            usedSlots.set(newIndex, true);
        }
        return DEFAULT_PALETTE[newIndex % DEFAULT_PALETTE.length];
    }

    /**
     * Sets a custom color override for the given profile.
     * Custom colors take precedence over auto-assigned palette colors.
     *
     * @param profileName the profile identifier (never null)
     * @param color       the color to assign (never null)
     */
    public synchronized void setCustomColor(String profileName, Color color) {
        Objects.requireNonNull(profileName, "Profile name must not be null");
        Objects.requireNonNull(color, "Color must not be null");
        customColors.put(profileName, color);
        // Remove from auto-assigned so the custom color takes over
        Integer prevIndex = assignedIndices.remove(profileName);
        if (prevIndex != null && prevIndex < usedSlots.size()) {
            usedSlots.set(prevIndex, false);
        }
    }

    /**
     * Clears any custom color override for the given profile,
     * reverting to auto-assigned palette behavior.
     *
     * @param profileName the profile identifier (never null)
     * @return true if a custom override was removed, false otherwise
     */
    public synchronized boolean clearCustomColor(String profileName) {
        Objects.requireNonNull(profileName, "Profile name must not be null");
        // Also remove auto-assignment so it gets a fresh palette slot
        Integer prevIndex = assignedIndices.remove(profileName);
        if (prevIndex != null && prevIndex < usedSlots.size()) {
            usedSlots.set(prevIndex, false);
        }
        return customColors.remove(profileName) != null;
    }

    /**
     * Checks if the given profile has a custom color override.
     *
     * @param profileName the profile identifier (never null)
     * @return true if a custom color is set for this profile
     */
    public synchronized boolean hasCustomColor(String profileName) {
        Objects.requireNonNull(profileName, "Profile name must not be null");
        return customColors.containsKey(profileName);
    }

    /**
     * Returns all currently tracked profiles (both custom and auto-assigned).
     *
     * @return an unmodifiable set of profile names
     */
    public synchronized List<String> getAssignedProfiles() {
        return Collections.unmodifiableList(
            new ArrayList<>(customColors.keySet()));
    }

    /**
     * Exports the custom color overrides as a serializable map.
     * The values are stored as integer RGB values for easy persistence.
     *
     * @return an unmodifiable map of profileName -> RGB int (never null)
     */
    public synchronized Map<String, Integer> exportCustomColors() {
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, Color> entry : customColors.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getRGB());
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Creates a new ConsoleColorScheme from exported RGB data.
     *
     * @param rgbMap map of profileName -> RGB int values (never null)
     * @return a new ConsoleColorScheme with the custom colors applied
     */
    public static ConsoleColorScheme fromRgbMap(Map<String, Integer> rgbMap) {
        Objects.requireNonNull(rgbMap, "RGB map must not be null");
        ConsoleColorScheme scheme = new ConsoleColorScheme();
        for (Map.Entry<String, Integer> entry : rgbMap.entrySet()) {
            scheme.setCustomColor(entry.getKey(), new Color(entry.getValue()));
        }
        return scheme;
    }

    /**
     * Resolves the color for a given log event by looking up its profile name.
     * Convenience method that delegates to {@link #getColorForProfile(String)}.
     *
     * @param profileName the profile from the log event, may be null for bootstrap/system events
     * @return the resolved color (never null; returns fallback for null profile)
     */
    public Color resolveEventColor(String profileName) {
        return resolveEventColor(profileName, null);
    }

    /**
     * Resolves the color for a profile, preferring an explicit (custom) color.
     * <p>
     * Tries the qualified {@code module.profile} key first, then the bare
     * {@code profile} key (for legacy custom colors keyed by the bare name), then
     * auto-assigns a color under the qualified key.
     * </p>
     *
     * @param qualifiedKey the qualified {@code module.profile} name, may be null
     * @param fallbackKey  the bare profile name, may be null
     * @return the resolved color (never null)
     */
    public synchronized Color resolveEventColor(String qualifiedKey, String fallbackKey) {
        if (qualifiedKey == null || qualifiedKey.isEmpty()) {
            qualifiedKey = fallbackKey;
        }
        if (qualifiedKey == null || qualifiedKey.isEmpty()) {
            return FALLBACK_COLOR;
        }
        // Prefer an explicit custom color: qualified first, then bare (legacy).
        Color custom = customColors.get(qualifiedKey);
        if (custom != null) {
            return custom;
        }
        if (fallbackKey != null && !fallbackKey.isEmpty() && !fallbackKey.equals(qualifiedKey)) {
            Color legacy = customColors.get(fallbackKey);
            if (legacy != null) {
                return legacy;
            }
        }
        // No explicit custom color: auto-assign (or reuse) under the qualified key.
        return getColorForProfile(qualifiedKey);
    }

    /**
     * Pre-assigns colors for a known set of profiles.
     * Useful at startup when the expected profiles are already known,
     * to avoid color changes as new profiles start dynamically.
     *
     * @param profileNames the list of profile names to pre-assign (never null)
     */
    public synchronized void preAssignColors(List<String> profileNames) {
        Objects.requireNonNull(profileNames, "Profile names must not be null");
        for (String name : profileNames) {
            if (name != null && !name.isEmpty()) {
                getColorForProfile(name); // Triggers auto-assignment
            }
        }
    }

    /**
     * Resets the scheme to its initial empty state, clearing all custom and auto-assigned colors.
     */
    public synchronized void reset() {
        customColors.clear();
        assignedIndices.clear();
        for (int i = 0; i < usedSlots.size(); i++) {
            usedSlots.set(i, false);
        }
    }

    /**
     * Finds the next available palette slot for auto-assignment.
     * If all default slots are used, returns the next sequential index
     * (colors will cycle through the palette with a distinguishable suffix).
     */
    private int findNextAvailableSlot() {
        // First pass: find an unused slot in the default palette
        for (int i = 0; i < usedSlots.size(); i++) {
            if (!usedSlots.get(i)) {
                return i;
            }
        }

        // Second pass: all slots used, expand tracking and cycle
        int nextIndex = usedSlots.size();
        usedSlots.add(false);
        usedSlots.set(nextIndex % DEFAULT_PALETTE.length, true);
        return nextIndex;
    }

    @Override
    public synchronized String toString() {
        return "ConsoleColorScheme{" +
                "custom=" + customColors.size() +
                ", autoAssigned=" + assignedIndices.size() +
                '}';
    }
}