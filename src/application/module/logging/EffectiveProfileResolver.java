package application.module.logging;

import application.utils.config.ConfigPaths;
import application.utils.config.ModuleIds;
import application.utils.config.PropertiesProfileLoader;
import application.utils.io.PathUtils;
import application.utils.logging.LoggingModuleRegistry;
import application.utils.logging.LoggingProfileManager;
import application.utils.logging.ModuleLoggingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Read-only resolver that explains the <b>effective</b> logging composition.
 * <p>
 * For a given profile name and per-module preset selections, it computes the same
 * effective {@link Properties} the node would run (via {@link LoggingProfileManager})
 * and additionally labels each logger key with the <b>source layer</b> that dominates
 * it ({@link Source}). This backs the "effective view" read-only section of the
 * planned Logging module UI (plan §2.2 / §2.3).
 * </p>
 *
 * <h3>Source precedence (highest → lowest)</h3>
 * {@link Source#RUNTIME_OVERRIDES} → {@link Source#ON_DISK} → {@link Source#PRESET}
 * → {@link Source#MODULE_DEFAULT} → {@link Source#GLOBAL_BASE}.
 *
 * <p>
 * The effective <b>value</b> is always taken from {@link LoggingProfileManager} (the
 * authoritative composition), so the view is guaranteed to match what actually runs;
 * the source label is the highest-priority layer that has an opinion on the key.
 * </p>
 *
 * <p>Headless-safe (no Swing).</p>
 *
 * @see LoggingProfileManager
 * @see LoggingAssignmentStore
 */
public final class EffectiveProfileResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(EffectiveProfileResolver.class);

    private final String confRoot;

    /**
     * Creates a resolver bound to the standard runtime conf root.
     */
    public EffectiveProfileResolver() {
        this(PropertiesProfileLoader.DEFAULT_CONF_ROOT);
    }

    /**
     * Creates a resolver bound to a specific configuration root.
     *
     * @param confRoot Base configuration root (e.g. {@code ./conf}); must not be null
     */
    public EffectiveProfileResolver(String confRoot) {
        if (confRoot == null || confRoot.isBlank()) {
            throw new IllegalArgumentException("confRoot must not be null or blank");
        }
        this.confRoot = confRoot;
    }

    /**
     * The layer that determines a logger key's effective value.
     */
    public enum Source {
        /** Global base defaults from {@code conf/logging-default.properties}. */
        GLOBAL_BASE("base"),
        /** A module's built-in defaults ({@code getDefaults()}). */
        MODULE_DEFAULT("default"),
        /** A selected preset override for a module. */
        PRESET("preset"),
        /** On-disk profile file {@code conf/{module}/logging/{profile}.properties}. */
        ON_DISK("disk"),
        /** Runtime overrides supplied by the caller. */
        RUNTIME_OVERRIDES("override");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * A single effective logger key with its resolved value and the source layer that
     * dominates it.
     *
     * @param key    logger key (e.g. {@code node.level})
     * @param value  effective value (e.g. {@code INFO})
     * @param source the highest-priority layer defining the key
     */
    public record EffectiveKey(String key, String value, Source source) {
    }

    /**
     * Computes the effective composition for a profile name plus per-module presets and
     * runtime overrides, labeling each key with its source layer.
     *
     * @param profileName profile name loaded from each module's logging directory
     * @param presetMap   optional module id → preset name (null treated as empty)
     * @param overrides   optional runtime key → value overrides (null treated as empty)
     * @return Sorted list of {@link EffectiveKey} (never null)
     */
    public List<EffectiveKey> previewComposition(
            String profileName, Map<String, String> presetMap, Map<String, String> overrides) {

        Map<String, String> safePresets = presetMap != null ? presetMap : Map.of();
        Map<String, String> safeOverrides = overrides != null ? overrides : Map.of();

        Properties effective = new LoggingProfileManager()
                .createCompositeProfile(confRoot, profileName, safePresets, safeOverrides);

        // Precompute the set of keys each layer has an opinion on.
        Set<String> baseKeys = baseDefaultKeys();
        Set<String> defaultKeys = providerKeys(provider -> provider.getProfile().getDefaults().keySet());
        Set<String> presetKeys = providerPresetKeys(safePresets);
        Set<String> onDiskKeys = providerKeys(provider ->
                PropertiesProfileLoader.loadProfile(confRoot, provider.getModuleId(), ModuleIds.CATEGORY_LOGGING, profileName).stringPropertyNames());

        List<String> keys = new ArrayList<>();
        for (String key : effective.stringPropertyNames()) {
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }
        keys.sort(String::compareTo);

        List<EffectiveKey> result = new ArrayList<>(keys.size());
        for (String key : keys) {
            Source source = sourceFor(key, baseKeys, defaultKeys, presetKeys, onDiskKeys, safeOverrides);
            result.add(new EffectiveKey(key, effective.getProperty(key), source));
        }
        LOGGER.debug("previewComposition({}) produced {} effective keys", profileName, result.size());
        return List.copyOf(result);
    }

    /**
     * Convenience: preview with no presets and no runtime overrides.
     *
     * @param profileName profile name
     * @return Sorted list of {@link EffectiveKey} (never null)
     */
    public List<EffectiveKey> previewComposition(String profileName) {
        return previewComposition(profileName, Map.of(), Map.of());
    }

    /**
     * Reverse lookup: which node profiles reference the given logging profile.
     * Delegates to {@link LoggingAssignmentStore} (the single source of truth).
     *
     * @param loggingProfileName logging profile name
     * @return Sorted list of node profile names (never null)
     */
    public List<String> findReferencingProfiles(String loggingProfileName) {
        return new LoggingAssignmentStore(confRoot).findReferencingProfiles(loggingProfileName);
    }

    // ── Private helpers ────────────────────────────────────────────────

    private Source sourceFor(String key, Set<String> base, Set<String> defaults,
            Set<String> presets, Set<String> onDisk, Map<String, String> overrides) {
        if (overrides.containsKey(key)) {
            return Source.RUNTIME_OVERRIDES;
        }
        if (onDisk.contains(key)) {
            return Source.ON_DISK;
        }
        if (presets.contains(key)) {
            return Source.PRESET;
        }
        if (defaults.contains(key)) {
            return Source.MODULE_DEFAULT;
        }
        // Defensive fallback: every key in the composite comes from base or a module.
        return base.contains(key) ? Source.GLOBAL_BASE : Source.MODULE_DEFAULT;
    }

    private Set<String> baseDefaultKeys() {
        Path baseFile = PathUtils.resolvePath(confRoot)
                .resolve(LoggingProfileManager.GLOBAL_BASE_PROFILE + ".properties");
        if (!Files.exists(baseFile)) {
            return Set.of();
        }
        try (InputStream is = Files.newInputStream(baseFile);
                InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            Properties props = new Properties();
            props.load(reader);
            return new HashSet<>(props.stringPropertyNames());
        } catch (Exception e) {
            LOGGER.debug("Failed to read base defaults for source labeling: {}", e.getMessage());
            return Set.of();
        }
    }

    private interface KeyProvider {
        Set<String> keys(ModuleLoggingProvider provider);
    }

    private Set<String> providerKeys(KeyProvider accessor) {
        Set<String> keys = new HashSet<>();
        for (ModuleLoggingProvider provider : LoggingModuleRegistry.getInstance().getAllProviders()) {
            Set<String> ks = accessor.keys(provider);
            if (ks != null) {
                keys.addAll(ks);
            }
        }
        return keys;
    }

    private Set<String> providerPresetKeys(Map<String, String> presetMap) {
        Set<String> keys = new HashSet<>();
        for (ModuleLoggingProvider provider : LoggingModuleRegistry.getInstance().getAllProviders()) {
            String preset = presetMap.get(provider.getModuleId());
            if (preset == null) {
                continue;
            }
            Map<String, String> overrides = provider.getProfile().getPresetOverrides().get(preset);
            if (overrides != null) {
                keys.addAll(overrides.keySet());
            }
        }
        return keys;
    }
}