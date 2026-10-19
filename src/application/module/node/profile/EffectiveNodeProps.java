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
 *   L1: {confRoot}/node/profiles/node-default.properties — the reserved
 *       default template. It ships fully commented (documenting L0 values);
 *       a user may uncomment lines to override L0 for EVERY profile.
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
 * baselines, so the two can never disagree about the effective values.
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
     * L1 (the reserved default template's ACTIVE lines) is loaded first, then
     * L2 (the profile file) on top of it, so L2 wins per key. Missing files,
     * missing directories and unreadable files are all tolerated — an
     * unreadable layer logs a warning and the lower layers stay effective.
     * <p>
     * A reserved profile name (e.g. {@code "node-default"}) is treated as
     * "L1 only": the profile file is not loaded on top of the template, so
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
                ModuleIds.CATEGORY_PROFILES, NodeProfile.DEFAULT_PROFILE_FILENAME);
        loadLayer(merged, layerOne, "default-template", profileName);

        // The profile file (L2) is skipped for reserved names — for
        // "node-default" it would be the very same file as L1.
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