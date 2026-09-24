package application.module.browser.engine.security;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Java-side TLS inspection (F2, S2) against a real embedded
 * HTTPS server: a valid (locally trusted) certificate and an expired one,
 * generated with BouncyCastle.
 */
class CertificateInspectorTest {

    private TestServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new TestServer(TestCert.valid());
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("a trusted certificate yields the chain with SAN entries")
    void validCertificate() throws Exception {
        CertificateInspector inspector = new CertificateInspector(server.trustingContext());
        CertificateInspector.Result result =
                inspector.inspect("https://127.0.0.1:" + server.port() + "/");

        assertTrue(result.isOk(), () -> "expected ok, got: " + result.getFailureReason());
        assertEquals(1, result.getChain().size());
        CertificateInspector.CertificateInfo leaf = result.getChain().get(0);
        assertEquals("CN=localhost", leaf.getSubject());
        assertEquals("CN=localhost", leaf.getIssuer()); // self-signed
        assertTrue(leaf.getSubjectAltNames().contains("localhost"));
        assertTrue(leaf.getSubjectAltNames().contains("127.0.0.1"));
        assertNotNull(leaf.getSignatureAlgorithm());
        assertFalse(leaf.getNotAfter().isBefore(leaf.getNotBefore()));
    }

    @Test
    @DisplayName("the result is cached per host (one handshake for two inspections)")
    void resultsAreCached() throws Exception {
        CertificateInspector inspector = new CertificateInspector(server.trustingContext());
        String url = "https://127.0.0.1:" + server.port() + "/";

        CertificateInspector.Result first = inspector.inspect(url);
        assertTrue(first.isOk());

        // Stop the server: a second inspection can only succeed from the cache.
        server.stop();
        CertificateInspector.Result second = inspector.inspect(url);

        assertTrue(second.isOk(),
                () -> "the second inspect must hit the cache, got: "
                        + second.getFailureReason());
        assertSame(first, second, "the cached result must be the identical instance");
    }

    @Test
    @DisplayName("an expired certificate fails with a readable reason")
    void expiredCertificateFails() throws Exception {
        try (TestServer expiredServer = new TestServer(TestCert.expired())) {
            CertificateInspector inspector =
                    new CertificateInspector(expiredServer.trustingContext());
            CertificateInspector.Result result =
                    inspector.inspect("https://127.0.0.1:" + expiredServer.port() + "/");

            assertFalse(result.isOk());
            assertNotNull(result.getFailureReason());
            assertTrue(result.getFailureReason().toLowerCase().contains("expired"),
                    () -> "expected an expired-cert reason, got: "
                            + result.getFailureReason());
        }
    }

    @Test
    @DisplayName("non-https and malformed URLs fail without throwing")
    void invalidUrlsFail() throws Exception {
        CertificateInspector inspector = new CertificateInspector(server.trustingContext());

        assertFalse(inspector.inspect("http://127.0.0.1:80/").isOk());
        assertFalse(inspector.inspect(null).isOk());
        assertFalse(inspector.inspect("https://").isOk());
        assertFalse(inspector.inspect("https://[bad-host:99999/").isOk());
    }

    // ------------------------------------------------------------------
    // Test fixtures
    // ------------------------------------------------------------------

    /** A self-signed certificate (valid or expired) for localhost. */
    private static final class TestCert {

        private final X509Certificate certificate;
        private final KeyPair keyPair;

        private TestCert(X509Certificate certificate, KeyPair keyPair) {
            this.certificate = certificate;
            this.keyPair = keyPair;
        }

        static TestCert valid() throws Exception {
            long now = System.currentTimeMillis();
            return generate(new Date(now - 60_000), new Date(now + 24L * 60 * 60 * 1000));
        }

        static TestCert expired() throws Exception {
            long now = System.currentTimeMillis();
            return generate(new Date(now - 48L * 60 * 60 * 1000),
                    new Date(now - 24L * 60 * 60 * 1000));
        }

        private static TestCert generate(Date notBefore, Date notAfter) throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            KeyPair keyPair = generator.generateKeyPair();
            X500Name subject = new X500Name("CN=localhost");
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    subject, BigInteger.valueOf(System.nanoTime()),
                    notBefore, notAfter, subject, keyPair.getPublic());
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(
                    new GeneralName[]{
                            new GeneralName(GeneralName.dNSName, "localhost"),
                            new GeneralName(GeneralName.iPAddress, "127.0.0.1")}));
            ContentSigner signer =
                    new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
            X509Certificate cert =
                    new JcaX509CertificateConverter().getCertificate(builder.build(signer));
            return new TestCert(cert, keyPair);
        }
    }

    /** An embedded HTTPS server with a test certificate (counts connections). */
    private static final class TestServer implements AutoCloseable {

        private final HttpsServer server;
        private final SSLContext serverContext;
        private final SSLContext trustContext;

        TestServer(TestCert cert) throws Exception {
            KeyStore serverStore = KeyStore.getInstance(KeyStore.getDefaultType());
            serverStore.load(null, null);
            serverStore.setKeyEntry("server", cert.keyPair.getPrivate(),
                    new char[0], new X509Certificate[]{cert.certificate});
            KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(serverStore, new char[0]);
            this.serverContext = SSLContext.getInstance("TLS");
            this.serverContext.init(kmf.getKeyManagers(), null, new SecureRandom());

            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("test", cert.certificate);
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            this.trustContext = SSLContext.getInstance("TLS");
            this.trustContext.init(null, tmf.getTrustManagers(), new SecureRandom());

            this.server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
            server.createContext("/", exchange -> {
                byte[] body = "ok".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        /** Stops the server (idempotent; @AfterEach stops it again). */
        void stop() {
            server.stop(0);
        }

        SSLContext trustingContext() {
            return trustContext;
        }

        @Override
        public void close() {
            stop();
        }
    }
}