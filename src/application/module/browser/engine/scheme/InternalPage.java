package application.module.browser.engine.scheme;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Registry of the built-in {@code signum://} pages (plan D6): name to static
 * classpath HTML under {@code resources/html/browser/}.
 * <p>
 * F1 ships only the New Tab page plus the built-in 404; F3/F4 (history,
 * bookmarks) add their pages through this same registry — no new mechanism.
 */
public final class InternalPage {

    private static final Logger logger = LoggerFactory.getLogger(InternalPage.class);

    /** Served for any unknown {@code signum://} page (a friendly 404, never a blank tab). */
    public static final String NOT_FOUND = "not-found";

    private static final Map<String, String> PAGES = Map.of(
            "newtab", "/html/browser/newtab.html",
            "css/browser.css", "/html/browser/css/browser.css",
            NOT_FOUND, "/html/browser/not-found.html");

    private InternalPage() {
        // utility class — never instantiated
    }

    /**
     * Maps a {@code signum://} URL to a registered page name.
     * <p>
     * Resolution is by exact path first, then by suffix: pages link to assets
     * with <em>relative</em> URLs (e.g. {@code <link href="css/browser.css">}
     * inside the new-tab page), which the engine resolves against the page's
     * own URL — {@code signum://newtab/css/browser.css}. Serving by suffix
     * keeps those relative links working without duplicating registry entries.
     *
     * @param url the full URL (e.g. {@code signum://newtab} or {@code signum://newtab/css/browser.css})
     * @return the page name, or {@code null} when the URL is not a registered page
     */
    public static String pageForUrl(String url) {
        if (url == null) {
            return null;
        }
        String path = url;
        if (path.startsWith(BrowserSchemeHandler.SCHEME_PREFIX)) {
            path = path.substring(BrowserSchemeHandler.SCHEME_PREFIX.length());
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (PAGES.containsKey(path)) {
            return path;
        }
        for (String page : PAGES.keySet()) {
            if (path.endsWith("/" + page)) {
                return page;
            }
        }
        return null;
    }

    /**
     * @param page a registered page name
     * @return the page bytes, or {@code null} when the classpath resource is missing
     */
    static byte[] read(String page) {
        String resource = PAGES.get(page);
        if (resource == null) {
            return null;
        }
        try (InputStream in = InternalPage.class.getResourceAsStream(resource)) {
            if (in == null) {
                logger.error("Internal page resource missing from the classpath: {}", resource);
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            logger.error("Could not read the internal page resource {}", resource, e);
            return null;
        }
    }

    /**
     * @param page a registered page name (by extension).
     * @return the BARE mime type — never parameterized: the pinned JCEF build
     *         (146.0.10) downgrades a main-frame response with a parameterized
     *         mime (e.g. {@code text/html;charset=utf-8}) to a download, which
     *         shows the page as plain-text source instead of rendering it.
     *         UTF-8 is the default charset for HTML and CSS.
     */
    static String mimeFor(String page) {
        return page.endsWith(".css") ? "text/css" : "text/html";
    }
}
