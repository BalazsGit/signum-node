package application.module.browser.core;

import application.module.browser.util.OsArch;
import application.utils.io.PathUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the directory of the JCEF native distribution (plan D2).
 * <p>
 * The distribution is a plain directory layout ({@code jcef/<os>-<arch>/: libcef,
 * CEF subprocess, locales, ...}), extracted from the pinned Maven artifact by
 * the {@code extractJcef} Gradle task. Candidate roots, in priority order:
 * <ol>
 *   <li>The application root ({@link PathUtils#getApplicationRoot()} — the directory
 *       containing the fat jar) and its parents. Climbing covers the jpackage
 *       app image, whose launcher loads the main jar from the nested {@code app/}
 *       directory while {@code jcef/} sits at the image root.</li>
 *   <li>The current working directory (development runs: {@code gradlew run} with
 *       the project root as CWD).</li>
 * </ol>
 * A candidate is accepted as soon as it contains {@code jcef/<os>-<arch>/}.
 * <p>
 * This class must stay free of {@code org.cef.*} imports: it is loaded from the
 * {@code Launcher} static block, before the native engine is made discoverable.
 */
public final class JcefPathResolver {

    /** Name of the dedicated distribution folder (plan D1). */
    public static final String JCEF_FOLDER = "jcef";

    /** How far above the application root to climb (jpackage {@code app/} layout). */
    private static final int MAX_PARENT_CLIMB = 2;

    private JcefPathResolver() {
        // utility class — never instantiated
    }

    /**
     * Resolves the JCEF distribution directory for the current platform.
     *
     * @return the distribution directory, or empty when it is not present (the
     *         caller must surface a clear error; typically: run {@code gradlew extractJcef})
     */
    public static Optional<Path> resolve() {
        Path appRoot = PathUtils.getApplicationRoot();
        Path workingDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return resolveAt(appRoot, workingDir, OsArch.current());
    }

    /**
     * Testable core of {@link #resolve()} with explicit inputs.
     *
     * @param appRoot    the application root (jar dir / CWD fallback), may be null
     * @param workingDir the current working directory
     * @param osArch     the target platform
     */
    static Optional<Path> resolveAt(Path appRoot, Path workingDir, OsArch osArch) {
        for (Path base : candidateRoots(appRoot, workingDir)) {
            Path candidate = base.resolve(JCEF_FOLDER).resolve(osArch.key()).normalize();
            if (Files.isDirectory(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static List<Path> candidateRoots(Path appRoot, Path workingDir) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        Path current = appRoot;
        for (int i = 0; i <= MAX_PARENT_CLIMB && current != null; i++) {
            roots.add(current);
            current = current.getParent();
        }
        roots.add(workingDir);
        return new ArrayList<>(roots);
    }
}
