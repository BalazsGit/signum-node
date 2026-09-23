package application.module.browser.core;

import application.module.browser.util.OsArch;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private final Object lock = new Object();
    private volatile BrowserEngineState state = BrowserEngineState.IDLE;
    private volatile String failureReason;
    private CompletableFuture<BrowserEngineState> initFuture;
    private final List<StateListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService initExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "browser-cef-init");
        t.setDaemon(true);
        return t;
    });
    private final Supplier<Optional<Path>> jcefDirProvider;

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
            // 1. The native distribution must be in place (plan D1/D2).
            Path jcefDir = jcefDirProvider.get().orElseThrow(() -> new IllegalStateException(
                    "JCEF native distribution not found (expected " + JcefPathResolver.JCEF_FOLDER + "/"
                            + OsArch.current().key() + "/ next to the application). "
                            + "Run 'gradlew extractJcef' first."));
            // 2. Make the native libraries loadable from the distribution directory.
            //    (System.loadLibrary cannot see a runtime-extended java.library.path —
            //    see JcefNativeLoader; must run before the first org.cef.* use.)
            JcefNativeLoader.install(jcefDir, OsArch.current());
            // 3. CEF cache/log locations (plan D8).
            Files.createDirectories(browserConfDir.resolve("cef"));

            // 4. Create the app (loads the native libraries; the pre-init step runs
            //    on the EDT and requires it to be free) and the first client, which
            //    performs the real, blocking CEF initialization (2–10 s).
            CefSettings settings = JcefBootstrap.buildSettings(browserConfDir, jcefDir, OsArch.current());
            CefApp.getInstance(cefCommandlineArgs(), settings);
            CefApp.getInstance().createClient();

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
     * CEF command-line switches forwarded to the engine at start.
     * <p>
     * <b>Security baseline (plan D17):</b> the list is deliberately the empty
     * set. Verified (F0, jcef-api bytecode): java-cef appends <em>only</em> the
     * args we pass ({@code CefAppHandlerAdapter.onBeforeCommandLineProcessing}),
     * with no JVM-argument fallback — so with no args the engine's safe defaults
     * (renderer sandbox ON, no remote debugging) stay in force.
     * <p>
     * Forbidden switches in this project (code-review gate):
     * {@code --no-sandbox}, {@code --disable-web-security},
     * {@code --allow-running-insecure-content}, {@code --remote-debugging-port},
     * {@code --test-type} / {@code --enable-automation}.
     * (plan D13: the {@code --disable-gpu} auto-fallback is added in F1.)
     */
    private String[] cefCommandlineArgs() {
        return new String[0];
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
