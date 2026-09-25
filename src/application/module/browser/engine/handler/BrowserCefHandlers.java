package application.module.browser.engine.handler;

import application.module.browser.config.BrowserSettings;
import application.module.browser.model.download.DownloadManager;
import application.module.browser.model.history.HistoryStore;
import application.module.browser.model.tab.TabController;
import org.cef.CefClient;

import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Builds and wires a tab's CEF handlers (plan §4.2 — Template Method style:
 * one place decides which handler goes with which tab).
 * <p>
 * <b>Threading wall:</b> JCEF delivers every callback on a non-EDT thread.
 * All handlers in this package pump their UI work through
 * {@link #runInEdt} — nothing CEF-derived may touch Swing directly.
 * <p>
 * F1 wires the load, display and lifespan handlers. F2 adds the request
 * handler (certificate errors S3, {@code file://} block N12); F3 the history
 * capture into the load handler (H1); F5 the download handler; F8 the JS
 * dialog handler.
 */
public final class BrowserCefHandlers {

    /** Repaint cadence for engine-driven tab animation (spinner, A1 birth). */
    public static final int REPAINT_PERIOD_MS = 100;

    private BrowserCefHandlers() {
        // utility class — never instantiated
    }

    /**
     * Attaches the F5 handler set to the client before its browser is created.
     *
     * @param settings  live browser settings (the request handler reads the
     *                  {@code file://} block switch on every navigation, N12)
     * @param history   the module's history store (visit capture, H1)
     * @param downloads the module's download manager (D1 target paths, D2 state)
     * @param activeDownloads the shared cancel hooks of the active downloads
     */
    public static void attach(CefClient client, String tabId, TabController controller,
                              Supplier<BrowserSettings> settings, HistoryStore history,
                              DownloadManager downloads, ActiveDownloadRegistry activeDownloads) {
        client.addLoadHandler(new CefLoadHandlerImpl(tabId, controller, history));
        client.addDisplayHandler(new CefDisplayHandlerImpl(tabId, controller));
        client.addLifeSpanHandler(new CefLifeSpanHandlerImpl(tabId, controller));
        client.addRequestHandler(new CefRequestHandlerImpl(tabId, controller, settings));
        client.addDownloadHandler(new CefDownloadHandlerImpl(downloads, activeDownloads));
    }

    /**
     * The single EDT-pump of the browser module (plan §4.2). Runs inline when
     * already on the EDT (keeps event order for same-thread callers).
     */
    static void runInEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /** @return a repeating EDT timer for animation repaints (shared with the tab strip). */
    public static Timer createRepaintTimer(Runnable repaintTask) {
        Timer timer = new Timer(REPAINT_PERIOD_MS, e -> repaintTask.run());
        timer.setRepeats(true);
        return timer;
    }
}
