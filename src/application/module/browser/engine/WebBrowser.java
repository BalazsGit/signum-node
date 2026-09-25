package application.module.browser.engine;

import application.module.browser.config.BrowserSettings;
import application.module.browser.engine.handler.ActiveDownloadRegistry;
import application.module.browser.engine.handler.BrowserCefHandlers;
import application.module.browser.model.download.DownloadManager;
import application.module.browser.model.history.HistoryStore;
import application.module.browser.model.tab.TabController;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;

import java.awt.Component;
import java.awt.Dimension;
import java.util.function.Supplier;

/**
 * One tab's CEF browser: owns a single {@link CefClient} + {@link CefBrowser}
 * (plan §4.1 — CEF objects live here, never in the tab model).
 * <p>
 * JCEF is windowed (D1): the browser is an AWT component that must be created
 * and disposed on the EDT. Disposal removes the component from its parent,
 * force-closes the browser and releases the client.
 */
public final class WebBrowser {

    private static final Dimension PREFERRED_SIZE = new Dimension(640, 480);

    private final CefClient client;
    private final CefBrowser browser;
    private final Component uiComponent;

    WebBrowser(CefClient client, String tabId, TabController controller, String initialUrl,
               Supplier<BrowserSettings> settings, HistoryStore history,
               DownloadManager downloads, ActiveDownloadRegistry activeDownloads) {
        this.client = client;
        BrowserCefHandlers.attach(client, tabId, controller, settings, history,
                downloads, activeDownloads);
        this.browser = client.createBrowser(initialUrl, false, true); // offscreen=false (D1), focusable
        this.browser.createImmediately();
        this.uiComponent = this.browser.getUIComponent();
        this.uiComponent.setPreferredSize(PREFERRED_SIZE);
    }

    /** @return the persistent AWT component shown in the content panel. */
    public Component getUiComponent() {
        return uiComponent;
    }

    public String getUrl() {
        return browser.getURL();
    }

    public void loadUrl(String url) {
        browser.loadURL(url);
    }

    public void goBack() {
        if (browser.canGoBack()) {
            browser.goBack();
        }
    }

    public void goForward() {
        if (browser.canGoForward()) {
            browser.goForward();
        }
    }

    public void reload() {
        browser.reload();
    }

    /** N9: Esc — stops the current load. */
    public void stop() {
        browser.stopLoad();
    }

    /**
     * Tears the browser down. Must run on the EDT. Idempotent enough for the
     * CEF close-callback race (a second call on a closed browser is a no-op
     * as far as the GUI is concerned).
     */
    public void dispose() {
        try {
            java.awt.Container parent = uiComponent.getParent();
            if (parent != null) {
                parent.remove(uiComponent);
            }
            browser.close(true);
        } finally {
            client.dispose();
        }
    }
}
