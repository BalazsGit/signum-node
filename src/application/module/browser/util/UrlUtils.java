package application.module.browser.util;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Pure URL helpers of the omnibox and the navigation layer (F2, N1).
 * <p>
 * No Swing, no CEF — the class is fully unit-testable. The single most
 * important rule (N1, Chrome semantics): omnibox input that contains a
 * scheme or "looks like a host" becomes a URL, everything else becomes a
 * search-engine query.
 */
public final class UrlUtils {

    /** Fallback search template when the settings template is unusable. */
    public static final String FALLBACK_SEARCH_TEMPLATE = "https://duckduckgo.com/?q={query}";

    private UrlUtils() {
        // utility class — never instantiated
    }

    /**
     * @param url any URL (may be null or malformed)
     * @return the lowercase scheme, or {@code ""} when there is none
     */
    public static String scheme(String url) {
        if (url == null) {
            return "";
        }
        try {
            String s = URI.create(url.trim()).getScheme();
            return s == null ? "" : s.toLowerCase();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * @param url any URL (may be null or malformed)
     * @return the lowercase host, or {@code ""} when there is none
     */
    public static String host(String url) {
        if (url == null) {
            return "";
        }
        try {
            String h = URI.create(url.trim()).getHost();
            return h == null ? "" : h.toLowerCase();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * @param url any URL (may be null)
     * @return {@code true} for {@code file://} URLs (the N12 block target)
     */
    public static boolean isFileUrl(String url) {
        return url != null && url.trim().toLowerCase().startsWith("file:");
    }

    /**
     * @param url any URL (may be null)
     * @return {@code true} for the built-in {@code signum://} scheme
     */
    public static boolean isSignumUrl(String url) {
        return url != null && url.trim().toLowerCase().startsWith("signum://");
    }

    /**
     * @param input omnibox input
     * @return {@code true} when the input already carries a navigable scheme
     *         (http, https, file or signum) and must not be turned into a search
     */
    public static boolean isWebUrl(String input) {
        String s = scheme(input);
        return "http".equals(s) || "https".equals(s) || "file".equals(s) || "signum".equals(s);
    }

    /**
     * N1: turns raw omnibox input into a navigation target.
     * <p>
     * Rules (Chrome semantics): blank input yields {@code null} (the caller
     * keeps the current state); a URL with a known scheme is passed through
     * unchanged; input that looks like a host (a dotted name or an explicit
     * port, no spaces) gets {@code http://} prepended; anything else is
     * searched through the engine template (the query is percent-encoded).
     *
     * @param input          the raw omnibox text
     * @param searchTemplate the settings template ({@code {query}} placeholder)
     * @return the navigation target, or {@code null} for blank input
     */
    public static String normalize(String input, String searchTemplate) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (isWebUrl(trimmed)) {
            return trimmed;
        }
        if (looksLikeHost(trimmed)) {
            return "http://" + trimmed;
        }
        String template = searchTemplate != null && searchTemplate.contains("{query}")
                ? searchTemplate
                : FALLBACK_SEARCH_TEMPLATE;
        return template.replace("{query}", URLEncoder.encode(trimmed, StandardCharsets.UTF_8));
    }

    /**
     * Host heuristic of {@link #normalize}: a candidate must not contain
     * spaces and its host part (before any {@code /} or {@code ?}) must have
     * a dot (dotted domain, dotted IP) or an explicit port.
     */
    public static boolean looksLikeHost(String input) {
        if (input.indexOf(' ') >= 0) {
            return false;
        }
        String hostPart = input;
        int slash = input.indexOf('/');
        if (slash >= 0) {
            hostPart = input.substring(0, slash);
        }
        int query = hostPart.indexOf('?');
        if (query >= 0) {
            hostPart = hostPart.substring(0, query);
        }
        int port = hostPart.lastIndexOf(':');
        String host = port >= 0 ? hostPart.substring(0, port) : hostPart;
        return !host.isEmpty() && (host.contains(".") || port >= 0);
    }

    /**
     * @param url  a URL with a query string
     * @param name the parameter name
     * @return the percent-decoded value of the named query parameter, or
     *         {@code null} when the parameter is absent
     */
    public static String queryParam(String url, String name) {
        if (url == null || name == null) {
            return null;
        }
        int q = url.indexOf('?');
        if (q < 0 || q == url.length() - 1) {
            return null;
        }
        String query = url.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) {
            query = query.substring(0, hash);
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            if (key.equals(name)) {
                String value = eq >= 0 ? pair.substring(eq + 1) : "";
                try {
                    return URLDecoder.decode(value, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return value;
                }
            }
        }
        return null;
    }
}
