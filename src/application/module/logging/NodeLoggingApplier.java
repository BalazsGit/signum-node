package application.module.logging;

import application.utils.config.PropertiesProfileLoader;
import application.utils.logging.LoggingModuleRegistry;
import application.utils.logging.ModuleLoggingProfile;
import application.utils.logging.ModuleLoggingProvider;
import application.utils.logging.NodeLoggerRegistry;
import application.utils.logging.ProfileLogger;
import application.utils.logging.event.LogLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Applies the <b>per-node</b> logging level of a node profile at (re)start.
 * <p>
 * For a given node profile this applier reads the node profile's per-module assignment
 * (SSOT: {@code conf/node/profiles.json} via {@link LoggingAssignmentStore}), resolves the
 * <b>effective</b> profile for the target module (guaranteeing a fallback to the reserved
 * {@code logging-default} when the assignment is invalid/missing), reads the effective
 * {@code <module>.level} (on-disk profile → preset → hardcoded module default), and sets it
 * as the minimum level of that node profile's {@link ProfileLogger}.
 * </p>
 * <p>
 * The applier is <b>defensive</b>: it never throws. A failure to resolve or apply simply
 * leaves the profile logger at its current level and is logged as a warning, so node startup
 * is never broken by a logging misconfiguration.
 * </p>
 * <p>
 * Headless-safe (no Swing). Module-agnostic: any module with a registered logging provider
 * and a {@code <module>.level} key can be applied with the same call.
 * </p>
 *
 * @see LoggingAssignmentStore
 * @see LoggingProfileRepository
 */
public final class NodeLoggingApplier {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeLoggingApplier.class);

    private NodeLoggingApplier() {
        // Utility class
    }

    /**
     * Applies the per-node logging level for the given node profile and module using the
     * standard runtime configuration root.
     *
     * @param nodeProfile the node profile name (e.g. {@code "mainnet"})
     * @param moduleId    the module id (e.g. {@code "node"})
     */
    public static void applyForNodeProfile(String nodeProfile, String moduleId) {
        applyForNodeProfile(PropertiesProfileLoader.DEFAULT_CONF_ROOT, nodeProfile, moduleId);
    }

    /**
     * Applies the per-node logging level for the given node profile and module.
     *
     * @param confRoot    the base configuration root (e.g. {@code ./conf})
     * @param nodeProfile the node profile name (e.g. {@code "mainnet"})
     * @param moduleId    the module id (e.g. {@code "node"})
     */
    public static void applyForNodeProfile(String confRoot, String nodeProfile, String moduleId) {
        if (nodeProfile == null || nodeProfile.isBlank() || moduleId == null || moduleId.isBlank()) {
            return;
        }
        try {
            String effective = new LoggingAssignmentStore(confRoot).resolveEffectiveForModule(nodeProfile, moduleId);
            if (effective == null) {
                return;
            }
            String levelStr = resolveLevel(confRoot, effective, moduleId);
            if (levelStr == null || levelStr.isBlank()) {
                LOGGER.debug("No '{}' level found for effective profile '{}' of module '{}'",
                        moduleId, effective, moduleId);
                return;
            }
            LogLevel level = parseLogLevel(levelStr);
            ProfileLogger logger = NodeLoggerRegistry.getOrCreate(moduleId, nodeProfile);
            logger.setLogLevel(level);
            LOGGER.info("Applied logging level {} (effective profile '{}') to node profile '{}' module '{}'",
                    level, effective, nodeProfile, moduleId);
        } catch (Exception e) {
            LOGGER.warn("Failed to apply per-node logging for profile '{}' module '{}': {}",
                    nodeProfile, moduleId, e.getMessage());
        }
    }

    /**
     * Resolves the effective {@code <module>.level} value for a profile, in precedence order:
     * on-disk profile file → provider preset override → hardcoded module default.
     *
     * @param confRoot    base configuration root
     * @param profileName the (already-fallback-resolved) profile name
     * @param moduleId    the module id
     * @return the level value (e.g. {@code "FINE"}), or null if it cannot be resolved
     */
    static String resolveLevel(String confRoot, String profileName, String moduleId) {
        String key = moduleId + ".level";
        Path file = new LoggingProfileRepository(confRoot).getProfileFile(moduleId, profileName);
        if (Files.exists(file)) {
            try (InputStream is = Files.newInputStream(file);
                 InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                Properties props = new Properties();
                props.load(reader);
                String value = props.getProperty(key);
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to read profile file {}: {}", file, e.getMessage());
            }
        }
        ModuleLoggingProvider provider = LoggingModuleRegistry.getInstance().getProvider(moduleId);
        if (provider == null) {
            return null;
        }
        ModuleLoggingProfile profile = provider.getProfile();
        Map<String, String> preset = profile.getPresetOverrides().get(profileName);
        if (preset != null) {
            String value = preset.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return profile.getDefaults().get(key);
    }

    /**
     * Maps a JUL level name (case-insensitive) to the unified {@link LogLevel}.
     * <p>
     * {@code ALL}/{@code FINEST} → {@code TRACE}; {@code FINER}/{@code FINE} → {@code DEBUG};
     * {@code CONFIG}/{@code INFO} → {@code INFO}; {@code WARNING} → {@code WARN};
     * {@code SEVERE} → {@code ERROR}; {@code OFF} → {@code OFF}. Unrecognised values map to
     * {@code INFO} (the safe default).
     * </p>
     *
     * @param name the JUL level name
     * @return the mapped {@link LogLevel}
     */
    static LogLevel parseLogLevel(String name) {
        if (name == null) {
            return LogLevel.INFO;
        }
        switch (name.trim().toUpperCase()) {
            case "ALL":
            case "FINEST":
                return LogLevel.TRACE;
            case "FINER":
            case "FINE":
                return LogLevel.DEBUG;
            case "CONFIG":
            case "INFO":
                return LogLevel.INFO;
            case "WARNING":
            case "WARN":
                return LogLevel.WARN;
            case "SEVERE":
            case "ERROR":
                return LogLevel.ERROR;
            case "OFF":
                return LogLevel.OFF;
            default:
                return LogLevel.INFO;
        }
    }
}