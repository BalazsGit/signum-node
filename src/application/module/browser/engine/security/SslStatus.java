package application.module.browser.engine.security;

/**
 * SSL state of a tab (S1–S4).
 * <p>
 * F2: the value is derived by {@link SslStatusDetector} from the URL scheme
 * — https and internal {@code signum://} pages are SECURE, the
 * cert-error page is CERT_ERROR, plain HTTP is INSECURE. The real
 * demotion path is {@code onCertificateError} (S3), which sets CERT_ERROR
 * explicitly and navigates the tab to the error page.
 */
public enum SslStatus {
    /** Valid TLS session (or a trusted internal {@code signum://} page). */
    SECURE,
    /** Plain HTTP, or not determinable yet. */
    INSECURE,
    /** The TLS certificate failed validation (S3: the tab shows the internal error page). */
    CERT_ERROR;

    /**
     * Scheme-based derivation — see {@link SslStatusDetector}.
     *
     * @param url the tab URL (may be null)
     * @return the detected status (SECURE / INSECURE / CERT_ERROR)
     */
    public static SslStatus forUrl(String url) {
        return SslStatusDetector.detect(url);
    }
}
