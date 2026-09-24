package application.module.browser.engine.handler;

import application.module.browser.model.tab.TabController;
import application.module.browser.model.tab.TabSource;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLifeSpanHandlerAdapter;

/**
 * Lifespan of one tab: popups become tabs, the tab (not CEF) owns the browser.
 * <p>
 * <b>Popups (plan 4.3/3, T1/N7):</b> {@code window.open}, {@code target=_blank}
 * and Ctrl+Click all arrive here. The URL is turned into a new tab and the
 * popup window itself is always refused — a browser tab is the only way a
 * page can open a second context (no popup windows, no window sprawl).
 */
final class CefLifeSpanHandlerImpl extends CefLifeSpanHandlerAdapter {

    private final String tabId;
    private final TabController controller;

    CefLifeSpanHandlerImpl(String tabId, TabController controller) {
        this.tabId = tabId;
        this.controller = controller;
    }

    @Override
    public boolean onBeforePopup(CefBrowser browser, CefFrame frame, String targetUrl, String targetFrameName) {
        if (targetUrl != null && !targetUrl.isBlank()) {
            String url = targetUrl.trim();
            BrowserCefHandlers.runInEdt(() -> controller.openTab(url, TabSource.POPUP));
        }
        // Never open a JCEF popup window.
        return false;
    }

    @Override
    public boolean doClose(CefBrowser browser) {
        // We own the browser lifecycle (WebBrowser.dispose); CEF may proceed.
        return true;
    }

    @Override
    public void onBeforeClose(CefBrowser browser) {
        // A page-initiated close (JS window.close()) becomes a tab close.
        // Idempotent: a user-initiated close removed the tab before dispose,
        // so the late callback is a no-op (plan 4.3).
        BrowserCefHandlers.runInEdt(() -> controller.closeTab(tabId));
    }
}
