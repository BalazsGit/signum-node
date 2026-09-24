package application.module.browser.engine.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java-side TLS inspection (S2, F2): opens its own TLS connection to the
 * target, performs the handshake and reads the certificate chain the server
 * presented — independent of the CEF renderer's own verification.
 * <p>
 * <b>Threading (plan §4.2):</b> the handshake blocks (connect + handshake,
 * 10 s connect timeout), so {@link #inspect} must be called from a background
 * thread; the GUI pumps the result back to the EDT. Results are cached per
 * {@code host:port} with a short TTL so a toolbar click does not
 * re-handshake on every view.
 * <p>
 * <b>Verification semantics:</b> the inspector uses the JVM's default trust
 * store (a test-injectable {@link SSLContext} in the package-private
 * constructor). A certificate that is not trusted (self-signed, expired,
 * wrong host) yields a {@link Result} with a failure reason instead of an
 * exception — the caller shows it, it never crashes the browser.
 */
public final class CertificateInspector {

    private static final Logger logger = LoggerFactory.getLogger(CertificateInspector.class);

    /** Cache lifetime of an inspection result (10 minutes). */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    /** Connect timeout of the inspection handshake. */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private final SSLContext sslContext;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Production inspector: the JVM's default trust store. */
    public CertificateInspector() {
        this(defaultContext());
    }

    /**
     * Testable constructor: an explicit SSL context (e.g. one that trusts a
     * locally generated test certificate).
     */
    CertificateInspector(SSLContext sslContext) {
        this.sslContext = sslContext;
    }

    private static SSLContext defaultContext() {
        try {
            return SSLContext.getDefault();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No default SSL context available", e);
        }
    }

    /**
     * Inspects the certificate chain of an https URL (blocking).
     *
     * @param httpsUrl the URL to inspect (port optional, defaults to 443)
     * @return a result with the chain (success) or a failure reason; never
     *         null — a malformed URL or handshake problem is a failure result
     */
    public Result inspect(String httpsUrl) {
        String host;
        int port;
        try {
            String stripped = httpsUrl == null ? "" : httpsUrl.trim();
            if (!stripped.toLowerCase().startsWith("https://")) {
                return Result.failure("Not an https URL");
            }
            String rest = stripped.substring("https://".length());
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                rest = rest.substring(0, slash);
            }
            int query = rest.indexOf('?');
            if (query >= 0) {
                rest = rest.substring(0, query);
            }
            int colon = rest.lastIndexOf(':');
            if (colon >= 0) {
                host = rest.substring(0, colon);
                port = Integer.parseInt(rest.substring(colon + 1));
            } else {
                host = rest;
                port = 443;
            }
            if (host.isEmpty() || port <= 0 || port > 65535) {
                return Result.failure("Invalid host or port");
            }
        } catch (NumberFormatException e) {
            return Result.failure("Invalid host or port");
        }

        String cacheKey = host + ":" + port;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.isFresh()) {
            return cached.result;
        }

        Result result;
        try {
            result = handshake(host, port);
        } catch (Exception e) {
            result = Result.failure(friendlyReason(e));
        }
        cache.put(cacheKey, new CacheEntry(result, System.currentTimeMillis()));
        return result;
    }

    private Result handshake(String host, int port) throws Exception {
        List<CertificateInfo> chain = new ArrayList<>();
        try (SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.startHandshake();
            SSLSession session = socket.getSession();
            for (Certificate certificate : session.getPeerCertificates()) {
                if (certificate instanceof X509Certificate x509) {
                    chain.add(CertificateInfo.of(x509));
                }
            }
        }
        if (chain.isEmpty()) {
            return Result.failure("The server presented no certificate");
        }
        // The JSSE trust manager does not always reject an expired
        // self-signed anchor, so the leaf's validity is checked explicitly.
        CertificateInfo leaf = chain.get(0);
        Instant now = Instant.now();
        if (leaf.getNotAfter().isBefore(now)) {
            return Result.failure("The certificate has expired");
        }
        if (leaf.getNotBefore().isAfter(now)) {
            return Result.failure("The certificate is not yet valid");
        }
        return Result.success(chain);
    }

    private static String friendlyReason(Exception e) {
        if (e instanceof SSLException ssl) {
            // The root cause carries the human-readable certificate problem.
            Throwable cause = ssl.getCause() != null ? ssl.getCause() : ssl;
            return cause.getMessage() != null && !cause.getMessage().isBlank()
                    ? cause.getMessage()
                    : "Certificate verification failed";
        }
        String message = e.getMessage();
        return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
    }

    private record CacheEntry(Result result, long createdAt) {
        boolean isFresh() {
            return System.currentTimeMillis() - createdAt < CACHE_TTL_MS;
        }
    }

    /**
     * One certificate of the presented chain — an immutable view for the UI.
     */
    public static final class CertificateInfo {

        private final String subject;
        private final String issuer;
        private final Instant notBefore;
        private final Instant notAfter;
        private final List<String> subjectAltNames;
        private final String signatureAlgorithm;

        private CertificateInfo(String subject, String issuer, Instant notBefore, Instant notAfter,
                                List<String> subjectAltNames, String signatureAlgorithm) {
            this.subject = subject;
            this.issuer = issuer;
            this.notBefore = notBefore;
            this.notAfter = notAfter;
            this.subjectAltNames = subjectAltNames;
            this.signatureAlgorithm = signatureAlgorithm;
        }

        static CertificateInfo of(X509Certificate cert) {
            try {
                return new CertificateInfo(
                        cert.getSubjectX500Principal().getName(),
                        cert.getIssuerX500Principal().getName(),
                        cert.getNotBefore().toInstant(),
                        cert.getNotAfter().toInstant(),
                        san(cert),
                        cert.getSigAlgName());
            } catch (GeneralSecurityException e) {
                logger.warn("Could not parse a certificate of the chain", e);
                return new CertificateInfo("<unparseable>", "<unparseable>",
                        Instant.EPOCH, Instant.EPOCH, List.of(), "<unknown>");
            }
        }

        /** @return the SAN entries of the certificate (DNS names and IPs). */
        private static List<String> san(X509Certificate cert) throws GeneralSecurityException {
            List<String> names = new ArrayList<>();
            Collection<List<?>> all = cert.getSubjectAlternativeNames();
            if (all != null) {
                for (List<?> entry : all) {
                    // Type 2 = DNS name, type 7 = IP address.
                    if (entry != null && entry.size() == 2
                            && entry.get(0) instanceof Integer type
                            && (type == 2 || type == 7)) {
                        names.add(String.valueOf(entry.get(1)));
                    }
                }
            }
            return names;
        }

        public String getSubject() {
            return subject;
        }

        public String getIssuer() {
            return issuer;
        }

        public Instant getNotBefore() {
            return notBefore;
        }

        public Instant getNotAfter() {
            return notAfter;
        }

        public List<String> getSubjectAltNames() {
            return subjectAltNames;
        }

        public String getSignatureAlgorithm() {
            return signatureAlgorithm;
        }
    }

    /**
     * The outcome of an inspection: either the chain or a failure reason.
     */
    public static final class Result {

        private final boolean ok;
        private final List<CertificateInfo> chain;
        private final String failureReason;

        private Result(boolean ok, List<CertificateInfo> chain, String failureReason) {
            this.ok = ok;
            this.chain = chain;
            this.failureReason = failureReason;
        }

        static Result success(List<CertificateInfo> chain) {
            return new Result(true, List.copyOf(chain), null);
        }

        static Result failure(String reason) {
            return new Result(false, List.of(), reason);
        }

        public boolean isOk() {
            return ok;
        }

        /** @return the certificate chain (leaf first), empty on failure. */
        public List<CertificateInfo> getChain() {
            return chain;
        }

        /** @return the failure reason, or null on success. */
        public String getFailureReason() {
            return failureReason;
        }
    }
}

