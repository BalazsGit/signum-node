package application.module.browser.logging;

import application.utils.config.ModuleIds;
import application.utils.logging.ModuleLoggingProfile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Browser module logging profile (plan F9, X3): the module's logger keys,
 * defaults and presets for the composite logging infrastructure.
 * <p>
 * Config files are resolved from {@code conf/browser/logging/*.properties}.
 *
 * <h3>Built-in Presets</h3>
 * <ul>
 *   <li><b>minimal</b> — module at WARNING, CEF off (production default)</li>
 *   <li><b>standard</b> — module at INFO, CEF at WARNING</li>
 *   <li><b>verbose</b> — module at DEBUG, CEF at INFO</li>
 *   <li><b>debug</b>   — maximum visibility (module + CEF at TRACE/FINE)</li>
 * </ul>
 *
 * @see application.utils.logging.ModuleLoggingProvider
 * @see BrowserLoggingProvider
 */
public class BrowserLoggingProfile extends ModuleLoggingProfile {

    private static final Logger LOGGER = LoggerFactory.getLogger(BrowserLoggingProfile.class);

    public static final String MODULE_ID = ModuleIds.BROWSER;
    public static final String DISPLAY_NAME = "Web3 Browser";
    public static final String DESCRIPTION = "Controls logging for the browser module: the "
            + "JCEF engine lifecycle, tab/session management, downloads and the signum:// pages.";

    public static final String PRESET_MINIMAL = "minimal";
    public static final String PRESET_STANDARD = "standard";
    public static final String PRESET_VERBOSE = "verbose";
    public static final String PRESET_DEBUG = "debug";

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

    /** Default logger levels: the module at INFO, the engine at WARNING. */
    @Override
    public Map<String, String> getDefaults() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("application.module.browser.level", "INFO");
        defaults.put("org.cef.level", "WARNING");
        LOGGER.debug("BrowserLoggingProfile defaults initialized with {} entries", defaults.size());
        return Collections.unmodifiableMap(defaults);
    }

    @Override
    public Map<String, Map<String, String>> getPresetOverrides() {
        Map<String, Map<String, String>> presets = new LinkedHashMap<>();

        Map<String, String> minimal = new LinkedHashMap<>();
        minimal.put("application.module.browser.level", "WARNING");
        minimal.put("org.cef.level", "OFF");
        presets.put(PRESET_MINIMAL, Collections.unmodifiableMap(minimal));

        Map<String, String> standard = new LinkedHashMap<>();
        standard.put("application.module.browser.level", "INFO");
        standard.put("org.cef.level", "WARNING");
        presets.put(PRESET_STANDARD, Collections.unmodifiableMap(standard));

        Map<String, String> verbose = new LinkedHashMap<>();
        verbose.put("application.module.browser.level", "DEBUG");
        verbose.put("org.cef.level", "INFO");
        presets.put(PRESET_VERBOSE, Collections.unmodifiableMap(verbose));

        Map<String, String> debug = new LinkedHashMap<>();
        debug.put("application.module.browser.level", "TRACE");
        debug.put("org.cef.level", "DEBUG");
        presets.put(PRESET_DEBUG, Collections.unmodifiableMap(debug));

        return Collections.unmodifiableMap(presets);
    }

    @Override
    public String toString() {
        return "BrowserLoggingProfile{presets=" + getPresetOverrides().keySet() + '}';
    }
}