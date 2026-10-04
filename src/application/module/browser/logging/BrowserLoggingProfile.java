package application.module.browser.logging;

import application.utils.config.ModuleIds;
import application.utils.logging.ModuleLoggingProfile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Browser module logging profile (plan F9, X3): the module's logger keys and
 * defaults for the composite logging infrastructure.
 * <p>
 * Config files are resolved from {@code conf/browser/logging/*.properties}.
 *
 * @see application.utils.logging.ModuleLoggingProvider
 * @see BrowserLoggingProvider
 */
public class BrowserLoggingProfile extends ModuleLoggingProfile {

    private static final Logger LOGGER = LoggerFactory.getLogger(BrowserLoggingProfile.class);

    public static final String MODULE_ID = ModuleIds.BROWSER;
    public static final String DISPLAY_NAME = "Browser";
    public static final String DESCRIPTION = "Controls logging for the browser module: the "
            + "JCEF engine lifecycle, tab/session management, downloads and the signum:// pages.";

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
    public String toString() {
        return "BrowserLoggingProfile{moduleId=" + getModuleId() + '}';
    }
}