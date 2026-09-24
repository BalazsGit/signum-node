package application.module.browser.engine.handler;

import application.module.browser.model.tab.TabController;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Display events of one tab → tab model (title, current URL; console to the
 * application log for debugging internal pages).
 * <p>
 * F1 note: the pinned JCEF fork (146.0.10) exposes no favicon callback — the
 * tab strip therefore renders a deterministic placeholder icon (T5 partial;
 * the {@code BrowserTab.favicon} field and the renderer's favicon path stay
 * in place for a fetch-based source in a later phase).
 */
final class CefDisplayHandlerImpl extends CefDisplayHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(CefDisplayHandlerImpl.class);

    private final String tabId;
    private final TabController controller;

    CefDisplayHandlerImpl(String tabId, TabController controller) {
        this.tabId = tabId;
        this.controller = controller;
    }

    @Override
    public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
        if (frame.isMain() && url != null) {
            BrowserCefHandlers.runInEdt(() -> controller.updateUrl(tabId, url));
        }
    }

    @Override
    public void onTitleChange(CefBrowser browser, String title) {
        if (title != null) {
            BrowserCefHandlers.runInEdt(() -> controller.updateTitle(tabId, title));
        }
    }

    @Override
    public boolean onConsoleMessage(CefBrowser browser, CefSettings.LogSeverity level,
                                    String message, String source, int line) {
        // Internal-page debugging aid (D6 pages talk to us through the console).
        logger.debug("Browser console [{}] {} ({}:{})", level, message, source, line);
        return false; // default handling
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, int type) {
        return false;
    }
}
