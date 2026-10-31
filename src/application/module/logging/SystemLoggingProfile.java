package application.module.logging;

import application.utils.config.ModuleIds;
import application.utils.logging.ModuleLoggingProfile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SYSTEM (JVM-global) logging profile — the L0 defaults of the single
 * global (system) {@code LoggerContext}, exposed as a first-class "System" tab
 * in the Logging module.
 * <p>
 * Unlike the node/database/browser module providers, the system context is a
 * <b>single</b> context: it has no per-profile L2 set and no node-assignments,
 * so its tab follows the node pattern with L0/L1 only and its "Assignments"
 * sub-tab is hidden (decision D5, {@code implementation/logging-implementation.md}
 * §5). Its on-disk layout is {@code conf/system/logging/} (L1
 * {@code default.properties}, read-only reference
 * {@code system-default.properties}) — the same {@code conf/{moduleId}/logging/}
 * contract every module provider uses, so the shared
 * {@code ModuleLoggingProfilePanel} operates on it unchanged.
 * </p>
 *
 * @see SystemLoggingProvider
 * @see ModuleLoggingProfile
 */
public class SystemLoggingProfile extends ModuleLoggingProfile {

    private static final Logger LOGGER = LoggerFactory.getLogger(SystemLoggingProfile.class);

    public static final String MODULE_ID = ModuleIds.SYSTEM;
    public static final String DISPLAY_NAME = "System";
    public static final String DESCRIPTION =
            "Controls the system-level (JVM-global) logging context: the global root level "
                    + "and the system console buffer. Per-module logger levels live in their "
                    + "own tabs (Node, Database, Browser).";

    /** L0 default of the global (system) context root level. */
    public static final String DEFAULT_ROOT_LEVEL = "INFO";
    /** L0 default of the system GUI console buffer size (lines); the SSOT for the System console (F4). */
    public static final int DEFAULT_CONSOLE_MAX_LINES = 1500;

    @Override
    public String getModuleId() {
        return MODULE_ID;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    /**
     * Returns the L0 defaults of the system (global) context: the global root
     * level and the system console buffer. Per-module class overrides are NOT
     * duplicated here — they are contributed by their own module providers
     * (Node, Database, Browser) and merged into the global context by the
     * resolver.
     */
    @Override
    public Map<String, String> getDefaults() {
        Map<String, String> defaults = new LinkedHashMap<>();
        // Global (system) context root level.
        defaults.put("level", DEFAULT_ROOT_LEVEL);
        // System GUI console buffer (lines) — the SSOT for the System console (F4).
        defaults.put("console.maxLines", String.valueOf(DEFAULT_CONSOLE_MAX_LINES));
        LOGGER.debug("SystemLoggingProfile defaults initialized with {} entries", defaults.size());
        return Collections.unmodifiableMap(defaults);
    }

    @Override
    public String toString() {
        return "SystemLoggingProfile{moduleId=" + getModuleId() + '}';
    }
}
