package application.module.browser.engine.scheme;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of the built-in {@code signum://} pages (plan D6): name to static
 * classpath HTML under {@code resources/html/browser/}.
 * <p>
 * F1 ships the New Tab page plus the built-in 404; F2 adds the
 * certificate-error page (S3). F3 introduces <em>dynamic</em> pages — pages
 * whose body is produced at request time from live module data (the history
 * page from the {@code HistoryStore}). A dynamic page registers a
 * {@link PageRenderer} and accepts sub-paths (its action URLs, e.g.
 * {@code history/delete}); the data it injects is JSON-escaped by the
 * renderer (S9: no XSS from UI data).
 */
public final class InternalPage {

    private static final Logger logger = LoggerFactory.getLogger(InternalPage.class);

    /** Served for any unknown {@code signum://} page (a friendly 404, never a blank tab). */
    public static final String NOT_FOUND = "not-found";

    private static final Map<String, String> PAGES = Map.of(
            "newtab", "/html/browser/newtab.html",
            "cert-error", "/html/browser/cert-error.html",
            "css/browser.css", "/html/browser/css/browser.css",
            NOT_FOUND, "/html/browser/not-found.html");

    /** Dynamic page bodies, produced at request time (F3+; S9-escaped data). */
    private static final Map<String, PageRenderer> RENDERERS = new ConcurrentHashMap<>();

    private InternalPage() {
        // utility class — never instantiated
    }

    /**
     * Produces the body of a dynamic page at request time (F3+).
     *
     * @param url   the full requested URL
     * @param query the parsed query parameters (never null)
     * @return the page bytes (HTML), or {@code null} to fall back to the 404 page
     */
    @FunctionalInterface
    public interface PageRenderer {
        byte[] render(String url, Map<String, String> query);
    }

    /**
     * Registers (or replaces) the body producer of a dynamic page.
     *
     * @param page     the page name (e.g. {@code "history"})
     * @param renderer the producer, or {@code null} to remove the registration
     */
    public static void registerRenderer(String page, PageRenderer renderer) {
        if (renderer == null) {
            RENDERERS.remove(page);
        } else {
            RENDERERS.put(page, renderer);
        }
    }

    /**
     * @param page a page name
     * @return the registered dynamic renderer, or {@code null} for static pages
     */
    public static PageRenderer rendererFor(String page) {
        return page == null ? null : RENDERERS.get(page);
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
        // Dynamic pages (F3+) accept sub-paths: their action URLs (e.g.
        // history/delete) render through the same page body.
        for (String dynamic : RENDERERS.keySet()) {
            if (path.equals(dynamic) || path.startsWith(dynamic + "/")) {
                return dynamic;
            }
        }
        for (String page : PAGES.keySet()) {
            if (path.endsWith("/" + page)) {
                return page;
            }
        }
        return null;
    }

    /**
     * Parses the query string of a {@code signum://} URL into parameters
     * (first occurrence wins, percent-decoded; malformed pairs fall back to
     * the raw values).
     *
     * @param url any URL (may be null or query-less)
     * @return the query parameters, never null (empty when there is no query)
     */
    public static Map<String, String> query(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        if (url == null) {
            return out;
        }
        int q = url.indexOf('?');
        if (q < 0 || q == url.length() - 1) {
            return out;
        }
        String query = url.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) {
            query = query.substring(0, hash);
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                out.putIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                out.putIfAbsent(key, value);
            }
        }
        return out;
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
