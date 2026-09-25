package application.module.browser.engine;

import application.module.browser.util.UrlUtils;
import org.cef.CefApp;
import org.cef.network.CefCookie;
import org.cef.network.CefCookieManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Browsing-data cleanup of the engine (plan F9, C6/S6): cookie clearing via
 * the CEF global cookie manager.
 * <p>
 * Scope notes (the pinned JCEF 146.0.10 fork):
 * <ul>
 *   <li>history is module data — cleared by {@code HistoryStore.clear}
 *       (the GUI orchestrates both);</li>
 *   <li>there is <em>no</em> cache-clearing API in this fork, so the
 *       clear-data dialog offers history + cookies only (the on-disk cache
 *       is managed by CEF and rebuilt as needed).</li>
 * </ul>
 * Every method is a no-op (returning 0) when the engine is not running, so
 * callers never need to check the engine state. Engine layer (may import
 * {@code org.cef}).
 */
public final class CefDataCleaner {

    private static final Logger logger = LoggerFactory.getLogger(CefDataCleaner.class);

    private CefDataCleaner() {
        // utility class — never instantiated
    }

    /**
     * Deletes every cookie visible to the given host (both the http and
     * https view of it; the host is validated — scheme/junk is dropped).
     *
     * @param host a plain host (e.g. {@code example.com}, port allowed)
     * @return the number of deleted cookies (0 when the host is invalid or
     *         the engine is off)
     */
    public static int clearCookiesForHost(String host) {
        String safe = host == null ? "" : host.trim();
        if (safe.isEmpty() || safe.contains("://") || safe.contains("@")
                || safe.startsWith(".") || safe.contains("/")) {
            return 0; // not a plain host — never pass user input to the cookie manager raw
        }
        try {
            if (!engineRunning()) {
                return 0;
            }
            CefCookieManager manager = CefCookieManager.getGlobalManager();
            boolean https = manager.deleteCookies("https://" + safe, "");
            boolean http = manager.deleteCookies("http://" + safe, "");
            if (https || http) {
                logger.info("Deleted the cookies of host {}", safe);
            }
            return https || http ? 1 : 0; // the manager reports success, not counts
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            logger.warn("Could not delete the cookies of host {}: {}", safe, e.toString());
            return 0;
        }
    }

    /**
     * Deletes <em>all</em> cookies: the cookie list is visited, and every
     * distinct URL is passed to {@code deleteCookies(url, "")} (which removes
     * every cookie visible to that URL).
     *
     * @return the number of URLs cleared (0 when there is nothing or the
     *         engine is off)
     */
    public static int clearAllCookies() {
        try {
            if (!engineRunning()) {
                return 0;
            }
            CefCookieManager manager = CefCookieManager.getGlobalManager();
            Set<String> urls = new LinkedHashSet<>();
            manager.visitAllCookies((cookie, index, total, delete) -> {
                collectCookieUrl(urls, cookie);
                return true; // keep visiting
            });
            int cleared = 0;
            for (String url : urls) {
                if (manager.deleteCookies(url, "")) {
                    cleared++;
                }
            }
            if (cleared > 0) {
                logger.info("Deleted the cookies of {} distinct URLs", cleared);
            }
            return cleared;
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            logger.warn("Could not delete the cookies: {}", e.toString());
            return 0;
        }
    }

    private static void collectCookieUrl(Set<String> out, CefCookie cookie) {
        if (cookie == null || cookie.domain == null || cookie.domain.isBlank()) {
            return;
        }
        // the cookie carries domain + path (no absolute URL); deleteCookies
        // needs a URL, so it is rebuilt (secure flag decides the scheme)
        String scheme = cookie.secure ? "https://" : "http://";
        String domain = cookie.domain.trim();
        if (domain.startsWith(".")) {
            domain = domain.substring(1); // a leading dot = subdomain scope
        }
        String path = cookie.path != null && !cookie.path.isBlank() ? cookie.path : "/";
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        out.add(scheme + domain + path);
    }

    private static boolean engineRunning() {
        try {
            return CefApp.getState() == CefApp.CefAppState.INITIALIZED;
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return false;
        }
    }
}