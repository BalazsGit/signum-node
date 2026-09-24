package application.module.browser.engine.scheme;

import org.cef.callback.CefSchemeRegistrar;
import org.cef.handler.CefAppHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The application-wide {@code CefAppHandler}: registers the custom
 * {@code signum://} scheme (plan D6).
 * <p>
 * CEF requires {@code CefApp::OnRegisterCustomSchemes} to be implemented in
 * <em>all</em> processes for a custom scheme, so this handler is installed
 * before the first {@code CefApp} use in <em>every</em> process — the browser
 * process at startup ({@code JcefProcessBootstrap}) and each JCEF subprocess
 * (which re-runs {@code main()}). Without the registration the scheme is a
 * non-standard scheme and CEF downgrades its responses (the main-frame
 * document is committed as {@code text/plain} instead of being parsed as
 * HTML).
 * <p>
 * <b>Security baseline (plan D17):</b> the adapter is constructed with an
 * explicitly <em>empty</em> argument list, so no JVM command-line arguments
 * are ever forwarded to the CEF command line — the engine's safe defaults
 * (renderer sandbox ON, no remote debugging) stay in force.
 * <p>
 * Forbidden switches in this project (code-review gate):
 * {@code --no-sandbox}, {@code --disable-web-security},
 * {@code --allow-running-insecure-content}, {@code --remote-debugging-port},
 * {@code --test-type} / {@code --enable-automation}.
 */
public final class SignumSchemeRegistrar extends CefAppHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(SignumSchemeRegistrar.class);

    public SignumSchemeRegistrar() {
        // Security baseline: never forward JVM arguments to the CEF command line.
        super(new String[0]);
    }

    @Override
    public void onRegisterCustomSchemes(CefSchemeRegistrar registrar) {
        // SCHEME_STANDARD: Blink treats signum:// as a first-class web scheme
        // (proper main-frame commits, relative URLs, mime handling). The other
        // flags stay off (F1: static pages, no cookies/fetch/service workers
        // on the internal scheme).
        boolean ok = registrar.addCustomScheme(BrowserSchemeHandler.SCHEME,
                /* isStandard */ true,
                /* isLocal */ false,
                /* isSecure */ false,
                /* isCorsEnabled */ false,
                /* isFetchEnabled */ false,
                /* isServiceWorkerEnabled */ false,
                /* isCacheControlled */ false);
        if (!ok) {
            logger.error("Failed to register the '{}' custom scheme — signum:// pages will not load",
                    BrowserSchemeHandler.SCHEME);
        } else {
            logger.debug("Registered the '{}' custom scheme (standard) in the current process",
                    BrowserSchemeHandler.SCHEME);
        }
    }
}