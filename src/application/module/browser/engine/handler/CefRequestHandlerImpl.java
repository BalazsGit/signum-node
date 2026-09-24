package application.module.browser.engine.handler;

import application.module.browser.config.BrowserSettings;
import application.module.browser.engine.security.SslStatus;
import application.module.browser.engine.security.SslStatusDetector;
import application.module.browser.model.tab.TabController;
import application.module.browser.util.UrlUtils;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefCallback;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.network.CefRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Request-level interception of one tab (F2): certificate errors (S3) and
 * the {@code file://} block (N12, D17).
 * <p>
 * <b>Certificate errors (plan 4.3/4, S3):</b> a failed TLS validation is
 * rejected and the tab is steered to {@code signum://cert-error?code=..&url=..}
 * (the internal page renders the details; the tab keeps the CERT_ERROR
 * security state). The page's "Proceed" button navigates to
 * {@code signum://cert-continue?url=..}, which is intercepted here: the host
 * is added to the <em>tab-scoped</em> override set and the original URL is
 * reloaded — the next {@code onCertificateError} for that host is accepted.
 * The override is per-tab (this handler instance) and never global.
 * <p>
 * <b>Fork API note (146.0.10):</b> this JCEF fork's
 * {@code onCertificateError(browser, errorCode, url, callback)} carries no
 * frame and no certificate object — the URL parameter identifies the target,
 * and {@code callback.Continue()} / {@code callback.cancel()} decide the fate
 * of the navigation.
 */
final class CefRequestHandlerImpl extends CefRequestHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(CefRequestHandlerImpl.class);

    private static final String CERT_CONTINUE_PREFIX = "signum://cert-continue";

    private final String tabId;
    private final TabController controller;
    private final Supplier<BrowserSettings> settings;
    /** S3: hosts the user explicitly proceeded to (tab-scoped, never global). */
    private final Set<String> allowedCertHosts = ConcurrentHashMap.newKeySet();

    CefRequestHandlerImpl(String tabId, TabController controller, Supplier<BrowserSettings> settings) {
        this.tabId = tabId;
        this.controller = controller;
        this.settings = settings;
    }

    @Override
    public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request,
                                  boolean isRedirect, boolean isProxyAuth) {
        if (!frame.isMain()) {
            return false;
        }
        String url = request != null ? request.getURL() : null;
        if (url == null) {
            return false;
        }
        if (url.toLowerCase().startsWith(CERT_CONTINUE_PREFIX)) {
            // S3: the internal error page asked to proceed to the original URL.
            String target = UrlUtils.queryParam(url, "url");
            if (target != null && target.toLowerCase().startsWith("https://")) {
                String host = UrlUtils.host(target);
                if (!host.isEmpty()) {
                    allowedCertHosts.add(host);
                }
                BrowserCefHandlers.runInEdt(() -> browser.loadURL(target));
            }
            // The continue URL itself must never be navigated to.
            return true;
        }
        if (UrlUtils.isFileUrl(url) && settings.get().isBlockFileUrls()) {
            // N12 / D17: local files stay off the web by default.
            logger.debug("Blocked file:// navigation in tab {}: {}", tabId, url);
            return true;
        }
        return false;
    }

    @Override
    public boolean onCertificateError(CefBrowser browser, CefLoadHandler.ErrorCode errorCode,
                                      String url, CefCallback callback) {
        String failedUrl = url != null ? url : browser.getURL();
        String host = UrlUtils.host(failedUrl);
        if (!host.isEmpty() && allowedCertHosts.contains(host)) {
            // S3: tab-scoped override — the user already proceeded to this host.
            if (callback != null) {
                callback.Continue();
            }
            return true;
        }
        logger.info("Certificate error in tab {} ({}): {}", tabId, errorCode, failedUrl);
        String code = errorCode != null ? errorCode.name() : "unknown";
        String encoded = URLEncoder.encode(failedUrl, StandardCharsets.UTF_8);
        String errorPage = SslStatusDetector.CERT_ERROR_PAGE + "?code=" + code + "&url=" + encoded;
        BrowserCefHandlers.runInEdt(() -> {
            controller.setSslStatus(tabId, SslStatus.CERT_ERROR);
            browser.loadURL(errorPage);
        });
        if (callback != null) {
            callback.cancel();
        }
        return true;
    }
}
