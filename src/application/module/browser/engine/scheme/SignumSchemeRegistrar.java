package application.module.browser.engine.scheme;

import application.module.browser.core.CefGuard;
import application.module.browser.core.JcefProcessBootstrap;
import org.cef.callback.CefCommandLine;
import org.cef.callback.CefSchemeRegistrar;
import org.cef.handler.CefAppHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The application-wide {@code CefAppHandler}: registers the custom
 * {@code signum://} scheme (plan D6) and — R2 — applies the CEF resilience
 * switches to the CEF command line.
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
 * (renderer sandbox ON, no remote debugging) stay in force. The only switches
 * this handler ever injects are the two {@link CefGuard} resilience switches
 * ({@code --disable-gpu}, {@code --single-process}), both of which are
 * <em>hardening</em> switches (software rendering / no subprocesses).
 * <p>
 * <b>R2 (why the switches are applied here):</b> the CEF command line is
 * built natively during the first {@code createClient()} through this
 * handler's {@link #onBeforeCommandLineProcessing} callback — the args
 * passed to {@code CefApp.startup}/{@code CefApp.getInstance} are <em>not</em>
 * forwarded to the CEF command line on Windows (verified against the pinned
 * java-cef bytecode: {@code CefApp.startup} ignores its args off-Linux, and
 * this registered app handler shadows the {@code CefApp} instance's own
 * args-carrying adapter).
 * <ul>
 *   <li><b>Browser process:</b> switches from the guard's pre-init decision
 *       ({@link JcefProcessBootstrap#activeSwitches()}).</li>
 *   <li><b>Subprocess:</b> CEF copies the browser's command line into every
 *       child process it launches, so the switches arrive on the
 *       re-executed {@code main()} arguments — they are re-derived from
 *       there ({@link CefGuard#resilienceSwitchesInArgs}).</li>
 * </ul>
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
    public void onBeforeCommandLineProcessing(String type, CefCommandLine commandLine) {
        // R2: apply the resilience switches to the CEF command line — this
        // callback (invoked by the native side right before CefInitialize) is
        // the only reliable injection point (see the class javadoc).
        List<String> switches = JcefProcessBootstrap.activeSwitches();
        if (switches.isEmpty()) {
            // A JCEF subprocess: the guard's decision lives in the browser
            // process — inherit the switches from the re-executed command
            // line (CEF propagates the browser's switches to its children).
            switches = CefGuard.resilienceSwitchesInArgs(JcefProcessBootstrap.mainArgs());
        }
        for (String sw : switches) {
            String name = CefGuard.switchName(sw);
            if (!commandLine.hasSwitch(name)) {
                commandLine.appendSwitch(name);
                logger.debug("Applied the CEF resilience switch '--{}' to the command line in the current process", name);
            }
        }
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