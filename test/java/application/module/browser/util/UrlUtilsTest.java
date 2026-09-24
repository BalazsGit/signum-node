package application.module.browser.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the omnibox URL normalization (F2, N1) and the URL helpers.
 */
class UrlUtilsTest {

    private static final String TEMPLATE = "https://duckduckgo.com/?q={query}";

    // ------------------------------------------------------------------
    // scheme / host
    // ------------------------------------------------------------------

    @Test
    @DisplayName("scheme returns the lowercase scheme of a URL")
    void schemeOfUrls() {
        assertEquals("https", UrlUtils.scheme("https://example.com/x"));
        assertEquals("http", UrlUtils.scheme("HTTP://example.com"));
        assertEquals("signum", UrlUtils.scheme("signum://newtab"));
        assertEquals("file", UrlUtils.scheme("file:///C:/x"));
    }

    @Test
    @DisplayName("scheme is empty for host-like or malformed input")
    void schemeOfNonUrls() {
        assertEquals("", UrlUtils.scheme("example.com"));
        assertEquals("", UrlUtils.scheme(""));
        assertEquals("", UrlUtils.scheme(null));
    }

    @Test
    @DisplayName("host extracts the lowercase host (port and path ignored)")
    void hostOfUrls() {
        assertEquals("example.com", UrlUtils.host("https://Example.com:8443/path?q=1"));
        assertEquals("localhost", UrlUtils.host("http://localhost:8080"));
        assertEquals("", UrlUtils.host("example.com"));
        assertEquals("", UrlUtils.host(null));
    }

    @Test
    @DisplayName("file and signum URL detection")
    void urlKindDetection() {
        assertTrue(UrlUtils.isFileUrl("file:///C:/x/y.html"));
        assertFalse(UrlUtils.isFileUrl("https://example.com"));
        assertFalse(UrlUtils.isFileUrl(null));
        assertTrue(UrlUtils.isSignumUrl("signum://newtab"));
        assertFalse(UrlUtils.isSignumUrl("http://signum.example"));
    }

    // ------------------------------------------------------------------
    // normalize (N1)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("blank input yields null (the caller keeps the current state)")
    void blankInputYieldsNull() {
        assertNull(UrlUtils.normalize(null, TEMPLATE));
        assertNull(UrlUtils.normalize("", TEMPLATE));
        assertNull(UrlUtils.normalize("   ", TEMPLATE));
    }

    @Test
    @DisplayName("URLs with a known scheme pass through unchanged")
    void webUrlsPassThrough() {
        assertEquals("https://example.com/a?b=1",
                UrlUtils.normalize("https://example.com/a?b=1", TEMPLATE));
        assertEquals("http://example.com", UrlUtils.normalize("http://example.com", TEMPLATE));
        assertEquals("signum://newtab", UrlUtils.normalize("signum://newtab", TEMPLATE));
        assertEquals("file:///C:/x", UrlUtils.normalize("file:///C:/x", TEMPLATE));
    }

    @Test
    @DisplayName("dotted hosts become http URLs (N1)")
    void dottedHostsBecomeUrls() {
        assertEquals("http://example.com", UrlUtils.normalize("example.com", TEMPLATE));
        assertEquals("http://www.signum.network/",
                UrlUtils.normalize("www.signum.network/", TEMPLATE));
        assertEquals("http://example.com/path?q=1",
                UrlUtils.normalize("example.com/path?q=1", TEMPLATE));
        assertEquals("http://192.168.0.1", UrlUtils.normalize("192.168.0.1", TEMPLATE));
    }

    @Test
    @DisplayName("an explicit port makes the input a host")
    void portMakesHost() {
        assertEquals("http://localhost:8080", UrlUtils.normalize("localhost:8080", TEMPLATE));
        assertEquals("http://127.0.0.1:9081/x",
                UrlUtils.normalize("127.0.0.1:9081/x", TEMPLATE));
    }

    @Test
    @DisplayName("everything else becomes a search (the query is percent-encoded)")
    void otherwiseSearch() {
        assertEquals("https://duckduckgo.com/?q=weather",
                UrlUtils.normalize("weather", TEMPLATE));
        assertEquals("https://duckduckgo.com/?q=hello+world",
                UrlUtils.normalize("hello world", TEMPLATE));
        assertEquals("https://duckduckgo.com/?q=a%26b%3Dc",
                UrlUtils.normalize("a&b=c", TEMPLATE));
    }

    @Test
    @DisplayName("an unusable template falls back to the built-in one")
    void unusableTemplateFallsBack() {
        assertEquals(UrlUtils.FALLBACK_SEARCH_TEMPLATE.replace("{query}", "weather"),
                UrlUtils.normalize("weather", "no-placeholder-here"));
        assertEquals(UrlUtils.FALLBACK_SEARCH_TEMPLATE.replace("{query}", "weather"),
                UrlUtils.normalize("weather", null));
    }

    // ------------------------------------------------------------------
    // looksLikeHost
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the host heuristic: dot or port, no spaces")
    void hostHeuristic() {
        assertTrue(UrlUtils.looksLikeHost("example.com"));
        assertTrue(UrlUtils.looksLikeHost("localhost:8080"));
        assertTrue(UrlUtils.looksLikeHost("example.com/a/b"));
        assertFalse(UrlUtils.looksLikeHost("hello world"));
        assertFalse(UrlUtils.looksLikeHost("weather"));
        assertFalse(UrlUtils.looksLikeHost(""));
        assertFalse(UrlUtils.looksLikeHost(":8080"));
    }

    // ------------------------------------------------------------------
    // queryParam
    // ------------------------------------------------------------------

    @Test
    @DisplayName("queryParam decodes percent-encoded values")
    void queryParamDecodes() {
        String url = "signum://cert-continue?url=https%3A%2F%2Fexample.com%2Fpath%3Fq%3D1";
        assertEquals("https://example.com/path?q=1", UrlUtils.queryParam(url, "url"));
        assertEquals("268", UrlUtils.queryParam("signum://cert-error?code=268&url=x", "code"));
    }

    @Test
    @DisplayName("queryParam is null for missing parameters or absent query strings")
    void queryParamMissing() {
        assertNull(UrlUtils.queryParam("signum://cert-error", "code"));
        assertNull(UrlUtils.queryParam("signum://cert-error?code=1", "url"));
        assertNull(UrlUtils.queryParam(null, "code"));
    }
}