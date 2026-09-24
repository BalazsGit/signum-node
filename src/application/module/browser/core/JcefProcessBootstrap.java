package application.module.browser.core;

import application.module.browser.engine.scheme.SignumSchemeRegistrar;
import application.module.browser.util.OsArch;
import application.utils.config.ModuleIds;
import org.cef.CefApp;
import org.cef.CefSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The JCEF process bootstrap (plan D4): the fast path the JCEF subprocesses
 * must take, plus the engine pre-initialization of the browser process.
 * <p>
 * <b>Why this exists:</b> JCEF's helper executable ({@code jcef_helper.exe})
 * re-executes the application's main class with extra CEF command-line
 * switches (notably {@code --type=renderer} / {@code --type=utility} /
 * {@code --type=gpu-process}). A subprocess that runs the full application
 * startup (node profiles, logging, kernel, GUI) delays — or breaks — CEF's
 * initialization: CEF requires that the process reaches
 * {@code CefInitialize} immediately, and that the custom-scheme handler
 * ({@link SignumSchemeRegistrar}) is installed identically in <em>all</em>
 * processes.
 * <p>
 * <b>Flow (verified against the pinned java-cef source, d3de827):</b>
 * <ol>
 *   <li>{@code CefApp.getInstance(args, settings)} is the lightweight
 *       pre-init: it loads the native libraries and runs
 *       {@code N_PreInitialize} on the EDT only.</li>
 *   <li>The first {@code createClient()} performs the real, blocking
 *       {@code CefInitialize}. In a subprocess CEF detects the {@code --type}
 *       switch, runs {@code CefExecuteProcess} internally and blocks until the
 *       process should exit — the subprocess must never run any application
 *       logic before (or after) that call.</li>
 * </ol>
 * <p>
 * <b>Browser process:</b> pre-initializes {@code CefApp} as early as possible
 * (native loader + app handler + settings) so the engine's
 * {@code createClient()} (and therefore the launch of the first CEF
 * subprocess) happens with the scheme handler already in place.
 * {@link BrowserEngine} keeps a defensive fallback for the case where this
 * pre-init was skipped or failed.
 */
public final class JcefProcessBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(JcefProcessBootstrap.class);

    /** The CEF command-line switch that marks a JCEF subprocess. */
    private static final String SUBPROCESS_TYPE_SWITCH = "--type=";

    private static volatile boolean preinitialized;
    private static volatile String[] mainArgs = new String[0];

    private JcefProcessBootstrap() {
        // utility class — never instantiated
    }

    /**
     * Whether this process is a JCEF subprocess (its command line carries the
     * CEF {@code --type=} switch) or the original browser process.
     *
     * @param args the process's main() arguments
     */
    static boolean isSubprocess(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (arg.startsWith(SUBPROCESS_TYPE_SWITCH)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the original main() arguments (kept for the engine's fallback
     *         initialization path)
     */
    public static String[] mainArgs() {
        return mainArgs;
    }

    /**
     * @return whether the browser-process pre-initialization succeeded (the
     *         {@code CefApp} instance exists with the handler and settings,
     *         awaiting the first {@code createClient()})
     */
    public static boolean isPreinitialized() {
        return preinitialized;
    }

    /**
     * Entry point, called from {@code Launcher.main()} before any application
     * logic (logging, kernel, GUI).
     * <ul>
     *   <li><b>Subprocess:</b> installs the JCEF native loader and app handler,
     *       then triggers the blocking CEF initialization; this method does not
     *       return — the process exits through CEF.</li>
     *   <li><b>Browser process:</b> pre-initializes the engine (fast), or skips
     *       pre-init in headless mode / when the native distribution is
     *       missing (the engine surfaces a clear FAILED reason at init time).</li>
     * </ul>
     *
     * @param args      the process's main() arguments
     * @param confPath  the application config directory ({@code conf/})
     * @param headless  whether the application runs headless (no browser engine)
     */
    public static void bootstrap(String[] args, Path confPath, boolean headless) {
        mainArgs = args != null ? args : new String[0];
        Optional<Path> jcefDir = JcefPathResolver.resolve();

        if (isSubprocess(mainArgs)) {
            runSubprocess(confPath, jcefDir);
            return; // unreachable: CEF exits the process
        }

        if (headless) {
            // Headless contract (AC-1): the CEF engine is never initialized.
            return;
        }
        if (jcefDir.isEmpty()) {
            logger.warn("JCEF native distribution not found — the browser engine will report FAILED "
                    + "at initialization (run 'gradlew extractJcef' first)");
            return;
        }
        preinitialize(confPath, jcefDir.get());
    }

    /**
     * Browser process: loads the native libraries, installs the app handler and
     * creates the {@code CefApp} instance (pre-init only — the blocking CEF
     * initialization is deferred to the first {@code createClient()}).
     */
    private static void preinitialize(Path confPath, Path jcefDir) {
        try {
            ensureCefDir(confPath);
            JcefNativeLoader.install(jcefDir, OsArch.current());
            if (!CefApp.startup(mainArgs)) {
                throw new IllegalStateException("JCEF startup failed (another JCEF process already running?)");
            }
            CefApp.addAppHandler(new SignumSchemeRegistrar());
            CefSettings settings = JcefBootstrap.buildSettings(
                    confPath.resolve(ModuleIds.BROWSER), jcefDir, OsArch.current());
            // Fast: loads the native libraries + N_PreInitialize on the EDT only.
            CefApp.getInstance(mainArgs, settings);
            preinitialized = true;
            logger.info("JCEF pre-initialized (scheme handler installed); the engine will "
                    + "run the blocking CEF initialization on the first createClient()");
        } catch (Throwable t) {
            // The engine's doInit() retries the remaining steps defensively.
            logger.error("JCEF pre-initialization failed — the browser engine will attempt "
                    + "a full initialization at start", t);
            preinitialized = false;
        }
    }

    /**
     * Subprocess: minimal bootstrap straight into the CEF process loop.
     * <p>
     * Logging is deliberately limited to stderr — the app's logging system is
     * not (and must not be) initialized in a subprocess.
     */
    private static void runSubprocess(Path confPath, Optional<Path> jcefDir) {
        try {
            if (jcefDir.isEmpty()) {
                System.err.println("CEF subprocess: JCEF native distribution not found — terminating");
                System.exit(1);
            }
            System.err.println("CEF subprocess detected — entering the CEF process loop");
            ensureCefDir(confPath);
            JcefNativeLoader.install(jcefDir.get(), OsArch.current());
            if (!CefApp.startup(mainArgs)) {
                System.err.println("CEF subprocess: JCEF startup failed — terminating");
                System.exit(1);
            }
            CefApp.addAppHandler(new SignumSchemeRegistrar());
            CefSettings settings = JcefBootstrap.buildSettings(
                    confPath.resolve(ModuleIds.BROWSER), jcefDir.get(), OsArch.current());
            CefApp.getInstance(mainArgs, settings);
            // The first createClient() runs CefInitialize; in a subprocess CEF
            // detects the --type switch, runs CefExecuteProcess internally and
            // blocks until the process should exit, then terminates it.
            CefApp.getInstance().createClient();
            System.exit(0); // defensive: only reached if the CEF loop returned
        } catch (Throwable t) {
            System.err.println("CEF subprocess failed: " + t);
            System.exit(1);
        }
    }

    private static void ensureCefDir(Path confPath) throws java.io.IOException {
        Files.createDirectories(confPath.resolve(ModuleIds.BROWSER).resolve("cef"));
    }
}