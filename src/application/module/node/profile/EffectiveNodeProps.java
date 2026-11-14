package application.module.node.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import application.module.node.props.CaselessProperties;
import application.utils.config.ModuleIds;
import application.utils.config.PropertiesProfileLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Headless SSOT for the effective node configuration of a named profile.
 * <p>
 * Implements the two-layer node configuration model:
 * <pre>
 *   L0: hardcoded defaults (Props.java) — NOT materialized here; every
 *       consumer falls back to {@code Prop.getDefaultValue()} when a key is
 *       absent, so a key present in L2 automatically overrides L0.
 *   L2: {confRoot}/node/profiles/{profileName}.properties — the profile's
 *       own file (GUI-editable). Its active lines override L0.
 * </pre>
 * Per-key the layer order is L0 &larr; L2 (the highest wins).
 * <p>
 * The profile file is OPTIONAL: deleting it degrades gracefully to pure
 * hardcoded L0 defaults.
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
     * Loads the effective properties (L2) for the given profile.
     * <p>
     * The profile file (L2) is loaded; missing files, missing directories
     * and unreadable files are all tolerated — an unreadable file logs a
     * warning and the hardcoded L0 defaults stay effective.
     *
     * @param confRoot    the base configuration root (e.g. {@code "./conf"},
     *                    {@code "conf/junit"} in tests), never null
     * @param profileName the profile name without extension, never null
     * @return the merged L2 properties (case-insensitive), never null —
     *         empty when no layer contributed any active line
     */
    public static CaselessProperties loadEffective(String confRoot, String profileName) {
        CaselessProperties merged = new CaselessProperties();

        Path profileFile = PropertiesProfileLoader.resolveProfileFile(confRoot, ModuleIds.NODE,
                ModuleIds.CATEGORY_PROFILES, profileName);
        loadLayer(merged, profileFile, "profile", profileName);

        LOGGER.info("Effective node properties for profile '{}' loaded: {} active line(s) "
                + "(hardcoded Props.java defaults apply for every missing key)",
                profileName, merged.size());
        return merged;
    }

    /**
     * The effective baseline (L0) of a single property for the
     * runtime configuration root — the {@code "Default"} value the GUI
     * profile editor must work with (edit / delete / reset / diff all run
     * against the effective L0 value, so a profile file only needs to
     * store the overrides).
     *
     * @param key       the property name, never null
     * @param l0Default the hardcoded L0 value ({@code Prop.getDefaultValue()}),
     *                  may be null
     * @return the effective baseline as a String, or {@code ""} when no
     *         value is available
     */
    public static String baselineValue(String key, Object l0Default) {
        return baselineValue(NodeProfile.CONF_ROOT, key, l0Default);
    }

    /**
     * The effective baseline (L0) of a single property: the hardcoded L0
     * value in its String form. In the two-layer architecture there is no
     * intermediate L1 layer, so the baseline is always the L0 default.
     *
     * @param confRoot  the base configuration root (unused in 2-layer, kept
     *                  for API compatibility), never null
     * @param key       the property name, never null
     * @param l0Default the hardcoded L0 value ({@code Prop.getDefaultValue()}),
     *                  may be null
     * @return the L0 default as a String, or {@code ""} when null
     */
    public static String baselineValue(String confRoot, String key, Object l0Default) {
        return l0Default == null ? "" : String.valueOf(l0Default);
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
            LOGGER.info("Loaded node {} layer for profile '{}' from {} ({} active line(s))",
                    layerLabel, profileName, file.toAbsolutePath(), target.size());
        } catch (IOException e) {
            LOGGER.warn("Could not read node {} layer for profile '{}' from {}",
                    layerLabel, profileName, file.toAbsolutePath(), e);
        }
    }
}