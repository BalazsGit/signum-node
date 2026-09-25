package application.module.browser.engine;

import application.module.browser.config.BrowserSettings;
import application.module.browser.core.BrowserEngine;
import application.module.browser.engine.handler.ActiveDownloadRegistry;
import application.module.browser.model.download.DownloadManager;
import application.module.browser.model.history.HistoryStore;
import application.module.browser.model.tab.BrowserTab;
import application.module.browser.model.tab.TabController;

import java.awt.Component;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@code tabId → WebBrowser} map (plan §4.1).
 * <p>
 * Browsers are created <em>lazily</em> on first access (when the tab is
 * added to the GUI) and stay alive for the tab's whole lifetime — a CEF
 * browser is expensive to (re)create, and the content panel only shows/hides
 * the components (T4). All public methods must be called on the EDT.
 */
public final class WebBrowserRegistry {

    private final BrowserEngine engine;
    private final TabController controller;
    private final Supplier<BrowserSettings> settings;
    private final HistoryStore history;
    private final DownloadManager downloads;
    private final ActiveDownloadRegistry activeDownloads;
    private final Map<String, WebBrowser> browsers = new LinkedHashMap<>();

    public WebBrowserRegistry(BrowserEngine engine, TabController controller,
                              Supplier<BrowserSettings> settings, HistoryStore history,
                              DownloadManager downloads, ActiveDownloadRegistry activeDownloads) {
        this.engine = engine;
        this.controller = controller;
        this.settings = settings;
        this.history = history;
        this.downloads = downloads;
        this.activeDownloads = activeDownloads;
    }

    /**
     * Creates the tab's browser on first access.
     *
     * @param tabId an existing tab
     * @return the tab's persistent CEF UI component (added to the content panel by the GUI)
     */
    public Component component(String tabId) {
        WebBrowser browser = browsers.get(tabId);
        if (browser == null) {
            BrowserTab tab = controller.getTab(tabId)
                    .orElseThrow(() -> new IllegalStateException("Unknown tab id: " + tabId));
            browser = new WebBrowser(engine.createClient(), tabId, controller, tab.getUrl(),
                    settings, history, downloads, activeDownloads);
            browsers.put(tabId, browser);
        }
        return browser.getUiComponent();
    }

    /** Disposes the tab's browser (close flow, plan 4.3). */
    public void remove(String tabId) {
        WebBrowser browser = browsers.remove(tabId);
        if (browser != null) {
            browser.dispose();
        }
    }

    /** Disposes every browser (engine shutdown). */
    public void clear() {
        for (WebBrowser browser : browsers.values()) {
            browser.dispose();
        }
        browsers.clear();
    }

    private WebBrowser of(String tabId) {
        return tabId != null ? browsers.get(tabId) : null;
    }

    /** F2 (omnibox): navigates an existing tab. @return {@code true} when handled. */
    public boolean navigate(String tabId, String url) {
        WebBrowser browser = of(tabId);
        if (browser == null) {
            return false;
        }
        browser.loadUrl(url);
        return true;
    }

    public void back(String tabId) {
        WebBrowser browser = of(tabId);
        if (browser != null) {
            browser.goBack();
        }
    }

    public void forward(String tabId) {
        WebBrowser browser = of(tabId);
        if (browser != null) {
            browser.goForward();
        }
    }

    public void reload(String tabId) {
        WebBrowser browser = of(tabId);
        if (browser != null) {
            browser.reload();
        }
    }

    public void stop(String tabId) {
        WebBrowser browser = of(tabId);
        if (browser != null) {
            browser.stop();
        }
    }

    /** X1: F12 — opens the tab's DevTools window. @return {@code true} when handled. */
    public boolean openDevTools(String tabId) {
        WebBrowser browser = of(tabId);
        if (browser == null) {
            return false;
        }
        browser.openDevTools();
        return true;
    }

    /** X1: F12 — closes the tab's DevTools window. */
    public void closeDevTools(String tabId) {
        WebBrowser browser = of(tabId);
        if (browser != null) {
            browser.closeDevTools();
        }
    }

    /**
     * T10/D13: discards the tab's engine (the tab itself stays in the strip;
     * the next {@link #component} call recreates the browser from the tab's
     * URL — the transparent restore).
     */
    public void discard(String tabId) {
        remove(tabId);
    }
}
