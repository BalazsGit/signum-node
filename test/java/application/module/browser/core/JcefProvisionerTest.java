package application.module.browser.core;

import application.module.browser.core.JcefProvisioner.JcefRelease;
import application.module.browser.core.JcefProvisioner.Phase;
import application.module.browser.core.JcefProvisioner.ProvisionCancelledException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for the runtime JCEF auto-install ({@link JcefProvisioner}).
 * <p>
 * A synthetic "natives jar" (a single embedded {@code *.tar.gz} with the
 * F0-verified distribution layout) is served from a local HTTP server, so the
 * full download → SHA-256 verify → tar extract → validate → install flow runs
 * without network access. Extraction uses the system {@code tar} (the same
 * mechanism as the {@code extractJcef} Gradle task — Windows 10+/macOS/Linux
 * all ship it).
 */
class JcefProvisionerTest {

    private static final String VERSION = "9.9-test";
    private static final String OS_KEY = "windows-amd64";
    private static final String DEFAULT_BASE = "https://repo1.maven.org/maven2/me/friwi/";

    private record PhaseEvent(Phase phase, double fraction, long done, long total) {
    }

    private final List<PhaseEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void resetMavenBase() {
        JcefProvisioner.setMavenBase(DEFAULT_BASE);
    }

    @AfterAll
    static void restoreMavenBase() {
        JcefProvisioner.setMavenBase(DEFAULT_BASE);
    }

    // ------------------------------------------------------------------
    // Full flow
    // ------------------------------------------------------------------

    @Test
    @DisplayName("provision downloads, verifies, extracts and installs the distribution")
    void fullFlowInstalls(@TempDir Path root) throws Exception {
        Path dist = root.resolve("dist-src");
        buildFakeDistribution(dist);
        Path jar = buildFakeNativesJar(root, dist);
        byte[] jarBytes = Files.readAllBytes(jar);
        String pin = sha256Hex(jar);

        try (Serving server = serve(jarBytes)) {
            JcefProvisioner.setMavenBase(server.baseUrl());
            JcefProvisioner.provision(
                    new JcefRelease(VERSION, OS_KEY, pin),
                    root.resolve("target/jcef/windows-amd64"),
                    this::record, new AtomicBoolean(false));
        }

        Path installed = root.resolve("target/jcef/windows-amd64");
        assertTrue(Files.isRegularFile(installed.resolve("libcef.dll")));
        assertTrue(Files.isRegularFile(installed.resolve("jcef.dll")));
        assertTrue(Files.isRegularFile(installed.resolve("locales/en-US.pak")));
        assertTrue(Files.isRegularFile(installed.resolve("build_meta.json")));
        assertFalse(Files.exists(root.resolve("target/jcef/dist")), "no temp leftovers at the target");

        // All four phases, in order.
        List<Phase> seen = new ArrayList<>();
        for (PhaseEvent e : events) {
            if (seen.isEmpty() || seen.get(seen.size() - 1) != e.phase()) {
                seen.add(e.phase());
            }
        }
        assertEquals(List.of(Phase.DOWNLOADING, Phase.VERIFYING, Phase.EXTRACTING, Phase.INSTALLING), seen);

        // The download phase reported the full transfer.
        PhaseEvent lastDownload = events.stream()
                .filter(e -> e.phase() == Phase.DOWNLOADING)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals(1.0, lastDownload.fraction(), 0.001);
        assertEquals(jarBytes.length, lastDownload.total());
        assertEquals(jarBytes.length, lastDownload.done());
    }
    // ------------------------------------------------------------------
    // Failure paths
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a checksum mismatch aborts the install")
    void checksumMismatchFails(@TempDir Path root) throws Exception {
        Path jar = buildFakeNativesJar(root, buildFakeDistribution(root.resolve("dist-src")));
        try (Serving server = serve(Files.readAllBytes(jar))) {
            JcefProvisioner.setMavenBase(server.baseUrl());
            IOException e = assertThrows(IOException.class, () ->
                    JcefProvisioner.provision(
                            new JcefRelease(VERSION, OS_KEY, "0".repeat(64)),
                            root.resolve("target"), this::record, new AtomicBoolean(false)));
            assertTrue(e.getMessage().contains("SHA-256 mismatch"), e.getMessage());
        }
        assertFalse(Files.exists(root.resolve("target")));
    }

    @Test
    @DisplayName("a pre-set cancellation aborts before anything is installed")
    void cancelledProvisionFails(@TempDir Path root) throws Exception {
        Path jar = buildFakeNativesJar(root, buildFakeDistribution(root.resolve("dist-src")));
        try (Serving server = serve(Files.readAllBytes(jar))) {
            JcefProvisioner.setMavenBase(server.baseUrl());
            AtomicBoolean cancelled = new AtomicBoolean(true);
            assertThrows(ProvisionCancelledException.class, () ->
                    JcefProvisioner.provision(
                            new JcefRelease(VERSION, OS_KEY, sha256Hex(jar)),
                            root.resolve("target"), this::record, cancelled));
        }
        assertFalse(Files.exists(root.resolve("target")));
    }

    @Test
    @DisplayName("a jar without an embedded tar.gz is rejected")
    void jarWithoutTarGzFails(@TempDir Path root) throws Exception {
        Path jar = root.resolve("broken.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("readme.txt"));
            out.write("no tar here".getBytes());
            out.closeEntry();
        }
        try (Serving server = serve(Files.readAllBytes(jar))) {
            JcefProvisioner.setMavenBase(server.baseUrl());
            IOException e = assertThrows(IOException.class, () ->
                    JcefProvisioner.provision(
                            new JcefRelease(VERSION, OS_KEY, sha256Hex(jar)),
                            root.resolve("target"), this::record, new AtomicBoolean(false)));
            assertTrue(e.getMessage().contains("No embedded *.tar.gz"), e.getMessage());
        }
    }

    @Test
    @DisplayName("an incomplete distribution layout is rejected by validation")
    void incompleteLayoutFails(@TempDir Path root) throws Exception {
        Path dist = buildFakeDistribution(root.resolve("dist-src"));
        Files.delete(dist.resolve("locales/en-US.pak"));
        Files.delete(dist.resolve("locales")); // empty now — no locales dir => not a distribution
        IOException e = assertThrows(IOException.class, () ->
                JcefProvisioner.validate(dist, OS_KEY));
        assertTrue(e.getMessage().contains("incomplete"), e.getMessage());
    }

    // ------------------------------------------------------------------
    // Release info
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the Maven URL follows the me.friwi coordinate layout")
    void mavenUrlLayout() {
        JcefRelease release = new JcefRelease("1.2.3", "windows-amd64", "x");
        assertEquals("https://repo1.maven.org/maven2/me/friwi/jcef-natives-windows-amd64/1.2.3"
                + "/jcef-natives-windows-amd64-1.2.3.jar", release.mavenUrl().toString());
    }

    @Test
    @DisplayName("the build-stamped release info matches gradle.properties")
    void stampedReleaseMatchesGradleProperties(@TempDir Path root) throws Exception {
        Path gradleProperties = Path.of("gradle.properties");
        assumeTrue(Files.isRegularFile(gradleProperties),
                "gradle.properties not reachable (test must run from the project root)");
        Properties props = new Properties();
        try (var in = Files.newInputStream(gradleProperties)) {
            props.load(in);
        }
        String version = props.getProperty("jcef.version", "").trim();
        String currentKey = application.module.browser.util.OsArch.current().key();
        String pin = props.getProperty("jcef.sha256." + currentKey, "").trim();
        assumeTrue(!version.isEmpty() && !pin.isEmpty(),
                "no SHA-256 pin for " + currentKey + " in gradle.properties");

        JcefRelease release = JcefRelease.forPlatform().orElseThrow();
        assertEquals(version, release.version(),
                "the build-stamped version must stay in sync with gradle.properties");
        assertEquals(currentKey, release.osArchKey());
        assertEquals(pin, release.sha256(),
                "the build-stamped SHA-256 pin must stay in sync with gradle.properties");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void record(Phase phase, double fraction, long done, long total) {
        events.add(new PhaseEvent(phase, fraction, done, total));
    }

    /** A minimal tree matching the F0-verified distribution layout (windows-amd64). */
    private Path buildFakeDistribution(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("locales"));
        Files.writeString(dir.resolve("libcef.dll"), "fake libcef");
        Files.writeString(dir.resolve("jcef.dll"), "fake jcef");
        Files.writeString(dir.resolve("jcef_helper.exe"), "fake helper");
        Files.writeString(dir.resolve("locales/en-US.pak"), "fake pak");
        Files.writeString(dir.resolve("build_meta.json"), "{\"release_tag\":\"test\"}");
        return dir;
    }

    /** The natives jar layout: a single embedded {@code *.tar.gz} (root = distribution root). */
    private Path buildFakeNativesJar(Path workDir, Path distDir) throws Exception {
        Path tarGz = workDir.resolve("jcef-natives-windows-amd64-" + VERSION + ".tar.gz");
        runSystemTar("tar", "-czf", tarGz.toString(), "-C", distDir.toString(), ".");
        Path jar = workDir.resolve("natives.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(tarGz.getFileName().toString()));
            Files.copy(tarGz, out);
            out.closeEntry();
        }
        return jar;
    }

    private static void runSystemTar(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("The system 'tar' failed (needed to build the test fixture): "
                    + output.strip());
        }
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Serves the given bytes under the Maven Central coordinate layout. */
    private record Serving(HttpServer server, String baseUrl) implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }

    private Serving serve(byte[] jarBytes) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/me/friwi/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, jarBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(jarBytes);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        return new Serving(server, "http://127.0.0.1:" + port + "/me/friwi/");
    }
}