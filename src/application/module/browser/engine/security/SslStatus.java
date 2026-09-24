package application.module.browser.engine.security;

/**
 * SSL state of a tab (S1–S4).
 * <p>
 * F1: the value is derived from the URL scheme only — a deliberately simple
 * stand-in so the tab model is complete. F2 replaces the derivation with the
 * real {@code SslStatusDetector} (certificate validation + inspection) while
 * keeping this enum and the tab field stable.
 */
public enum SslStatus {
    /** Valid TLS session (or a trusted internal {@code signum://} page). */
    SECURE,
    /** Plain HTTP, or not determinable yet. */
    INSECURE,
    /** The TLS certificate failed validation (S3: the tab shows the internal error page). */
    CERT_ERROR;

    /**
     * Scheme-based heuristic used until F2's detector lands.
     *
     * @param url the tab URL (may be null)
     * @return SECURE for https/ and signum://, INSECURE otherwise
     */
    public static SslStatus forUrl(String url) {
        if (url == null) {
            return INSECURE;
        }
        String u = url.trim().toLowerCase();
        if (u.startsWith("https://") || u.startsWith("signum://")) {
            return SECURE;
        }
        return INSECURE;
    }
}
