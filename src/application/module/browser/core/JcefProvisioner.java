package application.module.browser.core;

import application.module.browser.util.OsArch;
import application.utils.io.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runtime JCEF provisioning: downloads the pinned {@code jcef-natives-<os>-<arch>}
 * Maven artifact, verifies its SHA-256 pin and installs the distribution into
 * {@code <app root>/jcef/<os>-<arch>/} (the exact layout {@code extractJcef}
 * produces, plan D1/D2).
 * <p>
 * <b>Trust chain:</b> HTTPS to Maven Central + the SHA-256 pin stamped from
 * {@code gradle.properties} at build time (resource {@code /jcef-release.properties} —
 * the single source of truth stays in the repo). A platform without a pin is
 * <em>unsupported</em>: {@link #isSupported()} is false and the caller keeps the
 * manual-install guidance.
 * <p>
 * <b>Extraction:</b> the natives jar embeds a single {@code *.tar.gz} whose root
 * <em>is</em> the distribution root (verified in F0). It is unpacked with the
 * system {@code tar} (bsdtar on Windows 10+/macOS, GNU tar on Linux) — the same
 * mechanism the {@code extractJcef} Gradle task uses (Gradle's untar cannot
 * parse the GNU-format archive).
 * <p>
 * <b>Thread contract:</b> {@link #provision} blocks (multi-minute download);
 * call it from a background thread and pump {@link ProgressListener} callbacks
 * to the EDT. This class must stay free of {@code org.cef.*} imports.
 */
public final class JcefProvisioner {

    private static final Logger logger = LoggerFactory.getLogger(JcefProvisioner.class);

    /** Maven Central base of the {@code me.friwi} JCEF group (package-private
     *  so tests can point the download at a local server). */
    static volatile String mavenBase = "https://repo1.maven.org/maven2/me/friwi/";

    /** The build-time-stamped release info (version + SHA-256 pins per platform). */
    private static final String RELEASE_RESOURCE = "/jcef-release.properties";

    private JcefProvisioner() {
        // utility class — never instantiated
    }

    /**
     * The phase of an in-flight provisioning (progress-bar semantics).
     */
    public enum Phase {
        DOWNLOADING,
        VERIFYING,
        EXTRACTING,
        INSTALLING
    }

    /**
     * Progress sink (called on the provisioning thread).
     *
     * @param phase    the current phase
     * @param fraction {@code 0..1} within the phase, or {@code -1} when indeterminate
     * @param done     bytes processed (download/verify only)
     * @param total    bytes expected (download only; 0 when unknown)
     */
    public interface ProgressListener {
        void onProgress(Phase phase, double fraction, long done, long total);
    }

    /**
     * A pinned release: the Maven version plus the SHA-256 of the natives jar
     * for one platform.
     */
    public record JcefRelease(String version, String osArchKey, String sha256) {

        /**
         * @return the release for the current platform, or empty when no SHA-256
         *         pin exists for it (auto-install unsupported there)
         */
        public static Optional<JcefRelease> forPlatform() {
            return forPlatform(OsArch.current());
        }

        /** Testable lookup with an explicit platform. */
        static Optional<JcefRelease> forPlatform(OsArch osArch) {
            try (InputStream in = JcefProvisioner.class.getResourceAsStream(RELEASE_RESOURCE)) {
                if (in == null) {
                    logger.warn("JCEF release info missing from the classpath ({})", RELEASE_RESOURCE);
                    return Optional.empty();
                }
                Properties props = new Properties();
                props.load(in);
                String version = props.getProperty("jcef.version", "").trim();
                String pin = props.getProperty("jcef.sha256." + osArch.key(), "").trim();
                if (version.isEmpty() || pin.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new JcefRelease(version, osArch.key(), pin));
            } catch (IOException e) {
                logger.warn("Could not read the JCEF release info", e);
                return Optional.empty();
            }
        }

        /** The Maven Central URL of the natives jar. */
        public URI mavenUrl() {
            String artifact = "jcef-natives-" + osArchKey;
            return URI.create(mavenBase + artifact + "/" + version + "/" + artifact + "-" + version + ".jar");
        }
    }

    /**
     * Test hook: redirects the download to a different base URL (e.g. a local
     * HTTP server serving a synthetic natives jar).
     */
    static void setMavenBase(String base) {
        mavenBase = base;
    }

    /**
     * @return true when the current platform has a pinned release (the
     *         auto-install dialog can be offered)
     */
    public static boolean isSupported() {
        return JcefRelease.forPlatform().isPresent();
    }

    /** @return the installation target: {@code <app root>/jcef/<os>-<arch>/}. */
    public static Path targetDir() {
        return PathUtils.getApplicationRoot()
                .resolve(JcefPathResolver.JCEF_FOLDER)
                .resolve(OsArch.current().key());
    }

    /**
     * Downloads, verifies and installs the JCEF distribution for the current
     * platform (blocking; background thread).
     *
     * @param listener progress sink (may be null)
     * @throws Exception on any failure (network, checksum, extraction, install);
     *                   a {@link ProvisionCancelledException} means the user aborted
     */
    public static void provision(ProgressListener listener) throws Exception {
        provision(JcefRelease.forPlatform().orElseThrow(
                        () -> new IllegalStateException("JCEF auto-install is not supported on "
                                + OsArch.current().key())),
                targetDir(), listener, new AtomicBoolean(false));
    }

    /**
     * Testable core: explicit release, target directory and cancellation flag.
     */
    static void provision(JcefRelease release, Path targetDir, ProgressListener listener,
                          AtomicBoolean cancelled) throws Exception {
        Path work = Files.createTempDirectory("jcef-provision-");
        try {
            Path jar = work.resolve("natives.jar");
            download(release, jar, listener, cancelled);
            verifySha256(jar, release.sha256(), listener);
            Path dist = extract(release, jar, work, listener, cancelled);
            install(dist, targetDir, listener);
        } finally {
            deleteRecursively(work);
        }
    }

    // ------------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------------

    private static void download(JcefRelease release, Path jar, ProgressListener listener,
                                 AtomicBoolean cancelled) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest request = HttpRequest.newBuilder(release.mavenUrl()).GET().build();
        logger.info("Downloading JCEF artifact: {}", release.mavenUrl());
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Download failed: HTTP " + response.statusCode() + " for "
                    + release.mavenUrl());
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(0L);
        long done = 0;
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(jar)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                if (cancelled.get()) {
                    throw new ProvisionCancelledException();
                }
                out.write(buffer, 0, n);
                done += n;
                if (listener != null) {
                    listener.onProgress(Phase.DOWNLOADING,
                            total > 0 ? (double) done / total : -1, done, total);
                }
            }
        }
        logger.info("JCEF artifact downloaded ({} bytes)", done);
    }

    private static void verifySha256(Path jar, String expected, ProgressListener listener) throws Exception {
        if (listener != null) {
            listener.onProgress(Phase.VERIFYING, 0, 0, 0);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(jar)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("SHA-256 mismatch: expected " + expected + " but got " + actual);
        }
        if (listener != null) {
            listener.onProgress(Phase.VERIFYING, 1, 1, 1);
        }
        logger.info("JCEF artifact SHA-256 verified");
    }

    /**
     * Unpacks the jar's embedded {@code *.tar.gz} with the system tar into a
     * fresh distribution directory and validates the expected layout.
     */
    private static Path extract(JcefRelease release, Path jar, Path work,
                                ProgressListener listener, AtomicBoolean cancelled) throws Exception {
        if (listener != null) {
            listener.onProgress(Phase.EXTRACTING, -1, 0, 0);
        }
        Path tarGz = work.resolve("distribution.tar.gz");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            ZipEntry entry = entries.hasMoreElements() ? entries.nextElement() : null;
            while (entry != null && !entry.getName().endsWith(".tar.gz")) {
                entry = entries.hasMoreElements() ? entries.nextElement() : null;
            }
            if (entry == null || entry.isDirectory()) {
                throw new IOException("No embedded *.tar.gz found in " + release.mavenUrl());
            }
            try (InputStream in = zip.getInputStream(entry);
                 OutputStream out = Files.newOutputStream(tarGz)) {
                in.transferTo(out);
            }
        }
        Path dist = work.resolve("dist");
        Files.createDirectories(dist);
        runTar(tarGz, dist, cancelled);
        validate(dist, release.osArchKey());
        return dist;
    }

    private static void runTar(Path tarGz, Path dist, AtomicBoolean cancelled) throws Exception {
        if (cancelled.get()) {
            throw new ProvisionCancelledException();
        }
        Process process = new ProcessBuilder("tar", "-xzf", tarGz.toString(), "-C", dist.toString())
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes());
        }
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("The system 'tar' did not finish (extraction aborted)");
        }
        if (process.exitValue() != 0) {
            throw new IOException("The system 'tar' failed (exit " + process.exitValue() + "): "
                    + output.strip());
        }
    }

    /**
     * The extracted tree must look like the distribution (F0-verified layout):
     * the platform's libcef library, the jcef.dll bridge and {@code locales/}.
     */
    static void validate(Path dist, String osArchKey) throws IOException {
        String libcef;
        if (osArchKey.startsWith("windows")) {
            libcef = "libcef.dll";
        } else if (osArchKey.startsWith("macosx")) {
            libcef = "libcef.dylib";
        } else {
            libcef = "libcef.so";
        }
        checkFile(dist.resolve(libcef));
        checkFile(dist.resolve("jcef.dll")); // JNI bridge, same name on every platform
        checkDirectory(dist.resolve("locales"));
    }

    private static void checkFile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("The extracted JCEF distribution is incomplete (missing "
                    + file.getFileName() + ")");
        }
    }

    private static void checkDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("The extracted JCEF distribution is incomplete (missing "
                    + dir.getFileName() + "/)");
        }
    }

    /**
     * Atomic install: removes any existing target, then moves the validated tree
     * into place (atomic rename where the filesystem allows it).
     */
    private static void install(Path dist, Path targetDir, ProgressListener listener) throws Exception {
        if (listener != null) {
            listener.onProgress(Phase.INSTALLING, -1, 0, 0);
        }
        Files.createDirectories(targetDir.getParent());
        if (Files.exists(targetDir)) {
            deleteRecursively(targetDir);
        }
        try {
            Files.move(dist, targetDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(dist, targetDir, StandardCopyOption.REPLACE_EXISTING);
        }
        if (listener != null) {
            listener.onProgress(Phase.INSTALLING, 1, 1, 1);
        }
        logger.info("JCEF distribution installed to {}", targetDir);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort; the next run starts from a fresh temp dir
                }
            });
        } catch (IOException e) {
            logger.warn("Could not fully remove {}", root, e);
        }
    }

    /**
     * Thrown (and caught by the GUI) when the user aborts a running provisioning.
     */
    public static final class ProvisionCancelledException extends IOException {
        public ProvisionCancelledException() {
            super("Provisioning cancelled by the user");
        }
    }
}