package application.module.browser.engine.security;

import application.module.browser.util.UrlUtils;

/**
 * Derives the {@link SslStatus} of a tab from its URL (S1–S4, F2).
 * <p>
 * The pinned JCEF fork (146.0.10) exposes no per-navigation SSL state
 * callback, so the status is scheme-derived: {@code https://} starts as
 * SECURE and is only demoted to CERT_ERROR by the real error path
 * ({@code onCertificateError}, which navigates the tab to
 * {@code signum://cert-error} — recognized here explicitly, so the error
 * page itself keeps the red state instead of the internal-scheme green).
 * Plain HTTP stays INSECURE (S4).
 */
public final class SslStatusDetector {

    /** The internal certificate-error page URL (S3). */
    public static final String CERT_ERROR_PAGE = "signum://cert-error";

    private SslStatusDetector() {
        // utility class — never instantiated
    }

    /**
     * @param url the tab URL (may be null)
     * @return SECURE for https and internal {@code signum://} pages (except
     *         the cert-error page), CERT_ERROR for the cert-error page,
     *         INSECURE for everything else
     */
    public static SslStatus detect(String url) {
        if (url == null) {
            return SslStatus.INSECURE;
        }
        String u = url.trim().toLowerCase();
        if (u.startsWith(CERT_ERROR_PAGE) || u.startsWith(CERT_ERROR_PAGE + "/")
                || u.startsWith(CERT_ERROR_PAGE + "?")) {
            return SslStatus.CERT_ERROR;
        }
        if (u.startsWith("https://")) {
            return SslStatus.SECURE;
        }
        if (UrlUtils.isSignumUrl(url)) {
            return SslStatus.SECURE;
        }
        return SslStatus.INSECURE;
    }
}
