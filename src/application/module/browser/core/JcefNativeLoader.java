package application.module.browser.core;

import application.module.browser.util.OsArch;
import org.cef.SystemBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Makes the JCEF native libraries loadable from the dedicated distribution
 * directory (plan D1/D2) — F0-verified design.
 * <p>
 * Upstream JCEF (CEF 146.0.10) loads its native code through
 * {@code System.loadLibrary(...)} (see {@code CefApp} ctor: {@code jawt},
 * {@code chrome_elf}, {@code libcef}, {@code jcef} on Windows), which resolves
 * against {@code java.library.path} — a list the JVM caches at startup, so it
 * cannot be extended at runtime. The supported mechanism is
 * {@link SystemBootstrap#setLoader(SystemBootstrap.Loader)}, which must be
 * installed before any {@code org.cef.*} class is used.
 * <p>
 * The loader resolves each library against the distribution directory via
 * {@code System.load(absolutePath)}; libraries that are not part of the
 * distribution (e.g. {@code jawt}, provided by the JRE) fall back to the
 * default {@code System.loadLibrary} resolution.
 * <p>
 * Library file names (distribution layout verified for Windows in F0):
 * <ul>
 *   <li>Windows: {@code chrome_elf.dll}, {@code libcef.dll}, {@code jcef.dll}</li>
 *   <li>Linux: {@code libcef.so} — note the upstream call is
 *       {@code loadLibrary("cef")} (expects {@code cef.so}), hence the alias</li>
 *   <li>macOS: {@code .dylib} counterparts (verify on the platform at first use)</li>
 * </ul>
 */
public final class JcefNativeLoader {

    private static final Logger logger = LoggerFactory.getLogger(JcefNativeLoader.class);

    private JcefNativeLoader() {
        // utility class — never instantiated
    }

    /**
     * Installs the JCEF library loader for the given distribution directory.
     * Must run before the first {@code org.cef.*} use (the engine does this in
     * its init step, before {@code CefApp} is touched).
     *
     * @param jcefDir the JCEF native distribution directory (must exist)
     * @param osArch  the target platform
     */
    public static void install(Path jcefDir, OsArch osArch) {
        String suffix = switch (osArch.os()) {
            case WINDOWS -> ".dll";
            case LINUX -> ".so";
            case MACOSX -> ".dylib";
        };
        // Upstream calls loadLibrary("cef") on Linux, but the distribution ships libcef.so.
        String cefAlias = osArch.os() == OsArch.Os.LINUX ? "libcef" : null;

        SystemBootstrap.setLoader(libname -> {
            String base = cefAlias != null && libname.equals("cef") ? cefAlias : libname;
            Path candidate = jcefDir.resolve(base + suffix);
            if (Files.isRegularFile(candidate)) {
                System.load(candidate.toAbsolutePath().toString());
                logger.debug("JCEF native library loaded: {}", candidate);
            } else {
                // Not part of the distribution (e.g. "jawt" from the JRE) — default resolution.
                System.loadLibrary(libname);
                logger.debug("JCEF native library loaded via default resolution: {}", libname);
            }
        });
    }
}
