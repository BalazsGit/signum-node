package application.module.browser.engine.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the URL-derived security state (F2, S1/S4).
 */
class SslStatusDetectorTest {

    @Test
    @DisplayName("https URLs are SECURE")
    void httpsIsSecure() {
        assertEquals(SslStatus.SECURE, SslStatusDetector.detect("https://example.com"));
        assertEquals(SslStatus.SECURE,
                SslStatusDetector.detect("https://example.com:8443/path?q=1"));
    }

    @Test
    @DisplayName("internal signum:// pages are SECURE")
    void internalPagesAreSecure() {
        assertEquals(SslStatus.SECURE, SslStatusDetector.detect("signum://newtab"));
        assertEquals(SslStatus.SECURE, SslStatusDetector.detect("signum://cert-continue?url=x"));
    }

    @Test
    @DisplayName("the cert-error page is CERT_ERROR (not the internal-scheme green)")
    void certErrorPageIsCertError() {
        assertEquals(SslStatus.CERT_ERROR, SslStatusDetector.detect("signum://cert-error"));
        assertEquals(SslStatus.CERT_ERROR,
                SslStatusDetector.detect("signum://cert-error?code=268&url=https%3A%2F%2Fx"));
    }

    @Test
    @DisplayName("plain http is INSECURE (S4)")
    void httpIsInsecure() {
        assertEquals(SslStatus.INSECURE, SslStatusDetector.detect("http://example.com"));
    }

    @Test
    @DisplayName("file and unknown schemes are INSECURE")
    void otherSchemesAreInsecure() {
        assertEquals(SslStatus.INSECURE, SslStatusDetector.detect("file:///C:/x.html"));
        assertEquals(SslStatus.INSECURE, SslStatusDetector.detect("ftp://example.com"));
    }

    @Test
    @DisplayName("null and blank input is INSECURE (never a crash)")
    void nullInputIsInsecure() {
        assertEquals(SslStatus.INSECURE, SslStatusDetector.detect(null));
        assertEquals(SslStatus.INSECURE, SslStatusDetector.detect(""));
    }

    @Test
    @DisplayName("the tab-model entry point (SslStatus.forUrl) delegates to the detector")
    void forUrlDelegates() {
        assertEquals(SslStatus.SECURE, SslStatus.forUrl("https://example.com"));
        assertEquals(SslStatus.CERT_ERROR, SslStatus.forUrl("signum://cert-error?code=1"));
        assertEquals(SslStatus.INSECURE, SslStatus.forUrl("http://example.com"));
    }
}
