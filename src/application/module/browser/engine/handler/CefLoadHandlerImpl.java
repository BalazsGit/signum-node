package application.module.browser.engine.handler;

import application.module.browser.model.tab.TabController;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.network.CefRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Load events of one tab → tab model (loading flag, progress, final URL).
 * <p>
 * F1 note: the pinned JCEF fork (146.0.10) has no progress callback, so the
 * tab's progress is 0/100 from the load state — the F2 progress bar will be
 * indeterminate (A3) and needs no finer source.
 */
final class CefLoadHandlerImpl extends CefLoadHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(CefLoadHandlerImpl.class);

    private final String tabId;
    private final TabController controller;

    CefLoadHandlerImpl(String tabId, TabController controller) {
        this.tabId = tabId;
        this.controller = controller;
    }

    @Override
    public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
        BrowserCefHandlers.runInEdt(() -> {
            controller.setLoading(tabId, isLoading);
            controller.setProgress(tabId, isLoading ? 0 : 100);
            controller.setNavigationState(tabId, canGoBack, canGoForward); // N3 enabled state
        });
    }

    @Override
    public void onLoadStart(CefBrowser browser, CefFrame frame, CefRequest.TransitionType transitionType) {
        if (frame.isMain()) {
            BrowserCefHandlers.runInEdt(() -> controller.setLoading(tabId, true));
        }
    }

    @Override
    public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
        if (!frame.isMain()) {
            return;
        }
        String finalUrl = browser.getURL();
        BrowserCefHandlers.runInEdt(() -> {
            controller.setLoading(tabId, false);
            controller.setProgress(tabId, 100);
            controller.updateUrl(tabId, finalUrl);
        });
    }

    @Override
    public void onLoadError(CefBrowser browser, CefFrame frame, CefLoadHandler.ErrorCode errorCode,
                            String failedUrl, String errorText) {
        if (!frame.isMain()) {
            return;
        }
        logger.debug("Load failed in tab {}: {} ({})", tabId, errorCode, errorText);
        String url = failedUrl != null ? failedUrl : browser.getURL();
        BrowserCefHandlers.runInEdt(() -> {
            controller.setLoading(tabId, false);
            controller.updateUrl(tabId, url);
        });
    }
}
