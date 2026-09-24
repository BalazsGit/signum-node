package application.module.browser.core;

import application.module.browser.util.OsArch;
import org.cef.CefSettings;

import java.nio.file.Path;

/**
 * Assembles the {@link CefSettings} passed to the JCEF engine at start (plan D4).
 * <p>
 * Verified against the CEF 146.0.10 API (F0): the settings fields are
 * snake_case, and windowed rendering is the default
 * ({@code windowless_rendering_enabled = false} — the browser is embedded as an
 * AWT component, no offscreen rendering).
 */
public final class JcefBootstrap {

    private JcefBootstrap() {
        // utility class — never instantiated
    }

    /**
     * Builds the engine settings.
     *
     * @param browserConfDir the module's runtime config directory ({@code conf/browser/},
     *                       plan D8): CEF cache, cookies and the log live under {@code cef/}
     * @param jcefDir        the JCEF native distribution directory (plan D2)
     * @param osArch         the target platform
     */
    public static CefSettings buildSettings(Path browserConfDir, Path jcefDir, OsArch osArch) {
        CefSettings settings = new CefSettings();

        // Windowed rendering: the engine is embedded as an AWT component (plan D1).
        settings.windowless_rendering_enabled = false;

        // Security baseline (plan D17): explicit, so it is never relaxed by accident.
        // Remote debugging (CDP) stays closed, and the CEF command line stays the
        // engine's safe default — the app handler (SignumSchemeRegistrar)
        // forwards no JVM arguments to it.
        settings.remote_debugging_port = 0;
        settings.command_line_args_disabled = false;

        // CEF cache/cookies + engine log under conf/browser/cef (plan D8).
        settings.cache_path = browserConfDir.resolve("cef").toString();
        settings.log_file = browserConfDir.resolve("cef").resolve("cef.log").toString();
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_INFO;

        // Extensions are loaded from <root_cache_path>/Extensions at CefApp start only
        // (plan D10) — root_cache_path therefore points at conf/browser itself.
        settings.root_cache_path = browserConfDir.toString();

        // CEF's default subprocess location (next to the browser executable) does not
        // apply to a JVM — the helper lives in the native distribution, set it explicitly.
        settings.browser_subprocess_path = jcefDir.resolve(subprocessName(osArch.os())).toString();

        return settings;
    }

    /**
     * Name of the CEF subprocess executable inside the native distribution.
     */
    static String subprocessName(OsArch.Os os) {
        return switch (os) {
            // Verified against the jcef-natives-windows-amd64 layout (F0).
            case WINDOWS -> "jcef_helper.exe";
            // Upstream JCEF distribution convention; verify on the platform at first use.
            case LINUX -> "cef_subprocess";
            case MACOSX -> "JCEF Helper.app/Contents/MacOS/JCEF Helper";
        };
    }
}
