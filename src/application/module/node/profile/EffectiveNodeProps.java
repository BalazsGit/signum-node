package application.module.node.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import application.module.node.props.CaselessProperties;
import application.utils.config.ModuleIds;
import application.utils.config.PropertiesProfileLoader;
import application.utils.logging.NodeLogContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Headless SSOT for the effective node configuration of a named profile.
 * <p>
 * Implements the three-layer node configuration model:
 * <pre>
 *   L0: hardcoded defaults (Props.java) — NOT materialized here; every
 *       consumer falls back to {@code Prop.getDefaultValue()} when a key is
 *       absent, so a key present in L1 or L2 automatically overrides L0.
 *   L1: {confRoot}/node/profiles/default.properties — the user-editable L1
 *       layer; its ACTIVE lines override L0 for EVERY profile. It is
 *       auto-created at first start and never overwritten by the classpath
 *       sync (the fully commented node-default.properties file is the
 *       reference-only L0 documentation template).
 *   L2: {confRoot}/node/profiles/{profileName}.properties — the profile's
 *       own file (GUI-editable). Its active lines override L0 and L1.
 * </pre>
 * Per-key the layer order is L0 &larr; L1 &larr; L2 (the highest wins).
 * <p>
 * Both files are OPTIONAL: deleting either one degrades gracefully to the
 * layers below (worst case: pure hardcoded L0 defaults).
 * <p>
 * This helper is the single loading path for both the runtime
 * ({@code Signum.loadPropertiesForProfile}) and the GUI profile-editor
 * baselines ({@link #baselineValue}), so the two can never disagree about
 * the effective values.
 *
 * @see NodeProfileRepository
 * @see PropertiesProfileLoader
 */
public final class EffectiveNodeProps {

    private static final Logger LOGGER = LoggerFactory.getLogger(EffectiveNodeProps.class);

    private EffectiveNodeProps() {
        // static utility
    }

    /**
     * Loads the effective properties (L1 &larr; L2) for the given profile.
     * <p>
     * L1 (the user-editable default layer's ACTIVE lines) is loaded first, then
     * L2 (the profile file) on top of it, so L2 wins per key. Missing files,
     * missing directories and unreadable files are all tolerated — an
     * unreadable layer logs a warning and the lower layers stay effective.
     * <p>
     * A reserved profile name (e.g. {@code "default"}) is treated as
     * "L1 only": the profile file is not loaded on top of the layer, so
     * the reserved name can never double-load itself.
     *
     * @param confRoot    the base configuration root (e.g. {@code "./conf"},
     *                    {@code "conf/junit"} in tests), never null
     * @param profileName the profile name without extension, never null
     * @return the merged L1+L2 properties (case-insensitive), never null —
     *         empty when no layer contributed any active line
     */
    public static CaselessProperties loadEffective(String confRoot, String profileName) {
        CaselessProperties merged = new CaselessProperties();

        Path layerOne = PropertiesProfileLoader.resolveProfileFile(confRoot, ModuleIds.NODE,
                ModuleIds.CATEGORY_PROFILES, NodeProfile.DEFAULT_L1_FILENAME);
        loadLayer(merged, layerOne, "default", profileName);

        // The profile file (L2) is skipped for reserved names — for
        // "default" it would be the very same file as L1.
        if (!NodeProfile.RESERVED_PROFILE_NAMES.contains(profileName)) {
            Path layerTwo = PropertiesProfileLoader.resolveProfileFile(confRoot, ModuleIds.NODE,
                    ModuleIds.CATEGORY_PROFILES, profileName);
            loadLayer(merged, layerTwo, "profile", profileName);
        }

        NodeLogContext.runIn(ModuleIds.NODE, profileName,
                () -> LOGGER.info("Effective node properties for profile '{}' loaded: {} active line(s) "
                        + "(hardcoded Props.java defaults apply for every missing key)",
                        profileName, merged.size()));
        return merged;
    }

    /**
     * The effective baseline (L0 &larr; L1) of a single property for the
     * runtime configuration root — the {@code "Default"} value the GUI
     * profile editor must work with (edit / delete / reset / diff all run
     * against the effective L0&rarr;L1 value, so a profile file only needs to
     * store the non-L1 overrides). See
     * {@link #baselineValue(String, String, Object)}.
     *
     * @param key       the property name, never null
     * @param l0Default the hardcoded L0 value ({@code Prop.getDefaultValue()}),
     *                  may be null
     * @return the effective baseline as a String, or {@code ""} when both
     *         layers hold no value
     */
    public static String baselineValue(String key, Object l0Default) {
        return baselineValue(NodeProfile.CONF_ROOT, key, l0Default);
    }

    /**
     * The effective baseline (L0 &larr; L1) of a single property: the ACTIVE
     * line of the reserved default template
     * ({@code {confRoot}/node/profiles/node-default.properties}) when one
     * exists, otherwise the hardcoded L0 value in its String form.
     * <p>
     * A template line without a value ({@code key=}) is NOT an override —
     * the L0 default stays effective for that key. A missing or unreadable
     * template degrades gracefully to pure L0.
     * <p>
     * The template is re-read only when its fingerprint (path, modification
     * time, size) changes, so per-row / per-keystroke GUI lookups are cheap.
     *
     * @param confRoot  the base configuration root, never null
     * @param key       the property name, never null
     * @param l0Default the hardcoded L0 value ({@code Prop.getDefaultValue()}),
     *                  may be null
     * @return the effective baseline as a String, or {@code ""} when both
     *         layers hold no value
     */
    public static String baselineValue(String confRoot, String key, Object l0Default) {
        // getProperty — not get: CaselessProperties normalizes keys to lowercase
        // on write, so the lookup must go through the case-insensitive accessor.
        String value = loadDefaultTemplate(confRoot).getProperty(key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return l0Default == null ? "" : String.valueOf(l0Default);
    }

    // ── L1 template cache (per fingerprint: path + modification time + size) ─

    private static final Object L1_CACHE_LOCK = new Object();
    private static String l1CacheFingerprint;
    private static CaselessProperties l1Cache;

    /**
     * Loads the user-editable default layer (L1) of the node module
     * ({@code {confRoot}/node/profiles/default.properties}) from the given
     * conf root, transparently re-reading it only when its fingerprint
     * (absolute path, last-modified time, size) changes. A missing or
     * unreadable file yields an empty instance (pure L0 stays effective).
     *
     * @param confRoot the base configuration root, never null
     * @return the layer's active lines (case-insensitive), never null
     */
    static CaselessProperties loadDefaultTemplate(String confRoot) {
        Path file = PropertiesProfileLoader.resolveProfileFile(confRoot, ModuleIds.NODE,
                ModuleIds.CATEGORY_PROFILES, NodeProfile.DEFAULT_L1_FILENAME);
        String fingerprint;
        try {
            fingerprint = Files.isRegularFile(file)
                    ? file.toAbsolutePath() + "|" + Files.getLastModifiedTime(file).toMillis() + "|"
                            + Files.size(file)
                    : "absent";
        } catch (IOException e) {
            fingerprint = "unreadable:" + System.nanoTime();
        }
        synchronized (L1_CACHE_LOCK) {
            if (!fingerprint.equals(l1CacheFingerprint)) {
                CaselessProperties template = new CaselessProperties();
                if (Files.isRegularFile(file)) {
                    try (InputStream is = Files.newInputStream(file)) {
                        template.load(is);
                    } catch (IOException e) {
                        LOGGER.warn("Could not read the node default template {}",
                                file.toAbsolutePath(), e);
                    }
                }
                l1Cache = template;
                l1CacheFingerprint = fingerprint;
            }
            return l1Cache;
        }
    }

    /**
     * Loads one layer file into the target, tolerating a missing or
     * unreadable file (a missing file is the normal "layer absent" case).
     */
    private static void loadLayer(CaselessProperties target, Path file, String layerLabel,
            String profileName) {
        if (!Files.isRegularFile(file)) {
            return; // layer absent — the layers below stay effective
        }
        try (InputStream is = Files.newInputStream(file)) {
            target.load(is);
            NodeLogContext.runIn(ModuleIds.NODE, profileName,
                    () -> LOGGER.info("Loaded node {} layer for profile '{}' from {} ({} active line(s))",
                            layerLabel, profileName, file.toAbsolutePath(), target.size()));
        } catch (IOException e) {
            NodeLogContext.runIn(ModuleIds.NODE, profileName,
                    () -> LOGGER.warn("Could not read node {} layer for profile '{}' from {}",
                            layerLabel, profileName, file.toAbsolutePath(), e));
        }
    }
}