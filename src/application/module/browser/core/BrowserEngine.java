package application.module.browser.core;

import application.module.browser.engine.scheme.BrowserSchemeHandler;
import application.module.browser.engine.scheme.SignumSchemeRegistrar;
import application.module.browser.engine.security.SandboxVerifier;
import application.module.browser.util.OsArch;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Singleton facade over the JCEF engine (plan D4).
 * <p>
 * Owns the engine lifecycle ({@link BrowserEngineState}) and creates
 * {@link CefClient}s once READY. CEF initialization is blocking (2–10 s), so
 * it runs on a dedicated daemon thread; {@link #shutdown()} waits for a
 * still-running initialization (bounded) so CEF is never torn down mid-init.
 * <p>
 * <b>Threading (plan §4.2):</b> JCEF's message loop is pumped on the EDT
 * (internal Swing timer), and CEF callbacks arrive on non-EDT threads — the
 * GUI layer must pump all UI work through {@code SwingUtilities.invokeLater}.
 * <p>
 * <b>Verified CEF 146.0.10 API (F0):</b>
 * {@code CefApp.getInstance(args, settings)} creates the app and loads the
 * native libraries (its pre-init step runs on the EDT); the first
 * {@code CefApp.createClient()} performs the real, blocking CEF
 * initialization; {@code CefApp.dispose()} shuts everything down.
 */
public final class BrowserEngine {

    private static final Logger logger = LoggerFactory.getLogger(BrowserEngine.class);

    /** Bounded wait for a still-running initialization during shutdown. */
    private static final long SHUTDOWN_INIT_TIMEOUT_SECONDS = 30;

    private static final BrowserEngine INSTANCE = new BrowserEngine();

    /**
     * Notified on every engine state change.
     * <p>
     * The callback arrives on the engine's init/shutdown thread — listeners
     * that touch the UI must hop to the EDT themselves.
     */
    public interface StateListener {
        void onStateChanged(BrowserEngineState state);
    }

    /**
     * The category of an initialization failure, so the GUI can react
     * differently (e.g. offer the JCEF auto-install for {@link #MISSING_JCEF}).
     */
    public enum FailureKind {
        /** The native distribution directory is absent — installable at runtime. */
        MISSING_JCEF,
        /** Anything else (CEF init failure, sandbox error, ...). */
        OTHER
    }

    private final Object lock = new Object();
    private volatile BrowserEngineState state = BrowserEngineState.IDLE;
    private volatile String failureReason;
    private volatile FailureKind failureKind = FailureKind.OTHER;
    private CompletableFuture<BrowserEngineState> initFuture;
    private final List<StateListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService initExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "browser-cef-init");
        t.setDaemon(true);
        return t;
    });
    private final Supplier<Optional<Path>> jcefDirProvider;

    /**
     * R2: invoked by the fatal watchdog when a fatal CEF event is observed
     * shortly after READY (the escalation is already persisted — the watchdog
     * only asks for the restart). Wired by the module from
     * {@code ModuleContext.requestRestart()}; {@code null} when unavailable
     * (tests) — then the watchdog only persists the escalation.
     */
    private volatile Runnable restartRequester;

    /** R2: whether the fatal watchdog is currently observing cef.log. */
    private volatile boolean fatalWatchdogRunning;

    private BrowserEngine() {
        this(JcefPathResolver::resolve);
    }

    /**
     * Testable constructor: the JCEF distribution lookup can be injected so the
     * state machine (including the FAILED path) is testable without native code.
     */
    BrowserEngine(Supplier<Optional<Path>> jcefDirProvider) {
        this.jcefDirProvider = jcefDirProvider;
    }

    /**
     * @return the shared production engine instance
     */
    public static BrowserEngine getInstance() {
        return INSTANCE;
    }

    public BrowserEngineState getState() {
        return state;
    }

    /**
     * @return a human-readable initialization failure cause, or null when not failed
     */
    public String getFailureReason() {
        return failureReason;
    }

    /**
     * @return the category of the last initialization failure (meaningful only
     *         while the state is {@link BrowserEngineState#FAILED})
     */
    public FailureKind getFailureKind() {
        return failureKind;
    }

    /**
     * F6 (C9, about page): the running CEF/Chromium version.
     *
     * @return the version string (newlines flattened), or "" when the engine
     *         is not started or the version is unavailable
     */
    public String cefVersion() {
        try {
            CefApp.CefVersion version = CefApp.getInstance() == null
                    ? null
                    : CefApp.getInstance().getVersion();
            return version == null ? "" : version.toString().replace('\n', ' ').trim();
        } catch (Throwable t) {
            return "";
        }
    }

    public void addStateListener(StateListener listener) {
        listeners.add(listener);
    }

    public void removeStateListener(StateListener listener) {
        listeners.remove(listener);
    }

    /**
     * Starts engine initialization asynchronously.
     * No-op unless the engine is {@link BrowserEngineState#IDLE} or
     * {@link BrowserEngineState#FAILED} (a failed engine can be retried).
     *
     * @param browserConfDir the module's runtime config directory ({@code conf/browser/})
     */
    public synchronized void init(Path browserConfDir) {
        if (state != BrowserEngineState.IDLE && state != BrowserEngineState.FAILED) {
            return;
        }
        failureReason = null;
        failureKind = FailureKind.OTHER;
        transition(BrowserEngineState.INITIALIZING);
        CompletableFuture<BrowserEngineState> init =
                CompletableFuture.supplyAsync(() -> doInit(browserConfDir), initExecutor);
        initFuture = init.thenApply(newState -> {
            transition(newState);
            return newState;
        });
    }

    /**
     * Shuts the engine down (synchronous; called from module stop / shutdown hooks).
     * Safe from any state: an idle engine just transitions, a running
     * initialization is awaited (bounded) before CEF is torn down.
     */
    public synchronized void shutdown() {
        if (state == BrowserEngineState.SHUTTING_DOWN || state == BrowserEngineState.SHUT_DOWN) {
            return;
        }
        // R2: the engine goes down — the fatal watchdog is no longer needed.
        fatalWatchdogRunning = false;
        transition(BrowserEngineState.SHUTTING_DOWN);

        CompletableFuture<BrowserEngineState> future = initFuture;
        if (future != null) {
            try {
                future.get(SHUTDOWN_INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                logger.warn("Browser engine init did not finish within {} s — interrupting it",
                        SHUTDOWN_INIT_TIMEOUT_SECONDS);
                initExecutor.shutdownNow();
            } catch (Exception e) {
                logger.debug("Browser engine init wait ended: {}", e.toString());
            }
        }

        if (state == BrowserEngineState.READY) {
            try {
                CefApp.getInstance().dispose();
            } catch (Throwable t) {
                logger.error("Error during CefApp dispose", t);
            }
        }
        transition(BrowserEngineState.SHUT_DOWN);
    }

    /**
     * Creates a new CEF client (one per browser tab, plan D7).
     *
     * @throws IllegalStateException unless the engine is {@link BrowserEngineState#READY}
     */
    public CefClient createClient() {
        if (state != BrowserEngineState.READY) {
            throw new IllegalStateException("Browser engine is not READY (state=" + state + ")");
        }
        return CefApp.getInstance().createClient();
    }

    private BrowserEngineState doInit(Path browserConfDir) {
        try {
            // R2 (plan §Risk): the engine may be disabled for this run — the
            // previous runs crashed fatally even in the most degraded mode
            // (see CefGuard). Fail fast with an actionable reason: the browser
            // tab shows the error while the rest of the application keeps working.
            if (JcefProcessBootstrap.isEngineDisabled()) {
                failureReason = "The browser engine is disabled on this machine after repeated fatal CEF "
                        + "crashes (the application keeps running, only the browser is unavailable). "
                        + "Re-enable it by setting \"cefDisabled\": false in conf/browser/settings.json "
                        + "and restarting.";
                logger.error("Browser engine initialization failed: {}", failureReason);
                return BrowserEngineState.FAILED;
            }
            // 1. The native distribution must be in place (plan D1/D2).
            Optional<Path> jcefOpt = jcefDirProvider.get();
            if (jcefOpt.isEmpty()) {
                // Typed: the GUI can offer the runtime auto-install (JcefProvisioner).
                failureKind = FailureKind.MISSING_JCEF;
                failureReason = "JCEF native distribution not found (expected "
                        + JcefPathResolver.JCEF_FOLDER + "/" + OsArch.current().key()
                        + "/ next to the application). Install it with the browser's "
                        + "install dialog, or run 'gradlew extractJcef' first.";
                logger.error("Browser engine initialization failed: {}", failureReason);
                return BrowserEngineState.FAILED;
            }
            Path jcefDir = jcefOpt.get();
            // 2. CEF init (plan D4). The JCEF native loader, the app handler
            //    (which registers the signum:// scheme in ALL processes — CEF
            //    requires that for a custom scheme) and the CefApp instance were
            //    set up as early as possible at startup by
            //    {@link JcefProcessBootstrap}. If that pre-init was skipped
            //    (headless) or failed, complete it here — still before the first
            //    createClient(), which performs the real, blocking CEF
            //    initialization (2–10 s).
            if (!JcefProcessBootstrap.isPreinitialized()) {
                JcefNativeLoader.install(jcefDir, OsArch.current());
                if (!CefApp.startup(JcefProcessBootstrap.mainArgs())) {
                    throw new IllegalStateException("JCEF startup failed (another JCEF process already running?)");
                }
                CefApp.addAppHandler(new SignumSchemeRegistrar());
                CefSettings settings = JcefBootstrap.buildSettings(browserConfDir, jcefDir, OsArch.current());
                CefApp.getInstance(JcefProcessBootstrap.mainArgs(), settings);
            }
            CefApp.getInstance().createClient();

            // Built-in signum:// pages (plan D6): registered once, after init.
            CefApp.getInstance().registerSchemeHandlerFactory(
                    BrowserSchemeHandler.SCHEME, "", BrowserSchemeHandler.factory());
            // S7 (D17): verify the renderer sandbox is actually in effect (log-only, delayed).
            SandboxVerifier.verifyAfterReady();

            // R2 (plan §Risk): a fatal CEF event observed after init (e.g. the
            // GPU process dies on this machine) will kill the JVM within
            // seconds — escalate the persisted settings and request a restart
            // so the next start recovers automatically.
            startFatalWatchdog(browserConfDir);

            CefApp.CefVersion version = CefApp.getInstance().getVersion();
            logger.info("Browser engine ready (JCEF {})",
                    version != null ? version.toString().replace('\n', ' ') : "unknown version");
            return BrowserEngineState.READY;
        } catch (Throwable t) {
            logger.error("Browser engine initialization failed", t);
            failureReason = t.getMessage() != null ? t.getMessage() : t.toString();
            return BrowserEngineState.FAILED;
        }
    }

    /**
     * R2: registers the restart callback used by the fatal watchdog.
     *
     * @param restartRequester the restart action (e.g.
     *                         {@code ModuleContext::requestRestart}), or
     *                         {@code null} to clear it
     */
    public void setRestartRequester(Runnable restartRequester) {
        this.restartRequester = restartRequester;
    }

    /**
     * R2 (plan §Risk): after the engine reports READY, observes the CEF log for
     * a short window. A fatal event seen there (e.g. {@code GPU process launch
     * failed} → {@code FATAL: ... Goodbye.}) means the JVM is about to be
     * killed by CEF itself — there is nothing to catch that, so the watchdog
     * persists a resilience escalation (the next start recovers with the new
     * settings/switches) and requests an application restart. Best effort: if
     * CEF wins the race, the start-time guard (CefGuard) still recovers on the
     * next manual start.
     */
    private void startFatalWatchdog(Path browserConfDir) {
        if (fatalWatchdogRunning) {
            return;
        }
        fatalWatchdogRunning = true;
        Path cefLog = CefGuard.cefLog(browserConfDir.getParent());
        final long baseline = cefLogSize(cefLog);
        Thread watchdog = new Thread(() -> {
            try {
                // ~20 s window in 500 ms steps — CEF aborts a process whose GPU
                // process failed six launches (~1 s), so this usually wins the race.
                for (int i = 0; i < 40 && state == BrowserEngineState.READY && fatalWatchdogRunning; i++) {
                    TimeUnit.MILLISECONDS.sleep(500);
                    String region = CefGuard.readRegion(cefLog, baseline);
                    if (region == null || !CefGuard.indicatesFatal(region)) {
                        continue;
                    }
                    logger.error("A fatal CEF event was detected in {} right after the engine became READY — "
                            + "the process is likely about to crash; escalating the browser settings and "
                            + "requesting a restart...", cefLog);
                    try {
                        CefGuard.escalateAfterFatal(browserConfDir);
                    } catch (RuntimeException e) {
                        logger.error("Could not persist the CEF resilience escalation", e);
                    }
                    Runnable restart = restartRequester;
                    if (restart != null) {
                        try {
                            restart.run();
                        } catch (RuntimeException e) {
                            logger.error("The restart request failed", e);
                        }
                    } else {
                        logger.error("No restart requester is available — the application may crash now; the "
                                + "next start will recover automatically with the escalated settings");
                    }
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                fatalWatchdogRunning = false;
            }
        }, "cef-fatal-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static long cefLogSize(Path log) {
        try {
            return Files.isRegularFile(log) ? Files.size(log) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private void transition(BrowserEngineState next) {
        BrowserEngineState previous;
        synchronized (lock) {
            previous = state;
            if (previous == next) {
                return;
            }
            state = next;
        }
        logger.info("Browser engine state: {} -> {}", previous, next);
        for (StateListener listener : listeners) {
            try {
                listener.onStateChanged(next);
            } catch (Exception e) {
                logger.error("Browser engine state listener failed", e);
            }
        }
    }
}
