package application.module.browser.logging;

import application.utils.logging.ModuleLoggingProfile;
import application.utils.logging.ModuleLoggingProvider;

/**
 * The browser module's logging provider (plan F9, X3): registers the
 * {@link BrowserLoggingProfile} with the global logging registry so the
 * application's Logging panel can tune the browser's log output (module
 * {@code browser}).
 *
 * @see application.utils.logging.ModuleLoggingProvider
 */
public class BrowserLoggingProvider extends ModuleLoggingProvider {

    private final ModuleLoggingProfile profile = new BrowserLoggingProfile();

    @Override
    public ModuleLoggingProfile getProfile() {
        return profile;
    }
}